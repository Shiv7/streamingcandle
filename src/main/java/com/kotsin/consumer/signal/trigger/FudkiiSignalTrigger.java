package com.kotsin.consumer.signal.trigger;

import com.kotsin.consumer.client.FastAnalyticsClient;
import com.kotsin.consumer.indicator.calculator.BBSuperTrendCalculator;
import com.kotsin.consumer.indicator.model.BBSuperTrend;
import com.kotsin.consumer.indicator.model.BBSuperTrend.*;
import com.kotsin.consumer.model.HistoricalCandle;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.model.TickCandle;
import com.kotsin.consumer.repository.TickCandleRepository;
import com.kotsin.consumer.service.CandleService;
import com.kotsin.consumer.service.RedisCacheService;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * FudkiiSignalTrigger - Handles Strategy 1: SuperTrend + Bollinger Band trigger.
 *
 * CORRECT APPROACH:
 * 1. On each 1m candle close, check if it's a 30m boundary (xx:15 or xx:45)
 * 2. If yes, aggregate last 30 1m candles into a new 30m candle
 * 3. Append to cached historical 30m candles
 * 4. Calculate BB(20,2) and SuperTrend(10,3) on combined data
 * 5. Evaluate trigger conditions
 *
 * TRIGGER CONDITIONS:
 * - BULLISH: SuperTrend flips from DOWN to UP AND close > BB_UPPER
 * - BEARISH: SuperTrend flips from UP to DOWN AND close < BB_LOWER
 *
 * NSE 30m CANDLE BOUNDARIES (IST):
 * 9:15-9:45, 9:45-10:15, 10:15-10:45, 10:45-11:15, 11:15-11:45, 11:45-12:15,
 * 12:15-12:45, 12:45-13:15, 13:15-13:45, 13:45-14:15, 14:15-14:45, 14:45-15:15, 15:15-15:30
 */
@Component
@Slf4j
public class FudkiiSignalTrigger {

    private static final String LOG_PREFIX = "[FUDKII-TRIGGER]";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter CANDLE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // NSE market timing
    private static final int MARKET_OPEN_HOUR = 9;
    private static final int MARKET_OPEN_MINUTE = 15;
    private static final int MARKET_CLOSE_HOUR = 15;
    private static final int MARKET_CLOSE_MINUTE = 30;

    @Autowired
    private BBSuperTrendCalculator bbstCalculator;

    @Autowired
    private CandleService candleService;

    @Autowired
    private TickCandleRepository tickCandleRepository;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private FastAnalyticsClient fastAnalyticsClient;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${fudkii.trigger.enabled:true}")
    private boolean enabled;

    @Value("${fudkii.trigger.kafka.topic:kotsin_FUDKII}")
    private String fudkiiKafkaTopic;

    @Value("${fudkii.trigger.timeframe:30m}")
    private String triggerTimeframe;

    @Value("${fudkii.trigger.bb.period:20}")
    private int bbPeriod;

    @Value("${fudkii.trigger.st.period:10}")
    private int stPeriod;

    @Value("${fudkii.trigger.require.both.conditions:true}")
    private boolean requireBothConditions;

    @Value("${fudkii.trigger.flip.debounce.minutes:10}")
    private int flipDebounceMinutes;

    @Value("${fudkii.trigger.log.near.misses:true}")
    private boolean logNearMisses;

    // Cache for historical 30m candles per symbol (already aggregated)
    private final Map<String, List<Candle30m>> historical30mCandles = new ConcurrentHashMap<>();

    // Cache for last BBST state per symbol
    private final Map<String, BBSuperTrend> lastBbstState = new ConcurrentHashMap<>();

    // Cache for last processed 30m window to avoid duplicate processing
    private final Map<String, Instant> lastProcessed30mWindow = new ConcurrentHashMap<>();

    // NEGATIVE CACHE: Track scripCodes that have already been attempted for API fetch
    // Prevents repeated API calls for scripCodes that fail (e.g., F&O symbols returning 503)
    private final Set<String> apiAttemptedScripCodes = ConcurrentHashMap.newKeySet();

    // Track scripCodes with insufficient data (no API available)
    private final Set<String> insufficientDataScripCodes = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public void init() {
        log.info("{} Initializing FUDKII trigger with CORRECT 30m boundary approach", LOG_PREFIX);
        log.info("{} BB Period: {}, ST Period: {}, Require Both Conditions: {}",
            LOG_PREFIX, bbPeriod, stPeriod, requireBothConditions);
    }

