package com.kotsin.consumer.stats.tracker;

import com.kotsin.consumer.stats.model.SignalHistory;
import com.kotsin.consumer.stats.model.SignalHistory.SignalDirection;
import com.kotsin.consumer.stats.model.SignalHistory.SignalOutcome;
import com.kotsin.consumer.stats.model.SignalStats;
import com.kotsin.consumer.stats.repository.SignalHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * SignalStatsTracker - Tracks and manages signal statistics with MongoDB persistence.
 *
 * Features:
 * - Real-time stats updating
 * - Historical analysis with DB persistence
 * - Performance reporting
 * - Equity curve tracking
 * - Auto-recovery on restart from DB state
 */
@Component
@Slf4j
public class SignalStatsTracker {

    private static final String LOG_PREFIX = "[SIGNAL-STATS]";

    @Autowired
    private SignalHistoryRepository signalHistoryRepository;

    // Stats per symbol (computed from DB on startup)
    private final Map<String, SignalStats> statsBySymbol = new ConcurrentHashMap<>();

    // Stats per signal type (computed from DB on startup)
    private final Map<String, SignalStats> statsByType = new ConcurrentHashMap<>();

    // Global stats
    private SignalStats globalStats;

    // In-memory cache for active signals (not yet closed) - backed by DB
    private final Map<String, SignalHistory> activeSignals = new ConcurrentHashMap<>();

    // Equity tracking
    private double currentEquity = 100000;  // Starting equity
    private double peakEquity = 100000;
    private final List<EquityPoint> equityCurve = new ArrayList<>();

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private boolean initialized = false;

    /**
     * Initialize stats from database on startup.
     */
    @PostConstruct
    public void initialize() {
        if (!initialized) {
            log.info("{} Initializing signal stats tracker from database...", LOG_PREFIX);

            // Initialize global stats
            globalStats = SignalStats.builder()
                .symbol("GLOBAL")
                .signalType("ALL")
                .build();

            try {
                // Load active signals (those without exit time)
                List<SignalHistory> dbActiveSignals = signalHistoryRepository.findByGeneratedAtBetween(
                    Instant.now().minus(24, ChronoUnit.HOURS), Instant.now());

                for (SignalHistory signal : dbActiveSignals) {
                    if (signal.getExitTime() == null && signal.getOutcome() == null) {
                        activeSignals.put(signal.getSignalId(), signal);
                    }
                }

                // Calculate stats from historical data
                recalculateStatsFromDB();

                log.info("{} Stats tracker initialized - Active Signals: {}, Total in DB: {}",
                    LOG_PREFIX, activeSignals.size(), signalHistoryRepository.count());

            } catch (Exception e) {
                log.error("{} Failed to initialize from database: {}", LOG_PREFIX, e.getMessage(), e);
            }

            initialized = true;
        }
    }

    /**
     * Recalculate stats from database.
     */
    private void recalculateStatsFromDB() {
        // Get all completed signals
        List<SignalHistory> allSignals = signalHistoryRepository.findAll();

        long wins = 0, losses = 0, breakeven = 0;
        double totalProfit = 0, totalLoss = 0;

        for (SignalHistory signal : allSignals) {
            if (signal.getOutcome() == null) continue;

            double pnl = signal.getProfitLoss();

            switch (signal.getOutcome()) {
                case WIN:
                    wins++;
                    totalProfit += pnl;
                    break;
                case LOSS:
                    losses++;
                    totalLoss += Math.abs(pnl);
                    break;
                case BREAKEVEN:
                    breakeven++;
                    break;
                default:
                    break;
            }
        }

        // Update global stats
        globalStats.setTotalSignals((int) (wins + losses + breakeven));
        globalStats.setWinningSignals((int) wins);
        globalStats.setLosingSignals((int) losses);
        globalStats.setBreakEvenSignals((int) breakeven);
        globalStats.setTotalProfit(totalProfit);
        globalStats.setTotalLoss(totalLoss);
        globalStats.setNetProfit(totalProfit - totalLoss);

        if (wins + losses > 0) {
            globalStats.setWinRate((double) wins / (wins + losses) * 100);
        }
        if (totalLoss > 0) {
            globalStats.setProfitFactor(totalProfit / totalLoss);
        }

        log.info("{} Stats recalculated - Wins: {}, Losses: {}, WinRate: {}%",
            LOG_PREFIX, wins, losses, String.format("%.1f", globalStats.getWinRate()));
    }

