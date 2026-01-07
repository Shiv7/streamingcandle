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
 * SecurityContextScore - PART 1B: Security Regime Module Output
 * 
 * Determines if individual security is aligned with or diverging from index regime.
 * 
 * Formula:
 * Security_Regime_Strength = Raw_Security_Strength × Index_Regime_Strength × Flow_Alignment_Multiplier
 * 
 * Where Raw_Security_Strength =
 *   0.30*Trend_Direction
 * + 0.20*Trend_Persistence*Trend_Direction
 * + 0.20*Relative_Strength_vs_Index
 * + 0.15*ATR_Expansion*Trend_Direction
 * + 0.10*Structure_Quality
 * + 0.05*Breakout_Quality*Trend_Direction
 * 
 * Output Topic: regime-security-output
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SecurityContextScore {
    
    // ======================== IDENTIFICATION ========================
    
    private String scripCode;           // N:D:49812 format
    private String companyName;
    private long timestamp;
    
    // ======================== TREND COMPONENTS ========================
    
    /**
     * Trend_Direction = sign(EMA_20_30m - EMA_50_30m)
     * Values: +1, -1, 0
     */
    private int trendDirection;
    private double ema20_30m;
    private double ema50_30m;
    
    /**
     * Trend_Persistence = Consecutive_Bars_Same_Trend / Lookback_Bars (default 20)
     * Range: [0.0, 1.0]
     */
    private double trendPersistence;
    private int consecutiveBars;
    
    // ======================== RELATIVE STRENGTH ========================
    
    /**
     * Relative_Strength_vs_Index = (Security_ROC_20 − Index_ROC_20) / max(|Index_ROC_20|, ε)
     * Range: [-1.0, +1.0] (clamped)
     * Positive = outperforming index, Negative = lagging
     */
    private double relativeStrength;
    private double securityRoc20;
    private double indexRoc20;
    
    // ======================== VOLATILITY ========================
    
    /**
     * ATR_Expansion = (ATR_14 - SMA(ATR_14,20)) / SMA(ATR_14,20)
     * Positive = expanding volatility
     */
    private double atrExpansion;
    private double atr14;
    private double atrSma20;
    
    // ======================== STRUCTURE QUALITY ========================
    
    /**
     * Structure_Quality = 0.5 * Structure_30m + 0.5 * Structure_1D
     * Where Structure_tf:
     *   +1 if HigherHigh AND HigherLow
     *   -1 if LowerHigh AND LowerLow
     *   0 otherwise
     * Range: [-1.0, +1.0]
     */
    private double structureQuality;
    private int structure30m;           // +1, -1, or 0
    private int structure1D;            // +1, -1, or 0
    private boolean structureFlipRecent;  // If flipped within last 3 bars, reduce by 0.7
    
    // ======================== BREAKOUT QUALITY ========================
    
    /**
     * Breakout_Quality_bull = max(close - Resistance, 0) / ATR_14_30m
     * Breakout_Quality_bear = max(Support - close, 0) / ATR_14_30m
     * Range: [0.0, 1.0] (clamped, can go to 1.2 with volume override then clamped)
     */
    private double breakoutQuality;
    private boolean breakoutWithVolume;  // If Volume_Certainty >= 0.90, allow up to 1.2
    
    // ======================== FLOW ALIGNMENT ========================
    
    /**
     * Flow_Alignment_Multiplier:
     * - Aligned with index flow → ×1.10
     * - Misaligned → ×0.75
     */
    private double flowAlignmentMultiplier;
    private int securityFlowSign;       // +1, -1, 0
    private int indexFlowAgreement;     // From IndexContextScore
    private boolean isFlowAligned;
    
    // ======================== COMPOSITE SCORES ========================
    
    /**
     * Raw_Security_Strength before index and flow adjustments
     * Range: [-1.0, +1.0]
     */
    private double rawSecurityStrength;
    
    /**
     * Index_Regime_Strength from parent index
     * Range: [0.0, 1.0]
     */
    private double indexRegimeStrength;
    
    /**
     * FINAL: Security_Regime_Strength
     * = Raw_Security_Strength × Index_Regime_Strength × Flow_Alignment_Multiplier
     * Range: [-1.0, +1.0]
     */
    private NormalizedScore contextScore;
    
    // ======================== METADATA ========================
    
    private String parentIndexName;     // Which index this security belongs to
    private boolean isValid;
    private String invalidReason;
    
    // ======================== HELPER METHODS ========================
    
    public boolean isBullish() {
        return trendDirection > 0;
    }
    
    public boolean isBearish() {
        return trendDirection < 0;
    }
    
    public boolean isOutperforming() {
        return relativeStrength > 0.1;
    }
    
    public boolean isUnderperforming() {
        return relativeStrength < -0.1;
    }
    
    public boolean hasCleanStructure() {
        return Math.abs(structureQuality) >= 0.7 && !structureFlipRecent;
    }
    
    public boolean hasBreakout() {
        return breakoutQuality >= 0.5;
    }
    
    /**
     * Factory method
     */
    public static SecurityContextScore create(
            String scripCode,
            String companyName,
            long timestamp,
            int trendDirection,
            double trendPersistence,
            double relativeStrength,
            double atrExpansion,
            double structureQuality,
            double breakoutQuality,
            double indexRegimeStrength,
            boolean isFlowAligned,
            double previousScore
    ) {
        // Calculate raw security strength
        double raw = 0.30 * trendDirection
                   + 0.20 * trendPersistence * trendDirection
                   + 0.20 * clamp(relativeStrength, -1.0, 1.0)
                   + 0.15 * clamp(atrExpansion, -1.0, 1.0) * trendDirection
                   + 0.10 * structureQuality
                   + 0.05 * breakoutQuality * trendDirection;
        
        raw = clamp(raw, -1.0, 1.0);
        
        // Flow alignment multiplier
        double flowMultiplier = isFlowAligned ? 1.10 : 0.75;
        
        // Final score
        double currentScore = raw * indexRegimeStrength * flowMultiplier;
        currentScore = clamp(currentScore, -1.0, 1.0);
        
        return SecurityContextScore.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(timestamp)
                .trendDirection(trendDirection)
                .trendPersistence(trendPersistence)
                .relativeStrength(relativeStrength)
                .atrExpansion(atrExpansion)
                .structureQuality(structureQuality)
                .breakoutQuality(breakoutQuality)
                .rawSecurityStrength(raw)
                .indexRegimeStrength(indexRegimeStrength)
                .flowAlignmentMultiplier(flowMultiplier)
                .isFlowAligned(isFlowAligned)
                .contextScore(NormalizedScore.directional(currentScore, previousScore, timestamp))
                .isValid(true)
                .build();
    }
    
    public static SecurityContextScore invalid(String scripCode, String companyName, long timestamp, String reason) {
        return SecurityContextScore.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(timestamp)
                .contextScore(NormalizedScore.neutral(timestamp))
                .isValid(false)
                .invalidReason(reason)
                .build();
    }
    
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
    
    // ======================== SERIALIZATION ========================
    
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    public static Serde<SecurityContextScore> serde() {
        return Serdes.serdeFrom(new SecurityContextScoreSerializer(), new SecurityContextScoreDeserializer());
    }
    
    public static class SecurityContextScoreSerializer implements Serializer<SecurityContextScore> {
        @Override
        public byte[] serialize(String topic, SecurityContextScore data) {
            try {
                return data != null ? MAPPER.writeValueAsBytes(data) : null;
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed", e);
            }
        }
    }
    
    public static class SecurityContextScoreDeserializer implements Deserializer<SecurityContextScore> {
        @Override
        public SecurityContextScore deserialize(String topic, byte[] bytes) {
            try {
                return bytes != null ? MAPPER.readValue(bytes, SecurityContextScore.class) : null;
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed", e);
            }
        }
    }
}
