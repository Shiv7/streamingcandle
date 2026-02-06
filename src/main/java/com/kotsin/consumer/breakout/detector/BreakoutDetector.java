package com.kotsin.consumer.breakout.detector;

import com.kotsin.consumer.breakout.model.BreakoutEvent;
import com.kotsin.consumer.breakout.model.BreakoutEvent.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BreakoutDetector - Detects breakouts and retests of key levels.
 *
 * Features:
 * - Multi-level breakout detection
 * - Volume and momentum confirmation
 * - Retest identification
 * - Failed breakout detection
 */
@Component
@Slf4j
public class BreakoutDetector {

    // Active breakouts per symbol
    private final Map<String, List<BreakoutEvent>> activeBreakouts = new ConcurrentHashMap<>();

    // Key levels per symbol
    private final Map<String, List<KeyLevel>> keyLevels = new ConcurrentHashMap<>();

    // Configuration
    private static final double BREAKOUT_THRESHOLD = 0.001;  // 0.1% penetration for breakout
    private static final double RETEST_THRESHOLD = 0.003;    // 0.3% tolerance for retest
    private static final double VOLUME_CONFIRM_RATIO = 1.5;  // 1.5x average volume
    private static final int MAX_ACTIVE_BREAKOUTS = 20;
    private static final double LEVEL_MATCH_TOLERANCE = 0.0005; // 0.05% tolerance for matching levels
    private static final int STALE_EXPIRY_CANDLES = 12;      // Remove stale levels after 12 candles (~1 hour on 5m)

    /**
     * Check for breakouts against registered levels.
     *
     * @param symbol        Symbol identifier
     * @param timeframe     Timeframe
     * @param high          Current high
     * @param low           Current low
     * @param close         Current close
     * @param volume        Current volume
     * @param avgVolume     Average volume
     * @param rsi           Current RSI (optional)
     * @param macdHistogram MACD histogram (optional)
     * @return List of detected breakout events
     */
    public List<BreakoutEvent> detect(String symbol, String timeframe,
                                       double high, double low, double close,
                                       double volume, double avgVolume,
                                       Double rsi, Double macdHistogram) {

        List<BreakoutEvent> detectedBreakouts = new ArrayList<>();
        List<KeyLevel> levels = keyLevels.getOrDefault(symbol, Collections.emptyList());

        for (KeyLevel level : levels) {
            BreakoutEvent event = checkBreakout(symbol, timeframe, level,
                high, low, close, volume, avgVolume, rsi, macdHistogram);

            if (event != null) {
                detectedBreakouts.add(event);
                addActiveBreakout(symbol, event);
            }
        }

        // Check for retests of existing breakouts
        List<BreakoutEvent> retests = checkRetests(symbol, timeframe, high, low, close);
        detectedBreakouts.addAll(retests);

        // Check for failed breakouts
        checkFailedBreakouts(symbol, high, low, close);

        return detectedBreakouts;
    }

    /**
     * Register a key level to watch for breakouts.
     */
    public void registerLevel(String symbol, double levelPrice, LevelSource source, String description) {
        KeyLevel level = new KeyLevel();
        level.price = levelPrice;
        level.source = source;
        level.description = description;
        level.registeredAt = Instant.now();
        level.touchCount = 0;
        level.isBroken = false;

        keyLevels.computeIfAbsent(symbol, s -> new ArrayList<>()).add(level);
    }

    /**
     * Register multiple levels at once.
     */
    public void registerLevels(String symbol, List<LevelInfo> levels) {
        for (LevelInfo info : levels) {
            registerLevel(symbol, info.price, info.source, info.description);
        }
    }

    /**
     * Clear all levels for a symbol. Only use on day boundary (session reset).
     */
    public void clearLevels(String symbol) {
        keyLevels.remove(symbol);
        activeBreakouts.remove(symbol);
    }

