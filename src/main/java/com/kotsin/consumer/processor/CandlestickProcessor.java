package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.EnrichedCandlestick;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.timeExtractor.MultiMinuteOffsetTimestampExtractor;
import com.kotsin.consumer.timeExtractor.TickTimestampExtractor;
import com.kotsin.consumer.transformers.CumToDeltaTransformer;
import com.kotsin.consumer.util.MarketTimeAligner;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.state.Stores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Production-ready Kafka Streams processor for ENRICHED candlesticks.
 * Pattern: Copy of streamingcandle-working/CandlestickProcessor.java
 * 
 * Data Flow:
 * 1. Raw websocket tick data → 1-minute ENRICHED candles (OHLC + ImbalanceBars + VolumeProfile)
 * 2. 1-minute candles → Multi-minute candles (2m, 3m, 5m, 15m, 30m)
 * 
 * Features:
 * - Correct NSE/MCX market-hour alignment (9:15 AM for NSE, 9:00 AM for MCX)
 * - Single-emission of final candles per window
 * - Accurate volume calculation with buy/sell separation
 * - Imbalance bars (VIB, DIB, TRB, VRB) with EWMA thresholds
 * - Volume Profile (POC, Value Area)
 * - Proper lag handling with no fallback to current time
 */
@Component
public class CandlestickProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CandlestickProcessor.class);

    @Autowired
    private KafkaConfig kafkaConfig;

    private final Map<String, KafkaStreams> streamsInstances = new HashMap<>();

    /**
     * Initializes and starts the enriched candlestick aggregation pipeline.
     */
    public void process(String appId, String inputTopic, String outputTopic, int windowSize) {

        // Ensure there will be no duplicate streams
        String instanceKey = appId + "-" + windowSize + "m";
        if (streamsInstances.containsKey(instanceKey)) {
            LOGGER.warn("Streams app {} already running. Skipping duplicate start.", instanceKey);
            return;
        }

        Properties props = kafkaConfig.getStreamProperties(appId + "-" + windowSize + "m");
        StreamsBuilder builder = new StreamsBuilder();

        if (windowSize == 1) {
            processTickData(builder, inputTopic, outputTopic);
        } else {
            processMultiMinuteCandlestick(builder, inputTopic, outputTopic, windowSize);
        }

        KafkaStreams streams = new KafkaStreams(builder.build(), props);

        // Store streams instance BEFORE starting
        streamsInstances.put(instanceKey, streams);

        // State listener for monitoring
        streams.setStateListener((newState, oldState) -> {
            LOGGER.info("Kafka Streams state transition for {}-minute candles: {} -> {}",
                    windowSize, oldState, newState);

            if (newState == KafkaStreams.State.ERROR) {
                LOGGER.error("❌ Stream {} entered ERROR state!", instanceKey);
            } else if (newState == KafkaStreams.State.RUNNING) {
                LOGGER.info("✅ Stream {} is now RUNNING", instanceKey);
            } else if (newState == KafkaStreams.State.REBALANCING) {
                LOGGER.warn("⚠️ Stream {} is REBALANCING", instanceKey);
            }
        });

        // Exception handler
        streams.setUncaughtExceptionHandler((Throwable exception) -> {
            LOGGER.error("❌ Uncaught exception in {}-minute candle stream: ", windowSize, exception);

            String msg = exception.getMessage();
            if (msg != null && (msg.contains("timestamp") || msg.contains("Serialization"))) {
                LOGGER.error("🔥 CRITICAL: Timestamp or serialization error. Shutting down.");
                try {
                    streamsInstances.remove(instanceKey);
                } catch (Exception e) {
                    LOGGER.error("Error during cleanup: ", e);
                }
                return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_APPLICATION;
            }

            // For other exceptions, try to recover
            LOGGER.warn("⚠️ Attempting to recover by replacing stream thread...");
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });

        // Start streams
        try {
            streams.start();
            LOGGER.info("✅ Started Kafka Streams application: {}, window size: {}m", instanceKey, windowSize);
        } catch (Exception e) {
            LOGGER.error("❌ Failed to start Kafka Streams for {}: ", instanceKey, e);
            streamsInstances.remove(instanceKey);
            throw new RuntimeException("Failed to start Kafka Streams for " + instanceKey, e);
        }

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("🛑 Shutting down {}-minute candle stream", windowSize);
            try {
                streams.close(Duration.ofSeconds(30));
                streamsInstances.remove(instanceKey);
                LOGGER.info("✅ Successfully shut down {}-minute candle stream", windowSize);
            } catch (Exception e) {
                LOGGER.error("❌ Error during shutdown: ", e);
            }
        }, "shutdown-hook-" + instanceKey));
    }

    /**
     * Process raw tick data into 1-minute ENRICHED candles.
     * CRITICAL: Uses proper grace period and NSE alignment.
     */
    private void processTickData(StreamsBuilder builder, String inputTopic, String outputTopic) {
        // State store: max cumulative volume per symbol (for delta conversion)
        final String DELTA_STORE = "max-cum-vol-per-sym";
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(DELTA_STORE),
                Serdes.String(), Serdes.Long()));

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

        // 3) Convert cumulative → delta (order-safe)
        KStream<String, TickData> ticks = keyed.transform(
                () -> new CumToDeltaTransformer(DELTA_STORE), DELTA_STORE);

        // 4) Window & aggregate with deterministic OHLC and enriched features
        // Optimized: 1 second grace period
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
                Duration.ofMinutes(1),
                Duration.ofSeconds(1)
        );

        KTable<Windowed<String>, EnrichedCandlestick> candlestickTable = ticks
                .filter((sym, tick) -> withinTradingHours(tick))
                .filter((sym, tick) -> !Boolean.TRUE.equals(tick.getResetFlag()))  // Filter reset ticks
                .groupByKey(Grouped.with(Serdes.String(), TickData.serde()))
                .windowedBy(windows)
                .aggregate(
                        EnrichedCandlestick::new,
                        (sym, tick, candle) -> {
                            candle.updateWithDelta(tick);
                            return candle;
                        },
                        Materialized.<String, EnrichedCandlestick, WindowStore<Bytes, byte[]>>as("tick-candlestick-store")
                                .withKeySerde(Serdes.String())
                                .withValueSerde(EnrichedCandlestick.serde())
                )
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        candlestickTable.toStream()
                .map((windowedKey, candle) -> {
                    candle.setWindowStartMillis(windowedKey.window().start());
                    candle.setWindowEndMillis(windowedKey.window().end());
                    logCandleDetails(candle, 1);
                    return KeyValue.pair(windowedKey.key(), candle);
                })
                .to(outputTopic, Produced.with(Serdes.String(), EnrichedCandlestick.serde()));
    }

    /**
     * Filter to keep only data within trading hours.
     */
    private boolean withinTradingHours(TickData tick) {
        try {
            long ts = tick.getTimestamp();
            if (ts <= 0) {
                LOGGER.warn("⚠️ Invalid timestamp (<=0) for token {}", tick.getToken());
                return false;
            }

            ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.of("Asia/Kolkata"));
            LocalTime t = zdt.toLocalTime();
            String exch = tick.getExchange();

            if ("N".equalsIgnoreCase(exch)) {
                return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
            } else if ("M".equalsIgnoreCase(exch)) {
                return !t.isBefore(LocalTime.of(9, 0)) && !t.isAfter(LocalTime.of(23, 30));
            } else {
                LOGGER.debug("Unknown exchange '{}' for token {}, dropping", exch, tick.getToken());
                return false;
            }
        } catch (Exception e) {
            LOGGER.warn("⚠️ Invalid timestamp for token {}: {}", tick.getToken(), e.toString());
            return false;
        }
    }

    /**
     * Aggregates multi-minute candles from 1-minute candles with CORRECT NSE alignment.
     * CRITICAL: Uses MarketTimeAligner for NSE 9:15 AM alignment
     */
    private void processMultiMinuteCandlestick(StreamsBuilder builder,
                                               String inputTopic,
                                               String outputTopic,
                                               int windowSize) {

        KStream<String, EnrichedCandlestick> mins = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), EnrichedCandlestick.serde())
                        .withTimestampExtractor(new MultiMinuteOffsetTimestampExtractor(windowSize))
        );

        // Grace period for lag handling
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
                Duration.ofMinutes(windowSize),
                Duration.ofSeconds(1)
        );

        KTable<Windowed<String>, EnrichedCandlestick> aggregated = mins
                .groupByKey(Grouped.with(Serdes.String(), EnrichedCandlestick.serde()))
                .windowedBy(windows)
                .aggregate(
                        EnrichedCandlestick::new,
                        (sym, c, agg) -> {
                            agg.updateCandle(c);
                            return agg;
                        },
                        Materialized.<String, EnrichedCandlestick, WindowStore<Bytes, byte[]>>as("agg-candle-store-" + windowSize + "m")
                                .withKeySerde(Serdes.String())
                                .withValueSerde(EnrichedCandlestick.serde())
                )
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        aggregated.toStream()
                .map((wk, c) -> {
                    // Remove the alignment shift for display/start-end correctness
                    int offMin = MarketTimeAligner.getWindowOffsetMinutes(c.getExchange(), windowSize);
                    long offMs = offMin * 60_000L;

                    c.setWindowStartMillis(wk.window().start() - offMs);
                    c.setWindowEndMillis(wk.window().end() - offMs);
                    logCandleDetails(c, windowSize);
                    return KeyValue.pair(wk.key(), c);
                })
                .to(outputTopic, Produced.with(Serdes.String(), EnrichedCandlestick.serde()));
    }

    /**
     * Log candle details for debugging.
     */
    private void logCandleDetails(EnrichedCandlestick candle, int windowSizeMinutes) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }
        ZonedDateTime windowStart = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(candle.getWindowStartMillis()),
                ZoneId.of("Asia/Kolkata")
        );
        ZonedDateTime windowEnd = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(candle.getWindowEndMillis()),
                ZoneId.of("Asia/Kolkata")
        );
        LOGGER.debug("{}m candle for {}: {} window: {}-{}, OHLC: {}/{}/{}/{}, Volume: {}, Buy: {}, Sell: {}, VWAP: {:.2f}",
                windowSizeMinutes, candle.getCompanyName(), candle.getExchange(),
                windowStart.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                windowEnd.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose(), 
                candle.getVolume(), candle.getBuyVolume(), candle.getSellVolume(), candle.getVwap());
    }

    /**
     * Start all candlestick processors on application startup.
     */
    @PostConstruct
    public void start() {
        try {
            LOGGER.info("🚀 Starting Enriched Candlestick Processor with bootstrap servers: {}",
                    kafkaConfig.getBootstrapServers());

            process("realtime-candle-1min", "forwardtesting-data", "candle-ohlcv-1m", 1);
            Thread.sleep(1000);

            process("realtime-candle-2min", "candle-ohlcv-1m", "candle-ohlcv-2m", 2);
            Thread.sleep(1000);

            process("realtime-candle-3min", "candle-ohlcv-1m", "candle-ohlcv-3m", 3);
            Thread.sleep(1000);

            process("realtime-candle-5min", "candle-ohlcv-1m", "candle-ohlcv-5m", 5);
            Thread.sleep(1000);

            process("realtime-candle-15min", "candle-ohlcv-1m", "candle-ohlcv-15m", 15);
            Thread.sleep(1000);

            process("realtime-candle-30min", "candle-ohlcv-1m", "candle-ohlcv-30m", 30);

            LOGGER.info("✅ All Enriched Candlestick Processors started successfully");
            logStreamStates();

        } catch (Exception e) {
            LOGGER.error("❌ Error starting Enriched Candlestick Processors", e);
            throw new RuntimeException("Failed to start candlestick processors", e);
        }
    }

    /**
     * Get current states of all streams.
     */
    public Map<String, KafkaStreams.State> getStreamStates() {
        Map<String, KafkaStreams.State> states = new HashMap<>();
        streamsInstances.forEach((key, streams) -> {
            states.put(key, streams.state());
        });
        return states;
    }

    /**
     * Log current states of all streams.
     */
    public void logStreamStates() {
        LOGGER.info("📊 Current Stream States:");
        getStreamStates().forEach((key, state) -> {
            String emoji = getStateEmoji(state);
            LOGGER.info("  {} {}: {}", emoji, key, state);
        });
    }

    /**
     * Get emoji for stream state.
     */
    private String getStateEmoji(KafkaStreams.State state) {
        switch (state) {
            case RUNNING: return "✅";
            case REBALANCING: return "⚠️";
            case ERROR: return "❌";
            case PENDING_SHUTDOWN: return "🛑";
            case NOT_RUNNING: return "⭕";
            default: return "❓";
        }
    }

    /**
     * Stop a specific stream gracefully.
     */
    public void stopStream(String instanceKey) {
        KafkaStreams streams = streamsInstances.get(instanceKey);
        if (streams != null) {
            LOGGER.info("🛑 Stopping stream: {}", instanceKey);
            try {
                streams.close(Duration.ofSeconds(30));
                streamsInstances.remove(instanceKey);
                LOGGER.info("✅ Successfully stopped stream: {}", instanceKey);
            } catch (Exception e) {
                LOGGER.error("❌ Error stopping stream {}: ", instanceKey, e);
            }
        } else {
            LOGGER.warn("⚠️ Stream {} not found", instanceKey);
        }
    }

    /**
     * Stop all streams gracefully.
     */
    @PreDestroy
    public void stopAllStreams() {
        LOGGER.info("🛑 Stopping all {} streams", streamsInstances.size());
        List<String> keys = new ArrayList<>(streamsInstances.keySet());
        for (String key : keys) {
            stopStream(key);
        }
        LOGGER.info("✅ All streams stopped");
    }
}

