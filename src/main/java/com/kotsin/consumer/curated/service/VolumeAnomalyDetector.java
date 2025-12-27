package com.kotsin.consumer.curated.service;

import com.kotsin.consumer.model.UnifiedCandle;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * VolumeAnomalyDetector - Detects abnormal volume spikes
 *
 * Uses statistical methods to identify when volume is significantly
 * higher than normal, indicating institutional activity or breakout confirmation.
 */
@Service
public class VolumeAnomalyDetector {

    private static final double ABNORMAL_MULTIPLIER = 2.0;  // 2x average
    private static final double ZSCORE_THRESHOLD = 2.0;     // 2 standard deviations

    /**
     * Calculate volume Z-score
     * Z-score = (current - mean) / stdDev
     * Tells us how many standard deviations away from mean
     */
    public double calculateVolumeZScore(UnifiedCandle currentCandle, List<UnifiedCandle> history) {
        if (history == null || history.size() < 20) {
            return 0.0;
        }

        // Calculate mean volume
        double mean = history.stream()
                .mapToLong(UnifiedCandle::getVolume)
                .average()
                .orElse(0);

        if (mean == 0) return 0.0;

        // Calculate standard deviation
        double variance = history.stream()
                .mapToDouble(c -> Math.pow(c.getVolume() - mean, 2))
                .average()
                .orElse(0);

        double stdDev = Math.sqrt(variance);

        if (stdDev == 0) return 0.0;

        // Calculate Z-score
        return (currentCandle.getVolume() - mean) / stdDev;
    }

    /**
     * Check if volume is abnormal (> 2x average)
     */
    public boolean isAbnormalVolume(UnifiedCandle candle, List<UnifiedCandle> history) {
        if (history == null || history.isEmpty()) {
            return false;
        }

        double avgVolume = history.stream()
                .mapToLong(UnifiedCandle::getVolume)
                .average()
                .orElse(0);

        return candle.getVolume() > (ABNORMAL_MULTIPLIER * avgVolume);
    }

    /**
     * Get average volume from history
     */
    public double getAverageVolume(List<UnifiedCandle> history) {
        if (history == null || history.isEmpty()) {
            return 0.0;
        }

        return history.stream()
                .mapToLong(UnifiedCandle::getVolume)
                .average()
                .orElse(0.0);
    }

    /**
     * Check Kyle's Lambda spike (high price impact = low liquidity = institutional)
     */
    public boolean isKyleLambdaSpike(UnifiedCandle candle, List<UnifiedCandle> history) {
        if (history == null || history.size() < 20) {
            return false;
        }

        // Calculate 75th percentile of Kyle's Lambda
        double[] kyleLambdas = history.stream()
                .mapToDouble(UnifiedCandle::getKyleLambda)
                .filter(k -> k > 0)  // Filter out zeros
                .sorted()
                .toArray();

        if (kyleLambdas.length == 0) {
            return false;
        }

        int p75Index = (int) (kyleLambdas.length * 0.75);
        double p75 = kyleLambdas[Math.min(p75Index, kyleLambdas.length - 1)];

        // Current Kyle's Lambda should be > 75th percentile
        return candle.getKyleLambda() > p75;
    }

    /**
     * Check if volume delta shows buying pressure
     */
    public boolean hasBuyingPressure(UnifiedCandle candle) {
        // Buy volume > Sell volume
        return candle.getVolumeDelta() > 0;
    }

    /**
     * Check OFI (Order Flow Imbalance) for buying pressure
     */
    public boolean hasOFIBuyingPressure(UnifiedCandle candle) {
        return candle.getOfi() > 0;
    }

    /**
     * Check VPIN for directional volume
     */
    public boolean hasVPINConfirmation(UnifiedCandle candle) {
        return candle.getVpin() > 0.5;
    }
}
