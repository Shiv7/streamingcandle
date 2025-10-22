package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.CandleData;
import com.kotsin.consumer.model.TickData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for CandleAccumulator
 * 
 * CRITICAL: Verifies OHLCV calculation correctness
 * Purpose: Prevent regression in candle aggregation logic
 */
class CandleAccumulatorTest {

    private CandleAccumulator accumulator;
    private static final long WINDOW_START = 1700000000000L;  // Fixed timestamp

    @BeforeEach
    void setUp() {
        accumulator = new CandleAccumulator(WINDOW_START, 1);
    }

    // ========== OHLCV Calculation Tests ==========

    @Test
    @DisplayName("OHLCV: Basic calculation with multiple ticks")
    void testOHLCV_BasicCalculation() {
        // Add ticks: 100, 105, 98, 103
        accumulator.addTick(createTick(100.0, 10));
        accumulator.addTick(createTick(105.0, 20));
        accumulator.addTick(createTick(98.0, 15));
        accumulator.addTick(createTick(103.0, 25));
        
        accumulator.markComplete();
        CandleData candle = accumulator.toCandleData("N", "C");
        
        assertEquals(100.0, candle.getOpen(), "Open should be first tick price");
        assertEquals(105.0, candle.getHigh(), "High should be maximum price");
        assertEquals(98.0, candle.getLow(), "Low should be minimum price");
        assertEquals(103.0, candle.getClose(), "Close should be last tick price");
        assertEquals(70L, candle.getVolume(), "Volume should be sum of delta volumes");
        assertEquals(4, candle.getTickCount(), "Tick count should be 4");
    }

    @Test
    @DisplayName("OHLCV: Single tick creates valid candle")
    void testOHLCV_SingleTick() {
        accumulator.addTick(createTick(100.0, 50));
        accumulator.markComplete();
        
        CandleData candle = accumulator.toCandleData("N", "C");
        
        assertEquals(100.0, candle.getOpen());
        assertEquals(100.0, candle.getHigh());
        assertEquals(100.0, candle.getLow());
        assertEquals(100.0, candle.getClose());
        assertEquals(50L, candle.getVolume());
        assertEquals(1, candle.getTickCount());
    }

    @Test
    @DisplayName("OHLCV: Zero delta volume ticks handled correctly")
    void testOHLCV_ZeroDeltaVolume() {
        accumulator.addTick(createTick(100.0, 10));
        accumulator.addTick(createTick(105.0, 0));  // Zero volume
        accumulator.addTick(createTick(98.0, 20));
        
        accumulator.markComplete();
        CandleData candle = accumulator.toCandleData("N", "C");
        
        // Volume should only include non-zero deltas
        assertEquals(30L, candle.getVolume(), "Volume should exclude zero-delta ticks");
        assertEquals(3, candle.getTickCount(), "Tick count should include all ticks");
    }

    // ========== Window Boundary Tests ==========

    @Test
    @DisplayName("Window: Accumulator tracks window start correctly")
    void testWindow_StartsAtCorrectTime() {
        assertEquals(WINDOW_START, accumulator.getWindowStart(), 
            "Window start should be initialized correctly");
    }

    @Test
    @DisplayName("Window: Completion flag works correctly")
    void testWindow_CompletionFlag() {
        assertFalse(accumulator.isComplete(), "Should not be complete initially");
        
        accumulator.addTick(createTick(100.0, 10));
        assertFalse(accumulator.isComplete(), "Should not be complete before marking");
        
        accumulator.markComplete();
        assertTrue(accumulator.isComplete(), "Should be complete after marking");
    }

    // ========== Volume Calculation Tests ==========

