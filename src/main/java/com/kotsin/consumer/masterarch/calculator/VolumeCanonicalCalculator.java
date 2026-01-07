package com.kotsin.consumer.masterarch.calculator;

import com.kotsin.consumer.infrastructure.redis.RedisPercentileService;
import com.kotsin.consumer.masterarch.model.NormalizedScore;
import com.kotsin.consumer.model.UnifiedCandle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * VolumeCanonicalCalculator - PART 2B: Volume Canonical Module
 * 
 * MASTER ARCHITECTURE Compliant Calculator
 * 
 * Formulas:
 * 1. Vol_ROC_5 = (Vol_t - Vol_t-5) / Vol_t-5
 * 2. Vol_Pctl = percentile_rank(Vol_ROC_5, 1 year) [from Redis]
 * 3. Vol_Accel = Vol_ROC_5 - Vol_ROC_5_prev
 * 4. Vol_Exp_Strength = 0.6*Vol_Pctl + 0.4*Vol_Accel
 * 
 * Volume Certainty Thresholds:
 * - ≥90 percentile → 0.95
 * - 75–90 percentile → 0.80
 * - 60–75 percentile → 0.65
 * - <60 percentile → 0.50
 * 
 * Hard Rule:
 * - If Volume_Certainty < 0.60 → WAIT ±2 bars
 * - If still < 0.60 → REJECT
 * 
 * Output Topic: signal-volume-output
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VolumeCanonicalCalculator {
    
    private final RedisPercentileService redisPercentileService;
    
    private static final int VOLUME_ROC_PERIOD = 5;
    private static final double CERTAINTY_THRESHOLD = 0.60;
    private static final int PATIENCE_BARS = 2;
    
    /**
     * Volume Output
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class VolumeOutput {
        private String scripCode;
        private String companyName;
        private long timestamp;
        
        // Volume ROC
        private double volRoc5;
        private double volRoc5Prev;
        
        // Percentile (from Redis 1-year data)
        private double volPercentile;
        private boolean percentileFromRedis;
        
        // Acceleration
        private double volAcceleration;
        
        // Expansion Strength
        private double volExpStrength;
        
        // Certainty
        private double volumeCertainty;
        
        // Final Score
        private NormalizedScore volumeScore;
        
        // Patience tracking
        private boolean waitingForConfirmation;
        private int barsWaited;
        private boolean rejected;
        private String rejectReason;
        
        // Metadata
        private boolean isValid;
        private String invalidReason;
    }
    
    /**
     * Calculate Volume Canonical output
     */
    public VolumeOutput calculate(
            String scripCode,
            String companyName,
            List<UnifiedCandle> candles30m,
            double previousVolumeScore
    ) {
        long timestamp = System.currentTimeMillis();
        
        // Validate data
        if (candles30m == null || candles30m.size() < VOLUME_ROC_PERIOD + 2) {
            return VolumeOutput.builder()
                    .scripCode(scripCode)
                    .companyName(companyName)
                    .timestamp(timestamp)
                    .volumeScore(NormalizedScore.neutral(timestamp))
                    .isValid(false)
                    .invalidReason("Insufficient candles: need " + (VOLUME_ROC_PERIOD + 2))
                    .build();
        }
        
        // ======================== VOL_ROC_5 ========================
        double volRoc5 = calculateVolumeROC(candles30m, VOLUME_ROC_PERIOD);
        
        // Store in Redis for 1-year percentile tracking
        redisPercentileService.storeVolumeROC(scripCode, volRoc5, timestamp);
        
        // ======================== PREVIOUS VOL_ROC_5 ========================
        List<UnifiedCandle> prevCandles = candles30m.subList(0, candles30m.size() - 1);
        double volRoc5Prev = calculateVolumeROC(prevCandles, VOLUME_ROC_PERIOD);
        
        // ======================== VOL_PERCENTILE ========================
        double volPercentile = redisPercentileService.getVolumeROCPercentile(scripCode, volRoc5);
        boolean percentileFromRedis = volPercentile >= 0;
        
        // If no Redis data yet, estimate from current session
        if (!percentileFromRedis) {
            volPercentile = estimatePercentileFromSession(candles30m, volRoc5);
        }
        
        // ======================== VOL_ACCEL ========================
        // Vol_Accel = Vol_ROC_5 - Vol_ROC_5_prev
        double volAcceleration = volRoc5 - volRoc5Prev;
        // Normalize to [-1, 1] range
        volAcceleration = clamp(volAcceleration / 2.0, -1.0, 1.0);
        
        // ======================== VOL_EXP_STRENGTH ========================
        // Vol_Exp_Strength = 0.6*Vol_Pctl + 0.4*Vol_Accel
        double normalizedPercentile = volPercentile / 100.0; // Convert to [0, 1]
        double normalizedAccel = (volAcceleration + 1.0) / 2.0; // Convert [-1,1] to [0,1]
        double volExpStrength = 0.6 * normalizedPercentile + 0.4 * normalizedAccel;
        volExpStrength = clamp(volExpStrength, 0.0, 1.0);
        
        // ======================== VOLUME CERTAINTY ========================
        // Uses threshold mapping per spec
        double volumeCertainty = redisPercentileService.getVolumeCertainty(scripCode, volRoc5);
        if (!percentileFromRedis) {
            // Fallback: estimate certainty from expansion strength
            volumeCertainty = estimateCertainty(volExpStrength);
        }
        
        // ======================== PATIENCE LOGIC ========================
        boolean waiting = false;
        boolean rejected = false;
        String rejectReason = null;
        
        if (volumeCertainty < CERTAINTY_THRESHOLD) {
            waiting = true;
            log.debug("Volume certainty {} < {} - entering patience mode for {}", 
                    volumeCertainty, CERTAINTY_THRESHOLD, scripCode);
        }
        
        // ======================== FINAL SCORE ========================
        // Apply direction from acceleration
        double signedVolScore = volExpStrength;
        if (volAcceleration < -0.2) {
            signedVolScore = -volExpStrength; // Contracting volume = bearish signal
        }
        
        NormalizedScore volumeScore = NormalizedScore.directional(signedVolScore, previousVolumeScore, timestamp);
        
        return VolumeOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(timestamp)
                // ROC
                .volRoc5(volRoc5)
                .volRoc5Prev(volRoc5Prev)
                // Percentile
                .volPercentile(volPercentile)
                .percentileFromRedis(percentileFromRedis)
                // Acceleration
                .volAcceleration(volAcceleration)
                // Strength
                .volExpStrength(volExpStrength)
                // Certainty
                .volumeCertainty(volumeCertainty)
                // Score
                .volumeScore(volumeScore)
                // Patience
                .waitingForConfirmation(waiting)
                .barsWaited(0)
                .rejected(rejected)
                .rejectReason(rejectReason)
                // Meta
                .isValid(true)
                .build();
    }
    
    // ======================== HELPER METHODS ========================
    
    /**
     * Calculate Volume Rate of Change
     * Vol_ROC_5 = (Vol_t - Vol_t-5) / Vol_t-5
     */
    private double calculateVolumeROC(List<UnifiedCandle> candles, int period) {
        if (candles == null || candles.size() <= period) {
            return 0.0;
        }
        
        long currentVolume = candles.get(candles.size() - 1).getVolume();
        long previousVolume = candles.get(candles.size() - 1 - period).getVolume();
        
        if (previousVolume <= 0) {
            return 0.0;
        }
        
        return (double) (currentVolume - previousVolume) / previousVolume;
    }
    
    /**
     * Estimate percentile from current session when Redis has insufficient data
     */
    private double estimatePercentileFromSession(List<UnifiedCandle> candles, double currentRoc) {
        if (candles.size() < 10) return 50.0; // Default to median
        
        // Calculate ROC for recent bars and rank current
        int countLessThan = 0;
        int total = Math.min(50, candles.size());
        
        for (int i = VOLUME_ROC_PERIOD + 1; i < total; i++) {
            List<UnifiedCandle> subset = candles.subList(0, i);
            double historicalRoc = calculateVolumeROC(subset, VOLUME_ROC_PERIOD);
            if (historicalRoc < currentRoc) {
                countLessThan++;
            }
        }
        
        return (countLessThan / (double) total) * 100.0;
    }
    
    /**
     * Estimate certainty from expansion strength when Redis unavailable
     */
    private double estimateCertainty(double volExpStrength) {
        if (volExpStrength >= 0.9) return 0.95;
        if (volExpStrength >= 0.75) return 0.80;
        if (volExpStrength >= 0.60) return 0.65;
        return 0.50;
    }
    
    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
