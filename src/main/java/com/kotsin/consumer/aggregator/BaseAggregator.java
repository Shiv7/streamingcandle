package com.kotsin.consumer.aggregator;

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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;

import com.kotsin.consumer.event.CandleBoundaryPublisher;
import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.service.RedisCacheService;
import com.kotsin.consumer.service.ScripMetadataService;

/**
 * BaseAggregator - Abstract base class for all event-time windowed aggregators.
 *
 * <h2>Extracted Common Logic</h2>
 * <ul>
 *   <li>Kafka consumer thread pool lifecycle (start/stop)</li>
 *   <li>Consume loop with stats logging</li>
 *   <li>Strict event-time window tracking and emission</li>
 *   <li>Idle flush with single-window advancement</li>
 *   <li>Fast-forward for historical replay catch-up</li>
 *   <li>Stale state cleanup</li>
 *   <li>Kafka publishing</li>
 *   <li>Trace scripcode logging configuration</li>
 * </ul>
 *
 * <h2>Type Parameters</h2>
 * <ul>
 *   <li>{@code T} - Input data type (TickData, OpenInterest, OrderBookSnapshot)</li>
 *   <li>{@code S} - Aggregate state type implementing {@link WindowedState}</li>
 *   <li>{@code M} - Output metrics type (TickCandle, OIMetrics, OrderbookMetrics)</li>
 * </ul>
 *
 * <h2>Subclass Contract</h2>
 * <p>Subclasses must:</p>
 * <ol>
 *   <li>Provide configuration via abstract getter methods</li>
 *   <li>Implement data-specific consumer creation and record processing</li>
 *   <li>Implement state-to-metrics conversion and persistence</li>
 *   <li>Optionally override {@link #postEmission(List)} and {@link #onStart()}</li>
 * </ol>
 *
 * @param <T> input data type from Kafka
 * @param <S> aggregate state type
 * @param <M> output metrics type
 * @see WindowedState
 * @see TickAggregator
 * @see OIAggregator
 * @see OrderbookAggregator
 */
@Slf4j
public abstract class BaseAggregator<T, S extends WindowedState, M> {

    // ==================== CONSTANTS ====================

    protected static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    protected static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ==================== COMMON CONFIGURATION ====================

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    protected String bootstrapServers;

    @Value("${logging.trace.scripcodes:}")
    private String traceScripCodesStr;

    protected Set<String> traceScripCodes;

    // ==================== COMMON DEPENDENCIES ====================

    @Autowired
    protected KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    protected RedisCacheService redisCacheService;

    @Autowired
    protected ScripMetadataService scripMetadataService;

    @Autowired
    protected CandleBoundaryPublisher candleBoundaryPublisher;

    // ==================== STATE MANAGEMENT ====================

    protected final ConcurrentHashMap<String, S> aggregationState = new ConcurrentHashMap<>();
    protected ExecutorService consumerExecutor;
    protected ScheduledExecutorService emissionScheduler;
    protected final AtomicBoolean running = new AtomicBoolean(false);

    // ==================== EVENT TIME TRACKING ====================

    protected final AtomicReference<Instant> maxEventTime = new AtomicReference<>(Instant.EPOCH);
    protected final AtomicLong lastDataArrivalMillis = new AtomicLong(System.currentTimeMillis());
    protected final ReentrantLock windowLock = new ReentrantLock();
    protected volatile Instant currentWindowStart;
    protected volatile Instant currentWindowEnd;

    // ==================== ABSTRACT CONFIG METHODS ====================

    protected abstract String getLogPrefix();
    protected abstract boolean isEnabled();
    protected abstract String getInputTopic();
    protected abstract String getConsumerGroup();
    protected abstract int getNumThreads();
    protected abstract String getOutputTopic();

    // ==================== ABSTRACT PROCESSING METHODS ====================

    /**
     * Create a Kafka consumer configured for the specific data type.
     */
    protected abstract KafkaConsumer<String, T> createConsumer();

    /**
     * Process a single record from Kafka.
     * Subclasses should call {@link #updateEventTimeTracking(Instant)} and
     * {@link #initializeWindowIfNeeded(Instant)} from this method.
     */
    protected abstract void processRecord(T record, long kafkaTimestamp);

