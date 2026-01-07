package com.kotsin.consumer.controller;

import com.kotsin.consumer.monitoring.DataQualityMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DataQualityController - REST API for data quality monitoring
 * 
 * Endpoints:
 * - GET /api/data-quality/health - Quick health check
 * - GET /api/data-quality/metrics - Full metrics snapshot  
 * - GET /api/data-quality/violations - Violation breakdown
 * - POST /api/data-quality/reset - Reset metrics (admin)
 */
@RestController
@RequestMapping("/api/data-quality")
@Slf4j
public class DataQualityController {

    @Autowired
    private DataQualityMetrics metrics;

    /**
     * Quick health check - returns overall data quality status
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        
        response.put("timestamp", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        response.put("status", getHealthStatus());
        response.put("totalProcessed", metrics.getTotalCandlesProcessed());
        response.put("totalInvalid", metrics.getTotalInvalidCandles());
        response.put("invalidRate", String.format("%.4f%%", metrics.getInvalidCandleRate()));
        response.put("mergedCandles", metrics.getTotalMergedCandles());
        response.put("deduplicatedOptions", metrics.getTotalDeduplicatedOptions());
        
        return ResponseEntity.ok(response);
    }

    /**
     * Full metrics snapshot
     */
    @GetMapping("/metrics")
    public ResponseEntity<DataQualityMetrics.DataQualitySnapshot> metrics() {
        return ResponseEntity.ok(metrics.getSnapshot());
    }

    /**
     * Violation type breakdown
     */
    @GetMapping("/violations")
    public ResponseEntity<Map<String, Object>> violations() {
        Map<String, Object> response = new LinkedHashMap<>();
        
        response.put("timestamp", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        response.put("totalViolations", metrics.getTotalInvalidCandles());
        response.put("byType", metrics.getViolationCounts());
        response.put("byTimeframe", metrics.getTimeframeTinyRangeCounts());
        
        return ResponseEntity.ok(response);
    }

    /**
     * Timeframe statistics
     */
    @GetMapping("/timeframes")
    public ResponseEntity<Map<String, Object>> timeframes() {
        Map<String, Object> response = new LinkedHashMap<>();
        
        response.put("timestamp", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        response.put("candleCounts", metrics.getTimeframeCandleCounts());
        response.put("tinyRangeCounts", metrics.getTimeframeTinyRangeCounts());
        
        // Calculate tiny range percentage per timeframe
        Map<String, String> tinyRangePercent = new LinkedHashMap<>();
        Map<String, Long> candleCounts = metrics.getTimeframeCandleCounts();
        Map<String, Long> tinyRangeCounts = metrics.getTimeframeTinyRangeCounts();
        
        candleCounts.forEach((tf, count) -> {
            long tinyCount = tinyRangeCounts.getOrDefault(tf, 0L);
            double percent = count > 0 ? (double) tinyCount / count * 100 : 0;
            tinyRangePercent.put(tf, String.format("%.2f%%", percent));
        });
        
        response.put("tinyRangePercent", tinyRangePercent);
        
        return ResponseEntity.ok(response);
    }

    /**
     * Processor statistics
     */
    @GetMapping("/processors")
    public ResponseEntity<Map<String, Object>> processors() {
        Map<String, Object> response = new LinkedHashMap<>();
        
        response.put("timestamp", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        
        Map<String, Map<String, Object>> processorStats = new LinkedHashMap<>();
        metrics.getProcessorMetrics().forEach((name, pm) -> {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("totalProcessed", pm.getTotalProcessed());
            stats.put("invalidCount", pm.getInvalidCount());
            stats.put("mergeCount", pm.getMergeCount());
            stats.put("invalidRate", pm.getTotalProcessed() > 0 
                ? String.format("%.4f%%", (double) pm.getInvalidCount() / pm.getTotalProcessed() * 100)
                : "0%");
            processorStats.put(name, stats);
        });
        
        response.put("processors", processorStats);
        
        return ResponseEntity.ok(response);
    }

    /**
     * Reset all metrics (admin operation)
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> reset() {
        log.warn("🔄 Data quality metrics reset requested");
        metrics.reset();
        
        Map<String, String> response = new LinkedHashMap<>();
        response.put("status", "reset");
        response.put("timestamp", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        
        return ResponseEntity.ok(response);
    }

    /**
     * Determine health status based on invalid candle rate
     */
    private String getHealthStatus() {
        double invalidRate = metrics.getInvalidCandleRate();
        
        if (metrics.getTotalCandlesProcessed() < 100) {
            return "WARMING_UP";  // Not enough data
        } else if (invalidRate < 0.01) {
            return "HEALTHY";  // < 0.01% invalid
        } else if (invalidRate < 0.1) {
            return "DEGRADED";  // 0.01% - 0.1% invalid
        } else if (invalidRate < 1.0) {
            return "WARNING";  // 0.1% - 1% invalid
        } else {
            return "CRITICAL";  // > 1% invalid
        }
    }
}
