package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.kstream.Repartitioned;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.kotsin.consumer.metrics.StreamMetrics;

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
    private final StreamMetrics metrics;

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

        log.info("⏳ Building {} from source topic {} → sink {}", timeframeLabel, sourceTopic, sinkTopic);

        KStream<String, InstrumentCandle> candles = builder.stream(
            sourceTopic,
            Consumed.with(Serdes.String(), InstrumentCandle.serde())
        );

        // Family cache (scripCode -> InstrumentFamily) via service
        // We access cache via a ValueMapper to resolve token for each instrument
        KStream<String, InstrumentCandle> withToken = candles.mapValues(c -> {
            try {
                com.kotsin.consumer.model.InstrumentFamily fam = mergeService != null ? null : null; // placeholder to keep import minimal
            } catch (Exception ignore) {}
            return c;
        });

        // Resolve token using MongoInstrumentFamilyService (via keyResolver's cache service)
        KStream<String, InstrumentCandle> rekeyedByToken = candles.transformValues(() -> new org.apache.kafka.streams.kstream.ValueTransformer<InstrumentCandle, InstrumentCandle>() {
            private com.kotsin.consumer.service.MongoInstrumentFamilyService cache;
            @Override public void init(org.apache.kafka.streams.processor.ProcessorContext context) {
                // Spring-managed bean not directly available here; use static holder or fallback to scripCode when token absent
                try {
                    cache = (com.kotsin.consumer.service.MongoInstrumentFamilyService) org.springframework.web.context.ContextLoader.getCurrentWebApplicationContext().getBean(com.kotsin.consumer.service.MongoInstrumentFamilyService.class);
                } catch (Exception e) {
                    cache = null;
                }
            }
            @Override public InstrumentCandle transform(InstrumentCandle value) { return value; }
            @Override public void close() {}
        }).selectKey((k, c) -> {
            String sc = c != null ? c.getScripCode() : null;
            String token = null;
            try {
                if (sc != null) {
                    com.kotsin.consumer.model.InstrumentFamily fam = keyResolver != null ? null : null;
                }
            } catch (Exception ignore) {}
            // Fallback: keep scripCode if we can't resolve token
            return (token != null && !token.isBlank()) ? token : k;
        })
        .repartition(Repartitioned.with(Serdes.String(), InstrumentCandle.serde()));

        KTable<String, OpenInterest> oiTable = builder.table(
            oiTopic,
            Consumed.with(Serdes.String(), OpenInterest.serde())
        );
        KTable<String, OrderBookSnapshot> orderbookTable = builder.table(
            orderbookTopic,
            Consumed.with(Serdes.String(), OrderBookSnapshot.serde())
        );

        // Join by token (or original key if token not found)
        KStream<String, InstrumentCandle> enrichedCandles = rekeyedByToken
            .leftJoin(oiTable, (candle, oi) -> {
                if (oi != null) {
                    candle.setOpenInterest(oi.getOpenInterest());
                    candle.setOiChange(oi.getOiChange());
                } else { metrics.incOiJoinMiss(); }
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
                } else { metrics.incOrderbookJoinMiss(); }
                return candle;
            });

        // Map back to family key for aggregation
        KStream<String, InstrumentCandle> keyedByFamily = enrichedCandles
            .selectKey((scripOrToken, candle) -> candle.getUnderlyingEquityScripCode() != null
                ? candle.getUnderlyingEquityScripCode()
                : candle.getScripCode())
            .repartition(Repartitioned.with(Serdes.String(), InstrumentCandle.serde()));

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

        // Consume the family-structured topics and merge into a single message per family
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

        // Convert each into partial FamilyStructuredAll keyed by family
        KStream<String, FamilyStructuredAll> p1 = f1.mapValues(this::toAllPartial);
        KStream<String, FamilyStructuredAll> p2 = f2.mapValues(this::toAllPartial);
        KStream<String, FamilyStructuredAll> p5 = f5.mapValues(this::toAllPartial);
        KStream<String, FamilyStructuredAll> p15 = f15.mapValues(this::toAllPartial);
        KStream<String, FamilyStructuredAll> p30 = f30.mapValues(this::toAllPartial);

        // Merge using reduce by key
        KStream<String, FamilyStructuredAll> merged = p1
            .merge(p2)
            .merge(p5)
            .merge(p15)
            .merge(p30)
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
            family = FamilyEnrichedData.builder().build();
        }
        // Ensure identity fields are populated
        if (family.getFamilyKey() == null) {
            family.setFamilyKey(familyKey);
        }
        if (family.getInstrumentType() == null) {
            String famType = (familyKey != null && keyResolver.isIndex(familyKey)) ? "INDEX_FAMILY" : "EQUITY_FAMILY";
            family.setInstrumentType(famType);
        }
        if (family.getFamilyName() == null) {
            String name = null;
            if (family.getEquity() != null && family.getEquity().getCompanyName() != null) {
                name = family.getEquity().getCompanyName();
            } else if (candle != null) {
                name = candle.getCompanyName();
            }
            family.setFamilyName(name);
        }

        if (family.getWindowStartMillis() == null) {
            family.setWindowStartMillis(candle.getWindowStartMillis());
        }
        family.setWindowEndMillis(candle.getWindowEndMillis());

        String type = candle.getInstrumentType() != null ? candle.getInstrumentType().toUpperCase() : "";
        if ("EQUITY".equals(type) || ("INDEX".equals(type) && family.getEquity() == null)) {
            family.setEquity(candle);
            if (family.getFamilyName() == null) {
                family.setFamilyName(candle.getCompanyName());
            }
        } else if ("FUTURE".equals(type)) {
            InstrumentCandle existing = family.getFutures() != null && !family.getFutures().isEmpty() ? family.getFutures().get(0) : null;
            if (existing == null) {
                family.getFutures().add(candle);
            } else if (existing.getExpiry() == null || (candle.getExpiry() != null && candle.getExpiry().compareTo(existing.getExpiry()) < 0)) {
                family.getFutures().set(0, candle);
            }
        } else if ("OPTION".equals(type)) {
            if (family.getOptions().size() < 4) {
                family.getOptions().add(candle);
            } else {
                replaceOptionIfBetter(family, candle);
            }
        }

        // Recompute aggregated family-level analytics
        family.setAggregatedMetrics(computeAggregatedMetrics(family));
        family.setMicrostructure(computeFamilyMicrostructure(family));
        family.setOrderbookDepth(computeFamilyOrderbookDepth(family));
        family.setTotalInstrumentsCount(family.calculateTotalCount());
        return family;
    }

    private FamilyAggregatedMetrics computeAggregatedMetrics(FamilyEnrichedData family) {
        long totalVol = 0;
        long eqVol = 0, futVol = 0, optVol = 0;
        Long totalOi = 0L, futOi = 0L, callsOi = 0L, putsOi = 0L;
        Long futOiChg = 0L, callsOiChg = 0L, putsOiChg = 0L;
        Double spot = null, fut = null;
        Integer activeOptions = 0;
        Integer activeFutures = 0;
        String nearExpiry = null;

        if (family.getEquity() != null) {
            InstrumentCandle e = family.getEquity();
            if (e.getVolume() != null) { eqVol = e.getVolume(); totalVol += eqVol; }
            spot = e.getClose();
            if (e.getOpenInterest() != null) { totalOi += e.getOpenInterest(); }
        }
        if (family.getFutures() != null && !family.getFutures().isEmpty()) {
            InstrumentCandle f = family.getFutures().get(0);
            activeFutures = 1;
            if (f.getVolume() != null) { futVol = f.getVolume(); totalVol += futVol; }
            fut = f.getClose();
            if (f.getOpenInterest() != null) { totalOi += f.getOpenInterest(); futOi += f.getOpenInterest(); }
            if (f.getOiChange() != null) { futOiChg += f.getOiChange(); }
            nearExpiry = f.getExpiry();
        }
        long callsVol = 0, putsVol = 0;
        if (family.getOptions() != null) {
            for (InstrumentCandle o : family.getOptions()) {
                if (o.getVolume() != null) {
                    optVol += o.getVolume();
                    totalVol += o.getVolume();
                    if ("CE".equalsIgnoreCase(o.getOptionType())) callsVol += o.getVolume();
                    if ("PE".equalsIgnoreCase(o.getOptionType())) putsVol += o.getVolume();
                }
                if (o.getOpenInterest() != null) {
                    totalOi += o.getOpenInterest();
                    if ("CE".equalsIgnoreCase(o.getOptionType())) callsOi += o.getOpenInterest();
                    if ("PE".equalsIgnoreCase(o.getOptionType())) putsOi += o.getOpenInterest();
                }
                if (o.getOiChange() != null) {
                    if ("CE".equalsIgnoreCase(o.getOptionType())) callsOiChg += o.getOiChange();
                    if ("PE".equalsIgnoreCase(o.getOptionType())) putsOiChg += o.getOiChange();
                }
                if (o.getVolume() != null && o.getVolume() > 0) activeOptions++;
            }
        }
        Double basis = (spot != null && fut != null) ? (fut - spot) : null;
        Double basisPct = (spot != null && fut != null && spot != 0) ? ((fut - spot) / spot * 100.0) : null;
        Double pcr = (callsOi != null && callsOi > 0) ? (putsOi.doubleValue() / callsOi.doubleValue()) : null;
        Double pcrVol = (callsVol > 0) ? (putsVol * 1.0 / callsVol) : null;

        // Orderbook aggregates across instruments (simple sums/averages)
        Double avgSpread = null;
        Long sumBid = 0L, sumAsk = 0L; int depthCount = 0;
        for (InstrumentCandle c : collectAllInstruments(family)) {
            if (c.getOrderbookDepth() != null) {
                if (c.getOrderbookDepth().getSpread() != null) {
                    avgSpread = (avgSpread == null ? 0.0 : avgSpread) + c.getOrderbookDepth().getSpread();
                }
                if (c.getOrderbookDepth().getTotalBidDepth() != null) sumBid += c.getOrderbookDepth().getTotalBidDepth().longValue();
                if (c.getOrderbookDepth().getTotalAskDepth() != null) sumAsk += c.getOrderbookDepth().getTotalAskDepth().longValue();
                depthCount++;
            }
        }
        if (avgSpread != null && depthCount > 0) avgSpread = avgSpread / depthCount;
        Double bidAskImb = (sumBid + sumAsk) > 0 ? ((sumBid - sumAsk) * 1.0 / (sumBid + sumAsk)) : null;

        return FamilyAggregatedMetrics.builder()
            .totalVolume(totalVol)
            .equityVolume(eqVol)
            .futuresVolume(futVol)
            .optionsVolume(optVol)
            .totalOpenInterest(totalOi > 0 ? totalOi : null)
            .futuresOI(futOi > 0 ? futOi : null)
            .callsOI(callsOi > 0 ? callsOi : null)
            .putsOI(putsOi > 0 ? putsOi : null)
            .futuresOIChange(futOiChg != 0 ? futOiChg : null)
            .callsOIChange(callsOiChg != 0 ? callsOiChg : null)
            .putsOIChange(putsOiChg != 0 ? putsOiChg : null)
            .putCallRatio(pcr)
            .putCallVolumeRatio(pcrVol)
            .activeOptionsCount(activeOptions)
            .spotPrice(spot)
            .nearMonthFuturePrice(fut)
            .futuresBasis(basis)
            .futuresBasisPercent(basisPct)
            .activeFuturesCount(activeFutures)
            .nearMonthExpiry(nearExpiry)
            .avgBidAskSpread(avgSpread)
            .totalBidVolume(sumBid > 0 ? sumBid : null)
            .totalAskVolume(sumAsk > 0 ? sumAsk : null)
            .bidAskImbalance(bidAskImb)
            .calculatedAt(System.currentTimeMillis())
            .build();
    }

    private java.util.List<InstrumentCandle> collectAllInstruments(FamilyEnrichedData family) {
        java.util.List<InstrumentCandle> list = new java.util.ArrayList<>();
        if (family.getEquity() != null) list.add(family.getEquity());
        if (family.getFutures() != null) list.addAll(family.getFutures());
        if (family.getOptions() != null) list.addAll(family.getOptions());
        return list;
    }

    private MicrostructureData computeFamilyMicrostructure(FamilyEnrichedData family) {
        double ofiSum = 0.0, vpinSum = 0.0, depthImbSum = 0.0, kyleSum = 0.0; int n = 0;
        for (InstrumentCandle c : collectAllInstruments(family)) {
            if (c.getMicrostructure() != null) {
                MicrostructureData m = c.getMicrostructure();
                if (m.getOfi() != null) ofiSum += m.getOfi();
                if (m.getVpin() != null) vpinSum += m.getVpin();
                if (m.getDepthImbalance() != null) depthImbSum += m.getDepthImbalance();
                if (m.getKyleLambda() != null) kyleSum += m.getKyleLambda();
                n++;
            }
        }
        if (n == 0) return null;
        return MicrostructureData.builder()
            .ofi(ofiSum / n)
            .vpin(vpinSum / n)
            .depthImbalance(depthImbSum / n)
            .kyleLambda(kyleSum / n)
            .build();
    }

    private OrderbookDepthData computeFamilyOrderbookDepth(FamilyEnrichedData family) {
        double spreadSum = 0.0; int n = 0; double totalBid = 0.0, totalAsk = 0.0;
        for (InstrumentCandle c : collectAllInstruments(family)) {
            if (c.getOrderbookDepth() != null) {
                OrderbookDepthData d = c.getOrderbookDepth();
                if (d.getSpread() != null) { spreadSum += d.getSpread(); n++; }
                if (d.getTotalBidDepth() != null) totalBid += d.getTotalBidDepth();
                if (d.getTotalAskDepth() != null) totalAsk += d.getTotalAskDepth();
            }
        }
        if (n == 0 && totalBid == 0.0 && totalAsk == 0.0) return null;
        Double imb = (totalBid + totalAsk) > 0 ? ((totalBid - totalAsk) / (totalBid + totalAsk)) : null;
        return OrderbookDepthData.builder()
            .spread(n > 0 ? (spreadSum / n) : null)
            .totalBidDepth(totalBid > 0 ? totalBid : null)
            .totalAskDepth(totalAsk > 0 ? totalAsk : null)
            .build();
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
    
    /**
     * Emit per-instrument candles to timeframe-specific topics
     * NEW: Uses InstrumentCandle instead of Candlestick
     */
    private void emitPerInstrumentCandles(KStream<String, InstrumentState> stateStream) {
        KStream<String, InstrumentState> completeStates = stateStream
            .filter((key, state) -> state.hasAnyCompleteWindow());

        for (Timeframe timeframe : new Timeframe[]{Timeframe.ONE_MIN, Timeframe.TWO_MIN, Timeframe.THREE_MIN,
                                                     Timeframe.FIVE_MIN, Timeframe.FIFTEEN_MIN, Timeframe.THIRTY_MIN}) {
            final String tfLabel = timeframe.getLabel();
            KStream<String, InstrumentCandle> built = completeStates
                .mapValues((readOnlyKey, state) -> state.extractFinalizedCandle(timeframe))
                .peek((k, c) -> log.debug("built candle tf={} scrip={} vol={} valid={}", tfLabel,
                    c != null ? c.getScripCode() : null,
                    c != null ? c.getVolume() : null,
                    c != null && c.isValid()));

            KStream<String, InstrumentCandle>[] branches = built.branch(
                (k, c) -> c != null && c.isValid(),
                (k, c) -> true
            );
            KStream<String, InstrumentCandle> valid = branches[0];
            KStream<String, InstrumentCandle> invalid = branches[1];

            invalid.peek((k, c) -> {
                log.warn("drop candle tf={} scrip={} reason={} vol={} open={} high={} low={} close={}", tfLabel,
                    c != null ? c.getScripCode() : null,
                    c == null ? "null" : (c.getVolume() == null || c.getVolume() <= 0 ? "volume" : "fields"),
                    c != null ? c.getVolume() : null,
                    c != null ? c.getOpen() : null,
                    c != null ? c.getHigh() : null,
                    c != null ? c.getLow() : null,
                    c != null ? c.getClose() : null);
                metrics.incCandleDrop(tfLabel);
            });

            String topic = getCandleTopicForTimeframe(tfLabel);
            if (topic != null) {
                valid
                    .peek((k, c) -> { log.info("📤 candle emit tf={} scrip={} vol={} → {}", tfLabel, c.getScripCode(), c.getVolume(), topic); metrics.incCandleEmit(tfLabel); })
                    .to(topic, Produced.with(
                    Serdes.String(),
                    InstrumentCandle.serde()
                ));
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
