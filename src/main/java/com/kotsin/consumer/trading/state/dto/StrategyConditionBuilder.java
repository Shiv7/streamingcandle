package com.kotsin.consumer.trading.state.dto;

import com.kotsin.consumer.trading.gate.FlowAlignmentGate.FlowGateResult;
import com.kotsin.consumer.trading.mtf.HierarchicalMtfAnalyzer.HierarchicalContext;
import com.kotsin.consumer.trading.mtf.SwingRangeCalculator.SwingRange;
import com.kotsin.consumer.trading.mtf.SwingRangeCalculator.ZonePosition;
import com.kotsin.consumer.trading.quality.SignalQualityCalculator.SignalQuality;
import com.kotsin.consumer.trading.smc.SmcContext.MarketBias;
import com.kotsin.consumer.trading.strategy.EntrySequenceValidator.SequenceStep;
import com.kotsin.consumer.trading.strategy.EntrySequenceValidator.SequenceValidation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * StrategyConditionBuilder - Builds detailed condition lists for dashboard transparency.
 *
 * Creates condition lists showing CURRENT vs REQUIRED values for:
 * - INST_PIVOT strategy (HTF bias, zone location, flow, sweep, structure break)
 * - FUDKII strategy (BB squeeze, ST flip, BB breakout, volume, momentum)
 *
 * This enables the "no black box" dashboard where users see exactly why
 * signals trigger or don't trigger.
 */
@Slf4j
@Component
public class StrategyConditionBuilder {

    private static final String CATEGORY_REQUIRED = "REQUIRED";
    private static final String CATEGORY_OPTIMAL = "OPTIMAL";
    private static final String CATEGORY_BONUS = "BONUS";
    private static final String CATEGORY_INFO = "INFO";

    // ========== INST_PIVOT CONDITIONS ==========

    /**
     * Build INST_PIVOT conditions from hierarchical context and validation results.
     *
     * @param ctx         Hierarchical MTF context
     * @param flowResult  Flow alignment gate result
     * @param sequence    Entry sequence validation
     * @param quality     Signal quality
     * @param isLong      Whether this is a LONG setup
     * @return List of detailed conditions
     */
    public List<StrategyConditionDTO> buildInstPivotConditions(
            HierarchicalContext ctx,
            FlowGateResult flowResult,
            SequenceValidation sequence,
            SignalQuality quality,
            boolean isLong) {

        List<StrategyConditionDTO> conditions = new ArrayList<>();
        String direction = isLong ? "LONG" : "SHORT";

        // 1. HTF Bias (REQUIRED)
        conditions.add(buildHtfBiasCondition(ctx, isLong));

        // 2. Zone Location (REQUIRED)
        conditions.add(buildZoneLocationCondition(ctx, isLong));

        // 3. Flow Alignment (REQUIRED)
        conditions.add(buildFlowAlignmentCondition(flowResult, isLong));

        // 4. Liquidity Sweep (OPTIMAL)
        conditions.add(buildLiquiditySweepCondition(ctx, sequence, isLong));

        // 5. Structure Break (OPTIMAL)
        conditions.add(buildStructureBreakCondition(ctx, sequence, isLong));

        // 6. Quality Tier (INFO)
        if (quality != null) {
            conditions.add(buildQualityTierCondition(quality));
        }

        // Log summary
        int passed = (int) conditions.stream().filter(StrategyConditionDTO::isPassed).count();
        int required = (int) conditions.stream()
                .filter(c -> CATEGORY_REQUIRED.equals(c.getCategory()))
                .filter(StrategyConditionDTO::isPassed)
                .count();
        int totalRequired = (int) conditions.stream()
                .filter(c -> CATEGORY_REQUIRED.equals(c.getCategory()))
                .count();

        log.debug("[COND_BUILD] INST_PIVOT {} | {}/{} total | {}/{} required",
                direction, passed, conditions.size(), required, totalRequired);

        return conditions;
    }

