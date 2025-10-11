package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.MicrostructureFeature;
import com.kotsin.consumer.model.OrderBookSnapshot;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Simple Microstructure Processor
 * Calculates basic microstructure features without complex state
 */
@Slf4j
@Component
public class SimpleMicrostructureProcessor {
    
    private final KafkaConfig kafkaConfig;
    
    @Value("${microstructure.enabled:false}")
    private boolean enabled;
    
    @Value("${microstructure.input.topic:Orderbook}")
    private String inputTopic;
    
    @Value("${microstructure.output.topic:microstructure-features}")
    private String outputTopic;
    
    private KafkaStreams streams;
    
    /**
     * Constructor with dependency injection.
     *
     * @param kafkaConfig Kafka configuration for streams setup
     */
    public SimpleMicrostructureProcessor(KafkaConfig kafkaConfig) {
        this.kafkaConfig = kafkaConfig;
    }
    
    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("Simple Microstructure processor is DISABLED");
            return;
        }
        
        log.info("🚀 Starting Simple Microstructure Processor");
        log.info("  Input topic: {}", inputTopic);
        log.info("  Output topic: {}", outputTopic);
        
        try {
            String applicationId = "simple-microstructure-processor";
            Properties props = kafkaConfig.getStreamProperties(applicationId);
            
            StreamsBuilder builder = new StreamsBuilder();
            
            // Read Orderbook topic
            KStream<String, OrderBookSnapshot> orderBooks = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), OrderBookSnapshot.serde())
            );
            
            // Process and calculate basic features
            KStream<String, MicrostructureFeature> features = orderBooks
                .filter((key, snapshot) -> validateSnapshot(snapshot))
                .selectKey((key, snapshot) -> String.valueOf(snapshot.getToken()))
                .mapValues(this::calculateBasicFeatures)
                .filter((key, feature) -> feature != null && feature.isValid())
                .peek((key, feature) -> 
                    log.info("EMIT microstructure feature: token={}, company={}, " +
                        "ofi5={}, depthImb5={}, mid={}",
                        feature.getToken(), feature.getCompanyName(),
                        String.format("%.2f", feature.getOfi5()),
                        String.format("%.3f", feature.getDepthImbalance5()),
                        String.format("%.2f", feature.getMidPrice())));
            
            // Publish to output topic
            features.to(outputTopic, Produced.with(Serdes.String(), MicrostructureFeature.serde()));
            
            streams = new KafkaStreams(builder.build(), props);
            
            streams.setStateListener((newState, oldState) -> {
                log.info("Simple Microstructure processor state: {} -> {}", oldState, newState);
                
                if (newState == KafkaStreams.State.ERROR) {
                    log.error("❌ Simple Microstructure processor entered ERROR state!");
                } else if (newState == KafkaStreams.State.RUNNING) {
                    log.info("✅ Simple Microstructure processor is now RUNNING");
                }
            });
            
            streams.start();
            log.info("✅ Simple Microstructure Processor started successfully");
            
        } catch (Exception e) {
            log.error("❌ Failed to start Simple Microstructure Processor", e);
        }
    }
    
    /**
     * Validate order book snapshot
     */
    private boolean validateSnapshot(OrderBookSnapshot snapshot) {
        if (snapshot == null || snapshot.getToken() <= 0 || snapshot.getTimestamp() <= 0) {
            return false;
        }
        
        try {
            snapshot.parseDetails();
            return snapshot.isValid();
        } catch (Exception e) {
            log.debug("Invalid order book: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Calculate basic microstructure features without state
     */
    private MicrostructureFeature calculateBasicFeatures(OrderBookSnapshot snapshot) {
        try {
            MicrostructureFeature feature = new MicrostructureFeature();
            
            // Identity
            feature.setToken(snapshot.getToken());
            feature.setScripCode(String.valueOf(snapshot.getToken()));
            feature.setCompanyName(snapshot.getCompanyName());
            feature.setExchange(snapshot.getExch());
            feature.setExchangeType(snapshot.getExchType());
            feature.setTimestamp(snapshot.getTimestamp());
            
            // Basic order book state
            feature.setBestBid(snapshot.getBestBid());
            feature.setBestAsk(snapshot.getBestAsk());
            feature.setMidPrice(snapshot.getMidPrice());
            feature.setSpread(snapshot.getSpread());
            feature.setMicroprice(snapshot.getMicroprice());
            feature.calculateSpreadBps();
            
            // Volume metrics (top of book)
            if (!snapshot.getAllBids().isEmpty()) {
                feature.setBidVolume1(snapshot.getAllBids().get(0).getQuantity());
                feature.setBidOrders1(snapshot.getAllBids().get(0).getNumberOfOrders());
            }
            if (!snapshot.getAllAsks().isEmpty()) {
                feature.setAskVolume1(snapshot.getAllAsks().get(0).getQuantity());
                feature.setAskOrders1(snapshot.getAllAsks().get(0).getNumberOfOrders());
            }
            
            // Multi-level volume
            feature.setTotalBidVolume5(getTotalVolume(snapshot.getBids(5)));
            feature.setTotalAskVolume5(getTotalVolume(snapshot.getAsks(5)));
            feature.setTotalBidVolume20(getTotalVolume(snapshot.getBids(20)));
            feature.setTotalAskVolume20(getTotalVolume(snapshot.getAsks(20)));
            
            // Depth Imbalance (can calculate without history)
            feature.setDepthImbalance1(calculateDepthImbalance(snapshot, 1));
            feature.setDepthImbalance5(calculateDepthImbalance(snapshot, 5));
            feature.setDepthImbalance20(calculateDepthImbalance(snapshot, 20));
            
            // Set OFI to 0 (would need history for proper calculation)
            feature.setOfi1(0.0);
            feature.setOfi5(0.0);
            feature.setOfi20(0.0);
            
            // Set VPIN and Kyle's Lambda to 0 (would need history)
            feature.setVpin(0.0);
            feature.setKyleLambda(0.0);
            
            // Effective spread (simplified)
            feature.setEffectiveSpread(snapshot.getSpread());
            
            return feature;
            
        } catch (Exception e) {
            log.error("Error calculating features for token {}: {}", 
                snapshot.getToken(), e.getMessage());
            return null;
        }
    }
    
    /**
     * Calculate depth imbalance
     */
    private double calculateDepthImbalance(OrderBookSnapshot snapshot, int levels) {
        try {
            int bidVolume = getTotalVolume(snapshot.getBids(levels));
            int askVolume = getTotalVolume(snapshot.getAsks(levels));
            
            if (bidVolume + askVolume == 0) {
                return 0.0;
            }
            
            return (double) (bidVolume - askVolume) / (bidVolume + askVolume);
            
        } catch (Exception e) {
            log.debug("Error calculating depth imbalance: {}", e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Get total volume from list of levels
     */
    private int getTotalVolume(java.util.List<OrderBookSnapshot.OrderBookLevel> levels) {
        if (levels == null || levels.isEmpty()) {
            return 0;
        }
        
        return levels.stream()
            .mapToInt(OrderBookSnapshot.OrderBookLevel::getQuantity)
            .sum();
    }
    
    @PreDestroy
    public void cleanup() {
        if (streams != null) {
            log.info("🛑 Shutting down Simple Microstructure Processor...");
            streams.close();
        }
    }
}
