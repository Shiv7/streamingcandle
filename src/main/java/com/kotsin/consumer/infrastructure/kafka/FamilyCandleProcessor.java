package com.kotsin.consumer.infrastructure.kafka;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.domain.calculator.FuturesBuildupDetector;
import com.kotsin.consumer.domain.calculator.OISignalDetector;
import com.kotsin.consumer.domain.calculator.PCRCalculator;
import com.kotsin.consumer.domain.calculator.ReversalSignalCalculator;
import com.kotsin.consumer.domain.model.*;
import com.kotsin.consumer.service.ReversalSignalStateStore;
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
    
    // PHASE 2: MTF Distribution Calculator
    @Autowired
    private com.kotsin.consumer.service.MTFDistributionCalculator mtfDistributionCalculator;

    // PHASE 3: Reversal Signal State Store
    @Autowired
    private ReversalSignalStateStore reversalSignalStateStore;

    // FIX: Increased default grace period from 5 to 30 seconds
    // BEFORE: 5 seconds - options arriving late would miss family window
    // AFTER: 30 seconds - allows more time for all family members to arrive
    // This is especially important during market open when data bursts arrive
    @Value("${family.candle.window.grace.seconds:30}")
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
        // FIX: Added filter after selectKey to drop records where getFamilyId() returns null
        // This happens when option mapping fails completely (not in ScripGroup, Scrip, or symbolMap)
        KStream<String, InstrumentCandle> keyedByFamily = instruments
            .filter((key, candle) -> candle != null && candle.getScripCode() != null)
            .selectKey((key, candle) -> getFamilyId(candle))
            .filter((familyId, candle) -> familyId != null);  // FIX: Drop null familyIds (unmapped options)

        // Window and aggregate
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            Duration.ofMinutes(windowSizeMinutes),
            Duration.ofSeconds(graceSeconds)
        );

        // 🛡️ CRITICAL FIX: Use wall-clock based emission instead of suppress(untilWindowCloses)
        //
        // PROBLEM: suppress(untilWindowCloses) uses "stream-time" which only advances when
        // new records arrive. During low-activity periods or market close, this causes
        // multi-minute delays (e.g., 2+ minutes delay observed).
        //
        // SOLUTION: WallClockWindowEmitter uses WALL_CLOCK_TIME punctuation to emit windows
        // when real wall-clock time exceeds window_end + grace_period, regardless of stream-time.
        // This ensures consistent ~2-3 second latency after window close.
        //
        // BEFORE: suppress(untilWindowCloses) -> delayed by stream-time gaps
        // AFTER:  WallClockWindowEmitter -> emits on wall clock, max delay = grace period + 1s

        KTable<Windowed<String>, FamilyCandleCollector> collected = keyedByFamily
            .groupByKey(Grouped.with(Serdes.String(), InstrumentCandle.serde()))
            .windowedBy(windows)
            .aggregate(
                FamilyCandleCollector::new,
                (familyId, candle, collector) -> collector.add(candle),
                Materialized.<String, FamilyCandleCollector, org.apache.kafka.streams.state.WindowStore<org.apache.kafka.common.utils.Bytes, byte[]>>as(stateStoreName)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(FamilyCandleCollector.serde())
            );
            // REMOVED: .suppress(Suppressed.untilWindowCloses(...)) - replaced with WallClockWindowEmitter

        // Convert to FamilyCandle and emit with metrics tracking
        // FIX: Allow futures-only families (for commodities like MCX Gold, Crude, etc.)
        // Commodities don't have underlying equity - future IS the primary instrument
        //
        // Wall-clock emitter replaces suppress() - emits once per window based on real time
        long graceMsForEmitter = graceSeconds * 1000L;

        collected.toStream()
            .process(() -> new WallClockWindowEmitter<>(graceMsForEmitter))  // Wall-clock based emission
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
            .peek((key, familyCandle) -> {
                if (log.isDebugEnabled() && familyCandle != null) {
                    String familyId = familyCandle.getFamilyId();
                    // FIX: Use primaryInstrument for OHLC display (handles commodities correctly)
                    InstrumentCandle primary = familyCandle.getPrimaryInstrumentOrFallback();
                    String scripCode = primary != null ? primary.getScripCode() : "N/A";
                    log.debug("[FAMILY] {} | {} | equity={} future={} options={} commodity={} | OHLC={}/{}/{}/{} vol={} | emitted to {}",
                        familyId, scripCode,
                        familyCandle.getEquity() != null ? "YES" : "NO",
                        familyCandle.getFuture() != null ? "YES" : "NO",
                        familyCandle.getOptions() != null ? familyCandle.getOptions().size() : 0,
                        familyCandle.isCommodity() ? "YES" : "NO",
                        primary != null ? primary.getOpen() : 0.0,
                        primary != null ? primary.getHigh() : 0.0,
                        primary != null ? primary.getLow() : 0.0,
                        primary != null ? primary.getClose() : 0.0,
                        primary != null ? primary.getVolume() : 0L, outputTopic);
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
                // Record metric for successful symbol-based recovery
                if (dataQualityMetrics != null) {
                    dataQualityMetrics.recordViolation("SYMBOL_BASED_RECOVERY_SUCCESS");
                }
                return symbolBasedId;
            } else {
                log.warn("[FAMILY-ID-STEP3-FAILED] Symbol lookup returned null | scripCode: {} | type: {} | symbol: '{}' | companyName: '{}' | findEquityBySymbol found no equity",
                    scripCode, type, symbolRoot, companyName);
            }
        } else {
            log.warn("[FAMILY-ID-STEP3-FAILED] Symbol extraction returned null | scripCode: {} | type: {} | companyName: '{}' | extractSymbolRoot failed",
                scripCode, type, companyName != null ? companyName : "null");
        }
        
        // Step 4: For derivatives without mapping - log warning and record metrics
        log.error("[FAMILY-ID-STEP4-FAILED] All mapping attempts failed | scripCode: {} | type: {} | " +
                 "companyName: '{}' | symbol: '{}' | " +
                 "Step1 (getEquityScripCode): '{}' | Step3 (findEquityBySymbol): '{}' | " +
                 "Using scripCode as familyId (FALLBACK). This may indicate missing data in ScripGroup/Scrip collections.",
                 scripCode, type, companyName != null ? companyName : "null", symbolRoot != null ? symbolRoot : "null",
                 equityScripCode != null ? equityScripCode : "null", "N/A");

        // Record metrics for failed mappings
        if (dataQualityMetrics != null) {
            if (type == InstrumentType.OPTION_CE || type == InstrumentType.OPTION_PE) {
                dataQualityMetrics.recordViolation("OPTION_MAPPING_FAILED");
            } else if (type == InstrumentType.FUTURE) {
                dataQualityMetrics.recordViolation("FUTURE_MAPPING_FAILED");
            } else {
                dataQualityMetrics.recordViolation("DERIVATIVE_MAPPING_FAILED");
            }
        }

        // FIX: Return null for unmapped OPTIONS - they will be filtered out
        // BEFORE: Returned scripCode which created orphan families
        // AFTER: Return null for options (filtered by keyedByFamily filter)
        //        Return scripCode for futures (can still emit as commodity families)
        if (type == InstrumentType.OPTION_CE || type == InstrumentType.OPTION_PE) {
            log.warn("[FAMILY-ID-FINAL] Option mapping FAILED | scripCode: {} | type: {} | window: [{}, {}] | " +
                     "companyName: '{}' | symbol: '{}' | Returning NULL (option will be dropped). " +
                     "Fix the symbol mapping in MongoDB to include this option.",
                     scripCode, type, candle.getWindowStartMillis(), candle.getWindowEndMillis(),
                     companyName != null ? companyName : "null", symbolRoot != null ? symbolRoot : "null");
            return null;  // FIX: Return null so option is filtered out, not orphaned
        } else if (type == InstrumentType.FUTURE) {
            log.warn("[FAMILY-ID-FINAL] Future mapping FAILED | scripCode: {} | type: {} | " +
                     "companyName: '{}' | symbol: '{}' | Using scripCode as familyId (may be MCX commodity).",
                     scripCode, type, companyName != null ? companyName : "null", symbolRoot != null ? symbolRoot : "null");
            return scripCode;  // Futures can still form valid families (MCX commodities)
        } else {
            log.warn("[FAMILY-ID-FINAL] Derivative mapping FAILED | scripCode: {} | type: {} | " +
                     "companyName: '{}' | symbol: '{}' | Using scripCode as familyId",
                     scripCode, type, companyName != null ? companyName : "null", symbolRoot != null ? symbolRoot : "null");
            return scripCode;  // Other derivatives fallback to scripCode
        }
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
            builder.primaryInstrument(equity);  // FIX: Set primaryInstrument for consistent access
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
            // Set primaryInstrument for proper analysis, keep equity=null (no equity for commodities)
            builder.primaryInstrument(future);  // PRIMARY FIX: Use primaryInstrument, not equity
            // NOTE: We intentionally leave equity=null for commodities
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
            log.debug("Commodity family {} using future as primaryInstrument: {}", familyId, future.getCompanyName());
        } else if (future != null) {
            // NSE stock with only future (no equity) - this indicates GROUPING FAILURE
            // FIX: Try to look up equity using symbol from future before giving up
            String symbol = extractSymbolRoot(future.getCompanyName());

            if (symbol != null && !symbol.isEmpty()) {
                // FIX: Attempt to find equity scripCode by symbol
                try {
                    String equityScripCode = familyDataProvider.findEquityBySymbol(symbol);
                    if (equityScripCode != null && !equityScripCode.equals(familyId)) {
                        // Found equity scripCode - try to get family data to confirm mapping
                        log.debug("[FAMILY-RECOVERY] Family {} | Found equity {} for symbol {} from future {}",
                            familyId, equityScripCode, symbol, future.getScripCode());
                        // Note: We can't recover the actual equity candle here (it's in a different partition)
                        // but at least we have the correct symbol for downstream processing
                    }
                } catch (Exception e) {
                    log.debug("[FAMILY-RECOVERY-FAILED] Family {} | Symbol lookup failed for {}: {}",
                        familyId, symbol, e.getMessage());
                }
            }

            if (symbol == null || symbol.isEmpty()) {
                symbol = familyId; // Fallback to scripCode
                log.warn("Family {}: companyName is null, using scripCode as symbol fallback", familyId);
            }

            // CRITICAL FIX: Do NOT assign future to equity field - equity must be null when missing
            // Set primaryInstrument instead for downstream analysis
            builder.primaryInstrument(future);  // FIX: Use primaryInstrument, NOT equity
            // NOTE: equity remains null - this is correct behavior for type safety
            builder.symbol(symbol);

            // Only log warning if this is truly an incomplete family (not a commodity or index future)
            if (!symbol.contains("NIFTY") && !symbol.contains("BANK") && !symbol.contains("FIN")) {
                log.warn("[FAMILY-GROUPING-FAILURE] Family {} has future (scripCode: {}) but NO equity - " +
                         "equity field will be null, using primaryInstrument for analysis. Symbol: {}",
                         familyId, future.getScripCode(), symbol);
            } else {
                // Index futures (NIFTY, BANKNIFTY, FINNIFTY) don't have equity - this is normal
                log.debug("[FAMILY-INDEX] Family {} | Index future {} has no equity (expected)",
                         familyId, symbol);
            }
        }

        // Set future candle (already retrieved above)
        if (future != null && equity != null) {
            // FIX: Set hasFuture=true when BOTH equity AND future exist
            // BEFORE: hasFuture was never set in this branch (BUG!)
            validateOHLC(future, "FUTURE", familyId);
            builder.future(future);
            builder.hasFuture(true);  // FIX: Was missing!
        } else if (future != null && equity == null && isCommodity) {
            // Commodity case: future is set as equity for compatibility, but ALSO set in future field
            // so that MTISProcessor can access OI/PCR via getFuture()
            // Both equity and future slots point to the same InstrumentCandle object
            builder.future(future);  // CRITICAL FIX: Set future field so OI/PCR can be accessed
            builder.hasFuture(true);  // Mark that we have a future
        } else if (future != null && equity == null) {
            // NSE case: future is fallback, also set it in future field
            builder.future(future);
            builder.hasFuture(true);
        } else {
            builder.hasFuture(future != null);
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
        
        // FIX: Get spot price for moneyness calculation (ITM/ATM)
        Double spotPrice = null;
        if (primary != null) {
            spotPrice = primary.getClose();
        }

        // Convert to OptionCandle and log each conversion
        // FIX: Pass spot price to calculate isITM, isATM, intrinsicValue, timeValue
        List<OptionCandle> options = new ArrayList<>();
        for (InstrumentCandle rawOption : rawOptions) {
            OptionCandle converted = OptionCandle.fromInstrumentCandle(rawOption, spotPrice);
            if (converted == null) {
                log.warn("[OPTIONS-DEBUG] FamilyId: {} | Option conversion FAILED for scripCode: {} | InstrumentType: {} | Reason: fromInstrumentCandle returned null",
                    familyId,
                    rawOption.getScripCode(),
                    rawOption.getInstrumentType() != null ? rawOption.getInstrumentType().name() : "NULL");
            } else {
                options.add(converted);
                log.debug("[OPTIONS-DEBUG] FamilyId: {} | Option conversion SUCCESS for scripCode: {} | Strike: {} | Type: {} | ITM: {} | ATM: {}",
                    familyId,
                    converted.getScripCode(),
                    converted.getStrikePrice(),
                    converted.getOptionType(),
                    converted.isITM(),
                    converted.isATM());
            }
        }
        
        // 🔴 CRITICAL: Use totalOptionsReceived for analytics (original count before limiting)
        int totalReceived = collector.getTotalOptionsReceived();
        int keptCount = options.size();

        if (totalReceived > keptCount) {
            log.info("[OPTIONS-LIMITED] FamilyId: {} | Received: {} | Kept: {} (top by OI) | Dropped: {}",
                familyId, totalReceived, keptCount, totalReceived - keptCount);
        } else {
            log.info("[OPTIONS-DEBUG] FamilyId: {} | Final options count: {} | hasOptions: {}",
                familyId, options.size(), !options.isEmpty());
        }

        builder.options(options);
        builder.hasOptions(!options.isEmpty());
        builder.optionCount(totalReceived);  // Track ORIGINAL count for analytics

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
        
        // PHASE 2: MTF Distribution Analysis
        // Calculate intra-window sub-candle patterns for directional consistency
        // FIX: For commodities, use futureSubCandles when equitySubCandles is empty
        List<com.kotsin.consumer.model.UnifiedCandle> subCandles = collector.getEquitySubCandles();
        String subCandleSource = "equity";

        // For commodities (no equity), use future sub-candles for MTF distribution
        if ((subCandles == null || subCandles.isEmpty()) && isCommodity) {
            subCandles = collector.getFutureSubCandles();
            subCandleSource = "future";
        }

        if (subCandles != null && !subCandles.isEmpty() && subCandles.size() > 1) {
            try {
                com.kotsin.consumer.model.MTFDistribution dist = mtfDistributionCalculator.calculate(subCandles);
                builder.mtfDistribution(dist);

                log.info("MTF Distribution for {}: {} sub-candles (from {}), consistency={}, interpretation={}",
                         familyId, dist.getTotalSubCandles(), subCandleSource,
                         String.format("%.2f", dist.getDirectionalConsistency()),
                         dist.getInterpretation());
            } catch (Exception e) {
                log.warn("⚠️ MTF Distribution calculation failed for {}: {}", familyId, e.getMessage());
            }
        } else {
            log.debug("⚠️ MTF Distribution skipped for {} - insufficient sub-candles (equityCount={}, futureCount={}, isCommodity={})",
                      familyId,
                      collector.getEquitySubCandles() != null ? collector.getEquitySubCandles().size() : 0,
                      collector.getFutureSubCandles() != null ? collector.getFutureSubCandles().size() : 0,
                      isCommodity);
        }

        FamilyCandle familyCandle = builder
            .quality(familyQuality)
            .build();

        // ========== PHASE 3: REVERSAL SIGNAL DETECTION ==========
        // Calculate OFI velocity, exhaustion, delta divergence, OI interpretation, and reversal score
        // This requires previous candle state from ReversalSignalStateStore
        if (reversalSignalStateStore != null) {
            try {
                ReversalSignalStateStore.FamilyState prevState = reversalSignalStateStore.getOrCreateState(familyId);

                // Get ATM option premiums for options flow analysis
                Double atmCallPremium = null;
                Double atmPutPremium = null;
                if (options != null) {
                    for (OptionCandle opt : options) {
                        if (opt.isATM()) {
                            if ("CE".equals(opt.getOptionType())) {
                                atmCallPremium = opt.getClose();
                            } else if ("PE".equals(opt.getOptionType())) {
                                atmPutPremium = opt.getClose();
                            }
                        }
                    }
                }

                // Calculate all reversal signals
                ReversalSignalCalculator.calculate(
                    familyCandle,
                    prevState.previousOfi,
                    prevState.previousOfiVelocity,
                    prevState.previousVolume,
                    prevState.previousCallPremium,
                    prevState.previousPutPremium,
                    prevState.sessionLow,
                    prevState.sessionHigh
                );

                // Update state for next candle
                InstrumentCandle primaryForState = familyCandle.getPrimaryInstrumentOrFallback();
                if (primaryForState != null) {
                    reversalSignalStateStore.updateState(
                        familyId,
                        primaryForState.getOfi(),
                        familyCandle.getOfiVelocity(),
                        primaryForState.getVolume(),
                        atmCallPremium,
                        atmPutPremium,
                        primaryForState.getHigh(),
                        primaryForState.getLow(),
                        familyCandle.getWindowStartMillis()
                    );
                }

                // Log high-confidence reversals
                if (familyCandle.isHighConfidenceReversal()) {
                    log.info("[REVERSAL-HIGH-CONFIDENCE] {} | Score: {} | Signals: {} | OFI: {} vel={} | OI: {} | Exhaustion: {}",
                        familyId,
                        String.format("%.1f", familyCandle.getReversalScore()),
                        familyCandle.getReversalSignals(),
                        primaryForState != null && primaryForState.getOfi() != null ?
                            String.format("%.0f", primaryForState.getOfi()) : "N/A",
                        familyCandle.getOfiVelocity() != null ?
                            String.format("%.0f", familyCandle.getOfiVelocity()) : "N/A",
                        familyCandle.getOiInterpretation(),
                        familyCandle.getExhaustionType());
                }
            } catch (Exception e) {
                log.warn("[REVERSAL-CALC-ERROR] {} | Failed to calculate reversal signals: {}",
                    familyId, e.getMessage());
            }
        }

        // 🔴 NOTE: Options already limited to 200 in FamilyCandleCollector.add()
        // No need for additional compaction - 200 options × ~1KB = ~200KB (safe for Kafka)
        // The compactForEmission() method is kept as backup but not called

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
     * Delegates to centralized OHLCValidator utility
     * @return true if valid, false if any violations found
     */
    private boolean validateOHLC(InstrumentCandle candle, String type, String familyId) {
        return com.kotsin.consumer.domain.validator.OHLCValidator.validate(
            candle, type, familyId, dataQualityMetrics
        );
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
        if (future != null && future.hasOI()) {
            FuturesBuildupDetector.BuildupType buildup = FuturesBuildupDetector.detect(future);
            builder.futuresBuildup(buildup.name());
            builder.futureOiBuildingUp(future.getOiChangePercent() != null && future.getOiChangePercent() > 2);
            builder.futureOIChange(future.getOiChange());
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
     *
     * 🔴 CRITICAL FIX: Limit options to prevent 94MB changelog messages
     * The state store changelog was storing ALL options (thousands for NIFTY/BANKNIFTY),
     * causing RecordTooLargeException (94MB > 50MB limit) and OOM during restore.
     *
     * Solution: Limit options during aggregation, not just at emission.
     * Keep top 200 by OI to cover important strikes while preventing huge messages.
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    public static class FamilyCandleCollector {
        // 🔴 CRITICAL: Limit options to prevent 94MB changelog messages
        // 200 options covers ~100 strikes (CE+PE) which is sufficient for analysis
        // Original full count is tracked in totalOptionsReceived for metrics
        private static final int MAX_OPTIONS_IN_COLLECTOR = 200;

        private InstrumentCandle equity;
        private InstrumentCandle future;
        // REMOVED: private List<InstrumentCandle> options = new ArrayList<>(); (dead code - never populated)
        private Map<String, InstrumentCandle> optionsByScripCode = new HashMap<>();

        // Track merge counts for debugging
        private int equityMergeCount = 0;
        private int futureMergeCount = 0;

        // Track total options received (before limiting) for metrics
        private int totalOptionsReceived = 0;

        // PHASE 2: MTF Distribution - track sub-candles during aggregation
        private List<com.kotsin.consumer.model.UnifiedCandle> equitySubCandles = new ArrayList<>();
        // FIX: Also track future sub-candles for commodities (MCX)
        private List<com.kotsin.consumer.model.UnifiedCandle> futureSubCandles = new ArrayList<>();

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
                    // MTF Distribution Fix: Extract embedded sub-candle snapshots from InstrumentCandle
                    // These are created every 10 seconds during tick aggregation
                    extractAndAddSubCandles(candle, equitySubCandles, "equity");
                    break;
                case FUTURE:
                    if (this.future == null) {
                        this.future = candle;
                    } else {
                        // MERGE instead of replace!
                        mergeInstrumentCandle(this.future, candle);
                        futureMergeCount++;
                    }
                    // MTF Distribution Fix: Extract embedded sub-candle snapshots from InstrumentCandle
                    // This enables MTF distribution for commodities (MCX) where future is primary
                    extractAndAddSubCandles(candle, futureSubCandles, "future");
                    break;
                case OPTION_CE:
                case OPTION_PE:
                    // 🔴 CRITICAL FIX: Limit options to prevent 94MB changelog messages
                    // Track total received for metrics
                    totalOptionsReceived++;

                    // Deduplicate by scripCode and merge OHLCV
                    String scripCode = candle.getScripCode();
                    if (scripCode != null) {
                        InstrumentCandle existing = optionsByScripCode.get(scripCode);
                        if (existing == null) {
                            // Check if we've hit the limit
                            if (optionsByScripCode.size() >= MAX_OPTIONS_IN_COLLECTOR) {
                                // FIX Bug #24: Use HYBRID ranking (ATM proximity + OI) not just OI
                                // ATM options are critical for analysis - don't drop them for far OTM high-OI
                                double spotPrice = getEstimatedSpotPrice();
                                double newScore = calculateHybridOptionScore(candle, spotPrice);

                                String worstKey = null;
                                double worstScore = Double.MAX_VALUE;

                                for (Map.Entry<String, InstrumentCandle> entry : optionsByScripCode.entrySet()) {
                                    double score = calculateHybridOptionScore(entry.getValue(), spotPrice);
                                    if (score < worstScore) {
                                        worstScore = score;
                                        worstKey = entry.getKey();
                                    }
                                }

                                if (newScore > worstScore && worstKey != null) {
                                    // Replace worst-scored option with better one
                                    optionsByScripCode.remove(worstKey);
                                    optionsByScripCode.put(scripCode, candle);
                                    log.debug("[OPTIONS-LIMIT Bug#24] Replaced {} (score={}) with {} (score={})",
                                        worstKey, String.format("%.2f", worstScore), scripCode, String.format("%.2f", newScore));
                                } else {
                                    // New option scores worse than all existing - skip it
                                    log.debug("[OPTIONS-LIMIT Bug#24] Skipped {} (score={}) - limit {} reached",
                                        scripCode, String.format("%.2f", newScore), MAX_OPTIONS_IN_COLLECTOR);
                                }
                            } else {
                                // Under limit - just add it
                                optionsByScripCode.put(scripCode, candle);
                                log.debug("[OPTIONS-DEBUG] Option ADDED to collector | scripCode: {} | type: {} | OI: {} | count: {}/{}",
                                    scripCode, type.name(), candle.getOpenInterest(),
                                    optionsByScripCode.size(), MAX_OPTIONS_IN_COLLECTOR);
                            }
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
         * 🔴 NOTE: This returns limited options (max 200) - totalOptionsReceived tracks original count
         */
        public List<InstrumentCandle> getOptions() {
            // Return deduplicated options from map
            return new ArrayList<>(optionsByScripCode.values());
        }

        /**
         * Get total options received before limiting
         * Used for metrics to track how many options were dropped
         */
        public int getTotalOptionsReceived() {
            return totalOptionsReceived;
        }

        /**
         * FIX Bug #24: Get estimated spot price from equity or future
         * Used for ATM proximity calculation in hybrid option ranking
         */
        private double getEstimatedSpotPrice() {
            if (equity != null && equity.getClose() > 0) {
                return equity.getClose();
            }
            if (future != null && future.getClose() > 0) {
                return future.getClose();
            }
            // Fallback: estimate from existing options' average strike (weighted by OI)
            if (!optionsByScripCode.isEmpty()) {
                double weightedSum = 0;
                long totalOI = 0;
                for (InstrumentCandle opt : optionsByScripCode.values()) {
                    double strike = opt.getStrikePrice();
                    long oi = opt.getOpenInterest();
                    if (strike > 0 && oi > 0) {
                        weightedSum += strike * oi;
                        totalOI += oi;
                    }
                }
                if (totalOI > 0) {
                    return weightedSum / totalOI;
                }
            }
            return 0;
        }

        /**
         * FIX Bug #24: Calculate hybrid option score prioritizing ATM proximity then OI
         *
         * Score formula: ATM_PROXIMITY_SCORE (0-100) + OI_BONUS (0-20)
         * - ATM options score 100, far OTM score lower
         * - OI provides up to 20 bonus points
         *
         * This ensures ATM options with moderate OI beat far OTM with high OI
         */
        private double calculateHybridOptionScore(InstrumentCandle option, double spotPrice) {
            double strike = option.getStrikePrice();
            long oi = option.getOpenInterest();

            // If no spot price, fall back to pure OI ranking
            if (spotPrice <= 0 || strike <= 0) {
                return oi;
            }

            // ATM proximity score: 100 at ATM, decays with distance
            // Formula: 100 * exp(-distance_pct^2 / decay_factor)
            // decay_factor = 100 means ~37% score at 10% OTM
            double distancePct = Math.abs(strike - spotPrice) / spotPrice * 100;
            double atmScore = 100 * Math.exp(-distancePct * distancePct / 100);

            // OI bonus: log-scaled to prevent extreme OI from dominating
            // Max bonus ~20 points for very high OI
            double oiBonus = oi > 0 ? Math.min(20, Math.log10(oi) * 3) : 0;

            return atmScore + oiBonus;
        }

        /**
         * MTF Distribution Fix: Extract embedded sub-candle snapshots from InstrumentCandle
         * and convert them to UnifiedCandle objects for MTF pattern analysis.
         *
         * Before this fix: Only 1 sub-candle per window (the final aggregated InstrumentCandle)
         * After this fix: Multiple sub-candles (one every ~10 seconds during tick aggregation)
         *
         * @param candle InstrumentCandle with embedded sub-candle snapshots
         * @param targetList List to add the converted UnifiedCandle objects
         * @param type Type string for logging ("equity" or "future")
         */
        private void extractAndAddSubCandles(InstrumentCandle candle,
                                              List<com.kotsin.consumer.model.UnifiedCandle> targetList,
                                              String type) {
            java.util.List<InstrumentCandle.SubCandleSnapshot> snapshots = candle.getSubCandleSnapshots();

            if (snapshots != null && !snapshots.isEmpty()) {
                // Convert each SubCandleSnapshot to UnifiedCandle
                for (InstrumentCandle.SubCandleSnapshot snapshot : snapshots) {
                    com.kotsin.consumer.model.UnifiedCandle uc = com.kotsin.consumer.model.UnifiedCandle.builder()
                        .scripCode(candle.getScripCode())
                        .companyName(candle.getCompanyName())
                        .exchange(candle.getExchange())
                        .exchangeType(candle.getExchangeType())
                        .windowStartMillis(snapshot.getEventTime() - 10_000L)  // Approximate start (10s before snapshot)
                        .windowEndMillis(snapshot.getEventTime())
                        .open(snapshot.getOpen())
                        .high(snapshot.getHigh())
                        .low(snapshot.getLow())
                        .close(snapshot.getClose())
                        .volume(snapshot.getVolume())
                        .buyVolume(snapshot.getBuyVolume())
                        .sellVolume(snapshot.getSellVolume())
                        .vwap(snapshot.getVwap())
                        .tickCount(snapshot.getTickCount())
                        .oiClose(snapshot.getOiClose())
                        .build();
                    targetList.add(uc);
                }

                log.debug("[MTF-SUB-CANDLE] {} {} | Extracted {} sub-candle snapshots from InstrumentCandle",
                    candle.getScripCode(), type, snapshots.size());
            } else {
                // Fallback: No embedded snapshots, use the candle itself as single sub-candle
                // This maintains backward compatibility with older data that doesn't have snapshots
                targetList.add(com.kotsin.consumer.model.UnifiedCandle.from(candle));
                log.debug("[MTF-SUB-CANDLE] {} {} | No embedded snapshots, using candle as single sub-candle",
                    candle.getScripCode(), type);
            }
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
        
        // PHASE 2: Getter for MTF sub-candles
        public List<com.kotsin.consumer.model.UnifiedCandle> getEquitySubCandles() {
            return equitySubCandles;
        }

        // FIX: Getter for future sub-candles (for commodities MTF distribution)
        public List<com.kotsin.consumer.model.UnifiedCandle> getFutureSubCandles() {
            return futureSubCandles;
        }

        public static org.apache.kafka.common.serialization.Serde<FamilyCandleCollector> serde() {
            return new org.springframework.kafka.support.serializer.JsonSerde<>(FamilyCandleCollector.class);
        }
    }
}
