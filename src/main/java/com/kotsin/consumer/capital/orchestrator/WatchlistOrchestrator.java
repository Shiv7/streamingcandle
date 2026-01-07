package com.kotsin.consumer.capital.orchestrator;

import com.kotsin.consumer.capital.model.*;
import com.kotsin.consumer.capital.service.CorrelationGuardrail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * WatchlistOrchestrator - Module 16: Watchlist
 * 
 * Maintains ranked list of top trading opportunities:
 * - Collects FinalMagnitude outputs
 * - Applies Correlation Guardrail
 * - Ranks by magnitude
 * - Emits top N as watchlist
 * 
 * This is the final output of the quant framework.
 */
@Slf4j
@Service
public class WatchlistOrchestrator {

    @Autowired
    private CorrelationGuardrail correlationGuardrail;

    @Value("${watchlist.max.size:20}")
    private int maxWatchlistSize;

    @Value("${watchlist.min.magnitude:0.4}")
    private double minMagnitude;

    @Value("${watchlist.staleness.ms:300000}")
    private long stalenessThresholdMs;  // 5 minutes

    // Current watchlist entries by scripCode
    private final ConcurrentHashMap<String, WatchlistEntry> entries = new ConcurrentHashMap<>();

    // Sector lookup (simplified)
    private final Map<String, String> sectorCache = new ConcurrentHashMap<>();

    /**
     * Update watchlist with new FinalMagnitude
     */
    public void update(FinalMagnitude magnitude) {
        if (magnitude == null || magnitude.getFinalMagnitude() < minMagnitude) {
            // Remove if below threshold
            entries.remove(magnitude.getScripCode());
            return;
        }

        // Check correlation guardrail
        CorrelationGuardrailOutput cg = correlationGuardrail.check(
                magnitude.getScripCode(),
                magnitude.getCompanyName(),
                magnitude.getDirection()
        );

        // Build watchlist entry
        WatchlistEntry entry = buildEntry(magnitude, cg);
        
        // Add to entries
        entries.put(magnitude.getScripCode(), entry);

        // Log if high quality
        if (entry.getQualityLevel().getStars() >= 4) {
            log.info("⭐ WATCHLIST UPDATE | {} | rank=TBD mag={} dir={} quality={}",
                    entry.getScripCode(),
                    String.format("%.3f", entry.getFinalMagnitude()),
                    entry.getDirection(),
                    entry.getQualityLevel());
        }
    }

    /**
     * Get current ranked watchlist
     */
    public List<WatchlistEntry> getRankedWatchlist() {
        // Remove stale entries
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(e -> 
                now - e.getValue().getTimestamp() > stalenessThresholdMs);

        // Sort by magnitude descending and assign ranks
        List<WatchlistEntry> ranked = entries.values().stream()
                .sorted((a, b) -> Double.compare(b.getFinalMagnitude(), a.getFinalMagnitude()))
                .limit(maxWatchlistSize)
                .collect(Collectors.toList());

        // Assign ranks
        for (int i = 0; i < ranked.size(); i++) {
            ranked.get(i).setRank(i + 1);
        }

        return ranked;
    }

    /**
     * Get top N entries
     */
    public List<WatchlistEntry> getTopN(int n) {
        return getRankedWatchlist().stream()
                .limit(n)
                .collect(Collectors.toList());
    }

    /**
     * Get bullish opportunities
     */
    public List<WatchlistEntry> getBullish() {
        return getRankedWatchlist().stream()
                .filter(e -> "BULLISH".equals(e.getDirection()))
                .collect(Collectors.toList());
    }

    /**
     * Get bearish opportunities
     */
    public List<WatchlistEntry> getBearish() {
        return getRankedWatchlist().stream()
                .filter(e -> "BEARISH".equals(e.getDirection()))
                .collect(Collectors.toList());
    }

    /**
     * Get high quality entries (4+ stars)
     */
    public List<WatchlistEntry> getHighQuality() {
        return getRankedWatchlist().stream()
                .filter(e -> e.getQualityLevel().getStars() >= 4)
                .collect(Collectors.toList());
    }

    /**
     * Get entry for specific scripCode
     */
    public Optional<WatchlistEntry> getEntry(String scripCode) {
        return Optional.ofNullable(entries.get(scripCode));
    }

    /**
     * Remove entry from watchlist
     */
    public void remove(String scripCode) {
        entries.remove(scripCode);
    }

    /**
     * Clear entire watchlist
     */
    public void clear() {
        entries.clear();
    }

