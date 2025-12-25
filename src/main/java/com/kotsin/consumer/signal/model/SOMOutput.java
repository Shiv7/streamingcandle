package com.kotsin.consumer.signal.model;

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
 * SOMOutput - Output from Module 9: Sentiment Oscillation Module
 * 
 * Detects choppy, indecisive market conditions using:
 * - VWAP failure rate
 * - Wick rejection patterns
 * - Choppiness index (ADX-based)
 * - Trap detection (failed breakouts)
 * 
 * SOM penalty reduces magnitude in choppy conditions.
 * 
 * Emits to topic: som-output
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SOMOutput {

    // Identification
    private String scripCode;
    private String companyName;
    private long timestamp;
    
    // Core SOM score
    private double somScore;          // -1 (extreme chop) to +1 (clean trend)
    private double somPenalty;        // 0 to 0.5 penalty to apply to magnitude
    
    // Component scores
    private double vwapFailureScore;  // How often price fails at VWAP
    private double wickRejectionScore; // Wick as % of range (high = rejection)
    private double choppinessIndex;   // CMO/ADX-based choppiness
    private double trapScore;         // Failed breakout detection
    
    // Trap flags
    private boolean bullTrapDetected; // Failed upward breakout
    private boolean bearTrapDetected; // Failed downward breakout
    
    // Sentiment classification
    private SentimentState sentimentState;
    
    /**
     * Sentiment state classification
     */
    public enum SentimentState {
        CLEAN_TREND("Clear directional movement", 0.0),
        SLIGHTLY_CHOPPY("Minor noise, tradeable", 0.1),
        CHOPPY("Significant noise, reduce size", 0.25),
        VERY_CHOPPY("High noise, avoid new entries", 0.4),
        WHIPSAW("Extreme reversals, stay out", 0.5);

        private final String description;
        private final double penalty;

        SentimentState(String description, double penalty) {
            this.description = description;
            this.penalty = penalty;
        }

        public double getPenalty() {
            return penalty;
        }

        public static SentimentState fromScore(double somScore) {
            if (somScore >= 0.6) return CLEAN_TREND;
            if (somScore >= 0.3) return SLIGHTLY_CHOPPY;
            if (somScore >= 0.0) return CHOPPY;
            if (somScore >= -0.3) return VERY_CHOPPY;
            return WHIPSAW;
        }
    }
    
    /**
     * Check if conditions are tradeable
     */
    public boolean isTradeable() {
        return sentimentState != SentimentState.WHIPSAW 
            && sentimentState != SentimentState.VERY_CHOPPY;
    }
    
    /**
     * Check if any trap detected
     */
    public boolean hasTrap() {
        return bullTrapDetected || bearTrapDetected;
    }
    
    // ========== Serialization ==========
    
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    public static Serde<SOMOutput> serde() {
        return Serdes.serdeFrom(new SOMOutputSerializer(), new SOMOutputDeserializer());
    }
    
    public static class SOMOutputSerializer implements Serializer<SOMOutput> {
        @Override
        public byte[] serialize(String topic, SOMOutput data) {
            try {
                return data != null ? MAPPER.writeValueAsBytes(data) : null;
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed", e);
            }
        }
    }
    
    public static class SOMOutputDeserializer implements Deserializer<SOMOutput> {
        @Override
        public SOMOutput deserialize(String topic, byte[] bytes) {
            try {
                return bytes != null ? MAPPER.readValue(bytes, SOMOutput.class) : null;
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed", e);
            }
        }
    }
}
