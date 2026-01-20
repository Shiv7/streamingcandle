package com.kotsin.consumer.enrichment;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.signal.SignalGenerator;
import com.kotsin.consumer.enrichment.signal.TradingSignalPublisher;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import com.kotsin.consumer.enrichment.store.EventStore;
import com.kotsin.consumer.trading.state.InstrumentStateManager;
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
 * Phase 1-4: Enriched Quant Score (Historical, Options Intelligence, Technical, Events)
 * Phase 5: State Machine Trading (InstrumentStateManager - IDLE → WATCHING → READY → POSITIONED → COOLDOWN)
 *
 * This service provides a unified interface for complete market analysis.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnrichmentPipeline {

    private final EnrichedQuantScoreCalculator quantScoreCalculator;
    private final EventStore eventStore;
    private final SignalGenerator signalGenerator;
    private final TradingSignalPublisher signalPublisher;
    private final InstrumentStateManager instrumentStateManager;

    // Kafka template (available for future use)
    @Autowired(required = false)
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    // Statistics
    private long totalProcessed = 0;
    private long totalProcessingTimeMs = 0;
    private long phase1to4TimeMs = 0;
    private long phase5TimeMs = 0;
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

            // =============== Phase 5: State Machine Trading ===============
            // The InstrumentStateManager is the NEW signal generation system.
            // It maintains state per instrument (IDLE → WATCHING → READY → POSITIONED → COOLDOWN)
            // and generates ONE signal per trade lifecycle (no spam).
            // NOTE: InstrumentStateManager publishes directly to Kafka, we just track here for stats.
            List<TradingSignal> signals = new ArrayList<>();
            int publishedCount = 0;

            if (signalGenerationAllowed) {
                // Process through state machine - returns signal only on state transitions
                // NOTE: State manager publishes to Kafka internally, we don't use signalPublisher here
                java.util.Optional<TradingSignal> stateSignal = instrumentStateManager.processCandle(enrichedScore, timeframe);

                if (stateSignal.isPresent()) {
                    signals = Collections.singletonList(stateSignal.get());
                    publishedCount = 1; // Already published by state manager
                    log.info("[PIPELINE] STATE MACHINE signal generated for {}: {} {} @ {}",
                            familyId, stateSignal.get().getDirection(), stateSignal.get().getSetupId(),
                            stateSignal.get().getEntryPrice());
                }
            } else {
                // Skip signal generation for 1m timeframe - just enrichment/learning
                log.debug("[PIPELINE] Skipping signal generation for {} - timeframe {} not allowed (need 5m+)",
                        familyId, timeframe);
            }

            long phase5End = System.currentTimeMillis();
            long phase5Duration = phase5End - phase1to4End;

            // =============== Build Result ===============
            long totalTime = System.currentTimeMillis() - startTime;

            // Update stats
            synchronized (this) {
                totalProcessed++;
                totalProcessingTimeMs += totalTime;
                this.phase1to4TimeMs += phase1to4Duration;
                this.phase5TimeMs += phase5Duration;
                this.totalSignalsGenerated += signals.size();
                this.totalSignalsPublished += publishedCount;
            }

            log.debug("[PIPELINE] Processed {} in {}ms (Phase1-4: {}ms, Phase5: {}ms, signals: {})",
                    familyId, totalTime, phase1to4Duration, phase5Duration, signals.size());

            return PipelineResult.builder()
                    .familyId(familyId)
                    .processedAt(Instant.now())
                    .success(true)
                    // Phase 1-4 output
                    .enrichedScore(enrichedScore)
                    // Phase 5 output (state machine signals)
                    .generatedSignals(signals)
                    .signalsPublished(publishedCount)
                    // Summary
                    .isActionableMoment(enrichedScore.isActionableMoment())
                    .hasSignals(!signals.isEmpty())
                    // Timing
                    .phase1to4DurationMs(phase1to4Duration)
                    .phase5DurationMs(phase5Duration)
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
     * Get pipeline statistics
     */
    public PipelineStats getStats() {
        synchronized (this) {
            double avgTotal = totalProcessed > 0 ? (double) totalProcessingTimeMs / totalProcessed : 0;
            double avgPhase1to4 = totalProcessed > 0 ? (double) phase1to4TimeMs / totalProcessed : 0;
            double avgPhase5 = totalProcessed > 0 ? (double) phase5TimeMs / totalProcessed : 0;

            return PipelineStats.builder()
                    .totalProcessed(totalProcessed)
                    .avgTotalDurationMs(avgTotal)
                    .avgPhase1to4DurationMs(avgPhase1to4)
                    .avgPhase5DurationMs(avgPhase5)
                    .totalSignalsGenerated(totalSignalsGenerated)
                    .totalSignalsPublished(totalSignalsPublished)
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
     * Clear data for a family
     */
    public void clearFamily(String familyId) {
        eventStore.clearFamily(familyId);
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

        // Phase 5 output (state machine signals)
        @Builder.Default
        private List<TradingSignal> generatedSignals = new ArrayList<>();
        private int signalsPublished;

        // Summary
        private boolean isActionableMoment;
        private boolean hasSignals;

        // Timing
        private long phase1to4DurationMs;
        private long phase5DurationMs;
        private long totalDurationMs;

        public static PipelineResult empty(String familyId) {
            return PipelineResult.builder()
                    .familyId(familyId)
                    .processedAt(Instant.now())
                    .success(false)
                    .errorMessage("No data provided")
                    .isActionableMoment(false)
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

        @Override
        public String toString() {
            if (!success) {
                return String.format("PipelineResult[%s] FAILED: %s", familyId, errorMessage);
            }
            return String.format("PipelineResult[%s] Score:%.1f Conf:%.0f%% | Signals:%d | %dms",
                    familyId,
                    getAdjustedQuantScore(), getAdjustedConfidence() * 100,
                    generatedSignals != null ? generatedSignals.size() : 0,
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
        private long totalSignalsGenerated;
        private long totalSignalsPublished;
        private SignalGenerator.GeneratorStats signalGeneratorStats;
        private TradingSignalPublisher.PublisherStats signalPublisherStats;

        @Override
        public String toString() {
            return String.format(
                    "PipelineStats: %d processed, avg %.1fms (P1-4: %.1fms, P5: %.1fms)\n" +
                    "Signals: %d generated, %d published\n%s\n%s",
                    totalProcessed, avgTotalDurationMs, avgPhase1to4DurationMs, avgPhase5DurationMs,
                    totalSignalsGenerated, totalSignalsPublished,
                    signalGeneratorStats, signalPublisherStats);
        }
    }
}
