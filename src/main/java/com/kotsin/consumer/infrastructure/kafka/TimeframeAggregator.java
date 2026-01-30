package com.kotsin.consumer.infrastructure.kafka;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.WindowStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.kotsin.consumer.domain.calculator.FuturesBuildupDetector;
import com.kotsin.consumer.domain.calculator.OISignalDetector;
import com.kotsin.consumer.domain.calculator.PCRCalculator;

import com.kotsin.consumer.timeExtractor.MarketAlignedTimestampExtractor;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * TimeframeAggregator - Aggregates 1-minute candles into higher timeframes.
 * 
 * Input: family-candle-1m
 * Output: family-candle-{2m,3m,5m,15m,30m,1h,2h,4h,1d,1wk,1mo}
 * 
 * Algorithm:
 * 1. Consume 1-minute family candles
 * 2. Group by familyId and window by target timeframe
 * 3. Aggregate OHLCV and cross-instrument metrics
 * 4. Emit on window close
 * 
 * Key Design:
 * - Uses NSE market time alignment (9:15 AM IST)
 * - Handles daily/weekly/monthly with proper boundaries
 * - Preserves all cross-instrument metrics through aggregation
 */
@Component
@Slf4j
public class TimeframeAggregator {

    @Autowired
    private KafkaConfig kafkaConfig;
    
    @Autowired
    private com.kotsin.consumer.monitoring.DataQualityMetrics dataQualityMetrics;

    @Value("${timeframe.aggregator.enabled:true}")
    private boolean enabled;

    @Value("${family.output.topics.1m:family-candle-1m}")
    private String inputTopic;

    // FIX: Configurable grace period (2 seconds default for fast emission)
    @Value("${timeframe.aggregator.grace.seconds:2}")
    private int graceSeconds;

    private final Map<String, KafkaStreams> streamsByTimeframe = new HashMap<>();

    // Timeframe configurations: name -> minutes
    private static final Map<String, Integer> TIMEFRAMES = new LinkedHashMap<>();
    static {
        TIMEFRAMES.put("2m", 2);
        TIMEFRAMES.put("3m", 3);
        TIMEFRAMES.put("5m", 5);
        TIMEFRAMES.put("15m", 15);
        TIMEFRAMES.put("30m", 30);
        TIMEFRAMES.put("1h", 60);
        TIMEFRAMES.put("2h", 120);
        TIMEFRAMES.put("4h", 240);
        // Daily/Weekly/Monthly handled separately
    }

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("TimeframeAggregator is DISABLED");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                log.info("🚀 Starting TimeframeAggregator for {} timeframes...", TIMEFRAMES.size());
                
                // Start aggregators for each timeframe
                for (Map.Entry<String, Integer> entry : TIMEFRAMES.entrySet()) {
                    startTimeframeStream(entry.getKey(), entry.getValue());
                }

                // Start daily aggregator
                startDailyStream();