    /**
     * Update levels for a symbol by merging new levels into existing state.
     * Preserves touchCount, isBroken, and registeredAt for matched levels.
     * New levels are added; absent levels are marked stale and expired after STALE_EXPIRY_CANDLES.
     */
    public void updateLevels(String symbol, List<LevelInfo> newLevels) {
        List<KeyLevel> existing = keyLevels.get(symbol);

        if (existing == null || existing.isEmpty()) {
            // No existing levels — just register fresh
            registerLevels(symbol, newLevels);
            return;
        }

        List<KeyLevel> merged = new ArrayList<>();
        Set<Integer> matchedExistingIndices = new HashSet<>();
        Set<Integer> matchedNewIndices = new HashSet<>();

        // Match new levels to existing by price (within tolerance) + source
        for (int n = 0; n < newLevels.size(); n++) {
            LevelInfo newLevel = newLevels.get(n);
            boolean found = false;

            for (int e = 0; e < existing.size(); e++) {
                if (matchedExistingIndices.contains(e)) continue;

                KeyLevel existingLevel = existing.get(e);
                if (levelsMatch(existingLevel, newLevel)) {
                    // Preserve existing state, update description if changed
                    existingLevel.staleSince = null; // Clear staleness — level is still active
                    existingLevel.staleCandles = 0;
                    existingLevel.description = newLevel.description;
                    merged.add(existingLevel);
                    matchedExistingIndices.add(e);
                    matchedNewIndices.add(n);
                    found = true;
                    break;
                }
            }

            if (!found) {
                // Genuinely new level
                KeyLevel kl = new KeyLevel();
                kl.price = newLevel.price;
                kl.source = newLevel.source;
                kl.description = newLevel.description;
                kl.registeredAt = Instant.now();
                kl.touchCount = 0;
                kl.isBroken = false;
                kl.staleSince = null;
                kl.staleCandles = 0;
                merged.add(kl);
                matchedNewIndices.add(n);
            }
        }

        // Handle unmatched existing levels — mark as stale or expire
        for (int e = 0; e < existing.size(); e++) {
            if (matchedExistingIndices.contains(e)) continue;

            KeyLevel staleLevel = existing.get(e);
            if (staleLevel.staleSince == null) {
                staleLevel.staleSince = Instant.now();
                staleLevel.staleCandles = 1;
            } else {
                staleLevel.staleCandles++;
            }

            // Keep stale levels until they exceed expiry threshold
            if (staleLevel.staleCandles <= STALE_EXPIRY_CANDLES) {
                merged.add(staleLevel);
            } else {
                log.debug("[BREAKOUT] {} Expiring stale level: {} {} (stale for {} candles)",
                    symbol, staleLevel.source, staleLevel.description, staleLevel.staleCandles);
            }
        }

        keyLevels.put(symbol, merged);

        log.debug("[BREAKOUT] {} Updated levels: {} total ({} preserved, {} new, {} stale)",
            symbol, merged.size(), matchedExistingIndices.size(),
            newLevels.size() - matchedExistingIndices.size(),
            merged.size() - newLevels.size());
    }

    /**
     * Check if an existing KeyLevel matches a new LevelInfo by price proximity and source.
     */
    private boolean levelsMatch(KeyLevel existing, LevelInfo newLevel) {
        if (existing.source != newLevel.source) return false;
        double priceDiff = Math.abs(existing.price - newLevel.price);
        double tolerance = existing.price * LEVEL_MATCH_TOLERANCE;
        return priceDiff <= tolerance;
    }

    /**
     * Expire old breakouts that predate the session start.
     * Call this on day boundary / session reset.
     */
    public void expireOldBreakouts(String symbol, Instant sessionStart) {
        List<BreakoutEvent> actives = activeBreakouts.get(symbol);
        if (actives == null || actives.isEmpty()) return;

        int before = actives.size();
        actives.removeIf(b -> b.getTimestamp() != null && b.getTimestamp().isBefore(sessionStart));
        int removed = before - actives.size();

        if (removed > 0) {
            log.debug("[BREAKOUT] {} Expired {} old breakouts from previous session", symbol, removed);
        }
    }

