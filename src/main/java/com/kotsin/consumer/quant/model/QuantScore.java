package com.kotsin.consumer.quant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
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
 * QuantScore - Institutional-grade composite score for trading decisions.
 *
 * Synthesizes 8 major data categories into a single 0-100 score:
 * 1. Greeks Exposure (15 pts) - Delta bias, gamma squeeze, vega structure
 * 2. IV Surface (12 pts) - Rank, skew, term structure, crush risk
 * 3. Microstructure (18 pts) - OFI, VPIN, depth, Kyle Lambda
 * 4. Options Flow (15 pts) - PCR, OI evolution, futures buildup
 * 5. Price Action (12 pts) - Sequence patterns, Wyckoff phase
 * 6. Volume Profile (8 pts) - POC migration, value area shift
 * 7. Cross-Instrument (10 pts) - Spot-future premium, F&O alignment
 * 8. Signal Confluence (10 pts) - Multi-category agreement
 *
 * QUANT DESK GRADE: This score is designed for institutional trading decisions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuantScore {

    // ========== IDENTITY ==========
    private String familyId;
    private String symbol;
    private String scripCode;
    private long timestamp;
    private String timeframe;
    private String humanReadableTime;

    // ========== FINAL SCORE ==========
    /**
     * Final composite score (0-100)
     * Calculated as: BaseScore × RegimeModifier × ConfidenceModifier
     */
    private double quantScore;

    /**
     * Score label for quick interpretation
     */
    private QuantLabel quantLabel;

    /**
     * Overall confidence in the score (0-1)
     * Based on data quality, completeness, and cross-validation
     */
    private double confidence;

    public enum QuantLabel {
        STRONG_BUY(80, 100),    // >= 80
        BUY(65, 80),           // 65-80
        NEUTRAL(35, 65),       // 35-65
        SELL(20, 35),          // 20-35
        STRONG_SELL(0, 20);    // < 20

        private final int minScore;
        private final int maxScore;

        QuantLabel(int minScore, int maxScore) {
            this.minScore = minScore;
            this.maxScore = maxScore;
        }

        public static QuantLabel fromScore(double score) {
            if (score >= 80) return STRONG_BUY;
            if (score >= 65) return BUY;
            if (score >= 35) return NEUTRAL;
            if (score >= 20) return SELL;
            return STRONG_SELL;
        }
    }

    // ========== DIRECTION ==========
    /**
     * Directional bias
     */
    private Direction direction;

    /**
     * Directional strength (-1 to +1)
     * Positive = bullish, Negative = bearish
     */
    private double directionalStrength;

    public enum Direction {
        BULLISH,
        BEARISH,
        NEUTRAL
    }

    // ========== SCORE BREAKDOWN ==========
    /**
     * Detailed breakdown of all 8 score categories
     */
    private QuantScoreBreakdown breakdown;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuantScoreBreakdown {
        // Category scores
        private double greeksScore;           // 0-15
        private double ivSurfaceScore;        // 0-12
        private double microstructureScore;   // 0-18
        private double optionsFlowScore;      // 0-15
        private double priceActionScore;      // 0-12
        private double volumeProfileScore;    // 0-8
        private double crossInstrumentScore;  // 0-10
        private double confluenceScore;       // 0-10

        // Raw total before modifiers
        private double rawScore;              // 0-100

        // Modifiers
        private double regimeModifier;        // 0.7-1.3
        private double confidenceModifier;    // 0.8-1.0

        // Category percentages (normalized to max)
        private double greeksPct;             // 0-100%
        private double ivSurfacePct;
        private double microstructurePct;
        private double optionsFlowPct;
        private double priceActionPct;
        private double volumeProfilePct;
        private double crossInstrumentPct;
        private double confluencePct;

        public double calculateRawScore() {
            return greeksScore + ivSurfaceScore + microstructureScore +
                   optionsFlowScore + priceActionScore + volumeProfileScore +
                   crossInstrumentScore + confluenceScore;
        }

        public double calculateFinalScore() {
            double raw = calculateRawScore();
            double regime = regimeModifier > 0 ? regimeModifier : 1.0;
            double conf = confidenceModifier > 0 ? confidenceModifier : 1.0;
            return Math.min(100, Math.max(0, raw * regime * conf));
        }
    }

    // ========== GREEKS SUMMARY ==========
    /**
     * Summary of Greeks exposure for quick display
     */
    private GreeksSummary greeksSummary;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GreeksSummary {
        private double totalDelta;
        private double totalGamma;
        private double totalVega;
        private double totalTheta;

        private boolean gammaSqueezeRisk;
        private Double gammaSqueezeDistance;
        private double maxGammaStrike;

        private String deltaBias;        // LONG, SHORT, NEUTRAL
        private String vegaStructure;    // FRONT_HEAVY, BACK_HEAVY, BALANCED

        private double riskScore;        // 0-100
    }

    // ========== IV SUMMARY ==========
    /**
     * Summary of IV surface for quick display
     */
    private IVSummary ivSummary;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IVSummary {
        private double atmIV;
        private double ivRank;            // 0-100
        private String ivSignal;          // BUY_VOL, SELL_VOL, NEUTRAL, CRUSH_WARNING
        private boolean ivCrushRisk;
        private double ivVelocity;

        private String smileShape;        // NORMAL, STEEP_PUT, STEEP_CALL, FLAT, SMIRK
        private String termStructure;     // CONTANGO, BACKWARDATION, FLAT

        private double skew25Delta;
        private Double nearTermIV;
        private Double farTermIV;
    }

    // ========== MICROSTRUCTURE SUMMARY ==========
    /**
     * Summary of microstructure data
     */
    private MicrostructureSummary microstructureSummary;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MicrostructureSummary {
        private double avgOFI;
        private double avgVPIN;
        private double avgDepthImbalance;
        private double avgKyleLambda;
        private double avgSpread;         // Average bid-ask spread

        private double aggressiveBuyRatio;
        private double aggressiveSellRatio;

        private String flowDirection;     // BUYING, SELLING, BALANCED
        private double flowStrength;      // 0-1
    }

    // ========== OPTIONS FLOW SUMMARY ==========
    /**
     * Summary of options flow
     */
    private OptionsFlowSummary optionsFlowSummary;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionsFlowSummary {
        private double pcr;
        private double pcrChange;
        private String pcrSignal;         // BULLISH, BEARISH, NEUTRAL, EXTREME_FEAR, EXTREME_GREED

        private String oiBuildupType;     // LONG_BUILDUP, SHORT_BUILDUP, LONG_UNWINDING, SHORT_COVERING
        private double oiMomentum;

        private String futuresBuildup;
        private double spotFuturePremium;
    }

    // ========== PRICE ACTION SUMMARY ==========
    /**
     * Summary of price action patterns
     */
    private PriceActionSummary priceActionSummary;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriceActionSummary {
        private String candleSequencePattern;  // e.g., "↑↑↑↓↓"
        private String sequenceType;           // TREND, REVERSAL, CHOP, V_PATTERN
        private int reversalIndex;
        private double momentumSlope;

        private String wyckoffPhase;           // ACCUMULATION, MARKUP, DISTRIBUTION, MARKDOWN
        private double wyckoffStrength;

        private boolean pcrDivergence;
        private boolean oiDivergence;
    }

    // ========== VOLUME PROFILE SUMMARY ==========
    /**
     * Summary of volume profile
     */
    private VolumeProfileSummary volumeProfileSummary;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VolumeProfileSummary {
        private double poc;
        private double vah;
        private double val;
        private Double pocMigration;
        private String pocTrend;          // RISING, FALLING, STABLE

        private boolean valueAreaExpanding;
        private boolean valueAreaContracting;
        private String valueAreaShift;    // UPWARD, DOWNWARD, STABLE
    }

    // ========== WARNINGS ==========
    /**
     * List of warnings for risk awareness
     */
    @Builder.Default
    private List<Warning> warnings = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Warning {
        private WarningType type;
        private WarningSeverity severity;
        private String message;
        private String recommendation;

        public enum WarningType {
            GAMMA_SQUEEZE_IMMINENT,
            IV_CRUSH_RISK,
            LOW_LIQUIDITY,
            EXTREME_SKEW,
            COUNTER_TREND_GREEKS,
            DATA_QUALITY,
            POSITION_CONCENTRATION,
            VOLATILITY_SPIKE
        }

        public enum WarningSeverity {
            CRITICAL,   // Do not trade
            HIGH,       // Trade with caution
            MEDIUM,     // Be aware
            LOW         // Informational
        }
    }

    // ========== ACTIONABILITY ==========
    /**
     * Whether this score is actionable for trading
     */
    private boolean actionable;

    /**
     * Reason if not actionable
     */
    private String actionableReason;

    /**
     * Minimum score threshold for this to be considered actionable
     */
    private double minActionableScore;

    // ========== DATA QUALITY ==========
    /**
     * Data completeness indicator
     */
    private DataQuality dataQuality;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DataQuality {
        private boolean hasGreeks;
        private boolean hasIVSurface;
        private boolean hasMicrostructure;
        private boolean hasOptionsFlow;
        private boolean hasPriceAction;
        private boolean hasVolumeProfile;
        private boolean hasCrossInstrument;

        private double completenessScore;  // 0-1
        private String qualityLevel;       // FULL, PARTIAL, MINIMAL
    }

    // ========== CONVENIENCE METHODS ==========

    public boolean isStrongBuy() {
        return quantLabel == QuantLabel.STRONG_BUY;
    }

    public boolean isBuy() {
        return quantLabel == QuantLabel.BUY || quantLabel == QuantLabel.STRONG_BUY;
    }

    public boolean isSell() {
        return quantLabel == QuantLabel.SELL || quantLabel == QuantLabel.STRONG_SELL;
    }

    public boolean isNeutral() {
        return quantLabel == QuantLabel.NEUTRAL;
    }

    public boolean isBullish() {
        return direction == Direction.BULLISH;
    }

    public boolean isBearish() {
        return direction == Direction.BEARISH;
    }

    public boolean hasCriticalWarnings() {
        if (warnings == null) return false;
        return warnings.stream()
            .anyMatch(w -> w.getSeverity() == Warning.WarningSeverity.CRITICAL);
    }

    public boolean hasHighWarnings() {
        if (warnings == null) return false;
        return warnings.stream()
            .anyMatch(w -> w.getSeverity() == Warning.WarningSeverity.HIGH ||
                          w.getSeverity() == Warning.WarningSeverity.CRITICAL);
    }

    public int getWarningCount() {
        return warnings != null ? warnings.size() : 0;
    }

    /**
     * Get the strongest category (highest percentage score)
     */
    public String getStrongestCategory() {
        if (breakdown == null) return "UNKNOWN";

        double max = 0;
        String category = "UNKNOWN";

        if (breakdown.getGreeksPct() > max) { max = breakdown.getGreeksPct(); category = "GREEKS"; }
        if (breakdown.getIvSurfacePct() > max) { max = breakdown.getIvSurfacePct(); category = "IV_SURFACE"; }
        if (breakdown.getMicrostructurePct() > max) { max = breakdown.getMicrostructurePct(); category = "MICROSTRUCTURE"; }
        if (breakdown.getOptionsFlowPct() > max) { max = breakdown.getOptionsFlowPct(); category = "OPTIONS_FLOW"; }
        if (breakdown.getPriceActionPct() > max) { max = breakdown.getPriceActionPct(); category = "PRICE_ACTION"; }
        if (breakdown.getVolumeProfilePct() > max) { max = breakdown.getVolumeProfilePct(); category = "VOLUME_PROFILE"; }
        if (breakdown.getCrossInstrumentPct() > max) { max = breakdown.getCrossInstrumentPct(); category = "CROSS_INSTRUMENT"; }
        if (breakdown.getConfluencePct() > max) { category = "CONFLUENCE"; }

        return category;
    }

    /**
     * Count categories above 70% of their max
     */
    public int countStrongCategories() {
        if (breakdown == null) return 0;

        int count = 0;
        if (breakdown.getGreeksPct() >= 70) count++;
        if (breakdown.getIvSurfacePct() >= 70) count++;
        if (breakdown.getMicrostructurePct() >= 70) count++;
        if (breakdown.getOptionsFlowPct() >= 70) count++;
        if (breakdown.getPriceActionPct() >= 70) count++;
        if (breakdown.getVolumeProfilePct() >= 70) count++;
        if (breakdown.getCrossInstrumentPct() >= 70) count++;
        if (breakdown.getConfluencePct() >= 70) count++;

        return count;
    }

    /**
     * Factory method for empty score
     */
    public static QuantScore empty(String familyId, String symbol) {
        return QuantScore.builder()
            .familyId(familyId)
            .symbol(symbol)
            .timestamp(System.currentTimeMillis())
            .quantScore(50)
            .quantLabel(QuantLabel.NEUTRAL)
            .confidence(0)
            .direction(Direction.NEUTRAL)
            .directionalStrength(0)
            .actionable(false)
            .actionableReason("No data available")
            .warnings(new ArrayList<>())
            .build();
    }

    // ========== SERDE ==========

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public static Serde<QuantScore> serde() {
        return Serdes.serdeFrom(new QuantScoreSerializer(), new QuantScoreDeserializer());
    }

    public static class QuantScoreSerializer implements Serializer<QuantScore> {
        @Override
        public byte[] serialize(String topic, QuantScore data) {
            if (data == null) return null;
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for QuantScore", e);
            }
        }
    }

    public static class QuantScoreDeserializer implements Deserializer<QuantScore> {
        @Override
        public QuantScore deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return MAPPER.readValue(bytes, QuantScore.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for QuantScore", e);
            }
        }
    }
}
