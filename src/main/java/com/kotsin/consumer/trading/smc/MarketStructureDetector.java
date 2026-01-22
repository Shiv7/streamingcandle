package com.kotsin.consumer.trading.smc;

import com.kotsin.consumer.trading.smc.SmcContext.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MarketStructureDetector - Detects market structure for SMC trading
 *
 * Identifies:
 * 1. Swing Points (HH, HL, LH, LL)
 * 2. Market Bias (Bullish/Bearish/Ranging)
 * 3. BOS (Break of Structure) - Trend continuation
 * 4. CHoCH (Change of Character) - Potential reversal
 *
 * HOW REAL TRADERS USE THIS:
 * - Bullish structure (HH + HL): Only look for LONG setups
 * - Bearish structure (LH + LL): Only look for SHORT setups
 * - CHoCH signals potential reversal: Wait for confirmation before trading new direction
 */
@Slf4j
@Component
public class MarketStructureDetector {

    // Minimum candles needed for swing detection
    private static final int SWING_LOOKBACK = 3;

    // Tolerance for "equal" highs/lows (0.1%)
    private static final double EQUAL_TOLERANCE = 0.001;

    /**
     * Detect market structure from OHLC data
     *
     * @param highs  Array of high prices
     * @param lows   Array of low prices
     * @param closes Array of close prices
     * @param timestamps Array of timestamps
     * @return MarketStructureResult with all detected patterns
     */
    public MarketStructureResult detectStructure(double[] highs, double[] lows,
                                                   double[] closes, long[] timestamps) {
        if (highs.length < SWING_LOOKBACK * 2 + 1) {
            log.debug("[MKT_STRUCT_DEBUG] Not enough candles: {} (need {})",
                    highs.length, SWING_LOOKBACK * 2 + 1);
            return MarketStructureResult.empty();
        }

        // Step 1: Find all swing points
        List<SwingPoint> swingHighs = findSwingHighs(highs, timestamps);
        List<SwingPoint> swingLows = findSwingLows(lows, timestamps);

        log.debug("[MKT_STRUCT_DEBUG] From {} candles, found {} swing highs, {} swing lows",
                highs.length, swingHighs.size(), swingLows.size());

        // Step 2: Classify swing points (HH, HL, LH, LL)
        classifySwingPoints(swingHighs, swingLows);

        // Step 3: Determine market bias
        MarketBias bias = determineMarketBias(swingHighs, swingLows);

        // Step 4: Detect structure breaks (BOS/CHoCH)
        StructureBreak lastBreak = detectStructureBreak(swingHighs, swingLows, closes, timestamps);

        // Step 5: Calculate candles since last break
        int candlesSinceBreak = lastBreak != null ?
                calculateCandlesSince(lastBreak.getTimestamp(), timestamps) : Integer.MAX_VALUE;

        // Combine all swings and sort by time
        List<SwingPoint> allSwings = new ArrayList<>();
        allSwings.addAll(swingHighs);
        allSwings.addAll(swingLows);
        allSwings.sort((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));

        return MarketStructureResult.builder()
                .marketBias(bias)
                .swingHighs(swingHighs)
                .swingLows(swingLows)
                .allSwings(allSwings)
                .lastStructureBreak(lastBreak)
                .structureBullish(bias == MarketBias.BULLISH)
                .candlesSinceStructureBreak(candlesSinceBreak)
                .build();
    }

    /**
     * Find swing highs (local maxima)
     * A swing high is a high that is higher than the N candles before and after it
     */
    private List<SwingPoint> findSwingHighs(double[] highs, long[] timestamps) {
        List<SwingPoint> swingHighs = new ArrayList<>();

        for (int i = SWING_LOOKBACK; i < highs.length - SWING_LOOKBACK; i++) {
            boolean isSwingHigh = true;

            // Check if this high is higher than surrounding candles
            for (int j = 1; j <= SWING_LOOKBACK; j++) {
                if (highs[i] <= highs[i - j] || highs[i] <= highs[i + j]) {
                    isSwingHigh = false;
                    break;
                }
            }

            if (isSwingHigh) {
                swingHighs.add(SwingPoint.builder()
                        .price(highs[i])
                        .timestamp(timestamps[i])
                        .candleIndex(i)
                        .isHigh(true)
                        .broken(false)
                        .build());
            }
        }

        return swingHighs;
    }

    /**
     * Find swing lows (local minima)
     */
    private List<SwingPoint> findSwingLows(double[] lows, long[] timestamps) {
        List<SwingPoint> swingLows = new ArrayList<>();

        for (int i = SWING_LOOKBACK; i < lows.length - SWING_LOOKBACK; i++) {
            boolean isSwingLow = true;

            // Check if this low is lower than surrounding candles
            for (int j = 1; j <= SWING_LOOKBACK; j++) {
                if (lows[i] >= lows[i - j] || lows[i] >= lows[i + j]) {
                    isSwingLow = false;
                    break;
                }
            }

            if (isSwingLow) {
                swingLows.add(SwingPoint.builder()
                        .price(lows[i])
                        .timestamp(timestamps[i])
                        .candleIndex(i)
                        .isHigh(false)
                        .broken(false)
                        .build());
            }
        }

        return swingLows;
    }

