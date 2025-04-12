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
        
        // Calculate minutes elapsed based on exchange type
        int minutesElapsed;
        ZonedDateTime tradingOpen;
        
        if ("N".equals(candle.getExchange())) {
            // NSE (National Stock Exchange) - Trading starts at 09:15
            // Calculate minutes elapsed since 09:15
            minutesElapsed = ((recordTime.getHour() - 9) * 60 + (recordTime.getMinute() - 15));
            tradingOpen = recordTime.withHour(9).withMinute(15).withSecond(0).withNano(0);
        } else {
            // MCX and others - Trading starts at 09:00
            // Calculate minutes elapsed since 09:00
            minutesElapsed = ((recordTime.getHour() - 9) * 60 + recordTime.getMinute());
            tradingOpen = recordTime.withHour(9).withMinute(0).withSecond(0).withNano(0);
        }
        
        // Handle times before market open (use previous day)
        if (minutesElapsed < 0) {
            // For next day early morning records, use previous day's trading session
            tradingOpen = tradingOpen.minusDays(1);
            
            if ("N".equals(candle.getExchange())) {
                // Recalculate for NSE, adding 24 hours worth of minutes
                minutesElapsed = ((recordTime.getHour() + 24 - 9) * 60 + (recordTime.getMinute() - 15));
            } else {
                // Recalculate for MCX, adding 24 hours worth of minutes
                minutesElapsed = ((recordTime.getHour() + 24 - 9) * 60 + recordTime.getMinute());
            }
            
            LOGGER.debug("Record time {} is before today's trading open, using previous day", 
                    recordTime.format(TIME_FORMAT));
        }
        
        // Determine which window this belongs to
        int windowIndex = (int) (minutesElapsed / windowSizeMinutes);
        
        // Calculate window start time directly from trading open time
        ZonedDateTime windowStart = tradingOpen.plusMinutes(windowIndex * windowSizeMinutes);

        if (LOGGER.isDebugEnabled()) {
            ZonedDateTime windowEnd = windowStart.plusMinutes(windowSizeMinutes);
            LOGGER.debug("Exchange: {}, Record time: {}, Window: {}-{}, Size: {}m, Minutes elapsed: {}", 
                    candle.getExchange(),
                    recordTime.format(TIME_FORMAT),
                    windowStart.format(TIME_FORMAT), 
                    windowEnd.format(TIME_FORMAT),
                    windowSizeMinutes,
                    minutesElapsed);
        }

        return windowStart.toInstant().toEpochMilli();
    }
}
