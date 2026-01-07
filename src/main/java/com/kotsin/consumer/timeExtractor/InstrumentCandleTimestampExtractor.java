package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.domain.model.InstrumentCandle;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TimestampExtractor for InstrumentCandle that uses event time (windowStartMillis)
 * instead of Kafka record timestamp.
 * 
 * CRITICAL: This ensures windows close based on DATA TIME, not wall clock or ingestion time.
 * Without this, replay of historical data won't work correctly because windows never close.
 */
public class InstrumentCandleTimestampExtractor implements TimestampExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(InstrumentCandleTimestampExtractor.class);

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Object value = record.value();
        
        if (value instanceof InstrumentCandle) {
            InstrumentCandle candle = (InstrumentCandle) value;
            long eventTime = candle.getWindowStartMillis();
            
            // Validate event time is reasonable (after Jan 1, 2020)
            if (eventTime > 1577836800000L) {
                return eventTime;
            } else {
                LOGGER.warn("Invalid windowStartMillis {} for {}, using record timestamp",
                    eventTime, candle.getScripCode());
                return record.timestamp() > 0 ? record.timestamp() : partitionTime;
            }
        }
        
        // Fallback to record timestamp or partition time
        return record.timestamp() > 0 ? record.timestamp() : partitionTime;
    }
}
