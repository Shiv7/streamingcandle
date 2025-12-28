package com.kotsin.consumer.gate;

import com.kotsin.consumer.gate.model.GateResult;
import com.kotsin.consumer.stats.model.SignalStats;
import com.kotsin.consumer.stats.repository.SignalStatsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * SignalStatsGate - Layer 4 of the Gate Chain (Online Learning)
 * 
 * Uses historical performance to filter signals:
 * 1. New signal types are ALLOWED (need to learn)
 * 2. Too many daily losses = BLOCKED
 * 3. Win rate too low = BLOCKED
 * 4. Negative expectancy = BLOCKED
 * 5. Strong performers get position size boost
 * 
 * This is the "learning" gate that improves over time.
 */
@Service
public class SignalStatsGate {

    private static final Logger log = LoggerFactory.getLogger(SignalStatsGate.class);

    @Autowired
    private SignalStatsRepository statsRepo;

    @Value("${gate.stats.enabled:true}")
    private boolean enabled;

    @Value("${gate.stats.min.trades:5}")
    private int minTradesForStats;

    @Value("${gate.stats.min.win.rate:0.35}")
    private double minWinRate;

    @Value("${gate.stats.min.expectancy:-0.2}")
    private double minExpectancy;

    @Value("${gate.stats.max.daily.losses:3}")
    private int maxDailyLosses;

    @Value("${gate.stats.use.recent.window:true}")
    private boolean useRecentWindow;

    /**
     * Evaluate signal based on historical performance
     * 
     * @param scripCode   The scrip
     * @param signalType  The signal type (e.g., "BREAKOUT_RETEST")
     * @return GateResult with pass/fail and position multiplier
     */
    public GateResult evaluate(String scripCode, String signalType) {
        if (!enabled) {
            return GateResult.pass("STATS_GATE_DISABLED");
        }

        String key = scripCode + "_" + signalType;
        SignalStats stats = statsRepo.findById(key).orElse(null);

        // Rule 1: New signal type = ALLOW (need to learn)
        if (stats == null || stats.getTotalTrades() < minTradesForStats) {
            log.debug("STATS_GATE | {} | PASSED | reason=NEW_SIGNAL_LEARNING | trades={}", 
                    key, stats != null ? stats.getTotalTrades() : 0);
            GateResult result = GateResult.pass("NEW_SIGNAL_LEARNING", 0.8);  // Reduced size for untested
            result.setGateName("STATS_GATE");
            return result;
        }

        // Ensure daily stats are current
        LocalDate today = LocalDate.now();
        if (stats.getStatsDate() == null || !stats.getStatsDate().equals(today)) {
            // Stats from previous day - reset daily counters in memory
            stats.resetDailyStats();
            stats.setStatsDate(today);
            // Save will happen on next outcome
        }

        // Rule 2: Too many losses today
        if (stats.getTodayLosses() >= maxDailyLosses) {
            String detail = String.format("%d losses today, max=%d", stats.getTodayLosses(), maxDailyLosses);
            log.info("STATS_GATE | {} | FAILED | reason=DAILY_LOSS_LIMIT | {}", key, detail);
            return GateResult.fail("DAILY_LOSS_LIMIT", detail);
        }

        // Get the appropriate win rate and expectancy (all-time or recent)
        double winRate = useRecentWindow ? stats.getRecentWinRate() : stats.getWinRate();
        double expectancy = useRecentWindow ? stats.getRecentExpectancy() : stats.getExpectancy();

        // Rule 3: Win rate too low
        if (winRate < minWinRate) {
            String detail = String.format("winRate=%.1f%% < min=%.1f%% (trades=%d)", 
                    winRate * 100, minWinRate * 100, stats.getTotalTrades());
            log.info("STATS_GATE | {} | FAILED | reason=WIN_RATE_LOW | {}", key, detail);
            return GateResult.fail("WIN_RATE_LOW", detail);
        }

        // Rule 4: Negative expectancy
        if (expectancy < minExpectancy) {
            String detail = String.format("expectancy=%.2fR < min=%.2fR", expectancy, minExpectancy);
            log.info("STATS_GATE | {} | FAILED | reason=NEGATIVE_EXPECTANCY | {}", key, detail);
            return GateResult.fail("NEGATIVE_EXPECTANCY", detail);
        }

        // Calculate position multiplier based on performance
        double positionMultiplier = calculatePositionMultiplier(stats, winRate, expectancy);

        String passReason = String.format("winRate=%.1f%%, expectancy=%.2fR, trades=%d", 
                winRate * 100, expectancy, stats.getTotalTrades());
        log.debug("STATS_GATE | {} | PASSED | {} | multiplier={}", 
                key, passReason, String.format("%.2f", positionMultiplier));

        GateResult result = GateResult.pass("STATS_OK", positionMultiplier);
        result.setGateName("STATS_GATE");
        result.setDetail(passReason);
        return result;
    }

    /**
     * Calculate position size multiplier based on performance
     */
    private double calculatePositionMultiplier(SignalStats stats, double winRate, double expectancy) {
        double multiplier = 1.0;

        // Tier 1: Exceptional performers
        if (winRate > 0.6 && expectancy > 0.5) {
            multiplier = 1.25;
        }
        // Tier 2: Strong performers
        else if (winRate > 0.55 && expectancy > 0.3) {
            multiplier = 1.15;
        }
        // Tier 3: Average performers
        else if (winRate > 0.45 && expectancy > 0) {
            multiplier = 1.0;
        }
        // Tier 4: Weak performers (but still passing gates)
        else if (winRate >= minWinRate) {
            multiplier = 0.8;
        }

        // Adjust based on recent streak
        if (stats.getRecentTrades() != null && !stats.getRecentTrades().isEmpty()) {
            // Check last 5 trades for momentum
            int recentSize = Math.min(5, stats.getRecentTrades().size());
            int recentWins = 0;
            for (int i = stats.getRecentTrades().size() - recentSize; i < stats.getRecentTrades().size(); i++) {
                if (stats.getRecentTrades().get(i).isWin()) {
                    recentWins++;
                }
            }
            
            if (recentWins >= 4) {
                multiplier *= 1.1;  // On a hot streak
            } else if (recentWins <= 1) {
                multiplier *= 0.9;  // Cold streak
            }
        }

        // Cap multiplier between 0.5 and 1.5
        return Math.max(0.5, Math.min(1.5, multiplier));
    }

    /**
     * Get stats for a signal type (for external reporting)
     */
    public SignalStats getStats(String scripCode, String signalType) {
        String key = scripCode + "_" + signalType;
        return statsRepo.findById(key).orElse(null);
    }
}

