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
import com.kotsin.consumer.aggregator.state.OptionMetadata;
import com.kotsin.consumer.aggregator.state.TickAggregateState;
import com.kotsin.consumer.event.CandleBoundaryPublisher;
import com.kotsin.consumer.logging.TraceContext;
import com.kotsin.consumer.model.TickCandle;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.repository.TickCandleRepository;
import com.kotsin.consumer.service.RedisCacheService;
import com.kotsin.consumer.service.ScripMetadataService;
import com.kotsin.consumer.signal.trigger.FudkiiSignalTrigger;

/**
 * TickAggregator - Independent Kafka consumer for tick data aggregation.
 *
 * <h2>Responsibilities</h2>
 * <ol>
 *   <li>Consume tick data from forwardtesting-data Kafka topic</li>
 *   <li>Aggregate ticks into 1-minute candles using event-time windowing</li>
 *   <li>Persist aggregated candles to MongoDB (tick_candles_1m collection)</li>
 *   <li>Cache hot data in Redis for downstream consumers</li>
 *   <li>Publish completed candles to Kafka for downstream processing</li>
 *   <li>Trigger FUDKII signal evaluation at candle boundaries</li>
 * </ol>
 *
 * <h2>Key Design Principles</h2>
 * <ul>
 *   <li><b>SIMPLE</b>: Single responsibility - only tick aggregation</li>
 *   <li><b>NO JOINS</b>: Does not join with orderbook or OI data (handled by UnifiedCandle builder)</li>
 *   <li><b>INDEPENDENT</b>: Runs in its own thread pool, no Kafka Streams state stores</li>
 *   <li><b>EVENT-TIME</b>: Uses kafkaTimestamp for window assignment (not wall clock)</li>
 *   <li><b>THREAD-SAFE</b>: ConcurrentHashMap for state, ReentrantLock for window boundaries</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 * <pre>
 * Kafka (forwardtesting-data)
 *         │
 *         ▼
 * ┌───────────────────┐
 * │  TickAggregator   │  ◄── Multiple consumer threads
 * │  (Event-Time)     │
 * └───────────────────┘
 *         │
 *         ▼
 * ┌───────────────────┐
 * │ TickAggregateState│  ◄── Per-instrument, per-window state
 * │ (Lee-Ready, VPIN) │
 * └───────────────────┘
 *         │
 *         ├──► MongoDB (tick_candles_1m)
 *         ├──► Redis (hot cache)
 *         ├──► Kafka (tick-candles-1m)
 *         └──► FUDKII Signal Trigger
 * </pre>
 *
 * <h2>v2.1 Quant Fixes</h2>
 * <ul>
 *   <li>Event time based windowing (uses tickDt, not wall clock)</li>
 *   <li>Lee-Ready trade classification (uses previous midpoint)</li>
 *   <li>Volume-based VPIN (10k share buckets, not time-based)</li>
 *   <li>Data quality validation (rejects bad ticks)</li>
 * </ul>
 *
 * <h2>Bug Fixes</h2>
 * <ul>
 *   <li><b>Bug #4</b>: Idle flush advances by one window at a time</li>
 *   <li><b>Bug #5</b>: Quantity validation in validateTick()</li>
 *   <li><b>Bug #6</b>: ReentrantLock for atomic compound window updates</li>
 *   <li><b>Bug #17</b>: Safe state cleanup using pre-collected key lists</li>
 * </ul>
 *
 * @see TickAggregateState
 * @see TickCandle
 * @see RedisCacheService
 */
@Component
@Slf4j
public class TickAggregator {

    // ==================== CONSTANTS ====================

    /**
     * Log prefix for all log messages from this aggregator.
     */
    private static final String LOG_PREFIX = "[TICK-AGG]";

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
     * Feature flag to enable/disable tick aggregator.
     * <p>Default: true</p>
     */
    @Value("${v2.tick.aggregator.enabled:true}")
    private boolean enabled;

    /**
     * Kafka bootstrap servers for consumer connection.
     * <p>Default: localhost:9092</p>
     */
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    /**
     * Input Kafka topic containing raw tick data.
     * <p>Default: forwardtesting-data</p>
     */
    @Value("${v2.tick.input.topic:forwardtesting-data}")
    private String inputTopic;

