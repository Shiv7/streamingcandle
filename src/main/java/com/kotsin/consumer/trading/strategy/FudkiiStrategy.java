package com.kotsin.consumer.trading.strategy;

import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.model.HistoricalContext;
import com.kotsin.consumer.enrichment.model.TechnicalContext;
import com.kotsin.consumer.enrichment.signal.model.SignalRationale;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal.Direction;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal.Horizon;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal.SignalCategory;
import com.kotsin.consumer.trading.model.Position;
import com.kotsin.consumer.trading.model.TradeOutcome.ExitReason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * FudkiiStrategy - Bollinger Band squeeze + SuperTrend flip breakout.
 *
 * EDGE: BB squeeze = volatility contraction = energy building.
 *       SuperTrend flip = direction confirmed.
 *       Together = high probability momentum move.
 *
 * TIMEFRAME: 30 MINUTES ONLY
 *
 * ENTRY CONDITIONS (ALL required):
 * 1. BB squeeze detected (BB width < 20th percentile)
 * 2. SuperTrend flips direction (on 30m candle)
 * 3. Price breaks outside BB (close > upper for LONG, < lower for SHORT)
 * 4. Volume surge: current volume > 1.3x average
 * 5. OFI confirms direction (z-score > 1.5)
 * 6. MTF alignment: 15m and 30m SuperTrend agree
 *
 * STOP LOSS: SuperTrend value (dynamic trailing)
 * TARGETS: T1 = 1.5 ATR, T2 = 2.5 ATR (trail)
 * TIME STOP: 2 hours (breakouts should move fast)
 */
@Slf4j
@Component
public class FudkiiStrategy implements TradingStrategy {

    private static final String STRATEGY_ID = "FUDKII";
    private static final String REQUIRED_TIMEFRAME = "30m";

    // Thresholds
    private static final double MIN_OFI_ZSCORE = 1.5;
    private static final double BB_SQUEEZE_PERCENTILE = 20.0;  // BB width < 20th percentile
    private static final double VOLUME_SURGE_MULTIPLIER = 1.3;

    // Risk
    private static final double TARGET1_ATR_MULT = 1.5;
    private static final double TARGET2_ATR_MULT = 2.5;

    // Time limits - Extended to 60 minutes for 2-candle confirmation (2 x 30m candles)
    private static final long MAX_WATCHING_MS = 60 * 60 * 1000;   // 60 minutes (2 candles for confirmation)
    private static final long MAX_POSITION_MS = 2 * 60 * 60 * 1000; // 2 hours (breakouts should be fast)
    private static final long COOLDOWN_MS = 60 * 60 * 1000;       // 60 minutes (longer cooldown for breakout strategy)

    // NEAR-BREAKOUT PROXIMITY THRESHOLDS
    // If price is within this threshold of BB/ST breakout level, keep signal active for 2-candle confirmation
    private static final double MIN_PROXIMITY_PCT = 0.001;        // Minimum 0.1% proximity threshold
    private static final double ATR_PROXIMITY_MULT = 0.10;        // 10% of ATR as proximity threshold
    private static final double MAX_ADVERSE_MOVE_PCT = 0.005;     // Invalidate if price moves 0.5% against direction
    private static final int MAX_CANDLES_FOR_CONFIRMATION = 2;    // Max candles to wait for confirmation

    @Override
    public String getStrategyId() {
        return STRATEGY_ID;
    }

    @Override
    public String getDescription() {
        return "BB squeeze + SuperTrend flip breakout (30m timeframe)";
    }

    @Override
    public StrategyParams getParams() {
        return StrategyParams.builder()
                .strategyId(STRATEGY_ID)
                .requiredTimeframe(REQUIRED_TIMEFRAME)
                .maxWatchingDurationMs(MAX_WATCHING_MS)
                .maxPositionDurationMs(MAX_POSITION_MS)
                .cooldownDurationMs(COOLDOWN_MS)
                .defaultStopPct(0.8)  // Dynamic based on SuperTrend
                .target1Pct(1.0)
                .target2Pct(2.0)
                .minRiskRewardRatio(1.5)
                .minConfidence(0.65)
                .minOfiZscore(MIN_OFI_ZSCORE)
                .maxVpin(0.85)  // Allow higher VPIN for breakouts (informed traders front-running)
                .minSessionPosition(0.20)  // More lenient for breakouts
                .maxSessionPosition(0.80)
                .build();
    }

