package com.kotsin.consumer.signal.processor;

import com.kotsin.consumer.logging.TraceContext;
import com.kotsin.consumer.model.StrategyState.PivotState;
import com.kotsin.consumer.model.StrategyState.PriceLevel;
import com.kotsin.consumer.model.StrategyState.SwingLevel;
import com.kotsin.consumer.model.UnifiedCandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PivotProcessor - Swing point and market structure detection.
 *
 * Identifies:
 * - Swing Highs/Lows (pivot points)
 * - Market Structure (uptrend, downtrend, range)
 * - Support/Resistance levels
 * - Structure breaks (BOS - Break of Structure)
 *
 * Uses N-bar lookback for swing detection (typically 3-5 bars).
 */
@Component
@Slf4j
public class PivotProcessor {

    private static final String LOG_PREFIX = "[PIVOT]";
    private static final int DEFAULT_SWING_LOOKBACK = 3;
    private static final int MAX_SWING_HISTORY = 20;
    private static final double LEVEL_MERGE_THRESHOLD = 0.002; // 0.2%

    /**
     * Calculate pivot state from candle history.
     *
     * @param candles List of candles (most recent first)
     * @param existingState Current pivot state (for continuity)
     * @return Updated PivotState
     */
    public PivotState calculate(List<UnifiedCandle> candles, PivotState existingState) {
        TraceContext.addStage("PIVOT");

        if (candles == null || candles.size() < DEFAULT_SWING_LOOKBACK * 2 + 1) {
            log.debug("{} {} Insufficient candles ({}) for pivot detection, need at least {}",
                LOG_PREFIX, TraceContext.getShortPrefix(),
                candles != null ? candles.size() : 0,
                DEFAULT_SWING_LOOKBACK * 2 + 1);
            return createEmptyState();
        }

        log.debug("{} {} Calculating pivots with {} candles",
            LOG_PREFIX, TraceContext.getShortPrefix(), candles.size());

        // Reverse to process oldest first
        List<UnifiedCandle> chronological = new ArrayList<>(candles);
        Collections.reverse(chronological);

        // Detect swing points
        List<SwingLevel> swingHighs = detectSwingHighs(chronological);
        List<SwingLevel> swingLows = detectSwingLows(chronological);

        // Merge with existing state for continuity
        if (existingState != null) {
            swingHighs = mergeSwings(existingState.getSwingHighs(), swingHighs);
            swingLows = mergeSwings(existingState.getSwingLows(), swingLows);
        }

        // Trim to max history
        if (swingHighs.size() > MAX_SWING_HISTORY) {
            swingHighs = new ArrayList<>(swingHighs.subList(swingHighs.size() - MAX_SWING_HISTORY, swingHighs.size()));
        }
        if (swingLows.size() > MAX_SWING_HISTORY) {
            swingLows = new ArrayList<>(swingLows.subList(swingLows.size() - MAX_SWING_HISTORY, swingLows.size()));
        }

        // Determine market structure
        MarketStructureResult structure = determineStructure(swingHighs, swingLows);

        // Derive support/resistance levels
        List<PriceLevel> supportLevels = deriveSupportLevels(swingLows, candles.get(0).getClose());
        List<PriceLevel> resistanceLevels = deriveResistanceLevels(swingHighs, candles.get(0).getClose());

        // Get last significant swings
        SwingLevel lastHigh = swingHighs.isEmpty() ? null : swingHighs.get(swingHighs.size() - 1);
        SwingLevel lastLow = swingLows.isEmpty() ? null : swingLows.get(swingLows.size() - 1);

        log.debug("{} {} Pivot complete: structure={}, swingHighs={}, swingLows={}, supports={}, resistances={}, HH={}, HL={}, LH={}, LL={}",
            LOG_PREFIX, TraceContext.getShortPrefix(),
            structure.structure,
            swingHighs.size(),
            swingLows.size(),
            supportLevels.size(),
            resistanceLevels.size(),
            structure.higherHighs,
            structure.higherLows,
            structure.lowerHighs,
            structure.lowerLows);

        return PivotState.builder()
            .swingHighs(swingHighs)
            .swingLows(swingLows)
            .supportLevels(supportLevels)
            .resistanceLevels(resistanceLevels)
            .structure(structure.structure)
            .higherHighs(structure.higherHighs)
            .higherLows(structure.higherLows)
            .lowerHighs(structure.lowerHighs)
            .lowerLows(structure.lowerLows)
            .lastSwingHigh(lastHigh)
            .lastSwingLow(lastLow)
            .calculatedAt(Instant.now())
            .build();
    }

