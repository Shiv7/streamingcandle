package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.util.MarketTimeAligner;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/**
 * Timestamp extractor for orderbook snapshots with per-window alignment.
 * Uses snapshot timestamp (or record/partition time) and applies
 * exchange-specific offset for the given window size so windows align
 * with exchange trading schedule (e.g., NSE +15 minutes modulo window).
 */
public class OrderbookTimestampExtractorWithWindowOffset implements TimestampExtractor {

    private final int windowSizeMinutes;

    public OrderbookTimestampExtractorWithWindowOffset(int windowSizeMinutes) {
        this.windowSizeMinutes = windowSizeMinutes;
    }

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object v = record.value();
        long baseTs = 0L;

        if (v instanceof OrderBookSnapshot snap) {
            long ts = snap.getTimestamp();
            baseTs = ts > 0 ? ts : (record.timestamp() > 0 ? record.timestamp() : partitionTime);
            String exch = snap.getExch();
            int offMin = MarketTimeAligner.getWindowOffsetMinutes(exch, windowSizeMinutes);
            return baseTs + offMin * 60_000L;
        }

        long rt = record.timestamp();
        return rt > 0 ? rt : Math.max(partitionTime, 0L);
    }
}

