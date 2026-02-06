package com.kotsin.consumer.signal.trigger;

import com.kotsin.consumer.indicator.model.TechnicalIndicators;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.options.model.OptionsAnalytics;
import com.kotsin.consumer.regime.detector.RegimeDetector;
import com.kotsin.consumer.regime.model.MarketRegime;
import com.kotsin.consumer.service.CandleService;
import com.kotsin.consumer.session.model.SessionStructure;
import com.kotsin.consumer.session.tracker.SessionStructureTracker;
import com.kotsin.consumer.signal.calculator.MicroAlphaCalculator;
import com.kotsin.consumer.signal.calculator.MicroAlphaCalculator.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.kotsin.consumer.breakout.model.BreakoutEvent;
import com.kotsin.consumer.breakout.model.BreakoutEvent.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MicroAlphaTrigger - Strategy 4: Microstructure Alpha Engine.
 *
 * EDGE: Exploit information asymmetry when institutional footprint
 * (orderbook, OI, options) diverges from price action.
 *
 * REGIME-ADAPTIVE ENTRY LOGIC:
 *
 * Mode: TREND_FOLLOWING (negative GEX + trending market)
 *   Entry: Flow conviction aligned with trend + OI buildup confirming + volume surge
 *   Stop: Below/above last swing + 1 ATR
 *   Target: Next OI wall or 2x ATR
 *
 * Mode: MEAN_REVERSION (positive GEX + ranging/near max pain)
 *   Entry: Price at extremity + gamma pulling toward max pain + options sentiment confirming
 *   Stop: Beyond VWAP band 2 or opening range extreme
 *   Target: Max pain or VWAP (whichever is closer in direction)
 *
 * Mode: BREAKOUT_AWAITING (negative GEX + ranging with OI loading)
 *   Entry: OI velocity spike + flow conviction surge + OR breakout or VWAP breakout
 *   Stop: Opposite side of opening range
 *   Target: Call/put wall in breakout direction
 *
 * EVALUATION FREQUENCY: Every 5 minutes (aggregated M5 candle boundary).
 * This is NOT a 1-minute scalper — it waits for sufficient data to form conviction.
 *
 * KAFKA TOPIC: microalpha-signals
 */
@Component
@Slf4j
public class MicroAlphaTrigger {

    private static final String LOG_PREFIX = "[MICRO-ALPHA]";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Autowired
    private MicroAlphaCalculator calculator;

    @Autowired
    private RegimeDetector regimeDetector;

    @Autowired
    private CandleService candleService;

    @Autowired
    private SessionStructureTracker sessionTracker;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${microalpha.enabled:true}")
    private boolean enabled;

    @Value("${microalpha.kafka.topic:microalpha-signals}")
    private String kafkaTopic;

    @Value("${microalpha.trigger.min.conviction:45}")
    private double minConviction;

    @Value("${microalpha.trigger.high.conviction:65}")
    private double highConviction;

    @Value("${microalpha.trigger.cooldown.minutes:10}")
    private int cooldownMinutes;

    @Value("${microalpha.trigger.max.signals.per.day:8}")
    private int maxSignalsPerDay;

    @Value("${microalpha.trigger.require.orderbook:true}")
    private boolean requireOrderbook;

    @Value("${microalpha.trigger.atr.stop.multiplier:1.5}")
    private double atrStopMultiplier;

    @Value("${microalpha.trigger.evaluation.timeframe:5m}")
    private String evaluationTimeframe;

    // State tracking
    private final Map<String, MicroAlphaScore> lastScores = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastTriggerTime = new ConcurrentHashMap<>();
    private final Map<String, Integer> dailySignalCount = new ConcurrentHashMap<>();
    private final Map<String, MicroAlphaScore> previousScore = new ConcurrentHashMap<>();

    // Level context from BreakoutDetector (set by SignalEngine before evaluate)
    private final ConcurrentHashMap<String, List<BreakoutEvent>> levelContext = new ConcurrentHashMap<>();

    // ==================== LEVEL CONTEXT ====================

    /**
     * Set breakout/retest level context from BreakoutDetector.
     * Called by SignalEngine before evaluate() to provide structural level awareness.
     */
    public void setLevelContext(String symbol, List<BreakoutEvent> events) {
        if (events != null && !events.isEmpty()) {
            levelContext.put(symbol, events);
            log.debug("{} {} Level context set: {} events (types: {})", LOG_PREFIX, symbol, events.size(),
                events.stream().map(e -> e.getType().name()).distinct().toList());
        } else {
            levelContext.remove(symbol);
        }
    }

