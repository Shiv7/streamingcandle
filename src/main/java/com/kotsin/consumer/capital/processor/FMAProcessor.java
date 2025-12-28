package com.kotsin.consumer.capital.processor;

import com.kotsin.consumer.capital.model.FinalMagnitude;
import com.kotsin.consumer.capital.service.FinalMagnitudeAssembly;
import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.config.KafkaTopics;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.model.*;
import com.kotsin.consumer.processor.VCPProcessor;
import com.kotsin.consumer.regime.model.*;
import com.kotsin.consumer.regime.processor.RegimeProcessor;
import com.kotsin.consumer.regime.service.*;
import com.kotsin.consumer.signal.model.FUDKIIOutput;
import com.kotsin.consumer.signal.model.VTDOutput;
import com.kotsin.consumer.signal.service.FUDKIICalculator;
import com.kotsin.consumer.util.TTLCache;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * FMAProcessor - Kafka processor for Final Magnitude Assembly (Module 14)
 *
 * FIXED:
 * - Uses TTL cache with automatic expiry (5 min default)
 * - Uses unique state store names
 * - Has comprehensive cache miss logging
 * - Has input validation
 * - Has cache statistics logging
 *
 * Consumes:
 * - family-candle-5m
 * - ipu-signals-5m
 * - vcp-combined
 * - regime-security-output
 * - regime-acl-output
 *
 * Produces:
 * - magnitude-final
 * - fudkii-output
 */
