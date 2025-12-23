package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.model.*;
import com.kotsin.consumer.timeExtractor.MultiMinuteOffsetTimestampExtractor;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * UnifiedCandleProcessor - Joins Candle + Orderbook + OI into UnifiedCandle
 * 
 * Data Flow:
 * 1. Input: candle-ohlv-{tf}, orderbook-ohlv-{tf}, oi-ohlv-{tf}
 * 2. Join by scripCode (left join - candle is always present, OB/OI optional)
 * 3. Output: unified-candle-{tf}
 * 
 * Timeframes: 1m, 2m, 3m, 5m, 15m, 30m
 */
@Component
public class UnifiedCandleProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(UnifiedCandleProcessor.class);

    @Autowired
    private KafkaConfig kafkaConfig;

    private final Map<String, KafkaStreams> streamsInstances = new HashMap<>();

    // Grace period configuration
    @Value("${unified.window.grace.seconds:10}")
    private int graceSeconds;

    // Topic prefixes
    @Value("${unified.input.candle.prefix:candle-ohlv-}")
    private String candleTopicPrefix;

    @Value("${unified.input.orderbook.prefix:orderbook-ohlv-}")
    private String orderbookTopicPrefix;

    @Value("${unified.input.oi.prefix:oi-ohlv-}")
    private String oiTopicPrefix;

    @Value("${unified.output.prefix:unified-candle-}")
    private String outputTopicPrefix;

    // Which timeframes to process
    @Value("${unified.timeframes:1m,2m,3m,5m,15m,30m}")
    private String timeframesConfig;

    // Feature toggle
    @Value("${unified.enabled:true}")
    private boolean enabled;

    /**
     * Process a single timeframe: join candle + orderbook + OI into UnifiedCandle
     */
    public void processTimeframe(String timeframe) {
        int windowMinutes = parseTimeframe(timeframe);
        String instanceKey = "unified-candle-" + timeframe;

        if (streamsInstances.containsKey(instanceKey)) {
            LOGGER.warn("Streams app {} already running. Skipping duplicate start.", instanceKey);
            return;
        }

        String candleTopic = candleTopicPrefix + timeframe;
        String orderbookTopic = orderbookTopicPrefix + timeframe;
        String oiTopic = oiTopicPrefix + timeframe;
        String outputTopic = outputTopicPrefix + timeframe;

        Properties props = kafkaConfig.getStreamProperties(instanceKey);
        StreamsBuilder builder = new StreamsBuilder();

        buildJoinTopology(builder, candleTopic, orderbookTopic, oiTopic, outputTopic, timeframe, windowMinutes);

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streamsInstances.put(instanceKey, streams);

        // State listener
        streams.setStateListener((newState, oldState) -> {
            LOGGER.info("UnifiedCandleProcessor {} state: {} -> {}", timeframe, oldState, newState);
            if (newState == KafkaStreams.State.ERROR) {
                LOGGER.error("❌ UnifiedCandleProcessor {} entered ERROR state!", timeframe);
            }
        });

        // Exception handler
        streams.setUncaughtExceptionHandler((Throwable exception) -> {
            LOGGER.error("❌ Exception in UnifiedCandleProcessor {}: ", timeframe, exception);
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });

        try {
            streams.start();
            LOGGER.info("✅ Started UnifiedCandleProcessor for {}", timeframe);
        } catch (Exception e) {
            LOGGER.error("❌ Failed to start UnifiedCandleProcessor for {}: ", timeframe, e);
            streamsInstances.remove(instanceKey);
        }
    }

    /**
     * Build the join topology for one timeframe
     */
    private void buildJoinTopology(StreamsBuilder builder,
                                   String candleTopic,
                                   String orderbookTopic,
                                   String oiTopic,
                                   String outputTopic,
                                   String timeframe,
                                   int windowMinutes) {

        // Read candle stream (primary)
        KStream<String, EnrichedCandlestick> candleStream = builder.stream(
                candleTopic,
                Consumed.with(Serdes.String(), EnrichedCandlestick.serde())
        );

        // Read orderbook stream
        KStream<String, OrderbookAggregate> orderbookStream = builder.stream(
                orderbookTopic,
                Consumed.with(Serdes.String(), OrderbookAggregate.serde())
        );

        // Read OI stream
        KStream<String, OIAggregate> oiStream = builder.stream(
                oiTopic,
                Consumed.with(Serdes.String(), OIAggregate.serde())
        );

        // Convert streams to tables for joining (latest value per key per window)
        Duration windowDuration = Duration.ofMinutes(windowMinutes);
        Duration graceDuration = Duration.ofSeconds(graceSeconds);

        TimeWindows windows = TimeWindows.ofSizeAndGrace(windowDuration, graceDuration);

        // Aggregate candles into KTable (should already be single value per window from upstream)
        KTable<Windowed<String>, EnrichedCandlestick> candleTable = candleStream
                .groupByKey(Grouped.with(Serdes.String(), EnrichedCandlestick.serde()))
                .windowedBy(windows)
                .reduce((agg, newVal) -> newVal,  // Take latest
                        Materialized.<String, EnrichedCandlestick, WindowStore<Bytes, byte[]>>as("unified-candle-table-" + timeframe)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(EnrichedCandlestick.serde())
                );

        // Aggregate orderbook into KTable
        KTable<Windowed<String>, OrderbookAggregate> orderbookTable = orderbookStream
                .groupByKey(Grouped.with(Serdes.String(), OrderbookAggregate.serde()))
                .windowedBy(windows)
                .reduce((agg, newVal) -> newVal,  // Take latest
                        Materialized.<String, OrderbookAggregate, WindowStore<Bytes, byte[]>>as("unified-ob-table-" + timeframe)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(OrderbookAggregate.serde())
                );

        // Aggregate OI into KTable
        KTable<Windowed<String>, OIAggregate> oiTable = oiStream
                .groupByKey(Grouped.with(Serdes.String(), OIAggregate.serde()))
                .windowedBy(windows)
                .reduce((agg, newVal) -> newVal,  // Take latest
                        Materialized.<String, OIAggregate, WindowStore<Bytes, byte[]>>as("unified-oi-table-" + timeframe)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(OIAggregate.serde())
                );

        // Left join candle with orderbook
        KTable<Windowed<String>, CandleWithOrderbook> candleWithOb = candleTable.leftJoin(
                orderbookTable,
                (candle, ob) -> new CandleWithOrderbook(candle, ob),
                Materialized.<Windowed<String>, CandleWithOrderbook, org.apache.kafka.streams.state.KeyValueStore<Bytes, byte[]>>
                        as("unified-candle-ob-join-" + timeframe)
                        .withKeySerde(new WindowedSerdes.TimeWindowedSerde<>(Serdes.String(), windowDuration.toMillis()))
                        .withValueSerde(CandleWithOrderbook.serde())
        );

        // Left join with OI
        KTable<Windowed<String>, UnifiedCandle> unifiedTable = candleWithOb.leftJoin(
                oiTable,
                (candleOb, oi) -> UnifiedCandle.from(
                        candleOb != null ? candleOb.candle : null,
                        candleOb != null ? candleOb.orderbook : null,
                        oi,
                        timeframe
                ),
                Materialized.<Windowed<String>, UnifiedCandle, org.apache.kafka.streams.state.KeyValueStore<Bytes, byte[]>>
                        as("unified-final-join-" + timeframe)
                        .withKeySerde(new WindowedSerdes.TimeWindowedSerde<>(Serdes.String(), windowDuration.toMillis()))
                        .withValueSerde(UnifiedCandle.serde())
        );

        // Suppress until window closes and emit
        unifiedTable
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
                .toStream()
                .filter((wk, unified) -> unified != null && unified.hasValidPrice())
                .peek((wk, unified) -> {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("📦 UnifiedCandle {} | scrip={} OHLC={}/{}/{}/{} OFI={} OI={}",
                                timeframe, unified.getScripCode(),
                                unified.getOpen(), unified.getHigh(), unified.getLow(), unified.getClose(),
                                unified.getOfi(), unified.getOiClose());
                    }
                })
                .map((wk, unified) -> {
                    // Correct window timestamps
                    String exchange = unified.getExchange();
                    int offMin = MarketTimeAligner.getWindowOffsetMinutes(exchange, windowMinutes);
                    long offMs = offMin * 60_000L;
                    
                    unified.setWindowStartMillis(wk.window().start() - offMs);
                    unified.setWindowEndMillis(wk.window().end() - offMs);
                    unified.updateHumanReadableTimestamps();
                    
                    return KeyValue.pair(wk.key(), unified);
                })
                .to(outputTopic, Produced.with(Serdes.String(), UnifiedCandle.serde()));

        LOGGER.info("📐 Built UnifiedCandle join topology for {} -> {}", timeframe, outputTopic);
    }

    /**
     * Intermediate join result (candle + orderbook)
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CandleWithOrderbook {
        public EnrichedCandlestick candle;
        public OrderbookAggregate orderbook;

        public static org.apache.kafka.common.serialization.Serde<CandleWithOrderbook> serde() {
            return Serdes.serdeFrom(
                    new CandleWithOrderbookSerializer(),
                    new CandleWithOrderbookDeserializer()
            );
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = 
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static class CandleWithOrderbookSerializer implements org.apache.kafka.common.serialization.Serializer<CandleWithOrderbook> {
        @Override
        public byte[] serialize(String topic, CandleWithOrderbook data) {
            if (data == null) return null;
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed", e);
            }
        }
    }

    public static class CandleWithOrderbookDeserializer implements org.apache.kafka.common.serialization.Deserializer<CandleWithOrderbook> {
        @Override
        public CandleWithOrderbook deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return MAPPER.readValue(bytes, CandleWithOrderbook.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed", e);
            }
        }
    }

    /**
     * Parse timeframe string to minutes
     */
    private int parseTimeframe(String tf) {
        if (tf.endsWith("m")) {
            return Integer.parseInt(tf.substring(0, tf.length() - 1));
        }
        throw new IllegalArgumentException("Invalid timeframe: " + tf);
    }

    /**
     * Start all UnifiedCandle processors on application startup
     */
    @PostConstruct
    public void start() {
        if (!enabled) {
            LOGGER.info("⏸️ UnifiedCandleProcessor is disabled");
            return;
        }

        LOGGER.info("🚀 Scheduling UnifiedCandleProcessor startup...");

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                // Wait for upstream processors to start
                Thread.sleep(5000);

                String[] timeframes = timeframesConfig.split(",");
                for (String tf : timeframes) {
                    String timeframe = tf.trim();
                    if (!timeframe.isEmpty()) {
                        processTimeframe(timeframe);
                        Thread.sleep(1000);  // Small delay between starts
                    }
                }

                LOGGER.info("✅ All UnifiedCandleProcessors started successfully");
                logStreamStates();

            } catch (Exception e) {
                LOGGER.error("❌ Error starting UnifiedCandleProcessors", e);
            }
        });
    }

    /**
     * Get current states of all streams
     */
    public Map<String, KafkaStreams.State> getStreamStates() {
        Map<String, KafkaStreams.State> states = new HashMap<>();
        streamsInstances.forEach((key, streams) -> states.put(key, streams.state()));
        return states;
    }

    /**
     * Log current states
     */
    public void logStreamStates() {
        LOGGER.info("📊 UnifiedCandleProcessor Stream States:");
        getStreamStates().forEach((key, state) -> {
            String emoji = state == KafkaStreams.State.RUNNING ? "✅" : 
                          state == KafkaStreams.State.ERROR ? "❌" : "⚠️";
            LOGGER.info("  {} {}: {}", emoji, key, state);
        });
    }

    /**
     * Stop all streams gracefully
     */
    @PreDestroy
    public void stopAllStreams() {
        LOGGER.info("🛑 Stopping all {} UnifiedCandleProcessor streams", streamsInstances.size());
        List<String> keys = new ArrayList<>(streamsInstances.keySet());
        for (String key : keys) {
            KafkaStreams streams = streamsInstances.get(key);
            if (streams != null) {
                try {
                    streams.close(Duration.ofSeconds(30));
                    LOGGER.info("✅ Stopped {}", key);
                } catch (Exception e) {
                    LOGGER.error("❌ Error stopping {}: ", key, e);
                }
            }
        }
        streamsInstances.clear();
        LOGGER.info("✅ All UnifiedCandleProcessor streams stopped");
    }
}
