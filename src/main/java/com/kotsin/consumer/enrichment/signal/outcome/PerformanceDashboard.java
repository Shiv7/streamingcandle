package com.kotsin.consumer.enrichment.signal.outcome;

import com.kotsin.consumer.enrichment.EnrichmentPipeline;
import com.kotsin.consumer.enrichment.pattern.store.PatternOutcomeStore;
import com.kotsin.consumer.enrichment.signal.SignalGenerator;
import com.kotsin.consumer.enrichment.signal.TradingSignalPublisher;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * PerformanceDashboard - Exposes performance metrics via REST API
 *
 * Provides:
 * - Overall system health metrics
 * - Signal generation statistics
 * - Outcome tracking statistics
 * - Performance analysis results
 * - Real-time status
 */
@Slf4j
@Component
@RestController
@RequestMapping("/api/v2/performance")
@RequiredArgsConstructor
public class PerformanceDashboard {

    private final SignalOutcomeStore outcomeStore;
    private final SignalPerformanceAnalyzer performanceAnalyzer;
    private final SignalOutcomeConsumer outcomeConsumer;
    private final PatternOutcomeStore patternOutcomeStore;
    private final EnrichmentPipeline enrichmentPipeline;
    private final SignalGenerator signalGenerator;
    private final TradingSignalPublisher signalPublisher;

    // Cached dashboard data
    private volatile DashboardData cachedDashboard;
    private volatile Instant lastRefresh;

    // ======================== REST ENDPOINTS ========================

    /**
     * Get complete dashboard data
     */
    @GetMapping("/dashboard")
    public DashboardData getDashboard() {
        refreshIfNeeded();
        return cachedDashboard != null ? cachedDashboard : buildDashboard();
    }

    /**
     * Get overall performance summary
     */
    @GetMapping("/overall")
    public SignalOutcomeStore.OverallPerformance getOverallPerformance() {
        return outcomeStore.getOverallPerformance();
    }

    /**
     * Get performance by source
     */
    @GetMapping("/by-source/{source}")
    public SignalOutcomeStore.SourcePerformance getSourcePerformance(@PathVariable String source) {
        return outcomeStore.getSourcePerformance(source);
    }

    /**
     * Get performance by family
     */
    @GetMapping("/by-family/{familyId}")
    public SignalOutcomeStore.FamilyPerformance getFamilyPerformance(@PathVariable String familyId) {
        return outcomeStore.getFamilyPerformance(familyId);
    }

    /**
     * Get performance by category
     */
    @GetMapping("/by-category")
    public Map<String, SignalOutcomeStore.CategoryPerformance> getPerformanceByCategory() {
        return outcomeStore.getPerformanceByCategory();
    }

    /**
     * Get performance by session
     */
    @GetMapping("/by-session")
    public Map<String, SignalPerformanceAnalyzer.SessionPerformance> getPerformanceBySession(
            @RequestParam(defaultValue = "30") int days) {
        return performanceAnalyzer.analyzeBySession(days);
    }

    /**
     * Get performance by day of week
     */
    @GetMapping("/by-day")
    public Map<String, SignalPerformanceAnalyzer.DayPerformance> getPerformanceByDay(
            @RequestParam(defaultValue = "30") int days) {
        return performanceAnalyzer.analyzeByDayOfWeek(days);
    }

    /**
     * Get confidence calibration analysis
     */
    @GetMapping("/confidence-calibration")
    public SignalPerformanceAnalyzer.ConfidenceCalibration getConfidenceCalibration(
            @RequestParam(defaultValue = "30") int days) {
        return performanceAnalyzer.analyzeConfidenceCalibration(days);
    }

    /**
     * Get edge decay analysis
     */
    @GetMapping("/edge-decay")
    public SignalPerformanceAnalyzer.EdgeDecayAnalysis getEdgeDecay(
            @RequestParam(defaultValue = "30") int days) {
        return performanceAnalyzer.analyzeEdgeDecay(days);
    }

    /**
     * Get target optimization analysis
     */
    @GetMapping("/target-optimization")
    public SignalPerformanceAnalyzer.TargetOptimization getTargetOptimization(
            @RequestParam(defaultValue = "30") int days) {
        return performanceAnalyzer.analyzeTargetOptimization(days);
    }

