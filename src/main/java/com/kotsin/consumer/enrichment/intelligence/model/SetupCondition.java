package com.kotsin.consumer.enrichment.intelligence.model;

import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.model.DetectedEvent;
import com.kotsin.consumer.enrichment.model.GEXProfile;
import com.kotsin.consumer.enrichment.model.MetricContext;
import com.kotsin.consumer.enrichment.model.TimeContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * SetupCondition - A single condition that can be evaluated against market state
 *
 * Conditions can check:
 * - Technical indicators (SuperTrend, BB, price at support/resistance)
 * - Order flow metrics (OFI regime, volume delta, absorption)
 * - Options metrics (GEX regime, OI changes, max pain distance)
 * - Recent events (specific event types detected)
 * - Time context (session quality, DTE)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SetupCondition {

    /**
     * Condition ID for tracking
     */
    private String conditionId;

    /**
     * Human-readable description
     */
    private String description;

    /**
     * Type of condition
     */
    private ConditionType type;

    /**
     * Confidence boost when this condition is met (for booster conditions)
     */
    @Builder.Default
    private double confidenceBoost = 0.05;

    // ======================== CONDITION PARAMETERS ========================

    /**
     * For EVENT_PRESENT: which event types satisfy this condition
     */
    private List<DetectedEvent.EventType> requiredEventTypes;

    /**
     * For EVENT_PRESENT: direction requirement
     */
    private DetectedEvent.EventDirection requiredDirection;

    /**
     * For SUPERTREND_DIRECTION: required direction (true = bullish)
     */
    private Boolean superTrendBullish;

    /**
     * For GEX_REGIME: required regime
     */
    private GEXProfile.GEXRegime requiredGexRegime;

    /**
     * For PRICE_AT_SUPPORT/RESISTANCE: max distance percentage
     */
    @Builder.Default
    private double maxDistancePct = 0.5;

    /**
     * For MIN/MAX thresholds: threshold value
     */
    private Double thresholdValue;

    /**
     * For percentile checks: min percentile
     */
    private Double minPercentile;

    /**
     * For percentile checks: max percentile
     */
    private Double maxPercentile;

    /**
     * For time-based: min session quality
     */
    private Double minSessionQuality;

    /**
     * For OFI regime: required regime string
     */
    private String requiredOfiRegime;

    // ======================== CONDITION TYPES ========================

    public enum ConditionType {
        // Technical conditions
        SUPERTREND_BULLISH,         // SuperTrend direction is bullish
        SUPERTREND_BEARISH,         // SuperTrend direction is bearish
        SUPERTREND_FLIP,            // SuperTrend just flipped
        PRICE_AT_SUPPORT,           // Price near support level
        PRICE_AT_RESISTANCE,        // Price near resistance level
        PRICE_IN_CONFLUENCE,        // Price in confluence zone
        BB_OVERSOLD,                // %B < 0.2
        BB_OVERBOUGHT,              // %B > 0.8
        BB_SQUEEZE,                 // Bollinger bands squeezing

        // Order flow conditions
        OFI_POSITIVE,               // OFI > 0
        OFI_NEGATIVE,               // OFI < 0
        OFI_STRONG_BUY,             // OFI regime strong buy
        OFI_STRONG_SELL,            // OFI regime strong sell
        OFI_FLIP_BULLISH,           // OFI just flipped positive
        OFI_FLIP_BEARISH,           // OFI just flipped negative
        VOLUME_DELTA_POSITIVE,      // Volume delta > 0
        VOLUME_DELTA_NEGATIVE,      // Volume delta < 0
        ABSORPTION_DETECTED,        // Absorption event present

        // Options conditions
        GEX_TRENDING,               // GEX regime is trending (negative GEX)
        GEX_MEAN_REVERTING,         // GEX regime is mean reverting (positive GEX)
        CALL_OI_BUILDING,           // Call OI increasing
        PUT_OI_BUILDING,            // Put OI increasing
        CALL_OI_UNWINDING,          // Call OI decreasing
        PUT_OI_UNWINDING,           // Put OI decreasing
        NEAR_MAX_PAIN,              // Price within 1% of max pain
        GAMMA_SQUEEZE_SETUP,        // Gamma squeeze conditions present

        // Event conditions
        EVENT_PRESENT,              // Specific event type present
        NO_INVALIDATION_EVENTS,     // No invalidation events present

        // Time conditions
        PRIME_SESSION,              // Session quality > 0.7
        AVOID_LUNCH,                // Not in lunch session
        MIN_DTE,                    // Minimum days to expiry

        // Multi-timeframe
        HTF_ALIGNED,                // Higher timeframe confirms direction
        LTF_MOMENTUM                // Lower timeframe has momentum
    }

    // ======================== EVALUATION ========================

    /**
     * Evaluate this condition against current market state
     */
    public boolean evaluate(EnrichedQuantScore quantScore, List<DetectedEvent> recentEvents) {
        if (quantScore == null) {
            return false;
        }

        return switch (type) {
            // Technical conditions
            case SUPERTREND_BULLISH -> evaluateSuperTrendBullish(quantScore);
            case SUPERTREND_BEARISH -> evaluateSuperTrendBearish(quantScore);
            case SUPERTREND_FLIP -> evaluateSuperTrendFlip(quantScore);
            case PRICE_AT_SUPPORT -> evaluatePriceAtSupport(quantScore);
            case PRICE_AT_RESISTANCE -> evaluatePriceAtResistance(quantScore);
            case PRICE_IN_CONFLUENCE -> evaluatePriceInConfluence(quantScore);
            case BB_OVERSOLD -> evaluateBbOversold(quantScore);
            case BB_OVERBOUGHT -> evaluateBbOverbought(quantScore);
            case BB_SQUEEZE -> evaluateBbSqueeze(quantScore);

            // Order flow conditions
            case OFI_POSITIVE -> evaluateOfiPositive(quantScore);
            case OFI_NEGATIVE -> evaluateOfiNegative(quantScore);
            case OFI_STRONG_BUY -> evaluateOfiStrongBuy(quantScore);
            case OFI_STRONG_SELL -> evaluateOfiStrongSell(quantScore);
            case OFI_FLIP_BULLISH -> evaluateEventPresent(recentEvents, DetectedEvent.EventType.OFI_FLIP, DetectedEvent.EventDirection.BULLISH);
            case OFI_FLIP_BEARISH -> evaluateEventPresent(recentEvents, DetectedEvent.EventType.OFI_FLIP, DetectedEvent.EventDirection.BEARISH);
            case VOLUME_DELTA_POSITIVE -> evaluateVolumeDeltaPositive(quantScore);
            case VOLUME_DELTA_NEGATIVE -> evaluateVolumeDeltaNegative(quantScore);
            case ABSORPTION_DETECTED -> evaluateEventPresent(recentEvents, DetectedEvent.EventType.ABSORPTION, null);

            // Options conditions
            case GEX_TRENDING -> evaluateGexTrending(quantScore);
            case GEX_MEAN_REVERTING -> evaluateGexMeanReverting(quantScore);
            case CALL_OI_BUILDING -> evaluateEventPresent(recentEvents, DetectedEvent.EventType.CALL_OI_SURGE, null);
            case PUT_OI_BUILDING -> evaluateEventPresent(recentEvents, DetectedEvent.EventType.PUT_OI_SURGE, null);
            case CALL_OI_UNWINDING -> evaluateEventPresent(recentEvents, DetectedEvent.EventType.CALL_OI_UNWINDING, null);
            case PUT_OI_UNWINDING -> evaluateEventPresent(recentEvents, DetectedEvent.EventType.PUT_OI_UNWINDING, null);
            case NEAR_MAX_PAIN -> evaluateNearMaxPain(quantScore);
            case GAMMA_SQUEEZE_SETUP -> evaluateEventPresent(recentEvents, DetectedEvent.EventType.GAMMA_SQUEEZE_SETUP, null);

            // Event conditions
            case EVENT_PRESENT -> evaluateEventsPresent(recentEvents);
            case NO_INVALIDATION_EVENTS -> true; // Default to true, specific logic elsewhere

            // Time conditions
            case PRIME_SESSION -> evaluatePrimeSession(quantScore);
            case AVOID_LUNCH -> evaluateAvoidLunch(quantScore);
            case MIN_DTE -> evaluateMinDte(quantScore);

            // Multi-timeframe - NOW PROPERLY IMPLEMENTED
            case HTF_ALIGNED -> evaluateHtfAligned(quantScore);
            case LTF_MOMENTUM -> evaluateLtfMomentum(quantScore);
        };
    }

    // ======================== EVALUATION HELPERS ========================

    /**
     * Evaluate SuperTrend bullish using MTF analysis
     * For SWING trades: Uses MTF aggregated direction (15m+ timeframes)
     * Falls back to single TF if MTF not available
     */
    private boolean evaluateSuperTrendBullish(EnrichedQuantScore qs) {
        if (qs.getTechnicalContext() == null) return false;

        // Use MTF analysis for better accuracy
        // MTF considers all timeframes with weighted consensus
        if (qs.getTechnicalContext().getMtfAggregatedDirection() != null) {
            return qs.getTechnicalContext().isMtfSupportingLong();
        }

        // Fallback to single timeframe if MTF not available
        return qs.getTechnicalContext().isSuperTrendBullish();
    }

    /**
     * Evaluate SuperTrend bearish using MTF analysis
     * For SWING trades: Uses MTF aggregated direction (15m+ timeframes)
     * Falls back to single TF if MTF not available
     */
    private boolean evaluateSuperTrendBearish(EnrichedQuantScore qs) {
        if (qs.getTechnicalContext() == null) return false;

        // Use MTF analysis for better accuracy
        if (qs.getTechnicalContext().getMtfAggregatedDirection() != null) {
            return qs.getTechnicalContext().isMtfSupportingShort();
        }

        // Fallback to single timeframe if MTF not available
        return !qs.getTechnicalContext().isSuperTrendBullish();
    }

    /**
     * Evaluate if Higher TimeFrames (15m+) are aligned with the setup direction
     * Used for SWING and POSITION trades
     */
    private boolean evaluateHtfAligned(EnrichedQuantScore qs) {
        if (qs.getTechnicalContext() == null) return false;

        // Check if HTF bullish matches what we need for this condition
        // If superTrendBullish is set in condition, check for bullish alignment
        // Otherwise check for bearish alignment
        if (superTrendBullish != null) {
            return superTrendBullish
                    ? qs.getTechnicalContext().isMtfSupportingLong()
                    : qs.getTechnicalContext().isMtfSupportingShort();
        }

        // Default: return true if no conflict in MTF
        return !qs.getTechnicalContext().isMtfHasConflict();
    }

    /**
     * Evaluate if Lower TimeFrames show momentum in the setup direction
     * Used for entry timing after HTF alignment
     */
    private boolean evaluateLtfMomentum(EnrichedQuantScore qs) {
        if (qs.getTechnicalContext() == null) return false;

        // LTF momentum is current TF SuperTrend + recent price action
        // For now, check if current TF SuperTrend matches expected direction
        boolean currentTfBullish = qs.getTechnicalContext().isSuperTrendBullish();

        if (superTrendBullish != null) {
            return superTrendBullish == currentTfBullish;
        }

        // Default: return true (momentum is present)
        return true;
    }

    private boolean evaluateSuperTrendFlip(EnrichedQuantScore qs) {
        return qs.getTechnicalContext() != null && qs.getTechnicalContext().isSuperTrendFlip();
    }

    private boolean evaluatePriceAtSupport(EnrichedQuantScore qs) {
        if (qs.getConfluenceResult() == null) return false;
        Double supportDist = qs.getConfluenceResult().getSupportDistance();
        return supportDist != null && supportDist < maxDistancePct;
    }

    private boolean evaluatePriceAtResistance(EnrichedQuantScore qs) {
        if (qs.getConfluenceResult() == null) return false;
        Double resistDist = qs.getConfluenceResult().getResistanceDistance();
        return resistDist != null && resistDist < maxDistancePct;
    }

    private boolean evaluatePriceInConfluence(EnrichedQuantScore qs) {
        return qs.getConfluenceResult() != null && qs.getConfluenceResult().isInConfluenceZone();
    }

    private boolean evaluateBbOversold(EnrichedQuantScore qs) {
        Double percentB = qs.getBbPercentB();
        return percentB != null && percentB < 0.2;
    }

    private boolean evaluateBbOverbought(EnrichedQuantScore qs) {
        Double percentB = qs.getBbPercentB();
        return percentB != null && percentB > 0.8;
    }

    private boolean evaluateBbSqueeze(EnrichedQuantScore qs) {
        return qs.isBbSqueezing();
    }

    private boolean evaluateOfiPositive(EnrichedQuantScore qs) {
        if (qs.getHistoricalContext() == null || qs.getHistoricalContext().getOfiContext() == null) return false;
        return qs.getHistoricalContext().getOfiContext().getCurrentValue() > 0;
    }

    private boolean evaluateOfiNegative(EnrichedQuantScore qs) {
        if (qs.getHistoricalContext() == null || qs.getHistoricalContext().getOfiContext() == null) return false;
        return qs.getHistoricalContext().getOfiContext().getCurrentValue() < 0;
    }

    private boolean evaluateOfiStrongBuy(EnrichedQuantScore qs) {
        if (qs.getHistoricalContext() == null || qs.getHistoricalContext().getOfiContext() == null) return false;
        MetricContext.MetricRegime regime = qs.getHistoricalContext().getOfiContext().getRegime();
        return regime == MetricContext.MetricRegime.STRONG_POSITIVE || regime == MetricContext.MetricRegime.POSITIVE;
    }

    private boolean evaluateOfiStrongSell(EnrichedQuantScore qs) {
        if (qs.getHistoricalContext() == null || qs.getHistoricalContext().getOfiContext() == null) return false;
        MetricContext.MetricRegime regime = qs.getHistoricalContext().getOfiContext().getRegime();
        return regime == MetricContext.MetricRegime.STRONG_NEGATIVE || regime == MetricContext.MetricRegime.NEGATIVE;
    }

    private boolean evaluateVolumeDeltaPositive(EnrichedQuantScore qs) {
        if (qs.getHistoricalContext() == null || qs.getHistoricalContext().getVolumeDeltaContext() == null) return false;
        return qs.getHistoricalContext().getVolumeDeltaContext().getCurrentValue() > 0;
    }

    private boolean evaluateVolumeDeltaNegative(EnrichedQuantScore qs) {
        if (qs.getHistoricalContext() == null || qs.getHistoricalContext().getVolumeDeltaContext() == null) return false;
        return qs.getHistoricalContext().getVolumeDeltaContext().getCurrentValue() < 0;
    }

    private boolean evaluateGexTrending(EnrichedQuantScore qs) {
        GEXProfile.GEXRegime regime = qs.getGexRegime();
        return regime == GEXProfile.GEXRegime.TRENDING;
    }

    private boolean evaluateGexMeanReverting(EnrichedQuantScore qs) {
        GEXProfile.GEXRegime regime = qs.getGexRegime();
        return regime == GEXProfile.GEXRegime.MEAN_REVERTING;
    }

    private boolean evaluateNearMaxPain(EnrichedQuantScore qs) {
        if (qs.getMaxPainProfile() == null) return false;
        double distance = qs.getMaxPainProfile().getDistanceToMaxPainPct();
        return Math.abs(distance) < 1.0; // Within 1%
    }

    private boolean evaluatePrimeSession(EnrichedQuantScore qs) {
        if (qs.getTimeContext() == null) return false;
        double quality = minSessionQuality != null ? minSessionQuality : 0.7;
        return qs.getTimeContext().getSessionQuality() >= quality;
    }

    private boolean evaluateAvoidLunch(EnrichedQuantScore qs) {
        if (qs.getTimeContext() == null) return true;
        TimeContext.Session session = qs.getTimeContext().getSession();
        return session != TimeContext.Session.LUNCH_CHOP;
    }

    private boolean evaluateMinDte(EnrichedQuantScore qs) {
        if (qs.getExpiryContext() == null) return true;
        double minDte = thresholdValue != null ? thresholdValue : 1.0;
        return qs.getExpiryContext().getDaysToExpiry() >= minDte;
    }

    private boolean evaluateEventPresent(List<DetectedEvent> events, DetectedEvent.EventType eventType,
                                          DetectedEvent.EventDirection direction) {
        if (events == null || events.isEmpty()) return false;
        return events.stream().anyMatch(e ->
                e.getEventType() == eventType &&
                        (direction == null || e.getDirection() == direction));
    }

    private boolean evaluateEventsPresent(List<DetectedEvent> events) {
        if (requiredEventTypes == null || requiredEventTypes.isEmpty()) return true;
        if (events == null || events.isEmpty()) return false;

        return requiredEventTypes.stream().anyMatch(reqType ->
                events.stream().anyMatch(e ->
                        e.getEventType() == reqType &&
                                (requiredDirection == null || e.getDirection() == requiredDirection)));
    }

    // ======================== FACTORY METHODS ========================

    public static SetupCondition superTrendBullish() {
        return SetupCondition.builder()
                .conditionId("ST_BULLISH")
                .description("SuperTrend is bullish")
                .type(ConditionType.SUPERTREND_BULLISH)
                .build();
    }

    public static SetupCondition superTrendBearish() {
        return SetupCondition.builder()
                .conditionId("ST_BEARISH")
                .description("SuperTrend is bearish")
                .type(ConditionType.SUPERTREND_BEARISH)
                .build();
    }

    public static SetupCondition priceAtSupport(double maxDistance) {
        return SetupCondition.builder()
                .conditionId("PRICE_SUPPORT")
                .description("Price at support level")
                .type(ConditionType.PRICE_AT_SUPPORT)
                .maxDistancePct(maxDistance)
                .build();
    }

    public static SetupCondition priceAtResistance(double maxDistance) {
        return SetupCondition.builder()
                .conditionId("PRICE_RESISTANCE")
                .description("Price at resistance level")
                .type(ConditionType.PRICE_AT_RESISTANCE)
                .maxDistancePct(maxDistance)
                .build();
    }

    public static SetupCondition ofiPositive() {
        return SetupCondition.builder()
                .conditionId("OFI_POS")
                .description("Order flow imbalance positive")
                .type(ConditionType.OFI_POSITIVE)
                .build();
    }

    public static SetupCondition ofiNegative() {
        return SetupCondition.builder()
                .conditionId("OFI_NEG")
                .description("Order flow imbalance negative")
                .type(ConditionType.OFI_NEGATIVE)
                .build();
    }

    public static SetupCondition volumeDeltaPositive() {
        return SetupCondition.builder()
                .conditionId("VOL_DELTA_POS")
                .description("Volume delta positive")
                .type(ConditionType.VOLUME_DELTA_POSITIVE)
                .build();
    }

    public static SetupCondition volumeDeltaNegative() {
        return SetupCondition.builder()
                .conditionId("VOL_DELTA_NEG")
                .description("Volume delta negative")
                .type(ConditionType.VOLUME_DELTA_NEGATIVE)
                .build();
    }

    public static SetupCondition gexTrending() {
        return SetupCondition.builder()
                .conditionId("GEX_TREND")
                .description("GEX regime is trending")
                .type(ConditionType.GEX_TRENDING)
                .confidenceBoost(0.08)
                .build();
    }

    public static SetupCondition primeSession() {
        return SetupCondition.builder()
                .conditionId("PRIME_SESSION")
                .description("In prime trading session")
                .type(ConditionType.PRIME_SESSION)
                .minSessionQuality(0.7)
                .confidenceBoost(0.05)
                .build();
    }

    @Override
    public String toString() {
        return String.format("%s: %s", conditionId != null ? conditionId : type.name(), description);
    }
}
