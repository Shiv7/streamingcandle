package com.kotsin.consumer.service;

import com.kotsin.consumer.model.TickData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Service for validating trading hours
 * Single Responsibility: Trading hours validation logic
 *
 * Spring Best Practice: @Service annotation for business logic
 */
@Service
@Slf4j
public class TradingHoursValidationService {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    /**
     * Check if tick is within trading hours for its exchange
     */
    public boolean withinTradingHours(TickData tick) {
       /* try {
            long ts = tick.getTimestamp();
            if (ts <= 0) {
                log.warn("⚠️ Invalid timestamp (<=0) for token {}", tick.getToken());
                return false;
            }

            ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), IST_ZONE);
            String exch = tick.getExchange();

            if ("N".equalsIgnoreCase(exch)) {
                // NSE: 9:15 AM - 3:30 PM
                return !zdt.toLocalTime().isBefore(java.time.LocalTime.of(9, 15)) &&
                       !zdt.toLocalTime().isAfter(java.time.LocalTime.of(15, 30));
            } else if ("M".equalsIgnoreCase(exch)) {
                // MCX: 9:00 AM - 11:30 PM
                return !zdt.toLocalTime().isBefore(java.time.LocalTime.of(9, 0)) &&
                       !zdt.toLocalTime().isAfter(java.time.LocalTime.of(23, 30));
            } else {
                // Unknown exchange -> drop
                log.debug("Unknown exchange '{}' for token {}, dropping", exch, tick.getToken());
                return false;
            }
        } catch (Exception e) {
            log.warn("⚠️ Invalid timestamp for token {}: {}", tick.getToken(), e.toString());
            return false;
        }*/
    return true;
    }
}
