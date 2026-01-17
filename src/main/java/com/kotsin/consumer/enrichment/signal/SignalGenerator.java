package com.kotsin.consumer.enrichment.signal;

import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.analyzer.FamilyContextAnalyzer.FamilyContext;
import com.kotsin.consumer.enrichment.analyzer.FamilyContextAnalyzer.FamilyBias;
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
import com.kotsin.consumer.enrichment.model.SessionStructure;
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

            // 5.5 CRITICAL: Apply context-aware modifiers to adjust confidence/direction
            // This is where SessionStructure and FamilyContext actually MODIFY the signals
            // Without this, all the context we built is just display data, not used for interpretation
            applyContextAwareModifiers(signals, quantScore);

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

        // FIX: Add VPIN and Lambda microstructure data from quantScore
        HistoricalContext histCtx = quantScore.getHistoricalContext();
        if (histCtx != null) {
            MetricContext vpinCtx = histCtx.getVpinContext();
            if (vpinCtx != null && vpinCtx.hasEnoughSamples()) {
                builder.vpin(vpinCtx.getCurrentValue());
                builder.vpinPercentile(vpinCtx.getPercentile());
            }
            MetricContext lambdaCtx = histCtx.getLambdaContext();
            if (lambdaCtx != null && lambdaCtx.hasEnoughSamples()) {
                builder.kyleLambda(lambdaCtx.getCurrentValue());
                builder.lambdaPercentile(lambdaCtx.getPercentile());
            }
        }

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

        List<TradingSignal> signals = new ArrayList<>();

        for (ActiveSetup setup : intelligence.getReadySetups()) {
            double confidence = setup.getCurrentConfidence();
            int boosters = setup.getBoosterConditionsMet();

            // Check confidence threshold (0.65 = 65%)
            if (!setup.isActionable(0.65)) {
                log.debug("[SIGNAL_GEN] {} setup {} FILTERED: conf={}% < 65% required",
                        familyId, setup.getSetupId(), String.format("%.1f", confidence * 100));
                continue;
            }

            // Check booster requirement (minimum 1 for testing - was 2 in production)
            // TEST MODE: Reduced to 1 to capture more signals including failure cases
            if (boosters < 1) {
                log.debug("[SIGNAL_GEN] {} setup {} FILTERED: boosters={} < 1 required | metConditions={}",
                        familyId, setup.getSetupId(), boosters, setup.getMetConditions());
                continue;
            }

            // Generate signal
            TradingSignal signal = generateSetupSignal(setup, quantScore, intelligence);
            if (signal != null) {
                signals.add(signal);
                log.debug("[SIGNAL_GEN] {} setup {} ACCEPTED: conf={}%, boosters={}",
                        familyId, setup.getSetupId(), String.format("%.1f", confidence * 100), boosters);
            }
        }

        return signals;
    }

    /**
     * Generate signal from an active setup
     */
    private TradingSignal generateSetupSignal(ActiveSetup setup, EnrichedQuantScore quantScore,
                                                MarketIntelligence intelligence) {
        double currentPrice = quantScore.getClose();
        double atrPct = quantScore.getAtrPct() != null ? quantScore.getAtrPct() : 0.5;

        // FIX: Use microprice for entry when available (orderbook-derived fair value)
        // Microprice is more accurate for real-time/forward testing than candle close
        // Fallback to close if microprice not available or significantly different (>0.5% ATR)
        double entryPrice = currentPrice;
        Double microprice = quantScore.getMicroprice();
        if (microprice != null && microprice > 0) {
            double maxDeviation = currentPrice * atrPct / 100 * 0.5; // 50% of ATR
            if (Math.abs(microprice - currentPrice) <= maxDeviation) {
                entryPrice = microprice;
                log.debug("[SIGNAL_GEN] Using microprice {} instead of close {} for entry (diff={})",
                        String.format("%.2f", microprice), String.format("%.2f", currentPrice),
                        String.format("%.2f", microprice - currentPrice));
            } else {
                log.warn("[SIGNAL_GEN] Microprice {} too far from close {} (diff={:.2f} > maxDev={:.2f}), using close",
                        microprice, currentPrice, Math.abs(microprice - currentPrice), maxDeviation);
            }
        }

        // Calculate trade parameters
        boolean isLong = setup.getDirection() == com.kotsin.consumer.enrichment.intelligence.model.SetupDefinition.SetupDirection.LONG;
        double stopDistance = entryPrice * atrPct / 100 * 1.5;
        double targetDistance = stopDistance * setup.getRiskRewardRatio();
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
                .exchange(quantScore.getExchange())  // FIX: Propagate exchange for MCX/NSE/BSE
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
                // Position sizing - FIX: Use context-aware sizing with win rate and family alignment
                .positionSizeMultiplier(calculatePositionSizeWithContext(
                        setup.getCurrentConfidence(),
                        getActualSuccessRate(setup.getSetupId()),
                        Math.max(quantScore.getFamilyBullishAlignment(), quantScore.getFamilyBearishAlignment())))
                .riskPercentage(1.0);

        // FIX: Add technical context fields directly to signal for TradingSignalPublisher
        TechnicalContext techCtx = quantScore.getTechnicalContext();
        if (techCtx != null) {
            // SuperTrend direction (bullish/bearish)
            builder.superTrendDirection(techCtx.isSuperTrendBullish() ? "BULLISH" : "BEARISH");
        }
        // Nearest support/resistance from confluence result
        Double nearSupport = quantScore.getNearestSupportPrice();
        if (nearSupport != null && nearSupport > 0) {
            builder.nearestSupport(nearSupport);
        }
        Double nearResistance = quantScore.getNearestResistancePrice();
        if (nearResistance != null && nearResistance > 0) {
            builder.nearestResistance(nearResistance);
        }

        // GEX/Options context
        GEXProfile gexProfile = quantScore.getGexProfile();
        if (gexProfile != null) {
            if (gexProfile.getRegime() != null) {
                builder.gexRegime(gexProfile.getRegime().name());
            }
            if (gexProfile.getGammaFlipLevel() != null && gexProfile.getGammaFlipLevel() > 0) {
                builder.gammaFlipLevel(gexProfile.getGammaFlipLevel());
            }
        }
        MaxPainProfile mpProfile = quantScore.getMaxPainProfile();
        if (mpProfile != null && mpProfile.getMaxPainStrike() > 0) {
            builder.maxPainLevel(mpProfile.getMaxPainStrike());
        }

        // FIX: Add VPIN and Lambda microstructure data
        HistoricalContext histCtx = quantScore.getHistoricalContext();
        if (histCtx != null) {
            MetricContext vpinCtx = histCtx.getVpinContext();
            if (vpinCtx != null && vpinCtx.hasEnoughSamples()) {
                builder.vpin(vpinCtx.getCurrentValue());
                builder.vpinPercentile(vpinCtx.getPercentile());
            }
            MetricContext lambdaCtx = histCtx.getLambdaContext();
            if (lambdaCtx != null && lambdaCtx.hasEnoughSamples()) {
                builder.kyleLambda(lambdaCtx.getCurrentValue());
                builder.lambdaPercentile(lambdaCtx.getPercentile());
            }
        }

        // Build rationale
        builder.rationale(buildSetupRationale(setup, quantScore, intelligence));

        // Add invalidation conditions
        builder.invalidationWatch(generateSetupInvalidation(setup, quantScore));

        // Add predicted events
        builder.predictedEvents(generateSetupPredictions(setup, intelligence));

        // FIX: Populate metadata map with session/context values for TradingSignalPublisher
        Map<String, Object> metadata = new HashMap<>();

        // Session Context
        if (quantScore.hasSessionStructure()) {
            metadata.put("sessionPosition", quantScore.getPositionInRange() * 100);
            metadata.put("sessionPositionDesc", quantScore.getSessionPositionDescription());
            metadata.put("vBottomDetected", quantScore.hasVBottomDetected());
            metadata.put("vTopDetected", quantScore.hasVTopDetected());
            metadata.put("failedBreakoutCount", quantScore.getFailedBreakoutCount());
            metadata.put("failedBreakdownCount", quantScore.getFailedBreakdownCount());
        }

        // Current session from TimeContext
        if (quantScore.getTimeContext() != null && quantScore.getTimeContext().getSession() != null) {
            metadata.put("currentSession", quantScore.getTimeContext().getSession().name());
        }

        // Family Context
        if (quantScore.hasFamilyContext()) {
            double bullishAlign = quantScore.getFamilyBullishAlignment() * 100;
            double bearishAlign = quantScore.getFamilyBearishAlignment() * 100;

            // Determine family bias
            // FIX: Distinguish between "balanced" neutral and "no conviction" neutral
            double maxAlign = Math.max(bullishAlign, bearishAlign);
            String familyBias;
            if (quantScore.isFamilyFullyBullish()) {
                familyBias = "BULLISH";
                metadata.put("fullyAligned", true);
            } else if (quantScore.isFamilyFullyBearish()) {
                familyBias = "BEARISH";
                metadata.put("fullyAligned", true);
            } else if (maxAlign < 30) {
                // FIX: Very low alignment = NO CONVICTION (less than 30% of instruments agree on any direction)
                familyBias = "NO_CONVICTION";
                metadata.put("lowConviction", true);
                log.warn("[FAMILY_BIAS] {} has NO_CONVICTION - maxAlignment={}% (very weak signal quality)",
                        setup.getFamilyId(), String.format("%.0f", maxAlign));
            } else if (bullishAlign > bearishAlign + 20) {
                familyBias = "WEAK_BULLISH";
            } else if (bearishAlign > bullishAlign + 20) {
                familyBias = "WEAK_BEARISH";
            } else {
                // Balanced alignment (neither direction dominates by 20%)
                familyBias = "NEUTRAL";
            }
            metadata.put("familyBias", familyBias);
            metadata.put("familyAlignment", maxAlign);

            // Divergence
            metadata.put("hasDivergence", quantScore.hasFamilyDivergence());
            if (quantScore.hasFamilyDivergence()) {
                metadata.put("divergences", quantScore.getFamilyDivergences());
            }

            // Squeeze setups
            metadata.put("shortSqueezeSetup", quantScore.hasShortSqueezeSetup());
            metadata.put("longSqueezeSetup", quantScore.hasLongSqueezeSetup());

            // Interpretation
            String interpretation = quantScore.getFamilyContextInterpretation();
            if (interpretation != null) {
                metadata.put("familyInterpretation", interpretation);
            }
        }

        // Technical Context (reuse techCtx from above)
        if (techCtx != null) {
            metadata.put("superTrendDirection", techCtx.isSuperTrendBullish() ? "BULLISH" : "BEARISH");
            metadata.put("superTrendFlip", techCtx.isSuperTrendFlip());
            metadata.put("bbPercentB", techCtx.getBbPercentB());
            metadata.put("bbSqueeze", techCtx.isBbSqueezing());
            if (techCtx.getDailyPivot() != null && techCtx.getDailyPivot() > 0) {
                metadata.put("dailyPivot", techCtx.getDailyPivot());
            }
        }
        // Support/Resistance from confluence
        if (nearSupport != null && nearSupport > 0) {
            metadata.put("nearestSupport", nearSupport);
        }
        if (nearResistance != null && nearResistance > 0) {
            metadata.put("nearestResistance", nearResistance);
        }

        // GEX/Options Context (reuse gexProfile from above)
        if (gexProfile != null) {
            if (gexProfile.getRegime() != null) {
                metadata.put("gexRegime", gexProfile.getRegime().name());
            }
            if (gexProfile.getGammaFlipLevel() != null && gexProfile.getGammaFlipLevel() > 0) {
                metadata.put("gammaFlipLevel", gexProfile.getGammaFlipLevel());
            }
        }
        if (mpProfile != null && mpProfile.getMaxPainStrike() > 0) {
            metadata.put("maxPainLevel", mpProfile.getMaxPainStrike());
        }

        // Event Tracking
        List<DetectedEvent> detectedEvents = quantScore.getDetectedEvents();
        if (detectedEvents != null && !detectedEvents.isEmpty()) {
            metadata.put("triggerEvents", detectedEvents.stream()
                    .map(e -> e.getEventType().name())
                    .collect(Collectors.toList()));
            metadata.put("eventCount", detectedEvents.size());
        }

        // Context modifier (from intelligence action confidence)
        if (intelligence != null && intelligence.getRecommendation() != null) {
            ActionRecommendation rec = intelligence.getRecommendation();
            metadata.put("contextModifier", rec.getConfidence());
        }

        builder.metadata(metadata);

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

        // FIX: Use microprice for entry when available (orderbook-derived fair value)
        double entryPrice = currentPrice;
        Double microprice = quantScore.getMicroprice();
        if (microprice != null && microprice > 0) {
            double maxDeviation = currentPrice * atrPct / 100 * 0.5;
            if (Math.abs(microprice - currentPrice) <= maxDeviation) {
                entryPrice = microprice;
            }
        }

        // Determine direction
        boolean isLong = prediction.getDirection() == PredictedEvent.PredictionDirection.BULLISH;

        // Use prediction targets if available
        double targetPrice = prediction.getTargetPrice() != null ? prediction.getTargetPrice() :
                (isLong ? entryPrice * 1.01 : entryPrice * 0.99);
        double invalidationPrice = prediction.getInvalidationPrice() != null ? prediction.getInvalidationPrice() :
                (isLong ? entryPrice * 0.995 : entryPrice * 1.005);

        double stopDistance = Math.abs(entryPrice - invalidationPrice);
        double target1 = isLong ? entryPrice + (stopDistance * 0.7) : entryPrice - (stopDistance * 0.7);
        double target3 = isLong ? entryPrice + (stopDistance * 2.5) : entryPrice - (stopDistance * 2.5);

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
                .entryPrice(entryPrice)  // FIX: Use microprice-based entry when available
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

        // FIX: Use recommended entry if available, else microprice, else close
        double entryPrice;
        if (rec.getSuggestedEntry() != null) {
            entryPrice = rec.getSuggestedEntry();
        } else {
            // Use microprice (orderbook fair value) when available
            Double microprice = quantScore.getMicroprice();
            entryPrice = (microprice != null && microprice > 0) ? microprice : currentPrice;
        }
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

    // ======================== CONTEXT-AWARE MODIFIERS ========================

    /**
     * CRITICAL: Apply context-aware modifiers to signal confidence and direction.
     *
     * THIS IS THE KEY MISSING PIECE that makes all the context useful.
     *
     * Without this method:
     * - SessionStructure is just display data
     * - FamilyContext alignment/divergence is ignored
     * - Same signal at session low vs session high has same confidence (WRONG!)
     *
     * With this method:
     * - LONG signal at session LOW gets boosted (buying support)
     * - LONG signal at session HIGH gets reduced (buying resistance)
     * - Family alignment boosts confidence
     * - Divergence can flip signal direction
     * - Special events (FAILED_BREAKOUT, SESSION_REVERSAL) modify confidence
     */
    private void applyContextAwareModifiers(List<TradingSignal> signals, EnrichedQuantScore quantScore) {
        if (signals == null || signals.isEmpty()) {
            log.trace("[CONTEXT_MOD] No signals to modify");
            return;
        }

        boolean hasSession = quantScore.hasSessionStructure();
        boolean hasFamily = quantScore.hasFamilyContext();
        boolean hasEvents = quantScore.hasEvents();

        log.debug("[CONTEXT_MOD] Applying modifiers to {} signals | hasSession={} | hasFamily={} | hasEvents={}",
                signals.size(), hasSession, hasFamily, hasEvents);

        for (TradingSignal signal : signals) {
            double originalConfidence = signal.getConfidence();
            double modifiedConfidence = originalConfidence;
            StringBuilder modifierLog = new StringBuilder();

            // 1. Apply Session Structure modifier (position in range)
            if (quantScore.hasSessionStructure()) {
                double sessionModifier = applySessionStructureModifier(signal, quantScore.getSessionStructure(), modifierLog);
                modifiedConfidence *= sessionModifier;
            }

            // 1b. FIX: Apply exhaustion requirement at session extremes
            // Signals at extremes (>85% or <15%) without exhaustion confirmation are risky
            if (quantScore.hasSessionStructure() && quantScore.getHistoricalContext() != null) {
                double exhaustionModifier = applyExhaustionRequirementModifier(
                        signal, quantScore.getSessionStructure(), quantScore.getHistoricalContext(), modifierLog);
                modifiedConfidence *= exhaustionModifier;
            }

            // 2. Apply Family Context modifier (alignment/divergence)
            if (quantScore.hasFamilyContext()) {
                double familyModifier = applyFamilyContextModifier(signal, quantScore.getFamilyContext(), modifierLog);
                modifiedConfidence *= familyModifier;
            }

            // 3. Apply Event-based modifier (special events like FAILED_BREAKOUT)
            if (quantScore.hasEvents()) {
                double eventModifier = applyEventBasedModifier(signal, quantScore.getDetectedEvents(), modifierLog);
                modifiedConfidence *= eventModifier;
            }

            // Clamp confidence to valid range
            modifiedConfidence = Math.max(0.1, Math.min(1.0, modifiedConfidence));

            // Update signal confidence - quality score is calculated dynamically
            // based on confidence, so it will automatically reflect the new value
            signal.setConfidence(modifiedConfidence);

            // FIX: Update ConfidenceBreakdown with context modifier for proper output
            // contextConfidence represents the combined effect of all context modifiers
            double contextModifier = originalConfidence > 0 ? modifiedConfidence / originalConfidence : 1.0;
            if (signal.getConfidenceBreakdown() != null) {
                signal.getConfidenceBreakdown().setContextConfidence(contextModifier);
            }

            // Log modifications
            double change = modifiedConfidence - originalConfidence;
            if (Math.abs(change) > 0.05) {
                log.info("[CONTEXT_MOD] {} {} | conf: {:.0f}% → {:.0f}% ({:+.0f}%) | {}",
                        signal.getFamilyId(), signal.getDirection(),
                        originalConfidence * 100, modifiedConfidence * 100, change * 100,
                        modifierLog.toString().trim());
            } else {
                log.trace("[CONTEXT_MOD] {} {} | conf unchanged at {:.0f}% | {}",
                        signal.getFamilyId(), signal.getDirection(),
                        originalConfidence * 100,
                        modifierLog.length() > 0 ? modifierLog.toString().trim() : "no modifiers applied");
            }
        }
    }

    /**
     * Apply session structure modifier based on position in session range.
     *
     * KEY INSIGHT: The same signal means DIFFERENT things at different positions:
     * - LONG at session LOW (pos < 0.15) = buying support = GOOD → boost 1.3x
     * - LONG at session HIGH (pos > 0.85) = buying resistance = RISKY → reduce 0.6x
     * - SHORT at session HIGH = selling resistance = GOOD → boost 1.3x
     * - SHORT at session LOW = selling support = RISKY → reduce 0.6x
     */
    private double applySessionStructureModifier(TradingSignal signal, SessionStructure session,
                                                   StringBuilder modLog) {
        double modifier = 1.0;
        double positionInRange = session.getPositionInRange();
        boolean isLong = signal.getDirection() == Direction.LONG;

        // Use SessionStructure's built-in modifier calculation
        modifier = session.getStructureModifier(isLong);

        log.trace("[CONTEXT_MOD:SESSION] {} | pos={:.0f}% | isLong={} | baseModifier={:.2f}",
                signal.getFamilyId(), positionInRange * 100, isLong, modifier);

        // Additional boost for V-pattern detection
        if (session.isVBottomDetected() && isLong) {
            modifier *= 1.15;
            modLog.append("V-BOTTOM +15% ");
        }
        if (session.isVTopDetected() && !isLong) {
            modifier *= 1.15;
            modLog.append("V-TOP +15% ");
        }

        // Failed breakout/breakdown adjustments
        if (session.getFailedBreakoutCount() >= 2 && !isLong) {
            modifier *= 1.1;
            modLog.append("FAILED_BREAKOUTS +10% ");
        }
        if (session.getFailedBreakdownCount() >= 2 && isLong) {
            modifier *= 1.1;
            modLog.append("FAILED_BREAKDOWNS +10% ");
        }

        // Log position context
        if (session.isAtSessionLow()) {
            modLog.append(String.format("AT_LOW(%.0f%%) ", positionInRange * 100));
            modLog.append(isLong ? "ALIGNED " : "COUNTER ");
        } else if (session.isAtSessionHigh()) {
            modLog.append(String.format("AT_HIGH(%.0f%%) ", positionInRange * 100));
            modLog.append(!isLong ? "ALIGNED " : "COUNTER ");
        }

        return Math.max(0.5, Math.min(1.5, modifier));
    }

    /**
     * FIX: Apply exhaustion requirement at session extremes.
     *
     * Signals at session extremes (>85% or <15%) are RISKY without exhaustion confirmation.
     * - SHORT at session HIGH (>85%) without buying exhaustion → potential short squeeze → reduce
     * - LONG at session LOW (<15%) without selling exhaustion → potential waterfall → reduce
     *
     * Exhaustion = velocity reversal = safer reversal signal
     * No exhaustion at extreme = chasing price = dangerous
     */
    private double applyExhaustionRequirementModifier(TradingSignal signal, SessionStructure session,
                                                       HistoricalContext histCtx, StringBuilder modLog) {
        double modifier = 1.0;
        double positionInRange = session.getPositionInRange();
        boolean isLong = signal.getDirection() == Direction.LONG;

        // Check if at session extreme
        boolean atSessionHigh = positionInRange >= 0.85;
        boolean atSessionLow = positionInRange <= 0.15;

        // Get exhaustion status
        boolean hasSellingExhaustion = histCtx.isSellingExhaustion();
        boolean hasBuyingExhaustion = histCtx.isBuyingExhaustion();

        if (atSessionHigh && !isLong) {
            // SHORT at session high - should have buying exhaustion
            if (!hasBuyingExhaustion) {
                modifier *= 0.75; // 25% penalty: shorting at high without exhaustion = risky
                modLog.append("SHORT_NO_EXHAUST_AT_HIGH -25% ");
                log.warn("[CONTEXT_MOD:EXHAUST] {} SHORT at {}% session position WITHOUT buying exhaustion - risky!",
                        signal.getFamilyId(), String.format("%.0f", positionInRange * 100));
            } else {
                modifier *= 1.1; // 10% boost: exhaustion confirmed at extreme = good reversal signal
                modLog.append("SHORT_WITH_EXHAUST +10% ");
            }
        }

        if (atSessionLow && isLong) {
            // LONG at session low - should have selling exhaustion
            if (!hasSellingExhaustion) {
                modifier *= 0.75; // 25% penalty: buying at low without exhaustion = risky
                modLog.append("LONG_NO_EXHAUST_AT_LOW -25% ");
                log.warn("[CONTEXT_MOD:EXHAUST] {} LONG at {}% session position WITHOUT selling exhaustion - risky!",
                        signal.getFamilyId(), String.format("%.0f", positionInRange * 100));
            } else {
                modifier *= 1.1; // 10% boost: exhaustion confirmed at extreme = good reversal signal
                modLog.append("LONG_WITH_EXHAUST +10% ");
            }
        }

        return modifier;
    }

    /**
     * Apply family context modifier based on multi-instrument alignment.
     *
     * FAMILY ALIGNMENT = STRONGEST SIGNALS
     * When equity + futures + options all agree:
     * - Bullish alignment + LONG signal = boost confidence
     * - Bearish alignment + SHORT signal = boost confidence
     * - Alignment opposite to signal = reduce confidence
     * - LOW alignment (< 30%) = reduce confidence (weak conviction)
     *
     * DIVERGENCE = REVERSAL WARNING
     * When options flow contradicts price:
     * - Price falling + call accumulation = bullish divergence
     * - Price rising + put accumulation = bearish divergence
     */
    private double applyFamilyContextModifier(TradingSignal signal, FamilyContext familyContext,
                                                StringBuilder modLog) {
        double modifier = 1.0;
        boolean isLong = signal.getDirection() == Direction.LONG;

        // Alignment check
        double bullishAlignment = familyContext.getBullishAlignment();
        double bearishAlignment = familyContext.getBearishAlignment();
        double maxAlignment = Math.max(bullishAlignment, bearishAlignment);

        log.trace("[CONTEXT_MOD:FAMILY] {} | bullAlign={:.0f}% | bearAlign={:.0f}% | bias={} | isLong={}",
                signal.getFamilyId(), bullishAlignment * 100, bearishAlignment * 100,
                familyContext.getOverallBias(), isLong);

        // FIX: Penalize signals when family alignment is VERY LOW (< 30%)
        // This means only ~30% of instruments agree with any direction - weak conviction
        if (maxAlignment < 0.30) {
            modifier *= 0.85; // 15% reduction for weak family context
            modLog.append(String.format("WEAK_FAMILY_ALIGN(%.0f%%) -15%% ", maxAlignment * 100));
            log.warn("[CONTEXT_MOD:FAMILY] {} {} signal has WEAK family alignment ({}%) - reduced confidence",
                    signal.getFamilyId(), signal.getDirection(), String.format("%.0f", maxAlignment * 100));
        }

        // FIX: Additional penalty when signal direction CONFLICTS with family bias
        FamilyBias bias = familyContext.getOverallBias();
        if (bias != null && bias != FamilyBias.NEUTRAL) {
            boolean biasBullish = bias == FamilyBias.BULLISH || bias == FamilyBias.WEAK_BULLISH;
            boolean biasBearish = bias == FamilyBias.BEARISH || bias == FamilyBias.WEAK_BEARISH;

            if (isLong && biasBearish) {
                modifier *= 0.85; // LONG signal vs BEARISH family = reduce
                modLog.append("LONG_VS_BEAR_BIAS -15% ");
                log.warn("[CONTEXT_MOD:FAMILY] {} LONG signal conflicts with {} family bias - reduced confidence",
                        signal.getFamilyId(), bias);
            } else if (!isLong && biasBullish) {
                modifier *= 0.85; // SHORT signal vs BULLISH family = reduce
                modLog.append("SHORT_VS_BULL_BIAS -15% ");
                log.warn("[CONTEXT_MOD:FAMILY] {} SHORT signal conflicts with {} family bias - reduced confidence",
                        signal.getFamilyId(), bias);
            }
        }

        if (isLong && bullishAlignment >= 0.6) {
            // Long signal with bullish family alignment = boost
            modifier *= 1.0 + (bullishAlignment - 0.5) * 0.4; // Up to 1.2x boost
            modLog.append(String.format("BULLISH_ALIGN(%.0f%%) +%.0f%% ",
                    bullishAlignment * 100, (modifier - 1) * 100));
        } else if (isLong && bearishAlignment >= 0.6) {
            // Long signal with bearish family alignment = reduce
            modifier *= 1.0 - (bearishAlignment - 0.5) * 0.4; // Down to 0.8x
            modLog.append(String.format("BEARISH_ALIGN(%.0f%%) %.0f%% ",
                    bearishAlignment * 100, (modifier - 1) * 100));
        }

        if (!isLong && bearishAlignment >= 0.6) {
            // Short signal with bearish family alignment = boost
            modifier *= 1.0 + (bearishAlignment - 0.5) * 0.4;
            modLog.append(String.format("BEARISH_ALIGN(%.0f%%) +%.0f%% ",
                    bearishAlignment * 100, (modifier - 1) * 100));
        } else if (!isLong && bullishAlignment >= 0.6) {
            // Short signal with bullish family alignment = reduce
            modifier *= 1.0 - (bullishAlignment - 0.5) * 0.4;
            modLog.append(String.format("BULLISH_ALIGN(%.0f%%) %.0f%% ",
                    bullishAlignment * 100, (modifier - 1) * 100));
        }

        // Full alignment = extra boost
        if (familyContext.isFullyAlignedBullish() && isLong) {
            modifier *= 1.1;
            modLog.append("FULL_BULL +10% ");
        }
        if (familyContext.isFullyAlignedBearish() && !isLong) {
            modifier *= 1.1;
            modLog.append("FULL_BEAR +10% ");
        }

        // Divergence handling
        if (familyContext.isHasDivergence()) {
            List<String> divergences = familyContext.getDivergences();
            boolean hasBullishDivergence = divergences.stream()
                    .anyMatch(d -> d.contains("BULLISH"));
            boolean hasBearishDivergence = divergences.stream()
                    .anyMatch(d -> d.contains("BEARISH"));

            // Bullish divergence supports long signals
            if (hasBullishDivergence && isLong) {
                modifier *= 1.15;
                modLog.append("BULL_DIVERGENCE +15% ");
            } else if (hasBullishDivergence && !isLong) {
                modifier *= 0.85;
                modLog.append("BULL_DIVERGENCE -15% ");
            }

            // Bearish divergence supports short signals
            if (hasBearishDivergence && !isLong) {
                modifier *= 1.15;
                modLog.append("BEAR_DIVERGENCE +15% ");
            } else if (hasBearishDivergence && isLong) {
                modifier *= 0.85;
                modLog.append("BEAR_DIVERGENCE -15% ");
            }
        }

        // Squeeze setups
        if (familyContext.isShortSqueezeSetup() && isLong) {
            modifier *= 1.2;
            modLog.append("SHORT_SQUEEZE_SETUP +20% ");
        }
        if (familyContext.isLongSqueezeSetup() && !isLong) {
            modifier *= 1.2;
            modLog.append("LONG_SQUEEZE_SETUP +20% ");
        }

        return Math.max(0.5, Math.min(1.5, modifier));
    }

    /**
     * Apply event-based modifier for special context-aware events.
     *
     * These events are HIGH-CONFIDENCE reversal signals:
     * - FAILED_BREAKOUT_BULL → 60-80% probability of bearish reversal
     * - FAILED_BREAKOUT_BEAR → 60-80% probability of bullish reversal
     * - SESSION_LOW_REVERSAL → V-bottom confirmed with multi-instrument
     * - SESSION_HIGH_REVERSAL → V-top confirmed with multi-instrument
     */
    private double applyEventBasedModifier(TradingSignal signal, List<DetectedEvent> events,
                                            StringBuilder modLog) {
        double modifier = 1.0;
        boolean isLong = signal.getDirection() == Direction.LONG;

        log.trace("[CONTEXT_MOD:EVENTS] {} | eventCount={} | isLong={}",
                signal.getFamilyId(), events.size(), isLong);

        for (DetectedEvent event : events) {
            switch (event.getEventType()) {
                case FAILED_BREAKOUT_BULL:
                    // Failed bull breakout = bearish reversal
                    if (!isLong) {
                        modifier *= 1.25;
                        modLog.append("FAILED_BREAKOUT_BULL +25% ");
                    } else {
                        modifier *= 0.7;
                        modLog.append("FAILED_BREAKOUT_BULL -30% ");
                    }
                    break;

                case FAILED_BREAKOUT_BEAR:
                    // Failed bear breakdown = bullish reversal
                    if (isLong) {
                        modifier *= 1.25;
                        modLog.append("FAILED_BREAKOUT_BEAR +25% ");
                    } else {
                        modifier *= 0.7;
                        modLog.append("FAILED_BREAKOUT_BEAR -30% ");
                    }
                    break;

                case SESSION_LOW_REVERSAL:
                    // V-bottom = bullish
                    if (isLong) {
                        modifier *= 1.3;
                        modLog.append("SESSION_LOW_REVERSAL +30% ");
                    } else {
                        modifier *= 0.6;
                        modLog.append("SESSION_LOW_REVERSAL -40% ");
                    }
                    break;

                case SESSION_HIGH_REVERSAL:
                    // V-top = bearish
                    if (!isLong) {
                        modifier *= 1.3;
                        modLog.append("SESSION_HIGH_REVERSAL +30% ");
                    } else {
                        modifier *= 0.6;
                        modLog.append("SESSION_HIGH_REVERSAL -40% ");
                    }
                    break;

                case FAMILY_BULLISH_ALIGNMENT:
                    if (isLong) {
                        modifier *= 1.15;
                        modLog.append("FAMILY_BULL +15% ");
                    }
                    break;

                case FAMILY_BEARISH_ALIGNMENT:
                    if (!isLong) {
                        modifier *= 1.15;
                        modLog.append("FAMILY_BEAR +15% ");
                    }
                    break;

                case SHORT_SQUEEZE_SETUP:
                    if (isLong) {
                        modifier *= 1.2;
                        modLog.append("SQUEEZE_SETUP +20% ");
                    }
                    break;

                case LONG_SQUEEZE_SETUP:
                    if (!isLong) {
                        modifier *= 1.2;
                        modLog.append("SQUEEZE_SETUP +20% ");
                    }
                    break;

                case OPTIONS_PRICE_DIVERGENCE:
                    // Direction based on event direction
                    if (event.isBullish() && isLong) {
                        modifier *= 1.15;
                        modLog.append("OPT_DIVERGENCE_BULL +15% ");
                    } else if (event.isBearish() && !isLong) {
                        modifier *= 1.15;
                        modLog.append("OPT_DIVERGENCE_BEAR +15% ");
                    }
                    break;

                case SELLING_EXHAUSTION:
                    if (isLong) {
                        modifier *= 1.1;
                        modLog.append("SELL_EXHAUSTION +10% ");
                    }
                    break;

                case BUYING_EXHAUSTION:
                    if (!isLong) {
                        modifier *= 1.1;
                        modLog.append("BUY_EXHAUSTION +10% ");
                    }
                    break;

                default:
                    // Other events don't modify confidence
                    break;
            }
        }

        return Math.max(0.4, Math.min(1.6, modifier));
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
        // FIX: More conservative position sizing
        // Only allow multiplier > 1.0 for HIGH confidence signals
        // This prevents over-sizing on weak signals
        if (confidence >= 0.85) return 1.3;  // Only very high confidence gets boost
        if (confidence >= 0.75) return 1.1;  // High confidence gets small boost
        if (confidence >= 0.65) return 1.0;  // Good confidence = standard size
        if (confidence >= 0.55) return 0.8;  // Moderate confidence = reduced
        if (confidence >= 0.45) return 0.6;  // Low confidence = significantly reduced
        return 0.5;  // Minimum size for lowest confidence
    }

    /**
     * Calculate position size with additional context factors
     * FIX: Consider win rate and family alignment in sizing
     */
    private double calculatePositionSizeWithContext(double confidence, double historicalWinRate,
                                                    double familyAlignment) {
        double baseSize = calculatePositionSize(confidence);

        // FIX: NEVER allow multiplier > 1.0 if no statistical edge
        if (historicalWinRate > 0 && historicalWinRate < 0.55) {
            // No edge: cap at 1.0 and reduce further
            baseSize = Math.min(baseSize, 1.0) * 0.8;
        }

        // FIX: Reduce size if family alignment is weak
        if (familyAlignment > 0 && familyAlignment < 0.30) {
            baseSize *= 0.85; // 15% reduction for weak context
        }

        // Ensure reasonable bounds
        return Math.max(0.3, Math.min(1.5, baseSize));
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
     * Build comprehensive data-driven narrative with actual market data numbers.
     *
     * THIS IS THE KEY TO ACTIONABLE SIGNALS - no generic text, only real values.
     *
     * BEFORE: "Exhaustion at extremes often precedes reversal"
     * AFTER:  "SELLING_EXHAUSTION: OFI flipped from -2847 to +1523 (velocity: +4370).
     *          Position: 8% of session range (AT_SESSION_LOW).
     *          Family: 85% BULLISH aligned. PCR: 1.72 (extreme fear)."
     */
    private String buildDataDrivenNarrative(EnrichedQuantScore quantScore, ActiveSetup setup) {
        StringBuilder sb = new StringBuilder();
        Double close = quantScore.getClose();

        // Direction and setup
        sb.append(String.format("%s %s SIGNAL\n\n", setup.getDirection().name(), setup.getSetupId()));

        // ========== SECTION 1: SESSION CONTEXT (CRITICAL) ==========
        sb.append("SESSION CONTEXT:\n");

        if (quantScore.hasSessionStructure()) {
            double positionInRange = quantScore.getPositionInRange() * 100;
            String positionDesc = quantScore.getSessionPositionDescription();
            sb.append(String.format("- Position in Range: %.0f%% [%s]\n", positionInRange, positionDesc));

            if (quantScore.isAtSessionLow()) {
                sb.append("- AT SESSION LOW - bullish context for longs\n");
            } else if (quantScore.isAtSessionHigh()) {
                sb.append("- AT SESSION HIGH - bearish context for shorts\n");
            }

            if (quantScore.hasVBottomDetected()) {
                sb.append("- V-BOTTOM DETECTED - reversal signal\n");
            }
            if (quantScore.hasVTopDetected()) {
                sb.append("- V-TOP DETECTED - distribution signal\n");
            }

            int failedBreakouts = quantScore.getFailedBreakoutCount();
            int failedBreakdowns = quantScore.getFailedBreakdownCount();
            if (failedBreakouts > 0) {
                sb.append(String.format("- Failed Breakouts: %d (resistance holding)\n", failedBreakouts));
            }
            if (failedBreakdowns > 0) {
                sb.append(String.format("- Failed Breakdowns: %d (support holding)\n", failedBreakdowns));
            }
        }

        // ========== SECTION 2: FAMILY ALIGNMENT ==========
        if (quantScore.hasFamilyContext()) {
            sb.append("\nFAMILY ALIGNMENT:\n");

            double bullishAlign = quantScore.getFamilyBullishAlignment() * 100;
            double bearishAlign = quantScore.getFamilyBearishAlignment() * 100;

            if (bullishAlign > bearishAlign) {
                sb.append(String.format("- Bullish Alignment: %.0f%%", bullishAlign));
                if (quantScore.isFamilyFullyBullish()) sb.append(" [FULLY ALIGNED]");
                sb.append("\n");
            } else if (bearishAlign > bullishAlign) {
                sb.append(String.format("- Bearish Alignment: %.0f%%", bearishAlign));
                if (quantScore.isFamilyFullyBearish()) sb.append(" [FULLY ALIGNED]");
                sb.append("\n");
            } else {
                sb.append("- Mixed alignment (neutral)\n");
            }

            if (quantScore.hasFamilyDivergence()) {
                sb.append("- DIVERGENCE DETECTED: ");
                sb.append(String.join(", ", quantScore.getFamilyDivergences()));
                sb.append("\n");
            }

            if (quantScore.hasShortSqueezeSetup()) {
                sb.append("- SHORT SQUEEZE SETUP: Trapped shorts at support\n");
            }
            if (quantScore.hasLongSqueezeSetup()) {
                sb.append("- LONG SQUEEZE SETUP: Trapped longs at resistance\n");
            }

            String interpretation = quantScore.getFamilyContextInterpretation();
            if (interpretation != null && !interpretation.isEmpty()) {
                sb.append(String.format("- Interpretation: %s\n", interpretation));
            }
        }

        // ========== SECTION 3: ORDER FLOW ==========
        sb.append("\nORDER FLOW:\n");

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

            if (ofiCtx.getPercentile() > 0) {
                // FIX: Percentile is already [0-100], don't multiply by 100 again
                sb.append(String.format("- OFI Percentile: %.0f%%\n", ofiCtx.getPercentile()));
            }
        }

        if (ctx != null && ctx.getVolumeDeltaContext() != null) {
            MetricContext vdCtx = ctx.getVolumeDeltaContext();
            double volumeDelta = vdCtx.getCurrentValue();
            // FIX: Volume Delta is raw (buyVol - sellVol), show as z-score and percentile instead
            String direction = volumeDelta > 0 ? "BUY_DOMINANT" : volumeDelta < 0 ? "SELL_DOMINANT" : "NEUTRAL";
            sb.append(String.format("- Volume Delta: %s (z=%.2f, P%.0f)\n",
                    direction, vdCtx.getZscore(), vdCtx.getPercentile()));
        }

        if (ctx != null) {
            if (ctx.isSellingExhaustion()) {
                sb.append("- EXHAUSTION: SELLING_EXHAUSTION (sellers giving up)\n");
            } else if (ctx.isBuyingExhaustion()) {
                sb.append("- EXHAUSTION: BUYING_EXHAUSTION (buyers giving up)\n");
            }
            if (ctx.isAbsorptionDetected()) {
                sb.append("- ABSORPTION: Institutional accumulation detected\n");
            }
        }

        // ========== SECTION 4: SWING STRUCTURE ==========
        if (quantScore.hasSwingAnalysis()) {
            sb.append("\nSWING STRUCTURE:\n");
            sb.append(String.format("- Trend: %s\n", quantScore.getSwingTrendStructure()));

            Double swingSupport = quantScore.getSwingSupport();
            Double swingResistance = quantScore.getSwingResistance();

            if (swingSupport != null && close != null) {
                double distPct = (close - swingSupport) / close * 100;
                sb.append(String.format("- Swing Support: %.2f (%.1f%% away)\n", swingSupport, distPct));
            }
            if (swingResistance != null && close != null) {
                double distPct = (swingResistance - close) / close * 100;
                sb.append(String.format("- Swing Resistance: %.2f (%.1f%% away)\n", swingResistance, distPct));
            }

            if (quantScore.isSwingHighBroken()) {
                sb.append("- SWING HIGH BROKEN - bullish breakout\n");
            }
            if (quantScore.isSwingLowBroken()) {
                sb.append("- SWING LOW BROKEN - bearish breakdown\n");
            }
        }

        // ========== SECTION 5: TECHNICAL LEVELS ==========
        sb.append("\nTECHNICAL LEVELS:\n");

        TechnicalContext tech = quantScore.getTechnicalContext();
        if (tech != null && close != null && close > 0) {
            // SuperTrend
            String superTrend = tech.isSuperTrendBullish() ? "BULLISH" : "BEARISH";
            sb.append(String.format("- SuperTrend: %s", superTrend));
            if (tech.isSuperTrendFlip()) sb.append(" [JUST FLIPPED]");
            sb.append("\n");

            // Bollinger Band position
            Double bbPctB = tech.getBbPercentB();
            if (bbPctB != null) {
                String bbZone;
                if (bbPctB < 0.2) bbZone = "OVERSOLD";
                else if (bbPctB > 0.8) bbZone = "OVERBOUGHT";
                else bbZone = "MIDDLE";
                sb.append(String.format("- BB %%B: %.2f [%s]\n", bbPctB, bbZone));
            }

            if (tech.isBbSqueezing()) {
                sb.append("- BB SQUEEZE: Low volatility, breakout expected\n");
            }
        }

        // MTF Levels
        if (quantScore.hasMtfLevels()) {
            Double mtfSupport = quantScore.getMtfNearestSupport();
            Double mtfResistance = quantScore.getMtfNearestResistance();

            if (mtfSupport != null && close != null) {
                Double distPct = quantScore.getMtfSupportDistancePct();
                sb.append(String.format("- MTF Support: %.2f (%.1f%% away)\n", mtfSupport, distPct));
            }
            if (mtfResistance != null && close != null) {
                Double distPct = quantScore.getMtfResistanceDistancePct();
                sb.append(String.format("- MTF Resistance: %.2f (%.1f%% away)\n", mtfResistance, distPct));
            }

            if (quantScore.isNearMtfLevel()) {
                sb.append("- NEAR MTF LEVEL: Price at significant level\n");
            }

            Double dailyPivot = quantScore.getDailyPivot();
            if (dailyPivot != null) {
                sb.append(String.format("- Daily Pivot: %.2f\n", dailyPivot));
            }

            if (quantScore.isDailyCprNarrow()) {
                sb.append("- NARROW CPR: Breakout expected today\n");
            }
        }

        // ========== SECTION 6: GEX & OPTIONS ==========
        if (quantScore.getGexProfile() != null && quantScore.getGexProfile().getRegime() != null) {
            sb.append("\nOPTIONS CONTEXT:\n");
            sb.append(String.format("- GEX Regime: %s\n", quantScore.getGexProfile().getRegime().name()));

            if (quantScore.getGexProfile().isNearGammaFlip()) {
                Double flipLevel = quantScore.getGexProfile().getGammaFlipLevel();
                sb.append(String.format("- NEAR GAMMA FLIP: %.2f\n", flipLevel != null ? flipLevel : 0));
            }
        }

        if (quantScore.getMaxPainProfile() != null && quantScore.getMaxPainProfile().getMaxPainStrike() > 0) {
            sb.append(String.format("- Max Pain: %.2f (%.1f%% away)\n",
                    quantScore.getMaxPainProfile().getMaxPainStrike(),
                    quantScore.getMaxPainProfile().getAbsDistancePct()));

            if (quantScore.getMaxPainProfile().isInPinZone()) {
                sb.append("- IN PIN ZONE: Price may gravitate to max pain\n");
            }
        }

        // ========== SECTION 7: SETUP CONDITIONS ==========
        sb.append("\nSETUP CONDITIONS:\n");
        sb.append(String.format("- Required: %d met\n", setup.getRequiredConditionsMet()));
        sb.append(String.format("- Boosters: %d active\n", setup.getBoosterConditionsMet()));
        sb.append(String.format("- Confidence: %.0f%%\n", setup.getCurrentConfidence() * 100));
        sb.append(String.format("- R:R Ratio: %.1f:1\n", setup.getRiskRewardRatio()));

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
        SignalRationale.SignalRationaleBuilder builder = SignalRationale.builder()
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
                .confluenceScore(setup.getCurrentConfidence());

        // FIX: Populate family context fields from quantScore
        if (quantScore.hasFamilyContext()) {
            double bullishAlign = quantScore.getFamilyBullishAlignment() * 100;
            double bearishAlign = quantScore.getFamilyBearishAlignment() * 100;

            // Determine family bias
            // FIX: Distinguish between "balanced" neutral and "no conviction" neutral
            double maxAlign = Math.max(bullishAlign, bearishAlign);
            if (quantScore.isFamilyFullyBullish()) {
                builder.familyBias("BULLISH");
                builder.familyAlignment(bullishAlign);
                builder.fullyAligned(true);
            } else if (quantScore.isFamilyFullyBearish()) {
                builder.familyBias("BEARISH");
                builder.familyAlignment(bearishAlign);
                builder.fullyAligned(true);
            } else if (maxAlign < 30) {
                // FIX: Very low alignment = NO CONVICTION (less than 30% of instruments agree on any direction)
                builder.familyBias("NO_CONVICTION");
                builder.familyAlignment(maxAlign);
                builder.fullyAligned(false);
            } else if (bullishAlign > bearishAlign + 20) {
                builder.familyBias("WEAK_BULLISH");
                builder.familyAlignment(bullishAlign);
                builder.fullyAligned(false);
            } else if (bearishAlign > bullishAlign + 20) {
                builder.familyBias("WEAK_BEARISH");
                builder.familyAlignment(bearishAlign);
                builder.fullyAligned(false);
            } else {
                // Balanced alignment (neither direction dominates by 20%)
                builder.familyBias("NEUTRAL");
                builder.familyAlignment(maxAlign);
                builder.fullyAligned(false);
            }

            // Divergence detection
            if (quantScore.hasFamilyDivergence()) {
                builder.hasDivergence(true);
                List<String> divDetails = quantScore.getFamilyDivergences();
                if (divDetails != null && !divDetails.isEmpty()) {
                    builder.divergenceDetails(new ArrayList<>(divDetails));
                }
            }

            // Squeeze setups
            builder.shortSqueezeSetup(quantScore.hasShortSqueezeSetup());
            builder.longSqueezeSetup(quantScore.hasLongSqueezeSetup());

            // Interpretation
            String interpretation = quantScore.getFamilyContextInterpretation();
            if (interpretation != null && !interpretation.isEmpty()) {
                builder.familyInterpretation(interpretation);
            }
        }

        return builder.build();
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
