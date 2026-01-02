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
 * TIMESTAMP CLAMPING:
 * Uses wall clock time as reference to prevent repartition topic failures.
 * If event time is too far in the future (relative to now), clamp it to prevent
 * InvalidTimestampException on internal Kafka Streams topics.
 */
public class TickDataTimestampExtractor implements TimestampExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TickDataTimestampExtractor.class);
    private static final long MIN_VALID_TIMESTAMP = 1577836800000L; // Jan 1, 2020

    // Maximum allowed future drift from wall clock (1 hour = 3600000 ms)
    // This aligns with typical Kafka broker max.message.time.difference.ms
    private static final long MAX_FUTURE_DRIFT_MS = 3600000L;

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

            // Use wall clock as reference point for clamping
            // This ensures consistent behavior regardless of processing order
            long wallClock = System.currentTimeMillis();
            long maxAllowedTimestamp = wallClock + MAX_FUTURE_DRIFT_MS;

            // Check if timestamp is too far in the future
            if (eventTime > maxAllowedTimestamp) {
                // Clamp to prevent InvalidTimestampException on repartition topics
                long clampedTimestamp = maxAllowedTimestamp;

                LOGGER.warn("TickData timestamp {} is {}ms ahead of wall clock {}. " +
                        "Clamping to {} to prevent InvalidTimestampException. " +
                        "Topic: {}, Partition: {}, Offset: {}, Token: {}",
                    eventTime, eventTime - wallClock, wallClock, clampedTimestamp,
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

