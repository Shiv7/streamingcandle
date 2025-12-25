package com.kotsin.consumer.signal.service;

import com.kotsin.consumer.model.IPUOutput;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.signal.model.FUDKIIOutput;
import com.kotsin.consumer.signal.model.FUDKIIOutput.IgnitionDirection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * FUDKIICalculator - Module 5: FUDKII (Ignition Engine)
 * 
 * Detects first valid volatility expansion using simultaneity check:
 * 1. Price breaking recent high/low
 * 2. Volume surging > 2x average
 * 3. Momentum positive (from IPU)
 * 4. ATR expanding > 1.2x average
 * 5. Flow confirming (delta in price direction)
 * 
 * Ignition flag = simultaneity >= 3 AND atrRatio > 1.2
 */
@Slf4j
@Service
public class FUDKIICalculator {

    // Thresholds
    @Value("${fudkii.volume.surge.ratio:2.0}")
    private double volumeSurgeRatio;

    @Value("${fudkii.atr.expansion.ratio:1.2}")
    private double atrExpansionRatio;

    @Value("${fudkii.momentum.threshold:0.5}")
    private double momentumThreshold;

    @Value("${fudkii.simultaneity.min:3}")
    private int minSimultaneity;

    @Value("${fudkii.lookback.period:20}")
    private int lookbackPeriod;

    /**
     * Calculate FUDKII ignition detection
     * 
     * @param scripCode Security's scrip code
     * @param companyName Security's company name
     * @param candles Candle history (most recent last)
     * @param ipuOutput IPU output for momentum context (can be null)
     * @return FUDKIIOutput with ignition detection results
     */
    public FUDKIIOutput calculate(
            String scripCode,
            String companyName,
            List<UnifiedCandle> candles,
            IPUOutput ipuOutput) {

        if (candles == null || candles.size() < 5) {
            return emptyResult(scripCode, companyName);
        }

        UnifiedCandle current = candles.get(candles.size() - 1);
        UnifiedCandle prev = candles.get(candles.size() - 2);

        // Calculate averages
        double avgVolume = calculateAverageVolume(candles, lookbackPeriod);
        double avgAtr = calculateAverageATR(candles, lookbackPeriod);
        double currentAtr = calculateCurrentATR(candles, 14);
        double recentHigh = calculateRecentHigh(candles, lookbackPeriod);
        double recentLow = calculateRecentLow(candles, lookbackPeriod);

        // Condition 1: Price breaking
        boolean priceBreaking = current.getClose() > recentHigh || current.getClose() < recentLow;
        double priceVsHighLow = 0;
        if (current.getClose() > recentHigh) {
            priceVsHighLow = (current.getClose() - recentHigh) / recentHigh;
        } else if (current.getClose() < recentLow) {
            priceVsHighLow = (recentLow - current.getClose()) / recentLow;
        }

        // Condition 2: Volume surging
        double volumeRatio = avgVolume > 0 ? current.getVolume() / avgVolume : 1.0;
        boolean volumeSurging = volumeRatio >= volumeSurgeRatio;

        // Condition 3: Momentum positive (from IPU)
        double momentumScore = 0;
        if (ipuOutput != null) {
            momentumScore = ipuOutput.getMomentumContext();
        } else {
            // Calculate simple momentum if no IPU
            momentumScore = calculateSimpleMomentum(candles, 5);
        }
        boolean momentumPositive = momentumScore >= momentumThreshold;

        // Condition 4: ATR expanding
        double atrRatio = avgAtr > 0 ? currentAtr / avgAtr : 1.0;
        boolean atrExpanding = atrRatio >= atrExpansionRatio;

        // Condition 5: Flow confirming
        double priceChange = current.getClose() - current.getOpen();
        double volumeDelta = current.getBuyVolume() - current.getSellVolume();
        double flowScore = Math.abs(volumeDelta) / (current.getVolume() + 1);
        boolean flowConfirming = (priceChange > 0 && volumeDelta > 0) || 
                                (priceChange < 0 && volumeDelta < 0);

        // Calculate simultaneity
        int simultaneity = 0;
        if (priceBreaking) simultaneity++;
        if (volumeSurging) simultaneity++;
        if (momentumPositive) simultaneity++;
        if (atrExpanding) simultaneity++;
        if (flowConfirming) simultaneity++;

        // Determine ignition
        boolean ignitionFlag = simultaneity >= minSimultaneity && atrRatio >= atrExpansionRatio;

        // Calculate FUDKII strength
        double fudkiiStrength = calculateFUDKIIStrength(
                simultaneity, priceVsHighLow, volumeRatio, atrRatio, momentumScore, flowScore
        );

        // Determine direction
        IgnitionDirection direction;
        if (!ignitionFlag) {
            direction = IgnitionDirection.NO_IGNITION;
        } else if (current.getClose() > prev.getClose()) {
            direction = IgnitionDirection.BULLISH_IGNITION;
        } else {
            direction = IgnitionDirection.BEARISH_IGNITION;
        }

        return FUDKIIOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(System.currentTimeMillis())
                .fudkiiStrength(fudkiiStrength)
                .simultaneityScore(simultaneity)
                .ignitionFlag(ignitionFlag)
                .priceBreaking(priceBreaking)
                .volumeSurging(volumeSurging)
                .momentumPositive(momentumPositive)
                .atrExpanding(atrExpanding)
                .flowConfirming(flowConfirming)
                .priceVsHighLow(priceVsHighLow)
                .volumeRatio(volumeRatio)
                .atrRatio(atrRatio)
                .momentumScore(momentumScore)
                .flowScore(flowScore)
                .direction(direction)
                .build();
    }

