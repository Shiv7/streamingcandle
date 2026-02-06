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
import com.kotsin.consumer.aggregator.state.OIAggregateState;
import com.kotsin.consumer.aggregator.state.OptionMetadata;
import com.kotsin.consumer.event.CandleBoundaryPublisher;
import com.kotsin.consumer.model.OIMetrics;
import com.kotsin.consumer.model.OpenInterest;
import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.model.TimeframeBoundary;
import com.kotsin.consumer.repository.OIMetricsRepository;
import com.kotsin.consumer.service.RedisCacheService;
import com.kotsin.consumer.service.ScripMetadataService;

/**
 * OIAggregator - Independent Kafka consumer for Open Interest data aggregation.
 *
 * <h2>Responsibilities</h2>
 * <ol>
 *   <li>Consume OI snapshots from OpenInterest Kafka topic</li>
 *   <li>Aggregate OI data into 1-minute metrics using event-time windowing</li>
 *   <li>Calculate OI interpretation (LONG_BUILDUP, SHORT_COVERING, etc.)</li>
 *   <li>Track OI velocity and acceleration</li>
 *   <li>Persist aggregated metrics to MongoDB (oi_metrics_1m collection)</li>
 *   <li>Cache hot data in Redis for downstream consumers</li>
 *   <li>Publish completed metrics to Kafka for downstream processing</li>
 * </ol>
 *
 * <h2>Key Design Principles</h2>
 * <ul>
 *   <li><b>SIMPLE</b>: Single responsibility - only OI aggregation</li>
 *   <li><b>NO JOINS</b>: Does not join with tick or orderbook data (handled by UnifiedCandle builder)</li>
 *   <li><b>INDEPENDENT</b>: Runs in its own thread pool, no Kafka Streams state stores</li>
 *   <li><b>EVENT-TIME</b>: Uses kafkaTimestamp for window assignment (not wall clock)</li>
 *   <li><b>THREAD-SAFE</b>: ConcurrentHashMap for state, ReentrantLock for window boundaries</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 * <pre>
 * Kafka (OpenInterest)
 *         │
 *         ▼
 * ┌───────────────────┐
 * │   OIAggregator    │  ◄── Multiple consumer threads
 * │   (Event-Time)    │
 * └───────────────────┘
 *         │
 *         ▼
 * ┌───────────────────┐
 * │ OIAggregateState  │  ◄── Per-instrument, per-window state
 * │ (Interpretation)  │
 * └───────────────────┘
 *         │
 *         ├──► MongoDB (oi_metrics_1m)
 *         ├──► Redis (hot cache)
 *         └──► Kafka (oi-metrics-1m)
 * </pre>
 *
 * <h2>OI Interpretation Matrix</h2>
 * <pre>
 * OI ↑ + Price ↑ = LONG_BUILDUP   (Bullish)
 * OI ↓ + Price ↑ = SHORT_COVERING (Bullish)
 * OI ↑ + Price ↓ = SHORT_BUILDUP  (Bearish)
 * OI ↓ + Price ↓ = LONG_UNWINDING (Bearish)
 * </pre>
 *
 * <h2>v2.1 Quant Fixes</h2>
 * <ul>
 *   <li>Immediate OI interpretation using cached price from Redis</li>
 *   <li>No longer defers interpretation to query time</li>
 * </ul>
 *
 * <h2>Bug Fixes</h2>
 * <ul>
 *   <li><b>Bug #4</b>: Idle flush advances by one window at a time</li>
 *   <li><b>Bug #6</b>: ReentrantLock for atomic compound window updates</li>
 *   <li><b>Bug #13</b>: Logging for price cache misses during interpretation</li>
 *   <li><b>Bug #17</b>: Safe state cleanup using pre-collected key lists</li>
 * </ul>
 *
 * @see OIAggregateState
 * @see OIMetrics
 * @see RedisCacheService
 */
@Component
@Slf4j
public class OIAggregator {

    // ==================== CONSTANTS ====================

    /**
     * Log prefix for all log messages from this aggregator.
     */
    private static final String LOG_PREFIX = "[OI-AGG]";

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
     * Feature flag to enable/disable OI aggregator.
     * <p>Default: true</p>
     */
    @Value("${v2.oi.aggregator.enabled:true}")
    private boolean enabled;

    /**
     * Kafka bootstrap servers for consumer connection.
     * <p>Default: localhost:9092</p>
     */
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    /**
     * Input Kafka topic containing OI data.
     * <p>Default: OpenInterest</p>
     */
    @Value("${v2.oi.input.topic:OpenInterest}")
    private String inputTopic;

    /**
     * Kafka consumer group ID for this aggregator.
     * <p>Default: oi-aggregator-v2</p>
     */
    @Value("${v2.oi.consumer.group:oi-aggregator-v2}")
    private String consumerGroup;

