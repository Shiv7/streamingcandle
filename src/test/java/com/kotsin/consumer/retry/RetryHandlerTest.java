package com.kotsin.consumer.retry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DEFENSIVE TESTS FOR RetryHandler
 * 
 * GOAL: Ensure retry logic is bulletproof and handles all failure scenarios
 * APPROACH: Test success, failure, transient errors, and edge cases
 */
@DisplayName("RetryHandler - Comprehensive Tests")
class RetryHandlerTest {

    private RetryHandler retryHandler;

    @BeforeEach
    void setUp() {
        retryHandler = new RetryHandler();
    }

    // ========== SUCCESSFUL OPERATION TESTS ==========

    @Test
    @DisplayName("Should execute operation successfully on first attempt")
    void testExecuteWithRetry_SuccessFirstAttempt() {
        String result = retryHandler.executeWithRetry(
            () -> "success",
            "test-operation"
        );
        
        assertEquals("success", result);
    }

    @Test
    @DisplayName("Should execute void operation successfully")
    void testExecuteWithRetry_VoidOperation() {
        AtomicInteger counter = new AtomicInteger(0);
        
        assertDoesNotThrow(() -> 
            retryHandler.executeWithRetry(
                () -> counter.incrementAndGet(),
                "test-void-operation"
            )
        );
        
        assertEquals(1, counter.get());
    }

    // ========== RETRY MECHANISM TESTS ==========

    @Test
    @DisplayName("Should retry on failure and eventually succeed")
    void testExecuteWithRetry_SuccessAfterRetries() {
        AtomicInteger attemptCounter = new AtomicInteger(0);
        
        String result = retryHandler.executeWithRetry(
            () -> {
                int attempt = attemptCounter.incrementAndGet();
                if (attempt < 3) {
                    throw new RuntimeException("Transient failure");
                }
                return "success-after-retries";
            },
            "test-retry-operation"
        );
        
        assertEquals("success-after-retries", result);
        assertEquals(3, attemptCounter.get());
    }

    @Test
    @DisplayName("Should respect max attempts")
    void testExecuteWithRetry_MaxAttemptsReached() {
        AtomicInteger attemptCounter = new AtomicInteger(0);
        
        RuntimeException exception = assertThrows(
            RuntimeException.class,
            () -> retryHandler.executeWithRetry(
                () -> {
                    attemptCounter.incrementAndGet();
                    throw new RuntimeException("Always fails");
                },
                "test-max-attempts",
                3 // max attempts
            )
        );
        
        assertEquals(3, attemptCounter.get());
        assertTrue(exception.getMessage().contains("failed after 3 attempts"));
    }

    // ========== EXCEPTION HANDLING TESTS ==========

