package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.TickData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for WindowRotationService
 * 
 * CRITICAL: Verifies window boundary handling and rotation logic
 * Purpose: Prevent data loss at window boundaries
 */
class WindowRotationServiceTest {

    // ========== Window Alignment Tests ==========

    @Test
    @DisplayName("Window Alignment: 1-minute window aligns correctly")
    void testWindowAlignment_1Minute() {
        // 09:30:45 should align to 09:30:00
        long timestamp = parseTime("2025-10-22 09:30:45");
        
        CandleAccumulator acc = new CandleAccumulator();
        acc = WindowRotationService.rotateCandleIfNeeded(acc, timestamp, 1);
        
        long expectedStart = parseTime("2025-10-22 09:30:00");
        assertEquals(expectedStart, acc.getWindowStart(), 
            "Should align to 09:30:00");
    }

    @Test
    @DisplayName("Window Alignment: 5-minute window aligns correctly")
    void testWindowAlignment_5Minute() {
        // 09:32:30 should align to 09:30:00 (5-min window)
        long timestamp = parseTime("2025-10-22 09:32:30");
        
        CandleAccumulator acc = new CandleAccumulator();
        acc = WindowRotationService.rotateCandleIfNeeded(acc, timestamp, 5);
        
        long expectedStart = parseTime("2025-10-22 09:30:00");
        assertEquals(expectedStart, acc.getWindowStart());
    }

    @Test
    @DisplayName("Window Alignment: 15-minute window aligns correctly")
    void testWindowAlignment_15Minute() {
        // 09:42:00 should align to 09:30:00 (15-min window)
        long timestamp = parseTime("2025-10-22 09:42:00");
        
        CandleAccumulator acc = new CandleAccumulator();
        acc = WindowRotationService.rotateCandleIfNeeded(acc, timestamp, 15);
        
        long expectedStart = parseTime("2025-10-22 09:30:00");
        assertEquals(expectedStart, acc.getWindowStart());
    }

    // ========== Window Rotation Tests ==========

    @Test
    @DisplayName("Rotation: Detects window change at exact boundary")
    void testRotation_ExactBoundary() {
        long windowStart = parseTime("2025-10-22 09:30:00");
        CandleAccumulator acc = new CandleAccumulator(windowStart, 1);
        
        // Add tick at 09:30:30 (same window)
        long tick1Time = parseTime("2025-10-22 09:30:30");
        acc = WindowRotationService.rotateCandleIfNeeded(acc, tick1Time, 1);
        assertEquals(windowStart, acc.getWindowStart(), "Should stay in same window");
        assertFalse(acc.isComplete(), "Should not be complete yet");
        
        // Add tick at 09:31:00 (new window)
        long tick2Time = parseTime("2025-10-22 09:31:00");
        CandleAccumulator newAcc = WindowRotationService.rotateCandleIfNeeded(acc, tick2Time, 1);
        
        // Old accumulator should be marked complete
        assertTrue(acc.isComplete(), "Old accumulator should be marked complete");
        
        // New accumulator should have new window start
        assertNotEquals(acc, newAcc, "Should return new accumulator instance");
        assertEquals(parseTime("2025-10-22 09:31:00"), newAcc.getWindowStart(),
            "New accumulator should start at 09:31:00");
        assertFalse(newAcc.isComplete(), "New accumulator should not be complete");
    }

    @Test
    @DisplayName("Rotation: Handles tick just before boundary")
    void testRotation_BeforeBoundary() {
        long windowStart = parseTime("2025-10-22 09:30:00");
        CandleAccumulator acc = new CandleAccumulator(windowStart, 1);
        
        // Tick at 09:30:59.999 (still in window)
        long tickTime = parseTime("2025-10-22 09:30:59");
        acc = WindowRotationService.rotateCandleIfNeeded(acc, tickTime, 1);
        
        assertEquals(windowStart, acc.getWindowStart(), "Should stay in window");
        assertFalse(acc.isComplete(), "Should not be complete before boundary");
    }

