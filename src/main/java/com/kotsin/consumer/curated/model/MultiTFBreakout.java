package com.kotsin.consumer.curated.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MultiTFBreakout - Breakout confirmed across multiple timeframes
 * Requires 2 out of 3 timeframes (1m, 2m, 3m) to show breakout
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiTFBreakout {

    private String scripCode;
    private long timestamp;

    // Primary breakout (usually from 3m as most stable)
    private BreakoutBar primaryBreakout;

    // Individual timeframe breakouts (null if no breakout on that TF)
    private BreakoutBar breakout1m;
    private BreakoutBar breakout2m;
    private BreakoutBar breakout3m;

    // Confluence metrics
    private int confirmations;      // How many TFs confirmed (0-3)
    private double confluenceScore; // confirmations / 3.0 (0.0 to 1.0)

    // Consolidated pattern that led to breakout
    private ConsolidationPattern pattern;

    /**
     * Check if breakout is valid (minimum 2 out of 3 TFs)
     */
    public boolean isValid() {
        return confirmations >= 2;
    }

    /**
     * Get average volume Z-score across all confirmed TFs
     */
    public double getAvgVolumeZScore() {
        double sum = 0;
        int count = 0;

        if (breakout1m != null) {
            sum += breakout1m.getVolumeZScore();
            count++;
        }
        if (breakout2m != null) {
            sum += breakout2m.getVolumeZScore();
            count++;
        }
        if (breakout3m != null) {
            sum += breakout3m.getVolumeZScore();
            count++;
        }

        return count > 0 ? sum / count : 0;
    }

    /**
     * Get average Kyle's Lambda across all confirmed TFs
     */
    public double getAvgKyleLambda() {
        double sum = 0;
        int count = 0;

        if (breakout1m != null) {
            sum += breakout1m.getKyleLambda();
            count++;
        }
        if (breakout2m != null) {
            sum += breakout2m.getKyleLambda();
            count++;
        }
        if (breakout3m != null) {
            sum += breakout3m.getKyleLambda();
            count++;
        }

        return count > 0 ? sum / count : 0;
    }
}