                log.info("✅ TimeframeAggregator started for all timeframes");
                
            } catch (Exception e) {
                log.error("❌ Failed to start TimeframeAggregator", e);
            }
        });
    }

    /**
     * Start stream for a specific minute-based timeframe
     */
    private void startTimeframeStream(String timeframeName, int minutes) {
        try {
            String outputTopic = "family-candle-" + timeframeName;
            String appId = "timeframe-aggregator-" + timeframeName;

            Properties props = kafkaConfig.getStreamProperties(appId);
            StreamsBuilder builder = new StreamsBuilder();

            buildTimeframeTopology(builder, timeframeName, minutes, outputTopic);

            KafkaStreams streams = new KafkaStreams(builder.build(), props);
            setupExceptionHandling(streams, timeframeName);
            
            streams.start();
            streamsByTimeframe.put(timeframeName, streams);
            
            log.info("Started {} aggregator: {} -> {}", timeframeName, inputTopic, outputTopic);
            
        } catch (Exception e) {
            log.error("Failed to start {} aggregator", timeframeName, e);
        }
    }

    /**
     * 🛡️ CRITICAL FIX: Event-Time Processing for Multi-Timeframe Aggregation
     *
     * Build topology for minute-based timeframe aggregation
     *
     * 🔴 MARKET ALIGNMENT FIX (replaces old NSE-only approach):
     * Uses MarketAlignedTimestampExtractor which is EXCHANGE-AWARE:
     * - NSE/BSE: 15 min offset for 30m+ timeframes (market opens at 9:15)
     * - MCX: 0 offset (market opens at 9:00)
     * - Smaller timeframes (1m, 5m, 15m) naturally align, no offset needed
     *
     * The output transformation adds the offset back to window boundaries.
     */
    private void buildTimeframeTopology(StreamsBuilder builder, String timeframe, int minutes, String outputTopic) {
        // Use exchange-aware market alignment
        // MarketAlignedTimestampExtractor applies correct offset based on exchange AND timeframe
        KStream<String, FamilyCandle> input = builder.stream(
            inputTopic,
            Consumed.with(Serdes.String(), FamilyCandle.serde())
                .withTimestampExtractor(new MarketAlignedTimestampExtractor(minutes))
        );

        // Window by target timeframe with alignment to market open
        // FIX: Use configurable grace period (default 10s for data arrival)
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            Duration.ofMinutes(minutes),
            Duration.ofSeconds(graceSeconds)
        );

        KTable<Windowed<String>, FamilyCandle> aggregated = input
            .filter((key, candle) -> candle != null && candle.getFamilyId() != null)
            .groupByKey(Grouped.with(Serdes.String(), FamilyCandle.serde()))
            .windowedBy(windows)
            .aggregate(
                () -> null,
                (familyId, incoming, aggregate) -> mergeCandles(aggregate, incoming, timeframe),
                Materialized.<String, FamilyCandle, WindowStore<Bytes, byte[]>>as("agg-store-" + timeframe)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(FamilyCandle.serde())
            );

        // 🛡️ CRITICAL FIX: Wall-clock based emission instead of suppress()
        // BEFORE: suppress(untilWindowCloses) - delayed by stream-time gaps
        // AFTER: WallClockWindowEmitter - emits on wall clock, consistent latency
        long graceMsForEmitter = graceSeconds * 1000L;

        aggregated
            // REMOVED: .suppress(Suppressed.untilWindowCloses(...))
            .toStream()
            .filter((windowedKey, candle) -> candle != null)
            .process(() -> new WallClockWindowEmitter<>(graceMsForEmitter, minutes))  // Wall-clock based emission with timeframe
            .map((windowedKey, candle) -> {
                // 🔴 MARKET ALIGNMENT FIX: Add exchange-specific offset back to window boundaries
                // Get exchange from candle to determine correct offset
                String exchange = getExchangeFromCandle(candle);
                long offsetMs = MarketAlignedTimestampExtractor.getOffsetMs(exchange, minutes);

                long correctedWindowStart = windowedKey.window().start() + offsetMs;
                long correctedWindowEnd = windowedKey.window().end() + offsetMs;

                candle.setWindowStartMillis(correctedWindowStart);
                candle.setWindowEndMillis(correctedWindowEnd);
                candle.setTimeframe(timeframe);

                // 🔴 FIX: Set timestamp to EMISSION time, not merge time
                candle.setTimestamp(System.currentTimeMillis());

                updateHumanReadableTime(candle);

                // 🔴 CRITICAL FIX: Update instrument timeframe fields (Bug #12)
                updateInstrumentTimeframes(candle, timeframe);

                // 🔴 CRITICAL FIX: Recalculate all cross-instrument signals (Bugs #1,2,3,4,7)
                finalizeAggregatedCandle(candle);

                // Validate and log aggregation result
                validateAggregatedCandle(candle, timeframe);

                // 🔴 COMPLETENESS FIX: Calculate and validate candle completeness
                calculateAndValidateCompleteness(candle, timeframe, correctedWindowEnd);

                // Record metrics for this timeframe
                if (dataQualityMetrics != null) {
                    dataQualityMetrics.recordCandleProcessed("TimeframeAggregator", timeframe, true);
                }

                return KeyValue.pair(windowedKey.key(), candle);
            })
            .to(outputTopic, Produced.with(Serdes.String(), FamilyCandle.serde()));
    }

    /**
     * Extract exchange from FamilyCandle for market alignment.
     */
    private String getExchangeFromCandle(FamilyCandle candle) {
        if (candle.getEquity() != null && candle.getEquity().getExchange() != null) {
            return candle.getEquity().getExchange();
        }
        if (candle.getFuture() != null && candle.getFuture().getExchange() != null) {
            return candle.getFuture().getExchange();
        }
        // Default to NSE
        return "N";
    }

    /**
     * Get expected 1m candle count for a timeframe.
     * Used to calculate completeness ratio.
     */
    private static int getExpectedCandleCount(String timeframe) {
        return switch (timeframe) {
            case "2m" -> 2;
            case "3m" -> 3;
            case "5m" -> 5;
            case "15m" -> 15;
            case "30m" -> 30;
            case "1h" -> 60;
            case "2h" -> 120;
            case "4h" -> 240;
            case "1d" -> 375;  // NSE: 9:15-15:30 = 6h15m = 375 minutes
            default -> 1;
        };
    }

    /**
     * Calculate and validate candle completeness.
     *
     * Checks if the aggregated candle has the expected number of 1m candles.
     * For end-of-session candles (especially 4h), expects less data.
     *
     * NSE Session End Candles:
     * - 4h candle at 13:15-17:15: Only 2h15m of data (9:15-15:30 market close)
     * - Sets lastIncompleteSessionCandle = true for these
     *
     * @param candle The aggregated candle
     * @param timeframe The timeframe string
     * @param windowEndMillis The window end timestamp (already corrected for market alignment)
     */
    private void calculateAndValidateCompleteness(FamilyCandle candle, String timeframe, long windowEndMillis) {
        int aggregated = candle.getAggregatedCandleCount();
        int expected = candle.getExpectedCandleCount();

        if (expected <= 0) {
            expected = getExpectedCandleCount(timeframe);
            candle.setExpectedCandleCount(expected);
        }

        // Check if this is the last candle of the NSE session
        // NSE closes at 15:30 IST = 15*60+30 = 930 minutes from midnight
        LocalTime windowEnd = Instant.ofEpochMilli(windowEndMillis)
                .atZone(IST)
                .toLocalTime();
        LocalTime marketClose = LocalTime.of(15, 30);

        boolean isLastSessionCandle = windowEnd.isAfter(marketClose);
        candle.setLastIncompleteSessionCandle(isLastSessionCandle);

        // Adjust expected count for last session candle
        if (isLastSessionCandle && !timeframe.equals("1d")) {
            // Calculate how many minutes are actually in this window
            // Window start is windowEndMillis - (timeframe minutes * 60 * 1000)
            int tfMinutes = getExpectedCandleCount(timeframe);
            LocalTime windowStart = windowEnd.minusMinutes(tfMinutes);

            // If window starts before market close, count only the minutes until close
            if (windowStart.isBefore(marketClose)) {
                int actualMinutes = (int) java.time.Duration.between(windowStart, marketClose).toMinutes();
                expected = Math.max(1, actualMinutes);
                candle.setExpectedCandleCount(expected);
            }
        }

        // Calculate completeness ratio
        double completeness = expected > 0 ? (double) aggregated / expected : 1.0;
        candle.setCompletenessRatio(completeness);

        // Log warning if < 80% complete (except for known incomplete session candles)
        if (completeness < 0.8 && !isLastSessionCandle) {
            log.warn("[INCOMPLETE_CANDLE] {} {} | aggregated={}/{} | completeness={}% | " +
                            "This candle may have sparse data - use with caution",
                    candle.getFamilyId(), timeframe, aggregated, expected,
                    String.format("%.1f", completeness * 100));

            // Update quality reason
            String existingReason = candle.getQualityReason();
            String incompleteReason = String.format("SPARSE_DATA:%.0f%%", completeness * 100);
            candle.setQualityReason(existingReason != null ? existingReason + "," + incompleteReason : incompleteReason);
        }

        if (log.isDebugEnabled()) {
            log.debug("[COMPLETENESS] {} {} | {}/{} candles | {}% | lastSession={}",
                    candle.getFamilyId(), timeframe, aggregated, expected,
                    String.format("%.1f", completeness * 100), isLastSessionCandle);
        }
    }

    /**
     * Calculate completeness for daily candles.
     *
     * Expected minutes varies by exchange:
     * - NSE: 375 minutes (9:15-15:30)
     * - MCX: ~870 minutes (9:00-23:30 with break)
     *
     * @param candle The daily candle
     * @param actualStart Actual session start timestamp
     * @param actualEnd Actual session end timestamp
     */
    private void calculateDailyCompleteness(FamilyCandle candle, long actualStart, long actualEnd) {
        int aggregated = candle.getAggregatedCandleCount();

        // Calculate expected based on actual trading session duration
        long sessionDurationMs = actualEnd - actualStart;
        int sessionMinutes = (int) (sessionDurationMs / 60_000);

        // Expected 1m candles = session minutes (each minute should have a candle)
        int expected = Math.max(1, sessionMinutes);

        // For NSE, typical session is 375 minutes
        String exchange = getExchangeFromCandle(candle);
        if (!"M".equalsIgnoreCase(exchange)) {
            // NSE/BSE - expect at least 350 minutes (allowing some tolerance)
            expected = Math.max(expected, 350);
        }

        candle.setExpectedCandleCount(expected);
        candle.setLastIncompleteSessionCandle(false);  // Daily is always complete session

        double completeness = expected > 0 ? (double) aggregated / expected : 1.0;
        candle.setCompletenessRatio(completeness);

        // Log warning if < 80% complete
        if (completeness < 0.8) {
            log.warn("[INCOMPLETE_DAILY] {} | aggregated={}/{} | completeness={}% | " +
                            "Daily candle may have missing 1m data",
                    candle.getFamilyId(), aggregated, expected,
                    String.format("%.1f", completeness * 100));

            String existingReason = candle.getQualityReason();
            String incompleteReason = String.format("SPARSE_DAILY:%.0f%%", completeness * 100);
            candle.setQualityReason(existingReason != null ? existingReason + "," + incompleteReason : incompleteReason);
        }
    }

    /**
     * Validate aggregated candle data using centralized OHLCValidator
     */
    private void validateAggregatedCandle(FamilyCandle candle, String timeframe) {
        if (candle.getEquity() != null) {
            com.kotsin.consumer.domain.model.InstrumentCandle eq = candle.getEquity();

            // OHLC sanity check using centralized validator
            com.kotsin.consumer.domain.validator.OHLCValidator.validate(
                eq, timeframe, candle.getFamilyId()
            );

            // Log if range seems too small for larger timeframes
            if (timeframe.contains("h") || timeframe.equals("1d")) {
                // For hourly+ candles, range < 0.1% is suspicious
                com.kotsin.consumer.domain.validator.OHLCValidator.checkRangeForTimeframe(
                    eq, timeframe, candle.getFamilyId(), 0.1
                );
            }
        }
    }

    /**
     * Start daily session aggregator
     *
     * 🔴 FIX: Use 24-hour windows instead of hardcoded 375 minutes.
     * This works for BOTH NSE (375 min) and MCX (~870 min) because:
     * - Daily candles aggregate ALL 1m candles from that calendar day
     * - Window boundaries align to midnight IST
     * - No exchange-specific offset needed for daily candles
     */
    private void startDailyStream() {
        try {
            String outputTopic = "family-candle-1d";
            String appId = "timeframe-aggregator-1d";

            Properties props = kafkaConfig.getStreamProperties(appId);
            StreamsBuilder builder = new StreamsBuilder();

            // 🔴 FIX: Use InstrumentCandleTimestampExtractor for daily candles
            // Daily candles don't need market alignment - they aggregate the full day
            // Using windowStartMillis directly ensures correct calendar day alignment
            KStream<String, FamilyCandle> input = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), FamilyCandle.serde())
                    .withTimestampExtractor(new com.kotsin.consumer.timeExtractor.InstrumentCandleTimestampExtractor())
            );

            // 🔴 FIX: Use 24-hour windows for daily candles
            // This works for BOTH NSE (9:15-15:30) and MCX (9:00-23:30)
            // The window captures all trading data for each calendar day
            TimeWindows dailyWindows = TimeWindows.ofSizeAndGrace(
                Duration.ofHours(24),
                Duration.ofMinutes(30)  // 30 min grace for late data at market close
            );

            KTable<Windowed<String>, FamilyCandle> aggregated = input
                .filter((key, candle) -> candle != null)
                .groupByKey(Grouped.with(Serdes.String(), FamilyCandle.serde()))
                .windowedBy(dailyWindows)
                .aggregate(
                    () -> null,
                    (familyId, incoming, aggregate) -> mergeCandles(aggregate, incoming, "1d"),
                    Materialized.<String, FamilyCandle, WindowStore<Bytes, byte[]>>as("agg-store-1d")
                        .withKeySerde(Serdes.String())
                        .withValueSerde(FamilyCandle.serde())
                );

            // 🛡️ Wall-clock based emission for daily candles
            long dailyGraceMs = 30 * 60 * 1000L;  // 30 minutes for daily

            aggregated
                // REMOVED: .suppress(Suppressed.untilWindowCloses(...))
                .toStream()
                .filter((windowedKey, candle) -> candle != null)
                .process(() -> new WallClockWindowEmitter<>(dailyGraceMs, 1440))  // 1440 min = 24h for daily candles
                .map((windowedKey, candle) -> {
                    // 🔴 FIX: For daily candles, use actual trading session boundaries
                    // Calculate the actual first and last candle times from the aggregated data
                    long actualStart = calculateActualDayStart(candle);
                    long actualEnd = calculateActualDayEnd(candle);

                    candle.setWindowStartMillis(actualStart);
                    candle.setWindowEndMillis(actualEnd);
                    candle.setTimeframe("1d");

                    // 🔴 FIX: Set timestamp to EMISSION time
                    candle.setTimestamp(System.currentTimeMillis());

                    updateHumanReadableTime(candle);

                    // 🔴 CRITICAL FIX: Update instrument timeframe fields (Bug #12)
                    updateInstrumentTimeframes(candle, "1d");

                    // 🔴 CRITICAL FIX: Recalculate all cross-instrument signals (Bugs #1,2,3,4,7)
                    finalizeAggregatedCandle(candle);

                    // 🔴 COMPLETENESS FIX: Calculate daily candle completeness
                    // Daily candles expected = 375 for NSE, but varies by exchange
                    calculateDailyCompleteness(candle, actualStart, actualEnd);

                    return KeyValue.pair(windowedKey.key(), candle);
                })
                .to(outputTopic, Produced.with(Serdes.String(), FamilyCandle.serde()));

            KafkaStreams streams = new KafkaStreams(builder.build(), props);
            setupExceptionHandling(streams, "1d");
            streams.start();
            streamsByTimeframe.put("1d", streams);

            log.info("Started 1d aggregator");
            
        } catch (Exception e) {
            log.error("Failed to start daily aggregator", e);
        }
    }

    /**
     * Merge two family candles (for aggregation)
     * FIXED: Properly aggregates OHLCV - Open=first, High=max, Low=min, Close=last, Volume=sum
     * FIXED: Now includes commodity flag, reversal signals, and microstructure data
     *
     * 🔴 COMPREHENSIVE BUG FIXES:
     * - BUG-001: VWAP volume-weighted aggregation
     * - BUG-002: POC/VAH/VAL recalculation from merged volume profile
     * - BUG-003: DataQuality uses WORST not LATEST
     * - BUG-005: AvgTickGap calculated from temporal boundaries
     * - BUG-006: classificationReliability volume-weighted average
     * - BUG-007: OI OHLC proper initial preservation
     * - BUG-008: GreeksPortfolio & IVSurface handling
     * - BUG-011: All FamilyCandle fields preserved
     * - BUG-013/14: All InstrumentCandle fields preserved and aggregated
     */
    private FamilyCandle mergeCandles(FamilyCandle aggregate, FamilyCandle incoming, String timeframe) {
        if (aggregate == null) {
            // First candle in window - clone it with ALL fields
            return FamilyCandle.builder()
                .familyId(incoming.getFamilyId())
                .symbol(incoming.getSymbol())
                .timestamp(incoming.getTimestamp())
                .windowStartMillis(incoming.getWindowStartMillis())
                .windowEndMillis(incoming.getWindowEndMillis())
                .timeframe(timeframe)
                .equity(cloneInstrumentCandle(incoming.getEquity()))
                .future(cloneInstrumentCandle(incoming.getFuture()))
                // 🔴 BUG-011 FIX: Clone primaryInstrument
                .primaryInstrument(cloneInstrumentCandle(incoming.getPrimaryInstrument()))
                .options(cloneOptionsList(incoming.getOptions()))
                .hasFuture(incoming.isHasFuture())
                .hasOptions(incoming.isHasOptions())
                .optionCount(incoming.getOptionCount())
                // CRITICAL FIX: Add commodity flag
                .isCommodity(incoming.isCommodity())
                // ========== SPOT-FUTURE ANALYSIS ==========
                .spotFuturePremium(incoming.getSpotFuturePremium())
                // 🔴 BUG-011 FIX: Add missing fields
                .spotFuturePremiumChange(incoming.getSpotFuturePremiumChange())
                .premiumExpanding(incoming.isPremiumExpanding())
                .futuresBuildup(incoming.getFuturesBuildup())
                // ========== OPTIONS ANALYSIS ==========
                .pcr(incoming.getPcr())
                .pcrChange(incoming.getPcrChange())
                .maxPain(incoming.getMaxPain())
                .highestOIStrike(incoming.getHighestOIStrike())
                .atmIV(incoming.getAtmIV())
                .ivSkew(incoming.getIvSkew())
                // ========== OI DYNAMICS ==========
                .callOiBuildingUp(incoming.isCallOiBuildingUp())
                .putOiUnwinding(incoming.isPutOiUnwinding())
                .futureOiBuildingUp(incoming.isFutureOiBuildingUp())
                .totalCallOI(incoming.getTotalCallOI())
                .totalPutOI(incoming.getTotalPutOI())
                .totalCallOIChange(incoming.getTotalCallOIChange())
                .totalPutOIChange(incoming.getTotalPutOIChange())
                .futureOIChange(incoming.getFutureOIChange())
                .oiSignal(incoming.getOiSignal())
                // ========== CROSS-INSTRUMENT CONFLUENCE ==========
                .pivotRetestAcrossAll(incoming.isPivotRetestAcrossAll())
                .volumeConfluence(incoming.getVolumeConfluence())
                .directionalBias(incoming.getDirectionalBias())
                .biasConfidence(incoming.getBiasConfidence())
                // ========== REVERSAL DETECTION ==========
                .equityShowingReversal(incoming.isEquityShowingReversal())
                .oiConfirmsReversal(incoming.isOiConfirmsReversal())
                .reversalType(incoming.getReversalType())
                // ========== OFI VELOCITY & EXHAUSTION ==========
                .ofiVelocity(incoming.getOfiVelocity())
                .ofiAcceleration(incoming.getOfiAcceleration())
                .exhaustionDetected(incoming.isExhaustionDetected())
                .exhaustionType(incoming.getExhaustionType())
                .previousOfi(incoming.getPreviousOfi())
                // ========== DELTA DIVERGENCE ==========
                .deltaDivergenceDetected(incoming.isDeltaDivergenceDetected())
                .deltaDivergenceType(incoming.getDeltaDivergenceType())
                // ========== REVERSAL SCORE ==========
                .reversalScore(incoming.getReversalScore())
                .reversalSignals(incoming.getReversalSignals() != null ? new ArrayList<>(incoming.getReversalSignals()) : null)
                .highConfidenceReversal(incoming.isHighConfidenceReversal())
                // ========== OPTIONS FLOW ==========
                .callPremiumChange(incoming.getCallPremiumChange())
                .putPremiumChange(incoming.getPutPremiumChange())
                .optionsFlowConfirmsReversal(incoming.isOptionsFlowConfirmsReversal())
                .shortSqueezeDetected(incoming.isShortSqueezeDetected())
                // ========== ENHANCED OI INTERPRETATION ==========
                .oiInterpretation(incoming.getOiInterpretation())
                .oiInterpretationConfidence(incoming.getOiInterpretationConfidence())
                .oiSuggestsReversal(incoming.isOiSuggestsReversal())
                // ========== DATA QUALITY ==========
                .quality(incoming.getQuality())
                .qualityReason(incoming.getQualityReason())
                // ========== AGGREGATION COMPLETENESS ==========
                .aggregatedCandleCount(1)  // First 1m candle in window
                .expectedCandleCount(getExpectedCandleCount(timeframe))
                // ========== MTF DISTRIBUTION ==========
                .mtfDistribution(incoming.getMtfDistribution())
                // 🔴 BUG-008 FIX: Add GreeksPortfolio & IVSurface
                .greeksPortfolio(incoming.getGreeksPortfolio())
                .ivSurface(incoming.getIvSurface())
                .build();
        }

        // Existing aggregate - PROPERLY merge OHLCV
        // 🔴 FIX: Don't update timestamp during merge - it will be set to emission time in the map() function
        // REMOVED: aggregate.setTimestamp(Math.max(aggregate.getTimestamp(), incoming.getTimestamp()));

        // 🔴 COMPLETENESS FIX: Increment aggregated candle count
        aggregate.setAggregatedCandleCount(aggregate.getAggregatedCandleCount() + 1);

        // ========== MERGE EQUITY CANDLE OHLCV ==========
        if (incoming.getEquity() != null) {
            if (aggregate.getEquity() == null) {
                aggregate.setEquity(cloneInstrumentCandle(incoming.getEquity()));
            } else {
                mergeInstrumentCandle(aggregate.getEquity(), incoming.getEquity());
            }
        }
        
        // ========== MERGE FUTURE CANDLE OHLCV ==========
        if (incoming.getFuture() != null) {
            if (aggregate.getFuture() == null) {
                aggregate.setFuture(cloneInstrumentCandle(incoming.getFuture()));
            } else {
                mergeInstrumentCandle(aggregate.getFuture(), incoming.getFuture());
            }
        }
        
        // ========== MERGE OPTIONS (dedupe by scripCode, merge OHLCV) ==========
        if (incoming.getOptions() != null && !incoming.getOptions().isEmpty()) {
            if (aggregate.getOptions() == null) {
                aggregate.setOptions(new ArrayList<>(incoming.getOptions()));
            } else {
                // Merge options by scripCode
                Map<String, com.kotsin.consumer.domain.model.OptionCandle> optionMap = new HashMap<>();
                for (com.kotsin.consumer.domain.model.OptionCandle opt : aggregate.getOptions()) {
                    if (opt.getScripCode() != null) {
                        optionMap.put(opt.getScripCode(), opt);
                    }
                }
                for (com.kotsin.consumer.domain.model.OptionCandle incomingOpt : incoming.getOptions()) {
                    if (incomingOpt.getScripCode() == null) continue;
                    com.kotsin.consumer.domain.model.OptionCandle existing = optionMap.get(incomingOpt.getScripCode());
                    if (existing == null) {
                        optionMap.put(incomingOpt.getScripCode(), incomingOpt);
                    } else {
                        // Merge OHLCV: Open=first, High=max, Low=min, Close=last, Volume=sum
                        existing.setHigh(Math.max(existing.getHigh(), incomingOpt.getHigh()));
                        existing.setLow(Math.min(existing.getLow(), incomingOpt.getLow()));
                        existing.setClose(incomingOpt.getClose());
                        existing.setVolume(existing.getVolume() + incomingOpt.getVolume());
                        // OI uses latest, oiChange = sum
                        existing.setOpenInterest(incomingOpt.getOpenInterest());
                        existing.setOiChange(existing.getOiChange() + incomingOpt.getOiChange());

                        // 🔴 UNDERUTIL FIX #1: Aggregate options microstructure fields
                        // SUM fields (flow/volume based)
                        if (incomingOpt.getOfi() != null) {
                            double existingOfi = existing.getOfi() != null ? existing.getOfi() : 0.0;
                            existing.setOfi(existingOfi + incomingOpt.getOfi());
                        }
                        if (incomingOpt.getAggressiveBuyVolume() != null) {
                            long existingVal = existing.getAggressiveBuyVolume() != null ? existing.getAggressiveBuyVolume() : 0L;
                            existing.setAggressiveBuyVolume(existingVal + incomingOpt.getAggressiveBuyVolume());
                        }
                        if (incomingOpt.getAggressiveSellVolume() != null) {
                            long existingVal = existing.getAggressiveSellVolume() != null ? existing.getAggressiveSellVolume() : 0L;
                            existing.setAggressiveSellVolume(existingVal + incomingOpt.getAggressiveSellVolume());
                        }
                        existing.setVolumeImbalance(existing.getVolumeImbalance() + incomingOpt.getVolumeImbalance());
                        existing.setDollarImbalance(existing.getDollarImbalance() + incomingOpt.getDollarImbalance());
                        if (incomingOpt.getTickRuns() != null) {
                            int existingVal = existing.getTickRuns() != null ? existing.getTickRuns() : 0;
                            existing.setTickRuns(existingVal + incomingOpt.getTickRuns());
                        }
                        if (incomingOpt.getLargeTradeCount() != null) {
                            int existingVal = existing.getLargeTradeCount() != null ? existing.getLargeTradeCount() : 0;
                            existing.setLargeTradeCount(existingVal + incomingOpt.getLargeTradeCount());
                        }

                        // LATEST fields (point-in-time metrics)
                        existing.setMicroprice(incomingOpt.getMicroprice());
                        existing.setBidAskSpread(incomingOpt.getBidAskSpread());
                        existing.setDepthImbalance(incomingOpt.getDepthImbalance());
                        existing.setAverageBidDepth(incomingOpt.getAverageBidDepth());
                        existing.setAverageAskDepth(incomingOpt.getAverageAskDepth());
                        existing.setVpin(incomingOpt.getVpin());
                        existing.setKyleLambda(incomingOpt.getKyleLambda());

                        // Recalculate buy/sell pressure from aggregated volumes
                        long totalAggVol = existing.getVolume();
                        if (totalAggVol > 0 && existing.getAggressiveBuyVolume() != null && existing.getAggressiveSellVolume() != null) {
                            existing.setBuyPressure((double) existing.getAggressiveBuyVolume() / totalAggVol);
                            existing.setSellPressure((double) existing.getAggressiveSellVolume() / totalAggVol);
                        }

                        // Imbalance bar triggered - OR across window
                        if (incomingOpt.getImbalanceBarTriggered() != null && incomingOpt.getImbalanceBarTriggered()) {
                            existing.setImbalanceBarTriggered(true);
                        }

                        // Unusual volume - OR across window
                        if (incomingOpt.getUnusualVolume() != null && incomingOpt.getUnusualVolume()) {
                            existing.setUnusualVolume(true);
                        }

                        // Greeks - use latest (they're point-in-time)
                        existing.setDelta(incomingOpt.getDelta());
                        existing.setGamma(incomingOpt.getGamma());
                        existing.setTheta(incomingOpt.getTheta());
                        existing.setVega(incomingOpt.getVega());
                        existing.setImpliedVolatility(incomingOpt.getImpliedVolatility());

                        // 🔴 BUG-012 FIX: Add more options microstructure field aggregation
                        // OFI Momentum - LATEST
                        if (incomingOpt.getOfiMomentum() != null) {
                            existing.setOfiMomentum(incomingOpt.getOfiMomentum());
                        }
                        // Midpoint volume - SUM
                        if (incomingOpt.getMidpointVolume() != null) {
                            Long existingMid = existing.getMidpointVolume() != null ? existing.getMidpointVolume() : 0L;
                            existing.setMidpointVolume(existingMid + incomingOpt.getMidpointVolume());
                        }
                        // Classification reliability - volume-weighted average
                        if (incomingOpt.getClassificationReliability() != null && incomingOpt.getVolume() > 0) {
                            Double existingRel = existing.getClassificationReliability();
                            long prevVol = existing.getVolume() - incomingOpt.getVolume();
                            if (existingRel != null && prevVol > 0) {
                                double weighted = (existingRel * prevVol + incomingOpt.getClassificationReliability() * incomingOpt.getVolume()) / existing.getVolume();
                                existing.setClassificationReliability(weighted);
                            } else {
                                existing.setClassificationReliability(incomingOpt.getClassificationReliability());
                            }
                        }
                        // Weighted depth imbalance - LATEST
                        if (incomingOpt.getWeightedDepthImbalance() != null) {
                            existing.setWeightedDepthImbalance(incomingOpt.getWeightedDepthImbalance());
                        }
                        // Depth slopes - LATEST
                        if (incomingOpt.getBidDepthSlope() != null) {
                            existing.setBidDepthSlope(incomingOpt.getBidDepthSlope());
                        }
                        if (incomingOpt.getAskDepthSlope() != null) {
                            existing.setAskDepthSlope(incomingOpt.getAskDepthSlope());
                        }
                        // Depth concentration - LATEST
                        if (incomingOpt.getDepthConcentration() != null) {
                            existing.setDepthConcentration(incomingOpt.getDepthConcentration());
                        }
                        // Spoofing count - SUM
                        if (incomingOpt.getSpoofingCount() != null) {
                            Integer existingCount = existing.getSpoofingCount() != null ? existing.getSpoofingCount() : 0;
                            existing.setSpoofingCount(existingCount + incomingOpt.getSpoofingCount());
                        }
                        // Iceberg detected - OR
                        if (incomingOpt.getIcebergDetected() != null && incomingOpt.getIcebergDetected()) {
                            existing.setIcebergDetected(true);
                        }
                        // Cancel rate - use LATEST
                        if (incomingOpt.getCancelRate() != null) {
                            existing.setCancelRate(incomingOpt.getCancelRate());
                        }
                        // Volume at price - MERGE maps
                        if (incomingOpt.getVolumeAtPrice() != null && !incomingOpt.getVolumeAtPrice().isEmpty()) {
                            Map<Double, Long> existingVap = existing.getVolumeAtPrice();
                            if (existingVap == null) {
                                existing.setVolumeAtPrice(new HashMap<>(incomingOpt.getVolumeAtPrice()));
                            } else {
                                for (Map.Entry<Double, Long> entry : incomingOpt.getVolumeAtPrice().entrySet()) {
                                    existingVap.merge(entry.getKey(), entry.getValue(), Long::sum);
                                }
                            }
                        }
                        // POC/VAH/VAL - use LATEST (would need recalc for proper accuracy)
                        if (incomingOpt.getPoc() != null) {
                            existing.setPoc(incomingOpt.getPoc());
                        }
                        if (incomingOpt.getVah() != null) {
                            existing.setVah(incomingOpt.getVah());
                        }
                        if (incomingOpt.getVal() != null) {
                            existing.setVal(incomingOpt.getVal());
                        }
                        // Execution cost - average effective spread (tick-weighted)
                        if (incomingOpt.getAverageEffectiveSpread() != null && incomingOpt.getTicksPerSecond() != null && incomingOpt.getTicksPerSecond() > 0) {
                            // Approximate tick-weighted average
                            existing.setAverageEffectiveSpread(incomingOpt.getAverageEffectiveSpread());
                        }
                        if (incomingOpt.getPriceImprovementRatio() != null) {
                            existing.setPriceImprovementRatio(incomingOpt.getPriceImprovementRatio());
                        }
                        if (incomingOpt.getPriceImpactPerUnit() != null) {
                            existing.setPriceImpactPerUnit(incomingOpt.getPriceImpactPerUnit());
                        }
                        if (incomingOpt.getTightSpreadPercent() != null) {
                            existing.setTightSpreadPercent(incomingOpt.getTightSpreadPercent());
                        }
                        // Tick intensity - use LATEST for rate, SUM for count
                        if (incomingOpt.getTicksPerSecond() != null) {
                            existing.setTicksPerSecond(incomingOpt.getTicksPerSecond());
                        }
                        if (incomingOpt.getTickAcceleration() != null) {
                            existing.setTickAcceleration(incomingOpt.getTickAcceleration());
                        }
                        if (incomingOpt.getTickBurstRatio() != null) {
                            existing.setTickBurstRatio(incomingOpt.getTickBurstRatio());
                        }
                        // Algo activity - OR
                        if (incomingOpt.getAlgoActivityDetected() != null && incomingOpt.getAlgoActivityDetected()) {
                            existing.setAlgoActivityDetected(true);
                        }
                        // Options-specific flow - recalculate
                        if (incomingOpt.getOptionFlowScore() != null) {
                            existing.setOptionFlowScore(incomingOpt.getOptionFlowScore());
                        }
                        // 🔴 BUG-009 FIX: Recalculate delta-adjusted flow and gamma exposure
                        if (existing.getDelta() != null && existing.getOfi() != null) {
                            existing.setDeltaAdjustedFlow(existing.getOfi() * existing.getDelta());
                        }
                        if (existing.getGamma() != null) {
                            existing.setGammaExposure(existing.getGamma() * existing.getOpenInterest());
                        }
                        // IV metrics - LATEST
                        if (incomingOpt.getIvRank() != null) {
                            existing.setIvRank(incomingOpt.getIvRank());
                        }
                        if (incomingOpt.getIvChange() != null) {
                            existing.setIvChange(incomingOpt.getIvChange());
                        }
                    }
                }
                aggregate.setOptions(new ArrayList<>(optionMap.values()));
            }
        }
        
        // CRITICAL FIX: Recalculate hasOptions and optionCount after merge
        List<?> opts = aggregate.getOptions();
        aggregate.setHasOptions(opts != null && !opts.isEmpty());
        aggregate.setOptionCount(opts != null ? opts.size() : 0);

        // ========== AGGREGATE OI CHANGES ==========
        if (incoming.getTotalCallOIChange() != null) {
            Long current = aggregate.getTotalCallOIChange();
            aggregate.setTotalCallOIChange((current != null ? current : 0L) + incoming.getTotalCallOIChange());
        }
        if (incoming.getTotalPutOIChange() != null) {
            Long current = aggregate.getTotalPutOIChange();
            aggregate.setTotalPutOIChange((current != null ? current : 0L) + incoming.getTotalPutOIChange());
        }
        if (incoming.getFutureOIChange() != null) {
            Long current = aggregate.getFutureOIChange();
            aggregate.setFutureOIChange((current != null ? current : 0L) + incoming.getFutureOIChange());
        }

        // Use latest point-in-time values (not aggregated)
        aggregate.setSpotFuturePremium(incoming.getSpotFuturePremium());
        aggregate.setFuturesBuildup(incoming.getFuturesBuildup());
        aggregate.setPcr(incoming.getPcr());
        aggregate.setTotalCallOI(incoming.getTotalCallOI());
        aggregate.setTotalPutOI(incoming.getTotalPutOI());
        aggregate.setOiSignal(incoming.getOiSignal());
        aggregate.setDirectionalBias(incoming.getDirectionalBias());
        aggregate.setBiasConfidence(incoming.getBiasConfidence());

        // ========== UPDATE REVERSAL SIGNALS (use latest values) ==========
        // OFI velocity/acceleration should be recalculated based on 30m window, but for now use latest 1m signal
        if (incoming.getOfiVelocity() != null) {
            aggregate.setOfiVelocity(incoming.getOfiVelocity());
        }
        if (incoming.getOfiAcceleration() != null) {
            aggregate.setOfiAcceleration(incoming.getOfiAcceleration());
        }
        aggregate.setPreviousOfi(incoming.getPreviousOfi());

        // Exhaustion - use latest detection (may have changed from first to last candle)
        aggregate.setExhaustionDetected(incoming.isExhaustionDetected());
        aggregate.setExhaustionType(incoming.getExhaustionType());

        // Delta divergence - use latest detection
        aggregate.setDeltaDivergenceDetected(incoming.isDeltaDivergenceDetected());
        aggregate.setDeltaDivergenceType(incoming.getDeltaDivergenceType());

        // Reversal score - accumulate max score seen during the window
        Double incomingScore = incoming.getReversalScore();
        Double currentScore = aggregate.getReversalScore();
        if (incomingScore != null && (currentScore == null || incomingScore > currentScore)) {
            aggregate.setReversalScore(incomingScore);
            // Use signals from highest score candle
            aggregate.setReversalSignals(incoming.getReversalSignals() != null ?
                new ArrayList<>(incoming.getReversalSignals()) : null);
        }
        // High confidence reversal = any candle triggered high confidence
        if (incoming.isHighConfidenceReversal()) {
            aggregate.setHighConfidenceReversal(true);
        }

        // Options flow - use latest
        aggregate.setCallPremiumChange(incoming.getCallPremiumChange());
        aggregate.setPutPremiumChange(incoming.getPutPremiumChange());
        aggregate.setOptionsFlowConfirmsReversal(incoming.isOptionsFlowConfirmsReversal());
        aggregate.setShortSqueezeDetected(incoming.isShortSqueezeDetected() || aggregate.isShortSqueezeDetected());

        // OI interpretation - use latest
        aggregate.setOiInterpretation(incoming.getOiInterpretation());
        aggregate.setOiInterpretationConfidence(incoming.getOiInterpretationConfidence());
        aggregate.setOiSuggestsReversal(incoming.isOiSuggestsReversal());

        // PRICE_ACTION_FIX: Preserve mtfDistribution from incoming candle
        // For aggregated timeframes, use latest mtfDistribution as it represents
        // the most recent sub-candle analysis for that window
        if (incoming.getMtfDistribution() != null) {
            aggregate.setMtfDistribution(incoming.getMtfDistribution());
        }

        // 🔴 BUG-008 FIX: Update GreeksPortfolio & IVSurface (use LATEST - point-in-time)
        if (incoming.getGreeksPortfolio() != null) {
            aggregate.setGreeksPortfolio(incoming.getGreeksPortfolio());
        }
        if (incoming.getIvSurface() != null) {
            aggregate.setIvSurface(incoming.getIvSurface());
        }

        // 🔴 BUG-011 FIX: Update additional missing fields
        if (incoming.getSpotFuturePremiumChange() != null) {
            aggregate.setSpotFuturePremiumChange(incoming.getSpotFuturePremiumChange());
        }
        aggregate.setPremiumExpanding(incoming.isPremiumExpanding());
        if (incoming.getPcrChange() != null) {
            aggregate.setPcrChange(incoming.getPcrChange());
        }
        if (incoming.getMaxPain() != null) {
            aggregate.setMaxPain(incoming.getMaxPain());
        }
        if (incoming.getHighestOIStrike() != null) {
            aggregate.setHighestOIStrike(incoming.getHighestOIStrike());
        }
        if (incoming.getAtmIV() != null) {
            aggregate.setAtmIV(incoming.getAtmIV());
        }
        if (incoming.getIvSkew() != null) {
            aggregate.setIvSkew(incoming.getIvSkew());
        }
        aggregate.setPivotRetestAcrossAll(incoming.isPivotRetestAcrossAll() || aggregate.isPivotRetestAcrossAll());
        aggregate.setVolumeConfluence(incoming.getVolumeConfluence());
        aggregate.setEquityShowingReversal(incoming.isEquityShowingReversal() || aggregate.isEquityShowingReversal());
        aggregate.setOiConfirmsReversal(incoming.isOiConfirmsReversal() || aggregate.isOiConfirmsReversal());
        if (incoming.getReversalType() != null) {
            aggregate.setReversalType(incoming.getReversalType());
        }

        // 🔴 BUG-003 FIX: DataQuality uses WORST not LATEST
        if (incoming.getQuality() != null) {
            com.kotsin.consumer.domain.model.DataQuality aggQuality = aggregate.getQuality();
            com.kotsin.consumer.domain.model.DataQuality incQuality = incoming.getQuality();
            // Higher ordinal = worse quality (VALID=0 < WARNING=1 < CONFLICT=2 < STALE=3 < INSUFFICIENT=4)
            if (aggQuality == null || incQuality.ordinal() > aggQuality.ordinal()) {
                aggregate.setQuality(incQuality);
                aggregate.setQualityReason(incoming.getQualityReason());
            }
        }

        return aggregate;
    }

    /**
     * 🔴 BUG-011 FIX: Deep clone options list to prevent reference sharing
     */
    private List<com.kotsin.consumer.domain.model.OptionCandle> cloneOptionsList(
            List<com.kotsin.consumer.domain.model.OptionCandle> source) {
        if (source == null) return null;
        List<com.kotsin.consumer.domain.model.OptionCandle> cloned = new ArrayList<>(source.size());
        for (com.kotsin.consumer.domain.model.OptionCandle opt : source) {
            if (opt != null) {
                // Deep clone each option candle
                cloned.add(com.kotsin.consumer.domain.model.OptionCandle.builder()
                    // Identity
                    .scripCode(opt.getScripCode())
                    .symbol(opt.getSymbol())
                    .strikePrice(opt.getStrikePrice())
                    .optionType(opt.getOptionType())
                    .expiry(opt.getExpiry())
                    // OHLCV
                    .open(opt.getOpen())
                    .high(opt.getHigh())
                    .low(opt.getLow())
                    .close(opt.getClose())
                    .volume(opt.getVolume())
                    // OI
                    .openInterest(opt.getOpenInterest())
                    .oiChange(opt.getOiChange())
                    .oiChangePercent(opt.getOiChangePercent())
                    // Greeks
                    .impliedVolatility(opt.getImpliedVolatility())
                    .delta(opt.getDelta())
                    .gamma(opt.getGamma())
                    .theta(opt.getTheta())
                    .vega(opt.getVega())
                    // Derived
                    .intrinsicValue(opt.getIntrinsicValue())
                    .timeValue(opt.getTimeValue())
                    .isITM(opt.isITM())
                    .isATM(opt.isATM())
                    // Microstructure
                    .ofi(opt.getOfi())
                    .ofiMomentum(opt.getOfiMomentum())
                    .kyleLambda(opt.getKyleLambda())
                    .microprice(opt.getMicroprice())
                    .vpin(opt.getVpin())
                    .vpinBucketSize(opt.getVpinBucketSize())
                    .aggressiveBuyVolume(opt.getAggressiveBuyVolume())
                    .aggressiveSellVolume(opt.getAggressiveSellVolume())
                    .buyPressure(opt.getBuyPressure())
                    .sellPressure(opt.getSellPressure())
                    .midpointVolume(opt.getMidpointVolume())
                    .tickBidAskImbalance(opt.getTickBidAskImbalance())
                    .classificationReliability(opt.getClassificationReliability())
                    // Orderbook depth
                    .depthImbalance(opt.getDepthImbalance())
                    .weightedDepthImbalance(opt.getWeightedDepthImbalance())
                    .bidAskSpread(opt.getBidAskSpread())
                    .spreadVolatility(opt.getSpreadVolatility())
                    .averageBidDepth(opt.getAverageBidDepth())
                    .averageAskDepth(opt.getAverageAskDepth())
                    .bidDepthSlope(opt.getBidDepthSlope())
                    .askDepthSlope(opt.getAskDepthSlope())
                    .depthConcentration(opt.getDepthConcentration())
                    .spoofingCount(opt.getSpoofingCount())
                    .icebergDetected(opt.getIcebergDetected())
                    .cancelRate(opt.getCancelRate())
                    // Volume profile
                    .volumeAtPrice(opt.getVolumeAtPrice() != null ? new HashMap<>(opt.getVolumeAtPrice()) : null)
                    .poc(opt.getPoc())
                    .vah(opt.getVah())
                    .val(opt.getVal())
                    // Imbalance bars
                    .volumeImbalance(opt.getVolumeImbalance())
                    .dollarImbalance(opt.getDollarImbalance())
                    .imbalanceBarTriggered(opt.getImbalanceBarTriggered())
                    .tickRuns(opt.getTickRuns())
                    // Execution cost
                    .averageEffectiveSpread(opt.getAverageEffectiveSpread())
                    .priceImprovementRatio(opt.getPriceImprovementRatio())
                    .priceImpactPerUnit(opt.getPriceImpactPerUnit())
                    .tightSpreadPercent(opt.getTightSpreadPercent())
                    // Tick intensity
                    .ticksPerSecond(opt.getTicksPerSecond())
                    .tickAcceleration(opt.getTickAcceleration())
                    .tickBurstRatio(opt.getTickBurstRatio())
                    .algoActivityDetected(opt.getAlgoActivityDetected())
                    .largeTradeCount(opt.getLargeTradeCount())
                    // Options-specific flow
                    .optionFlowScore(opt.getOptionFlowScore())
                    .deltaAdjustedFlow(opt.getDeltaAdjustedFlow())
                    .gammaExposure(opt.getGammaExposure())
                    .unusualVolume(opt.getUnusualVolume())
                    .ivRank(opt.getIvRank())
                    .ivChange(opt.getIvChange())
                    .build());
            }
        }
        return cloned;
    }

    /**
     * 🔴 CRITICAL FIX: Finalize aggregated candle by recalculating all cross-instrument signals.
     *
     * BEFORE: Signals were copied from last 1m candle (WRONG for 30m analysis)
     * AFTER: Signals are recalculated based on aggregated 30m data
     *
     * This fixes:
     * - Bug #1: futuresBuildup = NEUTRAL (should be LONG_BUILDUP for price↑+OI↑)
     * - Bug #2: oiChangePercent = 0.0 (should be calculated from aggregated data)
     * - Bug #3: oiSignal = NEUTRAL (thresholds now dynamic for commodities)
     * - Bug #4: directionalBias = NEUTRAL (derived from oiSignal)
     * - Bug #5: commodity = false for MCX instruments (should be true)
     * - Bug #7: Options oiChangePercent = 0.0
     */
    private void finalizeAggregatedCandle(FamilyCandle aggregate) {
        if (aggregate == null) return;

        // ========== FIX #5: Ensure isCommodity flag is correct ==========
        // This fixes cases where the 1m candle had wrong isCommodity flag
        if (!aggregate.isCommodity() && aggregate.getFuture() != null) {
            String exchange = aggregate.getFuture().getExchange();
            if ("M".equalsIgnoreCase(exchange)) {
                aggregate.setCommodity(true);
                log.debug("[MTF-FINALIZE] {} | Fixed isCommodity flag: exchange={} -> commodity=true",
                    aggregate.getFamilyId(), exchange);
            }
        }

        // ========== FIX #2 & #7: Recalculate oiChangePercent for all instruments ==========

        // Fix Future oiChangePercent
        if (aggregate.getFuture() != null && aggregate.getFuture().getOiChange() != null) {
            recalculateOiChangePercent(aggregate.getFuture());
        }

        // Fix Equity oiChangePercent (if present)
        if (aggregate.getEquity() != null && aggregate.getEquity().getOiChange() != null) {
            recalculateOiChangePercent(aggregate.getEquity());
        }

        // Fix Options oiChangePercent
        if (aggregate.getOptions() != null) {
            for (com.kotsin.consumer.domain.model.OptionCandle opt : aggregate.getOptions()) {
                if (opt != null && opt.getOiChange() != 0) {
                    long previousOI = opt.getOpenInterest() - opt.getOiChange();
                    if (previousOI > 0) {
                        opt.setOiChangePercent((double) opt.getOiChange() / previousOI * 100.0);
                    }
                }
            }
        }

        // ========== FIX #1: Recalculate futuresBuildup based on aggregated data ==========
        if (aggregate.getFuture() != null && aggregate.getFuture().hasOI()) {
            com.kotsin.consumer.domain.model.InstrumentCandle future = aggregate.getFuture();

            // Recalculate priceChangePercent from aggregated OHLC
            double priceChange = 0.0;
            if (future.getOpen() > 0) {
                priceChange = (future.getClose() - future.getOpen()) / future.getOpen() * 100.0;
                // Update the field so downstream detectors use correct value
                // Note: InstrumentCandle may need a setPriceChangePercent or we calculate inline
            }

            // Get oiChangePercent (should be recalculated above)
            Double oiChangePct = future.getOiChangePercent();
            if (oiChangePct == null) oiChangePct = 0.0;

            // Detect buildup with proper thresholds
            FuturesBuildupDetector.BuildupType buildup =
                FuturesBuildupDetector.detect(priceChange, oiChangePct);
            aggregate.setFuturesBuildup(buildup.name());

            // Update futureOiBuildingUp flag
            aggregate.setFutureOiBuildingUp(oiChangePct > 1.0); // Lower threshold for commodities

            log.debug("[MTF-FINALIZE] {} | futuresBuildup recalculated: price={}% OI={}% -> {}",
                aggregate.getFamilyId(),
                String.format("%.3f", priceChange),
                String.format("%.3f", oiChangePct),
                buildup.name());
        }

        // ========== FIX #3 & #4: Recalculate OI signal with dynamic thresholds ==========
        OISignalDetector.OISignalType oiSignal = OISignalDetector.detectWithDynamicThresholds(aggregate);
        aggregate.setOiSignal(oiSignal.name());

        // Recalculate directional bias
        int bias = OISignalDetector.getDirectionalBias(oiSignal);
        if (bias > 0) {
            aggregate.setDirectionalBias("BULLISH");
        } else if (bias < 0) {
            aggregate.setDirectionalBias("BEARISH");
        } else {
            aggregate.setDirectionalBias("NEUTRAL");
        }
        aggregate.setBiasConfidence(OISignalDetector.getConfidence(aggregate, oiSignal));

        // ========== Recalculate PCR and OI totals from aggregated options ==========
        if (aggregate.getOptions() != null && !aggregate.getOptions().isEmpty()) {
            List<com.kotsin.consumer.domain.model.OptionCandle> options = aggregate.getOptions();

            // Recalculate totals
            long totalCallOI = 0, totalPutOI = 0;
            long totalCallOIChange = 0, totalPutOIChange = 0;

            for (com.kotsin.consumer.domain.model.OptionCandle opt : options) {
                if (opt == null) continue;
                if (opt.isCall()) {
                    totalCallOI += opt.getOpenInterest();
                    totalCallOIChange += opt.getOiChange();
                } else if (opt.isPut()) {
                    totalPutOI += opt.getOpenInterest();
                    totalPutOIChange += opt.getOiChange();
                }
            }

            aggregate.setTotalCallOI(totalCallOI);
            aggregate.setTotalPutOI(totalPutOI);
            aggregate.setTotalCallOIChange(totalCallOIChange);
            aggregate.setTotalPutOIChange(totalPutOIChange);

            // Recalculate PCR
            if (totalCallOI > 0) {
                aggregate.setPcr((double) totalPutOI / totalCallOI);
            }

            // Update OI change flags with lower thresholds for commodities
            long oiChangeThreshold = isCommodityFamily(aggregate) ? 100 : 1000;
            aggregate.setCallOiBuildingUp(totalCallOIChange > oiChangeThreshold);
            aggregate.setPutOiUnwinding(totalPutOIChange < -oiChangeThreshold);
        }

        // ========== Set spotPrice for commodities ==========
        if (aggregate.getSpotPrice() <= 0) {
            double primaryPrice = aggregate.getPrimaryPrice();
            if (primaryPrice > 0) {
                // spotPrice field needs to be set - it's computed via getSpotPrice() but we should ensure
                // the underlying data is correct
                log.debug("[MTF-FINALIZE] {} | spotPrice derived from primaryPrice: {}",
                    aggregate.getFamilyId(), primaryPrice);
            }
        }

        log.debug("[MTF-FINALIZE] {} | Signals recalculated: oiSignal={} bias={} confidence={}",
            aggregate.getFamilyId(), oiSignal.name(), aggregate.getDirectionalBias(),
            String.format("%.2f", aggregate.getBiasConfidence()));
    }

    /**
     * 🔴 BUG-020 FIX: Recalculate oiChangePercent using oiOpen (not derived from oiClose - oiChange)
     *
     * BEFORE: previousOI = oiClose - oiChange (WRONG - could double-count)
     * AFTER: previousOI = oiOpen (if available) - the true starting OI for the window
     */
    private void recalculateOiChangePercent(com.kotsin.consumer.domain.model.InstrumentCandle candle) {
        if (candle == null || candle.getOiChange() == null) {
            return;
        }

        long oiChange = candle.getOiChange();
        long previousOI;

        // 🔴 FIX: Use oiOpen (the true starting OI) if available
        if (candle.getOiOpen() != null && candle.getOiOpen() > 0) {
            previousOI = candle.getOiOpen();
        } else if (candle.getOiClose() != null) {
            // Fallback: derive from oiClose - oiChange
            previousOI = candle.getOiClose() - oiChange;
        } else {
            return;
        }

        if (previousOI > 0) {
            double pct = (double) oiChange / previousOI * 100.0;
            candle.setOiChangePercent(pct);
            log.debug("[MTF-OI-PCT] {} | oiChange={} previousOI={} (from oiOpen={}) -> oiChangePercent={}%",
                candle.getScripCode(), oiChange, previousOI, candle.getOiOpen(), String.format("%.3f", pct));
        }
    }

    /**
     * Check if this is a commodity family (MCX)
     */
    private boolean isCommodityFamily(FamilyCandle family) {
        if (family == null) return false;

        // Check isCommodity flag
        if (family.isCommodity()) return true;

        // Fallback: check future exchange
        if (family.getFuture() != null) {
            String exchange = family.getFuture().getExchange();
            return "M".equalsIgnoreCase(exchange);
        }

        return false;
    }

    /**
     * 🔴 FIX Bug #12: Update internal timeframe fields in all instruments
     *
     * The embedded InstrumentCandles retain their original 1m timeframe field.
     * This updates them to match the aggregated timeframe.
     */
    private void updateInstrumentTimeframes(FamilyCandle candle, String timeframe) {
        if (candle == null) return;

        // BUG FIX: Calculate consistent humanReadableTime from windowStartMillis
        String humanReadableTime = null;
        try {
            ZonedDateTime zdt = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(candle.getWindowStartMillis()),
                IST
            );
            humanReadableTime = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            // Fallback - will be null
        }

        if (candle.getEquity() != null) {
            candle.getEquity().setTimeframe(timeframe);
            candle.getEquity().setWindowStartMillis(candle.getWindowStartMillis());
            candle.getEquity().setWindowEndMillis(candle.getWindowEndMillis());
            if (humanReadableTime != null) {
                candle.getEquity().setHumanReadableTime(humanReadableTime);
            }
        }

        if (candle.getFuture() != null) {
            candle.getFuture().setTimeframe(timeframe);
            candle.getFuture().setWindowStartMillis(candle.getWindowStartMillis());
            candle.getFuture().setWindowEndMillis(candle.getWindowEndMillis());
            if (humanReadableTime != null) {
                candle.getFuture().setHumanReadableTime(humanReadableTime);
            }
        }

        if (candle.getPrimaryInstrument() != null) {
            candle.getPrimaryInstrument().setTimeframe(timeframe);
            candle.getPrimaryInstrument().setWindowStartMillis(candle.getWindowStartMillis());
            candle.getPrimaryInstrument().setWindowEndMillis(candle.getWindowEndMillis());
            if (humanReadableTime != null) {
                candle.getPrimaryInstrument().setHumanReadableTime(humanReadableTime);
            }
        }

        // BUG FIX: Also update options for full consistency
        if (candle.getOptions() != null) {
            for (var option : candle.getOptions()) {
                if (option != null && humanReadableTime != null) {
                    // Options don't have setHumanReadableTime but should be consistent
                    // If needed, add humanReadableTime field to OptionCandle
                }
            }
        }
    }

    /**
     * Clone an InstrumentCandle (for initial aggregation)
     * FIXED: Now includes ALL microstructure fields for proper aggregation
     */
    private com.kotsin.consumer.domain.model.InstrumentCandle cloneInstrumentCandle(
            com.kotsin.consumer.domain.model.InstrumentCandle source) {
        if (source == null) return null;

        return com.kotsin.consumer.domain.model.InstrumentCandle.builder()
            .scripCode(source.getScripCode())
            .symbol(source.getSymbol())
            .companyName(source.getCompanyName())
            .exchange(source.getExchange())
            .exchangeType(source.getExchangeType())
            .instrumentType(source.getInstrumentType())
            .windowStartMillis(source.getWindowStartMillis())
            .windowEndMillis(source.getWindowEndMillis())
            .timeframe(source.getTimeframe())
            .humanReadableTime(source.getHumanReadableTime())
            // OHLCV
            .open(source.getOpen())
            .high(source.getHigh())
            .low(source.getLow())
            .close(source.getClose())
            .volume(source.getVolume())
            .buyVolume(source.getBuyVolume())
            .sellVolume(source.getSellVolume())
            .vwap(source.getVwap())
            .tickCount(source.getTickCount())
            // Volume classification
            .aggressiveBuyVolume(source.getAggressiveBuyVolume())
            .aggressiveSellVolume(source.getAggressiveSellVolume())
            .midpointVolume(source.getMidpointVolume())
            .classificationReliability(source.getClassificationReliability())
            .buyPressure(source.getBuyPressure())
            .sellPressure(source.getSellPressure())
            // Imbalance metrics (SUM across candles)
            .volumeImbalance(source.getVolumeImbalance())
            .dollarImbalance(source.getDollarImbalance())
            .tickRuns(source.getTickRuns())
            .volumeRuns(source.getVolumeRuns())
            .vibTriggered(source.isVibTriggered())
            .dibTriggered(source.isDibTriggered())
            .trbTriggered(source.isTrbTriggered())
            .vrbTriggered(source.isVrbTriggered())
            // VPIN
            .vpin(source.getVpin())
            .vpinBucketSize(source.getVpinBucketSize())
            .vpinBucketCount(source.getVpinBucketCount())
            // Orderbook metrics (latest values)
            .orderbookPresent(source.isOrderbookPresent())
            .orderbookDataTimestamp(source.getOrderbookDataTimestamp())
            // 🔴 BUG-013 FIX: Add missing orderbook fields
            .isOrderbookFallback(source.getIsOrderbookFallback())
            .ofi(source.getOfi())
            .kyleLambda(source.getKyleLambda())
            .microprice(source.getMicroprice())
            .bidAskSpread(source.getBidAskSpread())
            .depthImbalance(source.getDepthImbalance())
            .weightedDepthImbalance(source.getWeightedDepthImbalance())
            .averageBidDepth(source.getAverageBidDepth())
            .averageAskDepth(source.getAverageAskDepth())
            .spoofingCount(source.getSpoofingCount())
            .icebergBidDetected(source.getIcebergBidDetected())
            .icebergAskDetected(source.getIcebergAskDetected())
            // Orderbook depth fragmentation
            .totalBidOrders(source.getTotalBidOrders())
            .totalAskOrders(source.getTotalAskOrders())
            .ordersAtBestBid(source.getOrdersAtBestBid())
            .ordersAtBestAsk(source.getOrdersAtBestAsk())
            .avgBidOrderSize(source.getAvgBidOrderSize())
            .avgAskOrderSize(source.getAvgAskOrderSize())
            .depthConcentration(source.getDepthConcentration())
            .maxDepthLevels(source.getMaxDepthLevels())
            .icebergAtBestBid(source.getIcebergAtBestBid())
            .icebergAtBestAsk(source.getIcebergAtBestAsk())
            .orderbookUpdateCount(source.getOrderbookUpdateCount())
            // Order cancellation rate
            .cancelRate(source.getCancelRate())
            .totalOrdersCancelled(source.getTotalOrdersCancelled())
            .totalOrdersObserved(source.getTotalOrdersObserved())
            // OFI Momentum
            .ofiMomentum(source.getOfiMomentum())
            .averageOfiMomentum(source.getAverageOfiMomentum())
            // Market depth slope
            .bidDepthSlope(source.getBidDepthSlope())
            .askDepthSlope(source.getAskDepthSlope())
            // OI
            .oiPresent(source.isOiPresent())
            // 🔴 BUG-013 FIX: Add missing OI fields
            .oiDataTimestamp(source.getOiDataTimestamp())
            .isOIFallback(source.getIsOIFallback())
            .openInterest(source.getOpenInterest())
            .oiOpen(source.getOiOpen())
            .oiHigh(source.getOiHigh())
            .oiLow(source.getOiLow())
            .oiClose(source.getOiClose())
            .oiChange(source.getOiChange())
            .oiChangePercent(source.getOiChangePercent())
            // OI Correlation
            .priceAtOIUpdate(source.getPriceAtOIUpdate())
            .volumeAtOIUpdate(source.getVolumeAtOIUpdate())
            .spreadAtOIUpdate(source.getSpreadAtOIUpdate())
            .oiUpdateLatency(source.getOiUpdateLatency())
            .oiUpdateCount(source.getOiUpdateCount())
            // OI Velocity
            .oiVelocity(source.getOiVelocity())
            .oiAcceleration(source.getOiAcceleration())
            // Quality
            .quality(source.getQuality())
            .qualityReason(source.getQualityReason())
            // Previous close
            .previousClose(source.getPreviousClose())
            .overnightGap(source.getOvernightGap())
            .isGapUp(source.getIsGapUp())
            .isGapDown(source.getIsGapDown())
            // ========== VWAP BANDS (Trading Signals) ==========
            .vwapUpperBand(source.getVwapUpperBand())
            .vwapLowerBand(source.getVwapLowerBand())
            .vwapStdDev(source.getVwapStdDev())
            .vwapSignal(source.getVwapSignal())
            // ========== VWAP VALIDATION ==========
            .exchangeVwap(source.getExchangeVwap())
            .vwapDrift(source.getVwapDrift())
            // ========== VOLUME PROFILE ==========
            .volumeAtPrice(source.getVolumeAtPrice() != null ? new java.util.HashMap<>(source.getVolumeAtPrice()) : null)
            .poc(source.getPoc())
            .vah(source.getVah())
            .val(source.getVal())
            // ========== TICK-LEVEL SPREAD METRICS ==========
            .averageTickSpread(source.getAverageTickSpread())
            .minTickSpread(source.getMinTickSpread())
            .maxTickSpread(source.getMaxTickSpread())
            .spreadVolatilityTick(source.getSpreadVolatilityTick())
            .tightSpreadPercent(source.getTightSpreadPercent())
            // ========== EFFECTIVE SPREAD (Execution Cost) ==========
            .averageEffectiveSpread(source.getAverageEffectiveSpread())
            .minEffectiveSpread(source.getMinEffectiveSpread())
            .maxEffectiveSpread(source.getMaxEffectiveSpread())
            .priceImprovementRatio(source.getPriceImprovementRatio())
            // ========== TICK BID/ASK IMBALANCE ==========
            .tickBidAskImbalance(source.getTickBidAskImbalance())
            .sumTotalBidQty(source.getSumTotalBidQty())
            .sumTotalOffQty(source.getSumTotalOffQty())
            // ========== TICK INTENSITY ZONES (Algo Detection) ==========
            .maxTicksInAnySecond(source.getMaxTicksInAnySecond())
            .secondsWithTicks(source.getSecondsWithTicks())
            .tickBurstRatio(source.getTickBurstRatio())
            .algoActivityDetected(source.getAlgoActivityDetected())
            // ========== TEMPORAL METRICS ==========
            .firstTickTimestamp(source.getFirstTickTimestamp())
            .lastTickTimestamp(source.getLastTickTimestamp())
            .minTickGap(source.getMinTickGap())
            .maxTickGap(source.getMaxTickGap())
            .avgTickGap(source.getAvgTickGap())
            .ticksPerSecond(source.getTicksPerSecond())
            .tickAcceleration(source.getTickAcceleration())
            // ========== TRADE SIZE DISTRIBUTION ==========
            .maxTradeSize(source.getMaxTradeSize())
            .minTradeSize(source.getMinTradeSize())
            .avgTradeSize(source.getAvgTradeSize())
            .medianTradeSize(source.getMedianTradeSize())
            .largeTradeCount(source.getLargeTradeCount())
            .priceImpactPerUnit(source.getPriceImpactPerUnit())
            // ========== SUB-CANDLE SNAPSHOTS (MTF Distribution) ==========
            .subCandleSnapshots(source.getSubCandleSnapshots() != null ? new java.util.ArrayList<>(source.getSubCandleSnapshots()) : null)
            // ========== ORDERBOOK DEPTH ANALYSIS ==========
            .levelWeightedBidDepth(source.getLevelWeightedBidDepth())
            .levelWeightedAskDepth(source.getLevelWeightedAskDepth())
            .levelWeightedImbalance(source.getLevelWeightedImbalance())
            .bidFragmentation(source.getBidFragmentation())
            .askFragmentation(source.getAskFragmentation())
            .institutionalBias(source.getInstitutionalBias())
            // ========== SPREAD DYNAMICS ==========
            .spreadVolatility(source.getSpreadVolatility())
            .maxSpread(source.getMaxSpread())
            .minSpread(source.getMinSpread())
            .spreadChangeRate(source.getSpreadChangeRate())
            .orderbookMomentum(source.getOrderbookMomentum())
            // ========== LATENCY TRACKING ==========
            .processingLatencyMs(source.getProcessingLatencyMs())
            .maxTickAgeMs(source.getMaxTickAgeMs())
            .minTickAgeMs(source.getMinTickAgeMs())
            // 🔴 BUG-013 FIX: Add cross-stream latency fields
            .tickToOrderbookLatency(source.getTickToOrderbookLatency())
            .tickToOILatency(source.getTickToOILatency())
            .tickStale(source.getTickStale())
            .orderbookStale(source.getOrderbookStale())
            .oiStale(source.getOiStale())
            .maxDataAge(source.getMaxDataAge())
            .stalenessReason(source.getStalenessReason())
            // ========== OPTIONS SPECIFIC (for OptionCandle conversion) ==========
            .strikePrice(source.getStrikePrice())
            .optionType(source.getOptionType())
            .expiry(source.getExpiry())
            .daysToExpiry(source.getDaysToExpiry())
            .hoursToExpiry(source.getHoursToExpiry())
            .isNearExpiry(source.getIsNearExpiry())
            // Options Greeks
            .delta(source.getDelta())
            .gamma(source.getGamma())
            .vega(source.getVega())
            .theta(source.getTheta())
            .impliedVolatility(source.getImpliedVolatility())
            .build();
    }

    /**
     * Merge OHLCV and microstructure data from incoming candle into aggregate
     * RULES:
     * - OHLCV: Open=first, High=max, Low=min, Close=last, Volume=sum
     * - Imbalance: SUM (cumulative imbalance over window)
     * - Orderbook: Latest values (point-in-time snapshot)
     * - VPIN: Volume-weighted or latest
     * - Triggers: OR'd (any trigger in window = triggered)
     */
    private void mergeInstrumentCandle(
            com.kotsin.consumer.domain.model.InstrumentCandle aggregate,
            com.kotsin.consumer.domain.model.InstrumentCandle incoming) {

        // ===== OHLCV AGGREGATION =====
        // Open stays from first candle (aggregate already has it)
        // High = max(aggregate.high, incoming.high)
        aggregate.setHigh(Math.max(aggregate.getHigh(), incoming.getHigh()));
        // Low = min(aggregate.low, incoming.low)
        aggregate.setLow(Math.min(aggregate.getLow(), incoming.getLow()));
        // Close = incoming.close (last candle's close)
        aggregate.setClose(incoming.getClose());
        // Volume = sum
        aggregate.setVolume(aggregate.getVolume() + incoming.getVolume());
        aggregate.setBuyVolume(aggregate.getBuyVolume() + incoming.getBuyVolume());
        aggregate.setSellVolume(aggregate.getSellVolume() + incoming.getSellVolume());
        aggregate.setTickCount(aggregate.getTickCount() + incoming.getTickCount());

        // Update window end time to latest
        aggregate.setWindowEndMillis(incoming.getWindowEndMillis());
        // 🔴 FIX: Don't set humanReadableTime during merge - it will be set correctly
        // at the end based on the corrected windowStartMillis in updateHumanReadableTime()
        // REMOVED: aggregate.setHumanReadableTime(incoming.getHumanReadableTime());

        // ===== VOLUME CLASSIFICATION (SUM) =====
        // FIX: Null-safe aggregation for volume classification fields
        Long aggBuyVol = aggregate.getAggressiveBuyVolume();
        Long incBuyVol = incoming.getAggressiveBuyVolume();
        aggregate.setAggressiveBuyVolume((aggBuyVol != null ? aggBuyVol : 0L) + (incBuyVol != null ? incBuyVol : 0L));

        Long aggSellVol = aggregate.getAggressiveSellVolume();
        Long incSellVol = incoming.getAggressiveSellVolume();
        aggregate.setAggressiveSellVolume((aggSellVol != null ? aggSellVol : 0L) + (incSellVol != null ? incSellVol : 0L));

        Long aggMidVol = aggregate.getMidpointVolume();
        Long incMidVol = incoming.getMidpointVolume();
        aggregate.setMidpointVolume((aggMidVol != null ? aggMidVol : 0L) + (incMidVol != null ? incMidVol : 0L));

        // Recalculate buy/sell pressure from aggregated volumes
        long totalVol = aggregate.getVolume();
        if (totalVol > 0) {
            aggregate.setBuyPressure((double) aggregate.getBuyVolume() / totalVol);
            aggregate.setSellPressure((double) aggregate.getSellVolume() / totalVol);
        }

        // 🔴 BUG-006 FIX: classificationReliability volume-weighted average
        // BEFORE: Not aggregated at all (only cloned in initial candle)
        // AFTER: Volume-weighted average for proper aggregation
        if (incoming.getClassificationReliability() != null && incoming.getVolume() > 0) {
            Double aggRel = aggregate.getClassificationReliability();
            long prevVol = aggregate.getVolume() - incoming.getVolume();
            if (aggRel != null && prevVol > 0 && aggregate.getVolume() > 0) {
                double weighted = (aggRel * prevVol + incoming.getClassificationReliability() * incoming.getVolume())
                                  / aggregate.getVolume();
                aggregate.setClassificationReliability(weighted);
            } else {
                aggregate.setClassificationReliability(incoming.getClassificationReliability());
            }
        }

        // ===== IMBALANCE METRICS (SUM - cumulative imbalance) =====
        // FIX: Null-safe aggregation for imbalance metrics
        Long aggVolImb = aggregate.getVolumeImbalance();
        Long incVolImb = incoming.getVolumeImbalance();
        aggregate.setVolumeImbalance((aggVolImb != null ? aggVolImb : 0L) + (incVolImb != null ? incVolImb : 0L));

        Double aggDolImb = aggregate.getDollarImbalance();
        Double incDolImb = incoming.getDollarImbalance();
        aggregate.setDollarImbalance((aggDolImb != null ? aggDolImb : 0.0) + (incDolImb != null ? incDolImb : 0.0));

        Integer aggTickRuns = aggregate.getTickRuns();
        Integer incTickRuns = incoming.getTickRuns();
        aggregate.setTickRuns((aggTickRuns != null ? aggTickRuns : 0) + (incTickRuns != null ? incTickRuns : 0));

        Long aggVolRuns = aggregate.getVolumeRuns();
        Long incVolRuns = incoming.getVolumeRuns();
        aggregate.setVolumeRuns((aggVolRuns != null ? aggVolRuns : 0L) + (incVolRuns != null ? incVolRuns : 0L));

        // Imbalance triggers = OR'd (any trigger in window = triggered)
        aggregate.setVibTriggered(aggregate.isVibTriggered() || incoming.isVibTriggered());
        aggregate.setDibTriggered(aggregate.isDibTriggered() || incoming.isDibTriggered());
        aggregate.setTrbTriggered(aggregate.isTrbTriggered() || incoming.isTrbTriggered());
        aggregate.setVrbTriggered(aggregate.isVrbTriggered() || incoming.isVrbTriggered());

        // ===== VPIN (volume-weighted or latest if no volume) =====
        // Use latest VPIN value (more accurate than averaging)
        if (incoming.getVpin() > 0) {
            aggregate.setVpin(incoming.getVpin());
        }
        // FIX: Null-safe aggregation for vpinBucketCount
        Integer aggVpinBucket = aggregate.getVpinBucketCount();
        Integer incVpinBucket = incoming.getVpinBucketCount();
        aggregate.setVpinBucketCount((aggVpinBucket != null ? aggVpinBucket : 0) + (incVpinBucket != null ? incVpinBucket : 0));

        // ===== ORDERBOOK METRICS (latest values - point-in-time snapshot) =====
        if (incoming.isOrderbookPresent()) {
            aggregate.setOrderbookPresent(true);
            aggregate.setOrderbookDataTimestamp(incoming.getOrderbookDataTimestamp());
            aggregate.setMicroprice(incoming.getMicroprice());
            aggregate.setBidAskSpread(incoming.getBidAskSpread());
            aggregate.setDepthImbalance(incoming.getDepthImbalance());
            aggregate.setAverageBidDepth(incoming.getAverageBidDepth());
            aggregate.setAverageAskDepth(incoming.getAverageAskDepth());
            aggregate.setTotalBidOrders(incoming.getTotalBidOrders());
            aggregate.setTotalAskOrders(incoming.getTotalAskOrders());
        }
        // OFI = SUM (cumulative order flow imbalance)
        if (incoming.getOfi() != null) {
            Double aggOfi = aggregate.getOfi();
            aggregate.setOfi((aggOfi != null ? aggOfi : 0.0) + incoming.getOfi());
        }
        // Kyle Lambda = latest (point-in-time price impact)
        aggregate.setKyleLambda(incoming.getKyleLambda());
        // Orderbook update count = SUM
        // FIX: Null-safe aggregation for orderbookUpdateCount
        Integer aggObCount = aggregate.getOrderbookUpdateCount();
        Integer incObCount = incoming.getOrderbookUpdateCount();
        aggregate.setOrderbookUpdateCount((aggObCount != null ? aggObCount : 0) + (incObCount != null ? incObCount : 0));

        // ===== OI AGGREGATION =====
        if (incoming.isOiPresent()) {
            aggregate.setOiPresent(true);
            // 🔴 BUG-007 FIX: OI Open - explicitly preserve first non-null value
            // BEFORE: Only a comment said "stays from first" but didn't handle null case
            // AFTER: Explicitly set oiOpen if aggregate's is null
            if (aggregate.getOiOpen() == null && incoming.getOiOpen() != null) {
                aggregate.setOiOpen(incoming.getOiOpen());
            }
            // OI High = max
            if (incoming.getOiHigh() != null) {
                Long aggHigh = aggregate.getOiHigh();
                aggregate.setOiHigh(aggHigh != null ? Math.max(aggHigh, incoming.getOiHigh()) : incoming.getOiHigh());
            }
            // OI Low = min
            if (incoming.getOiLow() != null) {
                Long aggLow = aggregate.getOiLow();
                aggregate.setOiLow(aggLow != null ? Math.min(aggLow, incoming.getOiLow()) : incoming.getOiLow());
            }
            // OI Close = latest
            aggregate.setOiClose(incoming.getOiClose());
            aggregate.setOpenInterest(incoming.getOpenInterest());
            // OI Change = sum
            if (incoming.getOiChange() != null) {
                Long aggChange = aggregate.getOiChange();
                aggregate.setOiChange((aggChange != null ? aggChange : 0L) + incoming.getOiChange());
            }
            // OI Velocity = latest
            aggregate.setOiVelocity(incoming.getOiVelocity());
            // 🔴 BUG-007 FIX: Also preserve OI-related timestamps and metadata
            if (incoming.getOiDataTimestamp() != null) {
                aggregate.setOiDataTimestamp(incoming.getOiDataTimestamp());
            }
            if (incoming.getIsOIFallback() != null) {
                aggregate.setIsOIFallback(incoming.getIsOIFallback());
            }
            // OI Update Count = SUM
            if (incoming.getOiUpdateCount() != null) {
                Integer aggCount = aggregate.getOiUpdateCount();
                aggregate.setOiUpdateCount((aggCount != null ? aggCount : 0) + incoming.getOiUpdateCount());
            }
            // OI Update Latency = MAX (worst case)
            if (incoming.getOiUpdateLatency() != null) {
                Long aggLatency = aggregate.getOiUpdateLatency();
                aggregate.setOiUpdateLatency(aggLatency != null ? Math.max(aggLatency, incoming.getOiUpdateLatency()) : incoming.getOiUpdateLatency());
            }
            // OI correlation metrics - use LATEST
            if (incoming.getPriceAtOIUpdate() != null) {
                aggregate.setPriceAtOIUpdate(incoming.getPriceAtOIUpdate());
            }
            if (incoming.getVolumeAtOIUpdate() != null) {
                aggregate.setVolumeAtOIUpdate(incoming.getVolumeAtOIUpdate());
            }
            if (incoming.getSpreadAtOIUpdate() != null) {
                aggregate.setSpreadAtOIUpdate(incoming.getSpreadAtOIUpdate());
            }
        }

        // ===== 🔴 BUG-001 FIX: VWAP (volume-weighted average) =====
        // BEFORE: aggregate.setVwap(incoming.getVwap()); // WRONG - used LATEST
        // AFTER: Proper volume-weighted aggregation
        // VWAP = Σ(Price × Volume) / Σ(Volume)
        // We approximate using: (aggVwap × prevVol + incVwap × incVol) / totalVol
        if (incoming.getVwap() > 0 && incoming.getVolume() > 0) {
            long prevVolume = aggregate.getVolume() - incoming.getVolume(); // volume before this merge
            double aggVwap = aggregate.getVwap();
            if (aggVwap > 0 && prevVolume > 0 && aggregate.getVolume() > 0) {
                // Volume-weighted average
                double aggregatePV = aggVwap * prevVolume;
                double incomingPV = incoming.getVwap() * incoming.getVolume();
                double newVwap = (aggregatePV + incomingPV) / aggregate.getVolume();
                aggregate.setVwap(newVwap);
            } else if (incoming.getVwap() > 0) {
                // First valid VWAP
                aggregate.setVwap(incoming.getVwap());
            }
        }

        // ===== 🔴 BUG-003 FIX: QUALITY (worst case) =====
        // BEFORE: Used LATEST quality (WRONG)
        // AFTER: Use WORST quality among all candles in window
        // Higher ordinal = worse quality: VALID(0) < WARNING(1) < CONFLICT(2) < STALE(3) < INSUFFICIENT(4)
        if (incoming.getQuality() != null) {
            com.kotsin.consumer.domain.model.DataQuality aggQuality = aggregate.getQuality();
            com.kotsin.consumer.domain.model.DataQuality incQuality = incoming.getQuality();
            if (aggQuality == null || incQuality.ordinal() > aggQuality.ordinal()) {
                aggregate.setQuality(incQuality);
                aggregate.setQualityReason(incoming.getQualityReason());
            }
        }

        // ===== 🔴 BUG-004 FIX: VWAP BANDS (recalculate relative to aggregated VWAP) =====
        // BEFORE: Used LATEST bands (WRONG - caused inverted bands where upper < VWAP)
        // AFTER: Recalculate bands based on aggregated VWAP and stdDev
        // The incoming bands are relative to incoming VWAP, not aggregated VWAP!
        if (incoming.getVwapStdDev() != null && incoming.getVwapStdDev() > 0) {
            aggregate.setVwapStdDev(incoming.getVwapStdDev());
        }

        // Recalculate bands relative to aggregated VWAP (not incoming VWAP)
        Double aggVwap = aggregate.getVwap();
        Double aggStdDev = aggregate.getVwapStdDev();
        if (aggVwap != null && aggVwap > 0 && aggStdDev != null && aggStdDev > 0) {
            // Standard VWAP bands: VWAP ± (2 * stdDev)
            double multiplier = 2.0;  // Standard 2-sigma bands
            double upperBand = aggVwap + (multiplier * aggStdDev);
            double lowerBand = aggVwap - (multiplier * aggStdDev);

            // Validate bands make sense (upper > VWAP > lower)
            if (upperBand > aggVwap && lowerBand < aggVwap) {
                aggregate.setVwapUpperBand(upperBand);
                aggregate.setVwapLowerBand(lowerBand);
            } else {
                // Fallback to incoming bands if calculation is invalid
                if (incoming.getVwapUpperBand() != null) {
                    aggregate.setVwapUpperBand(incoming.getVwapUpperBand());
                }
                if (incoming.getVwapLowerBand() != null) {
                    aggregate.setVwapLowerBand(incoming.getVwapLowerBand());
                }
            }
        } else {
            // No stdDev available, use incoming bands as fallback
            if (incoming.getVwapUpperBand() != null) {
                aggregate.setVwapUpperBand(incoming.getVwapUpperBand());
            }
            if (incoming.getVwapLowerBand() != null) {
                aggregate.setVwapLowerBand(incoming.getVwapLowerBand());
            }
        }

        // Update VWAP signal based on current close vs aggregated VWAP/bands
        updateVwapSignal(aggregate);

        // ===== VWAP VALIDATION (use LATEST) =====
        if (incoming.getExchangeVwap() != null) {
            aggregate.setExchangeVwap(incoming.getExchangeVwap());
        }
        if (incoming.getVwapDrift() != null) {
            aggregate.setVwapDrift(incoming.getVwapDrift());
        }

        // ===== VOLUME PROFILE (MERGE maps) =====
        // Merge volume-at-price histograms for accurate POC/VAH/VAL
        if (incoming.getVolumeAtPrice() != null && !incoming.getVolumeAtPrice().isEmpty()) {
            java.util.Map<Double, Long> aggVap = aggregate.getVolumeAtPrice();
            if (aggVap == null) {
                aggregate.setVolumeAtPrice(new java.util.HashMap<>(incoming.getVolumeAtPrice()));
            } else {
                for (java.util.Map.Entry<Double, Long> entry : incoming.getVolumeAtPrice().entrySet()) {
                    aggVap.merge(entry.getKey(), entry.getValue(), Long::sum);
                }
            }
            // 🔴 BUG-002 FIX: Recalculate POC/VAH/VAL from merged volume profile
            // BEFORE: Used LATEST POC/VAH/VAL (WRONG - loses accuracy)
            // AFTER: Recalculate from merged volumeAtPrice map
            recalculateVolumeProfileMetrics(aggregate);
        } else {
            // Fallback to incoming values if no VAP data
            if (incoming.getPoc() != null) {
                aggregate.setPoc(incoming.getPoc());
            }
            if (incoming.getVah() != null) {
                aggregate.setVah(incoming.getVah());
            }
            if (incoming.getVal() != null) {
                aggregate.setVal(incoming.getVal());
            }
        }

        // ===== TICK-LEVEL SPREAD METRICS (WEIGHTED AVERAGE / MIN / MAX) =====
        // Average spread: weighted by tick count for proper aggregation
        if (incoming.getAverageTickSpread() != null && incoming.getTickCount() > 0) {
            Double aggSpread = aggregate.getAverageTickSpread();
            int aggTicks = aggregate.getTickCount() - incoming.getTickCount(); // ticks before this merge
            if (aggSpread != null && aggTicks > 0) {
                // Weighted average: (aggSpread * aggTicks + incSpread * incTicks) / totalTicks
                double weighted = (aggSpread * aggTicks + incoming.getAverageTickSpread() * incoming.getTickCount())
                                  / aggregate.getTickCount();
                aggregate.setAverageTickSpread(weighted);
            } else {
                aggregate.setAverageTickSpread(incoming.getAverageTickSpread());
            }
        }
        // Min/Max spread
        if (incoming.getMinTickSpread() != null) {
            Double aggMin = aggregate.getMinTickSpread();
            aggregate.setMinTickSpread(aggMin != null ? Math.min(aggMin, incoming.getMinTickSpread()) : incoming.getMinTickSpread());
        }
        if (incoming.getMaxTickSpread() != null) {
            Double aggMax = aggregate.getMaxTickSpread();
            aggregate.setMaxTickSpread(aggMax != null ? Math.max(aggMax, incoming.getMaxTickSpread()) : incoming.getMaxTickSpread());
        }
        // Spread volatility - use LATEST (point-in-time) - would need variance tracking for proper aggregation
        if (incoming.getSpreadVolatilityTick() != null) {
            aggregate.setSpreadVolatilityTick(incoming.getSpreadVolatilityTick());
        }
        // 🔴 BUG-022 FIX: Tight spread percent - tick-weighted average
        // BEFORE: Used LATEST (lost accuracy across window)
        // AFTER: Tick-weighted average
        if (incoming.getTightSpreadPercent() != null && incoming.getTickCount() > 0) {
            Double aggTsp = aggregate.getTightSpreadPercent();
            int prevTicks = aggregate.getTickCount() - incoming.getTickCount();
            if (aggTsp != null && prevTicks > 0 && aggregate.getTickCount() > 0) {
                double weighted = (aggTsp * prevTicks + incoming.getTightSpreadPercent() * incoming.getTickCount())
                                  / aggregate.getTickCount();
                aggregate.setTightSpreadPercent(weighted);
            } else {
                aggregate.setTightSpreadPercent(incoming.getTightSpreadPercent());
            }
        }

        // ===== EFFECTIVE SPREAD (WEIGHTED AVERAGE / MIN / MAX) =====
        if (incoming.getAverageEffectiveSpread() != null && incoming.getTickCount() > 0) {
            Double aggEffSpread = aggregate.getAverageEffectiveSpread();
            int aggTicks = aggregate.getTickCount() - incoming.getTickCount();
            if (aggEffSpread != null && aggTicks > 0) {
                double weighted = (aggEffSpread * aggTicks + incoming.getAverageEffectiveSpread() * incoming.getTickCount())
                                  / aggregate.getTickCount();
                aggregate.setAverageEffectiveSpread(weighted);
            } else {
                aggregate.setAverageEffectiveSpread(incoming.getAverageEffectiveSpread());
            }
        }
        if (incoming.getMinEffectiveSpread() != null) {
            Double aggMin = aggregate.getMinEffectiveSpread();
            aggregate.setMinEffectiveSpread(aggMin != null ? Math.min(aggMin, incoming.getMinEffectiveSpread()) : incoming.getMinEffectiveSpread());
        }
        if (incoming.getMaxEffectiveSpread() != null) {
            Double aggMax = aggregate.getMaxEffectiveSpread();
            aggregate.setMaxEffectiveSpread(aggMax != null ? Math.max(aggMax, incoming.getMaxEffectiveSpread()) : incoming.getMaxEffectiveSpread());
        }
        if (incoming.getPriceImprovementRatio() != null) {
            aggregate.setPriceImprovementRatio(incoming.getPriceImprovementRatio());
        }

        // ===== TICK BID/ASK IMBALANCE (SUM for quantities, recalculate imbalance) =====
        if (incoming.getSumTotalBidQty() != null) {
            Long aggBid = aggregate.getSumTotalBidQty();
            aggregate.setSumTotalBidQty((aggBid != null ? aggBid : 0L) + incoming.getSumTotalBidQty());
        }
        if (incoming.getSumTotalOffQty() != null) {
            Long aggOff = aggregate.getSumTotalOffQty();
            aggregate.setSumTotalOffQty((aggOff != null ? aggOff : 0L) + incoming.getSumTotalOffQty());
        }
        // Recalculate tick bid/ask imbalance from aggregated quantities
        Long totalBid = aggregate.getSumTotalBidQty();
        Long totalOff = aggregate.getSumTotalOffQty();
        if (totalBid != null && totalOff != null && (totalBid + totalOff) > 0) {
            aggregate.setTickBidAskImbalance((double)(totalBid - totalOff) / (totalBid + totalOff));
        }

        // ===== TICK INTENSITY ZONES (MAX for peaks, SUM for counts) =====
        if (incoming.getMaxTicksInAnySecond() != null) {
            Integer aggMax = aggregate.getMaxTicksInAnySecond();
            aggregate.setMaxTicksInAnySecond(aggMax != null ? Math.max(aggMax, incoming.getMaxTicksInAnySecond()) : incoming.getMaxTicksInAnySecond());
        }
        if (incoming.getSecondsWithTicks() != null) {
            Integer aggSec = aggregate.getSecondsWithTicks();
            aggregate.setSecondsWithTicks((aggSec != null ? aggSec : 0) + incoming.getSecondsWithTicks());
        }
        // Recalculate tick burst ratio from aggregated data
        Integer maxTicks = aggregate.getMaxTicksInAnySecond();
        Integer totalSeconds = aggregate.getSecondsWithTicks();
        if (maxTicks != null && totalSeconds != null && totalSeconds > 0) {
            double avgTicksPerSec = (double) aggregate.getTickCount() / totalSeconds;
            aggregate.setTickBurstRatio(avgTicksPerSec > 0 ? maxTicks / avgTicksPerSec : 0.0);
            aggregate.setAlgoActivityDetected(aggregate.getTickBurstRatio() > 3.0);
        }

        // ===== TEMPORAL METRICS (MIN/MAX/SUM as appropriate) =====
        // First/Last timestamp - track window boundaries
        if (incoming.getFirstTickTimestamp() != null) {
            Long aggFirst = aggregate.getFirstTickTimestamp();
            aggregate.setFirstTickTimestamp(aggFirst != null ? Math.min(aggFirst, incoming.getFirstTickTimestamp()) : incoming.getFirstTickTimestamp());
        }
        if (incoming.getLastTickTimestamp() != null) {
            Long aggLast = aggregate.getLastTickTimestamp();
            aggregate.setLastTickTimestamp(aggLast != null ? Math.max(aggLast, incoming.getLastTickTimestamp()) : incoming.getLastTickTimestamp());
        }
        // Min/Max tick gap
        if (incoming.getMinTickGap() != null) {
            Long aggMin = aggregate.getMinTickGap();
            aggregate.setMinTickGap(aggMin != null ? Math.min(aggMin, incoming.getMinTickGap()) : incoming.getMinTickGap());
        }
        if (incoming.getMaxTickGap() != null) {
            Long aggMax = aggregate.getMaxTickGap();
            aggregate.setMaxTickGap(aggMax != null ? Math.max(aggMax, incoming.getMaxTickGap()) : incoming.getMaxTickGap());
        }
        // 🔴 BUG-005 FIX: Avg tick gap - CALCULATE from temporal boundaries, not LATEST
        // BEFORE: aggregate.setAvgTickGap(incoming.getAvgTickGap()); // WRONG - used LATEST
        // AFTER: avgTickGap = (lastTickTimestamp - firstTickTimestamp) / (tickCount - 1)
        Long firstTs = aggregate.getFirstTickTimestamp();
        Long lastTs = aggregate.getLastTickTimestamp();
        int ticks = aggregate.getTickCount();
        if (firstTs != null && lastTs != null && ticks > 1) {
            aggregate.setAvgTickGap((double)(lastTs - firstTs) / (ticks - 1));
        } else if (incoming.getAvgTickGap() != null) {
            // Fallback to incoming if we can't calculate
            aggregate.setAvgTickGap(incoming.getAvgTickGap());
        }
        // 🔴 BUG-016 FIX: Ticks per second - use Math.round instead of Math.ceil
        // BEFORE: Math.ceil() overestimated (21% error for edge cases)
        // AFTER: Math.round() for better accuracy
        if (firstTs != null && lastTs != null && lastTs > firstTs) {
            double durationSec = (lastTs - firstTs) / 1000.0;
            aggregate.setTicksPerSecond(durationSec > 0 ? (int) Math.round((double) aggregate.getTickCount() / durationSec) : aggregate.getTickCount());
        }
        // Tick acceleration - use LATEST
        if (incoming.getTickAcceleration() != null) {
            aggregate.setTickAcceleration(incoming.getTickAcceleration());
        }

        // ===== TRADE SIZE DISTRIBUTION (MIN/MAX/SUM) =====
        if (incoming.getMaxTradeSize() != null) {
            Long aggMax = aggregate.getMaxTradeSize();
            aggregate.setMaxTradeSize(aggMax != null ? Math.max(aggMax, incoming.getMaxTradeSize()) : incoming.getMaxTradeSize());
        }
        if (incoming.getMinTradeSize() != null && incoming.getMinTradeSize() > 0) {
            Long aggMin = aggregate.getMinTradeSize();
            aggregate.setMinTradeSize(aggMin != null && aggMin > 0 ? Math.min(aggMin, incoming.getMinTradeSize()) : incoming.getMinTradeSize());
        }
        // Avg trade size - recalculate from volume/tick count
        if (aggregate.getTickCount() > 0) {
            aggregate.setAvgTradeSize((double) aggregate.getVolume() / aggregate.getTickCount());
        }
        // Median - use LATEST (approximation)
        if (incoming.getMedianTradeSize() != null) {
            aggregate.setMedianTradeSize(incoming.getMedianTradeSize());
        }
        // Large trade count - SUM
        if (incoming.getLargeTradeCount() != null) {
            Integer aggCount = aggregate.getLargeTradeCount();
            aggregate.setLargeTradeCount((aggCount != null ? aggCount : 0) + incoming.getLargeTradeCount());
        }
        // Price impact per unit - use LATEST
        if (incoming.getPriceImpactPerUnit() != null) {
            aggregate.setPriceImpactPerUnit(incoming.getPriceImpactPerUnit());
        }

        // ===== SUB-CANDLE SNAPSHOTS (MERGE lists with limit) =====
        // 🔴 BUG-024 FIX: Limit sub-candle snapshots to prevent unbounded growth
        // BEFORE: No limit - daily candles could have 2,250+ snapshots
        // AFTER: Limit to 100 snapshots (sample evenly if exceeds)
        if (incoming.getSubCandleSnapshots() != null && !incoming.getSubCandleSnapshots().isEmpty()) {
            java.util.List<com.kotsin.consumer.domain.model.InstrumentCandle.SubCandleSnapshot> aggSnapshots = aggregate.getSubCandleSnapshots();
            if (aggSnapshots == null) {
                aggregate.setSubCandleSnapshots(new java.util.ArrayList<>(incoming.getSubCandleSnapshots()));
            } else {
                aggSnapshots.addAll(incoming.getSubCandleSnapshots());
                // Limit to max 100 snapshots - keep first, last, and evenly sampled middle
                final int MAX_SNAPSHOTS = 100;
                if (aggSnapshots.size() > MAX_SNAPSHOTS) {
                    java.util.List<com.kotsin.consumer.domain.model.InstrumentCandle.SubCandleSnapshot> sampled =
                        new java.util.ArrayList<>(MAX_SNAPSHOTS);
                    int step = aggSnapshots.size() / (MAX_SNAPSHOTS - 2);
                    // Always keep first
                    sampled.add(aggSnapshots.get(0));
                    // Sample middle
                    for (int i = step; i < aggSnapshots.size() - 1 && sampled.size() < MAX_SNAPSHOTS - 1; i += step) {
                        sampled.add(aggSnapshots.get(i));
                    }
                    // Always keep last
                    sampled.add(aggSnapshots.get(aggSnapshots.size() - 1));
                    aggregate.setSubCandleSnapshots(sampled);
                }
            }
        }

        // ===== ORDERBOOK DEPTH ANALYSIS (use LATEST for point-in-time) =====
        if (incoming.getLevelWeightedBidDepth() != null) {
            aggregate.setLevelWeightedBidDepth(incoming.getLevelWeightedBidDepth());
        }
        if (incoming.getLevelWeightedAskDepth() != null) {
            aggregate.setLevelWeightedAskDepth(incoming.getLevelWeightedAskDepth());
        }
        if (incoming.getLevelWeightedImbalance() != null) {
            aggregate.setLevelWeightedImbalance(incoming.getLevelWeightedImbalance());
        }
        if (incoming.getBidFragmentation() != null) {
            aggregate.setBidFragmentation(incoming.getBidFragmentation());
        }
        if (incoming.getAskFragmentation() != null) {
            aggregate.setAskFragmentation(incoming.getAskFragmentation());
        }
        if (incoming.getInstitutionalBias() != null) {
            aggregate.setInstitutionalBias(incoming.getInstitutionalBias());
        }
        // 🔴 BUG-014 FIX: Add more orderbook depth fields
        if (incoming.getWeightedDepthImbalance() != null) {
            aggregate.setWeightedDepthImbalance(incoming.getWeightedDepthImbalance());
        }
        // Spoofing count = SUM
        if (incoming.getSpoofingCount() != null) {
            Integer aggCount = aggregate.getSpoofingCount();
            aggregate.setSpoofingCount((aggCount != null ? aggCount : 0) + incoming.getSpoofingCount());
        }
        // Iceberg detection = OR
        if (incoming.getIcebergBidDetected() != null && incoming.getIcebergBidDetected()) {
            aggregate.setIcebergBidDetected(true);
        }
        if (incoming.getIcebergAskDetected() != null && incoming.getIcebergAskDetected()) {
            aggregate.setIcebergAskDetected(true);
        }
        if (incoming.getIcebergAtBestBid() != null && incoming.getIcebergAtBestBid()) {
            aggregate.setIcebergAtBestBid(true);
        }
        if (incoming.getIcebergAtBestAsk() != null && incoming.getIcebergAtBestAsk()) {
            aggregate.setIcebergAtBestAsk(true);
        }
        // Latest orderbook structure
        if (incoming.getOrdersAtBestBid() != null) {
            aggregate.setOrdersAtBestBid(incoming.getOrdersAtBestBid());
        }
        if (incoming.getOrdersAtBestAsk() != null) {
            aggregate.setOrdersAtBestAsk(incoming.getOrdersAtBestAsk());
        }
        if (incoming.getAvgBidOrderSize() != null) {
            aggregate.setAvgBidOrderSize(incoming.getAvgBidOrderSize());
        }
        if (incoming.getAvgAskOrderSize() != null) {
            aggregate.setAvgAskOrderSize(incoming.getAvgAskOrderSize());
        }
        if (incoming.getDepthConcentration() != null) {
            aggregate.setDepthConcentration(incoming.getDepthConcentration());
        }
        if (incoming.getMaxDepthLevels() != null) {
            Integer aggMax = aggregate.getMaxDepthLevels();
            aggregate.setMaxDepthLevels(aggMax != null ? Math.max(aggMax, incoming.getMaxDepthLevels()) : incoming.getMaxDepthLevels());
        }
        // Depth slope (LATEST)
        if (incoming.getBidDepthSlope() != null) {
            aggregate.setBidDepthSlope(incoming.getBidDepthSlope());
        }
        if (incoming.getAskDepthSlope() != null) {
            aggregate.setAskDepthSlope(incoming.getAskDepthSlope());
        }
        // Order cancellation (SUM counts, recalculate rate)
        if (incoming.getTotalOrdersCancelled() != null) {
            Long aggCancelled = aggregate.getTotalOrdersCancelled();
            aggregate.setTotalOrdersCancelled((aggCancelled != null ? aggCancelled : 0L) + incoming.getTotalOrdersCancelled());
        }
        if (incoming.getTotalOrdersObserved() != null) {
            Long aggObserved = aggregate.getTotalOrdersObserved();
            aggregate.setTotalOrdersObserved((aggObserved != null ? aggObserved : 0L) + incoming.getTotalOrdersObserved());
        }
        // Recalculate cancel rate from aggregated counts
        Long totalCancelled = aggregate.getTotalOrdersCancelled();
        Long totalObserved = aggregate.getTotalOrdersObserved();
        if (totalCancelled != null && totalObserved != null && totalObserved > 0) {
            aggregate.setCancelRate((double) totalCancelled / totalObserved);
        }
        // OFI Momentum (LATEST)
        if (incoming.getOfiMomentum() != null) {
            aggregate.setOfiMomentum(incoming.getOfiMomentum());
        }
        if (incoming.getAverageOfiMomentum() != null) {
            aggregate.setAverageOfiMomentum(incoming.getAverageOfiMomentum());
        }

        // ===== SPREAD DYNAMICS (use LATEST / MAX) =====
        if (incoming.getSpreadVolatility() != null) {
            aggregate.setSpreadVolatility(incoming.getSpreadVolatility());
        }
        if (incoming.getMaxSpread() != null) {
            Double aggMax = aggregate.getMaxSpread();
            aggregate.setMaxSpread(aggMax != null ? Math.max(aggMax, incoming.getMaxSpread()) : incoming.getMaxSpread());
        }
        if (incoming.getMinSpread() != null) {
            Double aggMin = aggregate.getMinSpread();
            aggregate.setMinSpread(aggMin != null ? Math.min(aggMin, incoming.getMinSpread()) : incoming.getMinSpread());
        }
        if (incoming.getSpreadChangeRate() != null) {
            aggregate.setSpreadChangeRate(incoming.getSpreadChangeRate());
        }
        if (incoming.getOrderbookMomentum() != null) {
            aggregate.setOrderbookMomentum(incoming.getOrderbookMomentum());
        }

        // ===== LATENCY TRACKING (MAX for worst case) =====
        aggregate.setProcessingLatencyMs(Math.max(aggregate.getProcessingLatencyMs(), incoming.getProcessingLatencyMs()));
        aggregate.setMaxTickAgeMs(Math.max(aggregate.getMaxTickAgeMs(), incoming.getMaxTickAgeMs()));
        aggregate.setMinTickAgeMs(Math.min(aggregate.getMinTickAgeMs(), incoming.getMinTickAgeMs()));

        // 🔴 BUG-014 FIX: Add cross-stream latency aggregation
        // Tick-to-orderbook latency = MAX (worst case)
        if (incoming.getTickToOrderbookLatency() != null) {
            Long aggLatency = aggregate.getTickToOrderbookLatency();
            aggregate.setTickToOrderbookLatency(aggLatency != null ? Math.max(aggLatency, incoming.getTickToOrderbookLatency()) : incoming.getTickToOrderbookLatency());
        }
        // Tick-to-OI latency = MAX (worst case)
        if (incoming.getTickToOILatency() != null) {
            Long aggLatency = aggregate.getTickToOILatency();
            aggregate.setTickToOILatency(aggLatency != null ? Math.max(aggLatency, incoming.getTickToOILatency()) : incoming.getTickToOILatency());
        }
        // Staleness flags = OR (if any candle was stale, aggregated is stale)
        if (incoming.getTickStale() != null && incoming.getTickStale()) {
            aggregate.setTickStale(true);
        }
        if (incoming.getOrderbookStale() != null && incoming.getOrderbookStale()) {
            aggregate.setOrderbookStale(true);
        }
        if (incoming.getOiStale() != null && incoming.getOiStale()) {
            aggregate.setOiStale(true);
        }
        // Max data age = MAX
        if (incoming.getMaxDataAge() != null) {
            Long aggAge = aggregate.getMaxDataAge();
            aggregate.setMaxDataAge(aggAge != null ? Math.max(aggAge, incoming.getMaxDataAge()) : incoming.getMaxDataAge());
        }
        // Use latest staleness reason (most relevant)
        if (incoming.getStalenessReason() != null) {
            aggregate.setStalenessReason(incoming.getStalenessReason());
        }

        // ===== OPTIONS SPECIFIC FIELDS (for InstrumentCandles that are options) =====
        // These are point-in-time values - use LATEST
        if (incoming.getStrikePrice() != null) {
            aggregate.setStrikePrice(incoming.getStrikePrice());
        }
        if (incoming.getOptionType() != null) {
            aggregate.setOptionType(incoming.getOptionType());
        }
        if (incoming.getExpiry() != null) {
            aggregate.setExpiry(incoming.getExpiry());
        }
        if (incoming.getDaysToExpiry() != null) {
            aggregate.setDaysToExpiry(incoming.getDaysToExpiry());
        }
        if (incoming.getHoursToExpiry() != null) {
            aggregate.setHoursToExpiry(incoming.getHoursToExpiry());
        }
        if (incoming.getIsNearExpiry() != null) {
            aggregate.setIsNearExpiry(incoming.getIsNearExpiry());
        }
        // Options Greeks - LATEST (they're point-in-time)
        if (incoming.getDelta() != null) {
            aggregate.setDelta(incoming.getDelta());
        }
        if (incoming.getGamma() != null) {
            aggregate.setGamma(incoming.getGamma());
        }
        if (incoming.getVega() != null) {
            aggregate.setVega(incoming.getVega());
        }
        if (incoming.getTheta() != null) {
            aggregate.setTheta(incoming.getTheta());
        }
        if (incoming.getImpliedVolatility() != null) {
            aggregate.setImpliedVolatility(incoming.getImpliedVolatility());
        }
    }

    /**
     * 🔴 BUG-002 FIX: Recalculate POC/VAH/VAL from merged volume-at-price histogram
     *
     * POC (Point of Control) = Price level with maximum volume
     * VAH (Value Area High) = Upper bound of 70% volume area
     * VAL (Value Area Low) = Lower bound of 70% volume area
     */
    private void recalculateVolumeProfileMetrics(com.kotsin.consumer.domain.model.InstrumentCandle candle) {
        java.util.Map<Double, Long> vap = candle.getVolumeAtPrice();
        if (vap == null || vap.isEmpty()) return;

        // Find POC (price with maximum volume)
        double poc = 0.0;
        long maxVolume = 0;
        long totalVolume = 0;

        for (java.util.Map.Entry<Double, Long> entry : vap.entrySet()) {
            totalVolume += entry.getValue();
            if (entry.getValue() > maxVolume) {
                maxVolume = entry.getValue();
                poc = entry.getKey();
            }
        }
        candle.setPoc(poc);

        // Calculate Value Area (70% of total volume, centered around POC)
        if (totalVolume == 0) return;

        // Sort price levels
        java.util.List<Double> sortedPrices = new java.util.ArrayList<>(vap.keySet());
        java.util.Collections.sort(sortedPrices);

        // Find POC index
        int pocIndex = sortedPrices.indexOf(poc);
        if (pocIndex < 0) pocIndex = sortedPrices.size() / 2;

        // Expand outward from POC until 70% volume captured
        long targetVolume = (long)(totalVolume * 0.70);
        long capturedVolume = vap.getOrDefault(poc, 0L);
        int lowIndex = pocIndex;
        int highIndex = pocIndex;

        while (capturedVolume < targetVolume && (lowIndex > 0 || highIndex < sortedPrices.size() - 1)) {
            // Decide which direction to expand
            long volumeIfExpandLow = (lowIndex > 0) ? vap.getOrDefault(sortedPrices.get(lowIndex - 1), 0L) : 0;
            long volumeIfExpandHigh = (highIndex < sortedPrices.size() - 1) ? vap.getOrDefault(sortedPrices.get(highIndex + 1), 0L) : 0;

            if (volumeIfExpandLow >= volumeIfExpandHigh && lowIndex > 0) {
                lowIndex--;
                capturedVolume += volumeIfExpandLow;
            } else if (highIndex < sortedPrices.size() - 1) {
                highIndex++;
                capturedVolume += volumeIfExpandHigh;
            } else if (lowIndex > 0) {
                lowIndex--;
                capturedVolume += volumeIfExpandLow;
            } else {
                break;
            }
        }

        candle.setVal(sortedPrices.get(lowIndex));
        candle.setVah(sortedPrices.get(highIndex));

        log.trace("[VAP-RECALC] {} | POC={} VAL={} VAH={} ({}% of volume captured)",
            candle.getScripCode(), poc, candle.getVal(), candle.getVah(),
            String.format("%.1f", (double)capturedVolume / totalVolume * 100));
    }

    /**
     * Calculate actual day start time from the aggregated candle's first data point.
     * For daily candles, this gives us the actual market open time:
     * - NSE: 9:15 AM IST
     * - MCX Morning: 9:00 AM IST
     *
     * Falls back to the equity/future's windowStartMillis from the first merged candle.
     */
    private long calculateActualDayStart(FamilyCandle candle) {
        // Use the windowStartMillis from the primary instrument
        // This was set by the FIRST merged 1m candle
        InstrumentCandle primary = candle.getEquity();
        if (primary == null) {
            primary = candle.getFuture();
        }
        if (primary != null && primary.getWindowStartMillis() > 0) {
            // Align to market open time for that day
            ZonedDateTime zdt = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(primary.getWindowStartMillis()),
                IST
            );
            // Get start of that trading day based on exchange
            String exchange = primary.getExchange();
            LocalTime marketOpen = "M".equalsIgnoreCase(exchange) ?
                LocalTime.of(9, 0) :   // MCX opens at 9:00
                LocalTime.of(9, 15);   // NSE/BSE opens at 9:15
            return zdt.toLocalDate().atTime(marketOpen).atZone(IST).toInstant().toEpochMilli();
        }
        // Fallback to candle's windowStartMillis
        return candle.getWindowStartMillis();
    }

    /**
     * Calculate actual day end time from the aggregated candle's last data point.
     * For daily candles, this gives us the actual market close time:
     * - NSE: 3:30 PM IST
     * - MCX Evening: 11:30 PM IST
     *
     * Falls back to the equity/future's windowEndMillis from the last merged candle.
     */
    private long calculateActualDayEnd(FamilyCandle candle) {
        // Use the windowEndMillis from the primary instrument
        // This was updated by each merged 1m candle
        InstrumentCandle primary = candle.getEquity();
        if (primary == null) {
            primary = candle.getFuture();
        }
        if (primary != null && primary.getWindowEndMillis() > 0) {
            // For daily candles, use the actual last candle's end time
            return primary.getWindowEndMillis();
        }
        // Fallback to candle's windowEndMillis
        return candle.getWindowEndMillis();
    }

    /**
     * Update human readable time
     */
    private void updateHumanReadableTime(FamilyCandle candle) {
        try {
            ZonedDateTime zdt = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(candle.getWindowStartMillis()),
                IST
            );
            candle.setHumanReadableTime(zdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        } catch (Exception e) {
            // Ignore
        }
    }

    /**
     * Setup exception handling for a stream
     */
    private void setupExceptionHandling(KafkaStreams streams, String timeframe) {
        streams.setStateListener((newState, oldState) -> {
            log.debug("TimeframeAggregator[{}] state: {} -> {}", timeframe, oldState, newState);
            if (newState == KafkaStreams.State.ERROR) {
                log.error("❌ TimeframeAggregator[{}] entered ERROR state!", timeframe);
            }
        });

        streams.setUncaughtExceptionHandler(exception -> {
            log.error("Uncaught exception in TimeframeAggregator[{}]", timeframe, exception);
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });
    }

    /**
     * Update VWAP signal based on current close relative to VWAP and bands.
     * This ensures the signal is correct after recalculating VWAP bands.
     *
     * Signal mapping:
     * - ABOVE_UPPER: close > vwapUpperBand (overbought)
     * - ABOVE_VWAP:  close > vwap && close <= vwapUpperBand (bullish)
     * - BELOW_VWAP:  close < vwap && close >= vwapLowerBand (bearish)
     * - BELOW_LOWER: close < vwapLowerBand (oversold)
     */
    private void updateVwapSignal(com.kotsin.consumer.domain.model.InstrumentCandle candle) {
        if (candle == null) return;

        double close = candle.getClose();
        Double vwap = candle.getVwap();
        Double upperBand = candle.getVwapUpperBand();
        Double lowerBand = candle.getVwapLowerBand();

        if (vwap == null || vwap <= 0) {
            candle.setVwapSignal("NEUTRAL");
            return;
        }

        String signal;
        if (upperBand != null && close > upperBand) {
            signal = "ABOVE_UPPER";
        } else if (close > vwap) {
            signal = "ABOVE_VWAP";
        } else if (lowerBand != null && close < lowerBand) {
            signal = "BELOW_LOWER";
        } else if (close < vwap) {
            signal = "BELOW_VWAP";
        } else {
            signal = "AT_VWAP";
        }

        candle.setVwapSignal(signal);
    }

    @PreDestroy
    public void stop() {
        log.info("🛑 Stopping TimeframeAggregator...");
        for (Map.Entry<String, KafkaStreams> entry : streamsByTimeframe.entrySet()) {
            try {
                entry.getValue().close(Duration.ofSeconds(10));
                log.debug("Stopped {} aggregator", entry.getKey());
            } catch (Exception e) {
                log.warn("Error stopping {} aggregator: {}", entry.getKey(), e.getMessage());
            }
        }
        streamsByTimeframe.clear();
        log.info("✅ TimeframeAggregator stopped");
    }
}