    /**
     * Called when a 1m candle closes. This is the main entry point.
     * Checks if we've reached a 30m boundary and evaluates trigger if so.
     */
    public FudkiiTriggerResult onCandleClose(String scripCode, TickCandle candle1m) {
        if (!enabled) {
            return FudkiiTriggerResult.noTrigger("FUDKII trigger disabled");
        }

        try {
            // Get candle time in IST
            Instant candleTime = candle1m.getWindowEnd();
            if (candleTime == null) {
                log.warn("{} {} windowEnd is NULL!", LOG_PREFIX, scripCode);
                return FudkiiTriggerResult.noTrigger("windowEnd is null");
            }

            ZonedDateTime istTime = candleTime.atZone(IST);
            int minute = istTime.getMinute();
            int hour = istTime.getHour();

            // Debug log for boundary detection
            if (minute == 15 || minute == 45 || minute == 14 || minute == 44) {
                log.info("{} {} Candle time: {} IST (hour={}, minute={})",
                    LOG_PREFIX, scripCode, istTime.format(TIME_FMT), hour, minute);
            }

            // Check if this is a 30m boundary (minute == 15 or minute == 45)
            boolean is30mBoundary = (minute == 15 || minute == 45);

            if (!is30mBoundary) {
                return FudkiiTriggerResult.noTrigger("Not a 30m boundary");
            }

            // Check market hours
            if (!isMarketHours(istTime)) {
                log.info("{} {} At 30m boundary but outside market hours: {} IST",
                    LOG_PREFIX, scripCode, istTime.format(TIME_FMT));
                return FudkiiTriggerResult.noTrigger("Outside market hours");
            }

            // Check if we already processed this 30m window
            Instant windowEnd = get30mWindowEnd(istTime);
            Instant lastProcessed = lastProcessed30mWindow.get(scripCode);
            if (lastProcessed != null && lastProcessed.equals(windowEnd)) {
                log.debug("{} {} Already processed 30m window ending at {}",
                    LOG_PREFIX, scripCode, istTime.format(TIME_FMT));
                return FudkiiTriggerResult.noTrigger("Already processed this 30m window");
            }

            log.info("{} ========== 30m BOUNDARY REACHED for {} at {} IST ==========",
                LOG_PREFIX, scripCode, istTime.format(TIME_FMT));

            // Calculate the 30m window boundaries
            Instant windowStart = get30mWindowStart(istTime);

            log.info("{} {} 30m Window: {} to {}",
                LOG_PREFIX, scripCode,
                windowStart.atZone(IST).format(CANDLE_TIME_FMT),
                windowEnd.atZone(IST).format(CANDLE_TIME_FMT));

            // Step 1: Fetch 1m candles for this 30m window from MongoDB
            List<TickCandle> candles1m = fetch1mCandlesForWindow(scripCode, windowStart, windowEnd);

            if (candles1m.isEmpty()) {
                log.warn("{} {} No 1m candles found for 30m window", LOG_PREFIX, scripCode);
                return FudkiiTriggerResult.noTrigger("No 1m candles for window");
            }

            log.info("{} {} Fetched {} 1m candles for aggregation", LOG_PREFIX, scripCode, candles1m.size());

            // Step 2: Aggregate 1m candles into a new 30m candle
            Candle30m newCandle30m = aggregate1mTo30m(scripCode, candles1m, windowStart, windowEnd);

            log.info("{} {} NEW 30m CANDLE: O={} H={} L={} C={} V={}",
                LOG_PREFIX, scripCode,
                String.format("%.2f", newCandle30m.open),
                String.format("%.2f", newCandle30m.high),
                String.format("%.2f", newCandle30m.low),
                String.format("%.2f", newCandle30m.close),
                newCandle30m.volume);

            // Step 3: Get historical 30m candles (from cache or build from DB)
            List<Candle30m> historicalCandles = getOrBuildHistorical30mCandles(scripCode, windowStart);

            log.info("{} {} Historical 30m candles: {} candles", LOG_PREFIX, scripCode, historicalCandles.size());

            // Log last 3 historical candles for debugging
            int histSize = historicalCandles.size();
            if (histSize > 0) {
                log.info("{} {} HISTORICAL CANDLES (last 3):", LOG_PREFIX, scripCode);
                for (int i = Math.max(0, histSize - 3); i < histSize; i++) {
                    Candle30m hc = historicalCandles.get(i);
                    log.info("{} {}   [{}] {} O={} H={} L={} C={}",
                        LOG_PREFIX, scripCode, i,
                        hc.windowStart.atZone(IST).format(CANDLE_TIME_FMT),
                        String.format("%.2f", hc.open),
                        String.format("%.2f", hc.high),
                        String.format("%.2f", hc.low),
                        String.format("%.2f", hc.close));
                }
            }

            // Step 4: Append new candle to historical
            List<Candle30m> allCandles = new ArrayList<>(historicalCandles);
            allCandles.add(newCandle30m);

            // Update cache with new historical data
            historical30mCandles.put(scripCode, allCandles);

            log.info("{} {} TOTAL 30m candles for calculation: {} (historical) + 1 (new) = {}",
                LOG_PREFIX, scripCode, historicalCandles.size(), allCandles.size());

            // Step 5: Check if we have enough candles for BB(20) calculation
            if (allCandles.size() < 21) {
                log.warn("{} {} Insufficient 30m candles: have {}, need 21",
                    LOG_PREFIX, scripCode, allCandles.size());
                lastProcessed30mWindow.put(scripCode, windowEnd);
                return FudkiiTriggerResult.noTrigger(
                    String.format("Insufficient 30m candles: got %d, need 21", allCandles.size()));
            }

            // Step 6: Prepare arrays for BB/ST calculation (oldest to newest)
            int n = allCandles.size();
            double[] closes = new double[n];
            double[] highs = new double[n];
            double[] lows = new double[n];

            for (int i = 0; i < n; i++) {
                Candle30m c = allCandles.get(i);
                closes[i] = c.close;
                highs[i] = c.high;
                lows[i] = c.low;
            }

            // Step 7: Calculate BB and SuperTrend
            BBSuperTrend bbst = bbstCalculator.calculate(scripCode, "30m", closes, highs, lows);

            // Detailed BB/ST logging
            log.info("{} {} ========== BB & SUPERTREND CALCULATION ==========", LOG_PREFIX, scripCode);
            log.info("{} {} Bollinger Bands (period={}, stdDev=2):", LOG_PREFIX, scripCode, bbPeriod);
            log.info("{} {}   BB_UPPER:  {}", LOG_PREFIX, scripCode, String.format("%.2f", bbst.getBbUpper()));
            log.info("{} {}   BB_MIDDLE: {}", LOG_PREFIX, scripCode, String.format("%.2f", bbst.getBbMiddle()));
            log.info("{} {}   BB_LOWER:  {}", LOG_PREFIX, scripCode, String.format("%.2f", bbst.getBbLower()));
            log.info("{} {} SuperTrend (period={}, multiplier=3):", LOG_PREFIX, scripCode, stPeriod);
            log.info("{} {}   ST_VALUE:  {}", LOG_PREFIX, scripCode, String.format("%.2f", bbst.getSuperTrend()));
            log.info("{} {}   ST_TREND:  {}", LOG_PREFIX, scripCode, bbst.getTrend());
            log.info("{} {}   TREND_CHANGED: {}", LOG_PREFIX, scripCode, bbst.isTrendChanged());
            log.info("{} {} Current Close: {}", LOG_PREFIX, scripCode, String.format("%.2f", newCandle30m.close));
            log.info("{} {} Price Position: {}", LOG_PREFIX, scripCode, bbst.getPricePosition());

            // Step 8: Evaluate trigger conditions
            FudkiiTriggerResult result = evaluateTrigger(scripCode, bbst, newCandle30m);

            // Step 9: Cache state and mark as processed
            lastBbstState.put(scripCode, bbst);
            redisCacheService.cacheBBSTState(scripCode, triggerTimeframe, bbst);
            lastProcessed30mWindow.put(scripCode, windowEnd);

            if (result.isTriggered()) {
                log.info("{} {} *** FUDKII SIGNAL TRIGGERED *** direction={}, reason={}",
                    LOG_PREFIX, scripCode, result.getDirection(), result.getReason());

                // Publish to Kafka topic
                publishToKafka(scripCode, result);
            }

            return result;

        } catch (Exception e) {
            log.error("{} {} Error in onCandleClose: {}", LOG_PREFIX, scripCode, e.getMessage(), e);
            return FudkiiTriggerResult.noTrigger("Error: " + e.getMessage());
        }
    }

