package com.kotsin.consumer.trading.smc;

import com.kotsin.consumer.trading.smc.SmcContext.OrderBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * OrderBlockDetector - Detects Order Blocks (Supply/Demand Zones) for SMC trading
 *
 * ORDER BLOCK DEFINITION:
 * - Bullish OB (Demand): The LAST bearish candle before a strong bullish move
 *   → This is where smart money placed their buy orders
 *   → Price is likely to return here for a retest before continuing up
 *
 * - Bearish OB (Supply): The LAST bullish candle before a strong bearish move
 *   → This is where smart money placed their sell orders
 *   → Price is likely to return here for a retest before continuing down
 *
 * HOW REAL TRADERS USE ORDER BLOCKS:
 * 1. Identify the OB after a strong move
 * 2. Wait for price to return (retest)
 * 3. Enter at the OB with stop below/above
 * 4. Target: The high/low that was made after the OB
 */
@Slf4j
@Component
public class OrderBlockDetector {

    // Minimum move after OB to be considered valid (as % of price)
    private static final double MIN_MOVE_PCT = 0.3;

    // Maximum age of OB in candles (older OBs lose strength)
    private static final int MAX_OB_AGE = 100;

    // OB is considered tested if price enters within this % of the zone
    private static final double TEST_TOLERANCE_PCT = 0.1;

    /**
     * Detect order blocks from OHLC data
     *
     * @param opens     Array of open prices
     * @param highs     Array of high prices
     * @param lows      Array of low prices
     * @param closes    Array of close prices
     * @param timestamps Array of timestamps
     * @param timeframe  Timeframe string for logging
     * @return OrderBlockResult with all detected OBs
     */
    public OrderBlockResult detectOrderBlocks(double[] opens, double[] highs, double[] lows,
                                                double[] closes, long[] timestamps, String timeframe) {
        List<OrderBlock> demandZones = new ArrayList<>();
        List<OrderBlock> supplyZones = new ArrayList<>();

        if (closes.length < 5) {
            return new OrderBlockResult(demandZones, supplyZones, null, null);
        }

        double currentPrice = closes[closes.length - 1];

        // Scan for order blocks
        for (int i = 2; i < closes.length - 2; i++) {
            // Check for BULLISH ORDER BLOCK (Demand)
            // Pattern: Bearish candle followed by strong bullish move
            if (isBearishCandle(opens[i], closes[i])) {
                // Check if there's a strong move up after this candle
                double moveUp = findMoveAfterCandle(highs, i, true);
                double movePct = moveUp / closes[i] * 100;

                if (movePct >= MIN_MOVE_PCT) {
                    OrderBlock ob = OrderBlock.builder()
                            .top(highs[i])
                            .bottom(lows[i])
                            .midpoint((highs[i] + lows[i]) / 2)
                            .timestamp(timestamps[i])
                            .candleIndex(i)
                            .isBullish(true)
                            .tested(false)
                            .testCount(0)
                            .broken(false)
                            .strength(calculateOBStrength(movePct, closes.length - i))
                            .timeframe(timeframe)
                            .build();

                    // Check if OB has been tested or broken
                    updateOBStatus(ob, highs, lows, i + 1, closes.length);

                    if (!ob.isBroken()) {
                        demandZones.add(ob);
                    }
                }
            }

            // Check for BEARISH ORDER BLOCK (Supply)
            // Pattern: Bullish candle followed by strong bearish move
            if (isBullishCandle(opens[i], closes[i])) {
                // Check if there's a strong move down after this candle
                double moveDown = findMoveAfterCandle(lows, i, false);
                double movePct = moveDown / closes[i] * 100;

                if (movePct >= MIN_MOVE_PCT) {
                    OrderBlock ob = OrderBlock.builder()
                            .top(highs[i])
                            .bottom(lows[i])
                            .midpoint((highs[i] + lows[i]) / 2)
                            .timestamp(timestamps[i])
                            .candleIndex(i)
                            .isBullish(false)
                            .tested(false)
                            .testCount(0)
                            .broken(false)
                            .strength(calculateOBStrength(movePct, closes.length - i))
                            .timeframe(timeframe)
                            .build();

                    // Check if OB has been tested or broken
                    updateOBStatus(ob, highs, lows, i + 1, closes.length);

                    if (!ob.isBroken()) {
                        supplyZones.add(ob);
                    }
                }
            }
        }

        // Sort by distance from current price and take most recent/strongest
        demandZones.sort((a, b) -> Double.compare(
                Math.abs(currentPrice - a.getMidpoint()),
                Math.abs(currentPrice - b.getMidpoint())));
        supplyZones.sort((a, b) -> Double.compare(
                Math.abs(currentPrice - a.getMidpoint()),
                Math.abs(currentPrice - b.getMidpoint())));

        // Find nearest demand below price and nearest supply above price
        OrderBlock nearestDemand = demandZones.stream()
                .filter(ob -> ob.getTop() < currentPrice)
                .findFirst()
                .orElse(null);

        OrderBlock nearestSupply = supplyZones.stream()
                .filter(ob -> ob.getBottom() > currentPrice)
                .findFirst()
                .orElse(null);

        // Log significant OBs
        if (nearestDemand != null) {
            log.debug("[SMC_OB] {} Nearest DEMAND: {}-{} (strength={}, tested={})",
                    timeframe, nearestDemand.getBottom(), nearestDemand.getTop(),
                    nearestDemand.getStrength(), nearestDemand.isTested());
        }
        if (nearestSupply != null) {
            log.debug("[SMC_OB] {} Nearest SUPPLY: {}-{} (strength={}, tested={})",
                    timeframe, nearestSupply.getBottom(), nearestSupply.getTop(),
                    nearestSupply.getStrength(), nearestSupply.isTested());
        }

        return new OrderBlockResult(demandZones, supplyZones, nearestDemand, nearestSupply);
    }

