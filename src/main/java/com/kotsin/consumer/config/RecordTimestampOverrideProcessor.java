package com.kotsin.consumer.config;

import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.kstream.Windowed;
import com.kotsin.consumer.model.Candlestick;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processor that overrides the record timestamp with window end time.
 * For 30-minute candles, this ensures timestamps show up as 9:45, 10:15, etc.
 */
public class RecordTimestampOverrideProcessor implements Processor<Windowed<String>, Candlestick, String, Candlestick> {

    private static final Logger LOGGER = LoggerFactory.getLogger(RecordTimestampOverrideProcessor.class);
    private ProcessorContext<String, Candlestick> context;

    @Override
    public void init(ProcessorContext<String, Candlestick> context) {
        this.context = context;
    }

    @Override
    public void process(Record<Windowed<String>, Candlestick> record) {
        // Extract window information
        long windowStart = record.key().window().start();
        long windowEnd = record.key().window().end();
        
        // Log window information for debugging
        ZonedDateTime startTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(windowStart), ZoneId.of("Asia/Kolkata"));
        ZonedDateTime endTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(windowEnd), ZoneId.of("Asia/Kolkata"));
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        LOGGER.debug("Window: {} to {}", startTime.format(formatter), endTime.format(formatter));

        // Update the candlestick payload with window boundaries
        Candlestick candle = record.value();
        candle.setWindowStartMillis(windowStart);
        candle.setWindowEndMillis(windowEnd);

        // Forward a new record with the original key, the updated payload, and window end timestamp
        Record<String, Candlestick> newRecord = record.withKey(record.key().key()).withTimestamp(windowEnd);
        context.forward(newRecord);
    }

    @Override
    public void close() {
        // No cleanup needed.
    }
}
