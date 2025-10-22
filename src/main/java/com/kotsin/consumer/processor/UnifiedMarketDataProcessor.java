package com.kotsin.consumer.processor;

import com.kotsin.consumer.processor.service.MarketDataOrchestrator;
import com.kotsin.consumer.processor.service.StreamMetrics;
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
 * REFACTORED: Split into focused services:
 * - TopologyConfiguration: Topology building
 * - InstrumentProcessor: Instrument-level processing  
 * - DataEnrichmentService: Data enrichment
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

    @Value("${stream.outputs.candles.enabled:true}")
    private boolean candlesOutputEnabled;

    @Value("${stream.outputs.familyStructured.enabled:false}")
    private boolean familyStructuredEnabled;

    @Value("${stream.outputs.candles.1m:candle-complete-1m}")
    private String candle1mTopic;

    @Value("${stream.outputs.candles.2m:candle-complete-2m}")
    private String candle2mTopic;

    @Value("${stream.outputs.candles.3m:candle-complete-3m}")
    private String candle3mTopic;

    @Value("${stream.outputs.candles.5m:candle-complete-5m}")
    private String candle5mTopic;

    @Value("${stream.outputs.candles.15m:candle-complete-15m}")
    private String candle15mTopic;

    @Value("${stream.outputs.candles.30m:candle-complete-30m}")
    private String candle30mTopic;

    @Value("${stream.outputs.familyStructured.1m:family-structured-1m}")
    private String familyStructured1mTopic;

    @Value("${stream.outputs.familyStructured.2m:family-structured-2m}")
    private String familyStructured2mTopic;

    @Value("${stream.outputs.familyStructured.5m:family-structured-5m}")
    private String familyStructured5mTopic;

    @Value("${stream.outputs.familyStructured.15m:family-structured-15m}")
    private String familyStructured15mTopic;

    @Value("${stream.outputs.familyStructured.30m:family-structured-30m}")
    private String familyStructured30mTopic;

    @Value("${stream.outputs.familyStructured.all:family-structured-all}")
    private String familyStructuredAllTopic;
    
    /**
     * Start the unified market data processor
     */
    @PostConstruct
    public void start() {
        try {
            log.info("🚀 Starting Unified Market Data Processor (Refactored)");
            log.info("Flags: candlesOutputEnabled={}, familyStructuredEnabled={}", candlesOutputEnabled, familyStructuredEnabled);
            log.info("Input topics: ticks={}, oi={}, orderbook={}", ticksTopic, oiTopic, orderbookTopic);
            log.info("Candle topics: 1m={}, 2m={}, 3m={}, 5m={}, 15m={}, 30m={}", candle1mTopic, candle2mTopic, candle3mTopic, candle5mTopic, candle15mTopic, candle30mTopic);
            log.info("Family topics: 1m={}, 2m={}, 5m={}, 15m={}, 30m={}, all={}", familyStructured1mTopic, familyStructured2mTopic, familyStructured5mTopic, familyStructured15mTopic, familyStructured30mTopic, familyStructuredAllTopic);

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