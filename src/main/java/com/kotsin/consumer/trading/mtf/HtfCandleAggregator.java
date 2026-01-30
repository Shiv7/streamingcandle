package com.kotsin.consumer.trading.mtf;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.infrastructure.redis.RedisCandleHistoryService;
import com.kotsin.consumer.model.UnifiedCandle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * HtfCandleAggregator - Aggregates 1-minute candles into higher timeframes
 *
 * THIS IS THE FOUNDATION FOR REAL MTF ANALYSIS.
 *
 * Without HTF candles, we can't do:
 * - HTF market structure analysis (Daily/4H bias)
 * - HTF Order Block detection (meaningful zones)
 * - HTF FVG detection (real imbalances)
 * - Proper premium/discount zones (from Daily range)
 *
 * TIMEFRAMES SUPPORTED:
 * - 5m  (5 x 1m candles)   - LTF entry confirmation
 * - 15m (15 x 1m candles)  - LTF structure and sweeps
 * - 1H  (60 x 1m candles)  - HTF structure
 * - 4H  (240 x 1m candles) - HTF bias
 * - Daily (session candles) - Major bias and levels
 *
 * HOW IT WORKS:
 * 1. Receive 1m candle from FamilyCandle stream
 * 2. Aggregate into building candles for each HTF
 * 3. When HTF candle completes, store it and start new one
 * 4. Maintain history for SMC analysis (100 candles per TF)
 */
@Slf4j
@Service
public class HtfCandleAggregator {

    private final RedisTemplate<String, String> redisTemplate;

    @Autowired(required = false)
    private RedisCandleHistoryService candleHistoryService;

    // Track which familyId:timeframe pairs have been SUCCESSFULLY bootstrapped
    // Key format: "familyId:timeframe" (e.g., "3150:1h", "3150:4h")
    private final Set<String> bootstrappedPairs = ConcurrentHashMap.newKeySet();

    // FIX: Track bootstrap attempts for retry logic (allows up to 3 retries before giving up)
    // Key format: "familyId:timeframe" -> attempt count
    private final Map<String, Integer> bootstrapAttempts = new ConcurrentHashMap<>();
    private static final int MAX_BOOTSTRAP_ATTEMPTS = 3;