    /**
     * Classify swing points as HH, HL, LH, LL
     */
    private void classifySwingPoints(List<SwingPoint> swingHighs, List<SwingPoint> swingLows) {
        // Classify swing highs
        for (int i = 1; i < swingHighs.size(); i++) {
            SwingPoint current = swingHighs.get(i);
            SwingPoint previous = swingHighs.get(i - 1);

            double diff = (current.getPrice() - previous.getPrice()) / previous.getPrice();

            if (diff > EQUAL_TOLERANCE) {
                current.setSwingType(SwingType.HIGHER_HIGH);
            } else if (diff < -EQUAL_TOLERANCE) {
                current.setSwingType(SwingType.LOWER_HIGH);
            } else {
                current.setSwingType(SwingType.EQUAL_HIGH);
            }
        }

        // Classify swing lows
        for (int i = 1; i < swingLows.size(); i++) {
            SwingPoint current = swingLows.get(i);
            SwingPoint previous = swingLows.get(i - 1);

            double diff = (current.getPrice() - previous.getPrice()) / previous.getPrice();

            if (diff > EQUAL_TOLERANCE) {
                current.setSwingType(SwingType.HIGHER_LOW);
            } else if (diff < -EQUAL_TOLERANCE) {
                current.setSwingType(SwingType.LOWER_LOW);
            } else {
                current.setSwingType(SwingType.EQUAL_LOW);
            }
        }
    }

    /**
     * Determine market bias based on swing structure.
     *
     * FIX: Changed from requiring PERFECT HH+HL or LH+LL to a scoring system.
     * The old algorithm marked 70%+ of instruments as RANGING because it required
     * BOTH conditions to be true simultaneously.
     *
     * New algorithm:
     * - Analyzes last 2-3 swing pairs
     * - Scores each pair as bullish (+1) or bearish (-1)
     * - Determines bias based on net score
     */
    private MarketBias determineMarketBias(List<SwingPoint> swingHighs, List<SwingPoint> swingLows) {
        if (swingHighs.size() < 2 || swingLows.size() < 2) {
            log.debug("[MKT_STRUCT_DEBUG] Insufficient swings for bias: swingHighs={}, swingLows={} (need 2+ each)",
                    swingHighs.size(), swingLows.size());
            return MarketBias.UNKNOWN;
        }

        // Look at last 2-3 swing pairs for more robust detection
        int lookback = Math.min(3, Math.min(swingHighs.size(), swingLows.size()));

        int bullishScore = 0;
        int bearishScore = 0;

        // Analyze swing highs
        for (int i = swingHighs.size() - lookback; i < swingHighs.size() - 1; i++) {
            SwingPoint current = swingHighs.get(i + 1);
            SwingPoint previous = swingHighs.get(i);
            double diff = (current.getPrice() - previous.getPrice()) / previous.getPrice();

            if (diff > EQUAL_TOLERANCE) bullishScore++;      // Higher high
            else if (diff < -EQUAL_TOLERANCE) bearishScore++; // Lower high
            // Equal highs don't change score
        }

        // Analyze swing lows
        for (int i = swingLows.size() - lookback; i < swingLows.size() - 1; i++) {
            SwingPoint current = swingLows.get(i + 1);
            SwingPoint previous = swingLows.get(i);
            double diff = (current.getPrice() - previous.getPrice()) / previous.getPrice();

            if (diff > EQUAL_TOLERANCE) bullishScore++;      // Higher low
            else if (diff < -EQUAL_TOLERANCE) bearishScore++; // Lower low
        }

        log.debug("[MKT_STRUCT_DEBUG] Bias scoring | bullishScore={} | bearishScore={} | lookback={}",
                bullishScore, bearishScore, lookback);

        // Determine bias based on net score
        int netScore = bullishScore - bearishScore;

        // FIX: More lenient thresholds - net score of 1+ is enough
        // (previously required BOTH HH+HL which is net score of 2+)
        if (netScore >= 1) {
            return MarketBias.BULLISH;
        }
        if (netScore <= -1) {
            return MarketBias.BEARISH;
        }

        // Truly mixed/flat structure
        return MarketBias.RANGING;
    }