    @Override
    public Optional<SetupContext> detectSetupForming(EnrichedQuantScore score) {
        // DIAGNOSTIC: Entry point
        String scripCode = score != null ? score.getScripCode() : "null";
        log.debug("[FUDKII_DIAG] detectSetupForming ENTRY | scripCode={}", scripCode);

        if (score == null) {
            log.debug("[FUDKII_DIAG] REJECTED: score is NULL");
            return Optional.empty();
        }

        TechnicalContext tech = score.getTechnicalContext();
        if (tech == null) {
            log.debug("[FUDKII_DIAG] {} REJECTED: TechnicalContext is NULL", scripCode);
            return Optional.empty();
        }

        // FIX: Filter by required timeframe FIRST - FUDKII only works on 30m candles
        String timeframe = tech.getTimeframe();
        if (!REQUIRED_TIMEFRAME.equals(timeframe)) {
            log.trace("[FUDKII] {} Skipping non-30m candle | tf={}", scripCode, timeframe);
            return Optional.empty();
        }

        // FIX: Validate BB data quality - reject if insufficient history caused fake values
        // When history < 20 candles, BB returns bbWidthPct=0 and bbPercentB=0.5 or NaN
        if (tech.getBbUpper() <= 0 || tech.getBbLower() <= 0 ||
            tech.getBbUpper() <= tech.getBbLower() ||
            Double.isNaN(tech.getBbPercentB())) {
            log.debug("[FUDKII_DIAG] {} REJECTED: Invalid BB data (insufficient history) | " +
                    "bbUpper={} | bbLower={} | bbPctB={}",
                    scripCode, tech.getBbUpper(), tech.getBbLower(), tech.getBbPercentB());
            return Optional.empty();
        }

        double price = score.getClose();
        if (price <= 0) {
            log.debug("[FUDKII_DIAG] {} REJECTED: price <= 0 | price={}", scripCode, price);
            return Optional.empty();
        }

        // DIAGNOSTIC: Log all key technical indicators
        log.debug("[FUDKII_DIAG] {} CHECKING | price={} | bbSqueeze={} | bbWidthPct={} | bbPctB={} | " +
                "stBullish={} | stFlip={} | atr={}",
                scripCode, price,
                tech.isBbSqueezing(),
                tech.getBbWidthPct(),
                tech.getBbPercentB(),
                tech.isSuperTrendBullish(),
                tech.isSuperTrendFlip(),
                tech.getAtr());

        // Check for BB squeeze
        if (!tech.isBbSqueezing()) {
            log.debug("[FUDKII_DIAG] {} REJECTED: BB NOT squeezing | bbWidthPct={} | threshold={}",
                    scripCode, tech.getBbWidthPct(), 0.02);
            return Optional.empty();
        }

        log.info("[FUDKII_DIAG] {} SETUP DETECTED: BB squeeze active! | bbWidthPct={}", scripCode, tech.getBbWidthPct());

        // Get ATR for calculations
        double atr = tech.getAtr();
        if (atr <= 0) atr = price * 0.01; // Default 1% if no ATR

        // Calculate proximity threshold: max(0.1%, 10% of ATR)
        double proximityThreshold = Math.max(MIN_PROXIMITY_PCT * price, atr * ATR_PROXIMITY_MULT);

        // Get BB levels
        double bbUpper = tech.getBbUpper();
        double bbLower = tech.getBbLower();
        double stValue = tech.getSuperTrendValue();
        double bbPctB = tech.getBbPercentB();

        // Check for NEAR-BREAKOUT conditions (within proximity threshold)
        boolean nearUpperBB = bbUpper > 0 && (bbUpper - price) <= proximityThreshold && (bbUpper - price) >= 0;
        boolean nearLowerBB = bbLower > 0 && (price - bbLower) <= proximityThreshold && (price - bbLower) >= 0;
        boolean nearSTFlip = stValue > 0 && Math.abs(price - stValue) <= proximityThreshold;

        // Also check if price is just above/below BB (actual breakout)
        boolean aboveUpperBB = bbPctB > 1.0;
        boolean belowLowerBB = bbPctB < 0.0;

        boolean nearBreakout = nearUpperBB || nearLowerBB || nearSTFlip || aboveUpperBB || belowLowerBB;

        // Determine direction based on which side we're near/breaking
        Direction potentialDirection;
        String breakoutType;

        if (aboveUpperBB || nearUpperBB) {
            potentialDirection = Direction.LONG;
            breakoutType = aboveUpperBB ? "ABOVE upper BB" : "NEAR upper BB";
        } else if (belowLowerBB || nearLowerBB) {
            potentialDirection = Direction.SHORT;
            breakoutType = belowLowerBB ? "BELOW lower BB" : "NEAR lower BB";
        } else if (nearSTFlip) {
            // Near SuperTrend - use ST direction
            potentialDirection = tech.isSuperTrendBullish() ? Direction.LONG : Direction.SHORT;
            breakoutType = "NEAR SuperTrend flip";
        } else {
            // Default to SuperTrend direction
            potentialDirection = tech.isSuperTrendBullish() ? Direction.LONG : Direction.SHORT;
            breakoutType = "ST direction";
        }

        // Calculate potential stop and targets
        double stopLevel = tech.getSuperTrendValue();
        double target1 = potentialDirection == Direction.LONG ?
                price + (atr * TARGET1_ATR_MULT) :
                price - (atr * TARGET1_ATR_MULT);
        double target2 = potentialDirection == Direction.LONG ?
                price + (atr * TARGET2_ATR_MULT) :
                price - (atr * TARGET2_ATR_MULT);

        // Get OFI z-score
        double ofiZscore = 0;
        if (score.getHistoricalContext() != null && score.getHistoricalContext().getOfiContext() != null) {
            ofiZscore = score.getHistoricalContext().getOfiContext().getZscore();
        }

        // Log near-breakout detection
        if (nearBreakout) {
            log.info("[FUDKII_NEAR] {} NEAR-BREAKOUT DETECTED | {} | direction={} | price={} | " +
                    "bbUpper={} | bbLower={} | stValue={} | proximityThreshold={} | " +
                    "nearUpperBB={} | nearLowerBB={} | nearSTFlip={} | aboveUpper={} | belowLower={}",
                    scripCode, breakoutType, potentialDirection, price,
                    String.format("%.2f", bbUpper), String.format("%.2f", bbLower),
                    String.format("%.2f", stValue), String.format("%.4f", proximityThreshold),
                    nearUpperBB, nearLowerBB, nearSTFlip, aboveUpperBB, belowLowerBB);
        }

        log.debug("[FUDKII] Setup forming: {} | BB squeezing | {} | ST={} | price={} | ATR={}",
                score.getScripCode(), breakoutType, potentialDirection, String.format("%.2f", price), String.format("%.2f", atr));

        return Optional.of(SetupContext.builder()
                .strategyId(STRATEGY_ID)
                .setupDescription(String.format("BB squeeze + %s", breakoutType))
                .keyLevel(tech.getSuperTrendValue())
                .entryZone(price)
                .proposedStop(stopLevel)
                .proposedTarget1(target1)
                .proposedTarget2(target2)
                .direction(potentialDirection)
                .priceAtDetection(price)
                .superTrendAligned(true)  // By definition, we use ST direction
                .ofiZscoreAtDetection(ofiZscore)
                // NEW: Near-breakout tracking fields
                .nearBreakoutDetected(nearBreakout)
                .bbUpperAtDetection(bbUpper)
                .bbLowerAtDetection(bbLower)
                .stValueAtDetection(stValue)
                .atrAtDetection(atr)
                .candlesSinceSetup(0)
                .pendingConfirmation(nearBreakout && !aboveUpperBB && !belowLowerBB) // Pending if near but not yet broken
                .proximityThreshold(proximityThreshold)
                .build());
    }

