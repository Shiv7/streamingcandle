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

/**
 * WatchlistEntry - Entry in the ranked watchlist
 * 
 * Contains all information needed to trade:
 * - Ranking position
 * - Final magnitude and components
 * - Trade parameters (entry, stop, targets)
 * - Quality flags
 * 
 * Emits to topic: watchlist-ranked
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WatchlistEntry {

    // Identification
    private String scripCode;
    private String companyName;
    private String sector;
    private long timestamp;
    
    // Ranking
    private int rank;                      // Position in watchlist (1 = best)
    private double finalMagnitude;         // THE score
    
    // Direction
    private String direction;              // BULLISH, BEARISH
    private double directionConfidence;
    private String signalType;
    
    // Trade Parameters
    private double entryPrice;
    private double stopLoss;
    private double target1;
    private double target2;
    private double riskRewardRatio;
    
    // Position Sizing
    private double suggestedPositionPct;   // % of capital
    private double positionMultiplier;     // Adjustments from guardrails
    
    // Quality Indicators
    private QualityLevel qualityLevel;
    private boolean indexAligned;
    private boolean ignitionDetected;
    private boolean volumeConfirmed;
    private boolean exhaustionWarning;
    
    // Module Scores (for transparency)
    private double ipuScore;
    private double vcpScore;
    private double regimeStrength;
    private double fudkiiStrength;
    private double aclMultiplier;
    private double somPenalty;
    private double vtdPenalty;
    
    // Guardrail Status
    private String guardrailAction;        // ALLOW, REDUCE, SMALL_ONLY, BLOCK
    private String guardrailReason;
    
    /**
     * Quality level classification
     */
    public enum QualityLevel {
        EXCELLENT("Top tier signal, high conviction", 5),
        GOOD("Strong signal, normal conviction", 4),
        MODERATE("Decent signal, reduced size", 3),
        WEAK("Low conviction, small size only", 2),
        POOR("Very low conviction, consider skipping", 1);

        private final String description;
        private final int stars;

        QualityLevel(String description, int stars) {
            this.description = description;
            this.stars = stars;
        }

        public int getStars() {
            return stars;
        }

        public static QualityLevel fromMagnitude(double magnitude, boolean indexAligned, boolean hasWarning) {
            if (hasWarning) {
                return magnitude > 0.6 ? MODERATE : POOR;
            }
            if (magnitude >= 0.8 && indexAligned) return EXCELLENT;
            if (magnitude >= 0.7) return GOOD;
            if (magnitude >= 0.5) return MODERATE;
            if (magnitude >= 0.3) return WEAK;
            return POOR;
        }
    }
    
    /**
     * Check if ready to trade
     */
    public boolean isReadyToTrade() {
        return qualityLevel != QualityLevel.POOR 
            && !"BLOCK".equals(guardrailAction)
            && !exhaustionWarning;
    }
    
    /**
     * Get risk amount for position
     */
    public double getRiskAmount(double capital) {
        double riskPct = suggestedPositionPct * positionMultiplier / 100;
        return capital * riskPct;
    }
    
    /**
     * Get number of shares based on capital and risk
     */
    public int getShareCount(double capital, double maxRiskPct) {
        if (entryPrice <= 0 || stopLoss <= 0) return 0;
        
        double riskPerShare = Math.abs(entryPrice - stopLoss);
        if (riskPerShare <= 0) return 0;
        
        double maxRisk = capital * maxRiskPct / 100;
        return (int) (maxRisk / riskPerShare);
    }
    
    // ========== Serialization ==========
    
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    public static Serde<WatchlistEntry> serde() {
        return Serdes.serdeFrom(new WatchlistEntrySerializer(), new WatchlistEntryDeserializer());
    }
    
    public static class WatchlistEntrySerializer implements Serializer<WatchlistEntry> {
        @Override
        public byte[] serialize(String topic, WatchlistEntry data) {
            try {
                return data != null ? MAPPER.writeValueAsBytes(data) : null;
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed", e);
            }
        }
    }
    
    public static class WatchlistEntryDeserializer implements Deserializer<WatchlistEntry> {
        @Override
        public WatchlistEntry deserialize(String topic, byte[] bytes) {
            try {
                return bytes != null ? MAPPER.readValue(bytes, WatchlistEntry.class) : null;
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed", e);
            }
        }
    }
}
