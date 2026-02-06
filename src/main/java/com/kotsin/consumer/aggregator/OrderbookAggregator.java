package com.kotsin.consumer.aggregator;

// ==================== JAVA STANDARD LIBRARY ====================
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

// ==================== JAKARTA EE ====================
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

// ==================== LOMBOK ====================
import lombok.extern.slf4j.Slf4j;

// ==================== APACHE KAFKA ====================
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

// ==================== SPRING FRAMEWORK ====================
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.stereotype.Component;

// ==================== PROJECT IMPORTS ====================
import com.kotsin.consumer.aggregator.state.OrderbookAggregateState;
import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.OrderbookMetrics;
import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.repository.OrderbookMetricsRepository;
import com.kotsin.consumer.service.RedisCacheService;
import com.kotsin.consumer.service.ScripMetadataService;

/**
 * OrderbookAggregator - Independent Kafka consumer for orderbook data aggregation.
 *
 * <h2>Responsibilities</h2>
 * <ol>
 *   <li>Consume orderbook snapshots from Orderbook Kafka topic</li>
 *   <li>Aggregate orderbook data into 1-minute metrics using event-time windowing</li>
 *   <li>Calculate Order Flow Imbalance (OFI) from bid/ask changes</li>
 *   <li>Calculate Kyle's Lambda (price impact coefficient)</li>
 *   <li>Track spread and depth metrics</li>
 *   <li>Persist aggregated metrics to MongoDB (orderbook_metrics_1m collection)</li>
 *   <li>Cache hot data in Redis for downstream consumers</li>
 *   <li>Publish completed metrics to Kafka for downstream processing</li>
 * </ol>
 *
 * <h2>Key Design Principles</h2>
 * <ul>
 *   <li><b>SIMPLE</b>: Single responsibility - only orderbook aggregation</li>
 *   <li><b>NO JOINS</b>: Does not join with tick or OI data (handled by UnifiedCandle builder)</li>
 *   <li><b>INDEPENDENT</b>: Runs in its own thread pool, no Kafka Streams state stores</li>
 *   <li><b>EVENT-TIME</b>: Uses kafkaTimestamp for window assignment (not wall clock)</li>
 *   <li><b>THREAD-SAFE</b>: ConcurrentHashMap for state, ReentrantLock for window boundaries</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 * <pre>
 * Kafka (Orderbook)
 *         │
 *         ▼
 * ┌─────────────────────┐
 * │ OrderbookAggregator │  ◄── Multiple consumer threads
 * │    (Event-Time)     │
 * └─────────────────────┘
 *         │
 *         ▼
 * ┌─────────────────────┐
 * │OrderbookAggregateState│  ◄── Per-instrument, per-window state
 * │(OFI, Kyle's Lambda) │
 * └─────────────────────┘
 *         │
 *         ├──► MongoDB (orderbook_metrics_1m)
 *         ├──► Redis (hot cache)
 *         └──► Kafka (orderbook-metrics-1m)
 * </pre>
 *
 * <h2>Key Metrics Calculated</h2>
 * <ul>
 *   <li><b>OFI</b>: Order Flow Imbalance = Σ(BidDelta - AskDelta)</li>
 *   <li><b>Kyle's Lambda</b>: Price impact coefficient from regression</li>
 *   <li><b>Microprice</b>: Volume-weighted fair price</li>
 *   <li><b>Spread Metrics</b>: Average, volatility, tight spread %</li>
 *   <li><b>Depth Metrics</b>: Bid/ask depth, imbalance</li>
 * </ul>
 *
 * <h2>v2.1 Quant Fixes</h2>
 * <ul>
 *   <li>Rolling Kyle's Lambda (50-observation window, persists across minutes)</li>
 * </ul>
 *
 * <h2>Bug Fixes</h2>
 * <ul>
 *   <li><b>Bug #2</b>: Kyle's Lambda window cleared on reset</li>
 *   <li><b>Bug #4</b>: Idle flush advances by one window at a time</li>
 *   <li><b>Bug #6</b>: ReentrantLock for atomic compound window updates</li>
 *   <li><b>Bug #9</b>: LinkedList for O(1) removal in Kyle's Lambda</li>
 *   <li><b>Bug #17</b>: Safe state cleanup using pre-collected key lists</li>
 * </ul>
 *
 * @see OrderbookAggregateState
 * @see OrderbookMetrics
 * @see RedisCacheService
 */