    @Test
    @DisplayName("Volume: Cumulative addition of delta volumes")
    void testVolume_CumulativeAddition() {
        accumulator.addTick(createTick(100.0, 100));
        accumulator.addTick(createTick(101.0, 50));
        accumulator.addTick(createTick(99.0, 75));
        accumulator.addTick(createTick(100.5, 125));
        
        accumulator.markComplete();
        CandleData candle = accumulator.toCandleData("N", "C");
        
        assertEquals(350L, candle.getVolume(), "Volume should be sum: 100+50+75+125");
    }

    @Test
    @DisplayName("Volume: Null delta volume skipped")
    void testVolume_NullDeltaSkipped() {
        accumulator.addTick(createTick(100.0, 100));
        accumulator.addTick(createTick(101.0, null));  // Null delta
        accumulator.addTick(createTick(99.0, 50));
        
        accumulator.markComplete();
        CandleData candle = accumulator.toCandleData("N", "C");
        
        assertEquals(150L, candle.getVolume(), "Volume should skip null deltas");
    }

    @Test
    @DisplayName("Volume: Negative delta volume skipped")
    void testVolume_NegativeDeltaSkipped() {
        accumulator.addTick(createTick(100.0, 100));
        accumulator.addTick(createTick(101.0, -50));  // Negative (should be skipped)
        accumulator.addTick(createTick(99.0, 75));
        
        accumulator.markComplete();
        CandleData candle = accumulator.toCandleData("N", "C");
        
        assertEquals(175L, candle.getVolume(), 
            "Volume should skip negative deltas (data corruption)");
    }

    // ========== High/Low Tracking Tests ==========

    @Test
    @DisplayName("High: Correctly tracks maximum price")
    void testHigh_MaximumTracking() {
        accumulator.addTick(createTick(100.0, 10));
        accumulator.addTick(createTick(105.0, 10));
        accumulator.addTick(createTick(103.0, 10));
        accumulator.addTick(createTick(107.0, 10));  // New high
        accumulator.addTick(createTick(102.0, 10));
        
        accumulator.markComplete();
        CandleData candle = accumulator.toCandleData("N", "C");
        
        assertEquals(107.0, candle.getHigh(), "High should be maximum across all ticks");
    }

    @Test
    @DisplayName("Low: Correctly tracks minimum price")
    void testLow_MinimumTracking() {
        accumulator.addTick(createTick(100.0, 10));
        accumulator.addTick(createTick(95.0, 10));
        accumulator.addTick(createTick(98.0, 10));
        accumulator.addTick(createTick(92.0, 10));  // New low
        accumulator.addTick(createTick(97.0, 10));
        
        accumulator.markComplete();
        CandleData candle = accumulator.toCandleData("N", "C");
        
        assertEquals(92.0, candle.getLow(), "Low should be minimum across all ticks");
    }

    // ========== Tick Count Tests ==========

    @Test
    @DisplayName("Tick Count: Accurately counts all ticks")
    void testTickCount_AccurateCount() {
        for (int i = 0; i < 50; i++) {
            accumulator.addTick(createTick(100.0 + i * 0.1, 10));
        }
        
        accumulator.markComplete();
        CandleData candle = accumulator.toCandleData("N", "C");
        
        assertEquals(50, candle.getTickCount(), "Tick count should be 50");
    }

    // ========== Edge Cases & Regression Tests ==========

    @Test
    @DisplayName("REGRESSION: First tick sets Open, not pre-initialized")
    void testRegression_OpenSetOnFirstTick() {
        CandleAccumulator acc = new CandleAccumulator(WINDOW_START, 1);
        
        // Before any ticks
        assertNull(acc.getOpen(), "Open should be null before first tick");
        
        acc.addTick(createTick(100.0, 10));
        
        // After first tick
        assertNotNull(acc.getOpen(), "Open should be set after first tick");
        assertEquals(100.0, acc.getOpen(), "Open should match first tick price");
    }

