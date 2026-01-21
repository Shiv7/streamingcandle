package com.kotsin.consumer.trading.mtf;

import com.kotsin.consumer.trading.mtf.HtfCandleAggregator.SmcCandleData;
import com.kotsin.consumer.trading.smc.SmcAnalyzer;
import com.kotsin.consumer.trading.smc.SmcContext;
import com.kotsin.consumer.trading.smc.SmcContext.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * MtfSmcAnalyzer - Multi-Timeframe Smart Money Concepts Analyzer
 *
 * THIS IS THE REAL MTF ANALYSIS ENGINE.
 *
 * WHAT IT DOES:
 * 1. Gets HTF candles (4H/1H) from HtfCandleAggregator
 * 2. Runs SmcAnalyzer on HTF data → HTF bias, major OBs, major FVGs
 * 3. Gets LTF candles (15m/5m) from HtfCandleAggregator
 * 4. Runs SmcAnalyzer on LTF data → sweeps, entry OBs, CHoCH
 * 5. Gets Daily range for premium/discount zones
 * 6. Combines everything into MtfSmcContext
 *
 * THE MTF TRADING FLOW:
 *
 * Step 1: HTF Analysis (Once per HTF candle close)
 * ┌─────────────────────────────────────────┐
 * │ 4H/Daily Structure → BULLISH/BEARISH   │
 * │ 4H/Daily Order Blocks → Major zones    │
 * │ 4H/Daily FVGs → Major imbalances       │
 * │ Daily Range → Premium/Discount zones   │
 * └─────────────────────────────────────────┘
 *                    ↓
 * Step 2: Wait for price at HTF POI
 * ┌─────────────────────────────────────────┐
 * │ Price reaches Daily/4H demand zone     │
 * │ Price in discount zone (below 50%)     │
 * └─────────────────────────────────────────┘
 *                    ↓
 * Step 3: LTF Entry (On every LTF candle)
 * ┌─────────────────────────────────────────┐
 * │ 15m/5m Liquidity sweep (stops taken!)  │
 * │ 15m/5m CHoCH confirms direction        │
 * │ Enter at 15m/5m Order Block            │
 * └─────────────────────────────────────────┘
 *                    ↓
 * Step 4: Trade Management
 * ┌─────────────────────────────────────────┐
 * │ Stop: Below LTF swept low              │
 * │ Target: Opposite liquidity/HTF OB      │
 * └─────────────────────────────────────────┘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MtfSmcAnalyzer {

    private final SmcAnalyzer smcAnalyzer;
    private final HtfCandleAggregator htfAggregator;

    // Timeframe configurations
    private static final String HTF_TIMEFRAME = "1h";      // For structure and major OBs
    private static final String LTF_TIMEFRAME = "15m";     // For entries and sweeps
    private static final int HTF_CANDLE_COUNT = 100;
    private static final int LTF_CANDLE_COUNT = 100;

    // Proximity thresholds
    private static final double POI_PROXIMITY_PCT = 1.0;   // Within 1% of POI

    /**
     * Perform full MTF SMC analysis
     *
     * @param familyId     The instrument family ID
     * @param currentPrice Current price for proximity checks
     * @return MtfSmcContext with complete MTF analysis
     */
    public MtfSmcContext analyze(String familyId, double currentPrice) {
        if (currentPrice <= 0) {
            return MtfSmcContext.empty();
        }

        // ========== STEP 1: GET DAILY RANGE FOR PREMIUM/DISCOUNT ==========
        double[] dailyRange = htfAggregator.getDailyRange(familyId);
        double dailyHigh = dailyRange[0];
        double dailyLow = dailyRange[1];

        if (dailyHigh <= 0 || dailyLow <= 0 || dailyHigh <= dailyLow) {
            log.debug("[MTF_SMC] {} No daily range available yet", familyId);
            return MtfSmcContext.empty();
        }

        double equilibrium = (dailyHigh + dailyLow) / 2.0;
        double rangePosition = (currentPrice - dailyLow) / (dailyHigh - dailyLow);
        boolean inPremium = rangePosition > 0.5;
        boolean inDiscount = rangePosition < 0.5;

        // ========== STEP 2: HTF SMC ANALYSIS (1H) ==========
        SmcCandleData htfData = htfAggregator.getCandleArrays(familyId, HTF_TIMEFRAME, HTF_CANDLE_COUNT);
        SmcContext htfContext = null;

        if (htfData.hasData()) {
            htfContext = smcAnalyzer.analyze(
                    htfData.getOpens(),
                    htfData.getHighs(),
                    htfData.getLows(),
                    htfData.getCloses(),
                    htfData.getTimestamps(),
                    HTF_TIMEFRAME
            );
            log.debug("[MTF_SMC] {} HTF({}) analysis | bias={} | demandZones={} | supplyZones={}",
                    familyId, HTF_TIMEFRAME,
                    htfContext.getMarketBias(),
                    htfContext.getDemandZones().size(),
                    htfContext.getSupplyZones().size());
        } else {
            log.debug("[MTF_SMC] {} Not enough HTF({}) data: {} candles",
                    familyId, HTF_TIMEFRAME, htfData.size());
        }

        // ========== STEP 3: LTF SMC ANALYSIS (15m) ==========
        SmcCandleData ltfData = htfAggregator.getCandleArrays(familyId, LTF_TIMEFRAME, LTF_CANDLE_COUNT);
        SmcContext ltfContext = null;

        if (ltfData.hasData()) {
            ltfContext = smcAnalyzer.analyze(
                    ltfData.getOpens(),
                    ltfData.getHighs(),
                    ltfData.getLows(),
                    ltfData.getCloses(),
                    ltfData.getTimestamps(),
                    LTF_TIMEFRAME
            );

            // Set HTF bias in LTF context
            if (htfContext != null) {
                ltfContext.setHtfBias(htfContext.getMarketBias());
            }

            log.debug("[MTF_SMC] {} LTF({}) analysis | bias={} | liqSwept={} | lastBreak={}",
                    familyId, LTF_TIMEFRAME,
                    ltfContext.getMarketBias(),
                    ltfContext.isLiquidityJustSwept(),
                    ltfContext.getLastStructureBreak() != null ?
                            ltfContext.getLastStructureBreak().getType() : "NONE");
        } else {
            log.debug("[MTF_SMC] {} Not enough LTF({}) data: {} candles",
                    familyId, LTF_TIMEFRAME, ltfData.size());
        }

        // ========== STEP 4: BUILD MTF CONTEXT ==========
        MtfSmcContext.MtfSmcContextBuilder builder = MtfSmcContext.builder()
                .htfTimeframe(HTF_TIMEFRAME)
                .ltfTimeframe(LTF_TIMEFRAME)
                // Daily range
                .dailySwingHigh(dailyHigh)
                .dailySwingLow(dailyLow)
                .equilibrium(equilibrium)
                .rangePosition(rangePosition)
                .inPremium(inPremium)
                .inDiscount(inDiscount);

        // HTF context
        if (htfContext != null) {
            builder.htfBias(htfContext.getMarketBias())
                    .htfStructureBullish(htfContext.isStructureBullish())
                    .htfNearestDemand(htfContext.getNearestDemand())
                    .htfNearestSupply(htfContext.getNearestSupply())
                    .htfNearestFvg(htfContext.getNearestFvg())
                    .htfLastStructureBreak(htfContext.getLastStructureBreak());

            // Check if at HTF POI
            builder.atHtfDemandZone(isNearPoi(currentPrice, htfContext.getNearestDemand()))
                    .atHtfSupplyZone(isNearPoi(currentPrice, htfContext.getNearestSupply()))
                    .atHtfFvg(htfContext.getNearestFvg() != null &&
                            htfContext.getNearestFvg().contains(currentPrice));
        } else {
            builder.htfBias(MarketBias.UNKNOWN)
                    .htfStructureBullish(false);
        }

        // LTF context
        if (ltfContext != null) {
            builder.ltfBias(ltfContext.getMarketBias())
                    .ltfStructureBullish(ltfContext.isStructureBullish())
                    .ltfNearestDemand(ltfContext.getNearestDemand())
                    .ltfNearestSupply(ltfContext.getNearestSupply())
                    .ltfNearestFvg(ltfContext.getNearestFvg())
                    .inLtfOrderBlock(ltfContext.isInOrderBlock())
                    .currentLtfOrderBlock(ltfContext.getCurrentOrderBlock())
                    .inLtfFvg(ltfContext.isInFvg())
                    // Liquidity
                    .ltfLiquidityJustSwept(ltfContext.isLiquidityJustSwept())
                    .ltfLastSweep(ltfContext.getLastSweep())
                    .buySideLiquidity(ltfContext.getBuySideLiquidity())
                    .sellSideLiquidity(ltfContext.getSellSideLiquidity())
                    // Structure breaks
                    .ltfLastStructureBreak(ltfContext.getLastStructureBreak())
                    .ltfCandlesSinceStructureBreak(ltfContext.getCandlesSinceStructureBreak());

            // Check for recent CHoCH/BOS
            if (ltfContext.getLastStructureBreak() != null &&
                    ltfContext.getCandlesSinceStructureBreak() <= 5) {
                if (ltfContext.getLastStructureBreak().getType() == StructureType.CHOCH) {
                    builder.ltfRecentChoch(true);
                } else if (ltfContext.getLastStructureBreak().getType() == StructureType.BOS) {
                    builder.ltfRecentBos(true);
                }
            }
        } else {
            builder.ltfBias(MarketBias.UNKNOWN)
                    .ltfStructureBullish(false)
                    .ltfLiquidityJustSwept(false);
        }

        // Check bias alignment
        boolean biasAligned = htfContext != null && ltfContext != null &&
                ((htfContext.getMarketBias() == MarketBias.BULLISH && ltfContext.isStructureBullish()) ||
                        (htfContext.getMarketBias() == MarketBias.BEARISH && !ltfContext.isStructureBullish()));
        builder.biasAligned(biasAligned);

        MtfSmcContext mtfContext = builder.build();
        mtfContext.calculateConfluenceScore();

        // Log summary
        logMtfSummary(familyId, currentPrice, mtfContext);

        return mtfContext;
    }

    /**
     * Check if price is near a POI (Order Block)
     */
    private boolean isNearPoi(double price, OrderBlock ob) {
        if (ob == null) return false;
        double distance = Math.abs(price - ob.getMidpoint()) / price * 100;
        return distance <= POI_PROXIMITY_PCT || ob.contains(price);
    }

    /**
     * Log MTF analysis summary
     */
    private void logMtfSummary(String familyId, double price, MtfSmcContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[MTF_SMC] %s @ %.2f | ", familyId, price));

        // HTF
        sb.append(String.format("HTF=%s | ", ctx.getHtfBias()));

        // Zone
        sb.append(String.format("Zone=%s (%.0f%%) | ",
                ctx.isInDiscount() ? "DISCOUNT" : (ctx.isInPremium() ? "PREMIUM" : "NEUTRAL"),
                ctx.getRangePosition() * 100));

        // At POI?
        if (ctx.isAtHtfDemandZone()) sb.append("@HTF_DEMAND | ");
        if (ctx.isAtHtfSupplyZone()) sb.append("@HTF_SUPPLY | ");
        if (ctx.isAtHtfFvg()) sb.append("@HTF_FVG | ");

        // LTF signals
        if (ctx.isLtfLiquidityJustSwept()) {
            sb.append(String.format("LTF_SWEEP(%s) | ",
                    ctx.getLtfLastSweep().isBuySide() ? "BUY" : "SELL"));
        }
        if (ctx.isLtfRecentChoch()) sb.append("LTF_CHOCH | ");
        if (ctx.isLtfRecentBos()) sb.append("LTF_BOS | ");

        sb.append(String.format("Conf=%d", ctx.getMtfConfluenceScore()));

        // Log at INFO if potential setup, DEBUG otherwise
        if (ctx.isValidMtfLongSetup() || ctx.isValidMtfShortSetup()) {
            log.info("{} | VALID_SETUP={}", sb,
                    ctx.isValidMtfLongSetup() ? "LONG" : "SHORT");
        } else if (ctx.getMtfConfluenceScore() >= 5) {
            log.info(sb.toString());
        } else {
            log.debug(sb.toString());
        }
    }
}
