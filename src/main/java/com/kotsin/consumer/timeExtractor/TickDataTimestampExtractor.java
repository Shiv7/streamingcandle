package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.model.TickData;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 🛡️ CRITICAL FIX: Event-Time Processing for TickData
 *
 * TimestampExtractor for TickData that uses event time (timestamp field)
 * instead of Kafka record timestamp.
 *
 * CRITICAL: Ensures consistent windowing behavior for both replay and live data.
 * Without this, replay of historical tick data won't aggregate correctly into candles.
 *
 * TIMESTAMP CLAMPING (ISSUE #1 FIX):
 * Uses max observed STREAM TIME as reference instead of wall clock.
 * This ensures correct behavior during replay of historical data.
 * - During replay: clamps relative to replay stream time
 * - During live: stream time ≈ wall clock anyway
 */
public class TickDataTimestampExtractor implements TimestampExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TickDataTimestampExtractor.class);
    private static final long MIN_VALID_TIMESTAMP = 1577836800000L; // Jan 1, 2020

    // Maximum allowed future drift from observed stream time (1 hour = 3600000 ms)
    private static final long MAX_FUTURE_DRIFT_MS = 3600000L;

    // ISSUE #1 FIX: Track max observed stream time instead of using wall clock
    // This enables correct replay of historical data
    private static final AtomicLong maxObservedStreamTime = new AtomicLong(0);

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object value = record.value();

        if (value instanceof TickData) {
            TickData tick = (TickData) value;
            long eventTime = tick.getTimestamp();

            // Validate event time is reasonable (after Jan 1, 2020)
            if (eventTime < MIN_VALID_TIMESTAMP) {
                LOGGER.warn("Invalid timestamp {} for token {}, using record timestamp",
                        eventTime, tick.getToken());
                return record.timestamp() > 0 ? record.timestamp() : partitionTime;
            }

            // ISSUE #1 FIX: Use max observed stream time as reference instead of wall clock
            // This ensures correct behavior during replay of historical data
            long currentMax = maxObservedStreamTime.get();

            // Update max observed time if this event is newer (but not too far ahead)
            if (eventTime > currentMax && eventTime <= currentMax + MAX_FUTURE_DRIFT_MS) {
                maxObservedStreamTime.updateAndGet(prev -> Math.max(prev, eventTime));
                currentMax = maxObservedStreamTime.get();
            }

            // For first few records or cold start, use wall clock as bootstrap reference
            if (currentMax == 0) {
                currentMax = System.currentTimeMillis();
                maxObservedStreamTime.compareAndSet(0, currentMax);
            }

            long maxAllowedTimestamp = currentMax + MAX_FUTURE_DRIFT_MS;

            // Check if timestamp is too far in the future relative to stream time
            if (eventTime > maxAllowedTimestamp) {
                // Clamp to prevent InvalidTimestampException on repartition topics
                long clampedTimestamp = maxAllowedTimestamp;

                LOGGER.warn("TickData timestamp {} is {}ms ahead of stream time {}. " +
                        "Clamping to {} to prevent InvalidTimestampException. " +
                        "Topic: {}, Partition: {}, Offset: {}, Token: {}",
                    eventTime, eventTime - currentMax, currentMax, clampedTimestamp,
                    record.topic(), record.partition(), record.offset(), tick.getToken());

                return clampedTimestamp;
            }

            // Timestamp is acceptable - use as-is
            return eventTime;
        }

        // Fallback to record timestamp or partition time
        return record.timestamp() > 0 ? record.timestamp() : partitionTime;
    }
}