    @Autowired
    public HtfCandleAggregator(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // Redis key patterns
    private static final String HTF_CANDLE_KEY = "smtis:htf:candles:%s:%s";  // familyId:timeframe
    private static final String HTF_BUILDING_KEY = "smtis:htf:building:%s:%s";
    private static final Duration CANDLE_TTL = Duration.ofHours(48);

    // In-memory building candles (for speed)
    private final Map<String, Map<String, BuildingCandle>> buildingCandles = new ConcurrentHashMap<>();

    // Timeframe definitions (in minutes)
    public static final int TF_5M = 5;
    public static final int TF_15M = 15;
    public static final int TF_1H = 60;
    public static final int TF_4H = 240;

    // Session times (IST - Indian market)
    private static final int SESSION_START_HOUR = 9;
    private static final int SESSION_START_MIN = 15;
    private static final int SESSION_END_HOUR = 15;
    private static final int SESSION_END_MIN = 30;

    /**
     * Process a new 1-minute candle and aggregate into HTF candles
     *
     * @param family The 1-minute FamilyCandle
     * @return List of completed HTF candles (if any completed this tick)
     */
    public List<HtfCandle> processCandle(FamilyCandle family) {
        if (family == null) return List.of();

        String familyId = family.getFamilyId();
        double open = getOpen(family);
        double high = getHigh(family);
        double low = getLow(family);
        double close = getClose(family);
        double volume = getVolume(family);
        long timestamp = family.getWindowEndMillis();

        if (close <= 0) return List.of();

        List<HtfCandle> completedCandles = new ArrayList<>();

        // Aggregate into each timeframe
        for (int tfMinutes : new int[]{TF_5M, TF_15M, TF_1H, TF_4H}) {
            HtfCandle completed = aggregateCandle(
                    familyId, tfMinutes, open, high, low, close, volume, timestamp);
            if (completed != null) {
                completedCandles.add(completed);
                storeCompletedCandle(familyId, completed);
            }
        }

        // Also track daily candle
        HtfCandle dailyCompleted = aggregateDailyCandle(
                familyId, open, high, low, close, volume, timestamp);
        if (dailyCompleted != null) {
            completedCandles.add(dailyCompleted);
            storeCompletedCandle(familyId, dailyCompleted);
        }

        return completedCandles;
    }

    /**
     * Aggregate 1m candle into specific timeframe
     */
    private HtfCandle aggregateCandle(String familyId, int tfMinutes,
                                       double open, double high, double low, double close,
                                       double volume, long timestamp) {
        String tfKey = normalizeTimeframe(tfMinutes);
        String key = familyId + ":" + tfKey;

        // Get or create building candle
        BuildingCandle building = buildingCandles
                .computeIfAbsent(familyId, k -> new ConcurrentHashMap<>())
                .get(tfKey);

        // Calculate candle boundary
        long periodStart = getCandlePeriodStart(timestamp, tfMinutes);

        // Check if we need to start a new candle
        if (building == null || building.periodStart != periodStart) {
            // Complete the old candle if exists
            HtfCandle completed = null;
            if (building != null && building.candleCount > 0) {
                completed = building.toHtfCandle(tfKey);
                log.debug("[HTF] {} {} candle completed | O={} H={} L={} C={} | count={}",
                        familyId, tfKey, completed.getOpen(), completed.getHigh(),
                        completed.getLow(), completed.getClose(), building.candleCount);
            }

            // Start new building candle
            building = new BuildingCandle();
            building.periodStart = periodStart;
            building.open = open;
            building.high = high;
            building.low = low;
            building.close = close;
            building.volume = volume;
            building.candleCount = 1;
            building.firstTimestamp = timestamp;
            building.lastTimestamp = timestamp;

            buildingCandles.get(familyId).put(tfKey, building);

            return completed;
        }

        // Update existing building candle
        building.high = Math.max(building.high, high);
        building.low = Math.min(building.low, low);
        building.close = close;
        building.volume += volume;
        building.candleCount++;
        building.lastTimestamp = timestamp;

        return null;  // Not completed yet
    }

    /**
     * Aggregate daily candle (based on trading session)
     */
    private HtfCandle aggregateDailyCandle(String familyId,
                                            double open, double high, double low, double close,
                                            double volume, long timestamp) {
        String tfKey = "daily";
        String key = familyId + ":" + tfKey;

        BuildingCandle building = buildingCandles
                .computeIfAbsent(familyId, k -> new ConcurrentHashMap<>())
                .get(tfKey);

        // Get current trading day
        ZonedDateTime dt = Instant.ofEpochMilli(timestamp).atZone(ZoneId.of("Asia/Kolkata"));
        long dayStart = getDayStart(timestamp);

        // Check if new day
        if (building == null || building.periodStart != dayStart) {
            HtfCandle completed = null;
            if (building != null && building.candleCount > 0) {
                completed = building.toHtfCandle(tfKey);
                log.info("[HTF] {} DAILY candle completed | O={} H={} L={} C={}",
                        familyId, completed.getOpen(), completed.getHigh(),
                        completed.getLow(), completed.getClose());
            }

            building = new BuildingCandle();
            building.periodStart = dayStart;
            building.open = open;
            building.high = high;
            building.low = low;
            building.close = close;
            building.volume = volume;
            building.candleCount = 1;
            building.firstTimestamp = timestamp;
            building.lastTimestamp = timestamp;

            buildingCandles.get(familyId).put(tfKey, building);

            return completed;
        }

        // Update
        building.high = Math.max(building.high, high);
        building.low = Math.min(building.low, low);
        building.close = close;
        building.volume += volume;
        building.candleCount++;
        building.lastTimestamp = timestamp;

        return null;
    }

    /**
     * Get candle period start time for a given timestamp
     */
    private long getCandlePeriodStart(long timestamp, int periodMinutes) {
        long periodMs = periodMinutes * 60 * 1000L;
        return (timestamp / periodMs) * periodMs;
    }

    /**
     * Get trading day start (9:15 IST)
     */
    private long getDayStart(long timestamp) {
        ZonedDateTime dt = Instant.ofEpochMilli(timestamp).atZone(ZoneId.of("Asia/Kolkata"));
        ZonedDateTime dayStart = dt.withHour(SESSION_START_HOUR).withMinute(SESSION_START_MIN)
                .withSecond(0).withNano(0);

        // If before session start, use previous day
        if (dt.isBefore(dayStart)) {
            dayStart = dayStart.minusDays(1);
        }

        return dayStart.toInstant().toEpochMilli();
    }

    /**
     * Store completed candle to Redis
     */
    private void storeCompletedCandle(String familyId, HtfCandle candle) {
        String key = String.format(HTF_CANDLE_KEY, familyId, candle.getTimeframe());
        String entry = String.format("%.4f,%.4f,%.4f,%.4f,%.0f,%d",
                candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose(),
                candle.getVolume(), candle.getTimestamp());

        redisTemplate.opsForList().rightPush(key, entry);
        redisTemplate.opsForList().trim(key, -100, -1);  // Keep last 100
        redisTemplate.expire(key, CANDLE_TTL);
    }

    /**
     * Get historical HTF candles for SMC analysis
     *
     * @param familyId  The family ID
     * @param timeframe Timeframe string ("5m", "15m", "1h", "4h", "daily") - normalized format
     * @param count     Number of candles to retrieve
     * @return List of HtfCandles (oldest first)
     */
    public List<HtfCandle> getCandles(String familyId, String timeframe, int count) {
        String key = String.format(HTF_CANDLE_KEY, familyId, timeframe);
        List<String> entries = redisTemplate.opsForList().range(key, -count, -1);

        // Bootstrap from historical service if insufficient data
        // Track per timeframe to ensure each timeframe is bootstrapped independently
        // FIX: Allow retries for failed bootstraps (up to MAX_BOOTSTRAP_ATTEMPTS)
        // FIX: Increase threshold from 20 to 50 to ensure we get historical data, not just today's real-time
        String bootstrapKey = familyId + ":" + timeframe;
        int currentSize = entries != null ? entries.size() : 0;

        // FIX: Trigger bootstrap if we have fewer than 50 candles (not just 20)
        // This ensures we get historical data from RedisCandleHistoryService
        // Real-time aggregation typically produces ~6 1h candles per day
        // We want at least 50 for proper swing detection and market structure analysis
        boolean needsBootstrap = currentSize < 50;
        boolean alreadySuccessful = bootstrappedPairs.contains(bootstrapKey);
        int attempts = bootstrapAttempts.getOrDefault(bootstrapKey, 0);
        boolean canRetry = attempts < MAX_BOOTSTRAP_ATTEMPTS;

        log.debug("[HTF_DEBUG] {} {} getCandles - currentSize={}, needsBootstrap={}, alreadySuccessful={}, attempts={}, canRetry={}",
                familyId, timeframe, currentSize, needsBootstrap, alreadySuccessful, attempts, canRetry);

        if (needsBootstrap && !alreadySuccessful && canRetry) {
            log.info("[HTF_DEBUG] {} {} - Triggering bootstrap (attempt {}) - currentSize={} < 50",
                    familyId, timeframe, attempts + 1, currentSize);
            bootstrapFromHistoricalService(familyId, timeframe);
            // Re-fetch after bootstrap
            entries = redisTemplate.opsForList().range(key, -count, -1);
            log.info("[HTF_DEBUG] {} {} - After bootstrap: {} entries", familyId, timeframe,
                    entries != null ? entries.size() : 0);
        }

        List<HtfCandle> candles = new ArrayList<>();
        if (entries == null) return candles;

        for (String entry : entries) {
            try {
                String[] parts = entry.split(",");
                if (parts.length >= 6) {
                    candles.add(HtfCandle.builder()
                            .open(Double.parseDouble(parts[0]))
                            .high(Double.parseDouble(parts[1]))
                            .low(Double.parseDouble(parts[2]))
                            .close(Double.parseDouble(parts[3]))
                            .volume(Double.parseDouble(parts[4]))
                            .timestamp(Long.parseLong(parts[5]))
                            .timeframe(timeframe)
                            .build());
                }
            } catch (NumberFormatException e) {
                log.warn("[HTF] Failed to parse candle entry: {}", entry);
            }
        }

        return candles;
    }

    /**
     * Bootstrap HTF candles from RedisCandleHistoryService (historical 1m data)
     * This bridges the gap between historical API data and the HTF candle store.
     *
     * FIX: Only mark as successfully bootstrapped if we have enough data (20+ candles).
     * Failed bootstraps are tracked and can be retried up to MAX_BOOTSTRAP_ATTEMPTS times.
     */
    private void bootstrapFromHistoricalService(String familyId, String timeframe) {
        String bootstrapKey = familyId + ":" + timeframe;

        // FIX: Track attempt count for retry logic
        int attemptCount = bootstrapAttempts.merge(bootstrapKey, 1, Integer::sum);
        log.debug("[HTF] Bootstrap attempt {} for {} {}", attemptCount, familyId, timeframe);

        if (candleHistoryService == null) {
            log.debug("[HTF] CandleHistoryService not available for bootstrap - will retry later");
            // FIX: Do NOT mark as bootstrapped - allow retry when service becomes available
            return;
        }

        // FIX: Determine exchange type from familyId instead of hardcoding "N", "C"
        // MCX scripCodes are typically in ranges: 400000+, 450000+, 467xxx, 488xxx
        String[] exchangeInfo = determineExchangeType(familyId);
        String exchange = exchangeInfo[0];
        String exchangeType = exchangeInfo[1];

        try {
            // FIRST: Try to get pre-aggregated candles directly (RedisCandleHistoryService now stores 1h)
            log.info("[HTF_BOOTSTRAP_DEBUG] {} {} - Fetching pre-aggregated from RedisCandleHistoryService (exchange={}:{})...",
                    familyId, timeframe, exchange, exchangeType);
            List<UnifiedCandle> preAggregated = candleHistoryService.getCandles(
                    familyId, timeframe, 500, exchange, exchangeType);
            log.info("[HTF_BOOTSTRAP_DEBUG] {} {} - Got {} pre-aggregated candles from history service",
                    familyId, timeframe, preAggregated.size());

            // FIX: Increase threshold to 50 to match minimum requirement for good market structure analysis
            if (preAggregated.size() >= 50) {
                // Convert UnifiedCandle to HtfCandle and store
                for (UnifiedCandle uc : preAggregated) {
                    HtfCandle htf = HtfCandle.builder()
                            .open(uc.getOpen())
                            .high(uc.getHigh())
                            .low(uc.getLow())
                            .close(uc.getClose())
                            .volume(uc.getVolume())
                            .timestamp(uc.getWindowEndMillis())
                            .timeframe(timeframe)
                            .build();
                    storeCompletedCandle(familyId, htf);
                }
                log.info("[HTF] Bootstrapped {} pre-aggregated {} candles for {} from historical service",
                        preAggregated.size(), timeframe, familyId);
                // FIX: Mark as successfully bootstrapped only when we have enough data
                bootstrappedPairs.add(bootstrapKey);
                return;
            }

            // FIX: ALWAYS try 1m fallback if pre-aggregated is insufficient (< 50)
            // This handles the case where RedisCandleHistoryService was bootstrapped before 1h aggregation was added
            log.info("[HTF_BOOTSTRAP_DEBUG] {} {} - Pre-aggregated insufficient ({}), trying 1m fallback...",
                    familyId, timeframe, preAggregated.size());

            // FALLBACK: Get 1m candles and aggregate manually
            // FIX: Increased from 500 to 15000 to support 2 months of historical data
            List<UnifiedCandle> candles1m = candleHistoryService.getCandles(
                    familyId, "1m", 15000, exchange, exchangeType);

            log.info("[HTF_BOOTSTRAP_DEBUG] {} {} - Got {} 1m candles for manual aggregation",
                    familyId, timeframe, candles1m.size());

            if (candles1m.isEmpty()) {
                log.warn("[HTF] No historical 1m candles found for {} {} - attempt {}/{}",
                        familyId, timeframe, attemptCount, MAX_BOOTSTRAP_ATTEMPTS);
                // FIX: Do NOT mark as bootstrapped - allow retry
                return;
            }

            // Determine aggregation period based on timeframe
            int periodMinutes = switch (timeframe) {
                case "5m" -> 5;
                case "15m" -> 15;
                case "1h" -> 60;
                case "4h" -> 240;
                default -> 60;
            };

            // Aggregate 1m candles to requested timeframe
            List<HtfCandle> aggregated = aggregateHistoricalCandles(candles1m, timeframe, periodMinutes);

            log.info("[HTF_BOOTSTRAP_DEBUG] {} {} - Aggregated {} {} candles from {} 1m candles",
                    familyId, timeframe, aggregated.size(), timeframe, candles1m.size());

            // FIX: Lower threshold to 20 for aggregated candles (we tried our best)
            if (aggregated.size() >= 20) {
                // Store aggregated candles
                for (HtfCandle candle : aggregated) {
                    storeCompletedCandle(familyId, candle);
                }
                log.info("[HTF] Successfully bootstrapped {} {} candles for {} from 1m aggregation",
                        aggregated.size(), timeframe, familyId);
                // FIX: Mark as successfully bootstrapped only when we have enough data
                bootstrappedPairs.add(bootstrapKey);
            } else {
                log.warn("[HTF] Insufficient aggregated candles ({}) for {} {} - need 20+, attempt {}/{}",
                        aggregated.size(), familyId, timeframe, attemptCount, MAX_BOOTSTRAP_ATTEMPTS);
                // FIX: Do NOT mark as bootstrapped - allow retry
            }

        } catch (Exception e) {
            log.warn("[HTF] Bootstrap failed for {} {} (attempt {}/{}): {}",
                    familyId, timeframe, attemptCount, MAX_BOOTSTRAP_ATTEMPTS, e.getMessage());
            // FIX: Do NOT mark as bootstrapped on exception - allow retry
        }
    }

    /**
     * Aggregate 1m UnifiedCandles to HTF candles
     */
    private List<HtfCandle> aggregateHistoricalCandles(List<UnifiedCandle> candles1m,
                                                        String timeframe, int periodMinutes) {
        List<HtfCandle> result = new ArrayList<>();
        if (candles1m.isEmpty()) return result;

        // Sort by timestamp
        candles1m.sort((a, b) -> Long.compare(a.getWindowStartMillis(), b.getWindowStartMillis()));

        long periodMs = periodMinutes * 60 * 1000L;
        Map<Long, List<UnifiedCandle>> windows = new java.util.LinkedHashMap<>();

        for (UnifiedCandle c : candles1m) {
            long windowStart = (c.getWindowStartMillis() / periodMs) * periodMs;
            windows.computeIfAbsent(windowStart, k -> new ArrayList<>()).add(c);
        }

        for (Map.Entry<Long, List<UnifiedCandle>> entry : windows.entrySet()) {
            List<UnifiedCandle> windowCandles = entry.getValue();
            if (windowCandles.isEmpty()) continue;

            double open = windowCandles.get(0).getOpen();
            double close = windowCandles.get(windowCandles.size() - 1).getClose();
            double high = windowCandles.stream().mapToDouble(UnifiedCandle::getHigh).max().orElse(0);
            double low = windowCandles.stream().mapToDouble(UnifiedCandle::getLow).min().orElse(0);
            double volume = windowCandles.stream().mapToDouble(UnifiedCandle::getVolume).sum();

            result.add(HtfCandle.builder()
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume)
                    .timestamp(entry.getKey() + periodMs) // End of window
                    .timeframe(timeframe)
                    .candleCount(windowCandles.size())
                    .build());
        }

        return result;
    }

