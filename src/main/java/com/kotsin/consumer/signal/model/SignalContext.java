package com.kotsin.consumer.signal.model;

import com.kotsin.consumer.enrichment.signal.model.TradingSignal.Direction;
import com.kotsin.consumer.trading.smc.SmcContext.MarketBias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SignalContext - Tracks the complete state of a trading signal through its lifecycle.
 *
 * THIS IS THE MEMORY OF A SIGNAL:
 * - Initial conditions when signal was generated
 * - Current state (decayed values, status)
 * - Tracking metrics for invalidation checks
 *
 * WHY WE NEED THIS:
 * Without tracking initial conditions, we can't:
 * - Calculate how much divergence has resolved
 * - Measure momentum decay
 * - Know if signal is strengthening or weakening
 * - Apply proper invalidation rules
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalContext {

    // ============ IDENTITY ============

    /**
     * Unique signal identifier
     */
    private String signalId;

    /**
     * Instrument family ID
     */
    private String familyId;

    /**
     * Scrip code (trading symbol)
     */
    private String scripCode;

    /**
     * Exchange (N=NSE, M=MCX, B=BSE)
     */
    private String exchange;

    // ============ SIGNAL DETAILS ============

    /**
     * Signal direction (LONG/SHORT)
     */
    private Direction direction;

    /**
     * Strategy that generated this signal
     */
    private String strategyId;

    /**
     * Signal generation timestamp
     */
    private long timestamp;

    /**
     * Entry price at signal generation
     */
    private double entryPrice;

    /**
     * Stop loss level
     */
    private double stopLoss;

    /**
     * Target 1 (primary target, usually 1:1 or 2:1)
     */
    private double target1;

    /**
     * Target 2 (extended target)
     */
    private double target2;

    /**
     * Target 3 (stretch target)
     */
    private double target3;

    /**
     * Initial score when signal was generated
     */
    private double initialScore;

    /**
     * Initial confidence when signal was generated
     */
    private double initialConfidence;

    // ============ INITIAL CONTEXT (FOR DECAY CALCULATION) ============

    /**
     * ATR at signal generation (for price movement validation)
     */
    @Builder.Default
    private double initialAtr = 0;

    /**
     * ATR as percentage of price
     */
    @Builder.Default
    private double initialAtrPct = 0;

    /**
     * Initial divergence magnitude (for divergence decay)
     * PCR or OI divergence percentage at signal time
     */
    @Builder.Default
    private double initialDivergenceMagnitude = 0;

    /**
     * Did signal have PCR divergence?
     */
    @Builder.Default
    private boolean hasPcrDivergence = false;

    /**
     * Did signal have OI divergence?
     */
    @Builder.Default
    private boolean hasOiDivergence = false;

    /**
     * Initial momentum slope (for momentum decay)
     */
    @Builder.Default
    private double initialMomentumSlope = 0;

    /**
     * Initial OFI zscore
     */
    @Builder.Default
    private double initialOfiZscore = 0;

    /**
     * Initial volume (for volume decay)
     */
    @Builder.Default
    private double initialVolume = 0;

    /**
     * HTF bias at signal generation
     */
    private MarketBias initialHtfBias;

    /**
     * Initial microstructure score (LTF)
     */
    @Builder.Default
    private double initialMicrostructureScore = 0;

    /**
     * Session when signal was generated (MORNING/AFTERNOON)
     */
    private String initialSession;

    /**
     * Range position at signal generation (0-1)
     */
    @Builder.Default
    private double initialRangePosition = 0.5;

    // ============ CURRENT STATE ============

    /**
     * Current signal status
     */
    @Builder.Default
    private SignalStatus currentStatus = SignalStatus.ACTIVE;

    /**
     * Current decayed score
     */
    @Builder.Default
    private double currentScore = 0;

    /**
     * Last update timestamp
     */
    @Builder.Default
    private long lastUpdateTime = 0;

    /**
     * Current price
     */
    @Builder.Default
    private double currentPrice = 0;

    // ============ DECAY COMPONENTS ============

    /**
     * Time decay factor (0-1, lower = more decay)
     */
    @Builder.Default
    private double timeDecay = 1.0;

    /**
     * Target progress decay factor
     */
    @Builder.Default
    private double targetDecay = 1.0;

    /**
     * Divergence decay factor
     */
    @Builder.Default
    private double divergenceDecay = 1.0;

    /**
     * Momentum decay factor
     */
    @Builder.Default
    private double momentumDecay = 1.0;

    /**
     * Volume decay factor
     */
    @Builder.Default
    private double volumeDecay = 1.0;

    /**
     * Session decay factor (faster decay during lunch, etc.)
     */
    @Builder.Default
    private double sessionDecay = 1.0;

    /**
     * Combined decay (product of all factors)
     */
    @Builder.Default
    private double combinedDecay = 1.0;

    // ============ TRACKING METRICS ============

    /**
     * Candles since signal generation
     */
    @Builder.Default
    private int candlesSinceSignal = 0;

    /**
     * Consecutive candles with momentum reversal
     */
    @Builder.Default
    private int momentumReversalCandles = 0;

    /**
     * Highest price since signal (for LONG)
     */
    @Builder.Default
    private double highestPriceSince = 0;

    /**
     * Lowest price since signal (for SHORT)
     */
    @Builder.Default
    private double lowestPriceSince = Double.MAX_VALUE;

    /**
     * Maximum favorable excursion (how far in profit)
     */
    @Builder.Default
    private double maxFavorableExcursion = 0;

    /**
     * Maximum adverse excursion (how far against)
     */
    @Builder.Default
    private double maxAdverseExcursion = 0;

    // ============ HELPER METHODS ============

    /**
     * Get signal age in milliseconds
     */
    public long getAgeMs() {
        return System.currentTimeMillis() - timestamp;
    }

    /**
     * Get signal age in minutes
     */
    public double getAgeMinutes() {
        return getAgeMs() / 60000.0;
    }

    /**
     * Get target 1 progress percentage (0-1)
     */
    public double getTarget1Progress() {
        if (target1 == 0 || entryPrice == 0) return 0;

        double targetDistance = Math.abs(target1 - entryPrice);
        if (targetDistance == 0) return 0;

        double currentProgress = direction == Direction.LONG ?
                currentPrice - entryPrice :
                entryPrice - currentPrice;

        return Math.max(0, currentProgress / targetDistance);
    }

    /**
     * Get risk (entry to stop distance)
     */
    public double getRisk() {
        return Math.abs(entryPrice - stopLoss);
    }

    /**
     * Get reward (entry to target1 distance)
     */
    public double getReward() {
        return Math.abs(target1 - entryPrice);
    }

    /**
     * Get risk-reward ratio
     */
    public double getRiskRewardRatio() {
        double risk = getRisk();
        return risk > 0 ? getReward() / risk : 0;
    }

    /**
     * Check if price is moving favorably
     */
    public boolean isPriceMovingFavorably() {
        if (direction == Direction.LONG) {
            return currentPrice > entryPrice;
        } else {
            return currentPrice < entryPrice;
        }
    }

    /**
     * Check if stop is breached
     */
    public boolean isStopBreached() {
        if (direction == Direction.LONG) {
            return currentPrice <= stopLoss;
        } else {
            return currentPrice >= stopLoss;
        }
    }

    /**
     * Get price movement in ATR units
     */
    public double getPriceMovementInAtr() {
        if (initialAtr <= 0) return 0;
        return Math.abs(currentPrice - entryPrice) / initialAtr;
    }

    /**
     * Update tracking metrics with new price
     */
    public void updateWithPrice(double price) {
        this.currentPrice = price;
        this.lastUpdateTime = System.currentTimeMillis();
        this.candlesSinceSignal++;

        // Track high/low
        if (price > highestPriceSince) {
            highestPriceSince = price;
        }
        if (price < lowestPriceSince) {
            lowestPriceSince = price;
        }

        // Track MFE/MAE
        if (direction == Direction.LONG) {
            double favorable = highestPriceSince - entryPrice;
            double adverse = entryPrice - lowestPriceSince;
            maxFavorableExcursion = Math.max(maxFavorableExcursion, favorable);
            maxAdverseExcursion = Math.max(maxAdverseExcursion, adverse);
        } else {
            double favorable = entryPrice - lowestPriceSince;
            double adverse = highestPriceSince - entryPrice;
            maxFavorableExcursion = Math.max(maxFavorableExcursion, favorable);
            maxAdverseExcursion = Math.max(maxAdverseExcursion, adverse);
        }
    }

    /**
     * Create from TradingSignal
     */
    public static SignalContext fromTradingSignal(
            com.kotsin.consumer.enrichment.signal.model.TradingSignal signal,
            com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore score) {

        SignalContextBuilder builder = SignalContext.builder()
                .signalId(signal.getSignalId())
                .familyId(signal.getFamilyId())
                .scripCode(signal.getScripCode())
                .exchange(signal.getExchange())
                .direction(signal.getDirection())
                .strategyId(signal.getSetupId())
                .timestamp(System.currentTimeMillis())
                .entryPrice(signal.getEntryPrice())
                .stopLoss(signal.getStopLoss())
                .target1(signal.getTarget1())
                .target2(signal.getTarget2() != null ? signal.getTarget2() : signal.getTarget1() * 1.005)
                .initialScore(signal.getConfidence() * 100)
                .initialConfidence(signal.getConfidence())
                .currentScore(signal.getConfidence() * 100)
                .currentPrice(signal.getEntryPrice())
                .highestPriceSince(signal.getEntryPrice())
                .lowestPriceSince(signal.getEntryPrice());

        // Enrich from score if available
        if (score != null) {
            // ATR
            if (score.getTechnicalContext() != null) {
                Double atrPct = score.getTechnicalContext().getAtrPct();
                if (atrPct != null && atrPct > 0) {
                    builder.initialAtrPct(atrPct);
                    builder.initialAtr(signal.getEntryPrice() * atrPct / 100);
                }
            }

            // Divergence
            if (score.getHistoricalContext() != null) {
                var hist = score.getHistoricalContext();
                builder.hasPcrDivergence(hist.isPcrDivergence());
                builder.hasOiDivergence(hist.isOiDivergence());

                // Get OFI zscore
                if (hist.getOfiContext() != null) {
                    builder.initialOfiZscore(hist.getOfiContext().getZscore());
                }

                // Calculate divergence magnitude
                double divMag = 0;
                if (hist.isPcrDivergence()) divMag += 50;
                if (hist.isOiDivergence()) divMag += 50;
                builder.initialDivergenceMagnitude(divMag);
            }

            // MTF SMC context
            if (score.getMtfSmcContext() != null) {
                var smc = score.getMtfSmcContext();
                builder.initialHtfBias(smc.getHtfBias());
                builder.initialRangePosition(smc.getRangePosition());
            }

            // Session
            if (score.getTimeContext() != null) {
                builder.initialSession(score.getTimeContext().getSession());
            }

            // Volume - use base score volume if available
            if (score.getBaseScore() != null && score.getBaseScore().getBreakdown() != null) {
                // Volume is part of breakdown, estimate from score
                builder.initialVolume(100); // Placeholder - would need actual volume
            }
        }

        return builder.build();
    }
}
