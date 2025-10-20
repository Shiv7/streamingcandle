package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.*;
import com.kotsin.consumer.service.MongoInstrumentFamilyService;
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
    
    private final KafkaConfig kafkaConfig;
    private final MongoInstrumentFamilyService cacheService;
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
        
        KStream<String, OpenInterest> oiStream = builder.stream(
            oiTopic,
            Consumed.with(Serdes.String(), OpenInterest.serde())
        );
        
        KStream<String, OrderBookSnapshot> orderbookStream = builder.stream(
            orderbookTopic,
            Consumed.with(Serdes.String(), OrderBookSnapshot.serde())
        );
        
        log.info("📥 Created input streams for topics: {}, {}, {}", ticksTopic, oiTopic, orderbookTopic);

        // FIX #2: Increase join windows to accommodate actual data arrival rates
        // OI arrives every 10 seconds, so join window must be >= 15s to ensure capture
        // Orderbook arrives every 4 seconds, 15s window is sufficient

        // Join ticks with OI data
        KStream<String, TickData> ticksWithOi = ticks.leftJoin(
            oiStream,
            this::mergeOiIntoTick,
            JoinWindows.ofTimeDifferenceAndGrace(Duration.ofSeconds(15), Duration.ofSeconds(5)),
            StreamJoined.with(Serdes.String(), TickData.serde(), OpenInterest.serde())
        );

        // Join with orderbook data
        KStream<String, TickData> enrichedTicks = ticksWithOi.leftJoin(
            orderbookStream,
            this::mergeOrderbookIntoTick,
            JoinWindows.ofTimeDifferenceAndGrace(Duration.ofSeconds(15), Duration.ofSeconds(5)),
            StreamJoined.with(Serdes.String(), TickData.serde(), OrderBookSnapshot.serde())
        );
        
        // Key by underlying equity scripCode (map derivatives to their underlying)
        KStream<String, TickData> keyed = enrichedTicks.selectKey(
            (k, v) -> getUnderlyingEquityScripCode(v)
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
            // Emit once per key when the 1-minute window closes
            .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        // DUAL EMISSION STRATEGY
        KStream<String, MultiTimeframeState> stateStream = aggregated.toStream()
            .selectKey((windowedKey, state) -> windowedKey.key());

        // Stream 1: ENRICHED updates (partial, every 1-min window close)
        if (enrichedOutputEnabled) {
            stateStream
                .mapValues(this::buildEnrichedMessage)
                .to(outputTopic, Produced.with(
                    Serdes.String(),
                    new JsonSerde<>(EnrichedMarketData.class)
                ));

            log.info("✅ Enriched emission enabled → topic: {}", outputTopic);
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
                CandleAccumulator accumulator = state.getCandleAccumulators().get(timeframe);

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

    private EnrichedMarketData buildEnrichedMessage(MultiTimeframeState state) {
        try {
            // Get instrument family from cache
            InstrumentFamily family = cacheService.resolveFamily(
                state.getScripCode(),
                state.getExchangeType(),
                state.getCompanyName()
            );
            
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
    
    /**
     * Get underlying equity scripCode for a tick.
     * If it's a derivative (ExchType=D), resolve to underlying equity.
     * Otherwise, return the tick's own scripCode.
     */
    private String getUnderlyingEquityScripCode(TickData tick) {
        if (tick == null) {
            return null;
        }
        
        // If it's a derivative, resolve to underlying equity
        if ("D".equalsIgnoreCase(tick.getExchangeType())) {
            InstrumentFamily family = cacheService.resolveFamily(
                tick.getScripCode(),
                tick.getExchangeType(),
                tick.getCompanyName()
            );
            
            if (family != null && family.getEquityScripCode() != null) {
                log.debug("📍 Mapped derivative {} to underlying equity {}", 
                    tick.getScripCode(), family.getEquityScripCode());
                return family.getEquityScripCode();
            }
        }
        
        // For equities or if resolution fails, use the tick's own scripCode
        return tick.getScripCode();
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
