package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.model.OpenInterest;
import com.kotsin.consumer.util.MarketTimeAligner;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/**
 * Timestamp extractor for OI updates with per-window alignment.
 * Uses OI event timestamp (if present) or record/partition time and applies
 * exchange-specific offset for the given window size.
 */
public class OITimestampExtractorWithWindowOffset implements TimestampExtractor {

    private final int windowSizeMinutes;

    public OITimestampExtractorWithWindowOffset(int windowSizeMinutes) {
        this.windowSizeMinutes = windowSizeMinutes;
    }

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object v = record.value();
        if (v instanceof OpenInterest oi) {
            Long rts = oi.getReceivedTimestamp();
            long ts = (rts != null && rts > 0) ? rts : (record.timestamp() > 0 ? record.timestamp() : partitionTime);
            String exch = oi.getExchange();
            int offMin = MarketTimeAligner.getWindowOffsetMinutes(exch, windowSizeMinutes);
            return ts + offMin * 60_000L;
        }
        long rt = record.timestamp();
        return rt > 0 ? rt : Math.max(partitionTime, 0L);
    }
}