    /**
     * Number of parallel consumer threads.
     * <p>Default: 2</p>
     */
    @Value("${v2.oi.aggregator.threads:16}")
    private int numThreads;

    /**
     * Output Kafka topic for aggregated OI metrics.
     * <p>Default: oi-metrics-1m</p>
     */
    @Value("${v2.oi.output.topic:oi-metrics-1m}")
    private String outputTopic;

    /**
     * Comma-separated list of scripcodes for trace logging.
     * <p>Empty = trace all scripcodes (FULL MODE)</p>
     */
    @Value("${logging.trace.scripcodes:}")
    private String traceScripCodesStr;

    /**
     * Parsed set of scripcodes for trace logging.
     */
    private Set<String> traceScripCodes;

    // ==================== INJECTED DEPENDENCIES ====================

    /**
     * Repository for persisting OIMetrics to MongoDB.
     */
    @Autowired
    private OIMetricsRepository oiRepository;

    /**
     * Service for caching metrics and prices in Redis.
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

    /**
     * Bug #8: Publishes boundary events for higher timeframe analysis.
     */
    @Autowired
    private CandleBoundaryPublisher candleBoundaryPublisher;

    // ==================== STATE MANAGEMENT ====================

    /**
     * Aggregation state per instrument-window pair.
     * <p>Key format: {@code "exchange:token:windowStartMillis"}</p>
     * <p>Example: {@code "N:12345:1706789400000"}</p>
     */
    private final ConcurrentHashMap<String, OIAggregateState> aggregationState = new ConcurrentHashMap<>();

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
     * Max event time observed across all OI snapshots.
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
     * Start the OI aggregator.
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

        log.info("{} [STRICT-EVENT-TIME] Window will be initialized from first OI event time", LOG_PREFIX);

        // Start consumer threads
        consumerExecutor = Executors.newFixedThreadPool(numThreads);
        for (int i = 0; i < numThreads; i++) {
            consumerExecutor.submit(this::consumeLoop);
        }

        // Start window emission scheduler
        emissionScheduler = Executors.newSingleThreadScheduledExecutor();
        emissionScheduler.scheduleAtFixedRate(this::checkWindowEmission, 1, 1, TimeUnit.SECONDS);

        log.info("{} Started successfully", LOG_PREFIX);

