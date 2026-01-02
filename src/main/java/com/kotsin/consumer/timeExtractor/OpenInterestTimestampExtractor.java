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

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object value = record.value();

        if (value instanceof OpenInterest) {
            OpenInterest oi = (OpenInterest) value;
            Long eventTime = oi.getReceivedTimestamp();

            // Validate event time exists and is reasonable
            if (eventTime != null && eventTime > MIN_VALID_TIMESTAMP) {
                return eventTime;
            } else {
                if (eventTime == null) {
                    LOGGER.warn("Null receivedTimestamp for token {}, using record timestamp", oi.getToken());
                } else {
                    LOGGER.warn("Invalid receivedTimestamp {} for token {}, using record timestamp",
                            eventTime, oi.getToken());
                }
                return record.timestamp() > 0 ? record.timestamp() : partitionTime;
            }
        }

        // Fallback to record timestamp or partition time
        return record.timestamp() > 0 ? record.timestamp() : partitionTime;
    }
}
