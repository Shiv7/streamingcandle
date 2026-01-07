package com.kotsin.consumer.masterarch.calculator;

import com.kotsin.consumer.masterarch.model.NormalizedScore;
import com.kotsin.consumer.model.UnifiedCandle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * BehaviouralValidator - PART 3: TYPE B - Behavioural Validation
 * 
 * MASTER ARCHITECTURE Compliant Validator
 * 
 * Components:
 * 1. ACL = 0.40*Acceleration + 0.35*Conviction(MFI) + 0.25*Liquidity
 * 2. SOM = Wick_Absorption = |upper_or_lower_wick| / range
 * 3. VTD = Trap_Score from failed breakouts
 * 4. OHM = Option_Buyer_Friendly (ΔDelta + ΔOI direction)
 * 
 * Single Penalty Resolution:
 * - Pick the WORST indicator (lowest score)
 * - Multiple penalties = min single + exponential decay
 * 
 * Behaviour_Multiplier:
 * - STRONG_CONFIRM → ×1.10
 * - PASS → ×1.00
 * - PENALIZE → ×0.85 (or 0.92 if Volume_Certainty ≥ 0.90)
 * 
 * Output Topic: validation-behavioural-output
 */
@Slf4j
@Component
public class BehaviouralValidator {
    
    // ACL weights per spec
    private static final double ACL_WEIGHT_ACCELERATION = 0.40;
    private static final double ACL_WEIGHT_CONVICTION = 0.35;
    private static final double ACL_WEIGHT_LIQUIDITY = 0.25;
    
    // Thresholds
    private static final double STRONG_CONFIRM_THRESHOLD = 0.75;
    private static final double PASS_THRESHOLD = 0.50;
    