    // ==================== MAIN ENTRY POINT ====================

    /**
     * Evaluate microalpha trigger for a symbol.
     * Called by SignalEngine on each processing cycle.
     *
     * @param symbol     Symbol to evaluate
     * @param candle     Current unified candle (1m with merged orderbook + OI)
     * @param indicators Technical indicators (may be null)
     * @param options    Options analytics (may be null)
     * @return MicroAlphaTriggerResult
     */
    public MicroAlphaTriggerResult evaluate(String symbol, UnifiedCandle candle,
                                             TechnicalIndicators indicators, OptionsAnalytics options) {
        if (!enabled) {
            return MicroAlphaTriggerResult.noTrigger("MicroAlpha disabled");
        }

        if (candle == null) {
            return MicroAlphaTriggerResult.noTrigger("No candle data");
        }

        // --- Gate 1: Market hours check ---
        String exchange = candle.getExchange() != null ? candle.getExchange() : "N";
        if (!isMarketHours(exchange)) {
            return MicroAlphaTriggerResult.noTrigger("Outside market hours");
        }

        // --- Gate 2: Cooldown check ---
        if (isInCooldown(symbol)) {
            return MicroAlphaTriggerResult.noTrigger("In cooldown period");
        }

        // --- Gate 3: Daily signal cap ---
        if (isDailyCapReached(symbol)) {
            return MicroAlphaTriggerResult.noTrigger("Daily signal cap reached");
        }

        // --- Gate 4: Data quality check ---
        if (requireOrderbook && !candle.isHasOrderbook()) {
            return MicroAlphaTriggerResult.noTrigger("No orderbook data (required)");
        }

        try {
            // Gather remaining data sources
            SessionStructure session = getSessionStructure(symbol);
            MarketRegime regime = regimeDetector.getCurrentRegime(symbol);

            // Calculate conviction score
            MicroAlphaScore score = calculator.calculate(candle, options, session, indicators, regime);
            lastScores.put(symbol, score);

            // Check if actionable
            if (!score.isActionable()) {
                return MicroAlphaTriggerResult.noTrigger(
                    String.format("Conviction %.1f below threshold %.0f (mode=%s)",
                        score.getAbsConviction(), minConviction, score.getTradingMode()));
            }

            // --- Gate 5: Mode-specific entry validation ---
            ModeEntryResult entryResult = validateModeSpecificEntry(
                symbol, candle, score, options, session, indicators);

            if (!entryResult.isValid()) {
                return MicroAlphaTriggerResult.noTrigger(
                    String.format("Entry rejected by %s mode: %s",
                        score.getTradingMode(), entryResult.getReason()));
            }

            // --- Gate 6: Momentum confirmation (prevent false triggers) ---
            if (!hasConvictionMomentum(symbol, score)) {
                return MicroAlphaTriggerResult.noTrigger("Conviction not building (momentum check failed)");
            }

            // All gates passed — TRIGGER!
            TriggerDirection direction = score.getDirection() == ConvictionDirection.BULLISH ?
                TriggerDirection.BULLISH : TriggerDirection.BEARISH;

            // Structural level conviction bonus: trading at a key level boosts conviction
            double convictionBonus = calculateStructuralBonus(symbol);
            if (convictionBonus > 0) {
                log.info("{} {} Structural level bonus: +{} conviction", LOG_PREFIX, symbol,
                    String.format("%.1f", convictionBonus));
            }

            // Calculate risk management levels
            RiskLevels riskLevels = calculateRiskLevels(
                candle, score, options, session, indicators, direction);

            double finalConviction = score.getConviction() + (score.getConviction() > 0 ? convictionBonus : -convictionBonus);
            double finalAbsConviction = Math.min(score.getAbsConviction() + convictionBonus, 100);

            MicroAlphaTriggerResult result = MicroAlphaTriggerResult.builder()
                .triggered(true)
                .direction(direction)
                .score(score)
                .tradingMode(score.getTradingMode())
                .conviction(finalConviction)
                .absConviction(finalAbsConviction)
                .entryPrice(candle.getClose())
                .stopLoss(riskLevels.getStopLoss())
                .target(riskLevels.getTarget())
                .riskReward(riskLevels.getRiskReward())
                .reason(buildTriggerReason(score, entryResult))
                .reasons(score.getReasons())
                .triggerTime(Instant.now())
                .build();

            // Record trigger
            lastTriggerTime.put(symbol, Instant.now());
            dailySignalCount.merge(symbol, 1, Integer::sum);

            // Publish to Kafka
            publishToKafka(symbol, result);

            log.info("{} {} TRIGGER FIRED: direction={} conviction={} mode={} entry={} stop={} target={} R:R={}",
                LOG_PREFIX, symbol, direction, String.format("%.1f", score.getConviction()), score.getTradingMode(),
                String.format("%.2f", candle.getClose()), String.format("%.2f", riskLevels.getStopLoss()),
                String.format("%.2f", riskLevels.getTarget()), String.format("%.2f", riskLevels.getRiskReward()));

            for (String reason : score.getReasons()) {
                log.info("{} {}   → {}", LOG_PREFIX, symbol, reason);
            }

            return result;

        } catch (Exception e) {
            log.error("{} {} Error evaluating: {}", LOG_PREFIX, symbol, e.getMessage(), e);
            return MicroAlphaTriggerResult.noTrigger("Error: " + e.getMessage());
        }
    }

