package com.kotsin.consumer.enrichment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * SessionStructure - Tracks intraday session structure for context-aware trading
 *
 * CRITICAL FOR CONTEXT-AWARE SIGNALS:
 * The same OI signal means OPPOSITE at session extremes:
 * - SHORT_BUILDUP at session LOW = squeeze fuel (bullish) - shorts trapped
 * - SHORT_BUILDUP at session HIGH = continuation (bearish) - shorts in control
 *
 * This class tracks:
 * 1. Session range (high/low) and position within range
 * 2. Opening range (first 30-45 minutes)
 * 3. Morning high/low (first hour)
 * 4. Level test counts (how many times price tested a level)
 * 5. Breakout/failure history at key levels
 *
 * USAGE:
 * - Before interpreting any OI signal, check positionInRange
 * - At extreme positions (< 0.1 or > 0.9), flip signal interpretation
 * - Track level tests to identify support/resistance strength
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionStructure {

    // ==================== IDENTITY ====================
    private String familyId;
    private LocalDate sessionDate;
    private long lastUpdateTimestamp;

    // ==================== SESSION RANGE ====================
    /**
     * Session high - highest price reached in current session
     */
    private double sessionHigh;

    /**
     * Session low - lowest price reached in current session
     */
    private double sessionLow;

    /**
     * Session open - opening price of the session
     */
    private double sessionOpen;

    /**
     * Current price
     */
    private double currentPrice;

    /**
     * Position in session range: 0.0 = at session low, 1.0 = at session high
     * CRITICAL for context-aware signal interpretation
     *
     * Formula: (currentPrice - sessionLow) / (sessionHigh - sessionLow)
     */
    private double positionInRange;

    /**
     * Session range in points (high - low)
     */
    private double sessionRange;

    /**
     * Session range as percentage of session open
     */
    private double sessionRangePct;

    // ==================== EXTREME DETECTION ====================
    /**
     * Is price at session extreme? (within 10% of high or low)
     * CRITICAL: Signal interpretation should flip at extremes
     */
    private boolean atSessionExtreme;

    /**
     * Type of extreme if at one
     */
    private ExtremeType extremeType;

    /**
     * Distance from session low as percentage
     */
    private double distanceFromLowPct;

    /**
     * Distance from session high as percentage
     */
    private double distanceFromHighPct;

    // ==================== OPENING RANGE ====================
    /**
     * Opening range high (first 30-45 minutes high)
     * Key breakout level for the day
     */
    private Double openingRangeHigh;

    /**
     * Opening range low (first 30-45 minutes low)
     * Key breakdown level for the day
     */
    private Double openingRangeLow;

    /**
     * Is opening range established? (typically after 9:45-10:00 AM)
     */
    private boolean openingRangeEstablished;

    /**
     * Opening range midpoint - potential reversion target
     */
    private Double openingRangeMidpoint;

    /**
     * Price position relative to opening range
     */
    private OpeningRangePosition orPosition;

    // ==================== MORNING SESSION ====================
    /**
     * Morning high (first hour high)
     */
    private Double morningHigh;

    /**
     * Morning low (first hour low)
     */
    private Double morningLow;

    /**
     * Is morning session complete? (after 10:15 AM)
     */
    private boolean morningSessionComplete;

    // ==================== LEVEL TESTS ====================
    /**
     * Number of times session high has been tested
     */
    private int sessionHighTestCount;

    /**
     * Number of times session low has been tested
     */
    private int sessionLowTestCount;

    /**
     * Number of times opening range high has been tested
     */
    private int orHighTestCount;

    /**
     * Number of times opening range low has been tested
     */
    private int orLowTestCount;

    /**
     * History of level tests (recent tests with outcomes)
     */
    @Builder.Default
    private List<LevelTest> recentLevelTests = new ArrayList<>();

    // ==================== STRUCTURE PATTERNS ====================
    /**
     * Has price made a V-bottom pattern at session low?
     */
    private boolean vBottomDetected;

    /**
     * Has price made an inverted-V (top) at session high?
     */
    private boolean vTopDetected;

    /**
     * Is price in a higher-low structure (uptrend)?
     */
    private boolean higherLowsStructure;

    /**
     * Is price in a lower-high structure (downtrend)?
     */
    private boolean lowerHighsStructure;

    /**
     * Number of failed breakouts at session high
     */
    private int failedBreakoutCount;

    /**
     * Number of failed breakdowns at session low
     */
    private int failedBreakdownCount;

    // ==================== HELPERS ====================

    /**
     * Check if we're at session low zone (bottom 15%)
     * CRITICAL: OI signals should be interpreted as bullish here
     */
    public boolean isAtSessionLow() {
        return positionInRange < 0.15;
    }

    /**
     * Check if we're at session high zone (top 15%)
     * CRITICAL: OI signals should be interpreted as bearish here
     */
    public boolean isAtSessionHigh() {
        return positionInRange > 0.85;
    }

    /**
     * Check if we're in the middle zone (30-70%)
     * Normal signal interpretation applies
     */
    public boolean isInMiddleZone() {
        return positionInRange >= 0.30 && positionInRange <= 0.70;
    }

    /**
     * Get context-aware signal bias based on position
     * Returns: 1.0 for bullish context (at low), -1.0 for bearish context (at high), 0 for neutral
     */
    public double getPositionBias() {
        if (positionInRange < 0.15) return 1.0;   // At low = bullish context
        if (positionInRange < 0.30) return 0.5;   // Near low = slightly bullish
        if (positionInRange > 0.85) return -1.0;  // At high = bearish context
        if (positionInRange > 0.70) return -0.5;  // Near high = slightly bearish
        return 0.0;  // Middle zone = neutral
    }

    /**
     * Should OI interpretation be flipped?
     * At session low: SHORT_BUILDUP = trapped shorts = bullish
     * At session high: LONG_BUILDUP = trapped longs = bearish
     */
    public boolean shouldFlipOIInterpretation() {
        return atSessionExtreme;
    }

    /**
     * Get signal modifier based on structure
     * Boosts signals aligned with structure, reduces counter-structure signals
     */
    public double getStructureModifier(boolean isLongSignal) {
        double modifier = 1.0;

        // At session low, boost long signals, reduce shorts
        if (isAtSessionLow()) {
            modifier = isLongSignal ? 1.3 : 0.6;
        }
        // At session high, boost short signals, reduce longs
        else if (isAtSessionHigh()) {
            modifier = isLongSignal ? 0.6 : 1.3;
        }

        // V-bottom detection boosts longs
        if (vBottomDetected && isLongSignal) {
            modifier *= 1.2;
        }
        // V-top detection boosts shorts
        if (vTopDetected && !isLongSignal) {
            modifier *= 1.2;
        }

        // Failed breakouts suggest reversal
        if (failedBreakoutCount >= 2 && !isLongSignal) {
            modifier *= 1.15;  // Multiple failed breakouts favor shorts
        }
        if (failedBreakdownCount >= 2 && isLongSignal) {
            modifier *= 1.15;  // Multiple failed breakdowns favor longs
        }

        // Level test counts
        if (sessionLowTestCount >= 3 && isLongSignal) {
            modifier *= 1.1;  // Strong support (tested and held)
        }
        if (sessionHighTestCount >= 3 && !isLongSignal) {
            modifier *= 1.1;  // Strong resistance (tested and held)
        }

        return Math.max(0.4, Math.min(1.6, modifier));
    }

    /**
     * Get breakout confidence based on level tests
     * More tests before break = stronger breakout
     */
    public double getBreakoutConfidence(boolean breakingHigh) {
        int testCount = breakingHigh ? sessionHighTestCount : sessionLowTestCount;
        int failedCount = breakingHigh ? failedBreakoutCount : failedBreakdownCount;

        // Base confidence
        double confidence = 0.5;

        // More tests = more confidence (up to 3 tests)
        confidence += Math.min(testCount, 3) * 0.1;

        // Previous failures reduce confidence
        confidence -= failedCount * 0.1;

        // Fresh breakout (first test) is less reliable
        if (testCount == 0) {
            confidence -= 0.2;
        }

        return Math.max(0.2, Math.min(0.9, confidence));
    }

    /**
     * Get position description for logging
     */
    public String getPositionDescription() {
        if (positionInRange < 0.10) return "AT_SESSION_LOW";
        if (positionInRange < 0.25) return "NEAR_SESSION_LOW";
        if (positionInRange > 0.90) return "AT_SESSION_HIGH";
        if (positionInRange > 0.75) return "NEAR_SESSION_HIGH";
        if (positionInRange > 0.45 && positionInRange < 0.55) return "AT_SESSION_MIDDLE";
        if (positionInRange < 0.50) return "LOWER_HALF";
        return "UPPER_HALF";
    }

    /**
     * Get OR position description
     */
    public String getORPositionDescription() {
        if (orPosition == null) return "OR_NOT_SET";
        return orPosition.name();
    }

    // ==================== ENUMS ====================

    public enum ExtremeType {
        SESSION_HIGH,
        SESSION_LOW,
        OR_HIGH,
        OR_LOW,
        NONE
    }

    public enum OpeningRangePosition {
        ABOVE_OR,          // Price above opening range high
        WITHIN_OR,         // Price within opening range
        BELOW_OR,          // Price below opening range low
        AT_OR_HIGH,        // Price testing opening range high
        AT_OR_LOW,         // Price testing opening range low
        NOT_ESTABLISHED    // Opening range not yet set
    }

    // ==================== LEVEL TEST RECORD ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LevelTest {
        private long timestamp;
        private double level;
        private LevelType levelType;
        private TestOutcome outcome;
        private double priceAtTest;
        private double reactionSize;  // How far price moved after test

        public enum LevelType {
            SESSION_HIGH,
            SESSION_LOW,
            OR_HIGH,
            OR_LOW,
            VWAP,
            PIVOT
        }

        public enum TestOutcome {
            HELD,           // Level held, price reversed
            BROKE,          // Level broke
            TESTING,        // Still testing (no clear outcome yet)
            FAILED_BREAK    // Broke but returned (trap)
        }
    }

    // ==================== FACTORY METHODS ====================

    /**
     * Create initial session structure for a new session
     */
    public static SessionStructure createNew(String familyId, LocalDate date, double openPrice) {
        return SessionStructure.builder()
                .familyId(familyId)
                .sessionDate(date)
                .sessionOpen(openPrice)
                .sessionHigh(openPrice)
                .sessionLow(openPrice)
                .currentPrice(openPrice)
                .positionInRange(0.5)
                .sessionRange(0)
                .sessionRangePct(0)
                .atSessionExtreme(false)
                .extremeType(ExtremeType.NONE)
                .openingRangeEstablished(false)
                .morningSessionComplete(false)
                .orPosition(OpeningRangePosition.NOT_ESTABLISHED)
                .recentLevelTests(new ArrayList<>())
                .lastUpdateTimestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * Create empty structure (for missing data scenarios)
     */
    public static SessionStructure empty(String familyId) {
        return SessionStructure.builder()
                .familyId(familyId)
                .positionInRange(0.5)
                .atSessionExtreme(false)
                .extremeType(ExtremeType.NONE)
                .orPosition(OpeningRangePosition.NOT_ESTABLISHED)
                .recentLevelTests(new ArrayList<>())
                .build();
    }

    @Override
    public String toString() {
        return String.format("SessionStructure[%s] pos=%.1f%% (%s) | range=[%.2f-%.2f] | OR=%s | tests: H=%d L=%d",
                familyId,
                positionInRange * 100,
                getPositionDescription(),
                sessionLow,
                sessionHigh,
                getORPositionDescription(),
                sessionHighTestCount,
                sessionLowTestCount);
    }
}
