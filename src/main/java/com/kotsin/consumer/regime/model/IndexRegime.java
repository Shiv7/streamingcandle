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
 * IndexRegime - Output from Module 1: Index Regime Calculator
 * 
 * Detects market-wide bias, coherence, and expansion readiness
 * for indices like NIFTY50, BANKNIFTY, FINNIFTY
 * 
 * Emits to topic: regime-index-output
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class IndexRegime {

    // Identification
    private String indexName;           // NIFTY50, BANKNIFTY, FINNIFTY, NIFTY_IT, NIFTY_FMCG, NIFTY_METAL
    private String scripCode;           // 999920000 for NIFTY50, etc.
    private long timestamp;
    
    // Per-timeframe scores (aggregated from 1D, 2H, 30m, 5m)
    private TimeframeRegimeData tf1D;
    private TimeframeRegimeData tf2H;
    private TimeframeRegimeData tf30m;
    private TimeframeRegimeData tf5m;
    
    // Aggregated outputs
    private double regimeStrength;       // [0,1] - aggregated across TFs
    private double regimeCoherence;      // [0,1] - how much TFs agree
    private RegimeLabel label;           // STRONG_BULL, WEAK_BULL, NEUTRAL, etc.
    private int flowAgreement;           // +1/-1/0 - net flow direction
    private VolatilityState volatilityState;  // COMPRESSED, NORMAL, EXPANDING
    
    // Multi-TF metrics
    private double multiTfAgreementScore;  // How aligned are all TFs
    private int bullishTfCount;            // How many TFs are bullish
    private int bearishTfCount;            // How many TFs are bearish
    
    // Session context
    private SessionPhase sessionPhase;     // OPENING, MORNING, MIDDAY, AFTERNOON, CLOSING
    private double sessionConfidenceModifier;  // Adjustment factor based on session
    
    /**
     * Per-timeframe regime data
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeframeRegimeData {
        private String timeframe;           // "1D", "2H", "30m", "5m"
        private double vwapControl;         // [0,1] - price vs VWAP relationship
        private double participation;       // [0,1] - volume delta / expected imbalance
        private int flowAgreement;          // +1/-1/0
        private VolatilityState volState;   // ATR-based volatility state
        private double regimeStrength;      // [0,1] - strength for this TF
        private double regimeCoherence;     // [0,1] - internal coherence for this TF
        private RegimeLabel label;          // Label for this TF
        private double close;               // Current close price
        private double atr14;               // ATR14 for this TF
        private double vwap;                // VWAP for this TF
    }
    
    /**
     * Volatility state classification
     */
    public enum VolatilityState {
        COMPRESSED(0.3, "Low volatility, potential breakout coming"),
        NORMAL(0.0, "Typical market volatility"),
        EXPANDING(0.3, "High volatility, trending environment");

        private final double score;
        private final String description;

        VolatilityState(double score, String description) {
            this.score = score;
            this.description = description;
        }

        public double getScore() {
            return score;
        }

        /**
         * Classify volatility state from ATR ratio
         */
        public static VolatilityState fromATRRatio(double currentATR, double avgATR) {
            double ratio = currentATR / (avgATR + 0.0001);
            if (ratio < 0.7) return COMPRESSED;
            if (ratio > 1.3) return EXPANDING;
            return NORMAL;
        }
    }
    
    /**
     * Market session phases (IST)
     */
    public enum SessionPhase {
        OPENING(0.8, "09:15-09:30 - High volatility, reduced confidence"),
        MORNING(0.9, "09:30-11:30 - Good liquidity, moderate confidence"),
        MIDDAY(1.0, "11:30-14:00 - Best signals, full confidence"),
        AFTERNOON(0.95, "14:00-15:00 - Slightly reduced, still good"),
        CLOSING(0.75, "15:00-15:30 - Position squaring, reduced confidence");

        private final double confidenceMultiplier;
        private final String description;

        SessionPhase(double confidenceMultiplier, String description) {
            this.confidenceMultiplier = confidenceMultiplier;
            this.description = description;
        }

        public double getConfidenceMultiplier() {
            return confidenceMultiplier;
        }

        /**
         * Determine session phase from hour and minute (IST)
         */
        public static SessionPhase fromTime(int hour, int minute) {
            int timeInMinutes = hour * 60 + minute;
            if (timeInMinutes < 9 * 60 + 30) return OPENING;
            if (timeInMinutes < 11 * 60 + 30) return MORNING;
            if (timeInMinutes < 14 * 60) return MIDDAY;
            if (timeInMinutes < 15 * 60) return AFTERNOON;
            return CLOSING;
        }
    }
    
    /**
     * Check if regime is tradeable (strong enough conviction)
     */
    public boolean isTradeable() {
        return regimeStrength >= 0.5 && regimeCoherence >= 0.4;
    }
    
    /**
     * Get directional bias as multiplier (-1 to +1)
     */
    public double getDirectionalBias() {
        return label.getValue() * regimeStrength;
    }
    
    // ========== Serialization ==========
    
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    public static Serde<IndexRegime> serde() {
        return Serdes.serdeFrom(new IndexRegimeSerializer(), new IndexRegimeDeserializer());
    }
    
    public static class IndexRegimeSerializer implements Serializer<IndexRegime> {
        @Override
        public byte[] serialize(String topic, IndexRegime data) {
            try {
                return data != null ? MAPPER.writeValueAsBytes(data) : null;
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed", e);
            }
        }
    }
    
    public static class IndexRegimeDeserializer implements Deserializer<IndexRegime> {
        @Override
        public IndexRegime deserialize(String topic, byte[] bytes) {
            try {
                return bytes != null ? MAPPER.readValue(bytes, IndexRegime.class) : null;
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed", e);
            }
        }
    }
}
