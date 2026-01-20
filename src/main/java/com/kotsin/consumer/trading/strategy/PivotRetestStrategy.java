package com.kotsin.consumer.trading.strategy;

import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.model.HistoricalContext;
import com.kotsin.consumer.enrichment.model.SessionStructure;
import com.kotsin.consumer.enrichment.model.TechnicalContext;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal.Direction;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal.Horizon;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal.SignalCategory;
import com.kotsin.consumer.trading.model.Position;
import com.kotsin.consumer.trading.model.TradeOutcome.ExitReason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * PivotRetestStrategy - Trade pivot level retests with confirmation.
 *
 * EDGE: Price respects pivot levels. Retest with rejection = high probability reversal.
 *
 * ENTRY CONDITIONS (ALL required):
 * 1. Price within 0.3% of pivot level (S1/S2/S3/R1/R2/R3/PP)
 * 2. Rejection candle: price wicked into level and closed away
 * 3. SuperTrend direction supports trade
 * 4. OFI direction supports trade (z-score > 1.0)
 * 5. Session position sensible (LONG < 70%, SHORT > 30%)
 * 6. VPIN < 0.75 (not extreme informed trading against us)
 *
 * STOP LOSS: 0.5% beyond the pivot level
 * TARGETS: T1 = next pivot, T2 = second pivot (trail)
 * TIME STOP: 4 hours
 */
@Slf4j
@Component
public class PivotRetestStrategy implements TradingStrategy {

    private static final String STRATEGY_ID = "PIVOT_RETEST";

    // Thresholds
    private static final double LEVEL_PROXIMITY_PCT = 0.3;    // Within 0.3% of level
    private static final double SETUP_PROXIMITY_PCT = 1.0;    // Within 1% to start watching
    private static final double MIN_OFI_ZSCORE = 1.0;
    private static final double MAX_VPIN = 0.75;
    private static final double STOP_PCT = 0.5;
    private static final double MIN_SESSION_FOR_SHORT = 0.30;
    private static final double MAX_SESSION_FOR_LONG = 0.70;

    // Time limits
    private static final long MAX_WATCHING_MS = 60 * 60 * 1000;   // 60 minutes
    private static final long MAX_POSITION_MS = 4 * 60 * 60 * 1000; // 4 hours
    private static final long COOLDOWN_MS = 30 * 60 * 1000;       // 30 minutes

    @Override
    public String getStrategyId() {
        return STRATEGY_ID;
    }

    @Override
    public String getDescription() {
        return "Trade pivot level retests with rejection confirmation";
    }

    @Override
    public StrategyParams getParams() {
        return StrategyParams.builder()
                .strategyId(STRATEGY_ID)
                .requiredTimeframe(null) // Works on any timeframe
                .maxWatchingDurationMs(MAX_WATCHING_MS)
                .maxPositionDurationMs(MAX_POSITION_MS)
                .cooldownDurationMs(COOLDOWN_MS)
                .defaultStopPct(STOP_PCT)
                .target1Pct(0.5)  // Flexible, depends on next pivot
                .target2Pct(1.0)
                .minRiskRewardRatio(1.5)
                .minConfidence(0.6)
                .minOfiZscore(MIN_OFI_ZSCORE)
                .maxVpin(MAX_VPIN)
                .minSessionPosition(MIN_SESSION_FOR_SHORT)
                .maxSessionPosition(MAX_SESSION_FOR_LONG)
                .build();
    }