    /**
     * Force evaluate (for SignalEngine backward compatibility).
     */
    public MicroAlphaTriggerResult forceCheck(String symbol) {
        try {
            List<UnifiedCandle> candles = candleService.getCandleHistory(symbol, Timeframe.M1, 1);
            if (candles == null || candles.isEmpty()) {
                return MicroAlphaTriggerResult.noTrigger("No candles found");
            }
            return evaluate(symbol, candles.get(0), null, null);
        } catch (Exception e) {
            return MicroAlphaTriggerResult.noTrigger("Error: " + e.getMessage());
        }
    }

    // ==================== MODE-SPECIFIC ENTRY VALIDATION ====================

    /**
     * Validate entry conditions specific to the current trading mode.
     * Each mode has different requirements for what constitutes a valid entry.
     */
    private ModeEntryResult validateModeSpecificEntry(String symbol, UnifiedCandle candle,
                                                       MicroAlphaScore score, OptionsAnalytics options,
                                                       SessionStructure session, TechnicalIndicators indicators) {

        switch (score.getTradingMode()) {
            case TREND_FOLLOWING:
                return validateTrendEntry(candle, score, indicators);

            case MEAN_REVERSION:
                return validateMeanReversionEntry(candle, score, options, session);

            case BREAKOUT_AWAITING:
                return validateBreakoutEntry(candle, score, session, indicators);

            case TREND_WITH_CAUTION:
                return validateTrendEntry(candle, score, indicators); // Same as trend but with extra caution

            case AVOID:
                return ModeEntryResult.reject("Trading mode is AVOID");

            case CAUTIOUS:
            default:
                // Cautious mode: need high conviction
                if (score.getAbsConviction() < highConviction) {
                    return ModeEntryResult.reject(
                        String.format("CAUTIOUS mode needs conviction >= %.0f (got %.1f)",
                            highConviction, score.getAbsConviction()));
                }
                return ModeEntryResult.accept("Cautious mode with high conviction");
        }
    }

    /**
     * TREND_FOLLOWING entry validation:
     * - Flow conviction aligned with trend direction
     * - OI confirms (buildup, not covering/unwinding)
     * - Volume above average
     */
    private ModeEntryResult validateTrendEntry(UnifiedCandle candle, MicroAlphaScore score,
                                                TechnicalIndicators indicators) {

        boolean bullish = score.getDirection() == ConvictionDirection.BULLISH;
        List<String> passes = new ArrayList<>();
        List<String> fails = new ArrayList<>();

        // Check 1: Flow conviction aligned
        double flowScore = score.getFlowScore().getScore();
        if ((bullish && flowScore > 15) || (!bullish && flowScore < -15)) {
            passes.add("Flow aligned with direction");
        } else {
            fails.add(String.format("Flow not aligned (%.0f vs %s)", flowScore, bullish ? "BULL" : "BEAR"));
        }

        // Check 2: OI confirms continuation (not exhaustion)
        OIScore oiScore = score.getOiScore();
        if (oiScore.getInterpretation() != null) {
            boolean oiContinuation = oiScore.getInterpretation().suggestsContinuation();
            if (oiContinuation) {
                passes.add("OI confirms continuation (" + oiScore.getInterpretation() + ")");
            } else {
                fails.add("OI suggests exhaustion (" + oiScore.getInterpretation() + ")");
            }
        }

        // Check 3: Volume above average
        if (indicators != null && indicators.getVolumeRatio() > 1.0) {
            passes.add(String.format("Volume above average (%.1fx)", indicators.getVolumeRatio()));
        } else {
            fails.add("Volume below average");
        }

        // Check 4: Technical trend alignment
        if (indicators != null) {
            boolean techAligned = bullish ? indicators.isBullish() : indicators.isBearish();
            if (techAligned) {
                passes.add("Technical indicators confirm trend");
            } else {
                fails.add("Technical indicators diverge from direction");
            }
        }

        // Need at least 3 out of 4 checks to pass
        boolean valid = passes.size() >= 3;
        String reason = valid ?
            "TREND entry: " + String.join(", ", passes) :
            "TREND entry rejected: " + String.join(", ", fails);

        return valid ? ModeEntryResult.accept(reason) : ModeEntryResult.reject(reason);
    }

