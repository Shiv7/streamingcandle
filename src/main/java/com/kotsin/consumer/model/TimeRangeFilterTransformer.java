package com.kotsin.consumer.model;

import org.apache.kafka.streams.kstream.Transformer;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.To;
import org.apache.kafka.streams.KeyValue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class TimeRangeFilterTransformer<V> implements Transformer<String, V, KeyValue<String, V>> {

    private final ZonedDateTime startTime;
    private final ZonedDateTime endTime;
    private ProcessorContext context;

    public TimeRangeFilterTransformer(ZonedDateTime startTime, ZonedDateTime endTime) {
        this.startTime = startTime;
        this.endTime = endTime;
    }

    @Override
    public void init(ProcessorContext context) {
        this.context = context;
    }

    @Override
    public KeyValue<String, V> transform(String key, V value) {
        if (value == null) {
            return null;
        }
        // Retrieve the record timestamp (already truncated to the minute by your KafkaRecordTimestampExtractor)
        long recordTimestamp = context.timestamp();

        // Convert to ZonedDateTime in IST
        ZonedDateTime recordTimeIST = Instant.ofEpochMilli(recordTimestamp)
                .atZone(ZoneId.of("Asia/Kolkata"));

        // Check if recordTimeIST is within [startTime, endTime]
        boolean inRange = !recordTimeIST.isBefore(startTime) && !recordTimeIST.isAfter(endTime);
        if (inRange) {
            // Forward only if in-range
            context.forward(key, value, To.all().withTimestamp(recordTimestamp));
        }
        // Return null so we do not emit the record automatically
        return null;
    }

    @Override
    public void close() {
        // No cleanup needed
    }
}