@Component
@Slf4j
public class OrderbookAggregator {

    // ==================== CONSTANTS ====================

    /**
     * Log prefix for all log messages from this aggregator.
     */
    private static final String LOG_PREFIX = "[OB-AGG]";

    /**
     * Indian Standard Time zone for timestamp formatting.
     */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Time formatter for log messages (HH:mm:ss).
     */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ==================== CONFIGURATION PROPERTIES ====================

    /**
     * Feature flag to enable/disable orderbook aggregator.
     * <p>Default: true</p>
     */
    @Value("${v2.orderbook.aggregator.enabled:true}")
    private boolean enabled;

    /**
     * Kafka bootstrap servers for consumer connection.
     * <p>Default: localhost:9092</p>
     */
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    /**
     * Input Kafka topic containing orderbook data.
     * <p>Default: Orderbook</p>
     */
    @Value("${v2.orderbook.input.topic:Orderbook}")
    private String inputTopic;

    /**
     * Kafka consumer group ID for this aggregator.
     * <p>Default: orderbook-aggregator-v2</p>
     */
    @Value("${v2.orderbook.consumer.group:orderbook-aggregator-v2}")
    private String consumerGroup;

    /**
     * Number of parallel consumer threads.
     * <p>Default: 2</p>
     */
    @Value("${v2.orderbook.aggregator.threads:16}")
    private int numThreads;

    /**
     * Output Kafka topic for aggregated orderbook metrics.
     * <p>Default: orderbook-metrics-1m</p>
     */
    @Value("${v2.orderbook.output.topic:orderbook-metrics-1m}")
    private String outputTopic;

    /**
     * Comma-separated list of symbols for trace logging.
     * <p>Empty = trace all symbols (FULL MODE)</p>
     */
    @Value("${logging.trace.symbols:}")
    private String traceSymbolsStr;

    /**
     * Parsed set of symbols for trace logging.
     */
    private Set<String> traceSymbols;

    // ==================== INJECTED DEPENDENCIES ====================

    /**
     * Repository for persisting OrderbookMetrics to MongoDB.
     */
    @Autowired
    private OrderbookMetricsRepository orderbookRepository;

    /**
     * Service for caching metrics in Redis.
     */
    @Autowired
    private RedisCacheService redisCacheService;

    /**
     * Kafka template for publishing aggregated metrics.
     */
    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Service for scrip metadata lookups (symbol, company name, etc.).
     * <p>Used to get authoritative symbol from scripCode via database lookup.</p>
     */
    @Autowired
    private ScripMetadataService scripMetadataService;

    // ==================== STATE MANAGEMENT ====================

    /**
     * Aggregation state per instrument-window pair.
     * <p>Key format: {@code "exchange:token:windowStartMillis"}</p>
     * <p>Example: {@code "N:12345:1706789400000"}</p>
     */
    private final ConcurrentHashMap<String, OrderbookAggregateState> aggregationState = new ConcurrentHashMap<>();

    /**
     * Executor service for Kafka consumer threads.
     */
    private ExecutorService consumerExecutor;

    /**
     * Scheduler for periodic window emission checks.
     */
    private ScheduledExecutorService emissionScheduler;

    /**
     * Flag indicating whether aggregator is running.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    // ==================== EVENT TIME TRACKING ====================

    /**
     * Max event time observed across all orderbook snapshots.
     * <p>Used as reference for strict event-time window emission.</p>
     */
    private final AtomicReference<Instant> maxEventTime = new AtomicReference<>(Instant.EPOCH);

    /**
     * Wall-clock time of last data arrival.
     * <p>Used for idle detection when no new data arrives.</p>
     */
    private final AtomicLong lastDataArrivalMillis = new AtomicLong(System.currentTimeMillis());

    /**
     * Lock for atomic compound updates to window boundaries.
     * <p>Bug #6 FIX: Prevents race conditions between emission threads.</p>
     */
    private final ReentrantLock windowLock = new ReentrantLock();

    /**
     * Current window start being tracked for emission.
     */
    private volatile Instant currentWindowStart;

