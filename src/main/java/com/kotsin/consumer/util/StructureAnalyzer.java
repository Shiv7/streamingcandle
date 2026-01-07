package com.kotsin.consumer.util;

import com.kotsin.consumer.model.UnifiedCandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * StructureAnalyzer - Utility for analyzing market structure
 *
 * Implements MASTER ARCHITECTURE Structure Quality calculation:
 * - Detects HigherHigh/HigherLow patterns (bullish structure)
 * - Detects LowerHigh/LowerLow patterns (bearish structure)
 * - Multi-timeframe analysis (30m + 1D)
 *
 * Formula:
 * Structure_Quality = 0.5 * Structure_30m + 0.5 * Structure_1D
 * Where Structure_tf = +1 (HH & HL), -1 (LH & LL), 0 (otherwise)
 *
 * Override: If structure flipped within last 3 bars, reduce magnitude by 0.7
 */
@Slf4j
@Component
public class StructureAnalyzer {

    private static final int MIN_CANDLES_FOR_ANALYSIS = 10;
    private static final int SWING_LOOKBACK = 5;  // Look back 5 bars for swing points
    private static final int FLIP_DETECTION_BARS = 3;

    /**
     * Calculate structure quality from multi-timeframe candles
     *
     * @param candles30m 30-minute candle history (most recent last)
     * @param candles1D Daily candle history (most recent last)
     * @return Structure quality score [-1.0, +1.0]
     */
    public double calculateStructureQuality(List<UnifiedCandle> candles30m,
                                           List<UnifiedCandle> candles1D) {

        if (candles30m == null || candles30m.size() < MIN_CANDLES_FOR_ANALYSIS) {
            log.debug("Insufficient 30m candles for structure analysis: {}",
                candles30m != null ? candles30m.size() : 0);
            return 0.0;
        }

        if (candles1D == null || candles1D.size() < MIN_CANDLES_FOR_ANALYSIS) {
            log.debug("Insufficient 1D candles for structure analysis: {}",
                candles1D != null ? candles1D.size() : 0);
            return 0.0;
        }

        // Analyze structure on each timeframe
        double structure30m = analyzeStructure(candles30m, "30m");
        double structure1D = analyzeStructure(candles1D, "1D");

        log.debug("Structure analysis: 30m={}, 1D={}", structure30m, structure1D);

        // Multi-timeframe combination (equal weights)
        double structureQuality = 0.5 * structure30m + 0.5 * structure1D;

        // Override: If structure flipped recently, reduce magnitude
        if (hasRecentFlip(candles30m, FLIP_DETECTION_BARS)) {
            log.debug("Recent structure flip detected within {} bars, applying 0.7 reduction",
                FLIP_DETECTION_BARS);
            structureQuality *= 0.7;
        }

        log.debug("Final structure quality: {} (after flip check)", structureQuality);

        return structureQuality;
    }

    /**
     * Analyze structure on a single timeframe
     *
     * @param candles Candle history
     * @param timeframe Timeframe label for logging
     * @return +1 (bullish structure), -1 (bearish structure), 0 (no structure)
     */
    private double analyzeStructure(List<UnifiedCandle> candles, String timeframe) {
        // Find swing highs and lows
        List<SwingPoint> swingHighs = findSwingHighs(candles);
        List<SwingPoint> swingLows = findSwingLows(candles);

        if (swingHighs.size() < 2 || swingLows.size() < 2) {
            log.debug("[{}] Insufficient swing points: highs={}, lows={}",
                timeframe, swingHighs.size(), swingLows.size());
            return 0.0;
        }

        // Check for HigherHigh and HigherLow (bullish structure)
        boolean higherHigh = swingHighs.get(swingHighs.size() - 1).price >
                            swingHighs.get(swingHighs.size() - 2).price;
        boolean higherLow = swingLows.get(swingLows.size() - 1).price >
                           swingLows.get(swingLows.size() - 2).price;

        // Check for LowerHigh and LowerLow (bearish structure)
        boolean lowerHigh = swingHighs.get(swingHighs.size() - 1).price <
                           swingHighs.get(swingHighs.size() - 2).price;
        boolean lowerLow = swingLows.get(swingLows.size() - 1).price <
                          swingLows.get(swingLows.size() - 2).price;

        double structure;
        if (higherHigh && higherLow) {
            structure = 1.0;
            log.debug("[{}] Bullish structure: HH={}, HL={}", timeframe,
                swingHighs.get(swingHighs.size() - 1).price,
                swingLows.get(swingLows.size() - 1).price);
        } else if (lowerHigh && lowerLow) {
            structure = -1.0;
            log.debug("[{}] Bearish structure: LH={}, LL={}", timeframe,
                swingHighs.get(swingHighs.size() - 1).price,
                swingLows.get(swingLows.size() - 1).price);
        } else {
            structure = 0.0;
            log.debug("[{}] No clear structure: HH={}, HL={}, LH={}, LL={}",
                timeframe, higherHigh, higherLow, lowerHigh, lowerLow);
        }

        return structure;
    }