    @Test
    @DisplayName("REGRESSION: Uses delta volume, not cumulative")
    void testRegression_UsesDeltaNotCumulative() {
        TickData tick1 = new TickData();
        tick1.setLastRate(100.0);
        tick1.setTimestamp(WINDOW_START + 1000);
        tick1.setTotalQuantity(1000);  // Cumulative
        tick1.setDeltaVolume(100);     // Delta (what we should use)
        
        TickData tick2 = new TickData();
        tick2.setLastRate(101.0);
        tick2.setTimestamp(WINDOW_START + 2000);
        tick2.setTotalQuantity(1150);  // Cumulative
        tick2.setDeltaVolume(150);     // Delta
        
        accumulator.addTick(tick1);
        accumulator.addTick(tick2);
        accumulator.markComplete();
        
        CandleData candle = accumulator.toCandleData("N", "C");
        
        // Should use delta (100 + 150 = 250), NOT cumulative (1150)
        assertEquals(250L, candle.getVolume(), 
            "Should use delta volumes, not cumulative");
    }

    @Test
    @DisplayName("REGRESSION: Completion flag prevents premature emission")
    void testRegression_CompletionFlagRequired() {
        accumulator.addTick(createTick(100.0, 10));
        accumulator.addTick(createTick(105.0, 20));
        
        // Not marked complete yet
        assertFalse(accumulator.isComplete(), "Should not be complete before marking");
        
        // Should still be able to build candle (for internal use)
        CandleData candle = accumulator.toCandleData("N", "C");
        assertNotNull(candle, "Should be able to build candle");
        
        // But isComplete flag should be false
        assertFalse(accumulator.isComplete(), "Completion flag should remain false");
    }

    @Test
    @DisplayName("Edge Case: All ticks at same price")
    void testEdgeCase_SamePrice() {
        for (int i = 0; i < 10; i++) {
            accumulator.addTick(createTick(100.0, 10));
        }
        
        accumulator.markComplete();
        CandleData candle = accumulator.toCandleData("N", "C");
        
        assertEquals(100.0, candle.getOpen());
        assertEquals(100.0, candle.getHigh());
        assertEquals(100.0, candle.getLow());
        assertEquals(100.0, candle.getClose());
        assertEquals(100L, candle.getVolume());
    }

    @Test
    @DisplayName("Edge Case: Extreme price movements")
    void testEdgeCase_ExtremePriceMovements() {
        accumulator.addTick(createTick(100.0, 10));
        accumulator.addTick(createTick(200.0, 20));  // 100% spike
        accumulator.addTick(createTick(50.0, 30));   // 50% crash
        accumulator.addTick(createTick(150.0, 40));  // Recovery
        
        accumulator.markComplete();
        CandleData candle = accumulator.toCandleData("N", "C");
        
        assertEquals(100.0, candle.getOpen());
        assertEquals(200.0, candle.getHigh());
        assertEquals(50.0, candle.getLow());
        assertEquals(150.0, candle.getClose());
    }

    @Test
    @DisplayName("Window Rotation: New accumulator starts fresh")
    void testWindowRotation_FreshStart() {
        // Fill first window
        accumulator.addTick(createTick(100.0, 100));
        accumulator.addTick(createTick(105.0, 50));
        accumulator.markComplete();
        
        // Create new accumulator for next window
        CandleAccumulator newAccumulator = new CandleAccumulator(WINDOW_START + 60000, 1);
        
        // Should start fresh
        assertNull(newAccumulator.getOpen(), "New accumulator should have null open");
        assertEquals(0L, newAccumulator.getVolume(), "New accumulator should have zero volume");
        assertFalse(newAccumulator.isComplete(), "New accumulator should not be complete");
    }

    // ========== Helper Methods ==========

    private TickData createTick(double price, Integer deltaVolume) {
        TickData tick = new TickData();
        tick.setLastRate(price);
        tick.setDeltaVolume(deltaVolume);
        tick.setTimestamp(WINDOW_START + System.currentTimeMillis() % 60000);
        tick.setBidRate(price - 0.5);
        tick.setOfferRate(price + 0.5);
        return tick;
    }
}

