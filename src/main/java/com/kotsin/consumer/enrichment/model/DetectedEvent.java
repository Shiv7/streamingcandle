package com.kotsin.consumer.enrichment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * DetectedEvent - Represents a significant market event detected by the system
 *
 * Events are the building blocks of patterns. Each event has a lifecycle:
 * DETECTED → PENDING → CONFIRMED/FAILED/EXPIRED
 *
 * Events can be:
 * - Microstructure events (OFI flip, exhaustion, absorption)
 * - Technical events (SuperTrend flip, BB touch)
 * - Options events (OI surge, gamma squeeze setup)
 *
 * The system tracks events to:
 * 1. Match them to patterns (sequences of events)
 * 2. Measure their outcome (for ML training)
 * 3. Generate trading signals when combined
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DetectedEvent {

    // ======================== IDENTITY ========================

    /**
     * Unique event ID (UUID)
     */
    private String eventId;

    /**
     * Family ID where event occurred (e.g., "NATURALGAS", "NIFTY")
     */
    private String familyId;

    /**
     * Timeframe (e.g., "1m", "5m", "15m")
     */
    private String timeframe;

    /**
     * Type of event
     */
    private EventType eventType;

    /**
     * Category of event for grouping
     */
    private EventCategory category;

    // ======================== TIMING ========================

    /**
     * When the event was detected
     */
    private Instant detectedAt;

    /**
     * Price at detection time
     */
    private double priceAtDetection;

    /**
     * Candle timestamp when detected
     */
    private long candleTimestamp;

    /**
     * Window for confirmation (milliseconds)
     */
    private long confirmationWindowMs;

    /**
     * When the event expires if not confirmed
     */
    private Instant expiresAt;

    // ======================== DIRECTION & STRENGTH ========================

    /**
     * Direction of the event (BULLISH, BEARISH, NEUTRAL)
     */
    private EventDirection direction;

    /**
     * Strength of the event (0-1)
     * Higher = more significant
     */
    private double strength;

    /**
     * Z-score if event is statistically based
     */
    private Double zScore;

    /**
     * Percentile rank if applicable
     */
    private Double percentile;

    // ======================== LIFECYCLE ========================

    /**
     * Current lifecycle state
     */
    private EventLifecycle lifecycle;

    /**
     * Confirmation criteria
     */
    private String confirmationCriteria;

    /**
     * Failure criteria
     */
    private String failureCriteria;

    /**
     * If confirmed, what was the outcome?
     */
    private String outcomeDescription;

    /**
     * Price at confirmation/failure
     */
    private Double priceAtOutcome;

    /**
     * Price move from detection to outcome (%)
     */
    private Double priceMovePct;

    /**
     * Time to outcome (milliseconds)
     */
    private Long timeToOutcomeMs;

    // ======================== CONTEXT ========================

    /**
     * Additional context data for this event
     */
    @Builder.Default
    private Map<String, Object> context = new HashMap<>();

    /**
     * Support/resistance level involved (if applicable)
     */
    private Double srLevel;

    /**
     * Is this event at a confluence zone?
     */
    private boolean atConfluence;

    /**
     * Related events (e.g., OFI flip after exhaustion)
     */
    private String relatedEventId;

    // ======================== ENUMS ========================

    /**
     * Event types the system can detect
     */
    public enum EventType {
        // ===== Microstructure Events =====
        /**
         * OFI regime changed sign (negative to positive or vice versa)
         */
        OFI_FLIP,

        /**
         * Selling pressure exhausting (OFI negative but velocity positive)
         */
        SELLING_EXHAUSTION,

        /**
         * Buying pressure exhausting (OFI positive but velocity negative)
         */
        BUYING_EXHAUSTION,

        /**
         * Large buyer absorbing selling pressure (lambda low + depth imbalance + price stable)
         */
        ABSORPTION,

        /**
         * Liquidity withdrawing from book (spread widening, depth reducing)
         */
        LIQUIDITY_WITHDRAWAL,

        /**
         * Informed trading detected (high VPIN spike)
         */
        INFORMED_FLOW,

        /**
         * Volume surge (> 2 stddev)
         */
        VOLUME_SURGE,

        /**
         * Momentum building (consecutive directional candles)
         */
        MOMENTUM_BUILDING,

        // ===== Technical Events =====
        /**
         * SuperTrend indicator flipped direction
         */
        SUPERTREND_FLIP,

        /**
         * Price touched lower Bollinger Band
         */
        BB_LOWER_TOUCH,

        /**
         * Price touched upper Bollinger Band
         */
        BB_UPPER_TOUCH,

        /**
         * Price crossed middle Bollinger Band
         */
        BB_MIDDLE_CROSS,

        /**
         * Bollinger Band squeeze (bands narrowing)
         */
        BB_SQUEEZE,

        /**
         * Price at pivot support level
         */
        PIVOT_SUPPORT_TEST,

        /**
         * Price at pivot resistance level
         */
        PIVOT_RESISTANCE_TEST,

        /**
         * Price broke through resistance
         */
        RESISTANCE_BREAK,

        /**
         * Price broke through support
         */
        SUPPORT_BREAK,

        // ===== Options Events =====
        /**
         * Significant call OI increase (> 2 stddev)
         */
        CALL_OI_SURGE,

        /**
         * Significant put OI increase (> 2 stddev)
         */
        PUT_OI_SURGE,

        /**
         * Call OI declining (unwinding)
         */
        CALL_OI_UNWINDING,

        /**
         * Put OI declining (unwinding)
         */
        PUT_OI_UNWINDING,

        /**
         * Gamma squeeze setup forming
         */
        GAMMA_SQUEEZE_SETUP,

        /**
         * IV spike detected
         */
        IV_SPIKE,

        /**
         * Price approaching max pain level
         */
        MAX_PAIN_CONVERGENCE,

        /**
         * GEX regime changed
         */
        GEX_REGIME_CHANGE,

        // ===== Composite Events =====
        /**
         * Multiple signals aligning (bullish confluence)
         */
        BULLISH_CONFLUENCE,

        /**
         * Multiple signals aligning (bearish confluence)
         */
        BEARISH_CONFLUENCE,

        /**
         * Reversal pattern forming
         */
        REVERSAL_SETUP,

        /**
         * Breakout pattern forming
         */
        BREAKOUT_SETUP,

        // ===== Session Structure Events (Context-Aware) =====
        /**
         * Failed breakout at resistance - price broke above then closed back below.
         * HIGH probability reversal signal (60-80% of breakouts fail).
         * Direction: BEARISH (failed bull breakout = sell signal)
         */
        FAILED_BREAKOUT_BULL,

        /**
         * Failed breakdown at support - price broke below then closed back above.
         * HIGH probability reversal signal (trapped shorts must cover).
         * Direction: BULLISH (failed bear breakdown = buy signal)
         */
        FAILED_BREAKOUT_BEAR,

        /**
         * Reversal detected at session low with multi-instrument confirmation.
         * Requires: position in range < 15% + (exhaustion OR OFI flip) + options confirmation
         * This is the "V-bottom" signal the system was missing.
         */
        SESSION_LOW_REVERSAL,

        /**
         * Reversal detected at session high with multi-instrument confirmation.
         * Requires: position in range > 85% + (exhaustion OR OFI flip) + options confirmation
         * This is the "inverted-V" distribution signal.
         */
        SESSION_HIGH_REVERSAL,

        // ===== Family Confluence Events (Multi-Instrument) =====
        /**
         * All family instruments aligned bullish:
         * - Equity: bullish candle
         * - Futures: long buildup (price up + OI up)
         * - Options: call OI up + put OI down + PCR falling
         * STRONGEST bullish signal when at support or after pullback.
         */
        FAMILY_BULLISH_ALIGNMENT,

        /**
         * All family instruments aligned bearish:
         * - Equity: bearish candle
         * - Futures: short buildup (price down + OI up)
         * - Options: put OI up + call OI down + PCR rising
         * STRONGEST bearish signal when at resistance or after rally.
         */
        FAMILY_BEARISH_ALIGNMENT,

        /**
         * Options flow diverging from price action - potential reversal.
         * Example: Price making new low but call OI surging + put OI dropping.
         * This often precedes V-bottom reversals.
         */
        OPTIONS_PRICE_DIVERGENCE,

        /**
         * Short squeeze fuel accumulating at session lows:
         * - Position in range < 20%
         * - Short buildup in futures (shorts entering at support)
         * - High put/call ratio (fear extreme)
         * When support holds, these shorts become buyers = squeeze.
         */
        SHORT_SQUEEZE_SETUP,

        /**
         * Long squeeze fuel accumulating at session highs:
         * - Position in range > 80%
         * - Long buildup in futures (longs entering at resistance)
         * - Low put/call ratio (greed extreme)
         * When resistance holds, these longs become sellers = dump.
         */
        LONG_SQUEEZE_SETUP
    }

    /**
     * Event categories for grouping and filtering
     */
    public enum EventCategory {
        MICROSTRUCTURE,  // Order flow based
        TECHNICAL,       // Technical indicator based
        OPTIONS,         // Options market based
        COMPOSITE        // Multiple signals combined
    }

    /**
     * Event direction
     */
    public enum EventDirection {
        BULLISH,
        BEARISH,
        NEUTRAL
    }

    /**
     * Event lifecycle states
     */
    public enum EventLifecycle {
        /**
         * Event just detected, awaiting confirmation
         */
        DETECTED,

        /**
         * Event pending confirmation (in confirmation window)
         */
        PENDING,

        /**
         * Event confirmed by price action
         */
        CONFIRMED,

        /**
         * Event failed (opposite price action occurred)
         */
        FAILED,

        /**
         * Event expired (confirmation window passed with no clear outcome)
         */
        EXPIRED
    }

    // ======================== HELPER METHODS ========================

    /**
     * Check if event is still active (pending confirmation)
     */
    public boolean isActive() {
        return lifecycle == EventLifecycle.DETECTED || lifecycle == EventLifecycle.PENDING;
    }

    /**
     * Check if event was successful
     */
    public boolean isSuccessful() {
        return lifecycle == EventLifecycle.CONFIRMED;
    }

    /**
     * Check if event has concluded (confirmed, failed, or expired)
     */
    public boolean isConcluded() {
        return lifecycle == EventLifecycle.CONFIRMED ||
               lifecycle == EventLifecycle.FAILED ||
               lifecycle == EventLifecycle.EXPIRED;
    }

    /**
     * Check if event is bullish
     */
    public boolean isBullish() {
        return direction == EventDirection.BULLISH;
    }

    /**
     * Check if event is bearish
     */
    public boolean isBearish() {
        return direction == EventDirection.BEARISH;
    }

    /**
     * Check if this is a microstructure event
     */
    public boolean isMicrostructure() {
        return category == EventCategory.MICROSTRUCTURE;
    }

    /**
     * Check if this is an options event
     */
    public boolean isOptionsEvent() {
        return category == EventCategory.OPTIONS;
    }

    /**
     * Get time since detection (milliseconds)
     */
    public long getAgeMills() {
        return Instant.now().toEpochMilli() - detectedAt.toEpochMilli();
    }

    /**
     * Check if event is expired
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * Get event description
     */
    public String getDescription() {
        return String.format("%s %s at %.2f (strength: %.2f)",
                direction, eventType, priceAtDetection, strength);
    }

    /**
     * Get short description for logging
     */
    public String toShortString() {
        return String.format("%s[%s] %s @ %.2f",
                eventType.name(), lifecycle.name(), direction, priceAtDetection);
    }

    /**
     * Add context data
     */
    public DetectedEvent withContext(String key, Object value) {
        if (context == null) {
            context = new HashMap<>();
        }
        context.put(key, value);
        return this;
    }

    /**
     * Get context value
     */
    @SuppressWarnings("unchecked")
    public <T> T getContextValue(String key, Class<T> type) {
        if (context == null) return null;
        Object value = context.get(key);
        if (value != null && type.isInstance(value)) {
            return (T) value;
        }
        return null;
    }

    /**
     * Get category for event type
     */
    public static EventCategory getCategoryForType(EventType type) {
        return switch (type) {
            case OFI_FLIP, SELLING_EXHAUSTION, BUYING_EXHAUSTION, ABSORPTION,
                 LIQUIDITY_WITHDRAWAL, INFORMED_FLOW, VOLUME_SURGE, MOMENTUM_BUILDING ->
                    EventCategory.MICROSTRUCTURE;

            case SUPERTREND_FLIP, BB_LOWER_TOUCH, BB_UPPER_TOUCH, BB_MIDDLE_CROSS,
                 BB_SQUEEZE, PIVOT_SUPPORT_TEST, PIVOT_RESISTANCE_TEST,
                 RESISTANCE_BREAK, SUPPORT_BREAK ->
                    EventCategory.TECHNICAL;

            case CALL_OI_SURGE, PUT_OI_SURGE, CALL_OI_UNWINDING, PUT_OI_UNWINDING,
                 GAMMA_SQUEEZE_SETUP, IV_SPIKE, MAX_PAIN_CONVERGENCE, GEX_REGIME_CHANGE ->
                    EventCategory.OPTIONS;

            // Session structure events are composite (use multiple data sources)
            case FAILED_BREAKOUT_BULL, FAILED_BREAKOUT_BEAR,
                 SESSION_LOW_REVERSAL, SESSION_HIGH_REVERSAL ->
                    EventCategory.COMPOSITE;

            // Family alignment events use all instruments
            case FAMILY_BULLISH_ALIGNMENT, FAMILY_BEARISH_ALIGNMENT,
                 OPTIONS_PRICE_DIVERGENCE, SHORT_SQUEEZE_SETUP, LONG_SQUEEZE_SETUP ->
                    EventCategory.COMPOSITE;

            case BULLISH_CONFLUENCE, BEARISH_CONFLUENCE, REVERSAL_SETUP, BREAKOUT_SETUP ->
                    EventCategory.COMPOSITE;
        };
    }

    /**
     * Get default confirmation window for event type (milliseconds)
     */
    public static long getDefaultConfirmationWindow(EventType type) {
        return switch (type) {
            // Quick confirmation events (15 min) - fast-moving signals
            case OFI_FLIP, SUPERTREND_FLIP, BB_MIDDLE_CROSS,
                 FAILED_BREAKOUT_BULL, FAILED_BREAKOUT_BEAR ->  // Failed breakouts resolve quickly
                    15 * 60 * 1000L;

            // Medium confirmation (30 min)
            case SELLING_EXHAUSTION, BUYING_EXHAUSTION, ABSORPTION,
                 BB_LOWER_TOUCH, BB_UPPER_TOUCH, VOLUME_SURGE,
                 CALL_OI_SURGE, PUT_OI_SURGE, GAMMA_SQUEEZE_SETUP,
                 SESSION_LOW_REVERSAL, SESSION_HIGH_REVERSAL,  // Session reversals need time to develop
                 OPTIONS_PRICE_DIVERGENCE ->
                    30 * 60 * 1000L;

            // Longer confirmation (60 min) - structural events
            case PIVOT_SUPPORT_TEST, PIVOT_RESISTANCE_TEST,
                 RESISTANCE_BREAK, SUPPORT_BREAK, MAX_PAIN_CONVERGENCE,
                 FAMILY_BULLISH_ALIGNMENT, FAMILY_BEARISH_ALIGNMENT,  // Multi-instrument alignment
                 SHORT_SQUEEZE_SETUP, LONG_SQUEEZE_SETUP ->  // Squeeze setups need time to build
                    60 * 60 * 1000L;

            // Default (30 min)
            default -> 30 * 60 * 1000L;
        };
    }
}
