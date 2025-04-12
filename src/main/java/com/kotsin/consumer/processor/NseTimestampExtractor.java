package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.Candlestick;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom TimestampExtractor for NSE (National Stock Exchange of India) market data.
 * 
 * Purpose:
 * This extractor aligns candlestick time windows with NSE trading hours, which start at 09:15 IST.
 * It ensures 30-minute candles follow the pattern: 09:15-09:45, 09:45-10:15, etc.
 * 
 * How it works:
 * 1. For each incoming record, calculates minutes elapsed since 09:15 IST
 * 2. Determines which 30-minute time bucket the record belongs to
 * 3. Returns the start timestamp of that 30-minute window
 * 
 * This ensures Kafka Streams creates windows aligned with NSE trading hours instead of
 * using standard clock-aligned windows (e.g., 09:00-09:30, 09:30-10:00).
 */
public class NseTimestampExtractor implements TimestampExtractor {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(NseTimestampExtractor.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ZoneId INDIA_ZONE = ZoneId.of("Asia/Kolkata");

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long previousTimestamp) {
        if (record.value() instanceof Candlestick) {
            // Get the record timestamp (typically the event time from the producer)
            long rawTs = record.timestamp();
            if (rawTs <= 0) {
                return System.currentTimeMillis(); // Fallback to wall-clock time if no timestamp
            }
            
            // Convert timestamp to India time zone (IST)
            ZonedDateTime recordTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(rawTs), INDIA_ZONE);
            
            // Find the 09:15 reference point for the trading day
            ZonedDateTime tradingOpen = recordTime.withHour(9).withMinute(15).withSecond(0).withNano(0);
            
            // If the record is from before 09:15, use previous day's 09:15
            if (recordTime.isBefore(tradingOpen)) {
                tradingOpen = tradingOpen.minusDays(1);
                LOGGER.debug("Record time {} is before today's trading open, using previous day's 09:15", 
                        recordTime.format(TIME_FORMAT));
            }
            
            // Calculate minutes elapsed since trading open (09:15)
            long minutesElapsed = (rawTs - tradingOpen.toInstant().toEpochMilli()) / (60 * 1000);
            
            // Determine which 30-minute window this belongs to (0-based)
            // E.g., 0-29 minutes → window 0 (09:15-09:45)
            //       30-59 minutes → window 1 (09:45-10:15)
            int windowIndex = (int) (minutesElapsed / 30);
            
            // Calculate the start time of this 30-minute window
            ZonedDateTime windowStart = tradingOpen.plusMinutes(windowIndex * 30);
            ZonedDateTime windowEnd = windowStart.plusMinutes(30);
            
            // Debug logging to verify alignment
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Record time: {}, Window: {}-{}, WindowIndex: {}", 
                      recordTime.format(TIME_FORMAT),
                      windowStart.format(TIME_FORMAT), 
                      windowEnd.format(TIME_FORMAT),
                      windowIndex);
            }
            
            // Return the window start time in milliseconds
            return windowStart.toInstant().toEpochMilli();
        }
        
        // For non-Candlestick records, use the record's timestamp
        return record.timestamp();
    }
}