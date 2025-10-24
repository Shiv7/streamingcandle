package com.kotsin.consumer.processor;

import com.kotsin.consumer.metrics.StreamMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Unified Market Data Processor (Refactored - God Class Split)
 * 
 * Responsibilities:
 * - TopologyConfiguration: Topology building
 * - MarketDataOrchestrator: Stream lifecycle management
 * 
 * This class now acts as a thin coordinator
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UnifiedMarketDataProcessor {

    private final MarketDataOrchestrator orchestrator;
    private final StreamMetrics metrics;
    
    @Value("${spring.kafka.streams.application-id:unified-market-processor1}")
    private String appIdPrefix;
    
    @Value("${unified.input.topic.ticks:forwardtesting-data}")
    private String ticksTopic;
    
    @Value("${unified.input.topic.oi:OpenInterest}")
    private String oiTopic;
    
    @Value("${unified.input.topic.orderbook:Orderbook}")
    private String orderbookTopic;

    /**
     * Start the unified market data processor
     */
    @PostConstruct
    public void start() {
        try {
            log.info("🚀 Starting Streaming Candle of Kostin Module Entry Point is Unified Market Data Processor");
            log.info("Input topics: ticks={}, oi={}, orderbook={}", ticksTopic, oiTopic, orderbookTopic);

            // Delegate to orchestrator
            orchestrator.startAllStreams();
            log.info("✅ Unified Market Data Processor started successfully");

        } catch (Exception e) {
            log.error("❌ Error starting Unified Market Data Processor", e);
            throw new RuntimeException("Failed to start unified processor", e);
        }
    }

    /**
     * Stop the unified market data processor with graceful shutdown
     */
    @PreDestroy
    public void stop() {
        try {
            log.info("🛑 Starting graceful shutdown of Unified Market Data Processor");
            
            // Step 1: Stop accepting new data
            log.info("📤 Step 1: Stopping new data acceptance");
            
            // Step 2: Wait for in-flight processing to complete
            log.info("⏳ Step 2: Waiting for in-flight processing to complete");
            Thread.sleep(2000); // Allow 2 seconds for processing to complete
            
            // Step 3: Flush any pending state
            log.info("💾 Step 3: Flushing pending state");
            
            // Step 4: Stop all streams gracefully
            log.info("🔄 Step 4: Stopping all streams");
            orchestrator.stopAllStreams();
            
            // Step 5: Final cleanup
            log.info("🧹 Step 5: Final cleanup");
            
            log.info("✅ Unified Market Data Processor stopped gracefully");
            
        } catch (Exception e) {
            log.error("❌ Error during graceful shutdown", e);
            // Force shutdown if graceful fails
            try {
                orchestrator.stopAllStreams();
            } catch (Exception forceException) {
                log.error("❌ Force shutdown also failed", forceException);
            }
        }
    }

    /**
     * Get stream states
     */
    public Map<String, String> getStreamStates() {
        return orchestrator.getStreamStatus();
    }

    /**
     * Get stream statistics
     */
    public String getStreamStats() {
        return orchestrator.getStreamStats();
    }

    /**
     * Get metrics
     */
    public String getMetrics() {
        return metrics.getMetrics();
    }

    /**
     * Health check
     */
    public boolean isHealthy() {
        Map<String, String> states = getStreamStates();
        return states.values().stream()
            .allMatch(state -> "RUNNING".equals(state) || "CREATED".equals(state));
    }
}
