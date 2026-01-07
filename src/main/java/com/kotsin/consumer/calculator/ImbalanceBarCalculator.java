package com.kotsin.consumer.calculator;

import com.kotsin.consumer.config.CalculatorConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ImbalanceBarCalculator - Calculates imbalance bars (VIB, DIB, TRB, VRB)
 *
 * EXTRACTED FROM: EnrichedCandlestick (SRP violation fix)
 *
 * Imbalance Bars detect order flow imbalances:
 * - VIB (Volume Imbalance Bar): Cumulative signed volume exceeds threshold
 * - DIB (Dollar Imbalance Bar): Cumulative signed dollar volume exceeds threshold
 * - TRB (Tick Run Bar): Consecutive same-direction ticks exceed threshold
 * - VRB (Volume Run Bar): Consecutive same-direction volume exceeds threshold
 *
 * Uses EWMA (Exponentially Weighted Moving Average) for adaptive thresholds
 *
 * Thread-safety: NOT thread-safe (stateful, use one instance per candle/window)
 */
@Component
@Slf4j
public class ImbalanceBarCalculator {

    private final CalculatorConfig calculatorConfig;

    @Autowired
    public ImbalanceBarCalculator(CalculatorConfig calculatorConfig) {
        this.calculatorConfig = calculatorConfig;
    }

    /**
     * Imbalance Bar State (per candle/window)
     */
    @Data
    public static class ImbalanceState {
        // Current imbalances
        private long volumeImbalance = 0L;
        private double dollarImbalance = 0.0;
        private int tickRuns = 0;
        private long volumeRuns = 0L;

        // Current direction and run tracking
        private String currentDirection = "NEUTRAL";
        private int currentRunLength = 0;
        private long currentVolumeRun = 0L;

        // Expected thresholds (adaptive via EWMA)
        private double expectedVolumeImbalance;
        private double expectedDollarImbalance;
        private double expectedTickRuns;
        private double expectedVolumeRuns;

        // EWMA state for adaptive thresholds
        private double vibEwmaMean = 0.0;
        private double vibEwmaSq = 0.0;
        private double dibEwmaMean = 0.0;
        private double dibEwmaSq = 0.0;
        private double trbEwmaMean = 0.0;
        private double trbEwmaSq = 0.0;
        private double vrbEwmaMean = 0.0;
        private double vrbEwmaSq = 0.0;

        // Bar emission tracking
        private boolean vibTriggered = false;
        private boolean dibTriggered = false;
        private boolean trbTriggered = false;
        private boolean vrbTriggered = false;

        private long lastVibTriggerTime = 0L;
        private long lastDibTriggerTime = 0L;
        private long lastTrbTriggerTime = 0L;
        private long lastVrbTriggerTime = 0L;

        /**
         * Initialize with configured defaults
         */
        public ImbalanceState(CalculatorConfig.ImbalanceBarConfig config) {
            this.expectedVolumeImbalance = config.getInitVolumeImbalance();
            this.expectedDollarImbalance = config.getInitDollarImbalance();
            this.expectedTickRuns = config.getInitTickRuns();
            this.expectedVolumeRuns = config.getInitVolumeRuns();
        }

        /**
         * Reset triggered flags (call after processing)
         */
        public void resetTriggers() {
            vibTriggered = false;
            dibTriggered = false;
            trbTriggered = false;
            vrbTriggered = false;
        }

        /**
         * Merge another imbalance state (for aggregating candles)
         */
        public void merge(ImbalanceState other) {
            this.volumeImbalance += other.volumeImbalance;
            this.dollarImbalance += other.dollarImbalance;

            // Propagate triggers
            this.vibTriggered = this.vibTriggered || other.vibTriggered;
            this.dibTriggered = this.dibTriggered || other.dibTriggered;
            this.trbTriggered = this.trbTriggered || other.trbTriggered;
            this.vrbTriggered = this.vrbTriggered || other.vrbTriggered;

            this.lastVibTriggerTime = Math.max(this.lastVibTriggerTime, other.lastVibTriggerTime);
            this.lastDibTriggerTime = Math.max(this.lastDibTriggerTime, other.lastDibTriggerTime);
            this.lastTrbTriggerTime = Math.max(this.lastTrbTriggerTime, other.lastTrbTriggerTime);
            this.lastVrbTriggerTime = Math.max(this.lastVrbTriggerTime, other.lastVrbTriggerTime);

            // Merge EWMA stats (weighted average)
            // Simplified: just propagate from other if we don't have observations
            if (this.vibEwmaMean == 0.0 && other.vibEwmaMean > 0.0) {
                this.vibEwmaMean = other.vibEwmaMean;
                this.vibEwmaSq = other.vibEwmaSq;
            }
            if (this.dibEwmaMean == 0.0 && other.dibEwmaMean > 0.0) {
                this.dibEwmaMean = other.dibEwmaMean;
                this.dibEwmaSq = other.dibEwmaSq;
            }
            if (this.trbEwmaMean == 0.0 && other.trbEwmaMean > 0.0) {
                this.trbEwmaMean = other.trbEwmaMean;
                this.trbEwmaSq = other.trbEwmaSq;
            }
            if (this.vrbEwmaMean == 0.0 && other.vrbEwmaMean > 0.0) {
                this.vrbEwmaMean = other.vrbEwmaMean;
                this.vrbEwmaSq = other.vrbEwmaSq;
            }
        }
    }

