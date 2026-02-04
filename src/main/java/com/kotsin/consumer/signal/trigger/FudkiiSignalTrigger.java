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
import com.kotsin.consumer.service.ScripMetadataService;
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
 * 1. On each 1m candle close, check if it's a 30m boundary (exchange-aware)
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
 *
 * MCX 30m CANDLE BOUNDARIES (IST):
 * 9:00-9:30, 9:30-10:00, 10:00-10:30, ... 22:30-23:00, 23:00-23:30
 *
 * FIX (2026-02-02):
 * - Added MCX support with exchange-aware market hours and boundaries
 * - Added historical data merging from API + MongoDB
 * - Pass window start time to calculator for state persistence
 */
@Component
@Slf4j
public class FudkiiSignalTrigger {

    private static final String LOG_PREFIX = "[FUDKII-TRIGGER]";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter CANDLE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // NSE market timing
    private static final int NSE_MARKET_OPEN_HOUR = 9;
    private static final int NSE_MARKET_OPEN_MINUTE = 15;
    private static final int NSE_MARKET_CLOSE_HOUR = 15;
    private static final int NSE_MARKET_CLOSE_MINUTE = 30;

    // MCX market timing
    private static final int MCX_MARKET_OPEN_HOUR = 9;
    private static final int MCX_MARKET_OPEN_MINUTE = 0;
    private static final int MCX_MARKET_CLOSE_HOUR = 23;
    private static final int MCX_MARKET_CLOSE_MINUTE = 30;

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

    @Autowired
    private ScripMetadataService scripMetadataService;

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

    @Value("${fudkii.trigger.bootstrap.days-back:5}")
    private int bootstrapDaysBack;

    @Value("${fudkii.trigger.mcx.enabled:true}")
    private boolean mcxEnabled;

    // Cache for historical 30m candles per symbol (already aggregated)
    // Limited to MAX_CACHED_CANDLES per symbol to prevent unbounded memory growth
    // FIX: Use CopyOnWriteArrayList for thread safety (ConcurrentHashMap only protects the map, not the list)
    private final Map<String, java.util.concurrent.CopyOnWriteArrayList<Candle30m>> historical30mCandles = new ConcurrentHashMap<>();
    private static final int MAX_CACHED_CANDLES = 100; // ~50 trading hours worth of 30m candles

    // Cache for last BBST state per symbol
    private final Map<String, BBSuperTrend> lastBbstState = new ConcurrentHashMap<>();

    // Cache for last processed 30m window to avoid duplicate processing
    private final Map<String, Instant> lastProcessed30mWindow = new ConcurrentHashMap<>();

    // NEGATIVE CACHE: Track scripCodes that have already been attempted for API fetch
    // Prevents repeated API calls for scripCodes that fail (e.g., F&O symbols returning 503)
    private final Set<String> apiAttemptedScripCodes = ConcurrentHashMap.newKeySet();

    // Track scripCodes with insufficient data (no API available) - with expiry timestamp
    // Key: scripCode, Value: timestamp when added (for expiry check)
    private final Map<String, Instant> insufficientDataScripCodes = new ConcurrentHashMap<>();

    // Negative cache TTL: retry after 4 hours
    private static final Duration INSUFFICIENT_DATA_TTL = Duration.ofHours(4);