    /**
     * Get candles as arrays for SmcAnalyzer
     */
    public SmcCandleData getCandleArrays(String familyId, String timeframe, int count) {
        List<HtfCandle> candles = getCandles(familyId, timeframe, count);

        if (candles.isEmpty()) {
            log.warn("[HTF_DEBUG] {} {} - No candles found! Check bootstrap status.", familyId, timeframe);
            return SmcCandleData.empty(timeframe);
        }

        log.debug("[HTF_DEBUG] {} {} - Retrieved {} candles for SMC analysis",
                familyId, timeframe, candles.size());

        double[] opens = new double[candles.size()];
        double[] highs = new double[candles.size()];
        double[] lows = new double[candles.size()];
        double[] closes = new double[candles.size()];
        long[] timestamps = new long[candles.size()];

        for (int i = 0; i < candles.size(); i++) {
            HtfCandle c = candles.get(i);
            opens[i] = c.getOpen();
            highs[i] = c.getHigh();
            lows[i] = c.getLow();
            closes[i] = c.getClose();
            timestamps[i] = c.getTimestamp();
        }

        return new SmcCandleData(opens, highs, lows, closes, timestamps, timeframe);
    }

    /**
     * Get the current building candle (incomplete HTF candle)
     */
    public HtfCandle getBuildingCandle(String familyId, String timeframe) {
        Map<String, BuildingCandle> familyBuilding = buildingCandles.get(familyId);
        if (familyBuilding == null) return null;

        BuildingCandle building = familyBuilding.get(timeframe);
        if (building == null || building.candleCount == 0) return null;

        return building.toHtfCandle(timeframe);
    }

