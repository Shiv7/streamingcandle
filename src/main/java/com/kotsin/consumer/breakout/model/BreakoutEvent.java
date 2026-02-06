package com.kotsin.consumer.breakout.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * BreakoutEvent - Represents a detected breakout or retest event.
 *
 * Tracks:
 * - Level being broken/tested
 * - Breakout direction and strength
 * - Volume confirmation
 * - Retest status
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BreakoutEvent {

    private String symbol;
    private Instant timestamp;
    private String timeframe;

    // ==================== BREAKOUT DETAILS ====================
    private BreakoutType type;
    private BreakoutDirection direction;
    private double breakoutLevel;        // Level that was broken
    private double breakoutPrice;        // Price at breakout
    private double penetration;          // How far past level
    private double penetrationPercent;

    // ==================== LEVEL SOURCE ====================
    private LevelSource levelSource;
    private String levelDescription;

    // ==================== CONFIRMATION ====================
    private boolean volumeConfirmed;     // Above average volume
    private double volumeRatio;          // Volume vs average
    private boolean closeConfirmed;      // Candle closed beyond level
    private boolean momentumConfirmed;   // RSI/MACD supports
    private int confirmationCount;       // Total confirmations

    // ==================== STRENGTH ====================
    private BreakoutStrength strength;
    private double strengthScore;        // 0-100

    // ==================== RETEST ====================
    private boolean hasRetested;
    private Instant retestTime;
    private double retestPrice;
    private boolean retestHeld;          // Level held as support/resistance
    private RetestQuality retestQuality;

    // ==================== FOLLOW-THROUGH ====================
    private double maxMove;              // Maximum move after breakout
    private double maxMovePercent;
    private boolean isFailed;            // Breakout failed (returned)
    private Instant failedTime;

    // ==================== CONTEXT ====================
    private boolean isWithTrend;         // Breakout in trend direction
    private boolean hasMultipleTimeframeConfluence;
    private int touchesBeforeBreak;      // Times level was tested

    // ==================== ENUMS ====================

    public enum BreakoutType {
        BREAKOUT,        // Fresh break of level
        RETEST,          // Return to broken level
        FAILED_BREAKOUT, // Breakout that failed
        FAKEOUT          // Quick break and immediate reversal
    }

    public enum BreakoutDirection {
        BULLISH,         // Breaking above resistance
        BEARISH          // Breaking below support
    }

    public enum LevelSource {
        SWING_HIGH,
        SWING_LOW,
        RESISTANCE,
        SUPPORT,
        VWAP,
        EMA,
        OPENING_RANGE,
        PREVIOUS_DAY,
        PREVIOUS_WEEK,
        ORDER_BLOCK,
        FVG,
        ROUND_NUMBER
    }

    public enum BreakoutStrength {
        STRONG,          // High volume, momentum, clean break
        MODERATE,        // Some confirmation
        WEAK             // Marginal break, low conviction
    }

    public enum RetestQuality {
        PERFECT,         // Clean touch and bounce
        GOOD,            // Small penetration, then bounce
        POOR,            // Significant penetration
        FAILED           // Retest failed, level didn't hold
    }

    // ==================== HELPER METHODS ====================

    public boolean isBullish() {
        return direction == BreakoutDirection.BULLISH;
    }

    public boolean isBearish() {
        return direction == BreakoutDirection.BEARISH;
    }

    public boolean isStrong() {
        return strength == BreakoutStrength.STRONG;
    }

    public boolean isConfirmed() {
        return confirmationCount >= 2 && closeConfirmed;
    }

    public boolean isFakeout() {
        return type == BreakoutType.FAKEOUT;
    }

    public boolean isActionable() {
        return isConfirmed() && !isFailed && strength != BreakoutStrength.WEAK;
    }

    public boolean hasGoodRetest() {
        return hasRetested && retestHeld &&
            (retestQuality == RetestQuality.PERFECT || retestQuality == RetestQuality.GOOD);
    }

    public double getRiskReward(double targetPrice) {
        if (direction == BreakoutDirection.BULLISH) {
            double risk = breakoutPrice - breakoutLevel;
            double reward = targetPrice - breakoutPrice;
            return risk > 0 ? reward / risk : 0;
        } else {
            double risk = breakoutLevel - breakoutPrice;
            double reward = breakoutPrice - targetPrice;
            return risk > 0 ? reward / risk : 0;
        }
    }

    public void markAsFailed(Instant time) {
        this.isFailed = true;
        this.failedTime = time;
        this.type = BreakoutType.FAILED_BREAKOUT;
    }

    public void recordRetest(Instant time, double price, boolean held, RetestQuality quality) {
        this.hasRetested = true;
        this.retestTime = time;
        this.retestPrice = price;
        this.retestHeld = held;
        this.retestQuality = quality;
        this.type = BreakoutType.RETEST;
    }

    public void updateMaxMove(double currentPrice) {
        if (direction == BreakoutDirection.BULLISH) {
            double move = currentPrice - breakoutPrice;
            if (move > maxMove) {
                maxMove = move;
                maxMovePercent = breakoutPrice > 0 ? move / breakoutPrice * 100 : 0;
            }
        } else {
            double move = breakoutPrice - currentPrice;
            if (move > maxMove) {
                maxMove = move;
                maxMovePercent = breakoutPrice > 0 ? move / breakoutPrice * 100 : 0;
            }
        }
    }
}
