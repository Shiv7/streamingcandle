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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FamilyScore - The SINGLE output for Multi-Timeframe Intelligence Score (MTIS).
 * 
 * Emitted to: family-score (single topic)
 * Key: familyId
 * 
 * Contains:
 * - Final MTIS score (-100 to +100)
 * - Score breakdown by category
 * - Per-TF scores for transparency
 * - Justification and contributors
 * - Warnings (divergence, exhaustion, expiry, etc.)
 * 
 * Updated by ANY timeframe candle - maintains cached scores from all TFs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FamilyScore {

    // ==================== IDENTITY ====================
    private String familyId;           // "25"
    private String symbol;             // "ADANIENT"
    private long timestamp;
    private long windowStartMillis;
    private long windowEndMillis;
    private String humanReadableTime;  // "2025-12-28 15:30:00"
    private String triggerTimeframe;   // Which TF triggered this update ("1m", "5m", etc.)

    // ==================== FINAL SCORE ====================
    private double mtis;               // -100 to +100
    private String mtisLabel;          // "STRONG_BULLISH", "BULLISH", "NEUTRAL", etc.
    private String mtisTrend;          // "RISING", "FALLING", "STABLE"
    private double previousMtis;       // For trend calculation
    private double mtisChange;         // Current - Previous

    // ==================== MODIFIERS APPLIED ====================
    private double sessionModifier;    // 0.5 to 1.1
    private double cprModifier;        // 0.7 to 1.3
    private double expiryModifier;     // 0.7 to 1.0
    private double rawMtis;            // Before modifiers

    // ==================== WARNINGS ====================
    @Builder.Default
    private List<Warning> warnings = new ArrayList<>();

    // ==================== SCORE BREAKDOWN ====================
    private ScoreBreakdown breakdown;

    // ==================== JUSTIFICATION ====================
    @Builder.Default
    private List<ScoreContributor> contributors = new ArrayList<>();
    private String summary;            // Human-readable one-liner

    // ==================== KEY DATA SNAPSHOT ====================
    private double spotPrice;
    private double open;             // Candle open price
    private double high;             // Candle high price
    private double low;              // Candle low price
    private long volume;             // Candle volume
    private Double futurePrice;
    private Double pcr;
    private String oiSignal;
    private String futuresBuildup;
    private String indexRegimeLabel;
    private Double vcpScore;
    private Double ipuFinalScore;
    private boolean fudkiiIgnition;
    private String cprWidth;           // "NARROW", "NORMAL", "WIDE"
    private boolean expiryDay;
    private String sessionPhase;       // "OPENING", "MORNING", "MIDDAY", etc.
    private boolean isCommodity;       // True for MCX commodities (future is primary)

    // ==================== FLAGS ====================
    private boolean hasDivergence;
    private boolean hasExhaustion;
    private boolean actionable;        // MTIS > 60 or < -60, no critical warnings

    // ==================== NESTED CLASSES ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Warning {
        private String type;           // "DIVERGENCE", "EXHAUSTION", "EXPIRY_DAY", etc.
        private String severity;       // "HIGH", "MEDIUM", "LOW"
        private String message;        // Human-readable explanation
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScoreBreakdown {
        // Category scores
        private double priceScore;          // ±12
        private double foAlignmentScore;    // ±20 (FuturesOptionsAlignment)
        private double ipuScore;            // ±15 (Institutional Urgency)
        private double fudkiiBonus;         // 0 to +20 (Ignition)
        private double microstructureScore; // ±8
        private double orderbookScore;      // ±5
        private double mtfRegimeScore;      // ±15
        private double patternBonus;        // 0 to +20
        private double levelRetestBonus;    // 0 to +20
        private double relativeStrengthBonus; // ±5
        private double mtisMomentumBonus;   // ±5

        // Per-TF breakdown (cached from state)
        @Builder.Default
        private Map<String, TFScoreDetail> tfScores = new HashMap<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TFScoreDetail {
        private String timeframe;
        private double score;          // Raw score for this TF
        private double weight;         // Weight applied
        private double weightedScore;  // score * weight
        private long lastUpdated;      // When this TF was last updated
        private boolean stale;         // >2x TF duration = stale
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScoreContributor {
        private String category;       // "IPU_EXHAUSTION", "FUDKII_IGNITION", etc.
        private double points;         // +15, -8, etc.
        private String reason;         // "IPU exhaustion detected - reversal risk"
        private String dataSource;     // "ipuOutput.exhaustionDetected"
        private String rawValue;       // "true"
    }

    // ==================== HELPER METHODS ====================

    /**
     * Get MTIS label from score value
     *
     * FIX: Tightened thresholds for better signal granularity
     * - Neutral zone reduced from ±20 to ±10 (was 40 points, now 20 points)
     * - Lower thresholds allow catching more alpha opportunities
     */
    public static String getLabelFromScore(double mtis) {
        if (mtis >= 70) return "EXTREME_BULLISH";   // 70+ (was 80+)
        if (mtis >= 50) return "STRONG_BULLISH";    // 50-69 (was 60-79)
        if (mtis >= 30) return "BULLISH";           // 30-49 (was 40-59)
        if (mtis >= 10) return "MILD_BULLISH";      // 10-29 (was 20-39)
        if (mtis >= -10) return "NEUTRAL";          // -10 to +10 (was -20 to +20)
        if (mtis >= -30) return "MILD_BEARISH";     // -30 to -11 (was -40 to -21)
        if (mtis >= -50) return "BEARISH";          // -50 to -31 (was -60 to -41)
        if (mtis >= -70) return "STRONG_BEARISH";   // -70 to -51 (was -80 to -61)
        return "EXTREME_BEARISH";                   // < -70 (was < -80)
    }

    /**
     * Get trend from score change
     */
    public static String getTrendFromChange(double change) {
        if (change > 5) return "RISING";
        if (change < -5) return "FALLING";
        return "STABLE";
    }

    /**
     * Check if score is actionable
     *
     * FIX: Lowered threshold from 60 to 25 for more signals
     * FIX: Allow HIGH warnings - exhaustion/divergence can BE the trading signal
     */
    public boolean isActionable() {
        // Only reject if MULTIPLE high severity warnings (not just one)
        if (warnings != null) {
            long highWarningCount = warnings.stream()
                    .filter(w -> "HIGH".equals(w.getSeverity()))
                    .count();
            // Reject only if 2+ HIGH warnings (too many red flags)
            if (highWarningCount >= 2) {
                return false;
            }
        }
        // Actionable if moderate signal strength (MTIS >= 25 or <= -25)
        // Lowered from 60 to capture more alpha
        return Math.abs(mtis) >= 25;
    }

    /**
     * Add a warning safely
     */
    public void addWarning(String type, String severity, String message) {
        if (this.warnings == null) {
            this.warnings = new ArrayList<>();
        }
        this.warnings.add(Warning.builder()
                .type(type)
                .severity(severity)
                .message(message)
                .build());
    }

    /**
     * Add a contributor safely
     */
    public void addContributor(String category, double points, String reason, 
                               String dataSource, String rawValue) {
        if (this.contributors == null) {
            this.contributors = new ArrayList<>();
        }
        this.contributors.add(ScoreContributor.builder()
                .category(category)
                .points(points)
                .reason(reason)
                .dataSource(dataSource)
                .rawValue(rawValue)
                .build());
    }

    // ==================== SERDE ====================

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public static Serde<FamilyScore> serde() {
        return Serdes.serdeFrom(new FamilyScoreSerializer(), new FamilyScoreDeserializer());
    }

    public static class FamilyScoreSerializer implements Serializer<FamilyScore> {
        @Override
        public byte[] serialize(String topic, FamilyScore data) {
            if (data == null) return null;
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for FamilyScore", e);
            }
        }
    }

    public static class FamilyScoreDeserializer implements Deserializer<FamilyScore> {
        @Override
        public FamilyScore deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return MAPPER.readValue(bytes, FamilyScore.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for FamilyScore", e);
            }
        }
    }
}
