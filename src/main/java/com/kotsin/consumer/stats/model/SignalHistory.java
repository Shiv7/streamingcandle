package com.kotsin.consumer.stats.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * SignalHistory - Historical record of a trading signal.
 *
 * Stores complete signal lifecycle:
 * - Generation details
 * - Entry/Exit prices
 * - Performance metrics
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "signal_history")
@CompoundIndex(name = "symbol_date_idx", def = "{'symbol': 1, 'generatedAt': -1}")
public class SignalHistory {

    @Id
    private String id;

    // ==================== IDENTIFICATION ====================
    @Indexed
    private String symbol;
    private String signalId;             // Unique signal identifier
    private String signalType;           // VCP, IPU, PIVOT, etc.
    private String timeframe;

    // ==================== TIMING ====================
    @Indexed
    private Instant generatedAt;
    private Instant entryTime;
    private Instant exitTime;
    private long durationMs;             // Time in position
    private int durationBars;            // Bars in position

    // ==================== SIGNAL DETAILS ====================
    private SignalDirection direction;
    private double confidence;           // Signal confidence (0-100)
    private double quality;              // Signal quality score
    private String triggerReason;        // What triggered the signal

    // ==================== PRICES ====================
    private double signalPrice;          // Price when signal generated
    private double entryPrice;           // Actual entry price
    private double exitPrice;            // Actual exit price
    private double targetPrice;          // Target price
    private double stopLoss;             // Stop loss price

    // ==================== PERFORMANCE ====================
    private double profitLoss;           // Absolute P&L
    private double profitLossPercent;    // P&L as percentage
    private double maxProfit;            // Maximum favorable excursion
    private double maxLoss;              // Maximum adverse excursion
    private double riskRewardAchieved;   // Actual R:R
    private double riskRewardPlanned;    // Planned R:R
    private SignalOutcome outcome;

    // ==================== CONTEXT ====================
    private double fudkiiScore;          // FUDKII at signal time
    private double rsi;                  // RSI at signal time
    private double vwap;                 // VWAP at signal time
    private String marketRegime;         // TRENDING, RANGING, etc.
    private String sessionSegment;       // OPENING, MORNING, etc.
    private boolean withTrend;           // Signal in trend direction

    // ==================== VALIDATION ====================
    private boolean volumeConfirmed;
    private boolean priceConfirmed;
    private boolean momentumConfirmed;
    private int confirmationCount;

    // ==================== NOTES ====================
    private String notes;
    private String exitReason;           // TARGET_HIT, STOP_HIT, TIME_EXIT, etc.

    // ==================== ENUMS ====================

    public enum SignalDirection {
        LONG,
        SHORT
    }

    public enum SignalOutcome {
        WIN,             // Hit target
        LOSS,            // Hit stop loss
        BREAKEVEN,       // Exited at entry
        PARTIAL,         // Partial target hit
        TIMEOUT,         // Time-based exit
        MANUAL           // Manual exit
    }

    // ==================== HELPER METHODS ====================

    public boolean isWin() {
        return outcome == SignalOutcome.WIN || profitLoss > 0;
    }

    public boolean isLoss() {
        return outcome == SignalOutcome.LOSS || profitLoss < 0;
    }

    public boolean isLong() {
        return direction == SignalDirection.LONG;
    }

    public boolean isShort() {
        return direction == SignalDirection.SHORT;
    }

    public void recordEntry(double price, Instant time) {
        this.entryPrice = price;
        this.entryTime = time;
    }

    public void recordExit(double price, Instant time, String reason) {
        this.exitPrice = price;
        this.exitTime = time;
        this.exitReason = reason;
        this.durationMs = entryTime != null ? time.toEpochMilli() - entryTime.toEpochMilli() : 0;

        // Calculate P&L
        if (direction == SignalDirection.LONG) {
            this.profitLoss = price - entryPrice;
        } else {
            this.profitLoss = entryPrice - price;
        }
        this.profitLossPercent = entryPrice > 0 ? profitLoss / entryPrice * 100 : 0;

        // Determine outcome
        if (Math.abs(profitLossPercent) < 0.1) {
            this.outcome = SignalOutcome.BREAKEVEN;
        } else if (profitLoss > 0) {
            this.outcome = SignalOutcome.WIN;
        } else {
            this.outcome = SignalOutcome.LOSS;
        }

        // Calculate achieved R:R
        if (stopLoss > 0 && entryPrice > 0) {
            double risk = Math.abs(entryPrice - stopLoss);
            this.riskRewardAchieved = risk > 0 ? Math.abs(profitLoss) / risk : 0;
        }
    }

    public void updateMaxExcursion(double currentPrice) {
        if (direction == SignalDirection.LONG) {
            double currentProfit = currentPrice - entryPrice;
            if (currentProfit > maxProfit) maxProfit = currentProfit;
            if (currentProfit < -maxLoss) maxLoss = Math.abs(currentProfit);
        } else {
            double currentProfit = entryPrice - currentPrice;
            if (currentProfit > maxProfit) maxProfit = currentProfit;
            if (currentProfit < -maxLoss) maxLoss = Math.abs(currentProfit);
        }
    }

    public static String generateSignalId(String symbol, String type, Instant time) {
        return String.format("%s_%s_%d", symbol, type, time.toEpochMilli());
    }
}
