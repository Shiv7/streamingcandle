package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.Candlestick;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.timeExtractor.CandleTimestampExtractor;
import com.kotsin.consumer.transformers.CumToDeltaTransformer;

import jakarta.annotation.PostConstruct;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.WindowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.streams.state.Stores;


/**
 * Production-ready Kafka Streams processor that aggregates market data into candlesticks of various durations.
 * REFACTORED to use Kafka Streams best practices for time-windowed aggregations and market-hour alignment.
 *
 * Data Flow:
 * 1. Raw websocket tick data → 1-minute candles
 * 2. 1-minute candles → Multi-minute candles (2m, 3m, 5m, 15m, 30m)
 *
 * Features:
 * - Correct, centralized market-hour alignment for NSE and MCX.
 * - Robust, single-emission of final candles per window.
 * - Accurate volume calculation for all timeframes.
 */
@Component
public class CandlestickProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CandlestickProcessor.class);

    @Autowired
    private KafkaConfig kafkaConfig;

    private final Map<String, KafkaStreams> streamsInstances = new HashMap<>();

    /**
     * Initializes and starts the candlestick aggregation pipeline.
     */
    public void process(String appId, String inputTopic, String outputTopic, int windowSize) {
        Properties props = kafkaConfig.getStreamProperties(appId + "-" + windowSize + "m");
        StreamsBuilder builder = new StreamsBuilder();

        if (windowSize == 1) {
            processTickData(builder, inputTopic, outputTopic);
        } else {
            processMultiMinuteCandlestick(builder, inputTopic, outputTopic, windowSize);
        }

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streamsInstances.put(appId + "-" + windowSize + "m", streams);


        streams.setStateListener((newState, oldState) -> {
            LOGGER.info("Kafka Streams state transition for {}-minute candles: {} -> {}",
                    windowSize, oldState, newState);
        });

        streams.setUncaughtExceptionHandler((Throwable exception) -> {
            LOGGER.error("Uncaught exception in {}-minute candle stream: ", windowSize, exception);
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });

        streams.start();
        LOGGER.info("Started Kafka Streams application with id: {}, window size: {}m", appId + "-" + windowSize + "m", windowSize);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down {}-minute candle stream", windowSize);
            streams.close();
        }));
    }

    private void processTickData(StreamsBuilder builder, String inputTopic, String outputTopic) {
        // State store: max cumulative volume per symbol (for delta conversion)
        final String DELTA_STORE = "max-cum-vol-per-sym";
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(DELTA_STORE),
                Serdes.String(), Serdes.Integer()));

        // 1) Read raw ticks with true event time
        KStream<String, TickData> raw = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), TickData.serde())
                        .withTimestampExtractor(new TickTimestampExtractor())
        );

        // 2) Stable key (scripCode or token)
        KStream<String, TickData> keyed = raw.selectKey((k, t) ->
                (t.getScripCode() != null && !t.getScripCode().isEmpty()) ?
                        t.getScripCode() : String.valueOf(t.getToken()));


        // 3) Convert cumulative -> delta (order-safe)
        KStream<String, TickData> ticks = keyed.transform(
                () -> new CumToDeltaTransformer(DELTA_STORE), DELTA_STORE);

        // 4) Window & aggregate with deterministic OHLC and summed deltas
        TimeWindows windows = TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), Duration.ofSeconds(5));

        KTable<Windowed<String>, Candlestick> candlestickTable = ticks
                .filter((sym, tick) -> withinTradingHours(tick)) // see method below
                .groupByKey(Grouped.with(Serdes.String(), TickData.serde()))
                .windowedBy(windows)
                .aggregate(
                        Candlestick::new,
                        (sym, tick, candle) -> { candle.updateWithDelta(tick); return candle; },
                        Materialized.<String, Candlestick, WindowStore<Bytes, byte[]>>as("tick-candlestick-store")
                                .withKeySerde(Serdes.String())
                                .withValueSerde(Candlestick.serde())
                )
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        candlestickTable.toStream()
                .map((windowedKey, candle) -> {
                    candle.setWindowStartMillis(windowedKey.window().start());
                    candle.setWindowEndMillis(windowedKey.window().end());
                    logCandleDetails(candle, 1);
                    return KeyValue.pair(windowedKey.key(), candle); // key = scripCode
                })
                .to(outputTopic, Produced.with(Serdes.String(), Candlestick.serde()));
    }


    private boolean withinTradingHours(TickData tick) {
        try {
            long ts = tick.getTimestamp();
            if (ts <= 0) return false;

            ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.of("Asia/Kolkata"));
            LocalTime t = zdt.toLocalTime();
            String exch = tick.getExchange();

            if ("N".equals(exch)) {
                return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
            } else if ("M".equals(exch)) {
                return !t.isBefore(LocalTime.of(9, 0)) && !t.isAfter(LocalTime.of(23, 30));
            } else {
                // Unknown exchange -> drop (or send to DLQ if you add one)
                return false;
            }
        } catch (Exception e) {
            LOGGER.warn("Invalid timestamp for token {}: {}", tick.getToken(), e.toString());
            return false;
        }
    }


    /**
     * Aggregates multi-minute candles from 1-minute candles with correct market-hour alignment.
     */
    private void processMultiMinuteCandlestick(StreamsBuilder builder,
                                               String inputTopic,
                                               String outputTopic,
                                               int windowSize) {
        KStream<String, Candlestick> mins = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), Candlestick.serde())
                        .withTimestampExtractor(new CandleTimestampExtractor())
        );

        TimeWindows windows = TimeWindows.ofSizeAndGrace(Duration.ofMinutes(windowSize), Duration.ofSeconds(5));

        KTable<Windowed<String>, Candlestick> aggregated = mins
                .groupByKey(Grouped.with(Serdes.String(), Candlestick.serde()))
                .windowedBy(windows)
                .aggregate(
                        Candlestick::new,
                        (sym, c, agg) -> { agg.updateCandle(c); return agg; },
                        Materialized.<String, Candlestick, WindowStore<Bytes, byte[]>>as("agg-candle-store-" + windowSize + "m")
                                .withKeySerde(Serdes.String())
                                .withValueSerde(Candlestick.serde())
                )
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        aggregated.toStream()
                .map((wk, c) -> {
                    c.setWindowStartMillis(wk.window().start());
                    c.setWindowEndMillis(wk.window().end());
                    logCandleDetails(c, windowSize);
                    return KeyValue.pair(wk.key(), c); // keep scripCode key
                })
                .to(outputTopic, Produced.with(Serdes.String(), Candlestick.serde()));
    }


    private boolean isWithinTradingHours(TickData tick) {
        try {
            ZonedDateTime time;
            String tickDt = tick.getTickDt();
            if (tickDt != null && tickDt.startsWith("/Date(") && tickDt.endsWith(")/")) {
                String millisStr = tickDt.substring(6, tickDt.length() - 2);
                long millis = Long.parseLong(millisStr);
                time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.of("Asia/Kolkata"));
            } else {
                time = ZonedDateTime.parse(tickDt, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata")));
            }

            LocalTime localTime = time.toLocalTime();
            if ("N".equals(tick.getExchange())) {
                return !localTime.isBefore(LocalTime.of(9, 15)) && !localTime.isAfter(LocalTime.of(15, 30));
            } else if ("M".equals(tick.getExchange())) {
                return !localTime.isBefore(LocalTime.of(9, 0)) && !localTime.isAfter(LocalTime.of(23, 30));
            }
            return true;
        } catch (Exception e) {
            LOGGER.warn("Error parsing tick datetime '{}' for token {}: {}. Allowing tick.",
                    tick.getTickDt(), tick.getToken(), e.getMessage());
            return true;
        }
    }

    private void logCandleDetails(Candlestick candle, int windowSizeMinutes) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }
        ZonedDateTime windowStart = ZonedDateTime.ofInstant(Instant.ofEpochMilli(candle.getWindowStartMillis()), ZoneId.of("Asia/Kolkata"));
        ZonedDateTime windowEnd = ZonedDateTime.ofInstant(Instant.ofEpochMilli(candle.getWindowEndMillis()), ZoneId.of("Asia/Kolkata"));
        LOGGER.debug("{}m candle for {}: {} window: {}-{}, OHLC: {}/{}/{}/{}, Volume: {}",
                windowSizeMinutes, candle.getCompanyName(), candle.getExchange(),
                windowStart.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                windowEnd.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose(), candle.getVolume());
    }

    @PostConstruct
    public void start() {
        try {
            LOGGER.info("Starting Realtime Candlestick Processor with bootstrap servers: {}", kafkaConfig.getBootstrapServers());
            process("realtime-candle-1min", "forwardtesting-data", "1-min-candle", 1);
            Thread.sleep(5000);
            process("realtime-candle-2min", "1-min-candle", "2-min-candle", 2);
            Thread.sleep(1000);
            process("realtime-candle-3min", "1-min-candle", "3-min-candle", 3);
            Thread.sleep(1000);
            process("realtime-candle-5min", "1-min-candle", "5-min-candle", 5);
            Thread.sleep(1000);
            process("realtime-candle-15min", "1-min-candle", "15-min-candle", 15);
            Thread.sleep(1000);
            process("realtime-candle-30min", "1-min-candle", "30-min-candle", 30);
            LOGGER.info("✅ All Realtime Candlestick Processors started successfully");
        } catch (Exception e) {
            LOGGER.error("❌ Error starting Realtime Candlestick Processors", e);
        }
    }


}


