package com.kotsin.consumer.processor.service;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.FamilyEnrichedData;
import com.kotsin.consumer.model.InstrumentCandle;
import com.kotsin.consumer.model.OpenInterest;
import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.processor.InstrumentState;
import com.kotsin.consumer.processor.Timeframe;
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
import java.util.Properties;

/**
 * Configuration service for Kafka Streams topologies
 * 
 * SINGLE RESPONSIBILITY: Topology building and configuration
 * EXTRACTED FROM: UnifiedMarketDataProcessor (God class refactoring)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TopologyConfiguration {

    private final KafkaConfig kafkaConfig;
    private final StreamMetrics metrics;
    private final FamilyAggregationService familyAggregationService;
    private final InstrumentKeyResolver keyResolver;
    private final TradingHoursValidationService tradingHoursService;
    
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
        log.info("🏗️ Building per-instrument candle topology");
        
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

        // Aggregate into InstrumentState
        KTable<Windowed<String>, InstrumentState> aggregated = instrumentKeyed
            .filter((key, tick) -> tradingHoursService.withinTradingHours(tick))
            .groupByKey(Grouped.with(Serdes.String(), TickData.serde()))
            .windowedBy(windows)
            .aggregate(
                InstrumentState::new,
                (scripCode, tick, state) -> {
                    // 🔧 CRITICAL FIX: Set metadata on first tick
                    if (state.getScripCode() == null) {
                        try {
                            String instrumentType = keyResolver.getInstrumentType(tick);
                            String familyKey = keyResolver.getFamilyKey(tick);
                            state.setInstrumentType(instrumentType);
                            state.setUnderlyingEquityScripCode(familyKey);
                            log.debug("🔧 Metadata set: scrip={}, type={}, family={}", 
                                scripCode, instrumentType, familyKey);
                        } catch (Exception e) {
                            log.error("❌ Failed to set metadata for {}", scripCode, e);
                        }
                    }
                    
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
                log.info("🎉 Window closed: scrip={} windowEnd={}", state.getScripCode(), windowEnd);
            })
            .selectKey((windowedKey, state) -> windowedKey.key());

        // Enrich with orderbook data
        KStream<String, InstrumentState> enrichedState = stateStream
            .leftJoin(
                orderbookTable,
                (state, orderbook) -> {
                    if (orderbook != null && orderbook.isValid()) {
                        state.addOrderbook(orderbook);
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
                                log.info("📤 Extracted candle: tf={} scrip={} vol={} complete={}", 
                                    tfLabel, candle.getScripCode(), candle.getVolume(), candle.getIsComplete());
                            } else {
                                log.warn("❌ Failed to extract candle: tf={} scrip={} hasComplete={}", 
                                    tfLabel, state.getScripCode(), state.hasAnyCompleteWindow());
                            }
                            return candle;
                        })
                        .filter((k, candle) -> candle != null && candle.isValid())
                        .peek((k, candle) -> {
                            log.info("📤 EMITTING: tf={} scrip={} vol={} → {}", 
                                tfLabel, candle.getScripCode(), candle.getVolume(), topic);
                            metrics.incCandleEmit(tfLabel);
                        })
                        .to(topic, Produced.with(Serdes.String(), InstrumentCandle.serde()));
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
            default: return null;
        }
    }

    /**
     * Resolve family key for candle aggregation
     */
    private String resolveFamilyKey(InstrumentCandle candle) {
        if (candle == null) {
            return "unknown";
        }

        // For options, try to extract underlying from company name
        if (candle.getOptionType() != null && candle.getCompanyName() != null) {
            // Extract underlying from "INDUSINDBK 28 OCT 2025 PE 760.00" -> "INDUSINDBK"
            String companyName = candle.getCompanyName();
            String[] parts = companyName.split(" ");
            if (parts.length > 0) {
                String underlying = parts[0];
                log.debug("🔗 Extracted underlying {} from option {}", underlying, candle.getScripCode());
                return underlying;
            }
        }

        // Fallback to scripCode
        return candle.getScripCode();
    }

    /**
     * Create family-structured aggregation topology
     */
    public StreamsBuilder createFamilyTopology(String timeframeLabel, String sourceTopic, String sinkTopic, Duration windowSize) {
        log.info("🏗️ Building family-structured topology for {}", timeframeLabel);
        
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
                // CRITICAL FIX: Use proper family key resolution
                String familyKey = resolveFamilyKey(candle);
                log.debug("🔗 Mapped candle {} to family key {}", candle.getScripCode(), familyKey);
                return familyKey;
            })
            .repartition(Repartitioned.with(Serdes.String(), InstrumentCandle.serde()));

        // BALANCED APPROACH: 30s grace period for family aggregation
        // Aligns with instrument stream grace period
        TimeWindows windows = TimeWindows.ofSizeAndGrace(windowSize, Duration.ofSeconds(30));

        KTable<Windowed<String>, FamilyEnrichedData> aggregated = keyedByFamily
            .groupByKey(Grouped.with(Serdes.String(), InstrumentCandle.serde()))
            .windowedBy(windows)
            .aggregate(
                () -> FamilyEnrichedData.builder().build(),
                (familyKey, candle, family) -> {
                    // CRITICAL FIX: Actually call FamilyAggregationService
                    if (familyAggregationService != null) {
                        return familyAggregationService.assembleFamily(familyKey, candle, family);
                    } else {
                        log.warn("❌ FamilyAggregationService is null, family aggregation will fail");
                        return family;
                    }
                },
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

        out.to(sinkTopic, Produced.with(Serdes.String(), FamilyEnrichedData.serde()));

        return builder;
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
                }
                return candle;
            }
        );
    }
}
