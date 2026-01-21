package com.kotsin.consumer.trading.mtf;

import com.kotsin.consumer.domain.model.FamilyCandle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
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
@RequiredArgsConstructor
public class HtfCandleAggregator {

    private final RedisTemplate<String, String> redisTemplate;

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
        String tfKey = tfMinutes + "m";
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
     * @param timeframe Timeframe string ("5m", "15m", "1h", "4h", "daily")
     * @param count     Number of candles to retrieve
     * @return List of HtfCandles (oldest first)
     */
    public List<HtfCandle> getCandles(String familyId, String timeframe, int count) {
        String key = String.format(HTF_CANDLE_KEY, familyId, timeframe);
        List<String> entries = redisTemplate.opsForList().range(key, -count, -1);

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
     * Get candles as arrays for SmcAnalyzer
     */
    public SmcCandleData getCandleArrays(String familyId, String timeframe, int count) {
        List<HtfCandle> candles = getCandles(familyId, timeframe, count);

        if (candles.isEmpty()) {
            return SmcCandleData.empty(timeframe);
        }

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
}
