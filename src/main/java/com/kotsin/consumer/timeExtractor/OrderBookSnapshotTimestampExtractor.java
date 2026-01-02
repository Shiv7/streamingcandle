package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.model.OrderBookSnapshot;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object value = record.value();

        if (value instanceof OrderBookSnapshot) {
            OrderBookSnapshot snapshot = (OrderBookSnapshot) value;
            long eventTime = snapshot.getTimestamp();

            // Validate event time is reasonable (after Jan 1, 2020)
            if (eventTime > MIN_VALID_TIMESTAMP) {
                return eventTime;
            } else {
                LOGGER.warn("Invalid timestamp {} for token {}, using record timestamp",
                        eventTime, snapshot.getToken());
                return record.timestamp() > 0 ? record.timestamp() : partitionTime;
            }
        }

        // Fallback to record timestamp or partition time
        return record.timestamp() > 0 ? record.timestamp() : partitionTime;
    }
}
