package com.kotsin.consumer.masterarch.calculator;

import com.kotsin.consumer.masterarch.model.NormalizedScore;
import com.kotsin.consumer.model.UnifiedCandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * VelocityMMSCalculator - PART 2C: Velocity Multi-timeframe Momentum Score
 * 
 * NEW MODULE - Not present in existing codebase
 * 
 * Formula:
 * Velocity_MMS = 0.5*ROC_30m + 0.3*ROC_5m + 0.2*ROC_1D
 * 
 * Override Rule (locked):
 * If Volume_Certainty >= 0.90 AND Fudkii >= 0.70
 * → Velocity optional UNLESS Velocity <= 0.20
 * 
 * Output Topic: signal-velocity-output
 */
@Slf4j
@Component
public class VelocityMMSCalculator {
    
    // Timeframe weights per spec
    private static final double WEIGHT_30M = 0.50;
    private static final double WEIGHT_5M = 0.30;
    private static final double WEIGHT_1D = 0.20;
    
    // ROC periods for each timeframe
    private static final int ROC_PERIOD = 1;  // 1-bar ROC
    
    /**
     * VelocityMMS Output
     */
    @lombok.Data
    @lombok.Builder
    @lombok.AllArgsConstructor
    public static class VelocityMMSOutput {
        private String scripCode;
        private String companyName;
        private long timestamp;
        
        // Individual timeframe ROCs
        private double roc30m;
        private double roc5m;
        private double roc1D;
        
        // Weighted velocity score
        private NormalizedScore velocityScore;
        
        // Override conditions
        private boolean velocityOptional;  // If Volume+FUDKII conditions met
        private boolean velocityTooLow;    // If <= 0.20 even with override
        
        // Metadata
        private boolean isValid;
        private String invalidReason;
        
        /**
         * Get adjusted velocity for final score calculation
         * Per spec: max(Velocity_MMS, 0.50) unless too low
         */
        public double getAdjustedVelocity() {
            if (velocityScore == null) return 0.50;
            return Math.max(velocityScore.getCurrent(), 0.50);
        }
        
        /**
         * Check if velocity should be applied
         */
        public boolean shouldApplyVelocity(double volumeCertainty, double fudkiiStrength) {
            // Check override conditions
            if (volumeCertainty >= 0.90 && fudkiiStrength >= 0.70) {
                // Velocity optional UNLESS too low
                if (velocityScore != null && velocityScore.getCurrent() <= 0.20) {
                    return true;  // Must still apply because too low
                }
                return false;  // Can skip velocity
            }
            return true;  // Must apply velocity
        }
    }
    
    /**
     * Calculate Velocity MMS from multi-timeframe candle data
     * 
     * @param candles30m 30-minute candles (most recent last)
     * @param candles5m 5-minute candles (most recent last)
     * @param candles1D Daily candles (most recent last)
     * @param scripCode Instrument identifier
     * @param companyName Company name
     * @param previousVelocity Previous velocity score for delta calculation
     */
    public VelocityMMSOutput calculate(
            List<UnifiedCandle> candles30m,
            List<UnifiedCandle> candles5m,
            List<UnifiedCandle> candles1D,
            String scripCode,
            String companyName,
            double previousVelocity
    ) {
        long timestamp = System.currentTimeMillis();
        
        // Validate inputs
        if (candles30m == null || candles30m.size() < 2) {
            return VelocityMMSOutput.builder()
                    .scripCode(scripCode)
                    .companyName(companyName)
                    .timestamp(timestamp)
                    .velocityScore(NormalizedScore.neutral(timestamp))
                    .isValid(false)
                    .invalidReason("Insufficient 30m candles")
                    .build();
        }
        
        // Calculate ROC for each timeframe
        double roc30m = calculateROC(candles30m);
        double roc5m = candles5m != null && candles5m.size() >= 2 
                ? calculateROC(candles5m) : 0.0;
        double roc1D = candles1D != null && candles1D.size() >= 2 
                ? calculateROC(candles1D) : 0.0;
        
        // Calculate weighted velocity
        double rawVelocity = WEIGHT_30M * roc30m 
                           + WEIGHT_5M * roc5m 
                           + WEIGHT_1D * roc1D;
        
        // Normalize to [-1, +1]
        // Assuming max ROC of ~5% per bar, normalize accordingly
        double normalizedVelocity = clamp(rawVelocity / 5.0, -1.0, 1.0);
        
        NormalizedScore velocityScore = NormalizedScore.directional(
                normalizedVelocity, 
                previousVelocity, 
                timestamp
        );
        
        return VelocityMMSOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(timestamp)
                .roc30m(roc30m)
                .roc5m(roc5m)
                .roc1D(roc1D)
                .velocityScore(velocityScore)
                .velocityOptional(false)
                .velocityTooLow(normalizedVelocity <= 0.20)
                .isValid(true)
                .build();
    }
    
    /**
     * Calculate simple ROC (Rate of Change) as percentage
     * ROC = (Current - Previous) / Previous * 100
     */
    private double calculateROC(List<UnifiedCandle> candles) {
        if (candles == null || candles.size() < 2) {
            return 0.0;
        }
        
        int size = candles.size();
        UnifiedCandle current = candles.get(size - 1);
        UnifiedCandle previous = candles.get(size - 2);
        
        double currentClose = current.getClose();
        double previousClose = previous.getClose();
        
        if (previousClose <= 0) {
            return 0.0;
        }
        
        return ((currentClose - previousClose) / previousClose) * 100.0;
    }
    
    /**
     * Calculate multi-bar ROC
     */
    private double calculateROC(List<UnifiedCandle> candles, int period) {
        if (candles == null || candles.size() <= period) {
            return 0.0;
        }
        
        int size = candles.size();
        UnifiedCandle current = candles.get(size - 1);
        UnifiedCandle previous = candles.get(size - 1 - period);
        
        double currentClose = current.getClose();
        double previousClose = previous.getClose();
        
        if (previousClose <= 0) {
            return 0.0;
        }
        
        return ((currentClose - previousClose) / previousClose) * 100.0;
    }
    
    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
