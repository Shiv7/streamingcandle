package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.util.MarketTimeAligner;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/**
 * Timestamp extractor for orderbook snapshots.
 * Uses receivedTimestamp as event time and shifts by exchange-specific offset
 * to align 1m windows with market open (e.g., NSE +15 minutes).
 */
public class OrderbookTimestampExtractorWithOffset implements TimestampExtractor {

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object v = record.value();
        long baseTs = 0L;

        if (v instanceof OrderBookSnapshot snap) {
            long ts = snap.getTimestamp();
            baseTs = ts > 0 ? ts : (record.timestamp() > 0 ? record.timestamp() : partitionTime);
            String exch = snap.getExch();
            int offMin = MarketTimeAligner.getWindowOffsetMinutes(exch, 1);
            return baseTs + offMin * 60_000L;
        }

        return record.timestamp() > 0 ? record.timestamp() : Math.max(partitionTime, 0L);
    }
}

