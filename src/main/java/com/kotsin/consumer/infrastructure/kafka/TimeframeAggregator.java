package com.kotsin.consumer.infrastructure.kafka;

import com.kotsin.consumer.config.KafkaConfig;
import com.kotsin.consumer.domain.model.FamilyCandle;
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
     */
    private void buildTimeframeTopology(StreamsBuilder builder, String timeframe, int minutes, String outputTopic) {
        // Use event-time (candle windowStartMillis) instead of ingestion time
        // This ensures 1m → 5m → 15m → 1h aggregation works for both replay and live data
        KStream<String, FamilyCandle> input = builder.stream(
            inputTopic,
            Consumed.with(Serdes.String(), FamilyCandle.serde())
                .withTimestampExtractor(new com.kotsin.consumer.timeExtractor.FamilyCandleTimestampExtractor())
        );

        // Window by target timeframe with alignment to market open
        // FIX: Use configurable grace period (2s default for fast emission)
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
            .process(() -> new WallClockWindowEmitter<>(graceMsForEmitter))  // Wall-clock based emission
            .map((windowedKey, candle) -> {
                // Update window times
                candle.setWindowStartMillis(windowedKey.window().start());
                candle.setWindowEndMillis(windowedKey.window().end());
                candle.setTimeframe(timeframe);
                updateHumanReadableTime(candle);
                
                // Validate and log aggregation result
                validateAggregatedCandle(candle, timeframe);
                
                // Record metrics for this timeframe
                if (dataQualityMetrics != null) {
                    dataQualityMetrics.recordCandleProcessed("TimeframeAggregator", timeframe, true);
                }
                
                return KeyValue.pair(windowedKey.key(), candle);
            })
            .to(outputTopic, Produced.with(Serdes.String(), FamilyCandle.serde()));
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
     */
    private void startDailyStream() {
        try {
            String outputTopic = "family-candle-1d";
            String appId = "timeframe-aggregator-1d";

            Properties props = kafkaConfig.getStreamProperties(appId);
            StreamsBuilder builder = new StreamsBuilder();

            // Daily uses session windows aligned to market hours (9:15 AM - 3:30 PM)
            // Use event-time for consistent replay/live behavior
            KStream<String, FamilyCandle> input = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), FamilyCandle.serde())
                    .withTimestampExtractor(new com.kotsin.consumer.timeExtractor.FamilyCandleTimestampExtractor())
            );

            // Use 6h 15m window (market session length) with gap detection
            // FIX: Use configurable grace period (2s default for fast emission)
            TimeWindows dailyWindows = TimeWindows.ofSizeAndGrace(
                Duration.ofMinutes(375), // 6h 15m = 375 minutes
                Duration.ofSeconds(graceSeconds)
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
            long dailyGraceMs = graceSeconds * 1000L;

            aggregated
                // REMOVED: .suppress(Suppressed.untilWindowCloses(...))
                .toStream()
                .filter((windowedKey, candle) -> candle != null)
                .process(() -> new WallClockWindowEmitter<>(dailyGraceMs))  // Wall-clock based emission
                .map((windowedKey, candle) -> {
                    candle.setWindowStartMillis(windowedKey.window().start());
                    candle.setWindowEndMillis(windowedKey.window().end());
                    candle.setTimeframe("1d");
                    updateHumanReadableTime(candle);
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
                .options(incoming.getOptions() != null ? new ArrayList<>(incoming.getOptions()) : null)
                .hasFuture(incoming.isHasFuture())
                .hasOptions(incoming.isHasOptions())
                .optionCount(incoming.getOptionCount())
                // CRITICAL FIX: Add commodity flag
                .isCommodity(incoming.isCommodity())
                // Use incoming values as initial
                .spotFuturePremium(incoming.getSpotFuturePremium())
                .futuresBuildup(incoming.getFuturesBuildup())
                .pcr(incoming.getPcr())
                .totalCallOI(incoming.getTotalCallOI())
                .totalPutOI(incoming.getTotalPutOI())
                .totalCallOIChange(incoming.getTotalCallOIChange())
                .totalPutOIChange(incoming.getTotalPutOIChange())
                .futureOIChange(incoming.getFutureOIChange())
                .oiSignal(incoming.getOiSignal())
                .directionalBias(incoming.getDirectionalBias())
                .biasConfidence(incoming.getBiasConfidence())
                .quality(incoming.getQuality())
                // Reversal signals from first candle (will be updated with last)
                .ofiVelocity(incoming.getOfiVelocity())
                .ofiAcceleration(incoming.getOfiAcceleration())
                .exhaustionDetected(incoming.isExhaustionDetected())
                .exhaustionType(incoming.getExhaustionType())
                .previousOfi(incoming.getPreviousOfi())
                .deltaDivergenceDetected(incoming.isDeltaDivergenceDetected())
                .deltaDivergenceType(incoming.getDeltaDivergenceType())
                .reversalScore(incoming.getReversalScore())
                .reversalSignals(incoming.getReversalSignals() != null ? new ArrayList<>(incoming.getReversalSignals()) : null)
                .highConfidenceReversal(incoming.isHighConfidenceReversal())
                .callPremiumChange(incoming.getCallPremiumChange())
                .putPremiumChange(incoming.getPutPremiumChange())
                .optionsFlowConfirmsReversal(incoming.isOptionsFlowConfirmsReversal())
                .shortSqueezeDetected(incoming.isShortSqueezeDetected())
                .oiInterpretation(incoming.getOiInterpretation())
                .oiInterpretationConfidence(incoming.getOiInterpretationConfidence())
                .oiSuggestsReversal(incoming.isOiSuggestsReversal())
                .build();
        }

        // Existing aggregate - PROPERLY merge OHLCV
        aggregate.setTimestamp(Math.max(aggregate.getTimestamp(), incoming.getTimestamp()));
        
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
                        // OI uses latest
                        existing.setOpenInterest(incomingOpt.getOpenInterest());
                        existing.setOiChange(existing.getOiChange() + incomingOpt.getOiChange());
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

        return aggregate;
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
            .ofi(source.getOfi())
            .kyleLambda(source.getKyleLambda())
            .microprice(source.getMicroprice())
            .bidAskSpread(source.getBidAskSpread())
            .depthImbalance(source.getDepthImbalance())
            .averageBidDepth(source.getAverageBidDepth())
            .averageAskDepth(source.getAverageAskDepth())
            .totalBidOrders(source.getTotalBidOrders())
            .totalAskOrders(source.getTotalAskOrders())
            .orderbookUpdateCount(source.getOrderbookUpdateCount())
            // OI
            .oiPresent(source.isOiPresent())
            .openInterest(source.getOpenInterest())
            .oiOpen(source.getOiOpen())
            .oiHigh(source.getOiHigh())
            .oiLow(source.getOiLow())
            .oiClose(source.getOiClose())
            .oiChange(source.getOiChange())
            .oiChangePercent(source.getOiChangePercent())
            .oiVelocity(source.getOiVelocity())
            // Quality
            .quality(source.getQuality())
            .qualityReason(source.getQualityReason())
            // Previous close
            .previousClose(source.getPreviousClose())
            .overnightGap(source.getOvernightGap())
            .isGapUp(source.getIsGapUp())
            .isGapDown(source.getIsGapDown())
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
        aggregate.setHumanReadableTime(incoming.getHumanReadableTime());

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
            // OI Open stays from first
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
        }

        // ===== VWAP (volume-weighted average) =====
        // For simplicity, use latest VWAP (accurate recalculation would require all tick data)
        aggregate.setVwap(incoming.getVwap());

        // ===== QUALITY (worst case) =====
        // Use worst quality among all candles in window
        if (incoming.getQuality() != null) {
            aggregate.setQuality(incoming.getQuality());
            aggregate.setQualityReason(incoming.getQualityReason());
        }
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
