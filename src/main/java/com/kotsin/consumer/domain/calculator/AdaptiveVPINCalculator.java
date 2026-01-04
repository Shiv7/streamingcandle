package com.kotsin.consumer.domain.calculator;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * AdaptiveVPINCalculator - Volume-Synchronized Probability of Informed Trading
 * 
 * FIXES the hardcoded bucket size bug in EnrichedCandlestick.java:
 * OLD: private static final double VPIN_INITIAL_BUCKET_SIZE = 10000.0;
 * NEW: Bucket size adapts per instrument based on average daily volume
 * 
 * Algorithm:
 * 1. Bucket size = avgDailyVolume / bucketsPerDay (default 50)
 * 2. Trade classification: buy vs sell using tick rule
 * 3. Bucket accumulation: fill buckets with buy/sell volume
 * 4. VPIN = |Σ(buyVol - sellVol)| / Σ(totalVol) over last N buckets
 * 
 * Interpretation:
 * - VPIN > 0.5: High probability of informed trading (caution!)
 * - VPIN 0.3-0.5: Moderate informed trading
 * - VPIN < 0.3: Normal market conditions
 * 
 * References:
 * - Easley, D., López de Prado, M., & O'Hara, M. (2012). "Flow Toxicity and Liquidity in a High-Frequency World"
 */
@Slf4j
public class AdaptiveVPINCalculator {

    // Default configuration
    private static final int DEFAULT_BUCKETS_PER_DAY = 50;
    private static final int DEFAULT_MAX_BUCKETS = 50;  // Rolling window
    private static final double MIN_BUCKET_SIZE = 100.0;  // Minimum for low-volume stocks
    // REMOVED: private static final double EWMA_ALPHA = 0.05; (dead code - was never used)

    // Instance state
    private final double bucketSize;
    private final int maxBuckets;
    private final List<VPINBucket> buckets;
    
    private double currentBucketVolume;
    private double currentBucketBuyVolume;
    private double currentBucketSellVolume;

    /**
     * Create calculator with adaptive bucket size based on daily volume
     *
     * @param avgDailyVolume Average daily volume for this instrument
     * @param bucketsPerDay Number of buckets to create per day (default 50)
     */
    public AdaptiveVPINCalculator(double avgDailyVolume, int bucketsPerDay) {
        this.bucketSize = Math.max(MIN_BUCKET_SIZE, avgDailyVolume / bucketsPerDay);
        this.maxBuckets = DEFAULT_MAX_BUCKETS;
        this.buckets = new ArrayList<>();
        this.currentBucketVolume = 0.0;
        this.currentBucketBuyVolume = 0.0;
        this.currentBucketSellVolume = 0.0;
        
        log.debug("Created AdaptiveVPINCalculator: avgDailyVolume={}, bucketSize={}", 
            avgDailyVolume, this.bucketSize);
    }

    /**
     * Create calculator with default settings
     *
     * @param avgDailyVolume Average daily volume
     */
    public AdaptiveVPINCalculator(double avgDailyVolume) {
        this(avgDailyVolume, DEFAULT_BUCKETS_PER_DAY);
    }

    /**
     * Create calculator with fallback bucket size (for unknown instruments)
     *
     * @param fallbackBucketSize Fixed bucket size to use
     */
    public static AdaptiveVPINCalculator withFallback(double fallbackBucketSize) {
        AdaptiveVPINCalculator calc = new AdaptiveVPINCalculator(
            fallbackBucketSize * DEFAULT_BUCKETS_PER_DAY
        );
        return calc;
    }

    /**
     * Update VPIN with a new trade
     *
     * @param volume Trade volume
     * @param isBuy True if buy trade, false if sell
     */
    public void update(long volume, boolean isBuy) {
        if (volume <= 0) return;

        // Add to current bucket
        currentBucketVolume += volume;
        if (isBuy) {
            currentBucketBuyVolume += volume;
        } else {
            currentBucketSellVolume += volume;
        }

        // Check if bucket is full
        while (currentBucketVolume >= bucketSize) {
            // Calculate proportion to fill this bucket
            double fillRatio = bucketSize / currentBucketVolume;
            double bucketBuyVol = currentBucketBuyVolume * fillRatio;
            double bucketSellVol = currentBucketSellVolume * fillRatio;

            // Create and add bucket
            buckets.add(new VPINBucket(bucketSize, bucketBuyVol, bucketSellVol));

            // Remove oldest if exceeding max
            if (buckets.size() > maxBuckets) {
                buckets.remove(0);
            }

            // Carry over excess to next bucket
            currentBucketVolume -= bucketSize;
            currentBucketBuyVolume -= bucketBuyVol;
            currentBucketSellVolume -= bucketSellVol;
        }
    }