    /**
     * Build MtfAnalysisDTO from hierarchical context and validation results.
     */
    public MtfAnalysisDTO buildMtfAnalysis(
            HierarchicalContext ctx,
            FlowGateResult flowResult,
            SequenceValidation sequence,
            SignalQuality quality) {

        if (ctx == null) {
            return null;
        }

        MtfAnalysisDTO.MtfAnalysisDTOBuilder builder = MtfAnalysisDTO.builder()
                // Hierarchical
                .htfTimeframe(ctx.getHtfTimeframe())
                .ltfTimeframe(ctx.getLtfTimeframe())
                .htfBias(ctx.getHtfBias() != null ? ctx.getHtfBias().name() : "UNKNOWN")
                .ltfBias(ctx.getLtfBias() != null ? ctx.getLtfBias().name() : "UNKNOWN")
                .biasAligned(ctx.isBiasAligned())
                .htfStructure(ctx.isHtfStructureBullish() ? "Bullish structure" : "Bearish structure")
                .ltfStructure(ctx.isLtfStructureBullish() ? "Bullish structure" : "Bearish structure");

        // Swing Range
        SwingRange swing = ctx.getSwingRange();
        if (swing != null && swing.isValid()) {
            builder.swingHigh(swing.getPremiumTop())
                    .swingLow(swing.getDiscountBottom())
                    .equilibrium(swing.getEquilibrium())
                    .swingSizePercent(swing.getSwingSizePercent())
                    .isUpswing(swing.isUpswing());
        }

        builder.zonePosition(ctx.getZonePosition() != null ? ctx.getZonePosition().name() : "UNKNOWN")
                .rangePositionPercent(ctx.getRangePosition() * 100);

        // Flow
        if (flowResult != null) {
            builder.flowStatus(flowResult.isBlocked() ? "BLOCKED" : (flowResult.hasFlowData() ? "PASS" : "NO_DATA"))
                    .flowReason(flowResult.reason())
                    .flowInterpretation(extractFlowInterpretation(flowResult.reason()));
        }

        // Entry Sequence
        if (sequence != null) {
            builder.completedSteps(sequence.completedSteps())
                    .totalSteps(sequence.totalSteps())
                    .completedStepNames(sequence.completed().stream().map(Enum::name).toList())
                    .missingStepNames(sequence.missing().stream().map(Enum::name).toList())
                    .coreRequirementsMet(sequence.coreRequirementsMet())
                    .fullSequenceMet(sequence.fullSequenceMet());
        }

        // Quality
        if (quality != null) {
            builder.qualityTier(quality.tier().name())
                    .qualityTierDisplay(quality.getTierDisplay())
                    .qualitySummary(quality.summary())
                    .qualityReasons(quality.reasoning())
                    .qualityScore(quality.getQualityScore());
        }

        // SMC Details
        builder.atHtfDemand(ctx.isAtHtfDemand())
                .atHtfSupply(ctx.isAtHtfSupply())
                .ltfSweepDetected(ctx.isLtfSweepDetected())
                .ltfSweepSide(ctx.getLtfLastSweep() != null ?
                        (ctx.getLtfLastSweep().isBuySide() ? "BUY_SIDE" : "SELL_SIDE") : null)
                .ltfChochDetected(ctx.isLtfRecentChoch())
                .ltfChochDirection(ctx.isLtfRecentChoch() ?
                        (ctx.isLtfChochBullish() ? "BULLISH" : "BEARISH") : null)
                .ltfBosDetected(ctx.isLtfRecentBos())
                .ltfBosDirection(ctx.isLtfRecentBos() ?
                        (ctx.isLtfBosBullish() ? "BULLISH" : "BEARISH") : null);

        return builder.build();
    }

    // ========== FUDKII CONDITIONS ==========

