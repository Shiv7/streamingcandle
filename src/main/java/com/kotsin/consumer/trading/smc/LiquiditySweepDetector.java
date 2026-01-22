package com.kotsin.consumer.trading.smc;

import com.kotsin.consumer.trading.smc.SmcContext.LiquidityLevel;
import com.kotsin.consumer.trading.smc.SmcContext.LiquiditySweep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LiquiditySweepDetector - Detects Liquidity Pools and Sweeps for SMC trading
 *
 * LIQUIDITY CONCEPT:
 * Stop losses cluster around swing highs and lows:
 * - Buy-side liquidity: Above swing highs (where shorts have stops)
 * - Sell-side liquidity: Below swing lows (where longs have stops)
 *
 * Smart money HUNTS this liquidity before reversing:
 * - Price sweeps above high, grabs stops, then drops = SELL signal
 * - Price sweeps below low, grabs stops, then rallies = BUY signal
 *
 * SWEEP PATTERN:
 * 1. Price makes a new high/low (taking out stops)
 * 2. BUT closes back inside the range
 * 3. This creates a "wick" that shows stop hunting
 *
 * HOW REAL TRADERS USE LIQUIDITY:
 * 1. Identify key swing highs/lows (liquidity pools)
 * 2. WAIT for price to sweep these levels
 * 3. Enter OPPOSITE direction after the sweep
 * 4. Stop goes beyond the sweep wick
 * 5. Target is the opposite liquidity pool
 */
@Slf4j
@Component
public class LiquiditySweepDetector {

    // Minimum wick size to be considered a sweep (as % of candle range)
    private static final double MIN_WICK_PCT = 30;

    // How many candles to look back for liquidity levels
    private static final int LIQUIDITY_LOOKBACK = 20;

    // Equal high/low tolerance
    private static final double EQUAL_TOLERANCE_PCT = 0.1;

    /**
     * Detect liquidity levels and sweeps
     *
     * @param highs     Array of high prices
     * @param lows      Array of low prices
     * @param opens     Array of open prices
     * @param closes    Array of close prices
     * @param timestamps Array of timestamps
     * @return LiquidityResult with all detected levels and sweeps
     */
    public LiquidityResult detectLiquidity(double[] highs, double[] lows, double[] opens,
                                            double[] closes, long[] timestamps) {
        if (closes.length < LIQUIDITY_LOOKBACK) {
            return LiquidityResult.empty();
        }

        // Step 1: Find liquidity levels (swing highs/lows with multiple touches)
        List<LiquidityLevel> buySideLiquidity = findBuySideLiquidity(highs, timestamps);
        List<LiquidityLevel> sellSideLiquidity = findSellSideLiquidity(lows, timestamps);

        // Step 2: Detect recent sweeps
        List<LiquiditySweep> recentSweeps = detectSweeps(highs, lows, opens, closes, timestamps,
                buySideLiquidity, sellSideLiquidity);

        // Step 3: Check if liquidity was just swept (in last 3 candles)
        LiquiditySweep lastSweep = recentSweeps.isEmpty() ? null :
                recentSweeps.get(recentSweeps.size() - 1);
        boolean liquidityJustSwept = lastSweep != null &&
                (timestamps.length - getIndexForTimestamp(lastSweep.getTimestamp(), timestamps)) <= 3;

        if (liquidityJustSwept) {
            log.info("[SMC_LIQ] LIQUIDITY SWEPT! {} @ {} | Sweep={} | Close={}",
                    lastSweep.isBuySide() ? "BUY-SIDE" : "SELL-SIDE",
                    lastSweep.getLiquidityLevel(),
                    lastSweep.getSweepPrice(),
                    lastSweep.getClosePrice());
        }

        return LiquidityResult.builder()
                .buySideLiquidity(buySideLiquidity)
                .sellSideLiquidity(sellSideLiquidity)
                .recentSweeps(recentSweeps)
                .lastSweep(lastSweep)
                .liquidityJustSwept(liquidityJustSwept)
                .build();
    }

    /**
     * Find buy-side liquidity (swing highs where stops are clustered)
     */
    private List<LiquidityLevel> findBuySideLiquidity(double[] highs, long[] timestamps) {
        List<LiquidityLevel> levels = new ArrayList<>();

        // Find swing highs
        for (int i = 2; i < highs.length - 2; i++) {
            if (highs[i] > highs[i - 1] && highs[i] > highs[i - 2] &&
                    highs[i] > highs[i + 1] && highs[i] > highs[i + 2]) {

                // Check for equal highs (more liquidity = stronger level)
                int touchCount = countTouches(highs, highs[i], i, EQUAL_TOLERANCE_PCT);

                levels.add(LiquidityLevel.builder()
                        .price(highs[i])
                        .timestamp(timestamps[i])
                        .candleIndex(i)
                        .isBuySide(true)
                        .swept(false)
                        .touchCount(touchCount)
                        .strength(calculateLevelStrength(touchCount, highs.length - i))
                        .build());
            }
        }

        return levels;
    }

