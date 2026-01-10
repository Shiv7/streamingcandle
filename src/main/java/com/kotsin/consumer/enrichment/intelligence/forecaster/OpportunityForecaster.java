package com.kotsin.consumer.enrichment.intelligence.forecaster;

import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.intelligence.model.*;
import com.kotsin.consumer.enrichment.intelligence.tracker.SetupTracker;
import com.kotsin.consumer.enrichment.model.*;
import com.kotsin.consumer.enrichment.pattern.model.PatternSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * OpportunityForecaster - Predicts upcoming market opportunities
 *
 * Generates forecasts by analyzing:
 * 1. Pattern sequences and their historical outcomes
 * 2. Active setup progress and expected completions
 * 3. Technical setups (breakout/breakdown imminent)
 * 4. Options context (GEX regime changes, max pain magnet)
 * 5. Order flow signals (absorption outcomes, exhaustion reversals)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpportunityForecaster {

    private final SetupTracker setupTracker;

    /**
     * Cache of recent forecasts by family
     */
    private final Map<String, OpportunityForecast> forecastCache = new ConcurrentHashMap<>();

    /**
     * Historical predictions for accuracy tracking
     */
    private final Map<String, List<PredictedEvent>> historicalPredictions = new ConcurrentHashMap<>();

    private static final int MAX_PREDICTIONS_PER_FORECAST = 10;
    private static final int MAX_HISTORICAL_PER_FAMILY = 100;

    // ======================== MAIN FORECASTING ========================

    /**
     * Generate opportunity forecast for a family
     *
     * @param familyId   Family identifier
     * @param quantScore Current enriched quant score
     * @param events     Recent detected events
     * @return Complete opportunity forecast
     */
    public OpportunityForecast generateForecast(String familyId, EnrichedQuantScore quantScore,
                                                  List<DetectedEvent> events) {
        if (quantScore == null) {
            return OpportunityForecast.empty(familyId);
        }

        Instant now = Instant.now();
        double currentPrice = quantScore.getClose();

        // Generate predictions from various sources
        List<PredictedEvent> predictions = new ArrayList<>();

        // 1. Setup-based predictions
        predictions.addAll(generateSetupPredictions(familyId, quantScore, currentPrice));

        // 2. Pattern-based predictions
        predictions.addAll(generatePatternPredictions(familyId, quantScore, currentPrice));

        // 3. Technical predictions
        predictions.addAll(generateTechnicalPredictions(familyId, quantScore, currentPrice));

        // 4. Options-based predictions
        predictions.addAll(generateOptionsPredictions(familyId, quantScore, currentPrice));

        // 5. Event-based predictions
        predictions.addAll(generateEventPredictions(familyId, quantScore, events, currentPrice));

        // Sort by confidence and limit
        predictions.sort(Comparator.comparingDouble(PredictedEvent::getConfidence).reversed());
        if (predictions.size() > MAX_PREDICTIONS_PER_FORECAST) {
            predictions = predictions.subList(0, MAX_PREDICTIONS_PER_FORECAST);
        }

        // Build forecast
        OpportunityForecast forecast = buildForecast(familyId, predictions, quantScore, currentPrice);

        // Cache forecast
        forecastCache.put(familyId, forecast);

        // Store predictions for tracking
        storePredictions(familyId, predictions);

        log.debug("[FORECASTER] Generated forecast for {}: {} predictions, direction={}, conf={:.0f}%",
                familyId, predictions.size(), forecast.getOverallDirection(),
                forecast.getOverallConfidence() * 100);

        return forecast;
    }

    // ======================== PREDICTION GENERATORS ========================

    /**
     * Generate predictions based on active setups
     */
    private List<PredictedEvent> generateSetupPredictions(String familyId, EnrichedQuantScore qs,
                                                           double currentPrice) {
        List<PredictedEvent> predictions = new ArrayList<>();

        // Ready setups -> imminent trades
        List<ActiveSetup> readySetups = setupTracker.getReadySetups(familyId);
        for (ActiveSetup setup : readySetups) {
            PredictedEvent prediction = createSetupPrediction(setup, currentPrice);
            if (prediction != null) {
                predictions.add(prediction);
            }
        }

        // Forming setups with high progress -> near-term opportunities
        List<ActiveSetup> formingSetups = setupTracker.getActiveSetups(familyId).stream()
                .filter(s -> s.getStatus() == ActiveSetup.SetupStatus.FORMING)
                .filter(s -> s.getProgress() >= 60)
                .collect(Collectors.toList());

        for (ActiveSetup setup : formingSetups) {
            PredictedEvent prediction = createFormingSetupPrediction(setup, currentPrice);
            if (prediction != null) {
                predictions.add(prediction);
            }
        }

        return predictions;
    }

    /**
     * Create prediction from ready setup
     */
    private PredictedEvent createSetupPrediction(ActiveSetup setup, double currentPrice) {
        PredictedEvent.PredictionDirection direction = setup.getDirection() == SetupDefinition.SetupDirection.LONG
                ? PredictedEvent.PredictionDirection.BULLISH
                : PredictedEvent.PredictionDirection.BEARISH;

        PredictedEvent.TimeFrame timeFrame = switch (setup.getHorizon()) {
            case SCALP -> PredictedEvent.TimeFrame.IMMEDIATE;
            case SWING -> PredictedEvent.TimeFrame.MEDIUM_TERM;
            case POSITIONAL -> PredictedEvent.TimeFrame.END_OF_DAY;
        };

        double targetPct = setup.getDirection() == SetupDefinition.SetupDirection.LONG ? 1.0 : -1.0;
        double stopPct = setup.getDirection() == SetupDefinition.SetupDirection.LONG ? -0.5 : 0.5;

        return PredictedEvent.builder()
                .predictionId(UUID.randomUUID().toString())
                .familyId(setup.getFamilyId())
                .type(getPredictionTypeFromSetup(setup))
                .direction(direction)
                .timeFrame(timeFrame)
                .confidence(setup.getCurrentConfidence())
                .probability(setup.getCurrentConfidence() * 0.9) // Slightly lower probability
                .description(String.format("%s setup ready: %s", setup.getSetupId(), setup.getStateDescription()))
                .rationale(String.format("All %d required conditions met, %d boosters active",
                        setup.getTotalRequiredConditions(), setup.getBoosterConditionsMet()))
                .currentPrice(currentPrice)
                .targetPrice(currentPrice * (1 + targetPct / 100))
                .invalidationPrice(currentPrice * (1 + stopPct / 100))
                .expectedMovePct(Math.abs(targetPct))
                .generatedAt(Instant.now())
                .expectedFrom(Instant.now())
                .expectedBy(Instant.now().plusSeconds(timeFrame.getMaxMinutes() * 60L))
                .expiresAt(setup.getExpiresAt())
                .setupId(setup.getSetupId())
                .triggerConditions(setup.getMetConditions())
                .historicalSuccessRate(0.6) // Default - would be populated from historical data
                .build();
    }

    /**
     * Create prediction from forming setup
     */
    private PredictedEvent createFormingSetupPrediction(ActiveSetup setup, double currentPrice) {
        PredictedEvent.PredictionDirection direction = setup.getDirection() == SetupDefinition.SetupDirection.LONG
                ? PredictedEvent.PredictionDirection.BULLISH
                : PredictedEvent.PredictionDirection.BEARISH;

        // Confidence reduced based on progress
        double adjustedConfidence = setup.getCurrentConfidence() * (setup.getProgress() / 100.0);

        return PredictedEvent.builder()
                .predictionId(UUID.randomUUID().toString())
                .familyId(setup.getFamilyId())
                .type(PredictedEvent.PredictionType.TREND_CONTINUATION)
                .direction(direction)
                .timeFrame(PredictedEvent.TimeFrame.SHORT_TERM)
                .confidence(adjustedConfidence)
                .probability(adjustedConfidence * 0.8)
                .description(String.format("%s setup forming (%.0f%% complete)",
                        setup.getSetupId(), setup.getProgress()))
                .rationale(String.format("%d/%d conditions met, watching for completion",
                        setup.getRequiredConditionsMet(), setup.getTotalRequiredConditions()))
                .currentPrice(currentPrice)
                .generatedAt(Instant.now())
                .expectedFrom(Instant.now().plusSeconds(300)) // Expect in 5+ minutes
                .expectedBy(Instant.now().plusSeconds(1800)) // Within 30 minutes
                .expiresAt(setup.getExpiresAt())
                .setupId(setup.getSetupId())
                .build();
    }

    /**
     * Get prediction type from setup
     */
    private PredictedEvent.PredictionType getPredictionTypeFromSetup(ActiveSetup setup) {
        String setupId = setup.getSetupId();
        if (setupId.contains("REVERSAL")) return PredictedEvent.PredictionType.DIRECTION_CHANGE;
        if (setupId.contains("BREAKOUT")) return PredictedEvent.PredictionType.BREAKOUT;
        if (setupId.contains("MEAN_REVERSION")) return PredictedEvent.PredictionType.MEAN_REVERSION;
        if (setupId.contains("CONTINUATION")) return PredictedEvent.PredictionType.TREND_CONTINUATION;
        return PredictedEvent.PredictionType.PRICE_TARGET;
    }

    /**
     * Generate predictions based on pattern signals
     */
    private List<PredictedEvent> generatePatternPredictions(String familyId, EnrichedQuantScore qs,
                                                             double currentPrice) {
        List<PredictedEvent> predictions = new ArrayList<>();

        if (!qs.hasPatternSignals()) {
            return predictions;
        }

        for (PatternSignal signal : qs.getPatternSignals()) {
            PredictedEvent.PredictionDirection direction = "LONG".equals(signal.getDirection())
                    ? PredictedEvent.PredictionDirection.BULLISH
                    : PredictedEvent.PredictionDirection.BEARISH;

            predictions.add(PredictedEvent.builder()
                    .predictionId(UUID.randomUUID().toString())
                    .familyId(familyId)
                    .type(PredictedEvent.PredictionType.PRICE_TARGET)
                    .direction(direction)
                    .timeFrame(PredictedEvent.TimeFrame.SHORT_TERM)
                    .confidence(signal.getConfidence())
                    .probability(signal.getConfidence() * signal.getHistoricalSuccessRate())
                    .description(String.format("Pattern %s triggered: %s %s",
                            signal.getPatternId(), signal.getCategory(), signal.getDirection()))
                    .rationale(String.format("Historical success: %.0f%% over %d occurrences",
                            signal.getHistoricalSuccessRate() * 100, signal.getHistoricalSampleSize()))
                    .currentPrice(currentPrice)
                    .targetPrice(signal.getTarget2())
                    .invalidationPrice(signal.getStopLoss())
                    .generatedAt(Instant.now())
                    .expectedBy(Instant.now().plusSeconds(3600)) // Within 1 hour
                    .expiresAt(Instant.now().plusSeconds(7200)) // Expires in 2 hours
                    .patternId(signal.getPatternId())
                    .historicalSuccessRate(signal.getHistoricalSuccessRate())
                    .historicalSampleCount(signal.getHistoricalSampleSize())
                    .build());
        }

        return predictions;
    }

    /**
     * Generate predictions based on technical analysis
     */
    private List<PredictedEvent> generateTechnicalPredictions(String familyId, EnrichedQuantScore qs,
                                                               double currentPrice) {
        List<PredictedEvent> predictions = new ArrayList<>();
        TechnicalContext tech = qs.getTechnicalContext();

        if (tech == null) {
            return predictions;
        }

        // SuperTrend flip prediction
        if (tech.isSuperTrendFlip()) {
            PredictedEvent.PredictionDirection direction = tech.isSuperTrendBullish()
                    ? PredictedEvent.PredictionDirection.BULLISH
                    : PredictedEvent.PredictionDirection.BEARISH;

            predictions.add(PredictedEvent.builder()
                    .predictionId(UUID.randomUUID().toString())
                    .familyId(familyId)
                    .type(PredictedEvent.PredictionType.TREND_CONTINUATION)
                    .direction(direction)
                    .timeFrame(PredictedEvent.TimeFrame.SHORT_TERM)
                    .confidence(0.70)
                    .probability(0.65)
                    .description(String.format("SuperTrend flipped %s - expect continuation",
                            direction.name().toLowerCase()))
                    .rationale("SuperTrend flip often leads to follow-through in direction")
                    .currentPrice(currentPrice)
                    .targetPrice(direction == PredictedEvent.PredictionDirection.BULLISH
                            ? currentPrice * 1.01 : currentPrice * 0.99)
                    .invalidationPrice(tech.getSuperTrendValue())
                    .generatedAt(Instant.now())
                    .expectedBy(Instant.now().plusSeconds(1800))
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .historicalSuccessRate(0.62)
                    .build());
        }

        // BB squeeze breakout prediction
        if (tech.isBbSqueezing()) {
            predictions.add(PredictedEvent.builder()
                    .predictionId(UUID.randomUUID().toString())
                    .familyId(familyId)
                    .type(PredictedEvent.PredictionType.VOLATILITY_EXPANSION)
                    .direction(PredictedEvent.PredictionDirection.EITHER)
                    .timeFrame(PredictedEvent.TimeFrame.SHORT_TERM)
                    .confidence(0.65)
                    .probability(0.60)
                    .description("Bollinger Band squeeze - volatility expansion imminent")
                    .rationale("BB squeeze indicates consolidation, typically followed by expansion")
                    .currentPrice(currentPrice)
                    .targetRangeLow(tech.getBbLower())
                    .targetRangeHigh(tech.getBbUpper())
                    .generatedAt(Instant.now())
                    .expectedBy(Instant.now().plusSeconds(3600))
                    .expiresAt(Instant.now().plusSeconds(7200))
                    .historicalSuccessRate(0.58)
                    .build());
        }

        // Oversold/overbought reversion
        Double bbPctB = tech.getBbPercentB();
        if (bbPctB != null) {
            if (bbPctB <= 0.05) {
                predictions.add(createRevertPrediction(familyId, currentPrice,
                        PredictedEvent.PredictionDirection.BULLISH,
                        "Price at lower BB - oversold bounce expected",
                        tech.getBbMiddle()));
            } else if (bbPctB >= 0.95) {
                predictions.add(createRevertPrediction(familyId, currentPrice,
                        PredictedEvent.PredictionDirection.BEARISH,
                        "Price at upper BB - overbought pullback expected",
                        tech.getBbMiddle()));
            }
        }

        // Confluence zone predictions
        if (qs.getConfluenceResult() != null) {
            var confluence = qs.getConfluenceResult();

            if (confluence.isAtSupport() && confluence.getSupportDistance() < 0.3) {
                predictions.add(PredictedEvent.builder()
                        .predictionId(UUID.randomUUID().toString())
                        .familyId(familyId)
                        .type(PredictedEvent.PredictionType.SUPPORT_TEST)
                        .direction(PredictedEvent.PredictionDirection.BULLISH)
                        .timeFrame(PredictedEvent.TimeFrame.IMMEDIATE)
                        .confidence(0.60)
                        .probability(0.55)
                        .description("Price testing support - bounce likely")
                        .rationale(String.format("At confluence support %.2f", confluence.getNearestSupportPrice()))
                        .currentPrice(currentPrice)
                        .targetPrice(currentPrice * 1.005)
                        .invalidationPrice(confluence.getNearestSupportPrice() * 0.995)
                        .generatedAt(Instant.now())
                        .expectedBy(Instant.now().plusSeconds(900))
                        .expiresAt(Instant.now().plusSeconds(1800))
                        .build());
            }

            if (confluence.isAtResistance() && confluence.getResistanceDistance() < 0.3) {
                predictions.add(PredictedEvent.builder()
                        .predictionId(UUID.randomUUID().toString())
                        .familyId(familyId)
                        .type(PredictedEvent.PredictionType.RESISTANCE_TEST)
                        .direction(PredictedEvent.PredictionDirection.BEARISH)
                        .timeFrame(PredictedEvent.TimeFrame.IMMEDIATE)
                        .confidence(0.60)
                        .probability(0.55)
                        .description("Price testing resistance - rejection likely")
                        .rationale(String.format("At confluence resistance %.2f", confluence.getNearestResistancePrice()))
                        .currentPrice(currentPrice)
                        .targetPrice(currentPrice * 0.995)
                        .invalidationPrice(confluence.getNearestResistancePrice() * 1.005)
                        .generatedAt(Instant.now())
                        .expectedBy(Instant.now().plusSeconds(900))
                        .expiresAt(Instant.now().plusSeconds(1800))
                        .build());
            }
        }

        return predictions;
    }

    /**
     * Create mean reversion prediction
     */
    private PredictedEvent createRevertPrediction(String familyId, double currentPrice,
                                                   PredictedEvent.PredictionDirection direction,
                                                   String description, double targetPrice) {
        return PredictedEvent.builder()
                .predictionId(UUID.randomUUID().toString())
                .familyId(familyId)
                .type(PredictedEvent.PredictionType.MEAN_REVERSION)
                .direction(direction)
                .timeFrame(PredictedEvent.TimeFrame.SHORT_TERM)
                .confidence(0.60)
                .probability(0.55)
                .description(description)
                .rationale("Price at Bollinger Band extreme - mean reversion expected")
                .currentPrice(currentPrice)
                .targetPrice(targetPrice)
                .generatedAt(Instant.now())
                .expectedBy(Instant.now().plusSeconds(1800))
                .expiresAt(Instant.now().plusSeconds(3600))
                .historicalSuccessRate(0.55)
                .build();
    }

    /**
     * Generate predictions based on options context
     */
    private List<PredictedEvent> generateOptionsPredictions(String familyId, EnrichedQuantScore qs,
                                                             double currentPrice) {
        List<PredictedEvent> predictions = new ArrayList<>();

        // Max pain magnet on expiry day
        MaxPainProfile maxPain = qs.getMaxPainProfile();
        ExpiryContext expiry = qs.getExpiryContext();

        if (maxPain != null && expiry != null && expiry.isExpiryDay()) {
            double maxPainStrike = maxPain.getMaxPainStrike();
            double distance = maxPain.getDistanceToMaxPainPct();

            if (Math.abs(distance) > 0.5 && Math.abs(distance) < 3.0) {
                PredictedEvent.PredictionDirection direction = distance > 0
                        ? PredictedEvent.PredictionDirection.BEARISH  // Above max pain, expect move down
                        : PredictedEvent.PredictionDirection.BULLISH; // Below max pain, expect move up

                predictions.add(PredictedEvent.builder()
                        .predictionId(UUID.randomUUID().toString())
                        .familyId(familyId)
                        .type(PredictedEvent.PredictionType.MAX_PAIN_MAGNET)
                        .direction(direction)
                        .timeFrame(expiry.isExpiryDayAfternoon()
                                ? PredictedEvent.TimeFrame.IMMEDIATE
                                : PredictedEvent.TimeFrame.END_OF_DAY)
                        .confidence(expiry.isExpiryDayAfternoon() ? 0.70 : 0.55)
                        .probability(expiry.isExpiryDayAfternoon() ? 0.65 : 0.50)
                        .description(String.format("Max pain magnet effect - price should move toward %.0f",
                                maxPainStrike))
                        .rationale(String.format("Expiry day %s, price %.1f%% from max pain",
                                expiry.isExpiryDayAfternoon() ? "afternoon" : "morning",
                                Math.abs(distance)))
                        .currentPrice(currentPrice)
                        .targetPrice(maxPainStrike)
                        .generatedAt(Instant.now())
                        .expectedBy(Instant.now().plusSeconds(
                                expiry.isExpiryDayAfternoon() ? 7200 : 14400))
                        .expiresAt(Instant.now().plusSeconds(21600)) // 6 hours
                        .historicalSuccessRate(0.58)
                        .build());
            }
        }

        // GEX regime predictions
        GEXProfile gex = qs.getGexProfile();
        if (gex != null && gex.getRegime() != null) {
            if (gex.isNearGammaFlip()) {
                predictions.add(PredictedEvent.builder()
                        .predictionId(UUID.randomUUID().toString())
                        .familyId(familyId)
                        .type(PredictedEvent.PredictionType.VOLATILITY_EXPANSION)
                        .direction(PredictedEvent.PredictionDirection.EITHER)
                        .timeFrame(PredictedEvent.TimeFrame.SHORT_TERM)
                        .confidence(0.60)
                        .probability(0.55)
                        .description("Near gamma flip level - regime change possible")
                        .rationale("Crossing gamma flip level often triggers increased volatility")
                        .currentPrice(currentPrice)
                        .generatedAt(Instant.now())
                        .expectedBy(Instant.now().plusSeconds(3600))
                        .expiresAt(Instant.now().plusSeconds(7200))
                        .build());
            }

            // Gamma squeeze setup from events
            if (qs.getDetectedEvents() != null) {
                boolean gammaSqueezeSetup = qs.getDetectedEvents().stream()
                        .anyMatch(e -> e.getEventType() == DetectedEvent.EventType.GAMMA_SQUEEZE_SETUP);

                if (gammaSqueezeSetup) {
                    predictions.add(PredictedEvent.builder()
                            .predictionId(UUID.randomUUID().toString())
                            .familyId(familyId)
                            .type(PredictedEvent.PredictionType.GAMMA_SQUEEZE)
                            .direction(PredictedEvent.PredictionDirection.BULLISH)
                            .timeFrame(PredictedEvent.TimeFrame.SHORT_TERM)
                            .confidence(0.65)
                            .probability(0.55)
                            .description("Gamma squeeze conditions detected - expect accelerated upside")
                            .rationale("Dealer hedging may amplify upward moves")
                            .currentPrice(currentPrice)
                            .targetPrice(currentPrice * 1.02)
                            .generatedAt(Instant.now())
                            .expectedBy(Instant.now().plusSeconds(3600))
                            .expiresAt(Instant.now().plusSeconds(7200))
                            .historicalSuccessRate(0.52)
                            .build());
                }
            }
        }

        return predictions;
    }

    /**
     * Generate predictions based on detected events
     */
    private List<PredictedEvent> generateEventPredictions(String familyId, EnrichedQuantScore qs,
                                                           List<DetectedEvent> events, double currentPrice) {
        List<PredictedEvent> predictions = new ArrayList<>();

        if (events == null || events.isEmpty()) {
            return predictions;
        }

        // Absorption bounce prediction
        Optional<DetectedEvent> absorptionEvent = events.stream()
                .filter(e -> e.getEventType() == DetectedEvent.EventType.ABSORPTION)
                .filter(e -> e.getStrength() >= 0.7)
                .findFirst();

        if (absorptionEvent.isPresent()) {
            DetectedEvent absorption = absorptionEvent.get();
            PredictedEvent.PredictionDirection direction = absorption.getDirection() == DetectedEvent.EventDirection.BULLISH
                    ? PredictedEvent.PredictionDirection.BULLISH
                    : PredictedEvent.PredictionDirection.BEARISH;

            predictions.add(PredictedEvent.builder()
                    .predictionId(UUID.randomUUID().toString())
                    .familyId(familyId)
                    .type(PredictedEvent.PredictionType.ABSORPTION_BOUNCE)
                    .direction(direction)
                    .timeFrame(PredictedEvent.TimeFrame.IMMEDIATE)
                    .confidence(absorption.getStrength())
                    .probability(absorption.getStrength() * 0.85)
                    .description(String.format("Absorption detected at %.2f - expect %s bounce",
                            absorption.getPriceAtDetection(), direction.name().toLowerCase()))
                    .rationale("Large orders absorbed at this level - institutional support/resistance")
                    .currentPrice(currentPrice)
                    .targetPrice(direction == PredictedEvent.PredictionDirection.BULLISH
                            ? currentPrice * 1.005 : currentPrice * 0.995)
                    .invalidationPrice(absorption.getPriceAtDetection())
                    .generatedAt(Instant.now())
                    .expectedFrom(Instant.now())
                    .expectedBy(Instant.now().plusSeconds(900))
                    .expiresAt(Instant.now().plusSeconds(1800))
                    .historicalSuccessRate(0.60)
                    .build());
        }

        // Exhaustion reversal prediction
        Optional<DetectedEvent> exhaustionEvent = events.stream()
                .filter(e -> e.getEventType() == DetectedEvent.EventType.BUYING_EXHAUSTION ||
                             e.getEventType() == DetectedEvent.EventType.SELLING_EXHAUSTION)
                .filter(e -> e.getStrength() >= 0.7)
                .findFirst();

        if (exhaustionEvent.isPresent()) {
            DetectedEvent exhaustion = exhaustionEvent.get();
            boolean isBuyingExhaustion = exhaustion.getEventType() == DetectedEvent.EventType.BUYING_EXHAUSTION;
            PredictedEvent.PredictionDirection direction = isBuyingExhaustion
                    ? PredictedEvent.PredictionDirection.BEARISH
                    : PredictedEvent.PredictionDirection.BULLISH;

            predictions.add(PredictedEvent.builder()
                    .predictionId(UUID.randomUUID().toString())
                    .familyId(familyId)
                    .type(PredictedEvent.PredictionType.EXHAUSTION_REVERSAL)
                    .direction(direction)
                    .timeFrame(PredictedEvent.TimeFrame.SHORT_TERM)
                    .confidence(exhaustion.getStrength())
                    .probability(exhaustion.getStrength() * 0.80)
                    .description(String.format("%s exhaustion detected - reversal expected",
                            isBuyingExhaustion ? "Buying" : "Selling"))
                    .rationale("Exhaustion at extremes often precedes reversal")
                    .currentPrice(currentPrice)
                    .generatedAt(Instant.now())
                    .expectedBy(Instant.now().plusSeconds(1800))
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .historicalSuccessRate(0.55)
                    .build());
        }

        return predictions;
    }

    // ======================== FORECAST BUILDER ========================

    /**
     * Build complete forecast from predictions
     */
    private OpportunityForecast buildForecast(String familyId, List<PredictedEvent> predictions,
                                               EnrichedQuantScore qs, double currentPrice) {
        Instant now = Instant.now();

        // Calculate direction scores
        double bullishScore = predictions.stream()
                .filter(p -> p.getDirection() == PredictedEvent.PredictionDirection.BULLISH)
                .mapToDouble(p -> p.getConfidence() * p.getProbability())
                .sum();

        double bearishScore = predictions.stream()
                .filter(p -> p.getDirection() == PredictedEvent.PredictionDirection.BEARISH)
                .mapToDouble(p -> p.getConfidence() * p.getProbability())
                .sum();

        // Determine overall direction
        OpportunityForecast.ForecastDirection overallDirection;
        double directionDiff = bullishScore - bearishScore;
        if (directionDiff > 0.5) overallDirection = OpportunityForecast.ForecastDirection.STRONGLY_BULLISH;
        else if (directionDiff > 0.2) overallDirection = OpportunityForecast.ForecastDirection.BULLISH;
        else if (directionDiff < -0.5) overallDirection = OpportunityForecast.ForecastDirection.STRONGLY_BEARISH;
        else if (directionDiff < -0.2) overallDirection = OpportunityForecast.ForecastDirection.BEARISH;
        else overallDirection = OpportunityForecast.ForecastDirection.NEUTRAL;

        // Calculate overall confidence
        double overallConfidence = predictions.isEmpty() ? 0.5 :
                predictions.stream().mapToDouble(PredictedEvent::getConfidence).average().orElse(0.5);

        // Calculate opportunity scores (0-100)
        double longScore = Math.min(100, 50 + bullishScore * 50);
        double shortScore = Math.min(100, 50 + bearishScore * 50);

        // Determine volatility forecast
        OpportunityForecast.VolatilityForecast volForecast = determineVolatilityForecast(qs, predictions);

        // Determine risk level
        OpportunityForecast.RiskLevel riskLevel = determineRiskLevel(qs, predictions);
        List<String> keyRisks = identifyKeyRisks(qs, predictions);

        // Calculate expected moves
        double expectedMove30Min = calculateExpectedMove(predictions, PredictedEvent.TimeFrame.IMMEDIATE);
        double expectedMoveHour = calculateExpectedMove(predictions, PredictedEvent.TimeFrame.SHORT_TERM);

        // Key levels from confluence
        Double keySupport = qs.getNearestSupportPrice();
        Double keyResistance = qs.getNearestResistancePrice();

        // Build best opportunity
        PredictedEvent bestPrediction = predictions.isEmpty() ? null : predictions.get(0);
        String bestOpportunityType = bestPrediction != null ? bestPrediction.getType().name() : "NONE";
        String bestOpportunityDesc = bestPrediction != null ? bestPrediction.getDescription() : "No clear opportunities";

        return OpportunityForecast.builder()
                .familyId(familyId)
                .generatedAt(now)
                .validUntil(now.plusSeconds(300)) // 5 minute validity
                .predictions(predictions)
                // Summary
                .overallDirection(overallDirection)
                .overallConfidence(overallConfidence)
                .expectedMove30Min(expectedMove30Min)
                .expectedMoveHour(expectedMoveHour)
                .volatilityForecast(volForecast)
                // Key levels
                .keySupport(keySupport)
                .keyResistance(keyResistance)
                .immediateUpsideTarget(currentPrice * 1.005)
                .immediateDownsideTarget(currentPrice * 0.995)
                // Risk
                .riskLevel(riskLevel)
                .keyRisks(keyRisks)
                .suggestedPositionSize(calculatePositionSize(riskLevel, overallConfidence))
                // Opportunity scores
                .longOpportunityScore(longScore)
                .shortOpportunityScore(shortScore)
                .bestOpportunityType(bestOpportunityType)
                .bestOpportunityDescription(bestOpportunityDesc)
                .build();
    }

    /**
     * Determine volatility forecast
     */
    private OpportunityForecast.VolatilityForecast determineVolatilityForecast(EnrichedQuantScore qs,
                                                                                 List<PredictedEvent> predictions) {
        // Check for volatility expansion predictions
        boolean expectingExpansion = predictions.stream()
                .anyMatch(p -> p.getType() == PredictedEvent.PredictionType.VOLATILITY_EXPANSION);

        if (expectingExpansion) {
            return OpportunityForecast.VolatilityForecast.EXPANSION;
        }

        // Check BB squeeze
        if (qs.isBbSqueezing()) {
            return OpportunityForecast.VolatilityForecast.EXPANSION;
        }

        // Check current volatility from ATR
        Double atrPct = qs.getAtrPct();
        if (atrPct != null) {
            if (atrPct > 1.5) return OpportunityForecast.VolatilityForecast.VERY_HIGH;
            if (atrPct > 1.0) return OpportunityForecast.VolatilityForecast.HIGH;
            if (atrPct < 0.3) return OpportunityForecast.VolatilityForecast.VERY_LOW;
            if (atrPct < 0.5) return OpportunityForecast.VolatilityForecast.LOW;
        }

        return OpportunityForecast.VolatilityForecast.NORMAL;
    }

    /**
     * Determine risk level
     */
    private OpportunityForecast.RiskLevel determineRiskLevel(EnrichedQuantScore qs,
                                                              List<PredictedEvent> predictions) {
        int riskScore = 0;

        // Time context risk
        TimeContext time = qs.getTimeContext();
        if (time != null && time.shouldAvoidTrading()) {
            riskScore += 3;
        }

        // Expiry day risk
        if (qs.isExpiryDay()) {
            riskScore += 2;
        }

        // Low confidence predictions
        double avgConfidence = predictions.isEmpty() ? 0.5 :
                predictions.stream().mapToDouble(PredictedEvent::getConfidence).average().orElse(0.5);
        if (avgConfidence < 0.5) {
            riskScore += 2;
        }

        // GEX near flip
        GEXProfile gex = qs.getGexProfile();
        if (gex != null && gex.isNearGammaFlip()) {
            riskScore += 1;
        }

        // Determine risk level
        if (riskScore >= 6) return OpportunityForecast.RiskLevel.EXTREME;
        if (riskScore >= 4) return OpportunityForecast.RiskLevel.VERY_HIGH;
        if (riskScore >= 2) return OpportunityForecast.RiskLevel.HIGH;
        if (riskScore >= 1) return OpportunityForecast.RiskLevel.MODERATE;
        return OpportunityForecast.RiskLevel.LOW;
    }

    /**
     * Identify key risks
     */
    private List<String> identifyKeyRisks(EnrichedQuantScore qs, List<PredictedEvent> predictions) {
        List<String> risks = new ArrayList<>();

        TimeContext time = qs.getTimeContext();
        if (time != null && time.shouldAvoidTrading()) {
            risks.add(time.getLowConfidenceReason());
        }

        if (qs.isExpiryDay()) {
            risks.add("Expiry day - increased unpredictability");
        }

        GEXProfile gex = qs.getGexProfile();
        if (gex != null && gex.isNearGammaFlip()) {
            risks.add("Near gamma flip level - regime change possible");
        }

        HistoricalContext hist = qs.getHistoricalContext();
        if (hist != null && hist.isLiquidityWithdrawal()) {
            risks.add("Liquidity withdrawal detected");
        }

        if (predictions.isEmpty()) {
            risks.add("No clear directional predictions");
        }

        return risks;
    }

    /**
     * Calculate expected move for time frame
     */
    private double calculateExpectedMove(List<PredictedEvent> predictions, PredictedEvent.TimeFrame timeFrame) {
        return predictions.stream()
                .filter(p -> p.getTimeFrame() == timeFrame)
                .filter(p -> p.getExpectedMovePct() != null)
                .mapToDouble(p -> p.getExpectedMovePct() * p.getProbability())
                .average()
                .orElse(0.0);
    }

    /**
     * Calculate suggested position size
     */
    private double calculatePositionSize(OpportunityForecast.RiskLevel risk, double confidence) {
        double baseSize = switch (risk) {
            case LOW -> 1.0;
            case MODERATE -> 0.8;
            case HIGH -> 0.5;
            case VERY_HIGH -> 0.3;
            case EXTREME -> 0.1;
        };

        // Adjust by confidence
        return baseSize * Math.max(0.3, confidence);
    }

    /**
     * Store predictions for historical tracking
     */
    private void storePredictions(String familyId, List<PredictedEvent> predictions) {
        List<PredictedEvent> history = historicalPredictions.computeIfAbsent(familyId, k -> new ArrayList<>());

        // Add new predictions
        history.addAll(predictions);

        // Cleanup old predictions
        if (history.size() > MAX_HISTORICAL_PER_FAMILY) {
            history.subList(0, history.size() - MAX_HISTORICAL_PER_FAMILY).clear();
        }
    }

    // ======================== QUERY METHODS ========================

    /**
     * Get cached forecast for family
     */
    public Optional<OpportunityForecast> getCachedForecast(String familyId) {
        OpportunityForecast forecast = forecastCache.get(familyId);
        if (forecast != null && forecast.isValid()) {
            return Optional.of(forecast);
        }
        return Optional.empty();
    }

    /**
     * Get historical predictions for family
     */
    public List<PredictedEvent> getHistoricalPredictions(String familyId) {
        return historicalPredictions.getOrDefault(familyId, Collections.emptyList());
    }

    /**
     * Calculate prediction accuracy for family
     */
    public double calculateAccuracy(String familyId) {
        List<PredictedEvent> history = historicalPredictions.get(familyId);
        if (history == null || history.isEmpty()) {
            return 0.0;
        }

        long resolved = history.stream()
                .filter(PredictedEvent::isResolved)
                .count();

        long correct = history.stream()
                .filter(p -> p.getOutcome() == PredictedEvent.PredictionOutcome.CORRECT ||
                             p.getOutcome() == PredictedEvent.PredictionOutcome.PARTIALLY_CORRECT)
                .count();

        return resolved > 0 ? (double) correct / resolved : 0.0;
    }

    /**
     * Clear expired forecasts
     */
    public void cleanup() {
        forecastCache.entrySet().removeIf(e -> !e.getValue().isValid());

        // Cleanup old historical predictions
        Instant cutoff = Instant.now().minusSeconds(86400); // 24 hours
        for (List<PredictedEvent> history : historicalPredictions.values()) {
            history.removeIf(p -> p.getGeneratedAt() != null && p.getGeneratedAt().isBefore(cutoff));
        }
    }
}
