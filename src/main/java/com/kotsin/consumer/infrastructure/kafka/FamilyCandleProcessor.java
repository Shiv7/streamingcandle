package com.kotsin.consumer.infrastructure.kafka;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.domain.calculator.FuturesBuildupDetector;
import com.kotsin.consumer.domain.calculator.OISignalDetector;
import com.kotsin.consumer.domain.calculator.PCRCalculator;
import com.kotsin.consumer.domain.model.*;
import com.kotsin.consumer.domain.service.IFamilyDataProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * FamilyCandleProcessor - Groups individual instrument candles into family candles.
 * 
 * Input: instrument-candle-1m (all instruments: equity, future, options)
 * Output: family-candle-{tf} (grouped by equity family)
 * 
 * Architecture:
 * 1. Consume all instrument candles from unified topic
 * 2. For each equity candle, lookup its family (future + 4 options)
 * 3. Collect all family member candles from a window
 * 4. Compute cross-instrument metrics
 * 5. Emit FamilyCandle
 * 
 * Key Design:
 * - Uses LEFT JOIN semantics: equity is mandatory, derivatives are optional
 * - Never discards candles: if only equity is available, still emit
 * - Caches family relationships to avoid repeated API calls
 */
@Component
@Slf4j
public class FamilyCandleProcessor {

    @Autowired
    private KafkaConfig kafkaConfig;

    @Autowired
    private IFamilyDataProvider familyDataProvider;
    
    @Autowired
    private com.kotsin.consumer.monitoring.DataQualityMetrics dataQualityMetrics;

    @Value("${family.candle.window.grace.seconds:5}")
    private int graceSeconds;

    @Value("${family.candle.processor.enabled:true}")
    private boolean processorEnabled;

    @Value("${unified.output.topic.instrument:instrument-candle-1m}")
    private String inputTopic;

    @Value("${family.output.topics.1m:family-candle-1m}")
    private String outputTopic;

    private KafkaStreams streams;

