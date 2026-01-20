package com.kotsin.consumer.enrichment.signal.tracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.config.KafkaTopics;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator;
import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.signal.confirmation.PriceActionConfirmer;
import com.kotsin.consumer.enrichment.signal.confirmation.PriceActionConfirmer.ConfirmationResult;
import com.kotsin.consumer.enrichment.signal.confirmation.PriceActionConfirmer.Recommendation;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PendingRRTracker - Tracks signals waiting for improved R:R ratio
 *
 * PHILOSOPHY:
 * - At signal generation time, R:R might not meet threshold (e.g., 2:1)
 * - But as price moves (via forward testing tick/candle data), R:R can IMPROVE
 * - This tracker monitors pending signals and PROMOTES them when R:R reaches threshold
 * - Context always matters - price movement can make a marginal signal into a great one
 *
 * MECHANICS:
 * 1. SignalGenerator publishes signals with R:R < threshold as "PENDING"
 * 2. This tracker stores them keyed by familyId
 * 3. On each price update, recalculates R:R based on new potential entry price
 * 4. When R:R reaches threshold, promotes signal to trading-signals-v2 topic
 * 5. Signals expire after timeout if R:R never improves
 *
 * R:R IMPROVEMENT SCENARIOS:
 * - LONG: Price pulls back toward SL = better entry = higher R:R
 * - SHORT: Price pushes up toward SL = better entry = higher R:R
 * - Target gets extended by momentum = higher R:R
 */
@Slf4j
@Service
public class PendingRRTracker {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    // Price Action Confirmer - validates direction alignment before promotion
    @Autowired(required = false)
    private PriceActionConfirmer priceActionConfirmer;

    // QuantScore calculator - for building EnrichedQuantScore from FamilyCandle
    @Autowired(required = false)
    private EnrichedQuantScoreCalculator quantScoreCalculator;

    @Autowired
    public PendingRRTracker(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    // Pending signals by familyId for quick lookup on price updates
    private final Map<String, List<PendingSignal>> pendingByFamily = new ConcurrentHashMap<>();

    // All pending signals by signalId
    private final Map<String, PendingSignal> pendingById = new ConcurrentHashMap<>();

    // Statistics
    private final AtomicInteger signalsTracked = new AtomicInteger(0);
    private final AtomicInteger signalsPromoted = new AtomicInteger(0);
    private final AtomicInteger signalsExpired = new AtomicInteger(0);
    private final AtomicInteger signalsInvalidated = new AtomicInteger(0);

    @Value("${pending.rr.tracker.enabled:true}")
    private boolean enabled;

    // Minimum R:R ratio required for promotion
    @Value("${pending.rr.tracker.min.rr:2.0}")
    private double minRiskReward;

    // How long to track a pending signal before expiring (minutes)
    @Value("${pending.rr.tracker.timeout.minutes:60}")
    private int timeoutMinutes;

    // Maximum pending signals per family
    @Value("${pending.rr.tracker.max.per.family:5}")
    private int maxPerFamily;

    // Minimum improvement threshold before rechecking (to avoid noise)
    @Value("${pending.rr.tracker.improvement.threshold.pct:0.1}")
    private double improvementThresholdPct;

    // ======================== SIGNAL TRACKING ========================

    /**
     * Track a pending signal (R:R below threshold)
     * Called by SignalGenerator when signal doesn't meet R:R criteria
     */
    public void trackPendingSignal(TradingSignal signal, double currentRR) {
        if (!enabled) {
            log.debug("[PENDING_RR] Tracking disabled, dropping signal {}", signal.getSignalId());
            return;
        }

        String familyId = signal.getFamilyId();
        String signalId = signal.getSignalId();

        // Check if already tracking
        if (pendingById.containsKey(signalId)) {
            log.debug("[PENDING_RR] Signal {} already tracked", signalId);
            return;
        }

        // Check family limit
        List<PendingSignal> familySignals = pendingByFamily.computeIfAbsent(familyId, k -> new ArrayList<>());
        if (familySignals.size() >= maxPerFamily) {
            // Remove oldest pending signal
            PendingSignal oldest = familySignals.stream()
                    .min(Comparator.comparing(PendingSignal::getTrackedAt))
                    .orElse(null);
            if (oldest != null) {
                removePending(oldest);
                log.debug("[PENDING_RR] Removed oldest pending {} to make room for new signal", oldest.getSignalId());
            }
        }

        // Create pending signal entry
        PendingSignal pending = PendingSignal.builder()
                .signalId(signalId)
                .familyId(familyId)
                .signal(signal)
                .initialRR(currentRR)
                .currentRR(currentRR)
                .bestRR(currentRR)
                .initialEntry(signal.getEntryPrice())
                .currentEntry(signal.getEntryPrice())
                .bestEntry(signal.getEntryPrice())
                .stopLoss(signal.getStopLoss())
                .target(signal.getTarget2())
                .trackedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofMinutes(timeoutMinutes)))
                .lastPriceUpdate(Instant.now())
                .priceUpdates(0)
                .build();