    /**
     * Update imbalance bars with new trade
     *
     * @param state Current imbalance state
     * @param price Trade price
     * @param deltaVolume Trade volume
     * @param isBuy True if buy, false if sell
     * @param eventTime Event timestamp
     */
    public void updateImbalanceBars(ImbalanceState state, double price, int deltaVolume, boolean isBuy, long eventTime) {
        String direction = isBuy ? "BUY" : "SELL";
        int directionSign = isBuy ? 1 : -1;

        // Volume Imbalance (VIB) - cumulative signed volume
        long signedVolume = deltaVolume * directionSign;
        state.volumeImbalance += signedVolume;

        // Dollar Imbalance (DIB) - cumulative signed dollar volume
        double dollarVolume = (double) deltaVolume * price;
        state.dollarImbalance += dollarVolume * directionSign;

        // Tick Runs (TRB) - count CONSECUTIVE same-direction ticks
        if (direction.equals(state.currentDirection)) {
            state.currentRunLength++;
        } else {
            // Direction changed - add completed run to total
            state.tickRuns += state.currentRunLength;
            state.currentRunLength = 1;
            state.currentDirection = direction;
        }

        // Volume Runs (VRB) - volume in CONSECUTIVE same-direction trades
        if (direction.equals(state.currentDirection)) {
            state.currentVolumeRun += deltaVolume;
        } else {
            state.volumeRuns += state.currentVolumeRun;
            state.currentVolumeRun = deltaVolume;
        }

        // Check thresholds and update EWMA
        checkAndUpdateThresholds(state, eventTime);
    }

    /**
     * Check imbalance bar thresholds and update EWMA estimates
     */
    private void checkAndUpdateThresholds(ImbalanceState state, long eventTime) {
        long currentTime = eventTime > 0 ? eventTime : System.currentTimeMillis();
        CalculatorConfig.ImbalanceBarConfig config = calculatorConfig.getImbalanceBar();

        // Update EWMA for each imbalance type
        updateVibEwma(state, Math.abs((double) state.volumeImbalance), config.getEwmaAlpha());
        updateDibEwma(state, Math.abs(state.dollarImbalance), config.getEwmaAlpha());
        updateTrbEwma(state, Math.abs((double) state.tickRuns), config.getEwmaAlpha());
        updateVrbEwma(state, Math.abs((double) state.volumeRuns), config.getEwmaAlpha());

        // Calculate thresholds from updated EWMA
        state.expectedVolumeImbalance = getQuantileThreshold(
            state.vibEwmaMean, state.vibEwmaSq,
            config.getInitVolumeImbalance(), config.getZScoreThreshold()
        );
        state.expectedDollarImbalance = getQuantileThreshold(
            state.dibEwmaMean, state.dibEwmaSq,
            config.getInitDollarImbalance(), config.getZScoreThreshold()
        );
        state.expectedTickRuns = getQuantileThreshold(
            state.trbEwmaMean, state.trbEwmaSq,
            config.getInitTickRuns(), config.getZScoreThreshold()
        );
        state.expectedVolumeRuns = getQuantileThreshold(
            state.vrbEwmaMean, state.vrbEwmaSq,
            config.getInitVolumeRuns(), config.getZScoreThreshold()
        );

        // VIB threshold check
        if (Math.abs(state.volumeImbalance) >= state.expectedVolumeImbalance) {
            state.vibTriggered = true;
            state.lastVibTriggerTime = currentTime;
            state.volumeImbalance = 0L;
        }

        // DIB threshold check
        if (Math.abs(state.dollarImbalance) >= state.expectedDollarImbalance) {
            state.dibTriggered = true;
            state.lastDibTriggerTime = currentTime;
            state.dollarImbalance = 0.0;
        }

        // TRB threshold check
        if (Math.abs(state.tickRuns) >= state.expectedTickRuns) {
            state.trbTriggered = true;
            state.lastTrbTriggerTime = currentTime;
            state.tickRuns = 0;
        }

        // VRB threshold check
        if (Math.abs(state.volumeRuns) >= state.expectedVolumeRuns) {
            state.vrbTriggered = true;
            state.lastVrbTriggerTime = currentTime;
            state.volumeRuns = 0L;
        }
    }

    /**
     * Update EWMA for VIB
     */
    private void updateVibEwma(ImbalanceState state, double x, double alpha) {
        state.vibEwmaMean = alpha * x + (1 - alpha) * state.vibEwmaMean;
        state.vibEwmaSq = alpha * x * x + (1 - alpha) * state.vibEwmaSq;
    }

    /**
     * Update EWMA for DIB
     */
    private void updateDibEwma(ImbalanceState state, double x, double alpha) {
        state.dibEwmaMean = alpha * x + (1 - alpha) * state.dibEwmaMean;
        state.dibEwmaSq = alpha * x * x + (1 - alpha) * state.dibEwmaSq;
    }

    /**
     * Update EWMA for TRB
     */
    private void updateTrbEwma(ImbalanceState state, double x, double alpha) {
        state.trbEwmaMean = alpha * x + (1 - alpha) * state.trbEwmaMean;
        state.trbEwmaSq = alpha * x * x + (1 - alpha) * state.trbEwmaSq;
    }

    /**
     * Update EWMA for VRB
     */
    private void updateVrbEwma(ImbalanceState state, double x, double alpha) {
        state.vrbEwmaMean = alpha * x + (1 - alpha) * state.vrbEwmaMean;
        state.vrbEwmaSq = alpha * x * x + (1 - alpha) * state.vrbEwmaSq;
    }

    /**
     * Calculate quantile threshold: mean + z-score * sigma
     */
    private double getQuantileThreshold(double mean, double sqMean, double floor, double zScore) {
        double var = Math.max(0.0, sqMean - mean * mean);
        double sigma = Math.sqrt(var);
        double q = mean + zScore * sigma;
        return Math.max(floor, q);
    }
}
