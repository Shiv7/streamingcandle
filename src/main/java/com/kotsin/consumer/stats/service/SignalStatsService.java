package com.kotsin.consumer.stats.service;

import com.kotsin.consumer.stats.model.SignalStats;
import com.kotsin.consumer.stats.model.TradeOutcome;
import com.kotsin.consumer.stats.repository.SignalStatsRepository;
import com.kotsin.consumer.stats.repository.TradeOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * SignalStatsService - Manages signal performance statistics
 * 
 * Responsibilities:
 * - Record trade outcomes
 * - Update signal stats
 * - Reset daily stats at market open
 * - Provide aggregated metrics
 */
@Service
public class SignalStatsService {

    private static final Logger log = LoggerFactory.getLogger(SignalStatsService.class);

    @Autowired
    private SignalStatsRepository statsRepo;

    @Autowired
    private TradeOutcomeRepository outcomeRepo;

    /**
     * Record a trade outcome and update stats
     */
    public void recordOutcome(TradeOutcome outcome) {
        if (outcome == null) {
            log.warn("Null outcome received, ignoring");
            return;
        }

        // Save the outcome
        outcomeRepo.save(outcome);

        // Update stats
        updateStats(
                outcome.getScripCode(),
                outcome.getSignalType(),
                outcome.isWin(),
                outcome.getRMultiple()
        );

        log.info("OUTCOME RECORDED | {} | signalType={} | win={} | R={:.2f} | exitReason={}",
                outcome.getScripCode(),
                outcome.getSignalType(),
                outcome.isWin(),
                outcome.getRMultiple(),
                outcome.getExitReason());
    }

    /**
     * Record outcome by components
     */
    public void recordOutcome(String scripCode, String signalType, boolean isWin, double rMultiple) {
        updateStats(scripCode, signalType, isWin, rMultiple);
    }

    /**
     * Update stats for a scrip/signalType
     */
    private void updateStats(String scripCode, String signalType, boolean isWin, double rMultiple) {
        String key = scripCode + "_" + signalType;
        
        SignalStats stats = statsRepo.findById(key)
                .orElseGet(() -> SignalStats.create(scripCode, signalType));

        // Record the outcome
        stats.recordOutcome(isWin, rMultiple);

        // Save updated stats
        statsRepo.save(stats);

        log.debug("STATS UPDATED | {} | trades={} | winRate={:.1f}% | avgR={:.2f} | todayLosses={}",
                key,
                stats.getTotalTrades(),
                stats.getWinRate() * 100,
                stats.getAvgR(),
                stats.getTodayLosses());
    }

    /**
     * Record a signal (even if not traded) for tracking signal frequency
     */
    public void recordSignal(String scripCode, String signalType) {
        String key = scripCode + "_" + signalType;
        
        SignalStats stats = statsRepo.findById(key)
                .orElseGet(() -> SignalStats.create(scripCode, signalType));

        stats.recordSignal();
        statsRepo.save(stats);
    }

    /**
     * Reset daily stats for all signals at market open (9:00 AM IST)
     * Scheduled to run Monday-Friday at 9:00 AM IST
     */
    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetDailyStatsForAll() {
        log.info("DAILY STATS RESET | Starting reset for all signal stats...");
        
        LocalDate today = LocalDate.now();
        List<SignalStats> allStats = statsRepo.findByStatsDateNot(today);
        
        int count = 0;
        for (SignalStats stats : allStats) {
            stats.resetDailyStats();
            stats.setStatsDate(today);
            statsRepo.save(stats);
            count++;
        }
        
        log.info("DAILY STATS RESET | Reset {} signal stats for {}", count, today);
    }

    /**
     * Get stats for a specific scrip/signalType
     */
    public SignalStats getStats(String scripCode, String signalType) {
        String key = scripCode + "_" + signalType;
        return statsRepo.findById(key).orElse(null);
    }

    /**
     * Get all stats for a scrip
     */
    public List<SignalStats> getStatsForScrip(String scripCode) {
        return statsRepo.findByScripCode(scripCode);
    }

    /**
     * Get top performing signal types
     */
    public List<SignalStats> getTopPerformers(int minTrades) {
        return statsRepo.findByTotalTradesGreaterThanOrderByWinsDesc(minTrades);
    }

    /**
     * Get aggregate metrics for all signals
     */
    public AggregateMetrics getAggregateMetrics() {
        List<SignalStats> allStats = statsRepo.findAll();
        
        int totalSignals = 0;
        int totalTrades = 0;
        int totalWins = 0;
        int totalLosses = 0;
        double totalR = 0;
        
        for (SignalStats stats : allStats) {
            totalSignals += stats.getTotalSignals();
            totalTrades += stats.getTotalTrades();
            totalWins += stats.getWins();
            totalLosses += stats.getLosses();
            totalR += stats.getTotalRMultiple();
        }
        
        return new AggregateMetrics(
                allStats.size(),
                totalSignals,
                totalTrades,
                totalWins,
                totalLosses,
                totalTrades > 0 ? (double) totalWins / totalTrades : 0,
                totalTrades > 0 ? totalR / totalTrades : 0
        );
    }

    /**
     * Aggregate metrics DTO
     */
    public record AggregateMetrics(
            int uniqueSignalTypes,
            int totalSignals,
            int totalTrades,
            int totalWins,
            int totalLosses,
            double overallWinRate,
            double overallExpectancy
    ) {}
}

