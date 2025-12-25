package com.kotsin.consumer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * CircuitBreaker - Prevents cascading failures from external services
 * 
 * States:
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: Failures exceeded threshold, requests rejected immediately
 * - HALF_OPEN: Testing if service recovered
 * 
 * Use for external API calls (e.g., OHM options API)
 */
public class CircuitBreaker {

    private static final Logger LOGGER = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State {
        CLOSED,     // Normal - requests pass
        OPEN,       // Failed - requests blocked
        HALF_OPEN   // Testing recovery
    }

    private final String name;
    private final int failureThreshold;
    private final long openTimeoutMs;
    private final int halfOpenMaxCalls;

    private volatile State state = State.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenCalls = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong openedTime = new AtomicLong(0);

    // Statistics
    private final AtomicLong totalCalls = new AtomicLong(0);
    private final AtomicLong rejectedCalls = new AtomicLong(0);
    private final AtomicLong successfulCalls = new AtomicLong(0);
    private final AtomicLong failedCalls = new AtomicLong(0);

    /**
     * Create circuit breaker
     * 
     * @param name Service name for logging
     * @param failureThreshold Failures before opening (e.g., 5)
     * @param openTimeoutMs Time to wait before half-open (e.g., 60000ms = 1 min)
     * @param halfOpenMaxCalls Max calls in half-open before closing (e.g., 3)
     */
    public CircuitBreaker(String name, int failureThreshold, long openTimeoutMs, int halfOpenMaxCalls) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openTimeoutMs = openTimeoutMs;
        this.halfOpenMaxCalls = halfOpenMaxCalls;
        
        LOGGER.info("⚡ CircuitBreaker '{}' created: threshold={}, timeout={}ms", 
                name, failureThreshold, openTimeoutMs);
    }

    /**
     * Execute a call through the circuit breaker
     * 
     * @param action The action to execute
     * @param fallback Fallback value if circuit is open or action fails
     * @return Result or fallback
     */
    public <T> T execute(Supplier<T> action, T fallback) {
        totalCalls.incrementAndGet();

        // Check state transitions
        checkStateTransitions();

        if (state == State.OPEN) {
            rejectedCalls.incrementAndGet();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("🔴 CircuitBreaker '{}' OPEN - rejecting call", name);
            }
            return fallback;
        }

        if (state == State.HALF_OPEN) {
            int calls = halfOpenCalls.incrementAndGet();
            if (calls > halfOpenMaxCalls) {
                rejectedCalls.incrementAndGet();
                return fallback;
            }
        }

        try {
            T result = action.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure(e);
            return fallback;
        }
    }

    /**
     * Execute without return value
     */
    public void executeVoid(Runnable action, Runnable fallback) {
        Boolean result = execute(() -> {
            action.run();
            return Boolean.TRUE;
        }, Boolean.FALSE);
        if (Boolean.FALSE.equals(result) && fallback != null) {
            fallback.run();
        }
    }

    /**
     * Check if ready to accept calls
     */
    public boolean isReady() {
        checkStateTransitions();
        return state != State.OPEN;
    }

    /**
     * Get current state
     */
    public State getState() {
        return state;
    }

    /**
     * Force reset to closed state
     */
    public void reset() {
        state = State.CLOSED;
        failureCount.set(0);
        successCount.set(0);
        halfOpenCalls.set(0);
        LOGGER.info("🔄 CircuitBreaker '{}' manually reset to CLOSED", name);
    }

    private void checkStateTransitions() {
        if (state == State.OPEN) {
            long elapsed = System.currentTimeMillis() - openedTime.get();
            if (elapsed >= openTimeoutMs) {
                transitionTo(State.HALF_OPEN);
            }
        }
    }

    private void onSuccess() {
        successfulCalls.incrementAndGet();
        failureCount.set(0);  // Reset consecutive failures

        if (state == State.HALF_OPEN) {
            int successes = successCount.incrementAndGet();
            if (successes >= halfOpenMaxCalls) {
                transitionTo(State.CLOSED);
            }
        }
    }

    private void onFailure(Exception e) {
        failedCalls.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        int failures = failureCount.incrementAndGet();

        LOGGER.warn("⚠️ CircuitBreaker '{}' failure #{}: {}", name, failures, e.getMessage());

        if (state == State.HALF_OPEN) {
            transitionTo(State.OPEN);
        } else if (state == State.CLOSED && failures >= failureThreshold) {
            transitionTo(State.OPEN);
        }
    }

    private void transitionTo(State newState) {
        State oldState = this.state;
        this.state = newState;

        if (newState == State.OPEN) {
            openedTime.set(System.currentTimeMillis());
            halfOpenCalls.set(0);
            successCount.set(0);
            LOGGER.warn("🔴 CircuitBreaker '{}' OPENED after {} failures", name, failureCount.get());
        } else if (newState == State.HALF_OPEN) {
            halfOpenCalls.set(0);
            successCount.set(0);
            LOGGER.info("🟡 CircuitBreaker '{}' HALF_OPEN - testing recovery", name);
        } else if (newState == State.CLOSED) {
            failureCount.set(0);
            LOGGER.info("🟢 CircuitBreaker '{}' CLOSED - service recovered", name);
        }

        LOGGER.info("⚡ CircuitBreaker '{}' state: {} -> {}", name, oldState, newState);
    }

    /**
     * Get statistics
     */
    public String getStats() {
        return String.format(
            "CircuitBreaker '%s': state=%s, total=%d, success=%d, failed=%d, rejected=%d",
            name, state, totalCalls.get(), successfulCalls.get(), failedCalls.get(), rejectedCalls.get()
        );
    }

    public void logStats() {
        LOGGER.info("📊 {}", getStats());
    }
}
