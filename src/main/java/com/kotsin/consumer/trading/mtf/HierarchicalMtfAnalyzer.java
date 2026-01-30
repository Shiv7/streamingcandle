package com.kotsin.consumer.trading.mtf;

import com.kotsin.consumer.enrichment.enricher.MTFSuperTrendAggregator.TradingHorizon;
import com.kotsin.consumer.trading.mtf.HtfCandleAggregator.SmcCandleData;
import com.kotsin.consumer.trading.mtf.SwingRangeCalculator.SwingRange;
import com.kotsin.consumer.trading.mtf.SwingRangeCalculator.ZonePosition;
import com.kotsin.consumer.trading.smc.SmcAnalyzer;
import com.kotsin.consumer.trading.smc.SmcContext;
import com.kotsin.consumer.trading.smc.SmcContext.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * HierarchicalMtfAnalyzer - Proper MTF analysis with HTF=context, LTF=timing.
 *
 * THE PROBLEM WITH VOTING SYSTEM:
 * - Old approach: All timeframes vote, 70% bullish = BULLISH
 * - This is WRONG because it treats 1m and 1D as equivalent
 * - Real traders: HTF determines DIRECTION, LTF provides ENTRY TIMING
 *
 * THE FIX - HIERARCHICAL APPROACH:
 * - HTF (Higher Timeframe) = CONTEXT
 *   - Determines overall trend direction (BULLISH/BEARISH)
 *   - Identifies MAJOR order blocks (significant S/R zones)
 *   - Identifies MAJOR FVGs (significant imbalances)
 *
 * - LTF (Lower Timeframe) = TIMING
 *   - Detects liquidity sweeps (stop hunts)
 *   - Detects CHoCH/BOS for entry confirmation
 *   - Identifies entry order blocks
 *
 * HORIZON-APPROPRIATE TIMEFRAMES:
 * - SCALP:      HTF=15m, LTF=1m   (quick trades, tight stops)
 * - INTRADAY:   HTF=1h,  LTF=5m  (same-day trades)
 * - SWING:      HTF=4h,  LTF=15m (multi-day trades)
 * - POSITIONAL: HTF=1d,  LTF=1h  (multi-week trades)
 *
 * KEY RULE: You NEVER trade against the HTF bias!
 * - If HTF is BEARISH, you DO NOT take LONG trades (only shorts or sit out)
 * - If HTF is BULLISH, you DO NOT take SHORT trades (only longs or sit out)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HierarchicalMtfAnalyzer {

    private final SmcAnalyzer smcAnalyzer;
    private final HtfCandleAggregator htfAggregator;
    private final SwingRangeCalculator swingRangeCalculator;

    // Horizon-appropriate timeframe pairs
    private static final Map<TradingHorizon, TimeframePair> HORIZON_TIMEFRAMES = Map.of(
            TradingHorizon.SCALP, new TimeframePair("15m", "1m"),
            TradingHorizon.INTRADAY, new TimeframePair("1h", "5m"),
            TradingHorizon.SWING, new TimeframePair("4h", "15m"),
            TradingHorizon.POSITION, new TimeframePair("1d", "1h")
    );

    // Default if horizon not specified
    private static final TimeframePair DEFAULT_PAIR = new TimeframePair("1h", "5m");

    // Minimum candles for analysis
    private static final int MIN_HTF_CANDLES = 50;
    private static final int MIN_LTF_CANDLES = 30;

    // CHoCH/BOS recency threshold (candles since break)
    private static final int RECENT_BREAK_THRESHOLD = 5;

    /**
     * Perform hierarchical MTF analysis.
     *
     * @param familyId     The instrument family ID
     * @param horizon      Trading horizon (determines timeframe pair)
     * @param currentPrice Current price for zone calculations
     * @return HierarchicalContext with complete HTF/LTF analysis
     */
    public HierarchicalContext analyze(String familyId, TradingHorizon horizon, double currentPrice) {
        TimeframePair tfPair = HORIZON_TIMEFRAMES.getOrDefault(horizon, DEFAULT_PAIR);

        log.debug("[HIER_MTF] {} Analyzing with HTF={} LTF={} for {}",
                familyId, tfPair.htf(), tfPair.ltf(), horizon);

        // ========== HTF ANALYSIS (CONTEXT) ==========
        SmcCandleData htfData = htfAggregator.getCandleArrays(familyId, tfPair.htf(), 100);
        SmcContext htfContext = null;

        if (htfData.hasData() && htfData.size() >= MIN_HTF_CANDLES / 2) {
            htfContext = smcAnalyzer.analyze(
                    htfData.getOpens(),
                    htfData.getHighs(),
                    htfData.getLows(),
                    htfData.getCloses(),
                    htfData.getTimestamps(),
                    tfPair.htf()
            );
            log.debug("[HIER_MTF] {} HTF({}) bias={} demandZones={} supplyZones={}",
                    familyId, tfPair.htf(),
                    htfContext.getMarketBias(),
                    htfContext.getDemandZones() != null ? htfContext.getDemandZones().size() : 0,
                    htfContext.getSupplyZones() != null ? htfContext.getSupplyZones().size() : 0);
        } else {
            log.warn("[HIER_MTF] {} Insufficient HTF({}) data: {} candles",
                    familyId, tfPair.htf(), htfData.size());
        }

        // ========== LTF ANALYSIS (TIMING) ==========
        SmcCandleData ltfData = htfAggregator.getCandleArrays(familyId, tfPair.ltf(), 100);
        SmcContext ltfContext = null;

        if (ltfData.hasData() && ltfData.size() >= MIN_LTF_CANDLES / 2) {
            ltfContext = smcAnalyzer.analyze(
                    ltfData.getOpens(),
                    ltfData.getHighs(),
                    ltfData.getLows(),
                    ltfData.getCloses(),
                    ltfData.getTimestamps(),
                    tfPair.ltf()
            );

            // Set HTF bias in LTF context for reference
            if (htfContext != null) {
                ltfContext.setHtfBias(htfContext.getMarketBias());
            }

            log.debug("[HIER_MTF] {} LTF({}) liquiditySwept={} lastBreak={}",
                    familyId, tfPair.ltf(),
                    ltfContext.isLiquidityJustSwept(),
                    ltfContext.getLastStructureBreak() != null ?
                            ltfContext.getLastStructureBreak().getType() : "NONE");
        } else {
            log.warn("[HIER_MTF] {} Insufficient LTF({}) data: {} candles",
                    familyId, tfPair.ltf(), ltfData.size());
        }

        // ========== SWING RANGE (ZONES) ==========
        SwingRange swingRange = swingRangeCalculator.calculateCurrentSwing(familyId, tfPair.htf());
        ZonePosition zonePosition = swingRangeCalculator.getZonePosition(currentPrice, swingRange);
        double rangePosition = swingRangeCalculator.getRangePosition(currentPrice, swingRange);

        // ========== BUILD HIERARCHICAL CONTEXT ==========
        HierarchicalContext.HierarchicalContextBuilder builder = HierarchicalContext.builder()
                .familyId(familyId)
                .horizon(horizon)
                .htfTimeframe(tfPair.htf())
                .ltfTimeframe(tfPair.ltf())
                .currentPrice(currentPrice)
                .swingRange(swingRange)
                .zonePosition(zonePosition)
                .rangePosition(rangePosition);

        // HTF context
        if (htfContext != null) {
            builder.htfBias(htfContext.getMarketBias())
                    .htfStructureBullish(htfContext.isStructureBullish())
                    .htfDemandZones(htfContext.getDemandZones())
                    .htfSupplyZones(htfContext.getSupplyZones())
                    .htfNearestDemand(htfContext.getNearestDemand())
                    .htfNearestSupply(htfContext.getNearestSupply())
                    .htfNearestFvg(htfContext.getNearestFvg())
                    .htfLastStructureBreak(htfContext.getLastStructureBreak());

            // Check if at HTF POI
            builder.atHtfDemand(isNearZone(currentPrice, htfContext.getNearestDemand()))
                    .atHtfSupply(isNearZone(currentPrice, htfContext.getNearestSupply()))
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
                    .ltfSweepDetected(ltfContext.isLiquidityJustSwept())
                    .ltfLastSweep(ltfContext.getLastSweep())
                    .ltfLastStructureBreak(ltfContext.getLastStructureBreak())
                    .ltfCandlesSinceBreak(ltfContext.getCandlesSinceStructureBreak())
                    .ltfEntryOb(ltfContext.getCurrentOrderBlock())
                    .inLtfOrderBlock(ltfContext.isInOrderBlock());

            // Check for recent CHoCH/BOS
            if (ltfContext.getLastStructureBreak() != null &&
                    ltfContext.getCandlesSinceStructureBreak() <= RECENT_BREAK_THRESHOLD) {
                StructureBreak brk = ltfContext.getLastStructureBreak();
                if (brk.getType() == StructureType.CHOCH) {
                    builder.ltfRecentChoch(true)
                            .ltfChochBullish(brk.isBullish());
                } else if (brk.getType() == StructureType.BOS) {
                    builder.ltfRecentBos(true)
                            .ltfBosBullish(brk.isBullish());
                }
            }
        } else {
            builder.ltfBias(MarketBias.UNKNOWN)
                    .ltfStructureBullish(false)
                    .ltfSweepDetected(false);
        }

        // Check bias alignment
        boolean biasAligned = htfContext != null && ltfContext != null &&
                htfContext.getMarketBias() == ltfContext.getMarketBias();
        builder.biasAligned(biasAligned);

        HierarchicalContext ctx = builder.build();

        // Log summary
        logHierarchicalSummary(ctx);

        return ctx;
    }

    /**
     * Check if price is near an order block.
     */
    private boolean isNearZone(double price, OrderBlock ob) {
        if (ob == null) return false;
        // Within the zone or within 0.5% of midpoint
        if (ob.contains(price)) return true;
        double distance = Math.abs(price - ob.getMidpoint()) / price * 100;
        return distance <= 0.5;
    }

    /**
     * Log hierarchical analysis summary.
     */
    private void logHierarchicalSummary(HierarchicalContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[HIER_MTF] %s @ %.2f | ", ctx.getFamilyId(), ctx.getCurrentPrice()));
        sb.append(String.format("horizon=%s | ", ctx.getHorizon()));
        sb.append(String.format("HTF(%s)=%s | ", ctx.getHtfTimeframe(), ctx.getHtfBias()));
        sb.append(String.format("LTF(%s)=%s | ", ctx.getLtfTimeframe(), ctx.getLtfBias()));
        sb.append(String.format("Zone=%s (%.0f%%) | ", ctx.getZonePosition(), ctx.getRangePosition() * 100));

        if (ctx.isAtHtfDemand()) sb.append("@HTF_DEMAND | ");
        if (ctx.isAtHtfSupply()) sb.append("@HTF_SUPPLY | ");
        if (ctx.isLtfSweepDetected()) {
            sb.append(ctx.getLtfLastSweep() != null && ctx.getLtfLastSweep().isBuySide() ?
                    "LTF_BUY_SWEEP | " : "LTF_SELL_SWEEP | ");
        }
        if (ctx.isLtfRecentChoch()) sb.append(ctx.isLtfChochBullish() ? "LTF_BULL_CHOCH | " : "LTF_BEAR_CHOCH | ");
        if (ctx.isLtfRecentBos()) sb.append(ctx.isLtfBosBullish() ? "LTF_BULL_BOS | " : "LTF_BEAR_BOS | ");

        sb.append(ctx.isBiasAligned() ? "ALIGNED" : "DIVERGENT");

        // Add setup validity indicators
        if (ctx.isValidForLong()) {
            sb.append(" | VALID_LONG");
            if (ctx.hasLtfLongConfirmation()) sb.append("+CONFIRMED");
        }
        if (ctx.isValidForShort()) {
            sb.append(" | VALID_SHORT");
            if (ctx.hasLtfShortConfirmation()) sb.append("+CONFIRMED");
        }

        log.info(sb.toString());
    }

    // ============ MODELS ============

    /**
     * Timeframe pair for HTF/LTF analysis.
     */
    public record TimeframePair(String htf, String ltf) {}

    /**
     * Complete hierarchical MTF context.
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class HierarchicalContext {
        // Identifiers
        private String familyId;
        private TradingHorizon horizon;
        private String htfTimeframe;
        private String ltfTimeframe;
        private double currentPrice;

        // Swing range (for premium/discount)
        private SwingRange swingRange;
        private ZonePosition zonePosition;
        private double rangePosition;  // 0.0 = swing low, 1.0 = swing high

        // HTF (Context)
        private MarketBias htfBias;
        private boolean htfStructureBullish;
        private List<OrderBlock> htfDemandZones;
        private List<OrderBlock> htfSupplyZones;
        private OrderBlock htfNearestDemand;
        private OrderBlock htfNearestSupply;
        private FairValueGap htfNearestFvg;
        private StructureBreak htfLastStructureBreak;
        private boolean atHtfDemand;
        private boolean atHtfSupply;
        private boolean atHtfFvg;

        // LTF (Timing)
        private MarketBias ltfBias;
        private boolean ltfStructureBullish;
        private OrderBlock ltfNearestDemand;
        private OrderBlock ltfNearestSupply;
        private OrderBlock ltfEntryOb;
        private boolean inLtfOrderBlock;
        private boolean ltfSweepDetected;
        private LiquiditySweep ltfLastSweep;
        private StructureBreak ltfLastStructureBreak;
        private int ltfCandlesSinceBreak;
        private boolean ltfRecentChoch;
        private boolean ltfChochBullish;
        private boolean ltfRecentBos;
        private boolean ltfBosBullish;

        // Alignment
        private boolean biasAligned;

        /**
         * Is this setup valid for a LONG trade?
         * Requires: HTF bullish + discount/equilibrium zone
         */
        public boolean isValidForLong() {
            return htfBias == MarketBias.BULLISH &&
                   (zonePosition == ZonePosition.DISCOUNT ||
                    zonePosition == ZonePosition.EQUILIBRIUM ||
                    zonePosition == ZonePosition.BELOW_RANGE);
        }

        /**
         * Is this setup valid for a SHORT trade?
         * Requires: HTF bearish + premium/equilibrium zone
         */
        public boolean isValidForShort() {
            return htfBias == MarketBias.BEARISH &&
                   (zonePosition == ZonePosition.PREMIUM ||
                    zonePosition == ZonePosition.EQUILIBRIUM ||
                    zonePosition == ZonePosition.ABOVE_RANGE);
        }

        /**
         * Does LTF confirm a LONG entry?
         * Requires: Sell-side sweep + bullish CHoCH/BOS
         */
        public boolean hasLtfLongConfirmation() {
            // Sweep of lows (sell-side) + bullish structure break
            boolean sweepConfirm = ltfSweepDetected && ltfLastSweep != null && !ltfLastSweep.isBuySide();
            boolean structureConfirm = (ltfRecentChoch && ltfChochBullish) || (ltfRecentBos && ltfBosBullish);
            return sweepConfirm && structureConfirm;
        }

        /**
         * Does LTF confirm a SHORT entry?
         * Requires: Buy-side sweep + bearish CHoCH/BOS
         */
        public boolean hasLtfShortConfirmation() {
            // Sweep of highs (buy-side) + bearish structure break
            boolean sweepConfirm = ltfSweepDetected && ltfLastSweep != null && ltfLastSweep.isBuySide();
            boolean structureConfirm = (ltfRecentChoch && !ltfChochBullish) || (ltfRecentBos && !ltfBosBullish);
            return sweepConfirm && structureConfirm;
        }

        /**
         * Get entry order block for LONG.
         */
        public OrderBlock getLongEntryOb() {
            if (ltfEntryOb != null && ltfEntryOb.isBullish()) {
                return ltfEntryOb;
            }
            return ltfNearestDemand;
        }

        /**
         * Get entry order block for SHORT.
         */
        public OrderBlock getShortEntryOb() {
            if (ltfEntryOb != null && !ltfEntryOb.isBullish()) {
                return ltfEntryOb;
            }
            return ltfNearestSupply;
        }

        public static HierarchicalContext empty() {
            return HierarchicalContext.builder()
                    .htfBias(MarketBias.UNKNOWN)
                    .ltfBias(MarketBias.UNKNOWN)
                    .zonePosition(ZonePosition.UNKNOWN)
                    .build();
        }
    }
}
