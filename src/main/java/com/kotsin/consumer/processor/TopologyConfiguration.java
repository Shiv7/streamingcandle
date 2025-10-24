package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.metrics.StreamMetrics;
import com.kotsin.consumer.model.InstrumentCandle;
import com.kotsin.consumer.model.MicrostructureData;
import com.kotsin.consumer.model.OpenInterest;
import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.OrderbookDepthData;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.monitoring.Timeframe;
import com.kotsin.consumer.service.TradingHoursValidationService;
import com.kotsin.consumer.transformers.OiDeltaTransformer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.WindowStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.support.serializer.JsonSerde;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Properties;

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

    @Value("${unified.streams.window.grace.period.seconds:30}")
    private int gracePeriodSeconds;
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

    public StreamsBuilder createInstrumentTopology() {
        log.info("🗃️ Building per-instrument candle topology");
        Properties props = kafkaConfig.getStreamProperties(appIdPrefix + "-instrument");
        props.put("auto.offset.reset", "latest");
        StreamsBuilder builder = new StreamsBuilder();

        // State store for cum->delta conversion
        String deltaVolumeStoreName = "instrument-delta-volume-store";
        builder.addStateStore(
            Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(deltaVolumeStoreName),
                Serdes.String(),
                Serdes.Long()
            )
        );

        // Ticks stream
        KStream<String, TickData> ticksRaw = builder.stream(
            ticksTopic,
            Consumed.with(Serdes.String(), TickData.serde())
                .withTimestampExtractor(new com.kotsin.consumer.time.MarketAlignedTimestampExtractor())
        );
        KStream<String, TickData> ticks = ticksRaw
            .transform(
                () -> new com.kotsin.consumer.transformers.CumToDeltaTransformer(deltaVolumeStoreName),
                deltaVolumeStoreName
            )
            .filter((key, tick) -> tick != null && tick.getDeltaVolume() != null)
            .filter((key, tick) -> !Boolean.TRUE.equals(tick.getResetFlag()));

        // Orderbook stream (continuous)
        KStream<String, OrderBookSnapshot> orderbookStream = builder.stream(
            orderbookTopic,
            Consumed.with(Serdes.String(), OrderBookSnapshot.serde())
                .withTimestampExtractor(new com.kotsin.consumer.time.MarketAlignedTimestampExtractor())
        );

        // OI table by token
        KTable<String, OpenInterest> oiTable = buildOiTable(builder);

        // Re-key ticks by scripCode
        KStream<String, TickData> instrumentKeyed = ticks.selectKey((k, v) -> v.getScripCode());

        if (candlesOutputEnabled) {
            for (Timeframe tf : Timeframe.values()) {
                final String tfLabel = tf.getLabel();
                final int tfGrace = getGraceSecondsFor(tf);
                final String outTopic = getCandleTopicForTimeframe(tfLabel);
                log.info("⏱️ Building branch for {} with grace={}s → {}", tfLabel, tfGrace, outTopic);

                String oiDeltaStoreNameTf = "instrument-oi-delta-store-" + tfLabel;
                builder.addStateStore(
                    Stores.keyValueStoreBuilder(
                        Stores.persistentKeyValueStore(oiDeltaStoreNameTf),
                        Serdes.String(),
                        Serdes.Long()
                    )
                );

                TimeWindows windows = TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), Duration.ofSeconds(tfGrace));

                // Merge trade + orderbook events
                KStream<String, MergedEvent> tradeEvents = instrumentKeyed.mapValues(MergedEvent::trade);
                KStream<String, MergedEvent> obEvents = orderbookStream
                    .selectKey((k, v) -> v != null && v.getToken() != null ? String.valueOf(v.getToken()) : k)
                    .mapValues(MergedEvent::orderbook);
                KStream<String, MergedEvent> merged = tradeEvents.merge(obEvents);

                KTable<Windowed<String>, InstrumentState> aggregated = merged
                    .filter((key, evt) -> evt != null && (evt.tick != null || evt.orderbook != null))
                    .groupByKey(Grouped.with(Serdes.String(), new JsonSerde<>(MergedEvent.class)))
                    .windowedBy(windows)
                    .aggregate(
                        InstrumentState::new,
                        (scripCode, evt, state) -> {
                            if (evt.tick != null) {
                                try {
                                    if (tradingHoursService.withinTradingHours(evt.tick)) {
                                        state.addTick(evt.tick);
                                    }
                                } catch (Exception e) {
                                    // On validation errors, drop the tick to avoid polluting session data
                                    log.warn("Trading hours validation error; dropping tick: {}", e.getMessage());
                                }
                            }
                            if (evt.orderbook != null && evt.orderbook.isValid()) {
                                state.addOrderbook(evt.orderbook);
                            }
                            return state;
                        },
                        Materialized.<String, InstrumentState, WindowStore<Bytes, byte[]>>as("instrument-state-store-" + tfLabel)
                            .withKeySerde(Serdes.String())
                            .withValueSerde(new JsonSerde<>(InstrumentState.class))
                    );

                KStream<String, InstrumentState> stateStream = aggregated
                    .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
                    .toStream()
                    .peek((windowedKey, state) -> {
                        long windowEnd = windowedKey.window().end();
                        state.forceCompleteWindows(windowEnd);
                        log.info("🎉 [{}] Window closed: scrip={} windowEnd={} hasComplete={}",
                            tfLabel, state.getScripCode(), windowEnd, state.hasAnyCompleteWindow());
                    })
                    .selectKey((windowedKey, state) -> windowedKey.key());

                KStream<String, InstrumentCandle> extracted = stateStream
                    .mapValues(state -> state.extractFinalizedCandle(tf))
                    .filter((k, candle) -> candle != null && candle.isValid());

                KStream<String, InstrumentCandle> withOi = extracted.leftJoin(
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
                    .transformValues(() -> new OiDeltaTransformer(oiDeltaStoreNameTf), oiDeltaStoreNameTf);

                withOiDelta
                    .mapValues(candle -> com.kotsin.consumer.model.UnifiedWindowMessage.builder()
                        .scripCode(candle.getScripCode())
                        .companyName(candle.getCompanyName())
                        .exchange(candle.getExchange())
                        .exchangeType(candle.getExchangeType())
                        .timeframe(tfLabel)
                        .windowStartMillis(candle.getWindowStartMillis())
                        .windowEndMillis(candle.getWindowEndMillis())
                        .startTimeIST(com.kotsin.consumer.model.UnifiedWindowMessage.toIstString(candle.getWindowStartMillis()))
                        .endTimeIST(com.kotsin.consumer.model.UnifiedWindowMessage.toIstString(candle.getWindowEndMillis()))
                        .candle(com.kotsin.consumer.model.UnifiedWindowMessage.CandleSection.builder()
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
                        .orderbookSignals(buildOrderbookSignals(candle))
                        .imbalanceBars(candle.getImbalanceBars())
                        .volumeProfile(candle.getVolumeProfile())
                        .openInterest(com.kotsin.consumer.model.UnifiedWindowMessage.OpenInterestSection.builder()
                            .oiClose(candle.getOpenInterest())
                            .oiChange(candle.getOiChange())
                            .oiChangePercent(candle.getOiChangePercent())
                            .build())
                        .build())
                    .peek((k, msg) -> {
                        log.info("📤 EMITTING unified: tf={} scrip={} vol={} → {}",
                            tfLabel, msg.getScripCode(),
                            msg.getCandle() != null ? msg.getCandle().getVolume() : null,
                            outTopic);
                        metrics.incCandleEmit(tfLabel);
                    })
                    .to(outTopic, Produced.with(Serdes.String(), new JsonSerde<>(com.kotsin.consumer.model.UnifiedWindowMessage.class)));
            }
        }

        return builder;
    }

    private String getCandleTopicForTimeframe(String timeframe) {
        switch (timeframe) {
            case "1m": return candle1mTopic;
            case "2m": return candle2mTopic;
            case "3m": return candle3mTopic;
            case "5m": return candle5mTopic;
            case "15m": return candle15mTopic;
            case "30m": return candle30mTopic;
            default: return "unified-window-" + timeframe; // fallback
        }
    }

    private com.kotsin.consumer.model.UnifiedWindowMessage.OrderbookSignals buildOrderbookSignals(
        com.kotsin.consumer.model.InstrumentCandle candle
    ) {
        MicrostructureData micro = candle.getMicrostructure();
        OrderbookDepthData depth = candle.getOrderbookDepth();
        return com.kotsin.consumer.model.UnifiedWindowMessage.OrderbookSignals.builder()
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

    private int getGraceSecondsFor(Timeframe tf) {
        return tf.getMinutes();
    }

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

    private KStream<String, InstrumentCandle> enrichCandles(
        KStream<String, InstrumentCandle> candles,
        KTable<String, OpenInterest> oiTable
    ) {
        return candles.leftJoin(
            oiTable,
            (candle, oi) -> {
                if (candle != null && oi != null) {
                    Long candleEnd = candle.getWindowEndMillis();
                    Long oiTimestamp = oi.getReceivedTimestamp();
                    if (candleEnd != null && (oiTimestamp == null || oiTimestamp <= candleEnd)) {
                        candle.setOpenInterest(oi.getOpenInterest());
                        candle.setOiChange(oi.getOiChange());
                    } else if (oiTimestamp != null && candleEnd != null && oiTimestamp > candleEnd) {
                        metrics.incOiFutureSkipped();
                    }
                }
                return candle;
            }
        );
    }

    public static class MergedEvent {
        // Public fields + no-arg ctor to allow Jackson (JsonSerde) to serialize/deserialize via field access
        public TickData tick;
        public OrderBookSnapshot orderbook;

        public MergedEvent() {}

        private MergedEvent(TickData tick, OrderBookSnapshot orderbook) {
            this.tick = tick;
            this.orderbook = orderbook;
        }

        static MergedEvent trade(TickData t) { return new MergedEvent(t, null); }
        static MergedEvent orderbook(OrderBookSnapshot ob) { return new MergedEvent(null, ob); }
    }
}
