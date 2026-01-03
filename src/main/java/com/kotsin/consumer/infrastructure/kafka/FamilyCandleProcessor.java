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

        // 🛡️ CRITICAL FIX: Event-Time Processing for Replay & Live Consistency
        //
        // BEFORE (BROKEN):
        // - Used Kafka record timestamp (ingestion time)
        // - Replay of Dec 24 data never closed windows because wall clock is Jan 1
        // - Inconsistent behavior between replay and live data
        //
        // AFTER (FIXED):
        // - Uses InstrumentCandle.windowStartMillis (event time)
        // - Windows close based on DATA time, not wall clock
        // - Replay and live data behave identically
        //
        // Consume instrument candles with event-time extraction
        KStream<String, InstrumentCandle> instruments = builder.stream(
            inputTopic,
            Consumed.with(Serdes.String(), InstrumentCandle.serde())
                .withTimestampExtractor(new com.kotsin.consumer.timeExtractor.InstrumentCandleTimestampExtractor())
        );

        // Key by family ID (equity scripCode)
        KStream<String, InstrumentCandle> keyedByFamily = instruments
            .filter((key, candle) -> {
                boolean passes = candle != null && candle.getScripCode() != null;
                // #region agent log
                if (!passes) {
                    try {
                        java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
                        String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"FILTER-A\",\"location\":\"FamilyCandleProcessor.java:171\",\"message\":\"Candle filtered out\",\"data\":{\"key\":\"%s\",\"candleNull\":%s,\"scripCodeNull\":%s},\"timestamp\":%d}\n",
                            key, candle == null, candle != null && candle.getScripCode() == null, System.currentTimeMillis());
                        fw.write(json);
                        fw.close();
                    } catch (Exception e) {}
                }
                // #endregion
                return passes;
            })
            .selectKey((key, candle) -> {
                String familyId = getFamilyId(candle);
                // #region agent log
                try {
                    java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
                    String instrumentType = candle.getInstrumentType() != null ? candle.getInstrumentType().name() : "UNKNOWN";
                    String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"GROUP-A\",\"location\":\"FamilyCandleProcessor.java:172\",\"message\":\"FamilyId grouping\",\"data\":{\"scripCode\":\"%s\",\"originalKey\":\"%s\",\"familyId\":\"%s\",\"instrumentType\":\"%s\",\"hasOI\":%s,\"openInterest\":%s},\"timestamp\":%d}\n",
                        candle.getScripCode(), key, familyId, instrumentType, candle.hasOI(), candle.getOpenInterest() != null ? candle.getOpenInterest() : "null", System.currentTimeMillis());
                    fw.write(json);
                    fw.close();
                } catch (Exception e) {}
                // #endregion
                return familyId;
            });

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
            .filter((windowedKey, collector) -> {
                boolean passes = collector != null && collector.hasPrimaryInstrument();
                // #region agent log
                if (passes) {
                    try {
                        java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
                        String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"STREAM-A\",\"location\":\"FamilyCandleProcessor.java:223\",\"message\":\"Collector before buildFamilyCandle\",\"data\":{\"familyId\":\"%s\",\"equityNotNull\":%s,\"futureNotNull\":%s,\"futureScripCode\":\"%s\",\"optionsSize\":%d},\"timestamp\":%d}\n",
                            windowedKey.key(), collector.getEquity() != null, collector.getFuture() != null, collector.getFuture() != null ? collector.getFuture().getScripCode() : "null", collector.getOptions().size(), System.currentTimeMillis());
                        fw.write(json);
                        fw.close();
                    } catch (Exception e) {}
                }
                // #endregion
                return passes;
            })
            .mapValues((windowedKey, collector) -> {
                FamilyCandle familyCandle = buildFamilyCandle(windowedKey, collector);
                // Record metrics
                if (dataQualityMetrics != null && familyCandle != null) {
                    dataQualityMetrics.recordCandleProcessed("FamilyCandleProcessor", "1m", true);
                }
                return familyCandle;
            })
            .map((windowedKey, familyCandle) -> KeyValue.pair(windowedKey.key(), familyCandle))
            .peek((key, familyCandle) -> {
                // #region agent log
                try {
                    if (familyCandle != null) {
                        String familyId = familyCandle.getFamilyId();
                        java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
                        String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"KAFKA-EMIT\",\"location\":\"FamilyCandleProcessor.java:247\",\"message\":\"FamilyCandle before Kafka emit\",\"data\":{\"familyId\":\"%s\",\"futureNotNull\":%s,\"futureScripCode\":\"%s\",\"isCommodity\":%s},\"timestamp\":%d}\n",
                            familyId, familyCandle.getFuture() != null, familyCandle.getFuture() != null ? familyCandle.getFuture().getScripCode() : "null", familyCandle.isCommodity(), System.currentTimeMillis());
                        fw.write(json);
                        fw.close();
                    }
                } catch (Exception e) {}
                // #endregion
                if (log.isDebugEnabled() && familyCandle != null) {
                    String familyId = familyCandle.getFamilyId();
                    InstrumentCandle equity = familyCandle.getEquity();
                    String scripCode = equity != null ? equity.getScripCode() : "N/A";
                    log.debug("[FAMILY] {} | {} | equity={} future={} options={} | OHLC={}/{}/{}/{} vol={} | emitted to {}", 
                        familyId, scripCode,
                        equity != null ? "YES" : "NO",
                        familyCandle.getFuture() != null ? "YES" : "NO",
                        familyCandle.getOptions() != null ? familyCandle.getOptions().size() : 0,
                        equity != null ? equity.getOpen() : 0.0, 
                        equity != null ? equity.getHigh() : 0.0, 
                        equity != null ? equity.getLow() : 0.0,
                        equity != null ? equity.getClose() : 0.0,
                        equity != null ? equity.getVolume() : 0L, outputTopic);
                }
            })
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
        InstrumentType type = candle.getInstrumentType();
        String companyName = candle.getCompanyName();
        String exchange = candle.getExchange();
        String exchangeType = candle.getExchangeType();
        
        log.debug("[FAMILY-ID-START] scripCode: {} | type: {} | companyName: '{}' | exchange: {} | exchangeType: {}",
            scripCode, type != null ? type.name() : "null", 
            companyName != null ? companyName : "null", exchange, exchangeType);
        
        if (type == null) {
            type = InstrumentType.detect(exchange, exchangeType, companyName);
            log.debug("[FAMILY-ID-TYPE-DETECTED] scripCode: {} | Detected type: {} (was null, detected from exchange/exchangeType/companyName)",
                scripCode, type != null ? type.name() : "null");
        }
        
        // Step 1: Get equity scripCode (queries MongoDB if not cached)
        log.debug("[FAMILY-ID-STEP1] scripCode: {} | Querying getEquityScripCode...", scripCode);
        long step1Start = System.currentTimeMillis();
        String equityScripCode = familyDataProvider.getEquityScripCode(scripCode);
        long step1Time = System.currentTimeMillis() - step1Start;
        
        log.debug("[FAMILY-ID-STEP1-RESULT] scripCode: {} | getEquityScripCode returned: '{}' | queryTime: {}ms",
            scripCode, equityScripCode != null ? equityScripCode : "null", step1Time);
        
        // If mapping found, return equity scripCode
        if (equityScripCode != null && !equityScripCode.equals(scripCode)) {
            // Enhanced logging for options
            if (type == InstrumentType.OPTION_CE || type == InstrumentType.OPTION_PE) {
                log.info("[FAMILY-ID-STEP1-SUCCESS] Option mapped | scripCode: {} | type: {} | familyId: {} | window: [{}, {}] | queryTime: {}ms", 
                    scripCode, type, equityScripCode, candle.getWindowStartMillis(), candle.getWindowEndMillis(), step1Time);
            } else {
                log.info("[FAMILY-ID-STEP1-SUCCESS] Direct mapping found | scripCode: {} | type: {} | familyId: {} | queryTime: {}ms", 
                    scripCode, type, equityScripCode, step1Time);
            }
            return equityScripCode;
        }
        
        // Step 2: If equity/index, it's already the family ID
        if (type == InstrumentType.EQUITY || type == InstrumentType.INDEX) {
            log.info("[FAMILY-ID-STEP2-SUCCESS] Equity/Index - self | scripCode: {} | type: {} | familyId: {} (triggering family fetch for reverse mappings)",
                scripCode, type, scripCode);
            // Trigger family fetch to build reverse mappings for derivatives
            familyDataProvider.getFamily(scripCode, candle.getClose());
            return scripCode;
        }
        
        // Step 3: Mapping failed? Try to find Equity by SYMBOL Name
        // (Solves the issue where "SUPREMEIND 27 JAN" exists but isn't mapped to ID 50081)
        log.info("[FAMILY-ID-STEP3-START] Symbol-based lookup | scripCode: {} | type: {} | companyName: '{}'",
            scripCode, type, companyName != null ? companyName : "null");
        
        String symbolRoot = null;
        if (companyName != null && !companyName.isEmpty()) {
            long symbolExtractStart = System.currentTimeMillis();
            symbolRoot = extractSymbolRoot(companyName);
            long symbolExtractTime = System.currentTimeMillis() - symbolExtractStart;
            
            log.info("[FAMILY-ID-STEP3-SYMBOL-EXTRACT] scripCode: {} | companyName: '{}' | extracted symbol: '{}' | extractTime: {}ms",
                scripCode, companyName, symbolRoot != null ? symbolRoot : "null", symbolExtractTime);
        } else {
            log.warn("[FAMILY-ID-STEP3-SKIP] scripCode: {} | companyName is null/empty - cannot extract symbol",
                scripCode);
        }
        
        if (symbolRoot != null) {
            log.info("[FAMILY-ID-STEP3-SYMBOL-LOOKUP] scripCode: {} | Querying findEquityBySymbol for symbol: '{}'...",
                scripCode, symbolRoot);
            
            long symbolLookupStart = System.currentTimeMillis();
            String symbolBasedId = familyDataProvider.findEquityBySymbol(symbolRoot);
            long symbolLookupTime = System.currentTimeMillis() - symbolLookupStart;
            
            log.info("[FAMILY-ID-STEP3-SYMBOL-LOOKUP-RESULT] scripCode: {} | symbol: '{}' | findEquityBySymbol returned: '{}' | lookupTime: {}ms",
                scripCode, symbolRoot, symbolBasedId != null ? symbolBasedId : "null", symbolLookupTime);
            
            if (symbolBasedId != null) {
                log.info("[FAMILY-ID-STEP3-SUCCESS] Smart Recovery | scripCode: {} | type: {} | familyId: {} | symbol: '{}' | companyName: '{}' | totalTime: {}ms", 
                    scripCode, type, symbolBasedId, symbolRoot, companyName, symbolLookupTime);
                return symbolBasedId;
            } else {
                log.warn("[FAMILY-ID-STEP3-FAILED] Symbol lookup returned null | scripCode: {} | type: {} | symbol: '{}' | companyName: '{}' | findEquityBySymbol found no equity",
                    scripCode, type, symbolRoot, companyName);
            }
        } else {
            log.warn("[FAMILY-ID-STEP3-FAILED] Symbol extraction returned null | scripCode: {} | type: {} | companyName: '{}' | extractSymbolRoot failed",
                scripCode, type, companyName != null ? companyName : "null");
        }
        
        // Step 4: For derivatives without mapping - log warning
        log.error("[FAMILY-ID-STEP4-FAILED] All mapping attempts failed | scripCode: {} | type: {} | " +
                 "companyName: '{}' | symbol: '{}' | " +
                 "Step1 (getEquityScripCode): '{}' | Step3 (findEquityBySymbol): '{}' | " +
                 "Using scripCode as familyId (FALLBACK). This may indicate missing data in ScripGroup/Scrip collections.",
                 scripCode, type, companyName != null ? companyName : "null", symbolRoot != null ? symbolRoot : "null",
                 equityScripCode != null ? equityScripCode : "null", "N/A");
        
        if (type == InstrumentType.OPTION_CE || type == InstrumentType.OPTION_PE) {
            log.warn("[FAMILY-ID-FINAL] Option mapping FAILED | scripCode: {} | type: {} | window: [{}, {}] | " +
                     "companyName: '{}' | symbol: '{}' | Using scripCode as familyId",
                     scripCode, type, candle.getWindowStartMillis(), candle.getWindowEndMillis(), 
                     companyName != null ? companyName : "null", symbolRoot != null ? symbolRoot : "null");
        } else {
            log.warn("[FAMILY-ID-FINAL] Derivative mapping FAILED | scripCode: {} | type: {} | " +
                     "companyName: '{}' | symbol: '{}' | Using scripCode as familyId",
                     scripCode, type, companyName != null ? companyName : "null", symbolRoot != null ? symbolRoot : "null");
        }
        
        return scripCode; // Fallback
    }
    

    /**
     * Extract symbol root from company name
     *
     * Handles various formats:
     * - "UNOMINDA 30 DEC 2025 CE 1280.00" -> "UNOMINDA"
     * - "NIFTY 50" -> "NIFTY"
     * - "BANK NIFTY" -> "BANKNIFTY" (concatenate)
     * - "M&M" -> "M&M" (preserve special chars)
     * - "L&TFH" -> "L&TFH"
     */
    private String extractSymbolRoot(String companyName) {
        if (companyName == null || companyName.isEmpty()) {
            return null;
        }

        String trimmed = companyName.trim();

        // Handle special cases for multi-word symbols
        String upperName = trimmed.toUpperCase();
        if (upperName.startsWith("BANK NIFTY") || upperName.startsWith("BANKNIFTY")) {
            return "BANKNIFTY";
        }
        if (upperName.startsWith("NIFTY BANK")) {
            return "BANKNIFTY";
        }
        if (upperName.startsWith("FIN NIFTY") || upperName.startsWith("FINNIFTY")) {
            return "FINNIFTY";
        }
        if (upperName.startsWith("MIDCP NIFTY") || upperName.startsWith("MIDCPNIFTY")) {
            return "MIDCPNIFTY";
        }

        // Split by whitespace
        String[] parts = trimmed.split("\\s+");

        // If single word or contains numbers/special chars, return as-is
        if (parts.length == 1) {
            return parts[0];
        }

        // Check if second part looks like date/month/year/option type
        // If yes, first part is the symbol
        String secondPart = parts.length > 1 ? parts[1] : "";
        if (secondPart.matches("\\d+") || // Number (day/strike)
            secondPart.matches("(?i)(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)") || // Month
            secondPart.matches("(?i)(CE|PE)") || // Option type
            secondPart.matches("\\d{4}")) { // Year
            return parts[0];
        }

        // For compound symbols like "BANK NIFTY" that didn't match above,
        // take first word (might be imperfect but better than nothing)
        return parts[0];
    }

    /**
     * REMOVED: findEquityScripCode() - now handled by FamilyCacheAdapter
     * REMOVED: prefetchFamily() - now handled by FamilyCacheAdapter
     */
    
    /**
     * Check if instrument is from MCX (Multi Commodity Exchange)
     * MCX instruments don't have underlying equity - futures are the primary instrument
     */
    private boolean isMCXExchange(InstrumentCandle candle) {
        if (candle == null) return false;
        return "M".equalsIgnoreCase(candle.getExchange());
    }

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
        
        // #region agent log
        try {
            java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
            InstrumentCandle equityCheck = collector.getEquity();
            InstrumentCandle futureCheck = collector.getFuture();
            String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"BUILD-A\",\"location\":\"FamilyCandleProcessor.java:437\",\"message\":\"Collector state at buildFamilyCandle\",\"data\":{\"familyId\":\"%s\",\"equityNotNull\":%s,\"futureNotNull\":%s,\"futureScripCode\":\"%s\",\"optionsSize\":%d},\"timestamp\":%d}\n",
                familyId, equityCheck != null, futureCheck != null, futureCheck != null ? futureCheck.getScripCode() : "null", collector.getOptions().size(), System.currentTimeMillis());
            fw.write(json);
            fw.close();
        } catch (Exception e) {}
        // #endregion
        
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
        
        // Detect if this is a commodity based on exchange (MCX = "M")
        // MCX commodities are always flagged as commodities, even if they have both equity and future
        boolean isCommodity = isMCXExchange(primary);
        builder.isCommodity(isCommodity);
        
        if (equity != null) {
            // VALIDATE OHLC
            validateOHLC(equity, "EQUITY", familyId);
            builder.equity(equity);
            String symbol = extractSymbolRoot(equity.getCompanyName());
            // Fallback: if symbol is null, try to get from family data or use scripCode
            if (symbol == null || symbol.isEmpty()) {
                try {
                    InstrumentFamily family = familyDataProvider.getFamily(familyId, equity.getClose());
                    if (family != null && family.getSymbolRoot() != null && !family.getSymbolRoot().isEmpty()) {
                        symbol = family.getSymbolRoot();
                        log.debug("Family {}: Using symbol from family data: {}", familyId, symbol);
                    } else if (family != null && family.getCompanyName() != null && !family.getCompanyName().isEmpty()) {
                        symbol = extractSymbolRoot(family.getCompanyName());
                        log.debug("Family {}: Using symbol from family companyName: {}", familyId, symbol);
                    } else {
                        symbol = familyId; // Last resort: use scripCode
                        log.warn("Family {}: companyName is null, using scripCode as symbol fallback", familyId);
                    }
                } catch (Exception e) {
                    symbol = familyId; // Fallback to scripCode on error
                    log.warn("Family {}: Failed to get symbol from family data, using scripCode: {}", familyId, e.getMessage());
                }
            }
            builder.symbol(symbol);
        } else if (future != null && isCommodity) {
            // ONLY for commodities (MCX): use future as the primary instrument
            // Set as "equity" slot for downstream compatibility (MTISProcessor expects equity)
            builder.equity(future);  // Commodity future acts as primary
            String symbol = extractSymbolRoot(future.getCompanyName());
            // Fallback: if symbol is null, try to get from family data or use scripCode
            if (symbol == null || symbol.isEmpty()) {
                try {
                    InstrumentFamily family = familyDataProvider.getFamily(familyId, future.getClose());
                    if (family != null && family.getSymbolRoot() != null && !family.getSymbolRoot().isEmpty()) {
                        symbol = family.getSymbolRoot();
                        log.debug("Commodity family {}: Using symbol from family data: {}", familyId, symbol);
                    } else if (family != null && family.getCompanyName() != null && !family.getCompanyName().isEmpty()) {
                        symbol = extractSymbolRoot(family.getCompanyName());
                        log.debug("Commodity family {}: Using symbol from family companyName: {}", familyId, symbol);
                    } else {
                        symbol = familyId; // Last resort: use scripCode
                        log.warn("Commodity family {}: companyName is null and no family data, using scripCode as symbol fallback", familyId);
                    }
                } catch (Exception e) {
                    symbol = familyId; // Fallback to scripCode on error
                    log.warn("Commodity family {}: Failed to get symbol from family data, using scripCode: {}", familyId, e.getMessage());
                }
            }
            builder.symbol(symbol);
            // #region agent log
            try {
                java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
                String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"SYMBOL-SET\",\"location\":\"FamilyCandleProcessor.java:561\",\"message\":\"Symbol set for commodity family\",\"data\":{\"familyId\":\"%s\",\"symbol\":\"%s\",\"companyName\":\"%s\",\"fromFamilyData\":%s},\"timestamp\":%d}\n",
                    familyId, symbol, future.getCompanyName() != null ? future.getCompanyName() : "null", symbol != null && !symbol.equals(familyId), System.currentTimeMillis());
                fw.write(json);
                fw.close();
            } catch (Exception e) {}
            // #endregion
            log.debug("Commodity family {} using future as primary: {}", familyId, future.getCompanyName());
        } else if (future != null) {
            // NSE stock with only future (no equity) - this indicates GROUPING FAILURE
            // The equity and future likely have different familyIds due to missing mapping
            // Set future in equity slot as fallback, but this is NOT ideal
            builder.equity(future);  // Use future as fallback, NOT ideal
            String symbol = extractSymbolRoot(future.getCompanyName());
            if (symbol == null || symbol.isEmpty()) {
                symbol = familyId; // Fallback to scripCode
                log.warn("Family {}: companyName is null, using scripCode as symbol fallback", familyId);
            }
            builder.symbol(symbol);
            log.warn("[FAMILY-GROUPING-FAILURE] Family {} has future (scripCode: {}) but NO equity - " +
                     "This indicates equity and future have different familyIds. " +
                     "Check if symbol-based fallback is working. Using future as fallback (incomplete family).",
                     familyId, future.getScripCode());
        }

        // Set future candle (already retrieved above)
        // #region agent log
        try {
            java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
            String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"BUILD-B\",\"location\":\"FamilyCandleProcessor.java:524\",\"message\":\"Setting future in builder\",\"data\":{\"familyId\":\"%s\",\"futureNotNull\":%s,\"equityNotNull\":%s,\"isCommodity\":%s,\"futureScripCode\":\"%s\",\"futureHasOI\":%s},\"timestamp\":%d}\n",
                familyId, future != null, equity != null, isCommodity, future != null ? future.getScripCode() : "null", future != null && future.hasOI(), System.currentTimeMillis());
            fw.write(json);
            fw.close();
        } catch (Exception e) {}
        // #endregion
        if (future != null && equity != null) {
            // Only validate future separately if we have both equity and future
            validateOHLC(future, "FUTURE", familyId);
            builder.future(future);
            // #region agent log
            try {
                java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
                String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"BUILD-C\",\"location\":\"FamilyCandleProcessor.java:528\",\"message\":\"Future set in builder (equity+future case)\",\"data\":{\"familyId\":\"%s\",\"futureScripCode\":\"%s\"},\"timestamp\":%d}\n",
                    familyId, future.getScripCode(), System.currentTimeMillis());
                fw.write(json);
                fw.close();
            } catch (Exception e) {}
            // #endregion
        } else if (future != null && equity == null && isCommodity) {
            // Commodity case: future is set as equity for compatibility, but ALSO set in future field
            // so that MTISProcessor can access OI/PCR via getFuture()
            // Both equity and future slots point to the same InstrumentCandle object
            builder.future(future);  // CRITICAL FIX: Set future field so OI/PCR can be accessed
            builder.hasFuture(true);  // Mark that we have a future
            // #region agent log
            try {
                java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
                String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"BUILD-D\",\"location\":\"FamilyCandleProcessor.java:532\",\"message\":\"Commodity: future in both equity and future slots\",\"data\":{\"familyId\":\"%s\",\"futureScripCode\":\"%s\"},\"timestamp\":%d}\n",
                    familyId, future.getScripCode(), System.currentTimeMillis());
                fw.write(json);
                fw.close();
            } catch (Exception e) {}
            // #endregion
        } else if (future != null && equity == null) {
            // NSE case: future is fallback, also set it in future field
            builder.future(future);
            builder.hasFuture(true);
            // #region agent log
            try {
                java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
                String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"BUILD-E\",\"location\":\"FamilyCandleProcessor.java:536\",\"message\":\"Future set in builder (future-only case)\",\"data\":{\"familyId\":\"%s\",\"futureScripCode\":\"%s\"},\"timestamp\":%d}\n",
                    familyId, future.getScripCode(), System.currentTimeMillis());
                fw.write(json);
                fw.close();
            } catch (Exception e) {}
            // #endregion
        } else {
            builder.hasFuture(future != null);
            // #region agent log
            try {
                java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
                String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"BUILD-F\",\"location\":\"FamilyCandleProcessor.java:540\",\"message\":\"No future in builder\",\"data\":{\"familyId\":\"%s\",\"futureNotNull\":%s},\"timestamp\":%d}\n",
                    familyId, future != null, System.currentTimeMillis());
                fw.write(json);
                fw.close();
            } catch (Exception e) {}
            // #endregion
        }

        // Set options (now deduplicated!)
        // DEBUG: Log collector state before conversion
        List<InstrumentCandle> rawOptions = collector.getOptions();
        long windowStart = windowedKey.window().start();
        long windowEnd = windowedKey.window().end();
        
        log.info("[OPTIONS-DEBUG] FamilyId: {} | Window: [{}, {}] | Raw options in collector: {} | OptionsByScripCode size: {}", 
            familyId, 
            windowStart, 
            windowEnd,
            rawOptions.size(),
            collector.getOptionsByScripCode().size());
        
        // Log window timing comparison with equity/future
        if (equity != null) {
            log.info("[OPTIONS-DEBUG] FamilyId: {} | Equity window: [{}, {}] | Collector window: [{}, {}] | Match: {}", 
                familyId,
                equity.getWindowStartMillis(), equity.getWindowEndMillis(),
                windowStart, windowEnd,
                equity.getWindowStartMillis() == windowStart && equity.getWindowEndMillis() == windowEnd);
        }
        if (future != null) {
            log.info("[OPTIONS-DEBUG] FamilyId: {} | Future window: [{}, {}] | Collector window: [{}, {}] | Match: {}", 
                familyId,
                future.getWindowStartMillis(), future.getWindowEndMillis(),
                windowStart, windowEnd,
                future.getWindowStartMillis() == windowStart && future.getWindowEndMillis() == windowEnd);
        }
        
        // Log each option scripCode in collector with window timing
        if (!rawOptions.isEmpty()) {
            for (InstrumentCandle opt : rawOptions) {
                boolean windowMatch = opt.getWindowStartMillis() == windowStart && opt.getWindowEndMillis() == windowEnd;
                log.info("[OPTIONS-DEBUG] FamilyId: {} | Option in collector | scripCode: {} | type: {} | option window: [{}, {}] | collector window: [{}, {}] | match: {}", 
                    familyId,
                    opt.getScripCode(),
                    opt.getInstrumentType() != null ? opt.getInstrumentType().name() : "NULL",
                    opt.getWindowStartMillis(), opt.getWindowEndMillis(),
                    windowStart, windowEnd,
                    windowMatch);
            }
        } else {
            log.warn("[OPTIONS-DEBUG] FamilyId: {} | NO OPTIONS in collector | Window: [{}, {}] | Equity: {} | Future: {}", 
                familyId, windowStart, windowEnd,
                equity != null ? equity.getScripCode() : "null",
                future != null ? future.getScripCode() : "null");
        }
        
        // Convert to OptionCandle and log each conversion
        List<OptionCandle> options = new ArrayList<>();
        for (InstrumentCandle rawOption : rawOptions) {
            OptionCandle converted = OptionCandle.fromInstrumentCandle(rawOption);
            if (converted == null) {
                log.warn("[OPTIONS-DEBUG] FamilyId: {} | Option conversion FAILED for scripCode: {} | InstrumentType: {} | Reason: fromInstrumentCandle returned null", 
                    familyId, 
                    rawOption.getScripCode(),
                    rawOption.getInstrumentType() != null ? rawOption.getInstrumentType().name() : "NULL");
            } else {
                options.add(converted);
                log.debug("[OPTIONS-DEBUG] FamilyId: {} | Option conversion SUCCESS for scripCode: {} | Strike: {} | Type: {}", 
                    familyId, 
                    converted.getScripCode(),
                    converted.getStrikePrice(),
                    converted.getOptionType());
            }
        }
        
        log.info("[OPTIONS-DEBUG] FamilyId: {} | Final options count: {} | hasOptions: {}", 
            familyId, options.size(), !options.isEmpty());
        
        builder.options(options);
        builder.hasOptions(!options.isEmpty());
        builder.optionCount(options.size());

        // Calculate cross-instrument metrics
        calculateCrossInstrumentMetrics(builder, equity, future, options);

        // 🛡️ CRITICAL FIX: Propagate quality from underlying instruments
        // Family candle quality = worst quality among all members
        DataQuality familyQuality = determineFamilyQuality(equity, future, collector.getOptions());

        if (log.isDebugEnabled()) {
            log.debug("[FAMILY-QUALITY] {} | equity={} future={} options={} -> FAMILY={}",
                familyId,
                equity != null ? equity.getQuality() : "N/A",
                future != null ? future.getQuality() : "N/A",
                collector.getOptions().size(),
                familyQuality);
        }

        FamilyCandle familyCandle = builder
            .quality(familyQuality)
            .build();
        
        // #region agent log
        try {
            java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
            String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"BUILD-FINAL\",\"location\":\"FamilyCandleProcessor.java:640\",\"message\":\"FamilyCandle built - verifying future field\",\"data\":{\"familyId\":\"%s\",\"futureNotNull\":%s,\"futureScripCode\":\"%s\",\"isCommodity\":%s},\"timestamp\":%d}\n",
                familyId, familyCandle.getFuture() != null, familyCandle.getFuture() != null ? familyCandle.getFuture().getScripCode() : "null", familyCandle.isCommodity(), System.currentTimeMillis());
            fw.write(json);
            fw.close();
        } catch (Exception e) {}
        // #endregion
        
        return familyCandle;
    }

    /**
     * Determine family candle quality based on member instrument quality
     * Worst quality wins: CONFLICT > WARNING > VALID
     */
    private DataQuality determineFamilyQuality(InstrumentCandle equity, InstrumentCandle future,
                                                List<InstrumentCandle> options) {
        DataQuality worstQuality = DataQuality.VALID;

        // Check equity quality
        if (equity != null && equity.getQuality() != null) {
            worstQuality = getWorseQuality(worstQuality, equity.getQuality());
        }

        // Check future quality
        if (future != null && future.getQuality() != null) {
            worstQuality = getWorseQuality(worstQuality, future.getQuality());
        }

        // Check options quality
        if (options != null) {
            for (InstrumentCandle option : options) {
                if (option != null && option.getQuality() != null) {
                    worstQuality = getWorseQuality(worstQuality, option.getQuality());
                    // Short circuit if we hit CONFLICT (worst possible)
                    if (worstQuality == DataQuality.CONFLICT) {
                        break;
                    }
                }
            }
        }

        return worstQuality;
    }

    /**
     * Compare two quality levels and return the worse one
     * CONFLICT (worst) > WARNING > VALID (best)
     */
    private DataQuality getWorseQuality(DataQuality q1, DataQuality q2) {
        if (q1 == DataQuality.CONFLICT || q2 == DataQuality.CONFLICT) {
            return DataQuality.CONFLICT;
        }
        if (q1 == DataQuality.WARNING || q2 == DataQuality.WARNING) {
            return DataQuality.WARNING;
        }
        return DataQuality.VALID;
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
        // FIX: Only calculate for NSE (equity-based families), not MCX commodities
        // MCX commodities have no underlying equity to compare against
        if (equity != null && future != null && equity.getClose() > 0) {
            double premium = (future.getClose() - equity.getClose()) / equity.getClose() * 100;
            builder.spotFuturePremium(premium);
        }
        // Note: For MCX commodities (future-only families), spotFuturePremium remains null
        
        // Futures buildup (works for both NSE and MCX)
        // #region agent log
        try {
            String familyId = builder.build().getFamilyId();
            java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
            String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"OI-C\",\"location\":\"FamilyCandleProcessor.java:656\",\"message\":\"Future OI check in FamilyCandle\",\"data\":{\"familyId\":\"%s\",\"futureNotNull\":%s,\"futureHasOI\":%s,\"futureOiPresent\":%s,\"futureOpenInterest\":%s},\"timestamp\":%d}\n",
                familyId, future != null, future != null ? future.hasOI() : false, future != null ? future.isOiPresent() : false, future != null && future.getOpenInterest() != null ? future.getOpenInterest() : "null", System.currentTimeMillis());
            fw.write(json);
            fw.close();
        } catch (Exception e) {}
        // #endregion
        if (future != null && future.hasOI()) {
            FuturesBuildupDetector.BuildupType buildup = FuturesBuildupDetector.detect(future);
            builder.futuresBuildup(buildup.name());
            builder.futureOiBuildingUp(future.getOiChangePercent() != null && future.getOiChangePercent() > 2);
            builder.futureOIChange(future.getOiChange());
        }

        // Options metrics
        // #region agent log
        try {
            String familyId = builder.build().getFamilyId();
            java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
            String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"PCR-A\",\"location\":\"FamilyCandleProcessor.java:664\",\"message\":\"PCR calculation check\",\"data\":{\"familyId\":\"%s\",\"optionsNotNull\":%s,\"optionsNotEmpty\":%s,\"optionsSize\":%d},\"timestamp\":%d}\n",
                familyId, options != null, options != null && !options.isEmpty(), options != null ? options.size() : 0, System.currentTimeMillis());
            fw.write(json);
            fw.close();
        } catch (Exception e) {}
        // #endregion
        if (options != null && !options.isEmpty()) {
            // PCR
            Double pcr = PCRCalculator.calculate(options);
            // #region agent log
            try {
                String familyId = builder.build().getFamilyId();
                java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
                String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"PCR-B\",\"location\":\"FamilyCandleProcessor.java:667\",\"message\":\"PCR calculated\",\"data\":{\"familyId\":\"%s\",\"pcr\":%s,\"pcrNotNull\":%s},\"timestamp\":%d}\n",
                    familyId, pcr != null ? pcr : "null", pcr != null, System.currentTimeMillis());
                fw.write(json);
                fw.close();
            } catch (Exception e) {}
            // #endregion
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
        // FIX: Calculate OI signal for both equity-based families (NSE) and future-only families (MCX commodities)
        // MCX commodities don't have equity - future is the primary instrument
        if (equity != null || future != null) {
            // Use equity if available, otherwise use future as primary (for MCX commodities)
            InstrumentCandle primary = equity != null ? equity : future;

            FamilyCandle tempFamily = FamilyCandle.builder()
                .familyId(builder.build().getFamilyId())
                .equity(equity)  // null for MCX commodities
                .future(future)
                .options(options)
                .build();
            OISignalDetector.OISignalType oiSignal = OISignalDetector.detect(tempFamily);
            builder.oiSignal(oiSignal.name());

            // 🔍 DEBUG: Log OI signal detection details
            if (log.isDebugEnabled() && oiSignal != OISignalDetector.OISignalType.NEUTRAL) {
                String familyId = tempFamily.getFamilyId();
                double priceChange = primary.getPriceChangePercent();
                Long futureOIChange = future != null && future.hasOI() ? future.getOiChange() : null;
                Double futureOIChangePct = future != null && future.hasOI() ? future.getOiChangePercent() : null;
                long callOIChange = options != null ? PCRCalculator.getTotalCallOIChange(options) : 0;
                long putOIChange = options != null ? PCRCalculator.getTotalPutOIChange(options) : 0;
                Double pcr = options != null ? PCRCalculator.calculate(options) : null;

                log.debug("[OI-SIGNAL] {} | SIGNAL={} | price={}% | futOI={}({}) | callOI={} putOI={} | PCR={}",
                    familyId, oiSignal.name(),
                    String.format("%.2f", priceChange),
                    futureOIChange, futureOIChangePct != null ? String.format("%.2f%%", futureOIChangePct) : "N/A",
                    callOIChange, putOIChange,
                    pcr != null ? String.format("%.2f", pcr) : "N/A");
            }

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
        // REMOVED: private List<InstrumentCandle> options = new ArrayList<>(); (dead code - never populated)
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
            // #region agent log
            try {
                java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
                String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"GROUP-B\",\"location\":\"FamilyCandleCollector.add\",\"message\":\"Adding candle to collector\",\"data\":{\"scripCode\":\"%s\",\"instrumentType\":\"%s\",\"exchangeType\":\"%s\",\"companyName\":\"%s\",\"hasOI\":%s,\"openInterest\":%s,\"equityBefore\":%s,\"futureBefore\":%s,\"optionsCountBefore\":%d},\"timestamp\":%d}\n",
                    candle.getScripCode(), type.name(), candle.getExchangeType() != null ? candle.getExchangeType() : "null", candle.getCompanyName() != null ? candle.getCompanyName() : "null", candle.hasOI(), candle.getOpenInterest() != null ? candle.getOpenInterest() : "null", equity != null ? equity.getScripCode() : "null", future != null ? future.getScripCode() : "null", optionsByScripCode.size(), System.currentTimeMillis());
                fw.write(json);
                fw.close();
            } catch (Exception e) {}
            // #endregion

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
                        // #region agent log
                        try {
                            java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
                            String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"FUTURE-ADD\",\"location\":\"FamilyCandleCollector.add:FUTURE\",\"message\":\"Future set in collector\",\"data\":{\"scripCode\":\"%s\",\"futureScripCode\":\"%s\",\"futureNotNull\":%s,\"futureHasOI\":%s,\"futureOI\":%s},\"timestamp\":%d}\n",
                                candle.getScripCode(), this.future != null ? this.future.getScripCode() : "null", this.future != null, this.future != null && this.future.hasOI(), this.future != null && this.future.getOpenInterest() != null ? this.future.getOpenInterest() : "null", System.currentTimeMillis());
                            fw.write(json);
                            fw.close();
                        } catch (Exception e) {}
                        // #endregion
                    } else {
                        // MERGE instead of replace!
                        mergeInstrumentCandle(this.future, candle);
                        futureMergeCount++;
                        // #region agent log
                        try {
                            java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
                            String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"FUTURE-MERGE\",\"location\":\"FamilyCandleCollector.add:FUTURE\",\"message\":\"Future merged in collector\",\"data\":{\"scripCode\":\"%s\",\"futureScripCode\":\"%s\",\"futureNotNull\":%s,\"mergeCount\":%d},\"timestamp\":%d}\n",
                                candle.getScripCode(), this.future != null ? this.future.getScripCode() : "null", this.future != null, futureMergeCount, System.currentTimeMillis());
                            fw.write(json);
                            fw.close();
                        } catch (Exception e) {}
                        // #endregion
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
                            // Enhanced logging for options
                            log.info("[OPTIONS-DEBUG] Option ADDED to collector | scripCode: {} | type: {} | companyName: {} | windowStart: {} | windowEnd: {} | optionsCountAfter: {}", 
                                scripCode, 
                                type.name(),
                                candle.getCompanyName() != null ? candle.getCompanyName() : "null",
                                candle.getWindowStartMillis(),
                                candle.getWindowEndMillis(),
                                optionsByScripCode.size());
                            // #region agent log
                            try {
                                java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
                                String companyName = candle.getCompanyName() != null ? candle.getCompanyName() : "null";
                                String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"OPTION-ADD\",\"location\":\"FamilyCandleCollector.add:OPTION\",\"message\":\"Option added to collector\",\"data\":{\"scripCode\":\"%s\",\"instrumentType\":\"%s\",\"companyName\":\"%s\",\"windowStart\":%d,\"windowEnd\":%d,\"optionsCountAfter\":%d},\"timestamp\":%d}\n",
                                    scripCode, type.name(), companyName, candle.getWindowStartMillis(), candle.getWindowEndMillis(), optionsByScripCode.size(), System.currentTimeMillis());
                                fw.write(json);
                                fw.close();
                            } catch (Exception e) {}
                            // #endregion
                        } else {
                            // Merge OHLCV for same option
                            log.debug("[OPTIONS-DEBUG] Option MERGED in collector | scripCode: {} | existing window: [{}, {}] | incoming window: [{}, {}]", 
                                scripCode,
                                existing.getWindowStartMillis(), existing.getWindowEndMillis(),
                                candle.getWindowStartMillis(), candle.getWindowEndMillis());
                            mergeInstrumentCandle(existing, candle);
                        }
                    } else {
                        log.warn("[OPTIONS-DEBUG] Option REJECTED - null scripCode | type: {} | companyName: {}", 
                            type.name(), 
                            candle.getCompanyName() != null ? candle.getCompanyName() : "null");
                    }
                    break;
            }
            // #region agent log
            try {
                java.io.FileWriter fw = new java.io.FileWriter("logs/debug.log", true);
                String json = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"GROUP-C\",\"location\":\"FamilyCandleCollector.add:AFTER\",\"message\":\"Collector state after add\",\"data\":{\"scripCode\":\"%s\",\"instrumentType\":\"%s\",\"equityAfter\":%s,\"futureAfter\":%s,\"futureScripCode\":\"%s\",\"optionsCountAfter\":%d},\"timestamp\":%d}\n",
                    candle.getScripCode(), type.name(), equity != null ? equity.getScripCode() : "null", future != null ? future.getScripCode() : "null", future != null ? future.getScripCode() : "null", optionsByScripCode.size(), System.currentTimeMillis());
                fw.write(json);
                fw.close();
            } catch (Exception e) {}
            // #endregion
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
         * FIX: Removed dead code - options list was never populated, only optionsByScripCode map is used
         */
        public List<InstrumentCandle> getOptions() {
            // Return deduplicated options from map
            return new ArrayList<>(optionsByScripCode.values());
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
