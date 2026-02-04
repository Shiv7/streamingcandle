package com.kotsin.consumer.stats.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * SignalStats - Performance statistics for trading signals.
 *
 * Tracks:
 * - Win rate and profit factor
 * - Signal type performance
 * - Time-based performance
 * - Drawdown and recovery
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalStats {

    private String symbol;
    private String signalType;
    private Instant lastUpdated;

    // ==================== OVERALL STATS ====================
    private int totalSignals;
    private int winningSignals;
    private int losingSignals;
    private int breakEvenSignals;
    private double winRate;              // Winning / Total * 100

    // ==================== PROFIT/LOSS ====================
    private double totalProfit;
    private double totalLoss;
    private double netProfit;
    private double profitFactor;         // Total Profit / Total Loss
    private double avgWin;
    private double avgLoss;
    private double avgRR;                // Average Risk-Reward ratio
    private double expectancy;           // (WinRate * AvgWin) - (LossRate * AvgLoss)

    // ==================== STREAKS ====================
    private int currentStreak;           // Positive = wins, Negative = losses
    private int maxWinStreak;
    private int maxLossStreak;
    private int currentWinStreak;
    private int currentLossStreak;

    // ==================== DRAWDOWN ====================
    private double maxDrawdown;
    private double maxDrawdownPercent;
    private double currentDrawdown;
    private Instant maxDrawdownDate;
    private int drawdownDuration;        // Bars in drawdown

    // ==================== TIME-BASED STATS ====================
    @Builder.Default
    private Map<String, DayStats> statsByDay = new HashMap<>();
    @Builder.Default
    private Map<String, SessionStats> statsBySession = new HashMap<>();
    private double morningWinRate;       // 9:15 - 12:00
    private double afternoonWinRate;     // 12:00 - 15:30

    // ==================== SIGNAL TYPE STATS ====================
    @Builder.Default
    private Map<String, TypeStats> statsBySignalType = new HashMap<>();

    // ==================== RECENT PERFORMANCE ====================
    private double last10WinRate;
    private double last20WinRate;
    private double last50WinRate;
    private double todayNetProfit;
    private int todaySignals;
    private int todayWins;

    // ==================== ENUMS ====================

    public enum PerformanceGrade {
        EXCELLENT,   // Win rate > 65%, PF > 2
        GOOD,        // Win rate > 55%, PF > 1.5
        AVERAGE,     // Win rate > 45%, PF > 1
        POOR,        // Win rate < 45%, PF < 1
        TERRIBLE     // Win rate < 35%, PF < 0.8
    }

    // ==================== HELPER METHODS ====================

    public void recordSignal(boolean won, double profit, String signalType, String session) {
        totalSignals++;

        if (profit > 0) {
            winningSignals++;
            totalProfit += profit;
            currentWinStreak++;
            currentLossStreak = 0;
            currentStreak = Math.max(0, currentStreak) + 1;
            maxWinStreak = Math.max(maxWinStreak, currentWinStreak);
        } else if (profit < 0) {
            losingSignals++;
            totalLoss += Math.abs(profit);
            currentLossStreak++;
            currentWinStreak = 0;
            currentStreak = Math.min(0, currentStreak) - 1;
            maxLossStreak = Math.max(maxLossStreak, currentLossStreak);
        } else {
            breakEvenSignals++;
        }

        netProfit = totalProfit - totalLoss;
        updateMetrics();

        // Update session stats
        SessionStats sStats = statsBySession.computeIfAbsent(session, s -> new SessionStats());
        sStats.record(won, profit);

        // Update type stats
        TypeStats tStats = statsBySignalType.computeIfAbsent(signalType, s -> new TypeStats());
        tStats.record(won, profit);

        lastUpdated = Instant.now();
    }

    private void updateMetrics() {
        winRate = totalSignals > 0 ? (double) winningSignals / totalSignals * 100 : 0;
        profitFactor = totalLoss > 0 ? totalProfit / totalLoss : totalProfit > 0 ? Double.MAX_VALUE : 0;
        avgWin = winningSignals > 0 ? totalProfit / winningSignals : 0;
        avgLoss = losingSignals > 0 ? totalLoss / losingSignals : 0;
        expectancy = (winRate / 100 * avgWin) - ((100 - winRate) / 100 * avgLoss);
        avgRR = avgLoss > 0 ? avgWin / avgLoss : 0;
    }

    public void updateDrawdown(double equity, double peak) {
        if (equity < peak) {
            currentDrawdown = peak - equity;
            double ddPercent = peak > 0 ? currentDrawdown / peak * 100 : 0;

            if (currentDrawdown > maxDrawdown) {
                maxDrawdown = currentDrawdown;
                maxDrawdownPercent = ddPercent;
                maxDrawdownDate = Instant.now();
            }
            drawdownDuration++;
        } else {
            currentDrawdown = 0;
            drawdownDuration = 0;
        }
    }

    public PerformanceGrade getGrade() {
        if (winRate > 65 && profitFactor > 2) return PerformanceGrade.EXCELLENT;
        if (winRate > 55 && profitFactor > 1.5) return PerformanceGrade.GOOD;
        if (winRate > 45 && profitFactor > 1) return PerformanceGrade.AVERAGE;
        if (winRate > 35 && profitFactor > 0.8) return PerformanceGrade.POOR;
        return PerformanceGrade.TERRIBLE;
    }

    public boolean isPerforming() {
        return winRate > 50 && profitFactor > 1.2 && currentLossStreak < 5;
    }

    public boolean isInDrawdown() {
        return currentDrawdown > 0;
    }

    public double getSharpeRatio(double riskFreeRate) {
        // Simplified Sharpe calculation
        if (totalSignals < 10) return 0;
        double avgReturn = netProfit / totalSignals;
        double stdDev = calculateStdDev();
        return stdDev > 0 ? (avgReturn - riskFreeRate) / stdDev : 0;
    }

    private double calculateStdDev() {
        // Would need individual trade returns - simplified version
        return Math.abs(avgWin - avgLoss) / 2;
    }

    // ==================== INNER CLASSES ====================

    @Data
    public static class DayStats {
        private LocalDate date;
        private int signals;
        private int wins;
        private double profit;
        private double winRate;

        public void record(boolean won, double pnl) {
            signals++;
            if (won) wins++;
            profit += pnl;
            winRate = signals > 0 ? (double) wins / signals * 100 : 0;
        }
    }

    @Data
    public static class SessionStats {
        private int signals;
        private int wins;
        private double profit;
        private double winRate;

        public void record(boolean won, double pnl) {
            signals++;
            if (won) wins++;
            profit += pnl;
            winRate = signals > 0 ? (double) wins / signals * 100 : 0;
        }
    }

    @Data
    public static class TypeStats {
        private int signals;
        private int wins;
        private double profit;
        private double winRate;
        private double avgProfit;

        public void record(boolean won, double pnl) {
            signals++;
            if (won) wins++;
            profit += pnl;
            winRate = signals > 0 ? (double) wins / signals * 100 : 0;
            avgProfit = signals > 0 ? profit / signals : 0;
        }
    }
}
