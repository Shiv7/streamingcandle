package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.OrderbookAggregate;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import com.kotsin.consumer.timeExtractor.OrderbookTimestampExtractorWithOffset;
import com.kotsin.consumer.timeExtractor.MultiMinuteOffsetTimestampExtractorForOrderbook;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Production-ready Kafka Streams processor for Orderbook microstructure signals.
 * Pattern: Copy of CandlestickProcessor.java
 * 
 * Data Flow:
 * 1. Raw orderbook snapshots → 1-minute microstructure signals (OFI, VPIN, Kyle's Lambda, etc.)
 * 2. 1-minute signals → Multi-minute signals (2m, 3m, 5m, 15m, 30m)
 * 
 * Features:
 * - OFI (Order Flow Imbalance - full depth)
 * - VPIN (Volume-Synchronized PIN with adaptive buckets)
 * - Kyle's Lambda (price impact coefficient)
 * - Depth metrics (bid/ask VWAP, slopes, imbalances)
 * - Iceberg detection
 * - Spoofing detection
 */
@Component
public class OrderbookProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderbookProcessor.class);

    @Autowired
    private KafkaConfig kafkaConfig;

    private final Map<String, KafkaStreams> streamsInstances = new HashMap<>();

    // Grace + filter configuration
    @Value("${orderbook.window.grace.seconds.1m:5}")
    private int graceSeconds1m;

    @Value("${orderbook.window.grace.seconds.multi:10}")
    private int graceSecondsMulti;

    @Value("${orderbook.filter.enabled:false}")
    private boolean filterEnabled;

    @Value("${orderbook.filter.token:123257}")
    private String filterToken;

    /**
     * Initializes and starts the orderbook microstructure pipeline.
     */
    public void process(String appId, String inputTopic, String outputTopic, int windowSize) {

        String instanceKey = appId + "-" + windowSize + "m";
        if (streamsInstances.containsKey(instanceKey)) {
            LOGGER.warn("Streams app {} already running. Skipping duplicate start.", instanceKey);
            return;
        }

        Properties props = kafkaConfig.getStreamProperties(appId + "-" + windowSize + "m");
        StreamsBuilder builder = new StreamsBuilder();

        if (windowSize == 1) {
            processOrderbookData(builder, inputTopic, outputTopic);
        } else {
            processMultiMinuteOrderbook(builder, inputTopic, outputTopic, windowSize);
        }

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streamsInstances.put(instanceKey, streams);

        // State listener
        streams.setStateListener((newState, oldState) -> {
            LOGGER.info("Orderbook Streams state transition for {}-minute: {} -> {}",
                    windowSize, oldState, newState);

            if (newState == KafkaStreams.State.ERROR) {
                LOGGER.error("❌ Orderbook Stream {} entered ERROR state!", instanceKey);
            } else if (newState == KafkaStreams.State.RUNNING) {
                LOGGER.info("✅ Orderbook Stream {} is now RUNNING", instanceKey);
            }
        });

        // Exception handler
        streams.setUncaughtExceptionHandler((Throwable exception) -> {
            LOGGER.error("❌ Uncaught exception in {}-minute orderbook stream: ", windowSize, exception);
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });

        // Start streams
        try {
            streams.start();
            LOGGER.info("✅ Started Orderbook Streams: {}, window size: {}m", instanceKey, windowSize);
        } catch (Exception e) {
            LOGGER.error("❌ Failed to start Orderbook Streams for {}: ", instanceKey, e);
            streamsInstances.remove(instanceKey);
            throw new RuntimeException("Failed to start Orderbook Streams for " + instanceKey, e);
        }

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("🛑 Shutting down {}-minute orderbook stream", windowSize);
            try {
                streams.close(Duration.ofSeconds(30));
                streamsInstances.remove(instanceKey);
                LOGGER.info("✅ Successfully shut down {}-minute orderbook stream", windowSize);
            } catch (Exception e) {
                LOGGER.error("❌ Error during shutdown: ", e);
            }
        }, "shutdown-hook-ob-" + instanceKey));
    }

    /**
     * Process raw orderbook snapshots into 1-minute microstructure signals.
     */
    private void processOrderbookData(StreamsBuilder builder, String inputTopic, String outputTopic) {
        // 1) Read orderbook snapshots
        KStream<String, OrderBookSnapshot> raw = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), OrderBookSnapshot.serde())
                        .withTimestampExtractor(new OrderbookTimestampExtractorWithOffset())
        );

        // 2) Composite key exch:exchType:token to avoid collisions
        KStream<String, OrderBookSnapshot> keyed = raw
                .selectKey((k, ob) -> {
                    if (ob == null) return k;
                    String exch = ob.getExch() == null ? "-" : ob.getExch();
                    String ext = ob.getExchType() == null ? "-" : ob.getExchType();
                    String tok = ob.getToken() == null ? "-" : ob.getToken();
                    return exch + ":" + ext + ":" + tok;
                })
                .filter((k, ob) -> ob != null && ob.isValid());

        // 2.5) Optional: filter to single token for testing
        if (filterEnabled) {
            keyed = keyed.filter((k, ob) -> {
                if (ob == null || ob.getToken() == null) return false;
                return ob.getToken().equals(filterToken);
            });
        }

        // 3) Window & aggregate microstructure metrics
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
                Duration.ofMinutes(1),
                Duration.ofSeconds(Math.max(0, graceSeconds1m))
        );

        KTable<Windowed<String>, OrderbookAggregate> orderbookTable = keyed
                .groupByKey(Grouped.with(Serdes.String(), OrderBookSnapshot.serde()))
                .windowedBy(windows)
                .aggregate(
                        OrderbookAggregate::new,
                        (token, snapshot, aggregate) -> {
                            aggregate.updateWithSnapshot(snapshot);
                            return aggregate;
                        },
                        Materialized.<String, OrderbookAggregate, WindowStore<Bytes, byte[]>>as("orderbook-aggregate-store")
                                .withKeySerde(Serdes.String())
                                .withValueSerde(OrderbookAggregate.serde())
                )
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        orderbookTable.toStream()
                .map((windowedKey, aggregate) -> {
                    // Remove alignment shift for display
                    String exch = aggregate.getExchange();
                    int offMin = com.kotsin.consumer.util.MarketTimeAligner.getWindowOffsetMinutes(exch, 1);
                    long offMs = offMin * 60_000L;
                    aggregate.setWindowStartMillis(windowedKey.window().start() - offMs);
                    aggregate.setWindowEndMillis(windowedKey.window().end() - offMs);
                    logOrderbookDetails(aggregate, 1);
                    return KeyValue.pair(windowedKey.key(), aggregate);
                })
                .to(outputTopic, Produced.with(Serdes.String(), OrderbookAggregate.serde()));
    }

    /**
     * Aggregate multi-minute orderbook signals from 1-minute signals.
     * Note: Orderbook aggregates don't merge like candles - we keep the latest snapshot per window
     */
    private void processMultiMinuteOrderbook(StreamsBuilder builder,
                                             String inputTopic,
                                             String outputTopic,
                                             int windowSize) {

        KStream<String, OrderbookAggregate> mins = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), OrderbookAggregate.serde())
                        .withTimestampExtractor(new MultiMinuteOffsetTimestampExtractorForOrderbook(windowSize))
        );

        TimeWindows windows = TimeWindows.ofSizeAndGrace(
                Duration.ofMinutes(windowSize),
                Duration.ofSeconds(Math.max(0, graceSecondsMulti))
        );

        // For orderbook: keep LATEST aggregate (most recent microstructure state)
        KTable<Windowed<String>, OrderbookAggregate> aggregated = mins
                .groupByKey(Grouped.with(Serdes.String(), OrderbookAggregate.serde()))
                .windowedBy(windows)
                .reduce(
                        (oldValue, newValue) -> newValue,  // Keep latest
                        Materialized.<String, OrderbookAggregate, WindowStore<Bytes, byte[]>>as("agg-orderbook-store-" + windowSize + "m")
                                .withKeySerde(Serdes.String())
                                .withValueSerde(OrderbookAggregate.serde())
                )
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        aggregated.toStream()
                .map((wk, aggregate) -> {
                    int offMin = com.kotsin.consumer.util.MarketTimeAligner.getWindowOffsetMinutes(aggregate.getExchange(), windowSize);
                    long offMs = offMin * 60_000L;
                    aggregate.setWindowStartMillis(wk.window().start() - offMs);
                    aggregate.setWindowEndMillis(wk.window().end() - offMs);
                    logOrderbookDetails(aggregate, windowSize);
                    return KeyValue.pair(wk.key(), aggregate);
                })
                .to(outputTopic, Produced.with(Serdes.String(), OrderbookAggregate.serde()));
    }

    /**
     * Log orderbook details for debugging.
     */
    private void logOrderbookDetails(OrderbookAggregate aggregate, int windowSizeMinutes) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }
        ZonedDateTime windowStart = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(aggregate.getWindowStartMillis()),
                ZoneId.of("Asia/Kolkata")
        );
        ZonedDateTime windowEnd = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(aggregate.getWindowEndMillis()),
                ZoneId.of("Asia/Kolkata")
        );
        LOGGER.debug("{}m orderbook for {}: window: {}-{}, OFI: {:.2f}, VPIN: {:.3f}, Depth Imb: {:.3f}, Iceberg: {}/{}, Spoofing: {}",
                windowSizeMinutes, aggregate.getCompanyName(),
                windowStart.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                windowEnd.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                aggregate.getOfi(), aggregate.getVpin(), aggregate.getDepthImbalance(),
                aggregate.detectIcebergBid() ? "Y" : "N",
                aggregate.detectIcebergAsk() ? "Y" : "N",
                aggregate.getSpoofingCount());
    }

    /**
     * Start all orderbook processors on application startup.
     */
    @PostConstruct
    public void start() {
        try {
            LOGGER.info("🚀 Starting Orderbook Microstructure Processor with bootstrap servers: {}",
                    kafkaConfig.getBootstrapServers());

            String baseAppId = "prod-123257-orderbook";
            process(baseAppId, "Orderbook", "orderbook-signals-1m", 1);
            Thread.sleep(1000);

            process(baseAppId, "orderbook-signals-1m", "orderbook-signals-2m", 2);
            Thread.sleep(1000);

            process(baseAppId, "orderbook-signals-1m", "orderbook-signals-3m", 3);
            Thread.sleep(1000);

            process(baseAppId, "orderbook-signals-1m", "orderbook-signals-5m", 5);
            Thread.sleep(1000);

            process(baseAppId, "orderbook-signals-1m", "orderbook-signals-15m", 15);
            Thread.sleep(1000);

            process(baseAppId, "orderbook-signals-1m", "orderbook-signals-30m", 30);

            LOGGER.info("✅ All Orderbook Processors started successfully");
            logStreamStates();

        } catch (Exception e) {
            LOGGER.error("❌ Error starting Orderbook Processors", e);
            throw new RuntimeException("Failed to start orderbook processors", e);
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
        LOGGER.info("📊 Current Orderbook Stream States:");
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
            LOGGER.info("🛑 Stopping orderbook stream: {}", instanceKey);
            try {
                streams.close(Duration.ofSeconds(30));
                streamsInstances.remove(instanceKey);
                LOGGER.info("✅ Successfully stopped orderbook stream: {}", instanceKey);
            } catch (Exception e) {
                LOGGER.error("❌ Error stopping orderbook stream {}: ", instanceKey, e);
            }
        } else {
            LOGGER.warn("⚠️ Orderbook Stream {} not found", instanceKey);
        }
    }

    /**
     * Stop all streams gracefully.
     */
    @PreDestroy
    public void stopAllStreams() {
        LOGGER.info("🛑 Stopping all {} orderbook streams", streamsInstances.size());
        List<String> keys = new ArrayList<>(streamsInstances.keySet());
        for (String key : keys) {
            stopStream(key);
        }
        LOGGER.info("✅ All orderbook streams stopped");
    }
}
