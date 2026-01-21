package com.kotsin.consumer.signal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * SignalDecayConfig - Configurable thresholds for signal decay and invalidation.
 *
 * All thresholds can be tuned via application.properties:
 * signal.decay.time.halflife.minutes=30
 * signal.decay.target.threshold=0.25
 * etc.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "signal.decay")
public class SignalDecayConfig {

    // ============ TIME DECAY ============

    /**
     * Half-life for time decay in minutes.
     * Signal loses 50% value every this many minutes.
     * Default: 30 minutes
     */
    private double timeHalfLifeMinutes = 30.0;

    /**
     * Maximum signal age in minutes before auto-invalidation.
     * Default: 60 minutes
     */
    private int maxSignalAgeMinutes = 60;

    // ============ TARGET DECAY ============

    /**
     * Target progress threshold for aggressive decay.
     * Once this % of target1 is achieved, decay accelerates.
     * Default: 25% (0.25)
     */
    private double targetThreshold = 0.25;

    /**
     * Target decay multiplier after threshold.
     * Higher = more aggressive decay.
     * Default: 1.5
     */
    private double targetDecayMultiplier = 1.5;

    // ============ DIVERGENCE DECAY ============

    /**
     * Divergence resolved threshold.
     * If divergence falls below this % of initial, signal weakens significantly.
     * Default: 20% (0.20)
     */
    private double divergenceResolvedThreshold = 0.20;

    /**
     * Divergence weak threshold.
     * If divergence falls below this % of initial, signal weakens.
     * Default: 50% (0.50)
     */
    private double divergenceWeakThreshold = 0.50;

    // ============ MOMENTUM DECAY ============

    /**
     * Number of candles of momentum reversal before invalidation.
     * Default: 3 candles
     */
    private int momentumReversalCandles = 3;

    /**
     * Momentum exhaustion threshold (slope ratio).
     * If current slope < initial * this, momentum is exhausted.
     * Default: 30% (0.30)
     */
    private double momentumExhaustionThreshold = 0.30;

    // ============ VOLUME DECAY ============

    /**
     * Volume dried threshold.
     * If volume falls below this % of initial, signal weakens.
     * Default: 30% (0.30)
     */
    private double volumeDriedThreshold = 0.30;

    /**
     * Volume dried decay factor.
     * Default: 0.6 (40% penalty)
     */
    private double volumeDriedDecayFactor = 0.6;

    // ============ SESSION DECAY ============

    /**
     * Lunch hour decay multiplier (faster decay during 12:00-13:30).
     * Default: 1.5 (50% faster decay)
     */
    private double lunchHourDecayMultiplier = 1.5;

    /**
     * End of day decay multiplier (faster decay after 15:00).
     * Default: 2.0 (100% faster decay)
     */
    private double endOfDayDecayMultiplier = 2.0;

    // ============ INVALIDATION THRESHOLDS ============

    /**
     * Combined decay floor for invalidation.
     * If combined decay falls below this, signal is invalid.
     * Default: 15% (0.15)
     */
    private double invalidationFloor = 0.15;

    /**
     * Strengthening threshold.
     * If combined decay > initial * this, signal is strengthening.
     * Default: 110% (1.10)
     */
    private double strengtheningThreshold = 1.10;

    /**
     * Weakening threshold.
     * If combined decay < initial * this, signal is weakening.
     * Default: 50% (0.50)
     */
    private double weakeningThreshold = 0.50;

    /**
     * Price movement threshold in ATR units.
     * If price moves more than this many ATRs without entry, invalidate.
     * Default: 3.0 ATR
     */
    private double priceMovementAtrThreshold = 3.0;

    // ============ FEATURE FLAGS ============

    /**
     * Enable/disable signal decay system.
     * Default: true
     */
    private boolean enabled = true;

    /**
     * Enable time decay.
     * Default: true
     */
    private boolean timeDecayEnabled = true;

    /**
     * Enable target decay.
     * Default: true
     */
    private boolean targetDecayEnabled = true;

    /**
     * Enable divergence decay.
     * Default: true
     */
    private boolean divergenceDecayEnabled = true;

    /**
     * Enable momentum decay.
     * Default: true
     */
    private boolean momentumDecayEnabled = true;

    /**
     * Enable volume decay.
     * Default: true
     */
    private boolean volumeDecayEnabled = true;

    /**
     * Enable session decay.
     * Default: true
     */
    private boolean sessionDecayEnabled = true;

    // ============ SESSION TIME BOUNDARIES ============

    /**
     * Morning session start (HHMM format).
     * Default: 0915 (9:15 AM)
     */
    private int morningSessionStart = 915;

    /**
     * Morning session end (HHMM format).
     * Default: 1200 (12:00 PM)
     */
    private int morningSessionEnd = 1200;

    /**
     * Lunch session start (HHMM format).
     * Default: 1200 (12:00 PM)
     */
    private int lunchSessionStart = 1200;

    /**
     * Lunch session end (HHMM format).
     * Default: 1330 (1:30 PM)
     */
    private int lunchSessionEnd = 1330;

    /**
     * Afternoon session start (HHMM format).
     * Default: 1330 (1:30 PM)
     */
    private int afternoonSessionStart = 1330;

    /**
     * Afternoon session end (HHMM format).
     * Default: 1500 (3:00 PM)
     */
    private int afternoonSessionEnd = 1500;

    /**
     * Closing session start (HHMM format).
     * Default: 1500 (3:00 PM)
     */
    private int closingSessionStart = 1500;

    /**
     * Closing session end (HHMM format).
     * Default: 1530 (3:30 PM)
     */
    private int closingSessionEnd = 1530;

    // ============ HELPER METHODS ============

    /**
     * Get current session based on time
     */
    public String getCurrentSession(int timeHHMM) {
        if (timeHHMM < morningSessionStart) return "PRE_MARKET";
        if (timeHHMM < morningSessionEnd) return "MORNING";
        if (timeHHMM < lunchSessionEnd) return "LUNCH";
        if (timeHHMM < afternoonSessionEnd) return "AFTERNOON";
        if (timeHHMM < closingSessionEnd) return "CLOSING";
        return "POST_MARKET";
    }

    /**
     * Check if current time is in avoid period (lunch, opening, closing)
     */
    public boolean isAvoidPeriod(int timeHHMM) {
        // Opening noise (first 5 minutes)
        if (timeHHMM >= 915 && timeHHMM <= 920) return true;

        // Lunch hour
        if (timeHHMM >= lunchSessionStart && timeHHMM <= lunchSessionEnd) return true;

        // Closing chaos
        if (timeHHMM >= closingSessionStart) return true;

        return false;
    }

    /**
     * Check if current time is prime trading time
     */
    public boolean isPrimeTime(int timeHHMM) {
        // Morning momentum: 9:30 - 11:30
        if (timeHHMM >= 930 && timeHHMM <= 1130) return true;

        // Afternoon momentum: 14:00 - 15:00
        if (timeHHMM >= 1400 && timeHHMM <= 1500) return true;

        return false;
    }

    /**
     * Get session decay multiplier for current time
     */
    public double getSessionDecayMultiplier(int timeHHMM) {
        if (timeHHMM >= lunchSessionStart && timeHHMM <= lunchSessionEnd) {
            return lunchHourDecayMultiplier;
        }
        if (timeHHMM >= closingSessionStart) {
            return endOfDayDecayMultiplier;
        }
        return 1.0;
    }
}
