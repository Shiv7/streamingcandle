package com.kotsin.consumer.enrichment.enricher;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.enrichment.config.CommodityConfig;
import com.kotsin.consumer.enrichment.model.TimeContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;

/**
 * TimeContextEnricher - Adds time-of-day context to adjust signal confidence
 *
 * Different times of day have different market characteristics:
 * - Opening hour: High volume but whipsaw-prone, gap fills
 * - Morning trend: Prime trading time, cleaner trends
 * - Lunch chop: Low volume, range-bound, avoid trading
 * - Afternoon: Repositioning, can have reversals
 * - Power hour: High volume again, expiry effects
 *
 * MCX (commodities) has different session dynamics:
 * - Morning: Follows domestic equity markets
 * - Evening open: International markets wake up
 * - Evening prime: Maximum overlap with COMEX, best liquidity
 * - Evening late: US session, can have big moves
 *
 * USAGE:
 * - Reduce signal confidence during lunch chop
 * - Boost confidence during prime sessions
 * - Adjust for day-of-week effects (Monday gaps, Friday expiry)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimeContextEnricher {

    private final CommodityConfig commodityConfig;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Enrich with time context
     *
     * @param family FamilyCandle to analyze
     * @return TimeContext with session info and modifiers
     */
    public TimeContext enrich(FamilyCandle family) {
        // Determine timestamp
        long timestamp = family != null && family.getWindowEndMillis() > 0 ?
                family.getWindowEndMillis() : System.currentTimeMillis();

        ZonedDateTime zdt = Instant.ofEpochMilli(timestamp).atZone(IST);
        LocalTime time = zdt.toLocalTime();
        LocalDate date = zdt.toLocalDate();
        DayOfWeek dayOfWeek = date.getDayOfWeek();

        // Determine if this is a commodity
        boolean isCommodity = family != null && isCommodityFamily(family);

        // Get session info
        TimeContext.Session session;
        double sessionQuality;
        String sessionDescription;

        if (isCommodity) {
            session = getMCXSession(time);
            sessionQuality = getMCXSessionQuality(time);
            sessionDescription = getMCXSessionDescription(session);
        } else {
            session = getNSESession(time);
            sessionQuality = getNSESessionQuality(session);
            sessionDescription = getNSESessionDescription(session);
        }

        // Day of week effects
        double dayOfWeekModifier = getDayOfWeekModifier(dayOfWeek, isCommodity);
        String dayOfWeekNote = getDayOfWeekNote(dayOfWeek);

        // Special time flags
        boolean isMarketOpen = isMarketOpen(time, isCommodity);
        boolean isFirstHour = isFirstHour(time, isCommodity);
        boolean isLastHour = isLastHour(time, isCommodity);
        boolean isLunchTime = isLunchTime(time, isCommodity);
        boolean isPrimeTime = isPrimeTime(time, isCommodity);

        // Calculate overall confidence modifier
        double confidenceModifier = calculateConfidenceModifier(
                sessionQuality, dayOfWeekModifier, isFirstHour, isLastHour, isLunchTime);

        // Determine if good time to trade
        boolean goodTimeToTrade = sessionQuality >= 0.6 && isMarketOpen && !isLunchTime;

        return TimeContext.builder()
                .timestamp(timestamp)
                .localTime(time)
                .localDate(date)
                .dayOfWeek(dayOfWeek)
                .isCommodity(isCommodity)
                .session(session)
                .sessionQuality(sessionQuality)
                .sessionDescription(sessionDescription)
                .dayOfWeekModifier(dayOfWeekModifier)
                .dayOfWeekNote(dayOfWeekNote)
                .isMarketOpen(isMarketOpen)
                .isFirstHour(isFirstHour)
                .isLastHour(isLastHour)
                .isLunchTime(isLunchTime)
                .isPrimeTime(isPrimeTime)
                .confidenceModifier(confidenceModifier)
                .goodTimeToTrade(goodTimeToTrade)
                .build();
    }

    // ======================== NSE SESSIONS ========================

    private TimeContext.Session getNSESession(LocalTime time) {
        if (time.isBefore(LocalTime.of(9, 15))) {
            return TimeContext.Session.PRE_MARKET;
        }
        if (time.isBefore(LocalTime.of(9, 20))) {
            return TimeContext.Session.OPENING_AUCTION;
        }
        if (time.isBefore(LocalTime.of(10, 0))) {
            return TimeContext.Session.OPENING_RANGE;
        }
        if (time.isBefore(LocalTime.of(12, 0))) {
            return TimeContext.Session.MORNING_TREND;
        }
        if (time.isBefore(LocalTime.of(13, 30))) {
            return TimeContext.Session.LUNCH_CHOP;
        }
        if (time.isBefore(LocalTime.of(14, 30))) {
            return TimeContext.Session.AFTERNOON;
        }
        if (time.isBefore(LocalTime.of(15, 30))) {
            return TimeContext.Session.POWER_HOUR;
        }
        return TimeContext.Session.POST_MARKET;
    }

    private double getNSESessionQuality(TimeContext.Session session) {
        return switch (session) {
            case PRE_MARKET -> 0.0;
            case OPENING_AUCTION -> 0.3;
            case OPENING_RANGE -> 0.6;
            case MORNING_TREND -> 1.0;  // PRIME
            case LUNCH_CHOP -> 0.2;     // AVOID
            case AFTERNOON -> 0.8;
            case POWER_HOUR -> 0.7;     // Volatile but tradeable
            case POST_MARKET -> 0.0;
            default -> 0.5;
        };
    }

    private String getNSESessionDescription(TimeContext.Session session) {
        return switch (session) {
            case PRE_MARKET -> "Pre-market: No trading";
            case OPENING_AUCTION -> "Opening auction: Gap settlement, wait for range";
            case OPENING_RANGE -> "Opening range: Range forming, watch for direction";
            case MORNING_TREND -> "Morning trend: PRIME time, cleanest trends";
            case LUNCH_CHOP -> "Lunch chop: Low volume, avoid new positions";
            case AFTERNOON -> "Afternoon: Repositioning, possible reversals";
            case POWER_HOUR -> "Power hour: High volume, expiry effects";
            case POST_MARKET -> "Post-market: No trading";
            default -> "Unknown session";
        };
    }

    // ======================== MCX SESSIONS ========================

    private TimeContext.Session getMCXSession(LocalTime time) {
        if (time.isBefore(LocalTime.of(9, 0))) {
            return TimeContext.Session.PRE_MARKET;
        }
        if (time.isBefore(LocalTime.of(17, 0))) {
            return TimeContext.Session.MCX_MORNING;
        }
        if (time.isBefore(LocalTime.of(18, 0))) {
            return TimeContext.Session.MCX_EVENING_OPEN;
        }
        if (time.isBefore(LocalTime.of(21, 0))) {
            return TimeContext.Session.MCX_EVENING_PRIME;
        }
        if (time.isBefore(LocalTime.of(23, 30))) {
            return TimeContext.Session.MCX_EVENING_LATE;
        }
        return TimeContext.Session.POST_MARKET;
    }

    private double getMCXSessionQuality(LocalTime time) {
        CommodityConfig.MCXSession session = commodityConfig.getCurrentSession(time);
        return session != null ? session.getQuality() : 0.0;
    }

    private String getMCXSessionDescription(TimeContext.Session session) {
        return switch (session) {
            case MCX_MORNING -> "MCX Morning: Tracks domestic equity markets";
            case MCX_EVENING_OPEN -> "MCX Evening Open: International markets opening";
            case MCX_EVENING_PRIME -> "MCX Evening Prime: BEST liquidity, COMEX overlap";
            case MCX_EVENING_LATE -> "MCX Evening Late: US market tracking";
            default -> "MCX session";
        };
    }

    // ======================== DAY OF WEEK ========================

    private double getDayOfWeekModifier(DayOfWeek day, boolean isCommodity) {
        return switch (day) {
            case MONDAY -> 0.9;    // Gap risk, catching up
            case TUESDAY -> 1.0;
            case WEDNESDAY -> 1.0;
            case THURSDAY -> isCommodity ? 1.0 : 1.1;  // NSE weekly expiry
            case FRIDAY -> isCommodity ? 1.1 : 0.9;     // MCX expiry, NSE weekend risk
            case SATURDAY, SUNDAY -> 0.0;
        };
    }

    private String getDayOfWeekNote(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "Monday: Weekend gap risk, market finding direction";
            case TUESDAY -> "Tuesday: Normal trading day";
            case WEDNESDAY -> "Wednesday: Normal trading day";
            case THURSDAY -> "Thursday: NSE weekly expiry (if applicable)";
            case FRIDAY -> "Friday: Weekend positioning, expiry effects";
            case SATURDAY, SUNDAY -> "Weekend: Markets closed";
        };
    }

    // ======================== TIME FLAGS ========================

    private boolean isMarketOpen(LocalTime time, boolean isCommodity) {
        if (isCommodity) {
            return time.isAfter(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(23, 30));
        }
        return time.isAfter(LocalTime.of(9, 15)) && time.isBefore(LocalTime.of(15, 30));
    }

    private boolean isFirstHour(LocalTime time, boolean isCommodity) {
        if (isCommodity) {
            return time.isAfter(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(10, 0));
        }
        return time.isAfter(LocalTime.of(9, 15)) && time.isBefore(LocalTime.of(10, 15));
    }

    private boolean isLastHour(LocalTime time, boolean isCommodity) {
        if (isCommodity) {
            return time.isAfter(LocalTime.of(22, 30)) && time.isBefore(LocalTime.of(23, 30));
        }
        return time.isAfter(LocalTime.of(14, 30)) && time.isBefore(LocalTime.of(15, 30));
    }

    private boolean isLunchTime(LocalTime time, boolean isCommodity) {
        if (isCommodity) {
            return false; // MCX doesn't have lunch break effect
        }
        return time.isAfter(LocalTime.of(12, 0)) && time.isBefore(LocalTime.of(13, 30));
    }

    private boolean isPrimeTime(LocalTime time, boolean isCommodity) {
        if (isCommodity) {
            return time.isAfter(LocalTime.of(18, 0)) && time.isBefore(LocalTime.of(21, 0));
        }
        return time.isAfter(LocalTime.of(10, 0)) && time.isBefore(LocalTime.of(12, 0));
    }

    // ======================== HELPERS ========================

    private boolean isCommodityFamily(FamilyCandle family) {
        if (family.isCommodity()) return true;
        if (family.getFuture() != null && family.getFuture().getExchange() != null) {
            return commodityConfig.isMCXExchange(family.getFuture().getExchange());
        }
        return false;
    }

    private double calculateConfidenceModifier(double sessionQuality, double dayModifier,
                                                boolean isFirstHour, boolean isLastHour, boolean isLunchTime) {
        double modifier = sessionQuality * dayModifier;

        // Reduce confidence in first hour (whipsaws)
        if (isFirstHour) modifier *= 0.8;

        // Reduce confidence in last hour (expiry effects)
        if (isLastHour) modifier *= 0.9;

        // Significantly reduce during lunch
        if (isLunchTime) modifier *= 0.5;

        return Math.max(0.1, Math.min(1.0, modifier));
    }
}