    /**
     * Behavioural Validation Output
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class BehaviouralValidationOutput {
        private String scripCode;
        private String companyName;
        private long timestamp;
        
        // ACL Components
        private double acceleration;
        private double conviction;          // MFI-based
        private double liquidity;
        private double aclScore;
        
        // SOM - Sentiment Oscillation Module
        private double wickAbsorption;
        private double upperWick;
        private double lowerWick;
        private double range;
        private double somScore;
        
        // VTD - Volatility Trap Detection
        private double trapScore;
        private boolean failedBreakoutDetected;
        private String trapType;            // BULLISH_TRAP, BEARISH_TRAP, NONE
        private double vtdScore;
        
        // OHM - Option Health Module
        private double ohmScore;
        private boolean optionBuyerFriendly;
        private double deltaChange;
        private double oiChange;
        
        // Resolution
        private String worstIndicator;
        private double behaviourMultiplier;
        private String behaviourResult;     // STRONG_CONFIRM, PASS, PENALIZE
        
        // Final Score
        private NormalizedScore behaviouralScore;
        
        // Metadata
        private boolean isValid;
        private String invalidReason;
    }
    
    /**
     * Calculate Behavioural Validation
     */
    public BehaviouralValidationOutput calculate(
            String scripCode,
            String companyName,
            List<UnifiedCandle> candles30m,
            double ohmScore,
            boolean optionBuyerFriendly,
            double volumeCertainty,
            double previousScore
    ) {
        long timestamp = System.currentTimeMillis();
        
        // Validate data
        if (candles30m == null || candles30m.size() < 5) {
            return BehaviouralValidationOutput.builder()
                    .scripCode(scripCode)
                    .companyName(companyName)
                    .timestamp(timestamp)
                    .behaviouralScore(NormalizedScore.neutral(timestamp))
                    .isValid(false)
                    .invalidReason("Insufficient candle data")
                    .build();
        }
        
        UnifiedCandle current = candles30m.get(candles30m.size() - 1);
        
        // ======================== ACL CALCULATION ========================
        // Acceleration = momentum change rate
        double acceleration = calculateAcceleration(candles30m);
        
        // Conviction (MFI proxy) = close position × volume confirmation
        double conviction = calculateConviction(candles30m);
        
        // Liquidity = volume consistency 
        double liquidity = calculateLiquidity(candles30m);
        
        // ACL = 0.40*Acceleration + 0.35*Conviction + 0.25*Liquidity
        double aclScore = ACL_WEIGHT_ACCELERATION * acceleration
                        + ACL_WEIGHT_CONVICTION * conviction
                        + ACL_WEIGHT_LIQUIDITY * liquidity;
        aclScore = clamp(aclScore, 0.0, 1.0);
        
        // ======================== SOM CALCULATION ========================
        // Wick_Absorption = |wick| / range (shows absorption of opposing pressure)
        double range = current.getHigh() - current.getLow();
        double upperWick = current.getHigh() - Math.max(current.getOpen(), current.getClose());
        double lowerWick = Math.min(current.getOpen(), current.getClose()) - current.getLow();
        
        // For bullish: lower wick absorption is positive (buyers absorbing sellers)
        // For bearish: upper wick absorption is positive (sellers absorbing buyers)
        boolean bullishCandle = current.getClose() > current.getOpen();
        double wickAbsorption;
        if (range > 0) {
            wickAbsorption = bullishCandle 
                    ? lowerWick / range    // Lower wick means buyers absorbed selling
                    : upperWick / range;   // Upper wick means sellers absorbed buying
        } else {
            wickAbsorption = 0.5;
        }
        
        double somScore = wickAbsorption > 0.3 ? 0.8 : (wickAbsorption > 0.1 ? 0.6 : 0.4);
        
        // ======================== VTD CALCULATION ========================
        // Trap detection: failed breakout patterns
        TrapResult trapResult = detectTrap(candles30m);
        double vtdScore = trapResult.trapScore;
        
        // ======================== RESOLUTION ========================
        // Find worst indicator
        double minScore = Math.min(aclScore, Math.min(somScore, Math.min(vtdScore, ohmScore)));
        String worstIndicator;
        if (minScore == aclScore) worstIndicator = "ACL";
        else if (minScore == somScore) worstIndicator = "SOM";
        else if (minScore == vtdScore) worstIndicator = "VTD";
        else worstIndicator = "OHM";
        
        // Calculate average for final determination
        double avgScore = (aclScore + somScore + vtdScore + ohmScore) / 4.0;
        
        // Determine behaviour result
        String behaviourResult;
        double behaviourMultiplier;
        
        if (avgScore >= STRONG_CONFIRM_THRESHOLD && minScore >= PASS_THRESHOLD) {
            behaviourResult = "STRONG_CONFIRM";
            behaviourMultiplier = 1.10;
        } else if (avgScore >= PASS_THRESHOLD && minScore >= 0.35) {
            behaviourResult = "PASS";
            behaviourMultiplier = 1.00;
        } else {
            behaviourResult = "PENALIZE";
            // Override: if Volume_Certainty >= 0.90, penalty softened
            behaviourMultiplier = volumeCertainty >= 0.90 ? 0.92 : 0.85;
        }
        
        // Final behavioural score = average × direction
        double direction = bullishCandle ? 1.0 : -1.0;
        double behaviouralValue = avgScore * behaviourMultiplier * direction;
        behaviouralValue = clamp(behaviouralValue, -1.0, 1.0);
        
        NormalizedScore behaviouralScore = NormalizedScore.directional(behaviouralValue, previousScore, timestamp);
        
        return BehaviouralValidationOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(timestamp)
                // ACL
                .acceleration(acceleration)
                .conviction(conviction)
                .liquidity(liquidity)
                .aclScore(aclScore)
                // SOM
                .wickAbsorption(wickAbsorption)
                .upperWick(upperWick)
                .lowerWick(lowerWick)
                .range(range)
                .somScore(somScore)
                // VTD
                .trapScore(trapResult.trapScore)
                .failedBreakoutDetected(trapResult.failedBreakout)
                .trapType(trapResult.trapType)
                .vtdScore(vtdScore)
                // OHM
                .ohmScore(ohmScore)
                .optionBuyerFriendly(optionBuyerFriendly)
                // Resolution
                .worstIndicator(worstIndicator)
                .behaviourMultiplier(behaviourMultiplier)
                .behaviourResult(behaviourResult)
                // Score
                .behaviouralScore(behaviouralScore)
                // Meta
                .isValid(true)
                .build();
    }
    
