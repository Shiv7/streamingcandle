package com.kotsin.consumer.gate.impl;

import com.kotsin.consumer.gate.SignalGate;
import com.kotsin.consumer.gate.model.GateResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MomentumGate - Validates momentum alignment for signals.
 *
 * Checks:
 * - RSI not in extreme zone against signal direction
 * - MACD histogram alignment
 * - Momentum score alignment
 */
@Component
public class MomentumGate implements SignalGate {

    private static final double RSI_OVERBOUGHT = 75;
    private static final double RSI_OVERSOLD = 25;
    private static final double RSI_BULLISH_MIN = 45;
    private static final double RSI_BEARISH_MAX = 55;

    @Override
    public String getName() {
        return "MOMENTUM";
    }

    @Override
    public double getWeight() {
        return 0.15;  // 15% weight
    }

    @Override
    public boolean isRequired() {
        return false;
    }

    @Override
    public GateResult evaluate(Map<String, Object> context) {
        String direction = (String) context.get("signalDirection");
        Double rsi = (Double) context.get("rsi");
        Double macdHistogram = (Double) context.get("macdHistogram");
        Double momentumScore = (Double) context.get("momentumScore");

        if (direction == null) {
            return GateResult.fail(getName(), getWeight(), "Signal direction not specified");
        }

        boolean isLong = "LONG".equalsIgnoreCase(direction);
        int alignmentScore = 0;
        int checks = 0;
        StringBuilder reasons = new StringBuilder();

        // RSI Check
        if (rsi != null) {
            checks++;
            if (isLong) {
                if (rsi > RSI_OVERBOUGHT) {
                    // Long signal with overbought RSI - dangerous
                    return GateResult.fail(getName(), getWeight(),
                        String.format("RSI overbought (%.1f) - avoid long entry", rsi));
                }
                if (rsi >= RSI_BULLISH_MIN) {
                    alignmentScore++;
                    reasons.append("RSI bullish; ");
                }
            } else {
                if (rsi < RSI_OVERSOLD) {
                    // Short signal with oversold RSI - dangerous
                    return GateResult.fail(getName(), getWeight(),
                        String.format("RSI oversold (%.1f) - avoid short entry", rsi));
                }
                if (rsi <= RSI_BEARISH_MAX) {
                    alignmentScore++;
                    reasons.append("RSI bearish; ");
                }
            }
        }

        // MACD Check
        if (macdHistogram != null) {
            checks++;
            boolean macdBullish = macdHistogram > 0;
            if ((isLong && macdBullish) || (!isLong && !macdBullish)) {
                alignmentScore++;
                reasons.append("MACD aligned; ");
            }
        }

        // Momentum Score Check
        if (momentumScore != null) {
            checks++;
            boolean momentumBullish = momentumScore > 0;
            if ((isLong && momentumBullish) || (!isLong && !momentumBullish)) {
                alignmentScore++;
                reasons.append("Momentum aligned; ");
            }
        }

        if (checks == 0) {
            return GateResult.fail(getName(), getWeight(), "No momentum data available");
        }

        double alignmentPercent = (double) alignmentScore / checks * 100;

        if (alignmentPercent < 33) {
            return GateResult.fail(getName(), getWeight(),
                String.format("Momentum against signal: only %.0f%% aligned", alignmentPercent));
        }

        double score;
        String reason;

        if (alignmentPercent >= 100) {
            score = 100;
            reason = "Perfect momentum alignment: " + reasons.toString().trim();
        } else if (alignmentPercent >= 66) {
            score = 80;
            reason = "Good momentum alignment: " + reasons.toString().trim();
        } else {
            score = 60;
            reason = "Partial momentum alignment: " + reasons.toString().trim();
        }

        GateResult result = GateResult.pass(getName(), score, getWeight(), reason);
        result.getDetails().put("alignmentPercent", alignmentPercent);
        if (rsi != null) result.getDetails().put("rsi", rsi);
        if (macdHistogram != null) result.getDetails().put("macdHistogram", macdHistogram);
        return result;
    }
}
