package com.kotsin.consumer.mtf.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * MultiTimeframeData - Aggregated analysis across multiple timeframes.
 *
 * Contains:
 * - Trend alignment across timeframes
 * - Key level confluence
 * - Signal strength based on MTF agreement
 * - Timeframe-specific metrics
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiTimeframeData {

    private String symbol;
    private Instant timestamp;

    // ==================== TREND ALIGNMENT ====================
    @Builder.Default
    private Map<String, TrendDirection> trendByTimeframe = new HashMap<>();
    private TrendAlignment overallAlignment;
    private double alignmentScore;  // 0-100 (higher = more aligned)

    // ==================== TIMEFRAME DATA ====================
    @Builder.Default
    private Map<String, TimeframeMetrics> metricsByTimeframe = new HashMap<>();

    // ==================== KEY LEVELS ====================
    private double htfResistance;    // Higher TF resistance
    private double htfSupport;       // Higher TF support
    private double ltfResistance;    // Lower TF resistance
    private double ltfSupport;       // Lower TF support
    private boolean hasLevelConfluence;  // HTF and LTF levels align

    // ==================== MOMENTUM ====================
    private double htfMomentum;      // Higher TF momentum
    private double ltfMomentum;      // Lower TF momentum
    private boolean momentumAligned;

    // ==================== SIGNALS ====================
    private MTFSignal signal;
    private double signalStrength;   // 0-100
    private String signalTimeframe;  // Timeframe that triggered
    private String signalReason;

    // ==================== ENTRY QUALITY ====================
    private EntryQuality entryQuality;
    private boolean isOptimalEntry;
    private double riskRewardRatio;

    // ==================== ENUMS ====================

    public enum TrendDirection {
        STRONG_UP,
        UP,
        NEUTRAL,
        DOWN,
        STRONG_DOWN
    }

    public enum TrendAlignment {
        FULLY_ALIGNED,      // All timeframes agree
        MOSTLY_ALIGNED,     // Most timeframes agree
        MIXED,              // No clear agreement
        CONFLICTING         // Opposing trends
    }

    public enum MTFSignal {
        STRONG_BUY,
        BUY,
        NEUTRAL,
        SELL,
        STRONG_SELL
    }

    public enum EntryQuality {
        EXCELLENT,   // All factors align
        GOOD,        // Most factors align
        AVERAGE,     // Some alignment
        POOR         // Little alignment
    }

    // ==================== HELPER METHODS ====================

    public boolean isBullishAlignment() {
        return overallAlignment == TrendAlignment.FULLY_ALIGNED
            && trendByTimeframe.values().stream()
                .allMatch(t -> t == TrendDirection.UP || t == TrendDirection.STRONG_UP);
    }

    public boolean isBearishAlignment() {
        return overallAlignment == TrendAlignment.FULLY_ALIGNED
            && trendByTimeframe.values().stream()
                .allMatch(t -> t == TrendDirection.DOWN || t == TrendDirection.STRONG_DOWN);
    }

    public boolean hasBuySignal() {
        return signal == MTFSignal.BUY || signal == MTFSignal.STRONG_BUY;
    }

    public boolean hasSellSignal() {
        return signal == MTFSignal.SELL || signal == MTFSignal.STRONG_SELL;
    }

    public boolean isHighQualitySetup() {
        return entryQuality == EntryQuality.EXCELLENT || entryQuality == EntryQuality.GOOD;
    }

    public TrendDirection getTrend(String timeframe) {
        return trendByTimeframe.getOrDefault(timeframe, TrendDirection.NEUTRAL);
    }

    public TimeframeMetrics getMetrics(String timeframe) {
        return metricsByTimeframe.get(timeframe);
    }

    public void addTimeframeData(String timeframe, TrendDirection trend, TimeframeMetrics metrics) {
        trendByTimeframe.put(timeframe, trend);
        metricsByTimeframe.put(timeframe, metrics);
    }

    // ==================== INNER CLASSES ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeframeMetrics {
        private String timeframe;
        private double rsi;
        private double macdHistogram;
        private double atr;
        private double ema20;
        private double ema50;
        private double vwap;
        private boolean aboveVwap;
        private boolean aboveEma20;
        private boolean aboveEma50;
        private double superTrendValue;
        private boolean superTrendBullish;
        private double close;
        private double high;
        private double low;

        public TrendDirection determineTrend() {
            int bullishCount = 0;
            int bearishCount = 0;

            if (aboveEma20) bullishCount++; else bearishCount++;
            if (aboveEma50) bullishCount++; else bearishCount++;
            if (aboveVwap) bullishCount++; else bearishCount++;
            if (superTrendBullish) bullishCount++; else bearishCount++;
            if (rsi > 50) bullishCount++; else bearishCount++;
            if (macdHistogram > 0) bullishCount++; else bearishCount++;

            if (bullishCount >= 5) return TrendDirection.STRONG_UP;
            if (bullishCount >= 4) return TrendDirection.UP;
            if (bearishCount >= 5) return TrendDirection.STRONG_DOWN;
            if (bearishCount >= 4) return TrendDirection.DOWN;
            return TrendDirection.NEUTRAL;
        }
    }
}
