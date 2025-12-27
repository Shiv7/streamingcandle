package com.kotsin.consumer.infrastructure.kafka;

import com.kotsin.consumer.domain.calculator.FuturesBuildupDetector;
import com.kotsin.consumer.domain.calculator.OISignalDetector;
import com.kotsin.consumer.domain.calculator.PCRCalculator;
import com.kotsin.consumer.domain.model.*;
import com.kotsin.consumer.infrastructure.api.ScripFinderClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
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
import java.util.concurrent.ConcurrentHashMap;
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
    private ScripFinderClient scripFinderClient;

    @Value("${family.candle.window.grace.seconds:5}")
    private int graceSeconds;

    @Value("${family.candle.cache.ttl.hours:24}")
    private int cacheTtlHours;

    // Cache for family relationships (scripCode -> InstrumentFamily)
    private final Map<String, InstrumentFamily> familyCache = new ConcurrentHashMap<>();
    
    // Cache for reverse lookup (any scripCode -> equityScripCode)
    private final Map<String, String> reverseMapping = new ConcurrentHashMap<>();

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

        // State store for collecting family members
        String stateStoreName = "family-members-store-" + windowSizeMinutes + "m";
        builder.addStateStore(Stores.keyValueStoreBuilder(
            Stores.persistentKeyValueStore(stateStoreName),
            Serdes.String(),
            FamilyCandleCollector.serde()
        ));

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
                Materialized.as(stateStoreName)
            )
            .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        // Convert to FamilyCandle and emit
        collected.toStream()
            .filter((windowedKey, collector) -> collector != null && collector.hasEquity())
            .mapValues((windowedKey, collector) -> buildFamilyCandle(windowedKey, collector))
            .map((windowedKey, familyCandle) -> KeyValue.pair(windowedKey.key(), familyCandle))
            .to(outputTopic, Produced.with(Serdes.String(), FamilyCandle.serde()));

        log.info("FamilyCandleProcessor topology built successfully");
    }

    /**
     * Get family ID for any instrument candle
     * Returns the equity scripCode for all family members
     */
    private String getFamilyId(InstrumentCandle candle) {
        String scripCode = candle.getScripCode();
        
        // Check reverse mapping cache first
        String cachedFamilyId = reverseMapping.get(scripCode);
        if (cachedFamilyId != null) {
            return cachedFamilyId;
        }

        InstrumentType type = candle.getInstrumentType();
        if (type == null) {
            type = InstrumentType.detect(
                candle.getExchange(), 
                candle.getExchangeType(), 
                candle.getCompanyName()
            );
        }

        // If equity or index, scripCode is the family ID
        if (type == InstrumentType.EQUITY || type == InstrumentType.INDEX) {
            // Cache the mapping
            reverseMapping.put(scripCode, scripCode);
            
            // Prefetch family if not cached
            if (!familyCache.containsKey(scripCode)) {
                prefetchFamily(scripCode, candle.getClose());
            }
            return scripCode;
        }

        // For derivatives, try to find equity from symbol root
        String symbolRoot = extractSymbolRoot(candle.getCompanyName());
        if (symbolRoot != null) {
            // Look for equity with matching symbol
            String equityScripCode = findEquityScripCode(symbolRoot);
            if (equityScripCode != null) {
                reverseMapping.put(scripCode, equityScripCode);
                return equityScripCode;
            }
        }

        // Fallback: use scripCode as family ID
        reverseMapping.put(scripCode, scripCode);
        return scripCode;
    }

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
     * Find equity scripCode from symbol
     */
    private String findEquityScripCode(String symbol) {
        // Search in cached families
        for (InstrumentFamily family : familyCache.values()) {
            if (symbol.equalsIgnoreCase(family.getSymbolRoot())) {
                return family.getEquityScripCode();
            }
        }
        return null;
    }

    /**
     * Prefetch family data from API
     */
    private void prefetchFamily(String equityScripCode, double closePrice) {
        try {
            InstrumentFamily family = scripFinderClient.getFamily(equityScripCode, closePrice);
            if (family != null) {
                familyCache.put(equityScripCode, family);
                
                // Build reverse mappings
                if (family.hasFuture()) {
                    reverseMapping.put(family.getFutureScripCode(), equityScripCode);
                }
                if (family.hasOptions()) {
                    for (InstrumentFamily.OptionInfo opt : family.getOptions()) {
                        reverseMapping.put(opt.getScripCode(), equityScripCode);
                    }
                }
                
                log.debug("Prefetched family for {}: future={}, options={}", 
                    equityScripCode, family.hasFuture(), family.getOptionCount());
            }
        } catch (Exception e) {
            log.warn("Failed to prefetch family for {}: {}", equityScripCode, e.getMessage());
        }
    }

    /**
     * Build FamilyCandle from collected instruments
     */
    private FamilyCandle buildFamilyCandle(Windowed<String> windowedKey, FamilyCandleCollector collector) {
        String familyId = windowedKey.key();
        
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
        if (equity != null) {
            builder.equity(equity);
            builder.symbol(extractSymbolRoot(equity.getCompanyName()));
        }

        // Set future candle
        InstrumentCandle future = collector.getFuture();
        builder.future(future);
        builder.hasFuture(future != null);

        // Set options
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
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    public static class FamilyCandleCollector {
        private InstrumentCandle equity;
        private InstrumentCandle future;
        private List<InstrumentCandle> options = new ArrayList<>();

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
                    this.equity = candle;
                    break;
                case FUTURE:
                    this.future = candle;
                    break;
                case OPTION_CE:
                case OPTION_PE:
                    this.options.add(candle);
                    break;
            }
            return this;
        }

        public boolean hasEquity() {
            return equity != null;
        }

        public static org.apache.kafka.common.serialization.Serde<FamilyCandleCollector> serde() {
            return new org.springframework.kafka.support.serializer.JsonSerde<>(FamilyCandleCollector.class);
        }
    }
}
