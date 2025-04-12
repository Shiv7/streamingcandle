package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.Candlestick;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Custom TimestampExtractor for different exchanges with different trading hours.
 * 
 * Handles:
 * - NSE (exchange="N"): Trading hours 09:15 IST - 15:30 IST
 * - MCX (exchange="M"): Trading hours 09:00 IST - 23:00 IST
 * 
 * Aligns candle windows based on exchange-specific trading hours:
 * - NSE: Windows start at 09:15 (e.g., 09:15-09:17 for 2m candles)
 * - MCX: Windows start at 09:00 (e.g., 09:00-09:02 for 2m candles)
 */
public class ExchangeTimestampExtractor implements TimestampExtractor {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(ExchangeTimestampExtractor.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ZoneId INDIA_ZONE = ZoneId.of("Asia/Kolkata");
    
    private final int windowSizeMinutes;
    
    public ExchangeTimestampExtractor(int windowSizeMinutes) {
        this.windowSizeMinutes = windowSizeMinutes;
    }

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long previousTimestamp) {
        if (!(record.value() instanceof Candlestick)) {
            return record.timestamp();
        }

        Candlestick candle = (Candlestick) record.value();
        long rawTs = record.timestamp();
        if (rawTs <= 0) {
            return System.currentTimeMillis();
        }

        // Convert timestamp to India time zone (IST)
        ZonedDateTime recordTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(rawTs), INDIA_ZONE);
        LOGGER.debug("Processing record with timestamp: {} IST", recordTime.format(TIME_FORMAT));
        
        // Calculate appropriate window based on exchange type
        if ("N".equals(candle.getExchange())) {
            // NSE (National Stock Exchange) - Trading starts at 09:15
            return calculateNseWindow(recordTime);
        } else {
            // MCX and others - Trading starts at 09:00
            return calculateMcxWindow(recordTime);
        }
    }
    
    /**
     * Calculates the appropriate window timestamp for NSE data (trading starts at 09:15)
     */
    private long calculateNseWindow(ZonedDateTime recordTime) {
        // Get today's trading open (09:15)
        ZonedDateTime tradingOpen = recordTime.withHour(9).withMinute(15).withSecond(0).withNano(0);
        
        // If record is before today's trading open, we need to use previous trading day
        if (recordTime.isBefore(tradingOpen)) {
            tradingOpen = tradingOpen.minusDays(1);
            LOGGER.debug("Record time {} is before today's trading open, using previous day's 09:15", 
                    recordTime.format(TIME_FORMAT));
        }
        
        // Ensure we never create windows before trading open
        // This prevents windows like 09:11-09:14 which should not exist for NSE
        if (recordTime.isBefore(tradingOpen)) {
            // If the record is before trading opens, assign it to the first window of the day
            LOGGER.warn("Record time {} is before NSE trading hours (09:15). Assigning to first window.", 
                    recordTime.format(TIME_FORMAT));
            return tradingOpen.toInstant().toEpochMilli();
        }
        
        // Calculate minutes elapsed since 09:15
        int minutesElapsed = ((recordTime.getHour() - 9) * 60 + (recordTime.getMinute() - 15));
        
        // Calculate window index and start time
        int windowIndex = Math.max(0, minutesElapsed / windowSizeMinutes);
        ZonedDateTime windowStart = tradingOpen.plusMinutes(windowIndex * windowSizeMinutes);
        ZonedDateTime windowEnd = windowStart.plusMinutes(windowSizeMinutes);
        
        LOGGER.debug("NSE record: {}, Trading open: {}, Window: {}-{}, Size: {}m, Minutes elapsed: {}", 
                recordTime.format(TIME_FORMAT),
                tradingOpen.format(TIME_FORMAT),
                windowStart.format(TIME_FORMAT), 
                windowEnd.format(TIME_FORMAT),
                windowSizeMinutes,
                minutesElapsed);
        
        return windowStart.toInstant().toEpochMilli();
    }
    
    /**
     * Calculates the appropriate window timestamp for MCX data (trading starts at 09:00)
     */
    private long calculateMcxWindow(ZonedDateTime recordTime) {
        // Get today's trading open (09:00)
        ZonedDateTime tradingOpen = recordTime.withHour(9).withMinute(0).withSecond(0).withNano(0);
        
        // If record is before today's trading open, we need to use previous trading day
        if (recordTime.isBefore(tradingOpen)) {
            tradingOpen = tradingOpen.minusDays(1);
            LOGGER.debug("Record time {} is before today's trading open, using previous day's 09:00", 
                    recordTime.format(TIME_FORMAT));
        }
        
        // Calculate minutes elapsed since 09:00
        int minutesElapsed = ((recordTime.getHour() - 9) * 60 + recordTime.getMinute());
        
        // Calculate window index and start time
        int windowIndex = Math.max(0, minutesElapsed / windowSizeMinutes);
        ZonedDateTime windowStart = tradingOpen.plusMinutes(windowIndex * windowSizeMinutes);
        ZonedDateTime windowEnd = windowStart.plusMinutes(windowSizeMinutes);
        
        LOGGER.debug("MCX record: {}, Trading open: {}, Window: {}-{}, Size: {}m, Minutes elapsed: {}", 
                recordTime.format(TIME_FORMAT),
                tradingOpen.format(TIME_FORMAT),
                windowStart.format(TIME_FORMAT), 
                windowEnd.format(TIME_FORMAT),
                windowSizeMinutes,
                minutesElapsed);
        
        return windowStart.toInstant().toEpochMilli();
    }
}
