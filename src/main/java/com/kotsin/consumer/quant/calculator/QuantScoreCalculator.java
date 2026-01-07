package com.kotsin.consumer.quant.calculator;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.OptionCandle;
import com.kotsin.consumer.model.GreeksPortfolio;
import com.kotsin.consumer.model.IVSurface;
import com.kotsin.consumer.model.MTFDistribution;
import com.kotsin.consumer.quant.config.QuantScoreConfig;
import com.kotsin.consumer.quant.model.QuantScore;
import com.kotsin.consumer.quant.model.QuantScore.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * QuantScoreCalculator - Main orchestrator for composite quant score calculation.
 *
 * Aggregates 8 category scores into a final 0-100 QuantScore:
 * 1. Greeks Exposure (15 pts)
 * 2. IV Surface (12 pts)
 * 3. Microstructure (18 pts)
 * 4. Options Flow (15 pts)
 * 5. Price Action (12 pts)
 * 6. Volume Profile (8 pts)
 * 7. Cross-Instrument (10 pts)
 * 8. Signal Confluence (10 pts)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QuantScoreCalculator {

    private final QuantScoreConfig config;
    private final GreeksScoreCalculator greeksCalculator;
    private final IVSurfaceScoreCalculator ivCalculator;
    private final MicrostructureScoreCalculator microCalculator;
    private final OptionsFlowScoreCalculator flowCalculator;
    private final PriceActionScoreCalculator priceCalculator;
    private final VolumeProfileScoreCalculator volumeCalculator;
    private final CrossInstrumentScoreCalculator crossCalculator;
    private final ConfluenceScoreCalculator confluenceCalculator;

    private static final DateTimeFormatter TIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata"));

    /**
     * Calculate comprehensive QuantScore from FamilyCandle
     */
    public QuantScore calculate(FamilyCandle family) {
        if (family == null) {
            return QuantScore.empty("UNKNOWN", "UNKNOWN");
        }

        String familyId = family.getFamilyId();
        String symbol = family.getSymbol();

        // Check data availability
        DataQuality dataQuality = assessDataQuality(family);

        // Calculate direction from price action
        double priceDirection = calculatePriceDirection(family);
        Direction direction = determineDirection(priceDirection);

        // Calculate individual category scores
        double greeksScore = greeksCalculator.calculate(family, priceDirection);
        double ivScore = ivCalculator.calculate(family);
        double microScore = microCalculator.calculate(family);
        double flowScore = flowCalculator.calculate(family, priceDirection);
        double priceScore = priceCalculator.calculate(family);
        double volumeScore = volumeCalculator.calculate(family);
        double crossScore = crossCalculator.calculate(family, priceDirection);

        // Create breakdown with raw scores
        QuantScoreBreakdown breakdown = QuantScoreBreakdown.builder()
            .greeksScore(greeksScore)
            .ivSurfaceScore(ivScore)
            .microstructureScore(microScore)
            .optionsFlowScore(flowScore)
            .priceActionScore(priceScore)
            .volumeProfileScore(volumeScore)
            .crossInstrumentScore(crossScore)
            .confluenceScore(0)  // Will be calculated after
            // Calculate percentages
            .greeksPct(greeksScore / config.getWeight().getGreeks() * 100)
            .ivSurfacePct(ivScore / config.getWeight().getIvSurface() * 100)
            .microstructurePct(microScore / config.getWeight().getMicrostructure() * 100)
            .optionsFlowPct(flowScore / config.getWeight().getOptionsFlow() * 100)
            .priceActionPct(priceScore / config.getWeight().getPriceAction() * 100)
            .volumeProfilePct(volumeScore / config.getWeight().getVolumeProfile() * 100)
            .crossInstrumentPct(crossScore / config.getWeight().getCrossInstrument() * 100)
            .build();

        // Calculate confluence score based on category agreement
        double confluenceScore = confluenceCalculator.calculate(breakdown, direction);
        breakdown.setConfluenceScore(confluenceScore);
        breakdown.setConfluencePct(confluenceScore / config.getWeight().getConfluence() * 100);

        // Calculate raw score
        double rawScore = breakdown.calculateRawScore();
        breakdown.setRawScore(rawScore);

        // Calculate modifiers
        double regimeModifier = calculateRegimeModifier(family);
        double confidenceModifier = calculateConfidenceModifier(dataQuality, breakdown);
        breakdown.setRegimeModifier(regimeModifier);
        breakdown.setConfidenceModifier(confidenceModifier);

        // Calculate final score
        double finalScore = Math.min(100, Math.max(0, rawScore * regimeModifier * confidenceModifier));
        QuantLabel label = QuantLabel.fromScore(finalScore);

        // Build summaries
        GreeksSummary greeksSummary = buildGreeksSummary(family);
        IVSummary ivSummary = buildIVSummary(family);
        MicrostructureSummary microSummary = buildMicrostructureSummary(family);
        OptionsFlowSummary flowSummary = buildOptionsFlowSummary(family);
        PriceActionSummary priceSummary = buildPriceActionSummary(family);
        VolumeProfileSummary volumeSummary = buildVolumeProfileSummary(family);

        // Collect warnings
        List<Warning> warnings = collectWarnings(family, breakdown, greeksSummary, ivSummary);

        // Determine actionability
        boolean actionable = isActionable(finalScore, warnings, breakdown);
        String actionableReason = actionable ? null : determineNonActionableReason(finalScore, warnings, breakdown);

        // Calculate confidence
        double confidence = calculateOverallConfidence(dataQuality, breakdown, warnings);

        return QuantScore.builder()
            .familyId(familyId)
            .symbol(symbol)
            .scripCode(family.getFamilyId())
            .timestamp(family.getTimestamp())
            .timeframe(family.getTimeframe())
            .humanReadableTime(TIME_FORMATTER.format(Instant.ofEpochMilli(family.getTimestamp())))
            .quantScore(finalScore)
            .quantLabel(label)
            .confidence(confidence)
            .direction(direction)
            .directionalStrength(priceDirection)
            .breakdown(breakdown)
            .greeksSummary(greeksSummary)
            .ivSummary(ivSummary)
            .microstructureSummary(microSummary)
            .optionsFlowSummary(flowSummary)
            .priceActionSummary(priceSummary)
            .volumeProfileSummary(volumeSummary)
            .warnings(warnings)
            .actionable(actionable)
            .actionableReason(actionableReason)
            .minActionableScore(config.getEmission().getMinScore())
            .dataQuality(dataQuality)
            .build();
    }

    /**
     * Assess data quality and completeness
     */
    private DataQuality assessDataQuality(FamilyCandle family) {
        boolean hasGreeks = family.hasGreeksPortfolio();
        boolean hasIV = family.hasIVSurface();
        boolean hasMicro = family.getOptions() != null && !family.getOptions().isEmpty() &&
                          family.getOptions().stream().anyMatch(o -> o.getOfi() != null && o.getOfi() != 0);
        boolean hasFlow = family.getPcr() != null;
        boolean hasPrice = family.getMtfDistribution() != null &&
                          family.getMtfDistribution().getEvolution() != null;
        boolean hasVolume = family.getOptions() != null &&
                           family.getOptions().stream().anyMatch(o -> o.getPoc() != null && o.getPoc() > 0);
        boolean hasCross = family.isHasFuture() || family.getSpotFuturePremium() != null;

        int count = 0;
        if (hasGreeks) count++;
        if (hasIV) count++;
        if (hasMicro) count++;
        if (hasFlow) count++;
        if (hasPrice) count++;
        if (hasVolume) count++;
        if (hasCross) count++;

        double completeness = count / 7.0;
        String level = completeness >= 0.8 ? "FULL" :
                      completeness >= 0.5 ? "PARTIAL" : "MINIMAL";

        return DataQuality.builder()
            .hasGreeks(hasGreeks)
            .hasIVSurface(hasIV)
            .hasMicrostructure(hasMicro)
            .hasOptionsFlow(hasFlow)
            .hasPriceAction(hasPrice)
            .hasVolumeProfile(hasVolume)
            .hasCrossInstrument(hasCross)
            .completenessScore(completeness)
            .qualityLevel(level)
            .build();
    }

    /**
     * Calculate price direction from equity/future movement
     */
    private double calculatePriceDirection(FamilyCandle family) {
        var primary = family.getPrimaryInstrumentOrFallback();
        if (primary == null) return 0;

        double open = primary.getOpen();
        double close = primary.getClose();
        double high = primary.getHigh();
        double low = primary.getLow();

        if (open == 0) return 0;

        // Combine price change with candle body analysis
        double priceChange = (close - open) / open;
        double range = high - low;
        double bodySize = Math.abs(close - open);
        double bodyRatio = range > 0 ? bodySize / range : 0;

        // Direction strength (-1 to +1)
        double direction = Math.tanh(priceChange * 100) * bodyRatio;

        return direction;
    }

    /**
     * Determine direction enum from strength
     */
    private Direction determineDirection(double strength) {
        if (strength > 0.1) return Direction.BULLISH;
        if (strength < -0.1) return Direction.BEARISH;
        return Direction.NEUTRAL;
    }

    /**
     * Calculate regime modifier based on index/market regime
     */
    private double calculateRegimeModifier(FamilyCandle family) {
        // Use directional bias and bias confidence from family
        String bias = family.getDirectionalBias();
        double biasConf = family.getBiasConfidence();

        double modifier = config.getRegime().getNeutralModifier();

        if ("STRONG_BULLISH".equals(bias) || "BULLISH".equals(bias)) {
            modifier = config.getRegime().getBullishModifier();
        } else if ("STRONG_BEARISH".equals(bias) || "BEARISH".equals(bias)) {
            modifier = config.getRegime().getBearishModifier();
        }

        // Scale by confidence
        modifier = 1.0 + (modifier - 1.0) * biasConf;

        // Clamp to range
        return Math.max(config.getRegime().getMinModifier(),
               Math.min(config.getRegime().getMaxModifier(), modifier));
    }

    /**
     * Calculate confidence modifier based on data quality
     */
    private double calculateConfidenceModifier(DataQuality quality, QuantScoreBreakdown breakdown) {
        double base = 0.8;

        // Add for data completeness
        base += quality.getCompletenessScore() * 0.15;

        // Add for category agreement
        int strongCategories = countStrongCategories(breakdown);
        base += (strongCategories / 8.0) * 0.05;

        return Math.min(1.0, base);
    }

    /**
     * Count categories above threshold
     */
    private int countStrongCategories(QuantScoreBreakdown breakdown) {
        double threshold = config.getEmission().getCategoryThresholdPercent();
        int count = 0;

        if (breakdown.getGreeksPct() >= threshold) count++;
        if (breakdown.getIvSurfacePct() >= threshold) count++;
        if (breakdown.getMicrostructurePct() >= threshold) count++;
        if (breakdown.getOptionsFlowPct() >= threshold) count++;
        if (breakdown.getPriceActionPct() >= threshold) count++;
        if (breakdown.getVolumeProfilePct() >= threshold) count++;
        if (breakdown.getCrossInstrumentPct() >= threshold) count++;
        if (breakdown.getConfluencePct() >= threshold) count++;

        return count;
    }

    /**
     * Build Greeks summary for display
     */
    private GreeksSummary buildGreeksSummary(FamilyCandle family) {
        GreeksPortfolio gp = family.getGreeksPortfolio();
        if (gp == null) {
            return GreeksSummary.builder().build();
        }

        return GreeksSummary.builder()
            .totalDelta(gp.getTotalDelta())
            .totalGamma(gp.getTotalGamma())
            .totalVega(gp.getTotalVega())
            .totalTheta(gp.getTotalTheta())
            .gammaSqueezeRisk(gp.isGammaSqueezeRisk())
            .gammaSqueezeDistance(gp.getGammaSqueezeDistance())
            .maxGammaStrike(gp.getMaxGammaStrike())
            .deltaBias(gp.getDeltaBias() != null ? gp.getDeltaBias().name() : "NEUTRAL")
            .vegaStructure(gp.getVegaStructure() != null ? gp.getVegaStructure().name() : "BALANCED")
            .riskScore(gp.getRiskScore())
            .build();
    }

    /**
     * Build IV summary for display
     */
    private IVSummary buildIVSummary(FamilyCandle family) {
        IVSurface iv = family.getIvSurface();
        if (iv == null) {
            return IVSummary.builder()
                .atmIV(family.getAtmIV() != null ? family.getAtmIV() : 0)
                .build();
        }

        return IVSummary.builder()
            .atmIV(iv.getAtmIV())
            .ivRank(iv.getIvRank())
            .ivSignal(iv.getIvSignal() != null ? iv.getIvSignal().name() : "NEUTRAL")
            .ivCrushRisk(iv.isIvCrushRisk())
            .ivVelocity(iv.getIvVelocity())
            .smileShape(iv.getSmileShape() != null ? iv.getSmileShape().name() : "NORMAL")
            .termStructure(iv.getTermStructure() != null ? iv.getTermStructure().name() : "FLAT")
            .skew25Delta(iv.getSkew25Delta())
            .nearTermIV(iv.getNearTermIV())
            .farTermIV(iv.getFarTermIV())
            .build();
    }

    /**
     * Build microstructure summary
     */
    private MicrostructureSummary buildMicrostructureSummary(FamilyCandle family) {
        List<OptionCandle> options = family.getOptions();
        if (options == null || options.isEmpty()) {
            return MicrostructureSummary.builder().build();
        }

        double totalOFI = 0, totalVPIN = 0, totalDepth = 0, totalLambda = 0;
        long totalAggBuy = 0, totalAggSell = 0;
        int count = 0;

        for (OptionCandle opt : options) {
            if (opt == null) continue;
            if (opt.getOfi() != null) totalOFI += opt.getOfi();
            if (opt.getVpin() != null) totalVPIN += opt.getVpin();
            if (opt.getDepthImbalance() != null) totalDepth += opt.getDepthImbalance();
            if (opt.getKyleLambda() != null) totalLambda += opt.getKyleLambda();
            if (opt.getAggressiveBuyVolume() != null) totalAggBuy += opt.getAggressiveBuyVolume();
            if (opt.getAggressiveSellVolume() != null) totalAggSell += opt.getAggressiveSellVolume();
            count++;
        }

        if (count == 0) {
            return MicrostructureSummary.builder().build();
        }

        double avgOFI = totalOFI / count;
        double avgVPIN = totalVPIN / count;
        double avgDepth = totalDepth / count;
        double avgLambda = totalLambda / count;

        long totalAgg = totalAggBuy + totalAggSell;
        double buyRatio = totalAgg > 0 ? (double) totalAggBuy / totalAgg : 0.5;
        double sellRatio = totalAgg > 0 ? (double) totalAggSell / totalAgg : 0.5;

        String flowDir = buyRatio > 0.6 ? "BUYING" : sellRatio > 0.6 ? "SELLING" : "BALANCED";
        double flowStrength = Math.abs(buyRatio - 0.5) * 2;

        return MicrostructureSummary.builder()
            .avgOFI(avgOFI)
            .avgVPIN(avgVPIN)
            .avgDepthImbalance(avgDepth)
            .avgKyleLambda(avgLambda)
            .aggressiveBuyRatio(buyRatio)
            .aggressiveSellRatio(sellRatio)
            .flowDirection(flowDir)
            .flowStrength(flowStrength)
            .build();
    }

    /**
     * Build options flow summary
     */
    private OptionsFlowSummary buildOptionsFlowSummary(FamilyCandle family) {
        Double pcr = family.getPcr();
        String pcrSignal = "NEUTRAL";
        if (pcr != null) {
            if (pcr < 0.5) pcrSignal = "EXTREME_GREED";
            else if (pcr < 0.7) pcrSignal = "BULLISH";
            else if (pcr > 1.5) pcrSignal = "EXTREME_FEAR";
            else if (pcr > 1.3) pcrSignal = "BEARISH";
        }

        MTFDistribution mtf = family.getMtfDistribution();
        String buildupType = null;
        double oiMomentum = 0;

        if (mtf != null && mtf.getEvolution() != null && mtf.getEvolution().getOiEvolution() != null) {
            var oiEvo = mtf.getEvolution().getOiEvolution();
            buildupType = oiEvo.getBuildupType() != null ? oiEvo.getBuildupType().name() : null;
            oiMomentum = oiEvo.getOiMomentum();
        }

        return OptionsFlowSummary.builder()
            .pcr(pcr != null ? pcr : 1.0)
            .pcrChange(family.getPcrChange() != null ? family.getPcrChange() : 0)
            .pcrSignal(pcrSignal)
            .oiBuildupType(buildupType)
            .oiMomentum(oiMomentum)
            .futuresBuildup(family.getFuturesBuildup())
            .spotFuturePremium(family.getSpotFuturePremium() != null ? family.getSpotFuturePremium() : 0)
            .build();
    }

    /**
     * Build price action summary
     */
    private PriceActionSummary buildPriceActionSummary(FamilyCandle family) {
        MTFDistribution mtf = family.getMtfDistribution();
        if (mtf == null || mtf.getEvolution() == null) {
            return PriceActionSummary.builder().build();
        }

        var evo = mtf.getEvolution();
        var seq = evo.getCandleSequence();
        var pcr = evo.getPcrEvolution();
        var wyckoff = evo.getWyckoffPhase();

        return PriceActionSummary.builder()
            .candleSequencePattern(seq != null ? seq.getPattern() : null)
            .sequenceType(seq != null && seq.getSequenceType() != null ? seq.getSequenceType().name() : null)
            .reversalIndex(seq != null ? seq.getReversalIndex() : 0)
            .momentumSlope(seq != null ? seq.getMomentumSlope() : 0)
            .wyckoffPhase(wyckoff != null && wyckoff.getPhase() != null ? wyckoff.getPhase().name() : null)
            .wyckoffStrength(wyckoff != null ? wyckoff.getPhaseStrength() : 0)
            .pcrDivergence(pcr != null && pcr.isPcrDivergence())
            .oiDivergence(evo.getOiEvolution() != null && evo.getOiEvolution().isOiDivergence())
            .build();
    }

    /**
     * Build volume profile summary
     */
    private VolumeProfileSummary buildVolumeProfileSummary(FamilyCandle family) {
        MTFDistribution mtf = family.getMtfDistribution();
        if (mtf == null || mtf.getEvolution() == null ||
            mtf.getEvolution().getVolumeProfileEvolution() == null) {
            return VolumeProfileSummary.builder().build();
        }

        var vol = mtf.getEvolution().getVolumeProfileEvolution();

        return VolumeProfileSummary.builder()
            .poc(vol.getPocEnd())
            .vah(vol.getVahHistory() != null && vol.getVahHistory().length > 0 ?
                 vol.getVahHistory()[vol.getVahHistory().length - 1] : 0)
            .val(vol.getValHistory() != null && vol.getValHistory().length > 0 ?
                 vol.getValHistory()[vol.getValHistory().length - 1] : 0)
            .pocMigration(vol.getPocMigration())
            .pocTrend(vol.getPocTrend() != null ? vol.getPocTrend().name() : null)
            .valueAreaExpanding(vol.isValueAreaExpanding())
            .valueAreaContracting(vol.isValueAreaContracting())
            .valueAreaShift(vol.getValueAreaShift() != null ? vol.getValueAreaShift().name() : null)
            .build();
    }

    /**
     * Collect warnings based on analysis
     */
    private List<Warning> collectWarnings(FamilyCandle family, QuantScoreBreakdown breakdown,
                                          GreeksSummary greeks, IVSummary iv) {
        List<Warning> warnings = new ArrayList<>();

        // Gamma squeeze warning
        if (greeks != null && greeks.isGammaSqueezeRisk()) {
            Double distance = greeks.getGammaSqueezeDistance();
            Warning.WarningSeverity severity = distance != null && distance < 1.0 ?
                Warning.WarningSeverity.CRITICAL : Warning.WarningSeverity.HIGH;

            warnings.add(Warning.builder()
                .type(Warning.WarningType.GAMMA_SQUEEZE_IMMINENT)
                .severity(severity)
                .message(String.format("Gamma squeeze risk detected. Distance: %.2f%%",
                    distance != null ? distance : 0))
                .recommendation("Consider delta hedging or reducing position size")
                .build());
        }

        // IV crush warning
        if (iv != null && iv.isIvCrushRisk()) {
            warnings.add(Warning.builder()
                .type(Warning.WarningType.IV_CRUSH_RISK)
                .severity(Warning.WarningSeverity.HIGH)
                .message("IV crush risk - high IV with near-term expiry")
                .recommendation("Avoid long premium positions or use spreads")
                .build());
        }

        // Extreme skew warning
        if (iv != null && Math.abs(iv.getSkew25Delta()) > 5) {
            warnings.add(Warning.builder()
                .type(Warning.WarningType.EXTREME_SKEW)
                .severity(Warning.WarningSeverity.MEDIUM)
                .message(String.format("Extreme skew detected: %.2f%%", iv.getSkew25Delta()))
                .recommendation("Consider skew trades or adjust strike selection")
                .build());
        }

        // Low data quality warning
        if (breakdown.getConfidenceModifier() < 0.9) {
            warnings.add(Warning.builder()
                .type(Warning.WarningType.DATA_QUALITY)
                .severity(Warning.WarningSeverity.LOW)
                .message("Some data categories are missing or incomplete")
                .recommendation("Score may be less reliable")
                .build());
        }

        return warnings;
    }

    /**
     * Determine if score is actionable
     */
    private boolean isActionable(double score, List<Warning> warnings, QuantScoreBreakdown breakdown) {
        // Check score threshold
        if (score < config.getEmission().getMinScore()) {
            return false;
        }

        // Check for critical warnings
        boolean hasCritical = warnings.stream()
            .anyMatch(w -> w.getSeverity() == Warning.WarningSeverity.CRITICAL);
        if (hasCritical) {
            return false;
        }

        // Check minimum categories above threshold
        int strongCategories = countStrongCategories(breakdown);
        if (strongCategories < config.getEmission().getMinCategoriesAboveThreshold()) {
            return false;
        }

        return true;
    }

    /**
     * Determine reason for non-actionability
     */
    private String determineNonActionableReason(double score, List<Warning> warnings,
                                                 QuantScoreBreakdown breakdown) {
        if (score < config.getEmission().getMinScore()) {
            return String.format("Score %.1f below threshold %.1f",
                score, config.getEmission().getMinScore());
        }

        boolean hasCritical = warnings.stream()
            .anyMatch(w -> w.getSeverity() == Warning.WarningSeverity.CRITICAL);
        if (hasCritical) {
            return "Critical warning present: " + warnings.stream()
                .filter(w -> w.getSeverity() == Warning.WarningSeverity.CRITICAL)
                .findFirst()
                .map(Warning::getMessage)
                .orElse("Unknown");
        }

        int strongCategories = countStrongCategories(breakdown);
        if (strongCategories < config.getEmission().getMinCategoriesAboveThreshold()) {
            return String.format("Only %d categories above threshold (need %d)",
                strongCategories, config.getEmission().getMinCategoriesAboveThreshold());
        }

        return "Unknown";
    }

    /**
     * Calculate overall confidence score
     */
    private double calculateOverallConfidence(DataQuality quality, QuantScoreBreakdown breakdown,
                                               List<Warning> warnings) {
        double confidence = 0.5;

        // Add for data completeness
        confidence += quality.getCompletenessScore() * 0.3;

        // Add for category agreement
        int strongCategories = countStrongCategories(breakdown);
        confidence += (strongCategories / 8.0) * 0.2;

        // Subtract for warnings
        int criticalCount = (int) warnings.stream()
            .filter(w -> w.getSeverity() == Warning.WarningSeverity.CRITICAL).count();
        int highCount = (int) warnings.stream()
            .filter(w -> w.getSeverity() == Warning.WarningSeverity.HIGH).count();

        confidence -= criticalCount * 0.2;
        confidence -= highCount * 0.1;

        return Math.max(0, Math.min(1, confidence));
    }
}
