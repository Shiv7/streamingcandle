package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.domain.model.FamilyCandle;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NseAlignedTimestampExtractor - Event-time extractor with NSE market alignment.
 *
 * PROBLEM:
 * Kafka Streams TimeWindows aligns to epoch (00:00 UTC), creating windows like:
 * - 30m: 9:00-9:30, 9:30-10:00, 10:00-10:30... (in IST)
 *
 * But NSE opens at 9:15 AM IST, so the first 30m candle (9:00-9:30) only contains
 * 15 minutes of actual trading data (9:15-9:30). This breaks technical indicators
 * like SuperTrend and Bollinger Bands which expect full 30-minute candles.
 *
 * SOLUTION:
 * Offset the event timestamp by -15 minutes before windowing. This makes Kafka
 * think the candle is 15 minutes earlier:
 *
 * Real Time  -> Virtual Time -> Kafka Window    -> Real Window (after +15min)
 * 9:15 AM    -> 9:00 AM      -> 9:00-9:30       -> 9:15-9:45
 * 9:44 AM    -> 9:29 AM      -> 9:00-9:30       -> 9:15-9:45
 * 9:45 AM    -> 9:30 AM      -> 9:30-10:00      -> 9:45-10:15
 *
 * The TimeframeAggregator must add 15 minutes back to window boundaries in output.
 *
 * CRITICAL: This extractor must be used ONLY with TimeframeAggregator which
 * applies the reverse offset (+15 minutes) to the output window times.
 */
public class NseAlignedTimestampExtractor implements TimestampExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(NseAlignedTimestampExtractor.class);
    private static final long MIN_VALID_TIMESTAMP = 1577836800000L; // Jan 1, 2020

    // NSE market opens at 9:15 AM IST, which is 15 minutes past the hour
    // Offset the timestamp by -15 minutes so epoch-aligned windows become NSE-aligned
    public static final long NSE_OFFSET_MS = 15 * 60 * 1000L;  // 15 minutes = 900,000 ms

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object value = record.value();

        if (value instanceof FamilyCandle) {
            FamilyCandle candle = (FamilyCandle) value;
            long eventTime = candle.getWindowStartMillis();

            // Validate event time is reasonable (after Jan 1, 2020)
            if (eventTime > MIN_VALID_TIMESTAMP) {
                // CRITICAL: Offset by -15 minutes for NSE alignment
                // This makes Kafka's epoch-aligned windows effectively NSE-aligned
                // The TimeframeAggregator will add 15 minutes back in the output
                long alignedTime = eventTime - NSE_OFFSET_MS;
                return alignedTime;
            } else {
                LOGGER.warn("Invalid windowStartMillis {} for familyId {}, using record timestamp",
                        eventTime, candle.getFamilyId());
                long fallbackTime = record.timestamp() > 0 ? record.timestamp() : partitionTime;
                return fallbackTime - NSE_OFFSET_MS;
            }
        }

        // Fallback to record timestamp or partition time (also offset)
        long fallbackTime = record.timestamp() > 0 ? record.timestamp() : partitionTime;
        return fallbackTime - NSE_OFFSET_MS;
    }
}
