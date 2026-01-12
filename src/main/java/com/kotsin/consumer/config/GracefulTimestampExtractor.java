package com.kotsin.consumer.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/**
 * Custom timestamp extractor that gracefully handles out-of-range timestamps
 * by falling back to partitionTime instead of crashing the stream.
 *
 * This prevents InvalidTimestampException when replaying historical data
 * that may have large time gaps or future timestamps. Using partitionTime
 * as fallback guarantees broker acceptance since it's maintained by Kafka Streams.
 */
@Slf4j
public class GracefulTimestampExtractor implements TimestampExtractor {

    // Maximum allowed future drift before falling back to partitionTime
    // Using conservative 5 minutes to stay well within broker limits
    private static final long MAX_FUTURE_DRIFT_MS = 300000L;

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        long recordTimestamp = record.timestamp();

        // If record has no timestamp, use partition time
        if (recordTimestamp < 0) {
            return partitionTime > 0 ? partitionTime : System.currentTimeMillis();
        }

        // Use partitionTime as reference - it's maintained by Kafka Streams
        // and is guaranteed to be valid for the broker
        long referenceTime = partitionTime > 0 ? partitionTime : System.currentTimeMillis();
        long drift = recordTimestamp - referenceTime;

        if (drift > MAX_FUTURE_DRIFT_MS) {
            // Fall back to partitionTime to prevent InvalidTimestampException
            log.debug("Record timestamp {} is {}ms ahead of partitionTime {}. " +
                    "Using partitionTime to prevent InvalidTimestampException. " +
                    "Topic: {}, Partition: {}, Offset: {}",
                recordTimestamp, drift, referenceTime,
                record.topic(), record.partition(), record.offset());

            return partitionTime > 0 ? partitionTime : referenceTime;
        }

        // Timestamp is acceptable - use as-is
        return recordTimestamp;
    }
}