    /**
     * Get watchlist statistics
     */
    public WatchlistStats getStats() {
        List<WatchlistEntry> list = getRankedWatchlist();
        
        int bullish = (int) list.stream().filter(e -> "BULLISH".equals(e.getDirection())).count();
        int bearish = (int) list.stream().filter(e -> "BEARISH".equals(e.getDirection())).count();
        int excellent = (int) list.stream().filter(e -> e.getQualityLevel() == WatchlistEntry.QualityLevel.EXCELLENT).count();
        int good = (int) list.stream().filter(e -> e.getQualityLevel() == WatchlistEntry.QualityLevel.GOOD).count();
        
        double avgMagnitude = list.stream().mapToDouble(WatchlistEntry::getFinalMagnitude).average().orElse(0);
        double maxMagnitude = list.stream().mapToDouble(WatchlistEntry::getFinalMagnitude).max().orElse(0);

        return WatchlistStats.builder()
                .totalEntries(list.size())
                .bullishCount(bullish)
                .bearishCount(bearish)
                .excellentCount(excellent)
                .goodCount(good)
                .averageMagnitude(avgMagnitude)
                .maxMagnitude(maxMagnitude)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * Build WatchlistEntry from FinalMagnitude and CorrelationGuardrail
     */
    private WatchlistEntry buildEntry(FinalMagnitude mag, CorrelationGuardrailOutput cg) {
        // Determine quality level
        boolean hasWarning = mag.getFlags() != null && mag.getFlags().isExhaustionWarning();
        boolean indexAligned = mag.getFlags() != null && mag.getFlags().isIndexAligned();
        WatchlistEntry.QualityLevel quality = WatchlistEntry.QualityLevel.fromMagnitude(
                mag.getFinalMagnitude(), indexAligned, hasWarning);

        // Get trade params
        FinalMagnitude.TradeParams tp = mag.getTradeParams();
        double entry = tp != null ? tp.getEntryPrice() : 0;
        double stop = tp != null ? tp.getStopLoss() : 0;
        double t1 = tp != null ? tp.getTarget1() : 0;
        double t2 = tp != null ? tp.getTarget2() : 0;
        double rr = tp != null ? tp.getRiskRewardRatio() : 0;
        double posPct = tp != null ? tp.getSuggestedPositionPct() : 1;

        // Get component scores
        FinalMagnitude.ComponentScores cs = mag.getComponents();
        double ipu = cs != null ? cs.getIpuScore() : 0;
        double vcp = cs != null ? cs.getVcpScore() : 0;
        double regime = cs != null ? cs.getRegimeStrength() : 0;
        double fudkii = cs != null ? cs.getFudkiiStrength() : 0;

        // Get multipliers and penalties
        FinalMagnitude.MultiplierBreakdown mb = mag.getMultipliers();
        double acl = mb != null ? mb.getAclMultiplier() : 1;

        FinalMagnitude.PenaltyBreakdown pb = mag.getPenalties();
        double som = pb != null ? pb.getSomPenalty() : 0;
        double vtd = pb != null ? pb.getVtdPenalty() : 0;

        // Get flags
        FinalMagnitude.QualityFlags qf = mag.getFlags();
        boolean ignition = qf != null && qf.isIgnitionDetected();
        boolean volume = qf != null && qf.isVolumeConfirmed();
        boolean exhaustion = qf != null && qf.isExhaustionWarning();

        return WatchlistEntry.builder()
                .scripCode(mag.getScripCode())
                .companyName(mag.getCompanyName())
                .sector(cg != null ? cg.getSector() : "UNKNOWN")
                .timestamp(System.currentTimeMillis())
                .rank(0)  // Will be set later
                .finalMagnitude(mag.getFinalMagnitude())
                .direction(mag.getDirection().name())
                .directionConfidence(mag.getDirectionConfidence())
                .signalType(mag.getSignalType())
                .entryPrice(entry)
                .stopLoss(stop)
                .target1(t1)
                .target2(t2)
                .riskRewardRatio(rr)
                .suggestedPositionPct(posPct)
                .positionMultiplier(cg != null ? cg.getPositionSizeMultiplier() : 1.0)
                .qualityLevel(quality)
                .indexAligned(indexAligned)
                .ignitionDetected(ignition)
                .volumeConfirmed(volume)
                .exhaustionWarning(exhaustion)
                .ipuScore(ipu)
                .vcpScore(vcp)
                .regimeStrength(regime)
                .fudkiiStrength(fudkii)
                .aclMultiplier(acl)
                .somPenalty(som)
                .vtdPenalty(vtd)
                .guardrailAction(cg != null ? cg.getAction().name() : "ALLOW")
                .guardrailReason(cg != null ? cg.getReason() : "")
                .build();
    }

    /**
     * Watchlist statistics summary
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class WatchlistStats {
        private int totalEntries;
        private int bullishCount;
        private int bearishCount;
        private int excellentCount;
        private int goodCount;
        private double averageMagnitude;
        private double maxMagnitude;
        private long timestamp;
    }
}
