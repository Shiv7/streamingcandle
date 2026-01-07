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

import java.util.ArrayList;
import java.util.List;

/**
 * MTVCPOutput - Multi-Timeframe Volume Cluster Pivot Output
 * 
 * Combined output containing:
 * - Fused VCP score across timeframes (5m, 15m, 30m)
 * - Directional support/resistance scores
 * - Structural bias (-1 to +1)
 * - Runway score (how clean the path is)
 * - Top clusters with full enrichment details
 * 
 * Emitted to topic: vcp-combined
 * Key: scripCode
 * Frequency: Every 5 minutes (on 5m candle close)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MTVCPOutput {

    // ========== Metadata ==========
    private String scripCode;
    private String companyName;
    private long timestamp;

    // ========== Primary Combined Score ==========
    /**
     * VCP Combined Score (0 to 1)
     * Weighted fusion: 0.50 * vcp5m + 0.30 * vcp15m + 0.20 * vcp30m
     * 
     * Interpretation:
     * >= 0.80: Price on major institutional levels, expect choppy action
     * 0.65-0.80: Significant structure, breakouts need conviction
     * 0.45-0.65: Moderate structure, normal conditions
     * 0.25-0.45: Weak structure, price has room to trend
     * < 0.25: No meaningful clusters, price in "open air"
     */
    private double vcpCombinedScore;

    // ========== Directional Scores ==========
    /**
     * Support Score (0 to 1)
     * Aggregate strength of clusters below current price
     * Weighted by alignment (buying clusters = stronger support)
     */
    private double supportScore;

    /**
     * Resistance Score (0 to 1)
     * Aggregate strength of clusters above current price
     * Weighted by alignment (selling clusters = stronger resistance)
     */
    private double resistanceScore;

    // ========== Structural Metrics ==========
    /**
     * Structural Bias (-1 to +1)
     * (supportScore - resistanceScore) / (supportScore + resistanceScore + epsilon)
     * 
     * Interpretation:
     * > 0.5: Strong support, weak resistance - bullish structure
     * 0.2 to 0.5: Moderate bullish structure
     * -0.2 to 0.2: Balanced, no directional edge
     * -0.5 to -0.2: Moderate bearish structure
     * < -0.5: Strong resistance, weak support - bearish structure
     */
    private double structuralBias;

    /**
     * Runway Score (0 to 1)
     * 1 / (1 + sum of cluster difficulties in direction of potential movement)
     * 
     * Interpretation:
     * > 0.7: Clean runway, good for trend trades
     * 0.4-0.7: Some obstacles but manageable
     * < 0.4: Heavily contested zone, breakouts likely to stall
     */
    private double runwayScore;

    // ========== Per-Timeframe Scores ==========
    /**
     * VCP score for 5-minute timeframe
     */
    private double vcp5m;

    /**
     * VCP score for 15-minute timeframe
     */
    private double vcp15m;

    /**
     * VCP score for 30-minute timeframe
     */
    private double vcp30m;

    // ========== Current Market Context ==========
    /**
     * Current price (from latest 5m candle close)
     */
    private double currentPrice;

    /**
     * Average True Range (for context)
     */
    private double atr;

    /**
     * Current microprice (from orderbook)
     */
    private double microprice;

    // ========== Top Clusters ==========
    /**
     * Top clusters (up to 5) with full enrichment details
     * Sorted by composite score descending
     */
    @Builder.Default
    private List<VCPCluster> clusters = new ArrayList<>();
    
    // ========== PHASE 2: Data Quality Metrics ==========
    /**
     * Ratio of estimated vs real data (0 to 1)
     * 0.0 = All real volumeAtPrice data
     * 1.0 = All estimated/fallback data
     * 
     * Interpretation:
     * < 0.30: High confidence - mostly real data
     * 0.30-0.50: Medium confidence - mixed data
     * > 0.50: Low confidence - mostly estimated (⚠️ use with caution!)
     */
    private double estimatedDataRatio;
    
    /**
     * True if data quality is high (< 30% estimated)
     * Use this to filter out low-quality VCP signals
     */
    private boolean isHighConfidence;
    
    /**
     * Human-readable warning about data quality
     * null if no issues
     * "ESTIMATED_DATA: XX% fallback" if low quality
     */
    private String dataQualityWarning;

    // ========== Convenience Getters ==========

    /**
     * Get interpretation band for VCP combined score
     */
    public String getVcpInterpretation() {
        if (vcpCombinedScore >= 0.80) {
            return "MAJOR_LEVELS";
        } else if (vcpCombinedScore >= 0.65) {
            return "SIGNIFICANT_STRUCTURE";
        } else if (vcpCombinedScore >= 0.45) {
            return "MODERATE_STRUCTURE";
        } else if (vcpCombinedScore >= 0.25) {
            return "WEAK_STRUCTURE";
        } else {
            return "OPEN_AIR";
        }
    }

    /**
     * Get interpretation band for structural bias
     */
    public String getBiasInterpretation() {
        if (structuralBias > 0.5) {
            return "STRONGLY_BULLISH";
        } else if (structuralBias > 0.2) {
            return "MODERATELY_BULLISH";
        } else if (structuralBias > -0.2) {
            return "NEUTRAL";
        } else if (structuralBias > -0.5) {
            return "MODERATELY_BEARISH";
        } else {
            return "STRONGLY_BEARISH";
        }
    }

    /**
     * Get interpretation band for runway score
     */
    public String getRunwayInterpretation() {
        if (runwayScore > 0.7) {
            return "CLEAN_RUNWAY";
        } else if (runwayScore > 0.4) {
            return "MANAGEABLE_OBSTACLES";
        } else {
            return "HEAVILY_CONTESTED";
        }
    }

    /**
     * Check if conditions favor long trades
     */
    public boolean favorsLong() {
        return structuralBias > 0 || runwayScore > 0.6;
    }

    /**
     * Check if conditions favor short trades
     */
    public boolean favorsShort() {
        return structuralBias < 0 || runwayScore > 0.6;
    }

    /**
     * Get nearest support cluster
     */
    public VCPCluster getNearestSupport() {
        return clusters.stream()
                .filter(c -> c.getType() == VCPCluster.ClusterType.SUPPORT)
                .findFirst()
                .orElse(null);
    }

    /**
     * Get nearest resistance cluster
     */
    public VCPCluster getNearestResistance() {
        return clusters.stream()
                .filter(c -> c.getType() == VCPCluster.ClusterType.RESISTANCE)
                .findFirst()
                .orElse(null);
    }

    /**
     * Get position sizing multiplier based on structure
     */
    public double getPositionSizeMultiplier() {
        if (runwayScore > 0.7 && Math.abs(structuralBias) > 0.3) {
            return 1.2;  // High conviction
        } else if (vcpCombinedScore > 0.75 && runwayScore < 0.4) {
            return 0.7;  // Reduce size in congested zone
        }
        return 1.0;
    }

    // ========== Kafka Serde ==========
    private static final ObjectMapper SHARED_OBJECT_MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    public static Serde<MTVCPOutput> serde() {
        return Serdes.serdeFrom(new MTVCPOutputSerializer(), new MTVCPOutputDeserializer());
    }

    public static class MTVCPOutputSerializer implements Serializer<MTVCPOutput> {
        @Override
        public byte[] serialize(String topic, MTVCPOutput data) {
            if (data == null) return null;
            try {
                return SHARED_OBJECT_MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for MTVCPOutput", e);
            }
        }
    }

    public static class MTVCPOutputDeserializer implements Deserializer<MTVCPOutput> {
        @Override
        public MTVCPOutput deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return SHARED_OBJECT_MAPPER.readValue(bytes, MTVCPOutput.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for MTVCPOutput", e);
            }
        }
    }
}
