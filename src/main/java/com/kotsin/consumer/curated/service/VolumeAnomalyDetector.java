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
     * 
     * BUG-FIX: If volume data is missing (volume=0), use tick count as fallback.
     * Also, if average is 0, check if current volume is just non-zero.
     */
    public boolean isAbnormalVolume(UnifiedCandle candle, List<UnifiedCandle> history) {
        if (history == null || history.isEmpty()) {
            // No history - allow if current candle has any volume
            return candle.getVolume() > 0 || candle.getTickCount() > 10;
        }

        double avgVolume = history.stream()
                .mapToLong(UnifiedCandle::getVolume)
                .average()
                .orElse(0);

        // If average is 0 but current has volume, that's abnormal
        if (avgVolume == 0) {
            return candle.getVolume() > 0;
        }

        // Standard check: volume > 2x average
        if (candle.getVolume() > (ABNORMAL_MULTIPLIER * avgVolume)) {
            return true;
        }
        
        // BUG-FIX: If volume data is sparse, also check tick count
        // High tick count with normal volume could still indicate activity
        double avgTicks = history.stream()
                .mapToInt(UnifiedCandle::getTickCount)
                .average()
                .orElse(0);
        
        if (avgTicks > 0 && candle.getTickCount() > (ABNORMAL_MULTIPLIER * avgTicks)) {
            return true;
        }

        return false;
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
     * 
     * BUG-FIX: If no orderbook data available (kyleLambda=0), return TRUE to allow
     * breakout detection to proceed. Orderbook data is a bonus, not a requirement.
     */
    public boolean isKyleLambdaSpike(UnifiedCandle candle, List<UnifiedCandle> history) {
        if (history == null || history.size() < 20) {
            return true;  // Not enough history - allow breakout check to proceed
        }

        // Calculate 75th percentile of Kyle's Lambda
        double[] kyleLambdas = history.stream()
                .mapToDouble(UnifiedCandle::getKyleLambda)
                .filter(k -> k > 0)  // Filter out zeros
                .sorted()
                .toArray();

        // BUG-FIX: If no orderbook data (all zeros), return TRUE
        // Orderbook data is optional - don't block breakouts just because we lack it
        if (kyleLambdas.length == 0) {
            return true;  // No orderbook data available - allow breakout
        }

        int p75Index = (int) (kyleLambdas.length * 0.75);
        double p75 = kyleLambdas[Math.min(p75Index, kyleLambdas.length - 1)];

        // If current candle has no Kyle's Lambda, still allow (missing data != failure)
        if (candle.getKyleLambda() == 0) {
            return true;
        }

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
     * 
     * BUG-FIX: If no orderbook data available (ofi=0), fall back to volume delta.
     * Don't require OFI if we simply don't have orderbook data.
     */
    public boolean hasOFIBuyingPressure(UnifiedCandle candle) {
        // If we have OFI data, use it
        if (candle.getOfi() != 0) {
            return candle.getOfi() > 0;
        }
        
        // BUG-FIX: Fallback to volume delta when OFI not available
        // Volume delta = buyVolume - sellVolume
        // Positive delta indicates buying pressure
        if (candle.getVolumeDelta() != 0) {
            return candle.getVolumeDelta() > 0;
        }
        
        // If no OFI and no volume delta, use buy/sell volume comparison
        if (candle.getBuyVolume() > 0 || candle.getSellVolume() > 0) {
            return candle.getBuyVolume() > candle.getSellVolume();
        }
        
        // No data available - allow breakout check to proceed
        // (missing data should not block breakout detection)
        return true;
    }
    
    /**
     * Check OFI (Order Flow Imbalance) for selling pressure (for bearish breakdowns)
     * 
     * Mirror of hasOFIBuyingPressure but checks for selling instead.
     */
    public boolean hasOFISellingPressure(UnifiedCandle candle) {
        // If we have OFI data, use it (negative = selling pressure)
        if (candle.getOfi() != 0) {
            return candle.getOfi() < 0;
        }
        
        // Fallback to volume delta (negative = more selling)
        if (candle.getVolumeDelta() != 0) {
            return candle.getVolumeDelta() < 0;
        }
        
        // If no OFI and no volume delta, use buy/sell volume comparison
        if (candle.getBuyVolume() > 0 || candle.getSellVolume() > 0) {
            return candle.getSellVolume() > candle.getBuyVolume();
        }
        
        // No data available - allow breakout check to proceed
        return true;
    }

    /**
     * Check VPIN for directional volume
     */
    public boolean hasVPINConfirmation(UnifiedCandle candle) {
        return candle.getVpin() > 0.5;
    }
}
