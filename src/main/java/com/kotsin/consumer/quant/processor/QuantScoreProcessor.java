package com.kotsin.consumer.quant.processor;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.quant.calculator.QuantScoreCalculator;
import com.kotsin.consumer.quant.config.QuantScoreConfig;
import com.kotsin.consumer.quant.model.QuantScore;
import com.kotsin.consumer.quant.model.QuantTradingSignal;
import com.kotsin.consumer.quant.signal.QuantSignalGenerator;
import com.kotsin.consumer.service.GreeksAggregator;
import com.kotsin.consumer.service.IVSurfaceCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired(required = false)
    private KafkaTemplate<String, QuantScore> scoreProducer;

    @Autowired(required = false)
    private KafkaTemplate<String, QuantTradingSignal> signalProducer;

    // Cache for score deduplication
    private Cache<String, QuantScore> scoreCache;

    // Cache for signal emission rate limiting
    private Cache<String, Long> emissionCache;

    @Autowired
    public QuantScoreProcessor(
            QuantScoreConfig config,
            QuantScoreCalculator scoreCalculator,
            QuantSignalGenerator signalGenerator,
            GreeksAggregator greeksAggregator,
            IVSurfaceCalculator ivSurfaceCalculator) {
        this.config = config;
        this.scoreCalculator = scoreCalculator;
        this.signalGenerator = signalGenerator;
        this.greeksAggregator = greeksAggregator;
        this.ivSurfaceCalculator = ivSurfaceCalculator;
    }

    @PostConstruct
    public void init() {
        // Initialize caches
        scoreCache = Caffeine.newBuilder()
            .expireAfterWrite(config.getCache().getTtlSeconds(), TimeUnit.SECONDS)
            .maximumSize(config.getCache().getMaxSize())
            .build();

        emissionCache = Caffeine.newBuilder()
            .expireAfterWrite(config.getEmission().getCooldownSeconds(), TimeUnit.SECONDS)
            .maximumSize(config.getCache().getMaxSize())
            .build();

        log.info("QuantScoreProcessor initialized with config: minScore={}, cooldown={}s",
            config.getEmission().getMinScore(),
            config.getEmission().getCooldownSeconds());
    }

    /**
     * Process FamilyCandle from Kafka and calculate QuantScore
     */
    @KafkaListener(
        topics = "family-candle-1m",
        groupId = "quant-score-processor-v1",
        containerFactory = "familyCandleListenerContainerFactory"
    )
    public void processFamilyCandle(FamilyCandle family) {
        if (family == null || family.getFamilyId() == null) {
            return;
        }

        try {
            String familyId = family.getFamilyId();

            // Enrich with Greeks and IV if not already present
            enrichFamilyCandle(family);

            // Calculate QuantScore
            QuantScore score = scoreCalculator.calculate(family);

            // Cache the score
            scoreCache.put(familyId, score);

            // Emit score to dashboard topic
            emitScore(score);

            // Check if we should emit a trading signal
            if (shouldEmitSignal(score)) {
                QuantTradingSignal signal = signalGenerator.generate(score, family);
                if (signal.isActionable()) {
                    emitSignal(signal);
                    recordEmission(familyId);
                }
            }

        } catch (Exception e) {
            log.error("Error processing FamilyCandle {}: {}",
                family.getFamilyId(), e.getMessage(), e);
        }
    }

    /**
     * Enrich FamilyCandle with Greeks and IV if not present
     */
    private void enrichFamilyCandle(FamilyCandle family) {
        // Add Greeks portfolio if not present
        if (!family.hasGreeksPortfolio() && family.getOptions() != null && !family.getOptions().isEmpty()) {
            try {
                var greeksPortfolio = greeksAggregator.aggregate(
                    family.getOptions(),
                    family.getPrimaryPrice()
                );
                family.setGreeksPortfolio(greeksPortfolio);
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
     * Emit QuantScore to dashboard topic
     */
    @Async
    public void emitScore(QuantScore score) {
        if (scoreProducer == null) {
            log.debug("Score producer not available, skipping emit");
            return;
        }

        try {
            scoreProducer.send(
                config.getScoresTopic(),
                score.getFamilyId(),
                score
            );
            log.debug("Emitted QuantScore for {} with score {}",
                score.getSymbol(), score.getQuantScore());
        } catch (Exception e) {
            log.error("Failed to emit QuantScore for {}: {}",
                score.getFamilyId(), e.getMessage());
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
