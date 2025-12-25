package com.kotsin.consumer.regime.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RegimeLabel - Enum for market regime classification
 * 
 * Used by Index Regime and Security Regime modules
 */
public enum RegimeLabel {
    STRONG_BULL(2, "Strong upward momentum with institutional participation"),
    WEAK_BULL(1, "Moderate upward bias, watch for reversal"),
    NEUTRAL(0, "No clear directional bias, range-bound or transition"),
    WEAK_BEAR(-1, "Moderate downward bias, watch for reversal"),
    STRONG_BEAR(-2, "Strong downward momentum with institutional participation");

    private final int value;
    private final String description;

    RegimeLabel(int value, String description) {
        this.value = value;
        this.description = description;
    }

    public int getValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }

    public boolean isBullish() {
        return value > 0;
    }

    public boolean isBearish() {
        return value < 0;
    }

    public boolean isNeutral() {
        return value == 0;
    }

    /**
     * Determine regime label from strength score and direction
     * 
     * @param strength 0-1 score
     * @param direction +1 = bullish, -1 = bearish, 0 = neutral
     * @return RegimeLabel
     */
    public static RegimeLabel fromStrengthAndDirection(double strength, int direction) {
        if (direction == 0 || strength < 0.3) {
            return NEUTRAL;
        }
        
        if (direction > 0) {
            return strength >= 0.6 ? STRONG_BULL : WEAK_BULL;
        } else {
            return strength >= 0.6 ? STRONG_BEAR : WEAK_BEAR;
        }
    }
}
