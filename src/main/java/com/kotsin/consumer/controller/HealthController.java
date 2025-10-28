package com.kotsin.consumer.controller;

import com.kotsin.consumer.monitoring.SystemMonitor;
import com.kotsin.consumer.processor.CandlestickProcessor;
import com.kotsin.consumer.processor.OrderbookProcessor;
import com.kotsin.consumer.processor.OIProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.KafkaStreams;
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
 * Updated: Now monitors 3 independent processors
 */
@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
@Slf4j
public class HealthController {

    private final SystemMonitor systemMonitor;
    private final CandlestickProcessor candlestickProcessor;
    private final OrderbookProcessor orderbookProcessor;
    private final OIProcessor oiProcessor;

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
        Map<String, KafkaStreams.State> candleStates = candlestickProcessor.getStreamStates();
        Map<String, KafkaStreams.State> obStates = orderbookProcessor.getStreamStates();
        Map<String, KafkaStreams.State> oiStates = oiProcessor.getStreamStates();
        
        boolean candlesHealthy = candleStates.values().stream()
            .allMatch(state -> state == KafkaStreams.State.RUNNING);
        boolean orderbookHealthy = obStates.values().stream()
            .allMatch(state -> state == KafkaStreams.State.RUNNING);
        boolean oiHealthy = oiStates.values().stream()
            .allMatch(state -> state == KafkaStreams.State.RUNNING);
            
        return candlesHealthy && orderbookHealthy && oiHealthy;
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
        
        // Stream states from all 3 processors
        Map<String, Object> allStreamStates = new HashMap<>();
        allStreamStates.put("candlesticks", candlestickProcessor.getStreamStates());
        allStreamStates.put("orderbook", orderbookProcessor.getStreamStates());
        allStreamStates.put("oi", oiProcessor.getStreamStates());
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
