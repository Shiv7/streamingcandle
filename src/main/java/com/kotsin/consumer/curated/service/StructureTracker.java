package com.kotsin.consumer.curated.service;

import com.kotsin.consumer.curated.model.ConsolidationPattern;
import com.kotsin.consumer.curated.model.SwingPoint;
import com.kotsin.consumer.model.UnifiedCandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * StructureTracker - Tracks price structure (swing highs/lows) and detects consolidation
 *
 * Detects:
 * - Swing highs (local peaks)
 * - Swing lows (local troughs)
 * - Consolidation patterns (Lower Highs + Higher Lows)
 * - Compression ratio (how tight the range is)
 */
@Service
public class StructureTracker {

    private static final Logger log = LoggerFactory.getLogger(StructureTracker.class);

    // Store last 20 candles per scrip per timeframe
    private final Map<String, Deque<UnifiedCandle>> candleHistory = new ConcurrentHashMap<>();

    // Store detected swing points
    private final Map<String, List<SwingPoint>> swingPoints = new ConcurrentHashMap<>();

    // Configuration
    private static final int SWING_LOOKBACK = 2;      // Bars before/after for swing detection
    private static final int MAX_CANDLE_HISTORY = 20;
    private static final double COMPRESSION_THRESHOLD = 1.5;  // Tight coil threshold

    /**
     * Update candle history for a scrip-timeframe
     */
    public void updateCandle(UnifiedCandle candle) {
        String key = key(candle.getScripCode(), candle.getTimeframe());

        candleHistory.computeIfAbsent(key, k -> new LinkedList<>()).addLast(candle);

        // Keep only last 20 candles
        Deque<UnifiedCandle> history = candleHistory.get(key);
        while (history.size() > MAX_CANDLE_HISTORY) {
            history.removeFirst();
        }

        // Try to detect new swing points
        detectAndStoreSwingPoints(candle.getScripCode(), candle.getTimeframe());
    }

    /**
     * Detect swing high: High higher than N bars before and after
     */
    public SwingPoint detectSwingHigh(String scripCode, String timeframe) {
        Deque<UnifiedCandle> history = candleHistory.get(key(scripCode, timeframe));
        if (history == null || history.size() < (SWING_LOOKBACK * 2 + 1)) {
            return null;
        }

        List<UnifiedCandle> candles = new ArrayList<>(history);
        int checkIdx = candles.size() - SWING_LOOKBACK - 1;  // Check 3rd last bar

        if (checkIdx < SWING_LOOKBACK) return null;

        UnifiedCandle candidateBar = candles.get(checkIdx);

        // Check if this bar's high is higher than SWING_LOOKBACK bars before and after
        for (int i = 1; i <= SWING_LOOKBACK; i++) {
            if (candidateBar.getHigh() <= candles.get(checkIdx - i).getHigh()) {
                return null;  // Not a swing high
            }
            if (candidateBar.getHigh() <= candles.get(checkIdx + i).getHigh()) {
                return null;  // Not a swing high
            }
        }

        // This is a swing high!
        return SwingPoint.builder()
                .scripCode(scripCode)
                .timeframe(timeframe)
                .type(SwingPoint.SwingType.HIGH)
                .price(candidateBar.getHigh())
                .timestamp(candidateBar.getWindowEndMillis())
                .volume(candidateBar.getVolume())
                .barIndex(checkIdx)
                .build();
    }

    /**
     * Detect swing low: Low lower than N bars before and after
     */
    public SwingPoint detectSwingLow(String scripCode, String timeframe) {
        Deque<UnifiedCandle> history = candleHistory.get(key(scripCode, timeframe));
        if (history == null || history.size() < (SWING_LOOKBACK * 2 + 1)) {
            return null;
        }

        List<UnifiedCandle> candles = new ArrayList<>(history);
        int checkIdx = candles.size() - SWING_LOOKBACK - 1;

        if (checkIdx < SWING_LOOKBACK) return null;

        UnifiedCandle candidateBar = candles.get(checkIdx);

        // Check if this bar's low is lower than SWING_LOOKBACK bars before and after
        for (int i = 1; i <= SWING_LOOKBACK; i++) {
            if (candidateBar.getLow() >= candles.get(checkIdx - i).getLow()) {
                return null;
            }
            if (candidateBar.getLow() >= candles.get(checkIdx + i).getLow()) {
                return null;
            }
        }

        // This is a swing low!
        return SwingPoint.builder()
                .scripCode(scripCode)
                .timeframe(timeframe)
                .type(SwingPoint.SwingType.LOW)
                .price(candidateBar.getLow())
                .timestamp(candidateBar.getWindowEndMillis())
                .volume(candidateBar.getVolume())
                .barIndex(checkIdx)
                .build();
    }

    /**
     * Detect and store swing points
     */
    private void detectAndStoreSwingPoints(String scripCode, String timeframe) {
        SwingPoint high = detectSwingHigh(scripCode, timeframe);
        SwingPoint low = detectSwingLow(scripCode, timeframe);

        String key = key(scripCode, timeframe);
        swingPoints.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());

        if (high != null) {
            swingPoints.get(key).add(high);
            log.debug("Swing HIGH detected: {} {} @ {}", scripCode, timeframe, high.getPrice());
        }

        if (low != null) {
            swingPoints.get(key).add(low);
            log.debug("Swing LOW detected: {} {} @ {}", scripCode, timeframe, low.getPrice());
        }