    /**
     * Get daily swing high/low for premium/discount calculation
     */
    public double[] getDailyRange(String familyId) {
        // Get last few daily candles to find swing high/low
        List<HtfCandle> dailyCandles = getCandles(familyId, "daily", 20);

        if (dailyCandles.isEmpty()) {
            // Use building daily candle
            HtfCandle building = getBuildingCandle(familyId, "daily");
            if (building != null) {
                return new double[]{building.getHigh(), building.getLow()};
            }
            return new double[]{0, 0};
        }

        double swingHigh = dailyCandles.stream().mapToDouble(HtfCandle::getHigh).max().orElse(0);
        double swingLow = dailyCandles.stream().mapToDouble(HtfCandle::getLow).min().orElse(0);

        // Include current building candle
        HtfCandle building = getBuildingCandle(familyId, "daily");
        if (building != null) {
            swingHigh = Math.max(swingHigh, building.getHigh());
            swingLow = Math.min(swingLow, building.getLow());
        }

        return new double[]{swingHigh, swingLow};
    }

    // ============ HELPER METHODS ============

    /**
     * Normalize timeframe to standard format (e.g., 60 -> "1h", 240 -> "4h")
     */
    private String normalizeTimeframe(int minutes) {
        if (minutes == 60) return "1h";
        if (minutes == 240) return "4h";
        return minutes + "m";
    }