    /**
     * Convert aggregate state to output metrics.
     */
    protected abstract M convertState(S state);

    /**
     * Persist metrics to MongoDB.
     */
    protected abstract void persistToMongoDB(List<M> data);

    /**
     * Cache metrics to Redis.
     */
    protected abstract void cacheToRedis(List<M> data);

    /**
     * Get the Kafka key for a metrics item (typically scripCode).
     */
    protected abstract String getKafkaKey(M item);

    // ==================== HOOK METHODS ====================

    /**
     * Called after emission of metrics. Override for FUDKII triggers, boundary events, etc.
     */
    protected void postEmission(List<M> data) {
        // Default: no-op. Subclasses override as needed.
    }

    /**
     * Called at end of start() for subclass-specific initialization.
     */
    protected void onStart() {
        // Default: no-op. Subclasses override as needed.
    }

    // ==================== LIFECYCLE METHODS ====================

    @PostConstruct
    public void start() {
        if (!isEnabled()) {
            log.info("{} Disabled by configuration", getLogPrefix());
            return;
        }

        log.info("{} Starting with threads={}, topic={}, consumerGroup={}, MODE=STRICT_EVENT_TIME",
            getLogPrefix(), getNumThreads(), getInputTopic(), getConsumerGroup());

        running.set(true);

        currentWindowStart = Instant.EPOCH;
        currentWindowEnd = Instant.EPOCH;
        maxEventTime.set(Instant.EPOCH);
        lastDataArrivalMillis.set(System.currentTimeMillis());

        log.info("{} [STRICT-EVENT-TIME] Window will be initialized from first event time", getLogPrefix());

        // Start consumer threads
        consumerExecutor = Executors.newFixedThreadPool(getNumThreads());
        for (int i = 0; i < getNumThreads(); i++) {
            consumerExecutor.submit(this::consumeLoop);
        }

        // Start window emission scheduler (runs every second)
        emissionScheduler = Executors.newSingleThreadScheduledExecutor();
        emissionScheduler.scheduleAtFixedRate(this::checkWindowEmission, 1, 1, TimeUnit.SECONDS);

        // Parse trace scripcodes
        this.traceScripCodes = new HashSet<>();
        if (traceScripCodesStr != null && !traceScripCodesStr.isBlank()) {
            String[] parts = traceScripCodesStr.split(",");
            for (String part : parts) {
                traceScripCodes.add(part.trim());
            }
            log.info("{} Trace logging enabled for scripcodes: {}", getLogPrefix(), traceScripCodes);
        } else {
            log.info("{} Trace logging enabled for ALL scripcodes (FULL - NO SAMPLING)", getLogPrefix());
        }

        onStart();

        log.info("{} Started successfully", getLogPrefix());
    }

    @PreDestroy
    public void stop() {
        log.info("{} Stopping...", getLogPrefix());
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

        log.info("{} Stopped", getLogPrefix());
    }

    // ==================== KAFKA CONSUMER ====================

