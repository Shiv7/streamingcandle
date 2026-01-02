package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.model.TickData;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 🛡️ CRITICAL FIX: Event-Time Processing for TickData
 *
 * TimestampExtractor for TickData that uses event time (timestamp field)
 * instead of Kafka record timestamp.
 *
 * CRITICAL: Ensures consistent windowing behavior for both replay and live data.
 * Without this, replay of historical tick data won't aggregate correctly into candles.
 *
 * BEFORE (BROKEN):
 * - Used Kafka record timestamp (ingestion time = wall clock time)
 * - Replaying Dec 24 tick data in Jan 1 results in wrong candle windows
 * - Live data and replay data behave differently
 *
 * AFTER (FIXED):
 * - Uses TickData.timestamp (actual market event time)
 * - Replay and live data use identical windowing logic
 * - Candles correctly aggregated based on when trade occurred, not when we received it
 */
public class TickDataTimestampExtractor implements TimestampExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TickDataTimestampExtractor.class);
    private static final long MIN_VALID_TIMESTAMP = 1577836800000L; // Jan 1, 2020

    // Maximum allowed timestamp drift (1 hour = 3600000 ms)
    private static final long MAX_FUTURE_DRIFT_MS = 3600000L;

    // Track stream time
    private long lastObservedTimestamp = -1L;

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

            // Initialize stream time tracking
            if (lastObservedTimestamp < 0) {
                lastObservedTimestamp = eventTime;
            }

            // Check if timestamp is too far in the future
            long currentStreamTime = Math.max(lastObservedTimestamp, partitionTime);
            long drift = eventTime - currentStreamTime;

            if (drift > MAX_FUTURE_DRIFT_MS) {
                // Timestamp is too far ahead - clamp it to acceptable range
                long clampedTimestamp = currentStreamTime + MAX_FUTURE_DRIFT_MS;

                LOGGER.warn("TickData timestamp {} is {}ms ahead of stream time {}. " +
                        "Clamping to {} to prevent InvalidTimestampException. " +
                        "Topic: {}, Partition: {}, Offset: {}, Token: {}",
                    eventTime, drift, currentStreamTime, clampedTimestamp,
                    record.topic(), record.partition(), record.offset(), tick.getToken());

                lastObservedTimestamp = clampedTimestamp;
                return clampedTimestamp;
            }

            // Timestamp is acceptable - use as-is
            lastObservedTimestamp = Math.max(lastObservedTimestamp, eventTime);
            return eventTime;
        }

        // Fallback to record timestamp or partition time
        return record.timestamp() > 0 ? record.timestamp() : partitionTime;
    }
}
