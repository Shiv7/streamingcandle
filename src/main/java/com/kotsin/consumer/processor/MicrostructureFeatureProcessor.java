package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.MicrostructureFeature;
import com.kotsin.consumer.model.MicrostructureFeatureState;
import com.kotsin.consumer.model.OrderBookSnapshot;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Microstructure Feature Processor
 * 
 * Processes order book snapshots and calculates microstructure features:
 * - OFI (Order Flow Imbalance)
 * - VPIN (Volume-Synchronized Probability of Informed Trading)
 * - Kyle's Lambda (price impact)
 * - Depth Imbalance
 * 
 * Based on "Advances in Financial Machine Learning" Chapter 19
 * 
 * Pattern: Follows InformationBarProcessor architecture
 */
@Slf4j
@Component
public class MicrostructureFeatureProcessor {
    
    private final KafkaConfig kafkaConfig;
    
    // Configuration
    @Value("${microstructure.enabled:false}")
    private boolean enabled;
    
    @Value("${microstructure.input.topic:Orderbook}")
    private String inputTopic;
    
    @Value("${microstructure.output.topic:microstructure-features}")
    private String outputTopic;
    
    @Value("${microstructure.window.size:50}")
    private int windowSize;
    
    @Value("${microstructure.min.observations:20}")
    private int minObservations;
    
    @Value("${microstructure.emit.interval.ms:1000}")
    private long emitIntervalMs;
    
    private KafkaStreams streams;
    
