package com.kotsin.consumer.trading.strategy;

import com.kotsin.consumer.curated.model.MultiTimeframeLevels;
import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.model.GEXProfile;
import com.kotsin.consumer.enrichment.model.HistoricalContext;
import com.kotsin.consumer.enrichment.model.TechnicalContext;
import com.kotsin.consumer.enrichment.model.TimeContext;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal.Direction;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal.Horizon;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal.SignalCategory;
import com.kotsin.consumer.signal.service.DivergenceStrengthCalculator;
import com.kotsin.consumer.signal.service.DivergenceStrengthCalculator.DivergenceStrength;
import com.kotsin.consumer.signal.service.MicrostructureLeadingEdgeCalculator;
import com.kotsin.consumer.signal.service.MicrostructureLeadingEdgeCalculator.LeadingEdgeResult;
import com.kotsin.consumer.trading.model.Position;
import com.kotsin.consumer.trading.model.TradeOutcome.ExitReason;
import com.kotsin.consumer.trading.mtf.MtfSmcContext;
import com.kotsin.consumer.trading.smc.SmcContext.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

/**
 * InstitutionalPivotStrategy - ENHANCED Multi-Timeframe Institutional Trading
 *
 * ============================================================================
 * ENHANCEMENTS OVER BASE VERSION:
 * ============================================================================
 *
 * 1. SESSION TIME FILTER
 *    - Avoid opening noise (9:15-9:20)
 *    - Avoid lunch hour (12:00-13:30)
 *    - Avoid closing chaos (15:00-15:30)
 *
 * 2. MICROSTRUCTURE LEADING EDGE
 *    - Check if LTF microstructure > MTF > HTF (institutions entering)
 *    - Boost confidence for LEADING pattern
 *    - Warn on REVERSAL_RISK pattern
 *
 * 3. FAMILY ALIGNMENT
 *    - Check if equity + futures + options are aligned
 *    - Boost confidence for full alignment
 *    - Reduce confidence for divergence
 *
 * 4. DIVERGENCE STRENGTH (NOT BINARY)
 *    - Score divergence 0-100 based on duration, magnitude, velocity
 *    - Strong divergence (60+) = higher confidence
 *    - Weak divergence = lower confidence
 *
 * 5. GEX REGIME ADAPTATION
 *    - TRENDING: Wider stops, bigger targets
 *    - MEAN_REVERTING: Tighter stops, quick targets
 *
 * 6. ATR-BASED STOP VALIDATION
 *    - Ensure stop is within 1-3 ATR
 *    - Adjust if outside bounds
 *
 * 7. MULTI-TARGET SYSTEM
 *    - T1: 1:1 R:R (book 50%)
 *    - T2: 2:1 R:R (book 30%)
 *    - T3: 3:1 R:R (book 20%)
 *
 * ALL CHECKS ARE NULL-SAFE - flow continues even if data unavailable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstitutionalPivotStrategy implements TradingStrategy {

    private static final String STRATEGY_ID = "INST_PIVOT";

    // Inject calculators (optional - null-safe if not available)
    private final Optional<DivergenceStrengthCalculator> divergenceCalculator;
    private final Optional<MicrostructureLeadingEdgeCalculator> leadingEdgeCalculator;

    // ============ THRESHOLDS ============

    // Confluence requirements
    private static final int MIN_MTF_CONFLUENCE = 5;        // Out of 10

    // Level proximity (% of price)
    private static final double LEVEL_PROXIMITY_PCT = 0.5;  // Within 0.5% of pivot

    // Risk management
    private static final double MIN_RR_RATIO = 2.0;
    private static final double MIN_STOP_PCT = 0.3;         // Minimum 0.3% stop
    private static final double MAX_STOP_PCT = 1.5;         // Maximum 1.5% stop

    // ATR-based bounds
    private static final double MIN_STOP_ATR = 1.0;         // Minimum 1 ATR
    private static final double MAX_STOP_ATR = 3.0;         // Maximum 3 ATR

    // Time limits
    private static final long MAX_WATCHING_MS = 2 * 60 * 60 * 1000;   // 2 hours
    private static final long MAX_POSITION_MS = 4 * 60 * 60 * 1000;   // 4 hours
    private static final long COOLDOWN_MS = 30 * 60 * 1000;           // 30 minutes

    // OFI thresholds for microstructure confirmation
    private static final double MIN_OFI_ZSCORE = 0.5;       // Minimum OFI zscore for entry

    @Override
    public String getStrategyId() {
        return STRATEGY_ID;
    }

    @Override
    public String getDescription() {
        return "Institutional Pivot Strategy - Enhanced MTF SMC + Pivot trading with session filters, " +
                "microstructure confirmation, family alignment, and divergence scoring";
    }

    @Override
    public StrategyParams getParams() {
        return StrategyParams.builder()
                .strategyId(STRATEGY_ID)
                .requiredTimeframe(null)
                .maxWatchingDurationMs(MAX_WATCHING_MS)
                .maxPositionDurationMs(MAX_POSITION_MS)
                .cooldownDurationMs(COOLDOWN_MS)
                .defaultStopPct(0.5)
                .target1Pct(1.0)
                .target2Pct(2.0)
                .minRiskRewardRatio(MIN_RR_RATIO)
                .minConfidence(0.7)
                .minOfiZscore(0.3)
                .maxVpin(0.85)
                .minSessionPosition(0.1)
                .maxSessionPosition(0.9)
                .build();
    }

    // ============================================================================
    // SETUP DETECTION (IDLE → WATCHING)
    // ============================================================================

    @Override
    public Optional<SetupContext> detectSetupForming(EnrichedQuantScore score) {
        if (score == null) return Optional.empty();

        String scripCode = score.getScripCode() != null ? score.getScripCode() : "UNKNOWN";
        double price = score.getClose();
        TechnicalContext tech = score.getTechnicalContext();
        MultiTimeframeLevels mtf = score.getMtfLevels();
        MtfSmcContext smc = score.getMtfSmcContext();

        // ========== ENHANCEMENT 1: SESSION TIME FILTER ==========
        // Skip if not good trading time (null-safe - proceeds if no time context)
        if (!isGoodTradingTime(score)) {
            log.debug("[INST_PIVOT] {} Skipping - not good trading time", scripCode);
            return Optional.empty();
        }

        // ========== CHECK 1: MTF SMC CONTEXT AVAILABLE ==========
        // Graceful: If no SMC context, skip setup detection but don't fail
        if (smc == null || smc.getHtfBias() == null) {
            log.debug("[INST_PIVOT] {} No MTF SMC context available yet", scripCode);
            return Optional.empty();
        }

        // ========== CHECK 2: HTF BIAS CLEAR ==========
        MarketBias htfBias = smc.getHtfBias();
        if (htfBias == MarketBias.UNKNOWN || htfBias == MarketBias.RANGING) {
            log.debug("[INST_PIVOT] {} HTF bias not clear: {}", scripCode, htfBias);
            return Optional.empty();
        }

        // ========== CHECK 3: IN CORRECT ZONE ==========
        boolean inDiscount = smc.isInDiscount();
        boolean inPremium = smc.isInPremium();

        // LONG needs discount, SHORT needs premium
        if (htfBias == MarketBias.BULLISH && !inDiscount) {
            log.debug("[INST_PIVOT] {} Bullish but not in discount zone", scripCode);
            return Optional.empty();
        }
        if (htfBias == MarketBias.BEARISH && !inPremium) {
            log.debug("[INST_PIVOT] {} Bearish but not in premium zone", scripCode);
            return Optional.empty();
        }

        // ========== CHECK 4: AT HTF POI OR KEY PIVOT ==========
        boolean atHtfPoi = smc.isAtHtfDemandZone() || smc.isAtHtfSupplyZone() || smc.isAtHtfFvg();
        boolean atKeyPivot = isAtKeyPivot(price, mtf);

        if (!atHtfPoi && !atKeyPivot) {
            log.debug("[INST_PIVOT] {} Not at HTF POI or key pivot", scripCode);
            return Optional.empty();
        }

        // ========== CHECK 5: MTF CONFLUENCE SCORE ==========
        int confluence = smc.getMtfConfluenceScore();

        // Add pivot confluence
        if (atKeyPivot) confluence += 2;
        if (isAtWeeklyPivot(price, mtf)) confluence += 1;

        // ========== ENHANCEMENT 3: FAMILY ALIGNMENT BONUS ==========
        // Null-safe: If no family context, continue without bonus
        int familyBonus = calculateFamilyAlignmentBonus(score, htfBias);
        confluence += familyBonus;

        // ========== ENHANCEMENT 4: DIVERGENCE STRENGTH BONUS ==========
        // Null-safe: If can't calculate divergence, continue without bonus
        DivergenceStrength divStrength = calculateDivergenceStrength(score);
        if (divStrength.isStrong()) {
            confluence += 2; // Strong divergence adds confluence
        } else if (divStrength.isWeak()) {
            confluence -= 1; // Weak divergence reduces confluence
        }

        if (confluence < MIN_MTF_CONFLUENCE) {
            log.debug("[INST_PIVOT] {} MTF confluence too low: {} < {}",
                    scripCode, confluence, MIN_MTF_CONFLUENCE);
            return Optional.empty();
        }

        // ========== DETERMINE DIRECTION ==========
        Direction direction = htfBias == MarketBias.BULLISH ? Direction.LONG : Direction.SHORT;

        // ========== ENHANCEMENT 5: GEX REGIME ADAPTATION ==========
        // Null-safe: If no GEX, use default multipliers
        double stopMultiplier = getGexStopMultiplier(score);
        double targetMultiplier = getGexTargetMultiplier(score);

        // ========== CALCULATE STOP AND TARGETS ==========
        double stopLoss = calculateStopLoss(price, direction, smc, tech, stopMultiplier);
        double target1 = calculateTarget1(price, direction, smc, mtf, targetMultiplier);
        double target2 = calculateTarget2(price, direction, smc, mtf, target1, targetMultiplier);
        double target3 = calculateTarget3(price, direction, target1, target2);

        // ========== ENHANCEMENT 6: ATR-BASED STOP VALIDATION ==========
        // Null-safe: If no ATR, use percentage-based validation
        stopLoss = validateStopWithAtr(price, stopLoss, direction, tech);

        // Validate stop bounds (percentage-based fallback)
        double stopPct = Math.abs(price - stopLoss) / price * 100;
        if (stopPct < MIN_STOP_PCT) {
            stopLoss = direction == Direction.LONG ?
                    price * (1 - MIN_STOP_PCT / 100) :
                    price * (1 + MIN_STOP_PCT / 100);
        }
        if (stopPct > MAX_STOP_PCT) {
            log.debug("[INST_PIVOT] {} Stop too wide: {}%", scripCode, stopPct);
            return Optional.empty();
        }

        // Validate R:R
        double risk = Math.abs(price - stopLoss);
        double reward = Math.abs(target1 - price);
        double rr = risk > 0 ? reward / risk : 0;

        if (rr < MIN_RR_RATIO) {
            log.debug("[INST_PIVOT] {} R:R too low: {} < {}", scripCode, rr, MIN_RR_RATIO);
            return Optional.empty();
        }

        // ========== SETUP DETECTED ==========
        String poiDesc = atHtfPoi ?
                (smc.isAtHtfDemandZone() ? "HTF_DEMAND" : "HTF_SUPPLY") :
                "PIVOT";

        log.info("[INST_PIVOT] SETUP | {} {} @ {} | HTF={} | zone={} | POI={} | conf={} | R:R={} | famBonus={} | divStr={}",
                scripCode, direction, String.format("%.2f", price),
                htfBias,
                inDiscount ? "DISCOUNT" : "PREMIUM",
                poiDesc,
                confluence,
                String.format("%.1f", rr),
                familyBonus,
                divStrength.getStrengthLabel());

        return Optional.of(SetupContext.builder()
                .strategyId(STRATEGY_ID)
                .familyId(scripCode)
                .watchingStartTime(System.currentTimeMillis())
                .setupDescription(String.format("%s at %s | HTF=%s | Conf=%d | R:R=%.1f | Fam=%d | Div=%s",
                        direction, poiDesc, htfBias, confluence, rr, familyBonus, divStrength.getStrengthLabel()))
                .keyLevel(smc.getHtfNearestDemand() != null ?
                        smc.getHtfNearestDemand().getMidpoint() :
                        (smc.getHtfNearestSupply() != null ?
                                smc.getHtfNearestSupply().getMidpoint() : price))
                .entryZone(price)
                .proposedStop(stopLoss)
                .proposedTarget1(target1)
                .proposedTarget2(target2)
                .direction(direction)
                .priceAtDetection(price)
                .superTrendAligned(tech != null &&
                        ((direction == Direction.LONG && tech.isSuperTrendBullish()) ||
                                (direction == Direction.SHORT && !tech.isSuperTrendBullish())))
                .confluenceScore(confluence)
                .build());
    }

    // ============================================================================
    // ENTRY TRIGGER (WATCHING → READY)
    // ============================================================================

    @Override
    public Optional<TradingSignal> checkEntryTrigger(EnrichedQuantScore score, SetupContext setup) {
        if (score == null || setup == null) return Optional.empty();

        String scripCode = score.getScripCode() != null ? score.getScripCode() : "UNKNOWN";
        double price = score.getClose();
        Direction direction = setup.getDirection();
        TechnicalContext tech = score.getTechnicalContext();
        MtfSmcContext smc = score.getMtfSmcContext();

        // Graceful: If no SMC context, skip entry check
        if (smc == null) {
            log.debug("[INST_PIVOT] {} No SMC context for entry check", scripCode);
            return Optional.empty();
        }

        // ========== ENHANCEMENT 1: SESSION TIME FILTER ==========
        if (!isGoodTradingTime(score)) {
            log.debug("[INST_PIVOT] {} Skipping entry - not good trading time", scripCode);
            return Optional.empty();
        }

        // ========== THE KEY ENTRY SIGNAL: LTF LIQUIDITY SWEEP ==========
        if (!smc.isLtfLiquidityJustSwept()) {
            log.debug("[INST_PIVOT] {} Waiting for LTF liquidity sweep", scripCode);
            return Optional.empty();
        }

        LiquiditySweep sweep = smc.getLtfLastSweep();
        if (sweep == null) return Optional.empty();

        // Validate sweep direction
        boolean correctSweep = (direction == Direction.LONG && !sweep.isBuySide()) ||
                (direction == Direction.SHORT && sweep.isBuySide());

        if (!correctSweep) {
            log.debug("[INST_PIVOT] {} Sweep direction mismatch | dir={} | sweepBuySide={}",
                    scripCode, direction, sweep.isBuySide());
            return Optional.empty();
        }

        // Validate sweep showed reversal
        if (!sweep.isValidReversal()) {
            log.debug("[INST_PIVOT] {} Sweep didn't show valid reversal", scripCode);
            return Optional.empty();
        }

        // ========== ENHANCEMENT 2: MICROSTRUCTURE LEADING EDGE ==========
        // Null-safe: If can't calculate, continue without this confirmation
        LeadingEdgeResult leadingEdge = calculateLeadingEdge(score);
        double leadingEdgeModifier = 1.0;

        if (leadingEdge.isDetected()) {
            leadingEdgeModifier = leadingEdge.getConfidenceModifier();

            // If reversal risk detected, require stronger confirmation
            if (leadingEdge.isCautionPattern()) {
                log.debug("[INST_PIVOT] {} REVERSAL_RISK pattern - extra caution", scripCode);
                // Don't block, but reduce confidence
            }

            // Check direction alignment
            boolean leadingEdgeAligned =
                    (direction == Direction.LONG && "BULLISH".equals(leadingEdge.getDirection())) ||
                            (direction == Direction.SHORT && "BEARISH".equals(leadingEdge.getDirection()));

            if (!leadingEdgeAligned && leadingEdge.isGoodEntry()) {
                log.debug("[INST_PIVOT] {} Leading edge direction mismatch", scripCode);
                leadingEdgeModifier = 0.9; // Slight penalty but don't block
            }
        }

        // ========== MICROSTRUCTURE CONFIRMATION (OFI) ==========
        // Null-safe: If no OFI, continue without this check
        double ofiConfirmation = getOfiConfirmation(score, direction);

        // ========== CHECK LTF STRUCTURE CONFIRMATION ==========
        boolean ltfConfirms = false;
        if (direction == Direction.LONG) {
            ltfConfirms = smc.isLtfStructureBullish() || smc.isLtfRecentChoch();
        } else {
            ltfConfirms = !smc.isLtfStructureBullish() || smc.isLtfRecentChoch();
        }

        // ========== GEX REGIME ADAPTATION ==========
        double stopMultiplier = getGexStopMultiplier(score);
        double targetMultiplier = getGexTargetMultiplier(score);

        // ========== CALCULATE FINAL STOP AND TARGETS ==========
        double stopLoss;
        double target1;
        double target2;
        double target3;

        if (direction == Direction.LONG) {
            stopLoss = sweep.getSweepPrice() * 0.999;
            double minStop = price * (1 - MIN_STOP_PCT / 100);
            if (stopLoss > minStop) stopLoss = minStop;

            target1 = smc.getLongTarget();
            if (target1 <= price) target1 = price * (1 + 0.01 * targetMultiplier);
        } else {
            stopLoss = sweep.getSweepPrice() * 1.001;
            double minStop = price * (1 + MIN_STOP_PCT / 100);
            if (stopLoss < minStop) stopLoss = minStop;

            target1 = smc.getShortTarget();
            if (target1 >= price || target1 <= 0) target1 = price * (1 - 0.01 * targetMultiplier);
        }

        // Apply ATR validation
        stopLoss = validateStopWithAtr(price, stopLoss, direction, tech);

        // Calculate multi-targets
        double risk = Math.abs(price - stopLoss);
        target1 = direction == Direction.LONG ? price + risk * 2 : price - risk * 2;  // 2:1
        target2 = direction == Direction.LONG ? price + risk * 3 : price - risk * 3;  // 3:1
        target3 = direction == Direction.LONG ? price + risk * 4 : price - risk * 4;  // 4:1

        // Validate R:R
        double reward = Math.abs(target1 - price);
        double rr = risk > 0 ? reward / risk : 0;

        if (rr < MIN_RR_RATIO) {
            log.debug("[INST_PIVOT] {} Final R:R too low: {} < {}", scripCode, rr, MIN_RR_RATIO);
            return Optional.empty();
        }

        // ========== CALCULATE CONFIDENCE ==========
        double confidence = 0.60; // Base confidence

        // LTF confirmation
        if (ltfConfirms) confidence += 0.10;

        // Sweep quality
        if (sweep.isValidReversal()) confidence += 0.05;

        // Bias alignment
        if (smc.isBiasAligned()) confidence += 0.08;

        // Confluence bonus
        if (setup.getConfluenceScore() >= 7) confidence += 0.05;
        if (setup.getConfluenceScore() >= 9) confidence += 0.05;

        // SuperTrend alignment
        if (setup.isSuperTrendAligned()) confidence += 0.05;

        // Leading edge bonus/penalty
        confidence *= leadingEdgeModifier;

        // OFI confirmation bonus
        confidence += ofiConfirmation * 0.05;

        // Family alignment bonus (from score context)
        if (score.isFamilyFullyBullish() && direction == Direction.LONG) confidence += 0.05;
        if (score.isFamilyFullyBearish() && direction == Direction.SHORT) confidence += 0.05;

        confidence = Math.max(0.5, Math.min(0.95, confidence));

        double riskPct = risk / price * 100;

        log.info("[INST_PIVOT] ENTRY! | {} {} @ {} | HTF={} | LTF_sweep={} | " +
                        "SL={} ({}%) | T1={} | T2={} | T3={} | R:R={} | conf={}% | leadEdge={} | ofi={:.2f}",
                scripCode, direction, String.format("%.2f", price),
                smc.getHtfBias(),
                String.format("%.2f", sweep.getLiquidityLevel()),
                String.format("%.2f", stopLoss),
                String.format("%.2f", riskPct),
                String.format("%.2f", target1),
                String.format("%.2f", target2),
                String.format("%.2f", target3),
                String.format("%.1f", rr),
                String.format("%.0f", confidence * 100),
                leadingEdge.getPattern(),
                ofiConfirmation);

        return Optional.of(TradingSignal.builder()
                .signalId(UUID.randomUUID().toString())
                .familyId(scripCode.length() > 5 ? scripCode.substring(0, 5) : scripCode)
                .scripCode(scripCode)
                .companyName(score.getCompanyName())
                .exchange(score.getExchange())
                .direction(direction)
                .horizon(Horizon.SWING)
                .category(SignalCategory.REVERSAL)
                .setupId(STRATEGY_ID)
                .entryPrice(price)
                .stopLoss(stopLoss)
                .target1(target1)
                .target2(target2)
                .confidence(confidence)
                .dataTimestamp(java.time.Instant.ofEpochMilli(
                        score.getPriceTimestamp() > 0 ? score.getPriceTimestamp() : System.currentTimeMillis()))
                .headline(String.format("INST_%s | HTF=%s | Sweep@%.2f | SL=%.2f (%.1f%%) | T1=%.2f | T2=%.2f | R:R=%.1f | LE=%s",
                        direction, smc.getHtfBias(), sweep.getLiquidityLevel(),
                        stopLoss, riskPct, target1, target2, rr, leadingEdge.getPattern()))
                .build());
    }

    // ============================================================================
    // EXIT CONDITIONS
    // ============================================================================

    @Override
    public Optional<ExitSignal> checkExitConditions(Position position, EnrichedQuantScore score) {
        if (position == null || score == null) return Optional.empty();

        Direction direction = position.getDirection();
        double price = score.getClose();
        MtfSmcContext smc = score.getMtfSmcContext();
        TechnicalContext tech = score.getTechnicalContext();

        // Exit 1: HTF CHoCH against position (major structure change)
        if (smc != null && smc.getHtfLastStructureBreak() != null) {
            StructureBreak sb = smc.getHtfLastStructureBreak();
            if (sb.getType() == StructureType.CHOCH) {
                boolean exitSignal = (direction == Direction.LONG && !sb.isBullish()) ||
                        (direction == Direction.SHORT && sb.isBullish());
                if (exitSignal) {
                    log.info("[INST_PIVOT] EXIT: HTF CHoCH against position | {}", position.getFamilyId());
                    return Optional.of(ExitSignal.builder()
                            .reason(ExitReason.INVALIDATION)
                            .exitPrice(price)
                            .description("HTF structure changed against position")
                            .partial(false)
                            .build());
                }
            }
        }

        // Exit 2: SuperTrend flip against
        if (tech != null && tech.isSuperTrendFlip()) {
            boolean stAgainst = (direction == Direction.LONG && !tech.isSuperTrendBullish()) ||
                    (direction == Direction.SHORT && tech.isSuperTrendBullish());
            if (stAgainst) {
                log.info("[INST_PIVOT] EXIT: SuperTrend flipped against | {}", position.getFamilyId());
                return Optional.of(ExitSignal.builder()
                        .reason(ExitReason.INVALIDATION)
                        .exitPrice(price)
                        .description("SuperTrend flipped against position")
                        .partial(false)
                        .build());
            }
        }

        return Optional.empty();
    }

    // ============================================================================
    // SETUP INVALIDATION
    // ============================================================================

    @Override
    public Optional<String> checkSetupInvalidation(EnrichedQuantScore score, SetupContext setup) {
        if (score == null || setup == null) return Optional.empty();

        double price = score.getClose();
        Direction direction = setup.getDirection();
        MtfSmcContext smc = score.getMtfSmcContext();

        // Graceful: If no SMC context, don't invalidate immediately
        if (smc == null) {
            log.debug("[INST_PIVOT] No SMC context for invalidation check - continuing");
            return Optional.empty(); // Don't invalidate just because context is temporarily unavailable
        }

        // Invalidate 1: HTF bias changed
        MarketBias htfBias = smc.getHtfBias();
        if (htfBias != null) {
            if ((direction == Direction.LONG && htfBias == MarketBias.BEARISH) ||
                    (direction == Direction.SHORT && htfBias == MarketBias.BULLISH)) {
                return Optional.of("HTF bias changed against direction");
            }
        }

        // Invalidate 2: Price moved out of zone
        if (direction == Direction.LONG && smc.isInPremium()) {
            return Optional.of("Price moved to premium zone - LONG invalidated");
        }
        if (direction == Direction.SHORT && smc.isInDiscount()) {
            return Optional.of("Price moved to discount zone - SHORT invalidated");
        }

        // Invalidate 3: HTF Order Block broken
        if (direction == Direction.LONG && smc.getHtfNearestDemand() != null &&
                smc.getHtfNearestDemand().isBroken()) {
            return Optional.of("HTF demand zone broken");
        }
        if (direction == Direction.SHORT && smc.getHtfNearestSupply() != null &&
                smc.getHtfNearestSupply().isBroken()) {
            return Optional.of("HTF supply zone broken");
        }

        return Optional.empty();
    }

    // ============================================================================
    // ENHANCEMENT METHODS (ALL NULL-SAFE)
    // ============================================================================

    /**
     * Check if current time is good for trading.
     * Avoids: Opening noise, lunch hour, closing chaos.
     * Null-safe: Returns true if no time context available.
     */
    private boolean isGoodTradingTime(EnrichedQuantScore score) {
        // Get current time
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        int hour = now.getHour();
        int minute = now.getMinute();
        int timeHHMM = hour * 100 + minute;

        // Opening noise (9:15-9:20)
        if (timeHHMM >= 915 && timeHHMM <= 920) {
            return false;
        }

        // Lunch hour (12:00-13:30)
        if (timeHHMM >= 1200 && timeHHMM <= 1330) {
            return false;
        }

        // Closing chaos (15:00-15:30)
        if (timeHHMM >= 1500) {
            return false;
        }

        // Check time context if available
        TimeContext timeContext = score != null ? score.getTimeContext() : null;
        if (timeContext != null && timeContext.shouldAvoidTrading()) {
            return false;
        }

        return true;
    }

    /**
     * Calculate family alignment bonus.
     * +2 if fully aligned, +1 if partially aligned, -1 if divergent.
     * Null-safe: Returns 0 if no family context.
     */
    private int calculateFamilyAlignmentBonus(EnrichedQuantScore score, MarketBias htfBias) {
        if (score == null || !score.hasFamilyContext()) {
            return 0;
        }

        if (htfBias == MarketBias.BULLISH) {
            if (score.isFamilyFullyBullish()) return 2;
            if (score.getFamilyBullishAlignment() > 0.6) return 1;
            if (score.hasFamilyDivergence()) return -1;
        } else if (htfBias == MarketBias.BEARISH) {
            if (score.isFamilyFullyBearish()) return 2;
            if (score.getFamilyBearishAlignment() > 0.6) return 1;
            if (score.hasFamilyDivergence()) return -1;
        }

        return 0;
    }

    /**
     * Calculate divergence strength.
     * Null-safe: Returns DivergenceStrength.none() if calculator not available.
     */
    private DivergenceStrength calculateDivergenceStrength(EnrichedQuantScore score) {
        if (divergenceCalculator.isEmpty() || score == null) {
            return DivergenceStrength.none();
        }

        try {
            return divergenceCalculator.get().calculate(score);
        } catch (Exception e) {
            log.debug("[INST_PIVOT] Error calculating divergence strength: {}", e.getMessage());
            return DivergenceStrength.none();
        }
    }

    /**
     * Calculate leading edge pattern.
     * Null-safe: Returns LeadingEdgeResult.none() if calculator not available.
     */
    private LeadingEdgeResult calculateLeadingEdge(EnrichedQuantScore score) {
        if (leadingEdgeCalculator.isEmpty() || score == null) {
            return LeadingEdgeResult.none();
        }

        try {
            return leadingEdgeCalculator.get().calculate(score);
        } catch (Exception e) {
            log.debug("[INST_PIVOT] Error calculating leading edge: {}", e.getMessage());
            return LeadingEdgeResult.none();
        }
    }

    /**
     * Get OFI confirmation for entry direction.
     * Returns 0-1 based on OFI alignment with direction.
     * Null-safe: Returns 0.5 (neutral) if no OFI data.
     */
    private double getOfiConfirmation(EnrichedQuantScore score, Direction direction) {
        if (score == null || score.getHistoricalContext() == null) {
            return 0.5; // Neutral
        }

        HistoricalContext hist = score.getHistoricalContext();
        if (hist.getOfiContext() == null) {
            return 0.5;
        }

        double zscore = hist.getOfiContext().getZscore();

        // LONG wants positive OFI, SHORT wants negative OFI
        if (direction == Direction.LONG) {
            if (zscore > MIN_OFI_ZSCORE) return Math.min(1.0, 0.5 + zscore * 0.25);
            if (zscore < -MIN_OFI_ZSCORE) return Math.max(0.0, 0.5 + zscore * 0.25);
        } else {
            if (zscore < -MIN_OFI_ZSCORE) return Math.min(1.0, 0.5 - zscore * 0.25);
            if (zscore > MIN_OFI_ZSCORE) return Math.max(0.0, 0.5 - zscore * 0.25);
        }

        return 0.5;
    }

    /**
     * Get GEX-based stop multiplier.
     * TRENDING = 1.5x (wider stops), MEAN_REVERTING = 0.8x (tighter).
     * Null-safe: Returns 1.0 if no GEX data.
     */
    private double getGexStopMultiplier(EnrichedQuantScore score) {
        if (score == null || score.getGexProfile() == null) {
            return 1.0;
        }

        GEXProfile.GEXRegime regime = score.getGexProfile().getRegime();
        if (regime == null) return 1.0;

        return switch (regime) {
            case STRONG_TRENDING -> 1.5;
            case TRENDING -> 1.3;
            case MEAN_REVERTING -> 0.8;
            case STRONG_MEAN_REVERTING -> 0.7;
            default -> 1.0;
        };
    }

    /**
     * Get GEX-based target multiplier.
     * TRENDING = 1.5x (bigger targets), MEAN_REVERTING = 0.8x (quicker exits).
     * Null-safe: Returns 1.0 if no GEX data.
     */
    private double getGexTargetMultiplier(EnrichedQuantScore score) {
        if (score == null || score.getGexProfile() == null) {
            return 1.0;
        }

        GEXProfile.GEXRegime regime = score.getGexProfile().getRegime();
        if (regime == null) return 1.0;

        return switch (regime) {
            case STRONG_TRENDING -> 1.5;
            case TRENDING -> 1.3;
            case MEAN_REVERTING -> 0.8;
            case STRONG_MEAN_REVERTING -> 0.7;
            default -> 1.0;
        };
    }

    /**
     * Validate stop loss with ATR bounds.
     * Ensures stop is between MIN_STOP_ATR and MAX_STOP_ATR.
     * Null-safe: Returns original stop if no ATR data.
     */
    private double validateStopWithAtr(double price, double stopLoss, Direction direction, TechnicalContext tech) {
        if (tech == null || tech.getAtrPct() == null || tech.getAtrPct() <= 0) {
            return stopLoss; // No ATR, use original
        }

        double atrPct = tech.getAtrPct();
        double atr = price * atrPct / 100;

        double currentStopDistance = Math.abs(price - stopLoss);
        double minStopDistance = atr * MIN_STOP_ATR;
        double maxStopDistance = atr * MAX_STOP_ATR;

        // Adjust if outside bounds
        if (currentStopDistance < minStopDistance) {
            stopLoss = direction == Direction.LONG ?
                    price - minStopDistance :
                    price + minStopDistance;
            log.debug("[INST_PIVOT] Stop adjusted to minimum ATR bound: {}", String.format("%.2f", stopLoss));
        } else if (currentStopDistance > maxStopDistance) {
            stopLoss = direction == Direction.LONG ?
                    price - maxStopDistance :
                    price + maxStopDistance;
            log.debug("[INST_PIVOT] Stop adjusted to maximum ATR bound: {}", String.format("%.2f", stopLoss));
        }

        return stopLoss;
    }

    // ============================================================================
    // HELPER METHODS
    // ============================================================================

    private boolean isAtKeyPivot(double price, MultiTimeframeLevels mtf) {
        if (mtf == null || mtf.getDailyPivot() == null) return false;

        var dp = mtf.getDailyPivot();
        double[] levels = {dp.getPivot(), dp.getS1(), dp.getS2(), dp.getR1(), dp.getR2(),
                dp.getTc(), dp.getBc()};

        for (double level : levels) {
            if (level > 0 && isNear(price, level)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAtWeeklyPivot(double price, MultiTimeframeLevels mtf) {
        if (mtf == null || mtf.getWeeklyPivot() == null) return false;

        var wp = mtf.getWeeklyPivot();
        double[] levels = {wp.getPivot(), wp.getS1(), wp.getR1()};

        for (double level : levels) {
            if (level > 0 && isNear(price, level)) {
                return true;
            }
        }
        return false;
    }

    private boolean isNear(double price, double level) {
        return Math.abs(price - level) / price * 100 <= LEVEL_PROXIMITY_PCT;
    }

    private double calculateStopLoss(double price, Direction direction, MtfSmcContext smc,
                                      TechnicalContext tech, double multiplier) {
        double stop;
        if (direction == Direction.LONG) {
            stop = smc.getLongStopLoss();
            if (stop <= 0) stop = price * (1 - MIN_STOP_PCT * multiplier / 100);
        } else {
            stop = smc.getShortStopLoss();
            if (stop <= 0) stop = price * (1 + MIN_STOP_PCT * multiplier / 100);
        }
        return stop;
    }

    private double calculateTarget1(double price, Direction direction,
                                     MtfSmcContext smc, MultiTimeframeLevels mtf, double multiplier) {
        if (direction == Direction.LONG) {
            double target = smc.getLongTarget();
            if (target <= price) {
                if (mtf != null && mtf.getDailyPivot() != null) {
                    var dp = mtf.getDailyPivot();
                    if (dp.getR1() > price) return dp.getR1();
                    if (dp.getR2() > price) return dp.getR2();
                }
                target = price * (1 + 0.01 * multiplier);
            }
            return target;
        } else {
            double target = smc.getShortTarget();
            if (target >= price || target <= 0) {
                if (mtf != null && mtf.getDailyPivot() != null) {
                    var dp = mtf.getDailyPivot();
                    if (dp.getS1() < price && dp.getS1() > 0) return dp.getS1();
                    if (dp.getS2() < price && dp.getS2() > 0) return dp.getS2();
                }
                target = price * (1 - 0.01 * multiplier);
            }
            return target;
        }
    }

    private double calculateTarget2(double price, Direction direction,
                                     MtfSmcContext smc, MultiTimeframeLevels mtf,
                                     double target1, double multiplier) {
        if (direction == Direction.LONG) {
            if (mtf != null && mtf.getDailyPivot() != null) {
                var dp = mtf.getDailyPivot();
                if (dp.getR2() > target1) return dp.getR2();
                if (dp.getR3() > target1) return dp.getR3();
            }
            if (smc.getHtfNearestSupply() != null &&
                    smc.getHtfNearestSupply().getBottom() > target1) {
                return smc.getHtfNearestSupply().getBottom();
            }
            return target1 * (1 + 0.005 * multiplier);
        } else {
            if (mtf != null && mtf.getDailyPivot() != null) {
                var dp = mtf.getDailyPivot();
                if (dp.getS2() < target1 && dp.getS2() > 0) return dp.getS2();
                if (dp.getS3() < target1 && dp.getS3() > 0) return dp.getS3();
            }
            if (smc.getHtfNearestDemand() != null &&
                    smc.getHtfNearestDemand().getTop() < target1) {
                return smc.getHtfNearestDemand().getTop();
            }
            return target1 * (1 - 0.005 * multiplier);
        }
    }

    private double calculateTarget3(double price, Direction direction, double target1, double target2) {
        // T3 = extended target beyond T2
        double t1Distance = Math.abs(target1 - price);
        double t2Distance = Math.abs(target2 - price);
        double t3Distance = t2Distance + (t2Distance - t1Distance); // Extend by same increment

        if (direction == Direction.LONG) {
            return price + t3Distance;
        } else {
            return price - t3Distance;
        }
    }
}
