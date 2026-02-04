package com.kotsin.consumer.gate.impl;

import com.kotsin.consumer.gate.SignalGate;
import com.kotsin.consumer.gate.model.GateResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * RiskRewardGate - Validates risk/reward ratio for signals.
 */
@Component
public class RiskRewardGate implements SignalGate {

    private static final double MIN_RR = 1.5;
    private static final double GOOD_RR = 2.0;
    private static final double EXCELLENT_RR = 3.0;

    @Override
    public String getName() {
        return "RISK_REWARD";
    }

    @Override
    public double getWeight() {
        return 0.20;  // 20% weight
    }

    @Override
    public boolean isRequired() {
        return true;  // Risk/Reward is required
    }

    @Override
    public GateResult evaluate(Map<String, Object> context) {
        Double entryPrice = (Double) context.get("entryPrice");
        Double targetPrice = (Double) context.get("targetPrice");
        Double stopLoss = (Double) context.get("stopLoss");
        String direction = (String) context.get("signalDirection");

        if (entryPrice == null || targetPrice == null || stopLoss == null) {
            return GateResult.fail(getName(), getWeight(), "Price levels not specified");
        }

        double risk, reward;
        if ("LONG".equalsIgnoreCase(direction)) {
            risk = entryPrice - stopLoss;
            reward = targetPrice - entryPrice;
        } else {
            risk = stopLoss - entryPrice;
            reward = entryPrice - targetPrice;
        }

        if (risk <= 0) {
            return GateResult.fail(getName(), getWeight(), "Invalid risk: stop loss in wrong direction");
        }

        double rr = reward / risk;

        if (rr < MIN_RR) {
            return GateResult.fail(getName(), getWeight(),
                String.format("R:R too low: %.2f (need %.2f)", rr, MIN_RR));
        }

        double score;
        String reason;

        if (rr >= EXCELLENT_RR) {
            score = 100;
            reason = String.format("Excellent R:R: %.2f", rr);
        } else if (rr >= GOOD_RR) {
            score = 80;
            reason = String.format("Good R:R: %.2f", rr);
        } else {
            score = 60;
            reason = String.format("Acceptable R:R: %.2f", rr);
        }

        GateResult result = GateResult.pass(getName(), score, getWeight(), reason);
        result.getDetails().put("riskRewardRatio", rr);
        result.getDetails().put("risk", risk);
        result.getDetails().put("reward", reward);
        return result;
    }
}