    /**
     * Start the FamilyCandleProcessor
     * FIX: This was missing! Without @PostConstruct, the processor never started.
     */
    @PostConstruct
    public void start() {
        if (!processorEnabled) {
            log.info("FamilyCandleProcessor is DISABLED");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                log.info("🚀 Starting FamilyCandleProcessor...");
                
                Properties props = kafkaConfig.getStreamProperties("family-candle-processor");
                StreamsBuilder builder = new StreamsBuilder();
                
                buildTopology(builder, inputTopic, outputTopic, 1);
                
                streams = new KafkaStreams(builder.build(), props);
                setupExceptionHandling();
                
                streams.start();
                log.info("✅ FamilyCandleProcessor started: {} -> {}", inputTopic, outputTopic);
                
            } catch (Exception e) {
                log.error("❌ Failed to start FamilyCandleProcessor", e);
            }
        });
    }

    /**
     * Setup exception handling for the stream
     */
    private void setupExceptionHandling() {
        streams.setStateListener((newState, oldState) -> {
            log.info("FamilyCandleProcessor state: {} -> {}", oldState, newState);
            if (newState == KafkaStreams.State.ERROR) {
                log.error("❌ FamilyCandleProcessor entered ERROR state!");
            }
        });

        streams.setUncaughtExceptionHandler(exception -> {
            log.error("Uncaught exception in FamilyCandleProcessor", exception);
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });
    }

    @PreDestroy
    public void stop() {
        if (streams != null) {
            log.info("🛑 Stopping FamilyCandleProcessor...");
            streams.close(Duration.ofSeconds(30));
            log.info("✅ FamilyCandleProcessor stopped");
        }
    }

    /**
     * Build the family candle topology
     *
     * @param builder StreamsBuilder
     * @param inputTopic Input topic (instrument-candle-1m)
     * @param outputTopic Output topic (family-candle-1m)
     * @param windowSizeMinutes Window size in minutes
     */
    public void buildTopology(StreamsBuilder builder, String inputTopic, String outputTopic, int windowSizeMinutes) {
        log.info("Building FamilyCandleProcessor topology: {} -> {}, window={}m", 
            inputTopic, outputTopic, windowSizeMinutes);

        // State store name - Materialized.as() will handle creation
        String stateStoreName = "family-members-store-" + windowSizeMinutes + "m";

        // Consume instrument candles
        KStream<String, InstrumentCandle> instruments = builder.stream(
            inputTopic,
            Consumed.with(Serdes.String(), InstrumentCandle.serde())
        );

        // Key by family ID (equity scripCode)
        KStream<String, InstrumentCandle> keyedByFamily = instruments
            .filter((key, candle) -> candle != null && candle.getScripCode() != null)
            .selectKey((key, candle) -> getFamilyId(candle));

        // Window and aggregate
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            Duration.ofMinutes(windowSizeMinutes),
            Duration.ofSeconds(graceSeconds)
        );

        KTable<Windowed<String>, FamilyCandleCollector> collected = keyedByFamily
            .groupByKey(Grouped.with(Serdes.String(), InstrumentCandle.serde()))
            .windowedBy(windows)
            .aggregate(
                FamilyCandleCollector::new,
                (familyId, candle, collector) -> collector.add(candle),
                Materialized.<String, FamilyCandleCollector, org.apache.kafka.streams.state.WindowStore<org.apache.kafka.common.utils.Bytes, byte[]>>as(stateStoreName)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(FamilyCandleCollector.serde())
            )
            .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        // Convert to FamilyCandle and emit with metrics tracking
        // FIX: Allow futures-only families (for commodities like MCX Gold, Crude, etc.)
        // Commodities don't have underlying equity - future IS the primary instrument
        collected.toStream()
            .filter((windowedKey, collector) -> collector != null && collector.hasPrimaryInstrument())
            .mapValues((windowedKey, collector) -> {
                FamilyCandle familyCandle = buildFamilyCandle(windowedKey, collector);
                // Record metrics
                if (dataQualityMetrics != null && familyCandle != null) {
                    dataQualityMetrics.recordCandleProcessed("FamilyCandleProcessor", "1m", true);
                }
                return familyCandle;
            })
            .map((windowedKey, familyCandle) -> KeyValue.pair(windowedKey.key(), familyCandle))
            .to(outputTopic, Produced.with(Serdes.String(), FamilyCandle.serde()));

        log.info("FamilyCandleProcessor topology built successfully");
    }

    /**
     * Get family ID for any instrument candle
     * Returns the equity scripCode for all family members
     * 
     * FIXED: Now properly handles options by extracting symbol root and looking up equity.
     * When no reverse mapping exists, extracts symbol (e.g., "ADANIENT" from "ADANIENT 30 DEC 2025 CE 2260.00")
     * and searches cached families for matching symbol.
     */
    private String getFamilyId(InstrumentCandle candle) {
        String scripCode = candle.getScripCode();

        // Use IFamilyDataProvider for reverse mapping (DIP compliance)
        String equityScripCode = familyDataProvider.getEquityScripCode(scripCode);

        // If mapping found and different from input, we have the equity
        if (!equityScripCode.equals(scripCode)) {
            return equityScripCode;
        }
        
        // No mapping found - need to determine type and handle accordingly
        InstrumentType type = candle.getInstrumentType();
        if (type == null) {
            type = InstrumentType.detect(
                candle.getExchange(),
                candle.getExchangeType(),
                candle.getCompanyName()
            );
        }

        // If equity or index, prefetch family data for future lookups
        if (type == InstrumentType.EQUITY || type == InstrumentType.INDEX) {
            // Cache the symbol -> scripCode mapping for derivative lookups
            String symbol = candle.getCompanyName();
            if (symbol != null && !symbol.isEmpty()) {
                symbolToScripCodeCache.put(symbol.toUpperCase(), scripCode);
            }
            // Trigger family fetch if not cached (IFamilyDataProvider handles this)
            familyDataProvider.getFamily(scripCode, candle.getClose());
            return scripCode;
        }

        // For derivatives (futures/options) without mapping, try symbol extraction
        String symbolRoot = extractSymbolRoot(candle.getCompanyName());
        if (symbolRoot != null && !symbolRoot.isEmpty()) {
            // Try to find equity scripCode by looking up symbol in cached families
            String mappedEquity = findEquityBySymbol(symbolRoot);
            if (mappedEquity != null) {
                return mappedEquity;
            }
            // Log this so we can track unmapped options
            log.debug("No equity mapping found for {} (symbol: {}), using scripCode as familyId", 
                candle.getCompanyName(), symbolRoot);
        }
        
        // Fallback to scripCode - option will be grouped alone
        return scripCode;
    }
    
    /**
     * Find equity scripCode by symbol name
     * Searches cached families for matching symbol
     */
    private String findEquityBySymbol(String symbol) {
        if (symbol == null || symbol.isEmpty()) {
            return null;
        }
        // Search through cached families to find one with matching symbol
        // This works because equities usually have their symbol as company name
        // Note: This is a workaround until a proper symbol->scripCode index is built
        return symbolToScripCodeCache.get(symbol.toUpperCase());
    }
    
    // Cache for symbol -> equity scripCode mapping (built when equities are processed)
    private final java.util.concurrent.ConcurrentHashMap<String, String> symbolToScripCodeCache = 
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Extract symbol root from company name
     * e.g., "UNOMINDA 30 DEC 2025 CE 1280.00" -> "UNOMINDA"
     */
    private String extractSymbolRoot(String companyName) {
        if (companyName == null || companyName.isEmpty()) {
            return null;
        }
        // First word is usually the symbol root
        String[] parts = companyName.split("\\s+");
        if (parts.length > 0) {
            return parts[0];
        }
        return null;
    }

    /**
     * REMOVED: findEquityScripCode() - now handled by FamilyCacheAdapter
     * REMOVED: prefetchFamily() - now handled by FamilyCacheAdapter
     */

    /**
     * Build FamilyCandle from collected instruments
     */
    private FamilyCandle buildFamilyCandle(Windowed<String> windowedKey, FamilyCandleCollector collector) {
        String familyId = windowedKey.key();
        
        // Log merge stats if any merging occurred (indicates multiple candles in window)
        if (collector.getEquityMergeCount() > 0 || collector.getFutureMergeCount() > 0) {
            log.debug("FamilyCandle {} merged: equity={} future={} options={}", 
                familyId, 
                collector.getEquityMergeCount() + 1, 
                collector.getFutureMergeCount() + 1,
                collector.getOptions().size());
        }
        
        FamilyCandle.FamilyCandleBuilder builder = FamilyCandle.builder()
            .familyId(familyId)
            .timestamp(System.currentTimeMillis())
            .windowStartMillis(windowedKey.window().start())
            .windowEndMillis(windowedKey.window().end())
            .timeframe("1m");

        // Set human readable time
        ZonedDateTime zdt = ZonedDateTime.ofInstant(
            Instant.ofEpochMilli(windowedKey.window().start()),
            ZoneId.of("Asia/Kolkata")
        );
        builder.humanReadableTime(zdt.toString());

        // Set equity candle
        InstrumentCandle equity = collector.getEquity();
        InstrumentCandle future = collector.getFuture();
        
        // Determine primary instrument: equity for NSE stocks, future for MCX commodities
        InstrumentCandle primary = equity != null ? equity : future;
        
        if (equity != null) {
            // VALIDATE OHLC
            validateOHLC(equity, "EQUITY", familyId);
            builder.equity(equity);
            builder.symbol(extractSymbolRoot(equity.getCompanyName()));
        } else if (future != null) {
            // For commodities: use future as the primary instrument
            // Set as "equity" slot for downstream compatibility (MTISProcessor expects equity)
            builder.equity(future);  // Commodity future acts as primary
            builder.symbol(extractSymbolRoot(future.getCompanyName()));
            builder.isCommodity(true);  // Flag this as commodity family
            log.debug("Commodity family {} using future as primary: {}", familyId, future.getCompanyName());
        }

        // Set future candle (already retrieved above)
        if (future != null && equity != null) {
            // Only validate future separately if we have both equity and future
            validateOHLC(future, "FUTURE", familyId);
            builder.future(future);
        } else if (future != null && equity == null) {
            // Commodity case: future is already set as equity, don't duplicate
            builder.future(null);  // Already used as primary
        }
        builder.hasFuture(future != null);

        // Set options (now deduplicated!)
        List<OptionCandle> options = collector.getOptions().stream()
            .map(OptionCandle::fromInstrumentCandle)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        builder.options(options);
        builder.hasOptions(!options.isEmpty());
        builder.optionCount(options.size());

        // Calculate cross-instrument metrics
        calculateCrossInstrumentMetrics(builder, equity, future, options);

        return builder
            .quality(DataQuality.VALID)
            .build();
    }
    
    /**
     * Validate OHLC data, log errors, and record metrics
     * @return true if valid, false if any violations found
     */
    private boolean validateOHLC(InstrumentCandle candle, String type, String familyId) {
        double o = candle.getOpen();
        double h = candle.getHigh();
        double l = candle.getLow();
        double c = candle.getClose();
        boolean valid = true;
        
        // Check High >= Low
        if (h < l) {
            log.error("🚨 OHLC INVALID | {} {} | high={} < low={}", type, familyId, h, l);
            if (dataQualityMetrics != null) {
                dataQualityMetrics.recordViolation("OHLC_HIGH_LESS_THAN_LOW");
            }
            valid = false;
        }
        // Check High is highest
        if (h < o || h < c) {
            log.error("🚨 HIGH NOT HIGHEST | {} {} | O={} H={} L={} C={}", type, familyId, o, h, l, c);
            if (dataQualityMetrics != null) {
                dataQualityMetrics.recordViolation("HIGH_NOT_HIGHEST");
            }
            valid = false;
        }
        // Check Low is lowest
        if (l > o || l > c) {
            log.error("🚨 LOW NOT LOWEST | {} {} | O={} H={} L={} C={}", type, familyId, o, h, l, c);
            if (dataQualityMetrics != null) {
                dataQualityMetrics.recordViolation("LOW_NOT_LOWEST");
            }
            valid = false;
        }
        // Check for zero/negative close
        if (c <= 0) {
            log.error("🚨 INVALID CLOSE | {} {} | close={}", type, familyId, c);
            if (dataQualityMetrics != null) {
                dataQualityMetrics.recordViolation("INVALID_CLOSE");
            }
            valid = false;
        }
        return valid;
    }

    /**
     * Calculate all cross-instrument metrics
     */
    private void calculateCrossInstrumentMetrics(
        FamilyCandle.FamilyCandleBuilder builder,
        InstrumentCandle equity,
        InstrumentCandle future,
        List<OptionCandle> options
    ) {
        // Spot-Future Premium
        if (equity != null && future != null && equity.getClose() > 0) {
            double premium = (future.getClose() - equity.getClose()) / equity.getClose() * 100;
            builder.spotFuturePremium(premium);
            
            // Futures buildup
            if (future.hasOI()) {
                FuturesBuildupDetector.BuildupType buildup = FuturesBuildupDetector.detect(future);
                builder.futuresBuildup(buildup.name());
                builder.futureOiBuildingUp(future.getOiChangePercent() != null && future.getOiChangePercent() > 2);
                builder.futureOIChange(future.getOiChange());
            }
        }

        // Options metrics
        if (options != null && !options.isEmpty()) {
            // PCR
            Double pcr = PCRCalculator.calculate(options);
            builder.pcr(pcr);
            
            // Total OI
            builder.totalCallOI(PCRCalculator.getTotalCallOI(options));
            builder.totalPutOI(PCRCalculator.getTotalPutOI(options));
            builder.totalCallOIChange(PCRCalculator.getTotalCallOIChange(options));
            builder.totalPutOIChange(PCRCalculator.getTotalPutOIChange(options));
            
            // OI change flags
            long callOIChange = PCRCalculator.getTotalCallOIChange(options);
            long putOIChange = PCRCalculator.getTotalPutOIChange(options);
            builder.callOiBuildingUp(callOIChange > 1000);
            builder.putOiUnwinding(putOIChange < -1000);
        }

        // OI Signal (using the full family)
        if (equity != null) {
            FamilyCandle tempFamily = FamilyCandle.builder()
                .equity(equity)
                .future(future)
                .options(options)
                .build();
            OISignalDetector.OISignalType oiSignal = OISignalDetector.detect(tempFamily);
            builder.oiSignal(oiSignal.name());
            
            // Directional bias
            int bias = OISignalDetector.getDirectionalBias(oiSignal);
            if (bias > 0) {
                builder.directionalBias("BULLISH");
            } else if (bias < 0) {
                builder.directionalBias("BEARISH");
            } else {
                builder.directionalBias("NEUTRAL");
            }
            builder.biasConfidence(OISignalDetector.getConfidence(tempFamily, oiSignal));
        }
    }

    /**
     * Collector for family members within a window
     * FIXED: Now properly merges OHLCV instead of replacing
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    public static class FamilyCandleCollector {
        private InstrumentCandle equity;
        private InstrumentCandle future;
        private List<InstrumentCandle> options = new ArrayList<>();
        private Map<String, InstrumentCandle> optionsByScripCode = new HashMap<>();
        
        // Track merge counts for debugging
        private int equityMergeCount = 0;
        private int futureMergeCount = 0;

        public FamilyCandleCollector add(InstrumentCandle candle) {
            if (candle == null) return this;
            
            InstrumentType type = candle.getInstrumentType();
            if (type == null) {
                type = InstrumentType.detect(
                    candle.getExchange(),
                    candle.getExchangeType(),
                    candle.getCompanyName()
                );
                candle.setInstrumentType(type);
            }

            switch (type) {
                case EQUITY:
                case INDEX:
                    if (this.equity == null) {
                        this.equity = candle;
                    } else {
                        // MERGE instead of replace!
                        mergeInstrumentCandle(this.equity, candle);
                        equityMergeCount++;
                    }
                    break;
                case FUTURE:
                    if (this.future == null) {
                        this.future = candle;
                    } else {
                        // MERGE instead of replace!
                        mergeInstrumentCandle(this.future, candle);
                        futureMergeCount++;
                    }
                    break;
                case OPTION_CE:
                case OPTION_PE:
                    // Deduplicate by scripCode and merge OHLCV
                    String scripCode = candle.getScripCode();
                    if (scripCode != null) {
                        InstrumentCandle existing = optionsByScripCode.get(scripCode);
                        if (existing == null) {
                            optionsByScripCode.put(scripCode, candle);
                        } else {
                            // Merge OHLCV for same option
                            mergeInstrumentCandle(existing, candle);
                        }
                    }
                    break;
            }
            return this;
        }
        
        /**
         * Merge OHLCV: Open=first, High=max, Low=min, Close=last, Volume=sum
         */
        private void mergeInstrumentCandle(InstrumentCandle aggregate, InstrumentCandle incoming) {
            // Open stays from first (aggregate)
            aggregate.setHigh(Math.max(aggregate.getHigh(), incoming.getHigh()));
            aggregate.setLow(Math.min(aggregate.getLow(), incoming.getLow()));
            aggregate.setClose(incoming.getClose());
            aggregate.setVolume(aggregate.getVolume() + incoming.getVolume());
            aggregate.setBuyVolume(aggregate.getBuyVolume() + incoming.getBuyVolume());
            aggregate.setSellVolume(aggregate.getSellVolume() + incoming.getSellVolume());
            aggregate.setTickCount(aggregate.getTickCount() + incoming.getTickCount());
            aggregate.setWindowEndMillis(incoming.getWindowEndMillis());
            
            // Merge OI if present
            if (incoming.isOiPresent()) {
                aggregate.setOiPresent(true);
                if (incoming.getOiHigh() != null) {
                    Long aggHigh = aggregate.getOiHigh();
                    aggregate.setOiHigh(aggHigh != null ? Math.max(aggHigh, incoming.getOiHigh()) : incoming.getOiHigh());
                }
                if (incoming.getOiLow() != null) {
                    Long aggLow = aggregate.getOiLow();
                    aggregate.setOiLow(aggLow != null ? Math.min(aggLow, incoming.getOiLow()) : incoming.getOiLow());
                }
                aggregate.setOiClose(incoming.getOiClose());
                aggregate.setOpenInterest(incoming.getOpenInterest());
            }
        }
        
        /**
         * Get deduplicated options list
         */
        public List<InstrumentCandle> getOptions() {
            // Return deduplicated options from map
            if (!optionsByScripCode.isEmpty()) {
                return new ArrayList<>(optionsByScripCode.values());
            }
            return options;
        }

        public boolean hasEquity() {
            return equity != null;
        }
        
        /**
         * Check if collector has a primary instrument.
         * For NSE stocks: equity is primary
         * For MCX commodities: future is primary (no equity exists)
         */
        public boolean hasPrimaryInstrument() {
            return equity != null || future != null;
        }
        
        public int getEquityMergeCount() {
            return equityMergeCount;
        }
        
        public int getFutureMergeCount() {
            return futureMergeCount;
        }

        public static org.apache.kafka.common.serialization.Serde<FamilyCandleCollector> serde() {
            return new org.springframework.kafka.support.serializer.JsonSerde<>(FamilyCandleCollector.class);
        }
    }
}
