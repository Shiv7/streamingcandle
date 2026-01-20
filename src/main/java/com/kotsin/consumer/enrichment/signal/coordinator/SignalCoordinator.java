package com.kotsin.consumer.enrichment.signal.coordinator;

import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.model.TechnicalContext;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal.Direction;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal.Horizon;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SignalCoordinator - THE CENTRAL BRAIN FOR ALL SIGNAL GENERATION
 *
 * PHILOSOPHY:
 * - ONE signal coordinator, not multiple independent producers
 * - HTF (Higher TimeFrame) determines DIRECTION
 * - LTF (Lower TimeFrame) determines ENTRY/EXIT timing
 * - Max 1-3 signals per instrument per day (not 25+!)
 * - Active signals tracked until invalidated or expired
 *
 * THIS CLASS SOLVES:
 * 1. Signal spam (700+ signals/day → 50-100 high quality)
 * 2. No coordination between producers
 * 3. 5-minute dedup window too short
 * 4. Category bypass in dedup key
 * 5. No direction flip control
 * 6. No active signal state tracking
 * 7. No MTF validation
 *
 * ALL SIGNAL PRODUCERS MUST GO THROUGH THIS CLASS.
 * Direct publishing to trading-signals-v2 is FORBIDDEN.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalCoordinator {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    // ======================== CONFIGURATION ========================

    // Horizon-based dedup windows (in milliseconds)
    private static final long SCALP_DEDUP_WINDOW_MS = 15 * 60 * 1000;      // 15 minutes
    private static final long SWING_DEDUP_WINDOW_MS = 60 * 60 * 1000;      // 60 minutes
    private static final long POSITIONAL_DEDUP_WINDOW_MS = 4 * 60 * 60 * 1000; // 4 hours

    // Direction flip cooldown - can't flip direction within this window
    private static final long DIRECTION_FLIP_COOLDOWN_MS = 30 * 60 * 1000; // 30 minutes

    // Daily signal limit per family
    private static final int MAX_SIGNALS_PER_FAMILY_PER_DAY = 5;

    // Minimum confidence for MTF override
    private static final double MTF_OVERRIDE_MIN_CONFIDENCE = 0.75;

    // Topic
    private static final String TRADING_SIGNALS_TOPIC = "trading-signals-v2";

    // ======================== STATE TRACKING ========================

    // Active signals per family - key: familyId, value: active signal
    private final Map<String, ActiveSignal> activeSignals = new ConcurrentHashMap<>();

    // Last signal per family:direction - key: familyId:direction, value: timestamp
    private final Map<String, Long> lastSignalTimestamp = new ConcurrentHashMap<>();

    // Last direction per family - key: familyId, value: DirectionWithTime
    private final Map<String, DirectionWithTime> lastDirectionByFamily = new ConcurrentHashMap<>();

    // Daily signal count per family - resets at midnight
    private final Map<String, AtomicInteger> dailySignalCount = new ConcurrentHashMap<>();
    private final AtomicLong currentDay = new AtomicLong(getCurrentDay());

    // Statistics
    private final AtomicLong totalReceived = new AtomicLong(0);
    private final AtomicLong totalPublished = new AtomicLong(0);
    private final AtomicLong blockedByDedup = new AtomicLong(0);
    private final AtomicLong blockedByFlipCooldown = new AtomicLong(0);
    private final AtomicLong blockedByActiveSignal = new AtomicLong(0);
    private final AtomicLong blockedByMtf = new AtomicLong(0);
    private final AtomicLong blockedByDailyLimit = new AtomicLong(0);

    // ======================== MAIN COORDINATION ========================

    /**
     * Submit a signal for coordination.
     * This is the ONLY method that should be used to generate signals.
     * All producers (SignalGenerator, PatternSignalPublisher, FUDKIIProcessor, etc.)
     * must call this method instead of publishing directly.
     *
     * @param signal The signal to coordinate
     * @param quantScore The enriched quant score (for MTF validation)
     * @param source The source producer (for logging)
     * @return true if signal was published, false if blocked
     */
    public boolean submitSignal(TradingSignal signal, EnrichedQuantScore quantScore, String source) {
        if (signal == null) return false;

        totalReceived.incrementAndGet();
        String familyId = signal.getFamilyId();
        Direction direction = signal.getDirection();
        Horizon horizon = signal.getHorizon();
        long now = System.currentTimeMillis();

        List<String> blockReasons = new ArrayList<>();

        // ======================== CHECK 1: Daily Reset ========================
        resetDailyCountersIfNeeded();

        // ======================== CHECK 2: Daily Limit ========================
        AtomicInteger dayCount = dailySignalCount.computeIfAbsent(familyId, k -> new AtomicInteger(0));
        if (dayCount.get() >= MAX_SIGNALS_PER_FAMILY_PER_DAY) {
            blockedByDailyLimit.incrementAndGet();
            log.info("[COORD] BLOCKED {} {} from {} | DAILY_LIMIT_REACHED ({}/{})",
                    familyId, direction, source, dayCount.get(), MAX_SIGNALS_PER_FAMILY_PER_DAY);
            return false;
        }

        // ======================== CHECK 3: Active Signal ========================
        ActiveSignal active = activeSignals.get(familyId);
        if (active != null && !active.isExpired() && !active.isInvalidated()) {
            // We have an active signal that's still valid
            if (active.direction == direction) {
                // Same direction - duplicate
                blockedByActiveSignal.incrementAndGet();
                log.debug("[COORD] BLOCKED {} {} from {} | ACTIVE_SIGNAL_EXISTS (same direction)",
                        familyId, direction, source);
                return false;
            } else {
                // Opposite direction - need strong reason to flip
                if (signal.getConfidence() < MTF_OVERRIDE_MIN_CONFIDENCE) {
                    blockedByActiveSignal.incrementAndGet();
                    log.info("[COORD] BLOCKED {} {} from {} | ACTIVE_OPPOSITE_SIGNAL (conf={:.0f}% < {:.0f}% required)",
                            familyId, direction, source, signal.getConfidence() * 100, MTF_OVERRIDE_MIN_CONFIDENCE * 100);
                    return false;
                }
                // High confidence flip - invalidate old signal
                active.invalidated = true;
                log.info("[COORD] {} INVALIDATING active {} signal due to high-conf {} signal (conf={:.0f}%)",
                        familyId, active.direction, direction, signal.getConfidence() * 100);
            }
        }

        // ======================== CHECK 4: Direction Flip Cooldown ========================
        DirectionWithTime lastDir = lastDirectionByFamily.get(familyId);
        if (lastDir != null && lastDir.direction != direction) {
            long timeSinceFlip = now - lastDir.timestamp;
            if (timeSinceFlip < DIRECTION_FLIP_COOLDOWN_MS) {
                // Flip too soon - need very high confidence to override
                if (signal.getConfidence() < MTF_OVERRIDE_MIN_CONFIDENCE) {
                    blockedByFlipCooldown.incrementAndGet();
                    log.info("[COORD] BLOCKED {} {} from {} | FLIP_TOO_SOON ({}min < 30min, was {})",
                            familyId, direction, source, timeSinceFlip / 60000, lastDir.direction);
                    return false;
                }
                log.info("[COORD] {} ALLOWING early flip from {} to {} (conf={:.0f}% >= {:.0f}%)",
                        familyId, lastDir.direction, direction, signal.getConfidence() * 100, MTF_OVERRIDE_MIN_CONFIDENCE * 100);
            }
        }

        // ======================== CHECK 5: Horizon-Based Dedup ========================
        String dedupKey = familyId + ":" + direction;
        Long lastSignal = lastSignalTimestamp.get(dedupKey);
        long dedupWindow = getDedupWindow(horizon);

        if (lastSignal != null && now - lastSignal < dedupWindow) {
            blockedByDedup.incrementAndGet();
            log.debug("[COORD] BLOCKED {} {} from {} | DEDUP ({}min < {}min window)",
                    familyId, direction, source, (now - lastSignal) / 60000, dedupWindow / 60000);
            return false;
        }

        // ======================== CHECK 6: MTF Validation ========================
        // HTF (Higher TimeFrame) must agree with signal direction
        if (quantScore != null && quantScore.getTechnicalContext() != null) {
            TechnicalContext tech = quantScore.getTechnicalContext();
            boolean mtfValid = validateMtfAlignment(tech, direction);

            if (!mtfValid && signal.getConfidence() < MTF_OVERRIDE_MIN_CONFIDENCE) {
                blockedByMtf.incrementAndGet();
                log.info("[COORD] BLOCKED {} {} from {} | MTF_MISALIGNED (HTF disagrees, conf={:.0f}% < {:.0f}%)",
                        familyId, direction, source, signal.getConfidence() * 100, MTF_OVERRIDE_MIN_CONFIDENCE * 100);
                return false;
            }

            if (!mtfValid) {
                log.info("[COORD] {} {} MTF misaligned but high-conf override (conf={:.0f}%)",
                        familyId, direction, signal.getConfidence() * 100);
            }
        }

        // ======================== ALL CHECKS PASSED - PUBLISH ========================

        // Update state
        lastSignalTimestamp.put(dedupKey, now);
        lastDirectionByFamily.put(familyId, new DirectionWithTime(direction, now));
        dayCount.incrementAndGet();

        // Create active signal
        ActiveSignal newActive = new ActiveSignal(
                signal.getSignalId(),
                familyId,
                direction,
                horizon,
                now,
                getExpiryTime(horizon, now),
                signal.getStopLoss(),
                signal.getTarget1()
        );
        activeSignals.put(familyId, newActive);

        // Publish to Kafka
        try {
            kafkaTemplate.send(TRADING_SIGNALS_TOPIC, familyId, signal);
            totalPublished.incrementAndGet();

            log.info("[COORD] PUBLISHED {} {} {} from {} | conf={:.0f}% | dayCount={}/{} | active={}",
                    familyId, direction, horizon, source,
                    signal.getConfidence() * 100,
                    dayCount.get(), MAX_SIGNALS_PER_FAMILY_PER_DAY,
                    activeSignals.size());

            return true;
        } catch (Exception e) {
            log.error("[COORD] Failed to publish {} {} from {}: {}",
                    familyId, direction, source, e.getMessage());
            // Rollback state
            dayCount.decrementAndGet();
            activeSignals.remove(familyId);
            return false;
        }
    }

    /**
     * Update signal state based on price movement.
     * Call this on every price update to track SL/target hits.
     *
     * @param familyId Family identifier
     * @param currentPrice Current price
     */
    public void updateSignalState(String familyId, double currentPrice) {
        ActiveSignal active = activeSignals.get(familyId);
        if (active == null) return;

        // Check expiry
        if (active.isExpired()) {
            log.info("[COORD] {} active {} signal EXPIRED", familyId, active.direction);
            activeSignals.remove(familyId);
            return;
        }

        // Check stop loss
        if (active.stopLoss != null) {
            boolean slHit = (active.direction == Direction.LONG && currentPrice <= active.stopLoss) ||
                           (active.direction == Direction.SHORT && currentPrice >= active.stopLoss);
            if (slHit) {
                log.info("[COORD] {} active {} signal SL_HIT at {:.2f}",
                        familyId, active.direction, currentPrice);
                active.invalidated = true;
                activeSignals.remove(familyId);
                return;
            }
        }

        // Check target
        if (active.target != null) {
            boolean targetHit = (active.direction == Direction.LONG && currentPrice >= active.target) ||
                               (active.direction == Direction.SHORT && currentPrice <= active.target);
            if (targetHit) {
                log.info("[COORD] {} active {} signal TARGET_HIT at {:.2f}",
                        familyId, active.direction, currentPrice);
                active.invalidated = true;
                activeSignals.remove(familyId);
            }
        }
    }

    /**
     * Invalidate an active signal (e.g., when conditions change)
     *
     * @param familyId Family identifier
     * @param reason Reason for invalidation
     */
    public void invalidateSignal(String familyId, String reason) {
        ActiveSignal active = activeSignals.get(familyId);
        if (active != null) {
            active.invalidated = true;
            log.info("[COORD] {} active {} signal INVALIDATED: {}",
                    familyId, active.direction, reason);
            activeSignals.remove(familyId);
        }
    }

    // ======================== MTF VALIDATION ========================

    /**
     * Validate that signal direction aligns with MTF (Multi-TimeFrame) analysis.
     *
     * PHILOSOPHY:
     * - HTF (15m, 30m, 1H) determines the TREND DIRECTION
     * - LTF (1m, 3m, 5m) determines the ENTRY TIMING
     * - Don't trade LONG when HTF is bearish, even if LTF shows a bounce
     * - Don't trade SHORT when HTF is bullish, even if LTF shows a dip
     *
     * @param tech Technical context with MTF data
     * @param direction Signal direction
     * @return true if MTF supports the direction
     */
    private boolean validateMtfAlignment(TechnicalContext tech, Direction direction) {
        if (tech == null) return true; // No data, allow signal

        boolean isLong = direction == Direction.LONG;

        // Check MTF aggregated direction
        if (tech.getMtfAggregatedDirection() != null) {
            switch (tech.getMtfAggregatedDirection()) {
                case BULLISH:
                    if (!isLong) {
                        log.debug("[MTF] SHORT blocked - MTF is BULLISH");
                        return false;
                    }
                    break;
                case BEARISH:
                    if (isLong) {
                        log.debug("[MTF] LONG blocked - MTF is BEARISH");
                        return false;
                    }
                    break;
                case CONFLICTING:
                    // Conflicting signals - allow with warning
                    log.debug("[MTF] MTF CONFLICTING - allowing {} with caution", direction);
                    return true;
                case NEUTRAL:
                    // Neutral - allow
                    return true;
            }
        }

        // Check HTF SuperTrend (15m+ bullish percentage)
        if (tech.getMtfBullishPercentage() > 0) {
            double htfBullishPct = tech.getMtfBullishPercentage();

            // LONG requires at least 50% HTF bullish
            if (isLong && htfBullishPct < 0.5) {
                log.debug("[MTF] LONG blocked - HTF only {:.0f}% bullish", htfBullishPct * 100);
                return false;
            }

            // SHORT requires at least 50% HTF bearish (i.e., <50% bullish)
            if (!isLong && htfBullishPct > 0.5) {
                log.debug("[MTF] SHORT blocked - HTF {:.0f}% bullish", htfBullishPct * 100);
                return false;
            }
        }

        // Check HTF specific flag
        if (isLong && !tech.isMtfHtfBullish()) {
            log.debug("[MTF] LONG blocked - HTF not bullish");
            return false;
        }
        if (!isLong && tech.isMtfHtfBullish()) {
            log.debug("[MTF] SHORT blocked - HTF is bullish");
            return false;
        }

        return true;
    }

    // ======================== HELPER METHODS ========================

    private long getDedupWindow(Horizon horizon) {
        if (horizon == null) return SWING_DEDUP_WINDOW_MS;
        return switch (horizon) {
            case SCALP -> SCALP_DEDUP_WINDOW_MS;
            case SWING, INTRADAY -> SWING_DEDUP_WINDOW_MS;
            case POSITIONAL -> POSITIONAL_DEDUP_WINDOW_MS;
        };
    }

    private long getExpiryTime(Horizon horizon, long now) {
        if (horizon == null) return now + 4 * 60 * 60 * 1000; // 4 hours default
        return switch (horizon) {
            case SCALP -> now + 30 * 60 * 1000;        // 30 minutes
            case SWING, INTRADAY -> now + 4 * 60 * 60 * 1000;  // 4 hours
            case POSITIONAL -> now + 24 * 60 * 60 * 1000;      // 24 hours
        };
    }

    private void resetDailyCountersIfNeeded() {
        long today = getCurrentDay();
        if (today != currentDay.get()) {
            currentDay.set(today);
            dailySignalCount.clear();
            log.info("[COORD] Daily counters reset for new day");
        }
    }

    private long getCurrentDay() {
        return System.currentTimeMillis() / (24 * 60 * 60 * 1000);
    }

    // ======================== STATISTICS ========================

    /**
     * Get coordinator statistics
     */
    public String getStats() {
        return String.format(
                "[COORD] received=%d published=%d (%.1f%%) | blocked: dedup=%d flip=%d active=%d mtf=%d daily=%d | active_signals=%d",
                totalReceived.get(),
                totalPublished.get(),
                totalReceived.get() > 0 ? (totalPublished.get() * 100.0 / totalReceived.get()) : 0,
                blockedByDedup.get(),
                blockedByFlipCooldown.get(),
                blockedByActiveSignal.get(),
                blockedByMtf.get(),
                blockedByDailyLimit.get(),
                activeSignals.size()
        );
    }

    /**
     * Check if a family has an active signal
     */
    public boolean hasActiveSignal(String familyId) {
        ActiveSignal active = activeSignals.get(familyId);
        return active != null && !active.isExpired() && !active.isInvalidated();
    }

    /**
     * Get active signal direction for a family
     */
    public Direction getActiveDirection(String familyId) {
        ActiveSignal active = activeSignals.get(familyId);
        if (active != null && !active.isExpired() && !active.isInvalidated()) {
            return active.direction;
        }
        return null;
    }

    // ======================== INNER CLASSES ========================

    @Data
    private static class DirectionWithTime {
        final Direction direction;
        final long timestamp;

        DirectionWithTime(Direction direction, long timestamp) {
            this.direction = direction;
            this.timestamp = timestamp;
        }
    }

    @Data
    private static class ActiveSignal {
        final String signalId;
        final String familyId;
        final Direction direction;
        final Horizon horizon;
        final long generatedAt;
        final long expiresAt;
        final Double stopLoss;
        final Double target;
        boolean invalidated = false;

        ActiveSignal(String signalId, String familyId, Direction direction, Horizon horizon,
                     long generatedAt, long expiresAt, Double stopLoss, Double target) {
            this.signalId = signalId;
            this.familyId = familyId;
            this.direction = direction;
            this.horizon = horizon;
            this.generatedAt = generatedAt;
            this.expiresAt = expiresAt;
            this.stopLoss = stopLoss;
            this.target = target;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }

        boolean isInvalidated() {
            return invalidated;
        }
    }
}