    /**
     * Build FUDKII conditions.
     * FUDKII = First Up/Down Kill Ignition Indicator
     *
     * @param bbSqueeze     Is BB squeezed (width < threshold)
     * @param bbWidthPct    Current BB width percentage
     * @param stFlip        SuperTrend flipped direction
     * @param stDirection   Current SuperTrend direction
     * @param bbPercentB    BB %B value
     * @param volumeRatio   Volume vs average ratio
     * @param momentumScore Momentum score
     * @param isLong        Whether this is a LONG setup
     * @return List of detailed conditions
     */
    public List<StrategyConditionDTO> buildFudkiiConditions(
            boolean bbSqueeze,
            double bbWidthPct,
            boolean stFlip,
            String stDirection,
            double bbPercentB,
            double volumeRatio,
            double momentumScore,
            boolean isLong) {

        List<StrategyConditionDTO> conditions = new ArrayList<>();
        String direction = isLong ? "LONG" : "SHORT";

        // 1. BB Squeeze (REQUIRED)
        conditions.add(buildBbSqueezeCondition(bbSqueeze, bbWidthPct));

        // 2. SuperTrend Flip (REQUIRED)
        conditions.add(buildStFlipCondition(stFlip, stDirection, isLong));

        // 3. BB Breakout (REQUIRED)
        conditions.add(buildBbBreakoutCondition(bbPercentB, isLong));

        // 4. Volume Surge (OPTIMAL)
        conditions.add(buildVolumeSurgeCondition(volumeRatio));

        // 5. Momentum Alignment (OPTIMAL)
        conditions.add(buildMomentumCondition(momentumScore, isLong));

        // Log summary
        int passed = (int) conditions.stream().filter(StrategyConditionDTO::isPassed).count();
        log.debug("[COND_BUILD] FUDKII {} | {}/{} conditions met", direction, passed, conditions.size());

        return conditions;
    }

    // ========== PRIVATE BUILDERS ==========

    private StrategyConditionDTO buildHtfBiasCondition(HierarchicalContext ctx, boolean isLong) {
        String requiredBias = isLong ? "BULLISH" : "BEARISH";
        MarketBias currentBias = ctx != null ? ctx.getHtfBias() : MarketBias.UNKNOWN;
        boolean passed = currentBias == (isLong ? MarketBias.BULLISH : MarketBias.BEARISH);

        return StrategyConditionDTO.builder()
                .name("HTF Bias")
                .category(CATEGORY_REQUIRED)
                .passed(passed)
                .currentValue(currentBias != null ? currentBias.name() : "UNKNOWN")
                .requiredValue(requiredBias)
                .explanation("Higher timeframe must show " + requiredBias + " bias for " +
                        (isLong ? "LONG" : "SHORT") + " trades")
                .progressPercent(passed ? 100 : (currentBias == MarketBias.UNKNOWN ? 50 : 0))
                .source("HTF")
                .timeframe(ctx != null ? ctx.getHtfTimeframe() : "1h")
                .build();
    }

    private StrategyConditionDTO buildZoneLocationCondition(HierarchicalContext ctx, boolean isLong) {
        ZonePosition zone = ctx != null ? ctx.getZonePosition() : ZonePosition.UNKNOWN;
        boolean atPoi = isLong ?
                (ctx != null && ctx.isAtHtfDemand()) :
                (ctx != null && ctx.isAtHtfSupply());

        String requiredZone = isLong ?
                "DISCOUNT or EQUILIBRIUM (or at HTF demand)" :
                "PREMIUM or EQUILIBRIUM (or at HTF supply)";

        boolean passed;
        int progress;

        if (isLong) {
            passed = zone == ZonePosition.DISCOUNT || zone == ZonePosition.EQUILIBRIUM ||
                    zone == ZonePosition.BELOW_RANGE || atPoi;
            // Progress: DISCOUNT=100%, EQUILIBRIUM=75%, PREMIUM=25%
            progress = switch (zone) {
                case DISCOUNT, BELOW_RANGE -> 100;
                case EQUILIBRIUM -> 75;
                case PREMIUM -> 25;
                case ABOVE_RANGE -> 10;
                default -> 50;
            };
        } else {
            passed = zone == ZonePosition.PREMIUM || zone == ZonePosition.EQUILIBRIUM ||
                    zone == ZonePosition.ABOVE_RANGE || atPoi;
            progress = switch (zone) {
                case PREMIUM, ABOVE_RANGE -> 100;
                case EQUILIBRIUM -> 75;
                case DISCOUNT -> 25;
                case BELOW_RANGE -> 10;
                default -> 50;
            };
        }

        String currentZoneStr = zone != null ? zone.name() : "UNKNOWN";
        if (atPoi) {
            currentZoneStr += (isLong ? " (at HTF demand)" : " (at HTF supply)");
        }
        if (ctx != null && ctx.getRangePosition() > 0) {
            currentZoneStr += String.format(" (%.0f%%)", ctx.getRangePosition() * 100);
        }

        return StrategyConditionDTO.builder()
                .name("Zone Location")
                .category(CATEGORY_REQUIRED)
                .passed(passed)
                .currentValue(currentZoneStr)
                .requiredValue(requiredZone)
                .explanation(isLong ?
                        "LONG trades should be in discount (buy low)" :
                        "SHORT trades should be in premium (sell high)")
                .progressPercent(passed ? 100 : progress)
                .source("ZONE")
                .timeframe(ctx != null ? ctx.getHtfTimeframe() : "1h")
                .build();
    }

