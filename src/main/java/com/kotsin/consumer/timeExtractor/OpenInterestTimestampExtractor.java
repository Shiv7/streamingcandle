package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.model.OpenInterest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 🛡️ CRITICAL FIX: Event-Time Processing for OpenInterest
 *
 * TimestampExtractor for OpenInterest that uses event time
 * instead of Kafka record timestamp.
 *
 * CRITICAL: Ensures OI data aligns with tick data in time-based joins.
 * Without this, OI data from replay won't join correctly with derivatives candles.
 *
 * BEFORE (BROKEN):
 * - Used Kafka record timestamp (ingestion time)
 * - OI data from replay didn't join with tick/candle data
 * - Missing OI metrics (OI change, OI percent) for replay analysis
 *
 * AFTER (FIXED):
 * - Uses OpenInterest.receivedTimestamp (actual OI update time)
 * - OI data correctly joins with derivatives candles in time windows
 * - Full OI analysis available for both replay and live data
 */
public class OpenInterestTimestampExtractor implements TimestampExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenInterestTimestampExtractor.class);
    private static final long MIN_VALID_TIMESTAMP = 1577836800000L; // Jan 1, 2020

    // Maximum allowed timestamp drift (1 hour = 3600000 ms)
    private static final long MAX_FUTURE_DRIFT_MS = 3600000L;

    // Track stream time
    private long lastObservedTimestamp = -1L;

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object value = record.value();

        if (value instanceof OpenInterest) {
            OpenInterest oi = (OpenInterest) value;
            Long eventTime = oi.getReceivedTimestamp();

            // Validate event time exists and is reasonable
            if (eventTime == null || eventTime < MIN_VALID_TIMESTAMP) {
                if (eventTime == null) {
                    LOGGER.warn("Null receivedTimestamp for token {}, using record timestamp", oi.getToken());
                } else {
                    LOGGER.warn("Invalid receivedTimestamp {} for token {}, using record timestamp",
                            eventTime, oi.getToken());
                }
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

                LOGGER.warn("OpenInterest timestamp {} is {}ms ahead of stream time {}. " +
                        "Clamping to {} to prevent InvalidTimestampException. " +
                        "Topic: {}, Partition: {}, Offset: {}, Token: {}",
                    eventTime, drift, currentStreamTime, clampedTimestamp,
                    record.topic(), record.partition(), record.offset(), oi.getToken());

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