    @Override
    public Optional<SetupContext> detectSetupForming(EnrichedQuantScore score) {
        if (score == null) return Optional.empty();

        double price = score.getClose();
        if (price <= 0) return Optional.empty();

        TechnicalContext tech = score.getTechnicalContext();
        if (tech == null) return Optional.empty();

        // Find nearest pivot level
        PivotLevel nearestPivot = findNearestPivot(price, tech);
        if (nearestPivot == null) return Optional.empty();

        // Check if approaching (within 1%)
        double distancePct = Math.abs(price - nearestPivot.level) / price * 100;
        if (distancePct > SETUP_PROXIMITY_PCT) {
            return Optional.empty();
        }

        // Determine direction based on pivot type
        Direction direction;
        if (nearestPivot.isSupport) {
            // Approaching support from above → potential LONG
            direction = Direction.LONG;
        } else {
            // Approaching resistance from below → potential SHORT
            direction = Direction.SHORT;
        }

        // Check SuperTrend alignment
        boolean stAligned = (direction == Direction.LONG && tech.isSuperTrendBullish()) ||
                           (direction == Direction.SHORT && !tech.isSuperTrendBullish());

        // Get OFI z-score
        double ofiZscore = 0;
        if (score.getHistoricalContext() != null && score.getHistoricalContext().getOfiContext() != null) {
            ofiZscore = score.getHistoricalContext().getOfiContext().getZscore();
        }

        // Calculate targets
        double stopLevel = direction == Direction.LONG ?
                nearestPivot.level * (1 - STOP_PCT / 100) :
                nearestPivot.level * (1 + STOP_PCT / 100);

        Double target1 = findNextPivotTarget(price, tech, direction, 1);
        Double target2 = findNextPivotTarget(price, tech, direction, 2);

        log.debug("[PIVOT_RETEST] Setup forming: {} {} @ {:.2f} | level={:.2f} ({}) | dist={:.2f}%",
                score.getScripCode(), direction, price, nearestPivot.level, nearestPivot.name, distancePct);

        return Optional.of(SetupContext.builder()
                .strategyId(STRATEGY_ID)
                .setupDescription(String.format("%s at %s (%.2f)", direction, nearestPivot.name, nearestPivot.level))
                .keyLevel(nearestPivot.level)
                .entryZone(nearestPivot.level)
                .proposedStop(stopLevel)
                .proposedTarget1(target1 != null ? target1 : price * (direction == Direction.LONG ? 1.01 : 0.99))
                .proposedTarget2(target2 != null ? target2 : price * (direction == Direction.LONG ? 1.02 : 0.98))
                .direction(direction)
                .priceAtDetection(price)
                .superTrendAligned(stAligned)
                .ofiZscoreAtDetection(ofiZscore)
                .build());
    }