    private StrategyConditionDTO buildFlowAlignmentCondition(FlowGateResult flowResult, boolean isLong) {
        String direction = isLong ? "LONG" : "SHORT";
        boolean passed = flowResult == null || flowResult.passed();
        boolean hasData = flowResult != null && flowResult.hasFlowData();

        String currentValue;
        String requiredValue = "Not contradicting " + direction + " direction";
        int progress;

        if (flowResult == null || !flowResult.hasFlowData()) {
            currentValue = "No F&O data available";
            progress = 100;  // No data = allow
        } else if (flowResult.flowAligned()) {
            currentValue = "Flow CONFIRMS " + direction + " (" + extractFlowInterpretation(flowResult.reason()) + ")";
            progress = 100;
        } else if (flowResult.passed()) {
            currentValue = "Flow NEUTRAL";
            progress = 100;
        } else {
            currentValue = "Flow BLOCKS " + direction + " (" + extractFlowInterpretation(flowResult.reason()) + ")";
            progress = 0;
        }

        return StrategyConditionDTO.builder()
                .name("Flow Alignment")
                .category(CATEGORY_REQUIRED)
                .passed(passed)
                .currentValue(currentValue)
                .requiredValue(requiredValue)
                .explanation("F&O flow must not contradict the trade direction")
                .progressPercent(progress)
                .source("FLOW")
                .timeframe(null)
                .notes(flowResult != null ? flowResult.reason() : null)
                .build();
    }

    private StrategyConditionDTO buildLiquiditySweepCondition(HierarchicalContext ctx, SequenceValidation sequence, boolean isLong) {
        boolean sweepDetected = ctx != null && ctx.isLtfSweepDetected();
        boolean correctSide = false;
        String sweepSide = "Not detected";

        if (sweepDetected && ctx.getLtfLastSweep() != null) {
            boolean isBuySide = ctx.getLtfLastSweep().isBuySide();
            sweepSide = isBuySide ? "Buy-side swept" : "Sell-side swept";
            // For LONG: need sell-side sweep (stops below taken)
            // For SHORT: need buy-side sweep (stops above taken)
            correctSide = isLong ? !isBuySide : isBuySide;
        }

        boolean passed = sequence != null && sequence.hasStep(SequenceStep.LIQUIDITY_SWEEP);

        String required = isLong ?
                "Sell-side liquidity swept (stops below)" :
                "Buy-side liquidity swept (stops above)";

        return StrategyConditionDTO.builder()
                .name("Liquidity Sweep")
                .category(CATEGORY_OPTIMAL)
                .passed(passed)
                .currentValue(sweepSide)
                .requiredValue(required)
                .explanation("Smart money sweeps liquidity before reversing")
                .progressPercent(passed ? 100 : (sweepDetected ? 50 : 0))
                .source("LTF")
                .timeframe(ctx != null ? ctx.getLtfTimeframe() : "5m")
                .build();
    }

