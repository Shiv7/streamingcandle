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

import java.util.UUID;

/**
 * QuantTradingSignal - Actionable trading signal with full execution parameters.
 *
 * Contains:
 * - Entry/exit price levels with multiple targets
 * - Stop loss and trailing stop configuration
 * - Position sizing recommendation based on risk
 * - Greeks-based hedging suggestions
 * - Signal classification and confidence
 *
 * INSTITUTIONAL GRADE: Designed for systematic execution with proper risk management.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuantTradingSignal {

    // ========== SIGNAL IDENTITY ==========
    @Builder.Default
    private String signalId = UUID.randomUUID().toString();

    private String scripCode;
    private String symbol;
    private String companyName;
    private String exchange;          // NSE, BSE, MCX
    private long timestamp;
    private String humanReadableTime;

    // ========== QUANT SCORE REFERENCE ==========
    private double quantScore;
    private String quantLabel;
    private QuantScore.QuantScoreBreakdown scoreBreakdown;
    private double confidence;

    // ========== SIGNAL CLASSIFICATION ==========
    private SignalType signalType;
    private SignalDirection direction;
    private SignalStrength strength;

    public enum SignalType {
        // Greeks-based signals
        GAMMA_SQUEEZE_LONG,
        GAMMA_SQUEEZE_SHORT,
        DELTA_MOMENTUM_LONG,
        DELTA_MOMENTUM_SHORT,
        VEGA_EXPANSION,
        VEGA_CONTRACTION,

        // IV-based signals
        IV_CRUSH_ENTRY,
        IV_EXPANSION_ENTRY,
        SKEW_REVERSAL,

        // Flow-based signals
        FLOW_REVERSAL_LONG,
        FLOW_REVERSAL_SHORT,
        OI_BREAKOUT_LONG,
        OI_BREAKOUT_SHORT,

        // Microstructure signals
        MICROSTRUCTURE_IMBALANCE_LONG,
        MICROSTRUCTURE_IMBALANCE_SHORT,
        SMART_MONEY_ACCUMULATION,
        SMART_MONEY_DISTRIBUTION,

        // Confluence signals
        CONFLUENCE_BREAKOUT,
        CONFLUENCE_BREAKDOWN,
        MULTI_TIMEFRAME_ALIGNMENT,

        // Other
        WYCKOFF_ACCUMULATION,
        WYCKOFF_DISTRIBUTION,
        REVERSAL_PATTERN,
        NO_SIGNAL
    }

    public enum SignalDirection {
        LONG,
        SHORT,
        NEUTRAL
    }

    public enum SignalStrength {
        VERY_STRONG(0.9, 1.0),
        STRONG(0.75, 0.9),
        MODERATE(0.6, 0.75),
        WEAK(0.4, 0.6),
        VERY_WEAK(0, 0.4);

        private final double minConfidence;
        private final double maxConfidence;

        SignalStrength(double min, double max) {
            this.minConfidence = min;
            this.maxConfidence = max;
        }

        public static SignalStrength fromConfidence(double confidence) {
            if (confidence >= 0.9) return VERY_STRONG;
            if (confidence >= 0.75) return STRONG;
            if (confidence >= 0.6) return MODERATE;
            if (confidence >= 0.4) return WEAK;
            return VERY_WEAK;
        }
    }

    // ========== SIGNAL RATIONALE ==========
    private String rationale;
    private String primaryDriver;     // Main category driving the signal
    private String secondaryDriver;   // Supporting category

    // ========== ENTRY PARAMETERS ==========
    private double entryPrice;
    private double entryPriceMin;     // Entry zone lower bound
    private double entryPriceMax;     // Entry zone upper bound
    private EntryType entryType;

    public enum EntryType {
        MARKET,           // Enter at market
        LIMIT,            // Limit order at entryPrice
        STOP_LIMIT,       // Stop limit on breakout
        SCALE_IN          // Multiple entries
    }

    // ========== STOP LOSS ==========
    private double stopLoss;
    private double stopLossPercent;   // As % of entry
    private StopLossType slType;

    public enum StopLossType {
        FIXED,            // Static SL
        ATR_BASED,        // Dynamic based on ATR
        GAMMA_ADJUSTED,   // Adjusted for gamma exposure
        TRAILING          // Trailing stop
    }

    // ========== TARGETS ==========
    private double target1;           // Conservative target (2:1 R:R)
    private double target2;           // Standard target (3:1 R:R)
    private double target3;           // Runner target (5:1 R:R)

    private double target1Pct;        // % of position to exit at T1
    private double target2Pct;        // % of position to exit at T2
    private double target3Pct;        // % of position to exit at T3

    // ========== RISK PARAMETERS ==========
    private double riskRewardRatio;
    private double maxRiskPercent;    // Max % of capital to risk
    private double maxLossAmount;     // Max loss in currency
    private double riskPerShare;      // Entry - SL

    // ========== POSITION SIZING ==========
    private PositionSizing sizing;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PositionSizing {
        private int suggestedQuantity;
        private double notionalValue;
        private double riskAmount;        // Amount at risk if SL hit

        // F&O specific
        private int lotSize;
        private int lotsRecommended;
        private double marginRequired;

        // Sizing multipliers
        private double sizeMultiplier;    // 0.5 - 1.5 based on conviction
        private String sizingRationale;

        // Capital allocation
        private double capitalAllocationPct;  // % of portfolio
        private double maxPositionValue;
    }

    // ========== TRAILING STOP CONFIG ==========
    private TrailingStopConfig trailingStop;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrailingStopConfig {
        private boolean enabled;
        private TrailingType type;

        private double activationR;       // R multiple to activate trailing
        private double trailDistance;     // Distance to trail

        // Stage-based trailing (R-multiple)
        private double stage1R;           // Activate at 1.0R
        private double stage1StopR;       // Move SL to 0.0R (breakeven)
        private double stage2R;           // Activate at 1.5R
        private double stage2StopR;       // Move SL to 0.5R
        private double stage3R;           // Activate at 2.0R
        private double stage3StopR;       // Move SL to 1.0R

        public enum TrailingType {
            FIXED_DISTANCE,       // Trail by fixed amount
            PERCENTAGE,           // Trail by percentage
            ATR_BASED,            // Trail by ATR multiple
            R_MULTIPLE_STAGED     // Staged trailing by R-multiple
        }
    }

    // ========== HEDGING RECOMMENDATION ==========
    private HedgingRecommendation hedging;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HedgingRecommendation {
        private boolean hedgeNeeded;
        private HedgeType hedgeType;
        private HedgeUrgency urgency;

        // Hedge instrument
        private String hedgeInstrument;   // e.g., "24500PE", "ATM_PUT"
        private String hedgeStrike;
        private String hedgeExpiry;
        private double hedgeQuantity;
        private double hedgeRatio;        // Hedge ratio vs main position

        // Delta hedging
        private double deltaToNeutralize;
        private double suggestedHedgeDelta;
        private String deltaHedgeAction;  // "BUY_PUTS", "SELL_CALLS", etc.

        // Gamma hedging
        private double gammaExposure;
        private String gammaStrategy;     // "BUY_STRADDLE", "SELL_STRANGLE"

        // Vega hedging
        private double vegaExposure;
        private String vegaStrategy;      // "CALENDAR_SPREAD", "DIAGONAL"

        // Cost estimation
        private double estimatedHedgeCost;
        private double hedgeCostPercent;  // As % of main position

        // Rationale
        private String hedgeRationale;

        public enum HedgeType {
            DELTA_HEDGE,      // Neutralize delta
            GAMMA_HEDGE,      // Manage gamma risk
            VEGA_HEDGE,       // Manage volatility risk
            TAIL_HEDGE,       // Protect against tail risk
            COLLAR,           // Buy put + sell call
            PROTECTIVE_PUT,   // Just buy protective put
            NO_HEDGE          // No hedge recommended
        }

        public enum HedgeUrgency {
            IMMEDIATE,        // Hedge before entry
            ON_ENTRY,         // Hedge with entry
            AFTER_ENTRY,      // Hedge if position moves against
            OPTIONAL          // Nice to have
        }
    }

    // ========== TIME CONSTRAINTS ==========
    private TimeConstraints timeConstraints;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeConstraints {
        private long validUntil;          // Signal expires at
        private int maxHoldingMinutes;    // Max time in trade
        private boolean avoidFirstHour;   // Avoid 9:15-10:15
        private boolean avoidLastHour;    // Avoid 2:30-3:30
        private boolean intradayOnly;     // Must exit today
    }

    // ========== SIGNAL FLAGS ==========
    private boolean longSignal;
    private boolean shortSignal;
    private boolean hedgeRecommended;
    private boolean partialExitRecommended;
    private boolean scaleInRecommended;
    private boolean highConviction;

    // ========== METADATA ==========
    private String sourceProcessor;       // Which processor generated this
    private String timeframe;             // 1m, 5m, 15m, etc.
    private int categoriesAboveThreshold; // How many of 8 categories are strong

    // Phase 1 SMTIS: Enrichment context
    private String enrichmentNote;        // Historical context insights (e.g., "OFI flip detected")
    private boolean isActionableMoment;   // True if historical context suggests actionable moment

    // ========== CONVENIENCE METHODS ==========

    public boolean isLong() {
        return direction == SignalDirection.LONG;
    }

    public boolean isShort() {
        return direction == SignalDirection.SHORT;
    }

    public boolean isActionable() {
        return signalType != SignalType.NO_SIGNAL &&
               direction != SignalDirection.NEUTRAL &&
               confidence >= 0.6;
    }

    public boolean isHighConviction() {
        return strength == SignalStrength.VERY_STRONG ||
               strength == SignalStrength.STRONG;
    }

    public double getRiskAmount() {
        if (sizing != null) {
            return sizing.getRiskAmount();
        }
        return riskPerShare * (sizing != null ? sizing.getSuggestedQuantity() : 0);
    }

    public double getExpectedProfit(int targetLevel) {
        double target = switch (targetLevel) {
            case 1 -> target1;
            case 2 -> target2;
            case 3 -> target3;
            default -> target1;
        };

        if (isLong()) {
            return (target - entryPrice) * (sizing != null ? sizing.getSuggestedQuantity() : 0);
        } else {
            return (entryPrice - target) * (sizing != null ? sizing.getSuggestedQuantity() : 0);
        }
    }

    /**
     * Factory method for no-signal
     */
    public static QuantTradingSignal noSignal(String scripCode, String symbol) {
        return QuantTradingSignal.builder()
            .signalId(UUID.randomUUID().toString())
            .scripCode(scripCode)
            .symbol(symbol)
            .timestamp(System.currentTimeMillis())
            .signalType(SignalType.NO_SIGNAL)
            .direction(SignalDirection.NEUTRAL)
            .strength(SignalStrength.VERY_WEAK)
            .confidence(0)
            .longSignal(false)
            .shortSignal(false)
            .build();
    }

    // ========== SERDE ==========

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public static Serde<QuantTradingSignal> serde() {
        return Serdes.serdeFrom(new QuantTradingSignalSerializer(), new QuantTradingSignalDeserializer());
    }

    public static class QuantTradingSignalSerializer implements Serializer<QuantTradingSignal> {
        @Override
        public byte[] serialize(String topic, QuantTradingSignal data) {
            if (data == null) return null;
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for QuantTradingSignal", e);
            }
        }
    }

    public static class QuantTradingSignalDeserializer implements Deserializer<QuantTradingSignal> {
        @Override
        public QuantTradingSignal deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return MAPPER.readValue(bytes, QuantTradingSignal.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for QuantTradingSignal", e);
            }
        }
    }
}
