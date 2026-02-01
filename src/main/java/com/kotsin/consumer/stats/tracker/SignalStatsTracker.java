package com.kotsin.consumer.stats.tracker;

import com.kotsin.consumer.stats.model.SignalHistory;
import com.kotsin.consumer.stats.model.SignalHistory.SignalDirection;
import com.kotsin.consumer.stats.model.SignalHistory.SignalOutcome;
import com.kotsin.consumer.stats.model.SignalStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * SignalStatsTracker - Tracks and manages signal statistics.
 *
 * Features:
 * - Real-time stats updating
 * - Historical analysis
 * - Performance reporting
 * - Equity curve tracking
 */
@Component
@Slf4j
public class SignalStatsTracker {

    // Stats per symbol
    private final Map<String, SignalStats> statsBySymbol = new ConcurrentHashMap<>();

    // Stats per signal type
    private final Map<String, SignalStats> statsByType = new ConcurrentHashMap<>();

    // Global stats
    private final SignalStats globalStats = SignalStats.builder()
        .symbol("GLOBAL")
        .signalType("ALL")
        .build();

    // Signal history (in-memory cache, would be backed by MongoDB)
    private final Map<String, List<SignalHistory>> historyBySymbol = new ConcurrentHashMap<>();

    // Active signals (not yet closed)
    private final Map<String, SignalHistory> activeSignals = new ConcurrentHashMap<>();

    // Equity tracking
    private double currentEquity = 100000;  // Starting equity
    private double peakEquity = 100000;
    private final List<EquityPoint> equityCurve = new ArrayList<>();

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Record a new signal generation.
     */
    public SignalHistory recordSignal(String symbol, String signalType, String timeframe,
                                       SignalDirection direction, double signalPrice,
                                       double target, double stopLoss,
                                       double confidence, double quality,
                                       String triggerReason) {

        SignalHistory signal = SignalHistory.builder()
            .signalId(SignalHistory.generateSignalId(symbol, signalType, Instant.now()))
            .symbol(symbol)
            .signalType(signalType)
            .timeframe(timeframe)
            .direction(direction)
            .generatedAt(Instant.now())
            .signalPrice(signalPrice)
            .targetPrice(target)
            .stopLoss(stopLoss)
            .confidence(confidence)
            .quality(quality)
            .triggerReason(triggerReason)
            .riskRewardPlanned(calculateRR(signalPrice, target, stopLoss, direction))
            .build();

        // Store as active signal
        activeSignals.put(signal.getSignalId(), signal);

        log.info("Signal recorded: {} {} {} @ {} (target: {}, stop: {})",
            symbol, direction, signalType, signalPrice, target, stopLoss);

        return signal;
    }

    /**
     * Record signal entry.
     */
    public void recordEntry(String signalId, double entryPrice) {
        SignalHistory signal = activeSignals.get(signalId);
        if (signal != null) {
            signal.recordEntry(entryPrice, Instant.now());
            log.info("Entry recorded for {}: @ {}", signalId, entryPrice);
        }
    }

    /**
     * Record signal exit and update stats.
     */
    public void recordExit(String signalId, double exitPrice, String exitReason) {
        SignalHistory signal = activeSignals.remove(signalId);
        if (signal == null) {
            log.warn("No active signal found for exit: {}", signalId);
            return;
        }

        signal.recordExit(exitPrice, Instant.now(), exitReason);

        // Update stats
        updateStats(signal);

        // Store in history
        historyBySymbol.computeIfAbsent(signal.getSymbol(), s -> new ArrayList<>()).add(signal);

        // Update equity
        updateEquity(signal.getProfitLoss());

        log.info("Exit recorded for {}: @ {} P&L: {} ({})",
            signalId, exitPrice, signal.getProfitLoss(), signal.getOutcome());
    }

    /**
     * Update price excursion for active signals.
     */
    public void updateActiveSignals(String symbol, double currentPrice) {
        activeSignals.values().stream()
            .filter(s -> s.getSymbol().equals(symbol))
            .filter(s -> s.getEntryPrice() > 0)
            .forEach(s -> s.updateMaxExcursion(currentPrice));
    }

    /**
     * Check if any active signals should be closed.
     */
    public List<String> checkExits(String symbol, double high, double low, double close) {
        List<String> closedSignals = new ArrayList<>();

        activeSignals.values().stream()
            .filter(s -> s.getSymbol().equals(symbol))
            .filter(s -> s.getEntryPrice() > 0)
            .forEach(s -> {
                String exitReason = checkExitConditions(s, high, low, close);
                if (exitReason != null) {
                    double exitPrice = determineExitPrice(s, high, low, close, exitReason);
                    recordExit(s.getSignalId(), exitPrice, exitReason);
                    closedSignals.add(s.getSignalId());
                }
            });

        return closedSignals;
    }

    private String checkExitConditions(SignalHistory signal, double high, double low, double close) {
        if (signal.isLong()) {
            if (high >= signal.getTargetPrice()) return "TARGET_HIT";
            if (low <= signal.getStopLoss()) return "STOP_HIT";
        } else {
            if (low <= signal.getTargetPrice()) return "TARGET_HIT";
            if (high >= signal.getStopLoss()) return "STOP_HIT";
        }
        return null;
    }

