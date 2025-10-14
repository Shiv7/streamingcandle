package com.kotsin.consumer.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.OpenInterestAggregation;
import com.kotsin.consumer.model.OpenInterestData;
import com.kotsin.consumer.timeExtractor.OpenInterestTimestampExtractor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.apache.kafka.streams.kstream.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * Kafka Streams processor for Open Interest (OI) aggregation with advanced microstructure features.
 * 
 * Architecture:
 * 1. Consumes raw OI updates from "OpenInterest" topic
 * 2. Aggregates into time windows: 1m, 2m, 3m, 5m, 15m, 30m
 * 3. Computes quant features:
 *    - OI Delta & Momentum (trend strength)
 *    - OI Concentration (buildup intensity)
 *    - OI Volatility (regime detection)
 *    - Update frequency metrics
 * 
 * Trading Applications:
 * - Detect position buildup/unwinding in derivatives
 * - Identify bullish/bearish sentiment shifts
 * - Spot OI-price divergences (trapped traders)
 * - Measure market participation intensity
 */
@Component
public class OpenInterestProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenInterestProcessor.class);

    private final KafkaConfig kafkaConfig;
    private final Map<String, KafkaStreams> streamsInstances = new HashMap<>();

    @Value("${spring.kafka.streams.application-id:realtime-oi}")
    private String appIdPrefix;

    @Value("${openinterest.input.topic:OpenInterest}")
    private String inputTopic;

    @Value("${openinterest.output.topic.1min:1-min-oi-friday}")
    private String output1MinTopic;

    @Value("${openinterest.output.topic.2min:2-min-oi-friday}")
    private String output2MinTopic;

    @Value("${openinterest.output.topic.3min:3-min-oi-friday}")
    private String output3MinTopic;

    @Value("${openinterest.output.topic.5min:5-min-oi-friday}")
    private String output5MinTopic;

    @Value("${openinterest.output.topic.15min:15-min-oi-friday}")
    private String output15MinTopic;

    @Value("${openinterest.output.topic.30min:30-min-oi-friday}")
    private String output30MinTopic;

    @Value("${openinterest.enabled:true}")
    private boolean enabled;

    public OpenInterestProcessor(KafkaConfig kafkaConfig) {
        this.kafkaConfig = kafkaConfig;
    }

    /**
     * Starts OI aggregation streams for all configured windows.
     */
    @PostConstruct
    public void start() {
        if (!enabled) {
            LOGGER.info("⏸️ OpenInterest processor is disabled via configuration");
            return;
        }

        try {
            LOGGER.info("🚀 Starting OpenInterest Processor with bootstrap servers: {}, appIdPrefix: {}",
                    kafkaConfig.getBootstrapServers(), appIdPrefix);

            // Start all window aggregations
            processOI(appIdPrefix + "-oi-1min", inputTopic, output1MinTopic, 1);
            Thread.sleep(500);

            processOI(appIdPrefix + "-oi-2min", inputTopic, output2MinTopic, 2);
            Thread.sleep(500);

            processOI(appIdPrefix + "-oi-3min", inputTopic, output3MinTopic, 3);
            Thread.sleep(500);

            processOI(appIdPrefix + "-oi-5min", inputTopic, output5MinTopic, 5);
            Thread.sleep(500);

            if (!output15MinTopic.isEmpty()) {
                processOI(appIdPrefix + "-oi-15min", inputTopic, output15MinTopic, 15);
                Thread.sleep(500);
            }

            if (!output30MinTopic.isEmpty()) {
                processOI(appIdPrefix + "-oi-30min", inputTopic, output30MinTopic, 30);
            }

            LOGGER.info("✅ All OpenInterest Processors started successfully");

        } catch (Exception e) {
            LOGGER.error("❌ Error starting OpenInterest Processors", e);
            throw new RuntimeException("Failed to start OpenInterest processors", e);
        }
    }

    /**
     * Creates and starts a Kafka Streams topology for OI aggregation.
     */
    private void processOI(String appId, String inputTopic, String outputTopic, int windowMinutes) {
        String instanceKey = appId + "-" + windowMinutes + "m";
        if (streamsInstances.containsKey(instanceKey)) {
            LOGGER.warn("OI Streams app {} already running. Skipping duplicate start.", instanceKey);
            return;
        }

        Properties props = kafkaConfig.getStreamProperties(instanceKey);
        StreamsBuilder builder = new StreamsBuilder();

        // Build topology with custom timestamp extractor
        KStream<String, OpenInterestData> oiStream = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), OpenInterestData.serde())
                        .withTimestampExtractor(new OpenInterestTimestampExtractor())
        );

        // Key by token (scrip identifier)
        KStream<String, OpenInterestData> keyedStream = oiStream
                .selectKey((key, value) -> value.getScripKey())
                .filter((key, value) -> value.getOpenInterest() >= 0);  // Filter invalid OI

        // Aggregate into time windows with advanced features
        // FIXED: Add grace period like Orderbook processing to prevent lag
        TimeWindows timeWindows = TimeWindows
                .ofSizeAndGrace(Duration.ofMinutes(windowMinutes), Duration.ofSeconds(5))
                .advanceBy(Duration.ofMinutes(windowMinutes));

        KTable<Windowed<String>, OpenInterestAggregation> aggregated = keyedStream
                .groupByKey(Grouped.with(Serdes.String(), OpenInterestData.serde()))
                .windowedBy(timeWindows)
                .aggregate(
                        OpenInterestAggState::new,  // Initializer
                        this::aggregateOI,          // Aggregator
                        Materialized.with(Serdes.String(), OpenInterestAggStateSerde.serde())
                )
                .mapValues(this::computeFinalMetrics)  // Compute derived metrics
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));  // FIXED: Add suppress like Orderbook

        // Output to topic
        aggregated
                .toStream((windowedKey, value) -> windowedKey.key())
                .filter((key, value) -> value != null)  // Filter nulls
                .peek((key, value) -> LOGGER.debug("OI {}m aggregation for token {}: OI {} -> {}, Delta: {}",
                        windowMinutes, key, value.getOpenInterestStart(), value.getOpenInterestEnd(),
                        value.getOiChangeAbsolute()))
                .to(outputTopic, Produced.with(Serdes.String(), OpenInterestAggregation.serde()));

        // Start streams
        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streamsInstances.put(instanceKey, streams);

        streams.setStateListener((newState, oldState) -> {
            LOGGER.info("OI Streams state transition for {}: {} -> {}", instanceKey, oldState, newState);
            if (newState == KafkaStreams.State.ERROR) {
                LOGGER.error("❌ OI Stream {} entered ERROR state!", instanceKey);
            } else if (newState == KafkaStreams.State.RUNNING) {
                LOGGER.info("✅ OI Stream {} is RUNNING", instanceKey);
            }
        });

        streams.setUncaughtExceptionHandler((Throwable throwable) -> {
            LOGGER.error("❌ Uncaught exception in OI stream {}: ", instanceKey, throwable);
            // Continue processing with a replacement thread
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });

        try {
            streams.start();
            LOGGER.info("✅ Started OI Kafka Streams: {} (window: {}m, output: {})",
                    instanceKey, windowMinutes, outputTopic);
        } catch (Exception e) {
            LOGGER.error("❌ Failed to start OI Streams {}: ", instanceKey, e);
            streamsInstances.remove(instanceKey);
            throw new RuntimeException("Failed to start OI Streams " + instanceKey, e);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("🛑 Shutting down OI stream {}", instanceKey);
            try {
                streams.close(Duration.ofSeconds(30));
                streamsInstances.remove(instanceKey);
                LOGGER.info("✅ OI stream {} shut down successfully", instanceKey);
            } catch (Exception e) {
                LOGGER.error("❌ Error shutting down OI stream {}: ", instanceKey, e);
            }
        }, "shutdown-hook-oi-" + instanceKey));
    }

    /**
     * Aggregates OI updates into windowed state.
     */
    private OpenInterestAggState aggregateOI(String key, OpenInterestData newData, OpenInterestAggState aggState) {
        // Initialize on first update
        if (aggState.updateCount == 0) {
            aggState.token = newData.getToken();
            aggState.companyName = newData.getCompanyName();
            aggState.exchange = newData.getExchange();
            aggState.exchangeType = newData.getExchangeType();
            aggState.openInterestStart = newData.getOpenInterest();
            aggState.openInterestHigh = newData.getOpenInterest();
            aggState.openInterestLow = newData.getOpenInterest();
            aggState.windowStartTime = newData.getTimestamp();
            aggState.oiValues = new ArrayList<>();
        }

        // Update metrics
        aggState.updateCount++;
        aggState.openInterestEnd = newData.getOpenInterest();
        aggState.windowEndTime = newData.getTimestamp();
        aggState.lastReceivedTimestamp = newData.getReceivedTimestamp();

        // Track high/low
        if (newData.getOpenInterest() > aggState.openInterestHigh) {
            aggState.openInterestHigh = newData.getOpenInterest();
        }
        if (newData.getOpenInterest() < aggState.openInterestLow) {
            aggState.openInterestLow = newData.getOpenInterest();
        }

        // Store OI values for volatility calculation
        aggState.oiValues.add(newData.getOpenInterest());

        // Track cumulative OI changes
        if (aggState.previousOI != null) {
            aggState.cumulativeOiChange += (newData.getOpenInterest() - aggState.previousOI);
        }
        aggState.previousOI = newData.getOpenInterest();

        return aggState;
    }

    /**
     * Computes final derived metrics from aggregated state.
     */
    private OpenInterestAggregation computeFinalMetrics(OpenInterestAggState state) {
        if (state.updateCount == 0) {
            return null;  // Skip empty windows
        }

        // Calculate OI changes
        long oiDelta = state.openInterestEnd - state.openInterestStart;
        double oiChangePercent = state.openInterestStart > 0
                ? (oiDelta * 100.0) / state.openInterestStart
                : 0.0;

        // Calculate window duration in minutes
        long windowDurationMs = state.windowEndTime - state.windowStartTime;
        double windowDurationMinutes = Math.max(windowDurationMs / 60000.0, 0.016667);  // Min 1 second

        // OI Momentum: OI change per minute (even if window is small, show rate)
        double oiMomentum = windowDurationMinutes > 0 ? oiDelta / windowDurationMinutes : oiDelta;

        // OI Concentration: End/Start ratio (>1 = buildup, <1 = unwinding)
        double oiConcentration = state.openInterestStart > 0
                ? (double) state.openInterestEnd / state.openInterestStart
                : 1.0;

        // Calculate OI volatility (standard deviation)
        double oiVolatility = calculateStdDev(state.oiValues);

        // Average OI per update
        double avgOiPerUpdate = state.updateCount > 0
                ? state.oiValues.stream().mapToLong(Long::longValue).average().orElse(0.0)
                : 0.0;

        return OpenInterestAggregation.builder()
                .token(state.token)
                .companyName(state.companyName)
                .exchange(state.exchange)
                .exchangeType(state.exchangeType)
                .windowStartTime(state.windowStartTime)
                .windowEndTime(state.windowEndTime)
                .windowSizeMinutes((int) Math.round(windowDurationMinutes))
                .openInterestStart(state.openInterestStart)
                .openInterestEnd(state.openInterestEnd)
                .openInterestHigh(state.openInterestHigh)
                .openInterestLow(state.openInterestLow)
                .oiChangeAbsolute(oiDelta)
                .oiChangePercent(oiChangePercent)
                .oiMomentum(oiMomentum)
                .oiConcentration(oiConcentration)
                .cumulativeOiChange(state.cumulativeOiChange)
                .oiVolatility(oiVolatility)
                .updateCount(state.updateCount)
                .avgOiPerUpdate(avgOiPerUpdate)
                .lastReceivedTimestamp(state.lastReceivedTimestamp)
                .build();
    }

    /**
     * Calculates standard deviation for OI volatility measurement.
     */
    private double calculateStdDev(List<Long> values) {
        if (values == null || values.size() < 2) {
            return 0.0;
        }

        double mean = values.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0.0);

        return Math.sqrt(variance);
    }

    /**
     * Cleanup on application shutdown.
     */
    @PreDestroy
    public void cleanup() {
        LOGGER.info("🛑 Shutting down all OI Kafka Streams instances...");
        streamsInstances.forEach((key, streams) -> {
            try {
                streams.close(Duration.ofSeconds(10));
                LOGGER.info("✅ Closed OI stream: {}", key);
            } catch (Exception e) {
                LOGGER.error("❌ Error closing OI stream {}: ", key, e);
            }
        });
        streamsInstances.clear();
    }

    // ====================================================================
    // Internal State Management
    // ====================================================================

    /**
     * Intermediate aggregation state for OI processing.
     */
    @Data
    static class OpenInterestAggState {
        private int token;
        private String companyName;
        private String exchange;
        private String exchangeType;
        private long windowStartTime;
        private long windowEndTime;
        private long openInterestStart;
        private long openInterestEnd;
        private long openInterestHigh;
        private long openInterestLow;
        private int updateCount;
        private long cumulativeOiChange;
        private Long previousOI;  // For calculating incremental changes
        private List<Long> oiValues;  // For volatility calculation
        private long lastReceivedTimestamp;

        OpenInterestAggState() {
            this.oiValues = new ArrayList<>();
            this.updateCount = 0;
            this.cumulativeOiChange = 0;
        }
    }

    /**
     * Custom Serde for OpenInterestAggState.
     */
    static class OpenInterestAggStateSerde {
        private static final ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        static Serde<OpenInterestAggState> serde() {
            return Serdes.serdeFrom(
                    (topic, data) -> {
                        try {
                            return mapper.writeValueAsBytes(data);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to serialize OpenInterestAggState", e);
                        }
                    },
                    (topic, bytes) -> {
                        try {
                            return mapper.readValue(bytes, OpenInterestAggState.class);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to deserialize OpenInterestAggState", e);
                        }
                    }
            );
        }
    }
}