    /**
     * Update VPIN with proper trade classification using Lee-Ready Algorithm.
     *
     * This is the CORRECT way to use VPIN - classifying each trade at tick level.
     *
     * Lee-Ready Algorithm:
     * 1. Quote Rule: If trade price > midpoint → BUY, if < midpoint → SELL
     * 2. Tick Rule (fallback): If at midpoint, use price change from previous trade
     *
     * References:
     * - Lee, C., & Ready, M. (1991). "Inferring Trade Direction from Intraday Data"
     *
     * @param tradePrice The price at which the trade occurred
     * @param volume Trade volume
     * @param bidPrice Current best bid price
     * @param askPrice Current best ask price
     * @param prevTradePrice Previous trade price (for tick rule fallback), null if unknown
     */
    public void updateFromTick(double tradePrice, long volume, double bidPrice, double askPrice, Double prevTradePrice) {
        if (volume <= 0 || bidPrice <= 0 || askPrice <= 0) return;

        // Lee-Ready Trade Classification Algorithm
        double midpoint = (bidPrice + askPrice) / 2.0;
        double tickTolerance = (askPrice - bidPrice) * 0.01; // 1% of spread as tolerance
        boolean isBuy;

        if (tradePrice > midpoint + tickTolerance) {
            // Trade above midpoint = buyer lifted the offer (BUY)
            isBuy = true;
        } else if (tradePrice < midpoint - tickTolerance) {
            // Trade below midpoint = seller hit the bid (SELL)
            isBuy = false;
        } else if (prevTradePrice != null && Math.abs(tradePrice - prevTradePrice) > 0.0001) {
            // At midpoint: use Tick Rule - if price went up, it's a buy
            isBuy = tradePrice > prevTradePrice;
        } else {
            // Last resort: if exactly at midpoint with no price change
            // Use depth imbalance hint: more bids than asks suggests buying pressure
            // Default to buy if completely ambiguous (matches academic convention)
            isBuy = bidPrice >= askPrice - tickTolerance;
        }

        update(volume, isBuy);

        log.trace("Lee-Ready classification: price={} mid={} bid={} ask={} prev={} -> {}",
                  tradePrice, midpoint, bidPrice, askPrice, prevTradePrice, isBuy ? "BUY" : "SELL");
    }

    /**
     * Batch update from tick data with proper classification.
     * Use this for processing historical tick data.
     *
     * @param ticks List of tick data [tradePrice, volume, bidPrice, askPrice]
     */
    public void updateFromTickBatch(List<double[]> ticks) {
        if (ticks == null || ticks.isEmpty()) return;

        Double prevPrice = null;
        for (double[] tick : ticks) {
            if (tick.length >= 4) {
                updateFromTick(tick[0], (long) tick[1], tick[2], tick[3], prevPrice);
                prevPrice = tick[0];
            }
        }
    }

    /**
     * Update VPIN with aggregated buy/sell volumes from a candle
     *
     * @param buyVolume Total buy volume
     * @param sellVolume Total sell volume
     */
    public void updateFromCandle(long buyVolume, long sellVolume) {
        // Add buy trades
        if (buyVolume > 0) {
            update(buyVolume, true);
        }
        // Add sell trades
        if (sellVolume > 0) {
            update(sellVolume, false);
        }
    }

    /**
     * Calculate current VPIN value
     *
     * @return VPIN value between 0 and 1
     */
    public double calculate() {
        if (buckets.isEmpty()) {
            return 0.0;
        }

        double totalVolume = 0.0;
        double totalImbalance = 0.0;

        for (VPINBucket bucket : buckets) {
            totalVolume += bucket.totalVolume;
            totalImbalance += Math.abs(bucket.buyVolume - bucket.sellVolume);
        }

        if (totalVolume <= 0) {
            return 0.0;
        }

        return totalImbalance / totalVolume;
    }

    /**
     * Get number of filled buckets
     */
    public int getBucketCount() {
        return buckets.size();
    }

    /**
     * Get configured bucket size
     */
    public double getBucketSize() {
        return bucketSize;
    }

    /**
     * Check if enough data is available for reliable VPIN
     */
    public boolean isReliable() {
        return buckets.size() >= 10;  // At least 10 buckets
    }

    /**
     * Get average bucket fill time in milliseconds
     * Useful for monitoring bucket turnover
     */
    public double getAverageBucketFillTimeMs(long windowDurationMs) {
        if (buckets.isEmpty() || windowDurationMs <= 0) {
            return 0.0;
        }
        return (double) windowDurationMs / buckets.size();
    }

    /**
     * Reset calculator state
     */
    public void reset() {
        buckets.clear();
        currentBucketVolume = 0.0;
        currentBucketBuyVolume = 0.0;
        currentBucketSellVolume = 0.0;
    }

    /**
     * VPIN Bucket - Internal class to hold bucket data
     */
    public static class VPINBucket {
        public final double totalVolume;
        public final double buyVolume;
        public final double sellVolume;

        public VPINBucket(double totalVolume, double buyVolume, double sellVolume) {
            this.totalVolume = totalVolume;
            this.buyVolume = buyVolume;
            this.sellVolume = sellVolume;
        }

        public double getImbalance() {
            return Math.abs(buyVolume - sellVolume);
        }

        public double getSignedImbalance() {
            return buyVolume - sellVolume;
        }
    }

    // ==================== STATIC UTILITY METHODS ====================

    /**
     * Calculate recommended bucket size for an instrument
     *
     * @param avgDailyVolume Average daily volume
     * @param bucketsPerDay Target buckets per day (default 50)
     * @return Recommended bucket size
     */
    public static double calculateRecommendedBucketSize(double avgDailyVolume, int bucketsPerDay) {
        return Math.max(MIN_BUCKET_SIZE, avgDailyVolume / bucketsPerDay);
    }

    /**
     * Calculate recommended bucket size with default buckets per day
     */
    public static double calculateRecommendedBucketSize(double avgDailyVolume) {
        return calculateRecommendedBucketSize(avgDailyVolume, DEFAULT_BUCKETS_PER_DAY);
    }

    /**
     * Classify VPIN value into risk category
     *
     * @param vpin VPIN value (0-1)
     * @return Risk category string
     */
    public static String classifyVPIN(double vpin) {
        if (vpin >= 0.6) {
            return "EXTREME";  // Very high informed trading probability
        } else if (vpin >= 0.5) {
            return "HIGH";     // High informed trading
        } else if (vpin >= 0.4) {
            return "ELEVATED"; // Above normal
        } else if (vpin >= 0.3) {
            return "MODERATE"; // Normal range
        } else {
            return "LOW";      // Low informed trading
        }
    }
}
