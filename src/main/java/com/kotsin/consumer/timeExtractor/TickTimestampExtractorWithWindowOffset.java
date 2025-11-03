package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.util.MarketTimeAligner;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * TimestampExtractor that extracts event time from TickData and applies
 * market-specific offset for a given window size to align time windows.
 */
public class TickTimestampExtractorWithWindowOffset implements TimestampExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TickTimestampExtractorWithWindowOffset.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter FLEX_DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]");

    private final int windowSizeMinutes;

    public TickTimestampExtractorWithWindowOffset(int windowSizeMinutes) {
        this.windowSizeMinutes = windowSizeMinutes;
    }

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long previousTimestamp) {
        Object value = record.value();
        if (!(value instanceof TickData)) {
            return record.timestamp();
        }

        TickData tick = (TickData) value;
        long baseTs = extractBaseTimestamp(tick, record, previousTimestamp);

        // Apply market-specific offset to align N-minute boundaries
        String exchange = tick.getExchange();
        int offsetMinutes = MarketTimeAligner.getWindowOffsetMinutes(exchange, windowSizeMinutes);
        long offsetMs = offsetMinutes * 60_000L;

        long alignedTs = baseTs + offsetMs;

        if (baseTs > 0) {
            tick.setTimestamp(baseTs);
        } else {
            long fallback = record.timestamp() > 0 ? record.timestamp() : previousTimestamp;
            tick.setTimestamp(Math.max(fallback, 0L));
        }

        return alignedTs;
    }

    private long extractBaseTimestamp(TickData tick,
                                      ConsumerRecord<Object, Object> record,
                                      long previousTimestamp) {
        if (tick.getTimestamp() > 0) {
            return tick.getTimestamp();
        }

        long timeField = tick.getTime();
        if (timeField > 0) {
            long candidate = (timeField < 1000_000_000_000L) ? timeField * 1000L : timeField;
            return candidate;
        }

        String tickDt = tick.getTickDt();
        if (tickDt == null || tickDt.isBlank()) {
            long fallback = record.timestamp() > 0 ? record.timestamp() : previousTimestamp;
            return Math.max(fallback, 0L);
        }

        try {
            if (tickDt.startsWith("/Date(") && tickDt.endsWith(")/")) {
                String millisStr = tickDt.substring(6, tickDt.length() - 2);
                long ts = Long.parseLong(millisStr);
                long now = System.currentTimeMillis();
                if (ts > now + 60000L || ts <= 0) {
                    long fb = record.timestamp() > 0 ? record.timestamp() : previousTimestamp;
                    return Math.max(fb, 0L);
                }
                return ts;
            }

            ZonedDateTime zdt = LocalDateTime.parse(tickDt, FLEX_DTF).atZone(IST);
            long parsed = zdt.toInstant().toEpochMilli();
            if (parsed <= 0) {
                long fb = record.timestamp() > 0 ? record.timestamp() : previousTimestamp;
                return Math.max(fb, 0L);
            }
            return parsed;

        } catch (DateTimeParseException | NumberFormatException e) {
            long fallback = record.timestamp() > 0 ? record.timestamp() : previousTimestamp;
            return Math.max(fallback, 0L);
        }
    }
}

