package com.kotsin.consumer.enrichment.tracker;

import com.kotsin.consumer.curated.model.SwingPoint;
import com.kotsin.consumer.curated.model.SwingPoint.SwingType;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.enrichment.model.DetectedEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SwingPointTracker - Tracks swing highs and lows across families.
 *
 * WHAT IS A SWING POINT?
 * - Swing HIGH: A bar where the high is higher than N bars before and after
 * - Swing LOW: A bar where the low is lower than N bars before and after
 *
 * WHY TRACK SWINGS?
 * 1. Support/Resistance: Swing points become key levels
 * 2. Trend Structure: Higher highs + higher lows = uptrend
 * 3. Pattern Entry: Many patterns trigger on swing reversals
 * 4. Level Testing: Count how many times a swing level is tested
 *
 * SWING EVENTS GENERATED:
 * - SWING_HIGH_FORMED: New swing high confirmed
 * - SWING_LOW_FORMED: New swing low confirmed
 * - SWING_HIGH_BROKEN: Price broke above previous swing high
 * - SWING_LOW_BROKEN: Price broke below previous swing low
 * - LOWER_HIGH_FORMED: Bearish structure (lower high after high)
 * - HIGHER_LOW_FORMED: Bullish structure (higher low after low)
 */
@Slf4j
@Service
public class SwingPointTracker {

    // Configuration
    private static final int SWING_LOOKBACK = 3;  // Bars before/after to confirm swing
    private static final int MAX_SWINGS_PER_FAMILY = 20;  // Keep last N swings
    private static final long SWING_VALIDITY_MS = 4 * 60 * 60 * 1000L;  // 4 hours

    // State per family
    private final Map<String, SwingState> stateByFamily = new ConcurrentHashMap<>();

