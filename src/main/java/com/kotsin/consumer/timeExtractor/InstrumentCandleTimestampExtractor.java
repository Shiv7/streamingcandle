package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.model.InstrumentCandle;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Timestamp extractor for InstrumentCandle records.
 * Prefers windowEndMillis, falls back to windowStartMillis, then record timestamp.
 */
public class InstrumentCandleTimestampExtractor implements TimestampExtractor {
    private static final Logger LOGGER = LoggerFactory.getLogger(InstrumentCandleTimestampExtractor.class);

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long previousTimestamp) {
        Object value = record.value();
        if (!(value instanceof InstrumentCandle)) {
            return record.timestamp();
        }

        InstrumentCandle candle = (InstrumentCandle) value;
        Long ts = candle.getWindowEndMillis();
        if (ts == null) {
            ts = candle.getWindowStartMillis();
        }
        if (ts == null) {
            LOGGER.warn("InstrumentCandle missing window times for scripCode {}. Using record timestamp.", candle.getScripCode());
            return record.timestamp();
        }
        return ts;
    }
}
