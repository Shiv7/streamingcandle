package com.kotsin.consumer.session.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * SessionStructure - Tracks intraday session structure.
 *
 * Contains:
 * - Opening Range (first 15/30/60 minutes)
 * - Session High/Low
 * - VWAP and deviation bands
 * - Time-based levels (Pre-market, Opening, Mid-day, Closing)
 * - Previous day reference levels
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionStructure {

    private String symbol;
    private LocalDate sessionDate;
    private Instant lastUpdate;

    // ==================== OPENING RANGE ====================
    private double openingRangeHigh15;    // First 15 min high
    private double openingRangeLow15;     // First 15 min low
    private double openingRangeHigh30;    // First 30 min high
    private double openingRangeLow30;     // First 30 min low
    private double openingRangeHigh60;    // First 60 min high
    private double openingRangeLow60;     // First 60 min low
    private boolean openingRangeComplete; // First hour complete

    // ==================== SESSION LEVELS ====================
    private double sessionOpen;
    private double sessionHigh;
    private double sessionLow;
    private double sessionClose;          // Current/Last close
    private long sessionVolume;
    private double sessionValue;

    // ==================== VWAP ====================
    private double vwap;
    private double vwapUpperBand1;        // VWAP + 1 StdDev
    private double vwapUpperBand2;        // VWAP + 2 StdDev
    private double vwapLowerBand1;        // VWAP - 1 StdDev
    private double vwapLowerBand2;        // VWAP - 2 StdDev
    private double vwapSlope;             // VWAP trend

    // ==================== PREVIOUS DAY ====================
    private double prevDayHigh;
    private double prevDayLow;
    private double prevDayClose;
    private double prevDayVwap;

    // ==================== GAP ANALYSIS ====================
    private double gapSize;               // Today's open - Prev close
    private double gapPercent;
    private GapType gapType;
    private boolean gapFilled;

    // ==================== TIME SEGMENTS ====================
    private SessionSegment currentSegment;
    private double preMarketHigh;
    private double preMarketLow;

    // ==================== DERIVED LEVELS ====================
    private double midpoint;              // (SessionHigh + SessionLow) / 2
    private double range;                 // SessionHigh - SessionLow
    private double rangePercent;          // Range as % of price
    private boolean isInsideDay;          // Today's range inside yesterday's
    private boolean isOutsideDay;         // Today's range outside yesterday's

    // ==================== PRICE POSITION ====================
    private PricePosition priceVsVwap;
    private PricePosition priceVsOpeningRange;
    private PricePosition priceVsPrevDay;

    // ==================== ENUMS ====================

    public enum GapType {
        GAP_UP,         // Open > Prev High
        GAP_DOWN,       // Open < Prev Low
        NO_GAP          // Open within prev range
    }

    public enum SessionSegment {
        PRE_MARKET,     // Before 9:15
        OPENING,        // 9:15 - 10:15
        MORNING,        // 10:15 - 12:00
        MIDDAY,         // 12:00 - 14:00
        AFTERNOON,      // 14:00 - 15:00
        CLOSING,        // 15:00 - 15:30
        POST_MARKET     // After 15:30
    }

    public enum PricePosition {
        ABOVE,
        AT,
        BELOW
    }

    // ==================== HELPER METHODS ====================

    public boolean isAboveVwap() {
        return sessionClose > vwap;
    }

    public boolean isBelowVwap() {
        return sessionClose < vwap;
    }

    public boolean isAboveOpeningRange() {
        return openingRangeComplete && sessionClose > openingRangeHigh30;
    }

    public boolean isBelowOpeningRange() {
        return openingRangeComplete && sessionClose < openingRangeLow30;
    }

    public boolean isNearSessionHigh() {
        return range > 0 && (sessionHigh - sessionClose) / range < 0.1;
    }

    public boolean isNearSessionLow() {
        return range > 0 && (sessionClose - sessionLow) / range < 0.1;
    }

    public double getOpeningRangeSize() {
        return openingRangeHigh30 - openingRangeLow30;
    }

    public boolean isBreakoutAboveOR() {
        return openingRangeComplete && sessionHigh > openingRangeHigh30 * 1.001;
    }

    public boolean isBreakdownBelowOR() {
        return openingRangeComplete && sessionLow < openingRangeLow30 * 0.999;
    }

    public static SessionSegment getSegmentForTime(LocalTime time) {
        if (time.isBefore(LocalTime.of(9, 15))) return SessionSegment.PRE_MARKET;
        if (time.isBefore(LocalTime.of(10, 15))) return SessionSegment.OPENING;
        if (time.isBefore(LocalTime.of(12, 0))) return SessionSegment.MORNING;
        if (time.isBefore(LocalTime.of(14, 0))) return SessionSegment.MIDDAY;
        if (time.isBefore(LocalTime.of(15, 0))) return SessionSegment.AFTERNOON;
        if (time.isBefore(LocalTime.of(15, 30))) return SessionSegment.CLOSING;
        return SessionSegment.POST_MARKET;
    }
}
