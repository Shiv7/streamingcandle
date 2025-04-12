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
 * 
 * The processor sets timestamps in both:
 * 1. The Candlestick object (windowStartMillis and windowEndMillis fields)
 * 2. The Kafka record itself (using withTimestamp method)
 */
public class RecordTimestampOverrideProcessor implements Processor<Windowed<String>, Candlestick, String, Candlestick> {

    private static final Logger LOGGER = LoggerFactory.getLogger(RecordTimestampOverrideProcessor.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
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
        
        // Convert to IST timezone for logging
        ZonedDateTime startTimeIST = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(windowStart), IST_ZONE);
        ZonedDateTime endTimeIST = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(windowEnd), IST_ZONE);
        
        // Log window information at INFO level for debugging
        LOGGER.info("Processing window for {}: {} to {} IST", 
                record.key().key(), 
                startTimeIST.format(TIME_FORMATTER),
                endTimeIST.format(TIME_FORMATTER));
        
        // Also log the incoming record timestamp to diagnose issues
        long recordTs = record.timestamp();
        ZonedDateTime recordTimeIST = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(recordTs), IST_ZONE);
        LOGGER.info("Original record timestamp: {} IST", recordTimeIST.format(TIME_FORMATTER));

        // Update the candlestick payload with window boundaries
        Candlestick candle = record.value();
        candle.setWindowStartMillis(windowStart);
        candle.setWindowEndMillis(windowEnd);

        // CRITICAL: Get the current timestamp in UTC to verify timestamp handling
        long currentTimeMillis = System.currentTimeMillis();
        ZonedDateTime currentTimeIST = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(currentTimeMillis), IST_ZONE);
        
        LOGGER.info("Current system time: {} IST", currentTimeIST.format(TIME_FORMATTER));
        LOGGER.info("Window end time (target): {} IST", endTimeIST.format(TIME_FORMATTER));
        
        // Force the explicit timestamp by calling withTimestamp() - this is CRITICAL
        Record<String, Candlestick> newRecord = record
                .withKey(record.key().key())
                .withTimestamp(windowEnd);  // Use window end time as the record timestamp
        
        // Log the output record details
        LOGGER.info("Setting output record timestamp to: {} IST (window end)", 
                endTimeIST.format(TIME_FORMATTER));
        
        // Forward the record to the next processor in the topology
        context.forward(newRecord);
    }

    @Override
    public void close() {
        // No cleanup needed.
    }
}
