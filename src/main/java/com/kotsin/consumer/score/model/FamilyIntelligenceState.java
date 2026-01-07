package com.kotsin.consumer.score.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
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

import java.util.HashMap;
import java.util.Map;

/**
 * FamilyIntelligenceState - State store for MTIS calculation.
 * 
 * Persisted in RocksDB state store or ConcurrentHashMap cache.
 * Tracks per-family intelligence data across all timeframes.
 * 
 * Key: familyId
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FamilyIntelligenceState {

    // ==================== IDENTITY ====================
    private String familyId;
    private String symbol;

    // ==================== CURRENT MTIS ====================
    private double mtis;
    private double previousMtis;
    private long lastMtisUpdate;

    // ==================== PER-TF SCORES ====================
    @Builder.Default
    private Map<String, TFState> tfStates = new HashMap<>();

    // ==================== PER-TF VWAPS (for cross-TF comparison) ====================
    @Builder.Default
    private Map<String, Double> tfVwaps = new HashMap<>();

    // ==================== PREVIOUS VALUES (for divergence detection) ====================
    private double previousSpotPrice;
    private Long previousFutureOI;
    private Double previousPcr;

    // ==================== PATTERN TRACKING ====================
    private double vcpScore;
    private int vcpContractionCount;
    private boolean vcpReady;
    
    // ==================== LEVEL TRACKING ====================
    private Double breakoutLevel;
    private boolean breakingOut;
    private long breakoutTimestamp;

    // ==================== LATEST EXTERNAL DATA (cached) ====================
    // These are populated from listening to external topics
    private Double latestIpuScore;
    private String latestIpuUrgency;
    private boolean latestIpuExhaustion;
    
    private boolean latestFudkiiIgnition;
    private int latestFudkiiSimultaneity;
    
    private String latestIndexRegimeLabel;
    private double latestIndexRegimeStrength;
    private boolean securityAligned;

    // ==================== NESTED CLASSES ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TFState {
        private String timeframe;
        private double score;           // Raw score for this TF
        private long lastUpdated;       // Timestamp of last update
        private double vwap;            // VWAP at last update
        private double close;           // Close at last update
        
        // Data quality
        private boolean hasOI;
        private boolean hasOrderbook;
        private boolean hasFuture;
        private boolean hasOptions;
    }

    // ==================== HELPER METHODS ====================

    /**
     * Update TF state safely
     */
    public void updateTFState(String timeframe, double score, double vwap, double close,
                               boolean hasOI, boolean hasOrderbook, boolean hasFuture, boolean hasOptions) {
        if (this.tfStates == null) {
            this.tfStates = new HashMap<>();
        }
        
        TFState state = TFState.builder()
                .timeframe(timeframe)
                .score(score)
                .lastUpdated(System.currentTimeMillis())
                .vwap(vwap)
                .close(close)
                .hasOI(hasOI)
                .hasOrderbook(hasOrderbook)
                .hasFuture(hasFuture)
                .hasOptions(hasOptions)
                .build();
        
        this.tfStates.put(timeframe, state);
        this.tfVwaps.put(timeframe, vwap);
    }

    /**
     * Get TF score with staleness check
     */
    public double getTFScore(String timeframe) {
        if (tfStates == null || !tfStates.containsKey(timeframe)) {
            return 0.0;
        }
        TFState state = tfStates.get(timeframe);
        if (isStale(timeframe, state.getLastUpdated())) {
            return 0.0;  // Don't use stale data
        }
        return state.getScore();
    }

    /**
     * Check if TF data is stale
     */
    public boolean isStale(String timeframe, long lastUpdated) {
        long now = System.currentTimeMillis();
        long tfDurationMs = getTfDurationMs(timeframe);
        long staleness = tfDurationMs * 2;  // 2x TF duration = stale
        return (now - lastUpdated) > staleness;
    }

    /**
     * Get TF duration in milliseconds
     */
    public static long getTfDurationMs(String tf) {
        if (tf == null) return 60_000;
        switch (tf) {
            case "1m": return 60_000;
            case "2m": return 120_000;
            case "3m": return 180_000;
            case "5m": return 300_000;
            case "15m": return 900_000;
            case "30m": return 1_800_000;
            case "1h": return 3_600_000;
            case "2h": return 7_200_000;
            case "4h": return 14_400_000;
            case "1d": return 86_400_000;
            default: return 60_000;
        }
    }

    /**
     * Get TF weight for MTIS calculation
     */
    public static double getTfWeight(String tf) {
        if (tf == null) return 0.05;
        switch (tf) {
            case "1m": return 0.05;
            case "2m": return 0.05;
            case "3m": return 0.05;
            case "5m": return 0.15;
            case "15m": return 0.20;
            case "30m": return 0.15;
            case "1h": return 0.25;
            case "2h": return 0.20;
            case "4h": return 0.15;
            case "1d": return 0.15;
            default: return 0.05;
        }
    }

    /**
     * Calculate weighted TF score sum
     */
    public double calculateWeightedTFScore() {
        if (tfStates == null || tfStates.isEmpty()) {
            return 0.0;
        }
        
        double weightedSum = 0.0;
        double totalWeight = 0.0;
        
        for (Map.Entry<String, TFState> entry : tfStates.entrySet()) {
            String tf = entry.getKey();
            TFState state = entry.getValue();
            
            if (!isStale(tf, state.getLastUpdated())) {
                double weight = getTfWeight(tf);
                weightedSum += state.getScore() * weight;
                totalWeight += weight;
            }
        }
        
        // Normalize if we have meaningful weight (at least 20%)
        if (totalWeight >= 0.2 && totalWeight < 1.0) {
            weightedSum = weightedSum / totalWeight;  // Scale up
        } else if (totalWeight < 0.2) {
            return 0.0;  // Not enough data to be meaningful
        }
        
        return weightedSum;
    }

    /**
     * Track previous values for divergence detection
     */
    public void trackPreviousValues(double spotPrice, Long futureOI, Double pcr) {
        this.previousSpotPrice = spotPrice;
        this.previousFutureOI = futureOI;
        this.previousPcr = pcr;
    }

    /**
     * Check for price-OI divergence
     * FIX: Added magnitude check and multi-candle trend validation
     */
    public boolean hasDivergence(double currentSpotPrice, Long currentFutureOI) {
        if (previousFutureOI == null || currentFutureOI == null || previousSpotPrice <= 0) {
            return false;
        }
        
        // FIX: Calculate percentage changes, not just direction
        double priceChangePercent = Math.abs((currentSpotPrice - previousSpotPrice) / previousSpotPrice * 100);
        double oiChangePercent = Math.abs((double)(currentFutureOI - previousFutureOI) / previousFutureOI * 100);
        
        // FIX: Require minimum magnitude (0.1% for price, 0.5% for OI) to avoid noise
        if (priceChangePercent < 0.1 || oiChangePercent < 0.5) {
            return false;  // Too small to be meaningful
        }
        
        boolean priceUp = currentSpotPrice > previousSpotPrice;
        boolean oiUp = currentFutureOI > previousFutureOI;
        
        // FIX: Divergence requires opposite direction AND significant magnitude
        return priceUp != oiUp && (priceChangePercent > 0.2 || oiChangePercent > 1.0);
    }

    // ==================== SERDE ====================

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public static Serde<FamilyIntelligenceState> serde() {
        return Serdes.serdeFrom(new StateSerializer(), new StateDeserializer());
    }

    public static class StateSerializer implements Serializer<FamilyIntelligenceState> {
        @Override
        public byte[] serialize(String topic, FamilyIntelligenceState data) {
            if (data == null) return null;
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for FamilyIntelligenceState", e);
            }
        }
    }

    public static class StateDeserializer implements Deserializer<FamilyIntelligenceState> {
        @Override
        public FamilyIntelligenceState deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return MAPPER.readValue(bytes, FamilyIntelligenceState.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for FamilyIntelligenceState", e);
            }
        }
    }
}
