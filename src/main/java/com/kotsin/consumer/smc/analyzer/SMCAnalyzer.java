package com.kotsin.consumer.smc.analyzer;

import com.kotsin.consumer.smc.model.FairValueGap;
import com.kotsin.consumer.smc.model.FairValueGap.FVGStrength;
import com.kotsin.consumer.smc.model.FairValueGap.FVGType;
import com.kotsin.consumer.smc.model.LiquidityZone;
import com.kotsin.consumer.smc.model.LiquidityZone.LiquiditySource;
import com.kotsin.consumer.smc.model.LiquidityZone.LiquidityStrength;
import com.kotsin.consumer.smc.model.LiquidityZone.LiquidityType;
import com.kotsin.consumer.smc.model.OrderBlock;
import com.kotsin.consumer.smc.model.OrderBlock.OrderBlockStrength;
import com.kotsin.consumer.smc.model.OrderBlock.OrderBlockType;
import com.kotsin.consumer.smc.model.OrderBlock.TrendContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SMCAnalyzer - Smart Money Concept analysis engine.
 *
 * Detects:
 * - Order Blocks (institutional accumulation/distribution)
 * - Fair Value Gaps (price imbalances)
 * - Liquidity Zones (stop loss clusters)
 * - Market Structure (HH, HL, LH, LL)
 */
@Component
@Slf4j
public class SMCAnalyzer {

    // Storage for detected structures
    private final Map<String, List<OrderBlock>> orderBlocks = new ConcurrentHashMap<>();
    private final Map<String, List<FairValueGap>> fairValueGaps = new ConcurrentHashMap<>();
    private final Map<String, List<LiquidityZone>> liquidityZones = new ConcurrentHashMap<>();
    private final Map<String, MarketStructure> marketStructures = new ConcurrentHashMap<>();

    // Configuration
    private static final int MAX_STRUCTURES = 50;        // Max structures to keep per symbol
    private static final double MIN_FVG_SIZE_PCT = 0.1;  // Minimum FVG size (0.1%)
    private static final double MIN_OB_MOVE_PCT = 0.3;   // Minimum move after OB (0.3%)
    private static final int SWING_LOOKBACK = 5;         // Candles for swing detection
    private static final double EQUAL_LEVEL_TOLERANCE = 0.001; // 0.1% tolerance for equal highs/lows

    /**
     * Analyze candles and detect SMC structures.
     *
     * @param symbol     Symbol identifier
     * @param timeframe  Timeframe (e.g., "5m", "15m")
     * @param candles    List of candle data [timestamp, open, high, low, close, volume]
     * @return SMCResult with detected structures
     */
    public SMCResult analyze(String symbol, String timeframe, List<CandleData> candles) {
        if (candles == null || candles.size() < 5) {
            return new SMCResult();
        }

        SMCResult result = new SMCResult();

        // Detect Order Blocks
        List<OrderBlock> obs = detectOrderBlocks(symbol, timeframe, candles);
        result.setOrderBlocks(obs);
        mergeOrderBlocks(symbol, obs);

        // Detect Fair Value Gaps
        List<FairValueGap> fvgs = detectFairValueGaps(symbol, timeframe, candles);
        result.setFairValueGaps(fvgs);
        mergeFairValueGaps(symbol, fvgs);

        // Detect Liquidity Zones
        List<LiquidityZone> lzs = detectLiquidityZones(symbol, timeframe, candles);
        result.setLiquidityZones(lzs);
        mergeLiquidityZones(symbol, lzs);

        // Update Market Structure
        MarketStructure ms = updateMarketStructure(symbol, candles);
        result.setMarketStructure(ms);

        // Update validation status of existing structures
        updateStructureValidation(symbol, candles.get(candles.size() - 1));

        return result;
    }

