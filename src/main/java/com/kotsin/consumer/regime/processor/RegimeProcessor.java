package com.kotsin.consumer.regime.processor;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.processor.VCPProcessor;
import com.kotsin.consumer.regime.model.*;
import com.kotsin.consumer.regime.service.*;
import com.kotsin.consumer.util.FamilyCandleConverter;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.common.serialization.Serdes;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * RegimeProcessor - Kafka processor for all regime modules
 *
 * Processes:
 * - Module 1: Index Regime → regime-index-output
 * - Module 2: Security Regime → regime-security-output
 * - Module 3: ACL → regime-acl-output
 *
 * Consumes from family-candle-* topics and emits regime outputs
 */
@Component
public class RegimeProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegimeProcessor.class);

    @Autowired
    private KafkaConfig kafkaConfig;

    @Autowired
    private IndexRegimeCalculator indexRegimeCalculator;

    @Autowired
    private SecurityRegimeCalculator securityRegimeCalculator;

    @Autowired
    private AntiCycleLimiter antiCycleLimiter;

    @Autowired(required = false)
    private com.kotsin.consumer.infrastructure.redis.RedisCorrelationService correlationService;

    @Autowired(required = false)
    private com.kotsin.consumer.masterarch.calculator.VolumeCanonicalCalculator volumeCanonicalCalculator;

    private final Map<String, KafkaStreams> streamsInstances = new HashMap<>();

    // Output topics
    @Value("${regime.output.index:regime-index-output}")
    private String indexOutputTopic;

    @Value("${regime.output.security:regime-security-output}")
    private String securityOutputTopic;

    @Value("${regime.output.acl:regime-acl-output}")
    private String aclOutputTopic;

    // Input topics
    @Value("${regime.input.prefix:family-candle-}")
    private String inputTopicPrefix;

    // Index scrip codes
    private static final Set<String> INDEX_SCRIP_CODES = Set.of(
        "999920000",   // NIFTY50
        "999920005",   // BANKNIFTY
        "999920041",   // FINNIFTY
        "999920043"    // MIDCPNIFTY
    );

    // Feature toggle
    @Value("${regime.enabled:true}")
    private boolean enabled;

    @Value("${regime.history.lookback:50}")
    private int historyLookback;

    // Cache for index regimes (for security regime calculation)
    private final ConcurrentHashMap<String, IndexRegime> cachedIndexRegimes = new ConcurrentHashMap<>();

    // Cache for multi-TF candles per scripCode
    private final ConcurrentHashMap<String, CandleCache> candleCache = new ConcurrentHashMap<>();

    private static class CandleCache {
        final List<InstrumentCandle> candles1D = Collections.synchronizedList(new ArrayList<>());
        final List<InstrumentCandle> candles2H = Collections.synchronizedList(new ArrayList<>());
        final List<InstrumentCandle> candles30m = Collections.synchronizedList(new ArrayList<>());
        final List<InstrumentCandle> candles5m = Collections.synchronizedList(new ArrayList<>());

        void addCandle(String timeframe, InstrumentCandle candle, int maxSize) {
            List<InstrumentCandle> list;
            switch (timeframe) {
                case "1d": list = candles1D; break;
                case "2h": list = candles2H; break;
                case "30m": list = candles30m; break;
                case "5m": list = candles5m; break;
                default: return;
            }

            synchronized (list) {
                list.add(candle);
                while (list.size() > maxSize) {
                    list.remove(0);
                }
            }
        }
    }

    /**
     * Start Index Regime processor
     * Processes index candles and outputs to regime-index-output
     */
    private void startIndexRegimeProcessor() {
        String instanceKey = "regime-index-processor";

        if (streamsInstances.containsKey(instanceKey)) {
            LOGGER.warn("Index regime processor already running");
            return;
        }

        Properties props = kafkaConfig.getStreamProperties(instanceKey);
        StreamsBuilder builder = new StreamsBuilder();

        // Read 30m family candles (for indices)
        KStream<String, FamilyCandle> candle30mStream = builder.stream(
                inputTopicPrefix + "30m",
                Consumed.with(Serdes.String(), FamilyCandle.serde())
        );

        // Also consume 5m candles for faster regime
        KStream<String, FamilyCandle> candle5mStream = builder.stream(
                inputTopicPrefix + "5m",
                Consumed.with(Serdes.String(), FamilyCandle.serde())
        );

        // Cache 5m candles for index codes
        candle5mStream
                .filter((k, v) -> v != null && v.getEquity() != null && INDEX_SCRIP_CODES.contains(v.getEquity().getScripCode()))
                .foreach((k, familyCandle) -> {
                    InstrumentCandle equity = familyCandle.getEquity();
                    if (equity == null) return;
                    CandleCache cache = candleCache.computeIfAbsent(equity.getScripCode(), c -> new CandleCache());
                    cache.addCandle("5m", equity, historyLookback);
                });

        // Try to consume 2H family candles (may not exist in all deployments)
        try {
            builder.stream(inputTopicPrefix + "2h", Consumed.with(Serdes.String(), FamilyCandle.serde()))
                .filter((k, v) -> v != null && v.getEquity() != null && INDEX_SCRIP_CODES.contains(v.getEquity().getScripCode()))
                .foreach((k, familyCandle) -> {
                    InstrumentCandle equity = familyCandle.getEquity();
                    if (equity == null) return;
                    CandleCache cache = candleCache.computeIfAbsent(equity.getScripCode(), c -> new CandleCache());
                    cache.addCandle("2h", equity, 20);
                });
        } catch (Exception e) {
            LOGGER.warn("2H family-candle topic not available: {}", e.getMessage());
        }

        // Try to consume 1D family candles (may not exist in all deployments)
        try {
            builder.stream(inputTopicPrefix + "1d", Consumed.with(Serdes.String(), FamilyCandle.serde()))
                .filter((k, v) -> v != null && v.getEquity() != null && INDEX_SCRIP_CODES.contains(v.getEquity().getScripCode()))
                .foreach((k, familyCandle) -> {
                    InstrumentCandle equity = familyCandle.getEquity();
                    if (equity == null) return;
                    CandleCache cache = candleCache.computeIfAbsent(equity.getScripCode(), c -> new CandleCache());
                    cache.addCandle("1d", equity, 10);
                });
        } catch (Exception e) {
            LOGGER.warn("1D family-candle topic not available: {}", e.getMessage());
        }

        // Filter for index scrip codes only
        KStream<String, FamilyCandle> indexStream = candle30mStream
                .filter((k, v) -> v != null && v.getEquity() != null && INDEX_SCRIP_CODES.contains(v.getEquity().getScripCode()));

        // Process and emit index regime
        indexStream
                .mapValues((key, familyCandle) -> {
                    // Extract equity InstrumentCandle
                    InstrumentCandle equity = familyCandle.getEquity();
                    if (equity == null) {
                        LOGGER.warn("No equity data in FamilyCandle for index");
                        return null;
                    }

                    // Update cache with InstrumentCandle directly
                    CandleCache cache = candleCache.computeIfAbsent(equity.getScripCode(), k -> new CandleCache());
                    cache.addCandle("30m", equity, historyLookback);

                    // Calculate regime using all available timeframes
                    String indexName = getIndexName(equity.getScripCode());

                    IndexRegime regime = indexRegimeCalculator.calculate(
                            indexName,
                            equity.getScripCode(),
                            cache.candles1D,
                            cache.candles2H,
                            cache.candles30m,
                            cache.candles5m.isEmpty() ? cache.candles30m : cache.candles5m  // Fallback to 30m if no 5m
                    );

                    // Cache for security regime use - use BOTH scripCode and index name as keys
                    cachedIndexRegimes.put(equity.getScripCode(), regime);
                    cachedIndexRegimes.put(IndexRegimeCalculator.NIFTY50_CODE, regime);  // Ensure lookup works

                    return regime;
                })
                .filter((k, v) -> v != null && v.getRegimeStrength() > 0)
                .peek((k, v) -> {
                    LOGGER.info("[REGIME-INDEX] {} | strength={} label={} flow={} volatility={} 1D={} 2H={} | emitted to {}", 
                            v.getIndexName(),
                            String.format("%.2f", v.getRegimeStrength()),
                            v.getLabel(),
                            v.getFlowAgreement(),
                            v.getVolatilityState(),
                            v.getTf1D() != null ? v.getTf1D().getRegimeStrength() : "N/A",
                            v.getTf2H() != null ? v.getTf2H().getRegimeStrength() : "N/A",
                            indexOutputTopic);
                })
                .to(indexOutputTopic, Produced.with(Serdes.String(), IndexRegime.serde()));

        startStream(builder, props, instanceKey);
    }

    /**
     * Start Security Regime processor
     * Processes equity candles and outputs to regime-security-output
     */
    private void startSecurityRegimeProcessor() {
        String instanceKey = "regime-security-processor";

        if (streamsInstances.containsKey(instanceKey)) {
            LOGGER.warn("Security regime processor already running");
            return;
        }

        Properties props = kafkaConfig.getStreamProperties(instanceKey);
        StreamsBuilder builder = new StreamsBuilder();

        // State store for candle history
        String historyStore = "security-regime-history";
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(historyStore),
                Serdes.String(),
                new VCPProcessor.CandleHistorySerde()
        ));

        // Read 30m family candles
        KStream<String, FamilyCandle> candleStream = builder.stream(
                inputTopicPrefix + "30m",
                Consumed.with(Serdes.String(), FamilyCandle.serde())
        );

        // Filter out indices (process equities only)
        KStream<String, FamilyCandle> equityStream = candleStream
                .filter((k, v) -> v != null && v.getEquity() != null && !INDEX_SCRIP_CODES.contains(v.getEquity().getScripCode()));

        // Process with state store
        KStream<String, SecurityRegime> regimeStream = equityStream.process(
                () -> new SecurityRegimeHistoryProcessor(historyStore, historyLookback, 
                        securityRegimeCalculator, cachedIndexRegimes,
                        correlationService, volumeCanonicalCalculator),
                historyStore
        );

        // Emit security regime
        regimeStream
                .filter((k, v) -> v != null && v.getFinalRegimeScore() > 0)
                .peek((k, v) -> {
                    LOGGER.info("[REGIME-SECURITY] {} | score={} label={} aligned={} ema={} | emitted to {}", 
                            v.getScripCode(),
                            String.format("%.2f", v.getFinalRegimeScore()),
                            v.getLabel(),
                            v.isAlignedWithIndex(),
                            "EMA20/50", // EMA alignment simplified for new formula
                            securityOutputTopic);
                })
                .to(securityOutputTopic, Produced.with(Serdes.String(), SecurityRegime.serde()));

        startStream(builder, props, instanceKey);
    }

    /**
     * Start ACL processor
     * Processes candles and outputs to regime-acl-output
     */
    private void startACLProcessor() {
        String instanceKey = "regime-acl-processor";

        if (streamsInstances.containsKey(instanceKey)) {
            LOGGER.warn("ACL processor already running");
            return;
        }

        Properties props = kafkaConfig.getStreamProperties(instanceKey);
        StreamsBuilder builder = new StreamsBuilder();

        // State store for candle history
        String historyStore = "acl-history";
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(historyStore),
                Serdes.String(),
                new VCPProcessor.CandleHistorySerde()
        ));

        // Read 30m family candles
        KStream<String, FamilyCandle> candleStream = builder.stream(
                inputTopicPrefix + "30m",
                Consumed.with(Serdes.String(), FamilyCandle.serde())
        );

        // Process ACL
        KStream<String, ACLOutput> aclStream = candleStream
                .filter((k, v) -> v != null && v.getEquity() != null && !INDEX_SCRIP_CODES.contains(v.getEquity().getScripCode()))
                .process(
                        () -> new ACLHistoryProcessor(historyStore, historyLookback,
                                antiCycleLimiter, cachedIndexRegimes),
                        historyStore
                );

        // Emit ACL output
        aclStream
                .filter((k, v) -> v != null)
                .peek((k, v) -> {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("⏰ ACL | {} | state={} mult={} age={} exhaustion={}",
                                v.getScripCode(),
                                v.getAclState(),
                                String.format("%.2f", v.getAclMultiplier()),
                                v.getTrendAge30m(),
                                v.isExhaustionNear());
                    }
                })
                .to(aclOutputTopic, Produced.with(Serdes.String(), ACLOutput.serde()));

        startStream(builder, props, instanceKey);
    }

    /**
     * Processor for security regime with history
     */
    private static class SecurityRegimeHistoryProcessor implements Processor<String, FamilyCandle, String, SecurityRegime> {
        private final String storeName;
        private final int lookback;
        private final SecurityRegimeCalculator calculator;
        private final Map<String, IndexRegime> indexRegimes;
        private final com.kotsin.consumer.infrastructure.redis.RedisCorrelationService correlationService;
        private final com.kotsin.consumer.masterarch.calculator.VolumeCanonicalCalculator volumeCanonicalCalculator;
        private ProcessorContext<String, SecurityRegime> context;
        private KeyValueStore<String, VCPProcessor.CandleHistory> historyStore;

        SecurityRegimeHistoryProcessor(String storeName, int lookback, 
                                       SecurityRegimeCalculator calculator,
                                       Map<String, IndexRegime> indexRegimes,
                                       com.kotsin.consumer.infrastructure.redis.RedisCorrelationService correlationService,
                                       com.kotsin.consumer.masterarch.calculator.VolumeCanonicalCalculator volumeCanonicalCalculator) {
            this.storeName = storeName;
            this.lookback = lookback;
            this.calculator = calculator;
            this.indexRegimes = indexRegimes;
            this.correlationService = correlationService;
            this.volumeCanonicalCalculator = volumeCanonicalCalculator;
        }

        @Override
        public void init(ProcessorContext<String, SecurityRegime> context) {
            this.context = context;
            this.historyStore = context.getStateStore(storeName);
        }

        @Override
        public void process(Record<String, FamilyCandle> record) {
            String key = record.key();
            FamilyCandle familyCandle = record.value();
            if (key == null || familyCandle == null) return;

            // Extract equity InstrumentCandle
            InstrumentCandle equity = familyCandle.getEquity();
            if (equity == null) {
                LOGGER.warn("No equity data in FamilyCandle for {}", key);
                return;
            }

            // Convert to UnifiedCandle for backwards compatibility
            UnifiedCandle candle = FamilyCandleConverter.toUnifiedCandle(equity);

            // Get or create history
            VCPProcessor.CandleHistory history = historyStore.get(key);
            if (history == null) {
                history = new VCPProcessor.CandleHistory(lookback);
            }
            history.add(candle);
            historyStore.put(key, history);

            // Need at least 5 candles
            if (history.size() < 5) return;

            // Get parent index regime (use NIFTY50 as default)
            IndexRegime parentRegime = indexRegimes.get(IndexRegimeCalculator.NIFTY50_CODE);

            // Get candles1D (TODO: populate from 1D state store when available)
            List<UnifiedCandle> candles1D = new ArrayList<>();

            // Get volume certainty (default to 0.5 if not available)
            double volumeCertainty = 0.5;
            if (volumeCanonicalCalculator != null) {
                try {
                    var volumeOutput = volumeCanonicalCalculator.calculate(
                            candle.getScripCode(),
                            candle.getCompanyName(),
                            history.getCandles(),
                            0.0
                    );
                    if (volumeOutput != null) {
                        volumeCertainty = volumeOutput.getVolumeCertainty();
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to get volume certainty for {}: {}", candle.getScripCode(), e.getMessage());
                }
            }

            // Get correlation with index
            double correlation = 0.0;
            if (correlationService != null && parentRegime != null) {
                try {
                    correlation = correlationService.calculateCorrelation(
                            candle.getScripCode(),
                            parentRegime.getScripCode()
                    );
                } catch (Exception e) {
                    LOGGER.warn("Failed to get correlation for {}: {}", candle.getScripCode(), e.getMessage());
                }
            }

            // Calculate security regime
            SecurityRegime regime = calculator.calculate(
                    candle.getScripCode(),
                    candle.getCompanyName(),
                    history.getCandles(),
                    candles1D,
                    parentRegime,
                    volumeCertainty,
                    correlation
            );

            context.forward(new Record<>(key, regime, record.timestamp()));
        }

        @Override
        public void close() {}
    }

    /**
     * Processor for ACL with history
     */
    private static class ACLHistoryProcessor implements Processor<String, FamilyCandle, String, ACLOutput> {
        private final String storeName;
        private final int lookback;
        private final AntiCycleLimiter acl;
        private final Map<String, IndexRegime> indexRegimes;
        private ProcessorContext<String, ACLOutput> context;
        private KeyValueStore<String, VCPProcessor.CandleHistory> historyStore;

        ACLHistoryProcessor(String storeName, int lookback,
                           AntiCycleLimiter acl,
                           Map<String, IndexRegime> indexRegimes) {
            this.storeName = storeName;
            this.lookback = lookback;
            this.acl = acl;
            this.indexRegimes = indexRegimes;
        }

        @Override
        public void init(ProcessorContext<String, ACLOutput> context) {
            this.context = context;
            this.historyStore = context.getStateStore(storeName);
        }

        @Override
        public void process(Record<String, FamilyCandle> record) {
            String key = record.key();
            FamilyCandle familyCandle = record.value();
            if (key == null || familyCandle == null) return;

            // Extract equity InstrumentCandle
            InstrumentCandle equity = familyCandle.getEquity();
            if (equity == null) {
                LOGGER.warn("No equity data in FamilyCandle for {}", key);
                return;
            }

            // Convert to UnifiedCandle for backwards compatibility
            UnifiedCandle candle = FamilyCandleConverter.toUnifiedCandle(equity);

            // Get or create history
            VCPProcessor.CandleHistory history = historyStore.get(key);
            if (history == null) {
                history = new VCPProcessor.CandleHistory(lookback);
            }
            history.add(candle);
            historyStore.put(key, history);

            if (history.size() < 3) return;

            // Get parent index regime
            IndexRegime parentRegime = indexRegimes.get(IndexRegimeCalculator.NIFTY50_CODE);

            // Calculate ACL
            ACLOutput output = acl.calculate(
                    candle.getScripCode(),
                    candle.getCompanyName(),
                    history.getCandles(),
                    parentRegime
            );

            context.forward(new Record<>(key, output, record.timestamp()));
        }

        @Override
        public void close() {}
    }

    /**
     * Helper to start a KafkaStreams instance
     */
    private void startStream(StreamsBuilder builder, Properties props, String instanceKey) {
        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streamsInstances.put(instanceKey, streams);

        streams.setStateListener((newState, oldState) -> {
            LOGGER.info("RegimeProcessor {} state: {} -> {}", instanceKey, oldState, newState);
            if (newState == KafkaStreams.State.ERROR) {
                LOGGER.error("❌ RegimeProcessor {} entered ERROR state!", instanceKey);
            }
        });

        streams.setUncaughtExceptionHandler((Throwable e) -> {
            LOGGER.error("❌ Exception in RegimeProcessor {}: ", instanceKey, e);
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });

        try {
            streams.start();
            LOGGER.info("✅ Started RegimeProcessor: {}", instanceKey);
        } catch (Exception e) {
            LOGGER.error("❌ Failed to start RegimeProcessor {}: ", instanceKey, e);
            streamsInstances.remove(instanceKey);
        }
    }

    /**
     * Get index name from scrip code
     */
    private String getIndexName(String scripCode) {
        switch (scripCode) {
            case "999920000": return "NIFTY50";
            case "999920005": return "BANKNIFTY";
            case "999920041": return "FINNIFTY";
            case "999920043": return "MIDCPNIFTY";
            default: return "UNKNOWN";
        }
    }

    /**
     * Get cached index regime
     */
    public IndexRegime getCachedIndexRegime(String scripCode) {
        return cachedIndexRegimes.get(scripCode);
    }

    /**
     * Start all regime processors
     */
    @PostConstruct
    public void start() {
        if (!enabled) {
            LOGGER.info("⏸️ RegimeProcessor is disabled");
            return;
        }

        LOGGER.info("🚀 Scheduling RegimeProcessor startup...");

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                // Wait for upstream processors
                Thread.sleep(20000);

                startIndexRegimeProcessor();
                Thread.sleep(2000);
                startSecurityRegimeProcessor();
                Thread.sleep(2000);
                startACLProcessor();

                LOGGER.info("✅ All RegimeProcessors started");
                logStreamStates();

            } catch (Exception e) {
                LOGGER.error("❌ Error starting RegimeProcessors", e);
            }
        });
    }

    public Map<String, KafkaStreams.State> getStreamStates() {
        Map<String, KafkaStreams.State> states = new HashMap<>();
        streamsInstances.forEach((k, v) -> states.put(k, v.state()));
        return states;
    }

    public void logStreamStates() {
        LOGGER.info("📊 RegimeProcessor Stream States:");
        getStreamStates().forEach((key, state) -> {
            String emoji = state == KafkaStreams.State.RUNNING ? "✅" :
                          state == KafkaStreams.State.ERROR ? "❌" : "⚠️";
            LOGGER.info("  {} {}: {}", emoji, key, state);
        });
    }

    @PreDestroy
    public void stopAllStreams() {
        LOGGER.info("🛑 Stopping all RegimeProcessor streams");
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
