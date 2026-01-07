package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.model.OrderBookSnapshot;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 🛡️ CRITICAL FIX: Event-Time Processing for OrderBookSnapshot
 *
 * TimestampExtractor for OrderBookSnapshot that uses event time
 * instead of Kafka record timestamp.
 *
 * CRITICAL: Ensures orderbook data aligns with tick data in time-based joins.
 * Without this, orderbook snapshots from replay won't join correctly with tick data.
 *
 * BEFORE (BROKEN):
 * - Used Kafka record timestamp (ingestion time)
 * - Orderbook-tick joins failed during replay due to time misalignment
 * - OFI and Kyle's Lambda calculations missing for replay data
 *
 * AFTER (FIXED):
 * - Uses OrderBookSnapshot.getTimestamp() (actual snapshot time)
 * - Orderbook data correctly joins with tick data in time windows
 * - Microstructure indicators (OFI, lambda) available for all data
 */
public class OrderBookSnapshotTimestampExtractor implements TimestampExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderBookSnapshotTimestampExtractor.class);
    private static final long MIN_VALID_TIMESTAMP = 1577836800000L; // Jan 1, 2020

    // Maximum allowed timestamp drift (1 hour = 3600000 ms)
    private static final long MAX_FUTURE_DRIFT_MS = 3600000L;

    // FIX: Thread-safe track stream time using AtomicLong for concurrent access
    private final AtomicLong lastObservedTimestamp = new AtomicLong(-1L);

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object value = record.value();

        if (value instanceof OrderBookSnapshot) {
            OrderBookSnapshot snapshot = (OrderBookSnapshot) value;
            long eventTime = snapshot.getTimestamp();

            // Validate event time is reasonable (after Jan 1, 2020)
            if (eventTime < MIN_VALID_TIMESTAMP) {
                LOGGER.warn("Invalid timestamp {} for token {}, using record timestamp",
                        eventTime, snapshot.getToken());
                return record.timestamp() > 0 ? record.timestamp() : partitionTime;
            }

            // Initialize stream time tracking (thread-safe)
            long currentObserved = lastObservedTimestamp.get();
            if (currentObserved < 0) {
                lastObservedTimestamp.compareAndSet(-1L, eventTime);
                currentObserved = lastObservedTimestamp.get();
            }

            // Check if timestamp is too far in the future
            long currentStreamTime = Math.max(currentObserved, partitionTime);
            long drift = eventTime - currentStreamTime;

            if (drift > MAX_FUTURE_DRIFT_MS) {
                // Timestamp is too far ahead - clamp it to acceptable range
                long clampedTimestamp = currentStreamTime + MAX_FUTURE_DRIFT_MS;

                LOGGER.warn("OrderBook timestamp {} is {}ms ahead of stream time {}. " +
                        "Clamping to {} to prevent InvalidTimestampException. " +
                        "Topic: {}, Partition: {}, Offset: {}, Token: {}",
                    eventTime, drift, currentStreamTime, clampedTimestamp,
                    record.topic(), record.partition(), record.offset(), snapshot.getToken());

                lastObservedTimestamp.set(clampedTimestamp);
                return clampedTimestamp;
            }

            // Timestamp is acceptable - use as-is (thread-safe update to max)
            long newMax = Math.max(currentObserved, eventTime);
            lastObservedTimestamp.set(newMax);
            return eventTime;
        }

        // Fallback to record timestamp or partition time
        return record.timestamp() > 0 ? record.timestamp() : partitionTime;
    }
}