    /**
     * Detect Order Blocks from candle data.
     */
    private List<OrderBlock> detectOrderBlocks(String symbol, String timeframe, List<CandleData> candles) {
        List<OrderBlock> detected = new ArrayList<>();

        for (int i = 2; i < candles.size() - 2; i++) {
            CandleData prev = candles.get(i - 1);
            CandleData curr = candles.get(i);
            CandleData next = candles.get(i + 1);
            CandleData next2 = candles.get(i + 2);

            // Bullish OB: Bearish candle followed by bullish structure break
            if (isBearishCandle(curr) && isBullishBreak(curr, next, next2)) {
                double moveSize = next2.high - curr.low;
                double movePercent = moveSize / curr.close * 100;

                if (movePercent >= MIN_OB_MOVE_PCT) {
                    OrderBlock ob = OrderBlock.builder()
                        .symbol(symbol)
                        .timeframe(timeframe)
                        .timestamp(curr.timestamp)
                        .type(OrderBlockType.BULLISH)
                        .high(curr.high)
                        .low(curr.low)
                        .midpoint((curr.high + curr.low) / 2)
                        .open(curr.open)
                        .close(curr.close)
                        .isValid(true)
                        .isMitigated(false)
                        .mitigationCount(0)
                        .createdAt(curr.timestamp)
                        .moveSize(moveSize)
                        .movePercent(movePercent)
                        .candlesToBreak(2)
                        .volume(curr.volume)
                        .structureBreakLevel(prev.high)
                        .strength(calculateOBStrength(movePercent, curr.volume))
                        .trendContext(TrendContext.WITH_TREND)
                        .build();
                    detected.add(ob);
                }
            }

            // Bearish OB: Bullish candle followed by bearish structure break
            if (isBullishCandle(curr) && isBearishBreak(curr, next, next2)) {
                double moveSize = curr.high - next2.low;
                double movePercent = moveSize / curr.close * 100;

                if (movePercent >= MIN_OB_MOVE_PCT) {
                    OrderBlock ob = OrderBlock.builder()
                        .symbol(symbol)
                        .timeframe(timeframe)
                        .timestamp(curr.timestamp)
                        .type(OrderBlockType.BEARISH)
                        .high(curr.high)
                        .low(curr.low)
                        .midpoint((curr.high + curr.low) / 2)
                        .open(curr.open)
                        .close(curr.close)
                        .isValid(true)
                        .isMitigated(false)
                        .mitigationCount(0)
                        .createdAt(curr.timestamp)
                        .moveSize(moveSize)
                        .movePercent(movePercent)
                        .candlesToBreak(2)
                        .volume(curr.volume)
                        .structureBreakLevel(prev.low)
                        .strength(calculateOBStrength(movePercent, curr.volume))
                        .trendContext(TrendContext.WITH_TREND)
                        .build();
                    detected.add(ob);
                }
            }
        }

        return detected;
    }

