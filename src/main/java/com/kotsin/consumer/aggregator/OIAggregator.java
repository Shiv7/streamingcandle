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

import com.kotsin.consumer.aggregator.state.OIAggregateState;
import com.kotsin.consumer.aggregator.state.OptionMetadata;
import com.kotsin.consumer.model.OIMetrics;
import com.kotsin.consumer.model.OpenInterest;
import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.model.TimeframeBoundary;
import com.kotsin.consumer.repository.OIMetricsRepository;

/**
 * OIAggregator - Kafka consumer for Open Interest data aggregation.
 *
 * <p>Extends {@link BaseAggregator} with OI-specific logic:</p>
 * <ul>
 *   <li>OI data validation (reject null/negative values)</li>
 *   <li>Market hours enforcement</li>
 *   <li>OI interpretation calculation (LONG_BUILDUP, SHORT_COVERING, etc.)</li>
 *   <li>Boundary event publishing for OI data</li>
 * </ul>
 *
 * @see BaseAggregator
 * @see OIAggregateState
 * @see OIMetrics
 */
@Component
@Slf4j
public class OIAggregator extends BaseAggregator<OpenInterest, OIAggregateState, OIMetrics> {

    private static final String LOG_PREFIX = "[OI-AGG]";

    // ==================== CONFIGURATION ====================

    @Value("${v2.oi.aggregator.enabled:true}")
    private boolean enabled;

    @Value("${v2.oi.input.topic:OpenInterest}")
    private String inputTopic;

    @Value("${v2.oi.consumer.group:oi-aggregator-v2}")
    private String consumerGroup;

    @Value("${v2.oi.aggregator.threads:16}")
    private int numThreads;

    @Value("${v2.oi.output.topic:oi-metrics-1m}")
    private String outputTopic;

    // ==================== OI-SPECIFIC DEPENDENCIES ====================

    @Autowired
    private OIMetricsRepository oiRepository;

    // ==================== CONFIG IMPLEMENTATIONS ====================

    @Override protected String getLogPrefix() { return LOG_PREFIX; }
    @Override protected boolean isEnabled() { return enabled; }
    @Override protected String getInputTopic() { return inputTopic; }
    @Override protected String getConsumerGroup() { return consumerGroup; }
    @Override protected int getNumThreads() { return numThreads; }
    @Override protected String getOutputTopic() { return outputTopic; }

    // ==================== CONSUMER ====================

    @Override
    protected KafkaConsumer<String, OpenInterest> createConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.kotsin.consumer.model,com.kotsin.optionDataProducer.model");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, OpenInterest.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 2000);
        return new KafkaConsumer<>(props);
    }

    // ==================== OI PROCESSING ====================

    @Override
    protected void processRecord(OpenInterest oi, long kafkaTimestamp) {
        if (oi == null || oi.getToken() == 0) return;

        // Bug #13: Validate OI data
        if (oi.getOpenInterest() == null || oi.getOpenInterest() < 0) {
            log.debug("{} Rejected OI for token {}: invalid OI value={}", LOG_PREFIX, oi.getToken(), oi.getOpenInterest());
            return;
        }

        String key = oi.getExchange() + ":" + oi.getToken();
        Instant oiTime = Instant.ofEpochMilli(kafkaTimestamp);

        // Bug #23: Market hours enforcement
        if (!TimeframeBoundary.isMarketHours(oiTime, oi.getExchange())) {
            return;
        }

        // Trace logging (Bug #2: by scripcode)
        boolean isSpecific = (traceScripCodes != null && !traceScripCodes.isEmpty() &&
                             traceScripCodes.contains(String.valueOf(oi.getToken())));
        boolean shouldLog = isSpecific || (traceScripCodes == null || traceScripCodes.isEmpty());

        if (shouldLog) {
            log.info("[OI-TRACE] Received OI for {}: OI={}, Time={}",
                oi.getToken(), oi.getOpenInterest(), oiTime);
        }

        updateEventTimeTracking(oiTime);
        initializeWindowIfNeeded(oiTime);

        // Determine window assignment
        final Instant tickWindowStart = Timeframe.M1.alignToWindowStart(oiTime);
        final Instant tickWindowEnd = Timeframe.M1.getWindowEnd(tickWindowStart);
        String stateKey = key + ":" + tickWindowStart.toEpochMilli();

        // Bug #24: token == scripCode in 5paisa's system
        final String scripCode = String.valueOf(oi.getToken());
        final String resolvedSymbol = scripMetadataService.getSymbolRoot(scripCode);

        // Get OptionMetadata from Scrip if this is an option
        final OptionMetadata optionMeta = scripMetadataService.isOption(scripCode)
            ? OptionMetadata.fromScrip(scripMetadataService.getScripByCode(scripCode))
            : null;

        OIAggregateState state = aggregationState.computeIfAbsent(stateKey,
            k -> new OIAggregateState(oi, tickWindowStart, tickWindowEnd, resolvedSymbol, optionMeta));

        state.update(oi, oiTime);
    }

    // ==================== STATE CONVERSION ====================

    @Override
    protected OIMetrics convertState(OIAggregateState state) {
        return state.toOIMetrics(redisCacheService);
    }

    @Override
    protected String getKafkaKey(OIMetrics metrics) {
        return metrics.getScripCode();
    }

    // ==================== PERSISTENCE ====================

    @Override
    protected void persistToMongoDB(List<OIMetrics> metrics) {
        try {
            oiRepository.saveAll(metrics);

            if (traceScripCodes != null && !traceScripCodes.isEmpty()) {
                for (OIMetrics m : metrics) {
                    if (traceScripCodes.contains(m.getScripCode())) {
                        log.info("[MONGO-WRITE] OIMetrics saved for {} @ {} | OI: {} | Interp: {}",
                            m.getSymbol(), formatTime(m.getWindowEnd()), m.getOpenInterest(), m.getInterpretation());
                    }
                }
            }
        } catch (Exception e) {
            log.error("{} Failed to save to MongoDB: {}", LOG_PREFIX, e.getMessage());
        }
    }

    @Override
    protected void cacheToRedis(List<OIMetrics> metrics) {
        try {
            for (OIMetrics m : metrics) {
                redisCacheService.cacheOIMetrics(m);
            }
        } catch (Exception e) {
            log.error("{} Failed to cache in Redis: {}", LOG_PREFIX, e.getMessage());
        }
    }

    // ==================== POST-EMISSION (BOUNDARY EVENTS) ====================

    @Override
    protected void postEmission(List<OIMetrics> metrics) {
        // Bug #8: Publish boundary events for OI data
        for (OIMetrics m : metrics) {
            candleBoundaryPublisher.onMetricsClose(
                m.getScripCode(), m.getExchange(), m.getWindowEnd(), "OI");
        }
    }
}
