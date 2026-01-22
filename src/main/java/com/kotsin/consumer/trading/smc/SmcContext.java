package com.kotsin.consumer.trading.smc;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * SmcContext - Smart Money Concepts context for trading decisions
 *
 * Contains:
 * - Market Structure (HH/HL/LH/LL, trend direction)
 * - Structure Breaks (BOS/CHoCH)
 * - Order Blocks (supply/demand zones)
 * - Fair Value Gaps (imbalances)
 * - Liquidity Levels (stop hunt zones)
 * - Premium/Discount Zones
 *
 * This is how REAL institutional traders analyze markets.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmcContext {

    // ============ MARKET STRUCTURE ============

    /**
     * Current market structure bias
     */
    private MarketBias marketBias;

    /**
     * Higher timeframe bias (for MTF confluence)
     */
    private MarketBias htfBias;

    /**
     * Recent swing points
     */
    private List<SwingPoint> recentSwings;

    /**
     * Last confirmed structure break
     */
    private StructureBreak lastStructureBreak;

    /**
     * Is structure currently bullish? (HH + HL pattern)
     */
    private boolean structureBullish;

    /**
     * Candles since last structure break
     */
    private int candlesSinceStructureBreak;

    // ============ ORDER BLOCKS ============

    /**
     * Active demand zones (bullish order blocks)
     */
    private List<OrderBlock> demandZones;

    /**
     * Active supply zones (bearish order blocks)
     */
    private List<OrderBlock> supplyZones;

    /**
     * Nearest demand zone below current price
     */
    private OrderBlock nearestDemand;

    /**
     * Nearest supply zone above current price
     */
    private OrderBlock nearestSupply;

    /**
     * Is price currently in an order block?
     */
    private boolean inOrderBlock;

    /**
     * The order block price is currently in (if any)
     */
    private OrderBlock currentOrderBlock;

    // ============ FAIR VALUE GAPS ============

    /**
     * Unfilled bullish FVGs (gaps to fill from below)
     */
    private List<FairValueGap> bullishFvgs;

    /**
     * Unfilled bearish FVGs (gaps to fill from above)
     */
    private List<FairValueGap> bearishFvgs;

    /**
     * Is price in an FVG?
     */
    private boolean inFvg;

    /**
     * Nearest unfilled FVG
     */
    private FairValueGap nearestFvg;

    // ============ LIQUIDITY ============

    /**
     * Buy-side liquidity levels (highs where stops are)
     */
    private List<LiquidityLevel> buySideLiquidity;

    /**
     * Sell-side liquidity levels (lows where stops are)
     */
    private List<LiquidityLevel> sellSideLiquidity;

    /**
     * Recent liquidity sweeps
     */
    private List<LiquiditySweep> recentSweeps;

    /**
     * Was liquidity just swept? (entry signal!)
     */
    private boolean liquidityJustSwept;

    /**
     * The most recent liquidity sweep
     */
    private LiquiditySweep lastSweep;

    // ============ PREMIUM/DISCOUNT ============

    /**
     * Current range equilibrium (50% level)
     */
    private double equilibrium;

    /**
     * Is price in premium zone? (above 50% = sell zone)
     */
    private boolean inPremium;

    /**
     * Is price in discount zone? (below 50% = buy zone)
     */
    private boolean inDiscount;

    /**
     * Position in range (0.0 = low, 1.0 = high)
     */
    private double rangePosition;

    // ============ ENUMS ============

    public enum MarketBias {
        BULLISH,    // HH + HL structure
        BEARISH,    // LH + LL structure
        RANGING,    // No clear structure
        UNKNOWN
    }

    public enum StructureType {
        BOS,    // Break of Structure (trend continuation)
        CHOCH   // Change of Character (potential reversal)
    }

    public enum SwingType {
        HIGHER_HIGH,
        HIGHER_LOW,
        LOWER_HIGH,
        LOWER_LOW,
        EQUAL_HIGH,
        EQUAL_LOW
    }

    // ============ INNER CLASSES ============

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SwingPoint {
        private double price;
        private long timestamp;
        private int candleIndex;
        private boolean isHigh;        // true = swing high, false = swing low
        private SwingType swingType;
        private boolean broken;        // Has this swing been broken?
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StructureBreak {
        private StructureType type;    // BOS or CHoCH
        private double breakPrice;
        private double swingPrice;     // The swing that was broken
        private long timestamp;
        private boolean bullish;       // Break direction
        private int strength;          // 1-3 based on candle size/momentum
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderBlock {
        private double top;
        private double bottom;
        private double midpoint;
        private long timestamp;
        private int candleIndex;
        private boolean isBullish;     // true = demand, false = supply
        private boolean tested;        // Has price returned to test it?
        private int testCount;         // How many times tested
        private boolean broken;        // Has it been broken (invalidated)?
        private double strength;       // Based on move away from OB
        private String timeframe;      // Which TF this OB is from

        public boolean contains(double price) {
            return price >= bottom && price <= top;
        }

        public double getSize() {
            return top - bottom;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FairValueGap {
        private double top;
        private double bottom;
        private long timestamp;
        private int candleIndex;
        private boolean isBullish;     // true = gap up (fill from above), false = gap down
        private boolean filled;        // Has price filled this gap?
        private double fillPercentage; // How much of the gap has been filled (0-100)
        private String timeframe;

        public boolean contains(double price) {
            return price >= bottom && price <= top;
        }

        public double getMidpoint() {
            return (top + bottom) / 2;
        }

        public double getSize() {
            return top - bottom;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LiquidityLevel {
        private double price;
        private long timestamp;
        private int candleIndex;
        private boolean isBuySide;     // true = above price (buy stops), false = below (sell stops)
        private boolean swept;         // Has this liquidity been taken?
        private int touchCount;        // How many times price approached (more = more liquidity)
        private double strength;       // Based on touch count and time
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LiquiditySweep {
        private double liquidityLevel; // The level that was swept
        private double sweepPrice;     // How far price went past
        private double closePrice;     // Where price closed after sweep
        private long timestamp;
        private boolean isBuySide;     // Swept buy-side (above) or sell-side (below)
        private boolean validReversal; // Did price reverse after sweep?
        private double wickSize;       // Size of the wick (manipulation)
    }

    // ============ HELPER METHODS ============

    /**
     * Is this a valid LONG setup?
     * - Structure bullish or CHoCH to bullish
     * - Price in discount zone
     * - Near demand zone or FVG
     * - Liquidity swept below
     */
    public boolean isValidLongSetup() {
        return (structureBullish || (lastStructureBreak != null && lastStructureBreak.isBullish()))
                && inDiscount
                && (nearestDemand != null || (nearestFvg != null && nearestFvg.isBullish()))
                && (liquidityJustSwept && lastSweep != null && !lastSweep.isBuySide());
    }

    /**
     * Is this a valid SHORT setup?
     * - Structure bearish or CHoCH to bearish
     * - Price in premium zone
     * - Near supply zone or FVG
     * - Liquidity swept above
     */
    public boolean isValidShortSetup() {
        return (!structureBullish || (lastStructureBreak != null && !lastStructureBreak.isBullish()))
                && inPremium
                && (nearestSupply != null || (nearestFvg != null && !nearestFvg.isBullish()))
                && (liquidityJustSwept && lastSweep != null && lastSweep.isBuySide());
    }

    /**
     * Get entry zone for LONG
     */
    public double getLongEntryZone() {
        if (nearestDemand != null) {
            return nearestDemand.getMidpoint();
        }
        if (nearestFvg != null && nearestFvg.isBullish()) {
            return nearestFvg.getMidpoint();
        }
        return 0;
    }

    /**
     * Get entry zone for SHORT
     */
    public double getShortEntryZone() {
        if (nearestSupply != null) {
            return nearestSupply.getMidpoint();
        }
        if (nearestFvg != null && !nearestFvg.isBullish()) {
            return nearestFvg.getMidpoint();
        }
        return 0;
    }

    /**
     * Get stop loss for LONG (below the swept low)
     */
    public double getLongStopLoss() {
        if (lastSweep != null && !lastSweep.isBuySide()) {
            return lastSweep.getSweepPrice() * 0.999; // Slightly below sweep
        }
        if (nearestDemand != null) {
            return nearestDemand.getBottom() * 0.999;
        }
        return 0;
    }

    /**
     * Get stop loss for SHORT (above the swept high)
     */
    public double getShortStopLoss() {
        if (lastSweep != null && lastSweep.isBuySide()) {
            return lastSweep.getSweepPrice() * 1.001; // Slightly above sweep
        }
        if (nearestSupply != null) {
            return nearestSupply.getTop() * 1.001;
        }
        return 0;
    }

    /**
     * Get confluence score (0-10)
     */
    public int getConfluenceScore() {
        int score = 0;

        // Structure aligned (+2)
        if (structureBullish || !structureBullish) score += 2; // Just having clear structure

        // HTF aligned (+2)
        if (htfBias == marketBias) score += 2;

        // In discount/premium zone (+1)
        if (inDiscount || inPremium) score += 1;

        // At order block (+2)
        if (inOrderBlock || currentOrderBlock != null) score += 2;

        // Liquidity swept (+2)
        if (liquidityJustSwept) score += 2;

        // Recent CHoCH (+1)
        if (lastStructureBreak != null && lastStructureBreak.getType() == StructureType.CHOCH
                && candlesSinceStructureBreak < 5) score += 1;

        return Math.min(10, score);
    }
}