    /**
     * Main consumer loop - runs in thread pool.
     * Handles polling, stats logging, and dispatching to processRecord().
     */
    protected void consumeLoop() {
        String threadName = Thread.currentThread().getName();
        log.info("{} Consumer thread {} starting", getLogPrefix(), threadName);

        KafkaConsumer<String, T> consumer = createConsumer();
        consumer.subscribe(Collections.singletonList(getInputTopic()));
        log.info("{} Thread {} subscribed to topic: {}", getLogPrefix(), threadName, getInputTopic());

        long pollCount = 0;
        long lastLogTime = System.currentTimeMillis();
        int recordsReceived = 0;

        try {
            while (running.get()) {
                ConsumerRecords<String, T> records = consumer.poll(Duration.ofMillis(100));
                pollCount++;
                recordsReceived += records.count();

                // Log stats every 30 seconds
                if (System.currentTimeMillis() - lastLogTime > 30000) {
                    log.info("{} Thread {} stats: polls={}, recordsReceived={}, activeInstruments={}",
                        getLogPrefix(), threadName, pollCount, recordsReceived, aggregationState.size());
                    lastLogTime = System.currentTimeMillis();
                    pollCount = 0;
                    recordsReceived = 0;
                }

                for (ConsumerRecord<String, T> record : records) {
                    try {
                        processRecord(record.value(), record.timestamp());
                    } catch (Exception e) {
                        log.error("{} Error processing record: {}", getLogPrefix(), e.getMessage(), e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("{} Thread {} consumer loop failed: {}", getLogPrefix(), threadName, e.getMessage(), e);
        } finally {
            consumer.close();
            log.info("{} Thread {} consumer closed", getLogPrefix(), threadName);
        }
    }

    // ==================== EVENT TIME HELPERS ====================

    /**
     * Update max event time and last data arrival. Call from processRecord().
     */
    protected void updateEventTimeTracking(Instant eventTime) {
        lastDataArrivalMillis.set(System.currentTimeMillis());
        maxEventTime.accumulateAndGet(eventTime,
            (current, newTime) -> newTime.isAfter(current) ? newTime : current);
    }

    /**
     * Initialize window boundaries from first event time. Call from processRecord().
     */
    protected void initializeWindowIfNeeded(Instant eventTime) {
        if (currentWindowStart.equals(Instant.EPOCH) ||
            eventTime.isBefore(currentWindowStart.minusSeconds(60))) {
            currentWindowStart = Timeframe.M1.alignToWindowStart(eventTime);
            currentWindowEnd = Timeframe.M1.getWindowEnd(currentWindowStart);
            log.info("{} [STRICT-EVENT-TIME] Initialized window from event: {} - {}",
                getLogPrefix(), formatTime(currentWindowStart), formatTime(currentWindowEnd));
        }
    }

    // ==================== WINDOW EMISSION ====================

    /**
     * Check if current window should be emitted (strict event-time mode).
     *
     * <p>Bug #4 FIX: Idle flush advances by ONE window at a time.</p>
     * <p>Bug #6 FIX: Uses ReentrantLock for thread-safe boundary updates.</p>
     */
    protected void checkWindowEmission() {
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
                            getLogPrefix(), timeSinceLastData, formatTime(referenceTime));
                    }
                }
            }

            if (referenceTime.equals(Instant.EPOCH)) {
                return;
            }

            // Bug #4 FIX: Fast-forward if too far behind (>2 hours)
            long windowsBehind = Duration.between(currentWindowEnd, referenceTime).toMinutes();
            if (windowsBehind > 120) {
                log.warn("{} [STRICT-EVENT-TIME] Window {} hours behind reference {}, fast-forwarding",
                    getLogPrefix(), windowsBehind / 60, formatTime(referenceTime));
                currentWindowStart = Timeframe.M1.alignToWindowStart(referenceTime.minusSeconds(120));
                currentWindowEnd = Timeframe.M1.getWindowEnd(currentWindowStart);
                // Clear old states that would never be emitted
                Instant cutoff = currentWindowStart.minusSeconds(300);
                aggregationState.entrySet().removeIf(e ->
                    e.getValue().getWindowEnd() != null &&
                    e.getValue().getWindowEnd().isBefore(cutoff));
                log.info("{} [STRICT-EVENT-TIME] Fast-forwarded to window: {} - {}",
                    getLogPrefix(), formatTime(currentWindowStart), formatTime(currentWindowEnd));
                return;
            }

            // Use WHILE loop to emit ALL closed windows up to referenceTime
            int windowsEmitted = 0;
            while (referenceTime.isAfter(currentWindowEnd.plusSeconds(2))) {
                if (windowsEmitted == 0) {
                    log.info("{} [STRICT-EVENT-TIME] Catching up: current window {} -> reference time {}",
                        getLogPrefix(), formatTime(currentWindowEnd), formatTime(referenceTime));
                }

                emitCurrentWindow();
                windowsEmitted++;

                // Move to next window
                currentWindowStart = currentWindowEnd;
                currentWindowEnd = Timeframe.M1.getWindowEnd(currentWindowStart);

                // Safety: limit iterations
                if (windowsEmitted > 500) {
                    log.warn("{} [STRICT-EVENT-TIME] Too many windows ({}), breaking. Current: {}, Reference: {}",
                        getLogPrefix(), windowsEmitted, formatTime(currentWindowEnd), formatTime(referenceTime));
                    break;
                }
            }

            if (windowsEmitted > 0) {
                log.info("{} [STRICT-EVENT-TIME] Emitted {} windows, now at: {} - {}",
                    getLogPrefix(), windowsEmitted, formatTime(currentWindowStart), formatTime(currentWindowEnd));
            }
        } finally {
            windowLock.unlock();
        }
    }

    /**
     * Emit all metrics for closed windows.
     *
     * <p>Steps: scan states -> convert -> persist -> cache -> publish -> postEmission -> cleanup</p>
     */
    protected void emitCurrentWindow() {
        if (aggregationState.isEmpty()) return;

        Instant windowEnd = currentWindowEnd;
        List<M> itemsToEmit = new ArrayList<>();
        List<String> keysToRemove = new ArrayList<>();

        for (Map.Entry<String, S> entry : aggregationState.entrySet()) {
            S state = entry.getValue();

            boolean shouldEmit = state.hasData() &&
                                 state.getWindowEnd() != null &&
                                 !state.getWindowEnd().isAfter(windowEnd);

            if (shouldEmit) {
                M item = convertState(state);
                itemsToEmit.add(item);
                keysToRemove.add(entry.getKey());
            }
        }

        // Remove emitted states
        for (String key : keysToRemove) {
            aggregationState.remove(key);
        }

        if (!itemsToEmit.isEmpty()) {
            log.info("{} Emitting {} items for windows up to {}, removed {} states",
                getLogPrefix(), itemsToEmit.size(), formatTime(windowEnd), keysToRemove.size());

            persistToMongoDB(itemsToEmit);
            cacheToRedis(itemsToEmit);
            publishToKafka(itemsToEmit);
            postEmission(itemsToEmit);
        }

        // Bug #17 FIX: Clean up old states safely
        cleanupStaleStates();
    }

    // ==================== KAFKA PUBLISHING ====================

    /**
     * Publish metrics to output Kafka topic.
     */
    protected void publishToKafka(List<M> items) {
        try {
            for (M item : items) {
                kafkaTemplate.send(getOutputTopic(), getKafkaKey(item), item);
            }
            log.info("{} Published {} items to {}", getLogPrefix(), items.size(), getOutputTopic());
        } catch (Exception e) {
            log.error("{} Failed to publish to Kafka: {}", getLogPrefix(), e.getMessage());
        }
    }

    // ==================== CLEANUP ====================

    /**
     * Clean up stale aggregation states.
     * Bug #17 FIX: Collects keys first to avoid ConcurrentModificationException.
     */
    protected void cleanupStaleStates() {
        Instant cutoff = currentWindowEnd.minus(Duration.ofMinutes(5));
        List<String> staleKeys = new ArrayList<>();

        for (Map.Entry<String, S> entry : aggregationState.entrySet()) {
            if (entry.getValue().getLastUpdate().isBefore(cutoff)) {
                staleKeys.add(entry.getKey());
            }
        }

        for (String key : staleKeys) {
            aggregationState.remove(key);
        }

        if (!staleKeys.isEmpty()) {
            log.info("{} [CLEANUP] Removed {} old states, cutoff={}",
                getLogPrefix(), staleKeys.size(), formatTime(cutoff));
        }
    }

    // ==================== UTILITY METHODS ====================

    protected String formatTime(Instant instant) {
        return ZonedDateTime.ofInstant(instant, IST).format(TIME_FMT);
    }

    public boolean isRunning() {
        return running.get();
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("enabled", isEnabled());
        stats.put("running", running.get());
        stats.put("activeInstruments", aggregationState.size());
        stats.put("currentWindowStart", currentWindowStart != null ? currentWindowStart.toString() : null);
        stats.put("currentWindowEnd", currentWindowEnd != null ? currentWindowEnd.toString() : null);
        return stats;
    }
}