    /**
     * Detect Fair Value Gaps from candle data.
     */
    private List<FairValueGap> detectFairValueGaps(String symbol, String timeframe, List<CandleData> candles) {
        List<FairValueGap> detected = new ArrayList<>();

        for (int i = 2; i < candles.size(); i++) {
            CandleData c1 = candles.get(i - 2);
            CandleData c2 = candles.get(i - 1);
            CandleData c3 = candles.get(i);

            // Bullish FVG: Gap between C1 high and C3 low
            if (c3.low > c1.high) {
                double gapSize = c3.low - c1.high;
                double gapPercent = gapSize / c2.close * 100;

                if (gapPercent >= MIN_FVG_SIZE_PCT) {
                    FairValueGap fvg = FairValueGap.builder()
                        .symbol(symbol)
                        .timeframe(timeframe)
                        .timestamp(c2.timestamp)
                        .type(FVGType.BULLISH)
                        .high(c3.low)
                        .low(c1.high)
                        .midpoint((c3.low + c1.high) / 2)
                        .gapSize(gapSize)
                        .candle1High(c1.high)
                        .candle1Low(c1.low)
                        .candle2Open(c2.open)
                        .candle2High(c2.high)
                        .candle2Low(c2.low)
                        .candle2Close(c2.close)
                        .candle3High(c3.high)
                        .candle3Low(c3.low)
                        .isValid(true)
                        .isPartialFill(false)
                        .fillPercent(0)
                        .createdAt(c2.timestamp)
                        .impulseSize(c2.close - c2.open)
                        .impulsePercent((c2.close - c2.open) / c2.open * 100)
                        .volume(c2.volume)
                        .strength(calculateFVGStrength(gapPercent, c2.volume))
                        .entryLevel((c3.low + c1.high) / 2)
                        .stopLevel(c1.high * 0.995)
                        .isWithTrend(true)
                        .build();
                    detected.add(fvg);
                }
            }

            // Bearish FVG: Gap between C1 low and C3 high
            if (c1.low > c3.high) {
                double gapSize = c1.low - c3.high;
                double gapPercent = gapSize / c2.close * 100;

                if (gapPercent >= MIN_FVG_SIZE_PCT) {
                    FairValueGap fvg = FairValueGap.builder()
                        .symbol(symbol)
                        .timeframe(timeframe)
                        .timestamp(c2.timestamp)
                        .type(FVGType.BEARISH)
                        .high(c1.low)
                        .low(c3.high)
                        .midpoint((c1.low + c3.high) / 2)
                        .gapSize(gapSize)
                        .candle1High(c1.high)
                        .candle1Low(c1.low)
                        .candle2Open(c2.open)
                        .candle2High(c2.high)
                        .candle2Low(c2.low)
                        .candle2Close(c2.close)
                        .candle3High(c3.high)
                        .candle3Low(c3.low)
                        .isValid(true)
                        .isPartialFill(false)
                        .fillPercent(0)
                        .createdAt(c2.timestamp)
                        .impulseSize(c2.open - c2.close)
                        .impulsePercent((c2.open - c2.close) / c2.open * 100)
                        .volume(c2.volume)
                        .strength(calculateFVGStrength(gapPercent, c2.volume))
                        .entryLevel((c1.low + c3.high) / 2)
                        .stopLevel(c1.low * 1.005)
                        .isWithTrend(true)
                        .build();
                    detected.add(fvg);
                }
            }
        }

        return detected;
    }

    /**
     * Detect Liquidity Zones from candle data.
     */
    private List<LiquidityZone> detectLiquidityZones(String symbol, String timeframe, List<CandleData> candles) {
        List<LiquidityZone> detected = new ArrayList<>();

        // Detect swing highs and lows
        for (int i = SWING_LOOKBACK; i < candles.size() - SWING_LOOKBACK; i++) {
            CandleData curr = candles.get(i);

            // Check for swing high
            boolean isSwingHigh = true;
            for (int j = i - SWING_LOOKBACK; j <= i + SWING_LOOKBACK; j++) {
                if (j != i && candles.get(j).high >= curr.high) {
                    isSwingHigh = false;
                    break;
                }
            }

            if (isSwingHigh) {
                LiquidityZone lz = LiquidityZone.builder()
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .timestamp(curr.timestamp)
                    .type(LiquidityType.BUY_SIDE)
                    .level(curr.high)
                    .zoneHigh(curr.high * 1.002)
                    .zoneLow(curr.high)
                    .touchCount(1)
                    .isSwept(false)
                    .source(LiquiditySource.SWING_HIGH)
                    .touchTimestamps(new ArrayList<>(List.of(curr.timestamp)))
                    .totalVolume(curr.volume)
                    .strength(LiquidityStrength.MODERATE)
                    .ageInCandles(candles.size() - i)
                    .isRejected(true)
                    .rejectionCount(1)
                    .build();
                detected.add(lz);
            }

            // Check for swing low
            boolean isSwingLow = true;
            for (int j = i - SWING_LOOKBACK; j <= i + SWING_LOOKBACK; j++) {
                if (j != i && candles.get(j).low <= curr.low) {
                    isSwingLow = false;
                    break;
                }
            }

            if (isSwingLow) {
                LiquidityZone lz = LiquidityZone.builder()
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .timestamp(curr.timestamp)
                    .type(LiquidityType.SELL_SIDE)
                    .level(curr.low)
                    .zoneHigh(curr.low)
                    .zoneLow(curr.low * 0.998)
                    .touchCount(1)
                    .isSwept(false)
                    .source(LiquiditySource.SWING_LOW)
                    .touchTimestamps(new ArrayList<>(List.of(curr.timestamp)))
                    .totalVolume(curr.volume)
                    .strength(LiquidityStrength.MODERATE)
                    .ageInCandles(candles.size() - i)
                    .isRejected(true)
                    .rejectionCount(1)
                    .build();
                detected.add(lz);
            }
        }

        // Detect equal highs/lows
        detectEqualLevels(detected, candles, symbol, timeframe);

        return detected;
    }