    /**
     * Check if candle is bearish (close < open)
     */
    private boolean isBearishCandle(double open, double close) {
        return close < open;
    }

    /**
     * Check if candle is bullish (close > open)
     */
    private boolean isBullishCandle(double open, double close) {
        return close > open;
    }

    /**
     * Find the size of the move after a candle
     * For bullish OB: find the highest high reached
     * For bearish OB: find the lowest low reached
     */
    private double findMoveAfterCandle(double[] prices, int startIndex, boolean findHigh) {
        double extremePrice = prices[startIndex];
        int lookAhead = Math.min(10, prices.length - startIndex - 1); // Look ahead 10 candles max

        for (int i = startIndex + 1; i <= startIndex + lookAhead; i++) {
            if (findHigh) {
                extremePrice = Math.max(extremePrice, prices[i]);
            } else {
                extremePrice = Math.min(extremePrice, prices[i]);
            }
        }

        return Math.abs(extremePrice - prices[startIndex]);
    }

    /**
     * Update OB status (tested/broken) based on subsequent price action
     */
    private void updateOBStatus(OrderBlock ob, double[] highs, double[] lows,
                                  int startIndex, int endIndex) {
        for (int i = startIndex; i < endIndex; i++) {
            // Check if price entered the OB zone
            if (ob.isBullish()) {
                // For demand zone: check if low entered the zone
                if (lows[i] <= ob.getTop() && lows[i] >= ob.getBottom()) {
                    ob.setTested(true);
                    ob.setTestCount(ob.getTestCount() + 1);
                }
                // Broken if price closed below the OB
                if (lows[i] < ob.getBottom() * 0.998) { // Small buffer
                    ob.setBroken(true);
                    break;
                }
            } else {
                // For supply zone: check if high entered the zone
                if (highs[i] >= ob.getBottom() && highs[i] <= ob.getTop()) {
                    ob.setTested(true);
                    ob.setTestCount(ob.getTestCount() + 1);
                }
                // Broken if price closed above the OB
                if (highs[i] > ob.getTop() * 1.002) { // Small buffer
                    ob.setBroken(true);
                    break;
                }
            }
        }
    }

    /**
     * Calculate OB strength (0-1)
     * Based on: move size after OB, recency
     */
    private double calculateOBStrength(double movePct, int age) {
        // Move strength: 0.3% = 0.5, 1% = 1.0
        double moveStrength = Math.min(1.0, movePct / 1.0);

        // Age decay: fresh OB = 1.0, old OB = lower
        double ageFactor = Math.max(0.3, 1.0 - (age / (double) MAX_OB_AGE));

        return moveStrength * ageFactor;
    }

    /**
     * Check if current price is in any order block
     */
    public OrderBlock findCurrentOrderBlock(List<OrderBlock> allOBs, double currentPrice) {
        for (OrderBlock ob : allOBs) {
            if (ob.contains(currentPrice)) {
                return ob;
            }
        }
        return null;
    }

    // ============ RESULT CLASS ============

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class OrderBlockResult {
        private List<OrderBlock> demandZones;
        private List<OrderBlock> supplyZones;
        private OrderBlock nearestDemand;
        private OrderBlock nearestSupply;

        public List<OrderBlock> getAllOrderBlocks() {
            List<OrderBlock> all = new ArrayList<>();
            all.addAll(demandZones);
            all.addAll(supplyZones);
            return all;
        }
    }
}
