package com.kotsin.consumer.timeExtractor;


import com.kotsin.consumer.model.Candlestick;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/**
 * Event-time extractor for aggregated candles.
 * Uses the candle's window start as event time so downstream tumbling windows align perfectly.
 */
public final class CandleTimestampExtractor implements TimestampExtractor {

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        final Object v = record.value();

        if (v instanceof Candlestick c) {
            // Preferred: explicit window start set by the 1m aggregator
            final long start = c.getWindowStartMillis();
            if (start > 0L) {
                return start;
            }

            // Fallback: if only end is set, approximate start
            final long end = c.getWindowEndMillis();
            if (end > 0L) {
                final long approxStart = end - 60_000L; // assumes 1m base candles
                if (approxStart > 0L) {
                    return approxStart;
                }
            }
        }

        // Secondary fallbacks
        final long recTs = record.timestamp();
        if (recTs > 0L) return recTs;
        if (partitionTime > 0L) return partitionTime;

        // Last resort: fail fast
        throw new RuntimeException(
                "CandleTimestampExtractor: no usable timestamp for topic " + record.topic());
    }
}