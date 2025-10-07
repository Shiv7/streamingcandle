package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.model.Candlestick;
import com.kotsin.consumer.util.MarketTimeAligner;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

import java.util.Objects;

/**
 * Timestamp extractor for multi-minute rollups built from 1m candles.
 * Uses the 1m candle END time and applies an exchange/window-size offset
 * so windows align to exchange rules but stream-time still advances.
 */
public final class MultiMinuteOffsetTimestampExtractor implements TimestampExtractor {

    private final int windowSizeMinutes;

    public MultiMinuteOffsetTimestampExtractor(int windowSizeMinutes) {
        this.windowSizeMinutes = windowSizeMinutes;
    }

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        long baseTs = 0L;

        Object v = record.value();
        if (v instanceof Candlestick c) {
            // Prefer END time for faster window closure; fall back to START, then record/partition time
            if (c.getWindowEndMillis() > 0L) {
                baseTs = c.getWindowEndMillis();
            } else if (c.getWindowStartMillis() > 0L) {
                baseTs = c.getWindowStartMillis();
            } else if (record.timestamp() > 0L) {
                baseTs = record.timestamp();
            } else {
                baseTs = partitionTime;
            }

            if (baseTs <= 0L) baseTs = System.currentTimeMillis();

            // SHIFT (do not collapse) by per-exchange offset to align boundaries
            String exch = c.getExchange();
            int offMin;
            if (Objects.nonNull(exch)) {
                offMin = MarketTimeAligner.getWindowOffsetMinutes(exch, windowSizeMinutes);
            } else {
                offMin = MarketTimeAligner.getWindowOffsetMinutes("N", windowSizeMinutes);
            }
            return baseTs + offMin * 60_000L;
        }

        // Non-candle records (unlikely here)
        if (record.timestamp() > 0L) return record.timestamp();
        return Math.max(partitionTime, 0L);
    }
}