@Component
public class FMAProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(FMAProcessor.class);

    @Autowired
    private KafkaConfig kafkaConfig;

    @Autowired
    private FinalMagnitudeAssembly fmaService;

    @Autowired
    private FUDKIICalculator fudkiiCalculator;

    @Autowired
    private SecurityRegimeCalculator securityRegimeCalculator;

    @Autowired
    private AntiCycleLimiter antiCycleLimiter;

    @Autowired(required = false)
    private RegimeProcessor regimeProcessor;

    private final Map<String, KafkaStreams> streamsInstances = new HashMap<>();

    // Feature toggle
    @Value("${fma.enabled:true}")
    private boolean enabled;

    @Value("${fma.history.lookback:50}")
    private int historyLookback;

    @Value("${fma.magnitude.min.threshold:0.3}")
    private double minMagnitudeThreshold;

    @Value("${fma.cache.ttl.ms:300000}")
    private long cacheTtlMs;

    // TTL Caches with automatic expiry
    private TTLCache<String, IPUOutput> cachedIPU;
    private TTLCache<String, MTVCPOutput> cachedVCP;
    private TTLCache<String, SecurityRegime> cachedSecurityRegime;
    private TTLCache<String, ACLOutput> cachedACL;
    private TTLCache<String, FUDKIIOutput> cachedFUDKII;
    private TTLCache<String, VTDOutput> cachedVTD;  // Nice-to-have: VTD integration

    // Stats logging executor
    private ScheduledExecutorService statsExecutor;

    @PostConstruct
    public void start() {
        if (!enabled) {
            LOGGER.info("⏸️ FMAProcessor is disabled");
            return;
        }

        // Initialize TTL caches
        initializeCaches();

        LOGGER.info("🚀 Scheduling FMAProcessor startup...");

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(25000);
                startFMAProcessor();
                LOGGER.info("✅ FMAProcessor started");
                logStreamStates();
            } catch (Exception e) {
                LOGGER.error("❌ Error starting FMAProcessor", e);
            }
        });
    }

    private void initializeCaches() {
        // 5 min TTL, max 1000 entries, cleanup every 30s
        cachedIPU = new TTLCache<>("FMA-IPU", cacheTtlMs, 1000, 30000);
        cachedVCP = new TTLCache<>("FMA-VCP", cacheTtlMs, 1000, 30000);
        cachedSecurityRegime = new TTLCache<>("FMA-SecurityRegime", cacheTtlMs, 1000, 30000);
        cachedACL = new TTLCache<>("FMA-ACL", cacheTtlMs, 1000, 30000);
        cachedFUDKII = new TTLCache<>("FMA-FUDKII", cacheTtlMs, 1000, 30000);
        cachedVTD = new TTLCache<>("FMA-VTD", cacheTtlMs, 1000, 30000);  // Nice-to-have

        // Schedule stats logging every 5 minutes
        statsExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FMA-Stats");
            t.setDaemon(true);
            return t;
        });

        statsExecutor.scheduleAtFixedRate(this::logCacheStats, 5, 5, TimeUnit.MINUTES);

        LOGGER.info("✅ FMA caches initialized with TTL={}ms", cacheTtlMs);
    }

    private void logCacheStats() {
        cachedIPU.logStats();
        cachedVCP.logStats();
        cachedSecurityRegime.logStats();
        cachedACL.logStats();
        cachedFUDKII.logStats();
    }

    private void startFMAProcessor() {
        String instanceKey = "fma-processor";

        if (streamsInstances.containsKey(instanceKey)) {
            LOGGER.warn("FMA processor already running");
            return;
        }

        Properties props = kafkaConfig.getStreamProperties(instanceKey);
        StreamsBuilder builder = new StreamsBuilder();

        // State store for candle history - UNIQUE NAME
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(KafkaTopics.STORE_FMA_HISTORY),
                Serdes.String(),
                new VCPProcessor.CandleHistorySerde()
        ));

        // Separate store for FUDKII - UNIQUE NAME
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(KafkaTopics.STORE_FUDKII_HISTORY),
                Serdes.String(),
                new VCPProcessor.CandleHistorySerde()
        ));

        // Read family candle stream
        KStream<String, FamilyCandle> candleStream = builder.stream(
                KafkaTopics.FAMILY_CANDLE_5M,
                Consumed.with(Serdes.String(), FamilyCandle.serde())
        );

        // Consume and cache IPU outputs
        builder.stream(KafkaTopics.IPU_5M, Consumed.with(Serdes.String(), IPUOutput.serde()))
                .foreach((k, v) -> {
                    if (isValidKey(k) && v != null) {
                        cachedIPU.put(k, v);
                    }
                });

        // Consume and cache VCP outputs
        builder.stream(KafkaTopics.VCP_COMBINED, Consumed.with(Serdes.String(), MTVCPOutput.serde()))
                .foreach((k, v) -> {
                    if (isValidKey(k) && v != null) {
                        cachedVCP.put(k, v);
                    }
                });

        // Consume and cache regime outputs
        builder.stream(KafkaTopics.REGIME_SECURITY, Consumed.with(Serdes.String(), SecurityRegime.serde()))
                .foreach((k, v) -> {
                    if (isValidKey(k) && v != null) {
                        cachedSecurityRegime.put(k, v);
                    }
                });

        builder.stream(KafkaTopics.REGIME_ACL, Consumed.with(Serdes.String(), ACLOutput.serde()))
                .foreach((k, v) -> {
                    if (isValidKey(k) && v != null) {
                        cachedACL.put(k, v);
                    }
                });

        // Consume and cache VTD outputs (Nice-to-have: VTD integration)
        builder.stream(KafkaTopics.VTD_OUTPUT, Consumed.with(Serdes.String(), VTDOutput.serde()))
                .foreach((k, v) -> {
                    if (isValidKey(k) && v != null) {
                        cachedVTD.put(k, v);
                    }
                });

        // Process FMA
        KStream<String, FinalMagnitude> fmaStream = candleStream.process(
                () -> new FMAHistoryProcessor(
                        KafkaTopics.STORE_FMA_HISTORY, historyLookback, fmaService, fudkiiCalculator,
                        securityRegimeCalculator, antiCycleLimiter,
                        cachedIPU, cachedVCP, cachedSecurityRegime, cachedACL, cachedFUDKII,
                        regimeProcessor
                ),
                KafkaTopics.STORE_FMA_HISTORY
        );

        // Emit to magnitude-final with logging
        fmaStream
                .filter((k, v) -> v != null && v.getFinalMagnitude() >= minMagnitudeThreshold)
                .peek((k, v) -> {
                    LOGGER.info("🎯 FMA | {} | mag={} dir={} conf={} ipu={} vcp={} regime={}",
                            v.getScripCode(),
                            String.format("%.3f", v.getFinalMagnitude()),
                            v.getDirection(),
                            String.format("%.2f", v.getDirectionConfidence()),
                            v.getComponents() != null ? String.format("%.2f", v.getComponents().getIpuScore()) : "N/A",
                            v.getComponents() != null ? String.format("%.2f", v.getComponents().getVcpScore()) : "N/A",
                            v.getComponents() != null ? String.format("%.2f", v.getComponents().getRegimeStrength()) : "N/A");
                })
                .to(KafkaTopics.MAGNITUDE_FINAL, Produced.with(Serdes.String(), FinalMagnitude.serde()));

        // Process FUDKII with its own state store
        candleStream.process(
                () -> new FUDKIIProcessor(KafkaTopics.STORE_FUDKII_HISTORY, historyLookback, fudkiiCalculator, cachedIPU),
                KafkaTopics.STORE_FUDKII_HISTORY
        )
        .filter((k, v) -> v != null && v.isIgnitionFlag())
        .peek((k, v) -> {
            LOGGER.info("🔥 FUDKII | {} | strength={} sim={} dir={}",
                    v.getScripCode(),
                    String.format("%.2f", v.getFudkiiStrength()),
                    v.getSimultaneityScore(),
                    v.getDirection());
        })
        .to(KafkaTopics.FUDKII_OUTPUT, Produced.with(Serdes.String(), FUDKIIOutput.serde()));

        startStream(builder, props, instanceKey);
    }

    /**
     * Validate key is not null and not an index
     */
    private boolean isValidKey(String key) {
        return key != null && !key.isEmpty() && !key.contains("999920");
    }

    /**
     * FMA History Processor with null checks and logging
     */
    private static class FMAHistoryProcessor implements Processor<String, FamilyCandle, String, FinalMagnitude> {
        private final String storeName;
        private final int lookback;
        private final FinalMagnitudeAssembly fma;
        private final FUDKIICalculator fudkiiCalculator;
        private final SecurityRegimeCalculator securityRegimeCalculator;
        private final AntiCycleLimiter antiCycleLimiter;
        private final TTLCache<String, IPUOutput> cachedIPU;
        private final TTLCache<String, MTVCPOutput> cachedVCP;
        private final TTLCache<String, SecurityRegime> cachedSecurityRegime;
        private final TTLCache<String, ACLOutput> cachedACL;
        private final TTLCache<String, FUDKIIOutput> cachedFUDKII;
        private final RegimeProcessor regimeProcessor;

        private ProcessorContext<String, FinalMagnitude> context;
        private KeyValueStore<String, VCPProcessor.CandleHistory> historyStore;

        FMAHistoryProcessor(String storeName, int lookback,
                           FinalMagnitudeAssembly fma,
                           FUDKIICalculator fudkiiCalculator,
                           SecurityRegimeCalculator securityRegimeCalculator,
                           AntiCycleLimiter antiCycleLimiter,
                           TTLCache<String, IPUOutput> cachedIPU,
                           TTLCache<String, MTVCPOutput> cachedVCP,
                           TTLCache<String, SecurityRegime> cachedSecurityRegime,
                           TTLCache<String, ACLOutput> cachedACL,
                           TTLCache<String, FUDKIIOutput> cachedFUDKII,
                           RegimeProcessor regimeProcessor) {
            this.storeName = storeName;
            this.lookback = lookback;
            this.fma = fma;
            this.fudkiiCalculator = fudkiiCalculator;
            this.securityRegimeCalculator = securityRegimeCalculator;
            this.antiCycleLimiter = antiCycleLimiter;
            this.cachedIPU = cachedIPU;
            this.cachedVCP = cachedVCP;
            this.cachedSecurityRegime = cachedSecurityRegime;
            this.cachedACL = cachedACL;
            this.cachedFUDKII = cachedFUDKII;
            this.regimeProcessor = regimeProcessor;
        }

        @Override
        public void init(ProcessorContext<String, FinalMagnitude> context) {
            this.context = context;
            this.historyStore = context.getStateStore(storeName);
        }

        @Override
        public void process(Record<String, FamilyCandle> record) {
            String key = record.key();
            FamilyCandle familyCandle = record.value();

            // Input validation
            if (key == null || key.isEmpty()) {
                LOGGER.warn("⚠️ FMA: Received null/empty key");
                return;
            }
            if (familyCandle == null) {
                LOGGER.warn("⚠️ FMA: Received null candle for key={}", key);
                return;
            }
            if (key.contains("999920")) {
                return; // Skip indices silently
            }

            // Extract equity InstrumentCandle
            InstrumentCandle equity = familyCandle.getEquity();
            if (equity == null) {
                LOGGER.warn("No equity data in FamilyCandle for {}", key);
                return;
            }

            // Convert to UnifiedCandle for backwards compatibility
            UnifiedCandle candle = convertToUnifiedCandle(equity);

            // Get or create history with null check
            VCPProcessor.CandleHistory history = historyStore.get(key);
            if (history == null) {
                history = new VCPProcessor.CandleHistory(lookback);
            }
            history.add(candle);
            historyStore.put(key, history);

            // Need at least 5 candles
            List<UnifiedCandle> candles = history.getCandles();
            if (candles == null || candles.size() < 5) {
                return;
            }

            // Get cached outputs with logging
            IPUOutput ipuOutput = cachedIPU.getWithLogging(key, "FMA");
            MTVCPOutput vcpOutput = cachedVCP.getWithLogging(key, "FMA");
            SecurityRegime securityRegime = cachedSecurityRegime.get(key);
            ACLOutput aclOutput = cachedACL.get(key);

            // Get index regime
            IndexRegime indexRegime = regimeProcessor != null ?
                    regimeProcessor.getCachedIndexRegime(IndexRegimeCalculator.NIFTY50_CODE) : null;

            // Calculate FUDKII if not cached
            FUDKIIOutput fudkiiOutput = cachedFUDKII.get(key);
            if (fudkiiOutput == null) {
                fudkiiOutput = fudkiiCalculator.calculate(
                        candle.getScripCode(),
                        candle.getCompanyName(),
                        candles,
                        ipuOutput
                );
                if (fudkiiOutput != null) {
                    cachedFUDKII.put(key, fudkiiOutput);
                }
            }

            // Calculate security regime if not cached
            if (securityRegime == null) {
                securityRegime = securityRegimeCalculator.calculate(
                        candle.getScripCode(),
                        candle.getCompanyName(),
                        candles,
                        indexRegime
                );
                if (securityRegime != null) {
                    cachedSecurityRegime.put(key, securityRegime);
                }
            }

            // Calculate ACL if not cached
            if (aclOutput == null) {
                aclOutput = antiCycleLimiter.calculate(
                        candle.getScripCode(),
                        candle.getCompanyName(),
                        candles,
                        indexRegime
                );
                if (aclOutput != null) {
                    cachedACL.put(key, aclOutput);
                }
            }

            // Calculate FMA
            FinalMagnitude magnitude = fma.calculate(
                    candle.getScripCode(),
                    candle.getCompanyName(),
                    candle,
                    null,
                    ipuOutput,
                    vcpOutput,
                    indexRegime,
                    securityRegime,
                    aclOutput,
                    fudkiiOutput
            );

            if (magnitude != null) {
                context.forward(new Record<>(key, magnitude, record.timestamp()));
            }
        }

        @Override
        public void close() {}
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
     * FUDKII Processor with its own state store
     */
    private static class FUDKIIProcessor implements Processor<String, FamilyCandle, String, FUDKIIOutput> {
        private final String storeName;
        private final int lookback;
        private final FUDKIICalculator calculator;
        private final TTLCache<String, IPUOutput> cachedIPU;

        private ProcessorContext<String, FUDKIIOutput> context;
        private KeyValueStore<String, VCPProcessor.CandleHistory> historyStore;

        FUDKIIProcessor(String storeName, int lookback,
                       FUDKIICalculator calculator,
                       TTLCache<String, IPUOutput> cachedIPU) {
            this.storeName = storeName;
            this.lookback = lookback;
            this.calculator = calculator;
            this.cachedIPU = cachedIPU;
        }

        @Override
        public void init(ProcessorContext<String, FUDKIIOutput> context) {
            this.context = context;
            this.historyStore = context.getStateStore(storeName);
        }

        @Override
        public void process(Record<String, FamilyCandle> record) {
            String key = record.key();
            FamilyCandle familyCandle = record.value();

            if (key == null || familyCandle == null || key.contains("999920")) return;

            // Extract equity InstrumentCandle
            InstrumentCandle equity = familyCandle.getEquity();
            if (equity == null) {
                LOGGER.warn("No equity data in FamilyCandle for {}", key);
                return;
            }

            // Convert to UnifiedCandle for backwards compatibility
            UnifiedCandle candle = convertToUnifiedCandle(equity);

            VCPProcessor.CandleHistory history = historyStore.get(key);
            if (history == null) {
                history = new VCPProcessor.CandleHistory(lookback);
            }
            history.add(candle);
            historyStore.put(key, history);

            List<UnifiedCandle> candles = history.getCandles();
            if (candles == null || candles.size() < 5) return;

            IPUOutput ipuOutput = cachedIPU.get(key);

            FUDKIIOutput output = calculator.calculate(
                    candle.getScripCode(),
                    candle.getCompanyName(),
                    candles,
                    ipuOutput
            );

            if (output != null) {
                context.forward(new Record<>(key, output, record.timestamp()));
            }
        }

        @Override
        public void close() {}
    }

    private void startStream(StreamsBuilder builder, Properties props, String instanceKey) {
        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streamsInstances.put(instanceKey, streams);

        streams.setStateListener((newState, oldState) -> {
            LOGGER.info("FMAProcessor {} state: {} -> {}", instanceKey, oldState, newState);
            if (newState == KafkaStreams.State.ERROR) {
                LOGGER.error("❌ FMAProcessor {} entered ERROR state!", instanceKey);
            }
        });

        streams.setUncaughtExceptionHandler((Throwable e) -> {
            LOGGER.error("❌ Exception in FMAProcessor {}: ", instanceKey, e);
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });

        try {
            streams.start();
            LOGGER.info("✅ Started FMAProcessor: {}", instanceKey);
        } catch (Exception e) {
            LOGGER.error("❌ Failed to start FMAProcessor {}: ", instanceKey, e);
            streamsInstances.remove(instanceKey);
        }
    }

    public IPUOutput getCachedIPU(String scripCode) {
        return cachedIPU != null ? cachedIPU.get(scripCode) : null;
    }

    public MTVCPOutput getCachedVCP(String scripCode) {
        return cachedVCP != null ? cachedVCP.get(scripCode) : null;
    }

    public SecurityRegime getCachedSecurityRegime(String scripCode) {
        return cachedSecurityRegime != null ? cachedSecurityRegime.get(scripCode) : null;
    }

    public FUDKIIOutput getCachedFUDKII(String scripCode) {
        return cachedFUDKII != null ? cachedFUDKII.get(scripCode) : null;
    }

    public Map<String, KafkaStreams.State> getStreamStates() {
        Map<String, KafkaStreams.State> states = new HashMap<>();
        streamsInstances.forEach((k, v) -> states.put(k, v.state()));
        return states;
    }

    public void logStreamStates() {
        LOGGER.info("📊 FMAProcessor Stream States:");
        getStreamStates().forEach((key, state) -> {
            String emoji = state == KafkaStreams.State.RUNNING ? "✅" :
                          state == KafkaStreams.State.ERROR ? "❌" : "⚠️";
            LOGGER.info("  {} {}: {}", emoji, key, state);
        });
    }

    @PreDestroy
    public void stopAllStreams() {
        LOGGER.info("🛑 Stopping all FMAProcessor streams and caches");

        // Stop stats executor
        if (statsExecutor != null) {
            statsExecutor.shutdown();
        }

        // Log final stats
        logCacheStats();

        // Shutdown caches
        if (cachedIPU != null) cachedIPU.shutdown();
        if (cachedVCP != null) cachedVCP.shutdown();
        if (cachedSecurityRegime != null) cachedSecurityRegime.shutdown();
        if (cachedACL != null) cachedACL.shutdown();
        if (cachedFUDKII != null) cachedFUDKII.shutdown();

        // Stop streams
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
