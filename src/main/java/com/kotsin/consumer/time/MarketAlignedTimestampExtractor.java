package com.kotsin.consumer.time;

import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.TickData;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/**
 * Aligns event timestamps to market open (09:15 AM IST) by applying a 15-minute offset for NSE.
 * This makes Kafka Streams windows start at 09:15-based boundaries rather than wall-clock hours.
 */
public class MarketAlignedTimestampExtractor implements TimestampExtractor {
    private static final long OFFSET_MS_15_MIN = 15L * 60L * 1000L;

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object value = record.value();
        long ts = record.timestamp();
        String exch = null;

        if (value instanceof TickData) {
            TickData t = (TickData) value;
            exch = t.getExchange();
            long vts = t.getTimestamp();
            if (vts > 0) ts = vts;
        } else if (value instanceof OrderBookSnapshot) {
            OrderBookSnapshot ob = (OrderBookSnapshot) value;
            exch = ob.getExchange();
            long vts = ob.getTimestamp();
            if (vts > 0) ts = vts;
        }

        // Apply 15-minute offset for NSE (exchange code "N")
        if (exch != null && exch.equalsIgnoreCase("N")) {
            return ts - OFFSET_MS_15_MIN;
        }
        return ts;
    }
}

