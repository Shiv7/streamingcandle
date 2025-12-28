package com.kotsin.consumer.processor;

import com.kotsin.consumer.config.IPUConfig;
import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.model.*;
import com.kotsin.consumer.service.IPUCalculator;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * IPUProcessor - Calculate IPU (Institutional Participation & Urgency) from FamilyCandle
 *
 * Data Flow:
 * 1. Input: family-candle-{5m,15m,30m}
 * 2. Extract equity InstrumentCandle from FamilyCandle
 * 3. Maintain rolling history in state store
 * 4. Calculate IPU per timeframe
 * 5. Output: ipu-signals-{5m,15m,30m} for per-TF results
 * 6. Cache 15m/30m for multi-TF fusion
 */
@Component
public class IPUProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(IPUProcessor.class);

    @Autowired
    private KafkaConfig kafkaConfig;

    @Autowired
    private IPUConfig ipuConfig;

    @Autowired
    private IPUCalculator ipuCalculator;

    private final Map<String, KafkaStreams> streamsInstances = new HashMap<>();

    // Topic configuration
    @Value("${ipu.input.prefix:family-candle-}")
    private String inputTopicPrefix;

    @Value("${ipu.output.signals.prefix:ipu-signals-}")
    private String signalsTopicPrefix;

    // Feature toggle
    @Value("${ipu.enabled:true}")
    private boolean enabled;

    // State stores for multi-TF fusion - PER SCRIPCODE
    @Value("${ipu.lookback:20}")
    private int defaultLookback;

    // Cache for multi-TF fusion PER SCRIPCODE to avoid cross-contamination
    private static final java.util.concurrent.ConcurrentHashMap<String, IPUOutput> cached15mResults = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, IPUOutput> cached30mResults = new java.util.concurrent.ConcurrentHashMap<>();

    public static IPUOutput getCached15mResult(String scripCode) { 
        return cached15mResults.get(scripCode); 
    }
    public static IPUOutput getCached30mResult(String scripCode) { 
        return cached30mResults.get(scripCode); 
    }

    /**
     * Process IPU for a single timeframe
     */
    public void processIPUForTimeframe(String timeframe, int lookbackSize) {
        String instanceKey = "ipu-processor-" + timeframe;

        if (streamsInstances.containsKey(instanceKey)) {
            LOGGER.warn("IPU processor {} already running", instanceKey);
            return;
        }

        String inputTopic = inputTopicPrefix + timeframe;
        String outputTopic = signalsTopicPrefix + timeframe;

        Properties props = kafkaConfig.getStreamProperties(instanceKey);
        StreamsBuilder builder = new StreamsBuilder();

        buildIPUTopology(builder, inputTopic, outputTopic, timeframe, lookbackSize);

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streamsInstances.put(instanceKey, streams);

        streams.setStateListener((newState, oldState) -> {
            LOGGER.info("IPUProcessor {} state: {} -> {}", timeframe, oldState, newState);
        });

        streams.setUncaughtExceptionHandler((Throwable e) -> {
            LOGGER.error("❌ Exception in IPUProcessor {}: ", timeframe, e);
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });

        try {
            streams.start();
            LOGGER.info("✅ Started IPUProcessor for {}", timeframe);
        } catch (Exception e) {
            LOGGER.error("❌ Failed to start IPUProcessor for {}: ", timeframe, e);
            streamsInstances.remove(instanceKey);
        }
    }

    /**
     * Build IPU calculation topology
     */
    private void buildIPUTopology(StreamsBuilder builder,
                                  String inputTopic,
                                  String outputTopic,
                                  String timeframe,
                                  int lookbackSize) {

        String historyStoreName = "ipu-history-" + timeframe;
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(historyStoreName),
                Serdes.String(),
                new CandleHistorySerde()
        ));

        KStream<String, FamilyCandle> input = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), FamilyCandle.serde())
        );

        // Process: update history, calculate IPU, emit signals
        KStream<String, IPUOutput> ipuResults = input.process(
                () -> new IPUHistoryProcessor(historyStoreName, lookbackSize, timeframe, ipuCalculator),
                historyStoreName
        );

        // Emit per-timeframe IPU signals
        ipuResults
                .filter((k, v) -> v != null && v.getFinalIpuScore() > 0)
                .peek((k, v) -> {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("📈 IPU {} | scrip={} score={} direction={} momentum={}",
                                timeframe, k, String.format("%.3f", v.getFinalIpuScore()), 
                                v.getDirection(), v.getMomentumState());
                    }
                })
                .to(outputTopic, Produced.with(Serdes.String(), IPUOutput.serde()));

        // Cache 15m/30m results for fusion PER SCRIPCODE
        if ("15m".equals(timeframe)) {
            ipuResults.foreach((k, v) -> {
                if (v != null && k != null) cached15mResults.put(k, v);
            });
        }
        if ("30m".equals(timeframe)) {
            ipuResults.foreach((k, v) -> {
                if (v != null && k != null) cached30mResults.put(k, v);
            });
        }

        LOGGER.info("📐 Built IPU topology for {} -> {}", timeframe, outputTopic);
    }

    /**
     * Processor that maintains candle history and calculates IPU
     */
    private static class IPUHistoryProcessor implements Processor<String, FamilyCandle, String, IPUOutput> {

        private final String storeName;
        private final int lookbackSize;
        private final String timeframe;
        private final IPUCalculator calculator;
        private ProcessorContext<String, IPUOutput> context;
        private KeyValueStore<String, VCPProcessor.CandleHistory> historyStore;

        IPUHistoryProcessor(String storeName, int lookbackSize, String timeframe, IPUCalculator calculator) {
            this.storeName = storeName;
            this.lookbackSize = lookbackSize;
            this.timeframe = timeframe;
            this.calculator = calculator;
        }

        @Override
        public void init(ProcessorContext<String, IPUOutput> context) {
            this.context = context;
            this.historyStore = context.getStateStore(storeName);
        }

        @Override
        public void process(Record<String, FamilyCandle> record) {
            String key = record.key();
            FamilyCandle familyCandle = record.value();

            if (key == null || familyCandle == null) return;

            // Extract equity InstrumentCandle from FamilyCandle
            InstrumentCandle equity = familyCandle.getEquity();
            if (equity == null) {
                LOGGER.warn("No equity data in FamilyCandle for {}", key);
                return;
            }

            // Convert InstrumentCandle to UnifiedCandle for backwards compatibility
            UnifiedCandle candle = convertToUnifiedCandle(equity);

            // Get or create history
            VCPProcessor.CandleHistory history = historyStore.get(key);
            if (history == null) {
                history = new VCPProcessor.CandleHistory(lookbackSize);
            }

            // Add new candle
            history.add(candle);

            // Save updated history
            historyStore.put(key, history);

            // Calculate IPU if we have enough history
            if (history.size() >= 5) {
                IPUOutput result = calculator.calculate(history.getCandles(), timeframe);
                context.forward(new Record<>(key, result, record.timestamp()));
            }
        }

        @Override
        public void close() {}
    }

    /**
     * Reuse CandleHistory from VCPProcessor
     */
    public static class CandleHistorySerde implements org.apache.kafka.common.serialization.Serde<VCPProcessor.CandleHistory> {
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = 
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        @Override
        public org.apache.kafka.common.serialization.Serializer<VCPProcessor.CandleHistory> serializer() {
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
        public org.apache.kafka.common.serialization.Deserializer<VCPProcessor.CandleHistory> deserializer() {
            return (topic, bytes) -> {
                if (bytes == null) return null;
                try {
                    return MAPPER.readValue(bytes, VCPProcessor.CandleHistory.class);
                } catch (Exception e) {
                    throw new RuntimeException("Deserialization failed", e);
                }
            };
        }
    }

    /**
     * Convert InstrumentCandle to UnifiedCandle for backwards compatibility
     */
    private static UnifiedCandle convertToUnifiedCandle(InstrumentCandle instrument) {
        return UnifiedCandle.builder()
                .scripCode(instrument.getScripCode())
                .companyName(instrument.getCompanyName())
                .exchange(instrument.getExchange())
                .exchangeType(instrument.getExchangeType())
                .timeframe(instrument.getTimeframe())
                .windowStartMillis(instrument.getWindowStartMillis())
                .windowEndMillis(instrument.getWindowEndMillis())
                .humanReadableStartTime(instrument.getHumanReadableTime())
                .humanReadableEndTime(instrument.getHumanReadableTime())
                // OHLCV
                .open(instrument.getOpen())
                .high(instrument.getHigh())
                .low(instrument.getLow())
                .close(instrument.getClose())
                .volume(instrument.getVolume())
                .buyVolume(instrument.getBuyVolume())
                .sellVolume(instrument.getSellVolume())
                .vwap(instrument.getVwap())
                .tickCount(instrument.getTickCount())
                // Volume Profile
                .volumeAtPrice(instrument.getVolumeAtPrice())
                .poc(instrument.getPoc())
                .valueAreaHigh(instrument.getVah())
                .valueAreaLow(instrument.getVal())
                // Imbalance
                .volumeImbalance(instrument.getVolumeImbalance())
                .dollarImbalance(instrument.getDollarImbalance())
                .vpin(instrument.getVpin())
                // Orderbook (may be null)
                .ofi(instrument.getOfi() != null ? instrument.getOfi() : 0.0)
                .depthImbalance(instrument.getDepthImbalance() != null ? instrument.getDepthImbalance() : 0.0)
                .kyleLambda(instrument.getKyleLambda() != null ? instrument.getKyleLambda() : 0.0)
                .microprice(instrument.getMicroprice() != null ? instrument.getMicroprice() : 0.0)
                .bidAskSpread(instrument.getBidAskSpread() != null ? instrument.getBidAskSpread() : 0.0)
                .weightedDepthImbalance(instrument.getWeightedDepthImbalance() != null ? instrument.getWeightedDepthImbalance() : 0.0)
                .totalBidDepth(instrument.getAverageBidDepth() != null ? instrument.getAverageBidDepth() : 0.0)
                .totalAskDepth(instrument.getAverageAskDepth() != null ? instrument.getAverageAskDepth() : 0.0)
                // OI (may be null)
                .oiOpen(instrument.getOiOpen())
                .oiHigh(instrument.getOiHigh())
                .oiLow(instrument.getOiLow())
                .oiClose(instrument.getOiClose())
                .oiChange(instrument.getOiChange())
                .oiChangePercent(instrument.getOiChangePercent())
                // Derived fields
                .volumeDeltaPercent(instrument.getVolumeDeltaPercent())
                .range(instrument.getRange())
                .isBullish(instrument.isBullish())
                .build();
    }

    /**
     * Start IPU processors after FamilyCandle processors
     */
    @PostConstruct
    public void start() {
        if (!enabled) {
            LOGGER.info("⏸️ IPUProcessor is disabled");
            return;
        }

        LOGGER.info("🚀 Scheduling IPUProcessor startup...");

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(12000);  // Wait for FamilyCandle processors

                processIPUForTimeframe("5m", defaultLookback);
                Thread.sleep(1000);
                processIPUForTimeframe("15m", defaultLookback);
                Thread.sleep(1000);
                processIPUForTimeframe("30m", defaultLookback);

                LOGGER.info("✅ All IPUProcessors started");
                logStreamStates();

            } catch (Exception e) {
                LOGGER.error("❌ Error starting IPUProcessors", e);
            }
        });
    }

    public Map<String, KafkaStreams.State> getStreamStates() {
        Map<String, KafkaStreams.State> states = new HashMap<>();
        streamsInstances.forEach((k, v) -> states.put(k, v.state()));
        return states;
    }

    public void logStreamStates() {
        LOGGER.info("📊 IPUProcessor Stream States:");
        getStreamStates().forEach((key, state) -> {
            String emoji = state == KafkaStreams.State.RUNNING ? "✅" : 
                          state == KafkaStreams.State.ERROR ? "❌" : "⚠️";
            LOGGER.info("  {} {}: {}", emoji, key, state);
        });
    }

    @PreDestroy
    public void stopAllStreams() {
        LOGGER.info("🛑 Stopping all IPUProcessor streams");
        List<String> keys = new ArrayList<>(streamsInstances.keySet());
        for (String key : keys) {
            KafkaStreams streams = streamsInstances.get(key);
            if (streams != null) {
                try {
                    streams.close(Duration.ofSeconds(30));
                } catch (Exception e) {
                    LOGGER.error("Error stopping {}: ", key, e);
                }
            }
        }
        streamsInstances.clear();
    }
}
