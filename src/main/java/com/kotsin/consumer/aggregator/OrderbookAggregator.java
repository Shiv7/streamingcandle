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

import com.kotsin.consumer.aggregator.state.OrderbookAggregateState;
import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.OrderbookMetrics;
import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.model.TimeframeBoundary;
import com.kotsin.consumer.repository.OrderbookMetricsRepository;

/**
 * OrderbookAggregator - Kafka consumer for orderbook data aggregation.
 *
 * <p>Extends {@link BaseAggregator} with orderbook-specific logic:</p>
 * <ul>
 *   <li>Orderbook structure validation</li>
 *   <li>Market hours enforcement</li>
 *   <li>OFI, Kyle's Lambda, spread/depth metrics via {@link OrderbookAggregateState}</li>
 *   <li>Boundary event publishing for orderbook data</li>
 * </ul>
 *
 * @see BaseAggregator
 * @see OrderbookAggregateState
 * @see OrderbookMetrics
 */
@Component
@Slf4j
public class OrderbookAggregator extends BaseAggregator<OrderBookSnapshot, OrderbookAggregateState, OrderbookMetrics> {

    private static final String LOG_PREFIX = "[OB-AGG]";

    // ==================== CONFIGURATION ====================

    @Value("${v2.orderbook.aggregator.enabled:true}")
    private boolean enabled;

    @Value("${v2.orderbook.input.topic:Orderbook}")
    private String inputTopic;

    @Value("${v2.orderbook.consumer.group:orderbook-aggregator-v2}")
    private String consumerGroup;

    @Value("${v2.orderbook.aggregator.threads:16}")
    private int numThreads;

    @Value("${v2.orderbook.output.topic:orderbook-metrics-1m}")
    private String outputTopic;

    // ==================== OB-SPECIFIC DEPENDENCIES ====================

    @Autowired
    private OrderbookMetricsRepository orderbookRepository;

    // ==================== CONFIG IMPLEMENTATIONS ====================

    @Override protected String getLogPrefix() { return LOG_PREFIX; }
    @Override protected boolean isEnabled() { return enabled; }
    @Override protected String getInputTopic() { return inputTopic; }
    @Override protected String getConsumerGroup() { return consumerGroup; }
    @Override protected int getNumThreads() { return numThreads; }
    @Override protected String getOutputTopic() { return outputTopic; }

    // ==================== CONSUMER ====================

    @Override
    protected KafkaConsumer<String, OrderBookSnapshot> createConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.kotsin.consumer.model,com.kotsin.optionDataProducer.model");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, OrderBookSnapshot.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 2000);
        return new KafkaConsumer<>(props);
    }

    // ==================== ORDERBOOK PROCESSING ====================

    @Override
    protected void processRecord(OrderBookSnapshot ob, long kafkaTimestamp) {
        if (ob == null || ob.getToken() <= 0) return;

        // Bug #14: Validate orderbook structure
        if (!ob.isValid()) {
            log.debug("{} Rejected invalid orderbook for token {}", LOG_PREFIX, ob.getToken());
            return;
        }

        String key = ob.getExchange() + ":" + ob.getToken();
        Instant obTime = Instant.ofEpochMilli(kafkaTimestamp);

        // Bug #23: Market hours enforcement
        if (!TimeframeBoundary.isMarketHours(obTime, ob.getExchange())) {
            return;
        }

        // Trace logging (Bug #2: by scripcode)
        boolean isSpecific = (traceScripCodes != null && !traceScripCodes.isEmpty() &&
                             traceScripCodes.contains(String.valueOf(ob.getToken())));
        boolean shouldLog = isSpecific || (traceScripCodes == null || traceScripCodes.isEmpty());

        if (shouldLog) {
            int bidCount = ob.getBids() != null ? ob.getBids().size() : 0;
            int askCount = ob.getAsks() != null ? ob.getAsks().size() : 0;
            log.info("[OB-TRACE] Received Orderbook for {}: Bids={}, Asks={}, Time={}",
                ob.getToken(), bidCount, askCount, obTime);
        }

        updateEventTimeTracking(obTime);
        initializeWindowIfNeeded(obTime);

        // Determine window assignment
        final Instant tickWindowStart = Timeframe.M1.alignToWindowStart(obTime);
        final Instant tickWindowEnd = Timeframe.M1.getWindowEnd(tickWindowStart);
        String stateKey = key + ":" + tickWindowStart.toEpochMilli();

        // Resolve symbol using ScripMetadataService
        final String resolvedSymbol = scripMetadataService.getSymbolRoot(String.valueOf(ob.getToken()));

        OrderbookAggregateState state = aggregationState.computeIfAbsent(stateKey,
            k -> new OrderbookAggregateState(ob, tickWindowStart, tickWindowEnd, resolvedSymbol));

        state.update(ob, obTime);
    }

    // ==================== STATE CONVERSION ====================

    @Override
    protected OrderbookMetrics convertState(OrderbookAggregateState state) {
        return state.toOrderbookMetrics();
    }

    @Override
    protected String getKafkaKey(OrderbookMetrics metrics) {
        return metrics.getScripCode();
    }

    // ==================== PERSISTENCE ====================

    @Override
    protected void persistToMongoDB(List<OrderbookMetrics> metrics) {
        try {
            orderbookRepository.saveAll(metrics);

            if (traceScripCodes != null && !traceScripCodes.isEmpty()) {
                for (OrderbookMetrics m : metrics) {
                    if (traceScripCodes.contains(m.getScripCode())) {
                        log.info("[MONGO-WRITE] OrderbookMetrics saved for {} @ {} | OFI: {} | Spread: {}",
                            m.getSymbol(), formatTime(m.getWindowEnd()), m.getOfi(), m.getBidAskSpread());
                    }
                }
            }
        } catch (Exception e) {
            log.error("{} Failed to save to MongoDB: {}", LOG_PREFIX, e.getMessage());
        }
    }

    @Override
    protected void cacheToRedis(List<OrderbookMetrics> metrics) {
        try {
            for (OrderbookMetrics m : metrics) {
                redisCacheService.cacheOrderbookMetrics(m);
            }
        } catch (Exception e) {
            log.error("{} Failed to cache in Redis: {}", LOG_PREFIX, e.getMessage());
        }
    }

    // ==================== POST-EMISSION (BOUNDARY EVENTS) ====================

    @Override
    protected void postEmission(List<OrderbookMetrics> metrics) {
        // Bug #8: Publish boundary events for Orderbook data
        for (OrderbookMetrics m : metrics) {
            candleBoundaryPublisher.onMetricsClose(
                m.getScripCode(), m.getExchange(), m.getWindowEnd(), "ORDERBOOK");
        }
    }
}