    /**
     * MEAN_REVERSION entry validation:
     * - Price at extremity (near band/VWAP extreme)
     * - Gamma pulling toward max pain
     * - Options sentiment confirming reversal
     */
    private ModeEntryResult validateMeanReversionEntry(UnifiedCandle candle, MicroAlphaScore score,
                                                        OptionsAnalytics options, SessionStructure session) {
        boolean bullish = score.getDirection() == ConvictionDirection.BULLISH;
        List<String> passes = new ArrayList<>();
        List<String> fails = new ArrayList<>();

        // Check 1: Gamma score supports reversion direction
        double gammaScore = score.getGammaRegime().getScore();
        if ((bullish && gammaScore > 20) || (!bullish && gammaScore < -20)) {
            passes.add(String.format("Gamma regime supports reversion (score=%.0f)", gammaScore));
        } else {
            fails.add(String.format("Gamma regime weak (score=%.0f)", gammaScore));
        }

        // Check 2: Options sentiment supports direction
        double optScore = score.getOptionsSentiment().getScore();
        if ((bullish && optScore > 10) || (!bullish && optScore < -10)) {
            passes.add("Options sentiment confirms");
        } else {
            fails.add("Options sentiment doesn't confirm");
        }

        // Check 3: Price at extremity (near VWAP band or session extreme)
        boolean atExtremity = false;
        if (session != null) {
            double price = candle.getClose();
            double vwap = session.getVwap();
            double upperBand2 = session.getVwapUpperBand2();
            double lowerBand2 = session.getVwapLowerBand2();

            if (bullish && lowerBand2 > 0 && price < lowerBand2) {
                atExtremity = true;
                passes.add("Price below VWAP -2SD (oversold extremity)");
            } else if (!bullish && upperBand2 > 0 && price > upperBand2) {
                atExtremity = true;
                passes.add("Price above VWAP +2SD (overbought extremity)");
            } else if (session.isNearSessionLow() && bullish) {
                atExtremity = true;
                passes.add("Price near session low");
            } else if (session.isNearSessionHigh() && !bullish) {
                atExtremity = true;
                passes.add("Price near session high");
            }
        }
        // Level context: price at a registered structural level counts as extremity
        if (!atExtremity) {
            String symbol = String.valueOf(candle.getScripCode());
            List<BreakoutEvent> events = levelContext.get(symbol);
            if (events != null) {
                log.debug("{} {} MR validation: checking {} BreakoutDetector events for extremity",
                    LOG_PREFIX, symbol, events.size());
                for (BreakoutEvent event : events) {
                    if (event.getType() == BreakoutType.RETEST && event.isRetestHeld()) {
                        atExtremity = true;
                        passes.add("At confirmed retest level: " + event.getLevelDescription());
                        log.debug("{} {} MR validation: confirmed retest at {} counts as extremity",
                            LOG_PREFIX, symbol, event.getLevelDescription());
                        break;
                    }
                }
            }
        }
        if (!atExtremity) {
            fails.add("Price not at extremity");
        }

        // Check 4: Near max pain (target for reversion)
        if (options != null && Math.abs(options.getMaxPainDistance()) > 0.003) {
            passes.add(String.format("Max pain %.1f%% away (reversion target)", options.getMaxPainDistance() * 100));
        }

        boolean valid = passes.size() >= 2 && atExtremity; // Must be at extremity + 1 more
        String reason = valid ?
            "MR entry: " + String.join(", ", passes) :
            "MR entry rejected: " + String.join(", ", fails);

        return valid ? ModeEntryResult.accept(reason) : ModeEntryResult.reject(reason);
    }

