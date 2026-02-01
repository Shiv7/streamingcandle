package com.kotsin.consumer.smc.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * FairValueGap (FVG) - Smart Money Concept imbalance detection.
 *
 * An FVG is created when there's a gap between candle 1's low/high
 * and candle 3's high/low, with candle 2 being the impulse candle.
 *
 * Bullish FVG: Candle 1 high < Candle 3 low (gap to fill below)
 * Bearish FVG: Candle 1 low > Candle 3 high (gap to fill above)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FairValueGap {

    private String symbol;
    private Instant timestamp;
    private String timeframe;

    // ==================== FVG ZONE ====================
    private FVGType type;
    private double high;          // FVG zone high
    private double low;           // FVG zone low
    private double midpoint;      // (high + low) / 2
    private double gapSize;       // high - low

    // ==================== CONTEXT CANDLES ====================
    private double candle1High;
    private double candle1Low;
    private double candle2Open;
    private double candle2High;
    private double candle2Low;
    private double candle2Close;
    private double candle3High;
    private double candle3Low;

    // ==================== VALIDATION ====================
    private boolean isValid;      // FVG still valid (not filled)
    private boolean isPartialFill;
    private double fillPercent;   // How much of FVG is filled
    private Instant createdAt;
    private Instant filledAt;

    // ==================== STRENGTH ====================
    private double impulseSize;   // Size of candle 2 (impulse)
    private double impulsePercent;
    private double volume;        // Volume of impulse candle
    private FVGStrength strength;
    private boolean isConsequent; // Nested inside larger FVG

    // ==================== TRADING ====================
    private double entryLevel;    // 50% of FVG typically
    private double stopLevel;     // Beyond FVG
    private boolean isWithTrend;  // FVG in trend direction

    // ==================== ENUMS ====================

    public enum FVGType {
        BULLISH,    // Gap below current price (support)
        BEARISH     // Gap above current price (resistance)
    }

    public enum FVGStrength {
        STRONG,     // Large gap, high volume, with structure break
        MODERATE,   // Decent gap size
        WEAK        // Small gap, low volume
    }

    // ==================== HELPER METHODS ====================

    public boolean isBullish() {
        return type == FVGType.BULLISH;
    }

    public boolean isBearish() {
        return type == FVGType.BEARISH;
    }

    public boolean isStrong() {
        return strength == FVGStrength.STRONG;
    }

    public double getGapSizePercent() {
        return midpoint > 0 ? gapSize / midpoint * 100 : 0;
    }

    public boolean isPriceInGap(double price) {
        return price >= low && price <= high;
    }

    public boolean isGapFilled() {
        return fillPercent >= 100;
    }

    public boolean is50PercentFilled() {
        return fillPercent >= 50;
    }

    public double getDistanceFromGap(double price) {
        if (isPriceInGap(price)) return 0;
        return price > high ? price - high : low - price;
    }

    public double getDistancePercent(double price) {
        return midpoint > 0 ? getDistanceFromGap(price) / midpoint * 100 : 0;
    }

    /**
     * Update fill status based on current price.
     */
    public void updateFillStatus(double price) {
        if (!isValid) return;

        if (type == FVGType.BULLISH) {
            // Bullish FVG fills from above (price drops into it)
            if (price <= low) {
                fillPercent = 100;
                isValid = false;
            } else if (price < high) {
                fillPercent = (high - price) / gapSize * 100;
                isPartialFill = true;
            }
        } else {
            // Bearish FVG fills from below (price rises into it)
            if (price >= high) {
                fillPercent = 100;
                isValid = false;
            } else if (price > low) {
                fillPercent = (price - low) / gapSize * 100;
                isPartialFill = true;
            }
        }
    }
}