    /**
     * Get 30m window start time based on IST time.
     * When at boundary (minute == 15 or 45), returns the START of the window that just CLOSED.
     * NSE 30m windows: 9:15-9:45, 9:45-10:15, 10:15-10:45, etc.
     *
     * At 12:15 -> window 11:45-12:15 just closed -> return 11:45
     * At 12:45 -> window 12:15-12:45 just closed -> return 12:15
     */
    private Instant get30mWindowStart(ZonedDateTime istTime) {
        int minute = istTime.getMinute();
        ZonedDateTime windowStart;

        if (minute == 15) {
            // At xx:15, the window that just closed is (xx-1):45 to xx:15
            windowStart = istTime.minusMinutes(30).withSecond(0).withNano(0);
        } else if (minute == 45) {
            // At xx:45, the window that just closed is xx:15 to xx:45
            windowStart = istTime.minusMinutes(30).withSecond(0).withNano(0);
        } else if (minute > 45) {
            // After xx:45, current window started at xx:45
            windowStart = istTime.withMinute(45).withSecond(0).withNano(0);
        } else if (minute > 15) {
            // After xx:15, current window started at xx:15
            windowStart = istTime.withMinute(15).withSecond(0).withNano(0);
        } else {
            // Before xx:15, current window started at (xx-1):45
            windowStart = istTime.minusHours(1).withMinute(45).withSecond(0).withNano(0);
        }

        return windowStart.toInstant();
    }