    /**
     * Kafka consumer group ID for this aggregator.
     * <p>Default: tick-aggregator-v2</p>
     */
    @Value("${v2.tick.consumer.group:tick-aggregator-v2}")
    private String consumerGroup;

    /**
     * Number of parallel consumer threads.
     * <p>Default: 4</p>
     */
    @Value("${v2.tick.aggregator.threads:4}")
    private int numThreads;

    /**
     * Output Kafka topic for aggregated candles.
     * <p>Default: tick-candles-1m</p>
     */
    @Value("${v2.tick.output.topic:tick-candles-1m}")
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
     * Repository for persisting TickCandle to MongoDB.
     */
    @Autowired
    private TickCandleRepository tickCandleRepository;

    /**
     * Service for caching candles and prices in Redis.
     */
    @Autowired
    private RedisCacheService redisCacheService;

    /**
     * Kafka template for publishing aggregated candles.
     */
    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Signal trigger for FUDKII strategy evaluation.
     */
    @Autowired
    private FudkiiSignalTrigger fudkiiSignalTrigger;

    /**
     * Publisher for candle boundary events (HTF analysis).
     */
    @Autowired
    private CandleBoundaryPublisher candleBoundaryPublisher;

    /**
     * Service for scrip metadata lookups (symbol, company name, etc.).
     * Used to get authoritative symbol from scripCode via database lookup.
     */
    @Autowired
    private ScripMetadataService scripMetadataService;

    // ==================== STATE MANAGEMENT ====================

    /**
     * Aggregation state per instrument-window pair.
     * <p>Key format: {@code "exchange:scripCode:windowStartMillis"}</p>
     * <p>Example: {@code "N:12345:1706789400000"}</p>
     * <p>Package-private for testing access.</p>
     */
    final ConcurrentHashMap<String, TickAggregateState> aggregationState = new ConcurrentHashMap<>();

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
     * Max event time observed across all ticks.
     * <p>Used as reference for strict event-time window emission.</p>
     * <p>Updates atomically via {@code accumulateAndGet()}.</p>
     */
    private final AtomicReference<Instant> maxEventTime = new AtomicReference<>(Instant.EPOCH);

