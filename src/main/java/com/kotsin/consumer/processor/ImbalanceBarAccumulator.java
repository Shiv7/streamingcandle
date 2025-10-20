package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.ImbalanceBarData;
import com.kotsin.consumer.model.TickData;
import lombok.Data;

/**
 * Accumulator for imbalance bars (VIB, DIB, TRB, VRB)
 * Implements adaptive thresholds using EWMA
 */
@Data
public class ImbalanceBarAccumulator {
    // Current imbalance accumulators
    private Long volumeImbalance = 0L;
    private Long dollarImbalance = 0L;
    private Integer tickRuns = 0;
    private Long volumeRuns = 0L;

    // Direction tracking
    private String currentDirection = "NEUTRAL";
    private Double lastPrice = null;

    // Expected thresholds (EWMA-based)
    private Double expectedVolumeImbalance = 1000.0;  // Initial estimate
    private Double expectedDollarImbalance = 100000.0;  // Initial estimate
    private Double expectedTickRuns = 10.0;  // Initial estimate
    private Double expectedVolumeRuns = 5000.0;  // Initial estimate

    // EWMA alpha for threshold adaptation
    private static final double EWMA_ALPHA = 0.1;

    // Counters for bar completions
    private int vibCount = 0;
    private int dibCount = 0;
    private int trbCount = 0;
    private int vrbCount = 0;

    public void addTick(TickData tick) {
        if (tick.getDeltaVolume() == null || tick.getDeltaVolume() == 0) {
            return;
        }

        // Determine tick direction using tick rule
        String direction = determineDirection(tick);
        int directionSign = "BUY".equals(direction) ? 1 : -1;

        // Volume Imbalance (VIB)
        long signedVolume = tick.getDeltaVolume() * directionSign;
        volumeImbalance += signedVolume;

        // Dollar Imbalance (DIB)
        long dollarVolume = (long)(tick.getDeltaVolume() * tick.getLastRate());
        dollarImbalance += dollarVolume * directionSign;

        // Tick Runs (TRB)
        if (direction.equals(currentDirection)) {
            tickRuns++;
        } else {
            tickRuns = 1;
            currentDirection = direction;
        }

        // Volume Runs (VRB)
        if (direction.equals(currentDirection)) {
            volumeRuns += tick.getDeltaVolume().longValue();
        } else {
            volumeRuns = tick.getDeltaVolume().longValue();
        }

        lastPrice = tick.getLastRate();

        // Check thresholds and update EWMA estimates
        checkAndUpdateThresholds();
    }

    private String determineDirection(TickData tick) {
        if (lastPrice == null) {
            return "NEUTRAL";
        }

        double currentPrice = tick.getLastRate();
        if (currentPrice > lastPrice) {
            return "BUY";
        } else if (currentPrice < lastPrice) {
            return "SELL";
        } else {
            return currentDirection;  // No change, keep current direction
        }
    }

    private void checkAndUpdateThresholds() {
        // Volume Imbalance Bar threshold
        if (Math.abs(volumeImbalance) >= expectedVolumeImbalance) {
            // Update EWMA estimate
            expectedVolumeImbalance = EWMA_ALPHA * Math.abs(volumeImbalance)
                                    + (1 - EWMA_ALPHA) * expectedVolumeImbalance;
            vibCount++;
            volumeImbalance = 0L;  // Reset after bar emission
        }

        // Dollar Imbalance Bar threshold
        if (Math.abs(dollarImbalance) >= expectedDollarImbalance) {
            expectedDollarImbalance = EWMA_ALPHA * Math.abs(dollarImbalance)
                                    + (1 - EWMA_ALPHA) * expectedDollarImbalance;
            dibCount++;
            dollarImbalance = 0L;
        }

        // Tick Runs Bar threshold
        if (Math.abs(tickRuns) >= expectedTickRuns) {
            expectedTickRuns = EWMA_ALPHA * Math.abs(tickRuns)
                             + (1 - EWMA_ALPHA) * expectedTickRuns;
            trbCount++;
            tickRuns = 0;
        }

        // Volume Runs Bar threshold
        if (Math.abs(volumeRuns) >= expectedVolumeRuns) {
            expectedVolumeRuns = EWMA_ALPHA * Math.abs(volumeRuns)
                               + (1 - EWMA_ALPHA) * expectedVolumeRuns;
            vrbCount++;
            volumeRuns = 0L;
        }
    }

    public ImbalanceBarData toImbalanceBarData() {
        return ImbalanceBarData.create(
            volumeImbalance, dollarImbalance, tickRuns, volumeRuns,
            currentDirection, expectedVolumeImbalance, expectedDollarImbalance,
            expectedTickRuns, expectedVolumeRuns
        );
    }
}