    /**
     * Get 30m window end time based on IST time.
     * When at boundary (minute == 15 or 45), returns that boundary time.
     */
    private Instant get30mWindowEnd(ZonedDateTime istTime) {
        int minute = istTime.getMinute();
        ZonedDateTime windowEnd;

        if (minute == 15 || minute == 45) {
            // At boundary, this is the end of the window that just closed
            windowEnd = istTime.withSecond(0).withNano(0);
        } else if (minute < 15) {
            windowEnd = istTime.withMinute(15).withSecond(0).withNano(0);
        } else if (minute < 45) {
            windowEnd = istTime.withMinute(45).withSecond(0).withNano(0);
        } else {
            windowEnd = istTime.plusHours(1).withMinute(15).withSecond(0).withNano(0);
        }

        return windowEnd.toInstant();
    }

    /**
     * Check if time is within market hours.
     */
    private boolean isMarketHours(ZonedDateTime istTime) {
        int hour = istTime.getHour();
        int minute = istTime.getMinute();

        // Before market open
        if (hour < MARKET_OPEN_HOUR || (hour == MARKET_OPEN_HOUR && minute < MARKET_OPEN_MINUTE)) {
            return false;
        }

        // After market close
        if (hour > MARKET_CLOSE_HOUR || (hour == MARKET_CLOSE_HOUR && minute > MARKET_CLOSE_MINUTE)) {
            return false;
        }

        return true;
    }

    /**
     * Fetch 1m candles from MongoDB for a specific 30m window.
     */
    private List<TickCandle> fetch1mCandlesForWindow(String scripCode, Instant windowStart, Instant windowEnd) {
        // Fetch candles where windowStart >= window start and windowStart < window end
        return tickCandleRepository.findByScripCodeAndWindowStartBetween(
            scripCode,
            windowStart,
            windowEnd
        );
    }

    /**
     * Aggregate 1m candles into a single 30m candle.
     */
    private Candle30m aggregate1mTo30m(String scripCode, List<TickCandle> candles1m,
                                        Instant windowStart, Instant windowEnd) {
        // Sort by time (oldest first)
        candles1m.sort(Comparator.comparing(TickCandle::getWindowStart));

        double open = candles1m.get(0).getOpen();
        double high = candles1m.stream().mapToDouble(TickCandle::getHigh).max().orElse(0);
        double low = candles1m.stream().mapToDouble(TickCandle::getLow).min().orElse(0);
        double close = candles1m.get(candles1m.size() - 1).getClose();
        long volume = candles1m.stream().mapToLong(TickCandle::getVolume).sum();

        return Candle30m.builder()
            .scripCode(scripCode)
            .windowStart(windowStart)
            .windowEnd(windowEnd)
            .open(open)
            .high(high)
            .low(low)
            .close(close)
            .volume(volume)
            .build();
    }

