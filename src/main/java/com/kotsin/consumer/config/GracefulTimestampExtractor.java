package com.kotsin.consumer.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/**
 * Custom timestamp extractor that gracefully handles out-of-range timestamps
 * by clamping them to acceptable values instead of crashing the stream.
 *
 * This prevents InvalidTimestampException when replaying historical data
 * that may have large time gaps or future timestamps.
 */
@Slf4j
public class GracefulTimestampExtractor implements TimestampExtractor {

    // Maximum allowed timestamp drift (1 hour = 3600000 ms)
    private static final long MAX_FUTURE_DRIFT_MS = 3600000L;

    // Track stream time
    private long lastObservedTimestamp = -1L;

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        long recordTimestamp = record.timestamp();

        // If record has no timestamp, use partition time
        if (recordTimestamp < 0) {
            log.warn("Record has invalid timestamp {}, using partition time {}",
                recordTimestamp, partitionTime);
            return partitionTime > 0 ? partitionTime : System.currentTimeMillis();
        }

        // Update stream time tracking
        if (lastObservedTimestamp < 0) {
            lastObservedTimestamp = recordTimestamp;
        }

        // Check if timestamp is too far in the future
        long currentStreamTime = Math.max(lastObservedTimestamp, partitionTime);
        long drift = recordTimestamp - currentStreamTime;

        if (drift > MAX_FUTURE_DRIFT_MS) {
            // Timestamp is too far ahead - clamp it to acceptable range
            long clampedTimestamp = currentStreamTime + MAX_FUTURE_DRIFT_MS;

            log.warn("Record timestamp {} is {}ms ahead of stream time {}. " +
                    "Clamping to {} to prevent InvalidTimestampException. " +
                    "Topic: {}, Partition: {}, Offset: {}",
                recordTimestamp, drift, currentStreamTime, clampedTimestamp,
                record.topic(), record.partition(), record.offset());

            lastObservedTimestamp = clampedTimestamp;
            return clampedTimestamp;
        }

        // Timestamp is acceptable - use as-is
        lastObservedTimestamp = Math.max(lastObservedTimestamp, recordTimestamp);
        return recordTimestamp;
    }
}