    @Test
    @DisplayName("Rotation: Handles tick just after boundary")
    void testRotation_AfterBoundary() {
        long windowStart = parseTime("2025-10-22 09:30:00");
        CandleAccumulator acc = new CandleAccumulator(windowStart, 1);
        
        // Tick at 09:31:00.001 (new window)
        long tickTime = parseTime("2025-10-22 09:31:01");
        CandleAccumulator newAcc = WindowRotationService.rotateCandleIfNeeded(acc, tickTime, 1);
        
        assertTrue(acc.isComplete(), "Old window should be complete");
        assertNotEquals(acc, newAcc, "Should create new accumulator");
        assertEquals(parseTime("2025-10-22 09:31:00"), newAcc.getWindowStart());
    }

    // ========== Multi-Timeframe Coordination Tests ==========

    @Test
    @DisplayName("Multi-Timeframe: Different windows align independently")
    void testMultiTimeframe_IndependentAlignment() {
        long tick1Time = parseTime("2025-10-22 09:32:30");
        
        // 1-minute window: aligns to 09:32:00
        CandleAccumulator acc1m = new CandleAccumulator();
        acc1m = WindowRotationService.rotateCandleIfNeeded(acc1m, tick1Time, 1);
        assertEquals(parseTime("2025-10-22 09:32:00"), acc1m.getWindowStart());
        
        // 5-minute window: aligns to 09:30:00
        CandleAccumulator acc5m = new CandleAccumulator();
        acc5m = WindowRotationService.rotateCandleIfNeeded(acc5m, tick1Time, 5);
        assertEquals(parseTime("2025-10-22 09:30:00"), acc5m.getWindowStart());
        
        // 15-minute window: aligns to 09:30:00
        CandleAccumulator acc15m = new CandleAccumulator();
        acc15m = WindowRotationService.rotateCandleIfNeeded(acc15m, tick1Time, 15);
        assertEquals(parseTime("2025-10-22 09:30:00"), acc15m.getWindowStart());
    }

    @Test
    @DisplayName("Multi-Timeframe: Rotation timing differs by timeframe")
    void testMultiTimeframe_DifferentRotationTiming() {
        // At 09:31:00
        long tick1Time = parseTime("2025-10-22 09:31:00");
        
        CandleAccumulator acc1m = new CandleAccumulator(parseTime("2025-10-22 09:30:00"), 1);
        CandleAccumulator acc5m = new CandleAccumulator(parseTime("2025-10-22 09:30:00"), 5);
        
        acc1m = WindowRotationService.rotateCandleIfNeeded(acc1m, tick1Time, 1);
        acc5m = WindowRotationService.rotateCandleIfNeeded(acc5m, tick1Time, 5);
        
        // 1-minute should rotate
        assertEquals(parseTime("2025-10-22 09:31:00"), acc1m.getWindowStart(),
            "1-minute window should rotate at 09:31:00");
        
        // 5-minute should NOT rotate yet
        assertEquals(parseTime("2025-10-22 09:30:00"), acc5m.getWindowStart(),
            "5-minute window should not rotate until 09:35:00");
    }

    // ========== OI Accumulator Rotation Tests ==========

    @Test
    @DisplayName("OI Rotation: Same logic as candle rotation")
    void testOIRotation_SameLogic() {
        long windowStart = parseTime("2025-10-22 09:30:00");
        OiAccumulator oiAcc = new OiAccumulator(windowStart, 1);
        
        // Same window
        long tick1Time = parseTime("2025-10-22 09:30:45");
        oiAcc = WindowRotationService.rotateOiIfNeeded(oiAcc, tick1Time, 1);
        assertEquals(windowStart, oiAcc.getWindowStart());
        
        // New window
        long tick2Time = parseTime("2025-10-22 09:31:00");
        OiAccumulator newOiAcc = WindowRotationService.rotateOiIfNeeded(oiAcc, tick2Time, 1);
        
        assertTrue(oiAcc.isComplete());
        assertNotEquals(oiAcc, newOiAcc);
        assertEquals(parseTime("2025-10-22 09:31:00"), newOiAcc.getWindowStart());
    }

