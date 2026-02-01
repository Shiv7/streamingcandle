package com.kotsin.consumer.signal.calculator;

import com.kotsin.consumer.logging.TraceContext;
import com.kotsin.consumer.model.OIMetrics;
import com.kotsin.consumer.model.StrategyState.*;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.signal.model.FudkiiScore;
import com.kotsin.consumer.signal.model.FudkiiScore.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * FudkiiCalculator - Composite FUDKII signal score calculator.
 *
 * FUDKII = Flow + Urgency + Direction + Kyle + Imbalance + Intensity
 *
 * Combines all strategy components into a single actionable score.
 * Determines WATCH setups and ACTIVE triggers.
 */
@Component
@Slf4j
public class FudkiiCalculator {

    private static final String LOG_PREFIX = "[FUDKII]";

    // Thresholds for signal states
    @Value("${signal.watch.threshold:40}")
    private double watchThreshold;

    @Value("${signal.active.threshold:60}")
    private double activeThreshold;

    @Value("${signal.confidence.min:0.5}")
    private double minConfidence;

    // Component weights
    private final FudkiiWeights weights;

    public FudkiiCalculator() {
        this.weights = FudkiiWeights.builder()
            .flowWeight(0.15)
            .urgencyWeight(0.20)
            .directionWeight(0.25)
            .kyleWeight(0.10)
            .imbalanceWeight(0.15)
            .intensityWeight(0.15)
            .build();
    }