    /**
     * BREAKOUT_AWAITING entry validation:
     * - OI velocity spike (new positions entering rapidly)
     * - Flow conviction surge
     * - OR breakout or VWAP breakout
     */
    private ModeEntryResult validateBreakoutEntry(UnifiedCandle candle, MicroAlphaScore score,
                                                    SessionStructure session, TechnicalIndicators indicators) {
        boolean bullish = score.getDirection() == ConvictionDirection.BULLISH;
        List<String> passes = new ArrayList<>();
        List<String> fails = new ArrayList<>();

        // Check 1: OI velocity high (positions loading fast)
        double oiVel = score.getOiScore().getOiVelocity();
        if (Math.abs(oiVel) > 0.5) {
            passes.add(String.format("OI velocity high (%.1f) - positions loading", oiVel));
        } else {
            fails.add(String.format("OI velocity low (%.1f) - no position buildup", oiVel));
        }

        // Check 2: Flow conviction surge
        double flowScore = Math.abs(score.getFlowScore().getScore());
        if (flowScore > 40) {
            passes.add(String.format("Flow conviction surge (%.0f)", flowScore));
        } else {
            fails.add(String.format("Flow conviction insufficient (%.0f)", flowScore));
        }

        // Check 3: Structural breakout (OR, VWAP, or registered level from BreakoutDetector)
        boolean structuralBreak = false;
        if (session != null && session.isOpeningRangeComplete()) {
            if (bullish && candle.getClose() > session.getOpeningRangeHigh30()) {
                structuralBreak = true;
                passes.add("Above 30m Opening Range (structural breakout)");
            } else if (!bullish && candle.getClose() < session.getOpeningRangeLow30()) {
                structuralBreak = true;
                passes.add("Below 30m Opening Range (structural breakdown)");
            } else if (bullish && session.isAboveVwap()) {
                structuralBreak = true;
                passes.add("Above VWAP (institutional flow breakout)");
            } else if (!bullish && session.isBelowVwap()) {
                structuralBreak = true;
                passes.add("Below VWAP (institutional flow breakdown)");
            }
        }
        // Check BreakoutDetector events for structural break at registered levels
        if (!structuralBreak) {
            String symbol = String.valueOf(candle.getScripCode());
            List<BreakoutEvent> events = levelContext.get(symbol);
            if (events != null) {
                log.debug("{} {} BKO validation: checking {} BreakoutDetector events for structural break",
                    LOG_PREFIX, symbol, events.size());
                for (BreakoutEvent event : events) {
                    if (event.getType() == BreakoutType.BREAKOUT) {
                        structuralBreak = true;
                        passes.add("Breakout at " + event.getLevelDescription() + " (registered level)");
                        log.debug("{} {} BKO validation: structural break confirmed via BREAKOUT at {}",
                            LOG_PREFIX, symbol, event.getLevelDescription());
                        break;
                    } else if (event.getType() == BreakoutType.RETEST && event.isRetestHeld()) {
                        structuralBreak = true;
                        passes.add("Retest held at " + event.getLevelDescription() + " (confirmed)");
                        log.debug("{} {} BKO validation: structural break confirmed via RETEST at {}",
                            LOG_PREFIX, symbol, event.getLevelDescription());
                        break;
                    }
                }
            }
        }
        if (!structuralBreak) {
            fails.add("No structural breakout detected");
        }

        // Check 4: Volume spike
        if (indicators != null && indicators.getVolumeRatio() > 1.5) {
            passes.add(String.format("Volume spike (%.1fx avg)", indicators.getVolumeRatio()));
        } else {
            fails.add("Volume not confirming breakout");
        }

        // Need structural break + at least 1 more confirmation
        boolean valid = structuralBreak && passes.size() >= 3;
        String reason = valid ?
            "BKO entry: " + String.join(", ", passes) :
            "BKO entry rejected: " + String.join(", ", fails);

        return valid ? ModeEntryResult.accept(reason) : ModeEntryResult.reject(reason);
    }

    // ==================== MOMENTUM CHECK ====================

