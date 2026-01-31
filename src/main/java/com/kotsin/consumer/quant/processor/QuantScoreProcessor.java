package com.kotsin.consumer.quant.processor;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator;
import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.EnrichmentPipeline;
import com.kotsin.consumer.enrichment.enricher.HistoricalContextEnricher;
import com.kotsin.consumer.enrichment.model.HistoricalContext;
import com.kotsin.consumer.quant.calculator.QuantScoreCalculator;
import com.kotsin.consumer.quant.config.QuantScoreConfig;
import com.kotsin.consumer.quant.model.QuantScore;
import com.kotsin.consumer.quant.model.QuantTradingSignal;
import com.kotsin.consumer.quant.signal.QuantSignalGenerator;
import com.kotsin.consumer.service.GreeksAggregator;
import com.kotsin.consumer.service.IVSurfaceCalculator;
import com.kotsin.consumer.service.QuantScoreCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;

/**
 * QuantScoreProcessor - Main Kafka processor for QuantScore calculation and signal emission.
 *
 * Listens to family-candle-1m topic, calculates QuantScore, and emits:
 * - QuantScore to quant-scores topic (for dashboard)
 * - QuantTradingSignal to quant-trading-signals topic (for execution)
 */
@Service
@Slf4j
public class QuantScoreProcessor {

    private final QuantScoreConfig config;
    private final QuantScoreCalculator scoreCalculator;
    private final QuantSignalGenerator signalGenerator;
    private final GreeksAggregator greeksAggregator;
    private final IVSurfaceCalculator ivSurfaceCalculator;
    private final QuantScoreCacheService quantScoreCacheService;

    // Phase 1 SMTIS: Enriched calculator with historical context
    @Autowired(required = false)
    private EnrichedQuantScoreCalculator enrichedCalculator;

    @Autowired(required = false)
    private HistoricalContextEnricher historicalEnricher;

    // Phase 5-6: Full enrichment pipeline with intelligence, narrative, signals
    @Autowired(required = false)
    private EnrichmentPipeline enrichmentPipeline;

    // Feature flag for Phase 5-6 full pipeline
    @Value("${smtis.pipeline.enabled:true}")
    private boolean pipelineEnabled;

    // Feature flag for Phase 1 enrichment
    @Value("${smtis.enrichment.enabled:true}")
    private boolean enrichmentEnabled;

    @Autowired(required = false)
    private KafkaTemplate<String, QuantScore> scoreProducer;

    @Autowired(required = false)
    private KafkaTemplate<String, QuantTradingSignal> signalProducer;

    // Cache for score deduplication
    private Cache<String, QuantScore> scoreCache;

    // Cache for enriched scores
    private Cache<String, EnrichedQuantScore> enrichedScoreCache;

    // Cache for signal emission rate limiting
    private Cache<String, Long> emissionCache;

    @Autowired
    public QuantScoreProcessor(
            QuantScoreConfig config,
            QuantScoreCalculator scoreCalculator,
            QuantSignalGenerator signalGenerator,
            GreeksAggregator greeksAggregator,
            IVSurfaceCalculator ivSurfaceCalculator,
            QuantScoreCacheService quantScoreCacheService) {
        this.config = config;
        this.scoreCalculator = scoreCalculator;
        this.signalGenerator = signalGenerator;
        this.greeksAggregator = greeksAggregator;
        this.ivSurfaceCalculator = ivSurfaceCalculator;
        this.quantScoreCacheService = quantScoreCacheService;
    }

