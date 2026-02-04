package com.kotsin.consumer.gate;

import com.kotsin.consumer.gate.model.GateResult;

import java.util.Map;

/**
 * SignalGate - Interface for quality gate implementations.
 *
 * Each gate evaluates a specific aspect of signal quality:
 * - Volume confirmation
 * - Price action quality
 * - Trend alignment
 * - Risk/Reward ratio
 * - Time of day
 * - etc.
 */
public interface SignalGate {

    /**
     * Get the name of this gate.
     */
    String getName();

    /**
     * Get the weight of this gate (0-1).
     * Higher weight = more important.
     */
    double getWeight();

    /**
     * Check if this gate is required to pass.
     * Required gates will fail the entire chain if they fail.
     */
    boolean isRequired();

    /**
     * Evaluate the signal against this gate's criteria.
     *
     * @param context Signal context containing all relevant data
     * @return GateResult with pass/fail status and details
     */
    GateResult evaluate(Map<String, Object> context);
}