    /**
     * Check that conviction is building, not spiking randomly.
     * Compare current score with previous score to ensure direction consistency.
     */
    /**
     * Calculate conviction bonus from structural level context.
     * Trading at a confirmed retest or active breakout boosts conviction.
     */
    private double calculateStructuralBonus(String symbol) {
        List<BreakoutEvent> events = levelContext.get(symbol);
        if (events == null || events.isEmpty()) return 0;

        double bonus = 0;
        String bestLevel = null;
        String bestType = null;
        for (BreakoutEvent event : events) {
            if (event.getType() == BreakoutType.RETEST && event.isRetestHeld()) {
                RetestQuality quality = event.getRetestQuality();
                if (quality == RetestQuality.PERFECT) {
                    if (bonus < 10) { bestLevel = event.getLevelDescription(); bestType = "PERFECT_RETEST"; }
                    bonus = Math.max(bonus, 10);
                } else if (quality == RetestQuality.GOOD) {
                    if (bonus < 7) { bestLevel = event.getLevelDescription(); bestType = "GOOD_RETEST"; }
                    bonus = Math.max(bonus, 7);
                } else {
                    if (bonus < 3) { bestLevel = event.getLevelDescription(); bestType = "RETEST"; }
                    bonus = Math.max(bonus, 3);
                }
            } else if (event.getType() == BreakoutType.BREAKOUT) {
                if (bonus < 5) { bestLevel = event.getLevelDescription(); bestType = "BREAKOUT"; }
                bonus = Math.max(bonus, 5);
            }
        }
        if (bonus > 0) {
            log.debug("{} {} Structural bonus: +{} from {} at {}",
                LOG_PREFIX, symbol, String.format("%.0f", bonus), bestType, bestLevel);
        }
        return bonus;
    }

    private boolean hasConvictionMomentum(String symbol, MicroAlphaScore current) {
        MicroAlphaScore prev = previousScore.get(symbol);
        previousScore.put(symbol, current);

        if (prev == null) {
            // First evaluation — allow if conviction meets minimum threshold
            // (using minConviction instead of highConviction to avoid impossible gate on first signal)
            return current.getAbsConviction() >= minConviction;
        }

        // Same direction and conviction increasing or stable
        boolean sameDirection = current.getDirection() == prev.getDirection();
        boolean convictionIncreasing = current.getAbsConviction() >= prev.getAbsConviction() * 0.9;

        // If high conviction, allow even without momentum
        if (current.isHighConviction()) return true;

        return sameDirection && convictionIncreasing;
    }

    // ==================== RISK MANAGEMENT ====================

    /**
     * Calculate stop loss, target, and risk/reward.
     * Different modes use different stop/target logic.
     */
    private RiskLevels calculateRiskLevels(UnifiedCandle candle, MicroAlphaScore score,
                                            OptionsAnalytics options, SessionStructure session,
                                            TechnicalIndicators indicators, TriggerDirection direction) {
        double price = candle.getClose();
        double atr = indicators != null ? indicators.getAtr14() : price * 0.01;
        boolean bullish = direction == TriggerDirection.BULLISH;

        double stopLoss;
        double target;

        switch (score.getTradingMode()) {
            case TREND_FOLLOWING:
            case TREND_WITH_CAUTION:
                // Stop: ATR-based
                stopLoss = bullish ?
                    price - atr * atrStopMultiplier :
                    price + atr * atrStopMultiplier;

                // Target: OI wall or 2x ATR
                target = getOIWallTarget(price, options, bullish, atr);
                break;

            case MEAN_REVERSION:
                // Stop: Beyond VWAP band 2 or session extreme
                stopLoss = getMeanReversionStop(price, session, atr, bullish);

                // Target: Max pain or VWAP (whichever closer in direction)
                target = getMeanReversionTarget(price, options, session, bullish);
                break;

            case BREAKOUT_AWAITING:
                // Stop: Opposite side of opening range
                stopLoss = getBreakoutStop(price, session, atr, bullish);

                // Target: Call/put wall in breakout direction
                target = getOIWallTarget(price, options, bullish, atr);
                break;

            default:
                // Cautious: tight ATR stop
                stopLoss = bullish ? price - atr : price + atr;
                target = bullish ? price + atr * 2 : price - atr * 2;
        }

        // Ensure minimum R:R of 1.5
        double risk = Math.abs(price - stopLoss);
        double reward = Math.abs(target - price);
        double rr = risk > 0 ? reward / risk : 0;

        if (rr < 1.5 && risk > 0) {
            // Adjust target to ensure minimum R:R
            target = bullish ? price + risk * 1.5 : price - risk * 1.5;
            reward = risk * 1.5;
            rr = 1.5;
        }

        return RiskLevels.builder()
            .stopLoss(stopLoss)
            .target(target)
            .risk(risk)
            .reward(reward)
            .riskReward(rr)
            .build();
    }

