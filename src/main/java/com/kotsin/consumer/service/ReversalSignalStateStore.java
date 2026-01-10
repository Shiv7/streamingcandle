package com.kotsin.consumer.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * ReversalSignalStateStore - Stores previous candle state for reversal signal calculations.
 *
 * This in-memory store tracks:
 * - Previous OFI values (for velocity calculation)
 * - Previous OFI velocity (for acceleration calculation)
 * - Previous volume (for spike detection)
 * - Previous option premiums (for options flow)
 * - Session high/low (for divergence detection)
 *
 * Uses Caffeine cache with TTL to automatically clean up stale entries.
 */
@Service
public class ReversalSignalStateStore {

    /**
     * State record for a single family.
     */
    public static class FamilyState {
        public Double previousOfi;
        public Double previousOfiVelocity;
        public Long previousVolume;
        public Double previousCallPremium;
        public Double previousPutPremium;
        public Double sessionHigh;
        public Double sessionLow;
        public long lastUpdateTime;

        // Track session boundaries
        public long sessionStartMillis;
    }

    // Cache with 2-hour TTL (covers full trading session + some buffer)
    private final Cache<String, FamilyState> stateCache = Caffeine.newBuilder()
            .expireAfterWrite(2, TimeUnit.HOURS)
            .maximumSize(50000)  // ~50K families
            .build();

    /**
     * Get state for a family, creating new state if not exists.
     */
    public FamilyState getOrCreateState(String familyId) {
        return stateCache.get(familyId, k -> new FamilyState());
    }

    /**
     * Update state after processing a candle.
     *
     * @param familyId Family identifier
     * @param ofi Current OFI value
     * @param ofiVelocity Current OFI velocity
     * @param volume Current volume
     * @param atmCallPremium Current ATM call close price
     * @param atmPutPremium Current ATM put close price
     * @param high Current candle high
     * @param low Current candle low
     * @param windowStartMillis Window start time (for session detection)
     */
    public void updateState(
            String familyId,
            Double ofi,
            Double ofiVelocity,
            Long volume,
            Double atmCallPremium,
            Double atmPutPremium,
            Double high,
            Double low,
            long windowStartMillis
    ) {
        FamilyState state = getOrCreateState(familyId);

        // Check for new session (reset session high/low)
        if (isNewSession(state, windowStartMillis)) {
            state.sessionHigh = high;
            state.sessionLow = low;
            state.sessionStartMillis = windowStartMillis;
        } else {
            // Update session high/low
            if (high != null && (state.sessionHigh == null || high > state.sessionHigh)) {
                state.sessionHigh = high;
            }
            if (low != null && (state.sessionLow == null || low < state.sessionLow)) {
                state.sessionLow = low;
            }
        }

        // Update previous values for next iteration
        state.previousOfi = ofi;
        state.previousOfiVelocity = ofiVelocity;
        state.previousVolume = volume;
        state.previousCallPremium = atmCallPremium;
        state.previousPutPremium = atmPutPremium;
        state.lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * Check if this is a new trading session.
     * New session = gap of more than 30 minutes from last update.
     */
    private boolean isNewSession(FamilyState state, long windowStartMillis) {
        if (state.sessionStartMillis == 0) {
            return true;  // First candle
        }
        // Gap of more than 30 minutes = new session
        return windowStartMillis - state.lastUpdateTime > 30 * 60 * 1000;
    }

    /**
     * Get session high for a family.
     */
    public Double getSessionHigh(String familyId) {
        FamilyState state = stateCache.getIfPresent(familyId);
        return state != null ? state.sessionHigh : null;
    }

    /**
     * Get session low for a family.
     */
    public Double getSessionLow(String familyId) {
        FamilyState state = stateCache.getIfPresent(familyId);
        return state != null ? state.sessionLow : null;
    }

    /**
     * Clear all state (useful for testing or reset).
     */
    public void clearAll() {
        stateCache.invalidateAll();
    }

    /**
     * Get cache statistics for monitoring.
     */
    public String getStats() {
        return String.format("ReversalSignalStateStore: size=%d, hitRate=%.2f%%",
                stateCache.estimatedSize(),
                stateCache.stats().hitRate() * 100);
    }
}
