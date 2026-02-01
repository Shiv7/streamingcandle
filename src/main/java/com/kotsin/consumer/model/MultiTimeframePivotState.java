package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * MultiTimeframePivotState - Contains pivot levels across multiple timeframes.
 *
 * Stores:
 * - Daily pivots (today and yesterday for confluence)
 * - Weekly pivots (this week and last week)
 * - Monthly pivots (this month and last month)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MultiTimeframePivotState implements Serializable {

    private static final long serialVersionUID = 1L;

    private String symbol;
    private String scripCode;
    private String exchange;
    private String exchangeType;

    // Daily Pivots
    private PivotLevels dailyPivot;       // Today's pivot (from yesterday's HLC)
    private PivotLevels prevDailyPivot;   // Yesterday's pivot (for confluence check)

    // Weekly Pivots
    private PivotLevels weeklyPivot;      // This week's pivot (from last week's HLC)
    private PivotLevels prevWeeklyPivot;  // Last week's pivot (for confluence check)

    // Monthly Pivots
    private PivotLevels monthlyPivot;     // This month's pivot (from last month's HLC)
    private PivotLevels prevMonthlyPivot; // Last month's pivot (for confluence check)

    private Instant lastUpdated;

    /**
     * Check if daily CPR is thin (high breakout probability).
     */
    public boolean isDailyCprThin() {
        return dailyPivot != null && dailyPivot.isThinCpr();
    }

    /**
     * Check if daily CPR is ultra-thin.
     */
    public boolean isDailyCprUltraThin() {
        return dailyPivot != null && dailyPivot.isUltraThinCpr();
    }

    /**
     * Get daily CPR width percent.
     */
    public double getDailyCprWidthPercent() {
        return dailyPivot != null ? dailyPivot.getCprWidthPercent() : 0;
    }

    /**
     * Check if today's pivot is near yesterday's pivot (virgin CPR concept).
     */
    public boolean isDailyPivotConfluent(double thresholdPercent) {
        if (dailyPivot == null || prevDailyPivot == null) return false;

        double threshold = dailyPivot.getPivot() * thresholdPercent / 100;
        return Math.abs(dailyPivot.getPivot() - prevDailyPivot.getPivot()) <= threshold;
    }

    /**
     * Check if price is at a multi-timeframe confluence zone.
     */
    public int getConfluenceCount(double price, double thresholdPercent) {
        int count = 0;

        if (dailyPivot != null && dailyPivot.isNearAnyLevel(price, thresholdPercent)) {
            count++;
        }
        if (prevDailyPivot != null && prevDailyPivot.isNearAnyLevel(price, thresholdPercent)) {
            count++;
        }
        if (weeklyPivot != null && weeklyPivot.isNearAnyLevel(price, thresholdPercent)) {
            count++;
        }
        if (prevWeeklyPivot != null && prevWeeklyPivot.isNearAnyLevel(price, thresholdPercent)) {
            count++;
        }
        if (monthlyPivot != null && monthlyPivot.isNearAnyLevel(price, thresholdPercent)) {
            count++;
        }
        if (prevMonthlyPivot != null && prevMonthlyPivot.isNearAnyLevel(price, thresholdPercent)) {
            count++;
        }

        return count;
    }

    /**
     * Check if state has valid data.
     */
    public boolean isValid() {
        return dailyPivot != null && dailyPivot.getPivot() > 0;
    }

    /**
     * Check if state needs refresh (older than threshold).
     */
    public boolean needsRefresh(int maxAgeMinutes) {
        if (lastUpdated == null) return true;
        return Instant.now().isAfter(lastUpdated.plusSeconds(maxAgeMinutes * 60L));
    }
}
