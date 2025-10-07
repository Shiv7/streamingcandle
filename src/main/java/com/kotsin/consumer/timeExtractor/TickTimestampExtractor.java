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

                // CRITICAL: Validate timestamp is reasonable
                long now = System.currentTimeMillis();
                if (ts > now + 60000L) { // More than 1 min in future
                    LOGGER.error("Timestamp {} is in the future for token {}. Using record timestamp.", ts, tick.getToken());
                    return record.timestamp();
                }
                if (ts < now - 365L * 24 * 3600 * 1000) { // More than 1 year old
                    LOGGER.warn("Timestamp {} is more than 1 year old for token {}. Using as-is (historical data).", ts, tick.getToken());
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