package com.kotsin.consumer.enrichment.signal.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * SignalRationale - Complete narrative context for a trading signal
 *
 * Answers: "Why should I take this trade?"
 *
 * Provides multi-layered reasoning:
 * 1. Pattern/Setup basis (what triggered)
 * 2. Market context (why now)
 * 3. Technical alignment (supporting factors)
 * 4. Options flow context (institutional positioning)
 * 5. Risk considerations (what could go wrong)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SignalRationale {

    /**
     * Jackson constructor for deserializing from String value (legacy format)
     */
    @JsonCreator
    public SignalRationale(String headline) {
        this.headline = headline;
    }

    // ======================== HEADLINE ========================

    /**
     * One-line headline (max 100 chars)
     */
    private String headline;

    /**
     * Thesis statement (1-2 sentences)
     */
    private String thesis;

    /**
     * Trade type classification
     */
    private TradeType tradeType;

    public enum TradeType {
        TREND_FOLLOWING,        // Following established trend
        COUNTER_TREND,          // Against current trend (reversal)
        BREAKOUT,               // Breaking out of range
        MEAN_REVERSION,         // Return to average
        MOMENTUM,               // Following momentum burst
        SQUEEZE,                // Compression/expansion play
        FADE,                   // Fading move
        EXHAUSTION              // Trading exhaustion
    }

    // ======================== TRIGGER CONTEXT ========================

    /**
     * What triggered this signal
     */
    private TriggerContext trigger;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TriggerContext {
        /**
         * Primary trigger type
         */
        private TriggerType type;

        /**
         * Trigger description
         */
        private String description;

        /**
         * Pattern/Setup name if applicable
         */
        private String patternName;

        /**
         * Events that constituted the trigger
         */
        @Builder.Default
        private List<String> triggerEvents = new ArrayList<>();

        /**
         * Time since trigger
         */
        private long millisSinceTrigger;

        public enum TriggerType {
            PATTERN_COMPLETE,       // Pattern sequence completed
            SETUP_READY,            // Trading setup became ready
            FLIP_DETECTED,          // OFI/SuperTrend flip
            EXHAUSTION_SIGNAL,      // Buying/Selling exhaustion
            ABSORPTION_DETECTED,    // Institutional absorption
            TECHNICAL_SIGNAL,       // Technical indicator signal
            CONFLUENCE_ZONE,        // Entered confluence zone
            FORECAST_TRIGGER        // Forecaster prediction materialized
        }
    }

    // ======================== MARKET CONTEXT ========================

    /**
     * Why market conditions favor this trade
     */
    private MarketContext marketContext;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MarketContext {
        /**
         * Current market regime
         */
        private String regime;

        /**
         * Regime alignment with trade
         */
        private AlignmentLevel regimeAlignment;

        /**
         * Explanation of regime fit
         */
        private String regimeDescription;

        /**
         * Session quality
         */
        private String sessionQuality;

        /**
         * Time context description
         */
        private String timeContext;

        /**
         * Volatility context
         */
        private String volatilityContext;

        public enum AlignmentLevel {
            STRONG,     // Regime strongly supports trade
            MODERATE,   // Regime moderately supports
            NEUTRAL,    // Regime neutral
            CONTRARY    // Trading against regime (higher risk)
        }
    }

    // ======================== TECHNICAL CONTEXT ========================

    /**
     * Technical factors supporting the trade
     */
    private TechnicalContext technicalContext;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TechnicalContext {
        /**
         * SuperTrend alignment
         */
        private String superTrendStatus;

        /**
         * Bollinger Band position
         */
        private String bbPosition;

        /**
         * Pivot point context
         */
        private String pivotContext;

        /**
         * Moving average context
         */
        private String maContext;

        /**
         * Momentum indicators
         */
        private String momentumStatus;

        /**
         * Volume analysis
         */
        private String volumeAnalysis;

        /**
         * Key levels nearby
         */
        @Builder.Default
        private List<String> keyLevelDescriptions = new ArrayList<>();
    }

    // ======================== OPTIONS CONTEXT ========================

    /**
     * Options/GEX context
     */
    private OptionsContext optionsContext;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionsContext {
        /**
         * GEX regime
         */
        private String gexRegime;

        /**
         * GEX implication
         */
        private String gexImplication;

        /**
         * Max pain analysis
         */
        private String maxPainContext;

        /**
         * Days to expiry context
         */
        private String expiryContext;

        /**
         * Put/Call context
         */
        private String putCallContext;

        /**
         * Gamma exposure context
         */
        private String gammaContext;
    }

    // ======================== FLOW ANALYSIS ========================

    /**
     * Order flow analysis
     */
    private FlowContext flowContext;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FlowContext {
        /**
         * OFI status
         */
        private String ofiStatus;

        /**
         * VPIN status
         */
        private String vpinStatus;

        /**
         * Volume delta analysis
         */
        private String volumeDeltaStatus;

        /**
         * Smart money indication
         */
        private String smartMoneySignal;

        /**
         * Institutional activity
         */
        private String institutionalActivity;
    }

    // ======================== SUPPORTING FACTORS ========================

    /**
     * Factors supporting the trade
     */
    @Builder.Default
    private List<SupportingFactor> supportingFactors = new ArrayList<>();

    /**
     * Confluence score
     */
    private double confluenceScore;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SupportingFactor {
        private String factor;          // Description
        private FactorType type;        // Category
        private double weight;          // Importance (0-1)
        private boolean aligned;        // Supports trade direction

        public enum FactorType {
            PATTERN,        // Pattern/setup factor
            TECHNICAL,      // Technical indicator
            FLOW,           // Order flow
            OPTIONS,        // Options/GEX
            TIME,           // Time/session
            CONTEXT         // Other context
        }
    }

    // ======================== RISK FACTORS ========================

    /**
     * Factors that could work against the trade
     */
    @Builder.Default
    private List<RiskFactor> riskFactors = new ArrayList<>();

    /**
     * Overall risk assessment
     */
    private RiskAssessment riskAssessment;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskFactor {
        private String description;     // What the risk is
        private RiskLevel severity;     // How serious
        private String mitigation;      // How it's mitigated

        public enum RiskLevel {
            LOW, MEDIUM, HIGH, CRITICAL
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskAssessment {
        private OverallRisk overallRisk;
        private String riskSummary;
        private List<String> keyRisks;
        private double riskAdjustedReturn;

        public enum OverallRisk {
            LOW,
            MODERATE,
            ELEVATED,
            HIGH
        }
    }

    // ======================== FAMILY CONTEXT (Multi-Instrument) ========================

    /**
     * Family bias (from FamilyContextAnalyzer)
     */
    private String familyBias;

    /**
     * Family alignment percentage (0-100)
     */
    private Double familyAlignment;

    /**
     * Is family fully aligned (all instruments agree)
     */
    private boolean fullyAligned;

    /**
     * Has divergence detected (options vs price)
     */
    private boolean hasDivergence;

    /**
     * Divergence details
     */
    @Builder.Default
    private List<String> divergenceDetails = new ArrayList<>();

    /**
     * Short squeeze setup detected
     */
    private boolean shortSqueezeSetup;

    /**
     * Long squeeze setup detected
     */
    private boolean longSqueezeSetup;

    /**
     * Family context interpretation
     */
    private String familyInterpretation;

    // ======================== EDGE DEFINITION ========================

    /**
     * What provides the edge in this trade
     */
    private EdgeDefinition edge;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EdgeDefinition {
        /**
         * Primary edge type
         */
        private EdgeType type;

        /**
         * Edge description
         */
        private String description;

        /**
         * Historical success rate for this edge
         */
        private double historicalSuccessRate;

        /**
         * Expected edge decay time
         */
        private String edgeDecayTimeframe;

        public enum EdgeType {
            PATTERN_RECOGNITION,    // Recognized pattern
            FLOW_IMBALANCE,         // Order flow edge
            TECHNICAL_CONFLUENCE,   // Multiple technicals align
            OPTIONS_POSITIONING,    // Options/GEX edge
            SMART_MONEY,            // Following institutions
            MEAN_REVERSION,         // Statistical edge
            MOMENTUM,               // Momentum continuation
            EXHAUSTION              // Counter-move edge
        }
    }

    // ======================== NARRATIVE GENERATION ========================

    /**
     * Generate full narrative from components
     */
    public String getFullNarrative() {
        StringBuilder narrative = new StringBuilder();

        // Headline
        narrative.append(headline).append("\n\n");

        // Thesis
        if (thesis != null) {
            narrative.append("THESIS: ").append(thesis).append("\n\n");
        }

        // Trigger
        if (trigger != null) {
            narrative.append("TRIGGER: ").append(trigger.getDescription()).append("\n");
            if (!trigger.getTriggerEvents().isEmpty()) {
                narrative.append("Events: ").append(String.join(", ", trigger.getTriggerEvents())).append("\n");
            }
            narrative.append("\n");
        }

        // Market Context
        if (marketContext != null) {
            narrative.append("MARKET CONTEXT:\n");
            narrative.append("- Regime: ").append(marketContext.getRegime())
                    .append(" (").append(marketContext.getRegimeAlignment()).append(")\n");
            if (marketContext.getRegimeDescription() != null) {
                narrative.append("- ").append(marketContext.getRegimeDescription()).append("\n");
            }
            if (marketContext.getSessionQuality() != null) {
                narrative.append("- Session: ").append(marketContext.getSessionQuality()).append("\n");
            }
            narrative.append("\n");
        }

        // Supporting Factors
        if (!supportingFactors.isEmpty()) {
            narrative.append("SUPPORTING FACTORS:\n");
            for (SupportingFactor factor : supportingFactors) {
                String alignment = factor.isAligned() ? "[+]" : "[-]";
                narrative.append(alignment).append(" ").append(factor.getFactor()).append("\n");
            }
            narrative.append("\n");
        }

        // Risk Factors
        if (!riskFactors.isEmpty()) {
            narrative.append("RISKS:\n");
            for (RiskFactor risk : riskFactors) {
                narrative.append("- [").append(risk.getSeverity()).append("] ")
                        .append(risk.getDescription()).append("\n");
            }
            narrative.append("\n");
        }

        // Edge
        if (edge != null) {
            narrative.append("EDGE: ").append(edge.getDescription());
            if (edge.getHistoricalSuccessRate() > 0) {
                narrative.append(String.format(" (%.0f%% historical success)", edge.getHistoricalSuccessRate() * 100));
            }
            narrative.append("\n");
        }

        return narrative.toString().trim();
    }

    /**
     * Generate short summary (for display)
     */
    public String getShortSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append(headline);

        if (trigger != null && trigger.getDescription() != null) {
            summary.append(" | ").append(trigger.getDescription());
        }

        if (edge != null && edge.getHistoricalSuccessRate() > 0) {
            summary.append(String.format(" | %.0f%% hist", edge.getHistoricalSuccessRate() * 100));
        }

        return summary.toString();
    }

    /**
     * Get confluence count
     */
    public int getConfluenceCount() {
        return (int) supportingFactors.stream().filter(SupportingFactor::isAligned).count();
    }

    /**
     * Check if trade has strong rationale
     */
    public boolean hasStrongRationale() {
        return getConfluenceCount() >= 3 &&
                edge != null &&
                riskFactors.stream().noneMatch(r -> r.getSeverity() == RiskFactor.RiskLevel.CRITICAL);
    }

    @Override
    public String toString() {
        return String.format("SignalRationale: %s | Type: %s | Edge: %s | Confluence: %d factors",
                headline,
                tradeType,
                edge != null ? edge.getType() : "N/A",
                getConfluenceCount());
    }
}
