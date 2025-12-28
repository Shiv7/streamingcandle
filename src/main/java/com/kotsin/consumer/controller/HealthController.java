package com.kotsin.consumer.controller;

import com.kotsin.consumer.monitoring.SystemMonitor;
import com.kotsin.consumer.infrastructure.kafka.UnifiedInstrumentCandleProcessor;
import com.kotsin.consumer.infrastructure.kafka.FamilyCandleProcessor;
import com.kotsin.consumer.processor.IPUProcessor;
import com.kotsin.consumer.processor.VCPProcessor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.KafkaStreams;
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
 * OBSERVABILITY: Expose health and metrics for monitoring
 * KUBERNETES READY: Provides liveness and readiness probes
 *
 * Updated: Now monitors NEW family-candle architecture processors
 */
@RestController
@RequestMapping("/api/v1/health")
@Slf4j
public class HealthController {

    private final SystemMonitor systemMonitor;

    @Autowired(required = false)
    private UnifiedInstrumentCandleProcessor instrumentProcessor;

    @Autowired(required = false)
    private FamilyCandleProcessor familyProcessor;

    @Autowired(required = false)
    private IPUProcessor ipuProcessor;

    @Autowired(required = false)
    private VCPProcessor vcpProcessor;

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
        
        boolean isReady = systemMonitor.isSystemHealthy() && 
                         areAllProcessorsHealthy();
        
        response.put("status", isReady ? "UP" : "DOWN");
        response.put("timestamp", System.currentTimeMillis());
        response.put("healthy", isReady);
        
        if (!isReady) {
            return ResponseEntity.status(503).body(response);
        }
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Check if all processors are healthy
     */
    private boolean areAllProcessorsHealthy() {
        boolean allHealthy = true;

        // Check IPU processor
        if (ipuProcessor != null) {
            try {
                Map<String, KafkaStreams.State> states = ipuProcessor.getStreamStates();
                allHealthy = allHealthy && states.values().stream()
                    .allMatch(state -> state == KafkaStreams.State.RUNNING);
            } catch (Exception e) {
                log.warn("Error checking IPU processor health", e);
                allHealthy = false;
            }
        }

        // Check VCP processor
        if (vcpProcessor != null) {
            try {
                Map<String, KafkaStreams.State> states = vcpProcessor.getStreamStates();
                allHealthy = allHealthy && states.values().stream()
                    .allMatch(state -> state == KafkaStreams.State.RUNNING);
            } catch (Exception e) {
                log.warn("Error checking VCP processor health", e);
                allHealthy = false;
            }
        }

        return allHealthy;
    }

    /**
     * Detailed health check
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();

        boolean isHealthy = systemMonitor.isSystemHealthy() && areAllProcessorsHealthy();

        response.put("status", isHealthy ? "HEALTHY" : "UNHEALTHY");
        response.put("timestamp", System.currentTimeMillis());
        response.put("systemMetrics", systemMonitor.getSystemMetrics());

        // Stream states from NEW architecture processors
        Map<String, Object> allStreamStates = new HashMap<>();
        if (ipuProcessor != null) {
            try {
                allStreamStates.put("ipu", ipuProcessor.getStreamStates());
            } catch (Exception e) {
                allStreamStates.put("ipu", "ERROR: " + e.getMessage());
            }
        }
        if (vcpProcessor != null) {
            try {
                allStreamStates.put("vcp", vcpProcessor.getStreamStates());
            } catch (Exception e) {
                allStreamStates.put("vcp", "ERROR: " + e.getMessage());
            }
        }
        response.put("streamStates", allStreamStates);

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
