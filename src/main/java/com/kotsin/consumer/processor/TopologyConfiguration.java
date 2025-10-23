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
import com.kotsin.consumer.transformers.OiDeltaTransformer;
import java.util.List;
// FIX: use the canonical metrics class (the one that has incCandleDrop)
import com.kotsin.consumer.metrics.StreamMetrics;

import com.kotsin.consumer.service.InstrumentKeyResolver;
import com.kotsin.consumer.service.MongoInstrumentFamilyService;
import com.kotsin.consumer.model.InstrumentInfo;
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
import java.util.Comparator;
import java.util.Properties;
import java.util.stream.Collectors;

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
    private final MongoInstrumentFamilyService familyService; // ✅ Use cached master for metadata

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

    // ✅ P2-2 FIX: Externalized grace period configuration
    @Value("${unified.streams.window.grace.period.seconds:30}")
    private int gracePeriodSeconds;

    /**
     * Create per-instrument candle generation topology
     */
    public StreamsBuilder createInstrumentTopology() {
        log.info("🗃️ Building per-instrument candle topology");

        Properties props = kafkaConfig.getStreamProperties(appIdPrefix + "-instrument");
        props.put("auto.offset.reset", "latest");
        StreamsBuilder builder = new StreamsBuilder();

        // ✅ P0-4 FIX: Add state store for delta volume calculation (Long to prevent overflow)
        String deltaVolumeStoreName = "instrument-delta-volume-store";
        builder.addStateStore(
            Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(deltaVolumeStoreName),
                Serdes.String(),
                Serdes.Long()  // ✅ Changed from Integer to Long to prevent overflow on high-volume instruments
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

        String oiDeltaStoreName = "instrument-oi-delta-store";
        builder.addStateStore(
            Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(oiDeltaStoreName),
                Serdes.String(),
                Serdes.Long()
            )
        );

        KTable<String, OpenInterest> oiTable = buildOiTable(builder);

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

        // ✅ P2-2 FIX: Define windowing with configurable grace period
        // Grace period: how long to wait for late-arriving data after window closes
        // Trade-off: Higher grace = more complete data but higher latency
        // Configured via unified.streams.window.grace.period.seconds (default: 30s)
        // Note: suppress().untilWindowCloses() delays emission by grace period
        TimeWindows windows = TimeWindows
            .ofSizeAndGrace(Duration.ofMinutes(1), Duration.ofSeconds(gracePeriodSeconds));
        log.info("⏱️ Instrument stream window: size=1m, grace={}s", gracePeriodSeconds);

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

                            // Enrich derivative metadata from in-memory instrument master
                            InstrumentInfo info = familyService.getInstrumentInfoByScripCode(tick.getScripCode());
                            if (info != null) {
                                // Prefer master data when available
                                state.setExpiry(info.getExpiry());
                                state.setStrikePrice(info.getStrikeRate());
                                if (info.isOption()) {
                                    state.setOptionType(info.isCallOption() ? "CE" : "PE");
                                    state.setInstrumentType("OPTION");
                                } else if (info.isFuture()) {
                                    state.setInstrumentType("FUTURE");
                                }
                            }

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

                    KStream<String, InstrumentCandle> extractedCandles = enrichedState
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
                        });

                    KStream<String, InstrumentCandle> validCandles = extractedCandles
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
                        });

                    KStream<String, InstrumentCandle> withOi = validCandles
                        .leftJoin(
                            oiTable,
                            (candle, oi) -> {
                                if (oi != null) {
                                    candle.setOpenInterest(oi.getOpenInterest());
                                    candle.setOiChange(oi.getOiChange());
                                    candle.setOiChangePercent(oi.getOiChangePercent());
                                } else {
                                    candle.setOpenInterest(null);
                                    candle.setOiChange(null);
                                    candle.setOiChangePercent(null);
                                }
                                return candle;
                            }
                        );

                    KStream<String, InstrumentCandle> withOiDelta = withOi
                        .transformValues(() -> new OiDeltaTransformer(oiDeltaStoreName), oiDeltaStoreName);

                    withOiDelta
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
        props.put("auto.offset.reset", "latest");
        StreamsBuilder builder = new StreamsBuilder();

        // Read candle stream
        KStream<String, InstrumentCandle> candles = builder.stream(
            sourceTopic,
            Consumed.with(Serdes.String(), InstrumentCandle.serde())
        );

        // Re-key by scripCode
        KStream<String, InstrumentCandle> rekeyedByToken = candles
            .selectKey((k, c) -> c != null ? c.getScripCode() : k)
            .repartition(Repartitioned
                .with(Serdes.String(), InstrumentCandle.serde())
                .withNumberOfPartitions(3)
            );

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
            .repartition(Repartitioned
                .with(Serdes.String(), InstrumentCandle.serde())
                .withNumberOfPartitions(3)
            );

        // ✅ P2-2 FIX: Configurable grace period for family aggregation
        // Aligns with instrument stream grace period configuration
        TimeWindows windows = TimeWindows.ofSizeAndGrace(windowSize, Duration.ofSeconds(gracePeriodSeconds));
        log.info("⏱️ Family stream window: size={}, grace={}s", windowSize, gracePeriodSeconds);

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

                    // ✅ P0-3 FIX: Window times will be set from Kafka Streams window boundaries
                    // after aggregation completes, not from first-arrival candle
                    // This prevents race conditions from out-of-order arrivals

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
            .peek((windowedKey, family) -> {
                // ✅ P0-3 FIX: Set window times from Kafka Streams window boundaries
                // This prevents race conditions from out-of-order candle arrivals
                if (family != null) {
                    long windowStart = windowedKey.window().start();
                    long windowEnd = windowedKey.window().end();
                    family.setWindowStartMillis(windowStart);
                    family.setWindowEndMillis(windowEnd);
                    log.debug("🕒 Set family window from Kafka Streams: key={} start={} end={}", 
                        windowedKey.key(), windowStart, windowEnd);
                }
            })
            .selectKey((windowedKey, family) -> windowedKey.key())
            .mapValues(family -> {
                if (family != null) {
                    family.setProcessingTimestamp(System.currentTimeMillis());
                    family.setTimeframe(timeframeLabel);

                    // Calculate aggregated metrics
                    FamilyAggregatedMetrics famMetrics = calculateFamilyMetrics(family);
                    family.setAggregatedMetrics(famMetrics);

                    // --- Calculate and set volume-weighted metrics ---
                    calculateVolumeWeightedMetrics(family);

                    // ✅ FIX #3: Calculate and set imbalance bars (placeholder)
                    calculateFamilyImbalanceBars(family);

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
        double bidAskSpreadSum = 0.0;
        int bidAskSpreadCount = 0;
        double totalBidDepthSum = 0.0;
        double totalAskDepthSum = 0.0;

        // --- OI Metrics Initialization ---
        Long totalOpenInterest = 0L;
        Long futuresOI = 0L;
        Long callsOI = 0L;
        Long putsOI = 0L;
        Long futuresOIChange = 0L;
        Long callsOIChange = 0L;
        Long putsOIChange = 0L;

        // ✅ FIX #4: Separate variables for robust spot price calculation
        Double spotPriceFromEquity = null;  // Try to get from equity first
        Double nearMonthFuturePrice = null;
        String nearMonthExpiry = null;

        // Equity metrics
        if (family.getEquity() != null) {
            InstrumentCandle equity = family.getEquity();
            equityVolume = equity.getVolume() != null ? equity.getVolume() : 0;
            spotPriceFromEquity = equity.getClose();  // Store equity price separately
            totalVolume += equityVolume;
            OrderbookAccumulator equityOrderbook = extractOrderbookMetrics(equity);
            if (equityOrderbook != null) {
                bidAskSpreadSum += equityOrderbook.getSpreadSum();
                bidAskSpreadCount += equityOrderbook.getSpreadCount();
                totalBidDepthSum += equityOrderbook.getBidDepthSum();
                totalAskDepthSum += equityOrderbook.getAskDepthSum();
            }
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

                OrderbookAccumulator futureOrderbook = extractOrderbookMetrics(future);
                if (futureOrderbook != null) {
                    bidAskSpreadSum += futureOrderbook.getSpreadSum();
                    bidAskSpreadCount += futureOrderbook.getSpreadCount();
                    totalBidDepthSum += futureOrderbook.getBidDepthSum();
                    totalAskDepthSum += futureOrderbook.getAskDepthSum();
                }

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

                OrderbookAccumulator optionOrderbook = extractOrderbookMetrics(option);
                if (optionOrderbook != null) {
                    bidAskSpreadSum += optionOrderbook.getSpreadSum();
                    bidAskSpreadCount += optionOrderbook.getSpreadCount();
                    totalBidDepthSum += optionOrderbook.getBidDepthSum();
                    totalAskDepthSum += optionOrderbook.getAskDepthSum();
                }

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

        // ✅ P0-5 FIX: Calculate ratios with NaN/Infinity protection
        Double putCallVolumeRatio = null;
        if (callsVolume > 0 && putsVolume >= 0) {
            double ratio = (double) putsVolume / callsVolume;
            // Sanity check: ratio should be finite and reasonable (0-100 is extreme but possible)
            if (Double.isFinite(ratio) && ratio >= 0 && ratio <= 100) {
                putCallVolumeRatio = ratio;
            } else {
                log.warn("⚠️ Extreme volume P/C ratio: puts={} calls={} ratio={} familyKey={}", 
                    putsVolume, callsVolume, ratio, family.getFamilyKey());
            }
        }
        
        Double putCallRatio = null;
        if (callsOI != null && putsOI != null && callsOI > 0 && putsOI >= 0) {
            double ratio = putsOI.doubleValue() / callsOI.doubleValue();
            // Sanity check: P/C ratio typically 0.1-10, but allow up to 100 for edge cases
            if (Double.isFinite(ratio) && ratio >= 0 && ratio <= 100) {
                putCallRatio = ratio;
            } else {
                log.warn("⚠️ Extreme OI P/C ratio: putsOI={} callsOI={} ratio={} familyKey={}", 
                    putsOI, callsOI, ratio, family.getFamilyKey());
            }
        }

        // ✅ FIX #4: Robust spot price calculation with fallback
        // Prioritize equity price, but fall back to near-month future if equity is absent
        Double finalSpotPrice = (spotPriceFromEquity != null) ? spotPriceFromEquity : nearMonthFuturePrice;

        // Calculate futures basis using the robust spot price
        Double futuresBasis = null;
        Double futuresBasisPercent = null;
        if (finalSpotPrice != null && nearMonthFuturePrice != null && finalSpotPrice > 0) {
            futuresBasis = nearMonthFuturePrice - finalSpotPrice;
            futuresBasisPercent = (futuresBasis / finalSpotPrice) * 100.0;
        }

        log.debug("📊 Calculated spot price: equity={} nearFuture={} final={}", 
            spotPriceFromEquity, nearMonthFuturePrice, finalSpotPrice);

        Double avgBidAskSpread = bidAskSpreadCount > 0 ? bidAskSpreadSum / bidAskSpreadCount : null;
        Long totalBidVolumeMetric = totalBidDepthSum > 0.0 ? Math.round(totalBidDepthSum) : null;
        Long totalAskVolumeMetric = totalAskDepthSum > 0.0 ? Math.round(totalAskDepthSum) : null;
        Double bidAskImbalance = null;
        double depthTotal = totalBidDepthSum + totalAskDepthSum;
        if (depthTotal > 0.0) {
            bidAskImbalance = (totalBidDepthSum - totalAskDepthSum) / depthTotal;
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
            .spotPrice(finalSpotPrice)  // Use robust spot price
            .nearMonthFuturePrice(nearMonthFuturePrice)
            .futuresBasis(futuresBasis)
            .futuresBasisPercent(futuresBasisPercent)
            .activeFuturesCount(family.getFutures() != null ? family.getFutures().size() : 0)
            .activeOptionsCount(family.getOptions() != null ? family.getOptions().size() : 0)
            .nearMonthExpiry(nearMonthExpiry)
            .putCallVolumeRatio(putCallVolumeRatio)
            .avgBidAskSpread(avgBidAskSpread)
            .totalBidVolume(totalBidVolumeMetric)
            .totalAskVolume(totalAskVolumeMetric)
            .bidAskImbalance(bidAskImbalance)
            .calculatedAt(System.currentTimeMillis())
            .build();
    }

    /**
     * Calculate and set volume-weighted metrics for microstructure and order book
     * 
     * ✅ FIX: Dynamic isComplete/valid flags + comprehensive field aggregation
     */
    private void calculateVolumeWeightedMetrics(FamilyEnrichedData family) {
        List<InstrumentCandle> allInstruments = new ArrayList<>();
        if (family.getEquity() != null) allInstruments.add(family.getEquity());
        if (family.getFutures() != null) allInstruments.addAll(family.getFutures());
        if (family.getOptions() != null) allInstruments.addAll(family.getOptions());

        if (allInstruments.isEmpty()) {
            family.setMicrostructure(MicrostructureData.builder().isComplete(false).build());
            family.setOrderbookDepth(OrderbookDepthData.builder().isComplete(false).build());
            return;
        }

        if (allInstruments.size() == 1) {
            InstrumentCandle only = allInstruments.get(0);
            MicrostructureData micro = copyMicrostructure(only.getMicrostructure());
            OrderbookDepthData depth = copyOrderbook(only.getOrderbookDepth());
            family.setMicrostructure(micro != null ? micro : MicrostructureData.builder().isComplete(false).build());
            family.setOrderbookDepth(depth != null ? depth : OrderbookDepthData.builder().isComplete(false).build());
            return;
        }

        boolean anyMicrostructure = false;
        boolean allMicroComplete = true;
        boolean anyOrderbook = false;
        boolean allOrderbookComplete = true;

        double ofiSum = 0.0;
        double ofiWeight = 0.0;
        double vpinSum = 0.0;
        double vpinWeight = 0.0;
        double depthImbalanceSum = 0.0;
        double depthImbalanceWeight = 0.0;
        double kyleLambdaSum = 0.0;
        double kyleLambdaWeight = 0.0;
        double effectiveSpreadSum = 0.0;
        double effectiveSpreadWeight = 0.0;
        double micropriceSum = 0.0;
        double micropriceWeight = 0.0;
        double midPriceSum = 0.0;
        double midPriceWeight = 0.0;
        double bidAskSpreadSum = 0.0;
        double bidAskSpreadWeight = 0.0;
        Long windowStart = null;
        Long windowEnd = null;

        double spreadSum = 0.0;
        double spreadWeight = 0.0;
        double totalBidDepthSum = 0.0;
        double totalBidDepthWeight = 0.0;
        double totalAskDepthSum = 0.0;
        double totalAskDepthWeight = 0.0;
        double weightedDepthImbalanceSum = 0.0;
        double weightedDepthImbalanceWeight = 0.0;
        double bidVwapSum = 0.0;
        double bidVwapWeight = 0.0;
        double askVwapSum = 0.0;
        double askVwapWeight = 0.0;
        double depthPressureSum = 0.0;
        double depthPressureWeight = 0.0;
        double bidSlopeSum = 0.0;
        double bidSlopeWeight = 0.0;
        double askSlopeSum = 0.0;
        double askSlopeWeight = 0.0;
        double slopeRatioSum = 0.0;
        double slopeRatioWeight = 0.0;
        int spoofingCountSum = 0;
        boolean spoofingCountPresent = false;
        boolean activeSpoofingBid = false;
        boolean activeSpoofingAsk = false;
        boolean icebergDetectedBid = false;
        boolean icebergDetectedAsk = false;
        double icebergProbBidSum = 0.0;
        double icebergProbBidWeight = 0.0;
        double icebergProbAskSum = 0.0;
        double icebergProbAskWeight = 0.0;
        Long aggregatedTimestamp = null;
        InstrumentCandle referenceDepthInstrument = null;
        double referenceDepthWeight = -1.0;

        for (InstrumentCandle instrument : allInstruments) {
            double weight = instrument.getVolume() != null && instrument.getVolume() > 0
                ? instrument.getVolume()
                : 1.0;

            MicrostructureData micro = instrument.getMicrostructure();
            if (micro != null) {
                anyMicrostructure = true;
                if (!Boolean.TRUE.equals(micro.getIsComplete())) {
                    allMicroComplete = false;
                }
                if (micro.getOfi() != null) {
                    ofiSum += micro.getOfi() * weight;
                    ofiWeight += weight;
                }
                if (micro.getVpin() != null) {
                    vpinSum += micro.getVpin() * weight;
                    vpinWeight += weight;
                }
                if (micro.getDepthImbalance() != null) {
                    depthImbalanceSum += micro.getDepthImbalance() * weight;
                    depthImbalanceWeight += weight;
                }
                if (micro.getKyleLambda() != null) {
                    kyleLambdaSum += micro.getKyleLambda() * weight;
                    kyleLambdaWeight += weight;
                }
                if (micro.getEffectiveSpread() != null) {
                    effectiveSpreadSum += micro.getEffectiveSpread() * weight;
                    effectiveSpreadWeight += weight;
                }
                if (micro.getMicroprice() != null) {
                    micropriceSum += micro.getMicroprice() * weight;
                    micropriceWeight += weight;
                }
                if (micro.getMidPrice() != null) {
                    midPriceSum += micro.getMidPrice() * weight;
                    midPriceWeight += weight;
                }
                if (micro.getBidAskSpread() != null) {
                    bidAskSpreadSum += micro.getBidAskSpread() * weight;
                    bidAskSpreadWeight += weight;
                }
                if (micro.getWindowStart() != null) {
                    windowStart = windowStart == null ? micro.getWindowStart() : Math.min(windowStart, micro.getWindowStart());
                }
                if (micro.getWindowEnd() != null) {
                    windowEnd = windowEnd == null ? micro.getWindowEnd() : Math.max(windowEnd, micro.getWindowEnd());
                }
            }

            OrderbookDepthData depth = instrument.getOrderbookDepth();
            if (depth != null) {
                anyOrderbook = true;
                if (!Boolean.TRUE.equals(depth.getIsComplete())) {
                    allOrderbookComplete = false;
                }
                if (depth.getSpread() != null) {
                    spreadSum += depth.getSpread() * weight;
                    spreadWeight += weight;
                }
                if (depth.getTotalBidDepth() != null) {
                    totalBidDepthSum += depth.getTotalBidDepth() * weight;
                    totalBidDepthWeight += weight;
                }
                if (depth.getTotalAskDepth() != null) {
                    totalAskDepthSum += depth.getTotalAskDepth() * weight;
                    totalAskDepthWeight += weight;
                }
                if (depth.getWeightedDepthImbalance() != null) {
                    weightedDepthImbalanceSum += depth.getWeightedDepthImbalance() * weight;
                    weightedDepthImbalanceWeight += weight;
                }
                if (depth.getBidVWAP() != null) {
                    bidVwapSum += depth.getBidVWAP() * weight;
                    bidVwapWeight += weight;
                }
                if (depth.getAskVWAP() != null) {
                    askVwapSum += depth.getAskVWAP() * weight;
                    askVwapWeight += weight;
                }
                if (depth.getDepthPressure() != null) {
                    depthPressureSum += depth.getDepthPressure() * weight;
                    depthPressureWeight += weight;
                }
                if (depth.getBidSlope() != null) {
                    bidSlopeSum += depth.getBidSlope() * weight;
                    bidSlopeWeight += weight;
                }
                if (depth.getAskSlope() != null) {
                    askSlopeSum += depth.getAskSlope() * weight;
                    askSlopeWeight += weight;
                }
                if (depth.getSlopeRatio() != null) {
                    slopeRatioSum += depth.getSlopeRatio() * weight;
                    slopeRatioWeight += weight;
                }
                if (depth.getTimestamp() != null) {
                    aggregatedTimestamp = aggregatedTimestamp == null ? depth.getTimestamp() : Math.max(aggregatedTimestamp, depth.getTimestamp());
                }
                if (depth.getSpoofingCountLast1Min() != null) {
                    spoofingCountSum += depth.getSpoofingCountLast1Min();
                    spoofingCountPresent = true;
                }
                if (Boolean.TRUE.equals(depth.getActiveSpoofingBid())) {
                    activeSpoofingBid = true;
                }
                if (Boolean.TRUE.equals(depth.getActiveSpoofingAsk())) {
                    activeSpoofingAsk = true;
                }
                if (Boolean.TRUE.equals(depth.getIcebergDetectedBid())) {
                    icebergDetectedBid = true;
                }
                if (Boolean.TRUE.equals(depth.getIcebergDetectedAsk())) {
                    icebergDetectedAsk = true;
                }
                if (depth.getIcebergProbabilityBid() != null) {
                    icebergProbBidSum += depth.getIcebergProbabilityBid() * weight;
                    icebergProbBidWeight += weight;
                }
                if (depth.getIcebergProbabilityAsk() != null) {
                    icebergProbAskSum += depth.getIcebergProbabilityAsk() * weight;
                    icebergProbAskWeight += weight;
                }
                if (weight > referenceDepthWeight) {
                    referenceDepthWeight = weight;
                    referenceDepthInstrument = instrument;
                }
            }
        }

        MicrostructureData aggregatedMicrostructure = MicrostructureData.builder()
            .ofi(ofiWeight > 0.0 ? ofiSum / ofiWeight : null)
            .vpin(vpinWeight > 0.0 ? vpinSum / vpinWeight : null)
            .depthImbalance(depthImbalanceWeight > 0.0 ? depthImbalanceSum / depthImbalanceWeight : null)
            .kyleLambda(kyleLambdaWeight > 0.0 ? kyleLambdaSum / kyleLambdaWeight : null)
            .effectiveSpread(effectiveSpreadWeight > 0.0 ? effectiveSpreadSum / effectiveSpreadWeight : null)
            .microprice(micropriceWeight > 0.0 ? micropriceSum / micropriceWeight : null)
            .midPrice(midPriceWeight > 0.0 ? midPriceSum / midPriceWeight : null)
            .bidAskSpread(bidAskSpreadWeight > 0.0 ? bidAskSpreadSum / bidAskSpreadWeight : null)
            .isComplete(anyMicrostructure && allMicroComplete)
            .windowStart(windowStart)
            .windowEnd(windowEnd)
            .build();
        family.setMicrostructure(anyMicrostructure
            ? aggregatedMicrostructure
            : MicrostructureData.builder().isComplete(false).build());

        OrderbookDepthData referenceDepth = referenceDepthInstrument != null ? referenceDepthInstrument.getOrderbookDepth() : null;

        Double totalBidDepthValue = totalBidDepthWeight > 0.0
            ? Double.valueOf(totalBidDepthSum / totalBidDepthWeight)
            : (referenceDepth != null ? referenceDepth.getTotalBidDepth() : null);
        Double totalAskDepthValue = totalAskDepthWeight > 0.0
            ? Double.valueOf(totalAskDepthSum / totalAskDepthWeight)
            : (referenceDepth != null ? referenceDepth.getTotalAskDepth() : null);
        Double bidVwapValue = bidVwapWeight > 0.0
            ? Double.valueOf(bidVwapSum / bidVwapWeight)
            : (referenceDepth != null ? referenceDepth.getBidVWAP() : null);
        Double askVwapValue = askVwapWeight > 0.0
            ? Double.valueOf(askVwapSum / askVwapWeight)
            : (referenceDepth != null ? referenceDepth.getAskVWAP() : null);
        Double depthPressureValue = depthPressureWeight > 0.0
            ? Double.valueOf(depthPressureSum / depthPressureWeight)
            : (referenceDepth != null ? referenceDepth.getDepthPressure() : null);
        Double bidSlopeValue = bidSlopeWeight > 0.0
            ? Double.valueOf(bidSlopeSum / bidSlopeWeight)
            : (referenceDepth != null ? referenceDepth.getBidSlope() : null);
        Double askSlopeValue = askSlopeWeight > 0.0
            ? Double.valueOf(askSlopeSum / askSlopeWeight)
            : (referenceDepth != null ? referenceDepth.getAskSlope() : null);
        Double slopeRatioValue = slopeRatioWeight > 0.0
            ? Double.valueOf(slopeRatioSum / slopeRatioWeight)
            : (referenceDepth != null ? referenceDepth.getSlopeRatio() : null);
        Double midPriceValue = midPriceWeight > 0.0
            ? Double.valueOf(midPriceSum / midPriceWeight)
            : (referenceDepth != null ? referenceDepth.getMidPrice() : null);
        Double spreadValue = spreadWeight > 0.0
            ? Double.valueOf(spreadSum / spreadWeight)
            : (referenceDepth != null ? referenceDepth.getSpread() : null);

        OrderbookDepthData aggregatedOrderbook = OrderbookDepthData.builder()
            .bidProfile(copyDepthLevels(referenceDepth != null ? referenceDepth.getBidProfile() : null))
            .askProfile(copyDepthLevels(referenceDepth != null ? referenceDepth.getAskProfile() : null))
            .weightedDepthImbalance(weightedDepthImbalanceWeight > 0.0 ? weightedDepthImbalanceSum / weightedDepthImbalanceWeight : null)
            .level1Imbalance(referenceDepth != null ? referenceDepth.getLevel1Imbalance() : null)
            .level2to5Imbalance(referenceDepth != null ? referenceDepth.getLevel2to5Imbalance() : null)
            .level6to10Imbalance(referenceDepth != null ? referenceDepth.getLevel6to10Imbalance() : null)
            .cumulativeBidDepth(copyDoubleList(referenceDepth != null ? referenceDepth.getCumulativeBidDepth() : null))
            .cumulativeAskDepth(copyDoubleList(referenceDepth != null ? referenceDepth.getCumulativeAskDepth() : null))
            .totalBidDepth(totalBidDepthValue)
            .totalAskDepth(totalAskDepthValue)
            .bidVWAP(bidVwapValue)
            .askVWAP(askVwapValue)
            .depthPressure(depthPressureValue)
            .bidSlope(bidSlopeValue)
            .askSlope(askSlopeValue)
            .slopeRatio(slopeRatioValue)
            .icebergDetectedBid(icebergDetectedBid)
            .icebergDetectedAsk(icebergDetectedAsk)
            .icebergProbabilityBid(icebergProbBidWeight > 0.0 ? icebergProbBidSum / icebergProbBidWeight : (referenceDepth != null ? referenceDepth.getIcebergProbabilityBid() : null))
            .icebergProbabilityAsk(icebergProbAskWeight > 0.0 ? icebergProbAskSum / icebergProbAskWeight : (referenceDepth != null ? referenceDepth.getIcebergProbabilityAsk() : null))
            .spoofingEvents(copySpoofingEvents(referenceDepth != null ? referenceDepth.getSpoofingEvents() : null))
            .spoofingCountLast1Min(spoofingCountPresent ? spoofingCountSum : null)
            .activeSpoofingBid(activeSpoofingBid)
            .activeSpoofingAsk(activeSpoofingAsk)
            .timestamp(aggregatedTimestamp)
            .midPrice(midPriceValue)
            .spread(spreadValue)
            .depthLevels(referenceDepth != null ? referenceDepth.getDepthLevels() : null)
            .isComplete(anyOrderbook && allOrderbookComplete)
            .build();

        family.setOrderbookDepth(anyOrderbook
            ? aggregatedOrderbook
            : OrderbookDepthData.builder().isComplete(false).build());

        log.debug("📊 Family microstructure aggregated: complete={} valid={}",
            aggregatedMicrostructure.getIsComplete(), aggregatedMicrostructure.isValid());
        log.debug("📊 Family orderbook aggregated: spread={} bidDepth={} askDepth={} complete={} valid={}", 
            aggregatedOrderbook.getSpread(), aggregatedOrderbook.getTotalBidDepth(), aggregatedOrderbook.getTotalAskDepth(),
            aggregatedOrderbook.getIsComplete(), aggregatedOrderbook.isValid());
    }

    /**
     * ✅ FIX #3: Aggregates imbalance bar data across all instruments in a family
     * 
     * NOTE: This is a placeholder. A full implementation would require a new class
     * `FamilyImbalanceBars` and logic to aggregate from `InstrumentCandle.imbalanceBars`.
     */
    private void calculateFamilyImbalanceBars(FamilyEnrichedData family) {
        List<InstrumentCandle> instruments = new ArrayList<>();
        if (family.getEquity() != null) instruments.add(family.getEquity());
        if (family.getFutures() != null) instruments.addAll(family.getFutures());
        if (family.getOptions() != null) instruments.addAll(family.getOptions());

        InstrumentCandle selected = instruments.stream()
            .filter(candle -> candle.getImbalanceBars() != null)
            .max(Comparator.comparingLong(candle -> candle.getVolume() != null ? candle.getVolume() : 0L))
            .orElse(null);

        if (selected == null) {
            family.setImbalanceBars(null);
            log.debug("⚠️ No imbalance bar data available for family: {}", family.getFamilyKey());
            return;
        }

        family.setImbalanceBars(selected.getImbalanceBars());
        log.debug("📊 Family imbalance bars sourced from {} (volume={})", 
            selected.getScripCode(), selected.getVolume());
    }

    private OrderbookAccumulator extractOrderbookMetrics(InstrumentCandle instrument) {
        if (instrument == null || instrument.getOrderbookDepth() == null) {
            return null;
        }
        OrderbookDepthData depth = instrument.getOrderbookDepth();
        double spread = depth.getSpread() != null ? depth.getSpread() : 0.0;
        double bidDepth = depth.getTotalBidDepth() != null ? depth.getTotalBidDepth() : 0.0;
        double askDepth = depth.getTotalAskDepth() != null ? depth.getTotalAskDepth() : 0.0;
        int spreadCount = depth.getSpread() != null ? 1 : 0;
        return new OrderbookAccumulator(spread, spreadCount, bidDepth, askDepth);
    }

    private MicrostructureData copyMicrostructure(MicrostructureData source) {
        if (source == null) {
            return null;
        }
        return MicrostructureData.builder()
            .ofi(source.getOfi())
            .vpin(source.getVpin())
            .depthImbalance(source.getDepthImbalance())
            .kyleLambda(source.getKyleLambda())
            .effectiveSpread(source.getEffectiveSpread())
            .microprice(source.getMicroprice())
            .midPrice(source.getMidPrice())
            .bidAskSpread(source.getBidAskSpread())
            .isComplete(source.getIsComplete())
            .windowStart(source.getWindowStart())
            .windowEnd(source.getWindowEnd())
            .build();
    }

    private OrderbookDepthData copyOrderbook(OrderbookDepthData source) {
        if (source == null) {
            return null;
        }
        return OrderbookDepthData.builder()
            .bidProfile(copyDepthLevels(source.getBidProfile()))
            .askProfile(copyDepthLevels(source.getAskProfile()))
            .weightedDepthImbalance(source.getWeightedDepthImbalance())
            .level1Imbalance(source.getLevel1Imbalance())
            .level2to5Imbalance(source.getLevel2to5Imbalance())
            .level6to10Imbalance(source.getLevel6to10Imbalance())
            .cumulativeBidDepth(copyDoubleList(source.getCumulativeBidDepth()))
            .cumulativeAskDepth(copyDoubleList(source.getCumulativeAskDepth()))
            .totalBidDepth(source.getTotalBidDepth())
            .totalAskDepth(source.getTotalAskDepth())
            .bidVWAP(source.getBidVWAP())
            .askVWAP(source.getAskVWAP())
            .depthPressure(source.getDepthPressure())
            .bidSlope(source.getBidSlope())
            .askSlope(source.getAskSlope())
            .slopeRatio(source.getSlopeRatio())
            .icebergDetectedBid(source.getIcebergDetectedBid())
            .icebergDetectedAsk(source.getIcebergDetectedAsk())
            .icebergProbabilityBid(source.getIcebergProbabilityBid())
            .icebergProbabilityAsk(source.getIcebergProbabilityAsk())
            .spoofingEvents(copySpoofingEvents(source.getSpoofingEvents()))
            .spoofingCountLast1Min(source.getSpoofingCountLast1Min())
            .activeSpoofingBid(source.getActiveSpoofingBid())
            .activeSpoofingAsk(source.getActiveSpoofingAsk())
            .timestamp(source.getTimestamp())
            .midPrice(source.getMidPrice())
            .spread(source.getSpread())
            .depthLevels(source.getDepthLevels())
            .isComplete(source.getIsComplete())
            .build();
    }

    private List<OrderbookDepthData.DepthLevel> copyDepthLevels(List<OrderbookDepthData.DepthLevel> source) {
        if (source == null) {
            return null;
        }
        return source.stream()
            .map(level -> OrderbookDepthData.DepthLevel.builder()
                .level(level.getLevel())
                .price(level.getPrice())
                .quantity(level.getQuantity())
                .numberOfOrders(level.getNumberOfOrders())
                .distanceFromMid(level.getDistanceFromMid())
                .percentOfTotalDepth(level.getPercentOfTotalDepth())
                .build())
            .collect(Collectors.toList());
    }

    private List<OrderbookDepthData.SpoofingEvent> copySpoofingEvents(List<OrderbookDepthData.SpoofingEvent> source) {
        if (source == null) {
            return null;
        }
        return source.stream()
            .map(event -> OrderbookDepthData.SpoofingEvent.builder()
                .timestamp(event.getTimestamp())
                .side(event.getSide())
                .price(event.getPrice())
                .quantity(event.getQuantity())
                .durationMs(event.getDurationMs())
                .classification(event.getClassification())
                .build())
            .collect(Collectors.toList());
    }

    private List<Double> copyDoubleList(List<Double> source) {
        if (source == null) {
            return null;
        }
        return new ArrayList<>(source);
    }

    private static class OrderbookAccumulator {
        private final double spreadSum;
        private final int spreadCount;
        private final double bidDepthSum;
        private final double askDepthSum;

        OrderbookAccumulator(double spreadSum, int spreadCount, double bidDepthSum, double askDepthSum) {
            this.spreadSum = spreadSum;
            this.spreadCount = spreadCount;
            this.bidDepthSum = bidDepthSum;
            this.askDepthSum = askDepthSum;
        }

        public double getSpreadSum() {
            return spreadSum;
        }

        public int getSpreadCount() {
            return spreadCount;
        }

        public double getBidDepthSum() {
            return bidDepthSum;
        }

        public double getAskDepthSum() {
            return askDepthSum;
        }
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
     * ✅ P0-2 FIX: Enrich candles with OI data (with temporal validation)
     * 
     * CRITICAL: Only enriches candles with OI data if OI timestamp <= candle window end
     * This prevents look-ahead bias where future OI data corrupts historical candles
     */
    private KStream<String, InstrumentCandle> enrichCandles(
        KStream<String, InstrumentCandle> candles,
        KTable<String, OpenInterest> oiTable
    ) {
        return candles.leftJoin(
            oiTable,
            (candle, oi) -> {
                if (candle != null && oi != null) {
                    // ✅ P0-2 FIX: Temporal validation - only use OI if not from the future
                    Long candleEnd = candle.getWindowEndMillis();
                    Long oiTimestamp = oi.getReceivedTimestamp();
                    
                    if (candleEnd == null) {
                        // No window end timestamp on candle - skip enrichment
                        log.warn("⚠️ Candle missing windowEndMillis: scrip={}", candle.getScripCode());
                        return candle;
                    }
                    
                    if (oiTimestamp == null) {
                        // OI has no timestamp - use it anyway (backward compatibility)
                        // But log a warning for visibility
                        log.debug("⚠️ OI missing receivedTimestamp: scrip={} - enriching anyway", 
                            candle.getScripCode());
                        candle.setOpenInterest(oi.getOpenInterest());
                        candle.setOiChange(oi.getOiChange());
                    } else if (oiTimestamp <= candleEnd) {
                        // ✅ VALID: OI timestamp is before or at candle end - safe to use
                        candle.setOpenInterest(oi.getOpenInterest());
                        candle.setOiChange(oi.getOiChange());
                        log.debug("✅ OI enriched: scrip={} candleEnd={} oiTs={} oi={} oiChange={}",
                            candle.getScripCode(), candleEnd, oiTimestamp, 
                            oi.getOpenInterest(), oi.getOiChange());
                    } else {
                        // ❌ INVALID: OI timestamp is in the future relative to candle
                        // Skip enrichment to prevent look-ahead bias
                        log.warn("⏰ Skipped future OI: scrip={} candleEnd={} oiTs={} delta={}ms",
                            candle.getScripCode(), candleEnd, oiTimestamp, (oiTimestamp - candleEnd));
                        metrics.incOiFutureSkipped();
                    }
                }
                return candle;
            }
        );
    }
}
