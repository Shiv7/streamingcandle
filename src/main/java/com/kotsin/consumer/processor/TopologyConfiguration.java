package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.FamilyAggregatedMetrics;
import com.kotsin.consumer.model.FamilyEnrichedData;
import com.kotsin.consumer.model.InstrumentCandle;
import com.kotsin.consumer.model.MicrostructureData;
import com.kotsin.consumer.model.OpenInterest;
import com.kotsin.consumer.model.OrderbookDepthData;
import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.monitoring.Timeframe;
import java.util.List;
// FIX: use the canonical metrics class (the one that has incCandleDrop)
import com.kotsin.consumer.metrics.StreamMetrics;

import com.kotsin.consumer.service.InstrumentKeyResolver;
import com.kotsin.consumer.service.TradingHoursValidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.common.serialization.Serdes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Properties;

/**
 * Configuration service for Kafka Streams topologies
 *
 * SINGLE RESPONSIBILITY: Topology building and configuration
 * EXTRACTED FROM: UnifiedMarketDataProcessor (God class refactoring)
 *
 * FIXED ISSUES:
 * 1. Added metadata setting in instrument aggregation
 * 2. Fixed family aggregation to actually assemble families
 * 3. Added family metrics calculation
 * 4. Added trading hours filter
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TopologyConfiguration {

    private final KafkaConfig kafkaConfig;
    private final StreamMetrics metrics;
    private final InstrumentKeyResolver keyResolver;  // ✅ ADDED for metadata
    private final TradingHoursValidationService tradingHoursService;  // ✅ ADDED for filtering

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

    /**
     * Create per-instrument candle generation topology
     */
    public StreamsBuilder createInstrumentTopology() {
        log.info("🗃️ Building per-instrument candle topology");

        Properties props = kafkaConfig.getStreamProperties(appIdPrefix + "-instrument");
        props.put("auto.offset.reset", "earliest");
        StreamsBuilder builder = new StreamsBuilder();

        // Add state store for delta volume calculation
        String deltaVolumeStoreName = "instrument-delta-volume-store";
        builder.addStateStore(
            Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(deltaVolumeStoreName),
                Serdes.String(),
                Serdes.Integer()
            )
        );

        // Read tick stream
        KStream<String, TickData> ticksRaw = builder.stream(
            ticksTopic,
            Consumed.with(Serdes.String(), TickData.serde())
        );

        // Transform cumulative to delta volume
        KStream<String, TickData> ticks = ticksRaw
            .transform(
                () -> new com.kotsin.consumer.transformers.CumToDeltaTransformer(deltaVolumeStoreName),
                deltaVolumeStoreName
            )
            .filter((key, tick) -> tick != null && tick.getDeltaVolume() != null);

        // Read orderbook stream for enrichment
        KStream<String, OrderBookSnapshot> orderbookStream = builder.stream(
            orderbookTopic,
            Consumed.with(Serdes.String(), OrderBookSnapshot.serde())
        );

        // Re-key orderbook by token
        KTable<String, OrderBookSnapshot> orderbookTable = orderbookStream
            .selectKey((k, v) -> v != null && v.getToken() != null ? v.getToken() : k)
            .toTable(Materialized.with(Serdes.String(), OrderBookSnapshot.serde()));

        // Re-key ticks by instrument key
        KStream<String, TickData> instrumentKeyed = ticks
            .selectKey((k, v) -> v.getScripCode());

        // Define windowing
        // BALANCED APPROACH: 30s grace period (delays candle by max 30s)
        // Trade-off: Captures 95%+ of late data without excessive delay
        // Note: suppress().untilWindowCloses() delays emission by grace period
        TimeWindows windows = TimeWindows
            .ofSizeAndGrace(Duration.ofMinutes(1), Duration.ofSeconds(30));

        // ✅ FIX #1: Aggregate with metadata setting AND trading hours filter
        KTable<Windowed<String>, InstrumentState> aggregated = instrumentKeyed
            .filter((key, tick) -> {
                boolean withinHours = tradingHoursService.withinTradingHours(tick);
                if (!withinHours) {
                    log.debug("⏰ Filtered out tick outside trading hours: scripCode={}", tick.getScripCode());
                }
                return withinHours;
            })
            .groupByKey(Grouped.with(Serdes.String(), TickData.serde()))
            .windowedBy(windows)
            .aggregate(
                InstrumentState::new,
                (scripCode, tick, state) -> {
                    // ✅ SET METADATA ON FIRST TICK (before adding tick!)
                    if (state.getScripCode() == null) {
                        try {
                            String instrumentType = keyResolver.getInstrumentType(tick);
                            String familyKey = keyResolver.getFamilyKey(tick);
                            state.setInstrumentType(instrumentType);
                            state.setUnderlyingEquityScripCode(familyKey);

                            log.debug("🔧 Metadata set: scrip={}, type={}, family={}",
                                scripCode, instrumentType, familyKey);
                        } catch (Exception e) {
                            log.error("❌ Failed to set metadata for scripCode={}", scripCode, e);
                            // Continue with tick processing even if metadata fails
                        }
                    }

                    // Add tick to state
                    state.addTick(tick);
                    return state;
                },
                Materialized.<String, InstrumentState, WindowStore<Bytes, byte[]>>as("instrument-state-store")
                    .withKeySerde(Serdes.String())
                    .withValueSerde(new org.springframework.kafka.support.serializer.JsonSerde<>(InstrumentState.class))
            );

        // Suppress until window closes
        KStream<String, InstrumentState> stateStream = aggregated
            .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
            .toStream()
            .peek((windowedKey, state) -> {
                long windowEnd = windowedKey.window().end();
                state.forceCompleteWindows(windowEnd);
                log.info("🎉 Window closed: scrip={} windowEnd={} hasComplete={}",
                    state.getScripCode(), windowEnd, state.hasAnyCompleteWindow());
            })
            .selectKey((windowedKey, state) -> windowedKey.key());

        // Enrich with orderbook data
        KStream<String, InstrumentState> enrichedState = stateStream
            .leftJoin(
                orderbookTable,
                (state, orderbook) -> {
                    if (orderbook != null && orderbook.isValid()) {
                        state.addOrderbook(orderbook);
                        log.debug("✅ Orderbook joined for scripCode={}", state.getScripCode());
                    } else if (orderbook != null) {
                        log.debug("⚠️ Invalid orderbook for scripCode={}", state.getScripCode());
                    }
                    return state;
                }
            );

        // 🚀 CRITICAL FIX: Extract candles IMMEDIATELY before state gets modified
        // This prevents the race condition where new ticks reset accumulators
        if (candlesOutputEnabled) {
            log.info("📤 Setting up immediate candle extraction to prevent race condition");

            // Extract and emit candles for each timeframe RIGHT NOW
            for (Timeframe tf : Timeframe.values()) {
                final String tfLabel = tf.getLabel();
                String topic = getCandleTopicForTimeframe(tfLabel);

                if (topic != null) {
                    log.info("📤 Setting up candle emission for {} → {}", tfLabel, topic);

                    enrichedState
                        .mapValues(state -> {
                            // Extract candle IMMEDIATELY while accumulator is still complete
                            InstrumentCandle candle = state.extractFinalizedCandle(tf);
                            if (candle != null) {
                                log.info("📤 Extracted candle: tf={} scrip={} vol={} complete={} type={} family={}",
                                    tfLabel, candle.getScripCode(), candle.getVolume(),
                                    candle.getIsComplete(), candle.getInstrumentType(),
                                    candle.getUnderlyingEquityScripCode());
                            } else {
                                log.warn("❌ Failed to extract candle: tf={} scrip={} hasComplete={}",
                                    tfLabel, state.getScripCode(), state.hasAnyCompleteWindow());
                            }
                            return candle;
                        })
                        .filter((k, candle) -> {
                            if (candle == null) {
                                log.debug("⚠️ Filtered null candle for key={}", k);
                                return false;
                            }
                            if (!candle.isValid()) {
                                log.warn("⚠️ Filtered invalid candle: scrip={} vol={} ohlc=[{},{},{},{}]",
                                    candle.getScripCode(), candle.getVolume(),
                                    candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose());
                                metrics.incCandleDrop(tfLabel);
                                return false;
                            }
                            return true;
                        })
                        .peek((k, candle) -> {
                            log.info("📤 EMITTING: tf={} scrip={} vol={} type={} family={} → {}",
                                tfLabel, candle.getScripCode(), candle.getVolume(),
                                candle.getInstrumentType(), candle.getUnderlyingEquityScripCode(), topic);
                            metrics.incCandleEmit(tfLabel);
                        })
                        .to(topic, Produced.with(Serdes.String(), InstrumentCandle.serde()));
                } else {
                    log.warn("⚠️ No topic configured for timeframe: {}", tfLabel);
                }
            }
        }

        return builder;
    }

    /**
     * Get candle topic for timeframe
     */
    private String getCandleTopicForTimeframe(String timeframe) {
        switch (timeframe) {
            case "1m": return candle1mTopic;
            case "2m": return candle2mTopic;
            case "3m": return candle3mTopic;
            case "5m": return candle5mTopic;
            case "15m": return candle15mTopic;
            case "30m": return candle30mTopic;
            default:
                log.warn("⚠️ Unknown timeframe: {}", timeframe);
                return null;
        }
    }

    /**
     * Create family-structured aggregation topology
     *
     * ✅ FIX #2: Actually assemble families instead of returning empty builder
     */
    public StreamsBuilder createFamilyTopology(String timeframeLabel, String sourceTopic, String sinkTopic, Duration windowSize) {
        log.info("🗃️ Building family-structured topology for {}", timeframeLabel);

        Properties props = kafkaConfig.getStreamProperties(appIdPrefix + "-family-" + timeframeLabel);
        props.put("auto.offset.reset", "earliest");
        StreamsBuilder builder = new StreamsBuilder();

        // Read candle stream
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

        // Enrich candles with OI data
        KStream<String, InstrumentCandle> enrichedCandles = enrichCandles(rekeyedByToken, oiTable);

        // Map to family key and aggregate
        KStream<String, InstrumentCandle> keyedByFamily = enrichedCandles
            .selectKey((scripOrToken, candle) -> {
                String familyKey = candle.getUnderlyingEquityScripCode() != null
                    ? candle.getUnderlyingEquityScripCode()
                    : candle.getScripCode();

                log.debug("🔑 Keying candle by family: scrip={} type={} familyKey={}",
                    candle.getScripCode(), candle.getInstrumentType(), familyKey);

                return familyKey;
            })
            .repartition(Repartitioned.with(Serdes.String(), InstrumentCandle.serde()));

        // BALANCED APPROACH: 30s grace period for family aggregation
        // Aligns with instrument stream grace period
        TimeWindows windows = TimeWindows.ofSizeAndGrace(windowSize, Duration.ofSeconds(30));

        // ✅ FIX #2: ACTUALLY ASSEMBLE FAMILIES
        KTable<Windowed<String>, FamilyEnrichedData> aggregated = keyedByFamily
            .groupByKey(Grouped.with(Serdes.String(), InstrumentCandle.serde()))
            .windowedBy(windows)
            .aggregate(
                () -> {
                    // Initialize with empty lists
                    FamilyEnrichedData family = FamilyEnrichedData.builder()
                        .futures(new ArrayList<>())
                        .options(new ArrayList<>())
                        .build();
                    log.debug("🏗️ Created new family aggregate");
                    return family;
                },
                (familyKey, candle, family) -> {
                    // Set family metadata on first candle
                    if (family.getFamilyKey() == null) {
                        family.setFamilyKey(familyKey);
                        family.setFamilyName(familyKey);  // Could lookup full name from service
                        family.setInstrumentType("EQUITY_FAMILY");
                        log.info("🏗️ Initialized family: key={} name={}", familyKey, familyKey);
                    }

                    // Set/update window times
                    if (family.getWindowStartMillis() == null && candle.getWindowStartMillis() != null) {
                        family.setWindowStartMillis(candle.getWindowStartMillis());
                    }
                    if (candle.getWindowEndMillis() != null) {
                        family.setWindowEndMillis(candle.getWindowEndMillis());
                    }

                    // Add instrument to family based on type
                    String instrType = candle.getInstrumentType();
                    if (instrType != null) {
                        switch (instrType) {
                            case "EQUITY":
                                log.info("➕ Adding EQUITY to family: familyKey={} scripCode={}",
                                    familyKey, candle.getScripCode());
                                family.setEquity(candle);
                                break;

                            case "FUTURE":
                                log.info("➕ Adding FUTURE to family: familyKey={} scripCode={} expiry={}",
                                    familyKey, candle.getScripCode(), candle.getExpiry());
                                if (family.getFutures() == null) {
                                    family.setFutures(new ArrayList<>());
                                }
                                family.getFutures().add(candle);
                                break;

                            case "OPTION":
                                log.info("➕ Adding OPTION to family: familyKey={} scripCode={} type={} strike={}",
                                    familyKey, candle.getScripCode(), candle.getOptionType(), candle.getStrikePrice());
                                if (family.getOptions() == null) {
                                    family.setOptions(new ArrayList<>());
                                }
                                family.getOptions().add(candle);
                                break;

                            default:
                                log.warn("⚠️ Unknown instrument type: {} for scripCode={}",
                                    instrType, candle.getScripCode());
                        }
                    } else {
                        log.warn("⚠️ Candle has null instrumentType: scripCode={}", candle.getScripCode());
                    }

                    return family;
                },
                Materialized.with(Serdes.String(), FamilyEnrichedData.serde())
            );

        // ✅ FIX #3: Calculate aggregated metrics
        KStream<String, FamilyEnrichedData> out = aggregated
            .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
            .toStream()
            .selectKey((windowedKey, family) -> windowedKey.key())
            .mapValues(family -> {
                if (family != null) {
                    family.setProcessingTimestamp(System.currentTimeMillis());
                    family.setTimeframe(timeframeLabel);

                    // Calculate aggregated metrics
                    FamilyAggregatedMetrics famMetrics = calculateFamilyMetrics(family);
                    family.setAggregatedMetrics(famMetrics);

                    // --- NEW: Calculate and set volume-weighted metrics ---
                    calculateVolumeWeightedMetrics(family);


                    // Set total count (derived)
                    family.setTotalInstrumentsCount(
                        (family.getEquity() != null ? 1 : 0) +
                        (family.getFutures() != null ? family.getFutures().size() : 0) +
                        (family.getOptions() != null ? family.getOptions().size() : 0)
                    );

                    // NOTE: Removed non-existent setters setFuturesCount/setOptionsCount.
                    // If you need explicit counts in the payload, compute and expose via getters or metrics.
                    log.info("📊 Family complete: key={} tf={} equity={} futures={} options={} totalVol={}",
                        family.getFamilyKey(), timeframeLabel,
                        family.getEquity() != null,
                        (family.getFutures() != null ? family.getFutures().size() : 0),
                        (family.getOptions() != null ? family.getOptions().size() : 0),
                        famMetrics.getTotalVolume());
                }
                return family;
            });

        out.to(sinkTopic, Produced.with(Serdes.String(), FamilyEnrichedData.serde()));

        return builder;
    }

    /**
     * Calculate aggregated metrics for a family
     *
     * ✅ FIX #3: Proper metrics calculation
     */
    private FamilyAggregatedMetrics calculateFamilyMetrics(FamilyEnrichedData family) {
        long totalVolume = 0;
        long equityVolume = 0;
        long futuresVolume = 0;
        long optionsVolume = 0;

        // --- OI Metrics Initialization ---
        Long totalOpenInterest = 0L;
        Long futuresOI = 0L;
        Long callsOI = 0L;
        Long putsOI = 0L;
        Long futuresOIChange = 0L;
        Long callsOIChange = 0L;
        Long putsOIChange = 0L;

        Double spotPrice = null;
        Double nearMonthFuturePrice = null;
        String nearMonthExpiry = null;

        // Equity metrics
        if (family.getEquity() != null) {
            InstrumentCandle equity = family.getEquity();
            equityVolume = equity.getVolume() != null ? equity.getVolume() : 0;
            spotPrice = equity.getClose();
            totalVolume += equityVolume;
            if (equity.getOpenInterest() != null) {
                totalOpenInterest += equity.getOpenInterest();
            }
        }

        // Futures metrics
        if (family.getFutures() != null && !family.getFutures().isEmpty()) {
            for (InstrumentCandle future : family.getFutures()) {
                long vol = future.getVolume() != null ? future.getVolume() : 0;
                futuresVolume += vol;
                totalVolume += vol;

                // --- OI Aggregation for Futures ---
                if (future.getOpenInterest() != null) {
                    totalOpenInterest += future.getOpenInterest();
                    futuresOI += future.getOpenInterest();
                }
                if (future.getOiChange() != null) {
                    futuresOIChange += future.getOiChange();
                }

                // Find near month future (earliest expiry)
                if (nearMonthExpiry == null ||
                    (future.getExpiry() != null && future.getExpiry().compareTo(nearMonthExpiry) < 0)) {
                    nearMonthExpiry = future.getExpiry();
                    nearMonthFuturePrice = future.getClose();
                }
            }
        }

        // Options metrics
        long callsVolume = 0;
        long putsVolume = 0;
        if (family.getOptions() != null && !family.getOptions().isEmpty()) {
            for (InstrumentCandle option : family.getOptions()) {
                long vol = option.getVolume() != null ? option.getVolume() : 0;
                optionsVolume += vol;
                totalVolume += vol;

                // --- OI Aggregation for Options ---
                if (option.getOpenInterest() != null) {
                    totalOpenInterest += option.getOpenInterest();
                }

                if ("CE".equals(option.getOptionType())) {
                    callsVolume += vol;
                    if (option.getOpenInterest() != null) {
                        callsOI += option.getOpenInterest();
                    }
                    if (option.getOiChange() != null) {
                        callsOIChange += option.getOiChange();
                    }
                } else if ("PE".equals(option.getOptionType())) {
                    putsVolume += vol;
                    if (option.getOpenInterest() != null) {
                        putsOI += option.getOpenInterest();
                    }
                    if (option.getOiChange() != null) {
                        putsOIChange += option.getOiChange();
                    }
                }
            }
        }

        // Calculate ratios
        Double putCallVolumeRatio = callsVolume > 0 ? (double) putsVolume / callsVolume : null;
        Double putCallRatio = (callsOI != null && callsOI > 0) ? (putsOI.doubleValue() / callsOI.doubleValue()) : null;

        // Calculate futures basis
        Double futuresBasis = null;
        Double futuresBasisPercent = null;
        if (spotPrice != null && nearMonthFuturePrice != null && spotPrice > 0) {
            futuresBasis = nearMonthFuturePrice - spotPrice;
            futuresBasisPercent = (futuresBasis / spotPrice) * 100.0;
        }

        return FamilyAggregatedMetrics.builder()
            .totalVolume(totalVolume)
            .equityVolume(equityVolume)
            .futuresVolume(futuresVolume)
            .optionsVolume(optionsVolume)
            // --- Add OI Metrics to Builder ---
            .totalOpenInterest(totalOpenInterest > 0 ? totalOpenInterest : null)
            .futuresOI(futuresOI > 0 ? futuresOI : null)
            .callsOI(callsOI > 0 ? callsOI : null)
            .putsOI(putsOI > 0 ? putsOI : null)
            .futuresOIChange(futuresOIChange != 0 ? futuresOIChange : null)
            .callsOIChange(callsOIChange != 0 ? callsOIChange : null)
            .putsOIChange(putsOIChange != 0 ? putsOIChange : null)
            .putCallRatio(putCallRatio)
            .spotPrice(spotPrice)
            .nearMonthFuturePrice(nearMonthFuturePrice)
            .futuresBasis(futuresBasis)
            .futuresBasisPercent(futuresBasisPercent)
            .activeFuturesCount(family.getFutures() != null ? family.getFutures().size() : 0)
            .activeOptionsCount(family.getOptions() != null ? family.getOptions().size() : 0)
            .nearMonthExpiry(nearMonthExpiry)
            .putCallVolumeRatio(putCallVolumeRatio)
            .calculatedAt(System.currentTimeMillis())
            .build();
    }

    /**
     * Calculate and set volume-weighted metrics for microstructure and order book
     */
    private void calculateVolumeWeightedMetrics(FamilyEnrichedData family) {
        double totalVolume = family.getAggregatedMetrics().getTotalVolume() != null ? family.getAggregatedMetrics().getTotalVolume() : 0.0;
        if (totalVolume == 0) {
            return; // Avoid division by zero
        }

        double weightedOfi = 0.0;
        double weightedVpin = 0.0;
        double weightedDepthImbalance = 0.0;
        double weightedSpread = 0.0;

        List<InstrumentCandle> allInstruments = new ArrayList<>();
        if (family.getEquity() != null) allInstruments.add(family.getEquity());
        if (family.getFutures() != null) allInstruments.addAll(family.getFutures());
        if (family.getOptions() != null) allInstruments.addAll(family.getOptions());

        for (InstrumentCandle instrument : allInstruments) {
            long instrumentVolume = instrument.getVolume() != null ? instrument.getVolume() : 0;
            double weight = instrumentVolume / totalVolume;

            if (instrument.getMicrostructure() != null) {
                MicrostructureData micro = instrument.getMicrostructure();
                if (micro.getOfi() != null) weightedOfi += micro.getOfi() * weight;
                if (micro.getVpin() != null) weightedVpin += micro.getVpin() * weight;
                if (micro.getDepthImbalance() != null) weightedDepthImbalance += micro.getDepthImbalance() * weight;
            }
            if (instrument.getOrderbookDepth() != null && instrument.getOrderbookDepth().getSpread() != null) {
                weightedSpread += instrument.getOrderbookDepth().getSpread() * weight;
            }
        }

        // Create and set aggregated microstructure data
        MicrostructureData aggregatedMicrostructure = MicrostructureData.builder()
            .ofi(weightedOfi)
            .vpin(weightedVpin)
            .depthImbalance(weightedDepthImbalance)
            .isComplete(true)
            .build();
        family.setMicrostructure(aggregatedMicrostructure);

        // Create and set aggregated order book data
        OrderbookDepthData aggregatedOrderbook = OrderbookDepthData.builder()
            .spread(weightedSpread)
            .isComplete(true)
            .build();
        family.setOrderbookDepth(aggregatedOrderbook);
    }

    /**
     * Build OI table with stateful oiChange calculation
     */
    private KTable<String, OpenInterest> buildOiTable(StreamsBuilder builder) {
        KStream<String, OpenInterest> oiStream = builder.stream(
            oiTopic,
            Consumed.with(Serdes.String(), OpenInterest.serde())
        );

        return oiStream
            .selectKey((k, oi) -> oi != null && oi.getToken() != 0 ? String.valueOf(oi.getToken()) : k)
            .toTable(Materialized.<String, OpenInterest, KeyValueStore<Bytes, byte[]>>as("oi-table")
                .withKeySerde(Serdes.String())
                .withValueSerde(OpenInterest.serde()));
    }

    /**
     * Enrich candles with OI data
     */
    private KStream<String, InstrumentCandle> enrichCandles(
        KStream<String, InstrumentCandle> candles,
        KTable<String, OpenInterest> oiTable
    ) {
        return candles.leftJoin(
            oiTable,
            (candle, oi) -> {
                if (candle != null && oi != null) {
                    candle.setOpenInterest(oi.getOpenInterest());
                    candle.setOiChange(oi.getOiChange());
                    log.debug("✅ OI enriched: scrip={} oi={} oiChange={}",
                        candle.getScripCode(), oi.getOpenInterest(), oi.getOiChange());
                }
                return candle;
            }
        );
    }
}
