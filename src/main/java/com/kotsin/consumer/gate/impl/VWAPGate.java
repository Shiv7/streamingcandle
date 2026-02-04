package com.kotsin.consumer.gate.impl;

import com.kotsin.consumer.gate.SignalGate;
import com.kotsin.consumer.gate.model.GateResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * VWAPGate - Validates VWAP alignment for signals.
 *
 * Checks:
 * - Price position relative to VWAP
 * - Signal direction alignment with VWAP
 * - Distance from VWAP bands
 */
@Component
public class VWAPGate implements SignalGate {

    private static final double NEAR_VWAP_THRESHOLD = 0.3;  // 0.3% from VWAP considered "at VWAP"

    @Override
    public String getName() {
        return "VWAP";
    }

    @Override
    public double getWeight() {
        return 0.10;  // 10% weight
    }

    @Override
    public boolean isRequired() {
        return false;
    }

    @Override
    public GateResult evaluate(Map<String, Object> context) {
        String direction = (String) context.get("signalDirection");
        Double price = (Double) context.get("currentPrice");
        Double vwap = (Double) context.get("vwap");
        Boolean aboveVwap = (Boolean) context.get("aboveVwap");

        if (direction == null) {
            return GateResult.fail(getName(), getWeight(), "Signal direction not specified");
        }

        // If we have the boolean flag, use it
        if (aboveVwap != null) {
            boolean isLong = "LONG".equalsIgnoreCase(direction);

            if ((isLong && aboveVwap) || (!isLong && !aboveVwap)) {
                return GateResult.pass(getName(), 80, getWeight(),
                    isLong ? "Price above VWAP - bullish alignment" : "Price below VWAP - bearish alignment");
            } else {
                // Counter-VWAP trade - not a failure but reduced score
                return GateResult.pass(getName(), 50, getWeight(),
                    isLong ? "Long signal below VWAP - counter-VWAP entry" :
                            "Short signal above VWAP - counter-VWAP entry");
            }
        }

        // Calculate from price and VWAP
        if (price == null || vwap == null || vwap == 0) {
            return GateResult.fail(getName(), getWeight(), "VWAP data not available");
        }

        boolean isLong = "LONG".equalsIgnoreCase(direction);
        double distancePercent = (price - vwap) / vwap * 100;
        boolean priceAboveVwap = distancePercent > 0;
        double absDistance = Math.abs(distancePercent);

        double score;
        String reason;

        // Perfect alignment
        if ((isLong && priceAboveVwap) || (!isLong && !priceAboveVwap)) {
            if (absDistance <= NEAR_VWAP_THRESHOLD) {
                score = 100;
                reason = String.format("Price at VWAP (%.2f%%) - excellent entry point", distancePercent);
            } else if (absDistance <= 1.0) {
                score = 90;
                reason = String.format("Price near VWAP (%.2f%%) with trend alignment", distancePercent);
            } else if (absDistance <= 2.0) {
                score = 75;
                reason = String.format("Price %.2f%% from VWAP, aligned with signal", distancePercent);
            } else {
                score = 60;
                reason = String.format("Price extended from VWAP (%.2f%%) but aligned", distancePercent);
            }
        }
        // Counter-VWAP (price against signal direction)
        else {
            if (absDistance <= NEAR_VWAP_THRESHOLD) {
                score = 70;
                reason = String.format("Price at VWAP (%.2f%%) - acceptable counter-VWAP", distancePercent);
            } else if (absDistance <= 1.0) {
                score = 50;
                reason = String.format("Counter-VWAP entry (%.2f%%) - higher risk", distancePercent);
            } else {
                score = 40;
                reason = String.format("Significant counter-VWAP (%.2f%%) - proceed with caution", distancePercent);
            }
        }

        GateResult result = GateResult.pass(getName(), score, getWeight(), reason);
        result.getDetails().put("distanceFromVwap", distancePercent);
        result.getDetails().put("vwap", vwap);
        result.getDetails().put("price", price);
        result.getDetails().put("aboveVwap", priceAboveVwap);
        return result;
    }
}