    /**
     * Calculate complete FUDKII score from all inputs.
     *
     * @param candle Current unified candle
     * @param history Recent candles for context
     * @param vcpState VCP state (clusters, runway)
     * @param ipuState IPU state (scores, momentum)
     * @param pivotState Pivot state (structure, levels)
     * @return Complete FudkiiScore
     */
    public FudkiiScore calculate(
            UnifiedCandle candle,
            List<UnifiedCandle> history,
            VcpState vcpState,
            IpuState ipuState,
            PivotState pivotState) {

        TraceContext.addStage("FUDKII");

        if (candle == null) {
            log.debug("{} {} No candle provided, returning empty score",
                LOG_PREFIX, TraceContext.getShortPrefix());
            return createEmptyScore();
        }

        String symbol = candle.getSymbol();
        String scripCode = candle.getScripCode();
        String timeframe = "5m"; // Default, should come from context

        log.debug("{} {} Calculating FUDKII for price={}",
            LOG_PREFIX, TraceContext.getShortPrefix(),
            String.format("%.2f", candle.getClose()));

        FudkiiScore.FudkiiScoreBuilder builder = FudkiiScore.builder()
            .symbol(symbol)
            .scripCode(scripCode)
            .timeframe(timeframe)
            .timestamp(candle.getTimestamp());

        // ==================== F - FLOW (OFI) ====================
        FlowResult flow = calculateFlow(candle, history);
        builder.flowScore(flow.score)
               .flowMomentum(flow.momentum)
               .rawOfi(flow.rawOfi);

        // ==================== U - URGENCY (IPU) ====================
        UrgencyResult urgency = calculateUrgency(candle, ipuState);
        builder.urgencyScore(urgency.score)
               .urgencyBias(urgency.bias)
               .exhaustion(urgency.exhaustion)
               .momentumState(urgency.momentumState);

        // ==================== D - DIRECTION (VCP + Structure) ====================
        DirectionResult direction = calculateDirection(candle, vcpState, pivotState);
        builder.directionScore(direction.score)
               .bullishRunway(direction.bullishRunway)
               .bearishRunway(direction.bearishRunway)
               .structure(direction.structure);

        // ==================== K - KYLE (Price Impact) ====================
        KyleResult kyle = calculateKyle(candle, history);
        builder.kyleLambda(kyle.lambda)
               .kyleScore(kyle.score)
               .micropriceDeviation(kyle.micropriceDeviation);

        // ==================== I - IMBALANCE (Volume) ====================
        ImbalanceResult imbalance = calculateImbalance(candle);
        builder.imbalanceScore(imbalance.score)
               .vibTriggered(imbalance.vibTriggered)
               .dibTriggered(imbalance.dibTriggered)
               .trbTriggered(imbalance.trbTriggered)
               .buyPressure(imbalance.buyPressure)
               .sellPressure(imbalance.sellPressure);

        // ==================== I - INTENSITY (OI) ====================
        IntensityResult intensity = calculateIntensity(candle);
        builder.intensityScore(intensity.score)
               .oiInterpretation(intensity.interpretation)
               .oiChangePercent(intensity.oiChangePercent)
               .suggestsReversal(intensity.suggestsReversal);

        // ==================== COMPOSITE SCORES ====================
        double compositeScore = FudkiiScore.calculateComposite(
            flow.score, urgency.score, direction.score,
            kyle.score, imbalance.score, intensity.score, weights);

        double compositeBias = FudkiiScore.calculateBias(
            flow.score, urgency.score, urgency.bias,
            direction.score, imbalance.score);

        SignalStrength strength = SignalStrength.fromScore(compositeScore);
        Direction dir = determineDirection(compositeBias);
        double confidence = calculateConfidence(compositeScore, compositeBias,
            flow, urgency, direction, imbalance, intensity);

        builder.compositeScore(compositeScore)
               .compositeBias(compositeBias)
               .strength(strength)
               .direction(dir)
               .confidence(confidence);

        // ==================== TRIGGER CONDITIONS ====================
        boolean isWatch = isWatchSetup(compositeScore, confidence, urgency, direction);
        boolean isActive = isActiveTrigger(compositeScore, confidence, imbalance, pivotState, candle);
        String reason = buildReason(isWatch, isActive, strength, dir, compositeScore);

        builder.isWatchSetup(isWatch)
               .isActiveTrigger(isActive)
               .reason(reason);

        // Log FUDKII result
        if (isActive) {
            log.info("{} {} ACTIVE TRIGGER: direction={}, score={}, confidence={}, F={}, U={}, D={}, K={}, I1={}, I2={}",
                LOG_PREFIX, TraceContext.getShortPrefix(),
                dir, String.format("%.1f", compositeScore), String.format("%.2f", confidence),
                String.format("%.2f", flow.score), String.format("%.2f", urgency.score),
                String.format("%.2f", direction.score), String.format("%.2f", kyle.score),
                String.format("%.2f", imbalance.score), String.format("%.2f", intensity.score));
        } else if (isWatch) {
            log.info("{} {} WATCH SETUP: direction={}, score={}, confidence={}",
                LOG_PREFIX, TraceContext.getShortPrefix(),
                dir, String.format("%.1f", compositeScore), String.format("%.2f", confidence));
        } else {
            log.debug("{} {} IDLE: direction={}, score={}, confidence={}, bias={}",
                LOG_PREFIX, TraceContext.getShortPrefix(),
                dir, String.format("%.1f", compositeScore), String.format("%.2f", confidence),
                String.format("%.2f", compositeBias));
        }

        return builder.build();
    }

    // ==================== FLOW CALCULATION ====================

    private FlowResult calculateFlow(UnifiedCandle candle, List<UnifiedCandle> history) {
        FlowResult result = new FlowResult();

        if (!candle.isHasOrderbook() || candle.getOfi() == null) {
            return result;
        }

        double ofi = candle.getOfi();
        result.rawOfi = ofi;

        // Normalize OFI to -1 to +1
        double avgOfi = getAverageOfi(history);
        double stdOfi = getStdDevOfi(history, avgOfi);

        if (stdOfi > 0) {
            result.score = Math.max(-1, Math.min(1, ofi / (stdOfi * 2)));
        }

        // OFI momentum
        if (history != null && !history.isEmpty()) {
            UnifiedCandle prev = history.get(0);
            double prevOfi = (prev != null && prev.isHasOrderbook() && prev.getOfi() != null) ?
                prev.getOfi() : 0;
            result.momentum = ofi - prevOfi;
        }

        return result;
    }

    // ==================== URGENCY CALCULATION ====================

