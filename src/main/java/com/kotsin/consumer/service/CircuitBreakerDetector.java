package com.kotsin.consumer.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detector for market halts and circuit breakers
 * 
 * Indian Market Circuit Breaker Rules:
 * - 10% move -> 15-minute halt at index level
 * - 15% move -> Market closed for the day
 * - 20% move -> Market closed for the day
 * 
 * CRITICAL: Handles forced window closes when data stops flowing
 * 
 * @author System
 */
@Service
@Slf4j
@Data
public class CircuitBreakerDetector {
    
    // Timeout thresholds
    private static final Duration INACTIVITY_WARNING_THRESHOLD = Duration.ofMinutes(5);
    private static final Duration INACTIVITY_HALT_THRESHOLD = Duration.ofMinutes(20);
    
    // Track last tick time per instrument
    private final Map<String, Instant> lastTickTime = new ConcurrentHashMap<>();
    
    // Track circuit breaker events
    private final Map<String, CircuitBreakerEvent> circuitBreakerEvents = new ConcurrentHashMap<>();
    
    // Global market halt flag
    private volatile boolean globalMarketHalt = false;
    private volatile Instant globalHaltTime = null;
    
    /**
     * Circuit breaker event
     */
    @Data
    public static class CircuitBreakerEvent {
        String scripCode;
        Instant haltTime;
        String reason;  // INACTIVITY, CIRCUIT_BREAKER, MANUAL
        Duration inactivityDuration;
        boolean resolved;
    }
    
    /**
     * Update last tick time for instrument
     */
    public void recordTickActivity(String scripCode, Instant tickTime) {
        if (scripCode == null || tickTime == null) {
            return;
        }
        
        lastTickTime.put(scripCode, tickTime);
        
        // Check if this resolves a previous halt
        CircuitBreakerEvent event = circuitBreakerEvents.get(scripCode);
        if (event != null && !event.resolved) {
            event.resolved = true;
            log.info("✅ Market activity resumed for {}: inactive for {}", 
                scripCode, event.inactivityDuration);
            circuitBreakerEvents.remove(scripCode);
        }
    }
    
    /**
     * Check for inactive instruments (scheduled every minute)
     */
    @Scheduled(fixedRate = 60000)  // Every 60 seconds
    public void detectInactiveInstruments() {
        Instant now = Instant.now();
        
        for (Map.Entry<String, Instant> entry : lastTickTime.entrySet()) {
            String scripCode = entry.getKey();
            Instant lastTick = entry.getValue();
            
            Duration inactivity = Duration.between(lastTick, now);
            
            // Warning level (5 minutes)
            if (inactivity.compareTo(INACTIVITY_WARNING_THRESHOLD) > 0 && 
                inactivity.compareTo(INACTIVITY_HALT_THRESHOLD) < 0) {
                
                log.warn("⚠️ Inactivity warning for {}: {} minutes since last tick", 
                    scripCode, inactivity.toMinutes());
            }
            
            // Halt level (20 minutes) - trigger circuit breaker
            if (inactivity.compareTo(INACTIVITY_HALT_THRESHOLD) > 0) {
                CircuitBreakerEvent existingEvent = circuitBreakerEvents.get(scripCode);
                
                if (existingEvent == null || existingEvent.resolved) {
                    // New halt detected
                    CircuitBreakerEvent event = new CircuitBreakerEvent();
                    event.scripCode = scripCode;
                    event.haltTime = lastTick;
                    event.reason = "INACTIVITY";
                    event.inactivityDuration = inactivity;
                    event.resolved = false;
                    
                    circuitBreakerEvents.put(scripCode, event);
                    
                    log.error("🛑 CIRCUIT BREAKER TRIGGERED for {}: {} minutes of inactivity. " +
                        "Last tick: {}", scripCode, inactivity.toMinutes(), lastTick);
                    
                    // Check if this is a global market halt (multiple instruments affected)
                    checkForGlobalHalt(now);
                }
            }
        }
    }
    
