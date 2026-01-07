package com.kotsin.consumer.curated.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.capital.model.FinalMagnitude;
import com.kotsin.consumer.model.IPUOutput;
import com.kotsin.consumer.model.MTVCPOutput;
import com.kotsin.consumer.regime.model.ACLOutput;
import com.kotsin.consumer.regime.model.IndexRegime;
import com.kotsin.consumer.regime.model.SecurityRegime;
import com.kotsin.consumer.signal.model.CSSOutput;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;

/**
 * CuratedSignal - Final output of the Curated Signal System
 *
 * This is a HIGH-QUALITY signal that has passed ALL gates:
 * - Multi-TF structure confirmation (1m, 2m, 3m)
 * - Consolidation pattern detected (Lower Highs + Higher Lows)
 * - Breakout confirmed with volume + microstructure
 * - Index regime favorable
 * - Security regime aligned
 * - ACL allows entry
 * - Retest entry confirmed
 * - All 16 modules integrated
 *
 * Emits to topic: trading-signals-curated
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CuratedSignal {

    // ========== Metadata ==========
    private String scripCode;
    private String companyName;
    private long timestamp;
    private String signalId;  // For linking to SignalHistory and TradeOutcome

    // ========== Structure & Breakout ==========
    private MultiTFBreakout breakout;           // Multi-TF breakout data
    private ConsolidationPattern pattern;       // Consolidation that led to breakout

    // ========== Module Outputs (Transparency) ==========

    // Regime Modules
    private IndexRegime indexRegime;            // Module 1
    private SecurityRegime securityRegime;      // Module 2
    private ACLOutput acl;                      // Module 3

    // Structure Modules
    private MTVCPOutput vcp;                    // Module 4
    private CSSOutput css;                      // Module 5

    // Flow Module
    private IPUOutput ipu;                      // Module 6

    // Ranking Module (reference)
    private FinalMagnitude finalMagnitude;      // Module 16

    // ========== F&O & Levels (ENHANCED) 🆕 ==========
    private FuturesOptionsAlignment foAlignment;  // F&O alignment score & bias
    private MultiTimeframeLevels levels;          // Multi-TF Fibonacci & Pivot levels

    // ========== Entry Data ==========
    private RetestEntry entry;                  // Precise entry on retest

    // ========== Scoring ==========
    private double curatedScore;                // Final ranking (0-100)
    private ScoreBreakdown scoreBreakdown;      // Transparency
    private String curatedReason;               // Human-readable reason

    // ========== Risk Management ==========
    private double positionSizeMultiplier;      // 0.5 to 1.5 based on quality
    private double riskRewardRatio;

    /**
     * Score breakdown for transparency (ENHANCED)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScoreBreakdown {
        // Base scores (UPDATED weights)
        private double structureScore;     // 0-30 points (reduced from 40)
        private double regimeScore;        // 0-20 points (reduced from 25)
        private double flowScore;          // 0-15 points (reduced from 20)
        private double microScore;         // 0-10 points (reduced from 15)
        private double foScore;            // 0-15 points (NEW) 🆕
        private double levelsScore;        // 0-10 points (NEW) 🆕

        // Multipliers
        private double aclMultiplier;      // 0.7-1.1
        private double cssMultiplier;      // 0.9-1.1
        private double foMultiplier;       // 0.7-1.3 (NEW) 🆕

        private double totalScore;         // Final after multipliers
    }

    /**
     * Check if this is a high-quality signal
     */
    public boolean isHighQuality() {
        return curatedScore >= 70
                && entry != null
                && entry.isValidEntry()
                && breakout != null
                && breakout.isValid();
    }

    /**
     * Get suggested position size as % of capital
     */
    public double getSuggestedPositionPercent() {
        if (curatedScore >= 80) return 2.0 * positionSizeMultiplier;
        if (curatedScore >= 60) return 1.5 * positionSizeMultiplier;
        return 1.0 * positionSizeMultiplier;
    }

    // ========== Kafka Serde ==========
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static Serde<CuratedSignal> serde() {
        return Serdes.serdeFrom(new CuratedSignalSerializer(), new CuratedSignalDeserializer());
    }

    public static class CuratedSignalSerializer implements Serializer<CuratedSignal> {
        @Override
        public byte[] serialize(String topic, CuratedSignal data) {
            try {
                return data != null ? MAPPER.writeValueAsBytes(data) : null;
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for CuratedSignal", e);
            }
        }
    }

    public static class CuratedSignalDeserializer implements Deserializer<CuratedSignal> {
        @Override
        public CuratedSignal deserialize(String topic, byte[] bytes) {
            try {
                return bytes != null ? MAPPER.readValue(bytes, CuratedSignal.class) : null;
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for CuratedSignal", e);
            }
        }
    }
}