    /**
     * Constructor with dependency injection.
     *
     * @param kafkaConfig Kafka configuration for streams setup
     */
    public MicrostructureFeatureProcessor(KafkaConfig kafkaConfig) {
        this.kafkaConfig = kafkaConfig;
    }
    
    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("Microstructure feature processing is DISABLED");
            return;
        }
        
        // log.info("🚧 MicrostructureFeatureProcessor temporarily DISABLED for debugging");
        // return;
        
        // DISABLED - using SimpleMicrostructureProcessor instead
        log.info("🚧 Complex MicrostructureFeatureProcessor DISABLED - using SimpleMicrostructureProcessor");
        return;
        
        /* DISABLED - using SimpleMicrostructureProcessor instead
        log.info("🚀 Starting Microstructure Feature Processor");
        log.info("  Input topic: {}", inputTopic);
        log.info("  Output topic: {}", outputTopic);
        log.info("  Window size: {}", windowSize);
        log.info("  Min observations: {}", minObservations);
        log.info("  Emit interval: {}ms ({} features/second max)", emitIntervalMs, 1000.0 / emitIntervalMs);
        
        try {
            // Create Kafka Streams topology
            String applicationId = "microstructure-features-processor";
            Properties props = kafkaConfig.getStreamProperties(applicationId);
            
            StreamsBuilder builder = new StreamsBuilder();
            buildTopology(builder);
            
            streams = new KafkaStreams(builder.build(), props);
            
            // Add state listener
            streams.setStateListener((newState, oldState) -> {
                log.info("Kafka Streams state transition for microstructure processor: {} -> {}", 
                    oldState, newState);
                
                if (newState == KafkaStreams.State.ERROR) {
                    log.error("❌ Microstructure processor entered ERROR state!");
                } else if (newState == KafkaStreams.State.RUNNING) {
                    log.info("✅ Microstructure processor is now RUNNING");
                }
            });
            
            // Start streams
            streams.start();
            log.info("✅ Microstructure Feature Processor started successfully");
            
        } catch (Exception e) {
            log.error("❌ Failed to initialize Microstructure Feature Processor", e);
            throw new RuntimeException("Failed to start Microstructure Feature Processor", e);
        }
        */ // END DISABLED - using SimpleMicrostructureProcessor instead
    }
    
    /**
     * Build Kafka Streams topology
     */
    private void buildTopology(StreamsBuilder builder) {
        // State store name
        String stateStoreName = "microstructure-state-store";
        
        // Read order book stream
        KStream<String, OrderBookSnapshot> orderBooks = builder.stream(
            inputTopic,
            Consumed.with(Serdes.String(), OrderBookSnapshot.serde())
        );
        
        // Key by token (group order books by instrument)
        KStream<String, OrderBookSnapshot> keyedByToken = orderBooks
            .filter((key, snapshot) -> validateSnapshot(snapshot))
            .selectKey((key, snapshot) -> String.valueOf(snapshot.getToken()))
            .peek((key, snapshot) -> 
                log.debug("Processing order book: token={}, mid={:.2f}, bid={:.2f}, ask={:.2f}",
                    key, snapshot.getMidPrice(), snapshot.getBestBid(), snapshot.getBestAsk()));
        
        // Aggregate and calculate microstructure features
        KStream<String, MicrostructureFeature> features = keyedByToken
            .groupByKey(Grouped.with(Serdes.String(), OrderBookSnapshot.serde()))
            .aggregate(
                // Initializer: create new state for each token
                () -> new MicrostructureFeatureState(windowSize, minObservations, emitIntervalMs),
                
                // Aggregator: update state with new order book snapshot
                (key, snapshot, state) -> {
                    try {
                        state.update(snapshot);
                        return state;
                    } catch (Exception e) {
                        log.error("Error updating microstructure state for token {}: {}", 
                            key, e.getMessage(), e);
                        return state;
                    }
                },
                
                // Materialized: persist state to state store
                Materialized.<String, MicrostructureFeatureState, KeyValueStore<Bytes, byte[]>>as(stateStoreName)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(MicrostructureFeatureState.serde())
            )
            .toStream()
            .filter((key, state) -> state.hasFeature())  // Only emit when features are ready
            .mapValues(MicrostructureFeatureState::emitAndReset)  // Extract feature and reset
            .peek((key, feature) -> 
                log.info("EMIT microstructure feature: token={}, company={}, ofi5={:.2f}, " +
                    "depthImb5={:.3f}, vpin={:.4f}, kyleLambda={:.6f}",
                    feature.getToken(), feature.getCompanyName(),
                    feature.getOfi5(), feature.getDepthImbalance5(),
                    feature.getVpin(), feature.getKyleLambda()));
        
        // Publish to output topic
        features.to(outputTopic, Produced.with(Serdes.String(), MicrostructureFeature.serde()));
        
        log.info("✅ Microstructure feature topology built: {} -> {}", inputTopic, outputTopic);
    }
    
    /**
     * Validate order book snapshot
     */
    private boolean validateSnapshot(OrderBookSnapshot snapshot) {
        if (snapshot == null) {
            log.warn("Received null order book snapshot");
            return false;
        }
        
        if (snapshot.getToken() <= 0) {
            log.warn("Invalid token: {}", snapshot.getToken());
            return false;
        }
        
        if (snapshot.getTimestamp() <= 0) {
            log.warn("Invalid timestamp for token {}", snapshot.getToken());
            return false;
        }
        
        if (snapshot.getDetails() == null || snapshot.getDetails().isEmpty()) {
            log.debug("No order book details for token {}", snapshot.getToken());
            return false;
        }
        
        // Parse and validate
        try {
            snapshot.parseDetails();
            if (!snapshot.isValid()) {
                log.debug("Invalid order book for token {}: no bids/asks or zero mid price", 
                    snapshot.getToken());
                return false;
            }
        } catch (Exception e) {
            log.error("Error parsing order book for token {}: {}", 
                snapshot.getToken(), e.getMessage());
            return false;
        }
        
        return true;
    }
    
    @PreDestroy
    public void cleanup() {
        if (streams != null) {
            log.info("🛑 Shutting down Microstructure Feature Processor...");
            try {
                streams.close();
                log.info("✅ Microstructure Feature Processor stopped");
            } catch (Exception e) {
                log.error("❌ Error stopping Microstructure Feature Processor: {}", 
                    e.getMessage(), e);
            }
        }
    }
}