        // Keep only last 10 swing points
        List<SwingPoint> points = swingPoints.get(key);
        if (points.size() > 10) {
            points.remove(0);
        }
    }

    /**
     * Detect consolidation pattern (Lower Highs + Higher Lows)
     */
    public ConsolidationPattern detectConsolidation(String scripCode, String timeframe) {
        String key = key(scripCode, timeframe);
        List<SwingPoint> points = swingPoints.get(key);

        if (points == null || points.size() < 4) {
            return null;
        }

        long currentTime = System.currentTimeMillis();
        long maxAge = 30 * 60 * 1000; // 30 minutes

        // Get recent highs and lows
        List<SwingPoint> recentHighs = points.stream()
                .filter(p -> p.getType() == SwingPoint.SwingType.HIGH)
                .filter(p -> p.isRecent(currentTime, maxAge))
                .sorted(Comparator.comparing(SwingPoint::getTimestamp).reversed())
                .limit(2)
                .collect(Collectors.toList());

        List<SwingPoint> recentLows = points.stream()
                .filter(p -> p.getType() == SwingPoint.SwingType.LOW)
                .filter(p -> p.isRecent(currentTime, maxAge))
                .sorted(Comparator.comparing(SwingPoint::getTimestamp).reversed())
                .limit(2)
                .collect(Collectors.toList());

        if (recentHighs.size() < 2 || recentLows.size() < 2) {
            return null;
        }

        // Lower High: Most recent high < Previous high
        boolean lowerHigh = recentHighs.get(0).getPrice() < recentHighs.get(1).getPrice();

        // Higher Low: Most recent low > Previous low
        boolean higherLow = recentLows.get(0).getPrice() > recentLows.get(1).getPrice();

        if (!lowerHigh || !higherLow) {
            return null;  // Not a consolidation pattern
        }

        // Calculate compression ratio
        double recentHighPrice = recentHighs.get(0).getPrice();
        double recentLowPrice = recentLows.get(0).getPrice();
        double range = recentHighPrice - recentLowPrice;

        // Get current ATR
        double atr = getCurrentATR(scripCode, timeframe);
        double compressionRatio = atr > 0 ? range / atr : 999;

        boolean isCoiling = compressionRatio < COMPRESSION_THRESHOLD;

        return ConsolidationPattern.builder()
                .scripCode(scripCode)
                .timeframe(timeframe)
                .recentHigh(recentHighPrice)
                .recentLow(recentLowPrice)
                .compressionRatio(compressionRatio)
                .isCoiling(isCoiling)
                .atr(atr)
                .detectedAt(currentTime)
                .build();
    }

    /**
     * Get current ATR for a scrip-timeframe
     * BUG-FIX: Use price-based default instead of arbitrary 1.0
     */
    private double getCurrentATR(String scripCode, String timeframe) {
        Deque<UnifiedCandle> history = candleHistory.get(key(scripCode, timeframe));
        if (history == null || history.isEmpty()) {
            // BUG-FIX: Return 0 to signal "no data" - caller should handle
            log.warn("No history for ATR calculation: {}/{}", scripCode, timeframe);
            return 0;
        }

        // Simple ATR: average of last 5 candle ranges
        List<UnifiedCandle> recent = new ArrayList<>(history).stream()
                .sorted(Comparator.comparing(UnifiedCandle::getWindowEndMillis).reversed())
                .limit(5)
                .collect(Collectors.toList());

        double avgRange = recent.stream()
                .mapToDouble(c -> c.getHigh() - c.getLow())
                .average()
                .orElse(0);

        // BUG-FIX: If ATR is suspiciously small (<0.01% of price), use 1% of price as fallback
        if (avgRange <= 0 && !recent.isEmpty()) {
            double price = recent.get(0).getClose();
            avgRange = price * 0.01;  // 1% of price as fallback ATR
            log.debug("Using fallback ATR for {}/{}: {} (1% of price {})", 
                    scripCode, timeframe, avgRange, price);
        }

        return avgRange;
    }

    /**
     * Get last N candles for a scrip-timeframe
     */
    public List<UnifiedCandle> getHistory(String scripCode, String timeframe, int limit) {
        Deque<UnifiedCandle> history = candleHistory.get(key(scripCode, timeframe));
        if (history == null) {
            return Collections.emptyList();
        }

        return new ArrayList<>(history).stream()
                .sorted(Comparator.comparing(UnifiedCandle::getWindowEndMillis).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Get latest candle
     */
    public UnifiedCandle getLatestCandle(String scripCode, String timeframe) {
        Deque<UnifiedCandle> history = candleHistory.get(key(scripCode, timeframe));
        return history != null ? history.peekLast() : null;
    }

    /**
     * Get previous (second-to-last) completed candle
     * Useful for MTF gate to avoid using partially formed current candle
     */
    public UnifiedCandle getPreviousCandle(String scripCode, String timeframe) {
        Deque<UnifiedCandle> history = candleHistory.get(key(scripCode, timeframe));
        if (history == null || history.size() < 2) {
            return null;
        }
        
        // Convert to list and get second-to-last
        List<UnifiedCandle> list = new ArrayList<>(history);
        return list.get(list.size() - 2);
    }

    private String key(String scripCode, String timeframe) {
        return scripCode + ":" + timeframe;
    }
}
