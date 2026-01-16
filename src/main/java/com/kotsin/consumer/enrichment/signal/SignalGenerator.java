package com.kotsin.consumer.enrichment.signal;

import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.intelligence.IntelligenceOrchestrator;
import com.kotsin.consumer.enrichment.intelligence.IntelligenceOrchestrator.ActionRecommendation;
import com.kotsin.consumer.enrichment.intelligence.IntelligenceOrchestrator.ActionType;
import com.kotsin.consumer.enrichment.intelligence.IntelligenceOrchestrator.MarketIntelligence;
import com.kotsin.consumer.enrichment.intelligence.model.ActiveSetup;
import com.kotsin.consumer.enrichment.intelligence.model.OpportunityForecast;
import com.kotsin.consumer.enrichment.intelligence.model.PredictedEvent;
import com.kotsin.consumer.enrichment.intelligence.narrative.NarrativeGenerator.MarketNarrative;
import com.kotsin.consumer.enrichment.model.DetectedEvent;
import com.kotsin.consumer.enrichment.model.GEXProfile;
import com.kotsin.consumer.enrichment.model.HistoricalContext;
import com.kotsin.consumer.enrichment.model.MaxPainProfile;
import com.kotsin.consumer.enrichment.model.MetricContext;
import com.kotsin.consumer.enrichment.model.TechnicalContext;
import com.kotsin.consumer.enrichment.model.TimeContext;
import com.kotsin.consumer.enrichment.pattern.model.PatternSignal;
import com.kotsin.consumer.enrichment.signal.model.SignalRationale;
import com.kotsin.consumer.enrichment.signal.model.SignalRationale.*;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal.*;
import com.kotsin.consumer.enrichment.signal.outcome.SignalOutcomeStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * SignalGenerator - Layer 8: Enhanced Signal Generation
 *
 * Transforms Phase 5 Intelligence into actionable trading signals with:
 * - Complete trade parameters (entry, stop, targets)
 * - Rich narrative context (why this signal)
 * - Pattern/sequence basis (what triggered it)
 * - Predicted follow-on events (what should happen next)
 * - Invalidation watch (what to monitor)
 *
 * Signal Sources:
 * 1. Pattern Signals - From Phase 4 pattern recognition
 * 2. Setup Signals - From Phase 5 setup tracker
 * 3. Forecast Signals - From Phase 5 opportunity forecaster
 * 4. Intelligence Signals - From combined market intelligence
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalGenerator {

    private final IntelligenceOrchestrator intelligenceOrchestrator;

    @Autowired(required = false)
    private SignalOutcomeStore signalOutcomeStore;

    /**
     * IST timezone for human readable time formatting
     */
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    /**
     * Formatter for full human readable time (e.g., "11 Jan 2026 12:45:33 IST")
     */
    private static final DateTimeFormatter FULL_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss z").withZone(IST_ZONE);

    /**
     * Formatter for short entry time (e.g., "12:45 PM")
     */
    private static final DateTimeFormatter ENTRY_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("hh:mm a").withZone(IST_ZONE);

    /**
     * FIX: Get the data timestamp (candle time) from quantScore for price-time correlation.
     * This is used for dataTimestamp field, NOT for generatedAt.
     *
     * generatedAt = Instant.now() (for staleness checking)
     * dataTimestamp = candle timestamp (for price-time correlation display)
     */
    private Instant getDataTimestamp(EnrichedQuantScore quantScore) {
        if (quantScore != null && quantScore.getPriceTimestamp() > 0) {
            return Instant.ofEpochMilli(quantScore.getPriceTimestamp());
        }
        // Fallback to current time if priceTimestamp not available
        return Instant.now();
    }

    /**
     * Cache of recent signals by family
     */
    private final ConcurrentHashMap<String, List<TradingSignal>> signalCache = new ConcurrentHashMap<>();

    /**
     * Statistics
     */
    private long totalSignalsGenerated = 0;
    private long patternSignals = 0;
    private long setupSignals = 0;
    private long forecastSignals = 0;
    private long filteredSignals = 0;

    // ======================== MAIN GENERATION ========================

    /**
     * Generate trading signals from enriched quant score and intelligence
     *
     * @param familyId    Family identifier
     * @param quantScore  Enriched quant score from Phase 1-4
     * @param intelligence Market intelligence from Phase 5
     * @return List of generated trading signals
     */
    public List<TradingSignal> generateSignals(String familyId, EnrichedQuantScore quantScore,
                                                 MarketIntelligence intelligence) {
        if (quantScore == null || intelligence == null) {
            log.warn("[SIGNAL_GEN] Null input for {}: quantScore={}, intelligence={}",
                    familyId, quantScore != null, intelligence != null);
            return Collections.emptyList();
        }

        List<TradingSignal> signals = new ArrayList<>();

        try {
            // Debug: Log input state
            boolean hasPatternSignals = quantScore.hasPatternSignals();
            int patternCount = quantScore.getPatternSignalCount();
            int readySetupCount = intelligence.getReadySetups() != null ? intelligence.getReadySetups().size() : 0;
            int forecastPredictions = intelligence.getForecast() != null &&
                                      intelligence.getForecast().getHighConfidencePredictions() != null ?
                                      intelligence.getForecast().getHighConfidencePredictions().size() : 0;
            ActionType actionType = intelligence.getRecommendation() != null ?
                                    intelligence.getRecommendation().getAction() : null;

            log.info("[SIGNAL_GEN] {} | Inputs: patternSignals={}, readySetups={}, highConfForecasts={}, action={}",
                    familyId, patternCount, readySetupCount, forecastPredictions, actionType);

            // 1. Convert pattern signals to trading signals (proven sequences only)
            List<TradingSignal> patternTradingSignals = convertPatternSignals(familyId, quantScore);
            signals.addAll(patternTradingSignals);

            // 2. Generate signals from ready setups (defined conditions met)
            List<TradingSignal> setupTradingSignals = generateSetupSignals(familyId, quantScore, intelligence);
            signals.addAll(setupTradingSignals);

            // REMOVED: Forecast signals - too speculative, no proven edge
            // REMOVED: Recommendation signals - redundant with setups

            int preFilterCount = signals.size();

            // 5. Enrich all signals with common context
            enrichSignalsWithContext(signals, quantScore, intelligence);

            // 6. Filter and validate signals
            signals = filterAndValidateSignals(signals);

            // 7. Update cache
            updateSignalCache(familyId, signals);

            // 8. Update statistics
            updateStatistics(signals, patternTradingSignals.size(), setupTradingSignals.size(), 0);

            // Debug: Log output state
            log.info("[SIGNAL_GEN] {} | Output: {} pattern, {} setup | preFilter={}, postFilter={}",
                    familyId, patternTradingSignals.size(), setupTradingSignals.size(),
                    preFilterCount, signals.size());

            // Additional debug if 0 signals
            if (signals.isEmpty() && preFilterCount == 0) {
                StringBuilder reason = new StringBuilder();
                if (!hasPatternSignals) reason.append("NoPatterns ");
                if (readySetupCount == 0) reason.append("NoSetups ");
                else {
                    // Log setup confidences
                    intelligence.getReadySetups().forEach(s ->
                        log.debug("[SIGNAL_GEN] {} setup {} conf={}% (need 65%)",
                            familyId, s.getSetupId(), String.format("%.1f", s.getCurrentConfidence() * 100)));
                }
                log.debug("[SIGNAL_GEN] {} | Zero signals - patterns={}, setups={}", familyId, hasPatternSignals, readySetupCount);
            }

            return signals;

        } catch (Exception e) {
            log.error("[SIGNAL_GEN] Error generating signals for {}: {}", familyId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ======================== PATTERN SIGNAL CONVERSION ========================

    /**
     * Convert Phase 4 PatternSignals to enhanced TradingSignals
     */
    private List<TradingSignal> convertPatternSignals(String familyId, EnrichedQuantScore quantScore) {
        if (!quantScore.hasPatternSignals()) {
            return Collections.emptyList();
        }

        return quantScore.getPatternSignals().stream()
                .filter(ps -> ps.isActionable())
                .map(ps -> convertPatternSignal(ps, quantScore))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Convert a single PatternSignal to TradingSignal
     */
    private TradingSignal convertPatternSignal(PatternSignal pattern, EnrichedQuantScore quantScore) {
        // FIX: Separate timestamps for staleness check vs price-time correlation
        // generatedAt = now (for staleness checking)
        // dataTimestamp = candle time (for price-time correlation display)
        Instant dataTime = getDataTimestamp(quantScore);

        // FIX: Use quantScore fields as fallback if pattern fields are null
        String scripCode = pattern.getScripCode() != null ? pattern.getScripCode() : quantScore.getScripCode();
        String companyName = pattern.getCompanyName() != null ? pattern.getCompanyName() : quantScore.getCompanyName();

        TradingSignal.TradingSignalBuilder builder = TradingSignal.builder()
                .signalId(pattern.getSignalId() != null ? pattern.getSignalId() : UUID.randomUUID().toString())
                .familyId(pattern.getFamilyId())
                .scripCode(scripCode)
                .companyName(companyName)
                .generatedAt(Instant.now())  // FIX: Signal generation time for staleness check
                .dataTimestamp(dataTime)     // FIX: Market data time for price-time correlation
                .expiresAt(calculateExpiry(pattern.getHorizon()))
                // Source
                .source(SignalSource.PATTERN)
                .category(mapCategory(pattern.getCategory()))
                .direction(mapDirection(pattern.getDirection()))
                .horizon(mapHorizon(pattern.getHorizon()))
                .urgency(pattern.isHighConfidence() ? Urgency.IMMEDIATE : Urgency.NORMAL)
                // Trade parameters
                .currentPrice(quantScore.getClose())
                .entryPrice(pattern.getEntryPrice())
                .stopLoss(pattern.getStopLoss())
                .target1(pattern.getTarget1())
                .target2(pattern.getTarget2())
                .target3(pattern.getTarget3())
                // Confidence
                .confidence(pattern.getConfidence())
                .confidenceBreakdown(ConfidenceBreakdown.builder()
                        .patternConfidence(pattern.getPatternConfidence())
                        .historicalConfidence(pattern.getHistoricalSuccessRate())
                        .quantScoreConfidence(quantScore.getAdjustedConfidence())
                        .build())
                // Sequence basis
                .patternId(pattern.getPatternId())
                .sequenceId(pattern.getSequenceId())
                .matchedEvents(pattern.getMatchedEvents() != null ? pattern.getMatchedEvents() : new ArrayList<>())
                .matchedBoosters(pattern.getMatchedBoosters() != null ? pattern.getMatchedBoosters() : new ArrayList<>())
                .patternProgress(pattern.getPatternProgress())
                .historicalSuccessRate(pattern.getHistoricalSuccessRate())
                .historicalSampleCount(pattern.getHistoricalSampleSize())
                .historicalExpectedValue(pattern.getHistoricalExpectedValue())
                // Narrative
                .headline(generatePatternHeadline(pattern))
                .narrative(pattern.getNarrative())
                .entryReasons(pattern.getEntryReasons() != null ? pattern.getEntryReasons() : new ArrayList<>())
                // Predictions
                .predictedEvents(convertPredictedEvents(pattern.getPredictedEvents()))
                // Invalidation
                .invalidationWatch(convertInvalidationWatch(pattern.getInvalidationWatch()))
                // Market context
                .gexRegime(pattern.getGexRegime())
                .superTrendDirection(pattern.getSuperTrendDirection())
                .atConfluenceZone(pattern.isAtConfluenceZone())
                .nearestSupport(pattern.getNearestSupport())
                .nearestResistance(pattern.getNearestResistance())
                .session(pattern.getSession())
                .daysToExpiry(pattern.getDaysToExpiry())
                // Position sizing
                .positionSizeMultiplier(pattern.getPositionSizeMultiplier())
                .riskPercentage(pattern.getRiskPercentage());

        return builder.build();
    }

    // ======================== SETUP SIGNAL GENERATION ========================

    /**
     * Generate signals from ready setups
     */
    private List<TradingSignal> generateSetupSignals(String familyId, EnrichedQuantScore quantScore,
                                                       MarketIntelligence intelligence) {
        if (intelligence.getReadySetups() == null || intelligence.getReadySetups().isEmpty()) {
            return Collections.emptyList();
        }

        return intelligence.getReadySetups().stream()
                .filter(setup -> setup.isActionable(0.65)) // Raised to 0.65 - quality over quantity
                .filter(setup -> setup.getBoosterConditionsMet() >= 2) // Require minimum 2 boosters
                .map(setup -> generateSetupSignal(setup, quantScore, intelligence))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Generate signal from an active setup
     */
    private TradingSignal generateSetupSignal(ActiveSetup setup, EnrichedQuantScore quantScore,
                                                MarketIntelligence intelligence) {
        double currentPrice = quantScore.getClose();
        double atrPct = quantScore.getAtrPct() != null ? quantScore.getAtrPct() : 0.5;

        // Calculate trade parameters
        boolean isLong = setup.getDirection() == com.kotsin.consumer.enrichment.intelligence.model.SetupDefinition.SetupDirection.LONG;
        double stopDistance = currentPrice * atrPct / 100 * 1.5;
        double targetDistance = stopDistance * setup.getRiskRewardRatio();

        double entryPrice = currentPrice;
        double stopLoss = isLong ? entryPrice - stopDistance : entryPrice + stopDistance;
        double target1 = isLong ? entryPrice + (targetDistance * 0.5) : entryPrice - (targetDistance * 0.5);
        double target2 = isLong ? entryPrice + targetDistance : entryPrice - targetDistance;
        double target3 = isLong ? entryPrice + (targetDistance * 1.5) : entryPrice - (targetDistance * 1.5);

        // FIX: Separate timestamps for staleness check vs price-time correlation
        Instant dataTime = getDataTimestamp(quantScore);

        // FIX: Log warning if scripCode or companyName is null
        if (quantScore.getScripCode() == null || quantScore.getCompanyName() == null) {
            log.warn("[SIGNAL_GEN] Setup signal missing context: scripCode={}, companyName={}, familyId={}",
                    quantScore.getScripCode(), quantScore.getCompanyName(), setup.getFamilyId());
        }

        TradingSignal.TradingSignalBuilder builder = TradingSignal.builder()
                .signalId(UUID.randomUUID().toString())
                .familyId(setup.getFamilyId())
                .scripCode(quantScore.getScripCode())
                .companyName(quantScore.getCompanyName())
                .generatedAt(Instant.now())  // FIX: Signal generation time for staleness check
                .dataTimestamp(dataTime)     // FIX: Market data time for price-time correlation
                .expiresAt(setup.getExpiresAt())
                // Source
                .source(SignalSource.SETUP)
                .category(mapSetupCategory(setup.getSetupId()))
                .direction(isLong ? Direction.LONG : Direction.SHORT)
                .horizon(mapSetupHorizon(setup.getHorizon()))
                .urgency(setup.getCurrentConfidence() >= 0.75 ? Urgency.IMMEDIATE : Urgency.NORMAL)
                // Trade parameters
                .currentPrice(currentPrice)
                .entryPrice(entryPrice)
                .stopLoss(stopLoss)
                .target1(target1)
                .target2(target2)
                .target3(target3)
                // Confidence
                .confidence(setup.getCurrentConfidence())
                .confidenceBreakdown(ConfidenceBreakdown.builder()
                        .setupConfidence(setup.getCurrentConfidence())
                        .quantScoreConfidence(quantScore.getAdjustedConfidence())
                        .build())
                // Sequence basis
                .setupId(setup.getSetupId())
                .matchedEvents(setup.getMetConditions())
                .patternProgress(setup.getProgress())
                // Narrative - now with actual data instead of generic text
                .headline(generateSetupHeadline(setup))
                .narrative(buildDataDrivenNarrative(quantScore, setup))
                .entryReasons(generateSetupEntryReasons(setup))
                // Historical performance - REAL data from outcome tracking
                .historicalSuccessRate(getActualSuccessRate(setup.getSetupId()))
                .historicalSampleCount(getActualSampleCount(setup.getSetupId()))
                // Position sizing
                .positionSizeMultiplier(calculatePositionSize(setup.getCurrentConfidence()))
                .riskPercentage(1.0);

        // Build rationale
        builder.rationale(buildSetupRationale(setup, quantScore, intelligence));

        // Add invalidation conditions
        builder.invalidationWatch(generateSetupInvalidation(setup, quantScore));

        // Add predicted events
        builder.predictedEvents(generateSetupPredictions(setup, intelligence));

        return builder.build();
    }

    // ======================== FORECAST SIGNAL GENERATION ========================

    /**
     * Generate signals from forecast predictions
     */
    private List<TradingSignal> generateForecastSignals(String familyId, EnrichedQuantScore quantScore,
                                                          MarketIntelligence intelligence) {
        if (intelligence.getForecast() == null) {
            return Collections.emptyList();
        }

        OpportunityForecast forecast = intelligence.getForecast();

        // Only generate from high-confidence predictions that are actionable
        List<PredictedEvent> actionablePredictions = forecast.getHighConfidencePredictions().stream()
                .filter(p -> p.getConfidence() >= 0.60) // Lowered from 0.70 for more signals
                .filter(p -> p.getTimeFrame() == PredictedEvent.TimeFrame.IMMEDIATE ||
                        p.getTimeFrame() == PredictedEvent.TimeFrame.SHORT_TERM)
                .limit(2) // Max 2 forecast signals
                .collect(Collectors.toList());

        return actionablePredictions.stream()
                .map(prediction -> generateForecastSignal(prediction, quantScore, intelligence))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Generate signal from a prediction
     */
    private TradingSignal generateForecastSignal(PredictedEvent prediction, EnrichedQuantScore quantScore,
                                                   MarketIntelligence intelligence) {
        double currentPrice = quantScore.getClose();
        double atrPct = quantScore.getAtrPct() != null ? quantScore.getAtrPct() : 0.5;

        // Determine direction
        boolean isLong = prediction.getDirection() == PredictedEvent.PredictionDirection.BULLISH;

        // Use prediction targets if available
        double targetPrice = prediction.getTargetPrice() != null ? prediction.getTargetPrice() :
                (isLong ? currentPrice * 1.01 : currentPrice * 0.99);
        double invalidationPrice = prediction.getInvalidationPrice() != null ? prediction.getInvalidationPrice() :
                (isLong ? currentPrice * 0.995 : currentPrice * 1.005);

        double stopDistance = Math.abs(currentPrice - invalidationPrice);
        double target1 = isLong ? currentPrice + (stopDistance * 0.7) : currentPrice - (stopDistance * 0.7);
        double target3 = isLong ? currentPrice + (stopDistance * 2.5) : currentPrice - (stopDistance * 2.5);

        // FIX: Separate timestamps for staleness check vs price-time correlation
        Instant dataTime = getDataTimestamp(quantScore);

        return TradingSignal.builder()
                .signalId(UUID.randomUUID().toString())
                .familyId(prediction.getFamilyId())
                .scripCode(quantScore.getScripCode())
                .companyName(quantScore.getCompanyName())
                .generatedAt(Instant.now())  // FIX: Signal generation time for staleness check
                .dataTimestamp(dataTime)     // FIX: Market data time for price-time correlation
                .expiresAt(prediction.getExpiresAt())
                // Source
                .source(SignalSource.FORECAST)
                .category(mapPredictionCategory(prediction.getType()))
                .direction(isLong ? Direction.LONG : Direction.SHORT)
                .horizon(mapPredictionHorizon(prediction.getTimeFrame()))
                .urgency(prediction.getTimeFrame() == PredictedEvent.TimeFrame.IMMEDIATE ?
                        Urgency.IMMEDIATE : Urgency.NORMAL)
                // Trade parameters
                .currentPrice(currentPrice)
                .entryPrice(currentPrice)
                .stopLoss(invalidationPrice)
                .target1(target1)
                .target2(targetPrice)
                .target3(target3)
                // Confidence
                .confidence(prediction.getConfidence())
                .confidenceBreakdown(ConfidenceBreakdown.builder()
                        .historicalConfidence(prediction.getHistoricalSuccessRate())
                        .quantScoreConfidence(quantScore.getAdjustedConfidence())
                        .build())
                // Sequence basis
                .setupId(prediction.getSetupId())
                .patternId(prediction.getPatternId())
                .historicalSuccessRate(prediction.getHistoricalSuccessRate())
                .historicalSampleCount(prediction.getHistoricalSampleCount())
                // Narrative
                .headline(prediction.getShortDescription())
                .narrative(prediction.getRationale())
                .entryReasons(prediction.getTriggerConditions() != null ?
                        prediction.getTriggerConditions() : new ArrayList<>())
                // Position sizing
                .positionSizeMultiplier(calculatePositionSize(prediction.getConfidence()))
                .riskPercentage(0.75) // Lower risk for forecast signals
                .build();
    }

    // ======================== RECOMMENDATION SIGNAL ========================

    /**
     * Generate signal from action recommendation
     */
    private TradingSignal generateRecommendationSignal(String familyId, EnrichedQuantScore quantScore,
                                                         MarketIntelligence intelligence) {
        ActionRecommendation rec = intelligence.getRecommendation();
        if (rec == null || rec.getAction() != ActionType.TRADE) {
            return null;
        }

        double currentPrice = quantScore.getClose();
        boolean isLong = "LONG".equalsIgnoreCase(rec.getDirection());

        // Use recommended prices if available
        double entryPrice = rec.getSuggestedEntry() != null ? rec.getSuggestedEntry() : currentPrice;
        double stopLoss = rec.getSuggestedStop() != null ? rec.getSuggestedStop() :
                (isLong ? currentPrice * 0.99 : currentPrice * 1.01);
        double target2 = rec.getSuggestedTarget() != null ? rec.getSuggestedTarget() :
                (isLong ? currentPrice * 1.015 : currentPrice * 0.985);

        double stopDistance = Math.abs(entryPrice - stopLoss);
        double target1 = isLong ? entryPrice + (stopDistance * 0.7) : entryPrice - (stopDistance * 0.7);
        double target3 = isLong ? entryPrice + (stopDistance * 2.0) : entryPrice - (stopDistance * 2.0);

        // FIX: Separate timestamps for staleness check vs price-time correlation
        Instant dataTime = getDataTimestamp(quantScore);
        Instant now = Instant.now();

        return TradingSignal.builder()
                .signalId(UUID.randomUUID().toString())
                .familyId(familyId)
                .scripCode(quantScore.getScripCode())
                .companyName(quantScore.getCompanyName())
                .generatedAt(now)        // FIX: Signal generation time for staleness check
                .dataTimestamp(dataTime) // FIX: Market data time for price-time correlation
                .expiresAt(now.plus(Duration.ofMinutes(30)))
                // Source
                .source(SignalSource.INTELLIGENCE)
                .category(SignalCategory.MOMENTUM)
                .direction(isLong ? Direction.LONG : Direction.SHORT)
                .horizon(Horizon.SCALP)
                .urgency(Urgency.IMMEDIATE)
                // Trade parameters
                .currentPrice(currentPrice)
                .entryPrice(entryPrice)
                .stopLoss(stopLoss)
                .target1(target1)
                .target2(target2)
                .target3(target3)
                // Confidence
                .confidence(rec.getConfidence())
                // Sequence basis
                .setupId(rec.getSetupId())
                // Narrative
                .headline(intelligence.getHeadline())
                .narrative(rec.getRationale())
                // Position sizing
                .positionSizeMultiplier(rec.getPositionSize())
                .riskPercentage(1.0)
                .build();
    }

    // ======================== SIGNAL ENRICHMENT ========================

    /**
     * Enrich signals with common context from quantScore and intelligence
     */
    private void enrichSignalsWithContext(List<TradingSignal> signals, EnrichedQuantScore quantScore,
                                           MarketIntelligence intelligence) {
        for (TradingSignal signal : signals) {
            // FIX: Human readable time should use dataTimestamp (market data time) for price-time correlation
            // This ensures the displayed time matches when the price data was captured
            Instant displayTime = signal.getDataTimestamp() != null ? signal.getDataTimestamp() :
                                  signal.getGeneratedAt() != null ? signal.getGeneratedAt() : Instant.now();
            signal.setHumanReadableTime(FULL_TIME_FORMATTER.format(displayTime));
            signal.setEntryTimeIST(ENTRY_TIME_FORMATTER.format(displayTime));

            // Market context
            if (quantScore.getGexProfile() != null) {
                signal.setGexRegime(quantScore.getGexProfile().getRegime() != null ?
                        quantScore.getGexProfile().getRegime().name() : null);
                Double gammaFlipLevel = quantScore.getGexProfile().getGammaFlipLevel();
                if (gammaFlipLevel != null && gammaFlipLevel > 0) {
                    signal.setGammaFlipLevel(gammaFlipLevel);
                }
            }

            if (quantScore.getMaxPainProfile() != null) {
                signal.setMaxPainLevel(quantScore.getMaxPainProfile().getMaxPainStrike());
            }

            if (quantScore.getTechnicalContext() != null) {
                signal.setSuperTrendDirection(quantScore.getTechnicalContext().isSuperTrendBullish() ?
                        "BULLISH" : "BEARISH");
            }

            if (quantScore.getTimeContext() != null) {
                signal.setSession(quantScore.getTimeContext().getSession() != null ?
                        quantScore.getTimeContext().getSession().name() : null);
            }

            if (quantScore.getExpiryContext() != null) {
                signal.setDaysToExpiry(quantScore.getExpiryContext().getDaysToExpiry());
            }

            // Confluence
            signal.setAtConfluenceZone(quantScore.isInConfluenceZone());
            signal.setNearestSupport(quantScore.getNearestSupportPrice());
            signal.setNearestResistance(quantScore.getNearestResistancePrice());

            // Key levels
            if (signal.getKeyLevels() == null || signal.getKeyLevels().isEmpty()) {
                signal.setKeyLevels(generateKeyLevels(quantScore));
            }

            // Build full rationale if not present
            if (signal.getRationale() == null) {
                signal.setRationale(buildSignalRationale(signal, quantScore, intelligence));
            }
        }
    }

    // ======================== SIGNAL FILTERING ========================

    /**
     * Filter and validate signals
     *
     * FIX: Added conflict resolution to prevent generating both LONG and SHORT
     * signals for the same instrument. This was causing contradictory signals
     * like MOMENTUM LONG + REVERSAL SHORT for the same familyId.
     */
    private List<TradingSignal> filterAndValidateSignals(List<TradingSignal> signals) {
        int originalCount = signals.size();

        // Step 1: Basic filtering
        List<TradingSignal> basicFiltered = signals.stream()
                .filter(TradingSignal::isActionable)
                .filter(s -> !s.hasExpired())
                .filter(s -> s.getQualityScore() >= 50) // Raised from 30 - quality over quantity
                .collect(Collectors.toList());

        // Step 2: Remove same-direction duplicates (keep highest confidence)
        List<TradingSignal> deduped = basicFiltered.stream()
                .collect(Collectors.toMap(
                        s -> s.getFamilyId() + "_" + s.getDirection() + "_" + s.getCategory(),
                        s -> s,
                        (s1, s2) -> s1.getConfidence() >= s2.getConfidence() ? s1 : s2
                ))
                .values()
                .stream()
                .collect(Collectors.toList());

        // Step 3: FIX - Resolve direction conflicts per family
        // If both LONG and SHORT signals exist for same familyId, keep only the highest confidence one
        Map<String, TradingSignal> bestPerFamily = new HashMap<>();
        for (TradingSignal signal : deduped) {
            String familyId = signal.getFamilyId();
            TradingSignal existing = bestPerFamily.get(familyId);

            if (existing == null) {
                bestPerFamily.put(familyId, signal);
            } else if (existing.getDirection() != signal.getDirection()) {
                // Conflict! Same family, opposite directions
                // Keep the one with higher confidence
                if (signal.getConfidence() > existing.getConfidence()) {
                    log.info("[SIGNAL_GEN] Conflict resolution: {} {} (conf={}) beats {} (conf={}) for {}",
                            signal.getDirection(), signal.getCategory(), signal.getConfidence(),
                            existing.getDirection(), existing.getConfidence(), familyId);
                    bestPerFamily.put(familyId, signal);
                } else {
                    log.info("[SIGNAL_GEN] Conflict resolution: {} {} (conf={}) beats {} (conf={}) for {}",
                            existing.getDirection(), existing.getCategory(), existing.getConfidence(),
                            signal.getDirection(), signal.getConfidence(), familyId);
                }
            } else {
                // Same direction - keep higher confidence
                if (signal.getConfidence() > existing.getConfidence()) {
                    bestPerFamily.put(familyId, signal);
                }
            }
        }

        List<TradingSignal> filtered = bestPerFamily.values().stream()
                .sorted((a, b) -> Integer.compare(b.getQualityScore(), a.getQualityScore()))
                .limit(5)
                .collect(Collectors.toList());

        filteredSignals += (originalCount - filtered.size());

        return filtered;
    }

    /**
     * Check if signal is duplicate of existing signals
     */
    private boolean isDuplicateSignal(List<TradingSignal> signals, TradingSignal newSignal) {
        return signals.stream().anyMatch(s ->
                s.getDirection() == newSignal.getDirection() &&
                s.getCategory() == newSignal.getCategory() &&
                Math.abs(s.getEntryPrice() - newSignal.getEntryPrice()) < s.getEntryPrice() * 0.001);
    }

    // ======================== HELPER METHODS ========================

    private SignalCategory mapCategory(String category) {
        if (category == null) return SignalCategory.MOMENTUM;
        return switch (category.toUpperCase()) {
            case "REVERSAL" -> SignalCategory.REVERSAL;
            case "CONTINUATION" -> SignalCategory.CONTINUATION;
            case "BREAKOUT" -> SignalCategory.BREAKOUT;
            case "BREAKDOWN" -> SignalCategory.BREAKDOWN;
            case "MEAN_REVERSION" -> SignalCategory.MEAN_REVERSION;
            case "SQUEEZE" -> SignalCategory.SQUEEZE;
            case "EXHAUSTION" -> SignalCategory.EXHAUSTION;
            default -> SignalCategory.MOMENTUM;
        };
    }

    private Direction mapDirection(PatternSignal.Direction dir) {
        return dir == PatternSignal.Direction.LONG ? Direction.LONG : Direction.SHORT;
    }

    private Horizon mapHorizon(PatternSignal.Horizon horizon) {
        if (horizon == null) return Horizon.INTRADAY;
        return switch (horizon) {
            case SCALP -> Horizon.SCALP;
            case SWING -> Horizon.SWING;
            case POSITIONAL -> Horizon.POSITIONAL;
        };
    }

    private SignalCategory mapSetupCategory(String setupId) {
        if (setupId == null) return SignalCategory.MOMENTUM;
        if (setupId.contains("REVERSAL")) return SignalCategory.REVERSAL;
        if (setupId.contains("BREAKOUT")) return SignalCategory.BREAKOUT;
        if (setupId.contains("SQUEEZE")) return SignalCategory.SQUEEZE;
        if (setupId.contains("CONTINUATION")) return SignalCategory.CONTINUATION;
        return SignalCategory.MOMENTUM;
    }

    /**
     * Get ACTUAL historical success rate from outcome tracking
     * Returns 0.5 (no edge) if insufficient data or store not available
     */
    private double getActualSuccessRate(String setupId) {
        if (signalOutcomeStore == null || setupId == null) {
            return 0.5; // No data - neutral assumption
        }
        try {
            return signalOutcomeStore.getHistoricalSuccessRate(setupId);
        } catch (Exception e) {
            log.debug("[SIGNAL_GEN] Could not get historical success rate for {}: {}", setupId, e.getMessage());
            return 0.5;
        }
    }

    /**
     * Get ACTUAL historical sample count from outcome tracking
     * Returns 0 if store not available
     */
    private int getActualSampleCount(String setupId) {
        if (signalOutcomeStore == null || setupId == null) {
            return 0;
        }
        try {
            return signalOutcomeStore.getHistoricalSampleCount(setupId);
        } catch (Exception e) {
            log.debug("[SIGNAL_GEN] Could not get sample count for {}: {}", setupId, e.getMessage());
            return 0;
        }
    }

    private Horizon mapSetupHorizon(com.kotsin.consumer.enrichment.intelligence.model.SetupDefinition.SetupHorizon horizon) {
        if (horizon == null) return Horizon.INTRADAY;
        return switch (horizon) {
            case SCALP -> Horizon.SCALP;
            case SWING -> Horizon.SWING;
            case POSITIONAL -> Horizon.POSITIONAL;
        };
    }

    private SignalCategory mapPredictionCategory(PredictedEvent.PredictionType type) {
        if (type == null) return SignalCategory.MOMENTUM;
        return switch (type) {
            case DIRECTION_CHANGE, EXHAUSTION_REVERSAL -> SignalCategory.REVERSAL;
            case BREAKOUT -> SignalCategory.BREAKOUT;
            case BREAKDOWN -> SignalCategory.BREAKDOWN;
            case MEAN_REVERSION -> SignalCategory.MEAN_REVERSION;
            case GAMMA_SQUEEZE -> SignalCategory.SQUEEZE;
            case TREND_CONTINUATION -> SignalCategory.CONTINUATION;
            default -> SignalCategory.MOMENTUM;
        };
    }

    private Horizon mapPredictionHorizon(PredictedEvent.TimeFrame timeFrame) {
        if (timeFrame == null) return Horizon.INTRADAY;
        return switch (timeFrame) {
            case IMMEDIATE -> Horizon.SCALP;
            case SHORT_TERM -> Horizon.INTRADAY;
            case MEDIUM_TERM, END_OF_DAY -> Horizon.SWING;
            case MULTI_DAY -> Horizon.POSITIONAL;
        };
    }

    private Instant calculateExpiry(PatternSignal.Horizon horizon) {
        if (horizon == null) return Instant.now().plus(Duration.ofHours(4));
        return switch (horizon) {
            case SCALP -> Instant.now().plus(Duration.ofMinutes(30));
            case SWING -> Instant.now().plus(Duration.ofHours(4));
            case POSITIONAL -> Instant.now().plus(Duration.ofDays(1));
        };
    }

    private double calculatePositionSize(double confidence) {
        // Base size with confidence adjustment
        if (confidence >= 0.80) return 1.5;
        if (confidence >= 0.70) return 1.2;
        if (confidence >= 0.60) return 1.0;
        if (confidence >= 0.50) return 0.7;
        return 0.5;
    }

    private String generatePatternHeadline(PatternSignal pattern) {
        return String.format("%s %s signal in %s",
                pattern.getDirection().name(),
                pattern.getCategory() != null ? pattern.getCategory() : "PATTERN",
                pattern.getFamilyId());
    }

    private String generateSetupHeadline(ActiveSetup setup) {
        return String.format("%s %s ready - %.0f%% confidence",
                setup.getSetupId(),
                setup.getDirection().name(),
                setup.getCurrentConfidence() * 100);
    }

    /**
     * Generate data-driven narrative with actual numbers instead of generic text
     */
    private String generateSetupNarrative(ActiveSetup setup, MarketIntelligence intelligence) {
        // This is a stub - actual data comes from buildDataDrivenNarrative which uses quantScore
        StringBuilder narrative = new StringBuilder();
        narrative.append(String.format("%s %s | ", setup.getSetupId(), setup.getDirection().name()));
        narrative.append(String.format("Confidence: %.0f%% | ", setup.getCurrentConfidence() * 100));
        narrative.append(String.format("Required: %d/%d | ", setup.getRequiredConditionsMet(), setup.getRequiredConditionsMet()));
        narrative.append(String.format("Boosters: %d | ", setup.getBoosterConditionsMet()));
        narrative.append(String.format("R:R %.1f:1", setup.getRiskRewardRatio()));
        return narrative.toString();
    }

    /**
     * Build comprehensive data-driven narrative with actual market data numbers
     * This replaces generic text like "Exhaustion at extremes" with real values
     */
    private String buildDataDrivenNarrative(EnrichedQuantScore quantScore, ActiveSetup setup) {
        StringBuilder sb = new StringBuilder();

        // Direction and setup
        sb.append(String.format("%s %s SIGNAL\n\n", setup.getDirection().name(), setup.getSetupId()));

        // === WHY NOW ===
        sb.append("WHY NOW:\n");

        // OFI data from HistoricalContext
        HistoricalContext ctx = quantScore.getHistoricalContext();
        if (ctx != null && ctx.getOfiContext() != null) {
            MetricContext ofiCtx = ctx.getOfiContext();
            double ofi = ofiCtx.getCurrentValue();
            double prevOfi = ofiCtx.getPreviousValue();
            sb.append(String.format("- OFI: %.0f", ofi));
            if (prevOfi != 0) {
                double velocity = ofi - prevOfi;
                sb.append(String.format(" (prev: %.0f, velocity: %+.0f)", prevOfi, velocity));
            }
            // Classify OFI
            if (ofi > 1000) sb.append(" [STRONG BUY FLOW]");
            else if (ofi > 500) sb.append(" [MODERATE BUY]");
            else if (ofi < -1000) sb.append(" [STRONG SELL FLOW]");
            else if (ofi < -500) sb.append(" [MODERATE SELL]");
            else sb.append(" [NEUTRAL]");
            sb.append("\n");

            // Add percentile if available
            if (ofiCtx.getPercentile() > 0) {
                sb.append(String.format("- OFI Percentile: %.0f%%\n", ofiCtx.getPercentile() * 100));
            }
        }

        // Volume delta from HistoricalContext
        if (ctx != null && ctx.getVolumeDeltaContext() != null) {
            MetricContext vdCtx = ctx.getVolumeDeltaContext();
            double volumeDelta = vdCtx.getCurrentValue();
            sb.append(String.format("- Volume Delta: %+.1f%%\n", volumeDelta * 100));
        }

        // Support/Resistance from TechnicalContext
        TechnicalContext tech = quantScore.getTechnicalContext();
        Double close = quantScore.getClose();
        if (tech != null && close != null && close > 0) {
            Double support = tech.getNearestSupport();
            Double resistance = tech.getNearestResistance();

            if (support != null && support > 0) {
                double distPct = (close - support) / close * 100;
                sb.append(String.format("- Support: %.2f (%.1f%% away)\n", support, distPct));
            }
            if (resistance != null && resistance > 0) {
                double distPct = (resistance - close) / close * 100;
                sb.append(String.format("- Resistance: %.2f (%.1f%% away)\n", resistance, distPct));
            }

            // SuperTrend
            String superTrend = tech.isSuperTrendBullish() ? "BULLISH" : "BEARISH";
            sb.append(String.format("- SuperTrend: %s\n", superTrend));

            // Bollinger Band position
            Double bbPctB = tech.getBbPercentB();
            if (bbPctB != null) {
                String bbZone;
                if (bbPctB < 0.2) bbZone = "OVERSOLD";
                else if (bbPctB > 0.8) bbZone = "OVERBOUGHT";
                else bbZone = "MIDDLE";
                sb.append(String.format("- BB %%B: %.2f [%s]\n", bbPctB, bbZone));
            }
        }

        // GEX Regime
        if (quantScore.getGexProfile() != null && quantScore.getGexProfile().getRegime() != null) {
            sb.append(String.format("- GEX Regime: %s\n", quantScore.getGexProfile().getRegime().name()));
        }

        // Exhaustion flags from HistoricalContext
        if (ctx != null) {
            if (ctx.isSellingExhaustion()) {
                sb.append("- EXHAUSTION DETECTED: SELLING_EXHAUSTION\n");
            } else if (ctx.isBuyingExhaustion()) {
                sb.append("- EXHAUSTION DETECTED: BUYING_EXHAUSTION\n");
            }
        }

        // === CONDITIONS MET ===
        sb.append(String.format("\nCONDITIONS:\n"));
        sb.append(String.format("- Required: %d met\n", setup.getRequiredConditionsMet()));
        sb.append(String.format("- Boosters: %d active\n", setup.getBoosterConditionsMet()));
        if (setup.getMetConditions() != null && !setup.getMetConditions().isEmpty()) {
            sb.append("- Triggers: ");
            sb.append(String.join(", ", setup.getMetConditions()));
            sb.append("\n");
        }

        return sb.toString();
    }

    private List<String> generateSetupEntryReasons(ActiveSetup setup) {
        List<String> reasons = new ArrayList<>();
        reasons.add(String.format("Setup %s ready at %.0f%% confidence", setup.getSetupId(),
                setup.getCurrentConfidence() * 100));
        reasons.add(String.format("All %d required conditions met", setup.getRequiredConditionsMet()));
        if (setup.getBoosterConditionsMet() > 0) {
            reasons.add(String.format("%d booster conditions active", setup.getBoosterConditionsMet()));
        }
        reasons.add(String.format("R:R ratio %.1f:1", setup.getRiskRewardRatio()));
        return reasons;
    }

    private SignalRationale buildSetupRationale(ActiveSetup setup, EnrichedQuantScore quantScore,
                                                  MarketIntelligence intelligence) {
        return SignalRationale.builder()
                .headline(generateSetupHeadline(setup))
                .thesis(generateSetupNarrative(setup, intelligence))
                .tradeType(setup.getDirection() == com.kotsin.consumer.enrichment.intelligence.model.SetupDefinition.SetupDirection.LONG ?
                        SignalRationale.TradeType.TREND_FOLLOWING : SignalRationale.TradeType.COUNTER_TREND)
                .trigger(TriggerContext.builder()
                        .type(TriggerContext.TriggerType.SETUP_READY)
                        .description(String.format("Setup %s became ready", setup.getSetupId()))
                        .patternName(setup.getSetupId())
                        .triggerEvents(setup.getMetConditions())
                        .build())
                .confluenceScore(setup.getCurrentConfidence())
                .build();
    }

    private SignalRationale buildSignalRationale(TradingSignal signal, EnrichedQuantScore quantScore,
                                                   MarketIntelligence intelligence) {
        SignalRationale.SignalRationaleBuilder builder = SignalRationale.builder()
                .headline(signal.getHeadline())
                .thesis(signal.getNarrative());

        // Trigger context
        builder.trigger(TriggerContext.builder()
                .type(mapSourceToTriggerType(signal.getSource()))
                .description(signal.getNarrative())
                .triggerEvents(signal.getMatchedEvents())
                .build());

        // Market context
        if (quantScore.getGexProfile() != null || quantScore.getTimeContext() != null) {
            builder.marketContext(MarketContext.builder()
                    .regime(signal.getGexRegime())
                    .sessionQuality(signal.getSession())
                    .build());
        }

        // Technical context
        if (quantScore.getTechnicalContext() != null) {
            TechnicalContext tech = quantScore.getTechnicalContext();
            builder.technicalContext(SignalRationale.TechnicalContext.builder()
                    .superTrendStatus(signal.getSuperTrendDirection())
                    .bbPosition(String.format("%%B: %.2f", tech.getBbPercentB()))
                    .build());
        }

        // Edge
        builder.edge(EdgeDefinition.builder()
                .type(mapSourceToEdgeType(signal.getSource()))
                .description(signal.getEdgeDescription() != null ? signal.getEdgeDescription() : signal.getHeadline())
                .historicalSuccessRate(signal.getHistoricalSuccessRate())
                .build());

        return builder.build();
    }

    private TriggerContext.TriggerType mapSourceToTriggerType(SignalSource source) {
        if (source == null) return TriggerContext.TriggerType.TECHNICAL_SIGNAL;
        return switch (source) {
            case PATTERN -> TriggerContext.TriggerType.PATTERN_COMPLETE;
            case SETUP -> TriggerContext.TriggerType.SETUP_READY;
            case FORECAST -> TriggerContext.TriggerType.FORECAST_TRIGGER;
            case INTELLIGENCE -> TriggerContext.TriggerType.CONFLUENCE_ZONE;
            case MANUAL -> TriggerContext.TriggerType.TECHNICAL_SIGNAL;
        };
    }

    private EdgeDefinition.EdgeType mapSourceToEdgeType(SignalSource source) {
        if (source == null) return EdgeDefinition.EdgeType.TECHNICAL_CONFLUENCE;
        return switch (source) {
            case PATTERN -> EdgeDefinition.EdgeType.PATTERN_RECOGNITION;
            case SETUP -> EdgeDefinition.EdgeType.TECHNICAL_CONFLUENCE;
            case FORECAST -> EdgeDefinition.EdgeType.MOMENTUM;
            case INTELLIGENCE -> EdgeDefinition.EdgeType.SMART_MONEY;
            case MANUAL -> EdgeDefinition.EdgeType.TECHNICAL_CONFLUENCE;
        };
    }

    private List<InvalidationCondition> generateSetupInvalidation(ActiveSetup setup, EnrichedQuantScore quantScore) {
        List<InvalidationCondition> conditions = new ArrayList<>();

        // Price-based invalidation
        double currentPrice = quantScore.getClose();
        boolean isLong = setup.getDirection() == com.kotsin.consumer.enrichment.intelligence.model.SetupDefinition.SetupDirection.LONG;
        double invalidationPrice = isLong ? currentPrice * 0.985 : currentPrice * 1.015;

        conditions.add(InvalidationCondition.builder()
                .condition(String.format("Price breaks %s %.2f", isLong ? "below" : "above", invalidationPrice))
                .monitorType("PRICE")
                .threshold(invalidationPrice)
                .action("EXIT")
                .build());

        // Time-based
        if (setup.getExpiresAt() != null) {
            conditions.add(InvalidationCondition.builder()
                    .condition("Setup expires at " + setup.getExpiresAt())
                    .monitorType("TIME")
                    .action("CANCEL")
                    .build());
        }

        // Event-based
        conditions.add(InvalidationCondition.builder()
                .condition(isLong ? "OFI flips negative" : "OFI flips positive")
                .monitorType("EVENT")
                .action("ALERT")
                .build());

        return conditions;
    }

    private List<PredictedFollowOn> generateSetupPredictions(ActiveSetup setup, MarketIntelligence intelligence) {
        List<PredictedFollowOn> predictions = new ArrayList<>();

        boolean isLong = setup.getDirection() == com.kotsin.consumer.enrichment.intelligence.model.SetupDefinition.SetupDirection.LONG;

        predictions.add(PredictedFollowOn.builder()
                .event(isLong ? "Initial bullish momentum" : "Initial bearish momentum")
                .timeframe("5-15 minutes")
                .probability(0.65)
                .confirmation("Price moves 0.3% in direction")
                .build());

        predictions.add(PredictedFollowOn.builder()
                .event(isLong ? "Break of immediate resistance" : "Break of immediate support")
                .timeframe("15-30 minutes")
                .probability(0.55)
                .confirmation("Clean break with volume")
                .build());

        return predictions;
    }

    private List<PredictedFollowOn> convertPredictedEvents(List<String> predictions) {
        if (predictions == null) return new ArrayList<>();
        return predictions.stream()
                .map(p -> PredictedFollowOn.builder()
                        .event(p)
                        .probability(0.6)
                        .build())
                .collect(Collectors.toList());
    }

    private List<InvalidationCondition> convertInvalidationWatch(List<String> invalidations) {
        if (invalidations == null) return new ArrayList<>();
        return invalidations.stream()
                .map(i -> InvalidationCondition.builder()
                        .condition(i)
                        .monitorType("GENERAL")
                        .action("ALERT")
                        .build())
                .collect(Collectors.toList());
    }

    private List<KeyLevel> generateKeyLevels(EnrichedQuantScore quantScore) {
        List<KeyLevel> levels = new ArrayList<>();

        if (quantScore.getNearestSupportPrice() != null) {
            levels.add(KeyLevel.builder()
                    .price(quantScore.getNearestSupportPrice())
                    .type("SUPPORT")
                    .significance("Nearest support level")
                    .strength(0.7)
                    .build());
        }

        if (quantScore.getNearestResistancePrice() != null) {
            levels.add(KeyLevel.builder()
                    .price(quantScore.getNearestResistancePrice())
                    .type("RESISTANCE")
                    .significance("Nearest resistance level")
                    .strength(0.7)
                    .build());
        }

        if (quantScore.getMaxPainProfile() != null && quantScore.getMaxPainProfile().getMaxPainStrike() > 0) {
            levels.add(KeyLevel.builder()
                    .price(quantScore.getMaxPainProfile().getMaxPainStrike())
                    .type("MAX_PAIN")
                    .significance("Options max pain level")
                    .strength(0.6)
                    .build());
        }

        if (quantScore.getGexProfile() != null) {
            Double gammaFlipLevel = quantScore.getGexProfile().getGammaFlipLevel();
            if (gammaFlipLevel != null && gammaFlipLevel > 0) {
                levels.add(KeyLevel.builder()
                        .price(gammaFlipLevel)
                        .type("GEX_FLIP")
                        .significance("Gamma flip level")
                        .strength(0.8)
                        .build());
            }
        }

        return levels;
    }

    // ======================== CACHE & STATS ========================

    private void updateSignalCache(String familyId, List<TradingSignal> signals) {
        signalCache.put(familyId, signals);

        // Cleanup old entries
        Instant cutoff = Instant.now().minusSeconds(3600);
        signalCache.entrySet().removeIf(entry ->
                entry.getValue().stream().allMatch(s -> s.getGeneratedAt().isBefore(cutoff)));
    }

    private void updateStatistics(List<TradingSignal> allSignals, int patternCount, int setupCount, int forecastCount) {
        synchronized (this) {
            totalSignalsGenerated += allSignals.size();
            patternSignals += patternCount;
            setupSignals += setupCount;
            forecastSignals += forecastCount;
        }
    }

    /**
     * Get cached signals for family
     */
    public List<TradingSignal> getCachedSignals(String familyId) {
        return signalCache.getOrDefault(familyId, Collections.emptyList());
    }

    /**
     * Get generator statistics
     */
    public GeneratorStats getStats() {
        return GeneratorStats.builder()
                .totalSignalsGenerated(totalSignalsGenerated)
                .patternSignals(patternSignals)
                .setupSignals(setupSignals)
                .forecastSignals(forecastSignals)
                .filteredSignals(filteredSignals)
                .cacheSize(signalCache.size())
                .build();
    }

    /**
     * Clear cache
     */
    public void clearCache() {
        signalCache.clear();
    }

    @lombok.Data
    @lombok.Builder
    @lombok.AllArgsConstructor
    public static class GeneratorStats {
        private long totalSignalsGenerated;
        private long patternSignals;
        private long setupSignals;
        private long forecastSignals;
        private long filteredSignals;
        private int cacheSize;

        @Override
        public String toString() {
            return String.format("SignalGenerator: %d total (%d pattern, %d setup, %d forecast, %d filtered), cache: %d",
                    totalSignalsGenerated, patternSignals, setupSignals, forecastSignals, filteredSignals, cacheSize);
        }
    }
}
