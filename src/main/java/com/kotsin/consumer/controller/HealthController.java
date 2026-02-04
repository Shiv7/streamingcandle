package com.kotsin.consumer.controller;

import com.kotsin.consumer.monitoring.SystemMonitor;
import com.kotsin.consumer.aggregator.TickAggregator;
import com.kotsin.consumer.aggregator.OrderbookAggregator;
import com.kotsin.consumer.aggregator.OIAggregator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Health check and metrics endpoint
 *
 * Provides health and metrics for monitoring.
 * Monitors the new v2 architecture aggregators.
 */
@RestController
@RequestMapping("/api/v1/health")
@Slf4j
public class HealthController {

    private final SystemMonitor systemMonitor;

    @Autowired(required = false)
    private TickAggregator tickAggregator;

    @Autowired(required = false)
    private OrderbookAggregator orderbookAggregator;

    @Autowired(required = false)
    private OIAggregator oiAggregator;

    public HealthController(SystemMonitor systemMonitor) {
        this.systemMonitor = systemMonitor;
    }

    /**
     * Liveness probe - Is the application running?
     */
    @GetMapping("/live")
    public ResponseEntity<Map<String, Object>> liveness() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }

    /**
     * Readiness probe - Is the application ready to accept traffic?
     */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> readiness() {
        Map<String, Object> response = new HashMap<>();

        boolean isReady = systemMonitor.isSystemHealthy() && areAggregatorsRunning();

        response.put("status", isReady ? "UP" : "DOWN");
        response.put("timestamp", System.currentTimeMillis());
        response.put("healthy", isReady);

        if (!isReady) {
            return ResponseEntity.status(503).body(response);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Check if all aggregators are running
     */
    private boolean areAggregatorsRunning() {
        boolean allRunning = true;

        if (tickAggregator != null) {
            allRunning = allRunning && tickAggregator.isRunning();
        }
        if (orderbookAggregator != null) {
            allRunning = allRunning && orderbookAggregator.isRunning();
        }
        if (oiAggregator != null) {
            allRunning = allRunning && oiAggregator.isRunning();
        }

        return allRunning;
    }

    /**
     * Detailed health check
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();

        boolean isHealthy = systemMonitor.isSystemHealthy() && areAggregatorsRunning();

        response.put("status", isHealthy ? "HEALTHY" : "UNHEALTHY");
        response.put("timestamp", System.currentTimeMillis());
        response.put("systemMetrics", systemMonitor.getSystemMetrics());

        // Aggregator status
        Map<String, Object> aggregatorStatus = new HashMap<>();
        if (tickAggregator != null) {
            aggregatorStatus.put("tick", Map.of(
                "running", tickAggregator.isRunning(),
                "stats", tickAggregator.getStats()
            ));
        }
        if (orderbookAggregator != null) {
            aggregatorStatus.put("orderbook", Map.of(
                "running", orderbookAggregator.isRunning(),
                "stats", orderbookAggregator.getStats()
            ));
        }
        if (oiAggregator != null) {
            aggregatorStatus.put("oi", Map.of(
                "running", oiAggregator.isRunning(),
                "stats", oiAggregator.getStats()
            ));
        }
        response.put("aggregators", aggregatorStatus);

        if (!isHealthy) {
            return ResponseEntity.status(503).body(response);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Metrics endpoint for Prometheus
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> metrics() {
        Map<String, Object> metrics = systemMonitor.getSystemMetrics();
        return ResponseEntity.ok(metrics);
    }
}
