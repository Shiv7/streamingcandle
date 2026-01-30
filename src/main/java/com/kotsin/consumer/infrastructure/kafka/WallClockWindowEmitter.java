package com.kotsin.consumer.infrastructure.kafka;

import com.kotsin.consumer.domain.model.FamilyCandle;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * WallClockWindowEmitter - Emits windowed aggregates based on WALL CLOCK time.
 *
 * PROBLEM SOLVED:
 * Kafka Streams suppress(untilWindowCloses) uses "stream-time" which only advances
 * when new records arrive. If there's a gap in data (low activity, market close),
 * windows stay open indefinitely causing multi-minute delays.
 *
 * SOLUTION:
 * This processor uses WALL_CLOCK_TIME punctuation to emit windows when real wall
 * clock time exceeds window_end + grace_period, regardless of stream-time.
 *
 * KEY BEHAVIOR:
 * - Preserves Windowed<K> key type (downstream code can access window bounds)
 * - Buffers latest value per window until wall-clock close time
 * - Emits exactly once per window (deduplication built-in)
 * - Replaces suppress(untilWindowCloses) with wall-clock semantics
 *
 * USAGE:
 * Replace suppress() with this processor in any windowed aggregation:
 *
 * BEFORE (stream-time delay):
 *   .aggregate(...)
 *   .suppress(Suppressed.untilWindowCloses(...))
 *   .toStream()
 *   .mapValues((windowedKey, v) -> ...)
 *
 * AFTER (wall-clock emission):
 *   .aggregate(...)
 *   // NO suppress() here
 *   .toStream()
 *   .process(() -> new WallClockWindowEmitter<>(graceMs))
 *   .mapValues((windowedKey, v) -> ...)  // Still has Windowed<K> key
 *
 * @param <K> Key type (inside the Windowed wrapper)
 * @param <V> Value type
 */
public class WallClockWindowEmitter<K, V> implements Processor<Windowed<K>, V, Windowed<K>, V> {

    private static final Logger log = LoggerFactory.getLogger(WallClockWindowEmitter.class);

    private final long graceMs;
    private final long checkIntervalMs;
    private final int timeframeMinutes;  // For calculating correct output timestamp
    private ProcessorContext<Windowed<K>, V> context;

    // Track emitted windows to avoid duplicates
    private final Set<String> emittedWindowKeys = new HashSet<>();

    // Track pending windows (not yet emitted) - stores full Windowed<K> to preserve window bounds
    private final List<PendingWindow<K, V>> pendingWindows = new ArrayList<>();

    /**
     * Full constructor with all parameters.
     *
     * @param graceMs Grace period in milliseconds after window end
     * @param checkIntervalMs How often to check for closable windows (wall clock)
     * @param timeframeMinutes The timeframe in minutes (for calculating correct output timestamp)
     */
    public WallClockWindowEmitter(long graceMs, long checkIntervalMs, int timeframeMinutes) {
        this.graceMs = graceMs;
        this.checkIntervalMs = checkIntervalMs;
        this.timeframeMinutes = timeframeMinutes;
    }

    /**
     * Constructor with default check interval (1 second).
     *
     * @param graceMs Grace period in milliseconds after window end
     * @param timeframeMinutes The timeframe in minutes (for calculating correct output timestamp)
     */
    public WallClockWindowEmitter(long graceMs, int timeframeMinutes) {
        this(graceMs, 1000L, timeframeMinutes);
    }

    /**
     * Convenience constructor with 1 second check interval and default 1m timeframe.
     * Use this for 1m candles or when timestamp correction is not needed.
     */
    public WallClockWindowEmitter(long graceMs) {
        this(graceMs, 1000L, 1);
    }

    @Override
    public void init(ProcessorContext<Windowed<K>, V> context) {
        this.context = context;

        // Schedule wall-clock punctuator to emit closed windows
        context.schedule(
            Duration.ofMillis(checkIntervalMs),
            PunctuationType.WALL_CLOCK_TIME,
            this::punctuate
        );

        log.info("WallClockWindowEmitter initialized: graceMs={}, checkIntervalMs={}, timeframeMinutes={}",
            graceMs, checkIntervalMs, timeframeMinutes);
    }

    @Override
    public void process(Record<Windowed<K>, V> record) {
        Windowed<K> windowedKey = record.key();
        V value = record.value();

        if (windowedKey == null || value == null) {
            return;
        }

        long windowEnd = windowedKey.window().end();
        long closeTime = windowEnd + graceMs;
        long now = System.currentTimeMillis();

        // Create unique key for deduplication
        String windowKey = windowedKey.key().toString() + "|" + windowedKey.window().start() + "|" + windowEnd;

        // CRITICAL FIX: Never emit immediately on record arrival!
        // Always buffer and let punctuate() handle emission.
        // This gives time for ALL family members (future + options) to arrive
        // before the window is emitted.
        //
        // The old code had a bug: if wall clock was past closeTime when the first
        // record (e.g., future) arrived, it was emitted immediately without waiting
        // for options. This caused FamilyCandles to have options=[] even though
        // options arrived just milliseconds later.
        //
        // Now: Buffer ALL records and emit on next punctuate cycle.
        // Max delay = checkIntervalMs (1 second) which is acceptable.

        if (emittedWindowKeys.contains(windowKey)) {
            // Window already emitted - skip late arrivals
            if (log.isDebugEnabled()) {
                log.debug("WallClockEmitter: late record for already-emitted window key={}", windowKey);
            }
            return;
        }

        // Buffer until punctuate emits it (even if past close time)
        // Replace existing pending window for same key (keep latest value)
        pendingWindows.removeIf(pw -> pw.windowKey.equals(windowKey));

        // Use max of original closeTime or (now + small buffer) to ensure punctuate picks it up
        long effectiveCloseTime = Math.max(closeTime, now);
        pendingWindows.add(new PendingWindow<>(windowKey, windowedKey, value, effectiveCloseTime, record.timestamp()));

        if (log.isDebugEnabled()) {
            log.debug("WallClockEmitter buffered: key={} window=[{},{}] pendingCount={}",
                windowedKey.key(),
                Instant.ofEpochMilli(windowedKey.window().start()),
                Instant.ofEpochMilli(windowEnd),
                pendingWindows.size());
        }
    }