    /**
     * Check for breakout at a specific level.
     */
    private BreakoutEvent checkBreakout(String symbol, String timeframe, KeyLevel level,
                                         double high, double low, double close,
                                         double volume, double avgVolume,
                                         Double rsi, Double macdHistogram) {

        if (level.isBroken) {
            return null;
        }

        double levelPrice = level.price;
        double threshold = levelPrice * BREAKOUT_THRESHOLD;

        BreakoutDirection direction = null;
        double penetration = 0;

        // Check bullish breakout (price above resistance)
        if (high > levelPrice + threshold) {
            direction = BreakoutDirection.BULLISH;
            penetration = high - levelPrice;
        }
        // Check bearish breakout (price below support)
        else if (low < levelPrice - threshold) {
            direction = BreakoutDirection.BEARISH;
            penetration = levelPrice - low;
        }

        if (direction == null) {
            // No breakout, but check for touch
            if (Math.abs(high - levelPrice) < threshold || Math.abs(low - levelPrice) < threshold) {
                level.touchCount++;
            }
            return null;
        }

        // Breakout detected - build event
        level.isBroken = true;

        // Calculate confirmations
        boolean volumeConfirmed = volume > avgVolume * VOLUME_CONFIRM_RATIO;
        boolean closeConfirmed = direction == BreakoutDirection.BULLISH ?
            close > levelPrice : close < levelPrice;
        boolean momentumConfirmed = checkMomentumConfirmation(direction, rsi, macdHistogram);

        int confirmationCount = 0;
        if (volumeConfirmed) confirmationCount++;
        if (closeConfirmed) confirmationCount++;
        if (momentumConfirmed) confirmationCount++;

        // Calculate strength
        double volumeRatio = avgVolume > 0 ? volume / avgVolume : 1;
        double penetrationPercent = levelPrice > 0 ? penetration / levelPrice * 100 : 0;
        double strengthScore = calculateStrengthScore(volumeRatio, penetrationPercent,
            confirmationCount, level.touchCount);
        BreakoutStrength strength = determineStrength(strengthScore);

        return BreakoutEvent.builder()
            .symbol(symbol)
            .timestamp(Instant.now())
            .timeframe(timeframe)
            .type(BreakoutType.BREAKOUT)
            .direction(direction)
            .breakoutLevel(levelPrice)
            .breakoutPrice(direction == BreakoutDirection.BULLISH ? high : low)
            .penetration(penetration)
            .penetrationPercent(penetrationPercent)
            .levelSource(level.source)
            .levelDescription(level.description)
            .volumeConfirmed(volumeConfirmed)
            .volumeRatio(volumeRatio)
            .closeConfirmed(closeConfirmed)
            .momentumConfirmed(momentumConfirmed)
            .confirmationCount(confirmationCount)
            .strength(strength)
            .strengthScore(strengthScore)
            .hasRetested(false)
            .isFailed(false)
            .touchesBeforeBreak(level.touchCount)
            .build();
    }

    /**
     * Check for retests of active breakouts.
     */
    private List<BreakoutEvent> checkRetests(String symbol, String timeframe,
                                              double high, double low, double close) {
        List<BreakoutEvent> retests = new ArrayList<>();
        List<BreakoutEvent> actives = activeBreakouts.get(symbol);

        if (actives == null) return retests;

        for (BreakoutEvent breakout : actives) {
            if (breakout.isHasRetested() || breakout.isFailed()) {
                continue;
            }

            double level = breakout.getBreakoutLevel();
            double tolerance = level * RETEST_THRESHOLD;

            boolean isRetest = false;
            double retestPrice = 0;

            if (breakout.isBullish()) {
                // Bullish breakout - retest when price comes back to level from above
                if (low <= level + tolerance && low >= level - tolerance && close > level) {
                    isRetest = true;
                    retestPrice = low;
                }
            } else {
                // Bearish breakout - retest when price comes back to level from below
                if (high >= level - tolerance && high <= level + tolerance && close < level) {
                    isRetest = true;
                    retestPrice = high;
                }
            }

            if (isRetest) {
                boolean held = breakout.isBullish() ? close > level : close < level;
                RetestQuality quality = determineRetestQuality(retestPrice, level, close, breakout.getDirection());

                breakout.recordRetest(Instant.now(), retestPrice, held, quality);

                // Create retest event
                BreakoutEvent retestEvent = BreakoutEvent.builder()
                    .symbol(symbol)
                    .timestamp(Instant.now())
                    .timeframe(timeframe)
                    .type(BreakoutType.RETEST)
                    .direction(breakout.getDirection())
                    .breakoutLevel(level)
                    .breakoutPrice(close)
                    .levelSource(breakout.getLevelSource())
                    .levelDescription(breakout.getLevelDescription() + " (Retest)")
                    .hasRetested(true)
                    .retestTime(Instant.now())
                    .retestPrice(retestPrice)
                    .retestHeld(held)
                    .retestQuality(quality)
                    .build();

                retests.add(retestEvent);
            }
        }

        return retests;
    }

