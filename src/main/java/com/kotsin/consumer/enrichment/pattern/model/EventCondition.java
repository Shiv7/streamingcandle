package com.kotsin.consumer.enrichment.pattern.model;

import com.kotsin.consumer.enrichment.model.DetectedEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * EventCondition - Defines conditions for matching a DetectedEvent
 *
 * Used in SequenceTemplate to specify what events must occur
 * for a pattern to progress or complete.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventCondition {

    /**
     * Event type to match
     */
    private DetectedEvent.EventType eventType;

    /**
     * Required direction (BULLISH, BEARISH, or null for any)
     */
    private DetectedEvent.EventDirection direction;

    /**
     * Minimum strength required (0-1)
     */
    @Builder.Default
    private double minStrength = 0.5;

    /**
     * Additional constraints (flexible key-value pairs)
     * Examples:
     * - "nearSupport" -> true
     * - "minZScore" -> 1.5
     * - "atConfluenceZone" -> true
     */
    @Builder.Default
    private Map<String, Object> constraints = new HashMap<>();

    /**
     * Weight of this condition (for confidence calculation)
     */
    @Builder.Default
    private double weight = 1.0;

    /**
     * Human-readable description
     */
    private String description;

    /**
     * Probability boost when this booster event occurs (for booster events)
     */
    @Builder.Default
    private double probabilityBoost = 0.0;

    // ======================== MATCHING METHODS ========================

    /**
     * Check if a DetectedEvent matches this condition
     *
     * @param event The event to check
     * @return true if event matches all conditions
     */
    public boolean matches(DetectedEvent event) {
        if (event == null) return false;

        // Type must match
        if (eventType != null && event.getEventType() != eventType) {
            return false;
        }

        // Direction must match (if specified)
        if (direction != null && event.getDirection() != direction) {
            return false;
        }

        // Strength must meet minimum
        if (event.getStrength() < minStrength) {
            return false;
        }

        // Check additional constraints
        if (!checkConstraints(event)) {
            return false;
        }

        return true;
    }

    /**
     * Check additional constraints against event fields
     * Uses event's built-in fields (zScore, percentile) instead of metadata
     */
    private boolean checkConstraints(DetectedEvent event) {
        if (constraints == null || constraints.isEmpty()) {
            return true;
        }

        for (Map.Entry<String, Object> constraint : constraints.entrySet()) {
            String key = constraint.getKey();
            Object requiredValue = constraint.getValue();

            // Get actual value from event's fields
            Object actualValue = getEventFieldValue(event, key);

            if (!checkConstraint(key, requiredValue, actualValue, event)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Get field value from event for constraint checking
     */
    private Object getEventFieldValue(DetectedEvent event, String key) {
        return switch (key) {
            case "zScore", "minZScore", "maxZScore" -> event.getZScore();
            case "percentile" -> event.getPercentile();
            case "strength" -> event.getStrength();
            default -> null;
        };
    }

    /**
     * Check a single constraint
     */
    private boolean checkConstraint(String key, Object required, Object actual, DetectedEvent event) {
        // Special constraint: nearSupport
        if ("nearSupport".equals(key) && Boolean.TRUE.equals(required)) {
            // Check if event has nearSupport metadata
            return actual != null && Boolean.TRUE.equals(actual);
        }

        // Special constraint: nearResistance
        if ("nearResistance".equals(key) && Boolean.TRUE.equals(required)) {
            return actual != null && Boolean.TRUE.equals(actual);
        }

        // Special constraint: atConfluenceZone
        if ("atConfluenceZone".equals(key) && Boolean.TRUE.equals(required)) {
            return actual != null && Boolean.TRUE.equals(actual);
        }

        // Special constraint: minZScore
        if ("minZScore".equals(key) && required instanceof Number) {
            double minZ = ((Number) required).doubleValue();
            if (actual instanceof Number) {
                return ((Number) actual).doubleValue() >= minZ;
            }
            return false;
        }

        // Special constraint: maxZScore
        if ("maxZScore".equals(key) && required instanceof Number) {
            double maxZ = ((Number) required).doubleValue();
            if (actual instanceof Number) {
                return ((Number) actual).doubleValue() <= maxZ;
            }
            return false;
        }

        // Special constraint: gexRegime
        if ("gexRegime".equals(key) && required instanceof String) {
            return required.equals(actual);
        }

        // Default: exact match
        if (required != null && actual != null) {
            return required.equals(actual);
        }

        return required == null; // If no requirement, pass
    }

    // ======================== FACTORY METHODS ========================

    /**
     * Create a simple event type condition
     */
    public static EventCondition ofType(DetectedEvent.EventType type) {
        return EventCondition.builder()
                .eventType(type)
                .build();
    }

    /**
     * Create a condition with type and direction
     */
    public static EventCondition of(DetectedEvent.EventType type, DetectedEvent.EventDirection direction) {
        return EventCondition.builder()
                .eventType(type)
                .direction(direction)
                .build();
    }

    /**
     * Create a booster condition with probability boost
     */
    public static EventCondition booster(DetectedEvent.EventType type, double probabilityBoost) {
        return EventCondition.builder()
                .eventType(type)
                .probabilityBoost(probabilityBoost)
                .build();
    }

    /**
     * Create a condition with constraint
     */
    public EventCondition withConstraint(String key, Object value) {
        if (this.constraints == null) {
            this.constraints = new HashMap<>();
        }
        this.constraints.put(key, value);
        return this;
    }

    /**
     * Create a condition requiring near support
     */
    public EventCondition nearSupport() {
        return withConstraint("nearSupport", true);
    }

    /**
     * Create a condition requiring near resistance
     */
    public EventCondition nearResistance() {
        return withConstraint("nearResistance", true);
    }

    /**
     * Create a condition requiring confluence zone
     */
    public EventCondition atConfluence() {
        return withConstraint("atConfluenceZone", true);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(eventType != null ? eventType.name() : "ANY");
        if (direction != null) {
            sb.append(" [").append(direction).append("]");
        }
        if (minStrength > 0.5) {
            sb.append(" (min=").append(String.format("%.0f%%", minStrength * 100)).append(")");
        }
        if (probabilityBoost > 0) {
            sb.append(" +").append(String.format("%.0f%%", probabilityBoost * 100));
        }
        return sb.toString();
    }
}
