package com.kotsin.consumer.processor.service;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.FamilyEnrichedData;
import com.kotsin.consumer.model.InstrumentCandle;
import com.kotsin.consumer.processor.InstrumentState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private final InstrumentProcessor instrumentProcessor;
    private final DataEnrichmentService enrichmentService;
    private final CandleEmissionService candleEmissionService;
    private final FamilyAggregationService familyAggService;

    private final Map<String, KafkaStreams> streamsInstances = new ConcurrentHashMap<>();
    
    @Value("${stream.outputs.candles.enabled:true}")
    private boolean candlesOutputEnabled;
    
    @Value("${stream.outputs.familyStructured.enabled:false}")
    private boolean familyStructuredEnabled;

    // Candle topic names
    @Value("${stream.outputs.candles.1m:candle-complete-1m}")
    private String candle1mTopic;
    
    @Value("${stream.outputs.candles.2m:candle-complete-2m}")
    private String candle2mTopic;
    
    @Value("${stream.outputs.candles.5m:candle-complete-5m}")
    private String candle5mTopic;
    
    @Value("${stream.outputs.candles.15m:candle-complete-15m}")
    private String candle15mTopic;
    
    @Value("${stream.outputs.candles.30m:candle-complete-30m}")
    private String candle30mTopic;

    /**
     * Start all processing streams
     */
    public void startAllStreams() {
        log.info("🚀 Starting all market data processing streams");
        
        try {
            // Start per-instrument candle stream FIRST
            startInstrumentStream();
            
            // Wait for instrument stream to create candle topics
            log.info("⏳ Waiting for instrument stream to create candle topics...");
            Thread.sleep(5000); // 5 second delay
            
            // Start family-structured streams if enabled
            if (familyStructuredEnabled) {
                startFamilyStructuredStreamsWithRetry();
            }
            
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
     * Start family-structured aggregation streams with retry mechanism
     */
    public void startFamilyStructuredStreamsWithRetry() {
        log.info("🏗️ Starting family-structured streams with retry");
        
        int maxRetries = 3;
        int retryDelay = 2000; // 2 seconds
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                startFamilyStructuredStreams();
                log.info("✅ Family streams started successfully on attempt {}", attempt);
                return;
            } catch (Exception e) {
                log.warn("⚠️ Attempt {} failed to start family streams: {}", attempt, e.getMessage());
                if (attempt < maxRetries) {
                    log.info("⏳ Retrying in {} seconds...", retryDelay / 1000);
                    try {
                        Thread.sleep(retryDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry", ie);
                    }
                } else {
                    log.error("❌ All {} attempts failed to start family streams", maxRetries);
                    throw new RuntimeException("Failed to start family streams after " + maxRetries + " attempts", e);
                }
            }
        }
    }

    /**
     * Start family-structured aggregation streams
     */
    public void startFamilyStructuredStreams() {
        log.info("🏗️ Starting family-structured streams");
        
        // Verify required candle topics exist before starting family streams
        String[] requiredTopics = {
            candle1mTopic, candle2mTopic, candle5mTopic, 
            candle15mTopic, candle30mTopic
        };
        
        for (String topic : requiredTopics) {
            if (!topicExists(topic)) {
                throw new RuntimeException("Required candle topic does not exist: " + topic);
            }
        }
        
        // Start 1-minute family stream
        startFamilyStream("1m", candle1mTopic, "family-structured-1m", Duration.ofMinutes(1));
        
        // Start 2-minute family stream  
        startFamilyStream("2m", candle2mTopic, "family-structured-2m", Duration.ofMinutes(2));
        
        // Start 5-minute family stream
        startFamilyStream("5m", candle5mTopic, "family-structured-5m", Duration.ofMinutes(5));
        
        // Start 15-minute family stream
        startFamilyStream("15m", candle15mTopic, "family-structured-15m", Duration.ofMinutes(15));
        
        // Start 30-minute family stream
        startFamilyStream("30m", candle30mTopic, "family-structured-30m", Duration.ofMinutes(30));
    }

    /**
     * Start a specific family-structured stream
     */
    private void startFamilyStream(String timeframeLabel, String sourceTopic, String sinkTopic, Duration windowSize) {
        String instanceKey = "family-structured-" + timeframeLabel;
        
        if (streamsInstances.containsKey(instanceKey)) {
            log.warn("⚠️ {} already running. Skipping duplicate start.", instanceKey);
            return;
        }

        try {
            StreamsBuilder builder = topologyConfig.createFamilyTopology(timeframeLabel, sourceTopic, sinkTopic, windowSize);
            KafkaStreams streams = new KafkaStreams(builder.build(), kafkaConfig.getStreamProperties("family-" + timeframeLabel));
            streamsInstances.put(instanceKey, streams);
            streams.start();
            log.info("✅ Started {} stream → topic: {}", instanceKey, sinkTopic);
        } catch (Exception e) {
            log.error("❌ Failed to start family stream {}", timeframeLabel, e);
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

    /**
     * Check if a Kafka topic exists
     */
    private boolean topicExists(String topicName) {
        try {
            // Use Kafka admin client to check topic existence
            Properties adminProps = new Properties();
            adminProps.put("bootstrap.servers", kafkaConfig.getBootstrapServers());
            adminProps.put("client.id", "topic-checker");
            
            try (org.apache.kafka.clients.admin.AdminClient adminClient = 
                 org.apache.kafka.clients.admin.AdminClient.create(adminProps)) {
                
                org.apache.kafka.clients.admin.ListTopicsResult result = adminClient.listTopics();
                Set<String> topics = result.names().get(5, java.util.concurrent.TimeUnit.SECONDS);
                return topics.contains(topicName);
            }
        } catch (Exception e) {
            log.warn("⚠️ Failed to check if topic {} exists: {}", topicName, e.getMessage());
            return false; // Assume it doesn't exist if we can't check
        }
    }
}
