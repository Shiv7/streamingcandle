package com.kotsin.consumer.aggregator;

import java.time.Instant;
import java.util.List;
import java.util.Properties;

import lombok.extern.slf4j.Slf4j;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.stereotype.Component;

import com.kotsin.consumer.aggregator.state.OptionMetadata;
import com.kotsin.consumer.aggregator.state.TickAggregateState;
import com.kotsin.consumer.logging.TraceContext;
import com.kotsin.consumer.model.TickCandle;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.model.TimeframeBoundary;
import com.kotsin.consumer.repository.TickCandleRepository;
import com.kotsin.consumer.signal.trigger.FudkiiSignalTrigger;

import java.util.concurrent.TimeUnit;

/**
 * TickAggregator - Kafka consumer for tick data aggregation.
 *
 * <p>Extends {@link BaseAggregator} with tick-specific logic:</p>
 * <ul>
 *   <li>Data quality validation (price, quantity)</li>
 *   <li>Lee-Ready trade classification and VPIN via {@link TickAggregateState}</li>
 *   <li>FUDKII signal triggering at 30m boundaries</li>
 *   <li>Candle boundary event publishing for HTF analysis</li>
 *   <li>Instrument type resolution from ScripMetadataService</li>
 * </ul>
 *
 * @see BaseAggregator
 * @see TickAggregateState
 * @see TickCandle
 */
@Component
@Slf4j
public class TickAggregator extends BaseAggregator<TickData, TickAggregateState, TickCandle> {

    private static final String LOG_PREFIX = "[TICK-AGG]";

    // ==================== CONFIGURATION ====================

    @Value("${v2.tick.aggregator.enabled:true}")
    private boolean enabled;

    @Value("${v2.tick.input.topic:forwardtesting-data}")
    private String inputTopic;

    @Value("${v2.tick.consumer.group:tick-aggregator-v2}")
    private String consumerGroup;

    @Value("${v2.tick.aggregator.threads:16}")
    private int numThreads;

    @Value("${v2.tick.output.topic:tick-candles-1m}")
    private String outputTopic;

    // ==================== TICK-SPECIFIC DEPENDENCIES ====================

    @Autowired
    private TickCandleRepository tickCandleRepository;

    @Autowired
    private FudkiiSignalTrigger fudkiiSignalTrigger;

    // ==================== CONFIG IMPLEMENTATIONS ====================

    @Override protected String getLogPrefix() { return LOG_PREFIX; }
    @Override protected boolean isEnabled() { return enabled; }
    @Override protected String getInputTopic() { return inputTopic; }
    @Override protected String getConsumerGroup() { return consumerGroup; }
    @Override protected int getNumThreads() { return numThreads; }
    @Override protected String getOutputTopic() { return outputTopic; }

    // ==================== LIFECYCLE HOOKS ====================

    @Override
    protected void onStart() {
        // Bug #15: Schedule periodic cleanup of pending candles (every 10 minutes)
        emissionScheduler.scheduleAtFixedRate(
            candleBoundaryPublisher::cleanupOldPendingCandles, 10, 10, TimeUnit.MINUTES);
    }

    // ==================== CONSUMER ====================