    /**
     * Find swing highs (local maxima)
     */
    private List<SwingPoint> findSwingHighs(List<UnifiedCandle> candles) {
        List<SwingPoint> swingHighs = new ArrayList<>();

        for (int i = SWING_LOOKBACK; i < candles.size() - SWING_LOOKBACK; i++) {
            UnifiedCandle current = candles.get(i);
            boolean isSwingHigh = true;

            // Check if current high is greater than surrounding highs
            for (int j = i - SWING_LOOKBACK; j <= i + SWING_LOOKBACK; j++) {
                if (j != i && candles.get(j).getHigh() >= current.getHigh()) {
                    isSwingHigh = false;
                    break;
                }
            }

            if (isSwingHigh) {
                swingHighs.add(new SwingPoint(i, current.getHigh(), current.getWindowStartMillis()));
            }
        }

        return swingHighs;
    }

    /**
     * Find swing lows (local minima)
     */
    private List<SwingPoint> findSwingLows(List<UnifiedCandle> candles) {
        List<SwingPoint> swingLows = new ArrayList<>();

        for (int i = SWING_LOOKBACK; i < candles.size() - SWING_LOOKBACK; i++) {
            UnifiedCandle current = candles.get(i);
            boolean isSwingLow = true;

            // Check if current low is less than surrounding lows
            for (int j = i - SWING_LOOKBACK; j <= i + SWING_LOOKBACK; j++) {
                if (j != i && candles.get(j).getLow() <= current.getLow()) {
                    isSwingLow = false;
                    break;
                }
            }

            if (isSwingLow) {
                swingLows.add(new SwingPoint(i, current.getLow(), current.getWindowStartMillis()));
            }
        }

        return swingLows;
    }

    /**
     * Check if structure flipped within last N bars
     */
    private boolean hasRecentFlip(List<UnifiedCandle> candles, int bars) {
        if (candles.size() < bars + MIN_CANDLES_FOR_ANALYSIS) {
            return false;
        }

        // Get current structure
        List<UnifiedCandle> recentCandles = candles.subList(candles.size() - MIN_CANDLES_FOR_ANALYSIS, candles.size());
        double currentStructure = analyzeStructure(recentCandles, "recent");

        // Get structure N bars ago
        List<UnifiedCandle> previousCandles = candles.subList(
            candles.size() - MIN_CANDLES_FOR_ANALYSIS - bars,
            candles.size() - bars
        );
        double previousStructure = analyzeStructure(previousCandles, "previous");

        // Flip detected if signs differ and both are non-zero
        boolean flipped = (currentStructure * previousStructure < 0) &&
                         (Math.abs(currentStructure) > 0.1 && Math.abs(previousStructure) > 0.1);

        if (flipped) {
            log.debug("Structure flip detected: previous={}, current={}",
                previousStructure, currentStructure);
        }

        return flipped;
    }

    /**
     * Swing point data structure
     */
    private static class SwingPoint {
        final int index;
        final double price;
        final long timestamp;

        SwingPoint(int index, double price, long timestamp) {
            this.index = index;
            this.price = price;
            this.timestamp = timestamp;
        }
    }
}
