package com.kotsin.consumer.trading.smc;

import com.kotsin.consumer.trading.smc.SmcContext.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * SmcAnalyzer - Main orchestrator for Smart Money Concepts analysis
 *
 * Combines:
 * - Market Structure (HH/HL/LH/LL, BOS/CHoCH)
 * - Order Blocks (Supply/Demand zones)
 * - Fair Value Gaps (Imbalances)
 * - Liquidity Sweeps (Stop hunts)
 * - Premium/Discount Zones
 *
 * This produces a unified SmcContext that the trading strategy uses.
 *
 * MTF APPROACH:
 * 1. Analyze HTF (Daily/4H) for bias and major POIs
 * 2. Analyze LTF (15m/5m) for entry triggers
 * 3. Only trade when HTF and LTF align
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmcAnalyzer {

    private final MarketStructureDetector structureDetector;
    private final OrderBlockDetector orderBlockDetector;
    private final FairValueGapDetector fvgDetector;
    private final LiquiditySweepDetector liquidityDetector;

    /**
     * Perform full SMC analysis on price data
     *
     * @param opens      Array of open prices
     * @param highs      Array of high prices
     * @param lows       Array of low prices
     * @param closes     Array of close prices
     * @param timestamps Array of timestamps
     * @param timeframe  Timeframe string (e.g., "5m", "15m", "1h")
     * @return SmcContext with all analysis results
     */
    public SmcContext analyze(double[] opens, double[] highs, double[] lows,
                               double[] closes, long[] timestamps, String timeframe) {
        if (closes.length < 20) {
            log.debug("[SMC] Not enough data for analysis: {} candles", closes.length);
            return SmcContext.builder()
                    .marketBias(MarketBias.UNKNOWN)
                    .structureBullish(false)
                    .demandZones(new ArrayList<>())
                    .supplyZones(new ArrayList<>())
                    .bullishFvgs(new ArrayList<>())
                    .bearishFvgs(new ArrayList<>())
                    .buySideLiquidity(new ArrayList<>())
                    .sellSideLiquidity(new ArrayList<>())
                    .recentSweeps(new ArrayList<>())
                    .build();
        }

        double currentPrice = closes[closes.length - 1];

        // ========== 1. MARKET STRUCTURE ==========
        MarketStructureDetector.MarketStructureResult structureResult =
                structureDetector.detectStructure(highs, lows, closes, timestamps);

        // ========== 2. ORDER BLOCKS ==========
        OrderBlockDetector.OrderBlockResult obResult =
                orderBlockDetector.detectOrderBlocks(opens, highs, lows, closes, timestamps, timeframe);

        // Check if price is in any order block
        OrderBlock currentOB = orderBlockDetector.findCurrentOrderBlock(
                obResult.getAllOrderBlocks(), currentPrice);

        // ========== 3. FAIR VALUE GAPS ==========
        FairValueGapDetector.FVGResult fvgResult =
                fvgDetector.detectFVGs(highs, lows, closes, timestamps, timeframe);

        // Check if price is in any FVG
        FairValueGap currentFVG = fvgDetector.findCurrentFVG(fvgResult.getAllFVGs(), currentPrice);

        // ========== 4. LIQUIDITY ==========
        LiquiditySweepDetector.LiquidityResult liqResult =
                liquidityDetector.detectLiquidity(highs, lows, opens, closes, timestamps);

        // ========== 5. PREMIUM/DISCOUNT ==========
        // Calculate based on recent swing range
        double rangeHigh = findRecentHigh(highs, 50);
        double rangeLow = findRecentLow(lows, 50);
        double equilibrium = (rangeHigh + rangeLow) / 2.0;
        double rangePosition = rangeHigh > rangeLow ?
                (currentPrice - rangeLow) / (rangeHigh - rangeLow) : 0.5;
        boolean inPremium = rangePosition > 0.5;
        boolean inDiscount = rangePosition < 0.5;

        // ========== BUILD CONTEXT ==========
        SmcContext context = SmcContext.builder()
                // Structure
                .marketBias(structureResult.getMarketBias())
                .recentSwings(structureResult.getAllSwings())
                .lastStructureBreak(structureResult.getLastStructureBreak())
                .structureBullish(structureResult.isStructureBullish())
                .candlesSinceStructureBreak(structureResult.getCandlesSinceStructureBreak())
                // Order Blocks
                .demandZones(obResult.getDemandZones())
                .supplyZones(obResult.getSupplyZones())
                .nearestDemand(obResult.getNearestDemand())
                .nearestSupply(obResult.getNearestSupply())
                .inOrderBlock(currentOB != null)
                .currentOrderBlock(currentOB)
                // FVGs
                .bullishFvgs(fvgResult.getBullishFvgs())
                .bearishFvgs(fvgResult.getBearishFvgs())
                .inFvg(currentFVG != null)
                .nearestFvg(fvgResult.getNearestFvg())
                // Liquidity
                .buySideLiquidity(liqResult.getBuySideLiquidity())
                .sellSideLiquidity(liqResult.getSellSideLiquidity())
                .recentSweeps(liqResult.getRecentSweeps())
                .liquidityJustSwept(liqResult.isLiquidityJustSwept())
                .lastSweep(liqResult.getLastSweep())
                // Premium/Discount
                .equilibrium(equilibrium)
                .inPremium(inPremium)
                .inDiscount(inDiscount)
                .rangePosition(rangePosition)
                .build();

        // Log summary
        logSmcSummary(context, currentPrice, timeframe);

        return context;
    }

    /**
     * Analyze with HTF context (for MTF trading)
     */
    public SmcContext analyzeWithHtfContext(double[] opens, double[] highs, double[] lows,
                                             double[] closes, long[] timestamps, String timeframe,
                                             SmcContext htfContext) {
        SmcContext ltfContext = analyze(opens, highs, lows, closes, timestamps, timeframe);

        // Add HTF bias
        if (htfContext != null) {
            ltfContext.setHtfBias(htfContext.getMarketBias());
        }

        return ltfContext;
    }

    private double findRecentHigh(double[] highs, int lookback) {
        double high = highs[highs.length - 1];
        int start = Math.max(0, highs.length - lookback);
        for (int i = start; i < highs.length; i++) {
            high = Math.max(high, highs[i]);
        }
        return high;
    }

    private double findRecentLow(double[] lows, int lookback) {
        double low = lows[lows.length - 1];
        int start = Math.max(0, lows.length - lookback);
        for (int i = start; i < lows.length; i++) {
            low = Math.min(low, lows[i]);
        }
        return low;
    }

    private void logSmcSummary(SmcContext ctx, double price, String timeframe) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[SMC] %s @ %.2f | ", timeframe, price));
        sb.append(String.format("Bias=%s | ", ctx.getMarketBias()));
        sb.append(String.format("Zone=%s | ", ctx.isInPremium() ? "PREMIUM" : "DISCOUNT"));

        if (ctx.getLastStructureBreak() != null) {
            sb.append(String.format("%s(%d ago) | ",
                    ctx.getLastStructureBreak().getType(),
                    ctx.getCandlesSinceStructureBreak()));
        }

        if (ctx.isLiquidityJustSwept()) {
            sb.append("LIQ_SWEPT! | ");
        }

        if (ctx.isInOrderBlock()) {
            sb.append(String.format("IN_%s_OB | ",
                    ctx.getCurrentOrderBlock().isBullish() ? "DEMAND" : "SUPPLY"));
        }

        sb.append(String.format("Conf=%d", ctx.getConfluenceScore()));

        // Only log at INFO level if we have a potential setup
        if (ctx.isLiquidityJustSwept() || ctx.isInOrderBlock() ||
                (ctx.getLastStructureBreak() != null && ctx.getCandlesSinceStructureBreak() < 5)) {
            log.info(sb.toString());
        } else {
            log.debug(sb.toString());
        }
    }
}
