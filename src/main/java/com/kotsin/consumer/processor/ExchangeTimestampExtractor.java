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
 * - MCX (exchange="M"): Trading hours 09:00 IST - 23:30 IST
 * 
 * Aligns candle windows based on exchange-specific trading hours:
 * - NSE: Windows start at 09:15 (e.g., 09:15-09:17 for 2m candles)
 * - MCX: Windows start at 09:00 (e.g., 09:00-09:02 for 2m candles)
 * 
 * Records outside trading hours are discarded by returning -1 as timestamp.
 */
public class ExchangeTimestampExtractor implements TimestampExtractor {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(ExchangeTimestampExtractor.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ZoneId INDIA_ZONE = ZoneId.of("Asia/Kolkata");
    
    // Trading hours
    private static final int NSE_OPEN_HOUR = 9;
    private static final int NSE_OPEN_MINUTE = 15;
    private static final int NSE_CLOSE_HOUR = 15;
    private static final int NSE_CLOSE_MINUTE = 30;
    
    private static final int MCX_OPEN_HOUR = 9;
    private static final int MCX_OPEN_MINUTE = 0;
    private static final int MCX_CLOSE_HOUR = 23;
    private static final int MCX_CLOSE_MINUTE = 30;
    
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
            return -1; // Invalid timestamp
        }

        // Convert timestamp to India time zone (IST)
        ZonedDateTime recordTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(rawTs), INDIA_ZONE);
        LOGGER.debug("Processing record with timestamp: {} IST", recordTime.format(TIME_FORMAT));
        
        // Check if within trading hours based on exchange
        if ("N".equals(candle.getExchange())) {
            // NSE trading hours: 9:15 AM - 3:30 PM
            if (!isWithinNseTradingHours(recordTime)) {
                LOGGER.info("Discarding NSE record outside trading hours: {} IST", 
                        recordTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                return -1; // Returning -1 causes Kafka Streams to drop this record
            }
            return calculateNseWindow(recordTime);
        } else {
            // MCX trading hours: 9:00 AM - 11:30 PM
            if (!isWithinMcxTradingHours(recordTime)) {
                LOGGER.info("Discarding MCX record outside trading hours: {} IST", 
                        recordTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                return -1; // Returning -1 causes Kafka Streams to drop this record
            }
            return calculateMcxWindow(recordTime);
        }
    }
    
    /**
     * Checks if the given time is within NSE trading hours (9:15 AM - 3:30 PM)
     */
    private boolean isWithinNseTradingHours(ZonedDateTime time) {
        int hour = time.getHour();
        int minute = time.getMinute();
        
        // Before open time
        if (hour < NSE_OPEN_HOUR || (hour == NSE_OPEN_HOUR && minute < NSE_OPEN_MINUTE)) {
            return false;
        }
        
        // After close time
        if (hour > NSE_CLOSE_HOUR || (hour == NSE_CLOSE_HOUR && minute >= NSE_CLOSE_MINUTE)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Checks if the given time is within MCX trading hours (9:00 AM - 11:30 PM)
     */
    private boolean isWithinMcxTradingHours(ZonedDateTime time) {
        int hour = time.getHour();
        int minute = time.getMinute();
        
        // Before open time
        if (hour < MCX_OPEN_HOUR || (hour == MCX_OPEN_HOUR && minute < MCX_OPEN_MINUTE)) {
            return false;
        }
        
        // After close time
        if (hour > MCX_CLOSE_HOUR || (hour == MCX_CLOSE_HOUR && minute >= MCX_CLOSE_MINUTE)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Calculates the appropriate window timestamp for NSE data (trading starts at 09:15)
     */
    private long calculateNseWindow(ZonedDateTime recordTime) {
        // Get today's trading open (09:15)
        ZonedDateTime tradingOpen = recordTime.withHour(NSE_OPEN_HOUR).withMinute(NSE_OPEN_MINUTE)
                .withSecond(0).withNano(0);
        
        // If record is before today's trading open, we need to use previous trading day
        if (recordTime.isBefore(tradingOpen)) {
            tradingOpen = tradingOpen.minusDays(1);
            LOGGER.debug("Record time {} is before today's trading open, using previous day's 09:15", 
                    recordTime.format(TIME_FORMAT));
        }
        
        // Calculate minutes elapsed since 09:15
        int minutesElapsed = ((recordTime.getHour() - NSE_OPEN_HOUR) * 60 + 
                (recordTime.getMinute() - NSE_OPEN_MINUTE));
        
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
        ZonedDateTime tradingOpen = recordTime.withHour(MCX_OPEN_HOUR).withMinute(MCX_OPEN_MINUTE)
                .withSecond(0).withNano(0);
        
        // If record is before today's trading open, we need to use previous trading day
        if (recordTime.isBefore(tradingOpen)) {
            tradingOpen = tradingOpen.minusDays(1);
            LOGGER.debug("Record time {} is before today's trading open, using previous day's 09:00", 
                    recordTime.format(TIME_FORMAT));
        }
        
        // Calculate minutes elapsed since 09:00
        int minutesElapsed = ((recordTime.getHour() - MCX_OPEN_HOUR) * 60 + recordTime.getMinute());
        
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
