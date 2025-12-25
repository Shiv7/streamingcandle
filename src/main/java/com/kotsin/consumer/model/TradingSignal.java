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

import java.util.List;

/**
 * TradingSignal - Combined VCP + IPU output for downstream consumers
 * 
 * Merges:
 * - MT-VCP: Volume clusters, support/resistance, structural bias, runway
 * - IPU: Institutional participation, momentum, urgency, exhaustion
 * 
 * Provides integrated signals and actionable recommendations
 * 
 * Topic: trading-signals
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TradingSignal {

    // ========== Metadata ==========
    private String scripCode;
    private String companyName;
    private long timestamp;

    // ========== Primary Action Signal ==========
    private SignalType signal;
    private double confidence;
    private String rationale;

    // ========== VCP Component (from MTVCPOutput) ==========
    private double vcpCombinedScore;
    private double supportScore;
    private double resistanceScore;
    private double structuralBias;
    private double runwayScore;
    private List<VCPCluster> clusters;

    // ========== IPU Component (from IPUOutput) ==========
    private double ipuFinalScore;
    private double instProxy;
    private double momentumContext;
    private double validatedMomentum;
    private double exhaustionScore;
    private double urgencyScore;
    private IPUOutput.MomentumState momentumState;
    private IPUOutput.UrgencyLevel urgencyLevel;
    private IPUOutput.Direction direction;
    private double directionalConviction;
    private double flowMomentumAgreement;
    private boolean exhaustionWarning;
    private boolean xfactorFlag;

    // ========== Current Market Context ==========
    private double currentPrice;
    private double atr;
    private double microprice;

    // ========== Position Sizing Recommendations ==========
    private double positionSizeMultiplier;
    private double trailAtrMultiplier;

    // ========== Trade Execution Parameters (CRITICAL FOR PRODUCTION) ==========
    private double entryPrice;           // Suggested entry price
    private double stopLoss;             // Stop loss level
    private double target1;              // First target (2:1 R:R)
    private double target2;              // Second target (3:1 R:R)
    private double riskRewardRatio;      // Calculated R:R
    private double riskPercentage;       // Risk as % of entry

    // ========== Enums ==========

    public enum SignalType {
        STRONG_MOMENTUM_LONG,       // Clean runway + bullish momentum + institutional
        STRONG_MOMENTUM_SHORT,      // Clean runway + bearish momentum + institutional
        CONFIRMED_BOUNCE_LONG,      // Support + bullish momentum acceleration
        CONFIRMED_BREAKDOWN_SHORT,  // Resistance break + bearish momentum
        FADE_EXHAUSTION_SHORT,      // Exhaustion at resistance
        FADE_EXHAUSTION_LONG,       // Exhaustion at support (bearish exhaustion)
        CLEAN_TREND_ENTRY_LONG,     // Low VCP + trending momentum
        CLEAN_TREND_ENTRY_SHORT,    // Low VCP + trending bearish momentum
        DIVERGENCE_WARNING,         // Momentum fading, reduce exposure
        ACCUMULATION_DETECTED,      // High flow, low momentum (building position)
        DISTRIBUTION_DETECTED,      // High flow out, low momentum
        NO_SIGNAL                   // No actionable signal
    }

    // ========== Factory Method: Merge VCP + IPU ==========

    public static TradingSignal from(MTVCPOutput vcp, IPUOutput ipu) {
        if (vcp == null && ipu == null) {
            return emptySignal();
        }

        TradingSignalBuilder builder = TradingSignal.builder()
                .timestamp(System.currentTimeMillis());

        // Populate from VCP
        if (vcp != null) {
            builder.scripCode(vcp.getScripCode())
                   .companyName(vcp.getCompanyName())
                   .vcpCombinedScore(vcp.getVcpCombinedScore())
                   .supportScore(vcp.getSupportScore())
                   .resistanceScore(vcp.getResistanceScore())
                   .structuralBias(vcp.getStructuralBias())
                   .runwayScore(vcp.getRunwayScore())
                   .clusters(vcp.getClusters())
                   .currentPrice(vcp.getCurrentPrice())
                   .atr(vcp.getAtr())
                   .microprice(vcp.getMicroprice());
        }

        // Populate from IPU
        if (ipu != null) {
            if (vcp == null) {
                builder.scripCode(ipu.getScripCode())
                       .companyName(ipu.getCompanyName());
            }
            builder.ipuFinalScore(ipu.getFinalIpuScore())
                   .instProxy(ipu.getInstProxy())
                   .momentumContext(ipu.getMomentumContext())
                   .validatedMomentum(ipu.getValidatedMomentum())
                   .exhaustionScore(ipu.getExhaustionScore())
                   .urgencyScore(ipu.getUrgencyScore())
                   .momentumState(ipu.getMomentumState())
                   .urgencyLevel(ipu.getUrgencyLevel())
                   .direction(ipu.getDirection())
                   .directionalConviction(ipu.getDirectionalConviction())
                   .flowMomentumAgreement(ipu.getFlowMomentumAgreement())
                   .exhaustionWarning(ipu.isExhaustionWarning())
                   .xfactorFlag(ipu.isXfactorFlag());

            // Position sizing
            builder.positionSizeMultiplier(ipu.getMomentumMultiplier() * ipu.getAgreementMultiplier());
            builder.trailAtrMultiplier(ipu.getTrailAtrMultiplier());
        }

        TradingSignal signal = builder.build();
        signal.classifySignal(vcp, ipu);
        signal.calculateTradeParams(vcp, ipu);  // Calculate entry/stop/targets
        return signal;
    }

    /**
     * Classify signal based on VCP + IPU combination
     */
    private void classifySignal(MTVCPOutput vcp, IPUOutput ipu) {
        // Defaults
        this.signal = SignalType.NO_SIGNAL;
        this.confidence = 0;
        this.rationale = "No actionable conditions detected";

        if (ipu == null) {
            if (vcp != null && vcp.getVcpCombinedScore() > 0.7) {
                this.signal = SignalType.NO_SIGNAL;
                this.rationale = "Waiting for IPU confirmation";
            }
            return;
        }

        // Scenario 1: Strong Momentum Long
        if (vcp != null 
                && vcp.getRunwayScore() > 0.6 
                && vcp.getStructuralBias() > 0.3
                && ipu.getFinalIpuScore() > 0.5
                && ipu.getDirection() == IPUOutput.Direction.BULLISH
                && (ipu.getMomentumState() == IPUOutput.MomentumState.ACCELERATING 
                    || ipu.getMomentumState() == IPUOutput.MomentumState.TRENDING)) {
            this.signal = SignalType.STRONG_MOMENTUM_LONG;
            this.confidence = vcp.getRunwayScore() * ipu.getCertainty() * (1 + ipu.getValidatedMomentum() * 0.2);
            this.rationale = "Clean runway + bullish momentum + institutional participation";
            return;
        }

        // Scenario 2: Strong Momentum Short
        if (vcp != null 
                && vcp.getRunwayScore() > 0.6 
                && vcp.getStructuralBias() < -0.3
                && ipu.getFinalIpuScore() > 0.5
                && ipu.getDirection() == IPUOutput.Direction.BEARISH
                && (ipu.getMomentumState() == IPUOutput.MomentumState.ACCELERATING 
                    || ipu.getMomentumState() == IPUOutput.MomentumState.TRENDING)) {
            this.signal = SignalType.STRONG_MOMENTUM_SHORT;
            this.confidence = vcp.getRunwayScore() * ipu.getCertainty() * (1 + ipu.getValidatedMomentum() * 0.2);
            this.rationale = "Clean runway + bearish momentum + institutional selling";
            return;
        }

        // Scenario 3: Fade Exhaustion at Resistance
        if (vcp != null 
                && vcp.getVcpCombinedScore() > 0.7
                && vcp.getResistanceScore() > 0.5
                && ipu.isExhaustionWarning()
                && ipu.getDirection() == IPUOutput.Direction.BULLISH) {
            this.signal = SignalType.FADE_EXHAUSTION_SHORT;
            this.confidence = vcp.getResistanceScore() * ipu.getExhaustionScore();
            this.rationale = "Bullish exhaustion at major resistance cluster";
            return;
        }

        // Scenario 4: Fade Exhaustion at Support
        if (vcp != null 
                && vcp.getVcpCombinedScore() > 0.7
                && vcp.getSupportScore() > 0.5
                && ipu.isExhaustionWarning()
                && ipu.getDirection() == IPUOutput.Direction.BEARISH) {
            this.signal = SignalType.FADE_EXHAUSTION_LONG;
            this.confidence = vcp.getSupportScore() * ipu.getExhaustionScore();
            this.rationale = "Bearish exhaustion at major support cluster";
            return;
        }

        // Scenario 5: Confirmed Bounce Long
        if (vcp != null 
                && vcp.getSupportScore() > 0.5
                && ipu.getDirection() == IPUOutput.Direction.BULLISH
                && ipu.getMomentumState() == IPUOutput.MomentumState.ACCELERATING
                && ipu.getMmsSlope() > 0) {
            this.signal = SignalType.CONFIRMED_BOUNCE_LONG;
            this.confidence = vcp.getSupportScore() * ipu.getValidatedMomentum();
            this.rationale = "Accelerating bullish momentum off support cluster";
            return;
        }

        // Scenario 6: Confirmed Breakdown Short
        if (vcp != null 
                && vcp.getResistanceScore() > 0.5
                && ipu.getDirection() == IPUOutput.Direction.BEARISH
                && ipu.getMomentumState() == IPUOutput.MomentumState.ACCELERATING
                && ipu.getMmsSlope() < 0) {
            this.signal = SignalType.CONFIRMED_BREAKDOWN_SHORT;
            this.confidence = vcp.getResistanceScore() * ipu.getValidatedMomentum();
            this.rationale = "Accelerating bearish momentum breaking support";
            return;
        }

        // Scenario 7: Clean Trend Entry
        if (vcp != null 
                && vcp.getRunwayScore() > 0.7
                && ipu.getMomentumState() == IPUOutput.MomentumState.TRENDING
                && ipu.getExhaustionScore() < 0.3
                && ipu.getCertainty() > 0.6) {
            if (ipu.getDirection() == IPUOutput.Direction.BULLISH) {
                this.signal = SignalType.CLEAN_TREND_ENTRY_LONG;
            } else if (ipu.getDirection() == IPUOutput.Direction.BEARISH) {
                this.signal = SignalType.CLEAN_TREND_ENTRY_SHORT;
            }
            this.confidence = ipu.getCertainty() * (1 - vcp.getVcpCombinedScore() * 0.3);
            this.rationale = "Clean runway with steady trend, no exhaustion";
            return;
        }

        // Scenario 8a: Divergence Warning (BULLISH losing momentum)
        if (ipu.getMomentumState() == IPUOutput.MomentumState.DECELERATING
                && ipu.getDirection() == IPUOutput.Direction.BULLISH
                && ipu.getFlowMomentumAgreement() < 0.5) {
            this.signal = SignalType.DIVERGENCE_WARNING;
            this.confidence = 1 - ipu.getFlowMomentumAgreement();
            this.rationale = "Bullish momentum losing flow support - potential reversal";
            return;
        }

        // Scenario 8b: Divergence Warning (BEARISH losing momentum)
        // BUG-FIX: Previously only checked BULLISH direction
        if (ipu.getMomentumState() == IPUOutput.MomentumState.DECELERATING
                && ipu.getDirection() == IPUOutput.Direction.BEARISH
                && ipu.getFlowMomentumAgreement() < 0.5) {
            this.signal = SignalType.DIVERGENCE_WARNING;
            this.confidence = 1 - ipu.getFlowMomentumAgreement();
            this.rationale = "Bearish momentum losing selling pressure - potential bounce";
            return;
        }

        // Scenario 9: Accumulation Detected
        if (ipu.getInstProxy() > 0.6 
                && ipu.getMomentumState() == IPUOutput.MomentumState.FLAT
                && ipu.getDirection() == IPUOutput.Direction.BULLISH) {
            this.signal = SignalType.ACCUMULATION_DETECTED;
            this.confidence = ipu.getInstProxy() * ipu.getOfQuality();
            this.rationale = "Institutional buying without price movement - accumulation";
            return;
        }

        // Scenario 10: Distribution Detected
        if (ipu.getInstProxy() > 0.6 
                && ipu.getMomentumState() == IPUOutput.MomentumState.FLAT
                && ipu.getDirection() == IPUOutput.Direction.BEARISH) {
            this.signal = SignalType.DISTRIBUTION_DETECTED;
            this.confidence = ipu.getInstProxy() * ipu.getOfQuality();
            this.rationale = "Institutional selling without price movement - distribution";
            return;
        }
    }

    /**
     * Calculate trade parameters (entry, stop, targets)
     * Call this AFTER classifySignal
     */
    public void calculateTradeParams(MTVCPOutput vcp, IPUOutput ipu) {
        // Clamp confidence
        this.confidence = Math.max(0, Math.min(1.0, this.confidence));

        // Need price data
        if (this.currentPrice <= 0) {
            return;
        }

        // Calculate ATR-based stops and targets
        double atrValue = this.atr > 0 ? this.atr : this.currentPrice * 0.015; // Default 1.5%

        if (isLongSignal()) {
            this.entryPrice = this.currentPrice;
            this.stopLoss = this.currentPrice - (1.5 * atrValue);
            this.target1 = this.currentPrice + (2.0 * atrValue);
            this.target2 = this.currentPrice + (3.5 * atrValue);
        } else if (isShortSignal()) {
            this.entryPrice = this.currentPrice;
            this.stopLoss = this.currentPrice + (1.5 * atrValue);
            this.target1 = this.currentPrice - (2.0 * atrValue);
            this.target2 = this.currentPrice - (3.5 * atrValue);
        } else {
            // No signal - no targets
            this.entryPrice = this.currentPrice;
            this.stopLoss = 0;
            this.target1 = 0;
            this.target2 = 0;
        }

        // Calculate risk/reward
        if (this.stopLoss > 0 && this.entryPrice > 0) {
            double risk = Math.abs(this.entryPrice - this.stopLoss);
            double reward = Math.abs(this.target1 - this.entryPrice);
            this.riskRewardRatio = risk > 0 ? reward / risk : 0;
            this.riskPercentage = this.entryPrice > 0 ? (risk / this.entryPrice) * 100 : 0;
        }
    }

    // ========== Convenience Methods ==========

    /**
     * Check if signal is actionable long
     */
    public boolean isLongSignal() {
        return signal == SignalType.STRONG_MOMENTUM_LONG
            || signal == SignalType.CONFIRMED_BOUNCE_LONG
            || signal == SignalType.CLEAN_TREND_ENTRY_LONG
            || signal == SignalType.FADE_EXHAUSTION_LONG;
    }

    /**
     * Check if signal is actionable short
     */
    public boolean isShortSignal() {
        return signal == SignalType.STRONG_MOMENTUM_SHORT
            || signal == SignalType.CONFIRMED_BREAKDOWN_SHORT
            || signal == SignalType.CLEAN_TREND_ENTRY_SHORT
            || signal == SignalType.FADE_EXHAUSTION_SHORT;
    }

    /**
     * Check if signal requires caution/reduction
     */
    public boolean isWarningSignal() {
        return signal == SignalType.DIVERGENCE_WARNING
            || signal == SignalType.ACCUMULATION_DETECTED
            || signal == SignalType.DISTRIBUTION_DETECTED;
    }

    /**
     * Get adjusted position size
     */
    public double getAdjustedPositionSize(double baseSize) {
        return baseSize * positionSizeMultiplier;
    }

    /**
     * Get trailing stop distance
     */
    public double getTrailingStopDistance() {
        return atr * trailAtrMultiplier;
    }

    public static TradingSignal emptySignal() {
        return TradingSignal.builder()
                .signal(SignalType.NO_SIGNAL)
                .confidence(0)
                .rationale("No data available")
                .timestamp(System.currentTimeMillis())
                .build();
    }

    // ========== Kafka Serde ==========
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    public static Serde<TradingSignal> serde() {
        return Serdes.serdeFrom(new TradingSignalSerializer(), new TradingSignalDeserializer());
    }

    public static class TradingSignalSerializer implements Serializer<TradingSignal> {
        @Override
        public byte[] serialize(String topic, TradingSignal data) {
            if (data == null) return null;
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for TradingSignal", e);
            }
        }
    }

    public static class TradingSignalDeserializer implements Deserializer<TradingSignal> {
        @Override
        public TradingSignal deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return MAPPER.readValue(bytes, TradingSignal.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for TradingSignal", e);
            }
        }
    }
}
