package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.TickData;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * A custom TimestampExtractor that calculates the effective event time as the number of milliseconds
 * elapsed since the NSE trading open (09:15 IST). For each TickData record, if the record’s local time
 * is before 09:15, it treats the previous day’s 09:15 as the reference.
 */
public class NseTimestampExtractor implements TimestampExtractor {

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long previousTimestamp) {
        if (record.value() instanceof TickData) {
            TickData tick = (TickData) record.value();
            // Use tick.getTimestamp() if present, else fallback to record.timestamp()
            long rawTs = tick.getTimestamp() > 0 ? tick.getTimestamp() : record.timestamp();
            if (rawTs <= 0) {
                // fallback to system time if not set
                return System.currentTimeMillis();
            }
            ZonedDateTime recordTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(rawTs), ZoneId.of("Asia/Kolkata"));
            // Compute today's 09:15 IST.
            ZonedDateTime tradingOpen = recordTime.withHour(9).withMinute(15).withSecond(0).withNano(0);
            // If the event occurs before today's 09:15, use yesterday's 09:15.
            if (recordTime.isBefore(tradingOpen)) {
                tradingOpen = tradingOpen.minusDays(1);
            }
            long effectiveTimestamp = rawTs - tradingOpen.toInstant().toEpochMilli();
            // Ensure non-negative; if negative, return 0.
            return effectiveTimestamp < 0 ? 0 : effectiveTimestamp;
        }
        // For non-TickData records, fall back to the record timestamp.
        return record.timestamp();
    }
}