    /**
     * Get or build historical 30m candles for a symbol.
     * Priority: 1) Cache, 2) MongoDB, 3) 5paisa API (only once per scripCode)
     *
     * IMPORTANT: API is called ONLY ONCE per scripCode. Failed attempts are cached
     * to avoid repeated calls for the same scripCode.
     */
    private List<Candle30m> getOrBuildHistorical30mCandles(String scripCode, Instant beforeTime) {
        // Check if this scripCode is already marked as having insufficient data
        if (insufficientDataScripCodes.contains(scripCode)) {
            log.debug("{} {} Skipping - already marked as insufficient data", LOG_PREFIX, scripCode);
            return new ArrayList<>();
        }

        // Check cache first
        List<Candle30m> cached = historical30mCandles.get(scripCode);
        if (cached != null && cached.size() >= 21) {
            // Filter to only include candles before the current window
            List<Candle30m> filtered = cached.stream()
                .filter(c -> c.windowStart.isBefore(beforeTime))
                .collect(Collectors.toList());
            if (filtered.size() >= 20) {
                return filtered;
            }
        }

        // Try MongoDB first
        log.info("{} {} Trying to build historical 30m candles from MongoDB...", LOG_PREFIX, scripCode);
        Instant startDate = beforeTime.minus(Duration.ofDays(40));
        List<TickCandle> all1mCandles = tickCandleRepository.findByScripCodeAndWindowStartAfter(
            scripCode, startDate);

        List<Candle30m> historical = new ArrayList<>();

        if (!all1mCandles.isEmpty()) {
            log.info("{} {} Found {} 1m candles in MongoDB", LOG_PREFIX, scripCode, all1mCandles.size());
            historical = buildHistorical30mFromMongoDB(scripCode, all1mCandles, beforeTime);
        }

        // If MongoDB doesn't have enough data, fetch from 5paisa API
        // BUT ONLY IF WE HAVEN'T ALREADY TRIED FOR THIS SCRIPCODE
        if (historical.size() < 21) {
            if (apiAttemptedScripCodes.contains(scripCode)) {
                // Already tried API for this scripCode - don't retry
                log.debug("{} {} MongoDB has only {} 30m candles, API already attempted - skipping",
                    LOG_PREFIX, scripCode, historical.size());
            } else {
                // First time trying API for this scripCode
                log.info("{} {} MongoDB has only {} 30m candles, fetching from 5paisa API (first attempt)...",
                    LOG_PREFIX, scripCode, historical.size());

                // Mark as attempted BEFORE the call (even if it fails)
                apiAttemptedScripCodes.add(scripCode);

                List<Candle30m> apiCandles = fetchHistorical30mFromAPI(scripCode, beforeTime);

                if (!apiCandles.isEmpty()) {
                    historical = apiCandles;
                    log.info("{} {} Successfully fetched {} 30m candles from API",
                        LOG_PREFIX, scripCode, historical.size());
                } else {
                    log.warn("{} {} API returned no data - will not retry for this scripCode",
                        LOG_PREFIX, scripCode);
                }
            }
        }

        if (historical.isEmpty() || historical.size() < 21) {
            // Mark this scripCode as having insufficient data
            insufficientDataScripCodes.add(scripCode);
            log.warn("{} {} Insufficient historical data ({}), marked for skip",
                LOG_PREFIX, scripCode, historical.size());
            return new ArrayList<>();
        }

        // Sort by time and cache
        historical.sort(Comparator.comparing(c -> c.windowStart));
        historical30mCandles.put(scripCode, new ArrayList<>(historical));

        log.info("{} {} Cached {} historical 30m candles", LOG_PREFIX, scripCode, historical.size());
        return historical;
    }

    /**
     * Build historical 30m candles from 1m candles in MongoDB.
     */
    private List<Candle30m> buildHistorical30mFromMongoDB(String scripCode, List<TickCandle> all1mCandles, Instant beforeTime) {
        // Group 1m candles into 30m windows
        Map<Instant, List<TickCandle>> grouped = new TreeMap<>();

        for (TickCandle candle : all1mCandles) {
            Instant candleTime = candle.getWindowStart();
            ZonedDateTime istTime = candleTime.atZone(IST);

            // Skip if outside market hours
            if (!isMarketHours(istTime)) {
                continue;
            }

            // Calculate which 30m window this belongs to
            Instant windowStart = get30mWindowStartForCandle(istTime);

            // Only include windows before the current time
            if (windowStart.isBefore(beforeTime)) {
                grouped.computeIfAbsent(windowStart, k -> new ArrayList<>()).add(candle);
            }
        }

        // Aggregate each 30m window
        List<Candle30m> historical = new ArrayList<>();
        for (Map.Entry<Instant, List<TickCandle>> entry : grouped.entrySet()) {
            Instant windowStart = entry.getKey();
            List<TickCandle> windowCandles = entry.getValue();

            if (windowCandles.size() >= 20) { // Need at least 20 1m candles for a valid 30m candle
                Instant windowEnd = windowStart.plus(Duration.ofMinutes(30));
                Candle30m candle30m = aggregate1mTo30m(scripCode, windowCandles, windowStart, windowEnd);
                historical.add(candle30m);
            }
        }

        log.info("{} {} Built {} 30m candles from MongoDB", LOG_PREFIX, scripCode, historical.size());
        return historical;
    }

