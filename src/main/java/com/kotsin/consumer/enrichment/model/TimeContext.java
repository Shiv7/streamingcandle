package com.kotsin.consumer.enrichment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * TimeContext - Time-of-day and session awareness for signal filtering
 *
 * Different sessions have different characteristics:
 * - NSE: Opening whipsaws, morning trends, lunch chop, power hour
 * - MCX: Evening prime has best liquidity (COMEX overlap)
 *
 * Use this to adjust signal confidence based on when signals occur.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimeContext {

    // ======================== TIMESTAMP ========================

    /**
     * Original timestamp in millis
     */
    private long timestamp;

    /**
     * Local time in IST
     */
    private LocalTime localTime;

    /**
     * Local date in IST
     */
    private LocalDate localDate;

    /**
     * Day of week
     */
    private DayOfWeek dayOfWeek;

    // ======================== MARKET TYPE ========================

    /**
     * Is this a commodity (MCX) or equity (NSE/BSE)?
     */
    private boolean isCommodity;

    // ======================== SESSION INFO ========================

    /**
     * Current trading session
     */
    private Session session;

    /**
     * Session quality (0-1): how good is this session for trading?
     * 1.0 = prime time, 0.0 = market closed
     */
    private double sessionQuality;

    /**
     * Human-readable session description
     */
    private String sessionDescription;

    // ======================== DAY OF WEEK ========================

    /**
     * Day of week confidence modifier
     * Monday gap risk, Thursday expiry, Friday weekend positioning
     */
    private double dayOfWeekModifier;

    /**
     * Note about day-specific effects
     */
    private String dayOfWeekNote;

    // ======================== TIME FLAGS ========================

    /**
     * Is market currently open?
     */
    private boolean isMarketOpen;

    /**
     * Is this the first hour of trading? (higher whipsaw risk)
     */
    private boolean isFirstHour;

    /**
     * Is this the last hour of trading? (expiry effects, positioning)
     */
    private boolean isLastHour;

    /**
     * Is this lunch time? (NSE only - low volume, choppy)
     */
    private boolean isLunchTime;

    /**
     * Is this prime trading time?
     */
    private boolean isPrimeTime;

    // ======================== OVERALL ASSESSMENT ========================

    /**
     * Overall confidence modifier based on time context
     * Combines session quality, day effects, time flags
     */
    private double confidenceModifier;

    /**
     * Is this a good time to trade?
     * True if session quality >= 0.6, market open, not lunch
     */
    private boolean goodTimeToTrade;

    // ======================== ENUMS ========================

    public enum Session {
        // ===== NSE Sessions =====
        /**
         * Before 9:15 - No trading
         */
        PRE_MARKET,

        /**
         * 9:15-9:20 - Opening auction, gap settlement
         */
        OPENING_AUCTION,

        /**
         * 9:20-10:00 - Opening range forming
         */
        OPENING_RANGE,

        /**
         * 10:00-12:00 - PRIME TIME for NSE, cleanest trends
         */
        MORNING_TREND,

        /**
         * 12:00-13:30 - Low volume, choppy, AVOID
         */
        LUNCH_CHOP,

        /**
         * 13:30-14:30 - Repositioning, possible reversals
         */
        AFTERNOON,

        /**
         * 14:30-15:30 - High volume, expiry effects
         */
        POWER_HOUR,

        /**
         * After 15:30 - No trading
         */
        POST_MARKET,

        // ===== MCX Sessions =====
        /**
         * 9:00-17:00 - Morning session, tracks domestic equity
         */
        MCX_MORNING,

        /**
         * 17:00-18:00 - Evening open, international markets waking
         */
        MCX_EVENING_OPEN,

        /**
         * 18:00-21:00 - PRIME TIME for MCX, COMEX overlap, best liquidity
         */
        MCX_EVENING_PRIME,

        /**
         * 21:00-23:30 - US market tracking, can have big moves
         */
        MCX_EVENING_LATE
    }

    // ======================== HELPER METHODS ========================

    /**
     * Check if current session is prime time
     */
    public boolean isPrimeSession() {
        return session == Session.MORNING_TREND || session == Session.MCX_EVENING_PRIME;
    }

    /**
     * Check if we should avoid trading
     */
    public boolean shouldAvoidTrading() {
        return !isMarketOpen || isLunchTime || sessionQuality < 0.3;
    }

    /**
     * Get signal modifier for this time context
     * @return Multiplier for signal confidence (0.1 to 1.0)
     */
    public double getSignalModifier() {
        return confidenceModifier;
    }

    /**
     * Get reason for low confidence if applicable
     */
    public String getLowConfidenceReason() {
        if (!isMarketOpen) return "Market closed";
        if (isLunchTime) return "Lunch time - low volume";
        if (isFirstHour) return "First hour - whipsaw risk";
        if (sessionQuality < 0.5) return "Low quality session";
        return null;
    }

    /**
     * Get trading recommendation
     */
    public String getTradingRecommendation() {
        if (!isMarketOpen) return "WAIT: Market closed";
        if (shouldAvoidTrading()) return "AVOID: Poor session quality";
        if (isPrimeTime) return "PRIME: Best time for directional trades";
        if (isFirstHour) return "CAUTION: First hour volatility";
        if (isLastHour) return "CAUTION: Last hour expiry effects";
        return "OK: Normal trading conditions";
    }

    /**
     * Factory method for empty context
     */
    public static TimeContext empty() {
        return TimeContext.builder()
                .session(Session.PRE_MARKET)
                .sessionQuality(0)
                .confidenceModifier(0.1)
                .isMarketOpen(false)
                .goodTimeToTrade(false)
                .dayOfWeekModifier(1.0)
                .build();
    }
}
