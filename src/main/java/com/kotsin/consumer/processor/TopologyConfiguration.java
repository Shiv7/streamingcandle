package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.metrics.StreamMetrics;
import com.kotsin.consumer.model.*;
import com.kotsin.consumer.monitoring.Timeframe;
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
 * NEW ARCHITECTURE: Separate window aggregations per stream type.
 *
 * Flow:
 * 1. Ticks → Window → WindowedOHLCV → intermediate-ohlcv-{tf}
 * 2. Orderbook → Window → WindowedOrderbookSignals → intermediate-orderbook-{tf}
 * 3. OI → Window → WindowedOIMetrics → intermediate-oi-{tf}
 * 4. LEFT JOIN all three → UnifiedWindowMessage → candle-complete-{tf}
 *
 * Benefits:
 * - No cross-stream "late" records
 * - Each stream processes at its own pace
 * - Independent event time per stream
 * - Easier debugging and monitoring
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
        log.info("🗃️ Building NEW ARCHITECTURE: separate window aggregations per stream");
        Properties props = kafkaConfig.getStreamProperties(appIdPrefix + "-instrument");
        props.put("auto.offset.reset", "earliest");
        StreamsBuilder builder = new StreamsBuilder();

        if (!candlesOutputEnabled) {
            log.warn("⚠️ Candle outputs disabled via configuration");
            return builder;
        }

        // Build separate topologies for each timeframe
        for (Timeframe tf : Timeframe.values()) {
            log.info("⏱️ Building topology for timeframe: {}", tf.getLabel());

            // Step 1: Tick aggregation
            buildTickAggregation(builder, tf);

            // Step 2: Orderbook aggregation
            buildOrderbookAggregation(builder, tf);

            // Step 3: OI aggregation
            buildOIAggregation(builder, tf);

            // Step 4: Join all three
            buildJoinTopology(builder, tf);
        }

        return builder;
    }

    /**
     * Step 1: Aggregate ticks into WindowedOHLCV
     */
    private void buildTickAggregation(StreamsBuilder builder, Timeframe tf) {
        String tfLabel = tf.getLabel();
        String intermediateOHLCVTopic = getIntermediateTopic("ohlcv", tfLabel);

        log.info("  [{}] Building tick aggregation → {}", tfLabel, intermediateOHLCVTopic);

        // State store for cum->delta conversion
        String deltaVolumeStoreName = "tick-delta-volume-store-" + tfLabel;
        builder.addStateStore(
            Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(deltaVolumeStoreName),
                Serdes.String(),
                Serdes.Long()
            )
        );

        // Read ticks
        KStream<String, TickData> ticksRaw = builder.stream(
            ticksTopic,
            Consumed.with(Serdes.String(), TickData.serde())
                .withTimestampExtractor(new com.kotsin.consumer.time.MarketAlignedTimestampExtractor())
        );

        // Transform cum->delta
        KStream<String, TickData> ticks = ticksRaw
            .transform(
                () -> new com.kotsin.consumer.transformers.CumToDeltaTransformer(deltaVolumeStoreName),
                deltaVolumeStoreName
            )
            .filter((key, tick) -> tick != null && tick.getDeltaVolume() != null)
            .filter((key, tick) -> !Boolean.TRUE.equals(tick.getResetFlag()))
            .selectKey((k, tick) -> tick.getScripCode());  // Key by scripCode

        // Window and aggregate
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            Duration.ofMinutes(1),
            Duration.ofSeconds(gracePeriodSeconds)
        );

        KTable<Windowed<String>, TickWindowState> tickAggregated = ticks
            .groupByKey(Grouped.with(Serdes.String(), TickData.serde()))
            .windowedBy(windows)
            .aggregate(
                TickWindowState::new,
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
                Materialized.<String, TickWindowState, WindowStore<Bytes, byte[]>>as("tick-window-state-" + tfLabel)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(new JsonSerde<>(TickWindowState.class))
            );

        // Extract OHLCV and emit to intermediate topic
        tickAggregated
            .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
            .toStream()
            .mapValues((windowedKey, state) -> {
                long windowStart = windowedKey.window().start();
                long windowEnd = windowedKey.window().end();
                return state.extractOHLCV(tf, windowStart, windowEnd);
            })
            .selectKey((windowedKey, ohlcv) -> windowedKey.key())  // Unwrap windowed key
            .filter((k, ohlcv) -> ohlcv != null && ohlcv.isValid())
            .peek((k, ohlcv) -> log.info("  [{}] OHLCV emitted: scrip={} vol={}", tfLabel, ohlcv.getScripCode(), ohlcv.getVolume()))
            .to(intermediateOHLCVTopic, Produced.with(Serdes.String(), WindowedOHLCV.serde()));
    }

    /**
     * Step 2: Aggregate orderbook into WindowedOrderbookSignals
     */
    private void buildOrderbookAggregation(StreamsBuilder builder, Timeframe tf) {
        String tfLabel = tf.getLabel();
        String intermediateOrderbookTopic = getIntermediateTopic("orderbook", tfLabel);

        log.info("  [{}] Building orderbook aggregation → {}", tfLabel, intermediateOrderbookTopic);

        // Read orderbook
        KStream<String, OrderBookSnapshot> orderbookStream = builder.stream(
            orderbookTopic,
            Consumed.with(Serdes.String(), OrderBookSnapshot.serde())
                .withTimestampExtractor(new com.kotsin.consumer.time.MarketAlignedTimestampExtractor())
        );

        // Re-key by token (as string)
        KStream<String, OrderBookSnapshot> orderbookKeyed = orderbookStream
            .selectKey((k, ob) -> ob != null && ob.getToken() != null ? String.valueOf(ob.getToken()) : k);

        // Window and aggregate
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            Duration.ofMinutes(1),
            Duration.ofSeconds(gracePeriodSeconds)
        );

        KTable<Windowed<String>, OrderbookWindowState> obAggregated = orderbookKeyed
            .filter((k, ob) -> ob != null && ob.isValid())
            .groupByKey(Grouped.with(Serdes.String(), OrderBookSnapshot.serde()))
            .windowedBy(windows)
            .aggregate(
                OrderbookWindowState::new,
                (token, ob, state) -> {
                    state.addOrderbook(ob);
                    return state;
                },
                Materialized.<String, OrderbookWindowState, WindowStore<Bytes, byte[]>>as("orderbook-window-state-" + tfLabel)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(new JsonSerde<>(OrderbookWindowState.class))
            );

        // Extract signals and emit to intermediate topic
        obAggregated
            .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
            .toStream()
            .mapValues((windowedKey, state) -> {
                long windowStart = windowedKey.window().start();
                long windowEnd = windowedKey.window().end();
                return state.extractOrderbookSignals(tf, windowStart, windowEnd);
            })
            .selectKey((windowedKey, signals) -> windowedKey.key())  // Unwrap windowed key
            .filter((k, signals) -> signals != null && signals.isValid())
            .peek((k, signals) -> log.info("  [{}] Orderbook signals emitted: scrip={}", tfLabel, signals.getScripCode()))
            .to(intermediateOrderbookTopic, Produced.with(Serdes.String(), WindowedOrderbookSignals.serde()));
    }

    /**
     * Step 3: Aggregate OI into WindowedOIMetrics
     */
    private void buildOIAggregation(StreamsBuilder builder, Timeframe tf) {
        String tfLabel = tf.getLabel();
        String intermediateOITopic = getIntermediateTopic("oi", tfLabel);

        log.info("  [{}] Building OI aggregation → {}", tfLabel, intermediateOITopic);

        // Read OI
        KStream<String, OpenInterest> oiStream = builder.stream(
            oiTopic,
            Consumed.with(Serdes.String(), OpenInterest.serde())
        );

        // Re-key by token (as string)
        KStream<String, OpenInterest> oiKeyed = oiStream
            .selectKey((k, oi) -> oi != null && oi.getToken() != 0 ? String.valueOf(oi.getToken()) : k);

        // Window and aggregate
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            Duration.ofMinutes(1),
            Duration.ofSeconds(gracePeriodSeconds * 3)  // OI updates slower, more grace
        );

        KTable<Windowed<String>, OIWindowState> oiAggregated = oiKeyed
            .filter((k, oi) -> oi != null)
            .groupByKey(Grouped.with(Serdes.String(), OpenInterest.serde()))
            .windowedBy(windows)
            .aggregate(
                OIWindowState::new,
                (token, oi, state) -> {
                    state.addOI(oi);
                    return state;
                },
                Materialized.<String, OIWindowState, WindowStore<Bytes, byte[]>>as("oi-window-state-" + tfLabel)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(new JsonSerde<>(OIWindowState.class))
            );

        // Extract metrics and emit to intermediate topic
        oiAggregated
            .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
            .toStream()
            .mapValues((windowedKey, state) -> {
                long windowStart = windowedKey.window().start();
                long windowEnd = windowedKey.window().end();
                return state.extractOIMetrics(tf, windowStart, windowEnd);
            })
            .selectKey((windowedKey, metrics) -> windowedKey.key())  // Unwrap windowed key
            .filter((k, metrics) -> metrics != null && metrics.isValid())
            .peek((k, metrics) -> log.info("  [{}] OI metrics emitted: scrip={} oi={}", tfLabel, metrics.getScripCode(), metrics.getOpenInterest()))
            .to(intermediateOITopic, Produced.with(Serdes.String(), WindowedOIMetrics.serde()));
    }

    /**
     * Step 4: LEFT JOIN all three intermediate streams and produce final UnifiedWindowMessage
     */
    private void buildJoinTopology(StreamsBuilder builder, Timeframe tf) {
        String tfLabel = tf.getLabel();
        String outputTopic = getCandleTopicForTimeframe(tfLabel);

        log.info("  [{}] Building LEFT JOIN topology → {}", tfLabel, outputTopic);

        // Read intermediate topics
        KTable<String, WindowedOHLCV> ohlcvTable = builder.table(
            getIntermediateTopic("ohlcv", tfLabel),
            Consumed.with(Serdes.String(), WindowedOHLCV.serde()),
            Materialized.as("ohlcv-table-" + tfLabel)
        );

        KTable<String, WindowedOrderbookSignals> obTable = builder.table(
            getIntermediateTopic("orderbook", tfLabel),
            Consumed.with(Serdes.String(), WindowedOrderbookSignals.serde()),
            Materialized.as("orderbook-table-" + tfLabel)
        );

        KTable<String, WindowedOIMetrics> oiTable = builder.table(
            getIntermediateTopic("oi", tfLabel),
            Consumed.with(Serdes.String(), WindowedOIMetrics.serde()),
            Materialized.as("oi-table-" + tfLabel)
        );

        // LEFT JOIN: OHLCV (base) LEFT JOIN orderbook LEFT JOIN OI
        ohlcvTable
            .leftJoin(obTable, (ohlcv, obSignals) -> {
                log.debug("  [{}] Joining OHLCV + Orderbook: scrip={} ob={}", tfLabel, ohlcv.getScripCode(), obSignals != null);
                return new IntermediateJoinResult(ohlcv, obSignals, null);
            })
            .leftJoin(oiTable, (result, oiMetrics) -> {
                log.debug("  [{}] Joining + OI: scrip={} oi={}", tfLabel,
                    result.ohlcv.getScripCode(), oiMetrics != null);
                return new IntermediateJoinResult(result.ohlcv, result.obSignals, oiMetrics);
            })
            .toStream()
            .mapValues((key, result) -> buildUnifiedWindowMessage(result))
            .filter((k, msg) -> msg != null)
            .peek((k, msg) -> {
                log.info("📤 EMITTING unified: tf={} scrip={} vol={} → {}",
                    tfLabel, msg.getScripCode(),
                    msg.getCandle() != null ? msg.getCandle().getVolume() : null,
                    outputTopic);
                metrics.incCandleEmit(tfLabel);
            })
            .to(outputTopic, Produced.with(Serdes.String(), new JsonSerde<>(UnifiedWindowMessage.class)));
    }

    /**
     * Helper: Build UnifiedWindowMessage from joined results
     */
    private UnifiedWindowMessage buildUnifiedWindowMessage(IntermediateJoinResult result) {
        if (result == null || result.ohlcv == null) {
            return null;
        }

        WindowedOHLCV ohlcv = result.ohlcv;
        WindowedOrderbookSignals obSignals = result.obSignals;
        WindowedOIMetrics oiMetrics = result.oiMetrics;

        return UnifiedWindowMessage.builder()
            .scripCode(ohlcv.getScripCode())
            .companyName(ohlcv.getCompanyName())
            .exchange(ohlcv.getExchange())
            .exchangeType(ohlcv.getExchangeType())
            .timeframe(ohlcv.getTimeframe())
            .windowStartMillis(ohlcv.getWindowStartMillis())
            .windowEndMillis(ohlcv.getWindowEndMillis())
            .startTimeIST(UnifiedWindowMessage.toIstString(ohlcv.getWindowStartMillis()))
            .endTimeIST(UnifiedWindowMessage.toIstString(ohlcv.getWindowEndMillis()))
            .candle(UnifiedWindowMessage.CandleSection.builder()
                .open(ohlcv.getOpen())
                .high(ohlcv.getHigh())
                .low(ohlcv.getLow())
                .close(ohlcv.getClose())
                .volume(ohlcv.getVolume())
                .buyVolume(ohlcv.getBuyVolume())
                .sellVolume(ohlcv.getSellVolume())
                .vwap(ohlcv.getVwap())
                .tickCount(ohlcv.getTickCount())
                .isComplete(true)
                .build())
            .orderbookSignals(buildOrderbookSignals(ohlcv.getMicrostructure(), obSignals))
            .imbalanceBars(ohlcv.getImbalanceBars())
            .volumeProfile(null)  // Not implemented in separate aggregation yet
            .openInterest(oiMetrics != null ? UnifiedWindowMessage.OpenInterestSection.builder()
                .oiClose(oiMetrics.getOpenInterest())
                .oiChange(oiMetrics.getOiChange())
                .oiChangePercent(oiMetrics.getOiChangePercent())
                .build() : null)
            .build();
    }

    /**
     * Helper: Build orderbook signals section from microstructure + orderbook data
     */
    private UnifiedWindowMessage.OrderbookSignals buildOrderbookSignals(
        MicrostructureData micro,
        WindowedOrderbookSignals obSignals
    ) {
        OrderbookDepthData depth = obSignals != null ? obSignals.getOrderbookDepth() : null;

        return UnifiedWindowMessage.OrderbookSignals.builder()
            .vpinLevel(micro != null ? micro.getVpinLevel() : null)
            .depthBuyImbalanced(micro != null ? micro.isDepthBuyImbalanced() : null)
            .depthSellImbalanced(micro != null ? micro.isDepthSellImbalanced() : null)
            .spreadLevel(micro != null ? micro.getSpreadLevel() : null)
            .ofi(micro != null ? micro.getOfi() : null)
            .depthImbalance(micro != null ? micro.getDepthImbalance() : (depth != null ? depth.getWeightedDepthImbalance() : null))
            .spreadAvg(depth != null ? depth.getSpread() : (micro != null ? micro.getBidAskSpread() : null))
            .bidDepthSum(depth != null ? depth.getTotalBidDepth() : null)
            .askDepthSum(depth != null ? depth.getTotalAskDepth() : null)
            .bidVWAP(depth != null ? depth.getBidVWAP() : null)
            .askVWAP(depth != null ? depth.getAskVWAP() : null)
            .microprice(micro != null ? micro.getMicroprice() : (depth != null ? depth.getMidPrice() : null))
            .icebergBid(depth != null ? depth.getIcebergDetectedBid() : null)
            .icebergAsk(depth != null ? depth.getIcebergDetectedAsk() : null)
            .spoofingCount(depth != null ? depth.getSpoofingCountLast1Min() : null)
            .build();
    }

    /**
     * Helper: Get intermediate topic name for stream type and timeframe
     */
    private String getIntermediateTopic(String streamType, String timeframe) {
        // streamType: "ohlcv", "orderbook", "oi"
        // timeframe: "1m", "2m", etc.
        return "intermediate-" + streamType + "-" + timeframe;
    }

    /**
     * Helper: Get final output topic for timeframe
     */
    private String getCandleTopicForTimeframe(String timeframe) {
        switch (timeframe) {
            case "1m": return "candle-complete-1m";
            case "2m": return "candle-complete-2m";
            case "3m": return "candle-complete-3m";
            case "5m": return "candle-complete-5m";
            case "15m": return "candle-complete-15m";
            case "30m": return "candle-complete-30m-v2";
            default: return "unified-window-" + timeframe;
        }
    }

    /**
     * Helper class to hold intermediate join results
     */
    private static class IntermediateJoinResult {
        WindowedOHLCV ohlcv;
        WindowedOrderbookSignals obSignals;
        WindowedOIMetrics oiMetrics;

        IntermediateJoinResult(WindowedOHLCV ohlcv, WindowedOrderbookSignals obSignals, WindowedOIMetrics oiMetrics) {
            this.ohlcv = ohlcv;
            this.obSignals = obSignals;
            this.oiMetrics = oiMetrics;
        }
    }
}
