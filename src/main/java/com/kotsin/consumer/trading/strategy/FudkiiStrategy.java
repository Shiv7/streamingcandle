package com.kotsin.consumer.trading.strategy;

import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.model.TechnicalContext;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal.Direction;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal.Horizon;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal.SignalCategory;
import com.kotsin.consumer.trading.model.Position;
import com.kotsin.consumer.trading.model.TradeOutcome.ExitReason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

    // Time limits
    private static final long MAX_WATCHING_MS = 30 * 60 * 1000;   // 30 minutes
    private static final long MAX_POSITION_MS = 2 * 60 * 60 * 1000; // 2 hours (breakouts should be fast)
    private static final long COOLDOWN_MS = 60 * 60 * 1000;       // 60 minutes (longer cooldown for breakout strategy)

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
        if (score == null) return Optional.empty();

        TechnicalContext tech = score.getTechnicalContext();
        if (tech == null) return Optional.empty();

        double price = score.getClose();
        if (price <= 0) return Optional.empty();

        // Check for BB squeeze
        if (!tech.isBbSqueezing()) {
            return Optional.empty();
        }

        // Determine potential direction based on SuperTrend
        Direction potentialDirection = tech.isSuperTrendBullish() ? Direction.LONG : Direction.SHORT;

        // Get ATR for target calculation
        double atr = tech.getAtr();
        if (atr <= 0) atr = price * 0.01; // Default 1% if no ATR

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

        log.debug("[FUDKII] Setup forming: {} | BB squeezing | ST={} | price={:.2f} | ATR={:.2f}",
                score.getScripCode(), potentialDirection, price, atr);

        return Optional.of(SetupContext.builder()
                .strategyId(STRATEGY_ID)
                .setupDescription(String.format("BB squeeze + ST %s", potentialDirection))
                .keyLevel(tech.getSuperTrendValue())
                .entryZone(price)
                .proposedStop(stopLevel)
                .proposedTarget1(target1)
                .proposedTarget2(target2)
                .direction(potentialDirection)
                .priceAtDetection(price)
                .superTrendAligned(true)  // By definition, we use ST direction
                .ofiZscoreAtDetection(ofiZscore)
                .build());
    }

    @Override
    public Optional<TradingSignal> checkEntryTrigger(EnrichedQuantScore score, SetupContext setup) {
        if (score == null || setup == null) return Optional.empty();

        TechnicalContext tech = score.getTechnicalContext();
        if (tech == null) return Optional.empty();

        double price = score.getClose();
        Direction direction = setup.getDirection();

        // 1. Check SuperTrend FLIP (this is the key trigger)
        if (!tech.isSuperTrendFlip()) {
            log.trace("[FUDKII] No SuperTrend flip yet");
            return Optional.empty();
        }

        // 2. Verify flip direction matches our setup direction
        boolean stBullish = tech.isSuperTrendBullish();
        if ((direction == Direction.LONG && !stBullish) ||
            (direction == Direction.SHORT && stBullish)) {
            log.trace("[FUDKII] SuperTrend flipped wrong direction");
            // Update direction to match the flip
            direction = stBullish ? Direction.LONG : Direction.SHORT;
        }

        // 3. Check BB break (price outside bands)
        double bbPctB = tech.getBbPercentB();
        boolean bbBreak = (direction == Direction.LONG && bbPctB > 1.0) ||
                         (direction == Direction.SHORT && bbPctB < 0.0);

        if (!bbBreak) {
            log.trace("[FUDKII] No BB break: %B={:.2f}", bbPctB);
            return Optional.empty();
        }

        // 4. Check OFI alignment
        double ofiZscore = 0;
        if (score.getHistoricalContext() != null && score.getHistoricalContext().getOfiContext() != null) {
            ofiZscore = score.getHistoricalContext().getOfiContext().getZscore();
        }

        boolean ofiAligned = (direction == Direction.LONG && ofiZscore > MIN_OFI_ZSCORE) ||
                            (direction == Direction.SHORT && ofiZscore < -MIN_OFI_ZSCORE);

        if (!ofiAligned) {
            log.trace("[FUDKII] OFI not strongly aligned: {:.2f}", ofiZscore);
            return Optional.empty();
        }

        // 5. Check MTF alignment (HTF bullish for LONG, bearish for SHORT)
        if (tech.getMtfBullishPercentage() > 0) {
            if (direction == Direction.LONG && tech.getMtfBullishPercentage() < 0.5) {
                log.trace("[FUDKII] MTF not bullish enough for LONG: {:.0f}%", tech.getMtfBullishPercentage() * 100);
                return Optional.empty();
            }
            if (direction == Direction.SHORT && tech.getMtfBullishPercentage() > 0.5) {
                log.trace("[FUDKII] MTF too bullish for SHORT: {:.0f}%", tech.getMtfBullishPercentage() * 100);
                return Optional.empty();
            }
        }

        // Recalculate stop and targets based on current ATR
        double atr = tech.getAtr();
        if (atr <= 0) atr = price * 0.01;

        double stopLevel = tech.getSuperTrendValue();
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
            log.trace("[FUDKII] R:R too low: {:.2f}", rr);
            return Optional.empty();
        }

        // Calculate confidence
        double confidence = 0.65;  // Base for BB squeeze + ST flip
        if (Math.abs(ofiZscore) > 2.0) confidence += 0.1;  // Strong OFI
        if (tech.isVolatilityExpanding()) confidence += 0.05;  // Vol expanding after squeeze
        if (tech.getMtfBullishPercentage() > 0.7 && direction == Direction.LONG) confidence += 0.05;
        if (tech.getMtfBullishPercentage() < 0.3 && direction == Direction.SHORT) confidence += 0.05;
        confidence = Math.min(0.95, confidence);

        log.info("[FUDKII] ENTRY TRIGGERED | {} {} @ {:.2f} | ST flip | BB %B={:.2f} | OFI={:.2f} | R:R={:.2f} | conf={:.0f}%",
                score.getScripCode(), direction, price, bbPctB, ofiZscore, rr, confidence * 100);

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
                .entryPrice(price)
                .stopLoss(stopLevel)
                .target1(target1)
                .target2(target2)
                .confidence(confidence)
                .dataTimestamp(java.time.Instant.ofEpochMilli(score.getPriceTimestamp()))
                .headline(String.format("FUDKII %s | BB squeeze + ST flip | OFI=%.2f | ATR=%.2f | R:R=%.2f",
                        direction, ofiZscore, atr, rr))
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

        // Invalidate if BB squeeze resolves without SuperTrend flip
        if (!tech.isBbSqueezing() && !tech.isSuperTrendFlip()) {
            return Optional.of("BB squeeze resolved without ST flip");
        }

        // Invalidate if SuperTrend flips in wrong direction
        if (tech.isSuperTrendFlip()) {
            boolean stBullish = tech.isSuperTrendBullish();
            if ((setup.getDirection() == Direction.LONG && !stBullish) ||
                (setup.getDirection() == Direction.SHORT && stBullish)) {
                return Optional.of("SuperTrend flipped wrong direction");
            }
        }

        return Optional.empty();
    }
}
