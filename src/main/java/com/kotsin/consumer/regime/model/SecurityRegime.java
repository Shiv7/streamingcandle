package com.kotsin.consumer.regime.model;

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
 * SecurityRegime - Output from Module 2: Security Regime Calculator
 * 
 * Determines if individual stock is aligned with or diverging from index regime.
 * Applies EMA ordering analysis and ATR expansion/compression checks.
 * 
 * Emits to topic: regime-security-output
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SecurityRegime {

    // Identification
    private String scripCode;
    private String companyName;
    private String parentIndexCode;     // Which index this security relates to
    private long timestamp;
    
    // EMA Analysis (30m timeframe)
    private double ema12;
    private double ema60;
    private double ema240;
    private EMAAlignment emaAlignment;   // BULLISH_ALIGNED, BEARISH_ALIGNED, MIXED
    
    // ATR Analysis
    private double atr14;
    private double avgAtr20;             // 20-period average ATR
    private double atrExpansionRatio;    // atr14 / avgAtr20
    private ATRState atrState;           // COMPRESSED, NORMAL, EXPANDING
    
    // Regime Scores
    private double securityStrength;     // [0,1] - based on EMA + ATR
    private double indexAlignmentScore;  // [0,1] - how aligned with parent index
    private double divergenceScore;      // [0,1] - how much it diverges
    private RegimeLabel label;           // Classification
    
    // Index-relative penalties/boosts
    private double indexFlowMultiplier;  // 0.75 if diverging, 1.10 if aligned
    private boolean alignedWithIndex;    // true if same direction as index
    
    // Combined regime score (after index adjustment)
    private double finalRegimeScore;     // securityStrength * indexFlowMultiplier
    
    // Flow information
    private int securityFlowSign;        // +1/-1/0 based on volume delta
    private int indexFlowSign;           // Parent index flow sign
    
    /**
     * EMA Alignment classification
     */
    public enum EMAAlignment {
        BULLISH_ALIGNED("EMA12 > EMA60 > EMA240"),
        BEARISH_ALIGNED("EMA12 < EMA60 < EMA240"),
        MIXED_BULLISH("Partial bullish alignment"),
        MIXED_BEARISH("Partial bearish alignment"),
        CHOPPY("No clear alignment");

        private final String description;

        EMAAlignment(String description) {
            this.description = description;
        }

        public boolean isBullish() {
            return this == BULLISH_ALIGNED || this == MIXED_BULLISH;
        }

        public boolean isBearish() {
            return this == BEARISH_ALIGNED || this == MIXED_BEARISH;
        }

        /**
         * Determine EMA alignment from values
         */
        public static EMAAlignment fromEMAs(double ema12, double ema60, double ema240) {
            if (ema12 > ema60 && ema60 > ema240) return BULLISH_ALIGNED;
            if (ema12 < ema60 && ema60 < ema240) return BEARISH_ALIGNED;
            if (ema12 > ema60) return MIXED_BULLISH;
            if (ema12 < ema60) return MIXED_BEARISH;
            return CHOPPY;
        }
    }
    
    /**
     * ATR State classification
     */
    public enum ATRState {
        COMPRESSED(0.7, "Volatility squeeze, potential breakout"),
        NORMAL(1.0, "Typical volatility"),
        EXPANDING(1.3, "High volatility, trending market");

        private final double thresholdRatio;
        private final String description;

        ATRState(double threshold, String description) {
            this.thresholdRatio = threshold;
            this.description = description;
        }

        public static ATRState fromRatio(double ratio) {
            if (ratio < 0.7) return COMPRESSED;
            if (ratio > 1.3) return EXPANDING;
            return NORMAL;
        }
    }
    
    /**
     * Check if security is tradeable
     */
    public boolean isTradeable() {
        return finalRegimeScore >= 0.5 && alignedWithIndex;
    }
    
    /**
     * Get direction as integer
     */
    public int getDirectionSign() {
        return label.getValue() > 0 ? 1 : (label.getValue() < 0 ? -1 : 0);
    }
    
    // ========== Serialization ==========
    
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    public static Serde<SecurityRegime> serde() {
        return Serdes.serdeFrom(new SecurityRegimeSerializer(), new SecurityRegimeDeserializer());
    }
    
    public static class SecurityRegimeSerializer implements Serializer<SecurityRegime> {
        @Override
        public byte[] serialize(String topic, SecurityRegime data) {
            try {
                return data != null ? MAPPER.writeValueAsBytes(data) : null;
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed", e);
            }
        }
    }
    
    public static class SecurityRegimeDeserializer implements Deserializer<SecurityRegime> {
        @Override
        public SecurityRegime deserialize(String topic, byte[] bytes) {
            try {
                return bytes != null ? MAPPER.readValue(bytes, SecurityRegime.class) : null;
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed", e);
            }
        }
    }
}
