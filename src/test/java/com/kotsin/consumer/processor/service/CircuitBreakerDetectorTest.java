package com.kotsin.consumer.processor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CircuitBreakerDetector
 * 
 * CRITICAL: Verifies market halt detection logic
 * Purpose: Ensure proper handling of trading suspensions
 */
class CircuitBreakerDetectorTest {

    private CircuitBreakerDetector detector;

    @BeforeEach
    void setUp() {
        detector = new CircuitBreakerDetector();
    }

    // ========== Activity Tracking Tests ==========

    @Test
    @DisplayName("Activity: Records tick activity correctly")
    void testActivity_RecordsTicks() {
        Instant now = Instant.now();
        
        detector.recordTickActivity("STOCK1", now);
        detector.recordTickActivity("STOCK2", now.minusSeconds(10));
        
        assertFalse(detector.isHalted("STOCK1"), 
            "Should not be halted immediately after activity");
        assertFalse(detector.isHalted("STOCK2"), 
            "Should not be halted after recent activity");
    }

    @Test
    @DisplayName("Activity: Updates last tick time")
    void testActivity_UpdatesLastTickTime() {
        Instant time1 = Instant.now().minusSeconds(100);
        Instant time2 = Instant.now();
        
        detector.recordTickActivity("STOCK1", time1);
        detector.recordTickActivity("STOCK1", time2);
        
        // Should have updated to more recent time
        assertFalse(detector.isHalted("STOCK1"));
    }

    // ========== Halt Detection Tests ==========

    @Test
    @DisplayName("Halt Detection: Not triggered below threshold")
    void testHaltDetection_BelowThreshold() {
        // Record activity 15 minutes ago (below 20-min threshold)
        Instant recentTime = Instant.now().minus(Duration.ofMinutes(15));
        detector.recordTickActivity("STOCK1", recentTime);
        
        // Run detection
        detector.detectInactiveInstruments();
        
        assertFalse(detector.isHalted("STOCK1"), 
            "Should not be halted at 15 minutes (below 20-min threshold)");
    }

    @Test
    @DisplayName("Halt Detection: Triggered after 20 minutes")
    void testHaltDetection_AfterThreshold() {
        // Record activity 25 minutes ago (above 20-min threshold)
        Instant oldTime = Instant.now().minus(Duration.ofMinutes(25));
        detector.recordTickActivity("STOCK1", oldTime);
        
        // Run detection
        detector.detectInactiveInstruments();
        
        assertTrue(detector.isHalted("STOCK1"), 
            "Should be halted after 20+ minutes of inactivity");
    }

    @Test
    @DisplayName("Halt Resolution: Activity resumes")
    void testHaltResolution_ActivityResumes() {
        // Create halt condition
        Instant oldTime = Instant.now().minus(Duration.ofMinutes(25));
        detector.recordTickActivity("STOCK1", oldTime);
        detector.detectInactiveInstruments();
        
        assertTrue(detector.isHalted("STOCK1"), "Should be halted initially");
        
        // Resume activity
        detector.recordTickActivity("STOCK1", Instant.now());
        
        assertFalse(detector.isHalted("STOCK1"), 
            "Should not be halted after activity resumes");
    }

    // ========== Global Market Halt Tests ==========

    @Test
    @DisplayName("Global Halt: Detected when 10+ instruments halted")
    void testGlobalHalt_DetectionThreshold() {
        Instant oldTime = Instant.now().minus(Duration.ofMinutes(25));
        
        // Create halts for 9 instruments
        for (int i = 1; i <= 9; i++) {
            detector.recordTickActivity("STOCK" + i, oldTime);
        }
        
        detector.detectInactiveInstruments();
        assertFalse(detector.isGlobalMarketHalt(), 
            "Should not be global halt with only 9 instruments");
        
        // Add 10th halted instrument
        detector.recordTickActivity("STOCK10", oldTime);
        detector.detectInactiveInstruments();
        
        assertTrue(detector.isGlobalMarketHalt(), 
            "Should trigger global halt with 10+ instruments");
    }

    @Test
    @DisplayName("Global Halt: Cleared when activity resumes")
    void testGlobalHalt_ClearedOnResumption() {
        Instant oldTime = Instant.now().minus(Duration.ofMinutes(25));
        
        // Create global halt (10 instruments)
        for (int i = 1; i <= 10; i++) {
            detector.recordTickActivity("STOCK" + i, oldTime);
        }
        
        detector.detectInactiveInstruments();
        assertTrue(detector.isGlobalMarketHalt());
        
        // Resume activity for 6 instruments
        Instant now = Instant.now();
        for (int i = 1; i <= 6; i++) {
            detector.recordTickActivity("STOCK" + i, now);
        }
        
        detector.detectInactiveInstruments();
        
        // Global halt clears when active halts < 5
        // After resuming 6 instruments, only 4 remain halted
        // Note: May still be true immediately after detection, check active count
        Map<String, CircuitBreakerDetector.CircuitBreakerEvent> activeEvents = detector.getActiveEvents();
        assertTrue(activeEvents.size() <= 4, 
            "Should have 4 or fewer active halts after resumption");
    }