    @Override
    public Optional<TradingSignal> checkEntryTrigger(EnrichedQuantScore score, SetupContext setup) {
        if (score == null || setup == null) return Optional.empty();

        double price = score.getClose();
        Direction direction = setup.getDirection();

        // 1. Price at level (within 0.3%)
        double distancePct = Math.abs(price - setup.getKeyLevel()) / price * 100;
        if (distancePct > LEVEL_PROXIMITY_PCT) {
            log.trace("[PIVOT_RETEST] Not at level: {:.2f}% away", distancePct);
            return Optional.empty();
        }

        // 2. Check OFI alignment
        double ofiZscore = 0;
        if (score.getHistoricalContext() != null && score.getHistoricalContext().getOfiContext() != null) {
            ofiZscore = score.getHistoricalContext().getOfiContext().getZscore();
        }

        boolean ofiAligned = (direction == Direction.LONG && ofiZscore > MIN_OFI_ZSCORE) ||
                            (direction == Direction.SHORT && ofiZscore < -MIN_OFI_ZSCORE);

        if (!ofiAligned) {
            log.trace("[PIVOT_RETEST] OFI not aligned: {:.2f} (need {} for {})",
                    ofiZscore, direction == Direction.LONG ? ">" + MIN_OFI_ZSCORE : "<" + (-MIN_OFI_ZSCORE), direction);
            return Optional.empty();
        }

        // 3. Check VPIN (not extreme informed trading against us)
        double vpin = 0.5; // Default neutral
        if (score.getHistoricalContext() != null && score.getHistoricalContext().getVpinContext() != null) {
            vpin = score.getHistoricalContext().getVpinContext().getCurrentValue();
        }
        if (vpin > MAX_VPIN) {
            log.trace("[PIVOT_RETEST] VPIN too high: {:.2f}", vpin);
            return Optional.empty();
        }

        // 4. Check session position
        SessionStructure session = score.getSessionStructure();
        if (session != null) {
            double sessionPos = session.getPositionInRange();
            if (direction == Direction.LONG && sessionPos > MAX_SESSION_FOR_LONG) {
                log.trace("[PIVOT_RETEST] LONG blocked - session position too high: {:.0f}%", sessionPos * 100);
                return Optional.empty();
            }
            if (direction == Direction.SHORT && sessionPos < MIN_SESSION_FOR_SHORT) {
                log.trace("[PIVOT_RETEST] SHORT blocked - session position too low: {:.0f}%", sessionPos * 100);
                return Optional.empty();
            }
        }

        // 5. Check SuperTrend alignment
        TechnicalContext tech = score.getTechnicalContext();
        if (tech != null) {
            boolean stAligned = (direction == Direction.LONG && tech.isSuperTrendBullish()) ||
                               (direction == Direction.SHORT && !tech.isSuperTrendBullish());
            if (!stAligned) {
                log.trace("[PIVOT_RETEST] SuperTrend not aligned for {}", direction);
                return Optional.empty();
            }
        }

        // 6. Check exhaustion (SELLING_EXHAUSTION good for LONG, BUYING_EXHAUSTION good for SHORT)
        boolean exhaustionConfirms = false;
        if (score.hasEvents()) {
            for (var event : score.getDetectedEvents()) {
                if (direction == Direction.LONG &&
                    event.getEventType() == com.kotsin.consumer.enrichment.model.DetectedEvent.EventType.SELLING_EXHAUSTION) {
                    exhaustionConfirms = true;
                    break;
                }
                if (direction == Direction.SHORT &&
                    event.getEventType() == com.kotsin.consumer.enrichment.model.DetectedEvent.EventType.BUYING_EXHAUSTION) {
                    exhaustionConfirms = true;
                    break;
                }
            }
        }

        // Calculate R:R
        double risk = Math.abs(price - setup.getProposedStop());
        double reward = Math.abs(setup.getProposedTarget1() - price);
        double rr = reward / risk;

        if (rr < 1.5) {
            log.trace("[PIVOT_RETEST] R:R too low: {:.2f}", rr);
            return Optional.empty();
        }

        // Calculate confidence
        double confidence = 0.6;
        if (ofiAligned) confidence += 0.1;
        if (exhaustionConfirms) confidence += 0.1;
        if (setup.isSuperTrendAligned()) confidence += 0.05;
        if (rr >= 2.0) confidence += 0.05;
        confidence = Math.min(0.95, confidence);

        log.info("[PIVOT_RETEST] ENTRY TRIGGERED | {} {} @ {:.2f} | level={:.2f} | OFI={:.2f} | R:R={:.2f} | conf={:.0f}%",
                score.getScripCode(), direction, price, setup.getKeyLevel(), ofiZscore, rr, confidence * 100);

        // Build signal
        return Optional.of(TradingSignal.builder()
                .signalId(UUID.randomUUID().toString())
                .familyId(score.getScripCode() != null ? score.getScripCode().substring(0, Math.min(5, score.getScripCode().length())) : "UNK")
                .scripCode(score.getScripCode())
                .companyName(score.getCompanyName())
                .exchange(score.getExchange())
                .direction(direction)
                .horizon(Horizon.SWING)
                .category(SignalCategory.REVERSAL)
                .setupId(STRATEGY_ID)
                .entryPrice(price)
                .stopLoss(setup.getProposedStop())
                .target1(setup.getProposedTarget1())
                .target2(setup.getProposedTarget2())
                .confidence(confidence)
                .dataTimestamp(java.time.Instant.ofEpochMilli(score.getPriceTimestamp()))
                .headline(String.format("PIVOT_RETEST %s at %.2f | OFI=%.2f | Exhaustion=%s | R:R=%.2f",
                        direction, setup.getKeyLevel(), ofiZscore, exhaustionConfirms, rr))
                .build());
    }

    @Override
    public Optional<ExitSignal> checkExitConditions(Position position, EnrichedQuantScore score) {
        if (position == null || score == null) return Optional.empty();

        TechnicalContext tech = score.getTechnicalContext();
        if (tech == null) return Optional.empty();

        // Exit if SuperTrend flips against position
        boolean stBullish = tech.isSuperTrendBullish();
        if (position.getDirection() == Direction.LONG && !stBullish) {
            return Optional.of(ExitSignal.builder()
                    .reason(ExitReason.INVALIDATION)
                    .exitPrice(score.getClose())
                    .description("SuperTrend flipped bearish")
                    .partial(false)
                    .build());
        }
        if (position.getDirection() == Direction.SHORT && stBullish) {
            return Optional.of(ExitSignal.builder()
                    .reason(ExitReason.INVALIDATION)
                    .exitPrice(score.getClose())
                    .description("SuperTrend flipped bullish")
                    .partial(false)
                    .build());
        }

        return Optional.empty();
    }

