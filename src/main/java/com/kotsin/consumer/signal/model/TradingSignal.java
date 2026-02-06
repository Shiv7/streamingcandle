package com.kotsin.consumer.signal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * TradingSignal - Complete trading signal with lifecycle tracking.
 *
 * Represents a signal from formation (WATCH) through completion (WIN/LOSS/STOP).
 * Stores all FUDKII scores, entry/exit levels, and outcome for ML training.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Document(collection = "trading_signals")
@CompoundIndexes({
    @CompoundIndex(name = "symbol_state_idx", def = "{'symbol': 1, 'state': 1}"),
    @CompoundIndex(name = "state_created_idx", def = "{'state': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "symbol_created_idx", def = "{'symbol': 1, 'createdAt': -1}")
})
public class TradingSignal {

    @Id
    private String id;

    // ==================== IDENTITY ====================
    private String signalId;           // Unique signal identifier
    private String symbol;
    private String scripCode;
    private String exchange;
    private String companyName;
    private String timeframe;          // Primary timeframe

    // ==================== STATE ====================
    @Indexed
    private SignalState state;

    private SignalState previousState;
    private Instant stateChangedAt;

    // ==================== DIRECTION ====================
    private FudkiiScore.Direction direction;  // BULLISH or BEARISH
    private double directionConfidence;

    // ==================== FUDKII SCORES ====================
    /**
     * FUDKII score at WATCH entry.
     */
    private FudkiiScore watchScore;

    /**
     * FUDKII score at ACTIVE trigger.
     */
    private FudkiiScore triggerScore;

    /**
     * Latest FUDKII score.
     */
    private FudkiiScore currentScore;

    /**
     * Score history for analysis.
     */
    @Builder.Default
    private List<FudkiiScore> scoreHistory = new ArrayList<>();

    // ==================== ENTRY LEVELS ====================
    /**
     * Recommended entry price.
     */
    private double entryPrice;

    /**
     * Stop loss price.
     */
    private double stopLoss;

    /**
     * Target price (primary).
     */
    private double target1;

    /**
     * Target price (secondary).
     */
    private double target2;

    /**
     * Risk-reward ratio.
     */
    private double riskReward;

    /**
     * Position size multiplier (based on signal quality).
     */
    private double positionMultiplier;

    // ==================== ACTUAL EXECUTION ====================
    /**
     * Actual entry price (if traded).
     */
    private Double actualEntry;

    /**
     * Actual exit price.
     */
    private Double actualExit;

    /**
     * Exit reason.
     */
    private ExitReason exitReason;

    // ==================== PRICE TRACKING ====================
    /**
     * Price at WATCH entry.
     */
    private double watchPrice;

    /**
     * Price at ACTIVE trigger.
     */
    private double triggerPrice;

    /**
     * Highest price since trigger (for trailing).
     */
    private double highSinceTrigger;

    /**
     * Lowest price since trigger (for trailing).
     */
    private double lowSinceTrigger;

    /**
     * Current price.
     */
    private double currentPrice;

    // ==================== OUTCOME ====================
    /**
     * Signal outcome.
     */
    private Outcome outcome;

    /**
     * P&L in points.
     */
    private Double pnlPoints;

    /**
     * P&L percentage.
     */
    private Double pnlPercent;

    /**
     * R-multiple (P&L / Risk).
     */
    private Double rMultiple;

    // ==================== TIMING ====================
    @Indexed
    private Instant createdAt;          // IDLE -> WATCH

    private Instant watchedAt;          // Entered WATCH state
    private Instant triggeredAt;        // Entered ACTIVE state
    private Instant completedAt;        // Entered COMPLETE/EXPIRED

    /**
     * Time in WATCH state (seconds).
     */
    private Long watchDurationSec;

    /**
     * Time in ACTIVE state (seconds).
     */
    private Long activeDurationSec;

    /**
     * Expiry time for WATCH state.
     */
    private Instant watchExpiry;

    /**
     * Expiry time for ACTIVE state.
     */
    private Instant activeExpiry;

    // ==================== VALIDATION ====================
    /**
     * MTF (Multi-Timeframe) confirmations count.
     */
    private int mtfConfirmations;

    /**
     * Aligned timeframes.
     */
    @Builder.Default
    private List<String> alignedTimeframes = new ArrayList<>();

    /**
     * Gate checks passed.
     */
    @Builder.Default
    private List<GateCheck> gateChecks = new ArrayList<>();

    // ==================== PAPER TRADE LINK ====================
    /**
     * Paper trade ID (link to paper_trades collection).
     */
    @Indexed
    private String paperTradeId;

    // ==================== METADATA ====================
    /**
     * Tags for categorization.
     */
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    /**
     * Notes/comments.
     */
    private String notes;

    /**
     * Version for optimistic locking.
     */
    private long version;

    // ==================== ENUMS ====================

    public enum ExitReason {
        TARGET_HIT,        // Hit target price
        STOP_HIT,          // Hit stop loss
        TRAILING_STOP,     // Trailing stop triggered
        TIME_EXPIRY,       // Signal expired
        MANUAL,            // Manual exit
        REVERSAL,          // Opposite signal triggered
        INVALIDATION,      // Setup invalidated
        OI_EXHAUSTION      // OI interpretation contradicts signal direction
    }

    public enum Outcome {
        WIN,               // Profitable exit
        LOSS,              // Loss exit
        BREAKEVEN,         // ~0 P&L
        PENDING            // Not yet resolved
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GateCheck {
        private String gateName;
        private boolean passed;
        private String reason;
        private double score;
        private Instant checkedAt;
    }

    // ==================== LIFECYCLE METHODS ====================

    /**
     * Transition to WATCH state.
     */
    public void enterWatch(FudkiiScore score, double price, int watchMinutes) {
        this.previousState = this.state;
        this.state = SignalState.WATCH;
        this.stateChangedAt = Instant.now();
        this.watchedAt = Instant.now();
        this.watchScore = score;
        this.watchPrice = price;
        this.currentScore = score;
        this.currentPrice = price;
        this.watchExpiry = Instant.now().plusSeconds(watchMinutes * 60L);
        this.direction = score.getDirection();
        this.directionConfidence = score.getConfidence();
        addScoreToHistory(score);
    }

    /**
     * Transition to ACTIVE state.
     */
    public void enterActive(FudkiiScore score, double price,
                           double entry, double stop, double target1, double target2) {
        this.previousState = this.state;
        this.state = SignalState.ACTIVE;
        this.stateChangedAt = Instant.now();
        this.triggeredAt = Instant.now();
        this.triggerScore = score;
        this.triggerPrice = price;
        this.currentScore = score;
        this.currentPrice = price;
        this.highSinceTrigger = price;
        this.lowSinceTrigger = price;
        this.entryPrice = entry;
        this.stopLoss = stop;
        this.target1 = target1;
        this.target2 = target2;
        // Guard against division by zero when entry == stop
        double risk = Math.abs(entry - stop);
        this.riskReward = risk > 0.001 ? Math.abs(target1 - entry) / risk : 0;

        if (watchedAt != null) {
            this.watchDurationSec = java.time.Duration.between(watchedAt, triggeredAt).getSeconds();
        }

        // Active expiry: min(now + 4 hours, EOD at 15:30 IST)
        Instant fourHoursLater = Instant.now().plusSeconds(4 * 60 * 60L);
        java.time.ZoneId ist = java.time.ZoneId.of("Asia/Kolkata");
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(ist);
        java.time.ZonedDateTime eod = now.withHour(15).withMinute(30).withSecond(0).withNano(0);
        // If already past EOD today, use tomorrow's EOD
        if (now.isAfter(eod)) {
            eod = eod.plusDays(1);
        }
        Instant eodInstant = eod.toInstant();
        this.activeExpiry = fourHoursLater.isBefore(eodInstant) ? fourHoursLater : eodInstant;
        addScoreToHistory(score);
    }

    /**
     * Transition to COMPLETE state.
     */
    public void enterComplete(ExitReason reason, double exitPrice) {
        this.previousState = this.state;
        this.state = SignalState.COMPLETE;
        this.stateChangedAt = Instant.now();
        this.completedAt = Instant.now();
        this.exitReason = reason;
        this.actualExit = exitPrice;

        if (triggeredAt != null) {
            this.activeDurationSec = java.time.Duration.between(triggeredAt, completedAt).getSeconds();
        }

        // Calculate outcome
        calculateOutcome(exitPrice);
    }

    /**
     * Transition to EXPIRED state.
     */
    public void enterExpired(String reason) {
        this.previousState = this.state;
        this.state = SignalState.EXPIRED;
        this.stateChangedAt = Instant.now();
        this.completedAt = Instant.now();
        this.notes = reason;
        this.outcome = Outcome.PENDING;
    }

    /**
     * Update current price and track high/low.
     */
    public void updatePrice(double price, FudkiiScore score) {
        this.currentPrice = price;
        if (score != null) {
            this.currentScore = score;
        }

        if (state == SignalState.ACTIVE) {
            this.highSinceTrigger = Math.max(highSinceTrigger, price);
            this.lowSinceTrigger = Math.min(lowSinceTrigger, price);
        }

        if (score != null) {
            addScoreToHistory(score);
        }
    }

    private void addScoreToHistory(FudkiiScore score) {
        if (scoreHistory == null) {
            scoreHistory = new ArrayList<>();
        }
        scoreHistory.add(score);
        // Keep last 100 scores
        if (scoreHistory.size() > 100) {
            scoreHistory = new ArrayList<>(scoreHistory.subList(scoreHistory.size() - 100, scoreHistory.size()));
        }
    }

    private void calculateOutcome(double exitPrice) {
        double entry = actualEntry != null ? actualEntry : entryPrice;

        if (direction == FudkiiScore.Direction.BULLISH) {
            pnlPoints = exitPrice - entry;
        } else {
            pnlPoints = entry - exitPrice;
        }

        pnlPercent = (pnlPoints / entry) * 100;

        double risk = Math.abs(entry - stopLoss);
        rMultiple = risk > 0 ? pnlPoints / risk : 0;

        if (pnlPoints > risk * 0.1) {
            outcome = Outcome.WIN;
        } else if (pnlPoints < -risk * 0.1) {
            outcome = Outcome.LOSS;
        } else {
            outcome = Outcome.BREAKEVEN;
        }
    }

    /**
     * Check if WATCH has expired.
     */
    public boolean isWatchExpired() {
        return state == SignalState.WATCH &&
               watchExpiry != null &&
               Instant.now().isAfter(watchExpiry);
    }

    /**
     * Check if ACTIVE has expired.
     */
    public boolean isActiveExpired() {
        return state == SignalState.ACTIVE &&
               activeExpiry != null &&
               Instant.now().isAfter(activeExpiry);
    }

    /**
     * Check if stop loss hit.
     */
    public boolean isStopHit(double price) {
        if (state != SignalState.ACTIVE) return false;

        if (direction == FudkiiScore.Direction.BULLISH) {
            return price <= stopLoss;
        } else {
            return price >= stopLoss;
        }
    }

    /**
     * Check if target hit.
     */
    public boolean isTargetHit(double price) {
        if (state != SignalState.ACTIVE) return false;

        if (direction == FudkiiScore.Direction.BULLISH) {
            return price >= target1;
        } else {
            return price <= target1;
        }
    }

    /**
     * Generate unique signal ID.
     */
    public static String generateSignalId(String symbol, String timeframe) {
        return String.format("%s_%s_%d", symbol, timeframe, System.currentTimeMillis());
    }
}
