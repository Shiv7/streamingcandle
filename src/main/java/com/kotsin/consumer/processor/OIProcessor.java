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

        if (windowSize == 1) {
            processOIData(builder, inputTopic, outputTopic);
        } else {
            processMultiMinuteOI(builder, inputTopic, outputTopic, windowSize);
        }

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
    private void processOIData(StreamsBuilder builder, String inputTopic, String outputTopic) {
        // 1) Read OI updates
        KStream<String, OpenInterest> raw = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), OpenInterest.serde())
        );

        // 2) Extract token from composite key (e.g., "N|52343" -> "52343")
        KStream<String, OpenInterest> keyed = raw
                .selectKey((k, oi) -> {
                    if (oi != null && oi.getToken() != 0) {
                        return String.valueOf(oi.getToken());
                    }
                    if (k != null && k.contains("|")) {
                        String[] parts = k.split("\\|");
                        return parts.length > 1 ? parts[1] : k;
                    }
                    return k;
                })
                .filter((k, oi) -> oi != null && oi.getOpenInterest() != null);

        // 3) Window & aggregate OI metrics
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
                Duration.ofMinutes(1),
                Duration.ofSeconds(3)  // OI updates slower than ticks
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
                        Materialized.<String, OIAggregate, WindowStore<Bytes, byte[]>>as("oi-aggregate-store")
                                .withKeySerde(Serdes.String())
                                .withValueSerde(OIAggregate.serde())
                )
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        oiTable.toStream()
                .mapValues((windowedKey, aggregate) -> {
                    // Calculate derived metrics on window close
                    aggregate.calculateDerivedMetrics();
                    aggregate.setWindowStartMillis(windowedKey.window().start());
                    aggregate.setWindowEndMillis(windowedKey.window().end());
                    return aggregate;
                })
                .map((windowedKey, aggregate) -> {
                    logOIDetails(aggregate, 1);
                    return KeyValue.pair(windowedKey.key(), aggregate);
                })
                .to(outputTopic, Produced.with(Serdes.String(), OIAggregate.serde()));
    }

    /**
     * Aggregate multi-minute OI metrics from 1-minute metrics.
     */
    private void processMultiMinuteOI(StreamsBuilder builder,
                                      String inputTopic,
                                      String outputTopic,
                                      int windowSize) {

        KStream<String, OIAggregate> mins = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), OIAggregate.serde())
        );

        TimeWindows windows = TimeWindows.ofSizeAndGrace(
                Duration.ofMinutes(windowSize),
                Duration.ofSeconds(3)
        );

        KTable<Windowed<String>, OIAggregate> aggregated = mins
                .groupByKey(Grouped.with(Serdes.String(), OIAggregate.serde()))
                .windowedBy(windows)
                .aggregate(
                        OIAggregate::new,
                        (token, oiAgg, aggregate) -> {
                            aggregate.updateAggregate(oiAgg);
                            return aggregate;
                        },
                        Materialized.<String, OIAggregate, WindowStore<Bytes, byte[]>>as("agg-oi-store-" + windowSize + "m")
                                .withKeySerde(Serdes.String())
                                .withValueSerde(OIAggregate.serde())
                )
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        aggregated.toStream()
                .mapValues((windowedKey, aggregate) -> {
                    aggregate.calculateDerivedMetrics();
                    aggregate.setWindowStartMillis(windowedKey.window().start());
                    aggregate.setWindowEndMillis(windowedKey.window().end());
                    return aggregate;
                })
                .map((wk, aggregate) -> {
                    logOIDetails(aggregate, windowSize);
                    return KeyValue.pair(wk.key(), aggregate);
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

            process("realtime-oi-1min", "OpenInterest", "oi-metrics-1m", 1);
            Thread.sleep(1000);

            process("realtime-oi-2min", "oi-metrics-1m", "oi-metrics-2m", 2);
            Thread.sleep(1000);

            process("realtime-oi-3min", "oi-metrics-1m", "oi-metrics-3m", 3);
            Thread.sleep(1000);

            process("realtime-oi-5min", "oi-metrics-1m", "oi-metrics-5m", 5);
            Thread.sleep(1000);

            process("realtime-oi-15min", "oi-metrics-1m", "oi-metrics-15m", 15);
            Thread.sleep(1000);

            process("realtime-oi-30min", "oi-metrics-1m", "oi-metrics-30m", 30);

            LOGGER.info("✅ All OI Processors started successfully");
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