    /**
     * Check if multiple instruments are halted (indicates global market halt)
     */
    private void checkForGlobalHalt(Instant now) {
        long activeHalts = circuitBreakerEvents.values().stream()
            .filter(e -> !e.resolved)
            .count();
        
        // If 10+ instruments are halted, consider it a global market halt
        if (activeHalts >= 10 && !globalMarketHalt) {
            globalMarketHalt = true;
            globalHaltTime = now;
            
            log.error("🚨 GLOBAL MARKET HALT DETECTED: {} instruments affected. Time: {}", 
                activeHalts, now);
        }
        
        // Reset global halt if activity resumes
        if (activeHalts < 5 && globalMarketHalt) {
            globalMarketHalt = false;
            log.info("✅ Global market activity resumed. Active halts: {}", activeHalts);
        }
    }
    
    /**
     * Check if instrument is currently halted
     */
    public boolean isHalted(String scripCode) {
        CircuitBreakerEvent event = circuitBreakerEvents.get(scripCode);
        return event != null && !event.resolved;
    }
    
    /**
     * Check if global market is halted
     */
    public boolean isGlobalMarketHalt() {
        return globalMarketHalt;
    }
    
    /**
     * Get halt duration for instrument
     */
    public Duration getHaltDuration(String scripCode) {
        CircuitBreakerEvent event = circuitBreakerEvents.get(scripCode);
        if (event == null || event.resolved) {
            return Duration.ZERO;
        }
        return Duration.between(event.haltTime, Instant.now());
    }
    
    /**
     * Force close windows for halted instrument
     * Should be called by window rotation service
     */
    public boolean shouldForceCloseWindows(String scripCode) {
        CircuitBreakerEvent event = circuitBreakerEvents.get(scripCode);
        if (event == null || event.resolved) {
            return false;
        }
        
        // Force close if halted for more than 15 minutes
        return event.inactivityDuration.compareTo(Duration.ofMinutes(15)) > 0;
    }
    
    /**
     * Get active circuit breaker events
     */
    public Map<String, CircuitBreakerEvent> getActiveEvents() {
        Map<String, CircuitBreakerEvent> active = new ConcurrentHashMap<>();
        for (Map.Entry<String, CircuitBreakerEvent> entry : circuitBreakerEvents.entrySet()) {
            if (!entry.getValue().resolved) {
                active.put(entry.getKey(), entry.getValue());
            }
        }
        return active;
    }
    
    /**
     * Manual circuit breaker trigger (for testing or manual intervention)
     */
    public void triggerManualHalt(String scripCode, String reason) {
        CircuitBreakerEvent event = new CircuitBreakerEvent();
        event.scripCode = scripCode;
        event.haltTime = Instant.now();
        event.reason = "MANUAL: " + reason;
        event.inactivityDuration = Duration.ZERO;
        event.resolved = false;
        
        circuitBreakerEvents.put(scripCode, event);
        
        log.warn("🛑 Manual circuit breaker triggered for {}: {}", scripCode, reason);
    }
    
    /**
     * Resolve halt manually
     */
    public void resolveHalt(String scripCode) {
        CircuitBreakerEvent event = circuitBreakerEvents.get(scripCode);
        if (event != null) {
            event.resolved = true;
            log.info("✅ Circuit breaker resolved for {}", scripCode);
            circuitBreakerEvents.remove(scripCode);
        }
    }
    
    /**
     * Clear all tracking data (for testing or restart)
     */
    public void clear() {
        lastTickTime.clear();
        circuitBreakerEvents.clear();
        globalMarketHalt = false;
        globalHaltTime = null;
    }
    
    /**
     * Get health status
     */
    public String getHealthStatus() {
        long totalInstruments = lastTickTime.size();
        long activeHalts = circuitBreakerEvents.values().stream()
            .filter(e -> !e.resolved)
            .count();
        
        return String.format("CircuitBreaker[instruments=%d, halts=%d, globalHalt=%s]",
            totalInstruments, activeHalts, globalMarketHalt);
    }
}

