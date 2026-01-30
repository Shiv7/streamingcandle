package com.kotsin.consumer.trading.mtf;

import com.kotsin.consumer.trading.mtf.HtfCandleAggregator.HtfCandle;
import com.kotsin.consumer.trading.mtf.HtfCandleAggregator.SmcCandleData;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SwingRangeCalculator - Calculates premium/discount zones from CURRENT swing structure.
 *
 * THE PROBLEM WITH OLD APPROACH:
 * - Used 20-day high/low for premium/discount zones
 * - This is too wide - price can be "in discount" for weeks
 * - Doesn't reflect current market structure
 *
 * THE FIX:
 * - Calculate premium/discount from the CURRENT impulse swing
 * - Current swing = most recent swing high to swing low (or vice versa)
 * - Premium = above equilibrium (50% of current swing)
 * - Discount = below equilibrium
 *
 * SMC CONTEXT:
 * - We BUY in discount (below 50% of current swing)
 * - We SELL in premium (above 50% of current swing)
 * - LONG in premium = BAD (buying high)
 * - SHORT in discount = BAD (selling low)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SwingRangeCalculator {

    private final HtfCandleAggregator htfAggregator;

    // Minimum candles needed for swing detection
    private static final int MIN_CANDLES = 20;
    // Swing point lookback (candles before and after to confirm swing)
    private static final int SWING_LOOKBACK = 3;

    /**
     * Calculate current swing range for a family/timeframe.
     *
     * @param familyId  The instrument family ID
     * @param timeframe The timeframe to analyze ("1h", "4h", "daily")
     * @return SwingRange with current swing high/low and equilibrium
     */
    public SwingRange calculateCurrentSwing(String familyId, String timeframe) {
        SmcCandleData data = htfAggregator.getCandleArrays(familyId, timeframe, 100);

        if (!data.hasData() || data.size() < MIN_CANDLES) {
            log.debug("[SWING] {} {} - Insufficient data ({} candles), using fallback",
                    familyId, timeframe, data.size());
            return calculateFallbackRange(familyId);
        }

        double[] highs = data.getHighs();
        double[] lows = data.getLows();
        long[] timestamps = data.getTimestamps();
        int len = highs.length;

        // Find most recent swing high and swing low
        SwingPoint recentSwingHigh = null;
        SwingPoint recentSwingLow = null;

        // Scan from most recent candle backwards to find swing points
        for (int i = len - SWING_LOOKBACK - 1; i >= SWING_LOOKBACK; i--) {
            // Check for swing high (high is higher than SWING_LOOKBACK candles before and after)
            if (recentSwingHigh == null && isSwingHigh(highs, i, SWING_LOOKBACK)) {
                recentSwingHigh = new SwingPoint(highs[i], timestamps[i], i, true);
            }

            // Check for swing low (low is lower than SWING_LOOKBACK candles before and after)
            if (recentSwingLow == null && isSwingLow(lows, i, SWING_LOOKBACK)) {
                recentSwingLow = new SwingPoint(lows[i], timestamps[i], i, false);
            }

            // Found both swings
            if (recentSwingHigh != null && recentSwingLow != null) {
                break;
            }
        }

        // Fallback: use recent high/low if swing points not found
        if (recentSwingHigh == null) {
            double maxHigh = 0;
            int maxIdx = len - 1;
            for (int i = Math.max(0, len - 20); i < len; i++) {
                if (highs[i] > maxHigh) {
                    maxHigh = highs[i];
                    maxIdx = i;
                }
            }
            recentSwingHigh = new SwingPoint(maxHigh, timestamps[maxIdx], maxIdx, true);
        }

        if (recentSwingLow == null) {
            double minLow = Double.MAX_VALUE;
            int minIdx = len - 1;
            for (int i = Math.max(0, len - 20); i < len; i++) {
                if (lows[i] < minLow) {
                    minLow = lows[i];
                    minIdx = i;
                }
            }
            recentSwingLow = new SwingPoint(minLow, timestamps[minIdx], minIdx, false);
        }

        // Calculate equilibrium (50% of swing)
        double swingHigh = recentSwingHigh.price();
        double swingLow = recentSwingLow.price();
        double equilibrium = (swingHigh + swingLow) / 2.0;

        // Determine which swing came first (for trend context)
        boolean upswing = recentSwingLow.candleIndex() < recentSwingHigh.candleIndex();

        SwingRange range = SwingRange.builder()
                .swingHigh(recentSwingHigh)
                .swingLow(recentSwingLow)
                .equilibrium(equilibrium)
                .premiumTop(swingHigh)
                .premiumBottom(equilibrium)
                .discountTop(equilibrium)
                .discountBottom(swingLow)
                .swingSize(swingHigh - swingLow)
                .swingSizePercent((swingHigh - swingLow) / swingLow * 100)
                .isUpswing(upswing)
                .timeframe(timeframe)
                .build();

        log.debug("[SWING] {} {} | High={} Low={} EQ={} | Size={}% | {}",
                familyId, timeframe,
                String.format("%.2f", swingHigh),
                String.format("%.2f", swingLow),
                String.format("%.2f", equilibrium),
                String.format("%.2f", range.getSwingSizePercent()),
                upswing ? "UPSWING" : "DOWNSWING");

        return range;
    }

    /**
     * Get zone position for a given price within the swing range.
     *
     * @param price Current price
     * @param range The swing range to check against
     * @return ZonePosition (PREMIUM, DISCOUNT, EQUILIBRIUM, ABOVE_RANGE, BELOW_RANGE)
     */
    public ZonePosition getZonePosition(double price, SwingRange range) {
        if (range == null || range.getSwingSize() <= 0) {
            log.debug("[SWING_ZONE] No valid swing range - returning UNKNOWN");
            return ZonePosition.UNKNOWN;
        }

        double rangePosition = (price - range.getDiscountBottom()) / range.getSwingSize();
        ZonePosition zone;

        // Above the swing range entirely
        if (price > range.getPremiumTop() * 1.001) {
            zone = ZonePosition.ABOVE_RANGE;
        }
        // Below the swing range entirely
        else if (price < range.getDiscountBottom() * 0.999) {
            zone = ZonePosition.BELOW_RANGE;
        }
        // Strong premium (above 60%)
        else if (rangePosition > 0.60) {
            zone = ZonePosition.PREMIUM;
        }
        // Strong discount (below 40%)
        else if (rangePosition < 0.40) {
            zone = ZonePosition.DISCOUNT;
        }
        // Equilibrium zone (40-60%)
        else {
            zone = ZonePosition.EQUILIBRIUM;
        }

        log.debug("[SWING_ZONE] price={} | range=[{}-{}] | position={}% | zone={}",
                String.format("%.2f", price),
                String.format("%.2f", range.getDiscountBottom()),
                String.format("%.2f", range.getPremiumTop()),
                String.format("%.0f", rangePosition * 100),
                zone);

        return zone;
    }

    /**
     * Check if price is in an acceptable zone for a LONG trade.
     * LONG should be in discount or equilibrium (not premium).
     */
    public boolean isAcceptableForLong(double price, SwingRange range) {
        ZonePosition zone = getZonePosition(price, range);
        return zone == ZonePosition.DISCOUNT ||
               zone == ZonePosition.EQUILIBRIUM ||
               zone == ZonePosition.BELOW_RANGE;  // Even better
    }

    /**
     * Check if price is in an acceptable zone for a SHORT trade.
     * SHORT should be in premium or equilibrium (not discount).
     */
    public boolean isAcceptableForShort(double price, SwingRange range) {
        ZonePosition zone = getZonePosition(price, range);
        return zone == ZonePosition.PREMIUM ||
               zone == ZonePosition.EQUILIBRIUM ||
               zone == ZonePosition.ABOVE_RANGE;  // Even better
    }

    /**
     * Calculate range position as percentage (0.0 = swing low, 1.0 = swing high).
     */
    public double getRangePosition(double price, SwingRange range) {
        if (range == null || range.getSwingSize() <= 0) {
            return 0.5;  // Default to middle
        }
        double position = (price - range.getDiscountBottom()) / range.getSwingSize();
        return Math.max(0.0, Math.min(1.0, position));
    }

    // ============ PRIVATE HELPERS ============

    /**
     * Check if candle at index is a swing high.
     */
    private boolean isSwingHigh(double[] highs, int index, int lookback) {
        double high = highs[index];
        for (int i = index - lookback; i < index; i++) {
            if (i >= 0 && highs[i] >= high) return false;
        }
        for (int i = index + 1; i <= index + lookback; i++) {
            if (i < highs.length && highs[i] >= high) return false;
        }
        return true;
    }

    /**
     * Check if candle at index is a swing low.
     */
    private boolean isSwingLow(double[] lows, int index, int lookback) {
        double low = lows[index];
        for (int i = index - lookback; i < index; i++) {
            if (i >= 0 && lows[i] <= low) return false;
        }
        for (int i = index + 1; i <= index + lookback; i++) {
            if (i < lows.length && lows[i] <= low) return false;
        }
        return true;
    }

    /**
     * Fallback to daily range when swing detection fails.
     */
    private SwingRange calculateFallbackRange(String familyId) {
        double[] dailyRange = htfAggregator.getDailyRange(familyId);
        double high = dailyRange[0];
        double low = dailyRange[1];

        if (high <= 0 || low <= 0 || high <= low) {
            return SwingRange.empty();
        }

        double equilibrium = (high + low) / 2.0;

        return SwingRange.builder()
                .swingHigh(new SwingPoint(high, System.currentTimeMillis(), 0, true))
                .swingLow(new SwingPoint(low, System.currentTimeMillis(), 0, false))
                .equilibrium(equilibrium)
                .premiumTop(high)
                .premiumBottom(equilibrium)
                .discountTop(equilibrium)
                .discountBottom(low)
                .swingSize(high - low)
                .swingSizePercent((high - low) / low * 100)
                .isUpswing(true)
                .timeframe("daily")
                .isFallback(true)
                .build();
    }

    // ============ MODELS ============

    /**
     * Zone position within the swing range.
     */
    public enum ZonePosition {
        PREMIUM,       // Above equilibrium (>60% of swing) - sell zone
        DISCOUNT,      // Below equilibrium (<40% of swing) - buy zone
        EQUILIBRIUM,   // Around equilibrium (40-60%) - neutral
        ABOVE_RANGE,   // Above the swing high
        BELOW_RANGE,   // Below the swing low
        UNKNOWN        // Insufficient data
    }

    /**
     * A swing point (high or low).
     */
    public record SwingPoint(
            double price,
            long timestamp,
            int candleIndex,
            boolean isHigh
    ) {}

    /**
     * The current swing range with premium/discount zones.
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class SwingRange {
        private SwingPoint swingHigh;
        private SwingPoint swingLow;
        private double equilibrium;
        private double premiumTop;      // Swing high
        private double premiumBottom;   // Equilibrium
        private double discountTop;     // Equilibrium
        private double discountBottom;  // Swing low
        private double swingSize;       // High - Low
        private double swingSizePercent;
        private boolean isUpswing;      // Swing low came before swing high
        private String timeframe;
        private boolean isFallback;     // True if using daily fallback

        public static SwingRange empty() {
            return SwingRange.builder()
                    .equilibrium(0)
                    .swingSize(0)
                    .isFallback(true)
                    .build();
        }

        public boolean isValid() {
            return swingSize > 0 && swingHigh != null && swingLow != null;
        }
    }
}
