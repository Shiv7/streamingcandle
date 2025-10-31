package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.model.OpenInterest;
import com.kotsin.consumer.util.MarketTimeAligner;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/**
 * Timestamp extractor for OI updates.
 * Uses receivedTimestamp as event time, shifted by exchange-specific offset
 * so 1m OI windows align with market open times.
 */
public class OITimestampExtractorWithOffset implements TimestampExtractor {

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object v = record.value();
        long baseTs = 0L;
        if (v instanceof OpenInterest oi) {
            Long ts = oi.getReceivedTimestamp();
            baseTs = (ts != null && ts > 0) ? ts : (record.timestamp() > 0 ? record.timestamp() : partitionTime);
            String exch = oi.getExchange();
            int offMin = MarketTimeAligner.getWindowOffsetMinutes(exch, 1);
            return baseTs + offMin * 60_000L;
        }
        return record.timestamp() > 0 ? record.timestamp() : Math.max(partitionTime, 0L);
    }
}

