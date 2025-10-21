package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.*;
import com.kotsin.consumer.processor.service.CandleEmissionService;
import com.kotsin.consumer.processor.service.FamilyAggregationService;
import com.kotsin.consumer.processor.service.InstrumentKeyResolver;
import com.kotsin.consumer.processor.service.TradingHoursValidationService;
import com.kotsin.consumer.metrics.StreamMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.KeyValue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified Market Data Processor (Refactored)
 * Orchestrates Kafka Streams topologies for market data processing
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UnifiedMarketDataProcessor {

    private final KafkaConfig kafkaConfig;
    private final TradingHoursValidationService tradingHoursService;
    private final InstrumentKeyResolver keyResolver;
    private final StreamMetrics metrics;
    private final FamilyAggregationService familyAggService;
    private final CandleEmissionService candleEmissionService;

    private final Map<String, KafkaStreams> streamsInstances = new ConcurrentHashMap<>();
    
    @Value("${spring.kafka.streams.application-id:unified-market-processor1}")
    private String appIdPrefix;
    
    @Value("${unified.input.topic.ticks:forwardtesting-data}")
    private String ticksTopic;
    
    @Value("${unified.input.topic.oi:OpenInterest}")
    private String oiTopic;
    
    @Value("${unified.input.topic.orderbook:Orderbook}")
    private String orderbookTopic;

    @Value("${stream.outputs.candles.enabled:true}")
    private boolean candlesOutputEnabled;

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
    
    @Value("${stream.outputs.familyStructured.enabled:false}")
    private boolean familyStructuredEnabled;

    @Value("${stream.outputs.familyStructured.1m:family-structured-1m}")
    private String familyStructured1mTopic;

    @Value("${stream.outputs.familyStructured.2m:family-structured-2m}")
    private String familyStructured2mTopic;

    @Value("${stream.outputs.familyStructured.5m:family-structured-5m}")
    private String familyStructured5mTopic;

    @Value("${stream.outputs.familyStructured.15m:family-structured-15m}")
    private String familyStructured15mTopic;

    @Value("${stream.outputs.familyStructured.30m:family-structured-30m}")
    private String familyStructured30mTopic;

    @Value("${stream.outputs.familyStructured.all:family-structured-all}")
    private String familyStructuredAllTopic;
    
    @PostConstruct
    public void start() {
        try {
            log.info("🚀 Starting Unified Market Data Processor...");
            log.info("Flags: candlesOutputEnabled={}, familyStructuredEnabled={}", candlesOutputEnabled, familyStructuredEnabled);
            log.info("Input topics: ticks={}, oi={}, orderbook={}", ticksTopic, oiTopic, orderbookTopic);
            log.info("Candle topics: 1m={}, 2m={}, 3m={}, 5m={}, 15m={}, 30m={}", candle1mTopic, candle2mTopic, candle3mTopic, candle5mTopic, candle15mTopic, candle30mTopic);
            log.info("Family topics: 1m={}, 2m={}, 5m={}, 15m={}, 30m={}, all={}", familyStructured1mTopic, familyStructured2mTopic, familyStructured5mTopic, familyStructured15mTopic, familyStructured30mTopic, familyStructuredAllTopic);

            if (!streamsInstances.isEmpty()) {
                log.warn("⚠️ Streams already initialized. Skipping duplicate start.");
                return;
            }

            processPerInstrumentStream();
            if (familyStructuredEnabled) {
                buildFamilyStructuredStream("1m", candle1mTopic, familyStructured1mTopic, Duration.ofMinutes(1));
                buildFamilyStructuredStream("2m", candle2mTopic, familyStructured2mTopic, Duration.ofMinutes(2));
                buildFamilyStructuredStream("5m", candle5mTopic, familyStructured5mTopic, Duration.ofMinutes(5));
                buildFamilyStructuredStream("15m", candle15mTopic, familyStructured15mTopic, Duration.ofMinutes(15));
                buildFamilyStructuredStream("30m", candle30mTopic, familyStructured30mTopic, Duration.ofMinutes(30));
                processFamilyStructuredAll();
            }

            log.info("✅ Unified Market Data Processor started successfully");

        } catch (Exception e) {
            log.error("❌ Error starting Unified Market Data Processor", e);
            throw new RuntimeException("Failed to start unified processor", e);
        }
    }

    /**
     * STREAM 1: Per-Instrument Candle Generation
     */
    private void processPerInstrumentStream() {
        String instanceKey = "instrument-stream";

        if (streamsInstances.containsKey(instanceKey)) {
            log.warn("⚠️ Instrument stream already running. Skipping duplicate start.");
            return;
        }

        Properties props = kafkaConfig.getStreamProperties(appIdPrefix + "-instrument");
        props.put("auto.offset.reset", "earliest");
        StreamsBuilder builder = new StreamsBuilder();

        String deltaVolumeStoreName = "instrument-delta-volume-store";
        builder.addStateStore(
            org.apache.kafka.streams.state.Stores.keyValueStoreBuilder(
                org.apache.kafka.streams.state.Stores.persistentKeyValueStore(deltaVolumeStoreName),
                Serdes.String(),
                Serdes.Integer()
            )
        );

        KStream<String, TickData> ticksRaw = builder.stream(
            ticksTopic,
            Consumed.with(Serdes.String(), TickData.serde())
        );

        KStream<String, TickData> ticks = ticksRaw
            .transform(
                () -> new com.kotsin.consumer.transformers.CumToDeltaTransformer(deltaVolumeStoreName),
                deltaVolumeStoreName
            )
            .filter((key, tick) -> tick != null && tick.getDeltaVolume() != null);

        // Orderbook stream for enrichment (pattern matches OI implementation)
        KStream<String, OrderBookSnapshot> orderbookStream = builder.stream(
            orderbookTopic,
            Consumed.with(Serdes.String(), OrderBookSnapshot.serde())
        );

        // Re-key by token (exactly like OI does - token equals scripCode for all instruments)
        KTable<String, OrderBookSnapshot> orderbookTable = orderbookStream
            .selectKey((k, v) -> v != null && v.getToken() != null ? v.getToken() : k)
            .toTable(Materialized.with(Serdes.String(), OrderBookSnapshot.serde()));

        KStream<String, TickData> instrumentKeyed = ticks
            .selectKey((k, v) -> keyResolver.getInstrumentKey(v));

        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            Duration.ofMinutes(1),
            Duration.ofSeconds(10)
        );

        KTable<org.apache.kafka.streams.kstream.Windowed<String>, InstrumentState> aggregated = instrumentKeyed
            .filter((scripCode, tick) -> tradingHoursService.withinTradingHours(tick))
            .groupByKey(Grouped.with(Serdes.String(), TickData.serde()))
            .windowedBy(windows)
            .aggregate(
                InstrumentState::new,
                (scripCode, tick, state) -> {
                    if (state.getScripCode() == null) {
                        String instrumentType = keyResolver.getInstrumentType(tick);
                        String familyKey = keyResolver.getFamilyKey(tick);
                        state.setInstrumentType(instrumentType);
                        state.setUnderlyingEquityScripCode(familyKey);
                    }
                    state.addTick(tick);
                    return state;
                },
                org.apache.kafka.streams.kstream.Materialized.<String, InstrumentState, org.apache.kafka.streams.state.WindowStore<org.apache.kafka.common.utils.Bytes, byte[]>>as("instrument-state-store")
                    .withKeySerde(Serdes.String())
                    .withValueSerde(new org.springframework.kafka.support.serializer.JsonSerde<>(InstrumentState.class))
            );

        KStream<String, InstrumentState> stateStream = aggregated
            .suppress(org.apache.kafka.streams.kstream.Suppressed.untilWindowCloses(
                org.apache.kafka.streams.kstream.Suppressed.BufferConfig.unbounded()
            ))
            .toStream()
            .peek((windowedKey, state) -> state.forceCompleteWindows(windowedKey.window().end()))
            .selectKey((windowedKey, state) -> windowedKey.key());

        // Enrich with orderbook data
        KStream<String, InstrumentState> enrichedState = stateStream
            .leftJoin(orderbookTable,
                (state, orderbook) -> {
                    log.debug("🔍 OB JOIN: scripCode={} obToken={} obValid={} hasBids={}", 
                        state.getScripCode(), 
                        orderbook != null ? orderbook.getToken() : "null",
                        orderbook != null && orderbook.isValid(),
                        orderbook != null && orderbook.getBids() != null ? orderbook.getBids().size() : 0);
                    if (orderbook != null && orderbook.isValid()) {
                        state.addOrderbook(orderbook);
                    } else if (orderbook != null) {
                        log.warn("⚠️ OB INVALID: scripCode={} token={} valid={}", 
                            state.getScripCode(), orderbook.getToken(), orderbook.isValid());
                    }
                    return state;
                });

        if (candlesOutputEnabled) {
            candleEmissionService.emitPerInstrumentCandles(enrichedState, this::getCandleTopicForTimeframe);
        }

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streamsInstances.put(instanceKey, streams);
        streams.start();
        log.info("✅ Started per-instrument candle stream");
    }

    /**
     * Build family-structured stream for a specific timeframe
     */
    private void buildFamilyStructuredStream(String timeframeLabel, String sourceTopic, String sinkTopic, Duration windowSize) {
        String instanceKey = "family-structured-" + timeframeLabel;
        if (streamsInstances.containsKey(instanceKey)) {
            log.warn("⚠️ {} already running. Skipping duplicate start.", instanceKey);
            return;
        }

        Properties props = kafkaConfig.getStreamProperties(appIdPrefix + "-" + instanceKey);
        props.put("auto.offset.reset", "earliest");
        StreamsBuilder builder = new StreamsBuilder();

        log.info("⏳ Building {} from source topic {} → sink {}", timeframeLabel, sourceTopic, sinkTopic);

        KStream<String, InstrumentCandle> candles = builder.stream(
            sourceTopic,
            Consumed.with(Serdes.String(), InstrumentCandle.serde())
        );

        // Re-key by scripCode
        KStream<String, InstrumentCandle> rekeyedByToken = candles
            .selectKey((k, c) -> c != null ? c.getScripCode() : k)
            .repartition(Repartitioned.with(Serdes.String(), InstrumentCandle.serde()));

        // Build OI enrichment table
        KTable<String, OpenInterest> oiTable = buildOiTable(builder);

        // Enrich candles with OI data (orderbook already embedded from per-instrument stream)
        KStream<String, InstrumentCandle> enrichedCandles = enrichCandles(rekeyedByToken, oiTable);

        // Map to family key and aggregate
        KStream<String, InstrumentCandle> keyedByFamily = enrichedCandles
            .selectKey((scripOrToken, candle) -> candle.getUnderlyingEquityScripCode() != null
                ? candle.getUnderlyingEquityScripCode()
                : candle.getScripCode())
            .repartition(Repartitioned.with(Serdes.String(), InstrumentCandle.serde()));

        TimeWindows windows = TimeWindows.ofSizeAndGrace(windowSize, Duration.ofSeconds(10));

        KTable<org.apache.kafka.streams.kstream.Windowed<String>, FamilyEnrichedData> aggregated = keyedByFamily
            .groupByKey(Grouped.with(Serdes.String(), InstrumentCandle.serde()))
            .windowedBy(windows)
            .aggregate(
                () -> FamilyEnrichedData.builder().build(),
                (familyKey, candle, family) -> familyAggService.assembleFamily(familyKey, candle, family),
                Materialized.with(Serdes.String(), FamilyEnrichedData.serde())
            );

        KStream<String, FamilyEnrichedData> out = aggregated
            .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
            .toStream()
            .selectKey((windowedKey, family) -> windowedKey.key())
            .peek((key, family) -> {
                if (family != null) {
                    family.setProcessingTimestamp(System.currentTimeMillis());
                    family.setTimeframe(timeframeLabel);
                    log.info("📤 family emit tf={} key={} eq={} futs={} opts={} volTotal={} basis={}",
                        timeframeLabel,
                        key,
                        family.getEquity() != null,
                        family.getFutures() != null ? family.getFutures().size() : 0,
                        family.getOptions() != null ? family.getOptions().size() : 0,
                        family.getAggregatedMetrics() != null ? family.getAggregatedMetrics().getTotalVolume() : null,
                        family.getAggregatedMetrics() != null ? family.getAggregatedMetrics().getFuturesBasis() : null);
                }
            });

        out.to(sinkTopic, Produced.with(Serdes.String(), FamilyEnrichedData.serde()));

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streamsInstances.put(instanceKey, streams);
        streams.start();
        log.info("✅ Started {} stream → topic: {}", instanceKey, sinkTopic);
    }

    /**
     * Build OI table with stateful oiChange calculation
     */
    private KTable<String, OpenInterest> buildOiTable(StreamsBuilder builder) {
        KStream<String, OpenInterest> oiStreamRaw = builder.stream(
            oiTopic,
            Consumed.with(Serdes.String(), OpenInterest.serde())
        );

        final String oiPrevStore = "oi-prev-store";
        builder.addStateStore(
            org.apache.kafka.streams.state.Stores.keyValueStoreBuilder(
                org.apache.kafka.streams.state.Stores.persistentKeyValueStore(oiPrevStore),
                Serdes.String(),
                Serdes.Long()
            )
        );

        KStream<String, OpenInterest> oiWithChange = oiStreamRaw
            .selectKey((k, v) -> v != null ? String.valueOf(v.getToken()) : null)
            .transform(() -> new org.apache.kafka.streams.kstream.Transformer<String, OpenInterest, KeyValue<String, OpenInterest>>() {
                private org.apache.kafka.streams.state.KeyValueStore<String, Long> store;
                @SuppressWarnings("unchecked")
                @Override public void init(org.apache.kafka.streams.processor.ProcessorContext context) {
                    store = (org.apache.kafka.streams.state.KeyValueStore<String, Long>) context.getStateStore(oiPrevStore);
                }
                @Override public KeyValue<String, OpenInterest> transform(String key, OpenInterest value) {
                    if (key == null || value == null || value.getOpenInterest() == null) return KeyValue.pair(key, value);
                    Long prev = store.get(key);
                    if (prev != null) {
                        value.setOiChange(value.getOpenInterest() - prev);
                        if (prev > 0 && value.getOiChange() != null) {
                            value.setOiChangePercent((value.getOiChange() * 100.0) / prev);
                        }
                    }
                    store.put(key, value.getOpenInterest());
                    return KeyValue.pair(key, value);
                }
                @Override public void close() {}
            }, oiPrevStore);

        return oiWithChange
            .groupByKey(Grouped.with(Serdes.String(), OpenInterest.serde()))
            .reduce((prev, curr) -> curr);
    }


    /**
     * Enrich candles with OI data (orderbook already embedded from per-instrument stream)
     */
    private KStream<String, InstrumentCandle> enrichCandles(
            KStream<String, InstrumentCandle> candles,
            KTable<String, OpenInterest> oiTable) {
        
        return candles
            .leftJoin(oiTable, (candle, oi) -> {
                if (oi != null) {
                    candle.setOpenInterest(oi.getOpenInterest());
                    candle.setOiChange(oi.getOiChange());
                    candle.setOiChangePercent(oi.getOiChangePercent());
                    log.debug("✅ OI join success: scripCode={} oi={} oiChange={} oiChangePct={}%", 
                        candle.getScripCode(), oi.getOpenInterest(), oi.getOiChange(), oi.getOiChangePercent());
                } else {
                    metrics.incOiJoinMiss();
                    log.debug("❌ OI join miss: scripCode={}", candle.getScripCode());
                }
                return candle;
            });
    }

    /**
     * Process family-structured-all (combines all timeframes)
     */
    private void processFamilyStructuredAll() {
        String instanceKey = "family-structured-all";
        if (streamsInstances.containsKey(instanceKey)) {
            log.warn("⚠️ {} already running. Skipping duplicate start.", instanceKey);
            return;
        }

        Properties props = kafkaConfig.getStreamProperties(appIdPrefix + "-" + instanceKey);
        props.put("auto.offset.reset", "earliest");
        StreamsBuilder builder = new StreamsBuilder();

        KStream<String, FamilyEnrichedData> f1 = builder.stream(
            familyStructured1mTopic, Consumed.with(Serdes.String(), FamilyEnrichedData.serde()));
        KStream<String, FamilyEnrichedData> f2 = builder.stream(
            familyStructured2mTopic, Consumed.with(Serdes.String(), FamilyEnrichedData.serde()));
        KStream<String, FamilyEnrichedData> f5 = builder.stream(
            familyStructured5mTopic, Consumed.with(Serdes.String(), FamilyEnrichedData.serde()));
        KStream<String, FamilyEnrichedData> f15 = builder.stream(
            familyStructured15mTopic, Consumed.with(Serdes.String(), FamilyEnrichedData.serde()));
        KStream<String, FamilyEnrichedData> f30 = builder.stream(
            familyStructured30mTopic, Consumed.with(Serdes.String(), FamilyEnrichedData.serde()));

        KStream<String, FamilyStructuredAll> p1 = f1.mapValues(this::toAllPartial).filter((k, v) -> v != null);
        KStream<String, FamilyStructuredAll> p2 = f2.mapValues(this::toAllPartial).filter((k, v) -> v != null);
        KStream<String, FamilyStructuredAll> p5 = f5.mapValues(this::toAllPartial).filter((k, v) -> v != null);
        KStream<String, FamilyStructuredAll> p15 = f15.mapValues(this::toAllPartial).filter((k, v) -> v != null);
        KStream<String, FamilyStructuredAll> p30 = f30.mapValues(this::toAllPartial).filter((k, v) -> v != null);

        KStream<String, FamilyStructuredAll> merged = p1
            .merge(p2)
            .merge(p5)
            .merge(p15)
            .merge(p30)
            .filter((k, v) -> k != null && v != null)
            .groupByKey(Grouped.with(Serdes.String(), FamilyStructuredAll.serde()))
            .reduce(this::mergeAll)
            .toStream()
            .peek((k, v) -> v.setProcessingTimestamp(System.currentTimeMillis()));

        merged.to(familyStructuredAllTopic, Produced.with(Serdes.String(), FamilyStructuredAll.serde()));

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streamsInstances.put(instanceKey, streams);
        streams.start();
        log.info("✅ Started family-structured-all stream → topic: {}", familyStructuredAllTopic);
    }

    private FamilyStructuredAll toAllPartial(FamilyEnrichedData fed) {
        if (fed == null) {
            log.warn("⚠️ toAllPartial received null FamilyEnrichedData");
            return null;
        }
        if (fed.getFamilyKey() == null || fed.getTimeframe() == null) {
            log.warn("⚠️ toAllPartial: familyKey={} timeframe={} - skipping null key/tf",
                fed.getFamilyKey(), fed.getTimeframe());
            return null;
        }
        
        FamilyStructuredAll all = FamilyStructuredAll.builder()
            .familyKey(fed.getFamilyKey())
            .familyName(fed.getFamilyName())
            .build();
        String tf = fed.getTimeframe();
        if (fed.getEquity() != null) {
            all.getEquity().put(tf, fed.getEquity());
        }
        if (fed.getFutures() != null && !fed.getFutures().isEmpty()) {
            all.getFuture().put(tf, fed.getFutures().get(0));
        }
        if (fed.getOptions() != null) {
            for (InstrumentCandle opt : fed.getOptions()) {
                all.getOptions().computeIfAbsent(opt.getScripCode(), k -> new HashMap<>()).put(tf, opt);
            }
        }
        return all;
    }

    private FamilyStructuredAll mergeAll(FamilyStructuredAll a, FamilyStructuredAll b) {
        if (a.getFamilyName() == null) a.setFamilyName(b.getFamilyName());
        a.getEquity().putAll(b.getEquity());
        a.getFuture().putAll(b.getFuture());
        b.getOptions().forEach((k, v) -> a.getOptions().computeIfAbsent(k, kk -> new HashMap<>()).putAll(v));
        return a;
    }

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

    public Map<String, KafkaStreams.State> getStreamStates() {
        Map<String, KafkaStreams.State> states = new HashMap<>();
        streamsInstances.forEach((key, streams) -> states.put(key, streams.state()));
        return states;
    }
    
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
    
    @PreDestroy
    public void stopAllStreams() {
        log.info("🛑 Stopping all unified processor streams");
        streamsInstances.keySet().forEach(this::stopStream);
        log.info("✅ All unified processor streams stopped");
    }
}