    /**
     * Punctuator called on wall clock schedule
     * Emits any windows whose close time has passed
     */
    private void punctuate(long wallClockTime) {
        List<PendingWindow<K, V>> toEmit = new ArrayList<>();

        // Find windows ready to emit
        pendingWindows.removeIf(pw -> {
            if (wallClockTime >= pw.closeTime) {
                if (!emittedWindowKeys.contains(pw.windowKey)) {
                    toEmit.add(pw);
                    emittedWindowKeys.add(pw.windowKey);
                }
                return true; // Remove from pending
            }
            return false;
        });

        // Emit windows
        for (PendingWindow<K, V> pw : toEmit) {
            emitWindow(pw.windowedKey, pw.value, pw.recordTimestamp);
        }

        // Log if we emitted anything
        if (!toEmit.isEmpty()) {
            log.debug("WallClockEmitter punctuate: emitted {} windows at wallClock={}",
                toEmit.size(), Instant.ofEpochMilli(wallClockTime));
        }

        cleanupOldKeys(wallClockTime);
    }

    private void emitWindow(Windowed<K> windowedKey, V value, long recordTimestamp) {
        // 🔴 FIX: Calculate correct output timestamp
        // The input timestamp may be offset (e.g., -15min for NSE alignment)
        // We need to use the CORRECTED timestamp for the output Kafka record
        long outputTimestamp = calculateCorrectedTimestamp(windowedKey, value);

        // Forward with the CORRECTED timestamp, preserving Windowed<K> key
        context.forward(new Record<>(windowedKey, value, outputTimestamp));

        if (log.isDebugEnabled()) {
            long windowEnd = windowedKey.window().end();
            long delay = System.currentTimeMillis() - windowEnd;
            log.debug("WallClockEmitter emitting: key={} window=[{},{}] delay={}ms outputTs={}",
                windowedKey.key(),
                Instant.ofEpochMilli(windowedKey.window().start()),
                Instant.ofEpochMilli(windowEnd),
                delay,
                Instant.ofEpochMilli(outputTimestamp));
        }
    }

    /**
     * Calculate the corrected timestamp for the output Kafka record.
     *
     * 🔴 FIX: The input timestamp may be offset for market alignment (e.g., -15min for NSE).
     * The output record should have the ACTUAL window start time, not the offset time.
     *
     * For FamilyCandle values, we extract the exchange and apply the correct offset.
     * For other values, we use the window start + offset based on timeframe.
     */
    private long calculateCorrectedTimestamp(Windowed<K> windowedKey, V value) {
        long windowStart = windowedKey.window().start();

        // For FamilyCandle, extract exchange and calculate correct offset
        if (value instanceof FamilyCandle) {
            FamilyCandle candle = (FamilyCandle) value;
            String exchange = extractExchange(candle);
            long offsetMs = com.kotsin.consumer.timeExtractor.MarketAlignedTimestampExtractor
                .getOffsetMs(exchange, timeframeMinutes);
            return windowStart + offsetMs;
        }

        // For other types, use default NSE offset for larger timeframes
        // (This maintains backward compatibility)
        if (timeframeMinutes >= 30) {
            return windowStart + com.kotsin.consumer.timeExtractor.MarketAlignedTimestampExtractor.NSE_OFFSET_MS;
        }

        // For smaller timeframes, no offset needed
        return windowStart;
    }

    /**
     * Extract exchange from FamilyCandle.
     */
    private String extractExchange(FamilyCandle candle) {
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
     * Clean up old emitted keys to prevent memory leak
     * Keep keys for windows that ended within last 5 minutes
     */
    private void cleanupOldKeys(long now) {
        long cutoff = now - 300_000; // 5 minutes ago
        emittedWindowKeys.removeIf(key -> {
            // Key format: "key|start|end"
            String[] parts = key.split("\\|");
            if (parts.length >= 3) {
                try {
                    long end = Long.parseLong(parts[2]);
                    return end < cutoff;
                } catch (NumberFormatException e) {
                    return true;
                }
            }
            return true;
        });
    }

    @Override
    public void close() {
        // Emit any remaining pending windows on shutdown
        for (PendingWindow<K, V> pw : pendingWindows) {
            if (!emittedWindowKeys.contains(pw.windowKey)) {
                emitWindow(pw.windowedKey, pw.value, pw.recordTimestamp);
            }
        }
        pendingWindows.clear();
        emittedWindowKeys.clear();
        log.info("WallClockWindowEmitter closed");
    }

    /**
     * Holds a pending window awaiting emission
     * Stores full Windowed<K> to preserve window bounds for downstream processing
     */
    private static class PendingWindow<K, V> {
        final String windowKey;         // Unique key for deduplication: "key|start|end"
        final Windowed<K> windowedKey;  // Full windowed key (preserves window bounds)
        final V value;                  // Latest aggregated value
        final long closeTime;           // Wall-clock time when window should close
        final long recordTimestamp;     // Original record timestamp for forwarding

        PendingWindow(String windowKey, Windowed<K> windowedKey, V value, long closeTime, long recordTimestamp) {
            this.windowKey = windowKey;
            this.windowedKey = windowedKey;
            this.value = value;
            this.closeTime = closeTime;
            this.recordTimestamp = recordTimestamp;
        }
    }
}
