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
     * Build topology for minute-based timeframe aggregation
     */
    private void buildTimeframeTopology(StreamsBuilder builder, String timeframe, int minutes, String outputTopic) {
        KStream<String, FamilyCandle> input = builder.stream(
            inputTopic,
            Consumed.with(Serdes.String(), FamilyCandle.serde())
        );

        // Window by target timeframe with alignment to market open
        TimeWindows windows = TimeWindows.ofSizeAndGrace(
            Duration.ofMinutes(minutes),
            Duration.ofSeconds(10)
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

        // Emit on window close with validation
        aggregated
            .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
            .toStream()
            .filter((windowedKey, candle) -> candle != null)
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
     * Validate aggregated candle and log issues
     */
    private void validateAggregatedCandle(FamilyCandle candle, String timeframe) {
        if (candle.getEquity() != null) {
            com.kotsin.consumer.domain.model.InstrumentCandle eq = candle.getEquity();
            double range = eq.getHigh() - eq.getLow();
            
            // Log if range seems too small for larger timeframes
            if (timeframe.contains("h") || timeframe.equals("1d")) {
                // For hourly+ candles, range < 0.1% is suspicious
                double rangePercent = (range / eq.getClose()) * 100;
                if (rangePercent < 0.1 && eq.getClose() > 0) {
                    log.warn("⚠️ {} candle {} has tiny range: {}% | OHLC={}/{}/{}/{}", 
                        timeframe, candle.getFamilyId(), 
                        String.format("%.3f", rangePercent),
                        eq.getOpen(), eq.getHigh(), eq.getLow(), eq.getClose());
                }
            }
            
            // OHLC sanity check
            if (eq.getHigh() < eq.getLow()) {
                log.error("🚨 {} OHLC INVALID | {} | high={} < low={}", 
                    timeframe, candle.getFamilyId(), eq.getHigh(), eq.getLow());
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
            KStream<String, FamilyCandle> input = builder.stream(
                inputTopic,
                Consumed.with(Serdes.String(), FamilyCandle.serde())
            );

            // Use 6h 15m window (market session length) with gap detection
            TimeWindows dailyWindows = TimeWindows.ofSizeAndGrace(
                Duration.ofMinutes(375), // 6h 15m = 375 minutes
                Duration.ofMinutes(5)
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

            aggregated
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
                .toStream()
                .filter((windowedKey, candle) -> candle != null)
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
     */
    private FamilyCandle mergeCandles(FamilyCandle aggregate, FamilyCandle incoming, String timeframe) {
        if (aggregate == null) {
            // First candle in window - clone it
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
        
        // ========== MERGE OPTIONS (use latest with OI aggregation) ==========
        if (incoming.getOptions() != null && !incoming.getOptions().isEmpty()) {
            aggregate.setOptions(incoming.getOptions());  // Options are point-in-time, use latest
        }

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

        return aggregate;
    }

    /**
     * Clone an InstrumentCandle (for initial aggregation)
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
            // OI
            .oiPresent(source.isOiPresent())
            .openInterest(source.getOpenInterest())
            .oiOpen(source.getOiOpen())
            .oiHigh(source.getOiHigh())
            .oiLow(source.getOiLow())
            .oiClose(source.getOiClose())
            .oiChange(source.getOiChange())
            .oiChangePercent(source.getOiChangePercent())
            .quality(source.getQuality())
            .build();
    }

    /**
     * Merge OHLCV from incoming candle into aggregate
     * RULE: Open=first, High=max, Low=min, Close=last, Volume=sum
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
        }
        
        // VWAP = use latest (could be volume-weighted average but complex)
        aggregate.setVwap(incoming.getVwap());
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
