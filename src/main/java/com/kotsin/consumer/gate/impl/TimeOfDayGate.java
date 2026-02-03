package com.kotsin.consumer.gate.impl;

import com.kotsin.consumer.gate.SignalGate;
import com.kotsin.consumer.gate.model.GateResult;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

/**
 * TimeOfDayGate - Validates trading time for signals.
 *
 * Exchange-aware:
 * - NSE: 9:15 AM - 3:30 PM IST
 * - MCX: 9:00 AM - 11:30 PM IST
 *
 * Avoids:
 * - First 15 minutes (opening volatility)
 * - Last 15 minutes (closing volatility)
 * - Lunch hour (low liquidity) - NSE only
 */
@Component
public class TimeOfDayGate implements SignalGate {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // NSE trading windows
    private static final LocalTime NSE_MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime NSE_MARKET_CLOSE = LocalTime.of(15, 30);
    private static final LocalTime NSE_MORNING_START = LocalTime.of(9, 30);   // After opening
    private static final LocalTime NSE_MORNING_END = LocalTime.of(11, 30);    // Before lunch
    private static final LocalTime NSE_AFTERNOON_START = LocalTime.of(13, 0); // After lunch
    private static final LocalTime NSE_AFTERNOON_END = LocalTime.of(15, 15);  // Before close

    // MCX trading windows (9:00 AM - 11:30 PM IST)
    private static final LocalTime MCX_MARKET_OPEN = LocalTime.of(9, 0);
    private static final LocalTime MCX_MARKET_CLOSE = LocalTime.of(23, 30);
    private static final LocalTime MCX_MORNING_START = LocalTime.of(9, 15);   // After opening
    private static final LocalTime MCX_EVENING_END = LocalTime.of(23, 15);    // Before close

    @Override
    public String getName() {
        return "TIME_OF_DAY";
    }

    @Override
    public double getWeight() {
        return 0.10;  // 10% weight
    }

    @Override
    public boolean isRequired() {
        return false;
    }

    @Override
    public GateResult evaluate(Map<String, Object> context) {
        LocalTime time;

        // Try to get time from context
        Object timeObj = context.get("signalTime");
        if (timeObj instanceof LocalTime) {
            time = (LocalTime) timeObj;
        } else if (timeObj instanceof ZonedDateTime) {
            time = ((ZonedDateTime) timeObj).withZoneSameInstant(IST).toLocalTime();
        } else {
            // Use current time
            time = ZonedDateTime.now(IST).toLocalTime();
        }

        // Determine exchange from context (default to NSE)
        String exchange = "N";
        Object exchangeObj = context.get("exchange");
        if (exchangeObj instanceof String) {
            exchange = (String) exchangeObj;
        }

        boolean isMCX = "M".equalsIgnoreCase(exchange);

        // Use exchange-appropriate market hours
        LocalTime marketOpen = isMCX ? MCX_MARKET_OPEN : NSE_MARKET_OPEN;
        LocalTime marketClose = isMCX ? MCX_MARKET_CLOSE : NSE_MARKET_CLOSE;
        LocalTime tradingStart = isMCX ? MCX_MORNING_START : NSE_MORNING_START;
        LocalTime tradingEnd = isMCX ? MCX_EVENING_END : NSE_AFTERNOON_END;

        // Check if outside market hours
        if (isMCX) {
            // MCX: 9:00 AM - 11:30 PM (may cross midnight in future, but not currently)
            if (time.isBefore(marketOpen) || time.isAfter(marketClose)) {
                return GateResult.fail(getName(), getWeight(), "Outside MCX market hours (9:00-23:30)");
            }
        } else {
            // NSE: 9:15 AM - 3:30 PM
            if (time.isBefore(marketOpen) || time.isAfter(marketClose)) {
                return GateResult.fail(getName(), getWeight(), "Outside NSE market hours (9:15-15:30)");
            }
        }

        // Check opening period (first 15 mins)
        if (time.isBefore(tradingStart)) {
            return GateResult.fail(getName(), getWeight(),
                "Opening period - high volatility, avoid new positions");
        }

        // Check closing period (last 15 mins)
        if (time.isAfter(tradingEnd)) {
            return GateResult.fail(getName(), getWeight(),
                "Closing period - high volatility, avoid new positions");
        }

        // Check lunch hour (NSE only - MCX doesn't have lunch break)
        if (!isMCX && time.isAfter(LocalTime.of(11, 45)) && time.isBefore(LocalTime.of(13, 15))) {
            GateResult result = GateResult.pass(getName(), 50, getWeight(),
                "Lunch hour - reduced liquidity, proceed with caution");
            result.getDetails().put("tradingWindow", "LUNCH");
            result.getDetails().put("exchange", exchange);
            return result;
        }

        // Prime trading windows
        double score;
        String reason;
        String window;

        if (isMCX) {
            // MCX prime windows: 9:15-11:30, 14:00-18:00, 20:00-23:00
            boolean isMorningWindow = time.isAfter(MCX_MORNING_START) && time.isBefore(LocalTime.of(11, 30));
            boolean isAfternoonWindow = time.isAfter(LocalTime.of(14, 0)) && time.isBefore(LocalTime.of(18, 0));
            boolean isEveningWindow = time.isAfter(LocalTime.of(20, 0)) && time.isBefore(LocalTime.of(23, 0));

            if (isMorningWindow || isAfternoonWindow || isEveningWindow) {
                score = 100;
                reason = "Prime MCX trading window";
                window = isMorningWindow ? "MCX_MORNING" : (isAfternoonWindow ? "MCX_AFTERNOON" : "MCX_EVENING");
            } else {
                score = 80;
                reason = "MCX transition period";
                window = "MCX_TRANSITION";
            }
        } else {
            // NSE prime windows
            boolean isMorningWindow = time.isAfter(NSE_MORNING_START) && time.isBefore(NSE_MORNING_END);
            boolean isAfternoonWindow = time.isAfter(NSE_AFTERNOON_START) && time.isBefore(NSE_AFTERNOON_END);

            if (isMorningWindow) {
                score = 100;
                reason = "Prime morning trading window";
                window = "MORNING_PRIME";
            } else if (isAfternoonWindow) {
                score = 90;
                reason = "Afternoon trading window";
                window = "AFTERNOON";
            } else {
                score = 70;
                reason = "Acceptable trading time";
                window = "TRANSITION";
            }
        }

        GateResult result = GateResult.pass(getName(), score, getWeight(), reason);
        result.getDetails().put("tradingWindow", window);
        result.getDetails().put("currentTime", time.toString());
        result.getDetails().put("exchange", exchange);
        return result;
    }
}