    private UrgencyResult calculateUrgency(UnifiedCandle candle, IpuState ipuState) {
        UrgencyResult result = new UrgencyResult();

        if (ipuState == null) {
            return result;
        }

        result.score = ipuState.getCurrentIpuScore();
        result.exhaustion = ipuState.getCurrentExhaustion();

        // Determine bias from direction
        String dir = ipuState.getCurrentDirection();
        result.bias = switch (dir) {
            case "BULLISH" -> 1.0;
            case "BEARISH" -> -1.0;
            default -> 0.0;
        };

        // Momentum state
        result.momentumState = switch (ipuState.getCurrentMomentumState()) {
            case "ACCELERATING" -> MomentumState.ACCELERATING;
            case "DECELERATING" -> MomentumState.DECELERATING;
            case "EXHAUSTED" -> MomentumState.EXHAUSTED;
            case "REVERSING" -> MomentumState.REVERSING;
            default -> MomentumState.STEADY;
        };

        return result;
    }

    // ==================== DIRECTION CALCULATION ====================

    private DirectionResult calculateDirection(UnifiedCandle candle, VcpState vcpState, PivotState pivotState) {
        DirectionResult result = new DirectionResult();

        if (vcpState != null) {
            result.bullishRunway = vcpState.getBullishRunway();
            result.bearishRunway = vcpState.getBearishRunway();

            // Net direction from runway difference
            result.score = result.bullishRunway - result.bearishRunway;
        }

        if (pivotState != null) {
            result.structure = switch (pivotState.getStructure()) {
                case "UPTREND" -> MarketStructure.UPTREND;
                case "DOWNTREND" -> MarketStructure.DOWNTREND;
                case "CONSOLIDATION" -> MarketStructure.CONSOLIDATION;
                case "RANGE" -> MarketStructure.RANGE;
                default -> MarketStructure.RANGE;
            };

            // Boost direction score based on structure
            if (result.structure == MarketStructure.UPTREND) {
                result.score = Math.max(result.score, 0.3);
            } else if (result.structure == MarketStructure.DOWNTREND) {
                result.score = Math.min(result.score, -0.3);
            }
        }

        return result;
    }

    // ==================== KYLE CALCULATION ====================

    private KyleResult calculateKyle(UnifiedCandle candle, List<UnifiedCandle> history) {
        KyleResult result = new KyleResult();

        if (!candle.isHasOrderbook() || candle.getKyleLambda() == null) {
            return result;
        }

        result.lambda = candle.getKyleLambda();

        // Microprice deviation
        Double microprice = candle.getMicroprice();
        if (microprice != null) {
            double midPrice = (candle.getHigh() + candle.getLow()) / 2;
            if (midPrice > 0) {
                result.micropriceDeviation = (microprice - midPrice) / midPrice;
            }
        }

        // Kyle score: lower lambda = better liquidity = higher score
        double avgLambda = getAverageKyleLambda(history);
        if (avgLambda > 0) {
            result.score = Math.max(0, 1 - (result.lambda / (avgLambda * 2)));
        } else {
            result.score = 0.5; // Default
        }

        return result;
    }

    // ==================== IMBALANCE CALCULATION ====================

    private ImbalanceResult calculateImbalance(UnifiedCandle candle) {
        ImbalanceResult result = new ImbalanceResult();

        result.buyPressure = candle.getBuyPressure();
        result.sellPressure = candle.getSellPressure();

        // Imbalance score: -1 (sell) to +1 (buy)
        result.score = result.buyPressure - result.sellPressure;

        // Trigger flags
        result.vibTriggered = candle.isVibTriggered();
        result.dibTriggered = candle.isDibTriggered();
        // Note: trbTriggered is not in UnifiedCandle flat structure, default to false

        return result;
    }

    // ==================== INTENSITY CALCULATION ====================