    /**
     * Detect swing highs using N-bar lookback.
     */
    private List<SwingLevel> detectSwingHighs(List<UnifiedCandle> candles) {
        List<SwingLevel> swings = new ArrayList<>();

        for (int i = DEFAULT_SWING_LOOKBACK; i < candles.size() - DEFAULT_SWING_LOOKBACK; i++) {
            UnifiedCandle current = candles.get(i);
            if (current == null) continue;

            boolean isSwingHigh = true;

            // Check if current high is higher than N bars on each side
            for (int j = 1; j <= DEFAULT_SWING_LOOKBACK; j++) {
                UnifiedCandle left = candles.get(i - j);
                UnifiedCandle right = candles.get(i + j);

                if (left == null || right == null) {
                    isSwingHigh = false;
                    break;
                }

                if (current.getHigh() <= left.getHigh() || current.getHigh() <= right.getHigh()) {
                    isSwingHigh = false;
                    break;
                }
            }

            if (isSwingHigh) {
                swings.add(SwingLevel.builder()
                    .price(current.getHigh())
                    .timestamp(current.getTimestamp())
                    .volume(current.getVolume())
                    .barIndex(i)
                    .confirmed(true)
                    .retestCount(0)
                    .build());
            }
        }

        return swings;
    }

    /**
     * Detect swing lows using N-bar lookback.
     */
    private List<SwingLevel> detectSwingLows(List<UnifiedCandle> candles) {
        List<SwingLevel> swings = new ArrayList<>();

        for (int i = DEFAULT_SWING_LOOKBACK; i < candles.size() - DEFAULT_SWING_LOOKBACK; i++) {
            UnifiedCandle current = candles.get(i);
            if (current == null) continue;

            boolean isSwingLow = true;

            for (int j = 1; j <= DEFAULT_SWING_LOOKBACK; j++) {
                UnifiedCandle left = candles.get(i - j);
                UnifiedCandle right = candles.get(i + j);

                if (left == null || right == null) {
                    isSwingLow = false;
                    break;
                }

                if (current.getLow() >= left.getLow() || current.getLow() >= right.getLow()) {
                    isSwingLow = false;
                    break;
                }
            }

            if (isSwingLow) {
                swings.add(SwingLevel.builder()
                    .price(current.getLow())
                    .timestamp(current.getTimestamp())
                    .volume(current.getVolume())
                    .barIndex(i)
                    .confirmed(true)
                    .retestCount(0)
                    .build());
            }
        }

        return swings;
    }

    /**
     * Merge new swings with existing, avoiding duplicates.
     */
    private List<SwingLevel> mergeSwings(List<SwingLevel> existing, List<SwingLevel> newSwings) {
        if (existing == null || existing.isEmpty()) return newSwings;
        if (newSwings == null || newSwings.isEmpty()) return existing;

        Map<String, SwingLevel> merged = new LinkedHashMap<>();

        // Add existing
        for (SwingLevel swing : existing) {
            String key = createSwingKey(swing);
            merged.put(key, swing);
        }

        // Add or update new
        for (SwingLevel swing : newSwings) {
            String key = createSwingKey(swing);
            if (!merged.containsKey(key)) {
                merged.put(key, swing);
            }
        }

        return new ArrayList<>(merged.values());
    }

    private String createSwingKey(SwingLevel swing) {
        // Round price to avoid floating point issues
        long roundedPrice = Math.round(swing.getPrice() * 100);
        return roundedPrice + "_" + (swing.getTimestamp() != null ? swing.getTimestamp().toEpochMilli() / 60000 : 0);
    }

    /**
     * Determine market structure from swing points.
     */
    private MarketStructureResult determineStructure(List<SwingLevel> highs, List<SwingLevel> lows) {
        MarketStructureResult result = new MarketStructureResult();

        if (highs.size() < 2 || lows.size() < 2) {
            result.structure = "UNKNOWN";
            return result;
        }

        // Get last 2-3 swings
        int n = Math.min(3, Math.min(highs.size(), lows.size()));

        // Check highs pattern
        List<SwingLevel> recentHighs = highs.subList(highs.size() - n, highs.size());
        result.higherHighs = isAscending(recentHighs);
        result.lowerHighs = isDescending(recentHighs);

        // Check lows pattern
        List<SwingLevel> recentLows = lows.subList(lows.size() - n, lows.size());
        result.higherLows = isAscending(recentLows);
        result.lowerLows = isDescending(recentLows);

        // Determine structure
        if (result.higherHighs && result.higherLows) {
            result.structure = "UPTREND";
        } else if (result.lowerHighs && result.lowerLows) {
            result.structure = "DOWNTREND";
        } else if (result.lowerHighs && result.higherLows) {
            result.structure = "CONSOLIDATION";
        } else {
            result.structure = "RANGE";
        }

        return result;
    }

    private boolean isAscending(List<SwingLevel> swings) {
        for (int i = 1; i < swings.size(); i++) {
            if (swings.get(i).getPrice() <= swings.get(i - 1).getPrice()) {
                return false;
            }
        }
        return true;
    }