    /**
     * Get expiry impact analysis
     */
    @GetMapping("/expiry-impact")
    public SignalPerformanceAnalyzer.ExpiryAnalysis getExpiryImpact(
            @RequestParam(defaultValue = "30") int days) {
        return performanceAnalyzer.analyzeExpiryImpact(days);
    }

    /**
     * Get pattern performance summary
     */
    @GetMapping("/patterns")
    public PatternOutcomeStore.PerformanceSummary getPatternPerformance() {
        return patternOutcomeStore.getPerformanceSummary();
    }

    /**
     * Get pipeline statistics
     */
    @GetMapping("/pipeline")
    public EnrichmentPipeline.PipelineStats getPipelineStats() {
        return enrichmentPipeline.getStats();
    }

    /**
     * Get signal generator statistics
     */
    @GetMapping("/signal-generator")
    public SignalGenerator.GeneratorStats getSignalGeneratorStats() {
        return signalGenerator.getStats();
    }

    /**
     * Get signal publisher statistics
     */
    @GetMapping("/signal-publisher")
    public TradingSignalPublisher.PublisherStats getSignalPublisherStats() {
        return signalPublisher.getStats();
    }

    /**
     * Get outcome consumer statistics
     */
    @GetMapping("/outcome-consumer")
    public SignalOutcomeConsumer.ConsumerStats getOutcomeConsumerStats() {
        return outcomeConsumer.getStats();
    }

    /**
     * Get outcome store statistics
     */
    @GetMapping("/outcome-store")
    public SignalOutcomeStore.StoreStats getOutcomeStoreStats() {
        return outcomeStore.getStats();
    }

    /**
     * Get system health status
     */
    @GetMapping("/health")
    public HealthStatus getHealthStatus() {
        return buildHealthStatus();
    }

    /**
     * Force dashboard refresh
     */
    @PostMapping("/refresh")
    public DashboardData forceRefresh() {
        cachedDashboard = buildDashboard();
        lastRefresh = Instant.now();
        return cachedDashboard;
    }

    // ======================== DASHBOARD BUILDING ========================

    private DashboardData buildDashboard() {
        SignalOutcomeStore.OverallPerformance overall = outcomeStore.getOverallPerformance();
        EnrichmentPipeline.PipelineStats pipeline = enrichmentPipeline.getStats();
        SignalGenerator.GeneratorStats generator = signalGenerator.getStats();
        TradingSignalPublisher.PublisherStats publisher = signalPublisher.getStats();
        PatternOutcomeStore.PerformanceSummary patterns = patternOutcomeStore.getPerformanceSummary();
        SignalPerformanceAnalyzer.ConfidenceCalibration calibration = performanceAnalyzer.analyzeConfidenceCalibration(30);

        return DashboardData.builder()
                // Overall metrics
                .totalSignalsGenerated(generator.getTotalSignalsGenerated())
                .totalSignalsPublished(publisher.getTotalPublished())
                .totalTradesRecorded(overall.getTotalTrades())
                .overallWinRate(overall.getWinRate())
                .totalPnlPct(overall.getTotalPnlPct())
                .avgRMultiple(overall.getAvgRMultiple())
                .profitFactor(overall.getProfitFactor())
                // Signal source breakdown
                .patternSignals(generator.getPatternSignals())
                .setupSignals(generator.getSetupSignals())
                .forecastSignals(generator.getForecastSignals())
                // Target metrics
                .target1HitRate(overall.getTarget1HitRate())
                .target2HitRate(overall.getTarget2HitRate())
                // Pattern metrics
                .patternsTracked(patterns.patternsTracked())
                .patternTotalTrades(patterns.totalTrades())
                .patternWinRate(patterns.overallWinRate())
                // Calibration
                .isWellCalibrated(calibration.isWellCalibrated())
                .avgCalibrationError(calibration.getAvgCalibrationError())
                // Pipeline metrics
                .candlesProcessed(pipeline.getTotalProcessed())
                .avgProcessingTimeMs(pipeline.getAvgTotalDurationMs())
                // Health
                .health(buildHealthStatus())
                // Timestamps
                .calculatedAt(Instant.now())
                .periodDays(30)
                .build();
    }