    private double getOpen(FamilyCandle family) {
        if (family.getFuture() != null) return family.getFuture().getOpen();
        if (family.getEquity() != null) return family.getEquity().getOpen();
        return 0;
    }

    private double getHigh(FamilyCandle family) {
        if (family.getFuture() != null) return family.getFuture().getHigh();
        if (family.getEquity() != null) return family.getEquity().getHigh();
        return 0;
    }

    private double getLow(FamilyCandle family) {
        if (family.getFuture() != null) return family.getFuture().getLow();
        if (family.getEquity() != null) return family.getEquity().getLow();
        return 0;
    }

    private double getClose(FamilyCandle family) {
        return family.getPrimaryPrice();
    }

    private double getVolume(FamilyCandle family) {
        if (family.getFuture() != null) return family.getFuture().getVolume();
        if (family.getEquity() != null) return family.getEquity().getVolume();
        return 0;
    }

    // ============ INNER CLASSES ============

    /**
     * Building candle (incomplete, being aggregated)
     */
    private static class BuildingCandle {
        long periodStart;
        double open;
        double high;
        double low;
        double close;
        double volume;
        int candleCount;
        long firstTimestamp;
        long lastTimestamp;

        HtfCandle toHtfCandle(String timeframe) {
            return HtfCandle.builder()
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume)
                    .timestamp(lastTimestamp)
                    .timeframe(timeframe)
                    .candleCount(candleCount)
                    .build();
        }
    }

    /**
     * Completed HTF candle
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HtfCandle {
        private double open;
        private double high;
        private double low;
        private double close;
        private double volume;
        private long timestamp;
        private String timeframe;
        private int candleCount;  // How many 1m candles formed this

        public boolean isBullish() {
            return close > open;
        }

        public boolean isBearish() {
            return close < open;
        }

        public double getRange() {
            return high - low;
        }

        public double getBody() {
            return Math.abs(close - open);
        }
    }

    /**
     * Determine exchange and exchange type from scripCode/familyId.
     * MCX scripCodes are typically in ranges: 400000+, 450000+, 467xxx, 488xxx
     *
     * @param familyId The scripCode/familyId to check
     * @return String array [exchange, exchangeType] - e.g., ["N", "C"] for NSE Cash, ["M", "D"] for MCX Derivatives
     */
    private String[] determineExchangeType(String familyId) {
        try {
            int scripCode = Integer.parseInt(familyId);

            // MCX scripCodes are typically > 400000
            // Common MCX ranges: 450000-470000 (futures), 480000-500000 (options)
            if (scripCode >= 400000) {
                return new String[]{"M", "D"};  // MCX Derivatives
            }
        } catch (NumberFormatException e) {
            // Not a numeric scripCode, default to NSE
        }

        // Default to NSE Cash
        return new String[]{"N", "C"};
    }

    /**
     * Candle data arrays for SmcAnalyzer
     */
    @Data
    @AllArgsConstructor
    public static class SmcCandleData {
        private double[] opens;
        private double[] highs;
        private double[] lows;
        private double[] closes;
        private long[] timestamps;
        private String timeframe;

        public static SmcCandleData empty(String timeframe) {
            return new SmcCandleData(
                    new double[0], new double[0], new double[0], new double[0],
                    new long[0], timeframe);
        }

        public int size() {
            return closes != null ? closes.length : 0;
        }

        public boolean hasData() {
            return size() >= 20;  // Minimum for SMC analysis
        }
    }

    // =============================================================================
    // ADMIN METHODS - Bootstrap attempt management
    // =============================================================================

    /**
     * Clear bootstrap attempts for a specific scripCode.
     * This allows re-bootstrap for instruments that exhausted their attempts.
     *
     * @param scripCode The scripCode to clear attempts for
     * @return Number of timeframe entries cleared
     */
    public int clearBootstrapAttempts(String scripCode) {
        int cleared = 0;
        String prefix = scripCode + ":";

        // Clear from bootstrapAttempts map
        for (String key : new ArrayList<>(bootstrapAttempts.keySet())) {
            if (key.startsWith(prefix)) {
                bootstrapAttempts.remove(key);
                cleared++;
            }
        }

        // Also clear from bootstrappedPairs (in case it was marked as successful)
        for (String key : new ArrayList<>(bootstrappedPairs)) {
            if (key.startsWith(prefix)) {
                bootstrappedPairs.remove(key);
            }
        }

        if (cleared > 0) {
            log.info("[HTF_ADMIN] Cleared {} bootstrap entries for scripCode={}", cleared, scripCode);
        }
        return cleared;
    }

    /**
     * Clear bootstrap attempts for all MCX instruments (scripCode >= 400000).
     * Useful after fixing MCX bootstrap issues.
     *
     * @return Number of entries cleared
     */
    public int clearMcxBootstrapAttempts() {
        int cleared = 0;

        for (String key : new ArrayList<>(bootstrapAttempts.keySet())) {
            try {
                String scripPart = key.split(":")[0];
                int scripCode = Integer.parseInt(scripPart);
                if (scripCode >= 400000) {
                    bootstrapAttempts.remove(key);
                    cleared++;
                }
            } catch (NumberFormatException e) {
                // Not a numeric key, skip
            }
        }

        for (String key : new ArrayList<>(bootstrappedPairs)) {
            try {
                String scripPart = key.split(":")[0];
                int scripCode = Integer.parseInt(scripPart);
                if (scripCode >= 400000) {
                    bootstrappedPairs.remove(key);
                }
            } catch (NumberFormatException e) {
                // Not a numeric key, skip
            }
        }

        log.info("[HTF_ADMIN] Cleared {} MCX bootstrap entries (scripCode >= 400000)", cleared);
        return cleared;
    }

    /**
     * Get bootstrap attempt statistics for monitoring.
     *
     * @return Map with statistics
     */
    public Map<String, Object> getBootstrapAttemptStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();

        int total = bootstrapAttempts.size();
        int exhausted = 0;
        int mcxTotal = 0;
        int mcxExhausted = 0;

        for (Map.Entry<String, Integer> entry : bootstrapAttempts.entrySet()) {
            String key = entry.getKey();
            int attempts = entry.getValue();

            if (attempts >= MAX_BOOTSTRAP_ATTEMPTS) {
                exhausted++;
            }

            try {
                String scripPart = key.split(":")[0];
                int scripCode = Integer.parseInt(scripPart);
                if (scripCode >= 400000) {
                    mcxTotal++;
                    if (attempts >= MAX_BOOTSTRAP_ATTEMPTS) {
                        mcxExhausted++;
                    }
                }
            } catch (NumberFormatException e) {
                // Not a numeric key, skip
            }
        }

        stats.put("totalTrackedPairs", total);
        stats.put("exhaustedAttempts", exhausted);
        stats.put("successfullyBootstrapped", bootstrappedPairs.size());
        stats.put("maxAttemptsAllowed", MAX_BOOTSTRAP_ATTEMPTS);
        stats.put("mcxTotal", mcxTotal);
        stats.put("mcxExhausted", mcxExhausted);

        return stats;
    }
}