    @PostConstruct
    public void init() {
        // Initialize caches
        scoreCache = Caffeine.newBuilder()
            .expireAfterWrite(config.getCache().getTtlSeconds(), TimeUnit.SECONDS)
            .maximumSize(config.getCache().getMaxSize())
            .build();

        enrichedScoreCache = Caffeine.newBuilder()
            .expireAfterWrite(config.getCache().getTtlSeconds(), TimeUnit.SECONDS)
            .maximumSize(config.getCache().getMaxSize())
            .build();

        emissionCache = Caffeine.newBuilder()
            .expireAfterWrite(config.getEmission().getCooldownSeconds(), TimeUnit.SECONDS)
            .maximumSize(config.getCache().getMaxSize())
            .build();

        log.info("QuantScoreProcessor initialized with config: minScore={}, cooldown={}s, enrichment={}",
            config.getEmission().getMinScore(),
            config.getEmission().getCooldownSeconds(),
            enrichmentEnabled && enrichedCalculator != null ? "ENABLED" : "DISABLED");
    }

    /**
     * Process FamilyCandle from Kafka and calculate QuantScore
     *
     * 🔴 CRITICAL FIX Bug #8 & #10: Listen to ALL timeframe topics, not just 1m.
     * BEFORE: Only 1m candles got Greeks/IV enrichment → 30m had totalDelta=0, ivrank=50
     * AFTER: All timeframes get enrichment → proper Greeks/IV for all MTF candles
     */
    @KafkaListener(
        topics = {
            "family-candle-1m",
            "family-candle-5m",
            "family-candle-15m",
            "family-candle-30m",
            "family-candle-1h",
            "family-candle-2h",
            "family-candle-4h",
            "family-candle-1d"
        },
        groupId = "${kafka.consumer.quant-group:quant-score-processor-v1}",
        containerFactory = "familyCandleListenerContainerFactory"
    )
    public void processFamilyCandle(FamilyCandle family) {
        if (family == null || family.getFamilyId() == null) {
            return;
        }

        try {
            String familyId = family.getFamilyId();
            String timeframe = family.getTimeframe() != null ? family.getTimeframe() : "1m";

            // Include timeframe in cache key to avoid overwriting across timeframes
            String cacheKey = familyId + ":" + timeframe;

            // Enrich with Greeks and IV if not already present
            enrichFamilyCandle(family);

            QuantScore score;
            EnrichedQuantScore enrichedScore = null;
            boolean isActionableMoment = false;
            String actionableReason = null;

            // Phase 1 SMTIS: Use enriched calculator if available and enabled
            if (enrichmentEnabled && enrichedCalculator != null) {
                enrichedScore = enrichedCalculator.calculate(family);
                score = enrichedScore.getBaseScore();

                // Override score with adjusted values from enrichment
                if (enrichedScore.hasHistoricalContext()) {
                    isActionableMoment = enrichedScore.isActionableMoment();
                    actionableReason = enrichedScore.getActionableMomentReason();

                    // Log enrichment details
                    HistoricalContext ctx = enrichedScore.getHistoricalContext();
                    log.debug("[SMTIS] {} {} | adjusted={} (base={}) conf={} flips={} absorption={} learning={}",
                        familyId, timeframe,
                        String.format("%.1f", enrichedScore.getAdjustedQuantScore()),
                        String.format("%.1f", score.getQuantScore()),
                        String.format("%.2f", enrichedScore.getAdjustedConfidence()),
                        ctx.getTotalFlipsDetected(),
                        ctx.isAbsorptionDetected(),
                        ctx.isInLearningMode());
                }

                // Cache enriched score
                enrichedScoreCache.put(cacheKey, enrichedScore);
            } else {
                // Fallback to base calculator
                score = scoreCalculator.calculate(family);
            }

            // Cache the base score
            scoreCache.put(cacheKey, score);

            // Emit score to dashboard topic
            emitScore(score);

            // Phase 5: Run full enrichment pipeline for state machine signals
            // FIX: Pass pre-calculated enrichedScore to avoid duplicate Redis insertions
            if (pipelineEnabled && enrichmentPipeline != null) {
                try {
                    EnrichmentPipeline.PipelineResult pipelineResult = enrichmentPipeline.process(family, enrichedScore);
                    if (pipelineResult != null && pipelineResult.getSignalsPublished() > 0) {
                        log.info("[PIPELINE] {} {} | signals={} actionable={}",
                            familyId, timeframe,
                            pipelineResult.getSignalsPublished(),
                            pipelineResult.isActionableMoment());
                    }
                } catch (Exception e) {
                    log.debug("[PIPELINE] Error in Phase 5 for {}: {}", familyId, e.getMessage());
                }
            }

            // Check if we should emit a trading signal
            // Boost signal emission for actionable moments
            boolean shouldEmit = shouldEmitSignal(score);
            if (!shouldEmit && isActionableMoment && enrichedScore != null) {
                // If it's an actionable moment, lower the threshold slightly
                if (enrichedScore.getAdjustedQuantScore() >= config.getEmission().getMinScore() * 0.9 &&
                    enrichedScore.getAdjustedConfidence() >= config.getEmission().getMinConfidence()) {
                    shouldEmit = true;
                    log.info("[SMTIS-MOMENT] {} {} - Actionable moment triggered: {}",
                        familyId, timeframe, actionableReason);
                }
            }

            if (shouldEmit) {
                QuantTradingSignal signal = signalGenerator.generate(score, family);

                // Enhance signal with enrichment context
                if (enrichedScore != null && isActionableMoment) {
                    signal.setEnrichmentNote(actionableReason);
                }

                if (signal.isActionable()) {
                    emitSignal(signal);
                    recordEmission(cacheKey);  // Rate limit per timeframe
                }
            }

            log.debug("[QUANT-MTF] Processed {} {} | score={} hasGreeks={} hasIV={} enriched={}",
                familyId, timeframe,
                String.format("%.1f", score.getQuantScore()),
                family.hasGreeksPortfolio(),
                family.hasIVSurface(),
                enrichedScore != null);

        } catch (Exception e) {
            log.error("Error processing FamilyCandle {} ({}): {}",
                family.getFamilyId(), family.getTimeframe(), e.getMessage(), e);
        }
    }

