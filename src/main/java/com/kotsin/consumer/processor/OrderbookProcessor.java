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
import com.kotsin.consumer.timeExtractor.OrderbookTimestampExtractorWithWindowOffset;
import com.kotsin.consumer.service.InstrumentMetadataService;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Production-ready Kafka Streams processor for Orderbook microstructure signals.
 * Pattern: Copy of CandlestickProcessor.java
 *
 * Data Flow:
 * 1. Raw orderbook snapshots → 1-minute microstructure signals (OFI, Kyle's Lambda, etc.)
 * 2. 1-minute signals → Multi-minute signals (2m, 3m, 5m, 15m, 30m)
 *
 * Features:
 * - OFI (Order Flow Imbalance - full depth)
 * - Kyle's Lambda (price impact coefficient)
 * - Depth metrics (bid/ask VWAP, slopes, imbalances)
 * - Iceberg detection
 * - Spoofing detection
 *
 * Note: VPIN is calculated in EnrichedCandlestick (from trade data), not here
 */
@Component
public class OrderbookProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderbookProcessor.class);

    @Autowired
    private KafkaConfig kafkaConfig;

    private final Map<String, KafkaStreams> streamsInstances = new HashMap<>();
    private final InstrumentMetadataService instrumentMetadataService;

    // Configurable parameters
    @Value("${orderbook.ticksize:0.05}")
    private double orderbookTickSize;
    @Value("${orderbook.spoof.size.threshold.ratio:0.3}")
    private double spoofSizeThresholdRatio;
    @Value("${orderbook.spoof.confirmation.snapshots:2}")
    private int spoofConfirmSnapshots;
    @Value("${orderbook.lambda.min.observations:10}")
    private int lambdaMinObservations;
    @Value("${orderbook.lambda.calc.frequency:5}")
    private int lambdaCalcFrequency;
    @Value("${orderbook.lambda.ofi.epsilon:1.0}")
    private double lambdaOfiEpsilon;
    @Value("${orderbook.spoof.price.epsilon.ticks:1.0}")
    private double spoofPriceEpsilonTicks;

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
    public OrderbookProcessor(InstrumentMetadataService instrumentMetadataService) {
        this.instrumentMetadataService = instrumentMetadataService;
    }

    public void process(String appId, String inputTopic, String outputTopic, int windowSize) {

        String instanceKey = appId + "-" + windowSize + "m";
        if (streamsInstances.containsKey(instanceKey)) {
            LOGGER.warn("Streams app {} already running. Skipping duplicate start.", instanceKey);
            return;
        }

        Properties props = kafkaConfig.getStreamProperties(appId + "-" + windowSize + "m");
        StreamsBuilder builder = new StreamsBuilder();

        // Apply configurable parameters to the aggregate model
        com.kotsin.consumer.model.OrderbookAggregate.configure(
                orderbookTickSize,
                spoofSizeThresholdRatio,
                spoofConfirmSnapshots,
                lambdaMinObservations,
                lambdaCalcFrequency,
                lambdaOfiEpsilon
        );
        com.kotsin.consumer.model.OrderbookAggregate.setSpoofPriceEpsilonTicks(spoofPriceEpsilonTicks);

        // Build all timeframes directly from orderbook snapshots (Option A)
        processOrderbookData(builder, inputTopic, outputTopic, windowSize);

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

        // BUG-018 FIX: Removed shutdown hook - cleanup handled by @PreDestroy
    }

    /**
     * Process raw orderbook snapshots into 1-minute microstructure signals.
     */
    private void processOrderbookData(StreamsBuilder builder, String inputTopic, String outputTopic, int windowSizeMinutes) {
        // 1) Read orderbook snapshots
        KStream<String, OrderBookSnapshot> raw = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), OrderBookSnapshot.serde())
                        .withTimestampExtractor(new OrderbookTimestampExtractorWithWindowOffset(windowSizeMinutes))
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
                Duration.ofMinutes(windowSizeMinutes),
                Duration.ofSeconds(Math.max(0, windowSizeMinutes == 1 ? graceSeconds1m : graceSecondsMulti))
        );

        KTable<Windowed<String>, OrderbookAggregate> orderbookTable = keyed
                .groupByKey(Grouped.with(Serdes.String(), OrderBookSnapshot.serde()))
                .windowedBy(windows)
                .aggregate(
                        OrderbookAggregate::new,
                        (token, snapshot, aggregate) -> {
                            double tick = instrumentMetadataService.getTickSize(snapshot.getExch(), snapshot.getExchType(), snapshot.getToken(), snapshot.getCompanyName(), orderbookTickSize);
                            aggregate.setInstrumentTickSize(tick);
                            instrumentMetadataService.getSpoofSizeRatio(snapshot.getExch(), snapshot.getExchType(), snapshot.getToken(), snapshot.getCompanyName()).ifPresent(aggregate::setInstrumentSpoofSizeRatio);
                            instrumentMetadataService.getSpoofEpsilonTicks(snapshot.getExch(), snapshot.getExchType(), snapshot.getToken(), snapshot.getCompanyName()).ifPresent(aggregate::setInstrumentSpoofEpsilonTicks);
                            aggregate.updateWithSnapshot(snapshot);
                            return aggregate;
                        },
                        Materialized.<String, OrderbookAggregate, WindowStore<Bytes, byte[]>>as("orderbook-aggregate-store-" + windowSizeMinutes + "m")
                                .withKeySerde(Serdes.String())
                                .withValueSerde(OrderbookAggregate.serde())
                )
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        orderbookTable.toStream()
                .map((windowedKey, aggregate) -> {
                    // Remove alignment shift for display
                    String exch = aggregate.getExchange();
                    int offMin = com.kotsin.consumer.util.MarketTimeAligner.getWindowOffsetMinutes(exch, windowSizeMinutes);
                    long offMs = offMin * 60_000L;
                    aggregate.setWindowStartMillis(windowedKey.window().start() - offMs);
                    aggregate.setWindowEndMillis(windowedKey.window().end() - offMs);
                    logOrderbookDetails(aggregate, windowSizeMinutes);
                    return KeyValue.pair(windowedKey.key(), aggregate);
                })
                .to(outputTopic, Produced.with(Serdes.String(), OrderbookAggregate.serde()));
    }

    /**
     * Wrapper to start multi-minute orderbook aggregation from 1-minute orderbook metrics.
     */
    public void processMultiMinuteOrderbook(String appId, String inputTopic, String outputTopic, int windowSize) {
        String instanceKey = appId + "-" + windowSize + "m";
        if (streamsInstances.containsKey(instanceKey)) {
            LOGGER.warn("Streams app {} already running. Skipping duplicate start.", instanceKey);
            return;
        }

        Properties props = kafkaConfig.getStreamProperties(appId + "-" + windowSize + "m");
        StreamsBuilder builder = new StreamsBuilder();

        // Build multi-minute orderbook from 1-minute orderbook metrics
        buildMultiMinuteOrderbook(builder, inputTopic, outputTopic, windowSize);

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streamsInstances.put(instanceKey, streams);

        // State listener
        streams.setStateListener((newState, oldState) -> {
            LOGGER.info("Orderbook Streams state transition for {}-minute (cascaded): {} -> {}",
                    windowSize, oldState, newState);

            if (newState == KafkaStreams.State.ERROR) {
                LOGGER.error("❌ Orderbook Stream {} entered ERROR state!", instanceKey);
            } else if (newState == KafkaStreams.State.RUNNING) {
                LOGGER.info("✅ Orderbook Stream {} is now RUNNING (cascaded)", instanceKey);
            }
        });

        // Exception handler
        streams.setUncaughtExceptionHandler((Throwable exception) -> {
            LOGGER.error("❌ Uncaught exception in Orderbook {}-minute stream (cascaded): ", windowSize, exception);

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

            LOGGER.warn("⚠️ Attempting to recover by replacing stream thread...");
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });

        // Start streams
        try {
            streams.start();
            LOGGER.info("✅ Started Orderbook Streams (cascaded): {}, window size: {}m", instanceKey, windowSize);
        } catch (Exception e) {
            LOGGER.error("❌ Failed to start Orderbook Streams for {}: ", instanceKey, e);
            streamsInstances.remove(instanceKey);
            throw new RuntimeException("Failed to start orderbook streams for " + instanceKey, e);
        }

        // BUG-018 FIX: Removed shutdown hook - cleanup handled by @PreDestroy
    }

    /**
     * Aggregate multi-minute orderbook signals from 1-minute signals with CORRECT NSE alignment.
     */
    private void buildMultiMinuteOrderbook(StreamsBuilder builder, String inputTopic, String outputTopic, int windowSize) {
        KStream<String, OrderbookAggregate> mins = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), OrderbookAggregate.serde())
                        .withTimestampExtractor(new com.kotsin.consumer.timeExtractor.MultiMinuteOffsetTimestampExtractorForOrderbook(windowSize))
        );

        // Optional: filter to a single token
        if (filterEnabled) {
            mins = mins.filter((k, agg) -> {
                if (k == null) return false;
                int last = k.lastIndexOf(':');
                if (last < 0 || last == k.length() - 1) return false;
                String tokenStr = k.substring(last + 1);
                return tokenStr.equals(String.valueOf(filterToken));
            });
        }

        // Grace period for lag handling (multi-minute)
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
                Duration.ofMinutes(windowSize),
                Duration.ofSeconds(Math.max(0, graceSecondsMulti))
        );

        KTable<Windowed<String>, OrderbookAggregate> aggregated = mins
                .groupByKey(Grouped.with(Serdes.String(), OrderbookAggregate.serde()))
                .windowedBy(windows)
                .aggregate(
                        OrderbookAggregate::new,
                        (sym, agg, total) -> {
                            total.merge(agg);
                            return total;
                        },
                        Materialized.<String, OrderbookAggregate, WindowStore<Bytes, byte[]>>as("agg-orderbook-store-" + windowSize + "m")
                                .withKeySerde(Serdes.String())
                                .withValueSerde(OrderbookAggregate.serde())
                )
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        aggregated.toStream()
                .map((wk, agg) -> {
                    // Remove the alignment shift for display/start-end correctness
                    int offMin = com.kotsin.consumer.util.MarketTimeAligner.getWindowOffsetMinutes(agg.getExchange(), windowSize);
                    long offMs = offMin * 60_000L;
                    agg.setWindowStartMillis(wk.window().start() - offMs);
                    agg.setWindowEndMillis(wk.window().end() - offMs);
                    logOrderbookDetails(agg, windowSize);
                    return KeyValue.pair(wk.key(), agg);
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
        // BUG-022 FIX: Removed VPIN reference (VPIN is in EnrichedCandlestick, not OrderbookAggregate)
        LOGGER.debug("{}m orderbook for {}: window: {}-{}, OFI: {:.2f}, Depth Imb: {:.3f}, Lambda: {:.6f}, Iceberg: {}/{}, Spoofing: {}",
                windowSizeMinutes, aggregate.getCompanyName(),
                windowStart.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                windowEnd.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                aggregate.getOfi(), aggregate.getDepthImbalance(), aggregate.getKyleLambda(),
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

            String baseAppId = "prod-unified-orderbook";

            // Build ONLY 1-minute orderbook metrics from raw orderbook snapshots (high precision, low grace period)
            process(baseAppId, "Orderbook", "orderbook-signals-1m", 1);
            Thread.sleep(500);

            // Build multi-minute orderbook metrics from 1-minute orderbook metrics (cascading aggregation)
            // This ensures accurate aggregation without tick-level lag issues
            processMultiMinuteOrderbook(baseAppId, "orderbook-signals-1m", "orderbook-signals-2m", 2);
            Thread.sleep(500);

            processMultiMinuteOrderbook(baseAppId, "orderbook-signals-1m", "orderbook-signals-3m", 3);
            Thread.sleep(500);

            processMultiMinuteOrderbook(baseAppId, "orderbook-signals-1m", "orderbook-signals-5m", 5);
            Thread.sleep(500);

            processMultiMinuteOrderbook(baseAppId, "orderbook-signals-1m", "orderbook-signals-15m", 15);
            Thread.sleep(500);

            processMultiMinuteOrderbook(baseAppId, "orderbook-signals-1m", "orderbook-signals-30m", 30);

            LOGGER.info("✅ All Orderbook Processors started successfully (1m from raw snapshots, rest cascaded)");
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