    @Override
    public Optional<String> checkSetupInvalidation(EnrichedQuantScore score, SetupContext setup) {
        if (score == null || setup == null) return Optional.empty();

        double price = score.getClose();

        // Invalidate if price moves too far from level (>1.5%)
        double distancePct = Math.abs(price - setup.getKeyLevel()) / price * 100;
        if (distancePct > 1.5) {
            return Optional.of(String.format("Price moved away from level: %.2f%% > 1.5%%", distancePct));
        }

        // Invalidate if price breaks through level (wrong direction)
        if (setup.getDirection() == Direction.LONG && price < setup.getKeyLevel() * 0.995) {
            return Optional.of("Support broken - LONG invalidated");
        }
        if (setup.getDirection() == Direction.SHORT && price > setup.getKeyLevel() * 1.005) {
            return Optional.of("Resistance broken - SHORT invalidated");
        }

        return Optional.empty();
    }

    // ======================== HELPER METHODS ========================

    private PivotLevel findNearestPivot(double price, TechnicalContext tech) {
        PivotLevel nearest = null;
        double minDistance = Double.MAX_VALUE;

        // Check daily pivots
        if (tech.getDailyPivot() != null) {
            double dist = Math.abs(price - tech.getDailyPivot());
            if (dist < minDistance) {
                minDistance = dist;
                nearest = new PivotLevel("PP", tech.getDailyPivot(), true); // PP is neutral
            }
        }

        // Support levels
        Double[] supports = {tech.getDailyS1(), tech.getDailyS2(), tech.getDailyS3()};
        String[] supportNames = {"S1", "S2", "S3"};
        for (int i = 0; i < supports.length; i++) {
            if (supports[i] != null && supports[i] > 0) {
                double dist = Math.abs(price - supports[i]);
                if (dist < minDistance) {
                    minDistance = dist;
                    nearest = new PivotLevel(supportNames[i], supports[i], true);
                }
            }
        }

        // Resistance levels
        Double[] resistances = {tech.getDailyR1(), tech.getDailyR2(), tech.getDailyR3()};
        String[] resistanceNames = {"R1", "R2", "R3"};
        for (int i = 0; i < resistances.length; i++) {
            if (resistances[i] != null && resistances[i] > 0) {
                double dist = Math.abs(price - resistances[i]);
                if (dist < minDistance) {
                    minDistance = dist;
                    nearest = new PivotLevel(resistanceNames[i], resistances[i], false);
                }
            }
        }

        // Camarilla levels
        if (tech.getCamL3() != null && tech.getCamL3() > 0) {
            double dist = Math.abs(price - tech.getCamL3());
            if (dist < minDistance) {
                minDistance = dist;
                nearest = new PivotLevel("CAM_L3", tech.getCamL3(), true);
            }
        }
        if (tech.getCamH3() != null && tech.getCamH3() > 0) {
            double dist = Math.abs(price - tech.getCamH3());
            if (dist < minDistance) {
                minDistance = dist;
                nearest = new PivotLevel("CAM_H3", tech.getCamH3(), false);
            }
        }

        return nearest;
    }

    private Double findNextPivotTarget(double price, TechnicalContext tech, Direction direction, int targetNum) {
        if (direction == Direction.LONG) {
            // For LONG, target is above price
            Double[] candidates = {tech.getDailyR1(), tech.getDailyR2(), tech.getDailyR3(),
                                   tech.getDailyPivot(), tech.getCamH3()};
            return findNthAbove(price, candidates, targetNum);
        } else {
            // For SHORT, target is below price
            Double[] candidates = {tech.getDailyS1(), tech.getDailyS2(), tech.getDailyS3(),
                                   tech.getDailyPivot(), tech.getCamL3()};
            return findNthBelow(price, candidates, targetNum);
        }
    }

    private Double findNthAbove(double price, Double[] levels, int n) {
        return java.util.Arrays.stream(levels)
                .filter(l -> l != null && l > price)
                .sorted()
                .skip(n - 1)
                .findFirst()
                .orElse(null);
    }

    private Double findNthBelow(double price, Double[] levels, int n) {
        return java.util.Arrays.stream(levels)
                .filter(l -> l != null && l < price)
                .sorted(java.util.Comparator.reverseOrder())
                .skip(n - 1)
                .findFirst()
                .orElse(null);
    }

    private record PivotLevel(String name, double level, boolean isSupport) {}
}