    private StrategyConditionDTO buildStructureBreakCondition(HierarchicalContext ctx, SequenceValidation sequence, boolean isLong) {
        boolean choch = ctx != null && ctx.isLtfRecentChoch();
        boolean bos = ctx != null && ctx.isLtfRecentBos();
        boolean chochBullish = ctx != null && ctx.isLtfChochBullish();
        boolean bosBullish = ctx != null && ctx.isLtfBosBullish();

        String currentValue;
        boolean correctDirection;

        if (choch) {
            currentValue = chochBullish ? "Bullish CHoCH" : "Bearish CHoCH";
            correctDirection = isLong == chochBullish;
        } else if (bos) {
            currentValue = bosBullish ? "Bullish BOS" : "Bearish BOS";
            correctDirection = isLong == bosBullish;
        } else {
            currentValue = "No recent structure break";
            correctDirection = false;
        }

        boolean passed = sequence != null && sequence.hasStep(SequenceStep.STRUCTURE_BREAK);

        String required = isLong ?
                "Bullish CHoCH or BOS" :
                "Bearish CHoCH or BOS";

        return StrategyConditionDTO.builder()
                .name("Structure Break")
                .category(CATEGORY_OPTIMAL)
                .passed(passed)
                .currentValue(currentValue)
                .requiredValue(required)
                .explanation("LTF structure must confirm direction change")
                .progressPercent(passed ? 100 : ((choch || bos) ? (correctDirection ? 75 : 25) : 0))
                .source("LTF")
                .timeframe(ctx != null ? ctx.getLtfTimeframe() : "5m")
                .build();
    }

    private StrategyConditionDTO buildQualityTierCondition(SignalQuality quality) {
        return StrategyConditionDTO.builder()
                .name("Quality Tier")
                .category(CATEGORY_INFO)
                .passed(quality.isValid())
                .currentValue(quality.getTierDisplay() + " (" + quality.getCompletionRatio() + " steps)")
                .requiredValue("B tier or better")
                .explanation(quality.summary())
                .progressPercent(quality.getQualityScore())
                .source(null)
                .timeframe(null)
                .build();
    }

    // ========== FUDKII CONDITION BUILDERS ==========

    private StrategyConditionDTO buildBbSqueezeCondition(boolean bbSqueeze, double bbWidthPct) {
        return StrategyConditionDTO.builder()
                .name("BB Squeeze")
                .category(CATEGORY_REQUIRED)
                .passed(bbSqueeze)
                .currentValue(String.format("%.2f%%", bbWidthPct * 100))
                .requiredValue("< 2.0% (volatility contraction)")
                .explanation("Bollinger Band width must be squeezed indicating low volatility before expansion")
                .progressPercent(bbSqueeze ? 100 : Math.min(100, (int) ((2.0 - bbWidthPct * 100) / 2.0 * 100)))
                .source("TECHNICAL")
                .timeframe("30m")
                .build();
    }

    private StrategyConditionDTO buildStFlipCondition(boolean stFlip, String stDirection, boolean isLong) {
        String required = isLong ? "Flip to BULLISH" : "Flip to BEARISH";
        boolean correctDirection = isLong ?
                "BULLISH".equalsIgnoreCase(stDirection) :
                "BEARISH".equalsIgnoreCase(stDirection);

        return StrategyConditionDTO.builder()
                .name("SuperTrend Flip")
                .category(CATEGORY_REQUIRED)
                .passed(stFlip && correctDirection)
                .currentValue(stFlip ? ("Flipped to " + stDirection) : ("No flip - " + stDirection))
                .requiredValue(required)
                .explanation("SuperTrend must flip to confirm direction change")
                .progressPercent(stFlip && correctDirection ? 100 : (stFlip ? 50 : 0))
                .source("TECHNICAL")
                .timeframe("30m")
                .build();
    }