    /**
     * Update swing tracking with new candle data.
     *
     * @param family FamilyCandle to process
     * @return SwingAnalysis containing current state and any new events
     */
    public SwingAnalysis update(FamilyCandle family) {
        long startTime = System.nanoTime();

        if (family == null) {
            log.trace("[SWING] Received null family, returning empty analysis");
            return SwingAnalysis.empty();
        }

        String familyId = family.getFamilyId();
        SwingState state = stateByFamily.computeIfAbsent(familyId, k -> {
            log.info("[SWING] {} Initializing new swing state tracking", familyId);
            return new SwingState();
        });

        // Get OHLC from primary instrument (equity or future)
        double high = getPrimaryHigh(family);
        double low = getPrimaryLow(family);
        double close = family.getPrimaryPrice();
        long volume = getPrimaryVolume(family);
        long timestamp = family.getWindowEndMillis() > 0 ? family.getWindowEndMillis() :
                         family.getTimestamp() > 0 ? family.getTimestamp() : System.currentTimeMillis();

        log.trace("[SWING] {} Processing candle | H={} L={} C={} V={} | bufferSize={} | barIndex={}",
                familyId, String.format("%.2f", high), String.format("%.2f", low), String.format("%.2f", close), volume, state.candleBuffer.size(), state.barIndex);

        // Add to candle buffer
        CandleData candle = new CandleData(timestamp, high, low, close, volume);
        state.candleBuffer.add(candle);

        // Keep buffer size manageable
        while (state.candleBuffer.size() > SWING_LOOKBACK * 3) {
            state.candleBuffer.remove(0);
        }

        List<DetectedEvent> events = new ArrayList<>();
        int requiredCandles = 2 * SWING_LOOKBACK + 1;

        // Need at least 2*SWING_LOOKBACK + 1 candles to detect swings
        if (state.candleBuffer.size() >= requiredCandles) {
            // Check for swing high at position SWING_LOOKBACK (middle of buffer)
            int checkIndex = state.candleBuffer.size() - SWING_LOOKBACK - 1;
            CandleData checkCandle = state.candleBuffer.get(checkIndex);

            log.trace("[SWING] {} Checking for swings at index {} | checkHigh={} checkLow={}",
                    familyId, checkIndex, String.format("%.2f", checkCandle.high), String.format("%.2f", checkCandle.low));

            if (isSwingHigh(state.candleBuffer, checkIndex)) {
                SwingPoint swingHigh = SwingPoint.builder()
                        .scripCode(familyId)
                        .timeframe(family.getTimeframe())
                        .timestamp(checkCandle.timestamp)
                        .type(SwingType.HIGH)
                        .price(checkCandle.high)
                        .volume(checkCandle.volume)
                        .barIndex(state.barIndex)
                        .build();

                // Check for lower high (bearish structure)
                SwingPoint lastHigh = getLastSwingHigh(state);
                boolean isLowerHigh = lastHigh != null && swingHigh.getPrice() < lastHigh.getPrice();

                state.swingPoints.add(swingHigh);
                trimSwingPoints(state);
                state.lastSwingHigh = swingHigh;

                // Generate event
                DetectedEvent.EventType eventType = isLowerHigh ?
                        DetectedEvent.EventType.MOMENTUM_BUILDING : // Use existing type for structure
                        DetectedEvent.EventType.RESISTANCE_BREAK;  // Swing high = potential resistance

                events.add(createSwingEvent(
                        familyId, family.getTimeframe(), checkCandle.high, timestamp,
                        DetectedEvent.EventDirection.BEARISH, // New swing high suggests resistance
                        isLowerHigh ? 0.7 : 0.5,
                        "SWING_HIGH_FORMED",
                        isLowerHigh ? "LOWER_HIGH - bearish structure" : "New swing high formed"
                ));

                log.info("[SWING] {} NEW_SWING_HIGH formed | price={} | lowerHigh={} | prevSwingHigh={} | barIndex={}",
                        familyId, String.format("%.2f", checkCandle.high), isLowerHigh,
                        lastHigh != null ? String.format("%.2f", lastHigh.getPrice()) : "none",
                        state.barIndex);
            }

            if (isSwingLow(state.candleBuffer, checkIndex)) {
                SwingPoint swingLow = SwingPoint.builder()
                        .scripCode(familyId)
                        .timeframe(family.getTimeframe())
                        .timestamp(checkCandle.timestamp)
                        .type(SwingType.LOW)
                        .price(checkCandle.low)
                        .volume(checkCandle.volume)
                        .barIndex(state.barIndex)
                        .build();

                // Check for higher low (bullish structure)
                SwingPoint lastLow = getLastSwingLow(state);
                boolean isHigherLow = lastLow != null && swingLow.getPrice() > lastLow.getPrice();

                state.swingPoints.add(swingLow);
                trimSwingPoints(state);
                state.lastSwingLow = swingLow;

                events.add(createSwingEvent(
                        familyId, family.getTimeframe(), checkCandle.low, timestamp,
                        DetectedEvent.EventDirection.BULLISH, // New swing low suggests support
                        isHigherLow ? 0.7 : 0.5,
                        "SWING_LOW_FORMED",
                        isHigherLow ? "HIGHER_LOW - bullish structure" : "New swing low formed"
                ));

                log.info("[SWING] {} NEW_SWING_LOW formed | price={} | higherLow={} | prevSwingLow={} | barIndex={}",
                        familyId, String.format("%.2f", checkCandle.low), isHigherLow,
                        lastLow != null ? String.format("%.2f", lastLow.getPrice()) : "none",
                        state.barIndex);
            }
        }

        // Check for swing level breaks with current price
        if (state.lastSwingHigh != null && close > state.lastSwingHigh.getPrice()) {
            if (!state.swingHighBroken) {
                state.swingHighBroken = true;
                events.add(createSwingEvent(
                        familyId, family.getTimeframe(), close, timestamp,
                        DetectedEvent.EventDirection.BULLISH,
                        0.75,
                        "SWING_HIGH_BROKEN",
                        String.format("Broke swing high %.2f", state.lastSwingHigh.getPrice())
                ));
                log.info("[SWING] {} SWING_HIGH_BROKEN | price {} > swingHigh {}",
                        familyId, close, state.lastSwingHigh.getPrice());
            }
        } else {
            state.swingHighBroken = false;
        }

        if (state.lastSwingLow != null && close < state.lastSwingLow.getPrice()) {
            if (!state.swingLowBroken) {
                state.swingLowBroken = true;
                events.add(createSwingEvent(
                        familyId, family.getTimeframe(), close, timestamp,
                        DetectedEvent.EventDirection.BEARISH,
                        0.75,
                        "SWING_LOW_BROKEN",
                        String.format("Broke swing low %.2f", state.lastSwingLow.getPrice())
                ));
                log.info("[SWING] {} SWING_LOW_BROKEN | price {} < swingLow {}",
                        familyId, close, state.lastSwingLow.getPrice());
            }
        } else {
            state.swingLowBroken = false;
        }

        state.barIndex++;

        // Clean old swings
        int swingsBeforeClean = state.swingPoints.size();
        cleanOldSwings(state, timestamp);
        int swingsRemoved = swingsBeforeClean - state.swingPoints.size();
        if (swingsRemoved > 0) {
            log.debug("[SWING] {} Cleaned {} expired swing points | remaining={}",
                    familyId, swingsRemoved, state.swingPoints.size());
        }

        // Determine trend structure
        TrendStructure trend = determineTrendStructure(state);

        // Build analysis result
        SwingAnalysis analysis = SwingAnalysis.builder()
                .familyId(familyId)
                .lastSwingHigh(state.lastSwingHigh)
                .lastSwingLow(state.lastSwingLow)
                .recentSwings(getRecentSwings(state, 5))
                .trendStructure(trend)
                .swingHighBroken(state.swingHighBroken)
                .swingLowBroken(state.swingLowBroken)
                .events(events)
                .build();

        long elapsedNanos = System.nanoTime() - startTime;

        // Log summary at debug level (or info if events were detected)
        if (!events.isEmpty()) {
            log.info("[SWING] {} Analysis complete | trend={} | swingHigh={} | swingLow={} | " +
                            "highBroken={} | lowBroken={} | eventsGenerated={} | timeMs={}",
                    familyId, trend,
                    state.lastSwingHigh != null ? String.format("%.2f", state.lastSwingHigh.getPrice()) : "none",
                    state.lastSwingLow != null ? String.format("%.2f", state.lastSwingLow.getPrice()) : "none",
                    state.swingHighBroken, state.swingLowBroken,
                    events.size(), String.format("%.2f", elapsedNanos / 1_000_000.0));
        } else {
            log.debug("[SWING] {} Update complete | trend={} | swingHigh={} | swingLow={} | " +
                            "totalSwings={} | barIndex={} | timeMs={}",
                    familyId, trend,
                    state.lastSwingHigh != null ? String.format("%.2f", state.lastSwingHigh.getPrice()) : "none",
                    state.lastSwingLow != null ? String.format("%.2f", state.lastSwingLow.getPrice()) : "none",
                    state.swingPoints.size(), state.barIndex, String.format("%.2f", elapsedNanos / 1_000_000.0));
        }

        return analysis;
    }

