package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.springframework.stereotype.Component;

/**
 * Orchestrator for market data processing streams
 * 
 * SINGLE RESPONSIBILITY: Stream lifecycle management
 * EXTRACTED FROM: UnifiedMarketDataProcessor (God class refactoring)
 * 
 * This is the main orchestrator that coordinates all processing streams
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MarketDataOrchestrator {

    private final KafkaConfig kafkaConfig;
    private final TopologyConfiguration topologyConfig;
    private final Map<String, KafkaStreams> streamsInstances = new ConcurrentHashMap<>();
    
    /**
     * Start all processing streams
     */
    public void startAllStreams() {
        log.info("🚀 Starting all market data processing streams");
        
        try {
            // Start per-instrument candle stream FIRST
            startInstrumentStream();
            
            log.info("✅ All streams started successfully");
            
        } catch (Exception e) {
            log.error("❌ Failed to start streams", e);
            throw new RuntimeException("Failed to start market data streams", e);
        }
    }

    /**
     * Start per-instrument candle generation stream
     */
    public void startInstrumentStream() {
        String instanceKey = "instrument-stream";
        
        if (streamsInstances.containsKey(instanceKey)) {
            log.warn("⚠️ Instrument stream already running. Skipping duplicate start.");
            return;
        }

        try {
            StreamsBuilder builder = topologyConfig.createInstrumentTopology();
            KafkaStreams streams = new KafkaStreams(builder.build(), kafkaConfig.getStreamProperties("instrument"));
            streamsInstances.put(instanceKey, streams);
            streams.start();
            log.info("✅ Started per-instrument candle stream");
        } catch (Exception e) {
            log.error("❌ Failed to start instrument stream", e);
            throw e;
        }
    }

    /**
     * Stop all streams gracefully
     */
    public void stopAllStreams() {
        log.info("🛑 Stopping all market data processing streams");
        
        streamsInstances.forEach((key, streams) -> {
            try {
                streams.close();
                log.info("✅ Stopped stream: {}", key);
            } catch (Exception e) {
                log.error("❌ Error stopping stream: {}", key, e);
            }
        });
        
        streamsInstances.clear();
        log.info("✅ All streams stopped");
    }

    /**
     * Get stream status
     */
    public Map<String, String> getStreamStatus() {
        Map<String, String> status = new ConcurrentHashMap<>();
        
        streamsInstances.forEach((key, streams) -> {
            status.put(key, streams.state().toString());
        });
        
        return status;
    }

    /**
     * Get stream statistics
     */
    public String getStreamStats() {
        return String.format("Active streams: %d, Status: %s", 
            streamsInstances.size(), 
            getStreamStatus());
    }

}