    private IntensityResult calculateIntensity(UnifiedCandle candle) {
        IntensityResult result = new IntensityResult();

        if (!candle.isHasOI()) {
            result.interpretation = OIInterpretation.NEUTRAL;
            return result;
        }

        Double oiChangePercent = candle.getOiChangePercent();
        result.oiChangePercent = oiChangePercent != null ? oiChangePercent : 0;

        // Map interpretation
        OIMetrics.OIInterpretation candleInterp = candle.getOiInterpretation();
        if (candleInterp != null) {
            result.interpretation = switch (candleInterp) {
                case LONG_BUILDUP -> OIInterpretation.LONG_BUILDUP;
                case SHORT_BUILDUP -> OIInterpretation.SHORT_BUILDUP;
                case LONG_UNWINDING -> OIInterpretation.LONG_UNWINDING;
                case SHORT_COVERING -> OIInterpretation.SHORT_COVERING;
                default -> OIInterpretation.NEUTRAL;
            };
        } else {
            result.interpretation = OIInterpretation.NEUTRAL;
        }

        Boolean suggestsReversal = candle.getOiSuggestsReversal();
        result.suggestsReversal = suggestsReversal != null && suggestsReversal;

        // Intensity score based on OI change magnitude
        result.score = Math.min(1, Math.abs(result.oiChangePercent) / 10.0);

        // Boost for clear interpretations
        if (result.interpretation != OIInterpretation.NEUTRAL) {
            result.score = Math.min(1, result.score * 1.5);
        }

        return result;
    }

    // ==================== COMPOSITE CALCULATIONS ====================

    private Direction determineDirection(double bias) {
        if (bias > 0.2) return Direction.BULLISH;
        if (bias < -0.2) return Direction.BEARISH;
        return Direction.NEUTRAL;
    }

    private double calculateConfidence(
            double compositeScore, double compositeBias,
            FlowResult flow, UrgencyResult urgency,
            DirectionResult direction, ImbalanceResult imbalance,
            IntensityResult intensity) {

        double confidence = 0;

        // Score magnitude contributes
        confidence += (compositeScore / 100) * 0.3;

        // Alignment of components
        int alignedCount = 0;
        boolean bullish = compositeBias > 0;

        if ((flow.score > 0) == bullish) alignedCount++;
        if ((urgency.bias > 0) == bullish) alignedCount++;
        if ((direction.score > 0) == bullish) alignedCount++;
        if ((imbalance.score > 0) == bullish) alignedCount++;
        if (bullish && (intensity.interpretation == OIInterpretation.LONG_BUILDUP ||
                       intensity.interpretation == OIInterpretation.SHORT_COVERING)) alignedCount++;
        if (!bullish && (intensity.interpretation == OIInterpretation.SHORT_BUILDUP ||
                        intensity.interpretation == OIInterpretation.LONG_UNWINDING)) alignedCount++;

        confidence += (alignedCount / 6.0) * 0.4;

        // Momentum state
        if (urgency.momentumState == MomentumState.ACCELERATING) {
            confidence += 0.15;
        } else if (urgency.momentumState == MomentumState.EXHAUSTED) {
            confidence -= 0.1;
        }

        // Imbalance triggers
        if (imbalance.vibTriggered || imbalance.dibTriggered) {
            confidence += 0.15;
        }

        return Math.max(0, Math.min(1, confidence));
    }

    // ==================== TRIGGER DETECTION ====================

    private boolean isWatchSetup(double score, double confidence,
                                  UrgencyResult urgency, DirectionResult direction) {
        // WATCH conditions:
        // 1. Score above watch threshold
        // 2. Confidence above minimum
        // 3. Clear direction (not neutral)
        // 4. Not exhausted

        return score >= watchThreshold &&
               confidence >= minConfidence &&
               Math.abs(direction.score) > 0.1 &&
               urgency.momentumState != MomentumState.EXHAUSTED;
    }

