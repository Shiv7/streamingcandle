package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.metrics.StreamMetrics;
import com.kotsin.consumer.model.*;
import com.kotsin.consumer.monitoring.Timeframe;
import com.kotsin.consumer.service.InstrumentStateManager;
import com.kotsin.consumer.service.TradingHoursValidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.WindowStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.support.serializer.JsonSerde;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Properties;

/**
 * SIMPLE 3-STREAM ARCHITECTURE: No merging, no joins, no intermediate topics
 *
 * Stream 1: Ticks → OHLCV Candles (6 timeframes)
 * Stream 2: Orderbook → Orderbook Signals (6 timeframes)
 * Stream 3: OI → OI Metrics (6 timeframes)
 *
 * Benefits:
 * - No cross-stream lateness issues
 * - No complex serialization
 * - No intermediate topics
 * - Easy to debug and scale
 * - Consumers can merge data if needed
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TopologyConfiguration {

    private final KafkaConfig kafkaConfig;
    private final StreamMetrics metrics;
    private final TradingHoursValidationService tradingHoursService;

    @Value("${spring.kafka.streams.application-id:unified-market-processor}")
    private String appIdPrefix;
    @Value("${unified.input.topic.ticks:forwardtesting-data}")
    private String ticksTopic;
    @Value("${unified.input.topic.oi:OpenInterest}")
    private String oiTopic;
    @Value("${unified.input.topic.orderbook:Orderbook}")
    private String orderbookTopic;

    @Value("${stream.outputs.candles.enabled:true}")
    private boolean candlesOutputEnabled;

    @Value("${unified.streams.window.grace.period.seconds:10}")
    private int gracePeriodSeconds;

    public StreamsBuilder createInstrumentTopology() {
        log.info("🗃️ Building SIMPLE 3-STREAM ARCHITECTURE (no merging, no joins)");
        Properties props = kafkaConfig.getStreamProperties(appIdPrefix + "-instrument");
        props.put("auto.offset.reset", "earliest");
        StreamsBuilder builder = new StreamsBuilder();

        if (!candlesOutputEnabled) {
            log.warn("⚠️ Candle outputs disabled via configuration");
            return builder;
        }

        // Stream 1: Ticks → OHLCV Candles
        buildTickStream(builder);

        // Stream 2: Orderbook → Orderbook Signals
        buildOrderbookStream(builder);

        // Stream 3: OI → OI Metrics
        buildOIStream(builder);

        return builder;
    }

    /**
     * STREAM 1: Ticks → OHLCV Candles
     * Produces: candle-ohlcv-{1m,2m,3m,5m,15m,30m}
     */
    private void buildTickStream(StreamsBuilder builder) {
        log.info("📊 Stream 1: Building Ticks → OHLCV Candles");

        // Shared delta volume transformation
        String deltaVolumeStoreName = "tick-delta-volume-store";
        builder.addStateStore(
            Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(deltaVolumeStoreName),
                Serdes.String(),
                Serdes.Long()
            )
        );

        KStream<String, TickData> ticksRaw = builder.stream(
            ticksTopic,
            Consumed.with(Serdes.String(), TickData.serde())
                .withTimestampExtractor(new com.kotsin.consumer.time.MarketAlignedTimestampExtractor())
        );

        KStream<String, TickData> processedTicks = ticksRaw
            .transform(
                () -> new com.kotsin.consumer.transformers.CumToDeltaTransformer(deltaVolumeStoreName),
                deltaVolumeStoreName
            )
            .filter((key, tick) -> tick != null && tick.getDeltaVolume() != null)
            .filter((key, tick) -> !Boolean.TRUE.equals(tick.getResetFlag()))
            .selectKey((k, tick) -> tick.getScripCode());

        // Build candles for each timeframe
        for (Timeframe tf : Timeframe.values()) {
            buildTickCandles(builder, processedTicks, tf);
        }
    }

    /**
     * Build OHLCV candles for a specific timeframe
     */
    private void buildTickCandles(StreamsBuilder builder, KStream<String, TickData> ticks, Timeframe tf) {
        String tfLabel = tf.getLabel();
        String outputTopic = "candle-ohlcv-" + tfLabel;

        log.info("  [{}] Ticks → OHLCV → {}", tfLabel, outputTopic);

        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            Duration.ofMinutes(1),
            Duration.ofSeconds(gracePeriodSeconds)
        );

        ticks
            .groupByKey(Grouped.with(Serdes.String(), TickData.serde()))
            .windowedBy(windows)
            .aggregate(
                InstrumentState::new,
                (scripCode, tick, state) -> {
                    try {
                        if (tradingHoursService.withinTradingHours(tick)) {
                            state.addTick(tick);
                        }
                    } catch (Exception e) {
                        log.warn("Trading hours validation error; dropping tick: {}", e.getMessage());
                    }
                    return state;
                },
                Materialized.<String, InstrumentState, WindowStore<Bytes, byte[]>>as("tick-state-" + tfLabel)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(new JsonSerde<>(InstrumentState.class))
            )
            .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
            .toStream()
            .mapValues((windowedKey, state) -> {
                long windowStart = windowedKey.window().start();
                long windowEnd = windowedKey.window().end();
                state.forceCompleteWindows(windowEnd);
                return state.extractFinalizedCandle(tf);
            })
            .selectKey((windowedKey, candle) -> windowedKey.key())
            .filter((k, candle) -> candle != null && candle.isValid())
            .mapValues(candle -> buildCandleMessage(candle, tfLabel))
            .peek((k, msg) -> {
                log.info("📤 [{}] OHLCV emitted: scrip={} vol={}", tfLabel, msg.getScripCode(),
                    msg.getCandle() != null ? msg.getCandle().getVolume() : null);
                metrics.incCandleEmit(tfLabel);
            })
            .to(outputTopic, Produced.with(Serdes.String(), new JsonSerde<>(UnifiedWindowMessage.class)));
    }

    /**
     * STREAM 2: Orderbook → Orderbook Signals
     * Produces: orderbook-signals-{1m,2m,3m,5m,15m,30m}
     */
    private void buildOrderbookStream(StreamsBuilder builder) {
        log.info("📖 Stream 2: Building Orderbook → Signals");

        KStream<String, OrderBookSnapshot> orderbookStream = builder.stream(
            orderbookTopic,
            Consumed.with(Serdes.String(), OrderBookSnapshot.serde())
                .withTimestampExtractor(new com.kotsin.consumer.time.MarketAlignedTimestampExtractor())
        );

        KStream<String, OrderBookSnapshot> orderbookKeyed = orderbookStream
            .selectKey((k, ob) -> ob != null && ob.getToken() != null ? String.valueOf(ob.getToken()) : k);

        for (Timeframe tf : Timeframe.values()) {
            buildOrderbookSignals(builder, orderbookKeyed, tf);
        }
    }

    /**
     * Build orderbook signals for a specific timeframe
     */
    private void buildOrderbookSignals(StreamsBuilder builder, KStream<String, OrderBookSnapshot> orderbook, Timeframe tf) {
        String tfLabel = tf.getLabel();
        String outputTopic = "orderbook-signals-" + tfLabel;

        log.info("  [{}] Orderbook → Signals → {}", tfLabel, outputTopic);

        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            Duration.ofMinutes(1),
            Duration.ofSeconds(gracePeriodSeconds)
        );

        orderbook
            .filter((k, ob) -> ob != null && ob.isValid())
            .groupByKey(Grouped.with(Serdes.String(), OrderBookSnapshot.serde()))
            .windowedBy(windows)
            .aggregate(
                InstrumentState::new,
                (token, ob, state) -> {
                    state.addOrderbook(ob);
                    return state;
                },
                Materialized.<String, InstrumentState, WindowStore<Bytes, byte[]>>as("orderbook-state-" + tfLabel)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(new JsonSerde<>(InstrumentState.class))
            )
            .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
            .toStream()
            .mapValues((windowedKey, state) -> {
                long windowStart = windowedKey.window().start();
                long windowEnd = windowedKey.window().end();
                state.forceCompleteWindows(windowEnd);
                return state.extractFinalizedCandle(tf);
            })
            .selectKey((windowedKey, candle) -> windowedKey.key())
            .filter((k, candle) -> candle != null && candle.getOrderbookDepth() != null)
            .mapValues(candle -> buildOrderbookMessage(candle, tfLabel))
            .peek((k, msg) -> {
                log.info("📤 [{}] Orderbook emitted: scrip={}", tfLabel, msg.getScripCode());
                metrics.incCandleEmit(tfLabel);
            })
            .to(outputTopic, Produced.with(Serdes.String(), new JsonSerde<>(UnifiedWindowMessage.class)));
    }

    /**
     * STREAM 3: OI → OI Metrics
     * Produces: oi-metrics-{1m,2m,3m,5m,15m,30m}
     */
    private void buildOIStream(StreamsBuilder builder) {
        log.info("💰 Stream 3: Building OI → Metrics");

        KStream<String, OpenInterest> oiStream = builder.stream(
            oiTopic,
            Consumed.with(Serdes.String(), OpenInterest.serde())
        );

        KStream<String, OpenInterest> oiKeyed = oiStream
            .selectKey((k, oi) -> oi != null && oi.getToken() != 0 ? String.valueOf(oi.getToken()) : k);

        for (Timeframe tf : Timeframe.values()) {
            buildOIMetrics(builder, oiKeyed, tf);
        }
    }

    /**
     * Build OI metrics for a specific timeframe
     */
    private void buildOIMetrics(StreamsBuilder builder, KStream<String, OpenInterest> oi, Timeframe tf) {
        String tfLabel = tf.getLabel();
        String outputTopic = "oi-metrics-" + tfLabel;

        log.info("  [{}] OI → Metrics → {}", tfLabel, outputTopic);

        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            Duration.ofMinutes(1),
            Duration.ofSeconds(gracePeriodSeconds * 3)  // OI updates slower
        );

        oi
            .filter((k, oiData) -> oiData != null)
            .groupByKey(Grouped.with(Serdes.String(), OpenInterest.serde()))
            .windowedBy(windows)
            .aggregate(
                () -> null,
                (token, oiData, current) -> oiData,  // Keep latest OI per window
                Materialized.<String, OpenInterest, WindowStore<Bytes, byte[]>>as("oi-state-" + tfLabel)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(OpenInterest.serde())
            )
            .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
            .toStream()
            .selectKey((windowedKey, oiData) -> windowedKey.key())
            .filter((k, oiData) -> oiData != null)
            .mapValues((k, oiData) -> buildOIMessage(oiData, tfLabel))
            .peek((k, msg) -> {
                log.info("📤 [{}] OI emitted: scrip={} oi={}", tfLabel, k,
                    msg.getOpenInterest() != null ? msg.getOpenInterest().getOiClose() : null);
                metrics.incCandleEmit(tfLabel);
            })
            .to(outputTopic, Produced.with(Serdes.String(), new JsonSerde<>(UnifiedWindowMessage.class)));
    }

    /**
     * Build OHLCV-only message
     */
    private UnifiedWindowMessage buildCandleMessage(InstrumentCandle candle, String tfLabel) {
        return UnifiedWindowMessage.builder()
            .scripCode(candle.getScripCode())
            .companyName(candle.getCompanyName())
            .exchange(candle.getExchange())
            .exchangeType(candle.getExchangeType())
            .timeframe(tfLabel)
            .windowStartMillis(candle.getWindowStartMillis())
            .windowEndMillis(candle.getWindowEndMillis())
            .startTimeIST(UnifiedWindowMessage.toIstString(candle.getWindowStartMillis()))
            .endTimeIST(UnifiedWindowMessage.toIstString(candle.getWindowEndMillis()))
            .candle(UnifiedWindowMessage.CandleSection.builder()
                .open(candle.getOpen())
                .high(candle.getHigh())
                .low(candle.getLow())
                .close(candle.getClose())
                .volume(candle.getVolume())
                .buyVolume(candle.getBuyVolume())
                .sellVolume(candle.getSellVolume())
                .vwap(candle.getVwap())
                .tickCount(candle.getTickCount())
                .isComplete(candle.getIsComplete())
                .build())
            .imbalanceBars(candle.getImbalanceBars())
            .build();
    }

    /**
     * Build Orderbook-only message
     */
    private UnifiedWindowMessage buildOrderbookMessage(InstrumentCandle candle, String tfLabel) {
        OrderbookDepthData depth = candle.getOrderbookDepth();
        MicrostructureData micro = candle.getMicrostructure();

        return UnifiedWindowMessage.builder()
            .scripCode(candle.getScripCode())
            .timeframe(tfLabel)
            .windowStartMillis(candle.getWindowStartMillis())
            .windowEndMillis(candle.getWindowEndMillis())
            .startTimeIST(UnifiedWindowMessage.toIstString(candle.getWindowStartMillis()))
            .endTimeIST(UnifiedWindowMessage.toIstString(candle.getWindowEndMillis()))
            .orderbookSignals(UnifiedWindowMessage.OrderbookSignals.builder()
                .vpinLevel(micro != null ? micro.getVpinLevel() : null)
                .depthBuyImbalanced(micro != null ? micro.isDepthBuyImbalanced() : null)
                .depthSellImbalanced(micro != null ? micro.isDepthSellImbalanced() : null)
                .spreadLevel(micro != null ? micro.getSpreadLevel() : null)
                .ofi(micro != null ? micro.getOfi() : null)
                .depthImbalance(depth != null ? depth.getWeightedDepthImbalance() : null)
                .spreadAvg(depth != null ? depth.getSpread() : null)
                .bidDepthSum(depth != null ? depth.getTotalBidDepth() : null)
                .askDepthSum(depth != null ? depth.getTotalAskDepth() : null)
                .bidVWAP(depth != null ? depth.getBidVWAP() : null)
                .askVWAP(depth != null ? depth.getAskVWAP() : null)
                .microprice(depth != null ? depth.getMidPrice() : null)
                .icebergBid(depth != null ? depth.getIcebergDetectedBid() : null)
                .icebergAsk(depth != null ? depth.getIcebergDetectedAsk() : null)
                .spoofingCount(depth != null ? depth.getSpoofingCountLast1Min() : null)
                .build())
            .build();
    }

    /**
     * Build OI-only message
     */
    private UnifiedWindowMessage buildOIMessage(OpenInterest oi, String tfLabel) {
        return UnifiedWindowMessage.builder()
            .scripCode(String.valueOf(oi.getToken()))
            .timeframe(tfLabel)
            .windowStartMillis(oi.getReceivedTimestamp())
            .windowEndMillis(oi.getReceivedTimestamp())
            .openInterest(UnifiedWindowMessage.OpenInterestSection.builder()
                .oiClose(oi.getOpenInterest())
                .oiChange(oi.getOiChange())
                .oiChangePercent(oi.getOiChangePercent())
                .build())
            .build();
    }
}