    /**
     * Check if any active breakouts have failed.
     */
    private void checkFailedBreakouts(String symbol, double high, double low, double close) {
        List<BreakoutEvent> actives = activeBreakouts.get(symbol);

        if (actives == null) return;

        for (BreakoutEvent breakout : actives) {
            if (breakout.isFailed()) continue;

            double level = breakout.getBreakoutLevel();

            // Check for failure (price significantly reversed through level)
            if (breakout.isBullish()) {
                if (close < level * 0.995) {  // 0.5% below level
                    breakout.markAsFailed(Instant.now());
                }
            } else {
                if (close > level * 1.005) {  // 0.5% above level
                    breakout.markAsFailed(Instant.now());
                }
            }

            // Update max move
            breakout.updateMaxMove(close);
        }
    }

    private boolean checkMomentumConfirmation(BreakoutDirection direction, Double rsi, Double macdHist) {
        if (rsi == null && macdHist == null) return false;

        boolean rsiConfirm = false;
        boolean macdConfirm = false;

        if (rsi != null) {
            rsiConfirm = direction == BreakoutDirection.BULLISH ? rsi > 50 : rsi < 50;
        }

        if (macdHist != null) {
            macdConfirm = direction == BreakoutDirection.BULLISH ? macdHist > 0 : macdHist < 0;
        }

        return rsiConfirm || macdConfirm;
    }

    private double calculateStrengthScore(double volumeRatio, double penetrationPercent,
                                           int confirmations, int touchCount) {
        double score = 0;

        // Volume contribution (max 30)
        score += Math.min(30, volumeRatio * 15);

        // Penetration contribution (max 25)
        score += Math.min(25, penetrationPercent * 50);

        // Confirmation contribution (max 30)
        score += confirmations * 10;

        // Touch count contribution (max 15) - more touches = stronger breakout
        score += Math.min(15, touchCount * 3);

        return Math.min(100, score);
    }

    private BreakoutStrength determineStrength(double score) {
        if (score >= 70) return BreakoutStrength.STRONG;
        if (score >= 40) return BreakoutStrength.MODERATE;
        return BreakoutStrength.WEAK;
    }

    private RetestQuality determineRetestQuality(double retestPrice, double level,
                                                  double close, BreakoutDirection direction) {
        double penetration = Math.abs(retestPrice - level) / level * 100;
        double bounceSize = Math.abs(close - retestPrice) / level * 100;

        if (penetration < 0.1 && bounceSize > 0.2) {
            return RetestQuality.PERFECT;
        } else if (penetration < 0.3 && bounceSize > 0.1) {
            return RetestQuality.GOOD;
        } else if (penetration < 0.5) {
            return RetestQuality.POOR;
        }
        return RetestQuality.FAILED;
    }

    private void addActiveBreakout(String symbol, BreakoutEvent event) {
        List<BreakoutEvent> actives = activeBreakouts.computeIfAbsent(symbol, s -> new ArrayList<>());
        actives.add(event);

        // Trim old breakouts
        while (actives.size() > MAX_ACTIVE_BREAKOUTS) {
            actives.remove(0);
        }
    }

    // ==================== PUBLIC GETTERS ====================

    public List<BreakoutEvent> getActiveBreakouts(String symbol) {
        return activeBreakouts.getOrDefault(symbol, Collections.emptyList());
    }

    public List<BreakoutEvent> getConfirmedBreakouts(String symbol) {
        return activeBreakouts.getOrDefault(symbol, Collections.emptyList())
            .stream()
            .filter(BreakoutEvent::isConfirmed)
            .filter(b -> !b.isFailed())
            .toList();
    }

    public List<KeyLevel> getKeyLevels(String symbol) {
        return keyLevels.getOrDefault(symbol, Collections.emptyList());
    }

    // ==================== HELPER CLASSES ====================

    @Data
    public static class KeyLevel {
        double price;
        LevelSource source;
        String description;
        Instant registeredAt;
        int touchCount;
        boolean isBroken;
        Instant staleSince;   // When this level was first absent from new data
        int staleCandles;     // Number of candles since level became stale
    }

    @Data
    public static class LevelInfo {
        double price;
        LevelSource source;
        String description;

        public LevelInfo(double price, LevelSource source, String description) {
            this.price = price;
            this.source = source;
            this.description = description;
        }
    }
}
