package com.kotsin.consumer.signal.processor;

import com.kotsin.consumer.capital.model.*;
import com.kotsin.consumer.capital.orchestrator.WatchlistOrchestrator;
import com.kotsin.consumer.capital.service.*;
import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.config.KafkaTopics;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.model.*;
import com.kotsin.consumer.processor.VCPProcessor;
import com.kotsin.consumer.regime.model.*;
import com.kotsin.consumer.regime.processor.RegimeProcessor;
import com.kotsin.consumer.signal.model.*;
import com.kotsin.consumer.signal.service.*;
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
import java.util.concurrent.*;

/**
 * UnifiedSignalProcessor - Integrates CSS, SOM, VTD modules
 *
 * FIXED:
 * - Uses unique state store names for each processor
 * - Uses TTL cache for module outputs
 * - Uses OHM cached results (no API calls in hot path)
 * - Has comprehensive logging
 * - Has null checks
 *
 * Emits to:
 * - css-output
 * - som-output
 * - vtd-output
 * - ohm-output
 * - watchlist-ranked
 */
@Component
public class UnifiedSignalProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(UnifiedSignalProcessor.class);

    @Autowired
    private KafkaConfig kafkaConfig;

    @Autowired
    private CompositeStructureScore cssCalculator;

    @Autowired
    private SentimentOscillationModule somCalculator;

    @Autowired
    private VolatilityTrapDetector vtdCalculator;

    @Autowired
    private OptionHealthModule ohmService;

    @Autowired
    private WatchlistOrchestrator watchlistOrchestrator;

    @Autowired(required = false)
    private RegimeProcessor regimeProcessor;

    private final Map<String, KafkaStreams> streamsInstances = new HashMap<>();

    @Value("${signal.enabled:true}")
    private boolean enabled;

    @Value("${signal.history.lookback:50}")
    private int historyLookback;

    @Value("${signal.cache.ttl.ms:300000}")
    private long cacheTtlMs;

    // TTL Caches
    private TTLCache<String, MTVCPOutput> cachedVCP;
    private TTLCache<String, FinalMagnitude> cachedMagnitudes;

    // Stats executor
    private ScheduledExecutorService statsExecutor;

    @PostConstruct
    public void start() {
        if (!enabled) {
            LOGGER.info("⏸️ UnifiedSignalProcessor is disabled");
            return;
        }

        initializeCaches();

        LOGGER.info("🚀 Scheduling UnifiedSignalProcessor startup...");

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(30000);
                startUnifiedProcessor();
                LOGGER.info("✅ UnifiedSignalProcessor started");
            } catch (Exception e) {
                LOGGER.error("❌ Error starting UnifiedSignalProcessor", e);
            }
        });
    }

    private void initializeCaches() {
        cachedVCP = new TTLCache<>("Signal-VCP", cacheTtlMs, 1000, 30000);
        cachedMagnitudes = new TTLCache<>("Signal-Magnitude", cacheTtlMs, 1000, 30000);

        statsExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Signal-Stats");
            t.setDaemon(true);
            return t;
        });

        statsExecutor.scheduleAtFixedRate(this::logCacheStats, 5, 5, TimeUnit.MINUTES);
        LOGGER.info("✅ Signal caches initialized with TTL={}ms", cacheTtlMs);
    }

    private void logCacheStats() {
        cachedVCP.logStats();
        cachedMagnitudes.logStats();
    }

    private void startUnifiedProcessor() {
        String instanceKey = "unified-signal-processor";

        if (streamsInstances.containsKey(instanceKey)) {
            LOGGER.warn("Unified signal processor already running");
            return;
        }

        Properties props = kafkaConfig.getStreamProperties(instanceKey);
        StreamsBuilder builder = new StreamsBuilder();

        // UNIQUE state stores for each processor
        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(KafkaTopics.STORE_CSS_HISTORY),
                Serdes.String(),
                new VCPProcessor.CandleHistorySerde()
        ));

        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(KafkaTopics.STORE_SOM_HISTORY),
                Serdes.String(),
                new VCPProcessor.CandleHistorySerde()
        ));

        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(KafkaTopics.STORE_VTD_HISTORY),
                Serdes.String(),
                new VCPProcessor.CandleHistorySerde()
        ));

        // 🛡️ CRITICAL FIX: Event-Time Processing for Signal Generation
        //
        // Read family candle stream with event-time extraction
        // CRITICAL: Signal generation must use candle event time, not ingestion time
        // This ensures signals generated during replay match signals from live trading
        KStream<String, FamilyCandle> candleStream = builder.stream(
                KafkaTopics.FAMILY_CANDLE_5M,
                Consumed.with(Serdes.String(), FamilyCandle.serde())
                    .withTimestampExtractor(new com.kotsin.consumer.timeExtractor.FamilyCandleTimestampExtractor())
        );

        // Consume VCP for CSS calculation
        builder.stream(KafkaTopics.VCP_COMBINED, Consumed.with(Serdes.String(), MTVCPOutput.serde()))
                .foreach((k, v) -> {
                    if (isValidKey(k) && v != null) cachedVCP.put(k, v);
                });

        // Consume FinalMagnitude for Watchlist
        builder.stream(KafkaTopics.MAGNITUDE_FINAL, Consumed.with(Serdes.String(), FinalMagnitude.serde()))
                .foreach((k, v) -> {
                    if (isValidKey(k) && v != null) {
                        cachedMagnitudes.put(k, v);
                        watchlistOrchestrator.update(v);
                    }
                });

        // Process CSS with its own store
        KStream<String, CSSOutput> cssStream = candleStream.process(
                () -> new CSSProcessor(KafkaTopics.STORE_CSS_HISTORY, historyLookback, cssCalculator, cachedVCP),
                KafkaTopics.STORE_CSS_HISTORY
        );
        cssStream.filter((k, v) -> v != null)
                .peek((k, v) -> {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("📐 CSS | {} | score={} cps={}",
                                v.getScripCode(),
                                String.format("%.2f", v.getCssScore()),
                                String.format("%.2f", v.getCpsScore()));
                    }
                })
                .to(KafkaTopics.CSS_OUTPUT, Produced.with(Serdes.String(), CSSOutput.serde()));

        // Process SOM with its own store
        KStream<String, SOMOutput> somStream = candleStream.process(
                () -> new SOMProcessor(KafkaTopics.STORE_SOM_HISTORY, historyLookback, somCalculator),
                KafkaTopics.STORE_SOM_HISTORY
        );
        somStream.filter((k, v) -> v != null)
                .peek((k, v) -> {
                    if (v.getSentimentState() == SOMOutput.SentimentState.WHIPSAW ||
                        v.getSentimentState() == SOMOutput.SentimentState.VERY_CHOPPY) {
                        LOGGER.info("⚠️ SOM ALERT | {} | state={} score={} penalty={}",
                                v.getScripCode(), v.getSentimentState(),
                                String.format("%.2f", v.getSomScore()),
                                String.format("%.2f", v.getSomPenalty()));
                    }
                })
                .to(KafkaTopics.SOM_OUTPUT, Produced.with(Serdes.String(), SOMOutput.serde()));

        // Process VTD with its own store + OHM integration
        KStream<String, VTDOutput> vtdStream = candleStream.process(
                () -> new VTDProcessor(KafkaTopics.STORE_VTD_HISTORY, historyLookback, vtdCalculator, ohmService),
                KafkaTopics.STORE_VTD_HISTORY
        );
        vtdStream.filter((k, v) -> v != null && v.isTrapActive())
                .peek((k, v) -> {
            LOGGER.info("🪤 VTD ALERT | {} | type={} score={} penalty={} iv={}",
                            v.getScripCode(), v.getTrapType(),
                            String.format("%.2f", v.getVtdScore()),
                            String.format("%.2f", v.getVtdPenalty()),
                            v.getIvPercentile() != null ? String.format("%.1f", v.getIvPercentile()) : "N/A");
                })
                .to(KafkaTopics.VTD_OUTPUT, Produced.with(Serdes.String(), VTDOutput.serde()));

        // Register scrip codes with OHM for background refresh (no hot-path API calls)
        candleStream
                .filter((k, v) -> isValidKey(k))
                .foreach((k, v) -> ohmService.registerForRefresh(k));

        // Emit OHM from cache (not computed per candle anymore!)
        candleStream
                .filter((k, v) -> isValidKey(k) && v.getEquity() != null)
                .mapValues((k, familyCandle) -> ohmService.getCached(familyCandle.getEquity().getScripCode()))
                .filter((k, v) -> v != null && v.getQualityScore() > 0.3)
                .to(KafkaTopics.OHM_OUTPUT, Produced.with(Serdes.String(), OptionHealthOutput.serde()));

        // Periodically emit watchlist
        candleStream
                .filter((k, v) -> v != null && v.getEquity() != null && v.getEquity().getScripCode() != null)
                .groupByKey(Grouped.with(Serdes.String(), FamilyCandle.serde()))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(1)))
                .count()
                .toStream()
                .filter((k, v) -> v > 0)
                .flatMapValues((k, count) -> {
                    List<WatchlistEntry> topEntries = watchlistOrchestrator.getTopN(10);
                    if (!topEntries.isEmpty()) {
                        long bullish = topEntries.stream().filter(e -> "BULLISH".equals(e.getDirection())).count();
                        long bearish = topEntries.stream().filter(e -> "BEARISH".equals(e.getDirection())).count();
                        LOGGER.info("📋 WATCHLIST | {} entries | Top: {} ({}) | Bullish: {} Bearish: {}",
                                topEntries.size(),
                                topEntries.get(0).getScripCode(),
                                String.format("%.3f", topEntries.get(0).getFinalMagnitude()),
                                bullish, bearish);
                    }
                    return topEntries;
                })
                .filter((k, v) -> v != null)
                .selectKey((k, v) -> v.getScripCode())
                .to(KafkaTopics.WATCHLIST_RANKED, Produced.with(Serdes.String(), WatchlistEntry.serde()));

        startStream(builder, props, instanceKey);
    }

    private boolean isValidKey(String key) {
        return key != null && !key.isEmpty() && !key.contains("999920");
    }

    /**
     * CSS Processor with unique state store
     */
    private static class CSSProcessor implements Processor<String, FamilyCandle, String, CSSOutput> {
        private final String storeName;
        private final int lookback;
        private final CompositeStructureScore calculator;
        private final TTLCache<String, MTVCPOutput> vcpCache;
        private ProcessorContext<String, CSSOutput> context;
        private KeyValueStore<String, VCPProcessor.CandleHistory> historyStore;

        CSSProcessor(String storeName, int lookback, CompositeStructureScore calculator,
                    TTLCache<String, MTVCPOutput> vcpCache) {
            this.storeName = storeName;
            this.lookback = lookback;
            this.calculator = calculator;
            this.vcpCache = vcpCache;
        }

        @Override
        public void init(ProcessorContext<String, CSSOutput> context) {
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

            MTVCPOutput vcpOutput = vcpCache.get(key);

            CSSOutput output = calculator.calculate(
                    candle.getScripCode(),
                    candle.getCompanyName(),
                    vcpOutput,
                    null,
                    candle
            );

            if (output != null) {
                context.forward(new Record<>(key, output, record.timestamp()));
            }
        }

        @Override
        public void close() {}
    }

    /**
     * SOM Processor with unique state store
     */
    private static class SOMProcessor implements Processor<String, FamilyCandle, String, SOMOutput> {
        private final String storeName;
        private final int lookback;
        private final SentimentOscillationModule calculator;
        private ProcessorContext<String, SOMOutput> context;
        private KeyValueStore<String, VCPProcessor.CandleHistory> historyStore;

        SOMProcessor(String storeName, int lookback, SentimentOscillationModule calculator) {
            this.storeName = storeName;
            this.lookback = lookback;
            this.calculator = calculator;
        }

        @Override
        public void init(ProcessorContext<String, SOMOutput> context) {
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
            if (candles == null || candles.size() < 10) return;

            SOMOutput output = calculator.calculate(
                    candle.getScripCode(),
                    candle.getCompanyName(),
                    candles
            );

            if (output != null) {
                context.forward(new Record<>(key, output, record.timestamp()));
            }
        }

        @Override
        public void close() {}
    }

    /**
     * VTD Processor with unique state store and OHM integration
     */
    private static class VTDProcessor implements Processor<String, FamilyCandle, String, VTDOutput> {
        private final String storeName;
        private final int lookback;
        private final VolatilityTrapDetector calculator;
        private final OptionHealthModule ohmService;
        private ProcessorContext<String, VTDOutput> context;
        private KeyValueStore<String, VCPProcessor.CandleHistory> historyStore;

        VTDProcessor(String storeName, int lookback, VolatilityTrapDetector calculator,
                    OptionHealthModule ohmService) {
            this.storeName = storeName;
            this.lookback = lookback;
            this.calculator = calculator;
            this.ohmService = ohmService;
        }

        @Override
        public void init(ProcessorContext<String, VTDOutput> context) {
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
            if (candles == null || candles.size() < 10) return;

            // Get IV from OHM cache (no API call!)
            Double ivPercentile = null;
            OptionHealthOutput ohm = ohmService.getCached(key);
            if (ohm != null && ohm.getIvPercentile() > 0) {
                ivPercentile = ohm.getIvPercentile();
            }

            VTDOutput output = calculator.calculate(
                    candle.getScripCode(),
                    candle.getCompanyName(),
                    candles,
                    ivPercentile
            );

            if (output != null) {
                context.forward(new Record<>(key, output, record.timestamp()));
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

    private void startStream(StreamsBuilder builder, Properties props, String instanceKey) {
        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streamsInstances.put(instanceKey, streams);

        streams.setStateListener((newState, oldState) -> {
            LOGGER.info("UnifiedSignalProcessor {} state: {} -> {}", instanceKey, oldState, newState);
        });

        streams.setUncaughtExceptionHandler((Throwable e) -> {
            LOGGER.error("❌ Exception in UnifiedSignalProcessor {}: ", instanceKey, e);
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });

        try {
            streams.start();
            LOGGER.info("✅ Started UnifiedSignalProcessor: {}", instanceKey);
        } catch (Exception e) {
            LOGGER.error("❌ Failed to start UnifiedSignalProcessor {}: ", instanceKey, e);
            streamsInstances.remove(instanceKey);
        }
    }

    @PreDestroy
    public void stopAllStreams() {
        LOGGER.info("🛑 Stopping all UnifiedSignalProcessor streams");

        if (statsExecutor != null) {
            statsExecutor.shutdown();
        }

        logCacheStats();

        if (cachedVCP != null) cachedVCP.shutdown();
        if (cachedMagnitudes != null) cachedMagnitudes.shutdown();

        for (String key : new ArrayList<>(streamsInstances.keySet())) {
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
