package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.*;
import com.kotsin.consumer.service.InstrumentFamilyCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.WindowStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.kafka.support.serializer.JsonSerde;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified Market Data Processor
 * Replaces 19 separate topics with 1 enriched topic
 * Processes all timeframes and features in a single pipeline
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UnifiedMarketDataProcessor {
    
    private final KafkaConfig kafkaConfig;
    private final InstrumentFamilyCacheService cacheService;
    private final Map<String, KafkaStreams> streamsInstances = new ConcurrentHashMap<>();
    
    @Value("${spring.kafka.streams.application-id:unified-market-processor}")
    private String appIdPrefix;
    
    @Value("${unified.output.topic:enriched-market-data}")
    private String outputTopic;
    
    @Value("${unified.input.topic.ticks:forwardtesting-data}")
    private String ticksTopic;
    
    @Value("${unified.input.topic.oi:OpenInterest}")
    private String oiTopic;
    
    @Value("${unified.input.topic.orderbook:Orderbook}")
    private String orderbookTopic;
    
    @PostConstruct
    public void start() {
        try {
            log.info("🚀 Starting Unified Market Data Processor...");
            log.info("📊 Input topics: {}, {}, {}", ticksTopic, oiTopic, orderbookTopic);
            log.info("📤 Output topic: {}", outputTopic);
            
            // Create unified pipeline
            processUnifiedMarketData();
            
            log.info("✅ Unified Market Data Processor started successfully");
            
        } catch (Exception e) {
            log.error("❌ Error starting Unified Market Data Processor", e);
            throw new RuntimeException("Failed to start unified processor", e);
        }
    }
    
    private void processUnifiedMarketData() {
        String instanceKey = "unified-processor";
        
        if (streamsInstances.containsKey(instanceKey)) {
            log.warn("⚠️ Unified processor already running. Skipping duplicate start.");
            return;
        }
        
        Properties props = kafkaConfig.getStreamProperties(appIdPrefix);
        StreamsBuilder builder = new StreamsBuilder();
        
        // Input streams with explicit Consumed serdes
        // CRITICAL: Must specify serdes to properly deserialize from optionProducerJava
        KStream<String, TickData> ticks = builder.stream(
            ticksTopic,
            Consumed.with(Serdes.String(), TickData.serde())
        );
        
        KStream<String, OpenInterest> oiStream = builder.stream(
            oiTopic,
            Consumed.with(Serdes.String(), OpenInterest.serde())
        );
        
        KStream<String, OrderBookSnapshot> orderbookStream = builder.stream(
            orderbookTopic,
            Consumed.with(Serdes.String(), OrderBookSnapshot.serde())
        );
        
        log.info("📥 Created input streams for topics: {}, {}, {}", ticksTopic, oiTopic, orderbookTopic);
        
        // Join ticks with OI data
        KStream<String, TickData> ticksWithOi = ticks.leftJoin(
            oiStream,
            this::mergeOiIntoTick,
            JoinWindows.ofTimeDifferenceAndGrace(Duration.ofSeconds(5), Duration.ofSeconds(1)),
            StreamJoined.with(Serdes.String(), TickData.serde(), OpenInterest.serde())
        );
        
        // Join with orderbook data
        KStream<String, TickData> enrichedTicks = ticksWithOi.leftJoin(
            orderbookStream,
            this::mergeOrderbookIntoTick,
            JoinWindows.ofTimeDifferenceAndGrace(Duration.ofSeconds(5), Duration.ofSeconds(1)),
            StreamJoined.with(Serdes.String(), TickData.serde(), OrderBookSnapshot.serde())
        );
        
        // Key by scripCode
        KStream<String, TickData> keyed = enrichedTicks.selectKey(
            (k, v) -> v.getScripCode()
        );
        
        // Window into 30-minute windows (longest timeframe)
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            Duration.ofMinutes(30),
            Duration.ofSeconds(10)  // Grace period for late data
        );
        
        log.info("⏰ Using 30-minute windows with 10-second grace period");
        
        // Aggregate into multi-timeframe state
        KTable<Windowed<String>, MultiTimeframeState> aggregated = keyed
            .filter((scripCode, tick) -> withinTradingHours(tick))
            .groupByKey(Grouped.with(Serdes.String(), TickData.serde()))
            .windowedBy(windows)
            .aggregate(
                MultiTimeframeState::new,
                (scripCode, tick, state) -> {
                    state.addTick(tick);
                    return state;
                },
                Materialized.<String, MultiTimeframeState, WindowStore<Bytes, byte[]>>as("multi-timeframe-store")
                    .withKeySerde(Serdes.String())
                    .withValueSerde(new JsonSerde<>(MultiTimeframeState.class))
            )
            .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));
        
        // Emit enriched messages
        aggregated.toStream()
            .filter((windowedKey, state) -> state.hasAnyCompleteWindow())
            .mapValues(this::buildEnrichedMessage)
            .selectKey((windowedKey, enrichedData) -> windowedKey.key())
            .to(outputTopic, Produced.with(
                Serdes.String(),
                new JsonSerde<>(EnrichedMarketData.class)
            ));
        
        // Create and start streams
        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streamsInstances.put(instanceKey, streams);
        
        // Error handling
        streams.setUncaughtExceptionHandler((Throwable exception) -> {
            log.error("❌ Uncaught exception in unified processor: ", exception);
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });
        
        // State listener for monitoring
        streams.setStateListener((newState, oldState) -> {
            log.info("🔄 Unified processor state transition: {} -> {}", oldState, newState);
            
            if (newState == KafkaStreams.State.ERROR) {
                log.error("❌ Unified processor entered ERROR state!");
            } else if (newState == KafkaStreams.State.RUNNING) {
                log.info("✅ Unified processor is now RUNNING");
            }
        });
        
        streams.start();
        log.info("✅ Started unified market data processor");
    }
    
    private EnrichedMarketData buildEnrichedMessage(MultiTimeframeState state) {
        try {
            // Get instrument family from cache
            InstrumentFamily family = cacheService.getFamily(state.getScripCode());
            
            if (family == null) {
                log.warn("⚠️ No instrument family found for scripCode: {}", state.getScripCode());
                family = InstrumentFamily.builder()
                    .equityScripCode(state.getScripCode())
                    .companyName(state.getCompanyName())
                    .dataSource("CACHE_MISS")
                    .build();
            }
            
            EnrichedMarketData enrichedData = EnrichedMarketData.builder()
                .scripCode(state.getScripCode())
                .companyName(state.getCompanyName())
                .exchange(state.getExchange())
                .exchangeType(state.getExchangeType())
                .timestamp(state.getLastTickTime())
                .instrumentFamily(family)
                .multiTimeframeCandles(state.getMultiTimeframeCandles())
                .openInterest(state.getOpenInterest())
                .imbalanceBars(state.getImbalanceBars())
                .microstructure(state.getMicrostructure())
                .metadata(MessageMetadata.builder()
                    .messageVersion("2.0")
                    .producedAt(System.currentTimeMillis())
                    .dataQuality(state.getDataQuality())
                    .completeWindows(state.getCompleteWindows())
                    .processingLatency((int) state.getProcessingLatency())
                    .source("unified-processor")
                    .sequenceNumber(state.getMessageCount())
                    .build())
                .build();
            
            log.debug("📤 Built enriched message for {} with {} complete timeframes", 
                state.getScripCode(), state.getCompleteWindows().size());
            
            return enrichedData;
            
        } catch (Exception e) {
            log.error("❌ Failed to build enriched message for scripCode: {}", state.getScripCode(), e);
            
            // Return minimal message to prevent data loss
            return EnrichedMarketData.builder()
                .scripCode(state.getScripCode())
                .companyName(state.getCompanyName())
                .exchange(state.getExchange())
                .exchangeType(state.getExchangeType())
                .timestamp(state.getLastTickTime())
                .metadata(MessageMetadata.builder()
                    .messageVersion("2.0")
                    .producedAt(System.currentTimeMillis())
                    .dataQuality("ERROR")
                    .source("unified-processor")
                    .build())
                .build();
        }
    }
    
    private boolean withinTradingHours(TickData tick) {
        try {
            long ts = tick.getTimestamp();
            if (ts <= 0) {
                log.warn("⚠️ Invalid timestamp (<=0) for token {}", tick.getToken());
                return false;
            }
            
            ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.of("Asia/Kolkata"));
            String exch = tick.getExchange();
            
            if ("N".equalsIgnoreCase(exch)) {
                // NSE: 9:15 AM - 3:30 PM
                return !zdt.toLocalTime().isBefore(java.time.LocalTime.of(9, 15)) && 
                       !zdt.toLocalTime().isAfter(java.time.LocalTime.of(15, 30));
            } else if ("M".equalsIgnoreCase(exch)) {
                // MCX: 9:00 AM - 11:30 PM
                return !zdt.toLocalTime().isBefore(java.time.LocalTime.of(9, 0)) && 
                       !zdt.toLocalTime().isAfter(java.time.LocalTime.of(23, 30));
            } else {
                // Unknown exchange -> drop
                log.debug("Unknown exchange '{}' for token {}, dropping", exch, tick.getToken());
                return false;
            }
        } catch (Exception e) {
            log.warn("⚠️ Invalid timestamp for token {}: {}", tick.getToken(), e.toString());
            return false;
        }
    }
    
    private TickData mergeOiIntoTick(TickData tick, OpenInterest oi) {
        if (oi != null) {
            tick.setOpenInterest(oi.getOpenInterest());
            tick.setOiChange(oi.getOiChange());
        }
        return tick;
    }
    
    private TickData mergeOrderbookIntoTick(TickData tick, OrderBookSnapshot orderbook) {
        if (orderbook != null) {
            // Merge orderbook data into tick
            // Implementation depends on your OrderBookSnapshot structure
            // For now, just return the tick as-is
        }
        return tick;
    }
    
    /**
     * Get current states of all streams
     */
    public Map<String, KafkaStreams.State> getStreamStates() {
        Map<String, KafkaStreams.State> states = new HashMap<>();
        streamsInstances.forEach((key, streams) -> {
            states.put(key, streams.state());
        });
        return states;
    }
    
    /**
     * Stop a specific stream gracefully
     */
    public void stopStream(String instanceKey) {
        KafkaStreams streams = streamsInstances.get(instanceKey);
        if (streams != null) {
            log.info("🛑 Stopping unified processor stream: {}", instanceKey);
            try {
                streams.close(Duration.ofSeconds(30));
                streamsInstances.remove(instanceKey);
                log.info("✅ Successfully stopped unified processor stream: {}", instanceKey);
            } catch (Exception e) {
                log.error("❌ Error stopping unified processor stream {}: ", instanceKey, e);
            }
        } else {
            log.warn("⚠️ Unified processor stream {} not found", instanceKey);
        }
    }
    
    /**
     * Stop all streams gracefully
     */
    @PreDestroy
    public void stopAllStreams() {
        log.info("🛑 Stopping all unified processor streams");
        streamsInstances.keySet().forEach(this::stopStream);
        log.info("✅ All unified processor streams stopped");
    }
}
