package com.kotsin.consumer.service;

import com.kotsin.consumer.model.EnrichedCandlestick;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * GapFieldPopulator - Populates gap-related fields for overnight gap analysis
 * 
 * CRITICAL FIX: Gap fields (previousClose, overnightGap, isGapUp, isGapDown) 
 * were defined but NEVER populated. This service fixes that.
 * 
 * Detects first candle of day and calculates gap metrics.
 */
@Slf4j
@Service
public class GapFieldPopulator {
    
    private static final double GAP_THRESHOLD = 0.3;  // 0.3% gap threshold
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    
    // Market open times
    private static final int NSE_OPEN_HOUR = 9;
    private static final int NSE_OPEN_MINUTE = 15;
    private static final int MCX_OPEN_HOUR = 9;
    private static final int MCX_OPEN_MINUTE = 0;
    
    /**
     * Check if this timestamp represents first candle of day
     */
    public boolean isFirstCandleOfDay(long timestampMillis, String exchange) {
        LocalTime time = Instant.ofEpochMilli(timestampMillis)
                                .atZone(IST)
                                .toLocalTime();
        
        if (exchange != null && exchange.startsWith("M")) {  // MCX
            return time.getHour() == MCX_OPEN_HOUR && time.getMinute() == MCX_OPEN_MINUTE;
        } else {  // NSE/BSE
            return time.getHour() == NSE_OPEN_HOUR && time.getMinute() == NSE_OPEN_MINUTE;
        }
    }
    
    /**
     * Populate gap fields if this is first candle and we have previous close
     * 
     * @param candle The candle to populate
     * @param previousDayClose Previous day's closing price (null if not available)
     * @param scripCode Script code for logging
     */
    public void populateGapFields(EnrichedCandlestick candle, Double previousDayClose, String scripCode) {
        if (previousDayClose == null || previousDayClose <= 0) {
            // No previous close available
            return;
        }
        
        double open = candle.getOpen();
        if (open <= 0) {
            return;
        }
        
        // Calculate gap percentage
        double gapPercent = ((open - previousDayClose) / previousDayClose) * 100;
        
        // Populate fields
        candle.setPreviousClose(previousDayClose);
        candle.setOvernightGap(gapPercent);
        candle.setIsGapUp(gapPercent > GAP_THRESHOLD);
        candle.setIsGapDown(gapPercent < -GAP_THRESHOLD);
        
        // Log significant gaps
        if (Math.abs(gapPercent) > GAP_THRESHOLD) {
            String direction = gapPercent > 0 ? "UP" : "DOWN";
            log.info("📊 GAP {} for {}: {:.2f}% (open={:.2f}, prevClose={:.2f})",
                     direction, scripCode, gapPercent, open, previousDayClose);
        }
    }
}
