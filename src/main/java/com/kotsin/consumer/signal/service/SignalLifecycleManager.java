package com.kotsin.consumer.signal.service;

import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import com.kotsin.consumer.signal.model.SignalContext;
import com.kotsin.consumer.signal.model.SignalStatus;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * SignalLifecycleManager - Manages the lifecycle of all trading signals.
 *
 * RESPONSIBILITIES:
 * 1. Register new signals when generated
 * 2. Update signals on each tick with decay calculation
 * 3. Track signal status transitions (ACTIVE → WEAKENING → INVALID)
 * 4. Provide active signals for a given instrument
 * 5. Clean up expired/invalid signals
 *
 * SIGNAL FLOW:
 * ┌─────────────────────────────────────────────────────────────┐
 * │  TradingSignal generated                                    │
 * │       ↓                                                     │
 * │  registerSignal() → Creates SignalContext                   │
 * │       ↓                                                     │
 * │  Every tick: updateSignal()                                 │
 * │       ↓                                                     │
 * │  DecayCalculator → Updates decay factors                    │
 * │  Invalidator → Checks all rules                             │
 * │       ↓                                                     │
 * │  Status: ACTIVE | STRENGTHENING | WEAKENING | INVALID_*     │
 * │       ↓                                                     │
 * │  If INVALID → Signal removed from active pool               │
 * └─────────────────────────────────────────────────────────────┘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalLifecycleManager {

    private final SignalDecayCalculator decayCalculator;
    private final SignalInvalidator invalidator;

    // Active signals cache (signal ID → SignalContext)
    private Cache<String, SignalContext> activeSignals;

    // Signals by family (family ID → Set of signal IDs)
    private final Map<String, Set<String>> signalsByFamily = new ConcurrentHashMap<>();

    // Recently invalidated signals (for logging/debugging)
    private Cache<String, SignalContext> recentlyInvalidated;

    @PostConstruct
    public void init() {
        // Active signals expire after 2 hours
        activeSignals = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(2))
                .maximumSize(1000)
                .build();

        // Keep recently invalidated for 30 minutes
        recentlyInvalidated = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(30))
                .maximumSize(500)
                .build();
    }

    /**
     * Register a new signal.
     *
     * @param signal The trading signal
     * @param score  The enriched score at signal generation
     * @return SignalContext for the registered signal
     */
    public SignalContext registerSignal(TradingSignal signal, EnrichedQuantScore score) {
        if (signal == null || signal.getSignalId() == null) {
            log.warn("[LIFECYCLE] Cannot register null signal");
            return null;
        }

        // Create signal context
        SignalContext context = SignalContext.fromTradingSignal(signal, score);

        // Store in cache
        activeSignals.put(signal.getSignalId(), context);

        // Track by family
        signalsByFamily.computeIfAbsent(signal.getFamilyId(), k -> ConcurrentHashMap.newKeySet())
                .add(signal.getSignalId());

        log.info("[LIFECYCLE] REGISTERED | {} | {} {} @ {} | SL={} | T1={} | conf={}%",
                signal.getSignalId(), signal.getDirection(), signal.getScripCode(),
                String.format("%.2f", signal.getEntryPrice()),
                String.format("%.2f", signal.getStopLoss()),
                String.format("%.2f", signal.getTarget1()),
                String.format("%.0f", signal.getConfidence() * 100));

        return context;
    }

    /**
     * Update a signal with new market data.
     *
     * @param signalId The signal ID
     * @param current  Current enriched score
     * @return Updated SignalStatus
     */
    public SignalStatus updateSignal(String signalId, EnrichedQuantScore current) {
        if (signalId == null) {
            return SignalStatus.ACTIVE;
        }

        SignalContext context = activeSignals.getIfPresent(signalId);
        if (context == null) {
            return SignalStatus.ACTIVE; // Signal not found, assume still active
        }

        // Evaluate status (this updates decay and checks invalidation)
        SignalStatus status = invalidator.evaluateStatus(context, current);
        context.setCurrentStatus(status);

        // Handle invalid signals
        if (status.isInvalid()) {
            invalidateSignal(signalId, status);
        }

        // Log status changes
        if (status == SignalStatus.STRENGTHENING || status == SignalStatus.WEAKENING) {
            log.debug("[LIFECYCLE] {} | {} | decay={}% | score={}",
                    context.getScripCode(), status, String.format("%.1f", context.getCombinedDecay() * 100), String.format("%.1f", context.getCurrentScore()));
        }

        return status;
    }

    /**
     * Update all active signals for a family.
     *
     * @param familyId The family ID
     * @param current  Current enriched score
     * @return Map of signal ID → SignalStatus
     */
    public Map<String, SignalStatus> updateFamilySignals(String familyId, EnrichedQuantScore current) {
        Map<String, SignalStatus> results = new HashMap<>();

        Set<String> signalIds = signalsByFamily.get(familyId);
        if (signalIds == null || signalIds.isEmpty()) {
            return results;
        }

        // Update each signal
        for (String signalId : new HashSet<>(signalIds)) { // Copy to avoid concurrent modification
            SignalStatus status = updateSignal(signalId, current);
            results.put(signalId, status);
        }

        return results;
    }

    /**
     * Invalidate a signal.
     *
     * @param signalId The signal ID
     * @param reason   The invalidation reason
     */
    public void invalidateSignal(String signalId, SignalStatus reason) {
        if (signalId == null) {
            return;
        }

        SignalContext context = activeSignals.getIfPresent(signalId);
        if (context == null) {
            return;
        }

        // Update status
        context.setCurrentStatus(reason);

        // Move to recently invalidated
        recentlyInvalidated.put(signalId, context);

        // Remove from active
        activeSignals.invalidate(signalId);

        // Remove from family tracking
        Set<String> familySignals = signalsByFamily.get(context.getFamilyId());
        if (familySignals != null) {
            familySignals.remove(signalId);
        }

        log.info("[LIFECYCLE] INVALIDATED | {} | {} | {} | decay={}% | age={}min",
                signalId, context.getScripCode(), reason.getDescription(),
                String.format("%.1f", context.getCombinedDecay() * 100), String.format("%.1f", context.getAgeMinutes()));
    }

    /**
     * Mark a signal as executed.
     *
     * @param signalId The signal ID
     */
    public void markExecuted(String signalId) {
        if (signalId == null) {
            return;
        }

        SignalContext context = activeSignals.getIfPresent(signalId);
        if (context == null) {
            return;
        }

        context.setCurrentStatus(SignalStatus.EXECUTED);

        // Move to recently invalidated (for tracking)
        recentlyInvalidated.put(signalId, context);

        // Remove from active
        activeSignals.invalidate(signalId);

        // Remove from family tracking
        Set<String> familySignals = signalsByFamily.get(context.getFamilyId());
        if (familySignals != null) {
            familySignals.remove(signalId);
        }

        log.info("[LIFECYCLE] EXECUTED | {} | {} | entry={} | decay={}%",
                signalId, context.getScripCode(), String.format("%.2f", context.getEntryPrice()),
                String.format("%.1f", context.getCombinedDecay() * 100));
    }

    /**
     * Get active signal context.
     *
     * @param signalId The signal ID
     * @return Optional<SignalContext>
     */
    public Optional<SignalContext> getSignal(String signalId) {
        if (signalId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(activeSignals.getIfPresent(signalId));
    }

    /**
     * Get all active signals for a family.
     *
     * @param familyId The family ID
     * @return List of active SignalContext
     */
    public List<SignalContext> getActiveSignals(String familyId) {
        Set<String> signalIds = signalsByFamily.get(familyId);
        if (signalIds == null || signalIds.isEmpty()) {
            return Collections.emptyList();
        }

        return signalIds.stream()
                .map(id -> activeSignals.getIfPresent(id))
                .filter(Objects::nonNull)
                .filter(ctx -> ctx.getCurrentStatus().isActive())
                .collect(Collectors.toList());
    }

    /**
     * Get the best active signal for a family (highest current score).
     *
     * @param familyId The family ID
     * @return Optional<SignalContext>
     */
    public Optional<SignalContext> getBestActiveSignal(String familyId) {
        return getActiveSignals(familyId).stream()
                .max(Comparator.comparingDouble(SignalContext::getCurrentScore));
    }

    /**
     * Check if there are any active signals for a family.
     *
     * @param familyId The family ID
     * @return true if active signals exist
     */
    public boolean hasActiveSignals(String familyId) {
        return !getActiveSignals(familyId).isEmpty();
    }

    /**
     * Get count of active signals.
     *
     * @return Total count of active signals
     */
    public long getActiveSignalCount() {
        return activeSignals.estimatedSize();
    }

    /**
     * Get count of active signals for a family.
     *
     * @param familyId The family ID
     * @return Count of active signals
     */
    public int getActiveSignalCount(String familyId) {
        return getActiveSignals(familyId).size();
    }

    /**
     * Get recently invalidated signal (for debugging/logging).
     *
     * @param signalId The signal ID
     * @return Optional<SignalContext>
     */
    public Optional<SignalContext> getRecentlyInvalidated(String signalId) {
        if (signalId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(recentlyInvalidated.getIfPresent(signalId));
    }

    /**
     * Get current decay factor for a signal.
     *
     * @param signalId The signal ID
     * @return Decay factor (0-1.5) or 1.0 if not found
     */
    public double getCurrentDecay(String signalId) {
        return getSignal(signalId)
                .map(SignalContext::getCombinedDecay)
                .orElse(1.0);
    }

    /**
     * Get current score for a signal (decayed).
     *
     * @param signalId The signal ID
     * @return Current score or 0 if not found
     */
    public double getCurrentScore(String signalId) {
        return getSignal(signalId)
                .map(SignalContext::getCurrentScore)
                .orElse(0.0);
    }

    /**
     * Clean up old signals (called periodically).
     */
    public void cleanup() {
        // Caffeine handles expiration automatically
        // This method can be used for additional cleanup if needed

        // Remove empty family entries
        signalsByFamily.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        log.debug("[LIFECYCLE] Cleanup complete | active={} | families={}",
                activeSignals.estimatedSize(), signalsByFamily.size());
    }

    /**
     * Get lifecycle statistics.
     *
     * @return Map of statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("activeSignals", activeSignals.estimatedSize());
        stats.put("trackedFamilies", signalsByFamily.size());
        stats.put("recentlyInvalidated", recentlyInvalidated.estimatedSize());

        // Count by status
        Map<SignalStatus, Long> statusCounts = new HashMap<>();
        activeSignals.asMap().values().forEach(ctx ->
                statusCounts.merge(ctx.getCurrentStatus(), 1L, Long::sum));
        stats.put("statusDistribution", statusCounts);

        return stats;
    }
}
