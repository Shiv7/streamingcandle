package com.kotsin.consumer.enrichment.intelligence;

import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.intelligence.forecaster.OpportunityForecaster;
import com.kotsin.consumer.enrichment.intelligence.model.ActiveSetup;
import com.kotsin.consumer.enrichment.intelligence.model.OpportunityForecast;
import com.kotsin.consumer.enrichment.intelligence.narrative.NarrativeGenerator;
import com.kotsin.consumer.enrichment.intelligence.narrative.NarrativeGenerator.MarketNarrative;
import com.kotsin.consumer.enrichment.intelligence.tracker.SetupTracker;
import com.kotsin.consumer.enrichment.model.DetectedEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IntelligenceOrchestrator - Coordinates Phase 5 Intelligence components
 *
 * Integrates:
 * 1. SetupTracker - Tracks trading setup progress
 * 2. NarrativeGenerator - Creates market stories
 * 3. OpportunityForecaster - Predicts opportunities
 *
 * Provides a unified interface for:
 * - Processing enriched market data
 * - Generating complete market intelligence
 * - Tracking setup lifecycle
 * - Forecasting opportunities
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntelligenceOrchestrator {

    private final SetupTracker setupTracker;
    private final NarrativeGenerator narrativeGenerator;
    private final OpportunityForecaster opportunityForecaster;

    /**
     * Cache of recent intelligence by family
     */
    private final ConcurrentHashMap<String, MarketIntelligence> intelligenceCache = new ConcurrentHashMap<>();

    /**
     * Statistics tracking
     */
    private final IntelligenceStats stats = new IntelligenceStats();

    // ======================== MAIN PROCESSING ========================

    /**
     * Process enriched quant score and generate complete market intelligence
     *
     * @param familyId   Family identifier
     * @param quantScore Enriched quant score from Phase 1-4
     * @param events     Recent detected events
     * @return Complete market intelligence
     */
    public MarketIntelligence processIntelligence(String familyId, EnrichedQuantScore quantScore,
                                                    List<DetectedEvent> events) {
        if (quantScore == null) {
            return MarketIntelligence.empty(familyId);
        }

        long startTime = System.currentTimeMillis();

        try {
            // 1. Evaluate setups
            List<ActiveSetup> readySetups = setupTracker.evaluateSetups(familyId, quantScore, events);

            // 2. Generate narrative
            MarketNarrative narrative = narrativeGenerator.generateNarrative(familyId, quantScore, events);

            // 3. Generate forecast
            OpportunityForecast forecast = opportunityForecaster.generateForecast(familyId, quantScore, events);

            // 4. Build complete intelligence
            MarketIntelligence intelligence = buildIntelligence(
                    familyId, quantScore, readySetups, narrative, forecast);

            // 5. Cache result
            intelligenceCache.put(familyId, intelligence);

            // 6. Update stats
            long processingTime = System.currentTimeMillis() - startTime;
            stats.recordProcessing(processingTime, readySetups.size(), forecast.getPredictions().size());

            log.debug("[INTEL] Processed {} in {}ms: {} ready setups, {} predictions, posture={}",
                    familyId, processingTime, readySetups.size(),
                    forecast.getPredictions().size(), narrative.getPosture());

            return intelligence;

        } catch (Exception e) {
            log.error("[INTEL] Error processing intelligence for {}: {}", familyId, e.getMessage(), e);
            stats.recordError();
            return MarketIntelligence.empty(familyId);
        }
    }

    /**
     * Build complete market intelligence
     */
    private MarketIntelligence buildIntelligence(String familyId, EnrichedQuantScore quantScore,
                                                   List<ActiveSetup> readySetups,
                                                   MarketNarrative narrative,
                                                   OpportunityForecast forecast) {
        // Determine overall action recommendation
        ActionRecommendation recommendation = determineRecommendation(
                quantScore, readySetups, narrative, forecast);

        // Calculate confidence
        double overallConfidence = calculateOverallConfidence(quantScore, narrative, forecast);

        return MarketIntelligence.builder()
                .familyId(familyId)
                .generatedAt(Instant.now())
                .quantScore(quantScore)
                // Phase 5 outputs
                .narrative(narrative)
                .forecast(forecast)
                .readySetups(readySetups)
                // Summary
                .headline(narrative.getHeadline())
                .oneLiner(narrative.getOneLiner())
                .posture(narrative.getPosture())
                .controlSide(narrative.getControlSide())
                // Recommendations
                .recommendation(recommendation)
                .overallConfidence(overallConfidence)
                // Flags
                .isActionableMoment(quantScore.isActionableMoment())
                .hasReadySetups(!readySetups.isEmpty())
                .hasHighConfidencePredictions(!forecast.getHighConfidencePredictions().isEmpty())
                .build();
    }

    /**
     * Determine action recommendation
     */
    private ActionRecommendation determineRecommendation(EnrichedQuantScore quantScore,
                                                          List<ActiveSetup> readySetups,
                                                          MarketNarrative narrative,
                                                          OpportunityForecast forecast) {
        // Check for ready setups
        if (!readySetups.isEmpty()) {
            ActiveSetup bestSetup = readySetups.stream()
                    .max((a, b) -> Double.compare(a.getCurrentConfidence(), b.getCurrentConfidence()))
                    .orElse(null);

            if (bestSetup != null && bestSetup.getCurrentConfidence() >= 0.65) {
                String direction = bestSetup.getDirection().name();
                return ActionRecommendation.builder()
                        .action(ActionType.TRADE)
                        .direction(direction)
                        .confidence(bestSetup.getCurrentConfidence())
                        .setupId(bestSetup.getSetupId())
                        .rationale(String.format("%s setup ready: %s", bestSetup.getSetupId(),
                                bestSetup.getStateDescription()))
                        .suggestedEntry(bestSetup.getCurrentPrice())
                        .suggestedStop(bestSetup.getStopLossPrice())
                        .suggestedTarget(bestSetup.getTargetPrice())
                        .positionSize(forecast.getSuggestedPositionSize())
                        .build();
            }
        }

        // Check for actionable moment
        if (quantScore.isActionableMoment()) {
            String direction = forecast.getDominantDirection();
            return ActionRecommendation.builder()
                    .action(ActionType.PREPARE)
                    .direction(direction)
                    .confidence(forecast.getOverallConfidence())
                    .rationale("Actionable moment detected: " + quantScore.getActionableMomentReason())
                    .positionSize(forecast.getSuggestedPositionSize())
                    .build();
        }

        // Check forecast opportunities
        if (forecast.hasActionableOpportunities()) {
            String direction = forecast.getDominantDirection();
            double score = "LONG".equals(direction) ?
                    forecast.getLongOpportunityScore() : forecast.getShortOpportunityScore();

            return ActionRecommendation.builder()
                    .action(ActionType.WATCH)
                    .direction(direction)
                    .confidence(forecast.getOverallConfidence())
                    .rationale(forecast.getBestOpportunityDescription())
                    .positionSize(forecast.getSuggestedPositionSize())
                    .build();
        }

        // Check risk level
        if (forecast.getRiskLevel() == OpportunityForecast.RiskLevel.VERY_HIGH ||
            forecast.getRiskLevel() == OpportunityForecast.RiskLevel.EXTREME) {
            return ActionRecommendation.builder()
                    .action(ActionType.AVOID)
                    .direction("NONE")
                    .confidence(0.0)
                    .rationale("High risk conditions: " + String.join(", ", forecast.getKeyRisks()))
                    .build();
        }

        // Default: Wait
        return ActionRecommendation.builder()
                .action(ActionType.WAIT)
                .direction("NONE")
                .confidence(forecast.getOverallConfidence())
                .rationale("No clear opportunities - waiting for better setup")
                .build();
    }

    /**
     * Calculate overall confidence
     */
    private double calculateOverallConfidence(EnrichedQuantScore quantScore,
                                               MarketNarrative narrative,
                                               OpportunityForecast forecast) {
        // Weighted average of confidences
        double quantConfidence = quantScore.getAdjustedConfidence();
        double forecastConfidence = forecast.getOverallConfidence();

        // More weight to quant score as it's based on actual data
        return quantConfidence * 0.6 + forecastConfidence * 0.4;
    }

    // ======================== QUERY METHODS ========================

    /**
     * Get cached intelligence for family
     */
    public MarketIntelligence getCachedIntelligence(String familyId) {
        return intelligenceCache.get(familyId);
    }

    /**
     * Get ready setups for family
     */
    public List<ActiveSetup> getReadySetups(String familyId) {
        return setupTracker.getReadySetups(familyId);
    }

    /**
     * Get cached forecast for family
     */
    public OpportunityForecast getCachedForecast(String familyId) {
        return opportunityForecaster.getCachedForecast(familyId).orElse(null);
    }

    /**
     * Get setup tracker stats
     */
    public SetupTracker.TrackerStats getSetupStats() {
        return setupTracker.getStats();
    }

    /**
     * Get intelligence processing stats
     */
    public IntelligenceStats getStats() {
        return stats;
    }

    // ======================== LIFECYCLE ========================

    /**
     * Periodic cleanup of expired data
     */
    @Scheduled(fixedRate = 60000) // Every minute
    public void cleanup() {
        long startTime = System.currentTimeMillis();

        setupTracker.cleanup();
        opportunityForecaster.cleanup();

        // Clear stale intelligence cache entries (older than 5 minutes)
        Instant cutoff = Instant.now().minusSeconds(300);
        intelligenceCache.entrySet().removeIf(entry ->
                entry.getValue().getGeneratedAt().isBefore(cutoff));

        long cleanupTime = System.currentTimeMillis() - startTime;
        log.debug("[INTEL] Cleanup completed in {}ms, cache size: {}",
                cleanupTime, intelligenceCache.size());
    }

    /**
     * Mark a setup as triggered (trade taken)
     */
    public void markSetupTriggered(String activeSetupId, double entry, double stop, double target) {
        setupTracker.markTriggered(activeSetupId, entry, stop, target);
    }

    /**
     * Clear all data for a family
     */
    public void clearFamily(String familyId) {
        setupTracker.clearFamily(familyId);
        intelligenceCache.remove(familyId);
    }

    // ======================== MODELS ========================

    @Data
    @Builder
    @AllArgsConstructor
    public static class MarketIntelligence {
        private String familyId;
        private Instant generatedAt;
        private EnrichedQuantScore quantScore;

        // Phase 5 outputs
        private MarketNarrative narrative;
        private OpportunityForecast forecast;
        private List<ActiveSetup> readySetups;

        // Summary
        private String headline;
        private String oneLiner;
        private NarrativeGenerator.MarketPosture posture;
        private NarrativeGenerator.ControlSide controlSide;

        // Recommendations
        private ActionRecommendation recommendation;
        private double overallConfidence;

        // Flags
        private boolean isActionableMoment;
        private boolean hasReadySetups;
        private boolean hasHighConfidencePredictions;

        public static MarketIntelligence empty(String familyId) {
            return MarketIntelligence.builder()
                    .familyId(familyId)
                    .generatedAt(Instant.now())
                    .headline("No data available")
                    .oneLiner("Waiting for market data")
                    .posture(NarrativeGenerator.MarketPosture.NEUTRAL)
                    .controlSide(NarrativeGenerator.ControlSide.BALANCED)
                    .recommendation(ActionRecommendation.builder()
                            .action(ActionType.WAIT)
                            .direction("NONE")
                            .confidence(0.0)
                            .rationale("No data available")
                            .build())
                    .overallConfidence(0.0)
                    .isActionableMoment(false)
                    .hasReadySetups(false)
                    .hasHighConfidencePredictions(false)
                    .build();
        }

        @Override
        public String toString() {
            return String.format("Intelligence[%s]: %s | %s | %s (%.0f%% confidence)",
                    familyId, headline, posture, recommendation.getAction(),
                    overallConfidence * 100);
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class ActionRecommendation {
        private ActionType action;
        private String direction;
        private double confidence;
        private String setupId;
        private String rationale;
        private Double suggestedEntry;
        private Double suggestedStop;
        private Double suggestedTarget;
        private double positionSize;

        @Override
        public String toString() {
            return String.format("%s %s: %s (%.0f%% conf, %.0f%% size)",
                    action, direction, rationale, confidence * 100, positionSize * 100);
        }
    }

    public enum ActionType {
        TRADE,      // Take trade now
        PREPARE,    // Prepare for imminent trade
        WATCH,      // Watch for entry
        WAIT,       // Wait for better opportunity
        AVOID       // Avoid trading
    }

    @Data
    public static class IntelligenceStats {
        private long totalProcessed = 0;
        private long totalErrors = 0;
        private long totalSetupsReady = 0;
        private long totalPredictions = 0;
        private long totalProcessingTimeMs = 0;
        private double avgProcessingTimeMs = 0;

        public synchronized void recordProcessing(long timeMs, int setupsReady, int predictions) {
            totalProcessed++;
            totalProcessingTimeMs += timeMs;
            totalSetupsReady += setupsReady;
            totalPredictions += predictions;
            avgProcessingTimeMs = (double) totalProcessingTimeMs / totalProcessed;
        }

        public synchronized void recordError() {
            totalErrors++;
        }

        @Override
        public String toString() {
            return String.format("IntelligenceStats: %d processed, %d errors, %d setups ready, " +
                            "%d predictions, avg %.1fms",
                    totalProcessed, totalErrors, totalSetupsReady, totalPredictions, avgProcessingTimeMs);
        }
    }
}
