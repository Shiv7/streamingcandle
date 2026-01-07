package com.kotsin.consumer.masterarch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;

/**
 * SignalStrengthScore - PART 3: Signal Quality & Confirmation
 * 
 * Formula:
 * Signal_Strength_Score = Fudkii_Strength × Volume_Certainty × Velocity_Adjusted 
 *                        × Structural_Score × Behaviour_Multiplier × CG_Multiplier
 * 
 * Where:
 * - Velocity_Adjusted = max(Velocity_MMS, 0.50)
 * - Behaviour_Multiplier: STRONG_CONFIRM → 1.10, PASS → 1.00, PENALIZE → 0.85
 *   (Override: If Volume_Certainty ≥ 0.90, penalty becomes 0.92)
 * 
 * Output Topic: Part of final score calculation
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SignalStrengthScore {
    
    // ======================== IDENTIFICATION ========================
    
    private String scripCode;
    private String companyName;
    private long timestamp;
    
    // ======================== COMPONENT SCORES ========================
    
    /**
     * FUDKII Strength from Part 2A
     * = min(1, BB_Score + ST_Score) × Simultaneity_Weight
     * Range: [0.0, 1.0]
     */
    private double fudkiiStrength;
    private double bbScore;
    private double stScore;
    private double simultaneityWeight;
    
    /**
     * Volume Certainty from Part 2B
     * Based on Vol_Exp_Strength percentile thresholds
     * Range: [0.0, 1.0]
     */
    private double volumeCertainty;
    private double volExpStrength;
    private double volPercentile;
    
    /**
     * Velocity Adjusted from Part 2C
     * = max(Velocity_MMS, 0.50)
     * Range: [0.5, 1.0]
     */
    private double velocityAdjusted;
    private double velocityRaw;
    
    /**
     * Structural Score from Type A Validation
     * = Driver_Strength × Product(Supporter_Multipliers)
     * Range: [0.0, 1.0]
     */
    private double structuralScore;
    private String driver;              // VCP, VWAP, or PIVOT
    private double driverStrength;
    
    /**
     * Behaviour Multiplier from Type B Validation
     * STRONG_CONFIRM → 1.10, PASS → 1.00, PENALIZE → 0.85/0.92
     */
    private double behaviourMultiplier;
    private String behaviourResult;     // STRONG_CONFIRM, PASS, PENALIZE
    private String behaviourSource;     // ACL, SOM, VTD, or OHM
    
    /**
     * Correlation Governor Multiplier
     * Same cluster active → 0.80, Opposite beta → 0.70
     */
    private double cgMultiplier;
    private boolean sameClusterActive;
    private boolean oppositeClusterActive;
    
    // ======================== FINAL SCORE ========================
    
    /**
     * Signal_Strength_Score with current/previous/delta
     */
    private NormalizedScore signalScore;
    
    // ======================== VALIDATION FLAGS ========================
    
    /**
     * Hard reject if Structural_Score < 0.45
     */
    private boolean structuralRejected;
    private String structuralRejectReason;
    
    /**
     * FUDKII wait logic: If < 0.55, wait ±2 bars
     */
    private boolean fudkiiWaiting;
    private int fudkiiBarsWaited;
    
    /**
     * Volume wait logic: If certainty < 0.60, wait ±2 bars
     */
    private boolean volumeWaiting;
    private int volumeBarsWaited;
    
    // ======================== METADATA ========================
    
    private boolean isValid;
    private String invalidReason;
    
    // ======================== FACTORY METHODS ========================
    
    /**
     * Calculate Signal Strength Score from all components
     */
    public static SignalStrengthScore calculate(
            String scripCode,
            String companyName,
            long timestamp,
            double fudkiiStrength,
            double volumeCertainty,
            double velocityMMS,
            double structuralScore,
            String behaviourResult,
            boolean sameClusterActive,
            boolean oppositeClusterActive,
            double previousScore
    ) {
        // Check for hard rejects
        if (structuralScore < 0.45) {
            return SignalStrengthScore.builder()
                    .scripCode(scripCode)
                    .companyName(companyName)
                    .timestamp(timestamp)
                    .structuralScore(structuralScore)
                    .structuralRejected(true)
                    .structuralRejectReason("Structural_Score < 0.45")
                    .signalScore(NormalizedScore.neutral(timestamp))
                    .isValid(false)
                    .invalidReason("Structural validation failed")
                    .build();
        }
        
        // Velocity Adjusted
        double velocityAdjusted = Math.max(velocityMMS, 0.50);
        
        // Behaviour Multiplier
        double behaviourMultiplier;
        if ("STRONG_CONFIRM".equals(behaviourResult)) {
            behaviourMultiplier = 1.10;
        } else if ("PENALIZE".equals(behaviourResult)) {
            // Override: if Volume_Certainty >= 0.90, penalty becomes 0.92
            behaviourMultiplier = volumeCertainty >= 0.90 ? 0.92 : 0.85;
        } else {
            behaviourMultiplier = 1.00;
        }
        
        // CG Multiplier
        double cgMultiplier = 1.00;
        if (sameClusterActive) {
            cgMultiplier = 0.80;
        } else if (oppositeClusterActive) {
            cgMultiplier = 0.70;
        }
        
        // Calculate final signal strength
        double rawScore = fudkiiStrength 
                        * volumeCertainty 
                        * velocityAdjusted 
                        * structuralScore 
                        * behaviourMultiplier 
                        * cgMultiplier;
        
        rawScore = clamp(rawScore, 0.0, 1.0);
        
        NormalizedScore signalScore = NormalizedScore.strength(rawScore, previousScore, timestamp);
        
        return SignalStrengthScore.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(timestamp)
                .fudkiiStrength(fudkiiStrength)
                .volumeCertainty(volumeCertainty)
                .velocityAdjusted(velocityAdjusted)
                .velocityRaw(velocityMMS)
                .structuralScore(structuralScore)
                .behaviourMultiplier(behaviourMultiplier)
                .behaviourResult(behaviourResult)
                .cgMultiplier(cgMultiplier)
                .sameClusterActive(sameClusterActive)
                .oppositeClusterActive(oppositeClusterActive)
                .signalScore(signalScore)
                .isValid(true)
                .build();
    }
    
    public static SignalStrengthScore invalid(String scripCode, String companyName, long timestamp, String reason) {
        return SignalStrengthScore.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(timestamp)
                .signalScore(NormalizedScore.neutral(timestamp))
                .isValid(false)
                .invalidReason(reason)
                .build();
    }
    
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
    
    // ======================== SERIALIZATION ========================
    
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    public static Serde<SignalStrengthScore> serde() {
        return Serdes.serdeFrom(new SignalStrengthScoreSerializer(), new SignalStrengthScoreDeserializer());
    }
    
    public static class SignalStrengthScoreSerializer implements Serializer<SignalStrengthScore> {
        @Override
        public byte[] serialize(String topic, SignalStrengthScore data) {
            try {
                return data != null ? MAPPER.writeValueAsBytes(data) : null;
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed", e);
            }
        }
    }
    
    public static class SignalStrengthScoreDeserializer implements Deserializer<SignalStrengthScore> {
        @Override
        public SignalStrengthScore deserialize(String topic, byte[] bytes) {
            try {
                return bytes != null ? MAPPER.readValue(bytes, SignalStrengthScore.class) : null;
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed", e);
            }
        }
    }
}
