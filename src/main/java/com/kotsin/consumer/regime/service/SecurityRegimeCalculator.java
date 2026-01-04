package com.kotsin.consumer.regime.service;

import com.kotsin.consumer.curated.model.MultiTimeframeLevels;
import com.kotsin.consumer.curated.service.MultiTimeframeLevelCalculator;
import com.kotsin.consumer.infrastructure.redis.RedisCorrelationService;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.regime.model.IndexRegime;
import com.kotsin.consumer.regime.model.RegimeLabel;
import com.kotsin.consumer.regime.model.SecurityRegime;
import com.kotsin.consumer.regime.model.SecurityRegime.*;
import com.kotsin.consumer.util.StructureAnalyzer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SecurityRegimeCalculator - Module 2: Security Regime
 * 
 * Determines if individual security is aligned with or diverging from index regime.
 * Implements:
 * - EMA ordering analysis (12, 60, 240)
 * - ATR expansion/compression detection
 * - Index flow penalty/boost application
 * 
 * If security flow differs from index, apply 25% penalty.
 * If aligned, apply 10% boost.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityRegimeCalculator {

    private final StructureAnalyzer structureAnalyzer;
    private final MultiTimeframeLevelCalculator levelCalculator;
    private final RedisCorrelationService correlationService;

    @Autowired(required = false)
    private com.kotsin.consumer.logging.PipelineTraceLogger traceLogger;

    // Penalty/boost factors
    @Value("${regime.divergence.penalty:0.75}")
    private double divergencePenalty;

    @Value("${regime.alignment.boost:1.10}")
    private double alignmentBoost;

    /**
     * Calculate Security Regime from 30m candle history and parent index regime
     * 
     * @param scripCode Security's scrip code
     * @param companyName Security's company name
     * @param candles30m 30m candle history (most recent last)
     * @param candles1D 1D candle history (most recent last) - needed for structure quality
     * @param parentIndexRegime Parent index regime (NIFTY50 for most stocks)
     * @param volumeCertainty Volume certainty from VolumeCanonicalCalculator (needed for Micro-Leader override)
     * @param correlation Correlation with index from RedisCorrelationService (needed for Micro-Leader override)
     * @return SecurityRegime with all computed metrics
     */
    public SecurityRegime calculate(
            String scripCode,
            String companyName,
            List<UnifiedCandle> candles30m,
            List<UnifiedCandle> candles1D,
            IndexRegime parentIndexRegime,
            double volumeCertainty,
            double correlation) {

        if (candles30m == null || candles30m.size() < 50) { // Need at least 50 for EMA50
            return emptyResult(scripCode, companyName);
        }

        UnifiedCandle current = candles30m.get(candles30m.size() - 1);
        double close = current.getClose();

        // MASTER ARCHITECTURE - EMA20/50 for trend
        double ema20 = calculateEMA(candles30m, 20);
        double ema50 = calculateEMA(candles30m, 50);

        // Step 1: Trend Direction
        int trendDirection = (ema20 > ema50) ? 1 : ((ema20 < ema50) ? -1 : 0);

        // Step 2: Trend Persistence
        int consecutiveBars = calculatePersistence(candles30m, 20);
        double trendPersistence = Math.min((double) consecutiveBars / 20.0, 1.0);

        // Step 3: Relative Strength vs Index
        double relativeStrength = calculateRelativeStrength(candles30m, parentIndexRegime);

        // Step 4: ATR Expansion
        double atr14 = calculateATR(candles30m, 14);
        double avgAtr20 = calculateAverageATR(candles30m, 20);
        double minAtr = close * 0.001;
        double effectiveAvgAtr = Math.max(avgAtr20, minAtr);
        double atrExpansion = effectiveAvgAtr > 0 ? (atr14 - avgAtr20) / effectiveAvgAtr : 0.0;
        double atrRatio = effectiveAvgAtr > 0 ? atr14 / effectiveAvgAtr : 0;
        ATRState atrState = ATRState.fromRatio(atrRatio);

        // Step 5: Structure Quality
        double structureQuality = structureAnalyzer.calculateStructureQuality(candles30m, candles1D);

        // Step 6: Breakout Quality
        double breakoutQuality = calculateBreakoutQuality(current, candles30m, atr14, volumeCertainty);

        // Calculate Raw Security Strength (MASTER ARCHITECTURE SPEC FORMULA)
        double rawSecurityStrength =
            0.30 * trendDirection +
            0.20 * trendPersistence * trendDirection +
            0.20 * relativeStrength +
            0.15 * atrExpansion * trendDirection +
            0.10 * structureQuality +
            0.05 * breakoutQuality * trendDirection;

        // Get Index Regime Strength
        double indexRegimeStrength = parentIndexRegime != null ? 
            parentIndexRegime.getRegimeStrength() : 0.5;

        // Calculate Flow Alignment Multiplier
        int securityFlowSign = calculateFlowSign(candles30m, 3);
        int indexFlowSign = parentIndexRegime != null ? parentIndexRegime.getFlowAgreement() : 0;
        String parentIndexCode = parentIndexRegime != null ? parentIndexRegime.getScripCode() : "";
        
        boolean alignedWithIndex = (securityFlowSign == indexFlowSign) || indexFlowSign == 0;
        double flowAlignmentMultiplier;
        if (indexFlowSign == 0) {
            flowAlignmentMultiplier = 1.0;
        } else if (securityFlowSign == indexFlowSign) {
            flowAlignmentMultiplier = alignmentBoost; // 1.10
        } else if (securityFlowSign == -indexFlowSign) {
            flowAlignmentMultiplier = divergencePenalty; // 0.75
        } else {
            flowAlignmentMultiplier = 1.0;
        }

        // Apply Index Regime Strength multiplication (normal path)
        double securityRegimeStrength = rawSecurityStrength * indexRegimeStrength * flowAlignmentMultiplier;

        // Micro-Leader Override Logic
        boolean microLeaderOverrideApplied = false;
        double effectiveIndexCoupling = indexRegimeStrength;
        double microLeaderDecoupleFactor = 0.0;

        if (shouldApplyMicroLeaderOverride(correlation, rawSecurityStrength, volumeCertainty, parentIndexRegime)) {
            microLeaderDecoupleFactor = (volumeCertainty >= 0.90 && structureQuality >= 0.75) ? 0.40 : 0.30;
            effectiveIndexCoupling = indexRegimeStrength * (1 - microLeaderDecoupleFactor);
            effectiveIndexCoupling = Math.max(effectiveIndexCoupling, 0.35); // Hard floor

            // Check: Never apply when index is strongly opposite
            if (parentIndexRegime != null && parentIndexRegime.getIndexContextScore() <= -0.50 && trendDirection > 0) {
                // Don't apply override - index strongly bearish but security is bullish
                microLeaderOverrideApplied = false;
            } else {
                securityRegimeStrength = rawSecurityStrength * effectiveIndexCoupling * flowAlignmentMultiplier;
                microLeaderOverrideApplied = true;
            }
        }

        // Final Context Score
        double securityContextScore = securityRegimeStrength;
        securityContextScore = Math.min(securityContextScore, 1.0); // Clamp to [0, 1]

        // Determine label
        RegimeLabel label = RegimeLabel.fromStrengthAndDirection(securityContextScore, trendDirection);

        // Logging at each step
        log.debug("[SecurityRegime] {} | EMA20={:.2f} EMA50={:.2f} trendDir={} trendPersistence={:.3f} ({} bars)", 
            scripCode, ema20, ema50, trendDirection, trendPersistence, consecutiveBars);
        log.debug("[SecurityRegime] {} | relativeStrength={:.3f} atrExpansion={:.3f} structureQuality={:.3f} breakoutQuality={:.3f}", 
            scripCode, relativeStrength, atrExpansion, structureQuality, breakoutQuality);
        log.debug("[SecurityRegime] {} | rawStrength={:.3f} indexRegimeStrength={:.3f} flowMult={:.2f}", 
            scripCode, rawSecurityStrength, indexRegimeStrength, flowAlignmentMultiplier);
        
        if (microLeaderOverrideApplied) {
            log.info("[SecurityRegime] {} | MICRO-LEADER OVERRIDE | decouple={:.2f} effectiveCoupling={:.3f}", 
                scripCode, microLeaderDecoupleFactor, effectiveIndexCoupling);
        }
        
        log.info("[SecurityRegime] {} | FINAL contextScore={:.3f}", 
            scripCode, securityContextScore);

        SecurityRegime result = SecurityRegime.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .parentIndexCode(parentIndexCode)
                .timestamp(System.currentTimeMillis())
                // MASTER ARCHITECTURE - EMA20/50
                .ema20(ema20)
                .ema50(ema50)
                .trendDirection(trendDirection)
                .trendPersistence(trendPersistence)
                .relativeStrength(relativeStrength)
                .atrExpansion(atrExpansion)
                .structureQuality(structureQuality)
                .breakoutQuality(breakoutQuality)
                .rawSecurityStrength(rawSecurityStrength)
                // Micro-Leader Override
                .microLeaderOverrideApplied(microLeaderOverrideApplied)
                .effectiveIndexCoupling(effectiveIndexCoupling)
                .microLeaderDecoupleFactor(microLeaderDecoupleFactor)
                // ATR Analysis
                .atr14(atr14)
                .avgAtr20(avgAtr20)
                .atrExpansionRatio(atrRatio)
                .atrState(atrState)
                // Final scores
                .securityContextScore(securityContextScore)
                .finalRegimeScore(securityContextScore) // Legacy field
                .label(label)
                .indexFlowMultiplier(flowAlignmentMultiplier)
                .alignedWithIndex(alignedWithIndex)
                .securityFlowSign(securityFlowSign)
                .indexFlowSign(indexFlowSign)
                .build();

        // Log security regime calculation with integrated logging
        if (traceLogger != null) {
            // Use logRegimeCalculated for basic info
            traceLogger.logRegimeCalculated(
                scripCode, "SEC",
                result.getTimestamp(),
                result.getLabel() != null ? result.getLabel().name() : "N/A",
                result.getSecurityContextScore(),
                "EMA20/50", // EMA alignment (simplified for new formula)
                result.getAtrState() != null ? result.getAtrState().name() : "N/A",
                result.isAlignedWithIndex(),
                result.getIndexFlowMultiplier()
            );

            // Also log with indicator values using logSignalAccepted
            Map<String, Object> indicators = new HashMap<>();
            indicators.put("ema20", ema20);
            indicators.put("ema50", ema50);
            indicators.put("trendDir", trendDirection);
            indicators.put("trendPersistence", trendPersistence);
            indicators.put("relativeStrength", relativeStrength);
            indicators.put("atrExpansion", atrExpansion);
            indicators.put("structureQuality", structureQuality);
            indicators.put("breakoutQuality", breakoutQuality);
            indicators.put("rawStrength", rawSecurityStrength);
            indicators.put("indexRegimeStrength", indexRegimeStrength);
            indicators.put("flowMultiplier", flowAlignmentMultiplier);
            if (microLeaderOverrideApplied) {
                indicators.put("microLeaderDecouple", microLeaderDecoupleFactor);
                indicators.put("effectiveCoupling", effectiveIndexCoupling);
            }
            indicators.put("atr14", atr14);
            indicators.put("atrRatio", atrRatio);
            indicators.put("contextScore", securityContextScore);

            String reason = String.format("EMA-based formula | aligned=%s microLeader=%s",
                alignedWithIndex ? "✓" : "✗",
                microLeaderOverrideApplied ? "✓" : "✗");

            traceLogger.logSignalAccepted("SECURITY-REGIME", scripCode,
                result.getTimestamp(), reason, indicators);
        }

        return result;
    }

    /**
     * Calculate EMA for given period
     */
    private double calculateEMA(List<UnifiedCandle> candles, int period) {
        if (candles.isEmpty()) return 0;
        
        int actualPeriod = Math.min(period, candles.size());
        // FIX: Guard against division by zero
        if (actualPeriod <= 0) return candles.get(candles.size() - 1).getClose();
        
        double multiplier = 2.0 / (actualPeriod + 1);
        
        // Start with SMA for initial value
        double sum = 0;
        for (int i = 0; i < actualPeriod && i < candles.size(); i++) {
            sum += candles.get(i).getClose();
        }
        double ema = actualPeriod > 0 ? sum / actualPeriod : candles.get(candles.size() - 1).getClose();
        
        // Calculate EMA for remaining candles
        for (int i = actualPeriod; i < candles.size(); i++) {
            ema = (candles.get(i).getClose() - ema) * multiplier + ema;
        }
        
        return ema;
    }

    /**
     * Calculate ATR for given period
     */
    private double calculateATR(List<UnifiedCandle> candles, int period) {
        int actualPeriod = Math.min(period, candles.size());
        if (actualPeriod <= 1) return 0;

        double sum = 0;
        for (int i = candles.size() - actualPeriod; i < candles.size(); i++) {
            if (i < 0) continue;
            UnifiedCandle c = candles.get(i);
            double tr = c.getHigh() - c.getLow();
            
            if (i > 0) {
                double prevClose = candles.get(i - 1).getClose();
                tr = Math.max(tr, Math.abs(c.getHigh() - prevClose));
                tr = Math.max(tr, Math.abs(c.getLow() - prevClose));
            }
            sum += tr;
        }
        return sum / actualPeriod;
    }

    /**
     * Calculate average ATR over longer period
     */
    private double calculateAverageATR(List<UnifiedCandle> candles, int period) {
        if (candles.size() < period) {
            return calculateATR(candles, candles.size());
        }
        
        double sum = 0;
        int count = 0;
        for (int i = 14; i < candles.size(); i++) {
            List<UnifiedCandle> sublist = candles.subList(Math.max(0, i - 14), i + 1);
            sum += calculateATR(sublist, 14);
            count++;
        }
        return count > 0 ? sum / count : calculateATR(candles, 14);
    }

    /**
     * Calculate flow sign over N bars
     */
    private int calculateFlowSign(List<UnifiedCandle> candles, int bars) {
        if (candles.size() < bars) return 0;
        
        double totalDelta = 0;
        for (int i = candles.size() - bars; i < candles.size(); i++) {
            UnifiedCandle c = candles.get(i);
            totalDelta += c.getBuyVolume() - c.getSellVolume();
        }
        
        if (totalDelta > 0) return 1;
        if (totalDelta < 0) return -1;
        return 0;
    }


    /**
     * Calculate persistence: count consecutive bars with same trend direction
     */
    private int calculatePersistence(List<UnifiedCandle> candles, int lookback) {
        if (candles == null || candles.size() < 2) return 0;
        
        int actualLookback = Math.min(lookback, candles.size() - 1);
        if (actualLookback <= 0) return 0;
        
        // Calculate EMA20 and EMA50 to determine current trend
        double ema20 = calculateEMA(candles, 20);
        double ema50 = calculateEMA(candles, 50);
        int currentTrendDir = (ema20 > ema50) ? 1 : ((ema20 < ema50) ? -1 : 0);
        
        if (currentTrendDir == 0) return 0;
        
        // Count consecutive bars with same trend direction
        int consecutiveCount = 0;
        for (int i = candles.size() - 1; i > 0 && consecutiveCount < actualLookback; i--) {
            // Need enough candles to calculate EMA for this bar
            if (i < 20) break;
            
            List<UnifiedCandle> sublist = candles.subList(0, i + 1);
            double prevEma20 = calculateEMA(sublist, 20);
            double prevEma50 = calculateEMA(sublist, 50);
            int prevTrendDir = (prevEma20 > prevEma50) ? 1 : ((prevEma20 < prevEma50) ? -1 : 0);
            
            if (prevTrendDir == currentTrendDir) {
                consecutiveCount++;
            } else {
                break;
            }
        }
        
        return consecutiveCount;
    }

    /**
     * Calculate relative strength vs index
     * Relative_Strength = (Sec_ROC20 - Idx_ROC20) / max(|Idx_ROC20|, 0.001)
     *
     * FIX: Now uses actual index regime data instead of hardcoded 0.0
     */
    private double calculateRelativeStrength(List<UnifiedCandle> candles30m, IndexRegime index) {
        if (candles30m == null || candles30m.size() < 21 || index == null) {
            return 0.0;
        }

        UnifiedCandle current = candles30m.get(candles30m.size() - 1);
        UnifiedCandle lag20 = candles30m.get(candles30m.size() - 21);

        // Security ROC20
        double securityROC20 = (current.getClose() - lag20.getClose()) / lag20.getClose();

        // Index ROC20 - use index regime's directionalBias as a proxy
        // DirectionalBias is already a normalized measure of index strength [-1, 1]
        // We can use it to approximate the index's momentum
        // Alternatively, use indexTrendStrength * indexTrendDir from the 30m timeframe
        double indexROC20 = 0.0;

        if (index.getTf30m() != null) {
            // Use index trend direction and strength from 30m timeframe
            // This gives us a directional measure of index performance
            int indexTrendDir = index.getTf30m().getIndexTrendDir();  // +1, -1, or 0
            double indexTrendStrength = index.getTf30m().getIndexTrendStrength();  // [0, 1]

            // Combine direction and strength to get index momentum proxy
            indexROC20 = indexTrendDir * indexTrendStrength * 0.1;  // Scale to approximate ROC range
        } else {
            // Fallback to overall directionalBias
            indexROC20 = index.getDirectionalBias() * 0.05;  // Scale to approximate ROC range
        }

        // Calculate relative strength
        // If index is moving (indexROC20 != 0), compare security vs index
        // If index is flat, relative strength is just the security's own momentum
        double denominator = Math.max(Math.abs(indexROC20), 0.001);
        double relativeStrength = (securityROC20 - indexROC20) / denominator;

        // Clamp to [-1, 1]
        return Math.max(-1.0, Math.min(1.0, relativeStrength));
    }

    /**
     * Calculate breakout quality
     * For bullish: max(close - resistanceLevel, 0) / ATR14
     * For bearish: max(supportLevel - close, 0) / ATR14
     */
    private double calculateBreakoutQuality(UnifiedCandle current, List<UnifiedCandle> candles30m, 
                                           double atr14, double volumeCertainty) {
        if (current == null || atr14 <= 0) return 0.0;
        
        try {
            MultiTimeframeLevels levels = levelCalculator.calculateLevels(current.getScripCode(), current.getClose());
            if (levels == null) return 0.0;
            
            double close = current.getClose();
            double breakoutQuality = 0.0;
            
            // Determine trend direction from current price vs pivots
            // Use daily pivot as reference
            MultiTimeframeLevels.PivotLevels dailyPivot = levels.getDailyPivot();
            if (dailyPivot == null) return 0.0;
            
            double pivot = dailyPivot.getPivot();
            
            // Check for bullish breakout (above resistance)
            if (close > pivot) {
                // Find nearest resistance level above current price
                double resistanceLevel = pivot;
                if (dailyPivot.getR1() > close) resistanceLevel = dailyPivot.getR1();
                else if (dailyPivot.getR2() > close) resistanceLevel = dailyPivot.getR2();
                else if (dailyPivot.getR3() > close) resistanceLevel = dailyPivot.getR3();
                else resistanceLevel = dailyPivot.getR4();
                
                if (resistanceLevel > pivot) {
                    double breakoutDistance = Math.max(close - resistanceLevel, 0);
                    breakoutQuality = breakoutDistance / atr14;
                }
            } else {
                // Check for bearish breakout (below support)
                double supportLevel = pivot;
                if (dailyPivot.getS1() < close) supportLevel = dailyPivot.getS1();
                else if (dailyPivot.getS2() < close) supportLevel = dailyPivot.getS2();
                else if (dailyPivot.getS3() < close) supportLevel = dailyPivot.getS3();
                else supportLevel = dailyPivot.getS4();
                
                if (supportLevel < pivot) {
                    double breakoutDistance = Math.max(supportLevel - close, 0);
                    breakoutQuality = breakoutDistance / atr14;
                }
            }
            
            // Clamp to [0, 1] (or 1.2 if Volume_Certainty >= 0.90)
            double maxValue = volumeCertainty >= 0.90 ? 1.2 : 1.0;
            breakoutQuality = Math.min(breakoutQuality, maxValue);
            if (volumeCertainty >= 0.90 && breakoutQuality > 1.0) {
                breakoutQuality = Math.min(breakoutQuality, 1.2);
                // Then clamp to 1.0 for final value
                breakoutQuality = Math.min(breakoutQuality, 1.0);
            }
            
            return Math.max(0.0, breakoutQuality);
        } catch (Exception e) {
            log.warn("Error calculating breakout quality for {}: {}", current.getScripCode(), e.getMessage());
            return 0.0;
        }
    }

    /**
     * Check if Micro-Leader Override should be applied
     * Conditions (ALL must be true):
     * - Security_Weight < 0.05 (we skip this - user said avoid)
     * - Correlation(Security, Index) < 0.30
     * - Security_Regime_Strength > 0.75
     * - Volume_Certainty > 0.80
     */
    private boolean shouldApplyMicroLeaderOverride(double correlation, double rawSecurityStrength, 
                                                   double volumeCertainty, IndexRegime index) {
        // Check correlation
        if (Math.abs(correlation) >= 0.30) {
            return false; // Too correlated with index
        }
        
        // Check raw security strength
        if (rawSecurityStrength <= 0.75) {
            return false; // Not strong enough
        }
        
        // Check volume certainty
        if (volumeCertainty <= 0.80) {
            return false; // Not enough volume certainty
        }
        
        // Check: Never apply when index is strongly opposite
        if (index != null && index.getIndexContextScore() <= -0.50 && rawSecurityStrength > 0) {
            return false; // Index strongly bearish but security is bullish
        }
        
        return true;
    }

    /**
     * Empty result for insufficient data
     */
    private SecurityRegime emptyResult(String scripCode, String companyName) {
        return SecurityRegime.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(System.currentTimeMillis())
                .ema20(0.0)
                .ema50(0.0)
                .trendDirection(0)
                .trendPersistence(0.0)
                .relativeStrength(0.0)
                .atrExpansion(0.0)
                .structureQuality(0.0)
                .breakoutQuality(0.0)
                .rawSecurityStrength(0.0)
                .microLeaderOverrideApplied(false)
                .effectiveIndexCoupling(0.5)
                .microLeaderDecoupleFactor(0.0)
                .atr14(0.0)
                .avgAtr20(0.0)
                .atrExpansionRatio(0.0)
                .atrState(ATRState.NORMAL)
                .securityContextScore(0.0)
                .finalRegimeScore(0.0)
                .label(RegimeLabel.NEUTRAL)
                .indexFlowMultiplier(1.0)
                .alignedWithIndex(false)
                .build();
    }
}
