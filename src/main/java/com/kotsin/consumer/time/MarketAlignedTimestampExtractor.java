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
    private static final long OFFSET_MS_15_MIN = 15L * 60L * 1000L; // currently unused (market-shift disabled)

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object value = record.value();
        long ts = record.timestamp();
        String exch = null;

        if (value instanceof TickData) {
            TickData t = (TickData) value;
            exch = t.getExchange();
            long vts = t.getTimestamp();
            // Validate timestamp is reasonable before using it
            if (vts > 0 && isValidTimestamp(vts)) {
                ts = vts;
            } else if (vts > 0) {
                // Invalid timestamp - use Kafka record timestamp instead
                System.err.println("Invalid timestamp from TickData: " + vts + ", using record timestamp: " + ts);
            }
        } else if (value instanceof OrderBookSnapshot) {
            OrderBookSnapshot ob = (OrderBookSnapshot) value;
            exch = ob.getExchange();
            long vts = ob.getTimestamp();
            if (vts > 0 && isValidTimestamp(vts)) {
                ts = vts;
            }
        }

        // Market-aligned shift disabled for now — return raw event-time
        return ts;
    }
    
    /**
     * Validate timestamp is within reasonable range (year 2020-2050)
     */
    private boolean isValidTimestamp(long timestamp) {
        long year2020 = 1577836800000L; // Jan 1, 2020
        long year2050 = 2524608000000L; // Jan 1, 2050
        return timestamp >= year2020 && timestamp <= year2050;
    }
}