    @Test
    @DisplayName("Should wrap original exception in RuntimeException")
    void testExecuteWithRetry_WrapsException() {
        IOException originalException = new IOException("Original error");
        
        RuntimeException exception = assertThrows(
            RuntimeException.class,
            () -> retryHandler.executeWithRetry(
                () -> {
                    try {
                        throw originalException;
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                "test-exception-wrapping",
                2
            )
        );
        
        assertNotNull(exception.getCause());
    }

    // ========== RETRYABLE EXCEPTION TESTS ==========

    @Test
    @DisplayName("Should identify SocketTimeoutException as retryable")
    void testIsRetryable_SocketTimeout() {
        assertTrue(retryHandler.isRetryable(new SocketTimeoutException("timeout")));
    }

    @Test
    @DisplayName("Should identify ConnectException as retryable")
    void testIsRetryable_ConnectException() {
        assertTrue(retryHandler.isRetryable(new ConnectException("connection refused")));
    }

    @Test
    @DisplayName("Should identify IOException as retryable")
    void testIsRetryable_IOException() {
        assertTrue(retryHandler.isRetryable(new IOException("io error")));
    }

    @Test
    @DisplayName("Should identify timeout in message as retryable")
    void testIsRetryable_TimeoutMessage() {
        RuntimeException ex = new RuntimeException("Request timeout");
        assertTrue(retryHandler.isRetryable(ex));
    }

    @Test
    @DisplayName("Should identify connection refused in message as retryable")
    void testIsRetryable_ConnectionRefusedMessage() {
        RuntimeException ex = new RuntimeException("connection refused by server");
        assertTrue(retryHandler.isRetryable(ex));
    }

    @Test
    @DisplayName("Should identify temporarily unavailable as retryable")
    void testIsRetryable_TemporarilyUnavailable() {
        RuntimeException ex = new RuntimeException("Service temporarily unavailable");
        assertTrue(retryHandler.isRetryable(ex));
    }

    @Test
    @DisplayName("Should NOT identify random exceptions as retryable")
    void testIsRetryable_NonRetryableException() {
        assertFalse(retryHandler.isRetryable(new IllegalArgumentException("bad argument")));
        assertFalse(retryHandler.isRetryable(new NullPointerException()));
    }

    @Test
    @DisplayName("Should NOT identify exceptions with null message as retryable")
    void testIsRetryable_NullMessage() {
        RuntimeException ex = new RuntimeException((String) null);
        assertFalse(retryHandler.isRetryable(ex));
    }

    // ========== BACKOFF DELAY TESTS ==========

    @Test
    @DisplayName("Should increase delay exponentially")
    void testExponentialBackoff() {
        AtomicInteger attemptCounter = new AtomicInteger(0);
        long[] delays = new long[3];
        
        try {
            retryHandler.executeWithRetry(
                () -> {
                    int attempt = attemptCounter.getAndIncrement();
                    if (attempt < 3) {
                        delays[attempt] = System.currentTimeMillis();
                        throw new RuntimeException("Force retry");
                    }
                    return "success";
                },
                "test-backoff"
            );
        } catch (Exception e) {
            // Expected
        }
        
        // Verify delays are increasing (approximately)
        if (delays[0] > 0 && delays[1] > 0 && delays[2] > 0) {
            long delay1 = delays[1] - delays[0];
            long delay2 = delays[2] - delays[1];
            assertTrue(delay2 > delay1, "Second delay should be longer than first");
        }
    }

    // ========== EDGE CASE TESTS (INTERN-PROOF) ==========

    @Test
    @DisplayName("INTERN TEST: Should handle max attempts of 1")
    void testEdgeCase_MaxAttempts1() {
        AtomicInteger counter = new AtomicInteger(0);
        
        assertThrows(
            RuntimeException.class,
            () -> retryHandler.executeWithRetry(
                () -> {
                    counter.incrementAndGet();
                    throw new RuntimeException("Always fails");
                },
                "test-single-attempt",
                1
            )
        );
        
        assertEquals(1, counter.get(), "Should only attempt once");
    }

    @Test
    @DisplayName("INTERN TEST: Should handle max attempts of 0 (defensive)")
    void testEdgeCase_MaxAttempts0() {
        AtomicInteger counter = new AtomicInteger(0);
        
        assertThrows(
            RuntimeException.class,
            () -> retryHandler.executeWithRetry(
                () -> {
                    counter.incrementAndGet();
                    throw new RuntimeException("Always fails");
                },
                "test-zero-attempts",
                0
            )
        );
        
        assertEquals(0, counter.get(), "Should not attempt at all");
    }

    @Test
    @DisplayName("INTERN TEST: Should handle negative max attempts (defensive)")
    void testEdgeCase_NegativeMaxAttempts() {
        AtomicInteger counter = new AtomicInteger(0);
        
        assertThrows(
            RuntimeException.class,
            () -> retryHandler.executeWithRetry(
                () -> {
                    counter.incrementAndGet();
                    throw new RuntimeException("Always fails");
                },
                "test-negative-attempts",
                -1
            )
        );
        
        assertEquals(0, counter.get(), "Should not attempt with negative max attempts");
    }

    @Test
    @DisplayName("INTERN TEST: Should handle null operation name")
    void testEdgeCase_NullOperationName() {
        String result = retryHandler.executeWithRetry(
            () -> "success",
            null
        );
        
        assertEquals("success", result);
    }

    @Test
    @DisplayName("INTERN TEST: Should handle empty operation name")
    void testEdgeCase_EmptyOperationName() {
        String result = retryHandler.executeWithRetry(
            () -> "success",
            ""
        );
        
        assertEquals("success", result);
    }

    @Test
    @DisplayName("INTERN TEST: Should handle operation returning null")
    void testEdgeCase_NullReturn() {
        String result = retryHandler.executeWithRetry(
            () -> null,
            "test-null-return"
        );
        
        assertNull(result);
    }

    @Test
    @DisplayName("INTERN TEST: Should handle exception with very long message")
    void testEdgeCase_LongExceptionMessage() {
        String longMessage = "error".repeat(1000);
        RuntimeException ex = new RuntimeException(longMessage);
        
        // Should still identify as retryable if it contains "timeout"
        assertFalse(retryHandler.isRetryable(ex));
    }

    // ========== INTERRUPTED EXCEPTION TESTS ==========

    @Test
    @DisplayName("Should handle thread interruption during retry")
    void testInterruptedExecution() {
        Thread.currentThread().interrupt();
        
        assertThrows(
            RuntimeException.class,
            () -> retryHandler.executeWithRetry(
                () -> {
                    throw new RuntimeException("Fail to trigger retry");
                },
                "test-interrupted",
                3
            )
        );
        
        assertTrue(Thread.interrupted(), "Thread should be interrupted");
    }

    // ========== CONCURRENT RETRY TESTS ==========

    @Test
    @DisplayName("Should handle concurrent retries safely")
    void testConcurrentRetries() throws InterruptedException {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        Runnable task = () -> {
            try {
                retryHandler.executeWithRetry(
                    () -> "success",
                    "concurrent-test"
                );
                successCount.incrementAndGet();
            } catch (Exception e) {
                failureCount.incrementAndGet();
            }
        };
        
        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        Thread t3 = new Thread(task);
        
        t1.start();
        t2.start();
        t3.start();
        
        t1.join();
        t2.join();
        t3.join();
        
        assertEquals(3, successCount.get());
        assertEquals(0, failureCount.get());
    }

    // ========== PERFORMANCE TESTS ==========

    @Test
    @DisplayName("INTERN TEST: Should not retry forever (timeout protection)")
    void testPerformance_NoInfiniteRetry() {
        AtomicInteger counter = new AtomicInteger(0);
        
        long startTime = System.currentTimeMillis();
        
        assertThrows(
            RuntimeException.class,
            () -> retryHandler.executeWithRetry(
                () -> {
                    counter.incrementAndGet();
                    throw new RuntimeException("Always fails");
                },
                "test-performance",
                3
            )
        );
        
        long duration = System.currentTimeMillis() - startTime;
        
        // Should complete within reasonable time (< 1 minute for 3 retries)
        assertTrue(duration < 60000, 
            "Retry should complete within 1 minute, took: " + duration + "ms");
    }

    // ========== DEFAULT MAX ATTEMPTS TESTS ==========

    @Test
    @DisplayName("Should use default max attempts when not specified")
    void testDefaultMaxAttempts() {
        AtomicInteger counter = new AtomicInteger(0);
        
        assertThrows(
            RuntimeException.class,
            () -> retryHandler.executeWithRetry(
                () -> {
                    counter.incrementAndGet();
                    throw new RuntimeException("Always fails");
                },
                "test-default-attempts"
            )
        );
        
        // Default should be 3 attempts (from ProcessingConstants)
        assertEquals(3, counter.get());
    }
}