    /**
     * Find sell-side liquidity (swing lows where stops are clustered)
     */
    private List<LiquidityLevel> findSellSideLiquidity(double[] lows, long[] timestamps) {
        List<LiquidityLevel> levels = new ArrayList<>();

        // Find swing lows
        for (int i = 2; i < lows.length - 2; i++) {
            if (lows[i] < lows[i - 1] && lows[i] < lows[i - 2] &&
                    lows[i] < lows[i + 1] && lows[i] < lows[i + 2]) {

                // Check for equal lows (more liquidity = stronger level)
                int touchCount = countTouches(lows, lows[i], i, EQUAL_TOLERANCE_PCT);

                levels.add(LiquidityLevel.builder()
                        .price(lows[i])
                        .timestamp(timestamps[i])
                        .candleIndex(i)
                        .isBuySide(false)
                        .swept(false)
                        .touchCount(touchCount)
                        .strength(calculateLevelStrength(touchCount, lows.length - i))
                        .build());
            }
        }

        return levels;
    }

    /**
     * Count how many times price touched a level (equal highs/lows = more liquidity)
     */
    private int countTouches(double[] prices, double level, int startIndex, double tolerancePct) {
        int count = 1; // The level itself counts as 1
        double tolerance = level * tolerancePct / 100;

        for (int i = 0; i < prices.length; i++) {
            if (i != startIndex && Math.abs(prices[i] - level) <= tolerance) {
                count++;
            }
        }

        return count;
    }

    /**
     * Detect liquidity sweeps (stop hunts)
     */
    private List<LiquiditySweep> detectSweeps(double[] highs, double[] lows, double[] opens,
                                                double[] closes, long[] timestamps,
                                                List<LiquidityLevel> buySideLiq,
                                                List<LiquidityLevel> sellSideLiq) {
        List<LiquiditySweep> sweeps = new ArrayList<>();

        for (int i = 1; i < closes.length; i++) {
            // Check for BUY-SIDE sweep (wick above, close inside)
            for (LiquidityLevel level : buySideLiq) {
                if (!level.isSwept() && level.getCandleIndex() < i) {
                    // Price went above the level
                    if (highs[i] > level.getPrice()) {
                        // But closed below the level (sweep confirmed)
                        if (closes[i] < level.getPrice()) {
                            // Calculate wick size
                            double candleRange = highs[i] - lows[i];
                            double upperWick = highs[i] - Math.max(opens[i], closes[i]);
                            double wickPct = candleRange > 0 ? (upperWick / candleRange) * 100 : 0;

                            if (wickPct >= MIN_WICK_PCT) {
                                level.setSwept(true);

                                sweeps.add(LiquiditySweep.builder()
                                        .liquidityLevel(level.getPrice())
                                        .sweepPrice(highs[i])
                                        .closePrice(closes[i])
                                        .timestamp(timestamps[i])
                                        .isBuySide(true)
                                        .validReversal(closes[i] < opens[i]) // Bearish close after sweep
                                        .wickSize(upperWick)
                                        .build());

                                log.debug("[SMC_LIQ] BUY-SIDE SWEEP @ {} | Sweep={} | Close={}",
                                        level.getPrice(), highs[i], closes[i]);
                            }
                        }
                    }
                }
            }

            // Check for SELL-SIDE sweep (wick below, close inside)
            for (LiquidityLevel level : sellSideLiq) {
                if (!level.isSwept() && level.getCandleIndex() < i) {
                    // Price went below the level
                    if (lows[i] < level.getPrice()) {
                        // But closed above the level (sweep confirmed)
                        if (closes[i] > level.getPrice()) {
                            // Calculate wick size
                            double candleRange = highs[i] - lows[i];
                            double lowerWick = Math.min(opens[i], closes[i]) - lows[i];
                            double wickPct = candleRange > 0 ? (lowerWick / candleRange) * 100 : 0;

                            if (wickPct >= MIN_WICK_PCT) {
                                level.setSwept(true);

                                sweeps.add(LiquiditySweep.builder()
                                        .liquidityLevel(level.getPrice())
                                        .sweepPrice(lows[i])
                                        .closePrice(closes[i])
                                        .timestamp(timestamps[i])
                                        .isBuySide(false)
                                        .validReversal(closes[i] > opens[i]) // Bullish close after sweep
                                        .wickSize(lowerWick)
                                        .build());

                                log.debug("[SMC_LIQ] SELL-SIDE SWEEP @ {} | Sweep={} | Close={}",
                                        level.getPrice(), lows[i], closes[i]);
                            }
                        }
                    }
                }
            }
        }

        return sweeps;
    }

    private double calculateLevelStrength(int touchCount, int age) {
        // More touches = stronger level
        double touchStrength = Math.min(1.0, touchCount / 3.0);
        // Fresher levels are stronger
        double ageFactor = Math.max(0.3, 1.0 - (age / 50.0));
        return touchStrength * ageFactor;
    }

    private int getIndexForTimestamp(long timestamp, long[] timestamps) {
        for (int i = timestamps.length - 1; i >= 0; i--) {
            if (timestamps[i] == timestamp) {
                return i;
            }
        }
        return timestamps.length;
    }

    // ============ RESULT CLASS ============

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class LiquidityResult {
        private List<LiquidityLevel> buySideLiquidity;
        private List<LiquidityLevel> sellSideLiquidity;
        private List<LiquiditySweep> recentSweeps;
        private LiquiditySweep lastSweep;
        private boolean liquidityJustSwept;

        public static LiquidityResult empty() {
            return LiquidityResult.builder()
                    .buySideLiquidity(new ArrayList<>())
                    .sellSideLiquidity(new ArrayList<>())
                    .recentSweeps(new ArrayList<>())
                    .liquidityJustSwept(false)
                    .build();
        }
    }
}
