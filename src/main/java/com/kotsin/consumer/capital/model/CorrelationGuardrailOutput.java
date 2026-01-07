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

import java.util.List;

/**
 * CorrelationGuardrailOutput - Output from Module 13: Correlation Guardrail
 * 
 * Prevents portfolio concentration risk:
 * - Sector heat tracking
 * - Direction concentration
 * - Beta exposure monitoring
 * - Maximum positions per sector
 * 
 * Applies position caps when portfolio heat exceeds thresholds.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CorrelationGuardrailOutput {

    // Identification
    private String scripCode;
    private String companyName;
    private String sector;
    private long timestamp;
    
    // Portfolio Heat
    private double portfolioHeat;          // 0-100 overall heat
    private double sectorHeat;             // 0-100 heat for this sector
    private double directionHeat;          // Concentration in one direction
    
    // Current Exposure
    private int openPositions;             // Total open positions
    private int sectorPositions;           // Positions in this sector
    private int sameDirPositions;          // Positions in same direction
    
    // Limits
    private int maxPositions;              // Portfolio max
    private int maxSectorPositions;        // Sector max
    private int maxSameDirPositions;       // Same direction max
    
    // Correlations
    private double avgCorrelation;         // Average correlation with portfolio
    private List<CorrelatedPosition> topCorrelated;  // Top correlated positions
    
    // Guardrail actions
    private GuardrailAction action;
    private double positionSizeMultiplier; // 0-1 reduction factor
    private String reason;                 // Why action taken
    
    /**
     * Correlated position info
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CorrelatedPosition {
        private String scripCode;
        private String companyName;
        private double correlation;
        private String direction;
    }
    
    /**
     * Guardrail action to take
     */
    public enum GuardrailAction {
        ALLOW("No restrictions", 1.0),
        REDUCE("Reduce position size", 0.7),
        SMALL_ONLY("Small position only", 0.4),
        BLOCK("Do not enter", 0.0);

        private final String description;
        private final double multiplier;

        GuardrailAction(String description, double multiplier) {
            this.description = description;
            this.multiplier = multiplier;
        }

        public double getMultiplier() {
            return multiplier;
        }
    }
    
    /**
     * Check if position is allowed
     */
    public boolean isAllowed() {
        return action != GuardrailAction.BLOCK;
    }
    
    /**
     * Get adjusted position size
     */
    public double getAdjustedSize(double baseSize) {
        return baseSize * positionSizeMultiplier;
    }
    
    // ========== Serialization ==========
    
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    public static Serde<CorrelationGuardrailOutput> serde() {
        return Serdes.serdeFrom(new CGSerializer(), new CGDeserializer());
    }
    
    public static class CGSerializer implements Serializer<CorrelationGuardrailOutput> {
        @Override
        public byte[] serialize(String topic, CorrelationGuardrailOutput data) {
            try {
                return data != null ? MAPPER.writeValueAsBytes(data) : null;
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed", e);
            }
        }
    }
    
    public static class CGDeserializer implements Deserializer<CorrelationGuardrailOutput> {
        @Override
        public CorrelationGuardrailOutput deserialize(String topic, byte[] bytes) {
            try {
                return bytes != null ? MAPPER.readValue(bytes, CorrelationGuardrailOutput.class) : null;
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed", e);
            }
        }
    }
}