    @PostConstruct
    public void init() {
        log.info("{} Initializing FUDKII trigger with exchange-aware 30m boundary approach", LOG_PREFIX);
        log.info("{} BB Period: {}, ST Period: {}, Require Both Conditions: {}",
            LOG_PREFIX, bbPeriod, stPeriod, requireBothConditions);
        log.info("{} MCX Support: {}, Bootstrap Days Back: {}",
            LOG_PREFIX, mcxEnabled, bootstrapDaysBack);
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
            // Determine exchange from candle
            String exchange = candle1m.getExchange();
            if (exchange == null || exchange.isEmpty()) {
                // FIX: Log when exchange is missing - this could indicate a data pipeline issue
                log.debug("{} {} Exchange is null/empty, defaulting to NSE. " +
                    "This may cause incorrect boundary detection for MCX symbols.", LOG_PREFIX, scripCode);
                exchange = "N"; // Default to NSE
            }

            // Check if MCX is enabled
            if ("M".equalsIgnoreCase(exchange) && !mcxEnabled) {
                return FudkiiTriggerResult.noTrigger("MCX FUDKII trigger disabled");
            }

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
            if (is30mBoundary(minute, exchange) || isNearBoundary(minute, exchange)) {
                log.info("{} {} [{}] Candle time: {} IST (hour={}, minute={})",
                    LOG_PREFIX, scripCode, exchange, istTime.format(TIME_FMT), hour, minute);
            }

            // Check if this is a 30m boundary based on exchange
            if (!is30mBoundary(minute, exchange)) {
                return FudkiiTriggerResult.noTrigger("Not a 30m boundary");
            }

            // Check market hours based on exchange
            if (!isMarketHours(istTime, exchange)) {
                log.info("{} {} [{}] At 30m boundary but outside market hours: {} IST",
                    LOG_PREFIX, scripCode, exchange, istTime.format(TIME_FMT));
                return FudkiiTriggerResult.noTrigger("Outside market hours");
            }

            // Check if we already processed this 30m window
            Instant windowEnd = get30mWindowEnd(istTime, exchange);
            Instant lastProcessed = lastProcessed30mWindow.get(scripCode);
            if (lastProcessed != null && lastProcessed.equals(windowEnd)) {
                log.debug("{} {} Already processed 30m window ending at {}",
                    LOG_PREFIX, scripCode, istTime.format(TIME_FMT));
                return FudkiiTriggerResult.noTrigger("Already processed this 30m window");
            }

            log.info("{} ========== 30m BOUNDARY REACHED for {} [{}] at {} IST ==========",
                LOG_PREFIX, scripCode, exchange, istTime.format(TIME_FMT));

            // Calculate the 30m window boundaries
            Instant windowStart = get30mWindowStart(istTime, exchange);

            log.info("{} {} [{}] 30m Window: {} to {}",
                LOG_PREFIX, scripCode, exchange,
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

            // Step 3: Get historical 30m candles (from cache or build from DB + API)
            // FIX: Pass exchangeType to ensure correct API segment (C=Cash, D=Derivative)
            String exchangeType = candle1m.getExchangeType();
            if (exchangeType == null || exchangeType.isEmpty()) {
                // Default based on instrument type detection
                exchangeType = candle1m.isDerivative() ? "D" : "C";
                log.debug("{} {} exchangeType was null, derived from instrumentType: {}",
                    LOG_PREFIX, scripCode, exchangeType);
            }
            List<Candle30m> historicalCandles = getOrBuildHistorical30mCandles(scripCode, exchange, exchangeType, windowStart);

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

            // Update cache with new historical data (with size limit)
            if (allCandles.size() > MAX_CACHED_CANDLES) {
                allCandles = new ArrayList<>(allCandles.subList(allCandles.size() - MAX_CACHED_CANDLES, allCandles.size()));
            }
            // FIX: Use CopyOnWriteArrayList for thread safety
            historical30mCandles.put(scripCode, new java.util.concurrent.CopyOnWriteArrayList<>(allCandles));

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
            Instant[] windowStarts = new Instant[n];  // For intelligent state matching

            for (int i = 0; i < n; i++) {
                Candle30m c = allCandles.get(i);
                closes[i] = c.close;
                highs[i] = c.high;
                lows[i] = c.low;
                windowStarts[i] = c.windowStart;  // Pass timestamps for state context validation
            }

            // Step 7: Calculate BB and SuperTrend (pass window timestamps for intelligent state handling)
            BBSuperTrend bbst = bbstCalculator.calculate(scripCode, "30m", closes, highs, lows, windowStarts, windowStart);

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
     * Check if minute is a 30m boundary for the given exchange.
     * NSE: xx:15 and xx:45
     * MCX: xx:00 and xx:30
     */
    private boolean is30mBoundary(int minute, String exchange) {
        if ("M".equalsIgnoreCase(exchange)) {
            // MCX: boundaries at :00 and :30
            return minute == 0 || minute == 30;
        } else {
            // NSE (default): boundaries at :15 and :45
            return minute == 15 || minute == 45;
        }
    }

    /**
     * Check if minute is near a 30m boundary (for debug logging).
     */
    private boolean isNearBoundary(int minute, String exchange) {
        if ("M".equalsIgnoreCase(exchange)) {
            return minute == 59 || minute == 29;
        } else {
            return minute == 14 || minute == 44;
        }
    }

    /**
     * Get 30m window start time based on IST time and exchange.
     * When at boundary, returns the START of the window that just CLOSED.
     */
    private Instant get30mWindowStart(ZonedDateTime istTime, String exchange) {
        int minute = istTime.getMinute();
        ZonedDateTime windowStart;

        if ("M".equalsIgnoreCase(exchange)) {
            // MCX: windows at :00 and :30
            if (minute == 0) {
                // At xx:00, window that closed is (xx-1):30 to xx:00
                windowStart = istTime.minusMinutes(30).withSecond(0).withNano(0);
            } else if (minute == 30) {
                // At xx:30, window that closed is xx:00 to xx:30
                windowStart = istTime.minusMinutes(30).withSecond(0).withNano(0);
            } else if (minute > 30) {
                // Current window started at xx:30
                windowStart = istTime.withMinute(30).withSecond(0).withNano(0);
            } else {
                // Current window started at xx:00
                windowStart = istTime.withMinute(0).withSecond(0).withNano(0);
            }
        } else {
            // NSE: windows at :15 and :45
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
        }

        return windowStart.toInstant();
    }

    /**
     * Get 30m window end time based on IST time and exchange.
     * When at boundary, returns that boundary time.
     */
    private Instant get30mWindowEnd(ZonedDateTime istTime, String exchange) {
        int minute = istTime.getMinute();
        ZonedDateTime windowEnd;

        if ("M".equalsIgnoreCase(exchange)) {
            // MCX: boundaries at :00 and :30
            if (minute == 0 || minute == 30) {
                windowEnd = istTime.withSecond(0).withNano(0);
            } else if (minute < 30) {
                windowEnd = istTime.withMinute(30).withSecond(0).withNano(0);
            } else {
                windowEnd = istTime.plusHours(1).withMinute(0).withSecond(0).withNano(0);
            }
        } else {
            // NSE: boundaries at :15 and :45
            if (minute == 15 || minute == 45) {
                windowEnd = istTime.withSecond(0).withNano(0);
            } else if (minute < 15) {
                windowEnd = istTime.withMinute(15).withSecond(0).withNano(0);
            } else if (minute < 45) {
                windowEnd = istTime.withMinute(45).withSecond(0).withNano(0);
            } else {
                windowEnd = istTime.plusHours(1).withMinute(15).withSecond(0).withNano(0);
            }
        }

        return windowEnd.toInstant();
    }

    /**
     * Check if time is within market hours for the given exchange.
     */
    private boolean isMarketHours(ZonedDateTime istTime, String exchange) {
        int hour = istTime.getHour();
        int minute = istTime.getMinute();

        if ("M".equalsIgnoreCase(exchange)) {
            // MCX: 9:00 AM - 11:30 PM
            if (hour < MCX_MARKET_OPEN_HOUR) return false;
            if (hour == MCX_MARKET_OPEN_HOUR && minute < MCX_MARKET_OPEN_MINUTE) return false;
            if (hour > MCX_MARKET_CLOSE_HOUR) return false;
            if (hour == MCX_MARKET_CLOSE_HOUR && minute > MCX_MARKET_CLOSE_MINUTE) return false;
            return true;
        } else {
            // NSE (default): 9:15 AM - 3:30 PM
            if (hour < NSE_MARKET_OPEN_HOUR) return false;
            if (hour == NSE_MARKET_OPEN_HOUR && minute < NSE_MARKET_OPEN_MINUTE) return false;
            if (hour > NSE_MARKET_CLOSE_HOUR) return false;
            if (hour == NSE_MARKET_CLOSE_HOUR && minute > NSE_MARKET_CLOSE_MINUTE) return false;
            return true;
        }
    }

    /**
     * Check if this is the first 30m candle of the trading day.
     * NSE: First candle starts at 09:15 (windowStart)
     * MCX: First candle starts at 09:00 (windowStart)
     *
     * FIX: Used to skip heuristic flip detection on first candle where
     * there's no intraday context to validate the flip.
     */
    private boolean isFirstCandleOfDay(Instant windowStart, String exchange) {
        if (windowStart == null || exchange == null) {
            return false;
        }
        ZonedDateTime zdt = windowStart.atZone(IST);
        int hour = zdt.getHour();
        int minute = zdt.getMinute();

        if ("M".equalsIgnoreCase(exchange)) {
            // MCX: First candle is 09:00-09:30, windowStart = 09:00
            return hour == MCX_MARKET_OPEN_HOUR && minute == MCX_MARKET_OPEN_MINUTE;
        } else {
            // NSE: First candle is 09:15-09:45, windowStart = 09:15
            return hour == NSE_MARKET_OPEN_HOUR && minute == NSE_MARKET_OPEN_MINUTE;
        }
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

        // Get exchange from first candle
        String exchange = candles1m.get(0).getExchange();

        // Get symbol and company name from ScripMetadataService
        String symbol = scripMetadataService.getSymbolRoot(scripCode, candles1m.get(0).getCompanyName());
        String companyName = scripMetadataService.getCompanyName(scripCode);
        if (companyName == null) {
            companyName = candles1m.get(0).getCompanyName();
        }

        return Candle30m.builder()
            .scripCode(scripCode)
            .symbol(symbol)
            .companyName(companyName)
            .exchange(exchange)
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
     * Priority: 1) Cache, 2) MongoDB + API merge
     *
     * FIX: Now merges MongoDB and API data to ensure complete history.
     */
    private List<Candle30m> getOrBuildHistorical30mCandles(String scripCode, String exchange, String exchangeType, Instant beforeTime) {
        // Check if this scripCode is already marked as having insufficient data (with TTL check)
        Instant markedAt = insufficientDataScripCodes.get(scripCode);
        if (markedAt != null) {
            if (Instant.now().isBefore(markedAt.plus(INSUFFICIENT_DATA_TTL))) {
                log.debug("{} {} Skipping - marked as insufficient data (will retry after TTL)", LOG_PREFIX, scripCode);
                return new ArrayList<>();
            } else {
                // TTL expired, remove from cache and retry
                insufficientDataScripCodes.remove(scripCode);
                apiAttemptedScripCodes.remove(scripCode); // Also allow API retry
                log.info("{} {} TTL expired for insufficient data cache, retrying...", LOG_PREFIX, scripCode);
            }
        }

        // Check cache first (thread-safe access to CopyOnWriteArrayList)
        java.util.concurrent.CopyOnWriteArrayList<Candle30m> cached = historical30mCandles.get(scripCode);
        if (cached != null && cached.size() >= 21) {
            // Filter to only include candles before the current window
            // CopyOnWriteArrayList is safe for iteration without explicit synchronization
            List<Candle30m> filtered = cached.stream()
                .filter(c -> c.windowStart.isBefore(beforeTime))
                .collect(Collectors.toList());
            if (filtered.size() >= 20) {
                return filtered;
            }
        }

        // Build historical data from MongoDB first
        log.info("{} {} Building historical 30m candles from MongoDB...", LOG_PREFIX, scripCode);
        Instant startDate = beforeTime.minus(Duration.ofDays(bootstrapDaysBack));
        List<TickCandle> all1mCandles = tickCandleRepository.findByScripCodeAndWindowStartAfter(
            scripCode, startDate);

        List<Candle30m> mongoCandles = new ArrayList<>();

        if (!all1mCandles.isEmpty()) {
            log.info("{} {} Found {} 1m candles in MongoDB", LOG_PREFIX, scripCode, all1mCandles.size());
            mongoCandles = buildHistorical30mFromMongoDB(scripCode, exchange, all1mCandles, beforeTime);
        }

        // If MongoDB doesn't have enough data for proper ATR warmup, fetch from API
        // FIX: Increased threshold from 21 to 50 for accurate SuperTrend calculation
        int minCandlesForAccurateCalc = 50;
        if (mongoCandles.size() < minCandlesForAccurateCalc && !apiAttemptedScripCodes.contains(scripCode)) {
            log.info("{} {} MongoDB has only {} 30m candles (need {} for accurate calculation), fetching from API...",
                LOG_PREFIX, scripCode, mongoCandles.size(), minCandlesForAccurateCalc);

            apiAttemptedScripCodes.add(scripCode);

            List<Candle30m> apiCandles = fetchHistorical30mFromAPI(scripCode, exchange, exchangeType, beforeTime);

            if (!apiCandles.isEmpty()) {
                // Merge: use TreeMap to deduplicate by windowStart, prefer MongoDB for overlaps
                Map<Instant, Candle30m> merged = new TreeMap<>();

                // Add API candles first (older/backup data)
                for (Candle30m c : apiCandles) {
                    merged.put(c.getWindowStart(), c);
                }

                // Override with MongoDB candles (more accurate live data)
                for (Candle30m c : mongoCandles) {
                    merged.put(c.getWindowStart(), c);
                }

                mongoCandles = new ArrayList<>(merged.values());
                log.info("{} {} Merged to {} 30m candles (API + MongoDB)",
                    LOG_PREFIX, scripCode, mongoCandles.size());
            } else {
                log.warn("{} {} API returned no data", LOG_PREFIX, scripCode);
            }
        }

        if (mongoCandles.isEmpty() || mongoCandles.size() < 21) {
            // Mark this scripCode as having insufficient data (with timestamp for TTL)
            insufficientDataScripCodes.put(scripCode, Instant.now());
            log.warn("{} {} Insufficient historical data ({}), marked for skip (TTL: {})",
                LOG_PREFIX, scripCode, mongoCandles.size(), INSUFFICIENT_DATA_TTL);
            return new ArrayList<>();
        }

        // Sort by time and cache (with size limit)
        mongoCandles.sort(Comparator.comparing(c -> c.windowStart));
        if (mongoCandles.size() > MAX_CACHED_CANDLES) {
            mongoCandles = new ArrayList<>(mongoCandles.subList(mongoCandles.size() - MAX_CACHED_CANDLES, mongoCandles.size()));
        }
        // FIX: Use CopyOnWriteArrayList for thread safety
        historical30mCandles.put(scripCode, new java.util.concurrent.CopyOnWriteArrayList<>(mongoCandles));

        // Warn if we still don't have enough for accurate calculation
        if (mongoCandles.size() < minCandlesForAccurateCalc) {
            log.warn("{} {} Only {} 30m candles available (recommended: {}). " +
                "SuperTrend values may differ from broker due to insufficient ATR warmup.",
                LOG_PREFIX, scripCode, mongoCandles.size(), minCandlesForAccurateCalc);
        }

        log.info("{} {} Cached {} historical 30m candles (max: {})", LOG_PREFIX, scripCode, mongoCandles.size(), MAX_CACHED_CANDLES);
        return mongoCandles;
    }

    /**
     * Build historical 30m candles from 1m candles in MongoDB.
     */
    private List<Candle30m> buildHistorical30mFromMongoDB(String scripCode, String exchange,
                                                           List<TickCandle> all1mCandles, Instant beforeTime) {
        // Group 1m candles into 30m windows
        Map<Instant, List<TickCandle>> grouped = new TreeMap<>();

        for (TickCandle candle : all1mCandles) {
            Instant candleTime = candle.getWindowStart();
            ZonedDateTime istTime = candleTime.atZone(IST);

            // Skip if outside market hours for this exchange
            if (!isMarketHours(istTime, exchange)) {
                continue;
            }

            // Calculate which 30m window this belongs to
            Instant windowStart = get30mWindowStartForCandle(istTime, exchange);

            // Only include windows before the current time
            if (windowStart.isBefore(beforeTime)) {
                grouped.computeIfAbsent(windowStart, k -> new ArrayList<>()).add(candle);
            }
        }

        // Aggregate each 30m window
        List<Candle30m> historical = new ArrayList<>();
        int minCandlesFor30m = "M".equalsIgnoreCase(exchange) ? 20 : 20; // Need at least 20 1m candles

        for (Map.Entry<Instant, List<TickCandle>> entry : grouped.entrySet()) {
            Instant windowStart = entry.getKey();
            List<TickCandle> windowCandles = entry.getValue();

            if (windowCandles.size() >= minCandlesFor30m) {
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
    private List<Candle30m> fetchHistorical30mFromAPI(String scripCode, String exchange, String exchangeType, Instant beforeTime) {
        try {
            // Calculate date range
            ZonedDateTime endDate = beforeTime.atZone(IST);
            ZonedDateTime startDate = endDate.minusDays(bootstrapDaysBack);

            String startDateStr = startDate.toLocalDate().toString();
            String endDateStr = endDate.toLocalDate().toString();

            // Determine exchange parameters
            String exch = "M".equalsIgnoreCase(exchange) ? "M" : "N";
            // FIX: Use passed exchangeType instead of hardcoding based on exchange
            // This ensures F&O instruments (exchangeType="D") fetch derivative data, not cash
            String exchType = exchangeType != null ? exchangeType :
                ("M".equalsIgnoreCase(exchange) ? "D" : "C"); // Fallback to old logic if null

            log.info("{} {} Fetching 30m candles from API: {} to {} (exch={}, exchType={})",
                LOG_PREFIX, scripCode, startDateStr, endDateStr, exch, exchType);

            // Fetch 30m candles directly from API
            List<HistoricalCandle> apiCandles = fastAnalyticsClient.getHistoricalData(
                exch, exchType, scripCode, startDateStr, endDateStr, "30m");

            if (apiCandles == null || apiCandles.isEmpty()) {
                log.warn("{} {} No 30m candles from API", LOG_PREFIX, scripCode);
                return new ArrayList<>();
            }

            log.info("{} {} Fetched {} 30m candles from API", LOG_PREFIX, scripCode, apiCandles.size());

            // Get symbol and company name from ScripMetadataService
            String symbol = scripMetadataService.getSymbolRoot(scripCode, null);
            String companyName = scripMetadataService.getCompanyName(scripCode);

            // Convert to Candle30m
            List<Candle30m> historical = new ArrayList<>();
            for (HistoricalCandle hc : apiCandles) {
                Instant ts = hc.getTimestampAsInstant();
                ZonedDateTime istTime = ts.atZone(IST);

                // Skip if outside market hours or at/after beforeTime
                // FIX: Changed from isAfter to !isBefore to exclude candles at beforeTime
                // This prevents the current window's candle from being included in historical data
                if (!isMarketHours(istTime, exchange) || !ts.isBefore(beforeTime)) {
                    continue;
                }

                Candle30m candle = Candle30m.builder()
                    .scripCode(scripCode)
                    .symbol(symbol)
                    .companyName(companyName)
                    .exchange(exchange)
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
     * Get the 30m window start for a given candle time (for grouping 1m candles).
     */
    private Instant get30mWindowStartForCandle(ZonedDateTime istTime, String exchange) {
        int minute = istTime.getMinute();
        ZonedDateTime windowStart;

        if ("M".equalsIgnoreCase(exchange)) {
            // MCX: windows at :00 and :30
            if (minute >= 30) {
                windowStart = istTime.withMinute(30).withSecond(0).withNano(0);
            } else {
                windowStart = istTime.withMinute(0).withSecond(0).withNano(0);
            }
        } else {
            // NSE: windows at :15 and :45
            if (minute >= 45) {
                windowStart = istTime.withMinute(45).withSecond(0).withNano(0);
            } else if (minute >= 15) {
                windowStart = istTime.withMinute(15).withSecond(0).withNano(0);
            } else {
                // Belongs to previous hour's 45 minute window
                windowStart = istTime.minusHours(1).withMinute(45).withSecond(0).withNano(0);
            }
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

        // Detect SuperTrend flip - multiple methods in order of reliability
        boolean superTrendFlipped = false;
        String flipDetectionMethod = "none";

        // Method 1: Calculator's built-in flip detection (most reliable when state is persisted)
        if (bbst.isTrendChanged()) {
            superTrendFlipped = true;
            flipDetectionMethod = "calculator_trendChanged";
            log.info("{} {} Flip detected via calculator.isTrendChanged()", LOG_PREFIX, scripCode);
        }

        // Method 2: Compare with in-memory previous state (catches flips across calculations)
        if (!superTrendFlipped && prevBbst != null) {
            if (prevBbst.getTrend() != bbst.getTrend()) {
                superTrendFlipped = true;
                flipDetectionMethod = "prevBbst_comparison";
                log.info("{} {} Flip detected via prevBbst comparison: {} -> {}",
                    LOG_PREFIX, scripCode, prevBbst.getTrend(), bbst.getTrend());
            }
        }

        // Method 3: Check debounced flip from Redis (catches flips within debounce window)
        boolean usingDebouncedFlip = false;
        if (!superTrendFlipped) {
            String[] recentFlip = redisCacheService.getRecentSTFlip(scripCode, triggerTimeframe);
            if (recentFlip != null) {
                String flipDirection = recentFlip[0];
                if (("UP".equals(flipDirection) && bbst.getTrend() == TrendDirection.UP) ||
                    ("DOWN".equals(flipDirection) && bbst.getTrend() == TrendDirection.DOWN)) {
                    superTrendFlipped = true;
                    usingDebouncedFlip = true;
                    flipDetectionMethod = "redis_debounce";
                    log.info("{} {} Flip detected via Redis debounce: {}", LOG_PREFIX, scripCode, flipDirection);
                }
            }
        }

        // Method 4: FIX - Fallback detection using barsInTrend
        // If barsInTrend is 1 and we have no previous state, this MIGHT be a flip
        // This is a heuristic - if trend just started (1 bar), it could be a flip
        // FIX: Skip this heuristic on first candle of day (09:15 NSE, 09:00 MCX)
        // because we have no intraday context to validate the flip
        if (!superTrendFlipped && prevBbst == null && bbst.getBarsInTrend() == 1) {
            // Check if this is the first candle of the day
            boolean isFirstCandleOfDay = isFirstCandleOfDay(currentCandle.windowStart, currentCandle.exchange);

            if (isFirstCandleOfDay) {
                log.info("{} {} Skipping barsInTrend heuristic - first candle of day (no intraday context)",
                    LOG_PREFIX, scripCode);
            } else {
                // Only trigger if price action strongly confirms the direction
                // (above upper BB for bullish flip, below lower BB for bearish flip)
                PricePosition pos = bbst.getPricePosition();
                if ((bbst.getTrend() == TrendDirection.UP && pos == PricePosition.ABOVE_UPPER) ||
                    (bbst.getTrend() == TrendDirection.DOWN && pos == PricePosition.BELOW_LOWER)) {
                    superTrendFlipped = true;
                    flipDetectionMethod = "barsInTrend_heuristic";
                    log.info("{} {} Flip detected via barsInTrend heuristic: trend={}, barsInTrend=1, pricePos={}",
                        LOG_PREFIX, scripCode, bbst.getTrend(), pos);
                }
            }
        }

        // Log why flip detection failed if it did
        if (!superTrendFlipped) {
            log.info("{} {} No flip detected. Reasons: calculator.trendChanged={}, prevBbst={}, " +
                "barsInTrend={}, trend={}, pricePosition={}",
                LOG_PREFIX, scripCode, bbst.isTrendChanged(),
                prevBbst != null ? prevBbst.getTrend() : "null",
                bbst.getBarsInTrend(), bbst.getTrend(), bbst.getPricePosition());
        }

        // Record new flip for debouncing (if not already using debounced flip)
        if (superTrendFlipped && !usingDebouncedFlip) {
            String flipDir = bbst.getTrend() == TrendDirection.UP ? "UP" : "DOWN";
            redisCacheService.recordSTFlip(scripCode, triggerTimeframe, flipDir, flipDebounceMinutes);
            log.debug("{} {} Recorded ST flip to Redis: {}", LOG_PREFIX, scripCode, flipDir);
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
        log.info("{} {} ST_FLIPPED: {} (method: {}, debounced: {})",
            LOG_PREFIX, scripCode, superTrendFlipped, flipDetectionMethod, usingDebouncedFlip);
        log.info("{} {} TREND: {} (barsInTrend: {})", LOG_PREFIX, scripCode, bbst.getTrend(), bbst.getBarsInTrend());
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

        // Determine exchange
        String exchange = latestCandle.getExchange();
        if (exchange == null) exchange = "N";

        // Use candle's timestamp (event time) instead of wall-clock time for 30m boundary check
        Instant candleTime = latestCandle.getWindowStart() != null ? latestCandle.getWindowStart() : latestCandle.getTimestamp();
        ZonedDateTime candleZdt = candleTime.atZone(IST);
        int minute = candleZdt.getMinute();

        if (!is30mBoundary(minute, exchange)) {
            return FudkiiTriggerResult.noTrigger("Not at 30m boundary (minute=" + minute + ", exchange=" + exchange + ")");
        }

        return onCandleClose(scripCode, latestCandle);
    }

    /**
     * Check if current time is a valid trigger time - for backward compatibility.
     */
    public boolean isValidTriggerTime() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        int minute = now.getMinute();
        // Check both NSE and MCX boundaries
        boolean nseValid = (minute == 15 || minute == 45) && isMarketHours(now, "N");
        boolean mcxValid = mcxEnabled && (minute == 0 || minute == 30) && isMarketHours(now, "M");
        return nseValid || mcxValid;
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
        private String symbol;        // Clean symbol from ScripMetadataService
        private String companyName;   // Full company name from ScripMetadataService
        private String exchange;      // Exchange code (N, M, B)
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
        private String scripCode;
        private String symbol;        // Clean symbol from ScripMetadataService
        private String companyName;   // Full company name from ScripMetadataService
        private String exchange;      // Exchange code (N, M, B)
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
