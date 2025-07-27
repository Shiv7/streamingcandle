package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.Candlestick;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/**
 * A simple TimestampExtractor that extracts the event time from the
 * Candlestick's window start time. This is used for aggregating
 * 1-minute candles into larger timeframes.
 */
public class CandleTimestampExtractor implements TimestampExtractor {

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long previousTimestamp) {
        Object value = record.value();
        if (!(value instanceof Candlestick)) {
            // Not a Candlestick record, fall back to stream time
            return record.timestamp();
        }

        Candlestick candle = (Candlestick) value;
        // Use the start of the candle's window as its timestamp for subsequent processing
        return candle.getWindowStartMillis();
    }
}
