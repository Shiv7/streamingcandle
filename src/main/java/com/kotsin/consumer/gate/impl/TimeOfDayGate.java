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
 * Avoids:
 * - First 15 minutes (opening volatility)
 * - Last 15 minutes (closing volatility)
 * - Lunch hour (low liquidity)
 */
@Component
public class TimeOfDayGate implements SignalGate {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // Preferred trading windows
    private static final LocalTime MORNING_START = LocalTime.of(9, 30);   // After opening
    private static final LocalTime MORNING_END = LocalTime.of(11, 30);    // Before lunch
    private static final LocalTime AFTERNOON_START = LocalTime.of(13, 0); // After lunch
    private static final LocalTime AFTERNOON_END = LocalTime.of(15, 15);  // Before close

    // Avoid periods
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

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

        // Check if outside market hours
        if (time.isBefore(MARKET_OPEN) || time.isAfter(MARKET_CLOSE)) {
            return GateResult.fail(getName(), getWeight(), "Outside market hours");
        }

        // Check opening period (first 15 mins)
        if (time.isBefore(MORNING_START)) {
            return GateResult.fail(getName(), getWeight(),
                "Opening period - high volatility, avoid new positions");
        }

        // Check closing period (last 15 mins)
        if (time.isAfter(AFTERNOON_END)) {
            return GateResult.fail(getName(), getWeight(),
                "Closing period - high volatility, avoid new positions");
        }

        // Check lunch hour (12:00 - 13:00)
        if (time.isAfter(LocalTime.of(11, 45)) && time.isBefore(LocalTime.of(13, 15))) {
            GateResult result = GateResult.pass(getName(), 50, getWeight(),
                "Lunch hour - reduced liquidity, proceed with caution");
            result.getDetails().put("tradingWindow", "LUNCH");
            return result;
        }

        // Prime trading windows
        boolean isMorningWindow = time.isAfter(MORNING_START) && time.isBefore(MORNING_END);
        boolean isAfternoonWindow = time.isAfter(AFTERNOON_START) && time.isBefore(AFTERNOON_END);

        double score;
        String reason;
        String window;

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

        GateResult result = GateResult.pass(getName(), score, getWeight(), reason);
        result.getDetails().put("tradingWindow", window);
        result.getDetails().put("currentTime", time.toString());
        return result;
    }
}