        // Parse trace scripcodes (Bug #2: trace by scripcode, not symbol)
        this.traceScripCodes = new HashSet<>();
        if (traceScripCodesStr != null && !traceScripCodesStr.isBlank()) {
            String[] parts = traceScripCodesStr.split(",");
            for (String part : parts) {
                traceScripCodes.add(part.trim());
            }
            log.info("{} Trace logging enabled for scripcodes: {}", LOG_PREFIX, traceScripCodes);
        } else {
            log.info("{} Trace logging enabled for ALL scripcodes (FULL - NO SAMPLING)", LOG_PREFIX);
        }
    }

    /**
     * Stop the OI aggregator gracefully.
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
     *   <li>Processes each OI snapshot via processOI()</li>
     * </ol>
     */
    private void consumeLoop() {
        KafkaConsumer<String, OpenInterest> consumer = createConsumer();
        consumer.subscribe(Collections.singletonList(inputTopic));

        try {
            while (running.get()) {
                ConsumerRecords<String, OpenInterest> records = consumer.poll(Duration.ofMillis(100));

                for (ConsumerRecord<String, OpenInterest> record : records) {
                    try {
                        processOI(record.value(), record.timestamp());
                    } catch (Exception e) {
                        log.error("{} Error processing OI: {}", LOG_PREFIX, e.getMessage());
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
    private KafkaConsumer<String, OpenInterest> createConsumer() {
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

    /**
     * Process a single OI snapshot.
     *
     * <p>Processing steps:</p>
     * <ol>
     *   <li>Null check and validation</li>
     *   <li>Update max event time atomically</li>
     *   <li>Initialize window tracker if needed</li>
     *   <li>Determine window assignment using strict event time</li>
     *   <li>Resolve symbol using ScripMetadataService</li>
     *   <li>Create or retrieve aggregation state</li>
     *   <li>Update aggregation state with OI data</li>
     * </ol>
     *
     * @param oi             incoming OI snapshot from Kafka
     * @param kafkaTimestamp Kafka record timestamp (event time)
     */
    private void processOI(OpenInterest oi, long kafkaTimestamp) {
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

        // Update last data arrival time (Wall Clock)
        lastDataArrivalMillis.set(System.currentTimeMillis());

        // Track max event time ATOMICALLY
        maxEventTime.accumulateAndGet(oiTime,
            (current, newTime) -> newTime.isAfter(current) ? newTime : current);

        // Initialize current window tracker if needed
        if (currentWindowStart.equals(Instant.EPOCH) ||
            oiTime.isBefore(currentWindowStart.minusSeconds(60))) {
            currentWindowStart = Timeframe.M1.alignToWindowStart(oiTime);
            currentWindowEnd = Timeframe.M1.getWindowEnd(currentWindowStart);
            log.info("{} [STRICT-EVENT-TIME] Initialized window from OI tick: {} - {}",
                LOG_PREFIX, formatTime(currentWindowStart), formatTime(currentWindowEnd));
        }

        // Determine which window this OI tick belongs to (STRICT EVENT TIME)
        final Instant tickWindowStart = Timeframe.M1.alignToWindowStart(oiTime);
        final Instant tickWindowEnd = Timeframe.M1.getWindowEnd(tickWindowStart);

        // Composite key: exchange:token:windowStartMillis
        String stateKey = key + ":" + tickWindowStart.toEpochMilli();

        // Bug #24: token == scripCode in 5paisa's system; we convert to String for consistency
        final String scripCode = String.valueOf(oi.getToken());
        // Resolve symbol using ScripMetadataService (authoritative source from database)
        final String resolvedSymbol = scripMetadataService.getSymbolRoot(scripCode);

        // Get OptionMetadata from Scrip if this is an option (PREFERRED over companyName parsing)
        final OptionMetadata optionMeta = scripMetadataService.isOption(scripCode)
            ? OptionMetadata.fromScrip(scripMetadataService.getScripByCode(scripCode))
            : null;

        OIAggregateState state = aggregationState.computeIfAbsent(stateKey,
            k -> new OIAggregateState(oi, tickWindowStart, tickWindowEnd, resolvedSymbol, optionMeta));

        state.update(oi, oiTime);
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
                for (Map.Entry<String, OIAggregateState> e : aggregationState.entrySet()) {
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
     * Emit all OI metrics for closed windows.
     *
     * <p>Emission steps:</p>
     * <ol>
     *   <li>Scan aggregation states for windows that should emit</li>
     *   <li>Convert states to OIMetrics objects (with interpretation)</li>
     *   <li>Batch save to MongoDB</li>
     *   <li>Cache to Redis</li>
     *   <li>Publish to Kafka topic</li>
     *   <li>Clean up stale states</li>
     * </ol>
     */
    private void emitCurrentWindow() {
        if (aggregationState.isEmpty()) return;

        Instant windowEnd = currentWindowEnd;
        List<OIMetrics> metricsToSave = new ArrayList<>();
        List<String> keysToRemove = new ArrayList<>();

        for (Map.Entry<String, OIAggregateState> entry : aggregationState.entrySet()) {
            String stateKey = entry.getKey();
            OIAggregateState state = entry.getValue();

            // STRICT EVENT TIME CHECK
            boolean shouldEmit = state.getUpdateCount() > 0 &&
                                 state.getWindowEnd() != null &&
                                 !state.getWindowEnd().isAfter(windowEnd);

            if (shouldEmit) {
                // Calculate interpretation NOW using cached price
                OIMetrics metrics = state.toOIMetrics(redisCacheService);
                metricsToSave.add(metrics);
                keysToRemove.add(stateKey);
            }
        }

        if (!metricsToSave.isEmpty()) {
            log.info("{} Emitting {} OI metrics for window end {}",
                LOG_PREFIX, metricsToSave.size(), formatTime(windowEnd));

            persistToMongoDB(metricsToSave);
            cacheToRedis(metricsToSave);
            publishToKafka(metricsToSave);

            // Bug #8: Publish boundary events for OI data
            for (OIMetrics m : metricsToSave) {
                candleBoundaryPublisher.onMetricsClose(
                    m.getScripCode(), m.getExchange(), m.getWindowEnd(), "OI");
            }
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
    private void persistToMongoDB(List<OIMetrics> metrics) {
        try {
            oiRepository.saveAll(metrics);

            // Trace individual saves if enabled
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

    /**
     * Cache metrics to Redis.
     *
     * @param metrics list of metrics to cache
     */
    private void cacheToRedis(List<OIMetrics> metrics) {
        try {
            for (OIMetrics m : metrics) {
                redisCacheService.cacheOIMetrics(m);
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
    private void publishToKafka(List<OIMetrics> metrics) {
        try {
            for (OIMetrics m : metrics) {
                kafkaTemplate.send(outputTopic, m.getScripCode(), m);
            }
            log.info("{} Published {} OI metrics", LOG_PREFIX, metrics.size());
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

        for (Map.Entry<String, OIAggregateState> entry : aggregationState.entrySet()) {
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
