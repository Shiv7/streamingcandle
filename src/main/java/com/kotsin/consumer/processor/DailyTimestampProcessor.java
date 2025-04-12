package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.Candlestick;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class DailyTimestampProcessor implements Processor<String, Candlestick, String, Candlestick> {

    private ProcessorContext<String, Candlestick> context;
    private static final Logger LOGGER = LoggerFactory.getLogger(DailyTimestampProcessor.class);

    @Override
    public void init(ProcessorContext<String, Candlestick> context) {
        this.context = context;
    }

    @Override
    public void process(Record<String, Candlestick> record) {
        if (record.value() == null) {
            LOGGER.warn("Received null value for key: {}", record.key());
            return;
        }
        long originalTs = record.timestamp();

        // Convert the record's timestamp to Asia/Kolkata time.
        ZonedDateTime recordTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(originalTs), ZoneId.of("Asia/Kolkata"));
        // Initially assume trading open is at 09:15 on the same day.
        ZonedDateTime tradingOpen = recordTime.withHour(9).withMinute(15).withSecond(0).withNano(0);
        boolean usedPreviousDay = false;
        // If record is before 09:15, assume it belongs to the previous trading day.
        if (recordTime.isBefore(tradingOpen)) {
            tradingOpen = tradingOpen.minusDays(1);
            usedPreviousDay = true;
        }
        long shift = tradingOpen.toInstant().toEpochMilli();
        long newTs = originalTs - shift;
        // Ensure new timestamp is non-negative.
        if (newTs < 0) {
            LOGGER.warn("Adjusted timestamp is negative; clamping to 0. Original timestamp: {}, shift: {}, computed newTs: {}",
                    originalTs, shift, newTs);
            newTs = 0;
        }
        LOGGER.debug("Record key {}: originalTs={}, recordTime={}, tradingOpen={} (usedPreviousDay={}), newTs={}",
                record.key(), originalTs, recordTime, tradingOpen, usedPreviousDay, newTs);
        // Forward the record with the new (adjusted) timestamp.
        context.forward(record.withTimestamp(newTs));
    }

    @Override
    public void close() {
        // No cleanup necessary.
    }
}