    @Override
    public Optional<TradingSignal> checkEntryTrigger(EnrichedQuantScore score, SetupContext setup) {
        if (score == null || setup == null) return Optional.empty();

        String scripCode = score.getScripCode();
        TechnicalContext tech = score.getTechnicalContext();
        if (tech == null) return Optional.empty();

        double price = score.getClose();
        Direction direction = setup.getDirection();

        // FRESHNESS COMPARISON: Show setup values vs current fresh values
        double currentOfi = 0;
        if (score.getHistoricalContext() != null && score.getHistoricalContext().getOfiContext() != null) {
            currentOfi = score.getHistoricalContext().getOfiContext().getZscore();
        }

        log.debug("[FUDKII_FRESH] {} COMPARING | setupPrice={} vs FRESH price={} | " +
                "setupOfi={} vs FRESH ofi={} | FRESH stFlip={} | FRESH bbPctB={}",
                scripCode,
                String.format("%.2f", setup.getPriceAtDetection()),
                String.format("%.2f", price),
                String.format("%.2f", setup.getOfiZscoreAtDetection()),
                String.format("%.2f", currentOfi),
                tech.isSuperTrendFlip(),
                String.format("%.3f", tech.getBbPercentB()));

        // DIAGNOSTIC: Entry into checkEntryTrigger
        log.debug("[FUDKII_DIAG] {} checkEntryTrigger | price={} | direction={} | stFlip={} | stBullish={} | bbPctB={} | nearBreakout={} | pending={}",
                scripCode, price, direction, tech.isSuperTrendFlip(), tech.isSuperTrendBullish(), tech.getBbPercentB(),
                setup.isNearBreakoutDetected(), setup.isPendingConfirmation());

        // Get current BB and ST values
        double bbPctB = tech.getBbPercentB();
        boolean stBullish = tech.isSuperTrendBullish();
        boolean stFlip = tech.isSuperTrendFlip();

        // NEAR-BREAKOUT 2-CANDLE CONFIRMATION LOGIC
        // If setup detected near-breakout, allow confirmation on 2nd candle with relaxed conditions
        boolean nearBreakoutConfirmation = false;
        if (setup.isNearBreakoutDetected()) {
            // Check if price has confirmed breakout (crossed BB or ST aligned)
            boolean bbConfirmed = (direction == Direction.LONG && bbPctB > 0.85) ||    // Close to or above upper BB
                                  (direction == Direction.SHORT && bbPctB < 0.15);     // Close to or below lower BB
            boolean stConfirmed = (direction == Direction.LONG && stBullish) ||
                                  (direction == Direction.SHORT && !stBullish);

            // Check for adverse price movement (invalidation)
            double setupPrice = setup.getPriceAtDetection();
            double adverseMovePct = direction == Direction.LONG ?
                    (setupPrice - price) / setupPrice :  // Price dropped for LONG
                    (price - setupPrice) / setupPrice;   // Price rose for SHORT

            if (adverseMovePct > MAX_ADVERSE_MOVE_PCT) {
                log.info("[FUDKII_NEAR] {} ADVERSE MOVE | direction={} | setupPrice={} | currentPrice={} | adverseMove={}%",
                        scripCode, direction, setupPrice, price, String.format("%.2f", adverseMovePct * 100));
                // Don't return empty - let normal flow handle, but don't use near-breakout confirmation
            } else if (bbConfirmed || stConfirmed) {
                nearBreakoutConfirmation = true;
                log.info("[FUDKII_NEAR] {} 2-CANDLE CONFIRMATION | direction={} | bbPctB={} | stBullish={} | bbConfirmed={} | stConfirmed={}",
                        scripCode, direction, String.format("%.3f", bbPctB), stBullish, bbConfirmed, stConfirmed);
            }
        }

        // 1. Check SuperTrend FLIP OR near-breakout confirmation
        if (!stFlip && !nearBreakoutConfirmation) {
            log.debug("[FUDKII_DIAG] {} BLOCKED: No SuperTrend flip and no near-breakout confirmation | stFlip=false | stBullish={} | nearBreakout={}",
                    scripCode, stBullish, setup.isNearBreakoutDetected());
            return Optional.empty();
        }

        if (stFlip) {
            log.debug("[FUDKII_DIAG] {} PASS: SuperTrend FLIP detected!", scripCode);
        } else {
            log.info("[FUDKII_DIAG] {} PASS: Near-breakout 2-candle confirmation!", scripCode);
        }

        // 2. Verify direction alignment
        if ((direction == Direction.LONG && !stBullish) ||
            (direction == Direction.SHORT && stBullish)) {
            if (stFlip) {
                // ST flipped opposite - adapt direction only if actual flip
                log.debug("[FUDKII_DIAG] {} INFO: ST flipped opposite direction, adapting | setup={} | stBullish={}",
                        scripCode, direction, stBullish);
                direction = stBullish ? Direction.LONG : Direction.SHORT;
            } else if (!nearBreakoutConfirmation) {
                // No flip and no near-breakout confirmation - block
                log.debug("[FUDKII_DIAG] {} BLOCKED: Direction mismatch without confirmation", scripCode);
                return Optional.empty();
            }
            // If nearBreakoutConfirmation, keep original direction from setup
        }

        // 3. Check BB break OR near-breakout confirmation (relaxed for 2-candle)
        boolean bbBreak = (direction == Direction.LONG && bbPctB > 1.0) ||
                         (direction == Direction.SHORT && bbPctB < 0.0);
        boolean bbNearBreak = (direction == Direction.LONG && bbPctB > 0.85) ||
                              (direction == Direction.SHORT && bbPctB < 0.15);

        if (!bbBreak && !nearBreakoutConfirmation) {
            log.debug("[FUDKII_DIAG] {} BLOCKED: No BB break | direction={} | bbPctB={} | need {} for {}",
                    scripCode, direction, bbPctB,
                    direction == Direction.LONG ? ">1.0" : "<0.0", direction);
            return Optional.empty();
        }

        if (bbBreak) {
            log.debug("[FUDKII_DIAG] {} PASS: BB break confirmed | bbPctB={}", scripCode, bbPctB);
        } else if (bbNearBreak && nearBreakoutConfirmation) {
            log.info("[FUDKII_DIAG] {} PASS: BB near-break with 2-candle confirmation | bbPctB={}", scripCode, bbPctB);
        }

        // 4. Check OFI alignment - CHANGED FROM HARD GATE TO SOFT MODIFIER
        // Only reject if OFI is STRONGLY AGAINST the direction (z-score > 1.5 opposite way)
        // Neutral or mildly aligned OFI is acceptable - will affect confidence instead
        double ofiZscore = 0;
        if (score.getHistoricalContext() != null && score.getHistoricalContext().getOfiContext() != null) {
            ofiZscore = score.getHistoricalContext().getOfiContext().getZscore();
        }

        // FIX: Only block if OFI is STRONGLY against us (not just "not aligned")
        boolean ofiStronglyAgainst = (direction == Direction.LONG && ofiZscore < -MIN_OFI_ZSCORE) ||
                                     (direction == Direction.SHORT && ofiZscore > MIN_OFI_ZSCORE);

        if (ofiStronglyAgainst) {
            log.debug("[FUDKII_DIAG] {} BLOCKED: OFI STRONGLY AGAINST direction | ofi={} | direction={}",
                    scripCode, String.format("%.2f", ofiZscore), direction);
            return Optional.empty();
        }

        // Calculate OFI confidence bonus (used later in confidence calculation)
        double ofiConfidenceBonus = 0;
        if ((direction == Direction.LONG && ofiZscore > MIN_OFI_ZSCORE) ||
            (direction == Direction.SHORT && ofiZscore < -MIN_OFI_ZSCORE)) {
            ofiConfidenceBonus = 0.10;  // Strong OFI alignment
            log.debug("[FUDKII_DIAG] {} OFI strongly aligned | ofi={} | bonus=+10%", scripCode, String.format("%.2f", ofiZscore));
        } else if ((direction == Direction.LONG && ofiZscore > 0.5) ||
                   (direction == Direction.SHORT && ofiZscore < -0.5)) {
            ofiConfidenceBonus = 0.05;  // Moderate OFI alignment
        }
        // Neutral OFI (between -0.5 and 0.5) = no bonus, no penalty

        // 5. Check MTF alignment (HTF bullish for LONG, bearish for SHORT)
        if (tech.getMtfBullishPercentage() > 0) {
            if (direction == Direction.LONG && tech.getMtfBullishPercentage() < 0.5) {
                log.trace("[FUDKII] MTF not bullish enough for LONG: {}%", String.format("%.0f", tech.getMtfBullishPercentage() * 100));
                return Optional.empty();
            }
            if (direction == Direction.SHORT && tech.getMtfBullishPercentage() > 0.5) {
                log.trace("[FUDKII] MTF too bullish for SHORT: {}%", String.format("%.0f", tech.getMtfBullishPercentage() * 100));
                return Optional.empty();
            }
        }

        // Recalculate stop and targets based on current ATR
        double atr = tech.getAtr();
        if (atr <= 0) atr = price * 0.01;

        double stopLevel = tech.getSuperTrendValue();

        // CRITICAL FIX #1: Validate stop is on CORRECT SIDE of entry
        // For LONG: stop MUST be below entry, For SHORT: stop MUST be above entry
        boolean stopOnWrongSide = (direction == Direction.LONG && stopLevel >= price) ||
                                  (direction == Direction.SHORT && stopLevel <= price);

        if (stopOnWrongSide) {
            double oldStop = stopLevel;
            // SuperTrend is on wrong side - calculate stop from entry price using ATR
            stopLevel = direction == Direction.LONG ?
                    price - (atr * 0.5) :  // Place stop 0.5 ATR below entry for LONG
                    price + (atr * 0.5);   // Place stop 0.5 ATR above entry for SHORT
            log.warn("[FUDKII_DIAG] {} STOP CORRECTED: SuperTrend on WRONG side | " +
                    "direction={} | oldStop={} | price={} | newStop={} (0.5 ATR from entry)",
                    scripCode, direction, oldStop, price, stopLevel);
        }

        // CRITICAL FIX #2: Ensure minimum stop distance of 0.3% from entry price
        // Prevents extremely tight stops that get stopped out by noise
        double minStopDistance = price * 0.003; // 0.3% minimum distance
        double actualDistance = Math.abs(price - stopLevel);
        if (actualDistance < minStopDistance) {
            double oldStop = stopLevel;
            // Stop is too tight - enforce minimum distance in correct direction
            stopLevel = direction == Direction.LONG ?
                    price - minStopDistance :
                    price + minStopDistance;
            log.warn("[FUDKII_DIAG] {} STOP ADJUSTED: Too tight ({}% < 0.3%) | " +
                    "oldStop={} | newStop={} | enforced 0.3% minimum buffer",
                    scripCode, String.format("%.4f", actualDistance / price * 100), oldStop, stopLevel);
        }

        double target1 = direction == Direction.LONG ?
                price + (atr * TARGET1_ATR_MULT) :
                price - (atr * TARGET1_ATR_MULT);
        double target2 = direction == Direction.LONG ?
                price + (atr * TARGET2_ATR_MULT) :
                price - (atr * TARGET2_ATR_MULT);

        // Calculate R:R
        double risk = Math.abs(price - stopLevel);
        double reward = Math.abs(target1 - price);
        double rr = risk > 0 ? reward / risk : 0;

        if (rr < 1.5) {
            log.trace("[FUDKII] R:R too low: {}", String.format("%.2f", rr));
            return Optional.empty();
        }

        // Calculate confidence
        double confidence = 0.60;  // Base (lowered from 0.65 since OFI is now soft gate)
        confidence += ofiConfidenceBonus;  // OFI bonus calculated earlier (0, 0.05, or 0.10)
        if (tech.isVolatilityExpanding()) confidence += 0.05;  // Vol expanding after squeeze
        if (tech.getMtfBullishPercentage() > 0.7 && direction == Direction.LONG) confidence += 0.05;
        if (tech.getMtfBullishPercentage() < 0.3 && direction == Direction.SHORT) confidence += 0.05;
        if (nearBreakoutConfirmation) confidence += 0.05;  // 2-candle confirmation adds confidence
        confidence = Math.min(0.95, confidence);

        log.info("[FUDKII] ENTRY TRIGGERED | {} {} @ {} | ST flip | BB %B={} | OFI={} | R:R={} | conf={}%",
                score.getScripCode(), direction, String.format("%.2f", price), String.format("%.2f", bbPctB),
                String.format("%.2f", ofiZscore), String.format("%.2f", rr), String.format("%.0f", confidence * 100));

        // Extract microstructure metrics (vpin, kyleLambda)
        Double vpin = null;
        Double kyleLambda = null;
        HistoricalContext histCtx = score.getHistoricalContext();
        if (histCtx != null) {
            if (histCtx.getVpinContext() != null) {
                vpin = histCtx.getVpinContext().getCurrentValue();
            }
            if (histCtx.getLambdaContext() != null) {
                kyleLambda = histCtx.getLambdaContext().getCurrentValue();
            }
        }

        // Get GEX regime
        String gexRegime = score.getGexRegime() != null ? score.getGexRegime().name() : null;

        // Build entry reasons list
        List<String> entryReasons = new ArrayList<>();
        entryReasons.add("BB squeeze detected (low volatility compression)");
        if (stFlip) {
            entryReasons.add(String.format("SuperTrend flipped %s", direction == Direction.LONG ? "bullish" : "bearish"));
        } else if (nearBreakoutConfirmation) {
            entryReasons.add("2-candle near-breakout confirmation");
        }
        if (bbBreak) {
            entryReasons.add(String.format("Price broke %s BB (%%B=%.2f)", direction == Direction.LONG ? "upper" : "lower", bbPctB));
        }
        entryReasons.add(String.format("OFI confirms direction (z-score=%.2f)", ofiZscore));
        if (tech.getMtfBullishPercentage() > 0) {
            entryReasons.add(String.format("MTF alignment: %.0f%% %s",
                    direction == Direction.LONG ? tech.getMtfBullishPercentage() * 100 : (1 - tech.getMtfBullishPercentage()) * 100,
                    direction == Direction.LONG ? "bullish" : "bearish"));
        }
        if (vpin != null && vpin > 0.5) {
            entryReasons.add(String.format("Informed flow active (VPIN=%.2f)", vpin));
        }

        // Build SignalRationale
        SignalRationale rationale = SignalRationale.builder()
                .headline(String.format("FUDKII %s | BB squeeze breakout with ST flip", direction))
                .thesis(String.format("Volatility compression (BB squeeze) resolved with %s breakout, " +
                        "confirmed by SuperTrend flip and OFI z-score of %.2f indicating %s flow pressure.",
                        direction == Direction.LONG ? "upward" : "downward",
                        ofiZscore,
                        direction == Direction.LONG ? "buying" : "selling"))
                .tradeType(SignalRationale.TradeType.BREAKOUT)
                .trigger(SignalRationale.TriggerContext.builder()
                        .type(SignalRationale.TriggerContext.TriggerType.TECHNICAL_SIGNAL)
                        .description("BB squeeze + SuperTrend flip combination")
                        .patternName("FUDKII_BREAKOUT")
                        .triggerEvents(entryReasons)
                        .build())
                .flowContext(SignalRationale.FlowContext.builder()
                        .ofiStatus(String.format("OFI z-score: %.2f (%s)", ofiZscore,
                                ofiZscore > 2 ? "Strong" : ofiZscore > 1.5 ? "Moderate" : "Normal"))
                        .vpinStatus(vpin != null ? String.format("VPIN: %.2f (%s)", vpin,
                                vpin > 0.7 ? "High informed trading" : vpin > 0.5 ? "Moderate" : "Low") : "N/A")
                        .build())
                .technicalContext(SignalRationale.TechnicalContext.builder()
                        .superTrendStatus(String.format("SuperTrend: %s (value=%.2f)",
                                tech.isSuperTrendBullish() ? "Bullish" : "Bearish", tech.getSuperTrendValue()))
                        .bbPosition(String.format("BB %%B: %.2f (%s)", bbPctB,
                                bbPctB > 1 ? "Above upper" : bbPctB < 0 ? "Below lower" : "Inside bands"))
                        .momentumStatus(tech.isVolatilityExpanding() ? "Volatility expanding post-squeeze" : "Volatility stable")
                        .build())
                .edge(SignalRationale.EdgeDefinition.builder()
                        .type(SignalRationale.EdgeDefinition.EdgeType.TECHNICAL_CONFLUENCE)
                        .description("BB squeeze energy release with trend confirmation from SuperTrend flip")
                        .build())
                .build();

        // Build signal
        return Optional.of(TradingSignal.builder()
                .signalId(UUID.randomUUID().toString())
                .familyId(score.getScripCode() != null ? score.getScripCode().substring(0, Math.min(5, score.getScripCode().length())) : "UNK")
                .scripCode(score.getScripCode())
                .companyName(score.getCompanyName())
                .exchange(score.getExchange())
                .direction(direction)
                .horizon(Horizon.SWING)
                .category(SignalCategory.BREAKOUT)
                .setupId(STRATEGY_ID)
                .currentPrice(price)
                .entryPrice(price)
                .stopLoss(stopLevel)
                .target1(target1)
                .target2(target2)
                .confidence(confidence)
                .dataTimestamp(java.time.Instant.ofEpochMilli(score.getPriceTimestamp()))
                .headline(String.format("FUDKII %s | BB squeeze + ST flip | OFI=%.2f | ATR=%.2f | R:R=%.2f",
                        direction, ofiZscore, atr, rr))
                // NEW: Added missing fields
                .vpin(vpin)
                .kyleLambda(kyleLambda)
                .gexRegime(gexRegime)
                .entryReasons(entryReasons)
                .rationale(rationale)
                .build());
    }

