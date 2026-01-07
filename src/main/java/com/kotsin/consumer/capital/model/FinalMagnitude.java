package com.kotsin.consumer.capital.model;

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
 * FinalMagnitude - Output from Module 14: Final Magnitude Assembly
 * 
 * This is the RANKING BACKBONE of the system.
 * Combines all module outputs into a single ranked magnitude score.
 * 
 * Formula:
 * FinalMagnitude = BaseSignal × ACL × Volume × CSS × (1 - SOM) × (1 - VTD)
 * 
 * Emits to topic: magnitude-final
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FinalMagnitude {

    // Identification
    private String scripCode;
    private String companyName;
    private long timestamp;
    
    // Final computed magnitude
    private double finalMagnitude;       // [0,1] - THE ranking score
    private int rank;                    // Position in watchlist
    
    // Direction and confidence
    private Direction direction;
    private double directionConfidence;  // [0,1]
    private String signalType;           // From TradingSignal
    
    // Component scores (for debugging/analysis)
    private ComponentScores components;
    
    // Multipliers applied
    private MultiplierBreakdown multipliers;
    
    // Penalties applied
    private PenaltyBreakdown penalties;
    
    // Trade parameters
    private TradeParams tradeParams;
    
    // Quality flags
    private QualityFlags flags;
    
    /**
     * Direction enum
     */
    public enum Direction {
        BULLISH(1, "Long bias"),
        BEARISH(-1, "Short bias"),
        NEUTRAL(0, "No directional bias");

        private final int sign;
        private final String description;

        Direction(int sign, String description) {
            this.sign = sign;
            this.description = description;
        }

        public int getSign() {
            return sign;
        }
    }
    
    /**
     * Component scores from each module
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComponentScores {
        private double ipuScore;         // From IPUCalculator
        private double vcpScore;         // From VCPCalculator
        private double regimeStrength;   // From IndexRegime
        private double securityStrength; // From SecurityRegime
        private double fudkiiStrength;   // From FUDKII
        private double cssScore;         // Composite Structure Score
        private double momentumScore;    // From IPU momentum
    }
    
    /**
     * Multipliers breakdown
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MultiplierBreakdown {
        private double aclMultiplier;       // From ACL module
        private double volumeMultiplier;    // From Volume Canonical
        private double cssMultiplier;       // From CSS module
        private double regimeMultiplier;    // From regime alignment
        private double ignitionMultiplier;  // From FUDKII
    }
    
    /**
     * Penalties breakdown
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PenaltyBreakdown {
        private double somPenalty;        // From SOM (choppiness)
        private double vtdPenalty;        // From VTD (volatility trap)
        private double exhaustionPenalty; // From IPU exhaustion
        private double divergencePenalty; // From index divergence
    }
    
    /**
     * Trade parameters derived from magnitude
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TradeParams {
        private double entryPrice;
        private double stopLoss;
        private double target1;
        private double target2;
        private double riskRewardRatio;
        private double suggestedPositionPct;  // % of capital to deploy
    }
    
    /**
     * Quality flags
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualityFlags {
        private boolean indexAligned;
        private boolean ignitionDetected;
        private boolean highConfidence;
        private boolean exhaustionWarning;
        private boolean volumeConfirmed;
        private boolean multiTfAligned;
    }
    
    /**
     * Check if this is a high-quality signal
     */
    public boolean isHighQuality() {
        return finalMagnitude >= 0.7 
            && directionConfidence >= 0.6
            && flags != null
            && flags.isIndexAligned()
            && flags.isVolumeConfirmed();
    }
    
    /**
     * Check if tradeable
     */
    public boolean isTradeable() {
        return finalMagnitude >= 0.5 
            && direction != Direction.NEUTRAL
            && (flags == null || !flags.isExhaustionWarning());
    }
    
    /**
     * Get position sizing multiplier based on magnitude
     */
    public double getPositionSizeMultiplier() {
        if (finalMagnitude >= 0.9) return 1.5;
        if (finalMagnitude >= 0.8) return 1.25;
        if (finalMagnitude >= 0.7) return 1.0;
        if (finalMagnitude >= 0.5) return 0.75;
        return 0.5;
    }
    
    // ========== Serialization ==========
    
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    public static Serde<FinalMagnitude> serde() {
        return Serdes.serdeFrom(new FinalMagnitudeSerializer(), new FinalMagnitudeDeserializer());
    }
    
    public static class FinalMagnitudeSerializer implements Serializer<FinalMagnitude> {
        @Override
        public byte[] serialize(String topic, FinalMagnitude data) {
            try {
                return data != null ? MAPPER.writeValueAsBytes(data) : null;
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed", e);
            }
        }
    }
    
    public static class FinalMagnitudeDeserializer implements Deserializer<FinalMagnitude> {
        @Override
        public FinalMagnitude deserialize(String topic, byte[] bytes) {
            try {
                return bytes != null ? MAPPER.readValue(bytes, FinalMagnitude.class) : null;
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed", e);
            }
        }
    }
}
