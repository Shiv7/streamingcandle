package com.kotsin.consumer.trading.smc;

import com.kotsin.consumer.trading.smc.SmcContext.FairValueGap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * FairValueGapDetector - Detects Fair Value Gaps (FVG) / Imbalances for SMC trading
 *
 * FVG DEFINITION:
 * A Fair Value Gap is an IMBALANCE in price created by aggressive buying/selling.
 * It's a 3-candle pattern where the wicks of candle 1 and candle 3 don't overlap,
 * leaving a "gap" that price is likely to fill.
 *
 * BULLISH FVG (price likely to fill from above):
 *   Candle 1: Any candle
 *   Candle 2: Large bullish candle (creates the gap)
 *   Candle 3: Low is ABOVE Candle 1's high
 *   GAP = Candle 3 low - Candle 1 high
 *
 * BEARISH FVG (price likely to fill from below):
 *   Candle 1: Any candle
 *   Candle 2: Large bearish candle (creates the gap)
 *   Candle 3: High is BELOW Candle 1's low
 *   GAP = Candle 1 low - Candle 3 high
 *
 * HOW REAL TRADERS USE FVGs:
 * 1. Identify FVG after impulsive move
 * 2. Wait for price to return to fill the gap (50% or full)
 * 3. Enter at the FVG with stop beyond the opposite side
 * 4. FVG acts as support/resistance until filled
 */
@Slf4j
@Component
public class FairValueGapDetector {

    // Minimum FVG size as % of price to be considered valid
    private static final double MIN_FVG_SIZE_PCT = 0.1;

    // Maximum age of FVG in candles
    private static final int MAX_FVG_AGE = 50;