    /**
     * Fetch historical 30m candles directly from 5paisa API.
     */
    private List<Candle30m> fetchHistorical30mFromAPI(String scripCode, Instant beforeTime) {
        try {
            // Calculate date range (40 days back)
            ZonedDateTime endDate = beforeTime.atZone(IST);
            ZonedDateTime startDate = endDate.minusDays(40);

            String startDateStr = startDate.toLocalDate().toString();
            String endDateStr = endDate.toLocalDate().toString();

            log.info("{} {} Fetching 30m candles from API: {} to {}",
                LOG_PREFIX, scripCode, startDateStr, endDateStr);

            // Fetch 30m candles directly from API
            List<HistoricalCandle> apiCandles = fastAnalyticsClient.getHistoricalData(
                "N", "C", scripCode, startDateStr, endDateStr, "30m");

            if (apiCandles == null || apiCandles.isEmpty()) {
                log.warn("{} {} No 30m candles from API", LOG_PREFIX, scripCode);
                return new ArrayList<>();
            }

            log.info("{} {} Fetched {} 30m candles from API", LOG_PREFIX, scripCode, apiCandles.size());

            // Convert to Candle30m
            List<Candle30m> historical = new ArrayList<>();
            for (HistoricalCandle hc : apiCandles) {
                Instant ts = hc.getTimestampAsInstant();
                ZonedDateTime istTime = ts.atZone(IST);

                // Skip if outside market hours or after beforeTime
                if (!isMarketHours(istTime) || ts.isAfter(beforeTime)) {
                    continue;
                }

                Candle30m candle = Candle30m.builder()
                    .scripCode(scripCode)
                    .windowStart(ts)
                    .windowEnd(ts.plus(Duration.ofMinutes(30)))
                    .open(hc.getOpen())
                    .high(hc.getHigh())
                    .low(hc.getLow())
                    .close(hc.getClose())
                    .volume(hc.getVolume())
                    .build();
                historical.add(candle);
            }

            log.info("{} {} Converted {} valid 30m candles from API", LOG_PREFIX, scripCode, historical.size());
            return historical;

        } catch (Exception e) {
            log.error("{} {} Failed to fetch from API: {}", LOG_PREFIX, scripCode, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Get the 30m window start for a given candle time.
     */
    private Instant get30mWindowStartForCandle(ZonedDateTime istTime) {
        int minute = istTime.getMinute();
        ZonedDateTime windowStart;

        if (minute >= 45) {
            windowStart = istTime.withMinute(45).withSecond(0).withNano(0);
        } else if (minute >= 15) {
            windowStart = istTime.withMinute(15).withSecond(0).withNano(0);
        } else {
            // Belongs to previous hour's 45 minute window
            windowStart = istTime.minusHours(1).withMinute(45).withSecond(0).withNano(0);
        }

        return windowStart.toInstant();
    }

    /**
     * Evaluate trigger conditions.
     */
    private FudkiiTriggerResult evaluateTrigger(String scripCode, BBSuperTrend bbst, Candle30m currentCandle) {
        double close = currentCandle.close;

        // Get previous state for flip detection
        BBSuperTrend prevBbst = lastBbstState.get(scripCode);

        // Detect SuperTrend flip
        boolean superTrendFlipped = bbst.isTrendChanged();
        if (!superTrendFlipped && prevBbst != null) {
            superTrendFlipped = prevBbst.getTrend() != bbst.getTrend();
        }

        // Check debounced flip from Redis
        boolean usingDebouncedFlip = false;
        if (!superTrendFlipped) {
            String[] recentFlip = redisCacheService.getRecentSTFlip(scripCode, triggerTimeframe);
            if (recentFlip != null) {
                String flipDirection = recentFlip[0];
                if (("UP".equals(flipDirection) && bbst.getTrend() == TrendDirection.UP) ||
                    ("DOWN".equals(flipDirection) && bbst.getTrend() == TrendDirection.DOWN)) {
                    superTrendFlipped = true;
                    usingDebouncedFlip = true;
                    log.info("{} {} Using debounced ST flip: {}", LOG_PREFIX, scripCode, flipDirection);
                }
            }
        }

        // Record new flip for debouncing
        if (superTrendFlipped && !usingDebouncedFlip) {
            String flipDir = bbst.getTrend() == TrendDirection.UP ? "UP" : "DOWN";
            redisCacheService.recordSTFlip(scripCode, triggerTimeframe, flipDir, flipDebounceMinutes);
        }

        // Price position relative to BB
        PricePosition pricePos = bbst.getPricePosition();
        boolean aboveUpperBB = pricePos == PricePosition.ABOVE_UPPER;
        boolean belowLowerBB = pricePos == PricePosition.BELOW_LOWER;

        // Calculate score
        double triggerScore = 0;
        List<String> reasons = new ArrayList<>();

        if (superTrendFlipped) {
            triggerScore += 50;
            reasons.add("ST_FLIP(" + bbst.getTrend() + ")");
        }
        if (aboveUpperBB && bbst.getTrend() == TrendDirection.UP) {
            triggerScore += 50;
            reasons.add("ABOVE_BB_UPPER");
        }
        if (belowLowerBB && bbst.getTrend() == TrendDirection.DOWN) {
            triggerScore += 50;
            reasons.add("BELOW_BB_LOWER");
        }

        // Log evaluation
        log.info("{} {} ========== TRIGGER EVALUATION ==========", LOG_PREFIX, scripCode);
        log.info("{} {} ST_FLIPPED: {} (debounced: {})", LOG_PREFIX, scripCode, superTrendFlipped, usingDebouncedFlip);
        log.info("{} {} TREND: {}", LOG_PREFIX, scripCode, bbst.getTrend());
        log.info("{} {} PRICE_POSITION: {}", LOG_PREFIX, scripCode, pricePos);
        log.info("{} {} ABOVE_BB_UPPER: {} (close {} > BB_upper {})",
            LOG_PREFIX, scripCode, aboveUpperBB,
            String.format("%.2f", close), String.format("%.2f", bbst.getBbUpper()));
        log.info("{} {} BELOW_BB_LOWER: {} (close {} < BB_lower {})",
            LOG_PREFIX, scripCode, belowLowerBB,
            String.format("%.2f", close), String.format("%.2f", bbst.getBbLower()));
        log.info("{} {} TRIGGER_SCORE: {}", LOG_PREFIX, scripCode, triggerScore);
        log.info("{} {} REQUIRE_BOTH: {} (threshold: {})",
            LOG_PREFIX, scripCode, requireBothConditions, requireBothConditions ? 100 : 50);

        double threshold = requireBothConditions ? 100 : 50;

        // BULLISH TRIGGER
        if (bbst.getTrend() == TrendDirection.UP && triggerScore >= threshold) {
            redisCacheService.clearSTFlip(scripCode, triggerTimeframe);
            String reason = String.format("BULLISH: %s | close=%.2f > BB_upper=%.2f, ST=%.2f",
                String.join(" + ", reasons), close, bbst.getBbUpper(), bbst.getSuperTrend());

            return FudkiiTriggerResult.builder()
                .triggered(true)
                .direction(TriggerDirection.BULLISH)
                .reason(reason)
                .bbst(bbst)
                .triggerPrice(close)
                .triggerTime(Instant.now())
                .triggerScore(triggerScore)
                .build();
        }

        // BEARISH TRIGGER
        if (bbst.getTrend() == TrendDirection.DOWN && triggerScore >= threshold) {
            redisCacheService.clearSTFlip(scripCode, triggerTimeframe);
            String reason = String.format("BEARISH: %s | close=%.2f < BB_lower=%.2f, ST=%.2f",
                String.join(" + ", reasons), close, bbst.getBbLower(), bbst.getSuperTrend());

            return FudkiiTriggerResult.builder()
                .triggered(true)
                .direction(TriggerDirection.BEARISH)
                .reason(reason)
                .bbst(bbst)
                .triggerPrice(close)
                .triggerTime(Instant.now())
                .triggerScore(triggerScore)
                .build();
        }

        // Log near-miss
        if (logNearMisses) {
            logNearMiss(scripCode, bbst, close, superTrendFlipped, aboveUpperBB, belowLowerBB, triggerScore);
        }

        String noTriggerReason = buildNoTriggerReason(superTrendFlipped, bbst, close, aboveUpperBB, belowLowerBB);
        log.info("{} {} NO TRIGGER: {}", LOG_PREFIX, scripCode, noTriggerReason);

        return FudkiiTriggerResult.builder()
            .triggered(false)
            .direction(TriggerDirection.NONE)
            .reason(noTriggerReason)
            .bbst(bbst)
            .triggerPrice(close)
            .triggerTime(Instant.now())
            .triggerScore(triggerScore)
            .build();
    }

    /**
     * Build reason for no trigger.
     */
    private String buildNoTriggerReason(boolean stFlipped, BBSuperTrend bbst, double close,
                                         boolean aboveUpper, boolean belowLower) {
        if (!stFlipped) {
            return "SuperTrend did not flip";
        }
        if (bbst.getTrend() == TrendDirection.UP && !aboveUpper) {
            return String.format("ST UP but close (%.2f) not above BB_upper (%.2f)", close, bbst.getBbUpper());
        }
        if (bbst.getTrend() == TrendDirection.DOWN && !belowLower) {
            return String.format("ST DOWN but close (%.2f) not below BB_lower (%.2f)", close, bbst.getBbLower());
        }
        return "Conditions not met";
    }

    /**
     * Log near-miss scenarios.
     */
    private void logNearMiss(String scripCode, BBSuperTrend bbst, double close,
                              boolean stFlipped, boolean aboveUpper, boolean belowLower, double score) {
        double gapToUpper = (bbst.getBbUpper() - close) / close * 100;
        double gapToLower = (close - bbst.getBbLower()) / close * 100;

        if (stFlipped && bbst.getTrend() == TrendDirection.UP && !aboveUpper && gapToUpper < 1.0) {
            log.warn("{} {} NEAR-MISS BULLISH: ST flipped UP, price {}% below BB_upper",
                LOG_PREFIX, scripCode, String.format("%.2f", gapToUpper));
        }
        if (stFlipped && bbst.getTrend() == TrendDirection.DOWN && !belowLower && gapToLower < 1.0) {
            log.warn("{} {} NEAR-MISS BEARISH: ST flipped DOWN, price {}% above BB_lower",
                LOG_PREFIX, scripCode, String.format("%.2f", gapToLower));
        }
        if (!stFlipped && aboveUpper) {
            log.warn("{} {} NEAR-MISS: Price above BB_upper but ST did not flip", LOG_PREFIX, scripCode);
        }
        if (!stFlipped && belowLower) {
            log.warn("{} {} NEAR-MISS: Price below BB_lower but ST did not flip", LOG_PREFIX, scripCode);
        }
    }

    /**
     * Force check trigger - for backward compatibility with SignalEngine.
     * This should ideally not be used; use onCandleClose instead.
     */
    public FudkiiTriggerResult forceCheckTrigger(String scripCode) {
        log.debug("{} {} forceCheckTrigger called - checking if at 30m boundary", LOG_PREFIX, scripCode);

        // Get the latest 1m candle first
        List<TickCandle> recentCandles = tickCandleRepository.findByScripCodeOrderByWindowStartDesc(scripCode);
        if (recentCandles.isEmpty()) {
            return FudkiiTriggerResult.noTrigger("No candles found");
        }

        TickCandle latestCandle = recentCandles.get(0);

        // Use candle's timestamp (event time) instead of wall-clock time for 30m boundary check
        Instant candleTime = latestCandle.getWindowStart() != null ? latestCandle.getWindowStart() : latestCandle.getTimestamp();
        ZonedDateTime candleZdt = candleTime.atZone(IST);
        int minute = candleZdt.getMinute();

        if (minute != 15 && minute != 45) {
            return FudkiiTriggerResult.noTrigger("Not at 30m boundary (minute=" + minute + ")");
        }

        return onCandleClose(scripCode, latestCandle);
    }

    /**
     * Check if current time is a valid trigger time - for backward compatibility.
     */
    public boolean isValidTriggerTime() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        int minute = now.getMinute();
        return (minute == 15 || minute == 45) && isMarketHours(now);
    }

    /**
     * Get BBST state for a symbol.
     */
    public Optional<BBSuperTrend> getBbstState(String scripCode) {
        return Optional.ofNullable(lastBbstState.get(scripCode));
    }

    /**
     * Publish FUDKII trigger result to Kafka.
     */
    private void publishToKafka(String scripCode, FudkiiTriggerResult result) {
        try {
            // Build a serializable DTO for Kafka
            Map<String, Object> payload = new HashMap<>();
            payload.put("scripCode", scripCode);
            payload.put("triggered", result.isTriggered());
            payload.put("direction", result.getDirection() != null ? result.getDirection().name() : null);
            payload.put("reason", result.getReason());
            payload.put("triggerPrice", result.getTriggerPrice());
            payload.put("triggerTime", result.getTriggerTime() != null ? result.getTriggerTime().toString() : null);
            payload.put("triggerScore", result.getTriggerScore());

            // Add BBST details if available
            if (result.getBbst() != null) {
                BBSuperTrend bbst = result.getBbst();
                payload.put("bbUpper", bbst.getBbUpper());
                payload.put("bbMiddle", bbst.getBbMiddle());
                payload.put("bbLower", bbst.getBbLower());
                payload.put("superTrend", bbst.getSuperTrend());
                payload.put("trend", bbst.getTrend() != null ? bbst.getTrend().name() : null);
                payload.put("trendChanged", bbst.isTrendChanged());
                payload.put("pricePosition", bbst.getPricePosition() != null ? bbst.getPricePosition().name() : null);
            }

            kafkaTemplate.send(fudkiiKafkaTopic, scripCode, payload);
            log.info("{} {} Published FUDKII trigger to Kafka topic: {}", LOG_PREFIX, scripCode, fudkiiKafkaTopic);

        } catch (Exception e) {
            log.error("{} {} Failed to publish to Kafka: {}", LOG_PREFIX, scripCode, e.getMessage());
        }
    }

    // ==================== INNER CLASSES ====================

    @Data
    @Builder
    public static class Candle30m {
        private String scripCode;
        private Instant windowStart;
        private Instant windowEnd;
        private double open;
        private double high;
        private double low;
        private double close;
        private long volume;
    }

    @Data
    @Builder
    public static class FudkiiTriggerResult {
        private boolean triggered;
        private TriggerDirection direction;
        private String reason;
        private BBSuperTrend bbst;
        private double triggerPrice;
        private Instant triggerTime;
        private UnifiedCandle latestCandle;
        private double triggerScore;

        public static FudkiiTriggerResult noTrigger(String reason) {
            return FudkiiTriggerResult.builder()
                .triggered(false)
                .direction(TriggerDirection.NONE)
                .reason(reason)
                .triggerScore(0)
                .build();
        }

        public boolean isBullish() {
            return direction == TriggerDirection.BULLISH;
        }

        public boolean isBearish() {
            return direction == TriggerDirection.BEARISH;
        }
    }

    public enum TriggerDirection {
        BULLISH, BEARISH, NONE
    }
}