    /**
     * Enrich FamilyCandle with Greeks and IV if not present.
     *
     * 🔴 MTF-FIX: For HTF candles (5m, 15m, etc.), Greeks/IV are intentionally NULL
     * from TimeframeAggregator so they get recalculated here using the properly
     * AGGREGATED options data, giving timeframe-specific values.
     */
    private void enrichFamilyCandle(FamilyCandle family) {
        String timeframe = family.getTimeframe() != null ? family.getTimeframe() : "1m";
        boolean isHTF = !"1m".equals(timeframe);

        // Add Greeks portfolio if not present
        if (!family.hasGreeksPortfolio() && family.getOptions() != null && !family.getOptions().isEmpty()) {
            try {
                var greeksPortfolio = greeksAggregator.aggregate(
                    family.getOptions(),
                    family.getPrimaryPrice()
                );
                family.setGreeksPortfolio(greeksPortfolio);
                if (isHTF) {
                    log.debug("[MTF-ENRICH] {} {} | Greeks recalculated: delta={} gamma={} (from {} options)",
                        family.getFamilyId(), timeframe,
                        String.format("%.2f", greeksPortfolio.getTotalDelta()),
                        String.format("%.4f", greeksPortfolio.getTotalGamma()),
                        family.getOptions().size());
                }
            } catch (Exception e) {
                log.debug("Failed to calculate Greeks for {}: {}", family.getFamilyId(), e.getMessage());
            }
        }

        // Add IV surface if not present
        if (!family.hasIVSurface() && family.getOptions() != null && !family.getOptions().isEmpty()) {
            try {
                var ivSurface = ivSurfaceCalculator.calculate(
                    family.getOptions(),
                    family.getPrimaryPrice(),
                    null  // No historical IV rank available
                );
                family.setIvSurface(ivSurface);
                if (isHTF) {
                    log.debug("[MTF-ENRICH] {} {} | IV recalculated: atmIV={} ivRank={}",
                        family.getFamilyId(), timeframe,
                        String.format("%.2f", ivSurface.getAtmIV()),
                        String.format("%.2f", ivSurface.getIvRank()));
                }
            } catch (Exception e) {
                log.debug("Failed to calculate IV Surface for {}: {}", family.getFamilyId(), e.getMessage());
            }
        }
    }

