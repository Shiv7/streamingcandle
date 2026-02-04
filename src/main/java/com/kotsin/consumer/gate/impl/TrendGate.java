package com.kotsin.consumer.gate.impl;

import com.kotsin.consumer.gate.SignalGate;
import com.kotsin.consumer.gate.model.GateResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * TrendGate - Validates trend alignment for signals.
 */
@Component
public class TrendGate implements SignalGate {

    @Override
    public String getName() {
        return "TREND";
    }

    @Override
    public double getWeight() {
        return 0.20;  // 20% weight
    }

    @Override
    public boolean isRequired() {
        return true;  // Trend alignment is required
    }

    @Override
    public GateResult evaluate(Map<String, Object> context) {
        String signalDirection = (String) context.get("signalDirection"); // LONG or SHORT
        Boolean aboveEma20 = (Boolean) context.get("aboveEma20");
        Boolean aboveEma50 = (Boolean) context.get("aboveEma50");
        Boolean superTrendBullish = (Boolean) context.get("superTrendBullish");
        String htfTrend = (String) context.get("htfTrend"); // BULLISH, BEARISH, NEUTRAL

        if (signalDirection == null) {
            return GateResult.fail(getName(), getWeight(), "Signal direction not specified");
        }

        boolean isLong = "LONG".equalsIgnoreCase(signalDirection);
        int alignmentCount = 0;
        int totalChecks = 0;

        // Check EMA 20
        if (aboveEma20 != null) {
            totalChecks++;
            if ((isLong && aboveEma20) || (!isLong && !aboveEma20)) {
                alignmentCount++;
            }
        }

        // Check EMA 50
        if (aboveEma50 != null) {
            totalChecks++;
            if ((isLong && aboveEma50) || (!isLong && !aboveEma50)) {
                alignmentCount++;
            }
        }

        // Check SuperTrend
        if (superTrendBullish != null) {
            totalChecks++;
            if ((isLong && superTrendBullish) || (!isLong && !superTrendBullish)) {
                alignmentCount++;
            }
        }

        // Check HTF trend
        if (htfTrend != null) {
            totalChecks++;
            boolean htfAligned = (isLong && "BULLISH".equalsIgnoreCase(htfTrend)) ||
                                 (!isLong && "BEARISH".equalsIgnoreCase(htfTrend));
            if (htfAligned) {
                alignmentCount++;
            }
        }

        if (totalChecks == 0) {
            return GateResult.fail(getName(), getWeight(), "No trend data available");
        }

        double alignmentPercent = (double) alignmentCount / totalChecks * 100;

        if (alignmentPercent < 50) {
            return GateResult.fail(getName(), getWeight(),
                String.format("Counter-trend signal: only %.0f%% aligned", alignmentPercent));
        }

        double score;
        String reason;

        if (alignmentPercent >= 100) {
            score = 100;
            reason = "Perfect trend alignment";
        } else if (alignmentPercent >= 75) {
            score = 80;
            reason = String.format("Strong trend alignment: %.0f%%", alignmentPercent);
        } else {
            score = 60;
            reason = String.format("Partial trend alignment: %.0f%%", alignmentPercent);
        }

        GateResult result = GateResult.pass(getName(), score, getWeight(), reason);
        result.getDetails().put("alignmentPercent", alignmentPercent);
        result.getDetails().put("alignedIndicators", alignmentCount);
        result.getDetails().put("totalIndicators", totalChecks);
        return result;
    }
}