    /**
     * Current window end being tracked for emission.
     */
    private volatile Instant currentWindowEnd;

    // ==================== LIFECYCLE METHODS ====================

    /**
     * Start the orderbook aggregator.
     *
     * <p>Initialization steps:</p>
     * <ol>
     *   <li>Check if enabled by configuration</li>
     *   <li>Initialize window tracking to EPOCH</li>
     *   <li>Start consumer thread pool</li>
     *   <li>Start emission scheduler (runs every second)</li>
     *   <li>Parse trace symbols for debug logging</li>
     * </ol>
     */
    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("{} Disabled by configuration", LOG_PREFIX);
            return;
        }

        log.info("{} Starting with threads={}, topic={}, MODE=STRICT_EVENT_TIME", LOG_PREFIX, numThreads, inputTopic);

        running.set(true);

        // Initialize current window from epoch - will catch up based on data
        currentWindowStart = Instant.EPOCH;
        currentWindowEnd = Instant.EPOCH;
        maxEventTime.set(Instant.EPOCH);
        lastDataArrivalMillis.set(System.currentTimeMillis());

        log.info("{} [STRICT-EVENT-TIME] Window will be initialized from first Orderbook event time", LOG_PREFIX);

        // Start consumer threads
        consumerExecutor = Executors.newFixedThreadPool(numThreads);
        for (int i = 0; i < numThreads; i++) {
            consumerExecutor.submit(this::consumeLoop);
        }

        // Start window emission scheduler
        emissionScheduler = Executors.newSingleThreadScheduledExecutor();
        emissionScheduler.scheduleAtFixedRate(this::checkWindowEmission, 1, 1, TimeUnit.SECONDS);

        log.info("{} Started successfully", LOG_PREFIX);

        // Parse trace symbols
        this.traceSymbols = new HashSet<>();
        if (traceSymbolsStr != null && !traceSymbolsStr.isBlank()) {
            String[] parts = traceSymbolsStr.split(",");
            for (String part : parts) {
                traceSymbols.add(part.trim().toUpperCase());
            }
            log.info("{} Trace logging enabled for symbols: {}", LOG_PREFIX, traceSymbols);
        } else {
            log.info("{} Trace logging enabled for ALL symbols (FULL - NO SAMPLING)", LOG_PREFIX);
        }
    }

    /**
     * Stop the orderbook aggregator gracefully.
     *
     * <p>Shutdown steps:</p>
     * <ol>
     *   <li>Set running flag to false</li>
     *   <li>Shutdown emission scheduler</li>
     *   <li>Shutdown consumer executor with 30s timeout</li>
     *   <li>Emit any remaining window data</li>
     * </ol>
     */
    @PreDestroy
    public void stop() {
        log.info("{} Stopping...", LOG_PREFIX);
        running.set(false);

        if (emissionScheduler != null) {
            emissionScheduler.shutdown();
        }
        if (consumerExecutor != null) {
            consumerExecutor.shutdown();
            try {
                consumerExecutor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Emit any remaining data
        emitCurrentWindow();

        log.info("{} Stopped", LOG_PREFIX);
    }

    // ==================== KAFKA CONSUMER ====================

    /**
     * Main consumer loop - runs in thread pool.
     *
     * <p>Each thread independently:</p>
     * <ol>
     *   <li>Creates Kafka consumer</li>
     *   <li>Subscribes to input topic</li>
     *   <li>Polls for records (100ms timeout)</li>
     *   <li>Processes each orderbook snapshot via processOrderbook()</li>
     * </ol>
     */
    private void consumeLoop() {
        KafkaConsumer<String, OrderBookSnapshot> consumer = createConsumer();
        consumer.subscribe(Collections.singletonList(inputTopic));

        try {
            while (running.get()) {
                ConsumerRecords<String, OrderBookSnapshot> records = consumer.poll(Duration.ofMillis(100));

                for (ConsumerRecord<String, OrderBookSnapshot> record : records) {
                    try {
                        processOrderbook(record.value(), record.timestamp());
                    } catch (Exception e) {
                        log.error("{} Error processing orderbook: {}", LOG_PREFIX, e.getMessage());
                    }
                }
            }
        } finally {
            consumer.close();
        }
    }

    /**
     * Create Kafka consumer with appropriate configuration.
     *
     * @return configured KafkaConsumer instance
     */
    private KafkaConsumer<String, OrderBookSnapshot> createConsumer() {
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

    /**
     * Process a single orderbook snapshot.
     *
     * <p>Processing steps:</p>
     * <ol>
     *   <li>Null check and validation</li>
     *   <li>Update max event time atomically</li>
     *   <li>Initialize window tracker if needed</li>
     *   <li>Determine window assignment using strict event time</li>
     *   <li>Resolve symbol using ScripMetadataService</li>
     *   <li>Create or retrieve aggregation state</li>
     *   <li>Update aggregation state with orderbook data</li>
     * </ol>
     *
     * @param ob             incoming orderbook snapshot from Kafka
     * @param kafkaTimestamp Kafka record timestamp (event time)
     */
    private void processOrderbook(OrderBookSnapshot ob, long kafkaTimestamp) {
        if (ob == null || ob.getToken() <= 0) return;

        String key = ob.getExchange() + ":" + ob.getToken();
        Instant obTime = Instant.ofEpochMilli(kafkaTimestamp);

        // Trace logging
        boolean isSpecific = (traceSymbols != null && !traceSymbols.isEmpty() &&
                             traceSymbols.contains(String.valueOf(ob.getToken())));
        boolean shouldLog = isSpecific || (traceSymbols == null || traceSymbols.isEmpty());

        if (shouldLog) {
            int bidCount = ob.getBids() != null ? ob.getBids().size() : 0;
            int askCount = ob.getAsks() != null ? ob.getAsks().size() : 0;
            log.info("[OB-TRACE] Received Orderbook for {}: Bids={}, Asks={}, Time={}",
                ob.getToken(), bidCount, askCount, obTime);
        }

        // Update last data arrival time (Wall Clock)
        lastDataArrivalMillis.set(System.currentTimeMillis());

        // Track max event time ATOMICALLY
        maxEventTime.accumulateAndGet(obTime,
            (current, newTime) -> newTime.isAfter(current) ? newTime : current);

        // Initialize current window tracker if needed
        if (currentWindowStart.equals(Instant.EPOCH) ||
            obTime.isBefore(currentWindowStart.minusSeconds(60))) {
            currentWindowStart = Timeframe.M1.alignToWindowStart(obTime);
            currentWindowEnd = Timeframe.M1.getWindowEnd(currentWindowStart);
            log.info("{} [STRICT-EVENT-TIME] Initialized window from OB tick: {} - {}",
                LOG_PREFIX, formatTime(currentWindowStart), formatTime(currentWindowEnd));
        }

        // Determine which window this OB tick belongs to (STRICT EVENT TIME)
        final Instant tickWindowStart = Timeframe.M1.alignToWindowStart(obTime);
        final Instant tickWindowEnd = Timeframe.M1.getWindowEnd(tickWindowStart);

        // Composite key: exchange:token:windowStartMillis
        String stateKey = key + ":" + tickWindowStart.toEpochMilli();

        // Resolve symbol using ScripMetadataService (authoritative source from database)
        final String resolvedSymbol = scripMetadataService.getSymbolRoot(String.valueOf(ob.getToken()));

        OrderbookAggregateState state = aggregationState.computeIfAbsent(stateKey,
            k -> new OrderbookAggregateState(ob, tickWindowStart, tickWindowEnd, resolvedSymbol));

        state.update(ob, obTime);
    }

    // ==================== WINDOW EMISSION ====================

    /**
     * Check if current window should be emitted.
     *
     * <p>STRICT EVENT TIME MODE:</p>
     * <ul>
     *   <li>Reference time is always maxEventTime</li>
     *   <li>Emits windows that have closed relative to maxEventTime</li>
     *   <li>Uses while loop to emit ALL closed windows (catch-up support)</li>
     * </ul>
     *
     * <p>Bug #4 FIX: Improved idle flush logic</p>
     * <p>Bug #6 FIX: Uses ReentrantLock for thread-safe boundary updates</p>
     */
    private void checkWindowEmission() {
        windowLock.lock();
        try {
            Instant referenceTime = maxEventTime.get();

            // Bug #4 FIX: Improved idle flush - advance by ONE window only
            long timeSinceLastData = System.currentTimeMillis() - lastDataArrivalMillis.get();
            if (timeSinceLastData > 60000) {
                Instant maxIdleAdvance = currentWindowEnd.plusSeconds(60);
                if (maxIdleAdvance.isAfter(referenceTime)) {
                    referenceTime = maxIdleAdvance;
                    if (timeSinceLastData > 61000 && timeSinceLastData < 62000) {
                        log.info("{} [IDLE-FLUSH] System idle for {}ms, advancing by 1 window to {}",
                            LOG_PREFIX, timeSinceLastData, formatTime(referenceTime));
                    }
                }
            }

            if (referenceTime.equals(Instant.EPOCH)) {
                return;
            }

            // Bug #4 FIX: Fast-forward if too far behind (>2 hours)
            long windowsBehind = Duration.between(currentWindowEnd, referenceTime).toMinutes();
            if (windowsBehind > 120) {
                log.warn("{} [STRICT-EVENT-TIME] Window {} hours behind, fast-forwarding",
                    LOG_PREFIX, windowsBehind / 60);
                currentWindowStart = Timeframe.M1.alignToWindowStart(referenceTime.minusSeconds(120));
                currentWindowEnd = Timeframe.M1.getWindowEnd(currentWindowStart);
                Instant cutoff = currentWindowStart.minusSeconds(300);
                List<String> staleKeys = new ArrayList<>();
                for (Map.Entry<String, OrderbookAggregateState> e : aggregationState.entrySet()) {
                    if (e.getValue().getWindowEnd() != null && e.getValue().getWindowEnd().isBefore(cutoff)) {
                        staleKeys.add(e.getKey());
                    }
                }
                staleKeys.forEach(aggregationState::remove);
                log.info("{} [STRICT-EVENT-TIME] Fast-forwarded to: {} - {}, cleared {} stale states",
                    LOG_PREFIX, formatTime(currentWindowStart), formatTime(currentWindowEnd), staleKeys.size());
                return;
            }

            int windowsEmitted = 0;
            while (referenceTime.isAfter(currentWindowEnd.plusSeconds(2))) {
                if (windowsEmitted == 0) {
                    log.info("{} [STRICT-EVENT-TIME] Catching up: current window {} -> reference time {}",
                        LOG_PREFIX, formatTime(currentWindowEnd), formatTime(referenceTime));
                }

                emitCurrentWindow();
                windowsEmitted++;

                currentWindowStart = currentWindowEnd;
                currentWindowEnd = Timeframe.M1.getWindowEnd(currentWindowStart);

                if (windowsEmitted > 500) {
                    log.warn("{} [STRICT-EVENT-TIME] Too many windows ({}), breaking. Current: {}, Reference: {}",
                        LOG_PREFIX, windowsEmitted, formatTime(currentWindowEnd), formatTime(referenceTime));
                    break;
                }
            }

            if (windowsEmitted > 0) {
                log.debug("{} [STRICT-EVENT-TIME] Emitted {} windows, now at: {} - {}",
                    LOG_PREFIX, windowsEmitted, formatTime(currentWindowStart), formatTime(currentWindowEnd));
            }
        } finally {
            windowLock.unlock();
        }
    }

    /**
     * Emit all orderbook metrics for closed windows.
     *
     * <p>Emission steps:</p>
     * <ol>
     *   <li>Scan aggregation states for windows that should emit</li>
     *   <li>Convert states to OrderbookMetrics objects</li>
     *   <li>Batch save to MongoDB</li>
     *   <li>Cache to Redis</li>
     *   <li>Publish to Kafka topic</li>
     *   <li>Clean up stale states</li>
     * </ol>
     */
    private void emitCurrentWindow() {
        if (aggregationState.isEmpty()) return;

        Instant windowEnd = currentWindowEnd;
        List<OrderbookMetrics> metricsToSave = new ArrayList<>();
        List<String> keysToRemove = new ArrayList<>();

        for (Map.Entry<String, OrderbookAggregateState> entry : aggregationState.entrySet()) {
            String stateKey = entry.getKey();
            OrderbookAggregateState state = entry.getValue();

            // STRICT EVENT TIME CHECK
            boolean shouldEmit = state.getUpdateCount() > 0 &&
                                 state.getWindowEnd() != null &&
                                 !state.getWindowEnd().isAfter(windowEnd);

            if (shouldEmit) {
                OrderbookMetrics metrics = state.toOrderbookMetrics();
                metricsToSave.add(metrics);
                keysToRemove.add(stateKey);
            }
        }

        if (!metricsToSave.isEmpty()) {
            log.info("{} Emitting {} OB metrics for window end {}",
                LOG_PREFIX, metricsToSave.size(), formatTime(windowEnd));

            persistToMongoDB(metricsToSave);
            cacheToRedis(metricsToSave);
            publishToKafka(metricsToSave);
        }

        // Remove emitted states
        for (String key : keysToRemove) {
            aggregationState.remove(key);
        }

        // Bug #17 FIX: Cleanup stale states safely
        cleanupStaleStates();
    }

    // ==================== PERSISTENCE ====================

    /**
     * Batch save metrics to MongoDB.
     *
     * @param metrics list of metrics to save
     */
    private void persistToMongoDB(List<OrderbookMetrics> metrics) {
        try {
            orderbookRepository.saveAll(metrics);

            // Trace individual saves if enabled
            if (traceSymbols != null && !traceSymbols.isEmpty()) {
                for (OrderbookMetrics m : metrics) {
                    if (traceSymbols.contains(m.getSymbol()) || traceSymbols.contains(m.getScripCode())) {
                        log.info("[MONGO-WRITE] OrderbookMetrics saved for {} @ {} | OFI: {} | Spread: {}",
                            m.getSymbol(), formatTime(m.getWindowEnd()), m.getOfi(), m.getBidAskSpread());
                    }
                }
            }
        } catch (Exception e) {
            log.error("{} Failed to save to MongoDB: {}", LOG_PREFIX, e.getMessage());
        }
    }

    /**
     * Cache metrics to Redis.
     *
     * @param metrics list of metrics to cache
     */
    private void cacheToRedis(List<OrderbookMetrics> metrics) {
        try {
            for (OrderbookMetrics m : metrics) {
                redisCacheService.cacheOrderbookMetrics(m);
            }
        } catch (Exception e) {
            log.error("{} Failed to cache in Redis: {}", LOG_PREFIX, e.getMessage());
        }
    }

    /**
     * Publish metrics to output Kafka topic.
     *
     * @param metrics list of metrics to publish
     */
    private void publishToKafka(List<OrderbookMetrics> metrics) {
        try {
            for (OrderbookMetrics m : metrics) {
                kafkaTemplate.send(outputTopic, m.getScripCode(), m);
            }
            log.info("{} Published {} OB metrics", LOG_PREFIX, metrics.size());
        } catch (Exception e) {
            log.error("{} Failed to publish to Kafka: {}", LOG_PREFIX, e.getMessage());
        }
    }

    /**
     * Clean up stale aggregation states.
     *
     * <p>Bug #17 FIX: Collects keys first to avoid ConcurrentModificationException.</p>
     */
    private void cleanupStaleStates() {
        Instant cutoff = currentWindowEnd.minus(Duration.ofMinutes(5));
        List<String> staleKeys = new ArrayList<>();

        for (Map.Entry<String, OrderbookAggregateState> entry : aggregationState.entrySet()) {
            if (entry.getValue().getLastUpdate().isBefore(cutoff)) {
                staleKeys.add(entry.getKey());
            }
        }

        for (String key : staleKeys) {
            aggregationState.remove(key);
        }
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Format instant as IST time string (HH:mm:ss).
     *
     * @param instant instant to format
     * @return formatted time string
     */
    private String formatTime(Instant instant) {
        return ZonedDateTime.ofInstant(instant, IST).format(TIME_FMT);
    }

    // ==================== PUBLIC API ====================

    /**
     * Check if the aggregator is currently running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Get aggregator statistics.
     *
     * @return map containing aggregator stats
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("enabled", enabled);
        stats.put("running", running.get());
        stats.put("activeInstruments", aggregationState.size());
        stats.put("currentWindowStart", currentWindowStart != null ? currentWindowStart.toString() : null);
        stats.put("currentWindowEnd", currentWindowEnd != null ? currentWindowEnd.toString() : null);
        return stats;
    }
}
