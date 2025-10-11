package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.OrderBookSnapshot;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Simple OrderBook Processor for debugging
 * Just reads Orderbook topic and logs messages
 */
@Slf4j
@Component
public class SimpleOrderBookProcessor {
    
    private final KafkaConfig kafkaConfig;
    
    @Value("${microstructure.enabled:false}")
    private boolean enabled;
    
    private KafkaStreams streams;
    
    /**
     * Constructor with dependency injection.
     *
     * @param kafkaConfig Kafka configuration for streams setup
     */
    public SimpleOrderBookProcessor(KafkaConfig kafkaConfig) {
        this.kafkaConfig = kafkaConfig;
    }
    
    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("Simple OrderBook processor is DISABLED");
            return;
        }
        
        log.info("🚧 Simple OrderBook processor DISABLED - using full MicrostructureFeatureProcessor");
        return;
        
        /* DISABLED
        log.info("🚀 Starting Simple OrderBook Processor for debugging");
        
        try {
            String applicationId = "simple-orderbook-processor";
            Properties props = kafkaConfig.getStreamProperties(applicationId);
            
            StreamsBuilder builder = new StreamsBuilder();
            
            // Read Orderbook topic
            KStream<String, OrderBookSnapshot> orderBooks = builder.stream(
                "Orderbook",
                Consumed.with(Serdes.String(), OrderBookSnapshot.serde())
            );
            
            // Just log the messages
            orderBooks
                .peek((key, snapshot) -> {
                    if (snapshot != null) {
                        log.info("📊 OrderBook: token={}, company={}, timestamp={}", 
                            snapshot.getToken(), snapshot.getCompanyName(), snapshot.getTimestamp());
                        
                        // Try to parse details
                        try {
                            snapshot.parseDetails();
                            log.info("   Parsed: bid={}, ask={}, mid={}, spread={}",
                                String.format("%.2f", snapshot.getBestBid()), 
                                String.format("%.2f", snapshot.getBestAsk()), 
                                String.format("%.2f", snapshot.getMidPrice()), 
                                String.format("%.2f", snapshot.getSpread()));
                        } catch (Exception e) {
                            log.error("   Parse error: {}", e.getMessage());
                        }
                    }
                });
            
            streams = new KafkaStreams(builder.build(), props);
            
            streams.setStateListener((newState, oldState) -> {
                log.info("Simple OrderBook processor state: {} -> {}", oldState, newState);
            });
            
            streams.start();
            log.info("✅ Simple OrderBook Processor started");
            
        } catch (Exception e) {
            log.error("❌ Failed to start Simple OrderBook Processor", e);
        }
        */ // END DISABLED
    }
    
    @PreDestroy
    public void cleanup() {
        if (streams != null) {
            log.info("🛑 Shutting down Simple OrderBook Processor...");
            streams.close();
        }
    }
}