    /**
     * Detect equal highs and equal lows.
     */
    private void detectEqualLevels(List<LiquidityZone> detected, List<CandleData> candles,
                                    String symbol, String timeframe) {
        Map<Double, List<Integer>> highLevels = new HashMap<>();
        Map<Double, List<Integer>> lowLevels = new HashMap<>();

        for (int i = 0; i < candles.size(); i++) {
            CandleData c = candles.get(i);

            // Round to tolerance level
            double roundedHigh = Math.round(c.high / (c.high * EQUAL_LEVEL_TOLERANCE)) * (c.high * EQUAL_LEVEL_TOLERANCE);
            double roundedLow = Math.round(c.low / (c.low * EQUAL_LEVEL_TOLERANCE)) * (c.low * EQUAL_LEVEL_TOLERANCE);

            highLevels.computeIfAbsent(roundedHigh, k -> new ArrayList<>()).add(i);
            lowLevels.computeIfAbsent(roundedLow, k -> new ArrayList<>()).add(i);
        }

        // Equal highs (2+ touches)
        for (Map.Entry<Double, List<Integer>> entry : highLevels.entrySet()) {
            if (entry.getValue().size() >= 2) {
                int lastIdx = entry.getValue().get(entry.getValue().size() - 1);
                CandleData lastCandle = candles.get(lastIdx);

                LiquidityZone lz = LiquidityZone.builder()
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .timestamp(lastCandle.timestamp)
                    .type(LiquidityType.BUY_SIDE)
                    .level(entry.getKey())
                    .zoneHigh(entry.getKey() * 1.002)
                    .zoneLow(entry.getKey())
                    .touchCount(entry.getValue().size())
                    .isSwept(false)
                    .source(LiquiditySource.EQUAL_HIGHS)
                    .strength(entry.getValue().size() >= 3 ? LiquidityStrength.STRONG : LiquidityStrength.MODERATE)
                    .ageInCandles(candles.size() - lastIdx)
                    .build();
                detected.add(lz);
            }
        }

        // Equal lows (2+ touches)
        for (Map.Entry<Double, List<Integer>> entry : lowLevels.entrySet()) {
            if (entry.getValue().size() >= 2) {
                int lastIdx = entry.getValue().get(entry.getValue().size() - 1);
                CandleData lastCandle = candles.get(lastIdx);

                LiquidityZone lz = LiquidityZone.builder()
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .timestamp(lastCandle.timestamp)
                    .type(LiquidityType.SELL_SIDE)
                    .level(entry.getKey())
                    .zoneHigh(entry.getKey())
                    .zoneLow(entry.getKey() * 0.998)
                    .touchCount(entry.getValue().size())
                    .isSwept(false)
                    .source(LiquiditySource.EQUAL_LOWS)
                    .strength(entry.getValue().size() >= 3 ? LiquidityStrength.STRONG : LiquidityStrength.MODERATE)
                    .ageInCandles(candles.size() - lastIdx)
                    .build();
                detected.add(lz);
            }
        }
    }

