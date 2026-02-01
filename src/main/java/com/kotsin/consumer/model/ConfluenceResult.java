package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * ConfluenceResult - Result of multi-timeframe confluence analysis.
 *
 * Higher confluence count = Stronger support/resistance level.
 * When multiple timeframe pivots align at same price, it's a high-probability reversal zone.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfluenceResult {

    private int confluenceCount;
    private ConfluenceStrength strength;
    private double priceLevel;

    @Builder.Default
    private List<ConfluenceLevel> levels = new ArrayList<>();

    /**
     * Confluence strength based on count.
     */
    public enum ConfluenceStrength {
        WEAK,        // 0-1 timeframes
        MODERATE,    // 2 timeframes
        STRONG,      // 3 timeframes
        VERY_STRONG  // 4+ timeframes
    }

    /**
     * Individual confluence level info.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfluenceLevel {
        private String timeframe;    // "DAILY", "WEEKLY", "MONTHLY", etc.
        private String levelName;    // "Pivot", "S1", "R1", etc.
        private double levelPrice;
        private double distancePercent;
        private LevelType type;

        public enum LevelType {
            SUPPORT,
            RESISTANCE,
            PIVOT,
            CPR
        }
    }

    /**
     * Check if confluence is tradeable (at least moderate).
     */
    public boolean isTradeable() {
        return strength == ConfluenceStrength.MODERATE ||
               strength == ConfluenceStrength.STRONG ||
               strength == ConfluenceStrength.VERY_STRONG;
    }

    /**
     * Check if this is a high-conviction level.
     */
    public boolean isHighConviction() {
        return strength == ConfluenceStrength.STRONG ||
               strength == ConfluenceStrength.VERY_STRONG;
    }

    /**
     * Get primary level type (support or resistance).
     */
    public ConfluenceLevel.LevelType getPrimaryType() {
        if (levels == null || levels.isEmpty()) return null;

        long supportCount = levels.stream()
            .filter(l -> l.getType() == ConfluenceLevel.LevelType.SUPPORT)
            .count();
        long resistanceCount = levels.stream()
            .filter(l -> l.getType() == ConfluenceLevel.LevelType.RESISTANCE)
            .count();

        if (supportCount > resistanceCount) {
            return ConfluenceLevel.LevelType.SUPPORT;
        } else if (resistanceCount > supportCount) {
            return ConfluenceLevel.LevelType.RESISTANCE;
        } else {
            return ConfluenceLevel.LevelType.PIVOT;
        }
    }

    /**
     * Get confluence description for logging.
     */
    public String getDescription() {
        if (levels == null || levels.isEmpty()) return "No confluence";

        StringBuilder sb = new StringBuilder();
        sb.append(strength.name()).append(" confluence (").append(confluenceCount).append(" levels): ");

        for (int i = 0; i < levels.size(); i++) {
            ConfluenceLevel level = levels.get(i);
            if (i > 0) sb.append(", ");
            sb.append(level.getTimeframe()).append(" ").append(level.getLevelName());
        }

        return sb.toString();
    }

    /**
     * Calculate confidence score (0-1).
     */
    public double getConfidenceScore() {
        return switch (strength) {
            case WEAK -> 0.2;
            case MODERATE -> 0.5;
            case STRONG -> 0.75;
            case VERY_STRONG -> 0.9;
        };
    }
}
