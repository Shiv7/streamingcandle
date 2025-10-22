package com.kotsin.consumer.processor.service;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.FamilyEnrichedData;
import com.kotsin.consumer.model.InstrumentCandle;
import com.kotsin.consumer.model.OpenInterest;
import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.processor.InstrumentState;
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
    
    @Value("${spring.kafka.streams.application-id:unified-market-processor1}")
    private String appIdPrefix;
    
    @Value("${unified.input.topic.ticks:forwardtesting-data}")
    private String ticksTopic;
    
    @Value("${unified.input.topic.oi:OpenInterest}")
    private String oiTopic;
    
    @Value("${unified.input.topic.orderbook:Orderbook}")
    private String orderbookTopic;

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
            .groupByKey(Grouped.with(Serdes.String(), TickData.serde()))
            .windowedBy(windows)
            .aggregate(
                InstrumentState::new,
                (scripCode, tick, state) -> {
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
            .peek((windowedKey, state) -> state.forceCompleteWindows(windowedKey.window().end()))
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

        return builder;
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
            .selectKey((scripOrToken, candle) -> candle.getUnderlyingEquityScripCode() != null
                ? candle.getUnderlyingEquityScripCode()
                : candle.getScripCode())
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
                    // This will be handled by FamilyAggregationService
                    return family;
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
