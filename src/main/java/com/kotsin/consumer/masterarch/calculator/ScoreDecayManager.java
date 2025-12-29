package com.kotsin.consumer.masterarch.calculator;

import com.kotsin.consumer.masterarch.model.FinalOpportunityScore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ScoreDecayManager - PART 4: Intraday Score Decay
 * 
 * MASTER ARCHITECTURE Compliant Module
 * 
 * Rule:
 * If no follow through after N bars:
 *   Score_t = Score_t−1 × 0.90
 * 
 * Default N = 3 bars
 * 
 * This prevents stale signals from maintaining high scores.
 */
@Slf4j
@Component
public class ScoreDecayManager {
    
    // Default bars before decay starts
    private static final int DEFAULT_DECAY_BARS = 3;
    
    // Decay factor per bar
    private static final double DECAY_FACTOR = 0.90;
    
    // Track signal age per scripCode
    private final Map<String, SignalState> signalStates = new ConcurrentHashMap<>();
    
    /**
     * Signal state tracker
     */
    private static class SignalState {
        long initialTimestamp;
        double initialScore;
        int barsSinceSignal;
        int decayCount;
        boolean followThroughDetected;
        
        SignalState(long timestamp, double score) {
            this.initialTimestamp = timestamp;
            this.initialScore = score;
            this.barsSinceSignal = 0;
            this.decayCount = 0;
            this.followThroughDetected = false;
        }
    }
    
    /**
     * Register a new signal
     */
    public void registerSignal(String scripCode, double score, long timestamp) {
        signalStates.put(scripCode, new SignalState(timestamp, score));
        log.debug("Registered new signal for {} with score {}", scripCode, score);
    }
    
    /**
     * Update signal with new bar and check for decay
     * 
     * @param scripCode The security
     * @param currentScore Current calculated score
     * @param hasFollowThrough True if price moved in signal direction
     * @param timestamp Current timestamp
     * @return Decayed score (or current score if no decay needed)
     */
    public double updateAndDecay(String scripCode, double currentScore, boolean hasFollowThrough, long timestamp) {
        SignalState state = signalStates.get(scripCode);
        
        if (state == null) {
            // No existing signal, return current score
            return currentScore;
        }
        
        // Check for follow through
        if (hasFollowThrough) {
            state.followThroughDetected = true;
            state.barsSinceSignal = 0;  // Reset counter
            state.decayCount = 0;
            log.debug("Follow through detected for {}, resetting decay", scripCode);
            return currentScore;
        }
        
        // Increment bars since signal
        state.barsSinceSignal++;
        
        // Check if decay should be applied
        if (state.barsSinceSignal >= DEFAULT_DECAY_BARS) {
            // Apply decay
            double decayedScore = currentScore * DECAY_FACTOR;
            state.decayCount++;
            
            log.debug("Applying decay to {}: {} -> {} (decay #{})", 
                    scripCode, currentScore, decayedScore, state.decayCount);
            
            // Reset bar counter for next decay cycle
            state.barsSinceSignal = 0;
            
            return decayedScore;
        }
        
        return currentScore;
    }
    
    /**
     * Apply decay to a FinalOpportunityScore
     */
    public FinalOpportunityScore applyDecayIfNeeded(
            FinalOpportunityScore score, 
            boolean hasFollowThrough,
            long newTimestamp
    ) {
        SignalState state = signalStates.get(score.getScripCode());
        
        if (state == null) {
            // Register this as a new signal
            registerSignal(score.getScripCode(), 
                    score.getFinalScore().getCurrent(), 
                    score.getTimestamp());
            return score;
        }
        
        if (hasFollowThrough) {
            state.followThroughDetected = true;
            state.barsSinceSignal = 0;
            state.decayCount = 0;
            return score;
        }
        
        state.barsSinceSignal++;
        
        if (state.barsSinceSignal >= DEFAULT_DECAY_BARS) {
            state.barsSinceSignal = 0;
            state.decayCount++;
            
            // Apply decay using the FinalOpportunityScore's built-in method
            FinalOpportunityScore decayedScore = score.applyDecay(newTimestamp);
            
            log.debug("Applied decay to {}: decision {} -> {} (decay #{})",
                    score.getScripCode(),
                    score.getDecision(),
                    decayedScore.getDecision(),
                    state.decayCount);
            
            return decayedScore;
        }
        
        return score;
    }
    
    /**
     * Check if a signal has decayed below threshold
     */
    public boolean isSignalStale(String scripCode, double staleThreshold) {
        SignalState state = signalStates.get(scripCode);
        if (state == null) return false;
        
        // After multiple decays, signal becomes stale
        double effectiveScore = state.initialScore * Math.pow(DECAY_FACTOR, state.decayCount);
        return effectiveScore < staleThreshold;
    }
    
    /**
     * Clear stale signals from tracking
     */
    public void clearStaleSignals(double staleThreshold) {
        signalStates.entrySet().removeIf(entry -> {
            double effectiveScore = entry.getValue().initialScore * 
                    Math.pow(DECAY_FACTOR, entry.getValue().decayCount);
            return effectiveScore < staleThreshold;
        });
    }
    
    /**
     * Get decay count for a signal
     */
    public int getDecayCount(String scripCode) {
        SignalState state = signalStates.get(scripCode);
        return state != null ? state.decayCount : 0;
    }
    
    /**
     * Get bars since last follow through
     */
    public int getBarsSinceFollowThrough(String scripCode) {
        SignalState state = signalStates.get(scripCode);
        return state != null ? state.barsSinceSignal : 0;
    }
}
