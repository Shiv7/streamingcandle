package com.kotsin.consumer.signal.service;

import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal.Direction;
import com.kotsin.consumer.signal.config.SignalDecayConfig;
import com.kotsin.consumer.signal.model.SignalContext;
import com.kotsin.consumer.signal.model.SignalStatus;
import com.kotsin.consumer.trading.smc.SmcContext.MarketBias;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * SignalInvalidator - Checks all invalidation rules for a signal.
 *
 * INVALIDATION RULE MATRIX:
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ Rule              │ Condition                       │ Priority      │
 * ├───────────────────┼─────────────────────────────────┼───────────────┤
 * │ STOP_BREACHED     │ Price crossed stop loss         │ IMMEDIATE     │
 * │ DIV_REVERSED      │ Divergence sign flipped         │ HIGH          │
 * │ TARGET_25         │ Target progress >= 25%          │ HIGH          │
 * │ TIME_EXPIRED      │ Signal age > 60 minutes         │ MEDIUM        │
 * │ DIV_RESOLVED      │ Divergence < 20% of initial     │ MEDIUM        │
 * │ MOM_REVERSED      │ Momentum reversed 3+ candles    │ MEDIUM        │
 * │ DECAY_FLOOR       │ Combined decay < 15%            │ MEDIUM        │
 * │ HTF_BIAS_CHANGED  │ HTF structure broke             │ MEDIUM        │
 * │ VOLUME_DRIED      │ Volume < 30% of initial         │ LOW           │
 * │ PRICE_MOVED_FAR   │ Price moved > 3 ATR             │ LOW           │
 * │ SESSION_CHANGED   │ Session context different       │ LOW           │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * ALL METHODS ARE NULL-SAFE:
 * - If data unavailable, skip that check
 * - Return Optional.empty() if can't determine invalidity
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalInvalidator {

    private final SignalDecayConfig config;
    private final SignalDecayCalculator decayCalculator;

    /**
     * Check all invalidation rules and return the status if invalid.
     * Rules are checked in priority order.
     *
     * @param signal  The signal context
     * @param current Current enriched score (null-safe)
     * @return Optional<SignalStatus> - the invalid status if signal should be invalidated
     */
    public Optional<SignalStatus> checkInvalidation(SignalContext signal, EnrichedQuantScore current) {
        if (signal == null) {
            return Optional.empty();
        }

        // Update signal with current price
        if (current != null && current.getClose() > 0) {
            signal.updateWithPrice(current.getClose());
        }

        // Check rules in priority order

        // 1. STOP BREACHED (IMMEDIATE)
        Optional<SignalStatus> stopCheck = checkStopBreached(signal, current);
        if (stopCheck.isPresent()) {
            log.info("[INVALIDATE] {} STOP_BREACHED | price={} | stop={}",
                    signal.getScripCode(), signal.getCurrentPrice(), signal.getStopLoss());
            return stopCheck;
        }

        // 2. DIVERGENCE REVERSED (HIGH)
        Optional<SignalStatus> divReversedCheck = checkDivergenceReversed(signal, current);
        if (divReversedCheck.isPresent()) {
            log.info("[INVALIDATE] {} DIVERGENCE_REVERSED", signal.getScripCode());
            return divReversedCheck;
        }

        // 3. TARGET 25% ACHIEVED (HIGH)
        Optional<SignalStatus> targetCheck = checkTargetPartiallyAchieved(signal, current);
        if (targetCheck.isPresent()) {
            log.info("[INVALIDATE] {} TARGET_PARTIALLY_ACHIEVED | progress={}%",
                    signal.getScripCode(), String.format("%.1f", signal.getTarget1Progress() * 100));
            return targetCheck;
        }

        // 4. TIME EXPIRED (MEDIUM)
        Optional<SignalStatus> timeCheck = checkTimeExpired(signal);
        if (timeCheck.isPresent()) {
            log.info("[INVALIDATE] {} TIME_EXPIRED | age={} min",
                    signal.getScripCode(), String.format("%.1f", signal.getAgeMinutes()));
            return timeCheck;
        }

        // 5. DIVERGENCE RESOLVED (MEDIUM)
        Optional<SignalStatus> divResolvedCheck = checkDivergenceResolved(signal, current);
        if (divResolvedCheck.isPresent()) {
            log.info("[INVALIDATE] {} DIVERGENCE_RESOLVED", signal.getScripCode());
            return divResolvedCheck;
        }

        // 6. MOMENTUM REVERSED (MEDIUM)
        Optional<SignalStatus> momCheck = checkMomentumReversed(signal, current);
        if (momCheck.isPresent()) {
            log.info("[INVALIDATE] {} MOMENTUM_REVERSED | reversalCandles={}",
                    signal.getScripCode(), signal.getMomentumReversalCandles());
            return momCheck;
        }

        // 7. DECAY FLOOR (MEDIUM)
        Optional<SignalStatus> decayCheck = checkDecayFloor(signal);
        if (decayCheck.isPresent()) {
            log.info("[INVALIDATE] {} DECAYED_TOO_MUCH | decay={}%",
                    signal.getScripCode(), String.format("%.1f", signal.getCombinedDecay() * 100));
            return decayCheck;
        }

        // 8. HTF BIAS CHANGED (MEDIUM)
        Optional<SignalStatus> htfCheck = checkHtfBiasChanged(signal, current);
        if (htfCheck.isPresent()) {
            log.info("[INVALIDATE] {} HTF_BIAS_CHANGED", signal.getScripCode());
            return htfCheck;
        }

        // 9. VOLUME DRIED (LOW)
        Optional<SignalStatus> volumeCheck = checkVolumeDried(signal, current);
        if (volumeCheck.isPresent()) {
            log.info("[INVALIDATE] {} VOLUME_DRIED", signal.getScripCode());
            return volumeCheck;
        }

        // 10. PRICE MOVED TOO FAR (LOW)
        Optional<SignalStatus> priceCheck = checkPriceMovedTooFar(signal, current);
        if (priceCheck.isPresent()) {
            log.info("[INVALIDATE] {} PRICE_MOVED_TOO_FAR | atrMove={}",
                    signal.getScripCode(), String.format("%.1f", signal.getPriceMovementInAtr()));
            return priceCheck;
        }

        // 11. SESSION CHANGED (LOW)
        Optional<SignalStatus> sessionCheck = checkSessionChanged(signal);
        if (sessionCheck.isPresent()) {
            log.info("[INVALIDATE] {} SESSION_CHANGED", signal.getScripCode());
            return sessionCheck;
        }

        // No invalidation
        return Optional.empty();
    }

    /**
     * Evaluate signal status (ACTIVE, STRENGTHENING, WEAKENING, or INVALID_*).
     */
    public SignalStatus evaluateStatus(SignalContext signal, EnrichedQuantScore current) {
        if (signal == null) {
            return SignalStatus.ACTIVE;
        }

        // Check for invalidation first
        Optional<SignalStatus> invalidStatus = checkInvalidation(signal, current);
        if (invalidStatus.isPresent()) {
            return invalidStatus.get();
        }

        // Calculate decay
        double decay = decayCalculator.calculateDecay(signal, current);

        // Determine status based on decay
        if (decayCalculator.isStrengthening(signal)) {
            return SignalStatus.STRENGTHENING;
        } else if (decayCalculator.isSignificantlyDecayed(signal)) {
            return SignalStatus.WEAKENING;
        }

        return SignalStatus.ACTIVE;
    }

    // ============ INDIVIDUAL RULE CHECKS ============

    /**
     * Check if stop loss is breached.
     */
    private Optional<SignalStatus> checkStopBreached(SignalContext signal, EnrichedQuantScore current) {
        if (signal.getCurrentPrice() <= 0) {
            return Optional.empty();
        }

        if (signal.isStopBreached()) {
            return Optional.of(SignalStatus.INVALID_STOP_BREACHED);
        }

        return Optional.empty();
    }

    /**
     * Check if divergence has reversed (opposite sign).
     */
    private Optional<SignalStatus> checkDivergenceReversed(SignalContext signal, EnrichedQuantScore current) {
        if (current == null || current.getHistoricalContext() == null) {
            return Optional.empty();
        }

        // If signal had divergence, check if it reversed
        if (!signal.isHasPcrDivergence() && !signal.isHasOiDivergence()) {
            return Optional.empty(); // No divergence to reverse
        }

        var hist = current.getHistoricalContext();

        // Check OFI zscore direction
        if (hist.getOfiContext() != null && signal.getInitialOfiZscore() != 0) {
            double initialZscore = signal.getInitialOfiZscore();
            double currentZscore = hist.getOfiContext().getZscore();

            // Reversed if signs are opposite and magnitude is significant
            if (Math.signum(initialZscore) != Math.signum(currentZscore) &&
                    Math.abs(currentZscore) > 1.0) {
                return Optional.of(SignalStatus.INVALID_DIVERGENCE_REVERSED);
            }
        }

        return Optional.empty();
    }

    /**
     * Check if target is partially achieved (>= 25%).
     */
    private Optional<SignalStatus> checkTargetPartiallyAchieved(SignalContext signal, EnrichedQuantScore current) {
        double progress = signal.getTarget1Progress();

        if (progress >= config.getTargetThreshold()) {
            return Optional.of(SignalStatus.INVALID_TARGET_PARTIALLY_ACHIEVED);
        }

        return Optional.empty();
    }

    /**
     * Check if signal has expired (> 60 minutes).
     */
    private Optional<SignalStatus> checkTimeExpired(SignalContext signal) {
        if (signal.getAgeMinutes() > config.getMaxSignalAgeMinutes()) {
            return Optional.of(SignalStatus.INVALID_TIME_EXPIRED);
        }

        return Optional.empty();
    }

    /**
     * Check if divergence has resolved (< 20% of initial).
     */
    private Optional<SignalStatus> checkDivergenceResolved(SignalContext signal, EnrichedQuantScore current) {
        if (signal.getDivergenceDecay() <= 0) {
            // Divergence fully resolved
            return Optional.of(SignalStatus.INVALID_DIVERGENCE_RESOLVED);
        }

        if (signal.getDivergenceDecay() < config.getDivergenceResolvedThreshold()) {
            return Optional.of(SignalStatus.INVALID_DIVERGENCE_RESOLVED);
        }

        return Optional.empty();
    }

    /**
     * Check if momentum has reversed for 3+ candles.
     */
    private Optional<SignalStatus> checkMomentumReversed(SignalContext signal, EnrichedQuantScore current) {
        if (signal.getMomentumReversalCandles() >= config.getMomentumReversalCandles()) {
            return Optional.of(SignalStatus.INVALID_MOMENTUM_REVERSED);
        }

        return Optional.empty();
    }

    /**
     * Check if combined decay is below floor (< 15%).
     */
    private Optional<SignalStatus> checkDecayFloor(SignalContext signal) {
        if (signal.getCombinedDecay() < config.getInvalidationFloor()) {
            return Optional.of(SignalStatus.INVALID_DECAYED_TOO_MUCH);
        }

        return Optional.empty();
    }

    /**
     * Check if HTF bias has changed against signal direction.
     */
    private Optional<SignalStatus> checkHtfBiasChanged(SignalContext signal, EnrichedQuantScore current) {
        if (current == null || current.getMtfSmcContext() == null) {
            return Optional.empty();
        }

        MarketBias initialBias = signal.getInitialHtfBias();
        MarketBias currentBias = current.getMtfSmcContext().getHtfBias();

        if (initialBias == null || currentBias == null) {
            return Optional.empty();
        }

        // Check if bias flipped against signal direction
        if (signal.getDirection() == Direction.LONG) {
            if (initialBias == MarketBias.BULLISH && currentBias == MarketBias.BEARISH) {
                return Optional.of(SignalStatus.INVALID_HTF_BIAS_CHANGED);
            }
        } else {
            if (initialBias == MarketBias.BEARISH && currentBias == MarketBias.BULLISH) {
                return Optional.of(SignalStatus.INVALID_HTF_BIAS_CHANGED);
            }
        }

        return Optional.empty();
    }

    /**
     * Check if volume has dried up (< 30% of initial).
     */
    private Optional<SignalStatus> checkVolumeDried(SignalContext signal, EnrichedQuantScore current) {
        if (signal.getVolumeDecay() < config.getVolumeDriedThreshold()) {
            return Optional.of(SignalStatus.INVALID_VOLUME_DRIED);
        }

        return Optional.empty();
    }

    /**
     * Check if price has moved too far without entry (> 3 ATR).
     */
    private Optional<SignalStatus> checkPriceMovedTooFar(SignalContext signal, EnrichedQuantScore current) {
        if (signal.getInitialAtr() <= 0) {
            return Optional.empty();
        }

        double atrMove = signal.getPriceMovementInAtr();

        if (atrMove > config.getPriceMovementAtrThreshold()) {
            return Optional.of(SignalStatus.INVALID_PRICE_MOVED_TOO_FAR);
        }

        return Optional.empty();
    }

    /**
     * Check if session has changed (morning signal, now afternoon).
     */
    private Optional<SignalStatus> checkSessionChanged(SignalContext signal) {
        String initialSession = signal.getInitialSession();
        if (initialSession == null) {
            return Optional.empty();
        }

        // Get current session
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        int timeHHMM = now.getHour() * 100 + now.getMinute();
        String currentSession = config.getCurrentSession(timeHHMM);

        // Check for significant session changes
        if ("MORNING".equals(initialSession) && "AFTERNOON".equals(currentSession)) {
            // Morning signal in afternoon - context changed
            return Optional.of(SignalStatus.INVALID_SESSION_CHANGED);
        }

        if ("AFTERNOON".equals(initialSession) && "CLOSING".equals(currentSession)) {
            // Afternoon signal in closing - context changed
            return Optional.of(SignalStatus.INVALID_SESSION_CHANGED);
        }

        return Optional.empty();
    }

    /**
     * Quick check if signal is still valid.
     */
    public boolean isValid(SignalContext signal, EnrichedQuantScore current) {
        return checkInvalidation(signal, current).isEmpty();
    }

    /**
     * Get invalidation reason string.
     */
    public String getInvalidationReason(SignalContext signal, EnrichedQuantScore current) {
        Optional<SignalStatus> status = checkInvalidation(signal, current);
        return status.map(SignalStatus::getDescription).orElse("Signal is valid");
    }
}