    // ======================== HELPER METHODS ========================
    
    private double calculateAcceleration(List<UnifiedCandle> candles) {
        if (candles.size() < 5) return 0.5;
        
        // Calculate momentum change (ROC of ROC)
        int size = candles.size();
        double roc1 = calculateROC(candles.get(size - 1), candles.get(size - 2));
        double roc2 = calculateROC(candles.get(size - 2), candles.get(size - 3));
        
        double accel = roc1 - roc2;
        // Normalize acceleration to [0, 1]
        return clamp((accel + 0.05) / 0.10, 0.0, 1.0);
    }
    
    private double calculateROC(UnifiedCandle current, UnifiedCandle previous) {
        if (previous.getClose() <= 0) return 0;
        return (current.getClose() - previous.getClose()) / previous.getClose();
    }
    
    private double calculateConviction(List<UnifiedCandle> candles) {
        if (candles.size() < 5) return 0.5;
        
        // MFI proxy: close position within range × volume relative
        UnifiedCandle current = candles.get(candles.size() - 1);
        double range = current.getHigh() - current.getLow();
        if (range <= 0) return 0.5;
        
        double closePosition = (current.getClose() - current.getLow()) / range;
        
        // Volume relative to average
        long avgVolume = 0;
        for (int i = candles.size() - 5; i < candles.size(); i++) {
            avgVolume += candles.get(i).getVolume();
        }
        avgVolume /= 5;
        
        double volRelative = avgVolume > 0 
                ? Math.min((double) current.getVolume() / avgVolume, 2.0) / 2.0 
                : 0.5;
        
        return (closePosition + volRelative) / 2.0;
    }
    
    private double calculateLiquidity(List<UnifiedCandle> candles) {
        if (candles.size() < 5) return 0.5;
        
        // Volume consistency: std dev of volumes relative to mean
        double sum = 0;
        for (int i = candles.size() - 5; i < candles.size(); i++) {
            sum += candles.get(i).getVolume();
        }
        double mean = sum / 5;
        
        double variance = 0;
        for (int i = candles.size() - 5; i < candles.size(); i++) {
            double diff = candles.get(i).getVolume() - mean;
            variance += diff * diff;
        }
        double stdDev = Math.sqrt(variance / 5);
        
        // Lower coefficient of variation = more consistent = higher liquidity
        double cv = mean > 0 ? stdDev / mean : 1.0;
        return Math.max(0, 1.0 - Math.min(cv, 1.0));
    }
    
    @Data
    @AllArgsConstructor
    private static class TrapResult {
        private double trapScore;
        private boolean failedBreakout;
        private String trapType;
    }
    
    private TrapResult detectTrap(List<UnifiedCandle> candles) {
        if (candles.size() < 5) {
            return new TrapResult(0.7, false, "NONE");
        }
        
        // Look for failed breakouts
        int size = candles.size();
        UnifiedCandle current = candles.get(size - 1);
        UnifiedCandle prev1 = candles.get(size - 2);
        UnifiedCandle prev2 = candles.get(size - 3);
        
        // Find recent high/low
        double recentHigh = Math.max(prev1.getHigh(), prev2.getHigh());
        double recentLow = Math.min(prev1.getLow(), prev2.getLow());
        
        // Bullish trap: broke above high then closed below
        if (current.getHigh() > recentHigh && current.getClose() < recentHigh) {
            return new TrapResult(0.3, true, "BULLISH_TRAP");
        }
        
        // Bearish trap: broke below low then closed above
        if (current.getLow() < recentLow && current.getClose() > recentLow) {
            return new TrapResult(0.3, true, "BEARISH_TRAP");
        }
        
        return new TrapResult(0.8, false, "NONE");
    }
    
    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