    @Override
    protected KafkaConsumer<String, TickData> createConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.kotsin.consumer.model,com.kotsin.optionDataProducer.model");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, TickData.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 2000);
        return new KafkaConsumer<>(props);
    }

    // ==================== TICK PROCESSING ====================

    @Override
    protected void processRecord(TickData tick, long kafkaTimestamp) {
        if (tick == null || tick.getScripCode() == null) return;

        // Data quality validation
        String validationError = validateTick(tick);
        if (validationError != null) {
            log.debug("{} Rejected tick {}: {}", LOG_PREFIX, tick.getScripCode(), validationError);
            return;
        }

        String key = tick.getExchange() + ":" + tick.getScripCode();
        String symbol = scripMetadataService.getSymbolRoot(tick.getScripCode());

        // Trace logging (Bug #2: by scripcode)
        boolean specificScripCode = traceScripCodes.contains(tick.getScripCode());
        boolean shouldLog = specificScripCode || traceScripCodes.isEmpty();

        if (shouldLog) {
            log.info("[TICK-TRACE] Received tick for {}: Price={}, Vol={}, Time={}",
                symbol, tick.getLastRate(), tick.getLastQuantity(), tick.getTickDt());
        }

        // Bug #19: Start trace context with scripcode
        TraceContext.start(tick.getScripCode(), "1m");
        try {
            Instant tickTime = Instant.ofEpochMilli(kafkaTimestamp);

            updateEventTimeTracking(tickTime);
            initializeWindowIfNeeded(tickTime);

            // Determine window assignment
            final Instant tickWindowStart = Timeframe.M1.alignToWindowStart(tickTime);
            final Instant tickWindowEnd = Timeframe.M1.getWindowEnd(tickWindowStart);
            final String stateKey = key + ":" + tickWindowStart.toEpochMilli();

            final String resolvedSymbol = symbol;

            // Get option metadata from Scrip database
            final OptionMetadata optionMeta = scripMetadataService.isOption(tick.getScripCode())
                ? OptionMetadata.fromScrip(scripMetadataService.getScripByCode(tick.getScripCode()))
                : null;

            // Bug #12: Resolve instrument type from ScripMetadataService
            final String scripType = scripMetadataService.getScripType(tick.getScripCode());
            final TickCandle.InstrumentType resolvedInstrumentType = scripType != null
                ? TickCandle.InstrumentType.fromScripType(scripType)
                : null;

            TickAggregateState state = aggregationState.computeIfAbsent(stateKey,
                k -> {
                    TickAggregateState s = new TickAggregateState(tick, tickWindowStart, tickWindowEnd, resolvedSymbol, optionMeta);
                    s.setInstrumentType(resolvedInstrumentType);
                    return s;
                });

            state.update(tick, tickTime);
        } finally {
            TraceContext.clear();
        }
    }

    /**
     * Validate tick data quality.
     */
    private String validateTick(TickData tick) {
        if (tick.getLastRate() <= 0) {
            return "Price <= 0";
        }
        // Bug #26: Quantity validation, allow qty=0 for INDEX instruments
        if (tick.getLastQuantity() < 0) {
            return "Quantity < 0";
        }
        if (tick.getLastQuantity() == 0) {
            TickCandle.InstrumentType type = TickCandle.InstrumentType.detect(
                tick.getExchange(), tick.getExchangeType(), tick.getCompanyName());
            if (type != TickCandle.InstrumentType.INDEX) {
                return "Quantity = 0 (non-INDEX)";
            }
        }
        return null;
    }

    // ==================== STATE CONVERSION ====================

    @Override
    protected TickCandle convertState(TickAggregateState state) {
        return state.toTickCandle();
    }

    @Override
    protected String getKafkaKey(TickCandle candle) {
        return candle.getScripCode();
    }

    // ==================== PERSISTENCE ====================

    @Override
    protected void persistToMongoDB(List<TickCandle> candles) {
        try {
            tickCandleRepository.saveAll(candles);
            log.info("{} Saved {} candles to MongoDB", LOG_PREFIX, candles.size());

            if (traceScripCodes != null && !traceScripCodes.isEmpty()) {
                for (TickCandle c : candles) {
                    if (traceScripCodes.contains(c.getScripCode())) {
                        log.info("[MONGO-WRITE] TickCandle saved for {} @ {} | Close: {} | Vol: {}",
                            c.getSymbol(), formatTime(c.getWindowEnd()), c.getClose(), c.getVolume());
                    }
                }
            }
        } catch (Exception e) {
            log.error("{} Failed to save to MongoDB: {}", LOG_PREFIX, e.getMessage());
        }
    }

    @Override
    protected void cacheToRedis(List<TickCandle> candles) {
        try {
            for (TickCandle candle : candles) {
                redisCacheService.cacheTickCandle(candle);
                // Bug #25 FIX: keyed by exchange:scripCode
                redisCacheService.cachePrice(candle.getExchange(), candle.getScripCode(), candle.getClose());
            }
            log.debug("{} Cached {} candles in Redis", LOG_PREFIX, candles.size());
        } catch (Exception e) {
            log.error("{} Failed to cache in Redis: {}", LOG_PREFIX, e.getMessage());
        }
    }

    // ==================== POST-EMISSION (FUDKII + BOUNDARY EVENTS) ====================

    @Override
    protected void postEmission(List<TickCandle> candles) {
        triggerFudkiiSignals(candles);
        publishBoundaryEvents(candles);
    }

    /**
     * Trigger FUDKII signal evaluation.
     *
     * <p>Bug #7: Only at 30m boundaries.</p>
     * <p>Bug #17: Only for equity/index, not derivatives.</p>
     * <p>MCX fix: Allow futures on MCX (no equity on commodity exchange).</p>
     */
    private void triggerFudkiiSignals(List<TickCandle> candles) {
        try {
            // Bug #7: FUDKII operates on 30m candles; skip if not a 30m boundary
            if (!candles.isEmpty()) {
                TickCandle first = candles.get(0);
                String exchange = first.getExchange() != null ? first.getExchange() : "N";
                if (!TimeframeBoundary.is30mBoundary(first.getWindowEnd(), exchange)) {
                    return;
                }
            }

            for (TickCandle candle : candles) {
                if (candle.getScripCode() != null) {
                    TickCandle.InstrumentType type = candle.getInstrumentType();
                    if (type != null && type.isDerivative()) {
                        // MCX has no equity - allow futures on MCX
                        boolean isMcxFuture = "M".equals(candle.getExchange()) &&
                                              type == TickCandle.InstrumentType.FUTURE;
                        if (!isMcxFuture) {
                            continue;
                        }
                    }
                    fudkiiSignalTrigger.onCandleClose(candle.getScripCode(), candle);
                }
            }
        } catch (Exception e) {
            log.error("{} Failed to check FUDKII trigger: {}", LOG_PREFIX, e.getMessage());
        }
    }

    /**
     * Publish candle boundary events for HTF analysis.
     */
    private void publishBoundaryEvents(List<TickCandle> candles) {
        try {
            for (TickCandle candle : candles) {
                if (candle.getScripCode() != null) {
                    candleBoundaryPublisher.onCandleClose(candle);
                }
            }
        } catch (Exception e) {
            log.error("{} Failed to publish boundary events: {}", LOG_PREFIX, e.getMessage());
        }
    }
}