    private boolean isActiveTrigger(double score, double confidence,
                                     ImbalanceResult imbalance,
                                     PivotState pivotState,
                                     UnifiedCandle candle) {
        // ACTIVE conditions:
        // 1. Score above active threshold
        // 2. High confidence
        // 3. Imbalance confirmation (VIB or DIB)
        // 4. Break of structure or level

        if (score < activeThreshold || confidence < 0.6) {
            return false;
        }

        // Need imbalance confirmation
        if (!imbalance.vibTriggered && !imbalance.dibTriggered && !imbalance.trbTriggered) {
            return false;
        }

        // Check for level break or BOS
        if (pivotState != null) {
            // Price breaking above resistance or below support
            double currentPrice = candle.getClose();

            for (PriceLevel level : pivotState.getResistanceLevels()) {
                if (currentPrice > level.getPrice() && !level.isBroken()) {
                    return true; // Breaking resistance
                }
            }

            for (PriceLevel level : pivotState.getSupportLevels()) {
                if (currentPrice < level.getPrice() && !level.isBroken()) {
                    return true; // Breaking support
                }
            }
        }

        // High score with strong imbalance is enough
        return score >= 70 && (imbalance.vibTriggered || imbalance.dibTriggered);
    }

    private String buildReason(boolean isWatch, boolean isActive,
                                SignalStrength strength, Direction dir,
                                double score) {
        if (isActive) {
            return String.format("ACTIVE TRIGGER: %s %s signal (%.1f)",
                strength.name(), dir.name(), score);
        }
        if (isWatch) {
            return String.format("WATCH SETUP: %s %s forming (%.1f)",
                strength.name(), dir.name(), score);
        }
        return String.format("IDLE: Score %.1f, %s", score, dir.name());
    }

    // ==================== HELPER METHODS ====================

    private FudkiiScore createEmptyScore() {
        return FudkiiScore.builder()
            .timestamp(Instant.now())
            .compositeScore(0)
            .compositeBias(0)
            .strength(SignalStrength.WEAK)
            .direction(Direction.NEUTRAL)
            .confidence(0)
            .isWatchSetup(false)
            .isActiveTrigger(false)
            .reason("IDLE: No data")
            .build();
    }

    private double getAverageOfi(List<UnifiedCandle> history) {
        if (history == null || history.isEmpty()) return 0;
        return history.stream()
            .filter(c -> c != null && c.isHasOrderbook() && c.getOfi() != null)
            .mapToDouble(UnifiedCandle::getOfi)
            .average().orElse(0);
    }

    private double getStdDevOfi(List<UnifiedCandle> history, double mean) {
        if (history == null || history.size() < 2) return 1;
        double variance = history.stream()
            .filter(c -> c != null && c.isHasOrderbook() && c.getOfi() != null)
            .mapToDouble(c -> Math.pow(c.getOfi() - mean, 2))
            .average().orElse(1);
        return Math.sqrt(variance);
    }

    private double getAverageKyleLambda(List<UnifiedCandle> history) {
        if (history == null || history.isEmpty()) return 0;
        return history.stream()
            .filter(c -> c != null && c.isHasOrderbook() && c.getKyleLambda() != null)
            .mapToDouble(UnifiedCandle::getKyleLambda)
            .average().orElse(0);
    }

    // ==================== RESULT CLASSES ====================

    private static class FlowResult {
        double score = 0;
        double momentum = 0;
        double rawOfi = 0;
    }

    private static class UrgencyResult {
        double score = 0;
        double bias = 0;
        double exhaustion = 0;
        MomentumState momentumState = MomentumState.STEADY;
    }

    private static class DirectionResult {
        double score = 0;
        double bullishRunway = 0;
        double bearishRunway = 0;
        MarketStructure structure = MarketStructure.RANGE;
    }

    private static class KyleResult {
        double lambda = 0;
        double score = 0;
        double micropriceDeviation = 0;
    }

    private static class ImbalanceResult {
        double score = 0;
        double buyPressure = 0;
        double sellPressure = 0;
        boolean vibTriggered = false;
        boolean dibTriggered = false;
        boolean trbTriggered = false;
    }

    private static class IntensityResult {
        double score = 0;
        OIInterpretation interpretation = OIInterpretation.NEUTRAL;
        double oiChangePercent = 0;
        boolean suggestsReversal = false;
    }
}
