package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.TickData;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
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
            // Not a TickData record, fall back to stream time
            return record.timestamp();
        }

        TickData tick = (TickData) value;
        String tickDt = tick.getTickDt();

        if (tickDt == null || tickDt.isBlank()) {
            LOGGER.warn("Tick has null or empty timestamp, falling back to stream time for token {}", tick.getToken());
            return record.timestamp();
        }

        try {
            // Handle the "/Date(1746430676000)/" format
            if (tickDt.startsWith("/Date(") && tickDt.endsWith(")/")) {
                String millisStr = tickDt.substring(6, tickDt.length() - 2);
                return Long.parseLong(millisStr);
            }
            // Handle standard format
            ZonedDateTime zdt = ZonedDateTime.parse(tickDt, DT_FORMATTER);
            return zdt.toInstant().toEpochMilli();
        } catch (DateTimeParseException | NumberFormatException e) {
            LOGGER.error("Could not parse timestamp '{}' for token {}. Falling back to stream time.", tickDt, tick.getToken(), e);
            return record.timestamp();
        }
    }
}