    private HealthStatus buildHealthStatus() {
        EnrichmentPipeline.PipelineStats pipeline = enrichmentPipeline.getStats();
        TradingSignalPublisher.PublisherStats publisher = signalPublisher.getStats();
        SignalOutcomeStore.OverallPerformance overall = outcomeStore.getOverallPerformance();

        // Determine health level
        HealthLevel level = HealthLevel.HEALTHY;
        StringBuilder issues = new StringBuilder();

        // Check processing latency
        if (pipeline.getAvgTotalDurationMs() > 100) {
            level = HealthLevel.DEGRADED;
            issues.append("High processing latency. ");
        }

        // Check publisher success rate
        if (publisher.getSuccessRate() < 0.95 && publisher.getTotalPublished() > 100) {
            level = HealthLevel.DEGRADED;
            issues.append("Publisher success rate below 95%. ");
        }

        // Check win rate
        if (overall.getTotalTrades() > 50 && overall.getWinRate() < 0.45) {
            level = HealthLevel.WARNING;
            issues.append("Win rate below 45%. ");
        }

        // Check profit factor
        if (overall.getTotalTrades() > 50 && overall.getProfitFactor() < 1.0) {
            level = HealthLevel.WARNING;
            issues.append("Profit factor below 1.0 (losing money). ");
        }

        return HealthStatus.builder()
                .level(level)
                .pipelineHealthy(pipeline.getAvgTotalDurationMs() < 100)
                .publisherHealthy(publisher.getSuccessRate() >= 0.95)
                .performanceHealthy(overall.getWinRate() >= 0.45 && overall.getProfitFactor() >= 1.0)
                .issues(issues.toString().trim())
                .lastCheck(Instant.now())
                .build();
    }

    private void refreshIfNeeded() {
        if (cachedDashboard == null || lastRefresh == null ||
            Instant.now().isAfter(lastRefresh.plusSeconds(60))) {
            cachedDashboard = buildDashboard();
            lastRefresh = Instant.now();
        }
    }

    // ======================== SCHEDULED REFRESH ========================

    @Scheduled(fixedRate = 60_000) // Every minute
    public void scheduledRefresh() {
        log.debug("[DASHBOARD] Refreshing dashboard data...");
        cachedDashboard = buildDashboard();
        lastRefresh = Instant.now();
    }

    // ======================== MODELS ========================

    @Data
    @Builder
    @AllArgsConstructor
    public static class DashboardData {
        // Overall metrics
        private long totalSignalsGenerated;
        private long totalSignalsPublished;
        private int totalTradesRecorded;
        private double overallWinRate;
        private double totalPnlPct;
        private double avgRMultiple;
        private double profitFactor;

        // Signal source breakdown
        private long patternSignals;
        private long setupSignals;
        private long forecastSignals;

        // Target metrics
        private double target1HitRate;
        private double target2HitRate;

        // Pattern metrics
        private int patternsTracked;
        private int patternTotalTrades;
        private double patternWinRate;

        // Calibration
        private boolean isWellCalibrated;
        private double avgCalibrationError;

        // Pipeline metrics
        private long candlesProcessed;
        private double avgProcessingTimeMs;

        // Health
        private HealthStatus health;

        // Timestamps
        private Instant calculatedAt;
        private int periodDays;

        @Override
        public String toString() {
            return String.format(
                    "Dashboard: %d signals, %d trades, %.1f%% win rate, %.2f%% P&L, %.2f profit factor | Health: %s",
                    totalSignalsGenerated, totalTradesRecorded,
                    overallWinRate * 100, totalPnlPct, profitFactor,
                    health != null ? health.getLevel() : "N/A");
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class HealthStatus {
        private HealthLevel level;
        private boolean pipelineHealthy;
        private boolean publisherHealthy;
        private boolean performanceHealthy;
        private String issues;
        private Instant lastCheck;
    }

    public enum HealthLevel {
        HEALTHY,    // All systems good
        WARNING,    // Performance concerns
        DEGRADED,   // Some systems degraded
        CRITICAL    // System failures
    }
}
