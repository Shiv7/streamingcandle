package com.kotsin.consumer.enrichment.signal.outcome;

import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * SignalOutcome - Records the outcome of a trading signal
 *
 * Tracks:
 * - Entry and exit details
 * - P&L calculation
 * - Target/stop hit tracking
 * - Time-based metrics
 * - Attribution to pattern/setup
 *
 * Used for:
 * - Performance measurement
 * - Strategy optimization
 * - ML feedback loop
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "signal_outcomes")
@CompoundIndex(name = "family_time_idx", def = "{'familyId': 1, 'closedAt': -1}")
@CompoundIndex(name = "pattern_idx", def = "{'patternId': 1, 'closedAt': -1}")
@CompoundIndex(name = "source_idx", def = "{'source': 1, 'closedAt': -1}")
public class SignalOutcome {

    // ======================== IDENTITY ========================

    @Id
    private String id;

    /**
     * Original signal ID
     */
    @Indexed
    private String signalId;

    /**
     * Family ID
     */
    @Indexed
    private String familyId;

    /**
     * Scrip code
     */
    private String scripCode;

    /**
     * Company name
     */
    private String companyName;

    // ======================== SIGNAL INFO ========================

    /**
     * Signal source (PATTERN, SETUP, FORECAST, INTELLIGENCE)
     */
    @Indexed
    private String source;

    /**
     * Signal category (REVERSAL, BREAKOUT, etc.)
     */
    private String category;

    /**
     * Signal direction (LONG, SHORT)
     */
    private String direction;

    /**
     * Signal horizon (SCALP, INTRADAY, SWING, POSITIONAL)
     */
    private String horizon;

    /**
     * Pattern ID if from pattern recognition
     */
    @Indexed
    private String patternId;

    /**
     * Setup ID if from setup tracker
     */
    private String setupId;

    /**
     * Sequence ID if from pattern sequence
     */
    private String sequenceId;

    /**
     * Original signal confidence
     */
    private double signalConfidence;

    /**
     * Original signal quality score
     */
    private int signalQualityScore;

    // ======================== TRADE EXECUTION ========================

    /**
     * When signal was generated
     */
    private Instant signalGeneratedAt;

    /**
     * When trade was entered
     */
    private Instant enteredAt;

    /**
     * When trade was closed
     */
    @Indexed
    private Instant closedAt;

    /**
     * Signal entry price (planned)
     */
    private double plannedEntryPrice;

    /**
     * Actual entry price
     */
    private double actualEntryPrice;

    /**
     * Entry slippage
     */
    private double entrySlippage;

    /**
     * Planned stop loss
     */
    private double plannedStopLoss;

    /**
     * Actual exit price
     */
    private double exitPrice;

    /**
     * Planned target 1
     */
    private double plannedTarget1;

    /**
     * Planned target 2
     */
    private double plannedTarget2;

    /**
     * Planned target 3
     */
    private double plannedTarget3;

    // ======================== OUTCOME ========================

    /**
     * Trade outcome
     */
    @Indexed
    private Outcome outcome;

    /**
     * Exit reason
     */
    private ExitReason exitReason;

    /**
     * Exit reason description
     */
    private String exitReasonDescription;

    public enum Outcome {
        WIN,            // Profit (any target hit or positive exit)
        LOSS,           // Loss (stop hit or negative exit)
        BREAKEVEN,      // Exit near entry
        EXPIRED,        // Signal expired without entry
        CANCELLED,      // Signal cancelled
        INVALIDATED     // Invalidation condition triggered
    }

    public enum ExitReason {
        TARGET_1_HIT,
        TARGET_2_HIT,
        TARGET_3_HIT,
        STOP_LOSS_HIT,
        TRAILING_STOP_HIT,
        TIME_EXIT,          // Time-based exit
        MANUAL_EXIT,        // User manual exit
        INVALIDATION,       // Invalidation condition triggered
        SIGNAL_EXPIRED,     // Never entered
        REVERSE_SIGNAL,     // Opposite signal generated
        SYSTEM_EXIT         // System-forced exit
    }

    // ======================== P&L METRICS ========================

    /**
     * Gross P&L amount
     */
    private double pnlAmount;

