package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.domain.model.FamilyCandle;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 🛡️ CRITICAL FIX: Event-Time Processing for FamilyCandle
 *
 * TimestampExtractor for FamilyCandle that uses event time (windowStartMillis)
 * instead of Kafka record timestamp.
 *
 * CRITICAL: Ensures multi-timeframe aggregation (1m → 5m → 15m → 1h → 1d) works
 * correctly for both replay and live data.
 *
 * BEFORE (BROKEN):
 * - Used Kafka record timestamp (ingestion time = wall clock)
 * - Replaying 1m family candles from Dec 24 couldn't aggregate into 5m/15m/1h/1d
 * - Wall clock = Jan 1, but candle data = Dec 24 → window mismatch
 * - Multi-timeframe analysis completely broken for replay
 *
 * AFTER (FIXED):
 * - Uses FamilyCandle.windowStartMillis (actual candle window start)
 * - 1m candles from Dec 24 correctly aggregate into 5m/15m/1h/1d for Dec 24
 * - Timeframe aggregation works identically for replay and live data
 * - Historical backtesting produces same results as live trading
 */
public class FamilyCandleTimestampExtractor implements TimestampExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(FamilyCandleTimestampExtractor.class);
    private static final long MIN_VALID_TIMESTAMP = 1577836800000L; // Jan 1, 2020

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object value = record.value();

        if (value instanceof FamilyCandle) {
            FamilyCandle candle = (FamilyCandle) value;
            long eventTime = candle.getWindowStartMillis();

            // Validate event time is reasonable (after Jan 1, 2020)
            if (eventTime > MIN_VALID_TIMESTAMP) {
                return eventTime;
            } else {
                LOGGER.warn("Invalid windowStartMillis {} for familyId {}, using record timestamp",
                        eventTime, candle.getFamilyId());
                return record.timestamp() > 0 ? record.timestamp() : partitionTime;
            }
        }

        // Fallback to record timestamp or partition time
        return record.timestamp() > 0 ? record.timestamp() : partitionTime;
    }
}