    private StrategyConditionDTO buildBbBreakoutCondition(double bbPercentB, boolean isLong) {
        boolean breakout = bbPercentB > 1.0 || bbPercentB < 0.0;
        boolean correctSide = isLong ? bbPercentB > 1.0 : bbPercentB < 0.0;

        String currentValue = String.format("%%B = %.2f", bbPercentB);
        String required = isLong ?
                "> 1.0 (above upper band)" :
                "< 0.0 (below lower band)";

        int progress;
        if (correctSide) {
            progress = 100;
        } else if (breakout) {
            progress = 50;  // Wrong side breakout
        } else {
            // Progress towards breakout
            progress = isLong ?
                    (int) Math.min(100, bbPercentB * 100) :
                    (int) Math.min(100, (1.0 - bbPercentB) * 100);
        }

        return StrategyConditionDTO.builder()
                .name("BB Breakout")
                .category(CATEGORY_REQUIRED)
                .passed(breakout && correctSide)
                .currentValue(currentValue)
                .requiredValue(required)
                .explanation("Price must break outside Bollinger Bands to confirm expansion")
                .progressPercent(progress)
                .source("TECHNICAL")
                .timeframe("30m")
                .build();
    }

    private StrategyConditionDTO buildVolumeSurgeCondition(double volumeRatio) {
        boolean surge = volumeRatio >= 1.5;

        return StrategyConditionDTO.builder()
                .name("Volume Surge")
                .category(CATEGORY_OPTIMAL)
                .passed(surge)
                .currentValue(String.format("%.1fx average", volumeRatio))
                .requiredValue(">= 1.5x average volume")
                .explanation("Volume should surge on the breakout candle")
                .progressPercent(surge ? 100 : Math.min(100, (int) (volumeRatio / 1.5 * 100)))
                .source("TECHNICAL")
                .timeframe("30m")
                .build();
    }

    private StrategyConditionDTO buildMomentumCondition(double momentumScore, boolean isLong) {
        boolean aligned = isLong ? momentumScore > 0 : momentumScore < 0;

        return StrategyConditionDTO.builder()
                .name("Momentum Alignment")
                .category(CATEGORY_OPTIMAL)
                .passed(aligned)
                .currentValue(String.format("%.1f", momentumScore))
                .requiredValue(isLong ? "> 0 (positive)" : "< 0 (negative)")
                .explanation("Momentum should align with trade direction")
                .progressPercent(aligned ? 100 : 50)
                .source("TECHNICAL")
                .timeframe("30m")
                .build();
    }

    // ========== HELPERS ==========

    private String extractFlowInterpretation(String reason) {
        if (reason == null) return "UNKNOWN";
        if (reason.contains("LONG_BUILDUP")) return "LONG_BUILDUP";
        if (reason.contains("SHORT_COVERING")) return "SHORT_COVERING";
        if (reason.contains("SHORT_BUILDUP")) return "SHORT_BUILDUP";
        if (reason.contains("LONG_UNWINDING")) return "LONG_UNWINDING";
        return "NEUTRAL";
    }

    /**
     * Calculate overall progress from conditions.
     */
    public int calculateOverallProgress(List<StrategyConditionDTO> conditions) {
        if (conditions == null || conditions.isEmpty()) return 0;

        // Weight: REQUIRED=2, OPTIMAL=1, BONUS=0.5
        double totalWeight = 0;
        double passedWeight = 0;

        for (StrategyConditionDTO c : conditions) {
            double weight = switch (c.getCategory()) {
                case "REQUIRED" -> 2.0;
                case "OPTIMAL" -> 1.0;
                case "BONUS" -> 0.5;
                default -> 0.0;
            };
            totalWeight += weight;
            if (c.isPassed()) {
                passedWeight += weight;
            }
        }

        return totalWeight > 0 ? (int) (passedWeight / totalWeight * 100) : 0;
    }

    /**
     * Find the blocking condition (first required condition that is not met).
     */
    public String findBlockingCondition(List<StrategyConditionDTO> conditions) {
        if (conditions == null) return null;

        return conditions.stream()
                .filter(c -> CATEGORY_REQUIRED.equals(c.getCategory()))
                .filter(c -> !c.isPassed())
                .findFirst()
                .map(c -> c.getName() + " - " + c.getCurrentValue())
                .orElse(null);
    }

    /**
     * Check if all required conditions are met.
     */
    public boolean allRequiredMet(List<StrategyConditionDTO> conditions) {
        if (conditions == null || conditions.isEmpty()) return false;

        return conditions.stream()
                .filter(c -> CATEGORY_REQUIRED.equals(c.getCategory()))
                .allMatch(StrategyConditionDTO::isPassed);
    }
}