    @Override
    public Optional<ExitSignal> checkExitConditions(Position position, EnrichedQuantScore score) {
        if (position == null || score == null) return Optional.empty();

        TechnicalContext tech = score.getTechnicalContext();
        if (tech == null) return Optional.empty();

        double currentPrice = score.getClose();

        // Trail stop with SuperTrend (FUDKII uses dynamic SuperTrend stop)
        double newStop = tech.getSuperTrendValue();
        if (newStop > 0) {
            position.trailStopLoss(newStop);
        }

        // Exit if SuperTrend flips against us
        boolean stBullish = tech.isSuperTrendBullish();
        if (position.getDirection() == Direction.LONG && !stBullish) {
            return Optional.of(ExitSignal.builder()
                    .reason(ExitReason.INVALIDATION)
                    .exitPrice(currentPrice)
                    .description("SuperTrend flipped bearish - momentum lost")
                    .partial(false)
                    .build());
        }
        if (position.getDirection() == Direction.SHORT && stBullish) {
            return Optional.of(ExitSignal.builder()
                    .reason(ExitReason.INVALIDATION)
                    .exitPrice(currentPrice)
                    .description("SuperTrend flipped bullish - momentum lost")
                    .partial(false)
                    .build());
        }

        // Exit if OFI flips strongly against us
        double ofiZscore = 0;
        if (score.getHistoricalContext() != null && score.getHistoricalContext().getOfiContext() != null) {
            ofiZscore = score.getHistoricalContext().getOfiContext().getZscore();
        }

        if (position.getDirection() == Direction.LONG && ofiZscore < -2.0) {
            return Optional.of(ExitSignal.builder()
                    .reason(ExitReason.INVALIDATION)
                    .exitPrice(currentPrice)
                    .description("OFI strongly negative - exit LONG")
                    .partial(false)
                    .build());
        }
        if (position.getDirection() == Direction.SHORT && ofiZscore > 2.0) {
            return Optional.of(ExitSignal.builder()
                    .reason(ExitReason.INVALIDATION)
                    .exitPrice(currentPrice)
                    .description("OFI strongly positive - exit SHORT")
                    .partial(false)
                    .build());
        }

        return Optional.empty();
    }

