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
 * market-specific offset to align windows with market open times.
 * 
 * CRITICAL: NSE opens at 9:15 AM, so 1-minute windows need 15-minute offset
 * to align correctly (9:15-9:16, 9:16-9:17, etc.)
 */
public class TickTimestampExtractorWithOffset implements TimestampExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TickTimestampExtractorWithOffset.class);
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
        long baseTs = extractBaseTimestamp(tick, record, previousTimestamp);

        // CRITICAL: Apply market-specific offset to align windows
        // For NSE: +15 minutes to align windows at :15, :16, :17, etc.
        // For MCX: +0 minutes (already at :00)
        String exchange = tick.getExchange();
        int offsetMinutes = MarketTimeAligner.getWindowOffsetMinutes(exchange, 1);
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

    /**
     * Extract base timestamp from TickData
     */
    private long extractBaseTimestamp(TickData tick,
                                      ConsumerRecord<Object, Object> record,
                                      long previousTimestamp) {
        // 1) Prefer already-parsed event time
        if (tick.getTimestamp() > 0) {
            return tick.getTimestamp();
        }

        // 2) Try numeric Time field (ms or sec)
        long timeField = tick.getTime();
        if (timeField > 0) {
            long candidate = (timeField < 1000_000_000_000L) ? timeField * 1000L : timeField; // seconds → ms
            return candidate;
        }

        String tickDt = tick.getTickDt();
        if (tickDt == null || tickDt.isBlank()) {
            long fallback = record.timestamp() > 0 ? record.timestamp() : previousTimestamp;
            return Math.max(fallback, 0L);
        }

        try {
            // Handle the "/Date(1746430676000)/" format
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

            // Handle standard local time string in IST with optional millis
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