    private double getOIWallTarget(double price, OptionsAnalytics options, boolean bullish, double atr) {
        if (options != null) {
            if (bullish && options.getCallOIWall() > price) {
                return options.getCallOIWall(); // Target = call wall (resistance)
            }
            if (!bullish && options.getPutOIWall() > 0 && options.getPutOIWall() < price) {
                return options.getPutOIWall(); // Target = put wall (support)
            }
        }
        // Fallback: 2x ATR
        return bullish ? price + atr * 2 : price - atr * 2;
    }

    private double getMeanReversionStop(double price, SessionStructure session, double atr, boolean bullish) {
        if (session != null) {
            if (bullish && session.getVwapLowerBand2() > 0) {
                return session.getVwapLowerBand2() - atr * 0.3; // Below VWAP -2SD
            }
            if (!bullish && session.getVwapUpperBand2() > 0) {
                return session.getVwapUpperBand2() + atr * 0.3; // Above VWAP +2SD
            }
        }
        return bullish ? price - atr * 1.2 : price + atr * 1.2;
    }

    private double getMeanReversionTarget(double price, OptionsAnalytics options, SessionStructure session,
                                           boolean bullish) {
        double maxPainTarget = Double.NaN;
        double vwapTarget = Double.NaN;

        if (options != null && options.getMaxPain() > 0) {
            maxPainTarget = options.getMaxPain();
        }
        if (session != null && session.getVwap() > 0) {
            vwapTarget = session.getVwap();
        }

        // Use whichever is closer in the direction of reversion
        if (bullish) {
            // Target is UP, so pick the lower of max pain / VWAP (closer target)
            double target = price * 1.01; // Default: 1% move
            if (!Double.isNaN(maxPainTarget) && maxPainTarget > price) {
                target = maxPainTarget;
            }
            if (!Double.isNaN(vwapTarget) && vwapTarget > price && vwapTarget < target) {
                target = vwapTarget;
            }
            return target;
        } else {
            double target = price * 0.99;
            if (!Double.isNaN(maxPainTarget) && maxPainTarget < price) {
                target = maxPainTarget;
            }
            if (!Double.isNaN(vwapTarget) && vwapTarget < price && vwapTarget > target) {
                target = vwapTarget;
            }
            return target;
        }
    }

    private double getBreakoutStop(double price, SessionStructure session, double atr, boolean bullish) {
        if (session != null && session.isOpeningRangeComplete()) {
            if (bullish) {
                return session.getOpeningRangeLow30() - atr * 0.2; // Below OR low
            } else {
                return session.getOpeningRangeHigh30() + atr * 0.2; // Above OR high
            }
        }
        return bullish ? price - atr * 1.5 : price + atr * 1.5;
    }

    // ==================== DATA RETRIEVAL ====================

    private SessionStructure getSessionStructure(String symbol) {
        try {
            return sessionTracker.getSession(symbol);
        } catch (Exception e) {
            log.debug("{} {} Failed to get session structure: {}", LOG_PREFIX, symbol, e.getMessage());
        }
        return null;
    }

    // ==================== UTILITY METHODS ====================

    private boolean isMarketHours(String exchange) {
        ZonedDateTime now = ZonedDateTime.now(IST);
        LocalTime time = now.toLocalTime();

        if ("M".equalsIgnoreCase(exchange)) {
            return !time.isBefore(LocalTime.of(9, 0)) && !time.isAfter(LocalTime.of(23, 30));
        }
        return !time.isBefore(LocalTime.of(9, 15)) && !time.isAfter(LocalTime.of(15, 30));
    }

    private boolean isInCooldown(String symbol) {
        Instant lastTrigger = lastTriggerTime.get(symbol);
        if (lastTrigger == null) return false;
        return Instant.now().isBefore(lastTrigger.plusSeconds(cooldownMinutes * 60L));
    }