    /**
     * Detect fair value gaps from OHLC data
     *
     * @param highs     Array of high prices
     * @param lows      Array of low prices
     * @param closes    Array of close prices
     * @param timestamps Array of timestamps
     * @param timeframe  Timeframe string for logging
     * @return FVGResult with all detected FVGs
     */
    public FVGResult detectFVGs(double[] highs, double[] lows, double[] closes,
                                  long[] timestamps, String timeframe) {
        List<FairValueGap> bullishFvgs = new ArrayList<>();
        List<FairValueGap> bearishFvgs = new ArrayList<>();

        if (closes.length < 3) {
            return new FVGResult(bullishFvgs, bearishFvgs, null);
        }

        double currentPrice = closes[closes.length - 1];

        // Scan for FVGs (need at least 3 candles)
        for (int i = 0; i < closes.length - 2; i++) {
            int candle1 = i;
            int candle2 = i + 1;
            int candle3 = i + 2;

            // Check for BULLISH FVG
            // Candle 3's low is above Candle 1's high = gap up
            if (lows[candle3] > highs[candle1]) {
                double gapTop = lows[candle3];
                double gapBottom = highs[candle1];
                double gapSize = gapTop - gapBottom;
                double gapSizePct = gapSize / closes[candle2] * 100;

                if (gapSizePct >= MIN_FVG_SIZE_PCT) {
                    FairValueGap fvg = FairValueGap.builder()
                            .top(gapTop)
                            .bottom(gapBottom)
                            .timestamp(timestamps[candle2])
                            .candleIndex(candle2)
                            .isBullish(true)
                            .filled(false)
                            .fillPercentage(0)
                            .timeframe(timeframe)
                            .build();

                    // Check if FVG has been filled
                    updateFVGStatus(fvg, highs, lows, candle3, closes.length);

                    if (!fvg.isFilled()) {
                        bullishFvgs.add(fvg);
                    }
                }
            }

            // Check for BEARISH FVG
            // Candle 3's high is below Candle 1's low = gap down
            if (highs[candle3] < lows[candle1]) {
                double gapTop = lows[candle1];
                double gapBottom = highs[candle3];
                double gapSize = gapTop - gapBottom;
                double gapSizePct = gapSize / closes[candle2] * 100;

                if (gapSizePct >= MIN_FVG_SIZE_PCT) {
                    FairValueGap fvg = FairValueGap.builder()
                            .top(gapTop)
                            .bottom(gapBottom)
                            .timestamp(timestamps[candle2])
                            .candleIndex(candle2)
                            .isBullish(false)
                            .filled(false)
                            .fillPercentage(0)
                            .timeframe(timeframe)
                            .build();

                    // Check if FVG has been filled
                    updateFVGStatus(fvg, highs, lows, candle3, closes.length);

                    if (!fvg.isFilled()) {
                        bearishFvgs.add(fvg);
                    }
                }
            }
        }

        // Sort by distance from current price
        bullishFvgs.sort((a, b) -> Double.compare(
                Math.abs(currentPrice - a.getMidpoint()),
                Math.abs(currentPrice - b.getMidpoint())));
        bearishFvgs.sort((a, b) -> Double.compare(
                Math.abs(currentPrice - a.getMidpoint()),
                Math.abs(currentPrice - b.getMidpoint())));

        // Find nearest unfilled FVG
        FairValueGap nearestFvg = null;
        double nearestDistance = Double.MAX_VALUE;

        for (FairValueGap fvg : bullishFvgs) {
            double distance = Math.abs(currentPrice - fvg.getMidpoint());
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestFvg = fvg;
            }
        }
        for (FairValueGap fvg : bearishFvgs) {
            double distance = Math.abs(currentPrice - fvg.getMidpoint());
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestFvg = fvg;
            }
        }

        // Log significant FVGs
        if (!bullishFvgs.isEmpty()) {
            log.debug("[SMC_FVG] {} Found {} bullish FVGs (unfilled)", timeframe, bullishFvgs.size());
        }
        if (!bearishFvgs.isEmpty()) {
            log.debug("[SMC_FVG] {} Found {} bearish FVGs (unfilled)", timeframe, bearishFvgs.size());
        }

        return new FVGResult(bullishFvgs, bearishFvgs, nearestFvg);
    }

    /**
     * Update FVG fill status based on subsequent price action
     */
    private void updateFVGStatus(FairValueGap fvg, double[] highs, double[] lows,
                                   int startIndex, int endIndex) {
        double gapSize = fvg.getTop() - fvg.getBottom();
        double maxFill = 0;

        for (int i = startIndex; i < endIndex; i++) {
            double fill = 0;

            if (fvg.isBullish()) {
                // Bullish FVG: check if price came down to fill
                if (lows[i] <= fvg.getTop()) {
                    double penetration = fvg.getTop() - lows[i];
                    fill = Math.min(100, (penetration / gapSize) * 100);
                }
            } else {
                // Bearish FVG: check if price came up to fill
                if (highs[i] >= fvg.getBottom()) {
                    double penetration = highs[i] - fvg.getBottom();
                    fill = Math.min(100, (penetration / gapSize) * 100);
                }
            }

            maxFill = Math.max(maxFill, fill);
        }

        fvg.setFillPercentage(maxFill);
        fvg.setFilled(maxFill >= 100);
    }

    /**
     * Check if price is currently in an FVG
     */
    public FairValueGap findCurrentFVG(List<FairValueGap> allFvgs, double currentPrice) {
        for (FairValueGap fvg : allFvgs) {
            if (fvg.contains(currentPrice)) {
                return fvg;
            }
        }
        return null;
    }

    // ============ RESULT CLASS ============

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class FVGResult {
        private List<FairValueGap> bullishFvgs;
        private List<FairValueGap> bearishFvgs;
        private FairValueGap nearestFvg;

        public List<FairValueGap> getAllFVGs() {
            List<FairValueGap> all = new ArrayList<>();
            all.addAll(bullishFvgs);
            all.addAll(bearishFvgs);
            return all;
        }

        public boolean hasFVGs() {
            return !bullishFvgs.isEmpty() || !bearishFvgs.isEmpty();
        }
    }
}
