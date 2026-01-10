package com.kotsin.consumer.enrichment.intelligence.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ActiveSetup - Tracks progress of a trading setup in real-time
 *
 * When market conditions start matching a SetupDefinition,
 * an ActiveSetup is created to track progress toward completion.
 *
 * Lifecycle: FORMING → READY → TRIGGERED/INVALIDATED/EXPIRED
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveSetup {

    /**
     * Unique setup instance ID
     */
    @Builder.Default
    private String activeSetupId = UUID.randomUUID().toString();

    /**
     * Setup definition ID being tracked
     */
    private String setupId;

    /**
     * Family ID where setup is active
     */
    private String familyId;

    /**
     * Current status
     */
    @Builder.Default
    private SetupStatus status = SetupStatus.FORMING;

    // ======================== TIMING ========================

    /**
     * When setup started forming
     */
    private Instant startedAt;

    /**
     * When setup became ready
     */
    private Instant readyAt;

    /**
     * When setup expires if not triggered
     */
    private Instant expiresAt;

    /**
     * When setup ended (triggered/invalidated/expired)
     */
    private Instant endedAt;

    // ======================== PROGRESS ========================

    /**
     * Number of required conditions met
     */
    @Builder.Default
    private int requiredConditionsMet = 0;

    /**
     * Total required conditions
     */
    private int totalRequiredConditions;

    /**
     * Number of booster conditions met
     */
    @Builder.Default
    private int boosterConditionsMet = 0;

    /**
     * Total booster conditions
     */
    private int totalBoosterConditions;

    /**
     * IDs of conditions that are met
     */
    @Builder.Default
    private List<String> metConditions = new ArrayList<>();

    /**
     * IDs of conditions that failed
     */
    @Builder.Default
    private List<String> failedConditions = new ArrayList<>();

    /**
     * Progress percentage (0-100)
     */
    public double getProgress() {
        if (totalRequiredConditions == 0) return 0;
        return (double) requiredConditionsMet / totalRequiredConditions * 100;
    }

    /**
     * Check if all required conditions are met
     */
    public boolean isAllRequiredMet() {
        return requiredConditionsMet >= totalRequiredConditions;
    }

    // ======================== CONFIDENCE ========================

    /**
     * Current confidence based on met conditions
     */
    @Builder.Default
    private double currentConfidence = 0.0;

    /**
     * Base confidence from setup definition
     */
    private double baseConfidence;

    /**
     * Maximum confidence from setup definition
     */
    private double maxConfidence;

    // ======================== PRICE CONTEXT ========================

    /**
     * Price when setup started forming
     */
    private double startPrice;

    /**
     * Current price
     */
    private double currentPrice;

    /**
     * Entry price (when setup triggers)
     */
    private double entryPrice;

    /**
     * Calculated stop loss price
     */
    private double stopLossPrice;

    /**
     * Calculated target price
     */
    private double targetPrice;

    // ======================== TRADE PARAMETERS ========================

    /**
     * Trade direction
     */
    private SetupDefinition.SetupDirection direction;

    /**
     * Trade horizon
     */
    private SetupDefinition.SetupHorizon horizon;

    /**
     * Risk/Reward ratio
     */
    private double riskRewardRatio;

    // ======================== INVALIDATION ========================

    /**
     * Reason for invalidation
     */
    private String invalidationReason;

    /**
     * Condition that caused invalidation
     */
    private String invalidationCondition;

    // ======================== STATUS ENUM ========================

    public enum SetupStatus {
        FORMING,        // Some conditions met, waiting for more
        READY,          // All required conditions met, waiting for trigger
        TRIGGERED,      // Trade was taken
        INVALIDATED,    // Invalidation condition hit
        EXPIRED         // Setup expired without trigger
    }

    // ======================== HELPER METHODS ========================

    /**
     * Check if setup is still active
     */
    public boolean isActive() {
        return status == SetupStatus.FORMING || status == SetupStatus.READY;
    }

    /**
     * Check if setup is ready to trigger
     */
    public boolean isReady() {
        return status == SetupStatus.READY;
    }

    /**
     * Check if setup has ended
     */
    public boolean hasEnded() {
        return status == SetupStatus.TRIGGERED
                || status == SetupStatus.INVALIDATED
                || status == SetupStatus.EXPIRED;
    }

    /**
     * Check if setup is actionable (ready and above minimum confidence)
     */
    public boolean isActionable(double minConfidence) {
        return status == SetupStatus.READY && currentConfidence >= minConfidence;
    }

    /**
     * Record a required condition being met
     */
    public void recordRequiredMet(String conditionId) {
        if (!metConditions.contains(conditionId)) {
            metConditions.add(conditionId);
            requiredConditionsMet++;

            // Check if setup becomes ready
            if (isAllRequiredMet() && status == SetupStatus.FORMING) {
                status = SetupStatus.READY;
                readyAt = Instant.now();
                currentConfidence = baseConfidence;
            }
        }
    }

    /**
     * Record a booster condition being met
     */
    public void recordBoosterMet(String conditionId, double confidenceBoost) {
        if (!metConditions.contains(conditionId)) {
            metConditions.add(conditionId);
            boosterConditionsMet++;
            currentConfidence = Math.min(maxConfidence, currentConfidence + confidenceBoost);
        }
    }

    /**
     * Record a condition failing
     */
    public void recordConditionFailed(String conditionId) {
        if (metConditions.contains(conditionId)) {
            metConditions.remove(conditionId);
            failedConditions.add(conditionId);
            // Decrement if it was a required condition
            // Note: actual logic would need to track which were required vs booster
        }
    }

    /**
     * Invalidate the setup
     */
    public void invalidate(String conditionId, String reason) {
        status = SetupStatus.INVALIDATED;
        endedAt = Instant.now();
        invalidationCondition = conditionId;
        invalidationReason = reason;
    }

    /**
     * Mark setup as triggered
     */
    public void trigger(double entry, double stop, double target) {
        status = SetupStatus.TRIGGERED;
        endedAt = Instant.now();
        entryPrice = entry;
        stopLossPrice = stop;
        targetPrice = target;
    }

    /**
     * Expire the setup
     */
    public void expire() {
        status = SetupStatus.EXPIRED;
        endedAt = Instant.now();
    }

    /**
     * Get time elapsed since start
     */
    public long getElapsedMs() {
        if (startedAt == null) return 0;
        Instant end = endedAt != null ? endedAt : Instant.now();
        return end.toEpochMilli() - startedAt.toEpochMilli();
    }

    /**
     * Get description of current state
     */
    public String getStateDescription() {
        return String.format("%s [%s]: %.0f%% complete (%d/%d required, %d boosters), confidence=%.0f%%",
                setupId, status, getProgress(),
                requiredConditionsMet, totalRequiredConditions,
                boosterConditionsMet, currentConfidence * 100);
    }

    /**
     * Create from definition
     */
    public static ActiveSetup fromDefinition(SetupDefinition def, String familyId, double currentPrice) {
        return ActiveSetup.builder()
                .activeSetupId(UUID.randomUUID().toString())
                .setupId(def.getSetupId())
                .familyId(familyId)
                .status(SetupStatus.FORMING)
                .startedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(
                        def.getHorizon() == SetupDefinition.SetupHorizon.SCALP ? 1800 :
                                def.getHorizon() == SetupDefinition.SetupHorizon.SWING ? 14400 : 86400))
                .totalRequiredConditions(def.getRequiredCount())
                .totalBoosterConditions(def.getBoosterCount())
                .baseConfidence(def.getBaseConfidence())
                .maxConfidence(def.getMaxConfidence())
                .startPrice(currentPrice)
                .currentPrice(currentPrice)
                .direction(def.getDirection())
                .horizon(def.getHorizon())
                .riskRewardRatio(def.getDefaultRiskReward())
                .build();
    }

    @Override
    public String toString() {
        return String.format("ActiveSetup[%s] %s %s: %s (%.0f%% confidence)",
                activeSetupId.substring(0, 8), setupId, status, getProgress() + "% complete",
                currentConfidence * 100);
    }
}
