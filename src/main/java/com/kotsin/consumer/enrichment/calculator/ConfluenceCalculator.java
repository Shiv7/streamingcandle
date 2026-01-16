package com.kotsin.consumer.enrichment.calculator;

import com.kotsin.consumer.enrichment.model.GEXProfile;
import com.kotsin.consumer.enrichment.model.MaxPainProfile;
import com.kotsin.consumer.enrichment.model.TechnicalContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ConfluenceCalculator - Detects and scores support/resistance confluence zones
 *
 * A confluence zone is where multiple support/resistance sources align:
 * - Pivot levels (daily, weekly, monthly)
 * - Bollinger Bands
 * - GEX levels (high gamma strikes)
 * - Max pain level
 * - Previous swing highs/lows
 *
 * The more sources that align at a price level, the stronger the zone.
 * Strong zones are more likely to hold as support or act as resistance.
 *
 * Usage:
 * - Boost confidence when signals occur at confluence zones
 * - Use confluence zones for stop placement and targets
 * - Avoid trading into strong confluence against position
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfluenceCalculator {

    // Confluence parameters
    private static final double ZONE_MERGE_THRESHOLD_PCT = 0.5; // Merge levels within 0.5%
    private static final int MIN_SOURCES_FOR_STRONG = 3;        // 3+ sources = strong zone
    private static final int MAX_ZONES_TO_RETURN = 10;          // Limit returned zones

    /**
     * Calculate confluence zones from all available sources
     *
     * @param currentPrice Current price
     * @param technicalContext Technical indicators with pivots and BB
     * @param gexProfile GEX profile with gamma levels
     * @param maxPainProfile Max pain profile
     * @return ConfluenceResult with zones and nearest levels
     */
    public ConfluenceResult calculate(double currentPrice, TechnicalContext technicalContext,
                                       GEXProfile gexProfile, MaxPainProfile maxPainProfile) {

        if (currentPrice <= 0) {
            return ConfluenceResult.empty();
        }

        // Collect all price levels with their sources
        List<PriceLevel> levels = new ArrayList<>();

        // 1. Add pivot levels
        if (technicalContext != null) {
            addPivotLevels(levels, technicalContext);
            addBollingerLevels(levels, technicalContext);
        }

        // 2. Add GEX levels
        if (gexProfile != null) {
            addGEXLevels(levels, gexProfile);
        }

        // 3. Add max pain level
        if (maxPainProfile != null && maxPainProfile.getMaxPainStrike() > 0) {
            levels.add(new PriceLevel(
                    maxPainProfile.getMaxPainStrike(),
                    LevelSource.MAX_PAIN,
                    maxPainProfile.getMaxPainStrength()
            ));
        }

        // Merge nearby levels into zones
        List<ConfluenceZone> zones = mergeLevelsIntoZones(levels, currentPrice);

        // Sort by strength
        zones.sort((a, b) -> Double.compare(b.getStrength(), a.getStrength()));

        // Limit zones
        if (zones.size() > MAX_ZONES_TO_RETURN) {
            zones = zones.subList(0, MAX_ZONES_TO_RETURN);
        }

        // Find nearest support and resistance
        ConfluenceZone nearestSupport = findNearestSupport(zones, currentPrice);
        ConfluenceZone nearestResistance = findNearestResistance(zones, currentPrice);

        // Check if price is in a confluence zone
        ConfluenceZone currentZone = findZoneAtPrice(zones, currentPrice);

        return ConfluenceResult.builder()
                .zones(zones)
                .nearestSupport(nearestSupport)
                .nearestResistance(nearestResistance)
                .currentZone(currentZone)
                .isInConfluenceZone(currentZone != null)
                .supportDistance(nearestSupport != null ?
                        (currentPrice - nearestSupport.getCenterPrice()) / currentPrice * 100 : null)
                .resistanceDistance(nearestResistance != null ?
                        (nearestResistance.getCenterPrice() - currentPrice) / currentPrice * 100 : null)
                .build();
    }

    // ======================== LEVEL COLLECTION ========================

    private void addPivotLevels(List<PriceLevel> levels, TechnicalContext tech) {
        // Daily pivots
        addIfValid(levels, tech.getDailyPivot(), LevelSource.DAILY_PIVOT, 0.8);
        addIfValid(levels, tech.getDailyR1(), LevelSource.DAILY_R1, 0.7);
        addIfValid(levels, tech.getDailyR2(), LevelSource.DAILY_R2, 0.6);
        addIfValid(levels, tech.getDailyR3(), LevelSource.DAILY_R3, 0.5);
        addIfValid(levels, tech.getDailyS1(), LevelSource.DAILY_S1, 0.7);
        addIfValid(levels, tech.getDailyS2(), LevelSource.DAILY_S2, 0.6);
        addIfValid(levels, tech.getDailyS3(), LevelSource.DAILY_S3, 0.5);

        // Weekly pivots (stronger)
        addIfValid(levels, tech.getWeeklyPivot(), LevelSource.WEEKLY_PIVOT, 0.9);
        addIfValid(levels, tech.getWeeklyR1(), LevelSource.WEEKLY_R1, 0.8);
        addIfValid(levels, tech.getWeeklyS1(), LevelSource.WEEKLY_S1, 0.8);

        // Monthly pivots (strongest)
        addIfValid(levels, tech.getMonthlyPivot(), LevelSource.MONTHLY_PIVOT, 1.0);
        addIfValid(levels, tech.getMonthlyR1(), LevelSource.MONTHLY_R1, 0.9);
        addIfValid(levels, tech.getMonthlyS1(), LevelSource.MONTHLY_S1, 0.9);
    }

    private void addBollingerLevels(List<PriceLevel> levels, TechnicalContext tech) {
        addIfValid(levels, tech.getBbUpper(), LevelSource.BB_UPPER, 0.6);
        addIfValid(levels, tech.getBbMiddle(), LevelSource.BB_MIDDLE, 0.5);
        addIfValid(levels, tech.getBbLower(), LevelSource.BB_LOWER, 0.6);
    }

    private void addGEXLevels(List<PriceLevel> levels, GEXProfile gex) {
        // Add gamma flip level
        if (gex.getGammaFlipLevel() != null) {
            levels.add(new PriceLevel(
                    gex.getGammaFlipLevel(),
                    LevelSource.GAMMA_FLIP,
                    0.85
            ));
        }

        // Add max GEX strike (gamma wall)
        if (gex.getMaxGexStrike() > 0) {
            levels.add(new PriceLevel(
                    gex.getMaxGexStrike(),
                    LevelSource.GAMMA_WALL,
                    0.8
            ));
        }

        // Add key support/resistance from GEX
        if (gex.getKeySupportLevels() != null) {
            for (Double level : gex.getKeySupportLevels()) {
                levels.add(new PriceLevel(level, LevelSource.GEX_SUPPORT, 0.65));
            }
        }

        if (gex.getKeyResistanceLevels() != null) {
            for (Double level : gex.getKeyResistanceLevels()) {
                levels.add(new PriceLevel(level, LevelSource.GEX_RESISTANCE, 0.65));
            }
        }
    }

    private void addIfValid(List<PriceLevel> levels, Double price, LevelSource source, double weight) {
        if (price != null && price > 0) {
            levels.add(new PriceLevel(price, source, weight));
        }
    }

    // ======================== ZONE MERGING ========================

    private List<ConfluenceZone> mergeLevelsIntoZones(List<PriceLevel> levels, double currentPrice) {
        if (levels.isEmpty()) {
            return Collections.emptyList();
        }

        // Sort levels by price
        levels.sort(Comparator.comparingDouble(l -> l.price));

        List<ConfluenceZone> zones = new ArrayList<>();
        List<PriceLevel> currentGroup = new ArrayList<>();
        currentGroup.add(levels.get(0));

        for (int i = 1; i < levels.size(); i++) {
            PriceLevel level = levels.get(i);
            PriceLevel lastInGroup = currentGroup.get(currentGroup.size() - 1);

            // Calculate percentage distance
            double pctDiff = Math.abs(level.price - lastInGroup.price) / lastInGroup.price * 100;

            if (pctDiff <= ZONE_MERGE_THRESHOLD_PCT) {
                // Same zone
                currentGroup.add(level);
            } else {
                // New zone - finalize current group
                zones.add(createZone(currentGroup, currentPrice));
                currentGroup = new ArrayList<>();
                currentGroup.add(level);
            }
        }

        // Don't forget last group
        if (!currentGroup.isEmpty()) {
            zones.add(createZone(currentGroup, currentPrice));
        }

        return zones;
    }

    private ConfluenceZone createZone(List<PriceLevel> levels, double currentPrice) {
        // Calculate zone boundaries
        double minPrice = levels.stream().mapToDouble(l -> l.price).min().orElse(0);
        double maxPrice = levels.stream().mapToDouble(l -> l.price).max().orElse(0);
        double centerPrice = levels.stream().mapToDouble(l -> l.price).average().orElse(0);

        // Calculate strength based on number of sources and their weights
        double totalWeight = levels.stream().mapToDouble(l -> l.weight).sum();
        int sourceCount = levels.size();
        double strength = Math.min(1.0, totalWeight / 3.0 + sourceCount * 0.1);

        // Determine zone type
        ZoneType type = centerPrice < currentPrice ? ZoneType.SUPPORT : ZoneType.RESISTANCE;

        // Collect sources
        List<LevelSource> sources = levels.stream()
                .map(l -> l.source)
                .distinct()
                .collect(Collectors.toList());

        // Is this a strong zone?
        boolean isStrong = sourceCount >= MIN_SOURCES_FOR_STRONG;

        return ConfluenceZone.builder()
                .centerPrice(centerPrice)
                .upperBound(maxPrice)
                .lowerBound(minPrice)
                .strength(strength)
                .sourceCount(sourceCount)
                .sources(sources)
                .type(type)
                .isStrong(isStrong)
                .build();
    }

    // ======================== ZONE FINDING ========================

    private ConfluenceZone findNearestSupport(List<ConfluenceZone> zones, double currentPrice) {
        return zones.stream()
                .filter(z -> z.getCenterPrice() < currentPrice)
                .max(Comparator.comparingDouble(ConfluenceZone::getCenterPrice))
                .orElse(null);
    }

    private ConfluenceZone findNearestResistance(List<ConfluenceZone> zones, double currentPrice) {
        return zones.stream()
                .filter(z -> z.getCenterPrice() > currentPrice)
                .min(Comparator.comparingDouble(ConfluenceZone::getCenterPrice))
                .orElse(null);
    }

    private ConfluenceZone findZoneAtPrice(List<ConfluenceZone> zones, double currentPrice) {
        return zones.stream()
                .filter(z -> currentPrice >= z.getLowerBound() && currentPrice <= z.getUpperBound())
                .findFirst()
                .orElse(null);
    }

    // ======================== DATA CLASSES ========================

    private record PriceLevel(double price, LevelSource source, double weight) {}

    /**
     * Sources of support/resistance levels
     */
    public enum LevelSource {
        DAILY_PIVOT, DAILY_R1, DAILY_R2, DAILY_R3, DAILY_S1, DAILY_S2, DAILY_S3,
        WEEKLY_PIVOT, WEEKLY_R1, WEEKLY_S1,
        MONTHLY_PIVOT, MONTHLY_R1, MONTHLY_S1,
        BB_UPPER, BB_MIDDLE, BB_LOWER,
        GAMMA_FLIP, GAMMA_WALL, GEX_SUPPORT, GEX_RESISTANCE,
        MAX_PAIN,
        SWING_HIGH, SWING_LOW,
        VWAP,
        CUSTOM
    }

    /**
     * Zone type
     */
    public enum ZoneType {
        SUPPORT, RESISTANCE
    }

    /**
     * A confluence zone where multiple S/R levels align
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfluenceZone {
        private double centerPrice;
        private double upperBound;
        private double lowerBound;
        private double strength;        // 0-1
        private int sourceCount;
        private List<LevelSource> sources;
        private ZoneType type;
        private boolean isStrong;

        /**
         * Get zone width as percentage
         */
        public double getWidthPct() {
            if (centerPrice <= 0) return 0;
            return (upperBound - lowerBound) / centerPrice * 100;
        }

        /**
         * Get description
         */
        public String getDescription() {
            return String.format("%s zone at %.2f (%.2f-%.2f) with %d sources, strength %.2f%s",
                    type, centerPrice, lowerBound, upperBound, sourceCount, strength,
                    isStrong ? " [STRONG]" : "");
        }

        /**
         * Get signal modifier for trades into this zone
         * Reduce confidence when trading into strong zone
         */
        public double getSignalModifier(boolean tradingIntoZone) {
            if (!tradingIntoZone) {
                // Trading with support/resistance
                return 1.0 + strength * 0.2; // Boost up to 20%
            } else {
                // Trading against zone
                return 1.0 - strength * 0.3; // Reduce up to 30%
            }
        }
    }

    /**
     * Result of confluence calculation
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfluenceResult {
        private List<ConfluenceZone> zones;
        private ConfluenceZone nearestSupport;
        private ConfluenceZone nearestResistance;
        private ConfluenceZone currentZone;
        private boolean isInConfluenceZone;
        private Double supportDistance;      // % distance to nearest support
        private Double resistanceDistance;   // % distance to nearest resistance

        public static ConfluenceResult empty() {
            return ConfluenceResult.builder()
                    .zones(Collections.emptyList())
                    .isInConfluenceZone(false)
                    .build();
        }

        /**
         * Get nearest support price
         */
        public Double getNearestSupportPrice() {
            return nearestSupport != null ? nearestSupport.getCenterPrice() : null;
        }

        /**
         * Get nearest resistance price
         */
        public Double getNearestResistancePrice() {
            return nearestResistance != null ? nearestResistance.getCenterPrice() : null;
        }

        /**
         * Check if at support
         */
        public boolean isAtSupport() {
            return supportDistance != null && supportDistance < 0.3;
        }

        /**
         * Check if at resistance
         */
        public boolean isAtResistance() {
            return resistanceDistance != null && resistanceDistance < 0.3;
        }

        /**
         * Get strong zones only
         */
        public List<ConfluenceZone> getStrongZones() {
            return zones.stream()
                    .filter(ConfluenceZone::isStrong)
                    .collect(Collectors.toList());
        }

        /**
         * Get signal modifier based on confluence
         */
        public double getSignalModifier(boolean isLongSignal) {
            double modifier = 1.0;

            // Boost at support for longs
            if (isLongSignal && isAtSupport() && nearestSupport != null) {
                modifier *= 1.0 + nearestSupport.getStrength() * 0.15;
            }

            // Boost at resistance for shorts
            if (!isLongSignal && isAtResistance() && nearestResistance != null) {
                modifier *= 1.0 + nearestResistance.getStrength() * 0.15;
            }

            // Reduce for longs at resistance
            if (isLongSignal && isAtResistance() && nearestResistance != null && nearestResistance.isStrong()) {
                modifier *= 0.8;
            }

            // Reduce for shorts at support
            if (!isLongSignal && isAtSupport() && nearestSupport != null && nearestSupport.isStrong()) {
                modifier *= 0.8;
            }

            return Math.max(0.5, Math.min(1.3, modifier));
        }
    }
}
