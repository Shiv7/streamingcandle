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
 * CSSOutput - Output from Module 6C: Composite Structure Score
 * 
 * Combines VCP fast (5m), VCP slow (30m), and CPS (pivot) into unified structure score.
 * Formula: CSS = 0.45*vcpFast + 0.35*vcpSlow + 0.20*cpsScore
 * 
 * Emits to topic: css-output
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CSSOutput {

    // Identification
    private String scripCode;
    private String companyName;
    private long timestamp;
    
    // Component Scores
    private double vcpFast;          // VCP 5m score [0,1]
    private double vcpSlow;          // VCP 30m score [0,1]
    private double cpsScore;         // CPR/Pivot proximity score [0,1]
    
    // Composite Score
    private double cssScore;         // Combined [0,1]
    
    // Structure interpretation
    private StructureState structureState;
    
    // Bias direction
    private double structuralBias;   // -1 to +1 (from VCP)
    private double runwayScore;      // How clear the path is
    
    // Pivot levels (from CPS)
    private PivotLevels pivotLevels;
    
    /**
     * Structure state classification
     */
    public enum StructureState {
        MAJOR_LEVELS("Price at major institutional levels", 0.3),
        SIGNIFICANT_STRUCTURE("Significant structure present", 0.5),
        MODERATE_STRUCTURE("Moderate structure", 0.7),
        WEAK_STRUCTURE("Weak structure, room to trend", 0.9),
        OPEN_AIR("No meaningful clusters", 1.1);

        private final String description;
        private final double trendMultiplier;

        StructureState(String description, double trendMultiplier) {
            this.description = description;
            this.trendMultiplier = trendMultiplier;
        }

        public double getTrendMultiplier() {
            return trendMultiplier;
        }

        public static StructureState fromScore(double cssScore) {
            if (cssScore >= 0.80) return MAJOR_LEVELS;
            if (cssScore >= 0.65) return SIGNIFICANT_STRUCTURE;
            if (cssScore >= 0.45) return MODERATE_STRUCTURE;
            if (cssScore >= 0.25) return WEAK_STRUCTURE;
            return OPEN_AIR;
        }
    }
    
    /**
     * Pivot levels from CPS module
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PivotLevels {
        private double pivot;        // Central pivot
        private double r1;           // Resistance 1
        private double r2;           // Resistance 2
        private double r3;           // Resistance 3
        private double s1;           // Support 1
        private double s2;           // Support 2
        private double s3;           // Support 3
        private double cprTop;       // CPR top
        private double cprBottom;    // CPR bottom
        private boolean inCPR;       // Price inside CPR zone
        private CPRWidth cprWidth;   // Narrow/Normal/Wide
    }
    
    /**
     * CPR Width classification
     */
    public enum CPRWidth {
        NARROW("< 0.3% range, expect breakout"),
        NORMAL("0.3-0.7% range, balanced"),
        WIDE("> 0.7% range, range-bound day");

        private final String description;

        CPRWidth(String description) {
            this.description = description;
        }
    }
    
    /**
     * Check if structure favors trending
     */
    public boolean favorsTrend() {
        return structureState == StructureState.WEAK_STRUCTURE 
            || structureState == StructureState.OPEN_AIR;
    }
    
    /**
     * Get multiplier for position sizing
     */
    public double getPositionMultiplier() {
        if (structureState == StructureState.MAJOR_LEVELS) return 0.7;
        if (structureState == StructureState.OPEN_AIR) return 1.2;
        return 1.0;
    }
    
    // ========== Serialization ==========
    
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    public static Serde<CSSOutput> serde() {
        return Serdes.serdeFrom(new CSSOutputSerializer(), new CSSOutputDeserializer());
    }
    
    public static class CSSOutputSerializer implements Serializer<CSSOutput> {
        @Override
        public byte[] serialize(String topic, CSSOutput data) {
            try {
                return data != null ? MAPPER.writeValueAsBytes(data) : null;
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed", e);
            }
        }
    }
    
    public static class CSSOutputDeserializer implements Deserializer<CSSOutput> {
        @Override
        public CSSOutput deserialize(String topic, byte[] bytes) {
            try {
                return bytes != null ? MAPPER.readValue(bytes, CSSOutput.class) : null;
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed", e);
            }
        }
    }
}