    /**
     * Check if we should emit a trading signal
     */
    private boolean shouldEmitSignal(QuantScore score) {
        if (score == null || !score.isActionable()) {
            return false;
        }

        // Check score threshold
        if (score.getQuantScore() < config.getEmission().getMinScore()) {
            return false;
        }

        // Check confidence threshold
        if (score.getConfidence() < config.getEmission().getMinConfidence()) {
            return false;
        }

        // Check for critical warnings
        if (score.hasCriticalWarnings()) {
            return false;
        }

        // Check rate limiting
        String familyId = score.getFamilyId();
        Long lastEmission = emissionCache.getIfPresent(familyId);
        if (lastEmission != null) {
            long elapsed = System.currentTimeMillis() - lastEmission;
            if (elapsed < config.getEmission().getCooldownSeconds() * 1000L) {
                log.debug("Rate limited signal for {}: {}ms since last", familyId, elapsed);
                return false;
            }
        }

        // Check minimum categories above threshold
        int strongCategories = score.countStrongCategories();
        if (strongCategories < config.getEmission().getMinCategoriesAboveThreshold()) {
            return false;
        }

        return true;
    }

    /**
     * Emit QuantScore to dashboard topic and cache to Redis for persistence.
     */
    public void emitScore(QuantScore score) {
        if (scoreProducer == null) {
            log.warn("Score producer not available (null), skipping emit for {}", score.getFamilyId());
            return;
        }

        try {
            String topic = config.getScoresTopic();
            log.debug("Sending QuantScore to topic {} for {} with score {}",
                topic, score.getFamilyId(), score.getQuantScore());

            scoreProducer.send(topic, score.getFamilyId(), score)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to emit QuantScore for {} to {}: {}",
                            score.getFamilyId(), topic, ex.getMessage());
                    } else {
                        log.debug("Successfully emitted QuantScore for {} to {} partition {} offset {}",
                            score.getSymbol(), topic,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                    }
                });

            // FIX: Cache to Redis for dashboard persistence across restarts
            if (quantScoreCacheService != null) {
                quantScoreCacheService.cacheScore(score);
            }
        } catch (Exception e) {
            log.error("Exception while emitting QuantScore for {}: {}",
                score.getFamilyId(), e.getMessage(), e);
        }
    }

    /**
     * Emit QuantTradingSignal to execution topic
     */
    @Async
    public void emitSignal(QuantTradingSignal signal) {
        if (signalProducer == null) {
            log.debug("Signal producer not available, skipping emit");
            return;
        }

        try {
            signalProducer.send(
                config.getOutputTopic(),
                signal.getScripCode(),
                signal
            );
            log.info("Emitted QuantTradingSignal for {} - {} {} @ {} (Score: {})",
                signal.getSymbol(),
                signal.getDirection(),
                signal.getSignalType(),
                signal.getEntryPrice(),
                signal.getQuantScore());
        } catch (Exception e) {
            log.error("Failed to emit QuantTradingSignal for {}: {}",
                signal.getScripCode(), e.getMessage());
        }
    }

    /**
     * Record signal emission for rate limiting
     */
    private void recordEmission(String familyId) {
        emissionCache.put(familyId, System.currentTimeMillis());
    }

    /**
     * Get cached score for a family
     */
    public QuantScore getCachedScore(String familyId) {
        return scoreCache.getIfPresent(familyId);
    }

    /**
     * Get all cached scores (for debugging)
     */
    public java.util.Map<String, QuantScore> getAllCachedScores() {
        return scoreCache.asMap();
    }
}
