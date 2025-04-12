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
import com.kotsin.consumer.model.TickData;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Custom TimestampExtractor that computes the effective event time as the number of milliseconds
 * elapsed since the NSE trading open (09:15 IST). If the record occurs before 09:15,
 * the previous day’s 09:15 is used.
 */
public class NseTimestampExtractor implements TimestampExtractor {

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long previousTimestamp) {
        if (record.value() instanceof TickData) {
            TickData tick = (TickData) record.value();
            long rawTs = tick.getTimestamp() > 0 ? tick.getTimestamp() : record.timestamp();
            if (rawTs <= 0) {
                return System.currentTimeMillis();
            }
            ZonedDateTime recordTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(rawTs), ZoneId.of("Asia/Kolkata"));
            ZonedDateTime tradingOpen = recordTime.withHour(9).withMinute(15).withSecond(0).withNano(0);
            if (recordTime.isBefore(tradingOpen)) {
                tradingOpen = tradingOpen.minusDays(1);
            }
            long effectiveTs = rawTs - tradingOpen.toInstant().toEpochMilli();
            return effectiveTs < 0 ? 0 : effectiveTs;
        }
        return record.timestamp();
    }
}