package com.kotsin.consumer.controller;

import com.kotsin.consumer.monitoring.SystemMonitor;
import com.kotsin.consumer.processor.UnifiedMarketDataProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Health check and metrics endpoint
 * 
 * OBSERVABILITY: Expose health and metrics for monitoring
 * KUBERNETES READY: Provides liveness and readiness probes
 */
@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
@Slf4j
public class HealthController {

    private final SystemMonitor systemMonitor;
    private final UnifiedMarketDataProcessor processor;

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
        
        boolean isReady = systemMonitor.isSystemHealthy() && processor.isHealthy();
        
        response.put("status", isReady ? "UP" : "DOWN");
        response.put("timestamp", System.currentTimeMillis());
        response.put("healthy", isReady);
        
        if (!isReady) {
            return ResponseEntity.status(503).body(response);
        }
        
        return ResponseEntity.ok(response);
    }

    /**
     * Detailed health check
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        
        boolean isHealthy = systemMonitor.isSystemHealthy();
        
        response.put("status", isHealthy ? "HEALTHY" : "UNHEALTHY");
        response.put("timestamp", System.currentTimeMillis());
        response.put("systemMetrics", systemMonitor.getSystemMetrics());
        response.put("streamStates", processor.getStreamStates());
        response.put("streamStats", processor.getStreamStats());
        
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
