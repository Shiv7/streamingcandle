package com.kotsin.consumer.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VPIN (Volume-Synchronized Probability of Informed Trading) calculation
 * in EnrichedCandlestick.
 *
 * VPIN Algorithm (Easley, López de Prado, O'Hara 2012):
 * 1. Bucket trades by volume (not time)
 * 2. Each bucket contains fixed volume (adaptive)
 * 3. Classify each bucket as buy-heavy or sell-heavy
 * 4. VPIN = average of |buy_volume - sell_volume| / total_volume over last N buckets
 */
public class EnrichedCandlestickVPINTest {

    private EnrichedCandlestick candle;
    private static final double DELTA = 0.001; // Precision for double comparisons

    @BeforeEach
    public void setUp() {
        candle = new EnrichedCandlestick();
        candle.setExchange("N");
        candle.setCompanyName("TEST");
        candle.setScripCode("12345");
    }

    /**
     * Test VPIN initialization
     */
    @Test
    public void testVPINInitialization() {
        // Initially, VPIN should be 0.0 (no data)
        assertEquals(0.0, candle.getVpin(), DELTA, "Initial VPIN should be 0.0");
        assertEquals(0, candle.getVpinBucketCount(), "Initial bucket count should be 0");
    }

    /**
     * Test VPIN with single bucket (buy-heavy)
     */
    @Test
    public void testVPINSingleBucketBuyHeavy() {
        // Add trades to fill one bucket (assume 10000 volume per bucket)
        // 8000 buy, 2000 sell = 80% buy
        addBuyTrade(1000L, 100.0, 8000);
        addSellTrade(2000L, 99.5, 2000);

        // After first bucket: VPIN = |8000 - 2000| / 10000 = 0.6
        assertTrue(candle.getVpin() > 0.0, "VPIN should be > 0 after buy-heavy bucket");
        assertEquals(0.6, candle.getVpin(), 0.1, "VPIN should be around 0.6 for 80/20 buy/sell");
    }

    /**
     * Test VPIN with single bucket (sell-heavy)
     */
    @Test
    public void testVPINSingleBucketSellHeavy() {
        // 2000 buy, 8000 sell = 20% buy
        addBuyTrade(1000L, 100.0, 2000);
        addSellTrade(2000L, 99.5, 8000);

        // VPIN = |2000 - 8000| / 10000 = 0.6
        assertTrue(candle.getVpin() > 0.0, "VPIN should be > 0 after sell-heavy bucket");
        assertEquals(0.6, candle.getVpin(), 0.1, "VPIN should be around 0.6 for 20/80 buy/sell");
    }

    /**
     * Test VPIN with balanced trades (low VPIN)
     */
    @Test
    public void testVPINBalancedTrades() {
        // 5000 buy, 5000 sell = balanced
        addBuyTrade(1000L, 100.0, 5000);
        addSellTrade(2000L, 100.0, 5000);

        // VPIN = |5000 - 5000| / 10000 = 0.0
        assertEquals(0.0, candle.getVpin(), DELTA, "VPIN should be 0.0 for balanced trades");
    }

    /**
     * Test VPIN with multiple buckets (rolling average)
     */
    @Test
    public void testVPINMultipleBuckets() {
        // Bucket 1: 8000 buy, 2000 sell (VPIN = 0.6)
        addBuyTrade(1000L, 100.0, 8000);
        addSellTrade(2000L, 99.5, 2000);

        double vpinAfterBucket1 = candle.getVpin();
        assertEquals(0.6, vpinAfterBucket1, 0.1, "VPIN after bucket 1");

        // Bucket 2: 5000 buy, 5000 sell (VPIN = 0.0)
        addBuyTrade(3000L, 100.5, 5000);
        addSellTrade(4000L, 100.0, 5000);

        // Average VPIN = (0.6 + 0.0) / 2 = 0.3
        double vpinAfterBucket2 = candle.getVpin();
        assertEquals(0.3, vpinAfterBucket2, 0.1, "VPIN should be average of 2 buckets");
    }

    /**
     * Test VPIN with 50 buckets (full window)
     */
    @Test
    public void testVPINFullWindow() {
        // Add 50 buckets with alternating buy/sell heavy
        for (int i = 0; i < 50; i++) {
            long timestamp = 1000L + (i * 1000);
            if (i % 2 == 0) {
                // Buy-heavy: 7000 buy, 3000 sell (VPIN = 0.4)
                addBuyTrade(timestamp, 100.0, 7000);
                addSellTrade(timestamp + 100, 99.5, 3000);
            } else {
                // Sell-heavy: 3000 buy, 7000 sell (VPIN = 0.4)
                addBuyTrade(timestamp, 100.0, 3000);
                addSellTrade(timestamp + 100, 99.5, 7000);
            }
        }

        // Average VPIN should be around 0.4
        assertEquals(0.4, candle.getVpin(), 0.1, "VPIN should be ~0.4 for alternating 70/30 split");
        assertEquals(50, candle.getVpinBucketCount(), "Should have 50 buckets");
    }

    /**
     * Test VPIN bucket overflow (should keep last 50 buckets)
     */
    @Test
    public void testVPINBucketOverflow() {
        // Add 60 buckets (should keep last 50)
        for (int i = 0; i < 60; i++) {
            long timestamp = 1000L + (i * 1000);
            addBuyTrade(timestamp, 100.0, 6000);
            addSellTrade(timestamp + 100, 99.5, 4000);
        }

        // Should only track last 50 buckets
        assertEquals(50, candle.getVpinBucketCount(), "Should cap at 50 buckets");
        assertTrue(candle.getVpin() > 0.0, "VPIN should be > 0");
    }

    /**
     * Test VPIN with adaptive bucket size
     */
    @Test
    public void testVPINAdaptiveBucketSize() {
        double initialBucketSize = candle.getVpinBucketSize();
        assertTrue(initialBucketSize > 0, "Initial bucket size should be > 0");

        // Add many buckets to trigger adaptive sizing
        for (int i = 0; i < 100; i++) {
            long timestamp = 1000L + (i * 1000);
            addBuyTrade(timestamp, 100.0, 15000); // Larger volumes
            addSellTrade(timestamp + 100, 99.5, 5000);
        }

        // Bucket size should adapt (grow) with larger volumes
        double adaptedBucketSize = candle.getVpinBucketSize();
        assertTrue(adaptedBucketSize >= initialBucketSize,
            "Bucket size should adapt to volume");
    }

    // Helper methods

    private void addBuyTrade(long timestamp, double price, int volume) {
        TickData tick = createTick(timestamp, price, volume);
        // Set bid/ask to classify as buy (trade at ask)
        tick.setBidRate(price - 0.1);
        tick.setOfferRate(price); // Trade at ask = buy
        candle.updateWithDelta(tick);
    }

    private void addSellTrade(long timestamp, double price, int volume) {
        TickData tick = createTick(timestamp, price, volume);
        // Set bid/ask to classify as sell (trade at bid)
        tick.setBidRate(price); // Trade at bid = sell
        tick.setOfferRate(price + 0.1);
        candle.updateWithDelta(tick);
    }

    private TickData createTick(long timestamp, double price, int volume) {
        TickData tick = new TickData();
        tick.setTimestamp(timestamp);
        tick.setLastRate(price);
        tick.setDeltaVolume(volume);
        // tick.setLastQty(volume);  // Not needed for VPIN tests
        tick.setToken(12345);
        tick.setExchange("N");
        tick.setCompanyName("TEST");
        tick.setScripCode("12345");
        return tick;
    }
}
