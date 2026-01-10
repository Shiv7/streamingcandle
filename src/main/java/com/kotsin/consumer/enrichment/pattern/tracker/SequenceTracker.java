package com.kotsin.consumer.enrichment.pattern.tracker;

import com.kotsin.consumer.enrichment.model.DetectedEvent;
import com.kotsin.consumer.enrichment.pattern.model.ActiveSequence;
import com.kotsin.consumer.enrichment.pattern.model.EventCondition;
import com.kotsin.consumer.enrichment.pattern.model.SequenceTemplate;
import com.kotsin.consumer.enrichment.pattern.registry.SequenceTemplateRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * SequenceTracker - Manages active pattern sequences across all families
 *
 * Responsibilities:
 * 1. Start new sequences when first event of a pattern is detected
 * 2. Progress sequences as subsequent events match
 * 3. Complete sequences when all required events are matched
 * 4. Invalidate sequences when invalidation events occur
 * 5. Expire sequences that exceed their maxDuration
 * 6. Clean up completed/invalidated/expired sequences
 *
 * State is kept in memory for fast access. In production,
 * active sequences could be backed by Redis for persistence.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SequenceTracker {

    private final SequenceTemplateRegistry templateRegistry;

    /**
     * Active sequences indexed by family ID
     * Map: familyId -> List of active sequences
     */
    private final Map<String, List<ActiveSequence>> activeSequencesByFamily = new ConcurrentHashMap<>();

    /**
     * All sequences indexed by sequence ID for fast lookup
     */
    private final Map<String, ActiveSequence> sequencesById = new ConcurrentHashMap<>();

    /**
     * Maximum active sequences per family (to prevent memory issues)
     */
    private static final int MAX_SEQUENCES_PER_FAMILY = 50;

    /**
     * Maximum total sequences across all families
     */
    private static final int MAX_TOTAL_SEQUENCES = 500;

    // ======================== SEQUENCE LIFECYCLE ========================

    /**
     * Start a new sequence for a pattern
     */
    public ActiveSequence startSequence(SequenceTemplate template, String familyId,
                                         DetectedEvent firstEvent) {
        // Check limits
        if (sequencesById.size() >= MAX_TOTAL_SEQUENCES) {
            log.warn("[SEQ_TRACKER] Max total sequences reached, cleaning up old sequences");
            cleanupOldSequences();
        }

        List<ActiveSequence> familySequences = activeSequencesByFamily
                .computeIfAbsent(familyId, k -> new ArrayList<>());

        if (familySequences.size() >= MAX_SEQUENCES_PER_FAMILY) {
            log.warn("[SEQ_TRACKER] Max sequences for family {} reached, removing oldest", familyId);
            removeOldestSequence(familyId);
        }

        // Check if we already have an active sequence for this template in this family
        boolean alreadyActive = familySequences.stream()
                .anyMatch(seq -> seq.getTemplateId().equals(template.getTemplateId())
                        && seq.isActive());

        if (alreadyActive) {
            log.debug("[SEQ_TRACKER] Sequence for {} already active in family {}",
                    template.getTemplateId(), familyId);
            return null;
        }

        // Create new sequence
        ActiveSequence sequence = ActiveSequence.fromTemplate(template, familyId, firstEvent);

        // Record the first event match
        sequence.recordMatch(firstEvent, 0);

        // Add to tracking maps
        familySequences.add(sequence);
        sequencesById.put(sequence.getSequenceId(), sequence);

        log.info("[SEQ_TRACKER] Started sequence {} for pattern {} in family {} (first event: {})",
                sequence.getSequenceId().substring(0, 8),
                template.getTemplateId(),
                familyId,
                firstEvent.getEventType());

        return sequence;
    }

    /**
     * Process an event against all active sequences for a family
     * Returns list of completed sequences
     */
    public List<ActiveSequence> processEvent(String familyId, DetectedEvent event) {
        List<ActiveSequence> familySequences = activeSequencesByFamily.get(familyId);
        if (familySequences == null || familySequences.isEmpty()) {
            return Collections.emptyList();
        }

        List<ActiveSequence> completedSequences = new ArrayList<>();

        // Process event against each active sequence
        for (ActiveSequence sequence : new ArrayList<>(familySequences)) {
            if (!sequence.isActive()) {
                continue;
            }

            // Check if sequence has expired by time
            if (sequence.isExpiredByTime()) {
                expireSequence(sequence);
                continue;
            }

            // Get the template for this sequence
            Optional<SequenceTemplate> templateOpt = templateRegistry.getTemplate(sequence.getTemplateId());
            if (templateOpt.isEmpty()) {
                continue;
            }

            SequenceTemplate template = templateOpt.get();

            // Check for invalidation
            if (checkInvalidation(sequence, template, event)) {
                continue;
            }

            // Check for next required event match
            if (checkRequiredEventMatch(sequence, template, event)) {
                if (sequence.isComplete()) {
                    completedSequences.add(sequence);
                    log.info("[SEQ_TRACKER] Sequence {} COMPLETED for pattern {} in family {}",
                            sequence.getSequenceId().substring(0, 8),
                            sequence.getTemplateId(),
                            familyId);
                }
                continue;
            }

            // Check for booster event match
            checkBoosterEventMatch(sequence, template, event);
        }

        return completedSequences;
    }

    /**
     * Check if event matches next required condition in sequence
     */
    private boolean checkRequiredEventMatch(ActiveSequence sequence, SequenceTemplate template,
                                             DetectedEvent event) {
        int nextIndex = sequence.getNextRequiredIndex();
        List<EventCondition> requiredEvents = template.getRequiredEvents();

        if (nextIndex >= requiredEvents.size()) {
            return false;
        }

        EventCondition nextCondition = requiredEvents.get(nextIndex);

        if (nextCondition.matches(event)) {
            sequence.recordMatch(event, nextIndex);

            // Update confidence based on event strength
            double strengthBonus = (event.getStrength() - 0.5) * 0.1;
            double newConfidence = Math.min(template.getMaxConfidence(),
                    sequence.getCurrentConfidence() + strengthBonus);
            sequence.setCurrentConfidence(newConfidence);

            log.debug("[SEQ_TRACKER] Sequence {} matched event {} ({}/{})",
                    sequence.getSequenceId().substring(0, 8),
                    event.getEventType(),
                    sequence.getMatchedEvents().size(),
                    sequence.getTotalRequiredEvents());

            return true;
        }

        return false;
    }

    /**
     * Check if event matches any booster condition
     */
    private void checkBoosterEventMatch(ActiveSequence sequence, SequenceTemplate template,
                                         DetectedEvent event) {
        for (EventCondition booster : template.getBoosterEvents()) {
            if (booster.matches(event)) {
                // Check if this booster was already matched
                boolean alreadyMatched = sequence.getMatchedBoosters().stream()
                        .anyMatch(m -> m.getEventType() == event.getEventType());

                if (!alreadyMatched) {
                    sequence.recordBooster(event, booster.getProbabilityBoost());
                    log.debug("[SEQ_TRACKER] Sequence {} matched booster {} (confidence now {:.1f}%)",
                            sequence.getSequenceId().substring(0, 8),
                            event.getEventType(),
                            sequence.getCurrentConfidence() * 100);
                }
            }
        }
    }

    /**
     * Check if event is an invalidation event for the sequence
     */
    private boolean checkInvalidation(ActiveSequence sequence, SequenceTemplate template,
                                       DetectedEvent event) {
        for (EventCondition invalidation : template.getInvalidationEvents()) {
            if (invalidation.matches(event)) {
                invalidateSequence(sequence, event.getEventType(),
                        "Invalidation event: " + event.getEventType());
                return true;
            }
        }
        return false;
    }

    /**
     * Invalidate a sequence
     */
    public void invalidateSequence(ActiveSequence sequence, DetectedEvent.EventType cause,
                                    String reason) {
        sequence.invalidate(cause, reason);
        log.info("[SEQ_TRACKER] Sequence {} INVALIDATED: {}",
                sequence.getSequenceId().substring(0, 8), reason);
    }

    /**
     * Expire a sequence (exceeded maxDuration)
     */
    public void expireSequence(ActiveSequence sequence) {
        sequence.expire();
        log.info("[SEQ_TRACKER] Sequence {} EXPIRED (exceeded max duration)",
                sequence.getSequenceId().substring(0, 8));
    }

    /**
     * Mark sequence as having emitted a signal
     */
    public void markSignalEmitted(String sequenceId, String signalId) {
        ActiveSequence sequence = sequencesById.get(sequenceId);
        if (sequence != null) {
            sequence.setSignalEmitted(true);
            sequence.setSignalId(signalId);
        }
    }

    // ======================== QUERY METHODS ========================

    /**
     * Get all active sequences for a family
     */
    public List<ActiveSequence> getActiveSequences(String familyId) {
        List<ActiveSequence> familySequences = activeSequencesByFamily.get(familyId);
        if (familySequences == null) {
            return Collections.emptyList();
        }
        return familySequences.stream()
                .filter(ActiveSequence::isActive)
                .collect(Collectors.toList());
    }

    /**
     * Get sequence by ID
     */
    public Optional<ActiveSequence> getSequence(String sequenceId) {
        return Optional.ofNullable(sequencesById.get(sequenceId));
    }

    /**
     * Get all active sequences for a specific pattern template
     */
    public List<ActiveSequence> getActiveSequencesForTemplate(String templateId) {
        return sequencesById.values().stream()
                .filter(seq -> seq.getTemplateId().equals(templateId) && seq.isActive())
                .collect(Collectors.toList());
    }

    /**
     * Get completed sequences for a family (for signal generation)
     */
    public List<ActiveSequence> getCompletedSequences(String familyId) {
        List<ActiveSequence> familySequences = activeSequencesByFamily.get(familyId);
        if (familySequences == null) {
            return Collections.emptyList();
        }
        return familySequences.stream()
                .filter(ActiveSequence::isComplete)
                .filter(seq -> !seq.isSignalEmitted())
                .collect(Collectors.toList());
    }

    /**
     * Get sequences awaiting a specific event type
     */
    public List<ActiveSequence> getSequencesAwaitingEvent(String familyId,
                                                           DetectedEvent.EventType eventType) {
        List<ActiveSequence> familySequences = activeSequencesByFamily.get(familyId);
        if (familySequences == null) {
            return Collections.emptyList();
        }

        return familySequences.stream()
                .filter(ActiveSequence::isActive)
                .filter(seq -> {
                    Optional<SequenceTemplate> template = templateRegistry.getTemplate(seq.getTemplateId());
                    if (template.isEmpty()) return false;

                    int nextIndex = seq.getNextRequiredIndex();
                    List<EventCondition> required = template.get().getRequiredEvents();
                    if (nextIndex >= required.size()) return false;

                    return required.get(nextIndex).getEventType() == eventType;
                })
                .collect(Collectors.toList());
    }

    // ======================== CLEANUP METHODS ========================

    /**
     * Cleanup expired and old sequences
     */
    public void cleanupExpiredSequences() {
        int cleaned = 0;
        Instant now = Instant.now();

        for (List<ActiveSequence> familySequences : activeSequencesByFamily.values()) {
            Iterator<ActiveSequence> iterator = familySequences.iterator();
            while (iterator.hasNext()) {
                ActiveSequence sequence = iterator.next();

                // Expire if exceeded time
                if (sequence.isActive() && sequence.isExpiredByTime()) {
                    expireSequence(sequence);
                }

                // Remove ended sequences older than 5 minutes
                if (sequence.hasEnded() && sequence.getEndedAt() != null) {
                    long endedAgoMs = now.toEpochMilli() - sequence.getEndedAt().toEpochMilli();
                    if (endedAgoMs > 300_000) { // 5 minutes
                        iterator.remove();
                        sequencesById.remove(sequence.getSequenceId());
                        cleaned++;
                    }
                }
            }
        }

        if (cleaned > 0) {
            log.info("[SEQ_TRACKER] Cleaned up {} old sequences", cleaned);
        }
    }

    /**
     * Cleanup old sequences when hitting memory limits
     */
    private void cleanupOldSequences() {
        // Remove all ended sequences
        for (List<ActiveSequence> familySequences : activeSequencesByFamily.values()) {
            familySequences.removeIf(seq -> {
                if (seq.hasEnded()) {
                    sequencesById.remove(seq.getSequenceId());
                    return true;
                }
                return false;
            });
        }
    }

    /**
     * Remove oldest sequence from a family
     */
    private void removeOldestSequence(String familyId) {
        List<ActiveSequence> familySequences = activeSequencesByFamily.get(familyId);
        if (familySequences == null || familySequences.isEmpty()) {
            return;
        }

        // Find the oldest ended sequence, or oldest active if none ended
        ActiveSequence oldest = familySequences.stream()
                .filter(ActiveSequence::hasEnded)
                .min(Comparator.comparing(seq -> seq.getEndedAt() != null ?
                        seq.getEndedAt() : Instant.MAX))
                .orElseGet(() -> familySequences.stream()
                        .min(Comparator.comparing(ActiveSequence::getStartedAt))
                        .orElse(null));

        if (oldest != null) {
            familySequences.remove(oldest);
            sequencesById.remove(oldest.getSequenceId());
        }
    }

    /**
     * Clear all sequences for a family
     */
    public void clearFamily(String familyId) {
        List<ActiveSequence> familySequences = activeSequencesByFamily.remove(familyId);
        if (familySequences != null) {
            for (ActiveSequence seq : familySequences) {
                sequencesById.remove(seq.getSequenceId());
            }
        }
    }

    /**
     * Clear all sequences
     */
    public void clearAll() {
        activeSequencesByFamily.clear();
        sequencesById.clear();
    }

    // ======================== STATISTICS ========================

    /**
     * Get tracking statistics
     */
    public TrackerStats getStats() {
        int totalActive = 0;
        int totalCompleted = 0;
        int totalInvalidated = 0;
        int totalExpired = 0;

        for (ActiveSequence seq : sequencesById.values()) {
            switch (seq.getStatus()) {
                case STARTED, PROGRESSING -> totalActive++;
                case COMPLETED -> totalCompleted++;
                case INVALIDATED -> totalInvalidated++;
                case EXPIRED -> totalExpired++;
            }
        }

        return new TrackerStats(
                activeSequencesByFamily.size(),
                sequencesById.size(),
                totalActive,
                totalCompleted,
                totalInvalidated,
                totalExpired
        );
    }

    public record TrackerStats(
            int familiesTracked,
            int totalSequences,
            int activeSequences,
            int completedSequences,
            int invalidatedSequences,
            int expiredSequences
    ) {
        @Override
        public String toString() {
            return String.format("Tracker: %d families, %d sequences (%d active, %d completed, %d invalidated, %d expired)",
                    familiesTracked, totalSequences, activeSequences, completedSequences, invalidatedSequences, expiredSequences);
        }
    }
}
