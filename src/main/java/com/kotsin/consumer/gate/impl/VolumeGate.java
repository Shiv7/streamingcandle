package com.kotsin.consumer.gate.impl;

import com.kotsin.consumer.gate.SignalGate;
import com.kotsin.consumer.gate.model.GateResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * VolumeGate - Validates volume confirmation for signals.
 */
@Component
public class VolumeGate implements SignalGate {

    private static final double MIN_VOLUME_RATIO = 1.2;  // 1.2x average
    private static final double GOOD_VOLUME_RATIO = 1.5; // 1.5x average
    private static final double EXCELLENT_VOLUME_RATIO = 2.0; // 2x average

    @Override
    public String getName() {
        return "VOLUME";
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
        Double volume = (Double) context.get("volume");
        Double avgVolume = (Double) context.get("avgVolume");

        if (volume == null || avgVolume == null || avgVolume == 0) {
            return GateResult.fail(getName(), getWeight(), "Volume data not available");
        }

        double ratio = volume / avgVolume;

        if (ratio < MIN_VOLUME_RATIO) {
            return GateResult.fail(getName(), getWeight(),
                String.format("Volume too low: %.1fx (need %.1fx)", ratio, MIN_VOLUME_RATIO));
        }

        double score;
        String reason;

        if (ratio >= EXCELLENT_VOLUME_RATIO) {
            score = 100;
            reason = String.format("Excellent volume: %.1fx average", ratio);
        } else if (ratio >= GOOD_VOLUME_RATIO) {
            score = 80;
            reason = String.format("Good volume: %.1fx average", ratio);
        } else {
            score = 60;
            reason = String.format("Acceptable volume: %.1fx average", ratio);
        }

        GateResult result = GateResult.pass(getName(), score, getWeight(), reason);
        result.getDetails().put("volumeRatio", ratio);
        return result;
    }
}
