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
 * ACLOutput - Output from Module 3: Anti-Cycle Limiter
 * 
 * Prevents late-stage entries into exhausted trends.
 * Tracks trend age across timeframes and applies multipliers.
 * 
 * Emits to topic: regime-acl-output
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ACLOutput {

    // Identification
    private String scripCode;
    private String companyName;
    private long timestamp;
    
    // Trend age per timeframe (in candles since trend start)
    private int trendAge30m;
    private int trendAge2H;
    private int trendAge4H;
    private int trendAge1D;
    
    // Cross-TF agreement
    private int agreementScore;          // 0-4: how many TFs agree on direction
    private boolean isOrderedTransition; // Higher TFs lead lower TFs
    private boolean isChaoticDisagreement; // TFs contradict each other
    
    // ACL Multiplier
    private double aclMultiplier;        // 0.7 to 1.1 adjustment factor
    private ACLState aclState;           // Classification of trend state
    
    // Trend direction info
    private int trendDirection;          // +1/-1/0
    private boolean exhaustionNear;      // Late in cycle warning
    
    // Flow agreement across TFs
    private int[] tfFlowAgreements;      // [30m, 2H, 4H, 1D] flow signs
    
    /**
     * ACL State classification
     */
    public enum ACLState {
        EARLY_TREND("Early stage, fresh momentum", 1.1),
        MID_TREND("Established trend, good entry", 1.0),
        LATE_TREND("Late stage, be cautious", 0.85),
        EXHAUSTION("Trend exhaustion imminent", 0.7),
        TRANSITION("Trend changing, wait for confirmation", 0.9);

        private final String description;
        private final double multiplier;

        ACLState(String description, double multiplier) {
            this.description = description;
            this.multiplier = multiplier;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public String getDescription() {
            return description;
        }
    }
    
    /**
     * Check if entry is allowed
     */
    public boolean isEntryAllowed() {
        return aclMultiplier >= 0.8 && !exhaustionNear;
    }
    
    /**
     * Get position size adjustment factor
     */
    public double getPositionSizeMultiplier() {
        return aclMultiplier;
    }
    
    // ========== Serialization ==========
    
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    public static Serde<ACLOutput> serde() {
        return Serdes.serdeFrom(new ACLOutputSerializer(), new ACLOutputDeserializer());
    }
    
    public static class ACLOutputSerializer implements Serializer<ACLOutput> {
        @Override
        public byte[] serialize(String topic, ACLOutput data) {
            try {
                return data != null ? MAPPER.writeValueAsBytes(data) : null;
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed", e);
            }
        }
    }
    
    public static class ACLOutputDeserializer implements Deserializer<ACLOutput> {
        @Override
        public ACLOutput deserialize(String topic, byte[] bytes) {
            try {
                return bytes != null ? MAPPER.readValue(bytes, ACLOutput.class) : null;
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed", e);
            }
        }
    }
}