    private boolean isDescending(List<SwingLevel> swings) {
        for (int i = 1; i < swings.size(); i++) {
            if (swings.get(i).getPrice() >= swings.get(i - 1).getPrice()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Derive support levels from swing lows.
     */
    private List<PriceLevel> deriveSupportLevels(List<SwingLevel> swingLows, double currentPrice) {
        Map<Double, PriceLevel> levels = new TreeMap<>();

        for (SwingLevel swing : swingLows) {
            if (swing.getPrice() >= currentPrice) continue; // Only levels below

            double roundedPrice = roundToLevel(swing.getPrice());

            PriceLevel existing = levels.get(roundedPrice);
            if (existing == null) {
                levels.put(roundedPrice, PriceLevel.builder()
                    .price(roundedPrice)
                    .strength(calculateLevelStrength(swing))
                    .touchCount(1)
                    .firstTouch(swing.getTimestamp())
                    .lastTouch(swing.getTimestamp())
                    .broken(false)
                    .build());
            } else {
                existing.setTouchCount(existing.getTouchCount() + 1);
                existing.setStrength(Math.min(1, existing.getStrength() + 0.1));
                existing.setLastTouch(swing.getTimestamp());
            }
        }

        // Sort by proximity to current price
        return levels.values().stream()
            .sorted((a, b) -> Double.compare(b.getPrice(), a.getPrice()))
            .limit(5)
            .collect(Collectors.toList());
    }

    /**
     * Derive resistance levels from swing highs.
     */
    private List<PriceLevel> deriveResistanceLevels(List<SwingLevel> swingHighs, double currentPrice) {
        Map<Double, PriceLevel> levels = new TreeMap<>();

        for (SwingLevel swing : swingHighs) {
            if (swing.getPrice() <= currentPrice) continue; // Only levels above

            double roundedPrice = roundToLevel(swing.getPrice());

            PriceLevel existing = levels.get(roundedPrice);
            if (existing == null) {
                levels.put(roundedPrice, PriceLevel.builder()
                    .price(roundedPrice)
                    .strength(calculateLevelStrength(swing))
                    .touchCount(1)
                    .firstTouch(swing.getTimestamp())
                    .lastTouch(swing.getTimestamp())
                    .broken(false)
                    .build());
            } else {
                existing.setTouchCount(existing.getTouchCount() + 1);
                existing.setStrength(Math.min(1, existing.getStrength() + 0.1));
                existing.setLastTouch(swing.getTimestamp());
            }
        }

        // Sort by proximity to current price
        return levels.values().stream()
            .sorted(Comparator.comparingDouble(PriceLevel::getPrice))
            .limit(5)
            .collect(Collectors.toList());
    }

    private double calculateLevelStrength(SwingLevel swing) {
        // Base strength from volume (normalized)
        double volumeStrength = Math.min(1, swing.getVolume() / 1000000.0);
        double confirmationBonus = swing.isConfirmed() ? 0.2 : 0;
        double retestBonus = Math.min(0.3, swing.getRetestCount() * 0.1);

        return Math.min(1, 0.5 + volumeStrength * 0.2 + confirmationBonus + retestBonus);
    }

    private double roundToLevel(double price) {
        // Round to significant level (0.1% increment)
        double increment = price * 0.001;
        return Math.round(price / increment) * increment;
    }

    private PivotState createEmptyState() {
        return PivotState.builder()
            .swingHighs(new ArrayList<>())
            .swingLows(new ArrayList<>())
            .supportLevels(new ArrayList<>())
            .resistanceLevels(new ArrayList<>())
            .structure("UNKNOWN")
            .calculatedAt(Instant.now())
            .build();
    }

    /**
     * Helper class for structure analysis result.
     */
    private static class MarketStructureResult {
        String structure = "UNKNOWN";
        boolean higherHighs = false;
        boolean higherLows = false;
        boolean lowerHighs = false;
        boolean lowerLows = false;
    }

    /**
     * Check if a level has been broken.
     *
     * @param level The price level
     * @param candle Current candle
     * @param isSupport True if support, false if resistance
     * @return True if broken
     */
    public boolean isLevelBroken(PriceLevel level, UnifiedCandle candle, boolean isSupport) {
        if (level == null || candle == null || level.isBroken()) return false;

        if (isSupport) {
            // Support broken if close below level
            return candle.getClose() < level.getPrice() * 0.998; // 0.2% tolerance
        } else {
            // Resistance broken if close above level
            return candle.getClose() > level.getPrice() * 1.002;
        }
    }

    /**
     * Check for Break of Structure (BOS).
     *
     * @param state Current pivot state
     * @param candle Current candle
     * @return BOS type or null
     */
    public String checkBreakOfStructure(PivotState state, UnifiedCandle candle) {
        if (state == null || candle == null) return null;

        SwingLevel lastHigh = state.getLastSwingHigh();
        SwingLevel lastLow = state.getLastSwingLow();

        if (lastHigh != null && candle.getClose() > lastHigh.getPrice()) {
            return "BOS_BULLISH"; // Break above last swing high
        }

        if (lastLow != null && candle.getClose() < lastLow.getPrice()) {
            return "BOS_BEARISH"; // Break below last swing low
        }

        return null;
    }
}