    /**
     * Calculate FUDKII strength from all inputs
     */
    private double calculateFUDKIIStrength(int simultaneity, double priceVsHighLow, 
                                           double volumeRatio, double atrRatio,
                                           double momentumScore, double flowScore) {
        // Base score from simultaneity
        double baseScore = simultaneity / 5.0;

        // Amplifiers
        double volumeAmplifier = Math.min(volumeRatio / volumeSurgeRatio, 1.5);
        double atrAmplifier = Math.min(atrRatio / atrExpansionRatio, 1.5);
        double momentumAmplifier = Math.min(momentumScore, 1.0);

        double strength = baseScore 
            * (0.5 + 0.2 * volumeAmplifier)
            * (0.5 + 0.2 * atrAmplifier)
            * (0.5 + 0.3 * momentumAmplifier);

        return Math.min(strength, 1.0);
    }

    /**
     * Calculate average volume over period
     */
    private double calculateAverageVolume(List<UnifiedCandle> candles, int period) {
        int actualPeriod = Math.min(period, candles.size() - 1);
        if (actualPeriod <= 0) return 1;

        double sum = 0;
        for (int i = candles.size() - 1 - actualPeriod; i < candles.size() - 1; i++) {
            if (i >= 0) sum += candles.get(i).getVolume();
        }
        return sum / actualPeriod;
    }

    /**
     * Calculate average ATR over period
     */
    private double calculateAverageATR(List<UnifiedCandle> candles, int period) {
        if (candles.size() < period + 14) {
            return calculateCurrentATR(candles, 14);
        }

        double sum = 0;
        int count = 0;
        for (int i = 14; i < candles.size() - 1; i++) {
            List<UnifiedCandle> sublist = candles.subList(Math.max(0, i - 14), i + 1);
            sum += calculateCurrentATR(sublist, 14);
            count++;
        }
        return count > 0 ? sum / count : calculateCurrentATR(candles, 14);
    }

    /**
     * Calculate current ATR
     */
    private double calculateCurrentATR(List<UnifiedCandle> candles, int period) {
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
     * Calculate recent high (excluding current candle)
     */
    private double calculateRecentHigh(List<UnifiedCandle> candles, int period) {
        double high = 0;
        int actualPeriod = Math.min(period, candles.size() - 1);
        for (int i = candles.size() - 1 - actualPeriod; i < candles.size() - 1; i++) {
            if (i >= 0) high = Math.max(high, candles.get(i).getHigh());
        }
        return high;
    }

    /**
     * Calculate recent low (excluding current candle)
     */
    private double calculateRecentLow(List<UnifiedCandle> candles, int period) {
        double low = Double.MAX_VALUE;
        int actualPeriod = Math.min(period, candles.size() - 1);
        for (int i = candles.size() - 1 - actualPeriod; i < candles.size() - 1; i++) {
            if (i >= 0) low = Math.min(low, candles.get(i).getLow());
        }
        return low == Double.MAX_VALUE ? 0 : low;
    }

    /**
     * Calculate simple momentum (price change / ATR)
     */
    private double calculateSimpleMomentum(List<UnifiedCandle> candles, int bars) {
        if (candles.size() < bars + 1) return 0;
        
        UnifiedCandle current = candles.get(candles.size() - 1);
        UnifiedCandle past = candles.get(candles.size() - 1 - bars);
        double atr = calculateCurrentATR(candles, 14);
        
        if (atr <= 0) return 0;
        double momentum = (current.getClose() - past.getClose()) / atr;
        return Math.min(Math.abs(momentum), 1.0);
    }

    /**
     * Empty result for insufficient data
     */
    private FUDKIIOutput emptyResult(String scripCode, String companyName) {
        return FUDKIIOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(System.currentTimeMillis())
                .fudkiiStrength(0)
                .simultaneityScore(0)
                .ignitionFlag(false)
                .direction(IgnitionDirection.NO_IGNITION)
                .build();
    }
}
