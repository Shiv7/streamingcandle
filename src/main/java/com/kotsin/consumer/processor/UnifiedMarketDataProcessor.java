package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified Market Data Processor
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UnifiedMarketDataProcessor {

    private final KafkaConfig kafkaConfig;
    private final com.kotsin.consumer.processor.service.MarketDataMergeService mergeService;
    private final com.kotsin.consumer.processor.service.TradingHoursValidationService tradingHoursService;
    private final com.kotsin.consumer.processor.service.InstrumentKeyResolver keyResolver;

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
    
    @Value("${stream.outputs.familyStructured.enabled:false}")
    private boolean familyStructuredEnabled;

    @Value("${stream.outputs.familyStructured.1m:family-structured-1m}")
    private String familyStructured1mTopic;

    @Value("${stream.outputs.familyStructured.2m:family-structured-2m}")
    private String familyStructured2mTopic;

    @Value("${stream.outputs.familyStructured.5m:family-structured-5m}")
    private String familyStructured5mTopic;

    @Value("${stream.outputs.familyStructured.all:family-structured-all}")
    private String familyStructuredAllTopic;
    
    @PostConstruct
    public void start() {
        try {
            log.info("🚀 Starting Unified Market Data Processor...");

            if (!streamsInstances.isEmpty()) {
                log.warn("⚠️ Streams already initialized. Skipping duplicate start.");
                return;
            }

            processPerInstrumentStream();
            if (familyStructuredEnabled) {
                buildFamilyStructuredStream("1m", candle1mTopic, familyStructured1mTopic, Duration.ofMinutes(1));
                buildFamilyStructuredStream("2m", candle2mTopic, familyStructured2mTopic, Duration.ofMinutes(2));
                buildFamilyStructuredStream("5m", candle5mTopic, familyStructured5mTopic, Duration.ofMinutes(5));
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

        java.util.Properties props = kafkaConfig.getStreamProperties(appIdPrefix + "-instrument");
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

        if (candlesOutputEnabled) {
            emitPerInstrumentCandles(stateStream);
        }

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streamsInstances.put(instanceKey, streams);
        streams.start();
        log.info("✅ Started per-instrument candle stream");
    }

    private void buildFamilyStructuredStream(String timeframeLabel, String sourceTopic, String sinkTopic, Duration windowSize) {
        String instanceKey = "family-structured-" + timeframeLabel;
        if (streamsInstances.containsKey(instanceKey)) {
            log.warn("⚠️ {} already running. Skipping duplicate start.", instanceKey);
            return;
        }

        java.util.Properties props = kafkaConfig.getStreamProperties(appIdPrefix + "-" + instanceKey);
        props.put("auto.offset.reset", "earliest");
        StreamsBuilder builder = new StreamsBuilder();

        KStream<String, InstrumentCandle> candles = builder.stream(
            sourceTopic,
            Consumed.with(Serdes.String(), InstrumentCandle.serde())
        );

        KTable<String, OpenInterest> oiTable = builder.table(
            oiTopic,
            Consumed.with(Serdes.String(), OpenInterest.serde())
        );
        KTable<String, OrderBookSnapshot> orderbookTable = builder.table(
            orderbookTopic,
            Consumed.with(Serdes.String(), OrderBookSnapshot.serde())
        );

        KStream<String, InstrumentCandle> enrichedCandles = candles
            .leftJoin(oiTable, (candle, oi) -> {
                if (oi != null) {
                    candle.setOpenInterest(oi.getOpenInterest());
                    candle.setOiChange(oi.getOiChange());
                }
                return candle;
            })
            .leftJoin(orderbookTable, (candle, ob) -> {
                if (ob != null && ob.isValid()) {
                    ob.parseDetails();
                    OrderbookDepthData depth = OrderbookDepthData.builder()
                        .totalBidDepth(ob.getTotalBidQty() != null ? ob.getTotalBidQty().doubleValue() : null)
                        .totalAskDepth(ob.getTotalOffQty() != null ? ob.getTotalOffQty().doubleValue() : null)
                        .spread(ob.getSpread())
                        .midPrice(ob.getMidPrice())
                        .timestamp(ob.getTimestamp())
                        .isComplete(true)
                        .build();
                    candle.setOrderbookDepth(depth);
                }
                return candle;
            });

        KStream<String, InstrumentCandle> keyedByFamily = enrichedCandles
            .selectKey((scrip, candle) -> candle.getUnderlyingEquityScripCode() != null
                ? candle.getUnderlyingEquityScripCode()
                : candle.getScripCode());

        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            windowSize,
            Duration.ofSeconds(10)
        );

        KTable<org.apache.kafka.streams.kstream.Windowed<String>, FamilyEnrichedData> aggregated = keyedByFamily
            .groupByKey(Grouped.with(Serdes.String(), InstrumentCandle.serde()))
            .windowedBy(windows)
            .aggregate(
                () -> FamilyEnrichedData.builder().build(),
                (familyKey, candle, family) -> assembleFamily(familyKey, candle, family),
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
                }
            });

        out.to(sinkTopic, Produced.with(
            Serdes.String(), FamilyEnrichedData.serde()
        ));

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streamsInstances.put(instanceKey, streams);
        streams.start();
        log.info("✅ Started {} stream → topic: {}", instanceKey, sinkTopic);
    }

    private void processFamilyStructuredAll() {
        String instanceKey = "family-structured-all";
        if (streamsInstances.containsKey(instanceKey)) {
            log.warn("⚠️ {} already running. Skipping duplicate start.", instanceKey);
            return;
        }

        java.util.Properties props = kafkaConfig.getStreamProperties(appIdPrefix + "-" + instanceKey);
        props.put("auto.offset.reset", "earliest");
        StreamsBuilder builder = new StreamsBuilder();

        // Consume the three family-structured topics and merge into a single message per family
        KStream<String, FamilyEnrichedData> f1 = builder.stream(
            familyStructured1mTopic, Consumed.with(Serdes.String(), FamilyEnrichedData.serde()));
        KStream<String, FamilyEnrichedData> f2 = builder.stream(
            familyStructured2mTopic, Consumed.with(Serdes.String(), FamilyEnrichedData.serde()));
        KStream<String, FamilyEnrichedData> f5 = builder.stream(
            familyStructured5mTopic, Consumed.with(Serdes.String(), FamilyEnrichedData.serde()));

        // Convert each into partial FamilyStructuredAll keyed by family
        KStream<String, FamilyStructuredAll> p1 = f1.mapValues(this::toAllPartial);
        KStream<String, FamilyStructuredAll> p2 = f2.mapValues(this::toAllPartial);
        KStream<String, FamilyStructuredAll> p5 = f5.mapValues(this::toAllPartial);

        // Merge using reduce by key
        KStream<String, FamilyStructuredAll> merged = p1
            .merge(p2)
            .merge(p5)
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
                all.getOptions().computeIfAbsent(opt.getScripCode(), k -> new java.util.HashMap<>()).put(tf, opt);
            }
        }
        return all;
    }

    private FamilyStructuredAll mergeAll(FamilyStructuredAll a, FamilyStructuredAll b) {
        if (a.getFamilyName() == null) a.setFamilyName(b.getFamilyName());
        a.getEquity().putAll(b.getEquity());
        a.getFuture().putAll(b.getFuture());
        b.getOptions().forEach((k, v) -> a.getOptions().computeIfAbsent(k, kk -> new java.util.HashMap<>()).putAll(v));
        return a;
    }

    private FamilyEnrichedData assembleFamily(String familyKey, InstrumentCandle candle, FamilyEnrichedData family) {
        if (family == null) {
            family = FamilyEnrichedData.builder().familyKey(familyKey).build();
        }
        // Set window bounds/timeframe if absent
        if (family.getWindowStartMillis() == null) {
            family.setWindowStartMillis(candle.getWindowStartMillis());
        }
        family.setWindowEndMillis(candle.getWindowEndMillis());

        // Identify instrument bucket
        String type = candle.getInstrumentType() != null ? candle.getInstrumentType().toUpperCase() : "";
        if ("EQUITY".equals(type) || ("INDEX".equals(type) && family.getEquity() == null)) {
            family.setEquity(candle);
        } else if ("FUTURE".equals(type)) {
            // Only one future (nearest) kept; replace if expiry earlier
            InstrumentCandle existing = family.getFutures() != null && !family.getFutures().isEmpty() ? family.getFutures().get(0) : null;
            if (existing == null) {
                family.getFutures().add(candle);
            } else if (existing.getExpiry() == null || (candle.getExpiry() != null && candle.getExpiry().compareTo(existing.getExpiry()) < 0)) {
                family.getFutures().set(0, candle);
            }
        } else if ("OPTION".equals(type)) {
            // Cap at 4 options
            if (family.getOptions().size() < 4) {
                family.getOptions().add(candle);
            } else {
                // Replace worst-fit (e.g., farthest strike from equity close)
                replaceOptionIfBetter(family, candle);
            }
        }

        // Recompute aggregated metrics (basic set)
        family.setAggregatedMetrics(computeAggregatedMetrics(family));
        family.setTotalInstrumentsCount(family.calculateTotalCount());
        return family;
    }

    private void replaceOptionIfBetter(FamilyEnrichedData family, InstrumentCandle candidate) {
        try {
            Double spot = family.getEquity() != null ? family.getEquity().getClose() : null;
            if (spot == null || candidate.getStrikePrice() == null) {
                return;
            }
            int worstIdx = -1;
            double worstDist = -1;
            for (int i = 0; i < family.getOptions().size(); i++) {
                InstrumentCandle opt = family.getOptions().get(i);
                if (opt.getStrikePrice() == null) continue;
                double dist = Math.abs(opt.getStrikePrice() - spot);
                if (dist > worstDist) {
                    worstDist = dist;
                    worstIdx = i;
                }
            }
            double candDist = Math.abs(candidate.getStrikePrice() - spot);
            if (worstIdx >= 0 && candDist < worstDist) {
                family.getOptions().set(worstIdx, candidate);
            }
        } catch (Exception ignored) {
        }
    }

    private FamilyAggregatedMetrics computeAggregatedMetrics(FamilyEnrichedData family) {
        long totalVol = 0;
        long eqVol = 0, futVol = 0, optVol = 0;
        Double spot = null, fut = null;
        if (family.getEquity() != null && family.getEquity().getVolume() != null) {
            eqVol = family.getEquity().getVolume();
            totalVol += eqVol;
            spot = family.getEquity().getClose();
        }
        if (family.getFutures() != null && !family.getFutures().isEmpty()) {
            InstrumentCandle f = family.getFutures().get(0);
            if (f.getVolume() != null) {
                futVol = f.getVolume();
                totalVol += futVol;
            }
            fut = f.getClose();
        }
        if (family.getOptions() != null) {
            for (InstrumentCandle o : family.getOptions()) {
                if (o.getVolume() != null) {
                    optVol += o.getVolume();
                    totalVol += o.getVolume();
                }
            }
        }
        Double basis = (spot != null && fut != null) ? (fut - spot) : null;
        Double basisPct = (spot != null && fut != null && spot != 0) ? ((fut - spot) / spot * 100.0) : null;
        return FamilyAggregatedMetrics.builder()
            .totalVolume(totalVol)
            .equityVolume(eqVol)
            .futuresVolume(futVol)
            .optionsVolume(optVol)
            .spotPrice(spot)
            .nearMonthFuturePrice(fut)
            .futuresBasis(basis)
            .futuresBasisPercent(basisPct)
            .calculatedAt(System.currentTimeMillis())
            .build();
    }
    
    /**
     * Emit per-instrument candles to timeframe-specific topics
     * NEW: Uses InstrumentCandle instead of Candlestick
     */
    private void emitPerInstrumentCandles(KStream<String, InstrumentState> stateStream) {
        // Filter states that have at least one complete window
        KStream<String, InstrumentState> completeStates = stateStream
            .filter((key, state) -> state.hasAnyCompleteWindow());

        // Create streams for each timeframe
        for (Timeframe timeframe : new Timeframe[]{Timeframe.ONE_MIN, Timeframe.TWO_MIN, Timeframe.THREE_MIN,
                                                     Timeframe.FIVE_MIN, Timeframe.FIFTEEN_MIN, Timeframe.THIRTY_MIN}) {
            KStream<String, InstrumentCandle> candleStream = completeStates
                .mapValues((readOnlyKey, state) -> state.extractFinalizedCandle(timeframe))
                .filter((key, candle) -> candle != null && candle.isValid());

            String topic = getCandleTopicForTimeframe(timeframe.getLabel());
            if (topic != null) {
                candleStream.to(topic, Produced.with(
                    Serdes.String(),
                    InstrumentCandle.serde()
                ));

                log.debug("✅ [INSTRUMENT] Configured finalized candle stream: {} → {}", timeframe.getLabel(), topic);
            }
        }
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