    // ========== Edge Cases ==========

    @Test
    @DisplayName("Edge Case: Midnight boundary")
    void testEdgeCase_MidnightBoundary() {
        // 23:59:30 should align to 23:59:00
        long beforeMidnight = parseTime("2025-10-22 23:59:30");
        CandleAccumulator acc = new CandleAccumulator();
        acc = WindowRotationService.rotateCandleIfNeeded(acc, beforeMidnight, 1);
        
        assertEquals(parseTime("2025-10-22 23:59:00"), acc.getWindowStart());
        
        // 00:00:30 next day should align to 00:00:00
        long afterMidnight = parseTime("2025-10-23 00:00:30");
        CandleAccumulator newAcc = WindowRotationService.rotateCandleIfNeeded(acc, afterMidnight, 1);
        
        assertTrue(acc.isComplete(), "Window should rotate at midnight");
        assertEquals(parseTime("2025-10-23 00:00:00"), newAcc.getWindowStart());
    }

    @Test
    @DisplayName("Edge Case: Large time gap (market halt)")
    void testEdgeCase_LargeTimeGap() {
        long windowStart = parseTime("2025-10-22 09:30:00");
        CandleAccumulator acc = new CandleAccumulator(windowStart, 1);
        
        // Add tick
        acc.addTick(createTick(100.0, 10));
        
        // Simulate 1-hour gap (market halt)
        long afterHalt = parseTime("2025-10-22 10:30:00");
        CandleAccumulator newAcc = WindowRotationService.rotateCandleIfNeeded(acc, afterHalt, 1);
        
        // Should rotate to new window (10:30:00)
        assertTrue(acc.isComplete(), "Old window should be complete");
        assertEquals(parseTime("2025-10-22 10:30:00"), newAcc.getWindowStart(),
            "Should align to current time window, not fill gap");
    }

    @Test
    @DisplayName("REGRESSION: Returns NEW object when rotated, not modified original")
    void testRegression_ReturnsNewObject() {
        long windowStart = parseTime("2025-10-22 09:30:00");
        CandleAccumulator acc1 = new CandleAccumulator(windowStart, 1);
        
        // Same window - should return same object
        long sameWindowTime = parseTime("2025-10-22 09:30:45");
        CandleAccumulator acc2 = WindowRotationService.rotateCandleIfNeeded(acc1, sameWindowTime, 1);
        
        assertSame(acc1, acc2, "Should return same object when window doesn't change");
        
        // New window - should return NEW object
        long newWindowTime = parseTime("2025-10-22 09:31:00");
        CandleAccumulator acc3 = WindowRotationService.rotateCandleIfNeeded(acc2, newWindowTime, 1);
        
        assertNotSame(acc2, acc3, "Should return NEW object when window rotates");
        assertTrue(acc2.isComplete(), "Old object should be marked complete");
        assertFalse(acc3.isComplete(), "New object should not be complete");
    }

    // ========== Helper Methods ==========

    private long parseTime(String timeStr) {
        // Parse "2025-10-22 09:30:45" format
        ZonedDateTime zdt = ZonedDateTime.parse(timeStr.replace(" ", "T") + "+05:30[Asia/Kolkata]");
        return zdt.toInstant().toEpochMilli();
    }

    private TickData createTick(double price, Integer deltaVolume) {
        TickData tick = new TickData();
        tick.setLastRate(price);
        tick.setDeltaVolume(deltaVolume);
        tick.setTimestamp(System.currentTimeMillis());
        return tick;
    }
}

