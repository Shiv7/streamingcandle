package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.Candlestick;
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
 * Production-ready Kafka Streams processor that aggregates market data into candlesticks of various durations.
 * <p>
 * Data Flow:
 * 1. Raw websocket tick data → 1-minute candles
 * 2. 1-minute candles → Multi-minute candles (2m, 3m, 5m, 15m, 30m)
 * <p>
 * Features:
 * - Correct, centralized market-hour alignment for NSE and MCX
 * - Robust, single-emission of final candles per window
 * - Accurate volume calculation for all timeframes
 * - Proper lag handling with no fallback to current time
 * - Enhanced error handling and recovery
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

        // CRITICAL FIX: Store streams instance BEFORE starting to prevent race conditions
        streamsInstances.put(instanceKey, streams);

        // State listener for monitoring
        streams.setStateListener((newState, oldState) -> {
            LOGGER.info("Kafka Streams state transition for {}-minute candles: {} -> {}",
                    windowSize, oldState, newState);

            // Log error states for debugging
            if (newState == KafkaStreams.State.ERROR) {
                LOGGER.error("❌ Stream {} entered ERROR state!", instanceKey);
            } else if (newState == KafkaStreams.State.RUNNING) {
                LOGGER.info("✅ Stream {} is now RUNNING", instanceKey);
            } else if (newState == KafkaStreams.State.REBALANCING) {
                LOGGER.warn("⚠️ Stream {} is REBALANCING", instanceKey);
            }
        });

        // FIXED: Better exception handler with proper cleanup
        streams.setUncaughtExceptionHandler((Throwable exception) -> {
            LOGGER.error("❌ Uncaught exception in {}-minute candle stream: ", windowSize, exception);

            // Log full stack trace for debugging
            LOGGER.error("Stack trace: ", exception);

            // Check if it's a critical timestamp or serialization issue
            String msg = exception.getMessage();
            if (msg != null && (msg.contains("timestamp") ||
                    msg.contains("Timestamp") ||
                    msg.contains("Serialization") ||
                    msg.contains("time travel") ||
                    msg.contains("Invalid timestamp") ||
                    msg.contains("DeserializationException"))) {
                LOGGER.error("🔥 CRITICAL: Timestamp or serialization error detected. Shutting down to prevent corruption.");
                LOGGER.error("Exception details: {}", msg);

                // Cleanup before shutdown
                try {
                    streamsInstances.remove(instanceKey);
                } catch (Exception e) {
                    LOGGER.error("Error during cleanup: ", e);
                }

                return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_APPLICATION;
            }

            // Check for repeated failures
            if (exception instanceof org.apache.kafka.streams.errors.StreamsException) {
                LOGGER.error("🔥 StreamsException detected. This may indicate corrupt state or configuration issues.");

                // Check if it's a state store exception
                if (msg != null && (msg.contains("state store") || msg.contains("StateStore"))) {
                    LOGGER.error("State store corruption detected. Shutting down to allow manual recovery.");
                    return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_APPLICATION;
                }
            }

            // For other exceptions, try to recover by replacing the thread
            LOGGER.warn("⚠️ Attempting to recover by replacing stream thread...");
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });

        // Global state restore listener for monitoring
        streams.setGlobalStateRestoreListener(new org.apache.kafka.streams.processor.StateRestoreListener() {
            @Override
            public void onRestoreStart(org.apache.kafka.common.TopicPartition topicPartition, String storeName, long startingOffset, long endingOffset) {
                LOGGER.info("🔄 Starting state restore for store {} from topic-partition {} (offset {}-{})",
                        storeName, topicPartition, startingOffset, endingOffset);
            }

            @Override
            public void onBatchRestored(org.apache.kafka.common.TopicPartition topicPartition, String storeName, long batchEndOffset, long numRestored) {
                if (numRestored > 0 && numRestored % 10000 == 0) {
                    LOGGER.info("📊 Restored batch for store {}: {} records (offset: {})", storeName, numRestored, batchEndOffset);
                }
            }

            @Override
            public void onRestoreEnd(org.apache.kafka.common.TopicPartition topicPartition, String storeName, long totalRestored) {
                LOGGER.info("✅ Completed state restore for store {}: {} total records restored", storeName, totalRestored);
            }
        });

        // Start streams with proper error handling
        try {
            streams.start();
            LOGGER.info("✅ Started Kafka Streams application with id: {}, window size: {}m", instanceKey, windowSize);
        } catch (Exception e) {
            LOGGER.error("❌ Failed to start Kafka Streams for {}: ", instanceKey, e);
            // Cleanup on start failure
            streamsInstances.remove(instanceKey);
            throw new RuntimeException("Failed to start Kafka Streams for " + instanceKey, e);
        }

        // Better shutdown hook with proper cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("🛑 Shutting down {}-minute candle stream", windowSize);
            try {
                // Close with timeout to prevent hanging
                streams.close(Duration.ofSeconds(30));
                streamsInstances.remove(instanceKey);
                LOGGER.info("✅ Successfully shut down {}-minute candle stream", windowSize);
            } catch (Exception e) {
                LOGGER.error("❌ Error during shutdown of {}-minute candle stream: ", windowSize, e);
            }
        }, "shutdown-hook-" + instanceKey));
    }

    /**
     * Process raw tick data into 1-minute candles.
     * CRITICAL: Uses proper grace period for lag handling.
     */
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
        // Optimized: Reduced grace period to 1s for minimal latency
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
                Duration.ofMinutes(1),
                Duration.ofSeconds(1)  // Grace period for late ticks
        );

        KTable<Windowed<String>, Candlestick> candlestickTable = ticks
                .filter((sym, tick) -> withinTradingHours(tick))
                .groupByKey(Grouped.with(Serdes.String(), TickData.serde()))
                .windowedBy(windows)
                .aggregate(
                        Candlestick::new,
                        (sym, tick, candle) -> {
                            candle.updateWithDelta(tick);
                            return candle;
                        },
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
                // Unknown exchange -> drop (or send to DLQ if you add one)
                LOGGER.debug("Unknown exchange '{}' for token {}, dropping", exch, tick.getToken());
                return false;
            }
        } catch (Exception e) {
            LOGGER.warn("⚠️ Invalid timestamp for token {}: {}", tick.getToken(), e.toString());
            return false;
        }
    }

    /**
     * Aggregates multi-minute candles from 1-minute candles with correct market-hour alignment.
     * CRITICAL: Uses proper grace period for lag handling.
     */
    private void processMultiMinuteCandlestick(StreamsBuilder builder,
                                               String inputTopic,
                                               String outputTopic,
                                               int windowSize) {

        KStream<String, Candlestick> mins = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), Candlestick.serde())
                        .withTimestampExtractor(new MultiMinuteOffsetTimestampExtractor(windowSize))
        );

        // CRITICAL FIX: Add grace period to handle lag and out-of-order data
        // Optimized: Reduced grace period to 1s for minimal latency
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
                Duration.ofMinutes(windowSize),
                Duration.ofSeconds(1)  // Grace period for late-arriving 1-min candles
        );

        KTable<Windowed<String>, Candlestick> aggregated = mins
                .groupByKey(Grouped.with(Serdes.String(), Candlestick.serde()))
                .windowedBy(windows)
                .aggregate(
                        Candlestick::new,
                        (sym, c, agg) -> {
                            agg.updateCandle(c);
                            return agg;
                        },
                        Materialized.<String, Candlestick, WindowStore<Bytes, byte[]>>as("agg-candle-store-" + windowSize + "m")
                                .withKeySerde(Serdes.String())
                                .withValueSerde(Candlestick.serde())
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
                    return KeyValue.pair(wk.key(), c); // keep scripCode key
                })
                .to(outputTopic, Produced.with(Serdes.String(), Candlestick.serde()));
    }

    /**
     * Log candle details for debugging.
     */
    private void logCandleDetails(Candlestick candle, int windowSizeMinutes) {
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
        LOGGER.debug("{}m candle for {}: {} window: {}-{}, OHLC: {}/{}/{}/{}, Volume: {}",
                windowSizeMinutes, candle.getCompanyName(), candle.getExchange(),
                windowStart.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                windowEnd.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose(), candle.getVolume());
    }

    /**
     * Start all candlestick processors on application startup.
     */
    @PostConstruct
    public void start() {
        try {
            LOGGER.info("🚀 Starting Realtime Candlestick Processor with bootstrap servers: {}",
                    kafkaConfig.getBootstrapServers());

            process("realtime-candle-1min", "forwardtesting-data", "1-min-candle", 1);
            Thread.sleep(1000);

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

            // Log initial states
            logStreamStates();

        } catch (Exception e) {
            LOGGER.error("❌ Error starting Realtime Candlestick Processors", e);
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