    private double determineExitPrice(SignalHistory signal, double high, double low,
                                       double close, String reason) {
        if ("TARGET_HIT".equals(reason)) {
            return signal.getTargetPrice();
        } else if ("STOP_HIT".equals(reason)) {
            return signal.getStopLoss();
        }
        return close;
    }

    /**
     * Update all stats with completed signal.
     */
    private void updateStats(SignalHistory signal) {
        String symbol = signal.getSymbol();
        String type = signal.getSignalType();
        boolean won = signal.isWin();
        double profit = signal.getProfitLoss();

        // Get session for time-based stats
        String session = getSession(signal.getEntryTime());

        // Update symbol stats
        SignalStats symbolStats = statsBySymbol.computeIfAbsent(symbol,
            s -> SignalStats.builder().symbol(s).signalType("ALL").build());
        symbolStats.recordSignal(won, profit, type, session);

        // Update type stats
        SignalStats typeStats = statsByType.computeIfAbsent(type,
            t -> SignalStats.builder().symbol("ALL").signalType(t).build());
        typeStats.recordSignal(won, profit, type, session);

        // Update global stats
        globalStats.recordSignal(won, profit, type, session);
    }

    private String getSession(Instant time) {
        if (time == null) return "UNKNOWN";
        int hour = time.atZone(IST).getHour();
        if (hour < 12) return "MORNING";
        if (hour < 14) return "MIDDAY";
        return "AFTERNOON";
    }

    private void updateEquity(double pnl) {
        currentEquity += pnl;

        if (currentEquity > peakEquity) {
            peakEquity = currentEquity;
        }

        // Update drawdown
        globalStats.updateDrawdown(currentEquity, peakEquity);

        // Record equity point
        equityCurve.add(new EquityPoint(Instant.now(), currentEquity));
    }

    private double calculateRR(double entry, double target, double stop, SignalDirection dir) {
        double risk = Math.abs(entry - stop);
        double reward = Math.abs(target - entry);
        return risk > 0 ? reward / risk : 0;
    }

    // ==================== REPORTING ====================

    /**
     * Get performance report for a symbol.
     */
    public SignalStats getSymbolStats(String symbol) {
        return statsBySymbol.get(symbol);
    }

    /**
     * Get performance report for a signal type.
     */
    public SignalStats getTypeStats(String signalType) {
        return statsByType.get(signalType);
    }

    /**
     * Get global stats.
     */
    public SignalStats getGlobalStats() {
        return globalStats;
    }

    /**
     * Get signal history for a symbol.
     */
    public List<SignalHistory> getHistory(String symbol, int limit) {
        List<SignalHistory> history = historyBySymbol.getOrDefault(symbol, Collections.emptyList());
        int start = Math.max(0, history.size() - limit);
        return new ArrayList<>(history.subList(start, history.size()));
    }

    /**
     * Get today's signals.
     */
    public List<SignalHistory> getTodaySignals(String symbol) {
        LocalDate today = LocalDate.now(IST);
        return historyBySymbol.getOrDefault(symbol, Collections.emptyList()).stream()
            .filter(s -> s.getGeneratedAt().atZone(IST).toLocalDate().equals(today))
            .collect(Collectors.toList());
    }

    /**
     * Get active signals count.
     */
    public int getActiveSignalsCount() {
        return activeSignals.size();
    }

    /**
     * Get active signals for symbol.
     */
    public List<SignalHistory> getActiveSignals(String symbol) {
        return activeSignals.values().stream()
            .filter(s -> s.getSymbol().equals(symbol))
            .collect(Collectors.toList());
    }

    /**
     * Get equity curve.
     */
    public List<EquityPoint> getEquityCurve() {
        return new ArrayList<>(equityCurve);
    }

    /**
     * Get best performing signal types.
     */
    public List<Map.Entry<String, SignalStats>> getBestPerformingTypes(int limit) {
        return statsByType.entrySet().stream()
            .filter(e -> e.getValue().getTotalSignals() >= 10)
            .sorted((a, b) -> Double.compare(b.getValue().getWinRate(), a.getValue().getWinRate()))
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * Get worst performing signal types.
     */
    public List<Map.Entry<String, SignalStats>> getWorstPerformingTypes(int limit) {
        return statsByType.entrySet().stream()
            .filter(e -> e.getValue().getTotalSignals() >= 10)
            .sorted(Comparator.comparingDouble(a -> a.getValue().getWinRate()))
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * Generate summary report.
     */
    public Map<String, Object> generateReport() {
        Map<String, Object> report = new HashMap<>();

        report.put("totalSignals", globalStats.getTotalSignals());
        report.put("winRate", String.format("%.2f%%", globalStats.getWinRate()));
        report.put("profitFactor", String.format("%.2f", globalStats.getProfitFactor()));
        report.put("netProfit", globalStats.getNetProfit());
        report.put("maxDrawdown", globalStats.getMaxDrawdownPercent());
        report.put("currentStreak", globalStats.getCurrentStreak());
        report.put("grade", globalStats.getGrade());
        report.put("activeSignals", activeSignals.size());
        report.put("currentEquity", currentEquity);
        report.put("peakEquity", peakEquity);

        return report;
    }

    // ==================== HELPER CLASSES ====================

    public static class EquityPoint {
        public final Instant time;
        public final double equity;

        public EquityPoint(Instant time, double equity) {
            this.time = time;
            this.equity = equity;
        }
    }
}
