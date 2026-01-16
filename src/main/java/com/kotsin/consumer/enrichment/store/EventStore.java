package com.kotsin.consumer.enrichment.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.enrichment.model.DetectedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * EventStore - Redis-backed storage for event lifecycle management
 *
 * Manages the lifecycle of detected events:
 * DETECTED → PENDING → CONFIRMED/FAILED/EXPIRED
 *
 * Redis Key Patterns:
 * - smtis:events:pending:{familyId}:{eventId} - Individual pending event (Hash)
 * - smtis:events:log:{familyId} - Event log sorted set (by timestamp)
 * - smtis:events:active:{familyId} - Set of active event IDs
 * - smtis:events:confirmed:{familyId} - Recently confirmed events
 * - smtis:events:failed:{familyId} - Recently failed events
 *
 * TTLs:
 * - Pending events: Based on confirmation window (typically 30-60 min)
 * - Confirmed/Failed: 24 hours for analysis
 * - Event log: 7 days
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventStore {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "smtis:events";
    private static final Duration CONFIRMED_TTL = Duration.ofHours(24);
    private static final Duration LOG_TTL = Duration.ofDays(7);
    private static final int MAX_ACTIVE_EVENTS = 50; // Per family

    // ======================== STORE OPERATIONS ========================

    /**
     * Store a newly detected event
     *
     * @param event The detected event
     */
    public void storeEvent(DetectedEvent event) {
        if (event == null || event.getEventId() == null) {
            return;
        }

        String familyId = event.getFamilyId();

        try {
            // Store event as JSON
            String eventJson = objectMapper.writeValueAsString(event);

            // Key for pending event
            String pendingKey = getPendingKey(familyId, event.getEventId());

            // Calculate TTL from confirmation window
            long ttlMs = event.getConfirmationWindowMs() > 0 ?
                    event.getConfirmationWindowMs() : Duration.ofMinutes(30).toMillis();

            // Store with TTL
            redisTemplate.opsForValue().set(pendingKey, eventJson, ttlMs, TimeUnit.MILLISECONDS);

            // Add to active set
            String activeKey = getActiveKey(familyId);
            redisTemplate.opsForSet().add(activeKey, event.getEventId());
            redisTemplate.expire(activeKey, Duration.ofHours(2));

            // Add to event log (sorted by timestamp)
            String logKey = getLogKey(familyId);
            redisTemplate.opsForZSet().add(logKey, event.getEventId(),
                    event.getDetectedAt().toEpochMilli());
            redisTemplate.expire(logKey, LOG_TTL);

            log.debug("[EVENT_STORE] Stored event {} for {} with TTL {}ms",
                    event.getEventId(), familyId, ttlMs);

        } catch (JsonProcessingException e) {
            log.error("[EVENT_STORE] Failed to serialize event {}: {}",
                    event.getEventId(), e.getMessage());
        }
    }

    /**
     * Get a pending event by ID
     *
     * @param familyId Family ID
     * @param eventId Event ID
     * @return The event or null
     */
    public DetectedEvent getEvent(String familyId, String eventId) {
        String key = getPendingKey(familyId, eventId);
        String json = redisTemplate.opsForValue().get(key);

        if (json == null) {
            // Try confirmed/failed stores
            json = getFromOutcomeStore(familyId, eventId);
        }

        if (json == null) return null;

        try {
            return objectMapper.readValue(json, DetectedEvent.class);
        } catch (JsonProcessingException e) {
            log.error("[EVENT_STORE] Failed to deserialize event {}: {}", eventId, e.getMessage());
            return null;
        }
    }

    /**
     * Get all active (pending) events for a family
     *
     * @param familyId Family ID
     * @return List of active events
     */
    public List<DetectedEvent> getActiveEvents(String familyId) {
        String activeKey = getActiveKey(familyId);
        Set<String> eventIds = redisTemplate.opsForSet().members(activeKey);

        if (eventIds == null || eventIds.isEmpty()) {
            return Collections.emptyList();
        }

        return eventIds.stream()
                .map(id -> getEvent(familyId, id))
                .filter(Objects::nonNull)
                .filter(e -> e.isActive())
                .collect(Collectors.toList());
    }

    /**
     * Get recent events from log (last N minutes)
     *
     * @param familyId Family ID
     * @param minutes Minutes to look back
     * @return List of events
     */
    public List<DetectedEvent> getRecentEvents(String familyId, int minutes) {
        String logKey = getLogKey(familyId);
        long minScore = Instant.now().minusSeconds(minutes * 60L).toEpochMilli();
        long maxScore = Instant.now().toEpochMilli();

        Set<String> eventIds = redisTemplate.opsForZSet().rangeByScore(logKey, minScore, maxScore);

        if (eventIds == null || eventIds.isEmpty()) {
            return Collections.emptyList();
        }

        return eventIds.stream()
                .map(id -> getEvent(familyId, id))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // ======================== LIFECYCLE UPDATES ========================

    /**
     * Confirm an event (price moved in expected direction)
     *
     * @param event The event to confirm
     * @param outcomePrice Price at confirmation
     * @param outcomeDescription Description of outcome
     */
    public void confirmEvent(DetectedEvent event, double outcomePrice, String outcomeDescription) {
        if (event == null) return;

        event.setLifecycle(DetectedEvent.EventLifecycle.CONFIRMED);
        event.setPriceAtOutcome(outcomePrice);
        event.setOutcomeDescription(outcomeDescription);
        event.setPriceMovePct(calculatePriceMove(event.getPriceAtDetection(), outcomePrice));
        event.setTimeToOutcomeMs(Instant.now().toEpochMilli() - event.getDetectedAt().toEpochMilli());

        updateEvent(event);
        moveToOutcomeStore(event, "confirmed");
        removeFromActive(event);

        log.info("[EVENT_STORE] CONFIRMED {} for {} - price moved {}% in {}ms",
                event.getEventType(), event.getFamilyId(),
                String.format("%.2f", event.getPriceMovePct()),
                event.getTimeToOutcomeMs());
    }

    /**
     * Fail an event (price moved opposite to expected)
     *
     * @param event The event to fail
     * @param outcomePrice Price at failure
     * @param failureReason Reason for failure
     */
    public void failEvent(DetectedEvent event, double outcomePrice, String failureReason) {
        if (event == null) return;

        event.setLifecycle(DetectedEvent.EventLifecycle.FAILED);
        event.setPriceAtOutcome(outcomePrice);
        event.setOutcomeDescription(failureReason);
        event.setPriceMovePct(calculatePriceMove(event.getPriceAtDetection(), outcomePrice));
        event.setTimeToOutcomeMs(Instant.now().toEpochMilli() - event.getDetectedAt().toEpochMilli());

        updateEvent(event);
        moveToOutcomeStore(event, "failed");
        removeFromActive(event);

        log.info("[EVENT_STORE] FAILED {} for {} - {}, price moved {}%",
                event.getEventType(), event.getFamilyId(), failureReason,
                String.format("%.2f", event.getPriceMovePct()));
    }

    /**
     * Expire an event (no clear outcome within window)
     *
     * @param event The event to expire
     */
    public void expireEvent(DetectedEvent event) {
        if (event == null) return;

        event.setLifecycle(DetectedEvent.EventLifecycle.EXPIRED);
        event.setOutcomeDescription("Expired - no clear outcome");

        updateEvent(event);
        moveToOutcomeStore(event, "expired");
        removeFromActive(event);

        log.debug("[EVENT_STORE] EXPIRED {} for {}",
                event.getEventType(), event.getFamilyId());
    }

    /**
     * Check and update lifecycle of all active events
     * Called periodically to confirm/fail/expire events
     *
     * @param familyId Family ID
     * @param currentPrice Current price for confirmation check
     */
    public void checkEventLifecycles(String familyId, double currentPrice) {
        List<DetectedEvent> activeEvents = getActiveEvents(familyId);

        for (DetectedEvent event : activeEvents) {
            if (event.isExpired()) {
                expireEvent(event);
                continue;
            }

            // Check confirmation/failure criteria
            double priceMove = calculatePriceMove(event.getPriceAtDetection(), currentPrice);

            if (shouldConfirm(event, priceMove)) {
                confirmEvent(event, currentPrice, "Price moved " + String.format("%.2f%%", priceMove));
            } else if (shouldFail(event, priceMove)) {
                failEvent(event, currentPrice, "Price moved opposite: " + String.format("%.2f%%", priceMove));
            }
        }
    }

    // ======================== QUERY OPERATIONS ========================

    /**
     * Get events by type for a family
     *
     * @param familyId Family ID
     * @param type Event type
     * @param includeInactive Include confirmed/failed events
     * @return List of matching events
     */
    public List<DetectedEvent> getEventsByType(String familyId, DetectedEvent.EventType type,
                                                boolean includeInactive) {
        List<DetectedEvent> events = includeInactive ?
                getRecentEvents(familyId, 60) : getActiveEvents(familyId);

        return events.stream()
                .filter(e -> e.getEventType() == type)
                .collect(Collectors.toList());
    }

    /**
     * Get event statistics for a family
     *
     * @param familyId Family ID
     * @return Map of event type to count
     */
    public Map<DetectedEvent.EventType, Integer> getEventStats(String familyId) {
        List<DetectedEvent> recent = getRecentEvents(familyId, 60);

        return recent.stream()
                .collect(Collectors.groupingBy(
                        DetectedEvent::getEventType,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
                ));
    }

    /**
     * Get success rate for event type
     *
     * @param familyId Family ID
     * @param type Event type
     * @return Success rate (0-1) or -1 if insufficient data
     */
    public double getEventSuccessRate(String familyId, DetectedEvent.EventType type) {
        List<DetectedEvent> concluded = getRecentEvents(familyId, 120).stream()
                .filter(e -> e.getEventType() == type && e.isConcluded())
                .toList();

        if (concluded.size() < 5) return -1; // Insufficient data

        long confirmed = concluded.stream()
                .filter(DetectedEvent::isSuccessful)
                .count();

        return (double) confirmed / concluded.size();
    }

    // ======================== HELPER METHODS ========================

    private String getPendingKey(String familyId, String eventId) {
        return String.format("%s:pending:%s:%s", KEY_PREFIX, familyId, eventId);
    }

    private String getActiveKey(String familyId) {
        return String.format("%s:active:%s", KEY_PREFIX, familyId);
    }

    private String getLogKey(String familyId) {
        return String.format("%s:log:%s", KEY_PREFIX, familyId);
    }

    private String getOutcomeKey(String familyId, String outcome) {
        return String.format("%s:%s:%s", KEY_PREFIX, outcome, familyId);
    }

    private void updateEvent(DetectedEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            String key = getPendingKey(event.getFamilyId(), event.getEventId());
            redisTemplate.opsForValue().set(key, json, CONFIRMED_TTL);
        } catch (JsonProcessingException e) {
            log.error("[EVENT_STORE] Failed to update event: {}", e.getMessage());
        }
    }

    private void moveToOutcomeStore(DetectedEvent event, String outcome) {
        try {
            String json = objectMapper.writeValueAsString(event);
            String key = getOutcomeKey(event.getFamilyId(), outcome) + ":" + event.getEventId();
            redisTemplate.opsForValue().set(key, json, CONFIRMED_TTL);
        } catch (JsonProcessingException e) {
            log.error("[EVENT_STORE] Failed to move event to outcome store: {}", e.getMessage());
        }
    }

    private void removeFromActive(DetectedEvent event) {
        String activeKey = getActiveKey(event.getFamilyId());
        redisTemplate.opsForSet().remove(activeKey, event.getEventId());

        // Also remove pending key
        String pendingKey = getPendingKey(event.getFamilyId(), event.getEventId());
        redisTemplate.delete(pendingKey);
    }

    private String getFromOutcomeStore(String familyId, String eventId) {
        // Check confirmed
        String confirmedKey = getOutcomeKey(familyId, "confirmed") + ":" + eventId;
        String json = redisTemplate.opsForValue().get(confirmedKey);
        if (json != null) return json;

        // Check failed
        String failedKey = getOutcomeKey(familyId, "failed") + ":" + eventId;
        json = redisTemplate.opsForValue().get(failedKey);
        if (json != null) return json;

        // Check expired
        String expiredKey = getOutcomeKey(familyId, "expired") + ":" + eventId;
        return redisTemplate.opsForValue().get(expiredKey);
    }

    private double calculatePriceMove(double fromPrice, double toPrice) {
        if (fromPrice <= 0) return 0;
        return (toPrice - fromPrice) / fromPrice * 100;
    }

    private boolean shouldConfirm(DetectedEvent event, double priceMove) {
        // Confirmation thresholds based on event type
        double threshold = getConfirmationThreshold(event.getEventType());

        if (event.isBullish()) {
            return priceMove >= threshold;
        } else if (event.isBearish()) {
            return priceMove <= -threshold;
        }
        return false;
    }

    private boolean shouldFail(DetectedEvent event, double priceMove) {
        // Failure threshold (usually same as confirmation but opposite direction)
        double threshold = getConfirmationThreshold(event.getEventType());

        if (event.isBullish()) {
            return priceMove <= -threshold;
        } else if (event.isBearish()) {
            return priceMove >= threshold;
        }
        return false;
    }

    private double getConfirmationThreshold(DetectedEvent.EventType type) {
        return switch (type) {
            case OFI_FLIP, SUPERTREND_FLIP -> 0.5;
            case SELLING_EXHAUSTION, BUYING_EXHAUSTION, ABSORPTION -> 0.4;
            case BB_LOWER_TOUCH, BB_UPPER_TOUCH -> 0.3;
            case GAMMA_SQUEEZE_SETUP, BREAKOUT_SETUP -> 0.8;
            case REVERSAL_SETUP -> 0.6;
            default -> 0.3;
        };
    }

    /**
     * Cleanup expired events from active set
     *
     * @param familyId Family ID
     */
    public void cleanupExpiredEvents(String familyId) {
        List<DetectedEvent> activeEvents = getActiveEvents(familyId);

        for (DetectedEvent event : activeEvents) {
            if (event.isExpired()) {
                expireEvent(event);
            }
        }
    }

    /**
     * Clear all events for a family
     *
     * @param familyId Family ID
     */
    public void clearFamily(String familyId) {
        // Clear active set
        String activeKey = getActiveKey(familyId);
        Set<String> eventIds = redisTemplate.opsForSet().members(activeKey);

        if (eventIds != null) {
            for (String eventId : eventIds) {
                String pendingKey = getPendingKey(familyId, eventId);
                redisTemplate.delete(pendingKey);
            }
        }
        redisTemplate.delete(activeKey);

        // Clear log
        String logKey = getLogKey(familyId);
        redisTemplate.delete(logKey);

        log.info("[EVENT_STORE] Cleared all events for family {}", familyId);
    }
}
