package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.util.MarketTimeAligner;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
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
    private static final DateTimeFormatter DT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata"));

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
        String tickDt = tick.getTickDt();

        if (tickDt == null || tickDt.isBlank()) {
            LOGGER.warn("Tick has null or empty timestamp, using Kafka record timestamp for token {}", tick.getToken());
            long fallback = record.timestamp() > 0 ? record.timestamp() : previousTimestamp;
            return Math.max(fallback, 0L);
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

                if (ts <= 0) {
                    LOGGER.error("Parsed non-positive timestamp {} for token {}. Using record timestamp fallback.", ts, tick.getToken());
                    long fallback = record.timestamp() > 0 ? record.timestamp() : previousTimestamp;
                    return Math.max(fallback, 0L);
                }

                return ts;
            }

            // Handle standard format
            ZonedDateTime zdt = ZonedDateTime.parse(tickDt, DT_FORMATTER);
            long parsed = zdt.toInstant().toEpochMilli();
            if (parsed <= 0) {
                LOGGER.error("Parsed non-positive timestamp {} for token {}. Using record timestamp fallback.", parsed, tick.getToken());
                long fallback = record.timestamp() > 0 ? record.timestamp() : previousTimestamp;
                return Math.max(fallback, 0L);
            }
            return parsed;

        } catch (DateTimeParseException | NumberFormatException e) {
            LOGGER.error("Could not parse timestamp '{}' for token {}. Using Kafka record timestamp.", tickDt, tick.getToken(), e);
            // CRITICAL: Use record.timestamp(), NEVER System.currentTimeMillis()
            long fallback = record.timestamp() > 0 ? record.timestamp() : previousTimestamp;
            return Math.max(fallback, 0L);
        }
    }
}
