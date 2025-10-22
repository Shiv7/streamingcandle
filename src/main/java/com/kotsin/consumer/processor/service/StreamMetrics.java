package com.kotsin.consumer.processor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Stream metrics service for monitoring processing performance
 * 
 * SINGLE RESPONSIBILITY: Metrics collection and reporting
 * EXTRACTED FROM: UnifiedMarketDataProcessor (God class refactoring)
 */
@Component("processorStreamMetrics")
@RequiredArgsConstructor
@Slf4j
public class StreamMetrics {

    private final AtomicLong processedTicks = new AtomicLong(0);
    private final AtomicLong processedCandles = new AtomicLong(0);
    private final AtomicLong processedFamilies = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private final AtomicLong startTime = new AtomicLong(System.currentTimeMillis());

    /**
     * Record processed tick
     */
    public void recordTick() {
        processedTicks.incrementAndGet();
    }

    /**
     * Record processed candle
     */
    public void recordCandle() {
        processedCandles.incrementAndGet();
    }

    /**
     * Record candle emission (alias for recordCandle)
     */
    public void incCandleEmit(String timeframe) {
        processedCandles.incrementAndGet();
        log.debug("📤 Candle emitted: timeframe={}, total={}", timeframe, processedCandles.get());
    }

    /**
     * Record processed family
     */
    public void recordFamily() {
        processedFamilies.incrementAndGet();
    }

    /**
     * Record error
     */
    public void recordError() {
        errorCount.incrementAndGet();
    }

    /**
     * Get metrics summary
     */
    public String getMetrics() {
        long uptime = System.currentTimeMillis() - startTime.get();
        long uptimeSeconds = uptime / 1000;
        
        return String.format(
            "Metrics: Ticks=%d, Candles=%d, Families=%d, Errors=%d, Uptime=%ds, TPS=%.2f",
            processedTicks.get(),
            processedCandles.get(),
            processedFamilies.get(),
            errorCount.get(),
            uptimeSeconds,
            uptimeSeconds > 0 ? (double) processedTicks.get() / uptimeSeconds : 0.0
        );
    }

    /**
     * Get detailed metrics
     */
    public String getDetailedMetrics() {
        long uptime = System.currentTimeMillis() - startTime.get();
        long uptimeSeconds = uptime / 1000;
        
        return String.format(
            "Detailed Metrics:\n" +
            "  Processed Ticks: %d\n" +
            "  Processed Candles: %d\n" +
            "  Processed Families: %d\n" +
            "  Error Count: %d\n" +
            "  Uptime: %d seconds\n" +
            "  Ticks per second: %.2f\n" +
            "  Candles per second: %.2f\n" +
            "  Families per second: %.2f\n" +
            "  Error rate: %.2f%%",
            processedTicks.get(),
            processedCandles.get(),
            processedFamilies.get(),
            errorCount.get(),
            uptimeSeconds,
            uptimeSeconds > 0 ? (double) processedTicks.get() / uptimeSeconds : 0.0,
            uptimeSeconds > 0 ? (double) processedCandles.get() / uptimeSeconds : 0.0,
            uptimeSeconds > 0 ? (double) processedFamilies.get() / uptimeSeconds : 0.0,
            processedTicks.get() > 0 ? (double) errorCount.get() / processedTicks.get() * 100 : 0.0
        );
    }

    /**
     * Reset metrics
     */
    public void reset() {
        processedTicks.set(0);
        processedCandles.set(0);
        processedFamilies.set(0);
        errorCount.set(0);
        startTime.set(System.currentTimeMillis());
        log.info("🔄 Stream metrics reset");
    }

    /**
     * Get health status
     */
    public boolean isHealthy() {
        long totalProcessed = processedTicks.get() + processedCandles.get() + processedFamilies.get();
        long errors = errorCount.get();
        
        // System is healthy if error rate is below 5%
        return totalProcessed == 0 || (double) errors / totalProcessed < 0.05;
    }
}