        pendingById.put(signalId, pending);
        familySignals.add(pending);
        signalsTracked.incrementAndGet();

        log.info("[PENDING_RR] Tracking {} {} {} | R:R={} < {} required | entry={} SL={} T={} | expires in {}min",
                signal.getDirection(), signal.getCategory(), familyId,
                String.format("%.2f", currentRR), String.format("%.1f", minRiskReward),
                String.format("%.2f", signal.getEntryPrice()), String.format("%.2f", signal.getStopLoss()), String.format("%.2f", signal.getTarget2()),
                timeoutMinutes);
    }

    // ======================== PRICE UPDATES ========================

    /**
     * Consume price data and update pending signals
     */
    @KafkaListener(
            topics = "${pending.rr.tracker.price.topic:" + KafkaTopics.FAMILY_CANDLE_1M + "}",
            groupId = "${pending.rr.tracker.group.id:pending-rr-tracker-v1}",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void consumePriceUpdate(String message) {
        if (!enabled || pendingById.isEmpty()) {
            return;
        }

        try {
            FamilyCandle candle = objectMapper.readValue(message, FamilyCandle.class);
            String familyId = candle.getFamilyId();

            List<PendingSignal> familySignals = pendingByFamily.get(familyId);
            if (familySignals == null || familySignals.isEmpty()) {
                return;
            }

            InstrumentCandle primary = candle.getPrimaryInstrumentOrFallback();
            if (primary == null) {
                return;
            }

            double high = primary.getHigh();
            double low = primary.getLow();
            double close = primary.getClose();

            // Process each pending signal for this family
            List<PendingSignal> toRemove = new ArrayList<>();
            for (PendingSignal pending : familySignals) {
                // Pass full candle for price action confirmation
                ProcessResult result = processPriceUpdate(pending, candle, high, low, close);

                if (result == ProcessResult.PROMOTED || result == ProcessResult.INVALIDATED) {
                    toRemove.add(pending);
                }
            }

            // Remove promoted/invalidated signals
            for (PendingSignal pending : toRemove) {
                removePending(pending);
            }

        } catch (Exception e) {
            log.debug("[PENDING_RR] Failed to process price update: {}", e.getMessage());
        }
    }

    /**
     * Process a price update for a pending signal
     * Now includes price action confirmation when R:R threshold is reached
     */
    private ProcessResult processPriceUpdate(PendingSignal pending, FamilyCandle candle,
                                              double high, double low, double close) {
        pending.setPriceUpdates(pending.getPriceUpdates() + 1);
        pending.setLastPriceUpdate(Instant.now());

        TradingSignal signal = pending.getSignal();
        boolean isLong = signal.isLong();

        // Check for invalidation (SL hit before promotion)
        if (isLong && low <= pending.getStopLoss()) {
            signalsInvalidated.incrementAndGet();
            log.info("[PENDING_RR] {} INVALIDATED (SL hit) | original entry={} SL={} low={}",
                    pending.getFamilyId(), String.format("%.2f", pending.getInitialEntry()), String.format("%.2f", pending.getStopLoss()), String.format("%.2f", low));
            return ProcessResult.INVALIDATED;
        }
        if (!isLong && high >= pending.getStopLoss()) {
            signalsInvalidated.incrementAndGet();
            log.info("[PENDING_RR] {} INVALIDATED (SL hit) | original entry={} SL={} high={}",
                    pending.getFamilyId(), String.format("%.2f", pending.getInitialEntry()), String.format("%.2f", pending.getStopLoss()), String.format("%.2f", high));
            return ProcessResult.INVALIDATED;
        }

        // Calculate new potential entry and R:R
        // For LONG: best entry is when price pulls back (use low as potential entry)
        // For SHORT: best entry is when price pushes up (use high as potential entry)
        double potentialEntry = isLong ? Math.max(low, pending.getStopLoss() * 1.003) : Math.min(high, pending.getStopLoss() * 0.997);

        // Ensure entry is valid (above SL for LONG, below SL for SHORT)
        if (isLong && potentialEntry <= pending.getStopLoss()) {
            potentialEntry = pending.getStopLoss() * 1.003; // 0.3% above SL minimum
        }
        if (!isLong && potentialEntry >= pending.getStopLoss()) {
            potentialEntry = pending.getStopLoss() * 0.997; // 0.3% below SL minimum
        }

        double stopDistance = Math.abs(potentialEntry - pending.getStopLoss());
        double targetDistance = Math.abs(pending.getTarget() - potentialEntry);
        double newRR = stopDistance > 0 ? targetDistance / stopDistance : 0;

        // Update tracking
        pending.setCurrentEntry(close); // Current market price
        pending.setCurrentRR(newRR);

        if (newRR > pending.getBestRR()) {
            pending.setBestRR(newRR);
            pending.setBestEntry(potentialEntry);
        }

        // Check for promotion (R:R reached threshold)
        if (newRR >= minRiskReward) {
            // NEW: Price Action Confirmation before promotion
            if (priceActionConfirmer != null) {
                // Build EnrichedQuantScore from FamilyCandle if calculator available
                EnrichedQuantScore quantScore = null;
                if (quantScoreCalculator != null) {
                    try {
                        quantScore = quantScoreCalculator.calculate(candle);
                    } catch (Exception e) {
                        log.debug("[PENDING_RR] Failed to calculate quantScore: {}", e.getMessage());
                    }
                }

                // Check price action confirmation
                ConfirmationResult confirmation = priceActionConfirmer.confirm(signal, candle, quantScore);

                if (confirmation.isConfirmed()) {
                    // PROMOTED: R:R good AND price action confirmed
                    promoteSignal(pending, potentialEntry, newRR, confirmation);
                    return ProcessResult.PROMOTED;
                } else if (confirmation.getRecommendation() == Recommendation.INVALIDATE) {
                    // INVALIDATED: Strong contradictory signals
                    signalsInvalidated.incrementAndGet();
                    log.info("[PENDING_RR] {} INVALIDATED (price action) | R:R={} but confirmation failed: {}",
                            pending.getFamilyId(), String.format("%.2f", newRR), confirmation.getNote());
                    pending.setInvalidationReason("PRICE_ACTION:" + String.join(",", confirmation.getFailedChecks()));
                    return ProcessResult.INVALIDATED;
                } else {
                    // AWAITING: R:R good but price action not yet confirmed
                    pending.setAwaitingConfirmation(true);
                    pending.setLastConfirmationScore(confirmation.getScore());
                    pending.setConfirmationAttempts(pending.getConfirmationAttempts() + 1);

                    // Log progress on first await or every 3 attempts
                    if (pending.getConfirmationAttempts() == 1 || pending.getConfirmationAttempts() % 3 == 0) {
                        log.info("[PENDING_RR] {} AWAITING confirmation | R:R={} OK | score={}/{} | attempts={}",
                                pending.getFamilyId(), String.format("%.2f", newRR), confirmation.getScore(), confirmation.getMinScore(),
                                pending.getConfirmationAttempts());
                    }

                    return ProcessResult.AWAITING_CONFIRMATION;
                }
            } else {
                // No confirmer - promote on R:R alone (backward compatible)
                promoteSignal(pending, potentialEntry, newRR, null);
                return ProcessResult.PROMOTED;
            }
        }

        // Log progress periodically (every 5 updates)
        if (pending.getPriceUpdates() % 5 == 0) {
            log.debug("[PENDING_RR] {} | R:R {}->{} (best={}) | need {} | updates={}",
                    pending.getFamilyId(), String.format("%.2f", pending.getInitialRR()), String.format("%.2f", newRR), String.format("%.2f", pending.getBestRR()),
                    String.format("%.1f", minRiskReward), pending.getPriceUpdates());
        }

        return ProcessResult.TRACKING;
    }

    /**
     * Promote a signal that now meets R:R criteria AND price action confirmed
     */
    private void promoteSignal(PendingSignal pending, double newEntry, double newRR,
                                ConfirmationResult confirmation) {
        TradingSignal original = pending.getSignal();
        boolean isLong = original.isLong();

        // Create updated signal with improved entry
        double stopDistance = Math.abs(newEntry - pending.getStopLoss());
        double target1 = isLong ? newEntry + (stopDistance * 0.5 * newRR) : newEntry - (stopDistance * 0.5 * newRR);
        double target2 = isLong ? newEntry + (stopDistance * newRR) : newEntry - (stopDistance * newRR);
        double target3 = isLong ? newEntry + (stopDistance * 1.5 * newRR) : newEntry - (stopDistance * 1.5 * newRR);

        // Build headline with confirmation info
        String headlineSuffix = " [R:R IMPROVED]";
        if (confirmation != null) {
            headlineSuffix += String.format(" [PA:%d/100]", confirmation.getScore());
        }

        // Build narrative with confirmation details
        StringBuilder narrativeAddition = new StringBuilder();
        narrativeAddition.append(String.format("\n\nR:R improved from %.2f to %.2f after price movement.", pending.getInitialRR(), newRR));
        if (confirmation != null && !confirmation.getPassedChecks().isEmpty()) {
            narrativeAddition.append("\n\nPrice Action Confirmed: ").append(String.join(", ", confirmation.getPassedChecks()));
        }

        // Build promoted signal with updated prices
        TradingSignal promoted = TradingSignal.builder()
                .signalId(original.getSignalId())
                .familyId(original.getFamilyId())
                .scripCode(original.getScripCode())
                .companyName(original.getCompanyName())
                .exchange(original.getExchange())
                .generatedAt(original.getGeneratedAt())
                .promotedAt(Instant.now())  // New field - when R:R was achieved
                .dataTimestamp(original.getDataTimestamp())
                .expiresAt(original.getExpiresAt())
                // Updated prices
                .currentPrice(pending.getCurrentEntry())
                .entryPrice(newEntry)
                .stopLoss(pending.getStopLoss())
                .target1(target1)
                .target2(target2)
                .target3(target3)
                // Original signal attributes
                .source(original.getSource())
                .category(original.getCategory())
                .direction(original.getDirection())
                .horizon(original.getHorizon())
                .urgency(TradingSignal.Urgency.IMMEDIATE) // Upgraded urgency since R:R improved
                .confidence(original.getConfidence())
                .confidenceBreakdown(original.getConfidenceBreakdown())
                .setupId(original.getSetupId())
                .patternId(original.getPatternId())
                .sequenceId(original.getSequenceId())
                .matchedEvents(original.getMatchedEvents())
                .patternProgress(original.getPatternProgress())
                .headline(original.getHeadline() + headlineSuffix)
                .narrative(original.getNarrative() + narrativeAddition.toString())
                .entryReasons(original.getEntryReasons())
                .historicalSuccessRate(original.getHistoricalSuccessRate())
                .historicalSampleCount(original.getHistoricalSampleCount())
                .positionSizeMultiplier(original.getPositionSizeMultiplier())
                .riskPercentage(original.getRiskPercentage())
                .build();

        // Publish to trading signals topic
        try {
            String json = objectMapper.writeValueAsString(promoted);
            kafkaTemplate.send(KafkaTopics.TRADING_SIGNALS_V2, pending.getFamilyId(), json);
            signalsPromoted.incrementAndGet();

            log.info("[PENDING_RR] PROMOTED {} {} {} | R:R {}->{} | entry {}->{} | tracked {}min",
                    original.getDirection(), original.getCategory(), pending.getFamilyId(),
                    String.format("%.2f", pending.getInitialRR()), String.format("%.2f", newRR),
                    String.format("%.2f", pending.getInitialEntry()), String.format("%.2f", newEntry),
                    Duration.between(pending.getTrackedAt(), Instant.now()).toMinutes());

        } catch (Exception e) {
            log.error("[PENDING_RR] Failed to publish promoted signal: {}", e.getMessage());
        }
    }

    // ======================== CLEANUP ========================

    /**
     * Periodic cleanup of expired signals
     */
    @Scheduled(fixedDelayString = "${pending.rr.tracker.cleanup.interval.ms:60000}")
    public void cleanupExpired() {
        if (!enabled || pendingById.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        List<PendingSignal> expired = new ArrayList<>();

        for (PendingSignal pending : pendingById.values()) {
            if (now.isAfter(pending.getExpiresAt())) {
                expired.add(pending);
            }
        }

        for (PendingSignal pending : expired) {
            removePending(pending);
            signalsExpired.incrementAndGet();

            log.info("[PENDING_RR] EXPIRED {} {} {} | R:R {}->{} (best={}) | needed {} | tracked {}min",
                    pending.getSignal().getDirection(), pending.getSignal().getCategory(),
                    pending.getFamilyId(),
                    String.format("%.2f", pending.getInitialRR()), String.format("%.2f", pending.getCurrentRR()), String.format("%.2f", pending.getBestRR()),
                    String.format("%.1f", minRiskReward),
                    Duration.between(pending.getTrackedAt(), now).toMinutes());
        }

        // Log stats periodically
        if (!pendingById.isEmpty() || signalsPromoted.get() > 0) {
            log.info("[PENDING_RR] Stats: tracked={} | promoted={} | expired={} | invalidated={} | active={}",
                    signalsTracked.get(), signalsPromoted.get(), signalsExpired.get(),
                    signalsInvalidated.get(), pendingById.size());
        }
    }

    /**
     * Remove a pending signal from tracking
     */
    private void removePending(PendingSignal pending) {
        pendingById.remove(pending.getSignalId());

        List<PendingSignal> familySignals = pendingByFamily.get(pending.getFamilyId());
        if (familySignals != null) {
            familySignals.removeIf(p -> p.getSignalId().equals(pending.getSignalId()));
            if (familySignals.isEmpty()) {
                pendingByFamily.remove(pending.getFamilyId());
            }
        }
    }

    // ======================== QUERY METHODS ========================

    /**
     * Get count of pending signals for a family
     */
    public int getPendingCount(String familyId) {
        List<PendingSignal> signals = pendingByFamily.get(familyId);
        return signals != null ? signals.size() : 0;
    }

    /**
     * Get total pending count
     */
    public int getTotalPendingCount() {
        return pendingById.size();
    }

    /**
     * Check if a signal is being tracked
     */
    public boolean isTracking(String signalId) {
        return pendingById.containsKey(signalId);
    }

    /**
     * Get statistics string
     */
    public String getStats() {
        return String.format("[PENDING_RR] tracked=%d | promoted=%d | expired=%d | invalidated=%d | active=%d",
                signalsTracked.get(), signalsPromoted.get(), signalsExpired.get(),
                signalsInvalidated.get(), pendingById.size());
    }

    // ======================== MODELS ========================

    private enum ProcessResult {
        TRACKING,              // Still tracking, R:R not yet met
        PROMOTED,              // R:R met AND price action confirmed, signal promoted
        INVALIDATED,           // SL hit before promotion OR strong contradictory signals
        AWAITING_CONFIRMATION  // R:R met but waiting for price action confirmation
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class PendingSignal {
        private String signalId;
        private String familyId;
        private TradingSignal signal;

        // R:R tracking
        private double initialRR;
        private double currentRR;
        private double bestRR;

        // Entry tracking
        private double initialEntry;
        private double currentEntry;
        private double bestEntry;

        // Fixed levels
        private double stopLoss;
        private double target;

        // Timing
        private Instant trackedAt;
        private Instant expiresAt;
        private Instant lastPriceUpdate;
        private int priceUpdates;

        // Price Action Confirmation tracking
        private boolean awaitingConfirmation;
        private int lastConfirmationScore;
        private int confirmationAttempts;
        private String invalidationReason;
    }
}