    /**
     * Get recent swing points for a family
     */
    public List<SwingPoint> getRecentSwings(String familyId, int count) {
        SwingState state = stateByFamily.get(familyId);
        if (state == null) {
            log.trace("[SWING] {} getRecentSwings: no state found", familyId);
            return Collections.emptyList();
        }
        List<SwingPoint> swings = getRecentSwings(state, count);
        log.trace("[SWING] {} getRecentSwings: returned {} swings", familyId, swings.size());
        return swings;
    }

    /**
     * Check if a swing high has been broken
     */
    public boolean isSwingHighBroken(String familyId) {
        SwingState state = stateByFamily.get(familyId);
        boolean broken = state != null && state.swingHighBroken;
        log.trace("[SWING] {} isSwingHighBroken={}", familyId, broken);
        return broken;
    }

    /**
     * Check if a swing low has been broken
     */
    public boolean isSwingLowBroken(String familyId) {
        SwingState state = stateByFamily.get(familyId);
        boolean broken = state != null && state.swingLowBroken;
        log.trace("[SWING] {} isSwingLowBroken={}", familyId, broken);
        return broken;
    }

    /**
     * Get the last confirmed swing high
     */
    public SwingPoint getLastSwingHigh(String familyId) {
        SwingState state = stateByFamily.get(familyId);
        SwingPoint swingHigh = state != null ? state.lastSwingHigh : null;
        log.trace("[SWING] {} getLastSwingHigh: {}",
                familyId, swingHigh != null ? String.format("%.2f", swingHigh.getPrice()) : "none");
        return swingHigh;
    }

    /**
     * Get the last confirmed swing low
     */
    public SwingPoint getLastSwingLow(String familyId) {
        SwingState state = stateByFamily.get(familyId);
        SwingPoint swingLow = state != null ? state.lastSwingLow : null;
        log.trace("[SWING] {} getLastSwingLow: {}",
                familyId, swingLow != null ? String.format("%.2f", swingLow.getPrice()) : "none");
        return swingLow;
    }