    private boolean isDailyCapReached(String symbol) {
        int count = dailySignalCount.getOrDefault(symbol, 0);
        return count >= maxSignalsPerDay;
    }

    /**
     * Reset daily counters (should be called at market open).
     */
    public void resetDailyCounters() {
        dailySignalCount.clear();
        previousScore.clear();
        log.info("{} Daily counters reset", LOG_PREFIX);
    }

    private void publishToKafka(String scripCode, MicroAlphaTriggerResult result) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            // Match standard trigger Kafka contract (scripCode, triggered, direction, reason, triggerTime)
            payload.put("scripCode", scripCode);
            payload.put("triggered", result.isTriggered());
            payload.put("direction", result.getDirection().name());
            payload.put("reason", result.getReason());
            payload.put("triggerTime", result.getTriggerTime() != null ? result.getTriggerTime().toString() : null);

            // MicroAlpha-specific fields
            payload.put("strategy", "MICRO_ALPHA");
            payload.put("score", result.getConviction());
            payload.put("conviction", result.getConviction());
            payload.put("absConviction", result.getAbsConviction());
            payload.put("tradingMode", result.getTradingMode().name());
            payload.put("entryPrice", result.getEntryPrice());
            payload.put("stopLoss", result.getStopLoss());
            payload.put("target", result.getTarget());
            payload.put("riskReward", result.getRiskReward());
            payload.put("reasons", result.getReasons());

            // Sub-score breakdown
            if (result.getScore() != null) {
                Map<String, Object> scores = new LinkedHashMap<>();
                scores.put("flow", result.getScore().getFlowScore().getScore());
                scores.put("oi", result.getScore().getOiScore().getScore());
                scores.put("gamma", result.getScore().getGammaRegime().getScore());
                scores.put("options", result.getScore().getOptionsSentiment().getScore());
                scores.put("session", result.getScore().getSessionContext().getScore());
                payload.put("subScores", scores);

                // Data quality
                payload.put("hasOrderbook", result.getScore().isHasOrderbook());
                payload.put("hasOI", result.getScore().isHasOI());
                payload.put("hasOptions", result.getScore().isHasOptions());
                payload.put("hasSession", result.getScore().isHasSession());
            }

            kafkaTemplate.send(kafkaTopic, scripCode, payload);
            log.debug("{} {} Published to Kafka topic: {}", LOG_PREFIX, scripCode, kafkaTopic);
        } catch (Exception e) {
            log.error("{} {} Failed to publish to Kafka: {}", LOG_PREFIX, scripCode, e.getMessage());
        }
    }

    private String buildTriggerReason(MicroAlphaScore score, ModeEntryResult entryResult) {
        return String.format("MicroAlpha %s [mode=%s conviction=%.1f]: %s",
            score.getDirection(), score.getTradingMode(),
            score.getConviction(), entryResult.getReason());
    }

    // ==================== PUBLIC GETTERS ====================

    public MicroAlphaScore getLastScore(String symbol) {
        return lastScores.get(symbol);
    }

    public Map<String, MicroAlphaScore> getAllScores() {
        return Collections.unmodifiableMap(lastScores);
    }

    // ==================== RESULT CLASSES ====================

    @Data
    @Builder
    public static class MicroAlphaTriggerResult {
        private boolean triggered;
        private TriggerDirection direction;
        private MicroAlphaScore score;
        private MicroAlphaCalculator.TradingMode tradingMode;
        private double conviction;
        private double absConviction;
        private double entryPrice;
        private double stopLoss;
        private double target;
        private double riskReward;
        private String reason;
        private List<String> reasons;
        private Instant triggerTime;

        public static MicroAlphaTriggerResult noTrigger(String reason) {
            return MicroAlphaTriggerResult.builder()
                .triggered(false)
                .direction(TriggerDirection.NONE)
                .reason(reason)
                .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskLevels {
        private double stopLoss;
        private double target;
        private double risk;
        private double reward;
        private double riskReward;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModeEntryResult {
        private boolean valid;
        private String reason;

        public static ModeEntryResult accept(String reason) {
            return ModeEntryResult.builder().valid(true).reason(reason).build();
        }

        public static ModeEntryResult reject(String reason) {
            return ModeEntryResult.builder().valid(false).reason(reason).build();
        }
    }

    public enum TriggerDirection {
        BULLISH, BEARISH, NONE
    }
}
