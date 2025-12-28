package com.kotsin.consumer.stats.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * SignalStats - Per (scripCode, signalType) performance statistics
 * 
 * Tracks:
 * - All-time win rate, expectancy, R-multiples
 * - Rolling window of recent trades (last 20)
 * - Daily stats (reset at market open)
 * 
 * Used by SignalStatsGate to filter out underperforming signals.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "signal_stats")
public class SignalStats {

    @Id
    private String id;  // Format: "SCRIPCODE_SIGNALTYPE" e.g., "RELIANCE_OI_BULLISH"

    private String scripCode;
    private String signalType;  // e.g., "BREAKOUT_RETEST", "MTF_CONFLUENCE", "OI_BULLISH"

    // ========== All-time Stats ==========
    @Builder.Default
    private int totalSignals = 0;     // Total signals generated
    @Builder.Default
    private int totalTrades = 0;      // Signals that became trades
    @Builder.Default
    private int wins = 0;
    @Builder.Default
    private int losses = 0;
    @Builder.Default
    private double totalRMultiple = 0.0;
    @Builder.Default
    private double maxWin = 0.0;      // Best R-multiple
    @Builder.Default
    private double maxLoss = 0.0;     // Worst R-multiple
    @Builder.Default
    private double peakEquity = 0.0;  // For drawdown calculation
    @Builder.Default
    private double maxDrawdown = 0.0;

    // ========== Rolling Window (last 20 trades) ==========
    @Builder.Default
    private List<SimpleOutcome> recentTrades = new ArrayList<>();

    // ========== Daily Stats (reset at 9 AM IST) ==========
    @Builder.Default
    private int todaySignals = 0;
    @Builder.Default
    private int todayTrades = 0;
    @Builder.Default
    private int todayWins = 0;
    @Builder.Default
    private int todayLosses = 0;
    @Builder.Default
    private double todayPnL = 0.0;
    private LocalDate statsDate;

    // ========== Computed Metrics ==========

    /**
     * Calculate all-time win rate
     */
    public double getWinRate() {
        return totalTrades > 0 ? (double) wins / totalTrades : 0.0;
    }

    /**
     * Calculate average R-multiple per trade
     */
    public double getAvgR() {
        return totalTrades > 0 ? totalRMultiple / totalTrades : 0.0;
    }

    /**
     * Calculate expectancy (expected R per trade)
     * Formula: (WinRate × AvgWin) - (LossRate × AvgLoss)
     * Simplified: AvgR already captures this
     */
    public double getExpectancy() {
        if (totalTrades == 0) return 0.0;
        double wr = getWinRate();
        double avgR = getAvgR();
        // Positive avgR means net winning system
        return avgR;
    }

    /**
     * Calculate rolling window win rate (last 20 trades)
     */
    public double getRecentWinRate() {
        if (recentTrades == null || recentTrades.isEmpty()) return 0.0;
        long recentWins = recentTrades.stream().filter(SimpleOutcome::isWin).count();
        return (double) recentWins / recentTrades.size();
    }

    /**
     * Calculate rolling window expectancy
     */
    public double getRecentExpectancy() {
        if (recentTrades == null || recentTrades.isEmpty()) return 0.0;
        double sumR = recentTrades.stream().mapToDouble(SimpleOutcome::getRMultiple).sum();
        return sumR / recentTrades.size();
    }

    /**
     * Calculate profit factor (gross profit / gross loss)
     */
    public double getProfitFactor() {
        if (recentTrades == null || recentTrades.isEmpty()) return 0.0;
        double grossProfit = recentTrades.stream()
                .mapToDouble(SimpleOutcome::getRMultiple)
                .filter(r -> r > 0)
                .sum();
        double grossLoss = Math.abs(recentTrades.stream()
                .mapToDouble(SimpleOutcome::getRMultiple)
                .filter(r -> r < 0)
                .sum());
        return grossLoss > 0 ? grossProfit / grossLoss : grossProfit > 0 ? 999.0 : 0.0;
    }

    // ========== Update Methods ==========

    /**
     * Record a new trade outcome
     */
    public void recordOutcome(boolean isWin, double rMultiple) {
        // Update all-time stats
        totalTrades++;
        totalRMultiple += rMultiple;

        if (isWin) {
            wins++;
            maxWin = Math.max(maxWin, rMultiple);
        } else {
            losses++;
            maxLoss = Math.min(maxLoss, rMultiple);
        }

        // Update rolling window
        if (recentTrades == null) {
            recentTrades = new ArrayList<>();
        }
        recentTrades.add(new SimpleOutcome(isWin, rMultiple));
        if (recentTrades.size() > 20) {
            recentTrades.remove(0);
        }

        // Update daily stats
        LocalDate today = LocalDate.now();
        if (statsDate == null || !statsDate.equals(today)) {
            resetDailyStats();
            statsDate = today;
        }
        todayTrades++;
        if (isWin) {
            todayWins++;
        } else {
            todayLosses++;
        }
    }

    /**
     * Record a signal (even if not traded)
     */
    public void recordSignal() {
        totalSignals++;
        LocalDate today = LocalDate.now();
        if (statsDate == null || !statsDate.equals(today)) {
            resetDailyStats();
            statsDate = today;
        }
        todaySignals++;
    }

    /**
     * Reset daily stats
     */
    public void resetDailyStats() {
        todaySignals = 0;
        todayTrades = 0;
        todayWins = 0;
        todayLosses = 0;
        todayPnL = 0.0;
    }

    /**
     * Check if this is a new/untested signal type
     */
    public boolean isNew(int minTrades) {
        return totalTrades < minTrades;
    }

    // ========== Inner Classes ==========

    /**
     * Simple outcome for rolling window
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SimpleOutcome {
        private boolean win;
        private double rMultiple;
    }

    // ========== Factory Methods ==========

    /**
     * Create new stats for a signal type
     */
    public static SignalStats create(String scripCode, String signalType) {
        String id = scripCode + "_" + signalType;
        return SignalStats.builder()
                .id(id)
                .scripCode(scripCode)
                .signalType(signalType)
                .recentTrades(new ArrayList<>())
                .statsDate(LocalDate.now())
                .build();
    }
}

