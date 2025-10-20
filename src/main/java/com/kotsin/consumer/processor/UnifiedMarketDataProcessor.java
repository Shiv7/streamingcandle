package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.*;
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
import java.util.*;
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

    // Spring-injected dependencies (Dependency Injection Pattern)
    private final KafkaConfig kafkaConfig;
    private final com.kotsin.consumer.processor.service.MarketDataEnrichmentService enrichmentService;
    private final com.kotsin.consumer.processor.service.MarketDataMergeService mergeService;
    private final com.kotsin.consumer.processor.service.TradingHoursValidationService tradingHoursService;
    private final com.kotsin.consumer.processor.service.InstrumentKeyResolver keyResolver;

    private final Map<String, KafkaStreams> streamsInstances = new ConcurrentHashMap<>();
    
    @Value("${spring.kafka.streams.application-id:unified-market-processor1}")
    private String appIdPrefix;
    
    @Value("${unified.output.topic:enriched-market-data}")
    private String outputTopic;
    
    @Value("${unified.input.topic.ticks:forwardtesting-data}")
    private String ticksTopic;
    
    @Value("${unified.input.topic.oi:OpenInterest}")
    private String oiTopic;
    
    @Value("${unified.input.topic.orderbook:Orderbook}")
    private String orderbookTopic;

    // Dual emission feature flags
    @Value("${stream.outputs.enriched.enabled:true}")
    private boolean enrichedOutputEnabled;

    @Value("${stream.outputs.candles.enabled:true}")
    private boolean candlesOutputEnabled;

    @Value("${stream.outputs.candles.include-extras:false}")
    private boolean includeCandleExtras;

    // Finalized candle topics
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
    
    @PostConstruct
    public void start() {
        try {
            log.info("🚀 Starting Unified Market Data Processor...");
            log.info("📊 Application ID: {}", appIdPrefix);
            log.info("📊 Input topics: {}, {}, {}", ticksTopic, oiTopic, orderbookTopic);
            log.info("📤 Output topic: {}", outputTopic);

            // CRITICAL: Ensure only ONE instance per application ID
            if (!streamsInstances.isEmpty()) {
                log.warn("⚠️ Streams already initialized. Skipping duplicate start.");
                log.warn("⚠️ Existing streams: {}", streamsInstances.keySet());
                return;
            }

            // Create unified pipeline
            processUnifiedMarketData();

            log.info("✅ Unified Market Data Processor started successfully");
            log.info("✅ Consumer Group: {}", appIdPrefix);

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
        // Force consume from earliest
        props.put("auto.offset.reset", "earliest");
        StreamsBuilder builder = new StreamsBuilder();

        // CRITICAL: Add state store for delta volume transformer
        String deltaVolumeStoreName = "delta-volume-store";
        builder.addStateStore(
            org.apache.kafka.streams.state.Stores.keyValueStoreBuilder(
                org.apache.kafka.streams.state.Stores.persistentKeyValueStore(deltaVolumeStoreName),
                Serdes.String(),
                Serdes.Integer()
            )
        );

        // Input streams with explicit Consumed serdes
        // CRITICAL: Must specify serdes to properly deserialize from optionProducerJava
        KStream<String, TickData> ticksRaw = builder.stream(
            ticksTopic,
            Consumed.with(Serdes.String(), TickData.serde())
        );

        // FIX #1: Add CumToDeltaTransformer to calculate volume deltas
        // The transformer returns KeyValue<String, TickData>, which Kafka Streams automatically unpacks
        KStream<String, TickData> ticks = ticksRaw
            .transform(
                () -> new com.kotsin.consumer.transformers.CumToDeltaTransformer(deltaVolumeStoreName),
                deltaVolumeStoreName
            )
            .filter((key, tick) -> tick != null && tick.getDeltaVolume() != null);

        // OPTIMIZATION: Convert OI and Orderbook streams to TABLES
        // This ensures we always join with the LATEST value, not all values in a time window
        // Critical for millisecond-level data to prevent Cartesian product explosion

        KTable<String, OpenInterest> oiTable = builder.table(
            oiTopic,
            Consumed.with(Serdes.String(), OpenInterest.serde()),
            org.apache.kafka.streams.kstream.Materialized.<String, OpenInterest, org.apache.kafka.streams.state.KeyValueStore<Bytes, byte[]>>as("oi-table-store")
                .withKeySerde(Serdes.String())
                .withValueSerde(OpenInterest.serde())
        );

        KTable<String, OrderBookSnapshot> orderbookTable = builder.table(
            orderbookTopic,
            Consumed.with(Serdes.String(), OrderBookSnapshot.serde()),
            org.apache.kafka.streams.kstream.Materialized.<String, OrderBookSnapshot, org.apache.kafka.streams.state.KeyValueStore<Bytes, byte[]>>as("orderbook-table-store")
                .withKeySerde(Serdes.String())
                .withValueSerde(OrderBookSnapshot.serde())
        );

        log.info("📥 Created input streams for topics: {}, {}, {}", ticksTopic, oiTopic, orderbookTopic);
        log.info("🔧 Using STREAM-TABLE joins (not STREAM-STREAM) for millisecond-level data efficiency");

        // Join ticks with LATEST OI data (stream-table join, no window needed)
        KStream<String, TickData> ticksWithOi = ticks.leftJoin(
            oiTable,
            mergeService::mergeOiIntoTick,
            Joined.with(Serdes.String(), TickData.serde(), OpenInterest.serde())
        );

        // Join with LATEST orderbook data (stream-table join, no window needed)
        KStream<String, TickData> enrichedTicks = ticksWithOi.leftJoin(
            orderbookTable,
            mergeService::mergeOrderbookIntoTick,
            Joined.with(Serdes.String(), TickData.serde(), OrderBookSnapshot.serde())
        );

        // Key by underlying equity scripCode (map derivatives to their underlying)
        KStream<String, TickData> keyed = enrichedTicks.selectKey(
            (k, v) -> keyResolver.getUnderlyingEquityScripCode(v)
        );

        // FIX #3: Use 1-minute windows instead of 30-minute to enable real-time emission
        // We'll maintain multi-timeframe state within the aggregator
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            Duration.ofMinutes(1),  // Changed from 30 to 1 minute
            Duration.ofSeconds(10)  // Grace period for late data
        );

        log.info("⏰ Using 1-minute tumbling windows with 10-second grace period");

        // Aggregate into multi-timeframe state
        KTable<Windowed<String>, MultiTimeframeState> aggregated = keyed
            .filter((scripCode, tick) -> tradingHoursService.withinTradingHours(tick))
            .groupByKey(Grouped.with(Serdes.String(), TickData.serde()))
            .windowedBy(windows)
            .aggregate(
                MultiTimeframeState::new,
                (scripCode, tick, state) -> {
                    state.addTick(tick);
                    log.debug("🔄 Updated state for {}: messageCount={}", scripCode, state.getMessageCount());
                    return state;
                },
                Materialized.<String, MultiTimeframeState, WindowStore<Bytes, byte[]>>as("multi-timeframe-store")
                    .withKeySerde(Serdes.String())
                    .withValueSerde(new JsonSerde<>(MultiTimeframeState.class))
            );
            // REMOVED SUPPRESSION: Allow immediate emission for debugging
            // .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        // DUAL EMISSION STRATEGY
        KStream<String, MultiTimeframeState> stateStream = aggregated.toStream()
            .selectKey((windowedKey, state) -> windowedKey.key())
            .peek((key, state) -> {
                log.debug("📤 Emitting state for {}: messageCount={}", key, state.getMessageCount());
                // Force window completion for finalized candles
                state.forceCompleteWindows();
            });

        // Stream 1: ENRICHED updates (DEPRECATED)
        if (enrichedOutputEnabled) {
            log.warn("⚠️ enriched-market-data emission is DEPRECATED and disabled by default. Enable only for debugging.");
            // If explicitly enabled via properties, emit as before
            stateStream
                .mapValues(enrichmentService::buildEnrichedMessage)
                .to(outputTopic, Produced.with(
                    Serdes.String(),
                    new JsonSerde<>(EnrichedMarketData.class)
                ));

            log.info("✅ Enriched emission enabled → topic: {}", outputTopic);
        } else {
            log.info("🛑 Enriched emission disabled (deprecated). Only finalized candle topics are active.");
        }

        // Stream 2: FINALIZED candles (only complete windows, per timeframe)
        if (candlesOutputEnabled) {
            // Branch out finalized candles to 6 topics
            emitFinalizedCandlesStreams(stateStream);

            log.info("✅ Finalized candles enabled → 6 topics (1m, 2m, 3m, 5m, 15m, 30m)");
        }
        
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
    
    /**
     * Emit finalized candles to timeframe-specific topics using Kafka Streams
     * This uses flatMapValues to convert one state into multiple candles
     */
    private void emitFinalizedCandlesStreams(KStream<String, MultiTimeframeState> stateStream) {
        // Filter states that have at least one complete window
        KStream<String, MultiTimeframeState> completeStates = stateStream
            .filter((key, state) -> state.hasAnyCompleteWindow());

        // FlatMap to extract all complete candles per timeframe
        Map<String, KStream<String, Candlestick>> timeframeStreams = new HashMap<>();

        // Create streams for each timeframe
        for (String timeframe : Arrays.asList("1m", "2m", "3m", "5m", "15m", "30m")) {
            KStream<String, Candlestick> candleStream = completeStates
                .flatMapValues((readOnlyKey, state) -> extractFinalizedCandles(state, timeframe))
                .filter((key, candle) -> candle != null);

            String topic = getCandleTopicForTimeframe(timeframe);
            if (topic != null) {
                candleStream.to(topic, Produced.with(
                    Serdes.String(),
                    Candlestick.serde()
                ));

                log.debug("✅ Configured finalized candle stream: {} → {}", timeframe, topic);
            }
        }
    }

    /**
     * Extract finalized candles for a specific timeframe
     * Returns a list (possibly empty) to support flatMapValues
     */
    private List<Candlestick> extractFinalizedCandles(MultiTimeframeState state, String timeframe) {
        List<Candlestick> candles = new ArrayList<>();

        try {
            Map<String, CandleData> allCandles = state.getMultiTimeframeCandles();
            CandleData candleData = allCandles.get(timeframe);

            if (candleData != null && candleData.getIsComplete()) {
                CandleAccumulator accumulator = state.getCandleAccumulators().get(Timeframe.fromLabel(timeframe));

                if (accumulator != null) {
                    Candlestick finalizedCandle = accumulator.toFinalizedCandlestick(
                        state.getScripCode(),
                        state.getCompanyName(),
                        state.getExchange(),
                        state.getExchangeType(),
                        includeCandleExtras
                    );

                    candles.add(finalizedCandle);

                    log.debug("✅ Extracted finalized {} candle for {}",
                        timeframe, state.getScripCode());
                }
            }
        } catch (Exception e) {
            log.error("❌ Failed to extract finalized {} candle for {}: {}",
                timeframe, state.getScripCode(), e.getMessage());
        }

        return candles;
    }

    /**
     * Get Kafka topic name for a given timeframe
     */
    private String getCandleTopicForTimeframe(String timeframe) {
        switch (timeframe) {
            case "1m": return candle1mTopic;
            case "2m": return candle2mTopic;
            case "3m": return candle3mTopic;
            case "5m": return candle5mTopic;
            case "15m": return candle15mTopic;
            case "30m": return candle30mTopic;
            default: return null;
        }
    }

    /**
     * SOLID Principle: Single Responsibility
     *
     * The following methods have been extracted to specialized services:
     * - buildEnrichedMessage() → MarketDataEnrichmentService
     * - mergeOiIntoTick() → MarketDataMergeService
     * - mergeOrderbookIntoTick() → MarketDataMergeService
     * - withinTradingHours() → TradingHoursValidationService
     * - getUnderlyingEquityScripCode() → InstrumentKeyResolver
     *
     * This follows Spring Boot best practices:
     * - Dependency Injection via constructor
     * - Business logic in @Service classes
     * - Processor focuses only on Kafka Streams topology
     */
    
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
