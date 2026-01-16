package com.kotsin.consumer.enrichment.pattern.model;

import com.kotsin.consumer.enrichment.model.DetectedEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ActiveSequence - Represents a pattern being tracked in real-time
 *
 * When events match the start of a SequenceTemplate, an ActiveSequence
 * is created to track progress toward pattern completion.
 *
 * Lifecycle: STARTED → PROGRESSING → COMPLETED/INVALIDATED/EXPIRED
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveSequence {

    /**
     * Unique sequence ID
     */
    @Builder.Default
    private String sequenceId = UUID.randomUUID().toString();

    /**
     * Template being tracked
     */
    private String templateId;

    /**
     * Family ID where sequence is active
     */
    private String familyId;

    /**
     * Current status
     */
    @Builder.Default
    private SequenceStatus status = SequenceStatus.STARTED;

    // ======================== TIMING ========================

    /**
     * When sequence started (first event matched)
     */
    private Instant startedAt;

    /**
     * When sequence expires (from template maxDuration)
     */
    private Instant expiresAt;

    /**
     * When sequence completed/invalidated/expired
     */
    private Instant endedAt;

    // ======================== PROGRESS ========================

    /**
     * Events that have matched required conditions
     */
    @Builder.Default
    private List<MatchedEvent> matchedEvents = new ArrayList<>();

    /**
     * Booster events that have matched
     */
    @Builder.Default
    private List<MatchedEvent> matchedBoosters = new ArrayList<>();

    /**
     * Index of next required event to match
     */
    @Builder.Default
    private int nextRequiredIndex = 0;

    /**
     * Total required events in template
     */
    private int totalRequiredEvents;

    /**
     * Progress percentage (0-100)
     */
    public double getProgress() {
        if (totalRequiredEvents == 0) return 0;
        return (double) matchedEvents.size() / totalRequiredEvents * 100;
    }

    // ======================== PRICE CONTEXT ========================

    /**
     * Price when sequence started
     */
    private double startPrice;

    /**
     * Current price (updated on each event)
     */
    private double currentPrice;

    /**
     * Highest price since sequence started
     */
    private double highSinceStart;

    /**
     * Lowest price since sequence started
     */
    private double lowSinceStart;

    // ======================== CONFIDENCE ========================

    /**
     * Current confidence based on matched events and boosters
     */
    @Builder.Default
    private double currentConfidence = 0.0;

    /**
     * Base confidence from template
     */
    private double baseConfidence;

    /**
     * Historical success rate for this pattern
     */
    private double historicalSuccessRate;

    /**
     * Combined probability (confidence * historical)
     */
    public double getProbability() {
        if (historicalSuccessRate > 0) {
            return currentConfidence * 0.6 + historicalSuccessRate * 0.4;
        }
        return currentConfidence;
    }

    // ======================== INVALIDATION ========================

    /**
     * Reason for invalidation (if invalidated)
     */
    private String invalidationReason;

    /**
     * Event that caused invalidation
     */
    private DetectedEvent.EventType invalidationEvent;

    // ======================== COMPLETION ========================

    /**
     * Price at completion (for outcome tracking)
     */
    private double completionPrice;

    /**
     * Whether signal was emitted
     */
    @Builder.Default
    private boolean signalEmitted = false;

    /**
     * Signal ID if emitted (for outcome tracking)
     */
    private String signalId;

    // ======================== STATUS ENUM ========================

    public enum SequenceStatus {
        STARTED,            // First event matched, waiting for more
        PROGRESSING,        // Multiple events matched, still waiting
        COMPLETED,          // All required events matched
        INVALIDATED,        // Invalidation event occurred
        EXPIRED             // Exceeded maxDuration
    }

    // ======================== MATCHED EVENT ========================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MatchedEvent {
        private DetectedEvent.EventType eventType;
        private Instant matchedAt;
        private double priceAtMatch;
        private double strength;
        private String eventId;
        private int conditionIndex;  // Which condition was matched
        private boolean isBooster;   // True if this is a booster event
    }

    // ======================== HELPER METHODS ========================

    /**
     * Check if sequence is still active
     */
    public boolean isActive() {
        return status == SequenceStatus.STARTED || status == SequenceStatus.PROGRESSING;
    }

    /**
     * Check if sequence is complete
     */
    public boolean isComplete() {
        return status == SequenceStatus.COMPLETED;
    }

    /**
     * Check if sequence has ended (complete, invalidated, or expired)
     */
    public boolean hasEnded() {
        return status == SequenceStatus.COMPLETED
                || status == SequenceStatus.INVALIDATED
                || status == SequenceStatus.EXPIRED;
    }

    /**
     * Check if sequence has expired based on time
     */
    public boolean isExpiredByTime() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * Record a matched required event
     */
    public void recordMatch(DetectedEvent event, int conditionIndex) {
        MatchedEvent matched = MatchedEvent.builder()
                .eventType(event.getEventType())
                .matchedAt(Instant.now())
                .priceAtMatch(event.getPriceAtDetection())
                .strength(event.getStrength())
                .eventId(event.getEventId())
                .conditionIndex(conditionIndex)
                .isBooster(false)
                .build();

        matchedEvents.add(matched);
        nextRequiredIndex++;
        currentPrice = event.getPriceAtDetection();
        updateHighLow(currentPrice);

        if (status == SequenceStatus.STARTED) {
            status = SequenceStatus.PROGRESSING;
        }

        // Check if complete
        if (nextRequiredIndex >= totalRequiredEvents) {
            complete(event.getPriceAtDetection());
        }
    }

    /**
     * Record a matched booster event
     */
    public void recordBooster(DetectedEvent event, double confidenceBoost) {
        MatchedEvent matched = MatchedEvent.builder()
                .eventType(event.getEventType())
                .matchedAt(Instant.now())
                .priceAtMatch(event.getPriceAtDetection())
                .strength(event.getStrength())
                .eventId(event.getEventId())
                .isBooster(true)
                .build();

        matchedBoosters.add(matched);
        currentConfidence = Math.min(0.95, currentConfidence + confidenceBoost);
        currentPrice = event.getPriceAtDetection();
        updateHighLow(currentPrice);
    }

    /**
     * Mark sequence as complete
     */
    public void complete(double price) {
        status = SequenceStatus.COMPLETED;
        endedAt = Instant.now();
        completionPrice = price;
    }

    /**
     * Invalidate the sequence
     */
    public void invalidate(DetectedEvent.EventType cause, String reason) {
        status = SequenceStatus.INVALIDATED;
        endedAt = Instant.now();
        invalidationEvent = cause;
        invalidationReason = reason;
    }

    /**
     * Expire the sequence
     */
    public void expire() {
        status = SequenceStatus.EXPIRED;
        endedAt = Instant.now();
    }

    /**
     * Update high/low tracking
     */
    private void updateHighLow(double price) {
        if (highSinceStart == 0 || price > highSinceStart) {
            highSinceStart = price;
        }
        if (lowSinceStart == 0 || price < lowSinceStart) {
            lowSinceStart = price;
        }
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
     * Get remaining time before expiry
     */
    public long getRemainingMs() {
        if (expiresAt == null) return Long.MAX_VALUE;
        long remaining = expiresAt.toEpochMilli() - Instant.now().toEpochMilli();
        return Math.max(0, remaining);
    }

    /**
     * Get events still awaiting
     */
    public int getAwaitingCount() {
        return totalRequiredEvents - nextRequiredIndex;
    }

    /**
     * Get description of current state
     */
    public String getStateDescription() {
        return String.format("%s: %.0f%% complete (%d/%d events), confidence %.0f%%",
                templateId,
                getProgress(),
                matchedEvents.size(),
                totalRequiredEvents,
                currentConfidence * 100);
    }

    /**
     * Create a new sequence from a template
     */
    public static ActiveSequence fromTemplate(SequenceTemplate template, String familyId,
                                               DetectedEvent firstEvent) {
        Instant now = Instant.now();
        return ActiveSequence.builder()
                .sequenceId(UUID.randomUUID().toString())
                .templateId(template.getTemplateId())
                .familyId(familyId)
                .status(SequenceStatus.STARTED)
                .startedAt(now)
                .expiresAt(now.plus(template.getMaxDuration()))
                .totalRequiredEvents(template.getRequiredEventCount())
                .startPrice(firstEvent.getPriceAtDetection())
                .currentPrice(firstEvent.getPriceAtDetection())
                .highSinceStart(firstEvent.getPriceAtDetection())
                .lowSinceStart(firstEvent.getPriceAtDetection())
                .baseConfidence(template.getBaseConfidence())
                .currentConfidence(template.getBaseConfidence())
                .historicalSuccessRate(template.getSuccessRate())
                .build();
    }

    @Override
    public String toString() {
        return String.format("Sequence[%s] %s: %s (%.0f%% @ %.0f%% confidence)",
                sequenceId.substring(0, 8),
                templateId,
                status,
                getProgress(),
                currentConfidence * 100);
    }
}
