package com.kotsin.consumer.util;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Custom timestamp extractor that truncates message timestamps to the start of the minute.
 */
public class KafkaRecordTimestampExtractor implements TimestampExtractor {

    @Override
    public long extract(ConsumerRecord<Object, Object> consumerRecord, long previousTimestamp) {
        long timestamp = consumerRecord.timestamp();
        Instant instant = Instant.ofEpochMilli(timestamp);
        // Truncate to the start of the minute (ignore seconds/milliseconds)
        Instant roundedTimestamp = instant.truncatedTo(ChronoUnit.MINUTES);
        return roundedTimestamp.toEpochMilli();
    }
}
