package com.kotsin.consumer.regime.service;

import com.kotsin.consumer.model.EnrichedCandlestick;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.regime.model.IndexRegime;
import com.kotsin.consumer.regime.model.RegimeLabel;
import com.kotsin.consumer.regime.model.SecurityRegime;
import com.kotsin.consumer.regime.model.SecurityRegime.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

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
public class SecurityRegimeCalculator {

    // EMA periods (in candles)
    @Value("${regime.ema.period.fast:12}")
    private int emaPeriodFast;

    @Value("${regime.ema.period.medium:60}")
    private int emaPeriodMedium;

    @Value("${regime.ema.period.slow:240}")
    private int emaPeriodSlow;

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
     * @param parentIndexRegime Parent index regime (NIFTY50 for most stocks)
     * @return SecurityRegime with all computed metrics
     */
    public SecurityRegime calculate(
            String scripCode,
            String companyName,
            List<UnifiedCandle> candles30m,
            IndexRegime parentIndexRegime) {

        if (candles30m == null || candles30m.size() < 5) {
            return emptyResult(scripCode, companyName);
        }

        UnifiedCandle current = candles30m.get(candles30m.size() - 1);

        // Calculate EMAs
        double ema12 = calculateEMA(candles30m, emaPeriodFast);
        double ema60 = calculateEMA(candles30m, emaPeriodMedium);
        double ema240 = calculateEMA(candles30m, emaPeriodSlow);

        // Determine EMA alignment
        EMAAlignment emaAlignment = EMAAlignment.fromEMAs(ema12, ema60, ema240);

        // Calculate ATR
        double atr14 = calculateATR(candles30m, 14);
        double avgAtr20 = calculateAverageATR(candles30m, 20);
        double atrRatio = avgAtr20 > 0 ? atr14 / avgAtr20 : 1.0;
        ATRState atrState = ATRState.fromRatio(atrRatio);

        // Calculate security flow direction
        int securityFlowSign = calculateFlowSign(candles30m, 3);

        // Get index flow from parent regime
        int indexFlowSign = parentIndexRegime != null ? parentIndexRegime.getFlowAgreement() : 0;
        String parentIndexCode = parentIndexRegime != null ? parentIndexRegime.getScripCode() : "";

        // Check alignment
        boolean alignedWithIndex = (securityFlowSign == indexFlowSign) || indexFlowSign == 0;

        // Calculate index flow multiplier
        double indexFlowMultiplier;
        if (indexFlowSign == 0) {
            indexFlowMultiplier = 1.0;  // Neutral index, no penalty/boost
        } else if (securityFlowSign == indexFlowSign) {
            indexFlowMultiplier = alignmentBoost;  // 10% boost
        } else if (securityFlowSign == -indexFlowSign) {
            indexFlowMultiplier = divergencePenalty;  // 25% penalty
        } else {
            indexFlowMultiplier = 1.0;  // Security neutral
        }

        // Calculate security strength from EMA alignment and ATR
        double emaStrength = calculateEMAStrength(emaAlignment, ema12, ema60, ema240, current.getClose());
        double atrStrength = atrState == ATRState.EXPANDING ? 0.8 :
                            (atrState == ATRState.COMPRESSED ? 0.3 : 0.5);
        double securityStrength = 0.7 * emaStrength + 0.3 * atrStrength;

        // Calculate index alignment score
        double indexAlignmentScore = 0.5;  // Default
        if (parentIndexRegime != null) {
            boolean sameTrend = (emaAlignment.isBullish() && parentIndexRegime.getLabel().isBullish()) ||
                               (emaAlignment.isBearish() && parentIndexRegime.getLabel().isBearish());
            if (sameTrend && alignedWithIndex) {
                indexAlignmentScore = 1.0;
            } else if (sameTrend || alignedWithIndex) {
                indexAlignmentScore = 0.7;
            } else {
                indexAlignmentScore = 0.3;
            }
        }

        // Calculate divergence score (inverse of alignment)
        double divergenceScore = 1 - indexAlignmentScore;

        // Final regime score with index adjustment
        double finalRegimeScore = securityStrength * indexFlowMultiplier;
        finalRegimeScore = Math.min(finalRegimeScore, 1.0);

        // Determine label
        int direction = emaAlignment.isBullish() ? 1 : (emaAlignment.isBearish() ? -1 : 0);
        RegimeLabel label = RegimeLabel.fromStrengthAndDirection(finalRegimeScore, direction);

        return SecurityRegime.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .parentIndexCode(parentIndexCode)
                .timestamp(System.currentTimeMillis())
                .ema12(ema12)
                .ema60(ema60)
                .ema240(ema240)
                .emaAlignment(emaAlignment)
                .atr14(atr14)
                .avgAtr20(avgAtr20)
                .atrExpansionRatio(atrRatio)
                .atrState(atrState)
                .securityStrength(securityStrength)
                .indexAlignmentScore(indexAlignmentScore)
                .divergenceScore(divergenceScore)
                .label(label)
                .indexFlowMultiplier(indexFlowMultiplier)
                .alignedWithIndex(alignedWithIndex)
                .finalRegimeScore(finalRegimeScore)
                .securityFlowSign(securityFlowSign)
                .indexFlowSign(indexFlowSign)
                .build();
    }

    /**
     * Calculate EMA for given period
     */
    private double calculateEMA(List<UnifiedCandle> candles, int period) {
        if (candles.isEmpty()) return 0;
        
        int actualPeriod = Math.min(period, candles.size());
        double multiplier = 2.0 / (actualPeriod + 1);
        
        // Start with SMA for initial value
        double sum = 0;
        for (int i = 0; i < actualPeriod && i < candles.size(); i++) {
            sum += candles.get(i).getClose();
        }
        double ema = sum / actualPeriod;
        
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
     * Calculate strength from EMA alignment
     */
    private double calculateEMAStrength(EMAAlignment alignment, double ema12, double ema60, double ema240, double close) {
        double baseStrength;
        switch (alignment) {
            case BULLISH_ALIGNED:
            case BEARISH_ALIGNED:
                baseStrength = 0.8;
                break;
            case MIXED_BULLISH:
            case MIXED_BEARISH:
                baseStrength = 0.5;
                break;
            default:
                baseStrength = 0.2;
        }
        
        // Bonus if price is above/below all EMAs (confirming trend)
        if (alignment.isBullish() && close > ema12 && close > ema60) {
            baseStrength *= 1.1;
        } else if (alignment.isBearish() && close < ema12 && close < ema60) {
            baseStrength *= 1.1;
        }
        
        return Math.min(baseStrength, 1.0);
    }

    /**
     * Empty result for insufficient data
     */
    private SecurityRegime emptyResult(String scripCode, String companyName) {
        return SecurityRegime.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(System.currentTimeMillis())
                .emaAlignment(EMAAlignment.CHOPPY)
                .atrState(ATRState.NORMAL)
                .label(RegimeLabel.NEUTRAL)
                .securityStrength(0)
                .finalRegimeScore(0)
                .indexFlowMultiplier(1.0)
                .alignedWithIndex(false)
                .build();
    }
}