    /**
     * P&L percentage
     */
    private double pnlPct;

    /**
     * R-multiple (P&L / Risk)
     */
    private double rMultiple;

    /**
     * Max favorable excursion (best price during trade)
     */
    private double maxFavorableExcursion;

    /**
     * Max favorable excursion percentage
     */
    private double mfePct;

    /**
     * Max adverse excursion (worst price during trade)
     */
    private double maxAdverseExcursion;

    /**
     * Max adverse excursion percentage
     */
    private double maePct;

    /**
     * Position size used
     */
    private double positionSize;

    /**
     * Position size multiplier
     */
    private double positionSizeMultiplier;

    // ======================== TARGET TRACKING ========================

    /**
     * Target 1 hit
     */
    private boolean target1Hit;

    /**
     * Target 2 hit
     */
    private boolean target2Hit;

    /**
     * Target 3 hit
     */
    private boolean target3Hit;

    /**
     * Time to target 1 (ms)
     */
    private Long timeToTarget1Ms;

    /**
     * Time to target 2 (ms)
     */
    private Long timeToTarget2Ms;

    /**
     * Time to target 3 (ms)
     */
    private Long timeToTarget3Ms;

    /**
     * Stop loss hit
     */
    private boolean stopLossHit;

    /**
     * Trailing stop triggered
     */
    private boolean trailingStopTriggered;

    // ======================== TIME METRICS ========================

    /**
     * Time from signal to entry (ms)
     */
    private long signalToEntryMs;

    /**
     * Time in trade (ms)
     */
    private long timeInTradeMs;

    /**
     * Total time from signal to close (ms)
     */
    private long totalDurationMs;

    // ======================== CONTEXT AT SIGNAL ========================

    /**
     * GEX regime at signal
     */
    private String gexRegimeAtSignal;

    /**
     * Session at signal
     */
    private String sessionAtSignal;

    /**
     * Days to expiry at signal
     */
    private Integer daysToExpiryAtSignal;

    /**
     * At confluence zone
     */
    private boolean atConfluenceZone;

    /**
     * Nearest support at signal
     */
    private Double nearestSupportAtSignal;

    /**
     * Nearest resistance at signal
     */
    private Double nearestResistanceAtSignal;

    // ======================== PREDICTIONS TRACKING ========================

    /**
     * Predicted events that were expected
     */
    private List<String> predictedEvents;

    /**
     * Predicted events that actually occurred
     */
    private List<String> predictedEventsOccurred;

    /**
     * Prediction accuracy (0-1)
     */
    private Double predictionAccuracy;

    // ======================== INVALIDATION TRACKING ========================

    /**
     * Invalidation conditions that were set
     */
    private List<String> invalidationConditions;

    /**
     * Invalidation condition that triggered (if any)
     */
    private String invalidationTriggered;

    // ======================== METADATA ========================

    /**
     * Additional metadata
     */
    private Map<String, Object> metadata;

    /**
     * Processing version
     */
    private String version;

    /**
     * Created at timestamp
     */
    @Builder.Default
    private Instant createdAt = Instant.now();

    // ======================== COMPUTED PROPERTIES ========================

    /**
     * Check if trade was profitable
     */
    public boolean isProfitable() {
        return outcome == Outcome.WIN || pnlPct > 0;
    }

    /**
     * Check if signal was successful (direction correct)
     */
    public boolean isDirectionCorrect() {
        if ("LONG".equals(direction)) {
            return exitPrice > actualEntryPrice;
        } else {
            return exitPrice < actualEntryPrice;
        }
    }

    /**
     * Get target hit count
     */
    public int getTargetHitCount() {
        int count = 0;
        if (target1Hit) count++;
        if (target2Hit) count++;
        if (target3Hit) count++;
        return count;
    }

    /**
     * Check if any target was hit
     */
    public boolean hasTargetHit() {
        return target1Hit || target2Hit || target3Hit;
    }

    /**
     * Get risk taken (entry to stop)
     */
    public double getRiskTaken() {
        if ("LONG".equals(direction)) {
            return actualEntryPrice - plannedStopLoss;
        } else {
            return plannedStopLoss - actualEntryPrice;
        }
    }

