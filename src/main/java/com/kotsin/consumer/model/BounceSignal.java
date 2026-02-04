package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * BounceSignal - Represents a price bounce at a pivot level.
 *
 * A bounce signal indicates potential reversal when price:
 * 1. Touches a support/resistance level
 * 2. Shows rejection (reversal candle pattern)
 * 3. Has confluence from multiple timeframes
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BounceSignal {

    private String symbol;
    private BounceType type;
    private double level;           // The pivot level price
    private String levelName;       // "Daily S1", "Weekly Pivot", etc.
    private int confluence;         // Number of timeframes with levels here
    private double confidence;      // 0-1 confidence score
    private String message;         // Human-readable description

    // Price context
    private double touchPrice;      // Price that touched the level
    private double currentPrice;    // Current price after bounce
    private double bouncePercent;   // Percentage moved from touch

    // Timing
    private Instant signalTime;
    private int candlesSinceBounce;

    /**
     * Type of bounce signal.
     */
    public enum BounceType {
        BULLISH_BOUNCE,    // Price bounced UP from support
        BEARISH_BOUNCE,    // Price bounced DOWN from resistance
        BULLISH_BREAKOUT,  // Price broke above resistance
        BEARISH_BREAKOUT   // Price broke below support
    }

    /**
     * Check if this is a reversal signal (bounce, not breakout).
     */
    public boolean isReversalSignal() {
        return type == BounceType.BULLISH_BOUNCE || type == BounceType.BEARISH_BOUNCE;
    }

    /**
     * Check if this is a breakout signal.
     */
    public boolean isBreakoutSignal() {
        return type == BounceType.BULLISH_BREAKOUT || type == BounceType.BEARISH_BREAKOUT;
    }

    /**
     * Check if signal is bullish (long opportunity).
     */
    public boolean isBullish() {
        return type == BounceType.BULLISH_BOUNCE || type == BounceType.BULLISH_BREAKOUT;
    }

    /**
     * Check if signal is bearish (short opportunity).
     */
    public boolean isBearish() {
        return type == BounceType.BEARISH_BOUNCE || type == BounceType.BEARISH_BREAKOUT;
    }

    /**
     * Check if this is a high-confidence signal.
     */
    public boolean isHighConfidence() {
        return confidence >= 0.7 && confluence >= 2;
    }

    /**
     * Get suggested stop loss level based on bounce type.
     */
    public double getSuggestedStopLoss(double atrMultiplier) {
        // Stop beyond the level that was tested
        double buffer = level * 0.002; // 0.2% buffer

        if (isBullish()) {
            return level - buffer; // Stop below support
        } else {
            return level + buffer; // Stop above resistance
        }
    }

    /**
     * Get suggested target based on risk-reward.
     */
    public double getSuggestedTarget(double riskRewardRatio) {
        double risk = Math.abs(currentPrice - getSuggestedStopLoss(1.0));
        double reward = risk * riskRewardRatio;

        if (isBullish()) {
            return currentPrice + reward;
        } else {
            return currentPrice - reward;
        }
    }

    /**
     * Get signal quality score (0-100).
     */
    public int getQualityScore() {
        int score = 0;

        // Confluence contribution (max 40)
        score += Math.min(confluence * 10, 40);

        // Confidence contribution (max 30)
        score += (int) (confidence * 30);

        // Bounce quality (max 20)
        if (Math.abs(bouncePercent) >= 0.5) score += 10;
        if (Math.abs(bouncePercent) >= 1.0) score += 10;

        // Fresh signal bonus (max 10)
        if (candlesSinceBounce <= 2) score += 10;
        else if (candlesSinceBounce <= 5) score += 5;

        return Math.min(score, 100);
    }

    /**
     * Get trading recommendation.
     */
    public String getRecommendation() {
        if (!isHighConfidence()) {
            return "WATCH - Low confidence (" + String.format("%.0f%%", confidence * 100) + ")";
        }

        String direction = isBullish() ? "LONG" : "SHORT";
        String trigger = isReversalSignal() ? "REVERSAL" : "BREAKOUT";

        return String.format("%s %s at %s (confluence: %d, quality: %d)",
            direction, trigger, levelName, confluence, getQualityScore());
    }
}
