package com.kotsin.consumer.gate.impl;

import com.kotsin.consumer.breakout.model.BreakoutEvent;
import com.kotsin.consumer.breakout.model.BreakoutEvent.*;
import com.kotsin.consumer.gate.SignalGate;
import com.kotsin.consumer.gate.model.GateResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * StructuralLevelGate - Validates that signals are trading at or near key structural price levels.
 *
 * Signals at confirmed retests of broken levels have the highest probability.
 * Signals at key confluence levels are stronger than those in no-man's land.
 * This gate penalizes signals far from any structural context.
 *
 * Context keys consumed:
 * - "breakoutEvents" (List<BreakoutEvent>): Active breakout/retest events for the symbol
 * - "entryPrice" (Double): Signal entry price
 */
@Component
@Slf4j
public class StructuralLevelGate implements SignalGate {

    @Override
    public String getName() {
        return "STRUCTURAL_LEVEL";
    }

    @Override
    public double getWeight() {
        return 0.20; // 20% weight — structural context is important
    }

    @Override
    public boolean isRequired() {
        return false; // Don't block signals without structural context, just penalize
    }

    @Override
    @SuppressWarnings("unchecked")
    public GateResult evaluate(Map<String, Object> context) {
        List<BreakoutEvent> events = null;
        try {
            events = (List<BreakoutEvent>) context.get("breakoutEvents");
        } catch (ClassCastException e) {
            // Not available
        }

        Double entryPrice = (Double) context.get("entryPrice");
        if (entryPrice == null || entryPrice <= 0) {
            return GateResult.pass(getName(), 30, getWeight(), "No entry price — minimal structural context");
        }

        // No breakout events at all — signal has no structural backing
        if (events == null || events.isEmpty()) {
            GateResult result = GateResult.pass(getName(), 20, getWeight(),
                "No breakout/retest events — signal lacks structural context");
            result.getDetails().put("structuralScore", 20);
            return result;
        }

        // Evaluate best structural event
        double bestScore = 20; // Base: no structural context
        String bestReason = "Near registered level";
        boolean hasRetest = false;
        boolean hasBreakout = false;
        boolean isFirstRetest = false;
        RetestQuality bestRetestQuality = null;

        for (BreakoutEvent event : events) {
            log.debug("[STRUCTURAL-GATE] Evaluating event: type={} level={} retestHeld={} quality={}",
                event.getType(), event.getLevelDescription(),
                event.isRetestHeld(), event.getRetestQuality());

            if (event.getType() == BreakoutType.RETEST && event.isRetestHeld()) {
                hasRetest = true;
                RetestQuality quality = event.getRetestQuality();

                if (quality == RetestQuality.PERFECT) {
                    bestScore = Math.max(bestScore, 100);
                    bestRetestQuality = quality;
                    bestReason = "PERFECT retest at " + event.getLevelDescription();
                } else if (quality == RetestQuality.GOOD) {
                    bestScore = Math.max(bestScore, 85);
                    if (bestRetestQuality != RetestQuality.PERFECT) bestRetestQuality = quality;
                    bestReason = "GOOD retest at " + event.getLevelDescription();
                } else {
                    bestScore = Math.max(bestScore, 60);
                    bestReason = "Retest at " + event.getLevelDescription() + " (quality=" + quality + ")";
                }

            } else if (event.getType() == BreakoutType.BREAKOUT) {
                hasBreakout = true;
                bestScore = Math.max(bestScore, 80);
                if (!hasRetest) {
                    bestReason = "Active breakout at " + event.getLevelDescription();
                }

            } else if (event.getType() == BreakoutType.FAKEOUT) {
                // Fakeout detected — this is a warning, reduce score
                bestScore = Math.min(bestScore, 30);
                bestReason = "Fakeout detected at " + event.getLevelDescription();
            }
        }

        // Any proximity to a key level is worth at least 60
        if (!hasRetest && !hasBreakout && bestScore < 60) {
            bestScore = 60;
            bestReason = "Signal near key structural level";
        }

        GateResult result = GateResult.pass(getName(), bestScore, getWeight(), bestReason);
        result.getDetails().put("structuralScore", bestScore);
        result.getDetails().put("hasRetest", hasRetest);
        result.getDetails().put("hasBreakout", hasBreakout);
        if (bestRetestQuality != null) {
            result.getDetails().put("retestQuality", bestRetestQuality.name());
        }

        log.debug("[STRUCTURAL-GATE] Score: {} | retest={}, breakout={} | {}",
            String.format("%.0f", bestScore), hasRetest, hasBreakout, bestReason);

        return result;
    }
}