    /**
     * Update market structure with new candle data.
     */
    private MarketStructure updateMarketStructure(String symbol, List<CandleData> candles) {
        MarketStructure ms = marketStructures.computeIfAbsent(symbol, s -> new MarketStructure());

        // Find recent swing points
        List<Double> swingHighs = new ArrayList<>();
        List<Double> swingLows = new ArrayList<>();

        for (int i = SWING_LOOKBACK; i < candles.size() - SWING_LOOKBACK; i++) {
            CandleData curr = candles.get(i);

            boolean isSwingHigh = true;
            boolean isSwingLow = true;

            for (int j = i - SWING_LOOKBACK; j <= i + SWING_LOOKBACK; j++) {
                if (j != i) {
                    if (candles.get(j).high >= curr.high) isSwingHigh = false;
                    if (candles.get(j).low <= curr.low) isSwingLow = false;
                }
            }

            if (isSwingHigh) swingHighs.add(curr.high);
            if (isSwingLow) swingLows.add(curr.low);
        }

        if (swingHighs.size() >= 2 && swingLows.size() >= 2) {
            double lastHigh = swingHighs.get(swingHighs.size() - 1);
            double prevHigh = swingHighs.get(swingHighs.size() - 2);
            double lastLow = swingLows.get(swingLows.size() - 1);
            double prevLow = swingLows.get(swingLows.size() - 2);

            ms.lastSwingHigh = lastHigh;
            ms.lastSwingLow = lastLow;

            // Determine trend
            if (lastHigh > prevHigh && lastLow > prevLow) {
                ms.trend = MarketStructure.Trend.BULLISH;
                ms.isHigherHigh = true;
                ms.isHigherLow = true;
            } else if (lastHigh < prevHigh && lastLow < prevLow) {
                ms.trend = MarketStructure.Trend.BEARISH;
                ms.isLowerHigh = true;
                ms.isLowerLow = true;
            } else {
                ms.trend = MarketStructure.Trend.RANGING;
            }
        }

        CandleData lastCandle = candles.get(candles.size() - 1);
        ms.currentPrice = lastCandle.close;
        ms.lastUpdate = lastCandle.timestamp;

        return ms;
    }

    /**
     * Update validation status of existing structures.
     */
    private void updateStructureValidation(String symbol, CandleData currentCandle) {
        double price = currentCandle.close;
        double high = currentCandle.high;
        double low = currentCandle.low;
        Instant now = currentCandle.timestamp;

        // Update Order Blocks
        List<OrderBlock> obs = orderBlocks.get(symbol);
        if (obs != null) {
            for (OrderBlock ob : obs) {
                if (!ob.isValid()) continue;

                // Check if OB is mitigated (price returned to zone)
                if (ob.isPriceInZone(price)) {
                    ob.setMitigated(true);
                    ob.setMitigationCount(ob.getMitigationCount() + 1);
                    if (ob.getMitigatedAt() == null) {
                        ob.setMitigatedAt(now);
                    }
                }

                // Check if OB is invalidated (price broke through)
                if (ob.isBullish() && low < ob.getLow() * 0.998) {
                    ob.setValid(false);
                } else if (ob.isBearish() && high > ob.getHigh() * 1.002) {
                    ob.setValid(false);
                }
            }
        }

        // Update FVGs
        List<FairValueGap> fvgs = fairValueGaps.get(symbol);
        if (fvgs != null) {
            for (FairValueGap fvg : fvgs) {
                if (!fvg.isValid()) continue;
                fvg.updateFillStatus(price);
                if (!fvg.isValid()) {
                    fvg.setFilledAt(now);
                }
            }
        }

        // Update Liquidity Zones
        List<LiquidityZone> lzs = liquidityZones.get(symbol);
        if (lzs != null) {
            for (LiquidityZone lz : lzs) {
                if (lz.isSwept()) continue;

                // Check if liquidity was swept
                if (lz.isBuySide() && high > lz.getLevel()) {
                    lz.recordSweep(now, high);
                } else if (lz.isSellSide() && low < lz.getLevel()) {
                    lz.recordSweep(now, low);
                }
            }
        }
    }

    // ==================== HELPER METHODS ====================

    private boolean isBullishCandle(CandleData c) {
        return c.close > c.open;
    }

    private boolean isBearishCandle(CandleData c) {
        return c.close < c.open;
    }

    private boolean isBullishBreak(CandleData ob, CandleData next1, CandleData next2) {
        return next1.close > ob.high || next2.close > ob.high;
    }

