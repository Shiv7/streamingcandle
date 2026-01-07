package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 * IPUOutput - Institutional Participation & Urgency with Integrated Momentum Context
 * 
 * Combined module that provides:
 * 1. Institutional Participation (efficiency + flow quality)
 * 2. Momentum Context (slope, acceleration, validation)
 * 3. Exhaustion Detection
 * 4. Urgency Classification
 * 5. Directional Conviction
 * 6. X-Factor Scoring
 * 
 * Emitted to: ipu-signals-{5m,15m,30m}, ipu-combined
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class IPUOutput {

    // ========== Metadata ==========
    private String scripCode;
    private String companyName;
    private String timeframe;
    private long timestamp;

    // ========== Primary Score ==========
    /**
     * Final IPU Score (0 to 1)
     * Combined institutional participation with momentum context
     * 
     * >= 0.80: Extreme institutional activity with momentum
     * 0.60-0.80: Strong institutional presence
     * 0.40-0.60: Moderate activity
     * 0.20-0.40: Weak signal
     * < 0.20: No meaningful institutional footprint
     */
    private double finalIpuScore;

    // ========== Participation Components ==========
    private double volExpansionScore;     // Volume vs SMA20
    private double priceEfficiency;        // Directional move / total range with wick penalty
    private double ofQuality;              // Order flow quality (flow agreement)
    private double instProxy;              // Institutional proxy (efficiency × flow × volume)

    // ========== Momentum Components ==========
    private double momentumContext;        // Combined slope + acceleration
    private double slopeMagnitude;         // Absolute slope (0-1)
    private double accelMagnitude;         // Absolute acceleration (0-1)
    private double mmsSlope;               // Signed momentum slope (-1 to +1)
    private double mmsAcceleration;        // Signed acceleration
    private double validatedMomentum;      // Flow-validated momentum
    private double momentumAlignment;      // Current bar alignment with trend
    private double flowMomentumAgreement;  // Flow-momentum validation score
    private MomentumState momentumState;   // Classification

    // ========== Exhaustion ==========
    private double exhaustionScore;        // 0-1, higher = more exhausted
    private boolean exhaustionWarning;     // True if exhaustion_score >= 0.6

    // ========== Urgency ==========
    private double urgencyScore;           // 0-1
    private UrgencyLevel urgencyLevel;     // Classification

    // ========== Direction ==========
    private Direction direction;           // BULLISH, BEARISH, NEUTRAL
    private double directionalConviction;  // 0-1

    // ========== X-Factor ==========
    private double xfactorScore;           // 0-1
    private boolean xfactorFlag;           // True if exceptional move

    // ========== Confidence ==========
    private double certainty;              // Overall confidence

    // ========== Market Context (for signal generation) ==========
    private double currentPrice;           // Close price of the candle
    private double atr;                    // Average True Range for position sizing

    // ========== Raw Inputs (for debugging) ==========
    @Builder.Default
    private RawInputs raw = new RawInputs();

    // ========== Enums ==========

    public enum MomentumState {
        ACCELERATING,   // Momentum building, trend strengthening
        DECELERATING,   // Momentum fading, trend weakening
        TRENDING,       // Steady trend, consistent pace
        DRIFTING,       // Weak directional bias
        FLAT            // No trend
    }

    public enum UrgencyLevel {
        AGGRESSIVE,     // >= 0.75: Institutions need fills NOW
        ELEVATED,       // 0.50-0.75: High activity
        PATIENT,        // 0.30-0.50: Working orders quietly
        PASSIVE         // < 0.30: Minimal urgency
    }

    public enum Direction {
        BULLISH,
        BEARISH,
        NEUTRAL
    }

    // ========== Raw Inputs Class ==========
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RawInputs {
        private double volRatio;
        private double volumeDeltaPct;
        private double ofiPressure;
        private double depthImbalance;
        private double tickDensity;
        private double priceChange3bar;
        private double wickRatio;
    }

    // ========== Convenience Methods ==========

    /**
     * Check if this is an actionable signal
     */
    public boolean isActionable() {
        return finalIpuScore >= 0.40 && direction != Direction.NEUTRAL && certainty >= 0.5;
    }

    /**
     * Get position size multiplier based on momentum state
     */
    public double getMomentumMultiplier() {
        if (momentumState == MomentumState.ACCELERATING && exhaustionScore < 0.3) {
            return 1.2;
        } else if (momentumState == MomentumState.DECELERATING || exhaustionWarning) {
            return 0.7;
        } else if (momentumState == MomentumState.FLAT) {
            return 0.5;
        }
        return 1.0;
    }

    /**
     * Get agreement multiplier for position sizing
     */
    public double getAgreementMultiplier() {
        if (flowMomentumAgreement > 0.8) {
            return 1.1;
        } else if (flowMomentumAgreement < 0.5) {
            return 0.8;
        }
        return 1.0;
    }

    /**
     * Get trailing stop ATR multiplier based on momentum
     */
    public double getTrailAtrMultiplier() {
        double mult;
        switch (momentumState) {
            case ACCELERATING:
                mult = 1.5;
                break;
            case TRENDING:
                mult = 1.2;
                break;
            case DECELERATING:
                mult = 0.8;
                break;
            default:
                mult = 1.0;
        }
        if (exhaustionWarning) {
            mult = Math.min(mult, 0.7);
        }
        return mult;
    }

    /**
     * Check for divergence warning
     */
    public boolean hasDivergence() {
        return momentumState == MomentumState.DECELERATING 
                && direction == Direction.BULLISH 
                && flowMomentumAgreement < 0.5;
    }

    /**
     * Get interpretation string for final score
     */
    public String getScoreInterpretation() {
        if (finalIpuScore >= 0.80) return "EXTREME_INSTITUTIONAL";
        if (finalIpuScore >= 0.60) return "STRONG_INSTITUTIONAL";
        if (finalIpuScore >= 0.40) return "MODERATE_ACTIVITY";
        if (finalIpuScore >= 0.20) return "WEAK_SIGNAL";
        return "NO_INSTITUTIONAL";
    }

    // ========== Kafka Serde ==========
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    public static Serde<IPUOutput> serde() {
        return Serdes.serdeFrom(new IPUOutputSerializer(), new IPUOutputDeserializer());
    }

    public static class IPUOutputSerializer implements Serializer<IPUOutput> {
        @Override
        public byte[] serialize(String topic, IPUOutput data) {
            if (data == null) return null;
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for IPUOutput", e);
            }
        }
    }

    public static class IPUOutputDeserializer implements Deserializer<IPUOutput> {
        @Override
        public IPUOutput deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return MAPPER.readValue(bytes, IPUOutput.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for IPUOutput", e);
            }
        }
    }
}
