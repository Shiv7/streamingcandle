package com.kotsin.consumer.enrichment.intelligence.tracker;

import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.intelligence.model.ActiveSetup;
import com.kotsin.consumer.enrichment.intelligence.model.SetupCondition;
import com.kotsin.consumer.enrichment.intelligence.model.SetupDefinition;
import com.kotsin.consumer.enrichment.intelligence.registry.SetupDefinitionRegistry;
import com.kotsin.consumer.enrichment.model.DetectedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * SetupTracker - Manages active trading setups across all families
 *
 * Responsibilities:
 * 1. Evaluate all setup definitions against current market state
 * 2. Track progress of setups as conditions are met/unmet
 * 3. Identify ready setups for signal generation
 * 4. Handle setup invalidation and expiration
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SetupTracker {

    private final SetupDefinitionRegistry setupRegistry;

    /**
     * Active setups by family
     */
    private final Map<String, List<ActiveSetup>> activeSetupsByFamily = new ConcurrentHashMap<>();

    /**
     * All setups by ID for quick lookup
     */
    private final Map<String, ActiveSetup> setupsById = new ConcurrentHashMap<>();

    /**
     * Maximum active setups per family
     */
    private static final int MAX_SETUPS_PER_FAMILY = 20;

    // ======================== MAIN EVALUATION ========================

    /**
     * Evaluate all setups for a family against current market state
     *
     * @param familyId   Family being evaluated
     * @param quantScore Current enriched quant score
     * @param events     Recent detected events
     * @return List of ready setups
     */
    public List<ActiveSetup> evaluateSetups(String familyId, EnrichedQuantScore quantScore,
                                             List<DetectedEvent> events) {
        if (quantScore == null) {
            return Collections.emptyList();
        }

        double currentPrice = quantScore.getClose();
        int totalDefinitions = setupRegistry.getAllSetups().size();

        // Evaluate each setup definition
        for (SetupDefinition definition : setupRegistry.getAllSetups()) {
            evaluateSetupDefinition(familyId, definition, quantScore, events, currentPrice);
        }

        // Check for invalidations and expirations
        checkInvalidationsAndExpirations(familyId, quantScore, events);

        // Return ready setups
        List<ActiveSetup> readySetups = getReadySetups(familyId);
        int activeCount = activeSetupsByFamily.getOrDefault(familyId, Collections.emptyList()).size();

        // Log summary
        log.info("[SETUP_TRACKER] {} | definitions={}, active={}, ready={} | events={}",
                familyId, totalDefinitions, activeCount, readySetups.size(),
                events != null ? events.size() : 0);

        // Log ready setup details with booster info for debugging
        for (ActiveSetup setup : readySetups) {
            log.info("[SETUP_TRACKER] {} | READY: {} {} | conf={}% | progress={}% | boosters={}/2",
                    familyId, setup.getSetupId(), setup.getDirection(),
                    String.format("%.1f", setup.getCurrentConfidence() * 100),
                    String.format("%.0f", setup.getProgress() * 100),
                    setup.getBoosterConditionsMet());
        }

        return readySetups;
    }

    /**
     * Evaluate a single setup definition against current state
     */
    private void evaluateSetupDefinition(String familyId, SetupDefinition definition,
                                          EnrichedQuantScore quantScore, List<DetectedEvent> events,
                                          double currentPrice) {
        // Check if we already have an active setup for this definition
        ActiveSetup existingSetup = getActiveSetupForDefinition(familyId, definition.getSetupId());

        if (existingSetup != null && existingSetup.isActive()) {
            // Update existing setup
            updateSetup(existingSetup, definition, quantScore, events, currentPrice);
        } else {
            // Try to start a new setup if any required condition is met
            tryStartNewSetup(familyId, definition, quantScore, events, currentPrice);
        }
    }

    /**
     * Try to start a new setup if conditions warrant
     */
    private void tryStartNewSetup(String familyId, SetupDefinition definition,
                                   EnrichedQuantScore quantScore, List<DetectedEvent> events,
                                   double currentPrice) {
        // Count how many required conditions are met
        int metCount = 0;
        List<String> metConditionIds = new ArrayList<>();

        for (SetupCondition condition : definition.getRequiredConditions()) {
            if (condition.evaluate(quantScore, events)) {
                metCount++;
                metConditionIds.add(condition.getConditionId());
            }
        }

        // Only start if at least one required condition is met
        if (metCount > 0) {
            // Check if any invalidation condition is already met
            for (SetupCondition invalidation : definition.getInvalidationConditions()) {
                if (invalidation.evaluate(quantScore, events)) {
                    // Don't start - already invalidated
                    return;
                }
            }

            // Start new setup
            ActiveSetup newSetup = ActiveSetup.fromDefinition(definition, familyId, currentPrice);

            // Record met conditions
            for (String conditionId : metConditionIds) {
                newSetup.recordRequiredMet(conditionId);
            }

            // Evaluate boosters
            for (SetupCondition booster : definition.getBoosterConditions()) {
                if (booster.evaluate(quantScore, events)) {
                    newSetup.recordBoosterMet(booster.getConditionId(), booster.getConfidenceBoost());
                }
            }

            // Add to tracking
            addSetup(familyId, newSetup);

            log.info("[SETUP_TRACKER] Started setup {} for family {}: {}",
                    definition.getSetupId(), familyId, newSetup.getStateDescription());
        }
    }

    /**
     * Update an existing active setup
     */
    private void updateSetup(ActiveSetup setup, SetupDefinition definition,
                              EnrichedQuantScore quantScore, List<DetectedEvent> events,
                              double currentPrice) {
        setup.setCurrentPrice(currentPrice);

        // Re-evaluate required conditions
        int newMetCount = 0;
        List<String> newMetIds = new ArrayList<>();

        for (SetupCondition condition : definition.getRequiredConditions()) {
            if (condition.evaluate(quantScore, events)) {
                newMetCount++;
                newMetIds.add(condition.getConditionId());
                if (!setup.getMetConditions().contains(condition.getConditionId())) {
                    setup.recordRequiredMet(condition.getConditionId());
                }
            }
        }

        // Update required conditions met count
        setup.setRequiredConditionsMet(newMetCount);

        // Evaluate boosters
        for (SetupCondition booster : definition.getBoosterConditions()) {
            if (booster.evaluate(quantScore, events)) {
                if (!setup.getMetConditions().contains(booster.getConditionId())) {
                    setup.recordBoosterMet(booster.getConditionId(), booster.getConfidenceBoost());
                    log.debug("[SETUP_TRACKER] Booster {} met for setup {}",
                            booster.getConditionId(), setup.getSetupId());
                }
            }
        }

        // Check if setup becomes ready
        if (setup.isAllRequiredMet() && setup.getStatus() == ActiveSetup.SetupStatus.FORMING) {
            setup.setStatus(ActiveSetup.SetupStatus.READY);
            setup.setReadyAt(Instant.now());
            setup.setCurrentConfidence(Math.max(setup.getCurrentConfidence(), setup.getBaseConfidence()));
            log.info("[SETUP_TRACKER] Setup {} READY for family {}: confidence={}%",
                    setup.getSetupId(), setup.getFamilyId(),
                    String.format("%.0f", setup.getCurrentConfidence() * 100));
        }
    }

    /**
     * Check for invalidations and expirations
     */
    private void checkInvalidationsAndExpirations(String familyId, EnrichedQuantScore quantScore,
                                                   List<DetectedEvent> events) {
        List<ActiveSetup> familySetups = activeSetupsByFamily.get(familyId);
        if (familySetups == null) return;

        for (ActiveSetup setup : new ArrayList<>(familySetups)) {
            if (!setup.isActive()) continue;

            // Check expiration
            if (setup.getExpiresAt() != null && Instant.now().isAfter(setup.getExpiresAt())) {
                setup.expire();
                log.info("[SETUP_TRACKER] Setup {} EXPIRED for family {}", setup.getSetupId(), familyId);
                continue;
            }

            // Check invalidation conditions
            Optional<SetupDefinition> defOpt = setupRegistry.getSetup(setup.getSetupId());
            if (defOpt.isPresent()) {
                for (SetupCondition invalidation : defOpt.get().getInvalidationConditions()) {
                    if (invalidation.evaluate(quantScore, events)) {
                        setup.invalidate(invalidation.getConditionId(), invalidation.getDescription());
                        log.info("[SETUP_TRACKER] Setup {} INVALIDATED for family {}: {}",
                                setup.getSetupId(), familyId, invalidation.getDescription());
                        break;
                    }
                }
            }
        }
    }

    // ======================== SETUP MANAGEMENT ========================

    /**
     * Add a setup to tracking
     */
    private void addSetup(String familyId, ActiveSetup setup) {
        List<ActiveSetup> familySetups = activeSetupsByFamily
                .computeIfAbsent(familyId, k -> new ArrayList<>());

        // Check limit
        if (familySetups.size() >= MAX_SETUPS_PER_FAMILY) {
            removeOldestSetup(familyId);
        }

        familySetups.add(setup);
        setupsById.put(setup.getActiveSetupId(), setup);
    }

    /**
     * Remove oldest setup from a family
     */
    private void removeOldestSetup(String familyId) {
        List<ActiveSetup> familySetups = activeSetupsByFamily.get(familyId);
        if (familySetups == null || familySetups.isEmpty()) return;

        // Remove oldest ended, or oldest forming
        ActiveSetup toRemove = familySetups.stream()
                .filter(ActiveSetup::hasEnded)
                .min(Comparator.comparing(s -> s.getEndedAt() != null ? s.getEndedAt() : Instant.MAX))
                .orElseGet(() -> familySetups.stream()
                        .min(Comparator.comparing(ActiveSetup::getStartedAt))
                        .orElse(null));

        if (toRemove != null) {
            familySetups.remove(toRemove);
            setupsById.remove(toRemove.getActiveSetupId());
        }
    }

    /**
     * Get active setup for a definition in a family
     */
    private ActiveSetup getActiveSetupForDefinition(String familyId, String setupId) {
        List<ActiveSetup> familySetups = activeSetupsByFamily.get(familyId);
        if (familySetups == null) return null;

        return familySetups.stream()
                .filter(s -> s.getSetupId().equals(setupId) && s.isActive())
                .findFirst()
                .orElse(null);
    }

    // ======================== QUERY METHODS ========================

    /**
     * Get all active setups for a family
     */
    public List<ActiveSetup> getActiveSetups(String familyId) {
        List<ActiveSetup> familySetups = activeSetupsByFamily.get(familyId);
        if (familySetups == null) return Collections.emptyList();

        return familySetups.stream()
                .filter(ActiveSetup::isActive)
                .collect(Collectors.toList());
    }

    /**
     * Get ready setups for a family
     */
    public List<ActiveSetup> getReadySetups(String familyId) {
        List<ActiveSetup> familySetups = activeSetupsByFamily.get(familyId);
        if (familySetups == null) return Collections.emptyList();

        return familySetups.stream()
                .filter(ActiveSetup::isReady)
                .collect(Collectors.toList());
    }

    /**
     * Get actionable setups (ready + above minimum confidence)
     */
    public List<ActiveSetup> getActionableSetups(String familyId, double minConfidence) {
        List<ActiveSetup> familySetups = activeSetupsByFamily.get(familyId);
        if (familySetups == null) return Collections.emptyList();

        return familySetups.stream()
                .filter(s -> s.isActionable(minConfidence))
                .sorted(Comparator.comparingDouble(ActiveSetup::getCurrentConfidence).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Get setup by ID
     */
    public Optional<ActiveSetup> getSetup(String activeSetupId) {
        return Optional.ofNullable(setupsById.get(activeSetupId));
    }

    /**
     * Mark setup as triggered
     */
    public void markTriggered(String activeSetupId, double entry, double stop, double target) {
        ActiveSetup setup = setupsById.get(activeSetupId);
        if (setup != null && setup.isReady()) {
            setup.trigger(entry, stop, target);
            log.info("[SETUP_TRACKER] Setup {} TRIGGERED: entry={}, stop={}, target={}",
                    setup.getSetupId(), entry, stop, target);
        }
    }

    // ======================== CLEANUP ========================

    /**
     * Cleanup expired and old setups
     */
    public void cleanup() {
        int removed = 0;
        Instant now = Instant.now();

        for (List<ActiveSetup> familySetups : activeSetupsByFamily.values()) {
            Iterator<ActiveSetup> it = familySetups.iterator();
            while (it.hasNext()) {
                ActiveSetup setup = it.next();

                // Expire if past expiry time
                if (setup.isActive() && setup.getExpiresAt() != null &&
                        now.isAfter(setup.getExpiresAt())) {
                    setup.expire();
                }

                // Remove ended setups older than 5 minutes
                if (setup.hasEnded() && setup.getEndedAt() != null) {
                    long endedAgoMs = now.toEpochMilli() - setup.getEndedAt().toEpochMilli();
                    if (endedAgoMs > 300_000) {
                        it.remove();
                        setupsById.remove(setup.getActiveSetupId());
                        removed++;
                    }
                }
            }
        }

        if (removed > 0) {
            log.debug("[SETUP_TRACKER] Cleaned up {} old setups", removed);
        }
    }

    /**
     * Clear all setups for a family
     */
    public void clearFamily(String familyId) {
        List<ActiveSetup> removed = activeSetupsByFamily.remove(familyId);
        if (removed != null) {
            for (ActiveSetup setup : removed) {
                setupsById.remove(setup.getActiveSetupId());
            }
        }
    }

    // ======================== STATISTICS ========================

    /**
     * Get tracking statistics
     */
    public TrackerStats getStats() {
        int totalActive = 0;
        int totalReady = 0;
        int totalTriggered = 0;

        for (ActiveSetup setup : setupsById.values()) {
            switch (setup.getStatus()) {
                case FORMING, READY -> {
                    totalActive++;
                    if (setup.getStatus() == ActiveSetup.SetupStatus.READY) totalReady++;
                }
                case TRIGGERED -> totalTriggered++;
            }
        }

        return new TrackerStats(
                activeSetupsByFamily.size(),
                setupsById.size(),
                totalActive,
                totalReady,
                totalTriggered
        );
    }

    public record TrackerStats(
            int familiesTracked,
            int totalSetups,
            int activeSetups,
            int readySetups,
            int triggeredSetups
    ) {
        @Override
        public String toString() {
            return String.format("SetupTracker: %d families, %d setups (%d active, %d ready, %d triggered)",
                    familiesTracked, totalSetups, activeSetups, readySetups, triggeredSetups);
        }
    }
}
