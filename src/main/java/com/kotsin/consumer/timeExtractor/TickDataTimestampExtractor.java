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

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object value = record.value();

        if (value instanceof TickData) {
            TickData tick = (TickData) value;
            long eventTime = tick.getTimestamp();

            // Validate event time is reasonable (after Jan 1, 2020)
            if (eventTime > MIN_VALID_TIMESTAMP) {
                return eventTime;
            } else {
                LOGGER.warn("Invalid timestamp {} for token {}, using record timestamp",
                        eventTime, tick.getToken());
                return record.timestamp() > 0 ? record.timestamp() : partitionTime;
            }
        }

        // Fallback to record timestamp or partition time
        return record.timestamp() > 0 ? record.timestamp() : partitionTime;
    }
}
