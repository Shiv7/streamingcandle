package com.kotsin.consumer.retry;

import com.kotsin.consumer.config.ProcessingConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Retry handler with exponential backoff
 * 
 * RESILIENCE: Handle transient failures gracefully
 * BEST PRACTICE: Exponential backoff to avoid overwhelming services
 */
@Component
@Slf4j
public class RetryHandler {

    /**
     * Execute operation with retry logic
     */
    public <T> T executeWithRetry(Supplier<T> operation, String operationName) {
        return executeWithRetry(
            operation, 
            operationName, 
            ProcessingConstants.MAX_RETRY_ATTEMPTS
        );
    }

    /**
     * Execute operation with custom retry count
     */
    public <T> T executeWithRetry(Supplier<T> operation, String operationName, int maxAttempts) {
        int attempt = 0;
        Exception lastException = null;

        while (attempt < maxAttempts) {
            try {
                return operation.get();
            } catch (Exception e) {
                lastException = e;
                attempt++;
                
                if (attempt >= maxAttempts) {
                    log.error("❌ Operation '{}' failed after {} attempts", 
                        operationName, maxAttempts);
                    break;
                }

                long delayMs = calculateBackoffDelay(attempt);
                log.warn("⚠️ Operation '{}' failed (attempt {}/{}). Retrying in {}ms. Error: {}", 
                    operationName, attempt, maxAttempts, delayMs, e.getMessage());

                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
            }
        }

        throw new RuntimeException(
            String.format("Operation '%s' failed after %d attempts", operationName, maxAttempts),
            lastException
        );
    }

    /**
     * Execute operation with retry logic (void return)
     */
    public void executeWithRetry(Runnable operation, String operationName) {
        executeWithRetry(() -> {
            operation.run();
            return null;
        }, operationName);
    }

    /**
     * Calculate backoff delay using exponential backoff
     */
    private long calculateBackoffDelay(int attempt) {
        long delay = (long) (ProcessingConstants.INITIAL_RETRY_DELAY_MS * 
            Math.pow(ProcessingConstants.RETRY_BACKOFF_MULTIPLIER, attempt - 1));
        
        return Math.min(delay, ProcessingConstants.MAX_RETRY_DELAY_MS);
    }

    /**
     * Check if exception is retryable
     */
    public boolean isRetryable(Exception e) {
        // Retry on transient failures
        if (e instanceof java.net.SocketTimeoutException ||
            e instanceof java.net.ConnectException ||
            e instanceof java.io.IOException) {
            return true;
        }

        // Check exception message for common transient errors
        String message = e.getMessage();
        if (message != null) {
            return message.contains("timeout") ||
                   message.contains("connection refused") ||
                   message.contains("temporarily unavailable");
        }

        return false;
    }
}
