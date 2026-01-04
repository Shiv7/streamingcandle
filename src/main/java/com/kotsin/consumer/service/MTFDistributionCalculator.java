package com.kotsin.consumer.service;

import com.kotsin.consumer.model.MTFDistribution;
import com.kotsin.consumer.model.UnifiedCandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * MTFDistributionCalculator - Multi-Timeframe Distribution Analysis
 * 
 * PHASE 2 ENHANCEMENT: Analyzes intra-window characteristics
 * 
 * Calculates distribution metrics from sub-candles to understand:
 * - Directional consistency (trend strength)
 * - Volume timing (early exhaustion vs late building)
 * - Momentum evolution (accelerating vs decelerating)
 * 
 * Example:
 * Input: 5x 1m candles aggregating into 1x 5m candle
 * Output: MTFDistribution showing pattern (5 bullish = strong, 3-2 = weak, etc.)
 */
@Slf4j
@Service
public class MTFDistributionCalculator {
    
    /**
     * Calculate MTF distribution from sub-candles
     * 
     * @param subCandles List of sub-candles (e.g., 5x 1m for 5m aggregate)
     * @return MTFDistribution metrics
     */
    public MTFDistribution calculate(List<UnifiedCandle> subCandles) {
        if (subCandles == null || subCandles.isEmpty()) {
            return createEmpty();
        }
        
        int total = subCandles.size();
        
        // Calculate directional distribution
        int bullish = 0, bearish = 0;
        for (UnifiedCandle candle : subCandles) {
            if (candle.getClose() > candle.getOpen()) {
                bullish++;
            } else if (candle.getClose() < candle.getOpen()) {
                bearish++;
            }
        }
        
        double directionalConsistency = total > 0 ? 
            Math.abs(bullish - bearish) / (double) total : 0.0;
        
        MTFDistribution.Direction dominantDirection = 
            bullish > bearish ? MTFDistribution.Direction.BULLISH :
            bearish > bullish ? MTFDistribution.Direction.BEARISH :
            MTFDistribution.Direction.NEUTRAL;
        
        // Calculate volume distribution
        int volumeSpikeIndex = findVolumeSpikeIndex(subCandles);
        double earlyVolRatio = calculateEarlyVolumeRatio(subCandles);
        double lateVolRatio = calculateLateVolumeRatio(subCandles);
        boolean volumeDrying = isVolumeDrying(subCandles);
        
        // Calculate momentum evolution
        double earlyMom = calculateEarlyMomentum(subCandles);
        double lateMom = calculateLateMomentum(subCandles);
        double momShift = lateMom - earlyMom;
        
        // Confidence based on sample size
        double confidence = calculateConfidence(total);
        
        return MTFDistribution.builder()
                .bullishSubCandles(bullish)
                .bearishSubCandles(bearish)
                .totalSubCandles(total)
                .directionalConsistency(directionalConsistency)
                .dominantDirection(dominantDirection)
                .volumeSpikeCandleIndex(volumeSpikeIndex)
                .earlyVolumeRatio(earlyVolRatio)
                .lateVolumeRatio(lateVolRatio)
                .volumeDrying(volumeDrying)
                .earlyMomentum(earlyMom)
                .lateMomentum(lateMom)
                .momentumShift(momShift)
                .momentumAccelerating(momShift > 0.1)
                .momentumDecelerating(momShift < -0.1)
                .confidence(confidence)
                .build();
    }
    
    /**
     * Find index of candle with highest volume
     */
    private int findVolumeSpikeIndex(List<UnifiedCandle> candles) {
        if (candles.isEmpty()) return -1;
        
        long maxVolume = 0;
        int maxIndex = 0;
        
        for (int i = 0; i < candles.size(); i++) {
            long vol = candles.get(i).getVolume();
            if (vol > maxVolume) {
                maxVolume = vol;
                maxIndex = i;
            }
        }
        
        return maxIndex;
    }
    
    /**
     * Calculate ratio of volume in first 40% of candles
     */
    private double calculateEarlyVolumeRatio(List<UnifiedCandle> candles) {
        if (candles.isEmpty()) return 0.0;
        
        int earlyCount = Math.max(1, (int) (candles.size() * 0.4));
        long earlyVolume = 0;
        long totalVolume = 0;
        
        for (int i = 0; i < candles.size(); i++) {
            long vol = candles.get(i).getVolume();
            totalVolume += vol;
            if (i < earlyCount) {
                earlyVolume += vol;
            }
        }
        
        return totalVolume > 0 ? earlyVolume / (double) totalVolume : 0.0;
    }
    
