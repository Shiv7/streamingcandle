package com.kotsin.consumer.processor.service;

import com.kotsin.consumer.service.BackpressureHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DEFENSIVE TESTS FOR BackpressureHandler
 * 
 * GOAL: Ensure backpressure handling prevents system overload
 * APPROACH: Test all scenarios including edge cases
 */
@DisplayName("BackpressureHandler - Comprehensive Tests")
class BackpressureHandlerTest {

    private BackpressureHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BackpressureHandler();
        // Enable backpressure for tests
        ReflectionTestUtils.setField(handler, "backpressureEnabled", true);
        ReflectionTestUtils.setField(handler, "maxPollRecords", 100);
        ReflectionTestUtils.setField(handler, "lagThreshold", 1000L);
        ReflectionTestUtils.setField(handler, "throttleFactor", 0.5);
    }

    // ========== BASIC FUNCTIONALITY TESTS ==========

    @Test
    @DisplayName("Should start with no backpressure")
    void testInitialState() {
        assertFalse(handler.shouldApplyBackpressure());
        assertTrue(handler.isHealthy());
        assertEquals(100, handler.getAdaptivePollRecords());
    }

    @Test
    @DisplayName("Should record processed records")
    void testRecordProcessed() {
        handler.recordProcessed();
        handler.recordProcessed();
        handler.recordProcessed();
        
        String stats = handler.getBackpressureStats();
        assertTrue(stats.contains("Processed: 3"));
    }

    @Test
    @DisplayName("Should record lag")
    void testRecordLag() {
        handler.recordLag(500);
        
        String stats = handler.getBackpressureStats();
        assertTrue(stats.contains("Lag: 500"));
    }

    // ========== BACKPRESSURE TRIGGER TESTS ==========

    @Test
    @DisplayName("Should trigger backpressure when lag exceeds threshold")
    void testBackpressureTrigger_HighLag() {
        handler.recordLag(1500); // Above threshold of 1000
        
        assertTrue(handler.shouldApplyBackpressure(),
            "Should trigger backpressure when lag exceeds threshold");
        assertFalse(handler.isHealthy(),
            "System should not be healthy under backpressure");
    }

    @Test
    @DisplayName("Should trigger backpressure when lag percentage exceeds 10%")
    void testBackpressureTrigger_HighLagPercentage() {
        // Process 100 records, lag 15 (15% > 10%)
        for (int i = 0; i < 100; i++) {
            handler.recordProcessed();
        }
        handler.recordLag(15);
        
        assertTrue(handler.shouldApplyBackpressure(),
            "Should trigger backpressure when lag percentage exceeds 10%");
    }

    @Test
    @DisplayName("Should NOT trigger backpressure for low lag")
    void testBackpressureNotTriggered_LowLag() {
        handler.recordLag(500); // Below threshold
        
        assertFalse(handler.shouldApplyBackpressure());
        assertTrue(handler.isHealthy());
    }

    // ========== ADAPTIVE POLL RECORDS TESTS ==========

    @Test
    @DisplayName("Should reduce poll records under backpressure")
    void testAdaptivePollRecords_UnderPressure() {
        handler.recordLag(1500); // Trigger backpressure
        
        int adaptiveRecords = handler.getAdaptivePollRecords();
        assertEquals(50, adaptiveRecords, // 100 * 0.5
            "Should reduce poll records to 50% under backpressure");
    }

    @Test
    @DisplayName("Should maintain full poll records when no backpressure")
    void testAdaptivePollRecords_NoPressure() {
        handler.recordLag(500); // No backpressure
        
        int adaptiveRecords = handler.getAdaptivePollRecords();
        assertEquals(100, adaptiveRecords,
            "Should maintain full poll records when no backpressure");
    }

    @Test
    @DisplayName("INTERN TEST: Should never reduce poll records below 1")
    void testAdaptivePollRecords_MinimumOne() {
        // Set very aggressive throttle factor
        ReflectionTestUtils.setField(handler, "throttleFactor", 0.0001);
        handler.recordLag(2000); // Trigger backpressure
        
        int adaptiveRecords = handler.getAdaptivePollRecords();
        assertTrue(adaptiveRecords >= 1,
            "Should never reduce poll records below 1");
    }

    // ========== BACKPRESSURE RELEASE TESTS ==========

    @Test
    @DisplayName("Should release backpressure when lag decreases")
    void testBackpressureRelease() {
        // Trigger backpressure
        handler.recordLag(1500);
        assertTrue(handler.shouldApplyBackpressure());
        
        // Reduce lag
        handler.recordLag(500);
        assertFalse(handler.shouldApplyBackpressure(),
            "Should release backpressure when lag decreases");
        assertTrue(handler.isHealthy());
    }

    // ========== STATISTICS TESTS ==========

    @Test
    @DisplayName("Should provide comprehensive statistics")
    void testGetBackpressureStats() {
        handler.recordProcessed();
        handler.recordProcessed();
        handler.recordLag(750);
        
        String stats = handler.getBackpressureStats();
        
        assertTrue(stats.contains("Backpressure"));
        assertTrue(stats.contains("Processed: 2"));
        assertTrue(stats.contains("Lag: 750"));
        assertTrue(stats.contains("Throttling: Inactive"));
    }

    @Test
    @DisplayName("Should show throttling active in stats when under pressure")
    void testGetBackpressureStats_Active() {
        handler.recordLag(1500);
        handler.shouldApplyBackpressure(); // Trigger calculation
        
        String stats = handler.getBackpressureStats();
        assertTrue(stats.contains("Throttling: Active"));
    }

    // ========== RESET TESTS ==========

    @Test
    @DisplayName("Should reset statistics")
    void testResetStats() {
        handler.recordProcessed();
        handler.recordProcessed();
        handler.recordLag(750);
        
        handler.resetStats();
        
        String stats = handler.getBackpressureStats();
        assertTrue(stats.contains("Processed: 0"));
        assertTrue(stats.contains("Lag: 0"));
    }

    // ========== DISABLED BACKPRESSURE TESTS ==========

    @Test
    @DisplayName("Should not apply backpressure when disabled")
    void testBackpressureDisabled() {
        ReflectionTestUtils.setField(handler, "backpressureEnabled", false);
        
        handler.recordLag(10000); // Very high lag
        
        assertFalse(handler.shouldApplyBackpressure(),
            "Should not apply backpressure when disabled");
        assertTrue(handler.isHealthy());
        assertEquals(100, handler.getAdaptivePollRecords());
    }

    // ========== EDGE CASE TESTS (INTERN-PROOF) ==========

    @Test
    @DisplayName("INTERN TEST: Should handle zero lag gracefully")
    void testEdgeCase_ZeroLag() {
        handler.recordLag(0);
        
        assertFalse(handler.shouldApplyBackpressure());
        assertTrue(handler.isHealthy());
    }

    @Test
    @DisplayName("INTERN TEST: Should handle negative lag (defensive)")
    void testEdgeCase_NegativeLag() {
        handler.recordLag(-100);
        
        // Should treat negative lag as zero
        assertFalse(handler.shouldApplyBackpressure());
    }

    @Test
    @DisplayName("INTERN TEST: Should handle extremely high lag")
    void testEdgeCase_ExtremelyHighLag() {
        handler.recordLag(Long.MAX_VALUE);
        
        assertTrue(handler.shouldApplyBackpressure());
        assertFalse(handler.isHealthy());
    }

    @Test
    @DisplayName("INTERN TEST: Should handle zero processed records")
    void testEdgeCase_ZeroProcessed() {
        handler.recordLag(10);
        
        // With zero processed, lag percentage is 0, should not trigger
        assertFalse(handler.shouldApplyBackpressure());
    }

    @Test
    @DisplayName("INTERN TEST: Should handle very small throttle factor")
    void testEdgeCase_SmallThrottleFactor() {
        ReflectionTestUtils.setField(handler, "throttleFactor", 0.01);
        handler.recordLag(1500);
        
        int adaptiveRecords = handler.getAdaptivePollRecords();
        assertTrue(adaptiveRecords >= 1,
            "Should maintain minimum of 1 record even with tiny throttle factor");
    }

    // ========== CONCURRENCY TESTS ==========

    @Test
    @DisplayName("Should handle concurrent access safely")
    void testConcurrentAccess() throws InterruptedException {
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                handler.recordProcessed();
            }
        });
        
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                handler.recordLag(i * 10);
            }
        });
        
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        
        // Should not throw exceptions and should have recorded some operations
        String stats = handler.getBackpressureStats();
        assertNotNull(stats);
    }

    // ========== HEALTH CHECK TESTS ==========

    @Test
    @DisplayName("Should report healthy when lag is below threshold")
    void testIsHealthy_BelowThreshold() {
        handler.recordLag(500);
        assertTrue(handler.isHealthy());
    }

    @Test
    @DisplayName("Should report unhealthy when lag exceeds threshold")
    void testIsHealthy_AboveThreshold() {
        handler.recordLag(1500);
        handler.shouldApplyBackpressure(); // Trigger check
        assertFalse(handler.isHealthy());
    }

    @Test
    @DisplayName("Should always be healthy when backpressure is disabled")
    void testIsHealthy_DisabledBackpressure() {
        ReflectionTestUtils.setField(handler, "backpressureEnabled", false);
        handler.recordLag(10000);
        
        assertTrue(handler.isHealthy(),
            "Should always be healthy when backpressure is disabled");
    }
}
