package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.model.TickData;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Event-Time Processing for TickData
 *
 * TimestampExtractor for TickData that uses event time (timestamp field)
 * instead of Kafka record timestamp.
 *
 * CRITICAL: Ensures consistent windowing behavior for both replay and live data.
 * Without this, replay of historical tick data won't aggregate correctly into candles.
 *
 * TIMESTAMP SAFETY:
 * When event timestamp is too far ahead, falls back to partitionTime which is
 * guaranteed to be accepted by Kafka broker. This prevents InvalidTimestampException
 * that causes consumer group rebalancing.
 */
public class TickDataTimestampExtractor implements TimestampExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TickDataTimestampExtractor.class);
    private static final long MIN_VALID_TIMESTAMP = 1577836800000L; // Jan 1, 2020

    // Maximum allowed future drift before falling back to partitionTime
    // Using conservative 5 minutes to stay well within broker limits
    private static final long MAX_FUTURE_DRIFT_MS = 300000L;

    // Rate-limit warnings to reduce log spam
    private static final AtomicLong futureTimestampCount = new AtomicLong(0);
    private static final AtomicLong lastFutureTimestampLogTime = new AtomicLong(0);
    private static final long LOG_INTERVAL_MS = 60000; // Log at most once per minute

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object value = record.value();

        if (value instanceof TickData) {
            TickData tick = (TickData) value;
            long eventTime = tick.getTimestamp();

            // Validate event time is reasonable (after Jan 1, 2020)
            if (eventTime < MIN_VALID_TIMESTAMP) {
                return getSafeTimestamp(record, partitionTime);
            }

            // Use partitionTime as reference - it's maintained by Kafka Streams
            // and is guaranteed to be valid for the broker
            long referenceTime = partitionTime > 0 ? partitionTime : System.currentTimeMillis();

            // Check if timestamp is too far in the future relative to partition time
            if (eventTime > referenceTime + MAX_FUTURE_DRIFT_MS) {
                // Fall back to partitionTime to prevent InvalidTimestampException
                logFutureTimestamp(tick, eventTime, referenceTime, record, partitionTime);
                return partitionTime > 0 ? partitionTime : referenceTime;
            }

            // Timestamp is acceptable - use event time
            return eventTime;
        }

        // Fallback for non-TickData records
        return getSafeTimestamp(record, partitionTime);
    }

    private long getSafeTimestamp(ConsumerRecord<Object, Object> record, long partitionTime) {
        if (partitionTime > 0) {
            return partitionTime;
        }
        if (record.timestamp() > 0) {
            return record.timestamp();
        }
        return System.currentTimeMillis();
    }

    private void logFutureTimestamp(TickData tick, long eventTime, long referenceTime,
                                    ConsumerRecord<Object, Object> record, long partitionTime) {
        long count = futureTimestampCount.incrementAndGet();
        long now = System.currentTimeMillis();
        long lastLog = lastFutureTimestampLogTime.get();

        if (now - lastLog > LOG_INTERVAL_MS) {
            if (lastFutureTimestampLogTime.compareAndSet(lastLog, now)) {
                LOGGER.warn("[FUTURE-TS] {} records with future timestamps in last {}s. " +
                        "Latest: token={}, eventTime={}, refTime={}, drift={}ms, using partitionTime={}. " +
                        "Topic: {}, Partition: {}, Offset: {}",
                    count, LOG_INTERVAL_MS / 1000,
                    tick.getToken(), eventTime, referenceTime, eventTime - referenceTime, partitionTime,
                    record.topic(), record.partition(), record.offset());
                futureTimestampCount.set(0);
            }
        }
    }
}

