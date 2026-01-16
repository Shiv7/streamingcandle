package com.kotsin.consumer.enrichment.tracker;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.enrichment.config.CommodityConfig;
import com.kotsin.consumer.enrichment.model.SessionStructure;
import com.kotsin.consumer.enrichment.model.SessionStructure.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SessionStructureTracker - Tracks intraday session structure for context-aware trading
 *
 * THE CRITICAL MISSING PIECE FOR CONTEXT-AWARE SIGNALS:
 *
 * Current problem: OISignalDetector generates signals without knowing WHERE in the session we are.
 * - SHORT_BUILDUP at session LOW = trapped shorts, squeeze fuel (BULLISH)
 * - SHORT_BUILDUP at session HIGH = continuation, shorts in control (BEARISH)
 *
 * This tracker maintains:
 * 1. Session high/low and position within range (positionInRange)
 * 2. Opening range (first 30-45 minutes)
 * 3. Morning high/low (first hour)
 * 4. Level test counts and outcomes
 * 5. V-bottom/top detection
 * 6. Failed breakout/breakdown tracking
 *
 * USAGE:
 * - Call update() with each FamilyCandle
 * - Call getStructure() before interpreting any OI/buildup signal
 * - Use structure.shouldFlipOIInterpretation() to determine if signal meaning is inverted
 * - Use structure.getStructureModifier() to adjust signal confidence
 *
 * MEMORY: Uses ConcurrentHashMap with daily reset (session structures cleared at market open)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionStructureTracker {

    private final CommodityConfig commodityConfig;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // Session structures by familyId
    private final Map<String, SessionStructure> structures = new ConcurrentHashMap<>();

    // Track last reset date to clear structures daily
    private LocalDate lastResetDate = null;

    // Configuration
    private static final double LEVEL_TEST_THRESHOLD_PCT = 0.3;  // Within 0.3% of level = testing
    private static final double EXTREME_THRESHOLD = 0.10;        // Within 10% of high/low = extreme
    private static final int MAX_LEVEL_TESTS_HISTORY = 20;       // Keep last 20 level tests
    private static final double V_PATTERN_MIN_MOVE_PCT = 0.5;    // Minimum move for V-pattern detection

    // NSE market hours
    private static final LocalTime NSE_OPEN = LocalTime.of(9, 15);
    private static final LocalTime NSE_OR_END = LocalTime.of(9, 45);       // Opening range ends
    private static final LocalTime NSE_MORNING_END = LocalTime.of(10, 15); // Morning session ends
    private static final LocalTime NSE_CLOSE = LocalTime.of(15, 30);

    // MCX hours
    private static final LocalTime MCX_OPEN = LocalTime.of(9, 0);
    private static final LocalTime MCX_OR_END = LocalTime.of(9, 30);
    private static final LocalTime MCX_MORNING_END = LocalTime.of(10, 0);

    // Track recent prices for V-pattern detection (per family)
    private final Map<String, LinkedList<PricePoint>> priceHistory = new ConcurrentHashMap<>();
    private static final int PRICE_HISTORY_SIZE = 30;  // Keep last 30 candles

    /**
     * Update session structure with new FamilyCandle
     *
     * @param family FamilyCandle to process
     * @return Updated SessionStructure
     */
    public SessionStructure update(FamilyCandle family) {
        if (family == null || family.getFamilyId() == null) {
            return SessionStructure.empty("UNKNOWN");
        }

        String familyId = family.getFamilyId();
        long timestamp = family.getWindowEndMillis() > 0 ? family.getWindowEndMillis() : System.currentTimeMillis();
        ZonedDateTime zdt = Instant.ofEpochMilli(timestamp).atZone(IST);
        LocalDate today = zdt.toLocalDate();
        LocalTime time = zdt.toLocalTime();

        // Daily reset check
        checkDailyReset(today);

        // Get current price
        InstrumentCandle primary = family.getPrimaryInstrumentOrFallback();
        if (primary == null) {
            return structures.getOrDefault(familyId, SessionStructure.empty(familyId));
        }

        double price = primary.getClose();
        double high = primary.getHigh();
        double low = primary.getLow();
        boolean isCommodity = isCommodityFamily(family);

        // Get or create structure
        SessionStructure structure = structures.computeIfAbsent(familyId,
                id -> SessionStructure.createNew(id, today, price));

        // Check if this is a new session
        if (!today.equals(structure.getSessionDate())) {
            structure = SessionStructure.createNew(familyId, today, price);
            structures.put(familyId, structure);
            clearPriceHistory(familyId);
        }

        // Update price history for pattern detection
        updatePriceHistory(familyId, timestamp, price, high, low);

        // Update session high/low
        double prevHigh = structure.getSessionHigh();
        double prevLow = structure.getSessionLow();

        if (high > structure.getSessionHigh()) {
            structure.setSessionHigh(high);
        }
        if (low < structure.getSessionLow()) {
            structure.setSessionLow(low);
        }

        // Update current price
        structure.setCurrentPrice(price);
        structure.setLastUpdateTimestamp(timestamp);

        // Calculate session range
        double range = structure.getSessionHigh() - structure.getSessionLow();
        structure.setSessionRange(range);
        if (structure.getSessionOpen() > 0) {
            structure.setSessionRangePct(range / structure.getSessionOpen() * 100);
        }

        // Calculate position in range (CRITICAL)
        if (range > 0) {
            double position = (price - structure.getSessionLow()) / range;
            structure.setPositionInRange(Math.max(0, Math.min(1, position)));
        } else {
            structure.setPositionInRange(0.5);  // No range = middle
        }

        // Distance calculations
        if (structure.getSessionHigh() > 0) {
            structure.setDistanceFromHighPct((structure.getSessionHigh() - price) / structure.getSessionHigh() * 100);
        }
        if (structure.getSessionLow() > 0) {
            structure.setDistanceFromLowPct((price - structure.getSessionLow()) / structure.getSessionLow() * 100);
        }

        // Detect extremes
        updateExtremeDetection(structure);

        // Update opening range if not yet established
        updateOpeningRange(structure, time, high, low, isCommodity);

        // Update morning session
        updateMorningSession(structure, time, high, low, isCommodity);

        // Check for level tests
        checkLevelTests(structure, price, prevHigh, prevLow, timestamp);

        // Detect V-patterns
        detectVPatterns(structure, familyId);

        // Detect failed breakouts/breakdowns
        detectFailedBreaks(structure, price, prevHigh, prevLow, familyId);

        // Update structure patterns (higher lows, lower highs)
        updateStructurePatterns(structure, familyId);

        // Log significant changes
        if (structure.isAtSessionExtreme()) {
            log.debug("[SESSION] {} at {} | pos={}% | {}",
                    familyId,
                    structure.getExtremeType(),
                    String.format("%.1f", structure.getPositionInRange() * 100),
                    structure.shouldFlipOIInterpretation() ? "FLIP_OI_INTERPRETATION" : "NORMAL");
        }

        return structure;
    }

    /**
     * Get current session structure for a family
     */
    public SessionStructure getStructure(String familyId) {
        return structures.getOrDefault(familyId, SessionStructure.empty(familyId));
    }

    /**
     * Check if OI interpretation should be flipped for this family
     */
    public boolean shouldFlipOI(String familyId) {
        SessionStructure structure = structures.get(familyId);
        return structure != null && structure.shouldFlipOIInterpretation();
    }

    /**
     * Get position in range for context-aware signal interpretation
     */
    public double getPositionInRange(String familyId) {
        SessionStructure structure = structures.get(familyId);
        return structure != null ? structure.getPositionInRange() : 0.5;
    }

    /**
     * Get signal modifier based on session structure
     */
    public double getStructureModifier(String familyId, boolean isLongSignal) {
        SessionStructure structure = structures.get(familyId);
        return structure != null ? structure.getStructureModifier(isLongSignal) : 1.0;
    }

    // ==================== INTERNAL METHODS ====================

    private void checkDailyReset(LocalDate today) {
        if (lastResetDate == null || !lastResetDate.equals(today)) {
            log.info("[SESSION_TRACKER] Daily reset - clearing session structures for {}", today);
            structures.clear();
            priceHistory.clear();
            lastResetDate = today;
        }
    }

    private boolean isCommodityFamily(FamilyCandle family) {
        if (family.isCommodity()) return true;
        if (family.getFuture() != null && family.getFuture().getExchange() != null) {
            return commodityConfig.isMCXExchange(family.getFuture().getExchange());
        }
        return false;
    }

    private void updateExtremeDetection(SessionStructure structure) {
        double position = structure.getPositionInRange();

        if (position <= EXTREME_THRESHOLD) {
            structure.setAtSessionExtreme(true);
            structure.setExtremeType(ExtremeType.SESSION_LOW);
        } else if (position >= (1 - EXTREME_THRESHOLD)) {
            structure.setAtSessionExtreme(true);
            structure.setExtremeType(ExtremeType.SESSION_HIGH);
        } else {
            structure.setAtSessionExtreme(false);
            structure.setExtremeType(ExtremeType.NONE);
        }

        // Also check OR extremes if established
        if (structure.isOpeningRangeEstablished()) {
            Double orHigh = structure.getOpeningRangeHigh();
            Double orLow = structure.getOpeningRangeLow();
            double price = structure.getCurrentPrice();

            if (orHigh != null && orLow != null) {
                double orRange = orHigh - orLow;
                if (orRange > 0) {
                    double distFromOrHigh = Math.abs(price - orHigh) / orRange;
                    double distFromOrLow = Math.abs(price - orLow) / orRange;

                    if (distFromOrHigh < 0.1) {
                        structure.setExtremeType(ExtremeType.OR_HIGH);
                    } else if (distFromOrLow < 0.1) {
                        structure.setExtremeType(ExtremeType.OR_LOW);
                    }
                }
            }
        }
    }

    private void updateOpeningRange(SessionStructure structure, LocalTime time, double high, double low, boolean isCommodity) {
        if (structure.isOpeningRangeEstablished()) {
            // OR already set - just update position
            updateORPosition(structure);
            return;
        }

        LocalTime orEnd = isCommodity ? MCX_OR_END : NSE_OR_END;

        // During OR formation period
        if (time.isBefore(orEnd)) {
            // Update OR high/low
            if (structure.getOpeningRangeHigh() == null || high > structure.getOpeningRangeHigh()) {
                structure.setOpeningRangeHigh(high);
            }
            if (structure.getOpeningRangeLow() == null || low < structure.getOpeningRangeLow()) {
                structure.setOpeningRangeLow(low);
            }
        } else {
            // OR formation complete
            structure.setOpeningRangeEstablished(true);
            if (structure.getOpeningRangeHigh() != null && structure.getOpeningRangeLow() != null) {
                structure.setOpeningRangeMidpoint(
                        (structure.getOpeningRangeHigh() + structure.getOpeningRangeLow()) / 2);
            }
            updateORPosition(structure);
            log.info("[SESSION] {} Opening Range established: [{} - {}]",
                    structure.getFamilyId(),
                    String.format("%.2f", structure.getOpeningRangeLow()),
                    String.format("%.2f", structure.getOpeningRangeHigh()));
        }
    }

    private void updateORPosition(SessionStructure structure) {
        if (!structure.isOpeningRangeEstablished() ||
                structure.getOpeningRangeHigh() == null ||
                structure.getOpeningRangeLow() == null) {
            structure.setOrPosition(OpeningRangePosition.NOT_ESTABLISHED);
            return;
        }

        double price = structure.getCurrentPrice();
        double orHigh = structure.getOpeningRangeHigh();
        double orLow = structure.getOpeningRangeLow();
        double threshold = (orHigh - orLow) * 0.05;  // 5% of OR range

        if (price > orHigh + threshold) {
            structure.setOrPosition(OpeningRangePosition.ABOVE_OR);
        } else if (price < orLow - threshold) {
            structure.setOrPosition(OpeningRangePosition.BELOW_OR);
        } else if (Math.abs(price - orHigh) <= threshold) {
            structure.setOrPosition(OpeningRangePosition.AT_OR_HIGH);
        } else if (Math.abs(price - orLow) <= threshold) {
            structure.setOrPosition(OpeningRangePosition.AT_OR_LOW);
        } else {
            structure.setOrPosition(OpeningRangePosition.WITHIN_OR);
        }
    }

    private void updateMorningSession(SessionStructure structure, LocalTime time, double high, double low, boolean isCommodity) {
        if (structure.isMorningSessionComplete()) {
            return;
        }

        LocalTime morningEnd = isCommodity ? MCX_MORNING_END : NSE_MORNING_END;

        if (time.isBefore(morningEnd)) {
            // Update morning high/low
            if (structure.getMorningHigh() == null || high > structure.getMorningHigh()) {
                structure.setMorningHigh(high);
            }
            if (structure.getMorningLow() == null || low < structure.getMorningLow()) {
                structure.setMorningLow(low);
            }
        } else {
            // Morning session complete
            structure.setMorningSessionComplete(true);
            log.debug("[SESSION] {} Morning session complete: [{} - {}]",
                    structure.getFamilyId(),
                    String.format("%.2f", structure.getMorningLow()),
                    String.format("%.2f", structure.getMorningHigh()));
        }
    }

    private void checkLevelTests(SessionStructure structure, double price, double prevHigh, double prevLow, long timestamp) {
        double threshold = structure.getSessionOpen() * (LEVEL_TEST_THRESHOLD_PCT / 100);

        // Check session high test
        if (Math.abs(price - structure.getSessionHigh()) <= threshold && price != prevHigh) {
            structure.setSessionHighTestCount(structure.getSessionHighTestCount() + 1);
            recordLevelTest(structure, timestamp, structure.getSessionHigh(),
                    LevelTest.LevelType.SESSION_HIGH, price);
        }

        // Check session low test
        if (Math.abs(price - structure.getSessionLow()) <= threshold && price != prevLow) {
            structure.setSessionLowTestCount(structure.getSessionLowTestCount() + 1);
            recordLevelTest(structure, timestamp, structure.getSessionLow(),
                    LevelTest.LevelType.SESSION_LOW, price);
        }

        // Check OR levels
        if (structure.isOpeningRangeEstablished()) {
            if (structure.getOpeningRangeHigh() != null &&
                    Math.abs(price - structure.getOpeningRangeHigh()) <= threshold) {
                structure.setOrHighTestCount(structure.getOrHighTestCount() + 1);
                recordLevelTest(structure, timestamp, structure.getOpeningRangeHigh(),
                        LevelTest.LevelType.OR_HIGH, price);
            }

            if (structure.getOpeningRangeLow() != null &&
                    Math.abs(price - structure.getOpeningRangeLow()) <= threshold) {
                structure.setOrLowTestCount(structure.getOrLowTestCount() + 1);
                recordLevelTest(structure, timestamp, structure.getOpeningRangeLow(),
                        LevelTest.LevelType.OR_LOW, price);
            }
        }
    }

    private void recordLevelTest(SessionStructure structure, long timestamp, double level,
                                  LevelTest.LevelType levelType, double priceAtTest) {
        LevelTest test = LevelTest.builder()
                .timestamp(timestamp)
                .level(level)
                .levelType(levelType)
                .priceAtTest(priceAtTest)
                .outcome(LevelTest.TestOutcome.TESTING)
                .build();

        List<LevelTest> tests = structure.getRecentLevelTests();
        tests.add(test);

        // Keep only recent tests
        while (tests.size() > MAX_LEVEL_TESTS_HISTORY) {
            tests.remove(0);
        }
    }

    private void updatePriceHistory(String familyId, long timestamp, double close, double high, double low) {
        LinkedList<PricePoint> history = priceHistory.computeIfAbsent(familyId, id -> new LinkedList<>());

        history.addLast(new PricePoint(timestamp, close, high, low));

        while (history.size() > PRICE_HISTORY_SIZE) {
            history.removeFirst();
        }
    }

    private void clearPriceHistory(String familyId) {
        priceHistory.remove(familyId);
    }

    private void detectVPatterns(SessionStructure structure, String familyId) {
        LinkedList<PricePoint> history = priceHistory.get(familyId);
        if (history == null || history.size() < 5) {
            return;
        }

        // Look for V-bottom: sharp drop followed by sharp recovery
        // Find the lowest point in recent history
        PricePoint lowest = null;
        int lowestIdx = -1;
        int idx = 0;
        for (PricePoint point : history) {
            if (lowest == null || point.low < lowest.low) {
                lowest = point;
                lowestIdx = idx;
            }
            idx++;
        }

        if (lowest == null || lowestIdx < 2 || lowestIdx > history.size() - 3) {
            return;  // Need candles on both sides
        }

        // Check if this is near session low
        if (Math.abs(lowest.low - structure.getSessionLow()) / structure.getSessionLow() < 0.005) {
            // Check for V-pattern: significant drop before, significant rise after
            PricePoint before = history.get(lowestIdx - 2);
            PricePoint after = history.getLast();

            double dropPct = (before.close - lowest.low) / before.close * 100;
            double risePct = (after.close - lowest.low) / lowest.low * 100;

            if (dropPct > V_PATTERN_MIN_MOVE_PCT && risePct > V_PATTERN_MIN_MOVE_PCT) {
                if (!structure.isVBottomDetected()) {
                    structure.setVBottomDetected(true);
                    log.info("[SESSION] {} V-BOTTOM detected at session low! drop={}% rise={}%",
                            familyId, String.format("%.2f", dropPct), String.format("%.2f", risePct));
                }
            }
        }

        // Similar logic for V-top (inverted V at session high)
        PricePoint highest = null;
        int highestIdx = -1;
        idx = 0;
        for (PricePoint point : history) {
            if (highest == null || point.high > highest.high) {
                highest = point;
                highestIdx = idx;
            }
            idx++;
        }

        if (highest != null && highestIdx >= 2 && highestIdx <= history.size() - 3) {
            if (Math.abs(highest.high - structure.getSessionHigh()) / structure.getSessionHigh() < 0.005) {
                PricePoint before = history.get(highestIdx - 2);
                PricePoint after = history.getLast();

                double risePct = (highest.high - before.close) / before.close * 100;
                double dropPct = (highest.high - after.close) / highest.high * 100;

                if (risePct > V_PATTERN_MIN_MOVE_PCT && dropPct > V_PATTERN_MIN_MOVE_PCT) {
                    if (!structure.isVTopDetected()) {
                        structure.setVTopDetected(true);
                        log.info("[SESSION] {} V-TOP detected at session high! rise={}% drop={}%",
                                familyId, String.format("%.2f", risePct), String.format("%.2f", dropPct));
                    }
                }
            }
        }
    }

    private void detectFailedBreaks(SessionStructure structure, double price, double prevHigh, double prevLow, String familyId) {
        // Failed breakout: Made new high but closed back below previous high
        if (structure.getSessionHigh() > prevHigh && price < prevHigh) {
            structure.setFailedBreakoutCount(structure.getFailedBreakoutCount() + 1);
            log.info("[SESSION] {} FAILED BREAKOUT #{} - made {} but closed at {}",
                    familyId, structure.getFailedBreakoutCount(),
                    String.format("%.2f", structure.getSessionHigh()),
                    String.format("%.2f", price));
        }

        // Failed breakdown: Made new low but closed back above previous low
        if (structure.getSessionLow() < prevLow && price > prevLow) {
            structure.setFailedBreakdownCount(structure.getFailedBreakdownCount() + 1);
            log.info("[SESSION] {} FAILED BREAKDOWN #{} - made {} but closed at {}",
                    familyId, structure.getFailedBreakdownCount(),
                    String.format("%.2f", structure.getSessionLow()),
                    String.format("%.2f", price));
        }
    }

    private void updateStructurePatterns(SessionStructure structure, String familyId) {
        LinkedList<PricePoint> history = priceHistory.get(familyId);
        if (history == null || history.size() < 6) {
            return;
        }

        // Find local lows (swing lows)
        List<Double> swingLows = new ArrayList<>();
        List<Double> swingHighs = new ArrayList<>();

        for (int i = 2; i < history.size() - 2; i++) {
            PricePoint prev2 = history.get(i - 2);
            PricePoint prev1 = history.get(i - 1);
            PricePoint curr = history.get(i);
            PricePoint next1 = history.get(i + 1);
            PricePoint next2 = history.get(i + 2);

            // Swing low
            if (curr.low < prev1.low && curr.low < prev2.low &&
                    curr.low < next1.low && curr.low < next2.low) {
                swingLows.add(curr.low);
            }

            // Swing high
            if (curr.high > prev1.high && curr.high > prev2.high &&
                    curr.high > next1.high && curr.high > next2.high) {
                swingHighs.add(curr.high);
            }
        }

        // Check for higher lows (uptrend structure)
        if (swingLows.size() >= 2) {
            boolean higherLows = true;
            for (int i = 1; i < swingLows.size(); i++) {
                if (swingLows.get(i) <= swingLows.get(i - 1)) {
                    higherLows = false;
                    break;
                }
            }
            structure.setHigherLowsStructure(higherLows);
        }

        // Check for lower highs (downtrend structure)
        if (swingHighs.size() >= 2) {
            boolean lowerHighs = true;
            for (int i = 1; i < swingHighs.size(); i++) {
                if (swingHighs.get(i) >= swingHighs.get(i - 1)) {
                    lowerHighs = false;
                    break;
                }
            }
            structure.setLowerHighsStructure(lowerHighs);
        }
    }

    // ==================== STATISTICS ====================

    /**
     * Get tracker statistics
     */
    public TrackerStats getStats() {
        int total = structures.size();
        int atExtreme = (int) structures.values().stream()
                .filter(SessionStructure::isAtSessionExtreme).count();
        int vBottoms = (int) structures.values().stream()
                .filter(SessionStructure::isVBottomDetected).count();
        int vTops = (int) structures.values().stream()
                .filter(SessionStructure::isVTopDetected).count();
        int failedBreakouts = structures.values().stream()
                .mapToInt(SessionStructure::getFailedBreakoutCount).sum();

        return new TrackerStats(total, atExtreme, vBottoms, vTops, failedBreakouts);
    }

    public record TrackerStats(
            int totalFamilies,
            int atExtremes,
            int vBottomsDetected,
            int vTopsDetected,
            int totalFailedBreakouts
    ) {
        @Override
        public String toString() {
            return String.format("SessionTracker: %d families, %d at extremes, %d V-bottoms, %d V-tops, %d failed breakouts",
                    totalFamilies, atExtremes, vBottomsDetected, vTopsDetected, totalFailedBreakouts);
        }
    }

    // ==================== HELPER CLASSES ====================

    private record PricePoint(long timestamp, double close, double high, double low) {}
}
