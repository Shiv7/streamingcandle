package com.kotsin.consumer.processor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Backpressure handler for Kafka Streams processing
 * 
 * CRITICAL FIX: Implements flow control to prevent memory overflow
 * Monitors processing lag and applies adaptive throttling
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BackpressureHandler {

    @Value("${kafka.streams.backpressure.enabled:true}")
    private boolean backpressureEnabled;
    
    @Value("${kafka.streams.backpressure.max.poll.records:100}")
    private int maxPollRecords;
    
    @Value("${kafka.streams.backpressure.lag.threshold:1000}")
    private long lagThreshold;
    
    @Value("${kafka.streams.backpressure.throttle.factor:0.5}")
    private double throttleFactor;

    private final AtomicLong processedRecords = new AtomicLong(0);
    private final AtomicLong lagRecords = new AtomicLong(0);
    private final AtomicBoolean throttlingActive = new AtomicBoolean(false);
    private final AtomicLong lastThrottleTime = new AtomicLong(0);

    /**
     * Check if backpressure should be applied
     */
    public boolean shouldApplyBackpressure() {
        if (!backpressureEnabled) {
            return false;
        }

        long currentLag = lagRecords.get();
        long currentProcessed = processedRecords.get();
        
        // Calculate lag percentage
        double lagPercentage = currentProcessed > 0 ? (double) currentLag / currentProcessed : 0;
        
        // Apply backpressure if lag exceeds threshold
        boolean shouldThrottle = currentLag > lagThreshold || lagPercentage > 0.1; // 10% lag threshold
        
        if (shouldThrottle && !throttlingActive.get()) {
            log.warn("🚨 Backpressure triggered: lag={}, processed={}, percentage={:.2f}%", 
                currentLag, currentProcessed, lagPercentage * 100);
            throttlingActive.set(true);
            lastThrottleTime.set(System.currentTimeMillis());
        } else if (!shouldThrottle && throttlingActive.get()) {
            long throttleDuration = System.currentTimeMillis() - lastThrottleTime.get();
            log.info("✅ Backpressure released after {}ms: lag={}, processed={}", 
                throttleDuration, currentLag, currentProcessed);
            throttlingActive.set(false);
        }
        
        return shouldThrottle;
    }

    /**
     * Get adaptive poll records based on current lag
     */
    public int getAdaptivePollRecords() {
        if (!backpressureEnabled) {
            return maxPollRecords;
        }

        if (shouldApplyBackpressure()) {
            // Reduce poll records when under pressure
            int adaptiveRecords = (int) (maxPollRecords * throttleFactor);
            return Math.max(adaptiveRecords, 1); // Minimum 1 record
        }
        
        return maxPollRecords;
    }

    /**
     * Record processed record
     */
    public void recordProcessed() {
        processedRecords.incrementAndGet();
    }

    /**
     * Record lag
     */
    public void recordLag(long lag) {
        lagRecords.set(lag);
    }

    /**
     * Get backpressure statistics
     */
    public String getBackpressureStats() {
        return String.format("Backpressure: %s, Processed: %d, Lag: %d, Throttling: %s", 
            backpressureEnabled ? "Enabled" : "Disabled",
            processedRecords.get(),
            lagRecords.get(),
            throttlingActive.get() ? "Active" : "Inactive");
    }

    /**
     * Reset statistics
     */
    public void resetStats() {
        processedRecords.set(0);
        lagRecords.set(0);
        throttlingActive.set(false);
        log.info("🔄 Backpressure statistics reset");
    }

    /**
     * Check if system is healthy (not under excessive pressure)
     */
    public boolean isHealthy() {
        if (!backpressureEnabled) {
            return true;
        }

        long currentLag = lagRecords.get();
        long currentProcessed = processedRecords.get();
        
        // System is healthy if lag is below threshold and not throttling
        return currentLag <= lagThreshold && !throttlingActive.get();
    }
}
