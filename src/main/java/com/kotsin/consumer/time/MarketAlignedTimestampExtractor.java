package com.kotsin.consumer.time;

import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.TickData;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/**
 * CLEAN & SIMPLE: Use Kafka record timestamp when event timestamp is invalid.
 *
 * Philosophy: Kafka timestamps are reliable, always present, and guaranteed valid.
 * When our data has bad timestamps, just use Kafka's instead.
 */
public class MarketAlignedTimestampExtractor implements TimestampExtractor {

    private static final long YEAR_2020_MS = 1577836800000L; // Jan 1, 2020
    private static final long YEAR_2050_MS = 2524608000000L; // Jan 1, 2050

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object value = record.value();
        long kafkaTimestamp = record.timestamp(); // Always valid and reliable

        // Extract timestamp from the event data
        long eventTimestamp = 0;
        if (value instanceof TickData) {
            eventTimestamp = ((TickData) value).getTimestamp();
        } else if (value instanceof OrderBookSnapshot) {
            eventTimestamp = ((OrderBookSnapshot) value).getTimestamp();
        }

        // Simple decision: Valid event timestamp? Use it. Otherwise? Kafka time.
        if (eventTimestamp > 0 && isValidTimestamp(eventTimestamp)) {
            return eventTimestamp;
        }

        // Fallback: Use Kafka record timestamp (always works)
        return kafkaTimestamp;
    }

    /**
     * Validate timestamp is within reasonable range (year 2020-2050)
     */
    private boolean isValidTimestamp(long timestamp) {
        return timestamp >= YEAR_2020_MS && timestamp <= YEAR_2050_MS;
    }
}
