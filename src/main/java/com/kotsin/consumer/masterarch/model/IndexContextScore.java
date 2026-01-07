package com.kotsin.consumer.masterarch.model;

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
 * IndexContextScore - PART 1A: Index Regime Module Output
 * 
 * Defines macro structure and volatility environment.
 * 
 * Formula:
 * Index_Context_Score = Index_Trend_Dir × Index_Regime_Strength
 * 
 * Where:
 * - Index_Trend_Dir = sign(EMA_20_30m - EMA_50_30m)
 * - Index_Regime_Strength = 0.40*Trend_Strength + 0.35*Persistence + 0.25*ATR_Pct
 * 
 * Output Topic: regime-index-output
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class IndexContextScore {
    
    // ======================== IDENTIFICATION ========================
    
    private String indexName;           // NIFTY50, BANKNIFTY, NIFTYIT, etc.
    private String scripCode;           // N:D:26000 format
    private long timestamp;
    
    // ======================== TREND COMPONENTS ========================
    
    /**
     * Index_Trend_Dir = sign(EMA_20_30m - EMA_50_30m)
     * Values: +1 (bullish), -1 (bearish), 0 (neutral)
     */
    private int trendDirection;
    
    /**
     * EMA values for reference
     */
    private double ema20_30m;
    private double ema50_30m;
    
    /**
     * Index_Trend_Strength = |EMA_20 - EMA_50| / ATR_14_30m
     * Range: [0.0, 1.0] (clamped)
     */
    private double trendStrength;
    
    /**
     * Index_Persistence = Consecutive bars with same Trend_Dir / 20
     * Range: [0.0, 1.0]
     */
    private double persistence;
    private int consecutiveBars;        // Raw count for debugging
    
    // ======================== VOLATILITY COMPONENTS ========================
    
    /**
     * ATR_Pct = ATR_14_30m / SMA(ATR_14_30m, 50)
     * Values: <1 = low vol, >1 = high vol
     */
    private double atrPct;
    private double atr14_30m;
    private double atrSma50;
    
    // ======================== FLOW COMPONENTS ========================
    
    /**
     * Index_flow_agreement_30m = sign(Volume_ROC_5_30m)
     * Values: +1 (expanding volume), -1 (contracting), 0 (flat)
     */
    private int flowAgreement30m;
    private double volumeRoc5_30m;      // Raw value for debugging
    
    // ======================== COMPOSITE SCORES ========================
    
    /**
     * Index_Regime_Strength = 0.40*Trend_Strength + 0.35*Persistence + 0.25*ATR_Pct
     * Range: [0.0, 1.0]
     */
    private double regimeStrength;
    
    /**
     * FINAL: Index_Context_Score = Index_Trend_Dir × Index_Regime_Strength
     * Range: [-1.0, +1.0]
     */
    private NormalizedScore contextScore;
    
    // ======================== METADATA ========================
    
    private String timeframe;           // "30m" for primary calculations
    private boolean isValid;            // True if sufficient data available
    private String invalidReason;       // Reason if not valid
    
    // ======================== HELPER METHODS ========================
    
    public boolean isBullish() {
        return trendDirection > 0;
    }
    
    public boolean isBearish() {
        return trendDirection < 0;
    }
    
    public boolean isNeutral() {
        return trendDirection == 0;
    }
    
    public boolean isHighVolatility() {
        return atrPct > 1.2;
    }
    
    public boolean isLowVolatility() {
        return atrPct < 0.8;
    }
    
    public boolean hasStrongPersistence() {
        return persistence >= 0.8;
    }
    
    /**
     * Factory method for creating from calculated components
     */
    public static IndexContextScore create(
            String indexName,
            String scripCode,
            long timestamp,
            int trendDirection,
            double trendStrength,
            double persistence,
            double atrPct,
            int flowAgreement,
            double previousScore
    ) {
        // Calculate regime strength
        double regimeStrength = 0.40 * trendStrength + 0.35 * persistence + 0.25 * Math.min(atrPct, 1.0);
        regimeStrength = Math.min(1.0, Math.max(0.0, regimeStrength));
        
        // Calculate context score
        double currentScore = trendDirection * regimeStrength;
        NormalizedScore contextScore = NormalizedScore.directional(currentScore, previousScore, timestamp);
        
        return IndexContextScore.builder()
                .indexName(indexName)
                .scripCode(scripCode)
                .timestamp(timestamp)
                .trendDirection(trendDirection)
                .trendStrength(trendStrength)
                .persistence(persistence)
                .atrPct(atrPct)
                .flowAgreement30m(flowAgreement)
                .regimeStrength(regimeStrength)
                .contextScore(contextScore)
                .timeframe("30m")
                .isValid(true)
                .build();
    }
    
    /**
     * Create an invalid/empty result
     */
    public static IndexContextScore invalid(String indexName, String scripCode, long timestamp, String reason) {
        return IndexContextScore.builder()
                .indexName(indexName)
                .scripCode(scripCode)
                .timestamp(timestamp)
                .contextScore(NormalizedScore.neutral(timestamp))
                .isValid(false)
                .invalidReason(reason)
                .build();
    }
    
    // ======================== SERIALIZATION ========================
    
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    public static Serde<IndexContextScore> serde() {
        return Serdes.serdeFrom(new IndexContextScoreSerializer(), new IndexContextScoreDeserializer());
    }
    
    public static class IndexContextScoreSerializer implements Serializer<IndexContextScore> {
        @Override
        public byte[] serialize(String topic, IndexContextScore data) {
            try {
                return data != null ? MAPPER.writeValueAsBytes(data) : null;
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed", e);
            }
        }
    }
    
    public static class IndexContextScoreDeserializer implements Deserializer<IndexContextScore> {
        @Override
        public IndexContextScore deserialize(String topic, byte[] bytes) {
            try {
                return bytes != null ? MAPPER.readValue(bytes, IndexContextScore.class) : null;
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed", e);
            }
        }
    }
}