    @Override
    public Optional<String> checkSetupInvalidation(EnrichedQuantScore score, SetupContext setup) {
        if (score == null || setup == null) return Optional.empty();

        TechnicalContext tech = score.getTechnicalContext();
        if (tech == null) return Optional.empty();

        String scripCode = score.getScripCode();
        Direction direction = setup.getDirection();
        double price = score.getClose();
        double setupPrice = setup.getPriceAtDetection();

        // NEAR-BREAKOUT: Allow longer watching period for 2-candle confirmation
        if (setup.isNearBreakoutDetected()) {
            // Check for adverse price movement
            double adverseMovePct = direction == Direction.LONG ?
                    (setupPrice - price) / setupPrice :  // Price dropped for LONG
                    (price - setupPrice) / setupPrice;   // Price rose for SHORT

            if (adverseMovePct > MAX_ADVERSE_MOVE_PCT) {
                log.info("[FUDKII_INVALIDATE] {} ADVERSE MOVE | direction={} | setupPrice={} | currentPrice={} | adverseMove={}%",
                        scripCode, direction, setupPrice, price, String.format("%.2f", adverseMovePct * 100));
                return Optional.of(String.format("Price moved %.2f%% against direction", adverseMovePct * 100));
            }

            // Check if BB is still tight enough (allow some expansion but not too much)
            double bbWidth = tech.getBbWidthPct();
            if (bbWidth > 0.05) {  // Allow up to 5% BB width for near-breakout setups
                log.info("[FUDKII_INVALIDATE] {} BB EXPANDED TOO MUCH | bbWidth={}% > 5%", scripCode, String.format("%.2f", bbWidth * 100));
                return Optional.of("BB expanded too much without confirmation");
            }

            // Near-breakout setups get relaxed invalidation - don't invalidate on BB squeeze resolve
            // as long as price is still in favorable position
            double bbPctB = tech.getBbPercentB();
            boolean priceStillFavorable = (direction == Direction.LONG && bbPctB > 0.5) ||
                                          (direction == Direction.SHORT && bbPctB < 0.5);

            if (!tech.isBbSqueezing() && !priceStillFavorable) {
                log.info("[FUDKII_INVALIDATE] {} BB squeeze resolved with unfavorable price | direction={} | bbPctB={}",
                        scripCode, direction, bbPctB);
                return Optional.of("BB squeeze resolved with price not in favor");
            }

            // Log that we're keeping near-breakout setup active
            log.debug("[FUDKII_NEAR] {} KEEPING ACTIVE | nearBreakout=true | bbPctB={} | priceStillFavorable={} | adverseMove={}%",
                    scripCode, String.format("%.3f", bbPctB), priceStillFavorable, String.format("%.2f", adverseMovePct * 100));

            return Optional.empty();  // Keep near-breakout setup active
        }

        // STANDARD INVALIDATION LOGIC (for non-near-breakout setups)

        // Invalidate if BB squeeze resolves without SuperTrend flip
        if (!tech.isBbSqueezing() && !tech.isSuperTrendFlip()) {
            return Optional.of("BB squeeze resolved without ST flip");
        }

        // Invalidate if SuperTrend flips in wrong direction
        if (tech.isSuperTrendFlip()) {
            boolean stBullish = tech.isSuperTrendBullish();
            if ((direction == Direction.LONG && !stBullish) ||
                (direction == Direction.SHORT && stBullish)) {
                return Optional.of("SuperTrend flipped wrong direction");
            }
        }

        return Optional.empty();
    }

    /**
     * Helper method to calculate proximity threshold based on ATR and price
     */
    private double calculateProximityThreshold(double price, double atr) {
        return Math.max(MIN_PROXIMITY_PCT * price, atr * ATR_PROXIMITY_MULT);
    }
}