    /**
     * Get current trend structure (HH+HL, LH+LL, or RANGE)
     */
    public TrendStructure getTrendStructure(String familyId) {
        SwingState state = stateByFamily.get(familyId);
        if (state == null) {
            log.trace("[SWING] {} getTrendStructure: no state found", familyId);
            return TrendStructure.UNKNOWN;
        }
        TrendStructure trend = determineTrendStructure(state);
        log.trace("[SWING] {} getTrendStructure: {}", familyId, trend);
        return trend;
    }

    // ======================== INTERNAL METHODS ========================

    private boolean isSwingHigh(List<CandleData> buffer, int index) {
        if (index < SWING_LOOKBACK || index >= buffer.size() - SWING_LOOKBACK) {
            return false;
        }

        double highAtIndex = buffer.get(index).high;

        // Check if higher than all bars before
        for (int i = index - SWING_LOOKBACK; i < index; i++) {
            if (buffer.get(i).high >= highAtIndex) {
                return false;
            }
        }

        // Check if higher than all bars after
        for (int i = index + 1; i <= index + SWING_LOOKBACK; i++) {
            if (buffer.get(i).high >= highAtIndex) {
                return false;
            }
        }

        return true;
    }

    private boolean isSwingLow(List<CandleData> buffer, int index) {
        if (index < SWING_LOOKBACK || index >= buffer.size() - SWING_LOOKBACK) {
            return false;
        }

        double lowAtIndex = buffer.get(index).low;

        // Check if lower than all bars before
        for (int i = index - SWING_LOOKBACK; i < index; i++) {
            if (buffer.get(i).low <= lowAtIndex) {
                return false;
            }
        }

        // Check if lower than all bars after
        for (int i = index + 1; i <= index + SWING_LOOKBACK; i++) {
            if (buffer.get(i).low <= lowAtIndex) {
                return false;
            }
        }

        return true;
    }

    private SwingPoint getLastSwingHigh(SwingState state) {
        for (int i = state.swingPoints.size() - 1; i >= 0; i--) {
            if (state.swingPoints.get(i).getType() == SwingType.HIGH) {
                return state.swingPoints.get(i);
            }
        }
        return null;
    }

    private SwingPoint getLastSwingLow(SwingState state) {
        for (int i = state.swingPoints.size() - 1; i >= 0; i--) {
            if (state.swingPoints.get(i).getType() == SwingType.LOW) {
                return state.swingPoints.get(i);
            }
        }
        return null;
    }

    private List<SwingPoint> getRecentSwings(SwingState state, int count) {
        int size = state.swingPoints.size();
        int start = Math.max(0, size - count);
        return new ArrayList<>(state.swingPoints.subList(start, size));
    }

    private void trimSwingPoints(SwingState state) {
        while (state.swingPoints.size() > MAX_SWINGS_PER_FAMILY) {
            state.swingPoints.remove(0);
        }
    }

    private void cleanOldSwings(SwingState state, long currentTime) {
        state.swingPoints.removeIf(sp ->
                (currentTime - sp.getTimestamp()) > SWING_VALIDITY_MS);
    }

    private TrendStructure determineTrendStructure(SwingState state) {
        List<SwingPoint> highs = new ArrayList<>();
        List<SwingPoint> lows = new ArrayList<>();

        for (SwingPoint sp : state.swingPoints) {
            if (sp.getType() == SwingType.HIGH) highs.add(sp);
            else lows.add(sp);
        }

        if (highs.size() < 2 || lows.size() < 2) {
            log.trace("[SWING] Insufficient swing points for trend | highs={} | lows={} | need>=2 each",
                    highs.size(), lows.size());
            return TrendStructure.UNKNOWN;
        }

        // Check last two highs and lows
        SwingPoint prevHigh = highs.get(highs.size() - 2);
        SwingPoint lastHigh = highs.get(highs.size() - 1);
        SwingPoint prevLow = lows.get(lows.size() - 2);
        SwingPoint lastLow = lows.get(lows.size() - 1);

        boolean higherHigh = lastHigh.getPrice() > prevHigh.getPrice();
        boolean higherLow = lastLow.getPrice() > prevLow.getPrice();
        boolean lowerHigh = lastHigh.getPrice() < prevHigh.getPrice();
        boolean lowerLow = lastLow.getPrice() < prevLow.getPrice();

        TrendStructure result;
        if (higherHigh && higherLow) {
            result = TrendStructure.UPTREND;
        } else if (lowerHigh && lowerLow) {
            result = TrendStructure.DOWNTREND;
        } else if (lowerHigh && higherLow) {
            result = TrendStructure.CONVERGING;  // Triangle/consolidation
        } else if (higherHigh && lowerLow) {
            result = TrendStructure.EXPANDING;   // Expansion
        } else {
            result = TrendStructure.RANGE;
        }

        log.trace("[SWING] Trend structure analysis | HH={} HL={} LH={} LL={} | " +
                        "prevHigh={} lastHigh={} | prevLow={} lastLow={} | result={}",
                higherHigh, higherLow, lowerHigh, lowerLow,
                String.format("%.2f", prevHigh.getPrice()), String.format("%.2f", lastHigh.getPrice()),
                String.format("%.2f", prevLow.getPrice()), String.format("%.2f", lastLow.getPrice()), result);

        return result;
    }

