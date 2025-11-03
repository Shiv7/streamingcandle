package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.OpenInterest;
import com.kotsin.consumer.model.OIAggregate;

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
import com.kotsin.consumer.timeExtractor.OITimestampExtractorWithOffset;
import com.kotsin.consumer.timeExtractor.OITimestampExtractorWithWindowOffset;
import com.kotsin.consumer.service.InstrumentMetadataService;
import com.kotsin.consumer.timeExtractor.MultiMinuteOffsetTimestampExtractorForOI;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Production-ready Kafka Streams processor for Open Interest metrics.
 * Pattern: Copy of CandlestickProcessor.java
 * 
 * Data Flow:
 * 1. Raw OI updates → 1-minute OI metrics (OI OHLC, Put/Call tracking)
 * 2. 1-minute metrics → Multi-minute metrics (2m, 3m, 5m, 15m, 30m)
 * 
 * Features:
 * - OI OHLC tracking (track OI changes like price movements)
 * - Put/Call OI separation
 * - OI change metrics (absolute and percentage)
 */
@Component
public class OIProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(OIProcessor.class);

    @Autowired
    private KafkaConfig kafkaConfig;

    private final Map<String, KafkaStreams> streamsInstances = new HashMap<>();

    // Grace + filter configuration
    @Value("${oi.window.grace.seconds.1m:5}")
    private int graceSeconds1m;

    @Value("${oi.window.grace.seconds.multi:10}")
    private int graceSecondsMulti;

    @Value("${oi.filter.enabled:false}")
    private boolean filterEnabled;

    @Value("${oi.filter.token:123257}")
    private int filterToken;
    @Value("${oi.scale.use.lots:false}")
    private boolean oiScaleUseLots;
    @Value("${oi.value.scale:1.0}")
    private double oiValueScale;

    /**
     * Initializes and starts the OI metrics pipeline.
     */
    public void process(String appId, String inputTopic, String outputTopic, int windowSize) {

        String instanceKey = appId + "-" + windowSize + "m";
        if (streamsInstances.containsKey(instanceKey)) {
            LOGGER.warn("Streams app {} already running. Skipping duplicate start.", instanceKey);
            return;
        }

        Properties props = kafkaConfig.getStreamProperties(appId + "-" + windowSize + "m");
        StreamsBuilder builder = new StreamsBuilder();

        // Build all timeframes directly from OI updates (Option A)
        processOIData(builder, inputTopic, outputTopic, windowSize);

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streamsInstances.put(instanceKey, streams);

        // State listener
        streams.setStateListener((newState, oldState) -> {
            LOGGER.info("OI Streams state transition for {}-minute: {} -> {}",
                    windowSize, oldState, newState);

            if (newState == KafkaStreams.State.ERROR) {
                LOGGER.error("❌ OI Stream {} entered ERROR state!", instanceKey);
            } else if (newState == KafkaStreams.State.RUNNING) {
                LOGGER.info("✅ OI Stream {} is now RUNNING", instanceKey);
            }
        });

        // Exception handler
        streams.setUncaughtExceptionHandler((Throwable exception) -> {
            LOGGER.error("❌ Uncaught exception in {}-minute OI stream: ", windowSize, exception);
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });

        // Start streams
        try {
            streams.start();
            LOGGER.info("✅ Started OI Streams: {}, window size: {}m", instanceKey, windowSize);
        } catch (Exception e) {
            LOGGER.error("❌ Failed to start OI Streams for {}: ", instanceKey, e);
            streamsInstances.remove(instanceKey);
            throw new RuntimeException("Failed to start OI Streams for " + instanceKey, e);
        }

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("🛑 Shutting down {}-minute OI stream", windowSize);
            try {
                streams.close(Duration.ofSeconds(30));
                streamsInstances.remove(instanceKey);
                LOGGER.info("✅ Successfully shut down {}-minute OI stream", windowSize);
            } catch (Exception e) {
                LOGGER.error("❌ Error during shutdown: ", e);
            }
        }, "shutdown-hook-oi-" + instanceKey));
    }

    /**
     * Process raw OI data into 1-minute OI metrics.
     */
    private final InstrumentMetadataService instrumentMetadataService;

    public OIProcessor(InstrumentMetadataService instrumentMetadataService) {
        this.instrumentMetadataService = instrumentMetadataService;
    }

    private void processOIData(StreamsBuilder builder, String inputTopic, String outputTopic, int windowSizeMinutes) {
        // 1) Read OI updates
        KStream<String, OpenInterest> raw = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), OpenInterest.serde())
                        .withTimestampExtractor(new OITimestampExtractorWithWindowOffset(windowSizeMinutes))
        );

        // 2) Extract token from composite key (e.g., "N|52343" -> "52343")
        KStream<String, OpenInterest> keyed = raw
                .mapValues(oi -> {
                    if (oi == null || oi.getOpenInterest() == null) return oi;
                    long v = oi.getOpenInterest();
                    if (oiScaleUseLots) {
                        long lot = instrumentMetadataService.getLotSize(oi.getExchange(), oi.getExchangeType(), String.valueOf(oi.getToken()), oi.getCompanyName(), 1L);
                        if (lot > 1) {
                            oi.setOpenInterest(v * lot);
                            return oi;
                        }
                    }
                    if (oiValueScale != 1.0) {
                        long scaled = (long) Math.round(v * oiValueScale);
                        oi.setOpenInterest(scaled);
                    }
                    return oi;
                })
                .selectKey((k, oi) -> {
                    if (oi == null) return k;
                    String exch = oi.getExchange() == null ? "-" : oi.getExchange();
                    String ext = oi.getExchangeType() == null ? "-" : oi.getExchangeType();
                    String tok = String.valueOf(oi.getToken());
                    return exch + ":" + ext + ":" + tok;
                })
                .filter((k, oi) -> oi != null && oi.getOpenInterest() != null);

        if (filterEnabled) {
            keyed = keyed.filter((k, oi) -> oi != null && oi.getToken() == filterToken);
        }

        // 3) Window & aggregate OI metrics
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
                Duration.ofMinutes(windowSizeMinutes),
                Duration.ofSeconds(Math.max(0, windowSizeMinutes == 1 ? graceSeconds1m : graceSecondsMulti))
        );

        KTable<Windowed<String>, OIAggregate> oiTable = keyed
                .groupByKey(Grouped.with(Serdes.String(), OpenInterest.serde()))
                .windowedBy(windows)
                .aggregate(
                        OIAggregate::new,
                        (token, oi, aggregate) -> {
                            aggregate.updateWithOI(oi);
                            return aggregate;
                        },
                        Materialized.<String, OIAggregate, WindowStore<Bytes, byte[]>>as("oi-aggregate-store-" + windowSizeMinutes + "m")
                                .withKeySerde(Serdes.String())
                                .withValueSerde(OIAggregate.serde())
                )
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        oiTable.toStream()
                .mapValues((windowedKey, aggregate) -> {
                    // Calculate derived metrics on window close
                    aggregate.calculateDerivedMetrics();
                    int offMin = com.kotsin.consumer.util.MarketTimeAligner.getWindowOffsetMinutes(aggregate.getExchange(), windowSizeMinutes);
                    long offMs = offMin * 60_000L;
                    aggregate.setWindowStartMillis(windowedKey.window().start() - offMs);
                    aggregate.setWindowEndMillis(windowedKey.window().end() - offMs);
                    return aggregate;
                })
                .map((windowedKey, aggregate) -> {
                    logOIDetails(aggregate, windowSizeMinutes);
                    return KeyValue.pair(windowedKey.key(), aggregate);
                })
                .to(outputTopic, Produced.with(Serdes.String(), OIAggregate.serde()));
    }

    /**
     * Wrapper to start multi-minute OI aggregation from 1-minute OI metrics.
     */
    public void processMultiMinuteOI(String appId, String inputTopic, String outputTopic, int windowSize) {
        String instanceKey = appId + "-" + windowSize + "m";
        if (streamsInstances.containsKey(instanceKey)) {
            LOGGER.warn("Streams app {} already running. Skipping duplicate start.", instanceKey);
            return;
        }

        Properties props = kafkaConfig.getStreamProperties(appId + "-" + windowSize + "m");
        StreamsBuilder builder = new StreamsBuilder();

        // Build multi-minute OI from 1-minute OI metrics
        buildMultiMinuteOI(builder, inputTopic, outputTopic, windowSize);

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streamsInstances.put(instanceKey, streams);

        // State listener
        streams.setStateListener((newState, oldState) -> {
            LOGGER.info("OI Streams state transition for {}-minute (cascaded): {} -> {}",
                    windowSize, oldState, newState);

            if (newState == KafkaStreams.State.ERROR) {
                LOGGER.error("❌ OI Stream {} entered ERROR state!", instanceKey);
            } else if (newState == KafkaStreams.State.RUNNING) {
                LOGGER.info("✅ OI Stream {} is now RUNNING (cascaded)", instanceKey);
            }
        });

        // Exception handler
        streams.setUncaughtExceptionHandler((Throwable exception) -> {
            LOGGER.error("❌ Uncaught exception in OI {}-minute stream (cascaded): ", windowSize, exception);

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
            LOGGER.info("✅ Started OI Streams (cascaded): {}, window size: {}m", instanceKey, windowSize);
        } catch (Exception e) {
            LOGGER.error("❌ Failed to start OI Streams for {}: ", instanceKey, e);
            streamsInstances.remove(instanceKey);
            throw new RuntimeException("Failed to start OI streams for " + instanceKey, e);
        }

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("🛑 Shutting down OI {}-minute stream (cascaded)", windowSize);
            try {
                streams.close(Duration.ofSeconds(30));
                streamsInstances.remove(instanceKey);
                LOGGER.info("✅ Successfully shut down OI {}-minute stream (cascaded)", windowSize);
            } catch (Exception e) {
                LOGGER.error("❌ Error during shutdown: ", e);
            }
        }, "shutdown-hook-oi-cascaded-" + instanceKey));
    }

    /**
     * Aggregate multi-minute OI metrics from 1-minute metrics with CORRECT NSE alignment.
     */
    private void buildMultiMinuteOI(StreamsBuilder builder, String inputTopic, String outputTopic, int windowSize) {
        KStream<String, OIAggregate> mins = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), OIAggregate.serde())
                        .withTimestampExtractor(new MultiMinuteOffsetTimestampExtractorForOI(windowSize))
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

        KTable<Windowed<String>, OIAggregate> aggregated = mins
                .groupByKey(Grouped.with(Serdes.String(), OIAggregate.serde()))
                .windowedBy(windows)
                .aggregate(
                        OIAggregate::new,
                        (sym, agg, total) -> {
                            total.updateAggregate(agg);
                            return total;
                        },
                        Materialized.<String, OIAggregate, WindowStore<Bytes, byte[]>>as("agg-oi-store-" + windowSize + "m")
                                .withKeySerde(Serdes.String())
                                .withValueSerde(OIAggregate.serde())
                )
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        aggregated.toStream()
                .map((wk, agg) -> {
                    // Remove the alignment shift for display/start-end correctness
                    int offMin = com.kotsin.consumer.util.MarketTimeAligner.getWindowOffsetMinutes(agg.getExchange(), windowSize);
                    long offMs = offMin * 60_000L;
                    agg.setWindowStartMillis(wk.window().start() - offMs);
                    agg.setWindowEndMillis(wk.window().end() - offMs);
                    agg.calculateDerivedMetrics();
                    logOIDetails(agg, windowSize);
                    return KeyValue.pair(wk.key(), agg);
                })
                .to(outputTopic, Produced.with(Serdes.String(), OIAggregate.serde()));
    }

    /**
     * Log OI details for debugging.
     */
    private void logOIDetails(OIAggregate aggregate, int windowSizeMinutes) {
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
        LOGGER.debug("{}m OI for {}: window: {}-{}, OI: {} → {}, Change: {}, Put/Call: {} / {} (Ratio: {:.2f})",
                windowSizeMinutes, aggregate.getCompanyName(),
                windowStart.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                windowEnd.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                aggregate.getOiOpen(), aggregate.getOiClose(), aggregate.getOiChange(),
                aggregate.getPutOI(), aggregate.getCallOI(),
                aggregate.getPutCallRatio() != null ? aggregate.getPutCallRatio() : 0.0);
    }

    /**
     * Start all OI processors on application startup.
     */
    @PostConstruct
    public void start() {
        try {
            LOGGER.info("🚀 Starting OI Metrics Processor with bootstrap servers: {}",
                    kafkaConfig.getBootstrapServers());

            String baseAppId = "prod-unified-oi";

            // Build ONLY 1-minute OI metrics from raw OI updates (high precision, low grace period)
            process(baseAppId, "OpenInterest", "oi-metrics-1m", 1);
            Thread.sleep(500);

            // Build multi-minute OI metrics from 1-minute OI metrics (cascading aggregation)
            // This ensures accurate open/close values without tick-level lag issues
            processMultiMinuteOI(baseAppId, "oi-metrics-1m", "oi-metrics-2m", 2);
            Thread.sleep(500);

            processMultiMinuteOI(baseAppId, "oi-metrics-1m", "oi-metrics-3m", 3);
            Thread.sleep(500);

            processMultiMinuteOI(baseAppId, "oi-metrics-1m", "oi-metrics-5m", 5);
            Thread.sleep(500);

            processMultiMinuteOI(baseAppId, "oi-metrics-1m", "oi-metrics-15m", 15);
            Thread.sleep(500);

            processMultiMinuteOI(baseAppId, "oi-metrics-1m", "oi-metrics-30m", 30);

            LOGGER.info("✅ All OI Processors started successfully (1m from raw OI, rest cascaded)");
            logStreamStates();

        } catch (Exception e) {
            LOGGER.error("❌ Error starting OI Processors", e);
            throw new RuntimeException("Failed to start OI processors", e);
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
        LOGGER.info("📊 Current OI Stream States:");
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
            LOGGER.info("🛑 Stopping OI stream: {}", instanceKey);
            try {
                streams.close(Duration.ofSeconds(30));
                streamsInstances.remove(instanceKey);
                LOGGER.info("✅ Successfully stopped OI stream: {}", instanceKey);
            } catch (Exception e) {
                LOGGER.error("❌ Error stopping OI stream {}: ", instanceKey, e);
            }
        } else {
            LOGGER.warn("⚠️ OI Stream {} not found", instanceKey);
        }
    }

    /**
     * Stop all streams gracefully.
     */
    @PreDestroy
    public void stopAllStreams() {
        LOGGER.info("🛑 Stopping all {} OI streams", streamsInstances.size());
        List<String> keys = new ArrayList<>(streamsInstances.keySet());
        for (String key : keys) {
            stopStream(key);
        }
        LOGGER.info("✅ All OI streams stopped");
    }
}
