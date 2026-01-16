package com.kotsin.consumer.enrichment;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.intelligence.IntelligenceOrchestrator;
import com.kotsin.consumer.enrichment.intelligence.IntelligenceOrchestrator.MarketIntelligence;
import com.kotsin.consumer.enrichment.model.DetectedEvent;
import com.kotsin.consumer.enrichment.signal.SignalGenerator;
import com.kotsin.consumer.enrichment.signal.TradingSignalPublisher;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import com.kotsin.consumer.enrichment.store.EventStore;
import com.kotsin.consumer.config.KafkaTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * EnrichmentPipeline - Complete enrichment pipeline integrating all phases
 *
 * Pipeline stages:
 * Phase 1: Historical Context (adaptive learning, regime detection)
 * Phase 2: Options Intelligence (GEX, Max Pain, Time/Expiry context)
 * Phase 3: Signal Intelligence (Technical, Confluence, Events)
 * Phase 4: Pattern Recognition (Sequence matching, Predictive patterns)
 * Phase 5: Intelligence (Narrative, Forecasting, Setup Tracking)
 * Phase 6: Signal Generation (Enhanced trading signals with context)
 *
 * This service provides a unified interface for complete market analysis.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnrichmentPipeline {

    private final EnrichedQuantScoreCalculator quantScoreCalculator;
    private final IntelligenceOrchestrator intelligenceOrchestrator;
    private final EventStore eventStore;
    private final SignalGenerator signalGenerator;
    private final TradingSignalPublisher signalPublisher;

    // Kafka publishers for intelligence outputs
    @Autowired(required = false)
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    // Statistics
    private long totalProcessed = 0;
    private long totalProcessingTimeMs = 0;
    private long phase1to4TimeMs = 0;
    private long phase5TimeMs = 0;
    private long phase6TimeMs = 0;
    private long totalSignalsGenerated = 0;
    private long totalSignalsPublished = 0;

    // TEST MODE: Allow all timeframes including 1m for testing/debugging
    // In production, 1m signals are noise - keep only 5m+ for live trading
    private static final java.util.Set<String> SIGNAL_ALLOWED_TIMEFRAMES = java.util.Set.of(
            "1m", "2m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "1d"
    );

    /**
     * Process a FamilyCandle through the complete enrichment pipeline
     *
     * @param family FamilyCandle to process
     * @return Complete pipeline result including all phases
     */
    public PipelineResult process(FamilyCandle family) {
        if (family == null || family.getFamilyId() == null) {
            return PipelineResult.empty("UNKNOWN");
        }

        String familyId = family.getFamilyId();
        String timeframe = family.getTimeframe() != null ? family.getTimeframe() : "1m";
        boolean signalGenerationAllowed = SIGNAL_ALLOWED_TIMEFRAMES.contains(timeframe);

        long startTime = System.currentTimeMillis();
        long phase1to4Start = startTime;

        try {
            // =============== Phase 1-4: Enriched Quant Score ===============
            EnrichedQuantScore enrichedScore = quantScoreCalculator.calculate(family);
            long phase1to4End = System.currentTimeMillis();
            long phase1to4Duration = phase1to4End - phase1to4Start;

            // Get recent events for context
            List<DetectedEvent> recentEvents = getRecentEvents(familyId);

            // =============== Phase 5: Intelligence ===============
            long phase5Start = System.currentTimeMillis();
            MarketIntelligence intelligence = intelligenceOrchestrator.processIntelligence(
                    familyId, enrichedScore, recentEvents);
            long phase5End = System.currentTimeMillis();
            long phase5Duration = phase5End - phase5Start;

            // =============== Phase 6: Signal Generation ===============
            // Only generate signals for 5m+ timeframes - 1m is pure noise
            long phase6Start = System.currentTimeMillis();
            List<TradingSignal> signals;
            int publishedCount = 0;

            if (signalGenerationAllowed) {
                signals = signalGenerator.generateSignals(familyId, enrichedScore, intelligence);
                // Publish signals if any were generated
                if (!signals.isEmpty()) {
                    publishedCount = signalPublisher.publishSignals(signals);
                }
            } else {
                // Skip signal generation for 1m timeframe - just enrichment/learning
                signals = Collections.emptyList();
                log.debug("[PIPELINE] Skipping signal generation for {} - timeframe {} not allowed (need 5m+)",
                        familyId, timeframe);
            }

            long phase6End = System.currentTimeMillis();
            long phase6Duration = phase6End - phase6Start;

            // Publish intelligence outputs to Kafka for dashboard
            publishIntelligenceOutputs(familyId, intelligence);

            // =============== Build Result ===============
            long totalTime = System.currentTimeMillis() - startTime;

            // Update stats
            synchronized (this) {
                totalProcessed++;
                totalProcessingTimeMs += totalTime;
                this.phase1to4TimeMs += phase1to4Duration;
                this.phase5TimeMs += phase5Duration;
                this.phase6TimeMs += phase6Duration;
                this.totalSignalsGenerated += signals.size();
                this.totalSignalsPublished += publishedCount;
            }

            log.debug("[PIPELINE] Processed {} in {}ms (Phase1-4: {}ms, Phase5: {}ms, Phase6: {}ms, signals: {})",
                    familyId, totalTime, phase1to4Duration, phase5Duration, phase6Duration, signals.size());

            return PipelineResult.builder()
                    .familyId(familyId)
                    .processedAt(Instant.now())
                    .success(true)
                    // Phase 1-4 output
                    .enrichedScore(enrichedScore)
                    // Phase 5 output
                    .intelligence(intelligence)
                    // Phase 6 output
                    .generatedSignals(signals)
                    .signalsPublished(publishedCount)
                    // Summary
                    .headline(intelligence.getHeadline())
                    .recommendation(intelligence.getRecommendation())
                    .isActionableMoment(enrichedScore.isActionableMoment())
                    .hasReadySetups(intelligence.isHasReadySetups())
                    .hasSignals(!signals.isEmpty())
                    // Timing
                    .phase1to4DurationMs(phase1to4Duration)
                    .phase5DurationMs(phase5Duration)
                    .phase6DurationMs(phase6Duration)
                    .totalDurationMs(totalTime)
                    .build();

        } catch (Exception e) {
            log.error("[PIPELINE] Error processing {}: {}", familyId, e.getMessage(), e);

            return PipelineResult.builder()
                    .familyId(familyId)
                    .processedAt(Instant.now())
                    .success(false)
                    .errorMessage(e.getMessage())
                    .totalDurationMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * Get recent events for a family
     */
    private List<DetectedEvent> getRecentEvents(String familyId) {
        try {
            return eventStore.getRecentEvents(familyId, 30); // Last 30 minutes
        } catch (Exception e) {
            log.warn("[PIPELINE] Error getting recent events for {}: {}", familyId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get pipeline statistics
     */
    public PipelineStats getStats() {
        synchronized (this) {
            double avgTotal = totalProcessed > 0 ? (double) totalProcessingTimeMs / totalProcessed : 0;
            double avgPhase1to4 = totalProcessed > 0 ? (double) phase1to4TimeMs / totalProcessed : 0;
            double avgPhase5 = totalProcessed > 0 ? (double) phase5TimeMs / totalProcessed : 0;
            double avgPhase6 = totalProcessed > 0 ? (double) phase6TimeMs / totalProcessed : 0;

            return PipelineStats.builder()
                    .totalProcessed(totalProcessed)
                    .avgTotalDurationMs(avgTotal)
                    .avgPhase1to4DurationMs(avgPhase1to4)
                    .avgPhase5DurationMs(avgPhase5)
                    .avgPhase6DurationMs(avgPhase6)
                    .totalSignalsGenerated(totalSignalsGenerated)
                    .totalSignalsPublished(totalSignalsPublished)
                    .setupStats(intelligenceOrchestrator.getSetupStats())
                    .intelligenceStats(intelligenceOrchestrator.getStats())
                    .signalGeneratorStats(signalGenerator.getStats())
                    .signalPublisherStats(signalPublisher.getStats())
                    .build();
        }
    }

    /**
     * Get cached signals for a family
     */
    public List<TradingSignal> getCachedSignals(String familyId) {
        return signalGenerator.getCachedSignals(familyId);
    }

    /**
     * Get cached intelligence for family (for quick lookups)
     */
    public MarketIntelligence getCachedIntelligence(String familyId) {
        return intelligenceOrchestrator.getCachedIntelligence(familyId);
    }

    /**
     * Mark a setup as triggered
     */
    public void markSetupTriggered(String activeSetupId, double entry, double stop, double target) {
        intelligenceOrchestrator.markSetupTriggered(activeSetupId, entry, stop, target);
    }

    /**
     * Clear data for a family
     */
    public void clearFamily(String familyId) {
        intelligenceOrchestrator.clearFamily(familyId);
        eventStore.clearFamily(familyId);
        // Signal cache is auto-cleaned, but we can trigger clearing for a specific family
        // if we add that method to SignalGenerator
    }

    /**
     * Publish intelligence outputs to Kafka for dashboard consumption
     */
    private void publishIntelligenceOutputs(String familyId, MarketIntelligence intelligence) {
        if (kafkaTemplate == null || objectMapper == null || intelligence == null) {
            return;
        }

        try {
            // Publish market narrative
            if (intelligence.getNarrative() != null) {
                String narrativeJson = objectMapper.writeValueAsString(intelligence.getNarrative());
                kafkaTemplate.send(KafkaTopics.MARKET_NARRATIVE, familyId, narrativeJson);
                log.debug("[PIPELINE] Published narrative for {} | headline: {}",
                    familyId, intelligence.getHeadline());
            }

            // Publish full market intelligence
            String intelligenceJson = objectMapper.writeValueAsString(intelligence);
            kafkaTemplate.send(KafkaTopics.MARKET_INTELLIGENCE, familyId, intelligenceJson);

            // Publish active setups if any
            if (intelligence.getReadySetups() != null && !intelligence.getReadySetups().isEmpty()) {
                String setupsJson = objectMapper.writeValueAsString(intelligence.getReadySetups());
                kafkaTemplate.send(KafkaTopics.ACTIVE_SETUPS, familyId, setupsJson);
                log.info("[PIPELINE] Published {} ready setups for {}",
                    intelligence.getReadySetups().size(), familyId);
            }

            // Publish opportunity forecast if available
            if (intelligence.getForecast() != null) {
                String forecastJson = objectMapper.writeValueAsString(intelligence.getForecast());
                kafkaTemplate.send(KafkaTopics.OPPORTUNITY_FORECAST, familyId, forecastJson);
            }

        } catch (Exception e) {
            log.warn("[PIPELINE] Failed to publish intelligence for {}: {}", familyId, e.getMessage());
        }
    }

    // ======================== RESULT MODELS ========================

    @Data
    @Builder
    @AllArgsConstructor
    public static class PipelineResult {
        private String familyId;
        private Instant processedAt;
        private boolean success;
        private String errorMessage;

        // Phase 1-4 output
        private EnrichedQuantScore enrichedScore;

        // Phase 5 output
        private MarketIntelligence intelligence;

        // Phase 6 output
        @Builder.Default
        private List<TradingSignal> generatedSignals = new ArrayList<>();
        private int signalsPublished;

        // Summary
        private String headline;
        private IntelligenceOrchestrator.ActionRecommendation recommendation;
        private boolean isActionableMoment;
        private boolean hasReadySetups;
        private boolean hasSignals;

        // Timing
        private long phase1to4DurationMs;
        private long phase5DurationMs;
        private long phase6DurationMs;
        private long totalDurationMs;

        public static PipelineResult empty(String familyId) {
            return PipelineResult.builder()
                    .familyId(familyId)
                    .processedAt(Instant.now())
                    .success(false)
                    .errorMessage("No data provided")
                    .headline("No data available")
                    .isActionableMoment(false)
                    .hasReadySetups(false)
                    .hasSignals(false)
                    .generatedSignals(Collections.emptyList())
                    .build();
        }

        /**
         * Get the best signal by quality score
         */
        public TradingSignal getBestSignal() {
            if (generatedSignals == null || generatedSignals.isEmpty()) return null;
            return generatedSignals.stream()
                    .max((a, b) -> Integer.compare(a.getQualityScore(), b.getQualityScore()))
                    .orElse(null);
        }

        /**
         * Get high-quality signals
         */
        public List<TradingSignal> getHighQualitySignals() {
            if (generatedSignals == null) return Collections.emptyList();
            return generatedSignals.stream()
                    .filter(TradingSignal::isHighQuality)
                    .toList();
        }

        /**
         * Get the adjusted quant score
         */
        public double getAdjustedQuantScore() {
            return enrichedScore != null ? enrichedScore.getAdjustedQuantScore() : 0;
        }

        /**
         * Get the adjusted confidence
         */
        public double getAdjustedConfidence() {
            return enrichedScore != null ? enrichedScore.getAdjustedConfidence() : 0;
        }

        /**
         * Get the full narrative
         */
        public String getFullNarrative() {
            return intelligence != null && intelligence.getNarrative() != null
                    ? intelligence.getNarrative().getFullNarrative()
                    : "No narrative available";
        }

        /**
         * Get action type recommendation
         */
        public IntelligenceOrchestrator.ActionType getActionType() {
            return recommendation != null
                    ? recommendation.getAction()
                    : IntelligenceOrchestrator.ActionType.WAIT;
        }

        @Override
        public String toString() {
            if (!success) {
                return String.format("PipelineResult[%s] FAILED: %s", familyId, errorMessage);
            }
            return String.format("PipelineResult[%s] %s | Score:%.1f Conf:%.0f%% | %s | Signals:%d | %dms",
                    familyId, headline,
                    getAdjustedQuantScore(), getAdjustedConfidence() * 100,
                    getActionType(), generatedSignals != null ? generatedSignals.size() : 0,
                    totalDurationMs);
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class PipelineStats {
        private long totalProcessed;
        private double avgTotalDurationMs;
        private double avgPhase1to4DurationMs;
        private double avgPhase5DurationMs;
        private double avgPhase6DurationMs;
        private long totalSignalsGenerated;
        private long totalSignalsPublished;
        private com.kotsin.consumer.enrichment.intelligence.tracker.SetupTracker.TrackerStats setupStats;
        private IntelligenceOrchestrator.IntelligenceStats intelligenceStats;
        private SignalGenerator.GeneratorStats signalGeneratorStats;
        private TradingSignalPublisher.PublisherStats signalPublisherStats;

        @Override
        public String toString() {
            return String.format(
                    "PipelineStats: %d processed, avg %.1fms (P1-4: %.1fms, P5: %.1fms, P6: %.1fms)\n" +
                    "Signals: %d generated, %d published\n%s\n%s\n%s\n%s",
                    totalProcessed, avgTotalDurationMs, avgPhase1to4DurationMs, avgPhase5DurationMs, avgPhase6DurationMs,
                    totalSignalsGenerated, totalSignalsPublished,
                    setupStats, intelligenceStats, signalGeneratorStats, signalPublisherStats);
        }
    }
}