    /**
     * Record a new signal generation and persist to database.
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

        // Persist to database FIRST
        try {
            signal = signalHistoryRepository.save(signal);
            log.info("{} Signal recorded and persisted: {} {} {} @ {}",
                LOG_PREFIX, symbol, direction, signalType, signalPrice);
        } catch (Exception e) {
            log.error("{} Failed to persist signal: {}", LOG_PREFIX, e.getMessage(), e);
        }

        // Store in active signals cache
        activeSignals.put(signal.getSignalId(), signal);

        return signal;
    }

    /**
     * Record signal entry and persist.
     */
    public void recordEntry(String signalId, double entryPrice) {
        SignalHistory signal = activeSignals.get(signalId);
        if (signal == null) {
            // Try to find in database
            signal = signalHistoryRepository.findAll().stream()
                .filter(s -> s.getSignalId().equals(signalId))
                .findFirst()
                .orElse(null);
        }

        if (signal != null) {
            signal.recordEntry(entryPrice, Instant.now());

            // Persist update
            try {
                signalHistoryRepository.save(signal);
                activeSignals.put(signalId, signal);
                log.info("{} Entry recorded and persisted for {}: @ {}", LOG_PREFIX, signalId, entryPrice);
            } catch (Exception e) {
                log.error("{} Failed to persist entry: {}", LOG_PREFIX, e.getMessage());
            }
        }
    }

    /**
     * Record signal exit and update stats - persist to database.
     */
    public void recordExit(String signalId, double exitPrice, String exitReason) {
        SignalHistory signal = activeSignals.remove(signalId);
        if (signal == null) {
            log.warn("{} No active signal found for exit: {}", LOG_PREFIX, signalId);
            return;
        }

        signal.recordExit(exitPrice, Instant.now(), exitReason);

        // Persist to database
        try {
            signalHistoryRepository.save(signal);
            log.info("{} Exit recorded and persisted for {}: @ {} P&L: {} ({})",
                LOG_PREFIX, signalId, exitPrice, signal.getProfitLoss(), signal.getOutcome());
        } catch (Exception e) {
            log.error("{} Failed to persist exit: {}", LOG_PREFIX, e.getMessage());
            // Re-add to active signals if persistence fails
            activeSignals.put(signalId, signal);
            return;
        }

        // Update stats
        updateStats(signal);

        // Update equity
        updateEquity(signal.getProfitLoss());
    }

    /**
     * Update price excursion for active signals.
     */
    public void updateActiveSignals(String symbol, double currentPrice) {
        activeSignals.values().stream()
            .filter(s -> s.getSymbol().equals(symbol))
            .filter(s -> s.getEntryPrice() > 0)
            .forEach(s -> {
                s.updateMaxExcursion(currentPrice);
                // Persist update periodically (not every tick)
                // This could be optimized with batch updates
            });
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

    // ==================== REPORTING (from database) ====================

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
     * Get signal history for a symbol from database.
     */
    public List<SignalHistory> getHistory(String symbol, int limit) {
        return signalHistoryRepository.findBySymbol(symbol,
            PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "generatedAt")))
            .getContent();
    }

    /**
     * Get today's signals from database.
     */
    public List<SignalHistory> getTodaySignals(String symbol) {
        LocalDate today = LocalDate.now(IST);
        Instant startOfDay = today.atStartOfDay(IST).toInstant();
        Instant endOfDay = today.plusDays(1).atStartOfDay(IST).toInstant();

        return signalHistoryRepository.findBySymbolAndGeneratedAtBetween(symbol, startOfDay, endOfDay);
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
     * Get best performing signal types from database.
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
     * Get win rate by signal type from database.
     */
    public Map<String, Double> getWinRateBySignalType() {
        Map<String, Double> winRates = new HashMap<>();

        var performances = signalHistoryRepository.getPerformanceByAllTypes();
        for (var perf : performances) {
            winRates.put(perf.getId(), perf.getWinRate());
        }

        return winRates;
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
        report.put("totalInDB", signalHistoryRepository.count());

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