    private DetectedEvent createSwingEvent(String familyId, String timeframe, double price,
                                            long timestamp, DetectedEvent.EventDirection direction,
                                            double strength, String eventName, String description) {
        return DetectedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .familyId(familyId)
                .timeframe(timeframe)
                .eventType(DetectedEvent.EventType.MOMENTUM_BUILDING) // Map to existing event type
                .category(DetectedEvent.EventCategory.TECHNICAL)
                .detectedAt(Instant.now())
                .priceAtDetection(price)
                .candleTimestamp(timestamp)
                .direction(direction)
                .strength(strength)
                .lifecycle(DetectedEvent.EventLifecycle.DETECTED)
                .confirmationWindowMs(15 * 60 * 1000L)
                .expiresAt(Instant.now().plusSeconds(900))
                .build()
                .withContext("swingEventType", eventName)
                .withContext("description", description);
    }

    private double getPrimaryHigh(FamilyCandle family) {
        if (family.getEquity() != null) return family.getEquity().getHigh();
        if (family.getFuture() != null) return family.getFuture().getHigh();
        return family.getPrimaryPrice();
    }

    private double getPrimaryLow(FamilyCandle family) {
        if (family.getEquity() != null) return family.getEquity().getLow();
        if (family.getFuture() != null) return family.getFuture().getLow();
        return family.getPrimaryPrice();
    }

    private long getPrimaryVolume(FamilyCandle family) {
        if (family.getEquity() != null) return family.getEquity().getVolume();
        if (family.getFuture() != null) return family.getFuture().getVolume();
        return 0;
    }

    // ======================== INNER CLASSES ========================

    private static class SwingState {
        List<CandleData> candleBuffer = new ArrayList<>();
        List<SwingPoint> swingPoints = new ArrayList<>();
        SwingPoint lastSwingHigh;
        SwingPoint lastSwingLow;
        boolean swingHighBroken;
        boolean swingLowBroken;
        int barIndex = 0;
    }

    @Data
    @AllArgsConstructor
    private static class CandleData {
        long timestamp;
        double high;
        double low;
        double close;
        long volume;
    }

    public enum TrendStructure {
        UPTREND,      // Higher highs + higher lows
        DOWNTREND,    // Lower highs + lower lows
        RANGE,        // No clear structure
        CONVERGING,   // Lower highs + higher lows (triangle)
        EXPANDING,    // Higher highs + lower lows (expansion)
        UNKNOWN       // Not enough data
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SwingAnalysis {
        private String familyId;
        private SwingPoint lastSwingHigh;
        private SwingPoint lastSwingLow;
        private List<SwingPoint> recentSwings;
        private TrendStructure trendStructure;
        private boolean swingHighBroken;
        private boolean swingLowBroken;
        private List<DetectedEvent> events;

        public static SwingAnalysis empty() {
            return SwingAnalysis.builder()
                    .recentSwings(Collections.emptyList())
                    .events(Collections.emptyList())
                    .trendStructure(TrendStructure.UNKNOWN)
                    .build();
        }

        public boolean hasSwings() {
            return lastSwingHigh != null || lastSwingLow != null;
        }

        public boolean isUptrend() {
            return trendStructure == TrendStructure.UPTREND;
        }

        public boolean isDowntrend() {
            return trendStructure == TrendStructure.DOWNTREND;
        }

        public boolean isConsolidating() {
            return trendStructure == TrendStructure.CONVERGING ||
                   trendStructure == TrendStructure.RANGE;
        }

        /**
         * Get resistance level from swing high
         */
        public Double getSwingResistance() {
            return lastSwingHigh != null ? lastSwingHigh.getPrice() : null;
        }

        /**
         * Get support level from swing low
         */
        public Double getSwingSupport() {
            return lastSwingLow != null ? lastSwingLow.getPrice() : null;
        }
    }
}
