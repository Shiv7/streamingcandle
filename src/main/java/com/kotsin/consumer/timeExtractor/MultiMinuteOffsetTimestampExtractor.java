package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.model.EnrichedCandlestick;
import com.kotsin.consumer.util.MarketTimeAligner;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Timestamp extractor for multi-minute rollups built from 1m candles.
 * Uses the 1m candle END time and applies an exchange/window-size offset
 * so windows align to exchange rules but stream-time still advances.
 *
 * CRITICAL: Never falls back to System.currentTimeMillis() to handle lag correctly.
 */
public final class MultiMinuteOffsetTimestampExtractor implements TimestampExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(MultiMinuteOffsetTimestampExtractor.class);
    private final int windowSizeMinutes;

    public MultiMinuteOffsetTimestampExtractor(int windowSizeMinutes) {
        this.windowSizeMinutes = windowSizeMinutes;
    }

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        long baseTs = 0L;

        Object v = record.value();
        if (v instanceof EnrichedCandlestick c) {
            // FIXED: Use Kafka record timestamp directly!
            // When a 1m candle window closes, Kafka Streams assigns the record timestamp = window END time
            // This is exactly what we need for immediate window closure in multi-minute aggregation
            // No more 60-second wait for the next candle!

            if (record.timestamp() > 0L) {
                baseTs = record.timestamp();
            } else if (c.getWindowEndMillis() > 0L) {
                // Fallback to candle's END time if Kafka timestamp missing
                baseTs = c.getWindowEndMillis();
            } else if (c.getWindowStartMillis() > 0L) {
                // Last resort: START time
                baseTs = c.getWindowStartMillis();
            } else {
                baseTs = partitionTime;
            }

            // CRITICAL: If still invalid, log error and use partition time
            // NEVER use System.currentTimeMillis() - it breaks lag processing
            if (baseTs <= 0L) {
                LOGGER.error("Invalid timestamp for candle {} (company: {}). Using partition time: {}",
                        c.getScripCode(), c.getCompanyName(), partitionTime);
                baseTs = Math.max(partitionTime, 0L);
            }

            // SHIFT (do not collapse) by per-exchange offset to align boundaries
            String exch = c.getExchange();
            int offMin = MarketTimeAligner.getWindowOffsetMinutes(exch, windowSizeMinutes);
            return baseTs + offMin * 60_000L;
        }

        // Non-candle records (unlikely here)
        if (record.timestamp() > 0L) return record.timestamp();
        return Math.max(partitionTime, 0L);
    }
}
