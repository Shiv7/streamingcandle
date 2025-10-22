package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.model.TickData;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * A simple, correct TimestampExtractor that extracts the event time from
 * the TickData payload. This is used for the initial 1-minute candle aggregation.
 */
public class TickTimestampExtractor implements TimestampExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TickTimestampExtractor.class);
    private static final DateTimeFormatter DT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata"));

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long previousTimestamp) {
        Object value = record.value();
        if (!(value instanceof TickData)) {
            // Not a TickData record, fall back to record timestamp
            return record.timestamp();
        }

        TickData tick = (TickData) value;
        String tickDt = tick.getTickDt();

        if (tickDt == null || tickDt.isBlank()) {
            LOGGER.warn("Tick has null or empty timestamp, using Kafka record timestamp for token {}", tick.getToken());
            return record.timestamp();
        }

        try {
            // Handle the "/Date(1746430676000)/" format
            if (tickDt.startsWith("/Date(") && tickDt.endsWith(")/")) {
                String millisStr = tickDt.substring(6, tickDt.length() - 2);
                long ts = Long.parseLong(millisStr);

                // CRITICAL: Reject negative timestamps (before Unix epoch)
                if (ts < 0) {
                    LOGGER.warn("Negative timestamp {} for token {}. Using Kafka record timestamp instead.", ts, tick.getToken());
                    return record.timestamp();
                }

                // CRITICAL: Validate using business logic (trading hours), NOT wall-clock time
                // This allows replay of historical data without "too old" rejections
                
                // Validate timestamp is within reasonable range (not too far from record timestamp)
                // Use record timestamp as reference (works for both live and replay)
                long recordTs = record.timestamp();
                if (recordTs > 0) {
                    long deviation = Math.abs(ts - recordTs);
                    // Allow 7 days deviation (handles clock skew, but catches corrupt data)
                    if (deviation > 7L * 24 * 3600 * 1000) {
                        LOGGER.warn("Timestamp {} deviates {} ms from record timestamp {} for token {}. Using record timestamp.", 
                            ts, deviation, recordTs, tick.getToken());
                        return recordTs;
                    }
                }
                
                // Additional validation: Check if timestamp is within trading hours
                // This catches obviously wrong timestamps (e.g., midnight, weekends)
                try {
                    ZonedDateTime zdt = ZonedDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(ts), 
                        ZoneId.of("Asia/Kolkata")
                    );
                    int hour = zdt.getHour();
                    int dayOfWeek = zdt.getDayOfWeek().getValue();
                    
                    // Weekend check
                    if (dayOfWeek == 6 || dayOfWeek == 7) {
                        LOGGER.debug("Timestamp {} is on weekend for token {}. Using record timestamp.", ts, tick.getToken());
                        return recordTs > 0 ? recordTs : ts; // Use record if available
                    }
                    
                    // Trading hours check (9 AM to 4 PM IST with buffer)
                    if (hour < 8 || hour > 17) {
                        LOGGER.debug("Timestamp {} is outside trading hours for token {}. Using record timestamp.", ts, tick.getToken());
                        return recordTs > 0 ? recordTs : ts; // Use record if available
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to validate timestamp {} for token {}: {}", ts, tick.getToken(), e.getMessage());
                }

                return ts;
            }

            // Handle standard format
            ZonedDateTime zdt = ZonedDateTime.parse(tickDt, DT_FORMATTER);
            return zdt.toInstant().toEpochMilli();

        } catch (DateTimeParseException | NumberFormatException e) {
            LOGGER.error("Could not parse timestamp '{}' for token {}. Using Kafka record timestamp.", tickDt, tick.getToken(), e);
            // CRITICAL: Use record.timestamp(), NEVER System.currentTimeMillis()
            return record.timestamp();
        }
    }
}