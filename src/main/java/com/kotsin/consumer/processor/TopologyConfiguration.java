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

    /**
     * CONSUMER 1: Ticks → OHLCV Candles
     * Independent consumer with its own application-id and state stores
     */
    public StreamsBuilder createTicksTopology() {
        log.info("📊 Building Ticks Consumer (independent)");
        StreamsBuilder builder = new StreamsBuilder();

        if (!candlesOutputEnabled) {
            log.warn("⚠️ Candle outputs disabled via configuration");
            return builder;
        }

        buildTickStream(builder);
        return builder;
    }

    /**
     * CONSUMER 2: Orderbook → Orderbook Signals
     * Independent consumer with its own application-id and state stores
     */
    public StreamsBuilder createOrderbookTopology() {
        log.info("📖 Building Orderbook Consumer (independent)");
        StreamsBuilder builder = new StreamsBuilder();

        if (!candlesOutputEnabled) {
            log.warn("⚠️ Candle outputs disabled via configuration");
            return builder;
        }

        buildOrderbookStream(builder);
        return builder;
    }

    /**
     * CONSUMER 3: OI → OI Metrics
     * Independent consumer with its own application-id and state stores
     */
    public StreamsBuilder createOITopology() {
        log.info("💰 Building OI Consumer (independent)");
        StreamsBuilder builder = new StreamsBuilder();

        if (!candlesOutputEnabled) {
            log.warn("⚠️ Candle outputs disabled via configuration");
            return builder;
        }

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
            .filter((key, tick) -> !Boolean.TRUE.equals(tick.getResetFlag()));
            // NO selectKey needed - forwardtesting-data topic is already keyed by token

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

        // CRITICAL FIX: Window size must match the timeframe!
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            Duration.ofMinutes(tf.getMinutes()),  // Use timeframe duration!
            Duration.ofSeconds(gracePeriodSeconds)
        );

        ticks
            .groupByKey(Grouped.with(Serdes.String(), TickData.serde())
                .withName("tick-group-" + tfLabel))  // Give explicit name to avoid conflicts
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
                    .withCachingDisabled()  // Disable caching to avoid serialization issues
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

        // Extract token from composite key (e.g., "N|48817" -> "48817")
        KStream<String, OrderBookSnapshot> orderbookKeyed = orderbookStream
            .selectKey((k, ob) -> {
                if (ob != null && ob.getToken() != null) {
                    return String.valueOf(ob.getToken());
                }
                // If key is in format "N|48817", extract token part
                if (k != null && k.contains("|")) {
                    String[] parts = k.split("\\|");
                    return parts.length > 1 ? parts[1] : k;
                }
                return k;
            });

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

        // CRITICAL FIX: Window size must match the timeframe!
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            Duration.ofMinutes(tf.getMinutes()),  // Use timeframe duration!
            Duration.ofSeconds(gracePeriodSeconds)
        );

        orderbook
            .peek((k, ob) -> log.info("🔍 [{}] Orderbook received: key={} token={}", tfLabel, k, ob != null ? ob.getToken() : "null"))
            .filter((k, ob) -> ob != null && ob.isValid())
            .peek((k, ob) -> log.info("✅ [{}] Orderbook passed filter: key={}", tfLabel, k))
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
            .filter((k, candle) -> candle != null)  // Just check if candle exists
            .mapValues(candle -> buildOrderbookMessage(candle, tfLabel))
            .filter((k, msg) -> msg != null && msg.getOrderbookSignals() != null)  // Filter AFTER building message
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

        // Extract token from composite key (e.g., "N|52343" -> "52343")
        // OpenInterest topic uses same key format as Orderbook: exch|token
        KStream<String, OpenInterest> oiKeyed = oiStream
            .selectKey((k, oi) -> {
                if (oi != null && oi.getToken() != 0) {
                    return String.valueOf(oi.getToken());
                }
                // If key is in format "N|52343", extract token part
                if (k != null && k.contains("|")) {
                    String[] parts = k.split("\\|");
                    return parts.length > 1 ? parts[1] : k;
                }
                return k;
            });

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

        // CRITICAL FIX: Window size must match the timeframe!
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            Duration.ofMinutes(tf.getMinutes()),  // Use timeframe duration!
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
            .filter((windowedKey, oiData) -> oiData != null)  // Simple null check
            .mapValues((windowedKey, oiData) -> {
                // Pass window times to builder
                long windowStart = windowedKey.window().start();
                long windowEnd = windowedKey.window().end();
                return buildOIMessage(oiData, tfLabel, windowStart, windowEnd);
            })
            .selectKey((windowedKey, msg) -> windowedKey.key())
            .filter((k, msg) -> msg != null)
            .peek((k, msg) -> {
                log.info("📤 [{}] OI emitted: scrip={} oi={}", tfLabel, k,
                    msg.getOpenInterest() != null ? msg.getOpenInterest().getOiClose() : null);
                metrics.incCandleEmit(tfLabel);
            })
            .to(outputTopic, Produced.with(Serdes.String(), new JsonSerde<>(UnifiedWindowMessage.class)));
    }

    /**
     * Build OHLCV-only message (includes OHLCV + imbalanceBars + volumeProfile)
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
            .volumeProfile(candle.getVolumeProfile())
            .build();
    }

    /**
     * Build Orderbook-only message
     */
    private UnifiedWindowMessage buildOrderbookMessage(InstrumentCandle candle, String tfLabel) {
        if (candle == null) {
            return null;
        }
        
        OrderbookDepthData depth = candle.getOrderbookDepth();
        MicrostructureData micro = candle.getMicrostructure();
        
        // Only build message if we have orderbook data
        if (depth == null) {
            log.debug("⚠️ No orderbook depth data for scripCode={}", candle.getScripCode());
            return null;
        }

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
            .orderbookSignals(UnifiedWindowMessage.OrderbookSignals.builder()
                .vpinLevel(micro != null ? micro.getVpinLevel() : null)
                .depthBuyImbalanced(micro != null ? micro.isDepthBuyImbalanced() : null)
                .depthSellImbalanced(micro != null ? micro.isDepthSellImbalanced() : null)
                .spreadLevel(micro != null ? micro.getSpreadLevel() : null)
                .ofi(micro != null ? micro.getOfi() : null)
                .depthImbalance(depth.getWeightedDepthImbalance())
                .spreadAvg(depth.getSpread())
                .bidDepthSum(depth.getTotalBidDepth())
                .askDepthSum(depth.getTotalAskDepth())
                .bidVWAP(depth.getBidVWAP())
                .askVWAP(depth.getAskVWAP())
                .microprice(depth.getMidPrice())
                .icebergBid(depth.getIcebergDetectedBid())
                .icebergAsk(depth.getIcebergDetectedAsk())
                .spoofingCount(depth.getSpoofingCountLast1Min())
                .build())
            .build();
    }

    /**
     * Build OI-only message with proper window times
     */
    private UnifiedWindowMessage buildOIMessage(OpenInterest oi, String tfLabel, long windowStart, long windowEnd) {
        return UnifiedWindowMessage.builder()
            .scripCode(String.valueOf(oi.getToken()))
            .companyName(oi.getCompanyName())
            .exchange(oi.getExchange())
            .exchangeType(oi.getExchangeType())
            .timeframe(tfLabel)
            .windowStartMillis(windowStart)
            .windowEndMillis(windowEnd)
            .startTimeIST(UnifiedWindowMessage.toIstString(windowStart))
            .endTimeIST(UnifiedWindowMessage.toIstString(windowEnd))
            .openInterest(UnifiedWindowMessage.OpenInterestSection.builder()
                .oiClose(oi.getOpenInterest())
                .oiChange(oi.getOiChange())
                .oiChangePercent(oi.getOiChangePercent())
                .build())
            .build();
    }
}