    /**
     * Calculate what R-multiple would have been at each target
     */
    public double getTarget1RMultiple() {
        double risk = getRiskTaken();
        if (risk <= 0) return 0;
        if ("LONG".equals(direction)) {
            return (plannedTarget1 - actualEntryPrice) / risk;
        } else {
            return (actualEntryPrice - plannedTarget1) / risk;
        }
    }

    public double getTarget2RMultiple() {
        double risk = getRiskTaken();
        if (risk <= 0) return 0;
        if ("LONG".equals(direction)) {
            return (plannedTarget2 - actualEntryPrice) / risk;
        } else {
            return (actualEntryPrice - plannedTarget2) / risk;
        }
    }

    /**
     * Get trade efficiency (actual P&L vs MFE)
     */
    public double getTradeEfficiency() {
        if (mfePct <= 0) return 0;
        return pnlPct / mfePct;
    }

    // ======================== FACTORY METHODS ========================

    /**
     * Create from TradingSignal and execution result
     */
    public static SignalOutcome fromSignal(TradingSignal signal, TradeExecution execution) {
        return SignalOutcome.builder()
                .signalId(signal.getSignalId())
                .familyId(signal.getFamilyId())
                .scripCode(signal.getScripCode())
                .companyName(signal.getCompanyName())
                .source(signal.getSource() != null ? signal.getSource().name() : null)
                .category(signal.getCategory() != null ? signal.getCategory().name() : null)
                .direction(signal.getDirection() != null ? signal.getDirection().name() : null)
                .horizon(signal.getHorizon() != null ? signal.getHorizon().name() : null)
                .patternId(signal.getPatternId())
                .setupId(signal.getSetupId())
                .sequenceId(signal.getSequenceId())
                .signalConfidence(signal.getConfidence())
                .signalQualityScore(signal.getQualityScore())
                .signalGeneratedAt(signal.getGeneratedAt())
                .plannedEntryPrice(signal.getEntryPrice())
                .plannedStopLoss(signal.getStopLoss())
                .plannedTarget1(signal.getTarget1())
                .plannedTarget2(signal.getTarget2())
                .plannedTarget3(signal.getTarget3())
                .gexRegimeAtSignal(signal.getGexRegime())
                .sessionAtSignal(signal.getSession())
                .daysToExpiryAtSignal(signal.getDaysToExpiry())
                .atConfluenceZone(signal.isAtConfluenceZone())
                .nearestSupportAtSignal(signal.getNearestSupport())
                .nearestResistanceAtSignal(signal.getNearestResistance())
                .positionSizeMultiplier(signal.getPositionSizeMultiplier())
                .version("2.0")
                .build();
    }

    /**
     * Create for expired signal
     */
    public static SignalOutcome expired(TradingSignal signal) {
        SignalOutcome outcome = fromSignal(signal, null);
        outcome.setOutcome(Outcome.EXPIRED);
        outcome.setExitReason(ExitReason.SIGNAL_EXPIRED);
        outcome.setClosedAt(Instant.now());
        return outcome;
    }

    /**
     * Create for cancelled signal
     */
    public static SignalOutcome cancelled(TradingSignal signal, String reason) {
        SignalOutcome outcome = fromSignal(signal, null);
        outcome.setOutcome(Outcome.CANCELLED);
        outcome.setExitReasonDescription(reason);
        outcome.setClosedAt(Instant.now());
        return outcome;
    }

    @Override
    public String toString() {
        return String.format("SignalOutcome[%s] %s %s %s: %s (%.2f%%, %.1fR)",
                signalId != null ? signalId.substring(0, 8) : "?",
                direction, category, familyId,
                outcome, pnlPct, rMultiple);
    }

    // ======================== EXECUTION DTO ========================

    /**
     * Trade execution details from trade execution module
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TradeExecution {
        private Instant enteredAt;
        private Instant closedAt;
        private double entryPrice;
        private double exitPrice;
        private double positionSize;
        private String exitReason;
        private double maxPrice;
        private double minPrice;
        private boolean target1Hit;
        private boolean target2Hit;
        private boolean target3Hit;
        private boolean stopLossHit;
    }
}
