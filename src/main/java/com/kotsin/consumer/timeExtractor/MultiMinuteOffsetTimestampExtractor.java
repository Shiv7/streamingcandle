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
            // Prefer START time to ensure consistent roll-up grouping across offsets (e.g., 2m on NSE)
            // Using END can split adjacent 1m candles into different 2m windows when offset != 0
            if (c.getWindowStartMillis() > 0L) {
                baseTs = c.getWindowStartMillis();
            } else if (c.getWindowEndMillis() > 0L) {
                baseTs = c.getWindowEndMillis();
            } else if (record.timestamp() > 0L) {
                baseTs = record.timestamp();
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