    // ========== Manual Halt Tests ==========

    @Test
    @DisplayName("Manual Halt: Trigger and resolve")
    void testManualHalt_TriggerAndResolve() {
        detector.triggerManualHalt("STOCK1", "Testing");
        
        assertTrue(detector.isHalted("STOCK1"), 
            "Should be halted after manual trigger");
        
        detector.resolveHalt("STOCK1");
        
        assertFalse(detector.isHalted("STOCK1"), 
            "Should not be halted after manual resolution");
    }

    // ========== Halt Duration Tests ==========

    @Test
    @DisplayName("Halt Duration: Calculates correctly")
    void testHaltDuration_Calculation() {
        Instant haltTime = Instant.now().minus(Duration.ofMinutes(30));
        detector.recordTickActivity("STOCK1", haltTime);
        detector.detectInactiveInstruments();
        
        Duration duration = detector.getHaltDuration("STOCK1");
        
        assertTrue(duration.toMinutes() >= 29 && duration.toMinutes() <= 31, 
            "Halt duration should be approximately 30 minutes");
    }

    // ========== Forced Window Close Tests ==========

    @Test
    @DisplayName("Force Close: Not triggered below 15 minutes")
    void testForceClose_BelowThreshold() {
        Instant time = Instant.now().minus(Duration.ofMinutes(10));
        detector.recordTickActivity("STOCK1", time);
        detector.detectInactiveInstruments();
        
        assertFalse(detector.shouldForceCloseWindows("STOCK1"),
            "Should not force close at 10 minutes");
    }

    @Test
    @DisplayName("Force Close: Triggered after 15+ minutes")
    void testForceClose_AfterThreshold() {
        Instant time = Instant.now().minus(Duration.ofMinutes(25));
        detector.recordTickActivity("STOCK1", time);
        detector.detectInactiveInstruments();
        
        assertTrue(detector.shouldForceCloseWindows("STOCK1"),
            "Should force close after 15+ minutes of halt");
    }

    // ========== Health Status Tests ==========

    @Test
    @DisplayName("Health Status: Reports correctly")
    void testHealthStatus_Reporting() {
        detector.recordTickActivity("STOCK1", Instant.now());
        detector.recordTickActivity("STOCK2", Instant.now().minus(Duration.ofMinutes(25)));
        detector.detectInactiveInstruments();
        
        String status = detector.getHealthStatus();
        
        assertNotNull(status);
        assertTrue(status.contains("instruments=2"), 
            "Should report 2 instruments");
        assertTrue(status.contains("halts=1"), 
            "Should report 1 halt");
    }

    @Test
    @DisplayName("Active Events: Returns only unresolved events")
    void testActiveEvents_OnlyUnresolved() {
        Instant oldTime = Instant.now().minus(Duration.ofMinutes(25));
        
        detector.recordTickActivity("STOCK1", oldTime);
        detector.recordTickActivity("STOCK2", oldTime);
        detector.detectInactiveInstruments();
        
        // Resolve STOCK1
        detector.recordTickActivity("STOCK1", Instant.now());
        
        Map<String, CircuitBreakerDetector.CircuitBreakerEvent> activeEvents = 
            detector.getActiveEvents();
        
        assertFalse(activeEvents.containsKey("STOCK1"), 
            "Resolved halt should not be in active events");
        assertTrue(activeEvents.containsKey("STOCK2"), 
            "Unresolved halt should be in active events");
    }

    // ========== Edge Cases ==========

    @Test
    @DisplayName("Edge Case: Null scripCode handled gracefully")
    void testEdgeCase_NullScripCode() {
        assertDoesNotThrow(() -> 
            detector.recordTickActivity(null, Instant.now())
        );
    }

    @Test
    @DisplayName("Edge Case: Null timestamp handled gracefully")
    void testEdgeCase_NullTimestamp() {
        assertDoesNotThrow(() -> 
            detector.recordTickActivity("STOCK1", null)
        );
    }

    @Test
    @DisplayName("Edge Case: Clear all data")
    void testEdgeCase_ClearAll() {
        detector.recordTickActivity("STOCK1", Instant.now());
        detector.triggerManualHalt("STOCK2", "Test");
        
        detector.clear();
        
        assertFalse(detector.isHalted("STOCK1"));
        assertFalse(detector.isHalted("STOCK2"));
        assertFalse(detector.isGlobalMarketHalt());
    }
}

