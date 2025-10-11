package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.*;
import com.kotsin.consumer.timeExtractor.TickTimestampExtractor;
import com.kotsin.consumer.transformers.CumToDeltaTransformer;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Multi-Threshold Information Bars Processor
 * 
 * Creates multiple granularities of information bars (like 1min, 2min, 5min for time bars)
 * VIB-1x, VIB-2x, VIB-5x (different volume imbalance thresholds)
 * 
 * Based on "Advances in Financial Machine Learning" Chapter 2.3.2
 * Pattern: Follows CandlestickProcessor multi-timeframe approach
 */
@Slf4j
@Component
public class InformationBarProcessor {
    
    private static final String DELTA_STORE_PREFIX = "delta-volume-store-info-bars";
    
    private final KafkaConfig kafkaConfig;
    
    // Configuration
    @Value("${information.bars.enabled:false}")
    private boolean enabled;
    
    @Value("${information.bars.thresholds:1.0,2.0,5.0}")
    private String thresholdsConfig;
    
    @Value("${information.bars.vib.enabled:true}")
    private boolean vibEnabled;
    
    @Value("${information.bars.dib.enabled:true}")
    private boolean dibEnabled;
    
    @Value("${information.bars.trb.enabled:true}")
    private boolean trbEnabled;
    
    @Value("${information.bars.vrb.enabled:true}")
    private boolean vrbEnabled;
    
    @Value("${information.bars.min.ticks:10}")
    private int minTicks;
    
    @Value("${information.bars.warmup.samples:20}")
    private int warmupSamples;
    
    @Value("${information.bars.ewma.span:100.0}")
    private double ewmaSpan;
    
    @Value("${information.bars.min.expected.volume:100.0}")
    private double minExpectedVolume;
    
    @Value("${information.bars.expected.bar.size:50.0}")
    private double expectedBarSize;
    
    @Value("${information.bars.input.topic:forwardtesting-data}")
    private String inputTopic;
    
    // Store KafkaStreams instances (like CandlestickProcessor)
    private final Map<String, KafkaStreams> streamsInstances = new HashMap<>();
    