    private boolean isBearishBreak(CandleData ob, CandleData next1, CandleData next2) {
        return next1.close < ob.low || next2.close < ob.low;
    }

    private OrderBlockStrength calculateOBStrength(double movePercent, double volume) {
        if (movePercent > 1.0) return OrderBlockStrength.STRONG;
        if (movePercent > 0.5) return OrderBlockStrength.MODERATE;
        return OrderBlockStrength.WEAK;
    }

    private FVGStrength calculateFVGStrength(double gapPercent, double volume) {
        if (gapPercent > 0.5) return FVGStrength.STRONG;
        if (gapPercent > 0.2) return FVGStrength.MODERATE;
        return FVGStrength.WEAK;
    }

    private void mergeOrderBlocks(String symbol, List<OrderBlock> newObs) {
        orderBlocks.computeIfAbsent(symbol, s -> new ArrayList<>()).addAll(newObs);
        trimList(orderBlocks.get(symbol), MAX_STRUCTURES);
    }

    private void mergeFairValueGaps(String symbol, List<FairValueGap> newFvgs) {
        fairValueGaps.computeIfAbsent(symbol, s -> new ArrayList<>()).addAll(newFvgs);
        trimList(fairValueGaps.get(symbol), MAX_STRUCTURES);
    }

    private void mergeLiquidityZones(String symbol, List<LiquidityZone> newLzs) {
        liquidityZones.computeIfAbsent(symbol, s -> new ArrayList<>()).addAll(newLzs);
        trimList(liquidityZones.get(symbol), MAX_STRUCTURES);
    }

    private <T> void trimList(List<T> list, int maxSize) {
        while (list.size() > maxSize) {
            list.remove(0);
        }
    }

    // ==================== PUBLIC GETTERS ====================

    public List<OrderBlock> getOrderBlocks(String symbol) {
        return orderBlocks.getOrDefault(symbol, Collections.emptyList());
    }

    public List<OrderBlock> getValidOrderBlocks(String symbol) {
        return orderBlocks.getOrDefault(symbol, Collections.emptyList())
            .stream().filter(OrderBlock::isValid).toList();
    }

    public List<FairValueGap> getFairValueGaps(String symbol) {
        return fairValueGaps.getOrDefault(symbol, Collections.emptyList());
    }

    public List<FairValueGap> getValidFairValueGaps(String symbol) {
        return fairValueGaps.getOrDefault(symbol, Collections.emptyList())
            .stream().filter(FairValueGap::isValid).toList();
    }

    public List<LiquidityZone> getLiquidityZones(String symbol) {
        return liquidityZones.getOrDefault(symbol, Collections.emptyList());
    }

    public List<LiquidityZone> getUnsweptLiquidityZones(String symbol) {
        return liquidityZones.getOrDefault(symbol, Collections.emptyList())
            .stream().filter(lz -> !lz.isSwept()).toList();
    }

    public MarketStructure getMarketStructure(String symbol) {
        return marketStructures.get(symbol);
    }

    // ==================== DATA CLASSES ====================

    @Data
    public static class CandleData {
        Instant timestamp;
        double open;
        double high;
        double low;
        double close;
        double volume;

        public CandleData(Instant timestamp, double open, double high, double low, double close, double volume) {
            this.timestamp = timestamp;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }
    }

    @Data
    public static class MarketStructure {
        double lastSwingHigh;
        double lastSwingLow;
        double currentPrice;
        Trend trend = Trend.RANGING;
        boolean isHigherHigh;
        boolean isHigherLow;
        boolean isLowerHigh;
        boolean isLowerLow;
        Instant lastUpdate;

        public enum Trend {
            BULLISH, BEARISH, RANGING
        }
    }

    @Data
    public static class SMCResult {
        List<OrderBlock> orderBlocks = new ArrayList<>();
        List<FairValueGap> fairValueGaps = new ArrayList<>();
        List<LiquidityZone> liquidityZones = new ArrayList<>();
        MarketStructure marketStructure;
    }
}
