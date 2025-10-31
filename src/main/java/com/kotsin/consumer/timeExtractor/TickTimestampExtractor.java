package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.model.TickData;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * A simple, correct TimestampExtractor that extracts the event time from
 * the TickData payload. This is used for the initial 1-minute candle aggregation.
 */
public class TickTimestampExtractor implements TimestampExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TickTimestampExtractor.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter FLEX_DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]");

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long previousTimestamp) {
        Object value = record.value();
        if (!(value instanceof TickData)) {
            // Not a TickData record, fall back to record timestamp
            return record.timestamp();
        }

        TickData tick = (TickData) value;
        // 1) If timestamp already set (e.g., by deserializer), use it
        if (tick.getTimestamp() > 0) {
            return tick.getTimestamp();
        }

        // 2) Try numeric Time field (ms or sec)
        long timeField = tick.getTime();
        if (timeField > 0) {
            long candidate = (timeField < 1000_000_000_000L) ? timeField * 1000L : timeField;
            tick.setTimestamp(candidate);
            return candidate;
        }

        String tickDt = tick.getTickDt();
        if (tickDt == null || tickDt.isBlank()) {
            long fallback = record.timestamp() > 0 ? record.timestamp() : previousTimestamp;
            tick.setTimestamp(Math.max(fallback, 0L));
            return tick.getTimestamp();
        }

        try {
            // Handle the "/Date(1746430676000)/" format
            if (tickDt.startsWith("/Date(") && tickDt.endsWith(")/")) {
                String millisStr = tickDt.substring(6, tickDt.length() - 2);
                long ts = Long.parseLong(millisStr);
                long now = System.currentTimeMillis();
                if (ts > now + 60000L || ts <= 0) {
                    long fb = record.timestamp() > 0 ? record.timestamp() : previousTimestamp;
                    tick.setTimestamp(Math.max(fb, 0L));
                    return tick.getTimestamp();
                }
                tick.setTimestamp(ts);
                return ts;
            }

            // Handle standard local time string in IST with optional millis
            ZonedDateTime zdt = LocalDateTime.parse(tickDt, FLEX_DTF).atZone(IST);
            long parsed = zdt.toInstant().toEpochMilli();
            if (parsed <= 0) {
                long fb = record.timestamp() > 0 ? record.timestamp() : previousTimestamp;
                tick.setTimestamp(Math.max(fb, 0L));
                return tick.getTimestamp();
            }
            tick.setTimestamp(parsed);
            return parsed;

        } catch (DateTimeParseException | NumberFormatException e) {
            long fallback = record.timestamp() > 0 ? record.timestamp() : previousTimestamp;
            tick.setTimestamp(Math.max(fallback, 0L));
            return tick.getTimestamp();
        }
    }
}
