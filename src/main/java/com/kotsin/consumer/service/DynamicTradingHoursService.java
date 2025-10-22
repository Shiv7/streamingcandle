package com.kotsin.consumer.service;

import com.kotsin.consumer.model.TickData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic trading hours service with exchange-specific configuration
 * 
 * CRITICAL FIX: Replaces hardcoded trading hours with dynamic configuration
 * Supports multiple exchanges (NSE, MCX, etc.) with different trading hours
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DynamicTradingHoursService {

    @Value("${trading.hours.nse.start:09:15}")
    private String nseStartTime;
    
    @Value("${trading.hours.nse.end:15:30}")
    private String nseEndTime;
    
    @Value("${trading.hours.mcx.start:09:00}")
    private String mcxStartTime;
    
    @Value("${trading.hours.mcx.end:23:30}")
    private String mcxEndTime;
    
    @Value("${trading.hours.buffer.minutes:15}")
    private int bufferMinutes;

    private final Map<String, TradingHours> exchangeHours = new ConcurrentHashMap<>();
    private final ZoneId istZone = ZoneId.of("Asia/Kolkata");

    /**
     * Check if tick is within trading hours for its exchange
     */
    public boolean withinTradingHours(TickData tick) {
        if (tick == null || tick.getTimestamp() == 0L) {
            return false;
        }

        try {
            ZonedDateTime tickTime = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(tick.getTimestamp()),
                istZone
            );

            // Check weekend
            if (isWeekend(tickTime)) {
                log.debug("⏰ Weekend detected for tick at {}", tickTime);
                return false;
            }

            // Get exchange-specific hours
            String exchange = getExchangeFromTick(tick);
            TradingHours hours = getTradingHours(exchange);
            
            if (hours == null) {
                log.warn("⚠️ No trading hours configured for exchange: {}", exchange);
                return true; // Default to allow if not configured
            }

            // Check if within trading hours (with buffer)
            boolean withinHours = hours.isWithinTradingHours(tickTime, bufferMinutes);
            
            if (!withinHours) {
                log.debug("⏰ Tick outside trading hours: {} (exchange: {}, hours: {})", 
                    tickTime.toLocalTime(), exchange, hours);
            }
            
            return withinHours;

        } catch (Exception e) {
            log.error("❌ Error checking trading hours for tick: {}", tick, e);
            return true; // Default to allow on error
        }
    }

    /**
     * Get trading hours for a specific exchange
     */
    public TradingHours getTradingHours(String exchange) {
        return exchangeHours.computeIfAbsent(exchange, this::loadTradingHours);
    }

    /**
     * Load trading hours for an exchange
     */
    private TradingHours loadTradingHours(String exchange) {
        switch (exchange.toUpperCase()) {
            case "NSE":
                return new TradingHours(
                    LocalTime.parse(nseStartTime),
                    LocalTime.parse(nseEndTime),
                    "NSE"
                );
            case "MCX":
                return new TradingHours(
                    LocalTime.parse(mcxStartTime),
                    LocalTime.parse(mcxEndTime),
                    "MCX"
                );
            default:
                log.warn("⚠️ Unknown exchange: {}, using default NSE hours", exchange);
                return new TradingHours(
                    LocalTime.parse(nseStartTime),
                    LocalTime.parse(nseEndTime),
                    "DEFAULT"
                );
        }
    }

    /**
     * Extract exchange from tick data
     */
    private String getExchangeFromTick(TickData tick) {
        // Try to extract from exchange field
        if (tick.getExchange() != null && !tick.getExchange().isEmpty()) {
            return tick.getExchange();
        }
        
        // Fallback: determine from scripCode patterns
        String scripCode = tick.getScripCode();
        if (scripCode != null) {
            if (scripCode.startsWith("99992")) {
                return "NSE"; // Index
            } else if (scripCode.length() == 8 && scripCode.matches("\\d+")) {
                return "NSE"; // NSE equity/derivative
            } else {
                return "MCX"; // Default to MCX for commodities
            }
        }
        
        return "NSE"; // Default
    }

    /**
     * Check if date is weekend
     */
    private boolean isWeekend(ZonedDateTime dateTime) {
        int dayOfWeek = dateTime.getDayOfWeek().getValue();
        return dayOfWeek == 6 || dayOfWeek == 7; // Saturday or Sunday
    }

    /**
     * Get trading hours statistics
     */
    public String getTradingHoursStats() {
        return String.format("Configured exchanges: %d, Buffer: %d minutes", 
            exchangeHours.size(), bufferMinutes);
    }

    /**
     * Trading hours configuration for an exchange
     */
    public static class TradingHours {
        private final LocalTime startTime;
        private final LocalTime endTime;
        private final String exchange;

        public TradingHours(LocalTime startTime, LocalTime endTime, String exchange) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.exchange = exchange;
        }

        public boolean isWithinTradingHours(ZonedDateTime dateTime, int bufferMinutes) {
            LocalTime time = dateTime.toLocalTime();
            LocalTime startWithBuffer = startTime.minusMinutes(bufferMinutes);
            LocalTime endWithBuffer = endTime.plusMinutes(bufferMinutes);
            
            return !time.isBefore(startWithBuffer) && !time.isAfter(endWithBuffer);
        }

        public LocalTime getStartTime() { return startTime; }
        public LocalTime getEndTime() { return endTime; }
        public String getExchange() { return exchange; }

        @Override
        public String toString() {
            return String.format("%s: %s-%s", exchange, startTime, endTime);
        }
    }
}
