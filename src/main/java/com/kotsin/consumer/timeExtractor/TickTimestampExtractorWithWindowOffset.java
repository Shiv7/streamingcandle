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

        // FIX: Use exchange timestamp (TickDt) as primary source of truth for OHLC accuracy
        // TickDt contains when the trade actually happened on the exchange
        // Kafka timestamp is ingestion time which can be delayed/out-of-order due to network latency
        // parseTimestamp() is called during deserialization, so tick.getTimestamp() has the parsed TickDt
        long baseTs = tick.getTimestamp();

        // Fallback to Kafka timestamp if TickDt was not parsed (invalid or missing)
        if (baseTs <= 0) {
            baseTs = record.timestamp() > 0 ? record.timestamp() : previousTimestamp;
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("TickDt missing/invalid for token={}, using Kafka timestamp={}",
                    tick.getToken(), baseTs);
            }
        }

        // Final fallback to previous timestamp
        if (baseTs <= 0) {
            LOGGER.warn("No valid timestamp for tick (token={}). Using previousTimestamp.", tick.getToken());
            baseTs = Math.max(previousTimestamp, System.currentTimeMillis());
        }

        // Apply market-specific offset to align N-minute boundaries
        String exchange = tick.getExchange();
        int offsetMinutes = MarketTimeAligner.getWindowOffsetMinutes(exchange, windowSizeMinutes);
        long offsetMs = offsetMinutes * 60_000L;

        long alignedTs = baseTs + offsetMs;

        return alignedTs;
    }
}