    /**
     * Calculate ratio of volume in last 40% of candles
     */
    private double calculateLateVolumeRatio(List<UnifiedCandle> candles) {
        if (candles.isEmpty()) return 0.0;
        
        int lateStart = (int) (candles.size() * 0.6);
        long lateVolume = 0;
        long totalVolume = 0;
        
        for (int i = 0; i < candles.size(); i++) {
            long vol = candles.get(i).getVolume();
            totalVolume += vol;
            if (i >= lateStart) {
                lateVolume += vol;
            }
        }
        
        return totalVolume > 0 ? lateVolume / (double) totalVolume : 0.0;
    }
    
    /**
     * Check if volume is declining through window
     */
    private boolean isVolumeDrying(List<UnifiedCandle> candles) {
        if (candles.size() < 3) return false;
        
        // Compare first half avg vs second half avg
        int midPoint = candles.size() / 2;
        double firstHalfAvg = 0;
        double secondHalfAvg = 0;
        
        for (int i = 0; i < midPoint; i++) {
            firstHalfAvg += candles.get(i).getVolume();
        }
        firstHalfAvg /= midPoint;
        
        for (int i = midPoint; i < candles.size(); i++) {
            secondHalfAvg += candles.get(i).getVolume();
        }
        secondHalfAvg /= (candles.size() - midPoint);
        
        // Drying if second half is < 70% of first half
        return secondHalfAvg < 0.7 * firstHalfAvg;
    }
    
    /**
     * Calculate average momentum of early candles
     * Momentum = (close - open) / (high - low)
     */
    private double calculateEarlyMomentum(List<UnifiedCandle> candles) {
        if (candles.isEmpty()) return 0.0;
        
        int earlyCount = Math.max(1, (int) (candles.size() * 0.4));
        double totalMomentum = 0;
        int validCount = 0;
        
        for (int i = 0; i < Math.min(earlyCount, candles.size()); i++) {
            UnifiedCandle candle = candles.get(i);
            double range = candle.getHigh() - candle.getLow();
            if (range > 0) {
                double momentum = (candle.getClose() - candle.getOpen()) / range;
                totalMomentum += momentum;
                validCount++;
            }
        }
        
        return validCount > 0 ? totalMomentum / validCount : 0.0;
    }
    
    /**
     * Calculate average momentum of late candles
     */
    private double calculateLateMomentum(List<UnifiedCandle> candles) {
        if (candles.isEmpty()) return 0.0;
        
        int lateStart = (int) (candles.size() * 0.6);
        double totalMomentum = 0;
        int validCount = 0;
        
        for (int i = lateStart; i < candles.size(); i++) {
            UnifiedCandle candle = candles.get(i);
            double range = candle.getHigh() - candle.getLow();
            if (range > 0) {
                double momentum = (candle.getClose() - candle.getOpen()) / range;
                totalMomentum += momentum;
                validCount++;
            }
        }
        
        return validCount > 0 ? totalMomentum / validCount : 0.0;
    }
    
    /**
     * Calculate confidence based on sample size
     */
    private double calculateConfidence(int sampleSize) {
        if (sampleSize < 3) return 0.3;  // Low confidence
        if (sampleSize < 5) return 0.6;  // Medium confidence
        return 1.0;  // High confidence
    }
    
    /**
     * Create empty distribution (no data)
     */
    private MTFDistribution createEmpty() {
        return MTFDistribution.builder()
                .bullishSubCandles(0)
                .bearishSubCandles(0)
                .totalSubCandles(0)
                .directionalConsistency(0.0)
                .dominantDirection(MTFDistribution.Direction.NEUTRAL)
                .volumeSpikeCandleIndex(-1)
                .earlyVolumeRatio(0.0)
                .lateVolumeRatio(0.0)
                .volumeDrying(false)
                .earlyMomentum(0.0)
                .lateMomentum(0.0)
                .momentumShift(0.0)
                .momentumAccelerating(false)
                .momentumDecelerating(false)
                .confidence(0.0)
                .build();
    }
}
