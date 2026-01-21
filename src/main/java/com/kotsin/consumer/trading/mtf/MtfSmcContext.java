package com.kotsin.consumer.trading.mtf;

import com.kotsin.consumer.trading.smc.SmcContext;
import com.kotsin.consumer.trading.smc.SmcContext.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MtfSmcContext - Multi-Timeframe Smart Money Concepts Context
 *
 * THIS IS HOW REAL INSTITUTIONAL TRADERS ANALYZE:
 *
 * 1. HTF (Higher Timeframe) Analysis - THE BIAS
 *    - Daily/4H for overall market direction
 *    - Daily/4H for major Order Blocks (where institutions placed orders)
 *    - Daily/4H for premium/discount zones
 *    - Daily swing high/low for range
 *
 * 2. LTF (Lower Timeframe) Analysis - THE ENTRY
 *    - 15m/5m for liquidity sweeps (stop hunts)
 *    - 15m/5m for CHoCH (entry confirmation)
 *    - 15m/5m for precise entry at OB
 *
 * 3. THE CONFLUENCE - ALL MUST ALIGN
 *    - HTF bias bullish → only look for LONG
 *    - Price at HTF demand zone
 *    - LTF liquidity swept (stops taken)
 *    - LTF CHoCH confirms direction
 *    → ENTER LONG
 *
 * WHY THIS MATTERS:
 * - HTF gives DIRECTION (don't fight the trend)
 * - HTF gives KEY LEVELS (where smart money operates)
 * - LTF gives TIMING (when to pull the trigger)
 * - Without all three, you're gambling
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MtfSmcContext {

    // ============ TIMEFRAME IDENTIFIERS ============

    /**
     * HTF timeframe used (e.g., "4h", "daily")
     */
    private String htfTimeframe;

    /**
     * LTF timeframe used (e.g., "15m", "5m")
     */
    private String ltfTimeframe;

    // ============ HTF CONTEXT (BIAS & MAJOR LEVELS) ============

    /**
     * HTF market structure bias - THIS IS THE TREND
     * Only trade in this direction!
     */
    private MarketBias htfBias;

    /**
     * Is HTF structure bullish? (HH + HL pattern on HTF)
     */
    private boolean htfStructureBullish;

    /**
     * HTF Order Blocks - WHERE smart money placed orders
     * These are the MAJOR zones to trade from
     */
    private OrderBlock htfNearestDemand;
    private OrderBlock htfNearestSupply;

    /**
     * Is price at HTF POI (Point of Interest)?
     */
    private boolean atHtfDemandZone;
    private boolean atHtfSupplyZone;
    private boolean atHtfFvg;

    /**
     * HTF FVG (major imbalances)
     */
    private FairValueGap htfNearestFvg;

    /**
     * Last HTF structure break (for trend change detection)
     */
    private StructureBreak htfLastStructureBreak;

    // ============ PREMIUM/DISCOUNT (FROM DAILY RANGE) ============

    /**
     * Daily swing high - upper boundary of institutional range
     */
    private double dailySwingHigh;

    /**
     * Daily swing low - lower boundary of institutional range
     */
    private double dailySwingLow;

    /**
     * Equilibrium (50% of daily range)
     */
    private double equilibrium;

    /**
     * Is price in premium zone? (Above 50% = where to SELL)
     */
    private boolean inPremium;

    /**
     * Is price in discount zone? (Below 50% = where to BUY)
     */
    private boolean inDiscount;

    /**
     * Position in daily range (0.0 = at low, 1.0 = at high)
     */
    private double rangePosition;

    // ============ LTF CONTEXT (ENTRY TRIGGERS) ============

    /**
     * LTF market bias (should align with HTF for entry)
     */
    private MarketBias ltfBias;

    /**
     * Is LTF structure bullish?
     */
    private boolean ltfStructureBullish;

    /**
     * LTF Order Blocks (for precise entry)
     */
    private OrderBlock ltfNearestDemand;
    private OrderBlock ltfNearestSupply;

    /**
     * Is price in LTF Order Block?
     */
    private boolean inLtfOrderBlock;
    private OrderBlock currentLtfOrderBlock;

    /**
     * LTF FVGs (for entry refinement)
     */
    private FairValueGap ltfNearestFvg;
    private boolean inLtfFvg;

    // ============ LIQUIDITY (LTF - THE ENTRY SIGNAL!) ============

    /**
     * Was liquidity just swept on LTF? THIS IS THE KEY ENTRY SIGNAL!
     */
    private boolean ltfLiquidityJustSwept;

    /**
     * The sweep details
     */
    private LiquiditySweep ltfLastSweep;

    /**
     * Buy-side liquidity levels (where to take profit for LONG)
     */
    private java.util.List<LiquidityLevel> buySideLiquidity;

    /**
     * Sell-side liquidity levels (where to take profit for SHORT)
     */
    private java.util.List<LiquidityLevel> sellSideLiquidity;

    // ============ STRUCTURE BREAKS (LTF - CONFIRMATION) ============

    /**
     * Last LTF structure break
     */
    private StructureBreak ltfLastStructureBreak;

    /**
     * Candles since LTF structure break
     */
    private int ltfCandlesSinceStructureBreak;

    /**
     * Was there a recent CHoCH on LTF? (Confirmation signal)
     */
    private boolean ltfRecentChoch;

    /**
     * Was there a recent BOS on LTF? (Continuation signal)
     */
    private boolean ltfRecentBos;

    // ============ ALIGNMENT FLAGS ============

    /**
     * Is HTF and LTF bias aligned?
     */
    private boolean biasAligned;

    /**
     * Overall confluence score (0-10)
     */
    private int mtfConfluenceScore;

    // ============ HELPER METHODS ============

    /**
     * Is this a valid LONG setup with proper MTF alignment?
     *
     * Requirements:
     * 1. HTF bias bullish (or recent bullish CHoCH)
     * 2. Price in discount zone (below 50%)
     * 3. Price at HTF demand zone or FVG
     * 4. LTF liquidity swept (sell-side)
     * 5. LTF structure confirms (bullish)
     */
    public boolean isValidMtfLongSetup() {
        // HTF must be bullish
        boolean htfBullish = htfBias == MarketBias.BULLISH ||
                (htfLastStructureBreak != null &&
                        htfLastStructureBreak.getType() == StructureType.CHOCH &&
                        htfLastStructureBreak.isBullish());

        // Must be in discount
        if (!inDiscount) return false;

        // Must be at HTF POI
        boolean atHtfPoi = atHtfDemandZone || atHtfFvg;
        if (!atHtfPoi) return false;

        // LTF must have swept sell-side liquidity
        if (!ltfLiquidityJustSwept || ltfLastSweep == null || ltfLastSweep.isBuySide()) {
            return false;
        }

        // LTF structure should confirm
        boolean ltfConfirms = ltfStructureBullish || ltfRecentChoch;

        return htfBullish && ltfConfirms;
    }

    /**
     * Is this a valid SHORT setup with proper MTF alignment?
     */
    public boolean isValidMtfShortSetup() {
        // HTF must be bearish
        boolean htfBearish = htfBias == MarketBias.BEARISH ||
                (htfLastStructureBreak != null &&
                        htfLastStructureBreak.getType() == StructureType.CHOCH &&
                        !htfLastStructureBreak.isBullish());

        // Must be in premium
        if (!inPremium) return false;

        // Must be at HTF POI
        boolean atHtfPoi = atHtfSupplyZone || atHtfFvg;
        if (!atHtfPoi) return false;

        // LTF must have swept buy-side liquidity
        if (!ltfLiquidityJustSwept || ltfLastSweep == null || !ltfLastSweep.isBuySide()) {
            return false;
        }

        // LTF structure should confirm
        boolean ltfConfirms = !ltfStructureBullish || ltfRecentChoch;

        return htfBearish && ltfConfirms;
    }

    /**
     * Get stop loss for LONG (below LTF swept low)
     */
    public double getLongStopLoss() {
        if (ltfLastSweep != null && !ltfLastSweep.isBuySide()) {
            return ltfLastSweep.getSweepPrice() * 0.999;
        }
        if (ltfNearestDemand != null) {
            return ltfNearestDemand.getBottom() * 0.999;
        }
        if (htfNearestDemand != null) {
            return htfNearestDemand.getBottom() * 0.999;
        }
        return 0;
    }

    /**
     * Get stop loss for SHORT (above LTF swept high)
     */
    public double getShortStopLoss() {
        if (ltfLastSweep != null && ltfLastSweep.isBuySide()) {
            return ltfLastSweep.getSweepPrice() * 1.001;
        }
        if (ltfNearestSupply != null) {
            return ltfNearestSupply.getTop() * 1.001;
        }
        if (htfNearestSupply != null) {
            return htfNearestSupply.getTop() * 1.001;
        }
        return 0;
    }

    /**
     * Get target for LONG (nearest buy-side liquidity or HTF supply)
     */
    public double getLongTarget() {
        // First target: nearest unswept buy-side liquidity
        if (buySideLiquidity != null && !buySideLiquidity.isEmpty()) {
            for (LiquidityLevel level : buySideLiquidity) {
                if (!level.isSwept()) {
                    return level.getPrice();
                }
            }
        }
        // Second target: HTF supply zone
        if (htfNearestSupply != null) {
            return htfNearestSupply.getBottom();
        }
        // Fallback: equilibrium
        return equilibrium;
    }

    /**
     * Get target for SHORT (nearest sell-side liquidity or HTF demand)
     */
    public double getShortTarget() {
        // First target: nearest unswept sell-side liquidity
        if (sellSideLiquidity != null && !sellSideLiquidity.isEmpty()) {
            for (LiquidityLevel level : sellSideLiquidity) {
                if (!level.isSwept()) {
                    return level.getPrice();
                }
            }
        }
        // Second target: HTF demand zone
        if (htfNearestDemand != null) {
            return htfNearestDemand.getTop();
        }
        // Fallback: equilibrium
        return equilibrium;
    }

    /**
     * Calculate MTF confluence score (0-10)
     */
    public int calculateConfluenceScore() {
        int score = 0;

        // HTF bias clear (+2)
        if (htfBias == MarketBias.BULLISH || htfBias == MarketBias.BEARISH) {
            score += 2;
        }

        // Bias aligned (+2)
        if (biasAligned) {
            score += 2;
        }

        // At HTF POI (+2)
        if (atHtfDemandZone || atHtfSupplyZone || atHtfFvg) {
            score += 2;
        }

        // In correct zone (+1)
        if (inDiscount || inPremium) {
            score += 1;
        }

        // LTF liquidity swept (+2)
        if (ltfLiquidityJustSwept) {
            score += 2;
        }

        // LTF structure confirms (+1)
        if (ltfRecentChoch || ltfRecentBos) {
            score += 1;
        }

        this.mtfConfluenceScore = Math.min(10, score);
        return this.mtfConfluenceScore;
    }

    /**
     * Create empty context
     */
    public static MtfSmcContext empty() {
        return MtfSmcContext.builder()
                .htfBias(MarketBias.UNKNOWN)
                .ltfBias(MarketBias.UNKNOWN)
                .htfTimeframe("4h")
                .ltfTimeframe("15m")
                .build();
    }
}