    /**
     * Wall-clock time of last data arrival.
     * <p>Used for idle detection when no new data arrives.</p>
     * <p>Distinct from event time to handle replay scenarios.</p>
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
     * Start the tick aggregator.
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

        log.info("{} Starting with threads={}, topic={}, consumerGroup={}, MODE=STRICT_EVENT_TIME",
            LOG_PREFIX, numThreads, inputTopic, consumerGroup);

        running.set(true);

        // Initialize current window from epoch - will catch up based on data
        currentWindowStart = Instant.EPOCH;
        currentWindowEnd = Instant.EPOCH;
        maxEventTime.set(Instant.EPOCH);
        lastDataArrivalMillis.set(System.currentTimeMillis());

        log.info("{} [STRICT-EVENT-TIME] Window will be initialized from first tick event time", LOG_PREFIX);

        // Start consumer threads
        consumerExecutor = Executors.newFixedThreadPool(numThreads);
        for (int i = 0; i < numThreads; i++) {
            consumerExecutor.submit(this::consumeLoop);
        }

        // Start window emission scheduler (runs every second to check window close)
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
     * Stop the tick aggregator gracefully.
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
     *   <li>Processes each tick via processTick()</li>
     *   <li>Logs stats every 30 seconds</li>
     * </ol>
     */
    private void consumeLoop() {
        String threadName = Thread.currentThread().getName();
        log.info("{} Consumer thread {} starting, connecting to {}", LOG_PREFIX, threadName, bootstrapServers);

        KafkaConsumer<String, TickData> consumer = createConsumer();
        consumer.subscribe(Collections.singletonList(inputTopic));
        log.info("{} Thread {} subscribed to topic: {}", LOG_PREFIX, threadName, inputTopic);

        long pollCount = 0;
        long lastLogTime = System.currentTimeMillis();
        int recordsReceived = 0;

        try {
            while (running.get()) {
                ConsumerRecords<String, TickData> records = consumer.poll(Duration.ofMillis(100));
                pollCount++;
                recordsReceived += records.count();

                // Log stats every 30 seconds
                if (System.currentTimeMillis() - lastLogTime > 30000) {
                    log.info("{} Thread {} stats: polls={}, recordsReceived={}, activeInstruments={}",
                        LOG_PREFIX, threadName, pollCount, recordsReceived, aggregationState.size());
                    lastLogTime = System.currentTimeMillis();
                    pollCount = 0;
                    recordsReceived = 0;
                }

                for (ConsumerRecord<String, TickData> record : records) {
                    try {
                        processTick(record.value(), record.timestamp());
                    } catch (Exception e) {
                        log.error("{} Error processing tick: {}", LOG_PREFIX, e.getMessage(), e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("{} Thread {} consumer loop failed: {}", LOG_PREFIX, threadName, e.getMessage(), e);
        } finally {
            consumer.close();
            log.info("{} Thread {} consumer closed", LOG_PREFIX, threadName);
        }
    }

    /**
     * Create Kafka consumer with appropriate configuration.
     *
     * <p>Configuration:</p>
     * <ul>
     *   <li>JSON deserializer for TickData</li>
     *   <li>Auto-commit enabled</li>
     *   <li>Max poll records: 2000</li>
     *   <li>Auto offset reset: earliest</li>
     * </ul>
     *
     * @return configured KafkaConsumer instance
     */
    private KafkaConsumer<String, TickData> createConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.kotsin.consumer.model,com.kotsin.optionDataProducer.model");
        // FIX: Specify the target type explicitly since producer doesn't send type headers
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, TickData.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 2000);

        return new KafkaConsumer<>(props);
    }

    // ==================== TICK PROCESSING ====================

    /**
     * Process a single tick with data quality validation.
     *
     * <p>Processing steps:</p>
     * <ol>
     *   <li>Null check and validation</li>
     *   <li>Data quality validation (price &gt; 0, quantity &gt; 0)</li>
     *   <li>Update max event time atomically</li>
     *   <li>Initialize window tracker if needed</li>
     *   <li>Determine window assignment using strict event time</li>
     *   <li>Create or retrieve aggregation state</li>
     *   <li>Update aggregation state with tick data</li>
     * </ol>
     *
     * <p>Package-private for testing.</p>
     *
     * @param tick           incoming tick data from Kafka
     * @param kafkaTimestamp Kafka record timestamp (event time)
     */
    void processTick(TickData tick, long kafkaTimestamp) {
        if (tick == null || tick.getScripCode() == null) return;

        // DATA QUALITY VALIDATION (v2.1)
        String validationError = validateTick(tick);
        if (validationError != null) {
            log.debug("{} Rejected tick {}: {}", LOG_PREFIX, tick.getScripCode(), validationError);
            return;
        }

        //good key building as nse or mcx might have same scripcode so use of exchange is good idea
        String key = buildKey(tick);
        // Extract symbol from ScripMetadataService (authoritative source from database)
        String symbol = scripMetadataService.getSymbolRoot(tick.getScripCode(), tick.getCompanyName());

        // [TICK-TRACE] Log everything if list is empty, or specific symbol
        boolean specificSymbol = traceSymbols.contains(symbol);
        boolean shouldLog = specificSymbol || traceSymbols.isEmpty();

        if (shouldLog) {
            log.info("[TICK-TRACE] Received tick for {}: Price={}, Vol={}, Time={}",
                symbol, tick.getLastRate(), tick.getLastQuantity(), tick.getTickDt());
        }

        // Start trace context
        TraceContext.start(symbol, "1m");
        try {
            // Use Kafka timestamp for consistency with OI and Orderbook aggregators
            Instant tickTime = Instant.ofEpochMilli(kafkaTimestamp);

            // Update last data arrival time (Wall Clock)
            lastDataArrivalMillis.set(System.currentTimeMillis());

            // Track max event time ATOMICALLY
            maxEventTime.accumulateAndGet(tickTime,
                (current, newTime) -> newTime.isAfter(current) ? newTime : current);

            // Initialize current window tracker if needed
            if (currentWindowStart.equals(Instant.EPOCH) ||
                tickTime.isBefore(currentWindowStart.minusSeconds(60))) {
                currentWindowStart = Timeframe.M1.alignToWindowStart(tickTime);
                currentWindowEnd = Timeframe.M1.getWindowEnd(currentWindowStart);
                log.info("{} [STRICT-EVENT-TIME] Initialized window from tick: {} - {}",
                    LOG_PREFIX, formatTime(currentWindowStart), formatTime(currentWindowEnd));
            }

            // Determine which window this tick belongs to (STRICT EVENT TIME)
            final Instant tickWindowStart = Timeframe.M1.alignToWindowStart(tickTime);
            final Instant tickWindowEnd = Timeframe.M1.getWindowEnd(tickWindowStart);

            // Composite key: scripCode:windowStartMillis
            final String stateKey = key + ":" + tickWindowStart.toEpochMilli();

            // Get or create aggregation state for this (instrument, window) pair
            // Pass pre-resolved symbol and option metadata from ScripMetadataService
            final String resolvedSymbol = symbol;

            // Get option metadata from Scrip database (authoritative source for strike, expiry, lotSize)
            final OptionMetadata optionMeta = scripMetadataService.isOption(tick.getScripCode())
                ? OptionMetadata.fromScrip(scripMetadataService.getScripByCode(tick.getScripCode()))
                : null;

            TickAggregateState state = aggregationState.computeIfAbsent(stateKey,
                k -> new TickAggregateState(tick, tickWindowStart, tickWindowEnd, resolvedSymbol, optionMeta));

            // Update aggregation
            state.update(tick, tickTime);
        } finally {
            TraceContext.clear();
        }
    }

    /**
     * Validate tick data quality.
     *
     * <p>Validation rules (v2.1):</p>
     * <ul>
     *   <li>Price must be positive (&gt; 0)</li>
     *   <li>Quantity must be positive (&gt; 0) - Bug #5 FIX</li>
     * </ul>
     *
     * @param tick tick to validate
     * @return error message if invalid, null if valid
     */
    private String validateTick(TickData tick) {
        // Check price > 0
        if (tick.getLastRate() <= 0) {
            return "Price <= 0";
        }

        return null; // Valid
    }

    // ==================== WINDOW EMISSION ====================

    /**
     * Check if current window should be emitted.
     *
     * <p>STRICT EVENT TIME MODE:</p>
     * <ul>
     *   <li>Reference time is always maxEventTime (derived from latest data)</li>
     *   <li>Emits windows that have closed relative to maxEventTime</li>
     *   <li>Uses while loop to emit ALL closed windows (catch-up support)</li>
     * </ul>
     *
     * <p>Bug #4 FIX: Improved idle flush logic</p>
     * <ul>
     *   <li>During idle periods, advances by ONE window at a time</li>
     *   <li>Prevents massive catch-up loops when wall-clock is far ahead</li>
     * </ul>
     *
     * <p>Bug #6 FIX: Uses ReentrantLock for thread-safe boundary updates</p>
     *
     * <p>Package-private for testing.</p>
     */
    void checkWindowEmission() {
        // Bug #6 FIX: Use lock for thread-safe window boundary updates
        windowLock.lock();
        try {
            // STRICT EVENT TIME: Reference is typically maxEventTime
            Instant referenceTime = maxEventTime.get();

            // Bug #4 FIX: Improved idle flush logic
            long timeSinceLastData = System.currentTimeMillis() - lastDataArrivalMillis.get();
            if (timeSinceLastData > 60000) { // 1 minute idle
                // During idle, only advance event time by one window period
                Instant maxIdleAdvance = currentWindowEnd.plusSeconds(60);
                if (maxIdleAdvance.isAfter(referenceTime)) {
                    referenceTime = maxIdleAdvance;
                    if (timeSinceLastData > 61000 && timeSinceLastData < 62000) {
                        log.info("{} [IDLE-FLUSH] System idle for {}ms, advancing by 1 window to {}",
                            LOG_PREFIX, timeSinceLastData, formatTime(referenceTime));
                    }
                }
            }

            // Skip if no data yet
            if (referenceTime.equals(Instant.EPOCH)) {
                return;
            }

            // Bug #4 FIX: Skip processing if data is too old (likely historical replay)
            long windowsBehind = Duration.between(currentWindowEnd, referenceTime).toMinutes();
            if (windowsBehind > 120) {
                log.warn("{} [STRICT-EVENT-TIME] Window {} hours behind reference {}, fast-forwarding",
                    LOG_PREFIX, windowsBehind / 60, formatTime(referenceTime));
                // Skip to 2 minutes before reference time
                currentWindowStart = Timeframe.M1.alignToWindowStart(referenceTime.minusSeconds(120));
                currentWindowEnd = Timeframe.M1.getWindowEnd(currentWindowStart);
                // Clear old states that would never be emitted
                Instant cutoff = currentWindowStart.minusSeconds(300);
                aggregationState.entrySet().removeIf(e ->
                    e.getValue().getWindowEnd() != null &&
                    e.getValue().getWindowEnd().isBefore(cutoff));
                log.info("{} [STRICT-EVENT-TIME] Fast-forwarded to window: {} - {}",
                    LOG_PREFIX, formatTime(currentWindowStart), formatTime(currentWindowEnd));
                return;
            }

            // Use WHILE loop to emit ALL closed windows up to referenceTime
            int windowsEmitted = 0;
            while (referenceTime.isAfter(currentWindowEnd.plusSeconds(2))) {
                if (windowsEmitted == 0) {
                    log.info("{} [STRICT-EVENT-TIME] Catching up: current window {} -> reference time {}",
                        LOG_PREFIX, formatTime(currentWindowEnd), formatTime(referenceTime));
                }

                emitCurrentWindow();
                windowsEmitted++;

                // Move to next window
                currentWindowStart = currentWindowEnd;
                currentWindowEnd = Timeframe.M1.getWindowEnd(currentWindowStart);

                // Safety: limit iterations to prevent infinite loop
                if (windowsEmitted > 500) {
                    log.warn("{} [STRICT-EVENT-TIME] Too many windows to emit ({}), breaking. Current: {}, Reference: {}",
                        LOG_PREFIX, windowsEmitted, formatTime(currentWindowEnd), formatTime(referenceTime));
                    break;
                }
            }

            if (windowsEmitted > 0) {
                log.info("{} [STRICT-EVENT-TIME] Emitted {} windows, now at: {} - {}",
                    LOG_PREFIX, windowsEmitted, formatTime(currentWindowStart), formatTime(currentWindowEnd));
            }
        } finally {
            windowLock.unlock();
        }
    }

    /**
     * Emit all candles for closed windows.
     *
     * <p>Emission steps:</p>
     * <ol>
     *   <li>Scan aggregation states for windows that should emit</li>
     *   <li>Convert states to TickCandle objects</li>
     *   <li>Batch save to MongoDB</li>
     *   <li>Cache to Redis (hot cache + price cache)</li>
     *   <li>Trigger FUDKII signal evaluation</li>
     *   <li>Publish boundary events for HTF analysis</li>
     *   <li>Publish to Kafka topic for downstream consumers</li>
     *   <li>Clean up stale states (Bug #17 FIX: safe cleanup)</li>
     * </ol>
     */
    private void emitCurrentWindow() {
        if (aggregationState.isEmpty()) return;

        Instant windowEnd = currentWindowEnd;
        List<TickCandle> candlesToSave = new ArrayList<>();
        List<String> keysToRemove = new ArrayList<>();

        // Collect candles to emit
        for (Map.Entry<String, TickAggregateState> entry : aggregationState.entrySet()) {
            String stateKey = entry.getKey();
            TickAggregateState state = entry.getValue();

            // STRICT EVENT TIME CHECK:
            // Emit if state's window has fully passed relative to our tracking window
            boolean shouldEmit = state.getTickCount() > 0 &&
                                 state.getWindowEnd() != null &&
                                 !state.getWindowEnd().isAfter(windowEnd);

            if (shouldEmit) {
                TickCandle candle = state.toTickCandle();
                candlesToSave.add(candle);
                keysToRemove.add(stateKey);
            }
        }

        // Remove emitted states
        for (String key : keysToRemove) {
            aggregationState.remove(key);
        }

        if (!candlesToSave.isEmpty()) {
            log.info("{} Emitting {} candles for windows up to {}, removed {} states",
                LOG_PREFIX, candlesToSave.size(), formatTime(windowEnd), keysToRemove.size());
        }

        // Persist and publish
        if (!candlesToSave.isEmpty()) {
            persistToMongoDB(candlesToSave);
            cacheToRedis(candlesToSave);
            triggerFudkiiSignals(candlesToSave);
            publishBoundaryEvents(candlesToSave);
            publishToKafka(candlesToSave);
        }

        // Bug #17 FIX: Clean up old states safely by collecting keys first
        cleanupStaleStates();
    }

    // ==================== PERSISTENCE ====================

    /**
     * Batch save candles to MongoDB.
     *
     * @param candles list of candles to save
     */
    private void persistToMongoDB(List<TickCandle> candles) {
        try {
            tickCandleRepository.saveAll(candles);
            log.info("{} Saved {} candles to MongoDB", LOG_PREFIX, candles.size());

            // Trace individual saves if enabled
            if (traceSymbols != null && !traceSymbols.isEmpty()) {
                for (TickCandle c : candles) {
                    if (traceSymbols.contains(c.getSymbol()) || traceSymbols.contains(c.getScripCode())) {
                        log.info("[MONGO-WRITE] TickCandle saved for {} @ {} | Close: {} | Vol: {}",
                            c.getSymbol(), formatTime(c.getWindowEnd()), c.getClose(), c.getVolume());
                    }
                }
            }
        } catch (Exception e) {
            log.error("{} Failed to save to MongoDB: {}", LOG_PREFIX, e.getMessage());
        }
    }

    /**
     * Cache candles and prices to Redis.
     *
     * @param candles list of candles to cache
     */
    private void cacheToRedis(List<TickCandle> candles) {
        try {
            for (TickCandle candle : candles) {
                redisCacheService.cacheTickCandle(candle);
                // v2.1: Cache price for OI interpretation (keyed by scripCode)
                redisCacheService.cachePrice(candle.getScripCode(), candle.getClose());
            }
            log.debug("{} Cached {} candles in Redis", LOG_PREFIX, candles.size());
        } catch (Exception e) {
            log.error("{} Failed to cache in Redis: {}", LOG_PREFIX, e.getMessage());
        }
    }

    /**
     * Trigger FUDKII signal evaluation for each candle.
     *
     * @param candles list of candles to evaluate
     */
    private void triggerFudkiiSignals(List<TickCandle> candles) {
        try {
            for (TickCandle candle : candles) {
                if (candle.getScripCode() != null) {
                    fudkiiSignalTrigger.onCandleClose(candle.getScripCode(), candle);
                }
            }
        } catch (Exception e) {
            log.error("{} Failed to check FUDKII trigger: {}", LOG_PREFIX, e.getMessage());
        }
    }

    /**
     * Publish boundary events for higher timeframe analysis.
     *
     * @param candles list of candles to publish events for
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

    /**
     * Publish candles to output Kafka topic.
     *
     * @param candles list of candles to publish
     */
    private void publishToKafka(List<TickCandle> candles) {
        try {
            for (TickCandle candle : candles) {
                kafkaTemplate.send(outputTopic, candle.getScripCode(), candle);
            }
            log.info("{} Published {} candles to Kafka topic {}", LOG_PREFIX, candles.size(), outputTopic);
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
        // STRICT EVENT TIME: Use currentWindowEnd (which tracks emission progress)
        Instant cutoff = currentWindowEnd.minus(Duration.ofMinutes(5));
        List<String> staleKeys = new ArrayList<>();

        for (Map.Entry<String, TickAggregateState> entry : aggregationState.entrySet()) {
            if (entry.getValue().getLastUpdate().isBefore(cutoff)) {
                staleKeys.add(entry.getKey());
            }
        }

        for (String key : staleKeys) {
            aggregationState.remove(key);
        }

        if (!staleKeys.isEmpty()) {
            log.info("{} [CLEANUP] Removed {} old states, cutoff={} (based on emission progress {})",
                LOG_PREFIX, staleKeys.size(), formatTime(cutoff), formatTime(currentWindowEnd));
        }
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Build aggregation state key from tick data.
     *
     * @param tick tick data
     * @return key in format "exchange:scripCode"
     */
    private String buildKey(TickData tick) {
        return tick.getExchange() + ":" + tick.getScripCode();
    }

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
