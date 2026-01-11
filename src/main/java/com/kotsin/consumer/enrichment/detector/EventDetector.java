package com.kotsin.consumer.enrichment.detector;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.OptionCandle;
import com.kotsin.consumer.enrichment.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EventDetector - Detects significant market events from enriched candle data
 *
 * This is the core event detection engine that identifies:
 * 1. Microstructure events (OFI flip, exhaustion, absorption)
 * 2. Technical events (SuperTrend flip, BB touches) - requires TechnicalContext
 * 3. Options events (OI surges, gamma squeeze setups)
 *
 * Events are the building blocks for pattern recognition.
 * Each event can be confirmed, failed, or expired based on subsequent price action.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventDetector {

    // Detection thresholds
    private static final double Z_SCORE_THRESHOLD = 1.5;        // For statistical events
    private static final double STRONG_Z_SCORE = 2.0;           // For strong events
    private static final double EXHAUSTION_VELOCITY_FLIP = 0.3; // Velocity threshold for exhaustion
    private static final double ABSORPTION_LAMBDA_LOW = 0.5;    // Low lambda = absorption
    private static final double VOLUME_SURGE_PERCENTILE = 90;   // 90th percentile = surge
    private static final double OI_SURGE_Z_SCORE = 2.0;         // OI surge threshold
    private static final double VPIN_INFORMED_THRESHOLD = 0.7;  // High VPIN = informed flow

    // FIX: Exhaustion cooldown to prevent signal spam
    private static final long EXHAUSTION_COOLDOWN_MS = 5 * 60 * 1000; // 5 minutes (5 bars for 1-min candles)
    private final Map<String, Long> lastExhaustionTime = new ConcurrentHashMap<>();

    /**
     * Detect all events from FamilyCandle and its contexts
     *
     * @param family The family candle
     * @param historicalContext Historical context with regime info
     * @param gexProfile GEX profile for options events
     * @param maxPainProfile Max pain profile
     * @param technicalContext Technical indicator context (if available)
     * @return List of detected events
     */
    public List<DetectedEvent> detectEvents(
            FamilyCandle family,
            HistoricalContext historicalContext,
            GEXProfile gexProfile,
            MaxPainProfile maxPainProfile,
            TechnicalContext technicalContext) {

        List<DetectedEvent> events = new ArrayList<>();

        if (family == null) {
            return events;
        }

        String familyId = family.getFamilyId();
        String timeframe = family.getTimeframe();
        double price = family.getPrimaryPrice();
        Instant now = Instant.now();

        // ========== Microstructure Events ==========
        detectMicrostructureEvents(events, family, historicalContext, familyId, timeframe, price, now);

        // ========== Options Events ==========
        detectOptionsEvents(events, family, gexProfile, maxPainProfile, familyId, timeframe, price, now);

        // ========== Technical Events (if context available) ==========
        if (technicalContext != null) {
            detectTechnicalEvents(events, family, technicalContext, familyId, timeframe, price, now);
        }

        // ========== Composite Events ==========
        detectCompositeEvents(events, historicalContext, gexProfile, familyId, timeframe, price, now);

        // Log detected events
        if (!events.isEmpty()) {
            log.info("[EVENT] Detected {} events for {} @ {}: {}",
                    events.size(), familyId, price,
                    events.stream().map(e -> e.getEventType().name()).toList());
        } else {
            // Log why no events detected for debugging
            boolean hasOfi = historicalContext != null && historicalContext.getOfiContext() != null;
            boolean hasFlip = hasOfi && historicalContext.getOfiContext().getFlipType() != null &&
                              historicalContext.getOfiContext().getFlipType() != MetricContext.FlipType.NONE;
            boolean hasGex = gexProfile != null;
            boolean hasTech = technicalContext != null;
            boolean inLearning = historicalContext != null && historicalContext.isInLearningMode();
            double dataCompleteness = historicalContext != null ? historicalContext.getDataCompleteness() : 0;

            log.debug("[EVENT] {} @ {} | No events | ofi={}, flip={}, gex={}, tech={}, learning={}, complete={:.0f}%",
                    familyId, price, hasOfi, hasFlip, hasGex, hasTech, inLearning, dataCompleteness * 100);
        }

        return events;
    }

    // ======================== MICROSTRUCTURE EVENTS ========================

    private void detectMicrostructureEvents(List<DetectedEvent> events, FamilyCandle family,
                                             HistoricalContext ctx, String familyId, String timeframe,
                                             double price, Instant now) {
        if (ctx == null) return;

        // OFI Flip detection
        MetricContext ofiCtx = ctx.getOfiContext();
        if (ofiCtx != null && ofiCtx.getFlipType() != null &&
                ofiCtx.getFlipType() != MetricContext.FlipType.NONE) {

            DetectedEvent.EventDirection direction = ofiCtx.getFlipType() == MetricContext.FlipType.BEARISH_TO_BULLISH ?
                    DetectedEvent.EventDirection.BULLISH : DetectedEvent.EventDirection.BEARISH;

            events.add(createEvent(
                    DetectedEvent.EventType.OFI_FLIP,
                    familyId, timeframe, price, now,
                    direction,
                    Math.min(1.0, Math.abs(ofiCtx.getFlipZscore()) / 3.0),
                    ofiCtx.getFlipZscore(),
                    ofiCtx.getPercentile()
            ).withContext("previousRegime", ofiCtx.getPreviousRegime())
             .withContext("currentRegime", ofiCtx.getRegime())
             .withContext("currentValue", ofiCtx.getCurrentValue()));
        }

        // Selling Exhaustion detection with cooldown
        if (ofiCtx != null && isSellExhaustion(ofiCtx) && isExhaustionCooldownExpired(familyId, now)) {
            double velocity = calculateVelocity(ofiCtx);
            events.add(createEvent(
                    DetectedEvent.EventType.SELLING_EXHAUSTION,
                    familyId, timeframe, price, now,
                    DetectedEvent.EventDirection.BULLISH, // Exhaustion is bullish
                    0.7,
                    null,
                    ofiCtx.getPercentile()
            ).withContext("ofiVelocity", velocity)
             .withContext("ofiRegime", ofiCtx.getRegime()));
            // Update last exhaustion time
            lastExhaustionTime.put(familyId, now.toEpochMilli());
        }

        // Buying Exhaustion detection with cooldown
        if (ofiCtx != null && isBuyExhaustion(ofiCtx) && isExhaustionCooldownExpired(familyId, now)) {
            double velocity = calculateVelocity(ofiCtx);
            events.add(createEvent(
                    DetectedEvent.EventType.BUYING_EXHAUSTION,
                    familyId, timeframe, price, now,
                    DetectedEvent.EventDirection.BEARISH,
                    0.7,
                    null,
                    ofiCtx.getPercentile()
            ).withContext("ofiVelocity", velocity)
             .withContext("ofiRegime", ofiCtx.getRegime()));
            // Update last exhaustion time
            lastExhaustionTime.put(familyId, now.toEpochMilli());
        }

        // Absorption detection
        if (ctx.isAbsorptionDetected()) {
            DetectedEvent.EventDirection absDirection = determineAbsorptionDirection(ctx);
            events.add(createEvent(
                    DetectedEvent.EventType.ABSORPTION,
                    familyId, timeframe, price, now,
                    absDirection,
                    0.8,
                    null,
                    null
            ).withContext("absorptionType", absDirection == DetectedEvent.EventDirection.BULLISH ?
                    "Buying absorption" : "Selling absorption"));
        }

        // Liquidity Withdrawal detection
        if (ctx.isLiquidityWithdrawal()) {
            events.add(createEvent(
                    DetectedEvent.EventType.LIQUIDITY_WITHDRAWAL,
                    familyId, timeframe, price, now,
                    DetectedEvent.EventDirection.NEUTRAL,
                    0.6,
                    null,
                    null
            ).withContext("warning", "Liquidity withdrawing - caution advised"));
        }

        // Informed Flow detection
        if (ctx.isInformedFlowActive() && ctx.getInformedFlowIntensity() > 0.5) {
            DetectedEvent.EventDirection flowDirection = determineInformedFlowDirection(ctx);
            events.add(createEvent(
                    DetectedEvent.EventType.INFORMED_FLOW,
                    familyId, timeframe, price, now,
                    flowDirection,
                    ctx.getInformedFlowIntensity(),
                    null,
                    null
            ).withContext("intensity", ctx.getInformedFlowIntensity()));
        }

        // Volume Surge detection
        MetricContext volCtx = ctx.getVolumeDeltaContext();
        if (volCtx != null && volCtx.getPercentile() >= VOLUME_SURGE_PERCENTILE) {
            DetectedEvent.EventDirection volDirection = volCtx.getCurrentValue() > 0 ?
                    DetectedEvent.EventDirection.BULLISH : DetectedEvent.EventDirection.BEARISH;
            events.add(createEvent(
                    DetectedEvent.EventType.VOLUME_SURGE,
                    familyId, timeframe, price, now,
                    volDirection,
                    volCtx.getPercentile() / 100.0,
                    volCtx.getZscore(),
                    volCtx.getPercentile()
            ).withContext("volumeDelta", volCtx.getCurrentValue()));
        }

        // Momentum Building detection
        if (ctx.isMomentumBuilding()) {
            DetectedEvent.EventDirection momDirection = "BULLISH".equalsIgnoreCase(ctx.getMomentumDirection()) ?
                    DetectedEvent.EventDirection.BULLISH : DetectedEvent.EventDirection.BEARISH;
            events.add(createEvent(
                    DetectedEvent.EventType.MOMENTUM_BUILDING,
                    familyId, timeframe, price, now,
                    momDirection,
                    0.6,
                    null,
                    null
            ).withContext("direction", ctx.getMomentumDirection()));
        }
    }

    // ======================== OPTIONS EVENTS ========================

    private void detectOptionsEvents(List<DetectedEvent> events, FamilyCandle family,
                                      GEXProfile gexProfile, MaxPainProfile maxPainProfile,
                                      String familyId, String timeframe, double price, Instant now) {

        // OI events from options
        if (family.getOptions() != null && !family.getOptions().isEmpty()) {
            detectOIEvents(events, family.getOptions(), familyId, timeframe, price, now);
        }

        // GEX regime change
        if (gexProfile != null && isGEXRegimeChange(gexProfile)) {
            DetectedEvent.EventDirection gexDirection = gexProfile.isTrending() ?
                    DetectedEvent.EventDirection.NEUTRAL : // Trending = momentum works
                    DetectedEvent.EventDirection.NEUTRAL;  // Mean-reverting = fades work

            events.add(createEvent(
                    DetectedEvent.EventType.GEX_REGIME_CHANGE,
                    familyId, timeframe, price, now,
                    gexDirection,
                    0.7,
                    null,
                    null
            ).withContext("regime", gexProfile.getRegime())
             .withContext("totalGex", gexProfile.getTotalGex()));
        }

        // Gamma Squeeze Setup
        if (gexProfile != null && isGammaSqueezeSetup(gexProfile, price)) {
            DetectedEvent.EventDirection squeezeDirection = price < gexProfile.getMaxGexStrike() ?
                    DetectedEvent.EventDirection.BULLISH : DetectedEvent.EventDirection.BEARISH;

            events.add(createEvent(
                    DetectedEvent.EventType.GAMMA_SQUEEZE_SETUP,
                    familyId, timeframe, price, now,
                    squeezeDirection,
                    0.75,
                    null,
                    null
            ).withContext("targetStrike", gexProfile.getMaxGexStrike())
             .withContext("distancePct", Math.abs(price - gexProfile.getMaxGexStrike()) / price * 100));
        }

        // Max Pain Convergence
        if (maxPainProfile != null && maxPainProfile.isActionable()) {
            DetectedEvent.EventDirection mpDirection = maxPainProfile.getBias() == MaxPainProfile.MaxPainBias.BULLISH ?
                    DetectedEvent.EventDirection.BULLISH :
                    maxPainProfile.getBias() == MaxPainProfile.MaxPainBias.BEARISH ?
                            DetectedEvent.EventDirection.BEARISH : DetectedEvent.EventDirection.NEUTRAL;

            if (mpDirection != DetectedEvent.EventDirection.NEUTRAL) {
                events.add(createEvent(
                        DetectedEvent.EventType.MAX_PAIN_CONVERGENCE,
                        familyId, timeframe, price, now,
                        mpDirection,
                        maxPainProfile.getMaxPainStrength(),
                        null,
                        null
                ).withContext("maxPainStrike", maxPainProfile.getMaxPainStrike())
                 .withContext("distancePct", maxPainProfile.getAbsDistancePct())
                 .withContext("bias", maxPainProfile.getBias()));
            }
        }
    }

    private void detectOIEvents(List<DetectedEvent> events, List<OptionCandle> options,
                                 String familyId, String timeframe, double price, Instant now) {

        long totalCallOIChange = 0;
        long totalPutOIChange = 0;

        for (OptionCandle opt : options) {
            if (opt == null) continue;

            long oiChange = opt.getOiChange();
            if (opt.isCall()) {
                totalCallOIChange += oiChange;
            } else {
                totalPutOIChange += oiChange;
            }
        }

        // Call OI Surge
        if (totalCallOIChange > 0 && isOISurge(totalCallOIChange, options.size())) {
            events.add(createEvent(
                    DetectedEvent.EventType.CALL_OI_SURGE,
                    familyId, timeframe, price, now,
                    DetectedEvent.EventDirection.BULLISH,
                    0.7,
                    null,
                    null
            ).withContext("oiChange", totalCallOIChange));
        }

        // Put OI Surge
        if (totalPutOIChange > 0 && isOISurge(totalPutOIChange, options.size())) {
            events.add(createEvent(
                    DetectedEvent.EventType.PUT_OI_SURGE,
                    familyId, timeframe, price, now,
                    DetectedEvent.EventDirection.BEARISH,
                    0.7,
                    null,
                    null
            ).withContext("oiChange", totalPutOIChange));
        }

        // Call OI Unwinding
        if (totalCallOIChange < 0 && Math.abs(totalCallOIChange) > 500) {
            events.add(createEvent(
                    DetectedEvent.EventType.CALL_OI_UNWINDING,
                    familyId, timeframe, price, now,
                    DetectedEvent.EventDirection.BEARISH,
                    0.5,
                    null,
                    null
            ).withContext("oiChange", totalCallOIChange));
        }

        // Put OI Unwinding
        if (totalPutOIChange < 0 && Math.abs(totalPutOIChange) > 500) {
            events.add(createEvent(
                    DetectedEvent.EventType.PUT_OI_UNWINDING,
                    familyId, timeframe, price, now,
                    DetectedEvent.EventDirection.BULLISH,
                    0.5,
                    null,
                    null
            ).withContext("oiChange", totalPutOIChange));
        }
    }

    // ======================== TECHNICAL EVENTS ========================

    private void detectTechnicalEvents(List<DetectedEvent> events, FamilyCandle family,
                                        TechnicalContext tech, String familyId, String timeframe,
                                        double price, Instant now) {

        // SuperTrend Flip
        if (tech.isSuperTrendFlip()) {
            DetectedEvent.EventDirection stDirection = tech.isSuperTrendBullish() ?
                    DetectedEvent.EventDirection.BULLISH : DetectedEvent.EventDirection.BEARISH;

            events.add(createEvent(
                    DetectedEvent.EventType.SUPERTREND_FLIP,
                    familyId, timeframe, price, now,
                    stDirection,
                    0.8,
                    null,
                    null
            ).withContext("superTrendValue", tech.getSuperTrendValue())
             .withContext("direction", stDirection));
        }

        // Bollinger Band events
        if (tech.getBbLower() > 0) {
            // BB Lower Touch
            if (price <= tech.getBbLower() * 1.002) { // Within 0.2%
                events.add(createEvent(
                        DetectedEvent.EventType.BB_LOWER_TOUCH,
                        familyId, timeframe, price, now,
                        DetectedEvent.EventDirection.BULLISH,
                        0.7,
                        null,
                        null
                ).withContext("bbLower", tech.getBbLower())
                 .withContext("bbPercentB", tech.getBbPercentB()));
            }

            // BB Upper Touch
            if (price >= tech.getBbUpper() * 0.998) {
                events.add(createEvent(
                        DetectedEvent.EventType.BB_UPPER_TOUCH,
                        familyId, timeframe, price, now,
                        DetectedEvent.EventDirection.BEARISH,
                        0.7,
                        null,
                        null
                ).withContext("bbUpper", tech.getBbUpper())
                 .withContext("bbPercentB", tech.getBbPercentB()));
            }

            // BB Squeeze
            if (tech.isBbSqueezing()) {
                events.add(createEvent(
                        DetectedEvent.EventType.BB_SQUEEZE,
                        familyId, timeframe, price, now,
                        DetectedEvent.EventDirection.NEUTRAL,
                        0.6,
                        null,
                        null
                ).withContext("bbWidth", tech.getBbWidth())
                 .withContext("warning", "Low volatility - breakout imminent"));
            }
        }

        // Pivot Support/Resistance tests
        if (tech.getNearestSupport() != null) {
            double supportDist = Math.abs(price - tech.getNearestSupport()) / price * 100;
            if (supportDist < 0.3) { // Within 0.3%
                events.add(createEvent(
                        DetectedEvent.EventType.PIVOT_SUPPORT_TEST,
                        familyId, timeframe, price, now,
                        DetectedEvent.EventDirection.BULLISH,
                        0.65,
                        null,
                        null
                ).withContext("supportLevel", tech.getNearestSupport())
                 .withContext("distancePct", supportDist));
            }
        }

        if (tech.getNearestResistance() != null) {
            double resDist = Math.abs(price - tech.getNearestResistance()) / price * 100;
            if (resDist < 0.3) {
                events.add(createEvent(
                        DetectedEvent.EventType.PIVOT_RESISTANCE_TEST,
                        familyId, timeframe, price, now,
                        DetectedEvent.EventDirection.BEARISH,
                        0.65,
                        null,
                        null
                ).withContext("resistanceLevel", tech.getNearestResistance())
                 .withContext("distancePct", resDist));
            }
        }
    }

    // ======================== COMPOSITE EVENTS ========================

    private void detectCompositeEvents(List<DetectedEvent> events, HistoricalContext ctx,
                                        GEXProfile gex, String familyId, String timeframe,
                                        double price, Instant now) {

        // Count bullish/bearish signals
        int bullishCount = 0;
        int bearishCount = 0;

        for (DetectedEvent event : events) {
            if (event.isBullish()) bullishCount++;
            if (event.isBearish()) bearishCount++;
        }

        // Bullish Confluence (3+ bullish signals)
        if (bullishCount >= 3 && bearishCount <= 1) {
            events.add(createEvent(
                    DetectedEvent.EventType.BULLISH_CONFLUENCE,
                    familyId, timeframe, price, now,
                    DetectedEvent.EventDirection.BULLISH,
                    Math.min(1.0, 0.5 + bullishCount * 0.1),
                    null,
                    null
            ).withContext("bullishSignals", bullishCount)
             .withContext("bearishSignals", bearishCount));
        }

        // Bearish Confluence
        if (bearishCount >= 3 && bullishCount <= 1) {
            events.add(createEvent(
                    DetectedEvent.EventType.BEARISH_CONFLUENCE,
                    familyId, timeframe, price, now,
                    DetectedEvent.EventDirection.BEARISH,
                    Math.min(1.0, 0.5 + bearishCount * 0.1),
                    null,
                    null
            ).withContext("bullishSignals", bullishCount)
             .withContext("bearishSignals", bearishCount));
        }

        // Reversal Setup (exhaustion + flip + confluence)
        boolean hasExhaustion = events.stream().anyMatch(e ->
                e.getEventType() == DetectedEvent.EventType.SELLING_EXHAUSTION ||
                e.getEventType() == DetectedEvent.EventType.BUYING_EXHAUSTION);
        boolean hasFlip = events.stream().anyMatch(e ->
                e.getEventType() == DetectedEvent.EventType.OFI_FLIP);

        if (hasExhaustion && hasFlip) {
            DetectedEvent.EventDirection revDirection = events.stream()
                    .filter(e -> e.getEventType() == DetectedEvent.EventType.OFI_FLIP)
                    .findFirst()
                    .map(DetectedEvent::getDirection)
                    .orElse(DetectedEvent.EventDirection.NEUTRAL);

            events.add(createEvent(
                    DetectedEvent.EventType.REVERSAL_SETUP,
                    familyId, timeframe, price, now,
                    revDirection,
                    0.75,
                    null,
                    null
            ).withContext("pattern", "Exhaustion + Flip"));
        }
    }

    // ======================== HELPER METHODS ========================

    private DetectedEvent createEvent(DetectedEvent.EventType type, String familyId, String timeframe,
                                       double price, Instant now, DetectedEvent.EventDirection direction,
                                       double strength, Double zScore, Double percentile) {

        long confirmationWindow = DetectedEvent.getDefaultConfirmationWindow(type);

        return DetectedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .familyId(familyId)
                .timeframe(timeframe)
                .eventType(type)
                .category(DetectedEvent.getCategoryForType(type))
                .detectedAt(now)
                .priceAtDetection(price)
                .candleTimestamp(now.toEpochMilli())
                .confirmationWindowMs(confirmationWindow)
                .expiresAt(now.plusMillis(confirmationWindow))
                .direction(direction)
                .strength(strength)
                .zScore(zScore)
                .percentile(percentile)
                .lifecycle(DetectedEvent.EventLifecycle.DETECTED)
                .confirmationCriteria(getConfirmationCriteria(type, direction, price))
                .failureCriteria(getFailureCriteria(type, direction, price))
                .build();
    }

    /**
     * Calculate velocity from current and previous values
     */
    private double calculateVelocity(MetricContext ctx) {
        if (ctx == null) return 0;
        double prev = ctx.getPreviousValue();
        if (prev == 0) return 0;
        return (ctx.getCurrentValue() - prev) / Math.abs(prev);
    }

    private boolean isSellExhaustion(MetricContext ofi) {
        if (ofi == null) return false;

        // Selling exhaustion: OFI negative but velocity turning positive
        boolean ofiNegative = ofi.getRegime() == MetricContext.MetricRegime.NEGATIVE ||
                              ofi.getRegime() == MetricContext.MetricRegime.STRONG_NEGATIVE;
        double velocity = calculateVelocity(ofi);

        return ofiNegative && velocity > EXHAUSTION_VELOCITY_FLIP;
    }

    private boolean isBuyExhaustion(MetricContext ofi) {
        if (ofi == null) return false;

        boolean ofiPositive = ofi.getRegime() == MetricContext.MetricRegime.POSITIVE ||
                              ofi.getRegime() == MetricContext.MetricRegime.STRONG_POSITIVE;
        double velocity = calculateVelocity(ofi);

        return ofiPositive && velocity < -EXHAUSTION_VELOCITY_FLIP;
    }

    /**
     * Check if exhaustion cooldown has expired for a family
     * Prevents signal spam from repeated exhaustion events within 5 minutes
     */
    private boolean isExhaustionCooldownExpired(String familyId, Instant now) {
        Long lastTime = lastExhaustionTime.get(familyId);
        if (lastTime == null) return true;
        return (now.toEpochMilli() - lastTime) > EXHAUSTION_COOLDOWN_MS;
    }

    private DetectedEvent.EventDirection determineAbsorptionDirection(HistoricalContext ctx) {
        // If OFI is turning bullish during absorption, buyers are absorbing
        MetricContext ofi = ctx.getOfiContext();
        if (ofi != null) {
            double velocity = calculateVelocity(ofi);
            if (velocity > 0) {
                return DetectedEvent.EventDirection.BULLISH;
            }
            if (velocity < 0) {
                return DetectedEvent.EventDirection.BEARISH;
            }
        }
        return DetectedEvent.EventDirection.NEUTRAL;
    }

    private DetectedEvent.EventDirection determineInformedFlowDirection(HistoricalContext ctx) {
        MetricContext ofi = ctx.getOfiContext();
        if (ofi != null) {
            if (ofi.getRegime() == MetricContext.MetricRegime.POSITIVE ||
                ofi.getRegime() == MetricContext.MetricRegime.STRONG_POSITIVE) {
                return DetectedEvent.EventDirection.BULLISH;
            }
            if (ofi.getRegime() == MetricContext.MetricRegime.NEGATIVE ||
                ofi.getRegime() == MetricContext.MetricRegime.STRONG_NEGATIVE) {
                return DetectedEvent.EventDirection.BEARISH;
            }
        }
        return DetectedEvent.EventDirection.NEUTRAL;
    }

    private boolean isGEXRegimeChange(GEXProfile gex) {
        // Simplified - ideally compare with previous GEX
        return gex.getRegime() == GEXProfile.GEXRegime.STRONG_TRENDING ||
               gex.getRegime() == GEXProfile.GEXRegime.STRONG_MEAN_REVERTING;
    }

    private boolean isGammaSqueezeSetup(GEXProfile gex, double price) {
        if (gex.getTotalGex() >= 0) return false; // Need negative GEX for squeeze

        // Check if price is near a high GEX strike
        double maxGexStrike = gex.getMaxGexStrike();
        if (maxGexStrike <= 0) return false;

        double distancePct = Math.abs(price - maxGexStrike) / price * 100;
        return distancePct < 2.0; // Within 2% of gamma wall
    }

    private boolean isOISurge(long oiChange, int optionCount) {
        // Simplified OI surge detection
        // In production, would use percentile-based threshold
        long avgThreshold = 500L * optionCount;
        return oiChange > avgThreshold;
    }

    private String getConfirmationCriteria(DetectedEvent.EventType type,
                                            DetectedEvent.EventDirection direction, double price) {
        double targetMove = type == DetectedEvent.EventType.OFI_FLIP ? 0.5 : 0.3;
        String moveDirection = direction == DetectedEvent.EventDirection.BULLISH ? "rises" : "falls";
        double target = direction == DetectedEvent.EventDirection.BULLISH ?
                price * (1 + targetMove / 100) : price * (1 - targetMove / 100);

        return String.format("Price %s %.2f%% to %.2f", moveDirection, targetMove, target);
    }

    private String getFailureCriteria(DetectedEvent.EventType type,
                                       DetectedEvent.EventDirection direction, double price) {
        double failMove = type == DetectedEvent.EventType.OFI_FLIP ? 0.5 : 0.3;
        String moveDirection = direction == DetectedEvent.EventDirection.BULLISH ? "falls" : "rises";
        double target = direction == DetectedEvent.EventDirection.BULLISH ?
                price * (1 - failMove / 100) : price * (1 + failMove / 100);

        return String.format("Price %s %.2f%% to %.2f", moveDirection, failMove, target);
    }
}
