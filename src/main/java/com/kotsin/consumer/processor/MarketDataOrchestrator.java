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
     * Start all processing streams (3 independent consumers)
     */
    public void startAllStreams() {
        log.info("🚀 Starting 3 INDEPENDENT market data processing streams");

        try {
            // Consumer 1: Ticks → OHLCV Candles
            startTicksStream();

            // Consumer 2: Orderbook → Orderbook Signals
            startOrderbookStream();

            // Consumer 3: OI → OI Metrics
            startOIStream();

            log.info("✅ All 3 independent streams started successfully");
        } catch (Exception e) {
            log.error("❌ Failed to start streams", e);
            throw new RuntimeException("Failed to start market data streams", e);
        }
    }

    /**
     * CONSUMER 1: Ticks → OHLCV Candles
     * Input: forwardtesting-data
     * Output: candle-ohlcv-{1m,2m,3m,5m,15m,30m}
     */
    public void startTicksStream() {
        String instanceKey = "ticks-stream";

        if (streamsInstances.containsKey(instanceKey)) {
            log.warn("⚠️ Ticks stream already running. Skipping duplicate start.");
            return;
        }

        try {
            StreamsBuilder builder = topologyConfig.createTicksTopology();
            KafkaStreams streams = new KafkaStreams(builder.build(), kafkaConfig.getStreamProperties("ticks-consumer"));
            streamsInstances.put(instanceKey, streams);
            streams.start();
            log.info("✅ Started TICKS consumer (candle-ohlcv-*)");
        } catch (Exception e) {
            log.error("❌ Failed to start ticks stream", e);
            throw e;
        }
    }

    /**
     * CONSUMER 2: Orderbook → Orderbook Signals
     * Input: Orderbook
     * Output: orderbook-signals-{1m,2m,3m,5m,15m,30m}
     */
    public void startOrderbookStream() {
        String instanceKey = "orderbook-stream";

        if (streamsInstances.containsKey(instanceKey)) {
            log.warn("⚠️ Orderbook stream already running. Skipping duplicate start.");
            return;
        }

        try {
            StreamsBuilder builder = topologyConfig.createOrderbookTopology();
            KafkaStreams streams = new KafkaStreams(builder.build(), kafkaConfig.getStreamProperties("orderbook-consumer"));
            streamsInstances.put(instanceKey, streams);
            streams.start();
            log.info("✅ Started ORDERBOOK consumer (orderbook-signals-*)");
        } catch (Exception e) {
            log.error("❌ Failed to start orderbook stream", e);
            throw e;
        }
    }

    /**
     * CONSUMER 3: OI → OI Metrics
     * Input: OpenInterest
     * Output: oi-metrics-{1m,2m,3m,5m,15m,30m}
     */
    public void startOIStream() {
        String instanceKey = "oi-stream";

        if (streamsInstances.containsKey(instanceKey)) {
            log.warn("⚠️ OI stream already running. Skipping duplicate start.");
            return;
        }

        try {
            StreamsBuilder builder = topologyConfig.createOITopology();
            KafkaStreams streams = new KafkaStreams(builder.build(), kafkaConfig.getStreamProperties("oi-consumer"));
            streamsInstances.put(instanceKey, streams);
            streams.start();
            log.info("✅ Started OI consumer (oi-metrics-*)");
        } catch (Exception e) {
            log.error("❌ Failed to start OI stream", e);
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