    /**
     * Constructor with dependency injection.
     *
     * @param kafkaConfig Kafka configuration for streams setup
     */
    public InformationBarProcessor(KafkaConfig kafkaConfig) {
        this.kafkaConfig = kafkaConfig;
    }
    
    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("Information bars processing is DISABLED");
            return;
        }
        
        // Parse threshold multipliers
        List<Double> thresholds = parseThresholds(thresholdsConfig);
        
        log.info("🚀 Starting Information Bars Processor");
        log.info("  Thresholds: {}", thresholds);
        log.info("  VIB: {}, DIB: {}, TRB: {}, VRB: {}", vibEnabled, dibEnabled, trbEnabled, vrbEnabled);
        
        try {
            // Start streams for each bar type × threshold combination
            for (Double threshold : thresholds) {
                if (vibEnabled) {
                    startStream("VIB", threshold);
                    Thread.sleep(500);  // Stagger starts
                }
                if (dibEnabled) {
                    startStream("DIB", threshold);
                    Thread.sleep(500);
                }
                if (trbEnabled) {
                    startStream("TRB", threshold);
                    Thread.sleep(500);
                }
                if (vrbEnabled) {
                    startStream("VRB", threshold);
                    Thread.sleep(500);
                }
            }
            
            log.info("✅ All Information Bars streams started successfully");
            logStreamStates();
            
        } catch (Exception e) {
            log.error("❌ Failed to initialize Information Bars Processor", e);
            throw new RuntimeException("Failed to start Information Bars Processor", e);
        }
    }
    
    /**
     * Parse threshold multipliers from config
     */
    private List<Double> parseThresholds(String config) {
        List<Double> thresholds = new ArrayList<>();
        for (String s : config.split(",")) {
            try {
                thresholds.add(Double.parseDouble(s.trim()));
            } catch (NumberFormatException e) {
                log.warn("Invalid threshold value: {}", s);
            }
        }
        if (thresholds.isEmpty()) {
            thresholds.add(1.0);  // Default
        }
        return thresholds;
    }
    
    /**
     * Start individual stream for barType × threshold
     * Pattern: Follows CandlestickProcessor.process()
     */
    private void startStream(String barType, double thresholdMultiplier) {
        // Format: information-bars-VIB-1x
        String instanceKey = String.format("information-bars-%s-%.0fx", barType, thresholdMultiplier);
        
        // Check for duplicates
        if (streamsInstances.containsKey(instanceKey)) {
            log.warn("Stream {} already running. Skipping duplicate start.", instanceKey);
            return;
        }
        
        // Output topic: volume-imbalance-bars-1x, volume-imbalance-bars-2x, etc.
        String outputTopic = getOutputTopic(barType, thresholdMultiplier);
        
        // Get Kafka properties (uses same config as CandlestickProcessor)
        Properties props = kafkaConfig.getStreamProperties(instanceKey);
        
        // Create StreamsBuilder for this stream
        StreamsBuilder builder = new StreamsBuilder();
        
        // Build topology
        buildTopology(builder, barType, thresholdMultiplier, outputTopic);
        
        // Create KafkaStreams instance
        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        
        // Store before starting (prevents race conditions)
        streamsInstances.put(instanceKey, streams);
        
        // Add state listener
        streams.setStateListener((newState, oldState) -> {
            log.info("Kafka Streams state transition for {}: {} -> {}", 
                instanceKey, oldState, newState);
            
            if (newState == KafkaStreams.State.ERROR) {
                log.error("❌ Stream {} entered ERROR state!", instanceKey);
            } else if (newState == KafkaStreams.State.RUNNING) {
                log.info("✅ Stream {} is now RUNNING", instanceKey);
            }
        });
        
        // Start
        streams.start();
        log.info("🚀 Started {} stream with {}× threshold: {}", barType, thresholdMultiplier, instanceKey);
    }
    
    /**
     * Get output topic name for bar type and threshold
     */
    private String getOutputTopic(String barType, double threshold) {
        String baseTopic;
        switch (barType) {
            case "VIB": baseTopic = "volume-imbalance-bars"; break;
            case "DIB": baseTopic = "dollar-imbalance-bars"; break;
            case "TRB": baseTopic = "tick-runs-bars"; break;
            case "VRB": baseTopic = "volume-runs-bars"; break;
            default: baseTopic = "information-bars";
        }
        return String.format("%s-%.0fx", baseTopic, threshold);
    }
    
    /**
     * Build topology for specific bar type × threshold
     */
    private void buildTopology(StreamsBuilder builder, String barType, 
                               double thresholdMultiplier, String outputTopic) {
        
        // Unique store name per bar type × threshold
        String deltaStore = String.format("%s-%s-%.0fx", DELTA_STORE_PREFIX, barType, thresholdMultiplier);
        String stateStore = String.format("%s-state-%.0fx", barType, thresholdMultiplier);
        
        // Add delta volume state store
        builder.addStateStore(Stores.keyValueStoreBuilder(
            Stores.persistentKeyValueStore(deltaStore),
            Serdes.String(),
            Serdes.Integer()
        ));
        
        // Read input stream
        KStream<String, TickData> raw = builder.stream(
            inputTopic,
            Consumed.with(Serdes.String(), TickData.serde())
                .withTimestampExtractor(new TickTimestampExtractor())
        );
        
        // Key by token and validate
        KStream<String, TickData> keyed = raw
            .filter((k, v) -> validateTick(v, barType, thresholdMultiplier))
            .selectKey((k, v) -> String.valueOf(v.getToken()));
        
        // Convert cumulative → delta
        KStream<String, TickData> ticks = keyed.transform(
            () -> new CumToDeltaTransformer(deltaStore),
            deltaStore
        );
        
        // Aggregate based on bar type
        KStream<String, InformationBar> bars;
        
        if (barType.equals("VIB") || barType.equals("DIB")) {
            // Imbalance bars
            bars = ticks
                .groupByKey(Grouped.with(Serdes.String(), TickData.serde()))
                .aggregate(
                    () -> new ImbalanceBarState(ewmaSpan, minExpectedVolume, warmupSamples, thresholdMultiplier),
                    (key, tick, state) -> {
                        try {
                            state.addTick(tick, barType);
                            return state;
                        } catch (Exception e) {
                            log.error("[{}-{}x] Error processing tick for {}: {}", 
                                barType, (int)thresholdMultiplier, key, e.getMessage());
                            return state;
                        }
                    },
                    Materialized.<String, ImbalanceBarState, KeyValueStore<Bytes, byte[]>>as(stateStore)
                        .withKeySerde(Serdes.String())
                        .withValueSerde(ImbalanceBarState.serde())
                )
                .toStream()
                .filter((k, state) -> state.hasCompletedBar())
                .mapValues(ImbalanceBarState::emitAndReset);
        } else {
            // Runs bars (TRB/VRB)
            bars = ticks
                .groupByKey(Grouped.with(Serdes.String(), TickData.serde()))
                .aggregate(
                    () -> new RunsBarState(ewmaSpan, expectedBarSize, warmupSamples, thresholdMultiplier),
                    (key, tick, state) -> {
                        try {
                            state.addTick(tick, barType);
                            return state;
                        } catch (Exception e) {
                            log.error("[{}-{}x] Error processing tick for {}: {}", 
                                barType, (int)thresholdMultiplier, key, e.getMessage());
                            return state;
                        }
                    },
                    Materialized.<String, RunsBarState, KeyValueStore<Bytes, byte[]>>as(stateStore)
                        .withKeySerde(Serdes.String())
                        .withValueSerde(RunsBarState.serde())
                )
                .toStream()
                .filter((k, state) -> state.hasCompletedBar())
                .mapValues(RunsBarState::emitAndReset);
        }
        
        // Publish to output topic
        bars.to(outputTopic, Produced.with(Serdes.String(), InformationBar.serde()));
        
        log.info("✅ {} stream with {}× threshold topology built: output={}", 
            barType, thresholdMultiplier, outputTopic);
    }
    
    /**
     * Validate tick before processing
     */
    private boolean validateTick(TickData tick, String barType, double threshold) {
        if (tick == null) {
            return false;
        }
        
        double price = tick.getLastRate();
        if (price <= 0 || Double.isNaN(price) || Double.isInfinite(price)) {
            return false;
        }
        
        int totalQty = tick.getTotalQuantity();
        if (totalQty < 0) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Log current states of all streams
     */
    public void logStreamStates() {
        log.info("📊 Information Bars Stream States:");
        streamsInstances.forEach((key, streams) -> {
            String emoji = getStateEmoji(streams.state());
            log.info("  {} {}: {}", emoji, key, streams.state());
        });
    }
    
    private String getStateEmoji(KafkaStreams.State state) {
        switch (state) {
            case RUNNING: return "✅";
            case REBALANCING: return "⚠️";
            case ERROR: return "❌";
            case PENDING_SHUTDOWN: return "🛑";
            default: return "ℹ️";
        }
    }
    
    @PreDestroy
    public void cleanup() {
        log.info("🛑 Shutting down Information Bars Processor...");
        streamsInstances.forEach((key, streams) -> {
            log.info("🛑 Stopping stream: {}", key);
            try {
                streams.close();
                log.info("✅ Successfully stopped stream: {}", key);
            } catch (Exception e) {
                log.error("❌ Error stopping stream {}: {}", key, e.getMessage());
            }
        });
        log.info("✅ Information Bars Processor stopped");
    }
}
