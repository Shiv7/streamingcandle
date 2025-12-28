package com.kotsin.consumer.monitoring;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DataQualityMetrics - Tracks data quality metrics across the pipeline
 * 
 * Provides:
 * - Counters for valid/invalid candles
 * - Counters by violation type
 * - Counters by processor
 * - Aggregation stats
 */
@Component
@Slf4j
public class DataQualityMetrics {

    // ==================== GLOBAL COUNTERS ====================
    private final AtomicLong totalCandlesProcessed = new AtomicLong(0);
    private final AtomicLong totalInvalidCandles = new AtomicLong(0);
    private final AtomicLong totalMergedCandles = new AtomicLong(0);
    private final AtomicLong totalDeduplicatedOptions = new AtomicLong(0);

    // ==================== COUNTERS BY VIOLATION TYPE ====================
    private final Map<String, AtomicLong> violationCounters = new ConcurrentHashMap<>();

    // ==================== COUNTERS BY PROCESSOR ====================
    private final Map<String, ProcessorMetrics> processorMetrics = new ConcurrentHashMap<>();

    // ==================== COUNTERS BY TIMEFRAME ====================
    private final Map<String, AtomicLong> timeframeCandleCount = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> timeframeTinyRangeCount = new ConcurrentHashMap<>();

    // ==================== INCREMENT METHODS ====================

    /**
     * Record a processed candle
     */
    public void recordCandleProcessed(String processor, String timeframe, boolean isValid) {
        totalCandlesProcessed.incrementAndGet();
        
        if (!isValid) {
            totalInvalidCandles.incrementAndGet();
        }
        
        // Processor-level metrics
        getProcessorMetrics(processor).recordCandle(isValid);
        
        // Timeframe-level metrics
        if (timeframe != null) {
            timeframeCandleCount.computeIfAbsent(timeframe, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    /**
     * Record a specific violation
     */
    public void recordViolation(String violationType) {
        violationCounters.computeIfAbsent(violationType, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * Record a merge event (multiple candles merged in window)
     */
    public void recordMerge(String processor, int mergeCount) {
        totalMergedCandles.addAndGet(mergeCount);
        getProcessorMetrics(processor).recordMerge(mergeCount);
    }

    /**
     * Record options deduplication
     */
    public void recordOptionsDeduplication(int originalCount, int deduplicatedCount) {
        int removed = originalCount - deduplicatedCount;
        if (removed > 0) {
            totalDeduplicatedOptions.addAndGet(removed);
        }
    }

    /**
     * Record a tiny range warning (for aggregated candles)
     */
    public void recordTinyRange(String timeframe) {
        timeframeTinyRangeCount.computeIfAbsent(timeframe, k -> new AtomicLong(0)).incrementAndGet();
    }

    // ==================== GETTER METHODS ====================

    public long getTotalCandlesProcessed() {
        return totalCandlesProcessed.get();
    }

    public long getTotalInvalidCandles() {
        return totalInvalidCandles.get();
    }

    public long getTotalMergedCandles() {
        return totalMergedCandles.get();
    }

    public long getTotalDeduplicatedOptions() {
        return totalDeduplicatedOptions.get();
    }

    public double getInvalidCandleRate() {
        long total = totalCandlesProcessed.get();
        return total > 0 ? (double) totalInvalidCandles.get() / total * 100 : 0;
    }

    public Map<String, Long> getViolationCounts() {
        Map<String, Long> result = new ConcurrentHashMap<>();
        violationCounters.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    public Map<String, Long> getTimeframeCandleCounts() {
        Map<String, Long> result = new ConcurrentHashMap<>();
        timeframeCandleCount.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    public Map<String, Long> getTimeframeTinyRangeCounts() {
        Map<String, Long> result = new ConcurrentHashMap<>();
        timeframeTinyRangeCount.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    // ==================== HELPER ====================

    private ProcessorMetrics getProcessorMetrics(String processor) {
        return processorMetrics.computeIfAbsent(processor, k -> new ProcessorMetrics(processor));
    }

    public Map<String, ProcessorMetrics> getProcessorMetrics() {
        return processorMetrics;
    }

    // ==================== SNAPSHOT FOR API ====================

    /**
     * Get a complete snapshot of all metrics
     */
    public DataQualitySnapshot getSnapshot() {
        return DataQualitySnapshot.builder()
            .totalCandlesProcessed(totalCandlesProcessed.get())
            .totalInvalidCandles(totalInvalidCandles.get())
            .invalidCandleRate(getInvalidCandleRate())
            .totalMergedCandles(totalMergedCandles.get())
            .totalDeduplicatedOptions(totalDeduplicatedOptions.get())
            .violationCounts(getViolationCounts())
            .timeframeCandleCounts(getTimeframeCandleCounts())
            .timeframeTinyRangeCounts(getTimeframeTinyRangeCounts())
            .processorStats(getProcessorStats())
            .build();
    }

    private Map<String, Map<String, Long>> getProcessorStats() {
        Map<String, Map<String, Long>> result = new ConcurrentHashMap<>();
        processorMetrics.forEach((name, metrics) -> {
            Map<String, Long> stats = new ConcurrentHashMap<>();
            stats.put("totalProcessed", metrics.getTotalProcessed());
            stats.put("invalidCount", metrics.getInvalidCount());
            stats.put("mergeCount", metrics.getMergeCount());
            result.put(name, stats);
        });
        return result;
    }

    // ==================== RESET ====================

    /**
     * Reset all metrics (for testing or periodic refresh)
     */
    public void reset() {
        totalCandlesProcessed.set(0);
        totalInvalidCandles.set(0);
        totalMergedCandles.set(0);
        totalDeduplicatedOptions.set(0);
        violationCounters.clear();
        processorMetrics.clear();
        timeframeCandleCount.clear();
        timeframeTinyRangeCount.clear();
        log.info("DataQualityMetrics reset");
    }

    // ==================== INNER CLASSES ====================

    /**
     * Per-processor metrics
     */
    @lombok.Data
    public static class ProcessorMetrics {
        private final String processorName;
        private final AtomicLong totalProcessed = new AtomicLong(0);
        private final AtomicLong invalidCount = new AtomicLong(0);
        private final AtomicLong mergeCount = new AtomicLong(0);

        public ProcessorMetrics(String processorName) {
            this.processorName = processorName;
        }

        public void recordCandle(boolean isValid) {
            totalProcessed.incrementAndGet();
            if (!isValid) {
                invalidCount.incrementAndGet();
            }
        }

        public void recordMerge(int count) {
            mergeCount.addAndGet(count);
        }

        public long getTotalProcessed() {
            return totalProcessed.get();
        }

        public long getInvalidCount() {
            return invalidCount.get();
        }

        public long getMergeCount() {
            return mergeCount.get();
        }
    }

    /**
     * Snapshot DTO for API response
     */
    @lombok.Data
    @lombok.Builder
    public static class DataQualitySnapshot {
        private long totalCandlesProcessed;
        private long totalInvalidCandles;
        private double invalidCandleRate;
        private long totalMergedCandles;
        private long totalDeduplicatedOptions;
        private Map<String, Long> violationCounts;
        private Map<String, Long> timeframeCandleCounts;
        private Map<String, Long> timeframeTinyRangeCounts;
        private Map<String, Map<String, Long>> processorStats;
    }
}
