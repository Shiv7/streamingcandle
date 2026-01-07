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
 * VTDOutput - Output from Module 10: Volatility Trap Detector
 * 
 * Detects when volatility expansion is fake/unsustainable:
 * - IV/ATR divergence (implied vs realized)
 * - Volume not confirming volatility
 * - Spike patterns (gap then fade)
 * - OI divergence
 * 
 * VTD penalty reduces position size in trap conditions.
 * 
 * Emits to topic: vtd-output
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class VTDOutput {

    // Identification
    private String scripCode;
    private String companyName;
    private long timestamp;
    
    // Core VTD Score
    private double vtdScore;          // 0 to 1 (1 = high trap probability)
    private double vtdPenalty;        // 0 to 0.5 penalty to apply
    
    // Component scores
    private double ivAtrDivergence;   // IV much higher than realized ATR
    private double volumeConfirmation; // Does volume support the move
    private double spikePattern;      // Gap then fade detected
    private double oiDivergence;      // OI not supporting the move
    
    // Trap classification
    private TrapType trapType;
    private boolean trapActive;
    
    // Volatility metrics
    private double currentATR;
    private double averageATR;
    private double atrRatio;
    private double impliedVolatility;  // From options if available
    private Double ivPercentile;       // IV rank 0-100 (null if OHM not available)
    
    /**
     * Trap type classification
     */
    public enum TrapType {
        NO_TRAP("No volatility trap detected", 0.0),
        MILD_DIVERGENCE("Slight IV/ATR divergence", 0.1),
        VOLUME_TRAP("Volume not confirming expansion", 0.2),
        SPIKE_TRAP("Spike pattern detected", 0.3),
        IV_CRUSH_RISK("High IV crush probability", 0.35),
        MAJOR_TRAP("Multiple trap signals", 0.5);

        private final String description;
        private final double penalty;

        TrapType(String description, double penalty) {
            this.description = description;
            this.penalty = penalty;
        }

        public double getPenalty() {
            return penalty;
        }
    }
    
    /**
     * Check if entry should be avoided
     */
    public boolean shouldAvoid() {
        return trapType == TrapType.MAJOR_TRAP || trapType == TrapType.IV_CRUSH_RISK;
    }
    
    /**
     * Get position size multiplier
     */
    public double getPositionMultiplier() {
        return 1.0 - vtdPenalty;
    }
    
    // ========== Serialization ==========
    
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    public static Serde<VTDOutput> serde() {
        return Serdes.serdeFrom(new VTDOutputSerializer(), new VTDOutputDeserializer());
    }
    
    public static class VTDOutputSerializer implements Serializer<VTDOutput> {
        @Override
        public byte[] serialize(String topic, VTDOutput data) {
            try {
                return data != null ? MAPPER.writeValueAsBytes(data) : null;
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed", e);
            }
        }
    }
    
    public static class VTDOutputDeserializer implements Deserializer<VTDOutput> {
        @Override
        public VTDOutput deserialize(String topic, byte[] bytes) {
            try {
                return bytes != null ? MAPPER.readValue(bytes, VTDOutput.class) : null;
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed", e);
            }
        }
    }
}
