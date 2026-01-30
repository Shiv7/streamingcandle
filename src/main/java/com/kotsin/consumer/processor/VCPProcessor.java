package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.config.VCPConfig;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.model.*;
import com.kotsin.consumer.model.SlimCandle;
import com.kotsin.consumer.service.VCPCalculator;
import com.kotsin.consumer.util.FamilyCandleConverter;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.WindowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.kotsin.consumer.util.TTLCache;

/**
 * VCPProcessor - Calculate VCP scores from FamilyCandle history
 *
 * Data Flow:
 * 1. Input: family-candle-{5m,15m,30m}
 * 2. Extract equity InstrumentCandle from FamilyCandle
 * 3. Maintain rolling history in state store (48/32/24 candles for 5m/15m/30m)
 * 4. Calculate VCP per timeframe on each new candle
 * 5. Output: vcp-signals-{5m,15m,30m} for per-TF results
 * 6. Output: vcp-combined for fused multi-TF output (emitted on 5m close)
 */
@Component
public class VCPProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(VCPProcessor.class);

    @Autowired
    private KafkaConfig kafkaConfig;

    @Autowired
    private VCPConfig vcpConfig;

    @Autowired
    private VCPCalculator vcpCalculator;

    private final Map<String, KafkaStreams> streamsInstances = new HashMap<>();

    // Topic configuration
    @Value("${vcp.input.prefix:family-candle-}")
    private String inputTopicPrefix;

    @Value("${vcp.output.signals.prefix:vcp-signals-}")
    private String signalsTopicPrefix;

    @Value("${vcp.output.combined:vcp-combined}")
    private String combinedTopic;

    // Feature toggle
    @Value("${vcp.enabled:true}")
    private boolean enabled;

    @Value("${vcp.cache.ttl.ms:300000}")
    private long cacheTtlMs;

    // TTL Cache for multi-timeframe fusion - PER SCRIPCODE
    private TTLCache<String, VCPCalculator.VCPResult> cached15mResults;
    private TTLCache<String, VCPCalculator.VCPResult> cached30mResults;
    private TTLCache<String, UnifiedCandle> lastCandleCache;  // For currentPrice fix

    /**
     * Process VCP for all timeframes
     */
    public void startVCPProcessors() {
        if (!enabled) {
            LOGGER.info("⏸️ VCPProcessor is disabled");
            return;
        }

        // Initialize TTL caches
        cached15mResults = new TTLCache<>("VCP-15m", cacheTtlMs, 1000, 30000);
        cached30mResults = new TTLCache<>("VCP-30m", cacheTtlMs, 1000, 30000);
        lastCandleCache = new TTLCache<>("VCP-LastCandle", 60000, 1000, 30000); // 1 min TTL

        LOGGER.info("✅ VCP caches initialized with TTL={}ms", cacheTtlMs);

        // Process each timeframe
        processVCPForTimeframe("5m", vcpConfig.getLookback5m());
        processVCPForTimeframe("15m", vcpConfig.getLookback15m());
        processVCPForTimeframe("30m", vcpConfig.getLookback30m());

        LOGGER.info("✅ All VCP processors started");
    }

    /**
     * Process VCP for a single timeframe
     */
    private void processVCPForTimeframe(String timeframe, int lookbackSize) {
        String instanceKey = "vcp-processor-" + timeframe;

        if (streamsInstances.containsKey(instanceKey)) {
            LOGGER.warn("VCP processor {} already running", instanceKey);
            return;
        }

        String inputTopic = inputTopicPrefix + timeframe;
        String outputTopic = signalsTopicPrefix + timeframe;

        Properties props = kafkaConfig.getStreamProperties(instanceKey);
        StreamsBuilder builder = new StreamsBuilder();

        buildVCPTopology(builder, inputTopic, outputTopic, timeframe, lookbackSize);

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streamsInstances.put(instanceKey, streams);

        streams.setStateListener((newState, oldState) -> {
            LOGGER.info("VCPProcessor {} state: {} -> {}", timeframe, oldState, newState);
        });

        streams.setUncaughtExceptionHandler((Throwable e) -> {
            LOGGER.error("❌ Exception in VCPProcessor {}: ", timeframe, e);
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });

        try {
            streams.start();
            LOGGER.info("✅ Started VCPProcessor for {}", timeframe);
        } catch (Exception e) {
            LOGGER.error("❌ Failed to start VCPProcessor for {}: ", timeframe, e);
            streamsInstances.remove(instanceKey);
        }
    }

    /**
     * Build VCP calculation topology for one timeframe
     */
    private void buildVCPTopology(StreamsBuilder builder,
                                  String inputTopic,
                                  String outputTopic,
                                  String timeframe,
                                  int lookbackSize) {

        // State store for candle history
        String historyStoreName = "vcp-history-" + timeframe;
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(historyStoreName),
                Serdes.String(),
                new CandleHistorySerde()
        ));

        // Read FamilyCandle stream
        KStream<String, FamilyCandle> input = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), FamilyCandle.serde())
        );

        // Cache last candle for currentPrice fix (only needed for 5m)
        if ("5m".equals(timeframe) && lastCandleCache != null) {
            input.foreach((k, v) -> {
                if (k != null && v != null && v.getEquity() != null) {
                    lastCandleCache.put(k, FamilyCandleConverter.toUnifiedCandle(v.getEquity()));
                }
            });
        }

        // Process: update history, calculate VCP, emit signals
        KStream<String, VCPCalculator.VCPResult> vcpResults = input.process(
                () -> new VCPHistoryProcessor(historyStoreName, lookbackSize, timeframe, vcpCalculator),
                historyStoreName
        );

        // Emit per-timeframe VCP signals
        vcpResults
                .filter((k, v) -> v != null && v.getScore() > 0)
                .peek((k, v) -> {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("📊 VCP {} | scrip={} score={} support={} resistance={}",
                                timeframe, k, 
                                String.format("%.3f", v.getScore()), 
                                String.format("%.3f", v.getSupportScore()), 
                                String.format("%.3f", v.getResistanceScore()));
                    }
                })
                .to(outputTopic, Produced.with(Serdes.String(), VCPResultSerde.serde()));

        // For 5m: also emit combined output fusing all timeframes
        if ("5m".equals(timeframe)) {
            vcpResults
                    .filter((k, v) -> v != null)
                    .mapValues((k, result5m) -> {
                        // Get last candle for this scripCode (for currentPrice fix)
                        UnifiedCandle current = lastCandleCache != null ? lastCandleCache.get(k) : null;

                        // Build combined output using per-scripCode cache
                        MTVCPOutput combined = vcpCalculator.buildCombinedOutput(
                                result5m,
                                cached15mResults != null ? cached15mResults.getOrDefault(k, VCPCalculator.VCPResult.empty()) : VCPCalculator.VCPResult.empty(),
                                cached30mResults != null ? cached30mResults.getOrDefault(k, VCPCalculator.VCPResult.empty()) : VCPCalculator.VCPResult.empty(),
                                current
                        );
                        combined.setScripCode(k);
                        return combined;
                    })
                    .filter((k, v) -> v != null)
                    .to(combinedTopic, Produced.with(Serdes.String(), MTVCPOutput.serde()));
        }

        // For 15m and 30m: cache results for fusion PER SCRIPCODE
        if ("15m".equals(timeframe)) {
            vcpResults.foreach((k, v) -> {
                if (v != null && k != null && cached15mResults != null) cached15mResults.put(k, v);
            });
        }
        if ("30m".equals(timeframe)) {
            vcpResults.foreach((k, v) -> {
                if (v != null && k != null && cached30mResults != null) cached30mResults.put(k, v);
            });
        }

        LOGGER.info("📐 Built VCP topology for {} -> {}", timeframe, outputTopic);
    }

    /**
     * Processor that maintains candle history and calculates VCP
     */
    private static class VCPHistoryProcessor implements Processor<String, FamilyCandle, String, VCPCalculator.VCPResult> {

        private final String storeName;
        private final int lookbackSize;
        private final String timeframe;
        private final VCPCalculator calculator;
        private ProcessorContext<String, VCPCalculator.VCPResult> context;
        private KeyValueStore<String, CandleHistory> historyStore;

        VCPHistoryProcessor(String storeName, int lookbackSize, String timeframe, VCPCalculator calculator) {
            this.storeName = storeName;
            this.lookbackSize = lookbackSize;
            this.timeframe = timeframe;
            this.calculator = calculator;
        }

        @Override
        public void init(ProcessorContext<String, VCPCalculator.VCPResult> context) {
            this.context = context;
            this.historyStore = context.getStateStore(storeName);
        }

        @Override
        public void process(Record<String, FamilyCandle> record) {
            String key = record.key();
            FamilyCandle familyCandle = record.value();

            if (key == null || familyCandle == null) return;

            // FIX: Use primary instrument - equity for NSE, future for MCX commodities
            InstrumentCandle primary = familyCandle.getEquity();
            if (primary == null) {
                // No equity - check if it's a commodity with futures
                primary = familyCandle.getFuture();
                if (primary == null) {
                    LOGGER.debug("No equity or future data in FamilyCandle for {} - skipping", key);
                    return;
                }
                LOGGER.debug("VCP: Processing commodity {} using futures as primary", key);
            }

            // Convert InstrumentCandle to UnifiedCandle for backwards compatibility
            UnifiedCandle candle = FamilyCandleConverter.toUnifiedCandle(primary);

            // Get or create history
            CandleHistory history = historyStore.get(key);
            if (history == null) {
                history = new CandleHistory(lookbackSize);
            }

            // Add new candle
            history.add(candle);

            // Save updated history
            historyStore.put(key, history);

            // Calculate VCP if we have enough history
            // FIX: Increased from 5 to 20 candles for reliable volume profile
            // 5 candles = 25 min (5m TF), too little for meaningful clusters
            // 20 candles = 100 min (5m TF), minimum for volume profile stability
            int minHistory = Math.max(20, lookbackSize / 3);  // At least 20, or 1/3 of lookback
            if (history.size() >= minHistory) {
                VCPCalculator.VCPResult result = calculator.calculateForTimeframe(
                        history.getCandles(), timeframe);

                context.forward(new Record<>(key, result, record.timestamp()));
            }
        }

        @Override
        public void close() {
            // Nothing to close
        }
    }

    /**
     * Candle history wrapper for state store.
     *
     * CRITICAL FIX: Uses SlimCandle instead of UnifiedCandle to prevent
     * RecordTooLargeException. UnifiedCandle has ~100+ fields including large
     * Maps that cause changelog records to exceed Kafka's 1MB limit.
     * SlimCandle stores only essential OHLCV + volume profile data (~200 bytes each).
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    public static class CandleHistory {
        // New slim format - used for storage
        private List<SlimCandle> slimCandles = new ArrayList<>();

        // Legacy field for backward compatibility during migration
        // Jackson will populate this if old format is deserialized
        @com.fasterxml.jackson.annotation.JsonProperty("candles")
        private List<UnifiedCandle> legacyCandles;

        private int maxSize = 48;

        public CandleHistory(int maxSize) {
            this.maxSize = maxSize;
        }

        public void add(UnifiedCandle candle) {
            // Migrate legacy candles on first add if present
            migrateLegacyIfNeeded();

            // Convert to slim version before storing
            slimCandles.add(SlimCandle.from(candle));
            // Trim to max size
            while (slimCandles.size() > maxSize) {
                slimCandles.remove(0);
            }
        }

        public int size() {
            migrateLegacyIfNeeded();
            return slimCandles.size();
        }

        /**
         * Get candles as UnifiedCandle list for backward compatibility with calculators.
         * JsonIgnore prevents conflict with legacyCandles @JsonProperty("candles").
         */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public List<UnifiedCandle> getCandles() {
            migrateLegacyIfNeeded();
            List<UnifiedCandle> result = new ArrayList<>(slimCandles.size());
            for (SlimCandle slim : slimCandles) {
                result.add(slim.toUnifiedCandle());
            }
            return result;
        }

        /**
         * Migrate legacy UnifiedCandle list to SlimCandle list (one-time operation)
         */
        private void migrateLegacyIfNeeded() {
            if (legacyCandles != null && !legacyCandles.isEmpty()) {
                for (UnifiedCandle legacy : legacyCandles) {
                    slimCandles.add(SlimCandle.from(legacy));
                }
                legacyCandles = null; // Clear after migration
            }
        }
    }

    /**
     * Serde for CandleHistory
     */
    public static class CandleHistorySerde implements org.apache.kafka.common.serialization.Serde<CandleHistory> {
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = 
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        @Override
        public org.apache.kafka.common.serialization.Serializer<CandleHistory> serializer() {
            return (topic, data) -> {
                if (data == null) return null;
                try {
                    return MAPPER.writeValueAsBytes(data);
                } catch (Exception e) {
                    throw new RuntimeException("Serialization failed", e);
                }
            };
        }

        @Override
        public org.apache.kafka.common.serialization.Deserializer<CandleHistory> deserializer() {
            return (topic, bytes) -> {
                if (bytes == null) return null;
                try {
                    return MAPPER.readValue(bytes, CandleHistory.class);
                } catch (Exception e) {
                    throw new RuntimeException("Deserialization failed", e);
                }
            };
        }
    }

    /**
     * Serde for VCPResult
     */
    public static class VCPResultSerde {
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = 
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        public static org.apache.kafka.common.serialization.Serde<VCPCalculator.VCPResult> serde() {
            return Serdes.serdeFrom(
                    (topic, data) -> {
                        if (data == null) return null;
                        try {
                            return MAPPER.writeValueAsBytes(data);
                        } catch (Exception e) {
                            throw new RuntimeException("Serialization failed", e);
                        }
                    },
                    (topic, bytes) -> {
                        if (bytes == null) return null;
                        try {
                            return MAPPER.readValue(bytes, VCPCalculator.VCPResult.class);
                        } catch (Exception e) {
                            throw new RuntimeException("Deserialization failed", e);
                        }
                    }
            );
        }
    }

    /**
     * Start VCP processors after FamilyCandle processors
     */
    @PostConstruct
    public void start() {
        if (!enabled) {
            LOGGER.info("⏸️ VCPProcessor is disabled");
            return;
        }

        LOGGER.info("🚀 Scheduling VCPProcessor startup...");

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                // Wait for FamilyCandle processors to start
                Thread.sleep(10000);

                startVCPProcessors();

                LOGGER.info("✅ All VCPProcessors started");
                logStreamStates();

            } catch (Exception e) {
                LOGGER.error("❌ Error starting VCPProcessors", e);
            }
        });
    }

    /**
     * Get stream states
     */
    public Map<String, KafkaStreams.State> getStreamStates() {
        Map<String, KafkaStreams.State> states = new HashMap<>();
        streamsInstances.forEach((k, v) -> states.put(k, v.state()));
        return states;
    }

    /**
     * Log stream states
     */
    public void logStreamStates() {
        LOGGER.info("📊 VCPProcessor Stream States:");
        getStreamStates().forEach((key, state) -> {
            String emoji = state == KafkaStreams.State.RUNNING ? "✅" : 
                          state == KafkaStreams.State.ERROR ? "❌" : "⚠️";
            LOGGER.info("  {} {}: {}", emoji, key, state);
        });
    }

    /**
     * Stop all streams
     */
    @PreDestroy
    public void stopAllStreams() {
        LOGGER.info("🛑 Stopping all VCPProcessor streams");
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
    }
}
