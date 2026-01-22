package com.kotsin.consumer.signal.service;

import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal.Direction;
import com.kotsin.consumer.signal.config.SignalDecayConfig;
import com.kotsin.consumer.signal.model.SignalContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * SignalDecayCalculator - Calculates how much a signal has decayed over time.
 *
 * MASTER DECAY FORMULA:
 * CurrentValue = InitialValue × TimeDecay × TargetDecay × DivergenceDecay
 *                × MomentumDecay × VolumeDecay × SessionDecay
 *
 * WHY SIGNALS MUST DECAY:
 * 1. TIME: Old signals = stale information, market moved on
 * 2. TARGET: Edge consumed = opportunity taken by others
 * 3. DIVERGENCE: Premise resolved = reason for trade gone
 * 4. MOMENTUM: Trend exhausted = fuel spent
 * 5. VOLUME: Participation dropped = no conviction
 * 6. SESSION: Context changed = different market dynamics
 *
 * ALL METHODS ARE NULL-SAFE:
 * - If data unavailable, return 1.0 (no decay)
 * - Flow always continues to final score
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalDecayCalculator {

    private final SignalDecayConfig config;

    /**
     * Calculate all decay factors and update signal context.
     * Returns combined decay multiplier (0.0 - 1.5+).
     *
     * @param signal  The signal context to update
     * @param current Current enriched score (null-safe)
     * @return Combined decay multiplier
     */
    public double calculateDecay(SignalContext signal, EnrichedQuantScore current) {
        if (signal == null) {
            return 1.0;
        }

        if (!config.isEnabled()) {
            return 1.0;
        }

        // Calculate individual decay factors (all null-safe)
        double timeDecay = calculateTimeDecay(signal);
        double targetDecay = calculateTargetDecay(signal, current);
        double divergenceDecay = calculateDivergenceDecay(signal, current);
        double momentumDecay = calculateMomentumDecay(signal, current);
        double volumeDecay = calculateVolumeDecay(signal, current);
        double sessionDecay = calculateSessionDecay(signal);

        // Update signal context with individual factors
        signal.setTimeDecay(timeDecay);
        signal.setTargetDecay(targetDecay);
        signal.setDivergenceDecay(divergenceDecay);
        signal.setMomentumDecay(momentumDecay);
        signal.setVolumeDecay(volumeDecay);
        signal.setSessionDecay(sessionDecay);

        // Calculate combined decay
        double combinedDecay = timeDecay * targetDecay * divergenceDecay
                * momentumDecay * volumeDecay * sessionDecay;

        // Clamp to reasonable range (allow boost up to 1.5 for strengthening signals)
        combinedDecay = Math.max(0.0, Math.min(1.5, combinedDecay));

        signal.setCombinedDecay(combinedDecay);

        // Update current score
        double currentScore = signal.getInitialScore() * combinedDecay;
        signal.setCurrentScore(currentScore);

        log.debug("[DECAY] {} | time={:.2f} | target={:.2f} | div={:.2f} | mom={:.2f} | " +
                        "vol={:.2f} | session={:.2f} | COMBINED={:.2f} | score={:.1f}→{:.1f}",
                signal.getScripCode(),
                timeDecay, targetDecay, divergenceDecay, momentumDecay,
                volumeDecay, sessionDecay, combinedDecay,
                signal.getInitialScore(), currentScore);

        return combinedDecay;
    }

    /**
     * Calculate time decay using half-life model.
     * Signal loses 50% value every [halfLifeMinutes] minutes.
     *
     * Formula: decay = 0.5 ^ (minutesElapsed / halfLifeMinutes)
     *
     * Examples (30 min half-life):
     * T+0 min:  decay = 1.00
     * T+15 min: decay = 0.71
     * T+30 min: decay = 0.50
     * T+60 min: decay = 0.25
     * T+90 min: decay = 0.125
     */
    public double calculateTimeDecay(SignalContext signal) {
        if (!config.isTimeDecayEnabled() || signal == null) {
            return 1.0;
        }

        double minutesElapsed = signal.getAgeMinutes();
        if (minutesElapsed <= 0) {
            return 1.0;
        }

        double halfLife = config.getTimeHalfLifeMinutes();
        if (halfLife <= 0) {
            return 1.0;
        }

        double decay = Math.pow(0.5, minutesElapsed / halfLife);

        // Floor at 0.1 to prevent complete zeroing
        return Math.max(0.1, decay);
    }

    /**
     * Calculate target achievement decay.
     * Aggressive decay after [targetThreshold]% of Target1 achieved.
     *
     * Premise: Once 25% of target reached, "easy money" is taken.
     *
     * Examples (25% threshold):
     * +0% achieved:  decay = 1.00
     * +10% achieved: decay = 0.95
     * +25% achieved: decay = 0.625 (threshold crossed)
     * +50% achieved: decay = 0.25
     * +75% achieved: decay = 0.10 (floor)
     */
    public double calculateTargetDecay(SignalContext signal, EnrichedQuantScore current) {
        if (!config.isTargetDecayEnabled() || signal == null) {
            return 1.0;
        }

        double targetAchievedPct = signal.getTarget1Progress();
        if (targetAchievedPct <= 0) {
            return 1.0;
        }

        // ATR-adaptive threshold
        double baseThreshold = config.getTargetThreshold();
        double atrThreshold = signal.getInitialAtr() > 0 ?
                (signal.getInitialAtr() * 1.5) / Math.abs(signal.getTarget1() - signal.getEntryPrice()) : 0;
        double adaptiveThreshold = Math.max(baseThreshold, atrThreshold);

        double decay;
        if (targetAchievedPct >= adaptiveThreshold) {
            // Aggressive decay after threshold
            decay = Math.max(0.1, 1.0 - (targetAchievedPct * config.getTargetDecayMultiplier()));
        } else {
            // Minimal decay before threshold
            decay = 1.0 - (targetAchievedPct * 0.5);
        }

        return Math.max(0.1, Math.min(1.0, decay));
    }

    /**
     * Calculate divergence resolution decay.
     * If divergence that created the signal has resolved, signal weakens.
     *
     * - divergenceRatio < 0: Divergence REVERSED → decay = 0 (signal invalid)
     * - divergenceRatio < 0.2: Mostly resolved → decay = 0.2
     * - divergenceRatio < 0.5: Significantly weakened → decay = 0.4
     * - divergenceRatio < 0.8: Moderately weakened → decay = 0.7
     * - divergenceRatio >= 0.8: Still strong → decay = 1.0
     */
    public double calculateDivergenceDecay(SignalContext signal, EnrichedQuantScore current) {
        if (!config.isDivergenceDecayEnabled() || signal == null) {
            return 1.0;
        }

        double initialMagnitude = signal.getInitialDivergenceMagnitude();
        if (initialMagnitude <= 0) {
            return 1.0; // No divergence to decay
        }

        // Get current divergence magnitude
        double currentMagnitude = getCurrentDivergenceMagnitude(current, signal);
        if (currentMagnitude < 0) {
            return 1.0; // Can't determine, no decay
        }

        double divergenceRatio = currentMagnitude / initialMagnitude;

        if (divergenceRatio < 0) {
            // Divergence REVERSED - signal should be invalid
            return 0.0;
        } else if (divergenceRatio < config.getDivergenceResolvedThreshold()) {
            // Divergence mostly resolved - heavy decay
            return 0.2;
        } else if (divergenceRatio < config.getDivergenceWeakThreshold()) {
            // Divergence significantly weakened
            return 0.4;
        } else if (divergenceRatio < 0.8) {
            // Divergence moderately weakened
            return 0.7;
        } else {
            // Divergence still strong
            return 1.0;
        }
    }

    /**
     * Calculate momentum exhaustion decay.
     * If momentum that supported the signal has reversed, signal weakens.
     *
     * - Momentum reversed → decay = 0.2
     * - Momentum exhausted (< 30% of initial) → decay = 0.4
     * - Momentum weakening (< 60% of initial) → decay = 0.7
     * - Momentum strengthening (> 120% of initial) → decay = 1.2 (BOOST!)
     */
    public double calculateMomentumDecay(SignalContext signal, EnrichedQuantScore current) {
        if (!config.isMomentumDecayEnabled() || signal == null) {
            return 1.0;
        }

        double initialSlope = signal.getInitialMomentumSlope();
        if (initialSlope == 0) {
            return 1.0; // No initial momentum
        }

        // Get current momentum slope
        double currentSlope = getCurrentMomentumSlope(current, signal);
        if (currentSlope == 0 && initialSlope != 0) {
            // Lost momentum
            return 0.6;
        }

        // Check for reversal (opposite signs)
        if (Math.signum(currentSlope) != Math.signum(initialSlope)) {
            // Momentum reversed - heavy decay
            signal.setMomentumReversalCandles(signal.getMomentumReversalCandles() + 1);
            return 0.2;
        } else {
            // Reset reversal counter
            signal.setMomentumReversalCandles(0);
        }

        // Check for exhaustion
        double slopeRatio = Math.abs(currentSlope) / Math.abs(initialSlope);
        if (slopeRatio < config.getMomentumExhaustionThreshold()) {
            return 0.4; // Momentum largely exhausted
        } else if (slopeRatio < 0.6) {
            return 0.7; // Momentum weakening
        } else if (slopeRatio > 1.2) {
            return 1.2; // Momentum strengthening (boost!)
        }

        return 1.0;
    }

    /**
     * Calculate volume decay.
     * If volume has dried up, signal loses conviction.
     */
    public double calculateVolumeDecay(SignalContext signal, EnrichedQuantScore current) {
        if (!config.isVolumeDecayEnabled() || signal == null) {
            return 1.0;
        }

        double initialVolume = signal.getInitialVolume();
        if (initialVolume <= 0) {
            return 1.0; // No initial volume tracked
        }

        // Get current volume
        double currentVolume = getCurrentVolume(current);
        if (currentVolume < 0) {
            return 1.0; // Can't determine, no decay
        }

        double volumeRatio = currentVolume / initialVolume;

        if (volumeRatio < config.getVolumeDriedThreshold()) {
            return config.getVolumeDriedDecayFactor();
        } else if (volumeRatio < 0.5) {
            return 0.8;
        } else if (volumeRatio > 1.5) {
            return 1.1; // Volume increasing - slight boost
        }

        return 1.0;
    }

    /**
     * Calculate session-based decay.
     * Signals decay faster during lunch hour and end of day.
     */
    public double calculateSessionDecay(SignalContext signal) {
        if (!config.isSessionDecayEnabled() || signal == null) {
            return 1.0;
        }

        // Get current time
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        int timeHHMM = now.getHour() * 100 + now.getMinute();

        double sessionMultiplier = config.getSessionDecayMultiplier(timeHHMM);

        if (sessionMultiplier > 1.0) {
            // Apply faster decay during bad sessions
            double baseTimeDecay = signal.getTimeDecay();
            // Return factor that will increase overall decay
            return 1.0 / sessionMultiplier;
        }

        return 1.0;
    }

    // ============ HELPER METHODS ============

    /**
     * Get current divergence magnitude from score.
     * Returns -1 if cannot be determined.
     */
    private double getCurrentDivergenceMagnitude(EnrichedQuantScore current, SignalContext signal) {
        if (current == null || current.getHistoricalContext() == null) {
            return -1;
        }

        double magnitude = 0;

        // GRACEFUL_DEGRADATION: Check PCR divergence from MTFDistribution evolution if available
        if (signal.isHasPcrDivergence()) {
            boolean hasPcrDiv = hasPcrDivergence(current);
            if (hasPcrDiv) {
                magnitude += 50;
            }
        }

        // GRACEFUL_DEGRADATION: Check OI divergence from MTFDistribution evolution if available
        if (signal.isHasOiDivergence()) {
            boolean hasOiDiv = hasOiDivergence(current);
            if (hasOiDiv) {
                magnitude += 50;
            }
        }

        return magnitude;
    }

    /**
     * GRACEFUL_DEGRADATION: Extract PCR divergence.
     * Returns true if any bullish/bearish flip detected in historical context.
     * Falls back to false if data not available.
     */
    private boolean hasPcrDivergence(EnrichedQuantScore score) {
        if (score == null || score.getHistoricalContext() == null) {
            return false;
        }
        // Check for any divergence signal from historical context
        // Use bullish/bearish flips as proxy for PCR divergence
        return score.getHistoricalContext().hasBullishFlip() ||
               score.getHistoricalContext().hasBearishFlip();
    }

    /**
     * GRACEFUL_DEGRADATION: Extract OI divergence.
     * Returns true if absorption or exhaustion detected.
     * Falls back to false if data not available.
     */
    private boolean hasOiDivergence(EnrichedQuantScore score) {
        if (score == null || score.getHistoricalContext() == null) {
            return false;
        }
        // Use absorption or exhaustion as proxy for OI divergence
        return score.getHistoricalContext().isAbsorptionDetected() ||
               score.getHistoricalContext().isSellingExhaustion() ||
               score.getHistoricalContext().isBuyingExhaustion();
    }

    /**
     * Get current momentum slope from score.
     * Returns 0 if cannot be determined.
     */
    private double getCurrentMomentumSlope(EnrichedQuantScore current, SignalContext signal) {
        if (current == null) {
            return 0;
        }

        // Try to get from OFI context
        if (current.getHistoricalContext() != null &&
                current.getHistoricalContext().getOfiContext() != null) {
            double zscore = current.getHistoricalContext().getOfiContext().getZscore();
            // Use zscore direction as momentum proxy
            if (signal.getDirection() == Direction.LONG) {
                return zscore; // Positive zscore = bullish momentum
            } else {
                return -zscore; // Negative zscore = bearish momentum
            }
        }

        // Fallback: use price movement direction
        if (current.getClose() > 0 && signal.getEntryPrice() > 0) {
            double priceChange = current.getClose() - signal.getEntryPrice();
            if (signal.getDirection() == Direction.LONG) {
                return priceChange > 0 ? 1.0 : -1.0;
            } else {
                return priceChange < 0 ? 1.0 : -1.0;
            }
        }

        return 0;
    }

    /**
     * Get current volume from score.
     * Returns -1 if cannot be determined.
     */
    private double getCurrentVolume(EnrichedQuantScore current) {
        if (current == null || current.getBaseScore() == null) {
            return -1;
        }

        // Try to extract volume from base score
        // This is a simplification - real implementation would track actual volume
        return 100; // Placeholder - would need actual volume tracking
    }

    /**
     * Quick decay check - returns true if signal is significantly decayed.
     */
    public boolean isSignificantlyDecayed(SignalContext signal) {
        if (signal == null) {
            return false;
        }
        return signal.getCombinedDecay() < config.getWeakeningThreshold();
    }

    /**
     * Quick check - returns true if signal is strengthening.
     */
    public boolean isStrengthening(SignalContext signal) {
        if (signal == null) {
            return false;
        }
        return signal.getCombinedDecay() > config.getStrengtheningThreshold();
    }

    /**
     * Quick check - returns true if signal should be invalidated due to decay.
     */
    public boolean shouldInvalidateDueToDecay(SignalContext signal) {
        if (signal == null) {
            return false;
        }
        return signal.getCombinedDecay() < config.getInvalidationFloor();
    }
}