    /**
     * Detect BOS (Break of Structure) or CHoCH (Change of Character)
     *
     * BOS: Break in direction of current trend (continuation)
     *   - Bullish BOS: Price breaks above previous swing high
     *   - Bearish BOS: Price breaks below previous swing low
     *
     * CHoCH: Break against current trend (reversal signal)
     *   - Bullish CHoCH: In downtrend, price breaks above previous swing high
     *   - Bearish CHoCH: In uptrend, price breaks below previous swing low
     */
    private StructureBreak detectStructureBreak(List<SwingPoint> swingHighs,
                                                  List<SwingPoint> swingLows,
                                                  double[] closes,
                                                  long[] timestamps) {
        if (swingHighs.isEmpty() || swingLows.isEmpty() || closes.length == 0) {
            return null;
        }

        double currentPrice = closes[closes.length - 1];
        long currentTime = timestamps[timestamps.length - 1];

        // Find the most recent unbroken swing high and low
        SwingPoint lastUnbrokenHigh = null;
        SwingPoint lastUnbrokenLow = null;

        for (int i = swingHighs.size() - 1; i >= 0; i--) {
            if (!swingHighs.get(i).isBroken()) {
                lastUnbrokenHigh = swingHighs.get(i);
                break;
            }
        }

        for (int i = swingLows.size() - 1; i >= 0; i--) {
            if (!swingLows.get(i).isBroken()) {
                lastUnbrokenLow = swingLows.get(i);
                break;
            }
        }

        // Determine current structure
        MarketBias currentBias = determineMarketBias(swingHighs, swingLows);

        // Check for break above swing high
        if (lastUnbrokenHigh != null && currentPrice > lastUnbrokenHigh.getPrice()) {
            lastUnbrokenHigh.setBroken(true);

            // Is this BOS or CHoCH?
            StructureType type;
            if (currentBias == MarketBias.BEARISH) {
                // Breaking high in downtrend = CHoCH (potential reversal to bullish)
                type = StructureType.CHOCH;
                log.info("[SMC] CHoCH BULLISH detected! Price {} broke above swing high {}",
                        currentPrice, lastUnbrokenHigh.getPrice());
            } else {
                // Breaking high in uptrend = BOS (continuation)
                type = StructureType.BOS;
                log.debug("[SMC] BOS BULLISH: Price {} broke above swing high {}",
                        currentPrice, lastUnbrokenHigh.getPrice());
            }

            return StructureBreak.builder()
                    .type(type)
                    .breakPrice(currentPrice)
                    .swingPrice(lastUnbrokenHigh.getPrice())
                    .timestamp(currentTime)
                    .bullish(true)
                    .strength(calculateBreakStrength(currentPrice, lastUnbrokenHigh.getPrice()))
                    .build();
        }

        // Check for break below swing low
        if (lastUnbrokenLow != null && currentPrice < lastUnbrokenLow.getPrice()) {
            lastUnbrokenLow.setBroken(true);

            // Is this BOS or CHoCH?
            StructureType type;
            if (currentBias == MarketBias.BULLISH) {
                // Breaking low in uptrend = CHoCH (potential reversal to bearish)
                type = StructureType.CHOCH;
                log.info("[SMC] CHoCH BEARISH detected! Price {} broke below swing low {}",
                        currentPrice, lastUnbrokenLow.getPrice());
            } else {
                // Breaking low in downtrend = BOS (continuation)
                type = StructureType.BOS;
                log.debug("[SMC] BOS BEARISH: Price {} broke below swing low {}",
                        currentPrice, lastUnbrokenLow.getPrice());
            }

            return StructureBreak.builder()
                    .type(type)
                    .breakPrice(currentPrice)
                    .swingPrice(lastUnbrokenLow.getPrice())
                    .timestamp(currentTime)
                    .bullish(false)
                    .strength(calculateBreakStrength(currentPrice, lastUnbrokenLow.getPrice()))
                    .build();
        }

        return null;
    }

    private int calculateBreakStrength(double breakPrice, double swingPrice) {
        double breakPct = Math.abs(breakPrice - swingPrice) / swingPrice * 100;
        if (breakPct > 0.5) return 3;  // Strong break
        if (breakPct > 0.2) return 2;  // Medium break
        return 1;                       // Weak break
    }

    private int calculateCandlesSince(long timestamp, long[] timestamps) {
        for (int i = timestamps.length - 1; i >= 0; i--) {
            if (timestamps[i] <= timestamp) {
                return timestamps.length - 1 - i;
            }
        }
        return timestamps.length;
    }

    // ============ RESULT CLASS ============

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class MarketStructureResult {
        private MarketBias marketBias;
        private List<SwingPoint> swingHighs;
        private List<SwingPoint> swingLows;
        private List<SwingPoint> allSwings;
        private StructureBreak lastStructureBreak;
        private boolean structureBullish;
        private int candlesSinceStructureBreak;

        public static MarketStructureResult empty() {
            return MarketStructureResult.builder()
                    .marketBias(MarketBias.UNKNOWN)
                    .swingHighs(new ArrayList<>())
                    .swingLows(new ArrayList<>())
                    .allSwings(new ArrayList<>())
                    .structureBullish(false)
                    .candlesSinceStructureBreak(Integer.MAX_VALUE)
                    .build();
        }
    }
}
