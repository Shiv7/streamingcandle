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
 * FUDKIIOutput - Output from Module 5: FUDKII (Ignition Engine)
 * 
 * Detects first valid volatility expansion - the ignition point of a move.
 * Measures simultaneity of price breakout, volume surge, and momentum.
 * 
 * Emits to topic: fudkii-output
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FUDKIIOutput {

    // Identification
    private String scripCode;
    private String companyName;
    private long timestamp;
    
    // Core FUDKII metrics
    private double fudkiiStrength;       // [0,1] - overall ignition strength
    private int simultaneityScore;       // 0-5: how many conditions met at once
    private boolean ignitionFlag;        // True if valid ignition detected
    
    // Individual conditions
    private boolean priceBreaking;       // Price broke recent high/low
    private boolean volumeSurging;       // Volume > 2x average
    private boolean momentumPositive;    // Momentum score > threshold
    private boolean atrExpanding;        // ATR > SMA20(ATR) * 1.2
    private boolean flowConfirming;      // Volume delta in same direction as price
    
    // Threshold comparisons
    private double priceVsHighLow;       // How far above/below recent range
    private double volumeRatio;          // Current volume / average volume
    private double atrRatio;             // Current ATR / average ATR
    private double momentumScore;        // From IPU momentum context
    private double flowScore;            // Volume delta strength
    
    // Direction of ignition
    private IgnitionDirection direction;
    
    /**
     * Ignition direction classification
     */
    public enum IgnitionDirection {
        BULLISH_IGNITION("Upward breakout detected"),
        BEARISH_IGNITION("Downward breakdown detected"),
        NO_IGNITION("No ignition detected");

        private final String description;

        IgnitionDirection(String description) {
            this.description = description;
        }

        public boolean isValid() {
            return this != NO_IGNITION;
        }

        public int getDirectionSign() {
            return this == BULLISH_IGNITION ? 1 : (this == BEARISH_IGNITION ? -1 : 0);
        }
    }
    
    /**
     * Check if this is a strong ignition signal
     */
    public boolean isStrongIgnition() {
        return ignitionFlag && simultaneityScore >= 4 && fudkiiStrength >= 0.7;
    }
    
    /**
     * Get position size multiplier based on ignition strength
     */
    public double getPositionMultiplier() {
        if (!ignitionFlag) return 0.5;
        if (isStrongIgnition()) return 1.2;
        if (simultaneityScore >= 3) return 1.0;
        return 0.8;
    }
    
    // ========== Serialization ==========
    
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    public static Serde<FUDKIIOutput> serde() {
        return Serdes.serdeFrom(new FUDKIIOutputSerializer(), new FUDKIIOutputDeserializer());
    }
    
    public static class FUDKIIOutputSerializer implements Serializer<FUDKIIOutput> {
        @Override
        public byte[] serialize(String topic, FUDKIIOutput data) {
            try {
                return data != null ? MAPPER.writeValueAsBytes(data) : null;
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed", e);
            }
        }
    }
    
    public static class FUDKIIOutputDeserializer implements Deserializer<FUDKIIOutput> {
        @Override
        public FUDKIIOutput deserialize(String topic, byte[] bytes) {
            try {
                return bytes != null ? MAPPER.readValue(bytes, FUDKIIOutput.class) : null;
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed", e);
            }
        }
    }
}
