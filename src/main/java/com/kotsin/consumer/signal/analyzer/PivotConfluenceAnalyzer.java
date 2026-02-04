package com.kotsin.consumer.signal.analyzer;

import com.kotsin.consumer.model.*;
import com.kotsin.consumer.model.ConfluenceResult.ConfluenceLevel;
import com.kotsin.consumer.model.ConfluenceResult.ConfluenceStrength;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * PivotConfluenceAnalyzer - Analyzes pivot confluence and detects bounce signals.
 *
 * Key concepts:
 * 1. CPR Width Analysis - Thin CPR = High breakout probability
 * 2. Confluence Detection - Multiple pivots at same price = Strong S/R
 * 3. Bounce Detection - Price reversal at pivot = Top/Bottom signal
 */
@Component
@Slf4j
public class PivotConfluenceAnalyzer {

    private static final String LOG_PREFIX = "[PIVOT-CONFLUENCE]";

    @Value("${pivot.confluence.threshold-percent:0.5}")
    private double confluenceThresholdPercent;

    @Value("${pivot.cpr.thin-threshold:0.5}")
    private double thinCprThreshold;

    @Value("${pivot.cpr.ultra-thin-threshold:0.3}")
    private double ultraThinCprThreshold;

    /**
     * Analyze CPR characteristics for trading decisions.
     */
    public CprAnalysis analyzeCpr(PivotLevels daily, double currentPrice) {
        if (daily == null || daily.getPivot() <= 0) {
            log.debug("{} No valid daily pivot data for CPR analysis", LOG_PREFIX);
            return createEmptyCprAnalysis();
        }

        double cprWidth = daily.getCprWidth();
        double cprWidthPercent = daily.getCprWidthPercent(currentPrice);

        // Determine CPR type
        CprAnalysis.CprType type;
        double breakoutProbability;

        if (cprWidthPercent < ultraThinCprThreshold) {
            type = CprAnalysis.CprType.ULTRA_THIN;
            breakoutProbability = 0.8;
            log.info("{} ULTRA-THIN CPR detected: {}% width | Breakout Prob: 80% | TC: {} | BC: {}",
                LOG_PREFIX, String.format("%.3f", cprWidthPercent),
                String.format("%.2f", daily.getTc()), String.format("%.2f", daily.getBc()));
        } else if (cprWidthPercent < thinCprThreshold) {
            type = CprAnalysis.CprType.THIN;
            breakoutProbability = 0.6;
            log.info("{} THIN CPR detected: {}% width | Breakout Prob: 60% | TC: {} | BC: {}",
                LOG_PREFIX, String.format("%.3f", cprWidthPercent),
                String.format("%.2f", daily.getTc()), String.format("%.2f", daily.getBc()));
        } else if (cprWidthPercent < 1.0) {
            type = CprAnalysis.CprType.NORMAL;
            breakoutProbability = 0.4;
            log.debug("{} NORMAL CPR: {}% width | TC: {} | BC: {}",
                LOG_PREFIX, String.format("%.3f", cprWidthPercent),
                String.format("%.2f", daily.getTc()), String.format("%.2f", daily.getBc()));
        } else {
            type = CprAnalysis.CprType.WIDE;
            breakoutProbability = 0.2;  // Mean reversion more likely
            log.debug("{} WIDE CPR: {}% width - Mean reversion likely | TC: {} | BC: {}",
                LOG_PREFIX, String.format("%.3f", cprWidthPercent),
                String.format("%.2f", daily.getTc()), String.format("%.2f", daily.getBc()));
        }

        // Determine price position relative to CPR
        CprAnalysis.PricePosition position;
        if (currentPrice > daily.getTc()) {
            position = CprAnalysis.PricePosition.ABOVE_CPR;
            log.debug("{} Price {} is ABOVE CPR (TC: {})", LOG_PREFIX,
                String.format("%.2f", currentPrice), String.format("%.2f", daily.getTc()));
        } else if (currentPrice < daily.getBc()) {
            position = CprAnalysis.PricePosition.BELOW_CPR;
            log.debug("{} Price {} is BELOW CPR (BC: {})", LOG_PREFIX,
                String.format("%.2f", currentPrice), String.format("%.2f", daily.getBc()));
        } else {
            position = CprAnalysis.PricePosition.INSIDE_CPR;
            log.debug("{} Price {} is INSIDE CPR (BC: {} - TC: {})", LOG_PREFIX,
                String.format("%.2f", currentPrice),
                String.format("%.2f", daily.getBc()), String.format("%.2f", daily.getTc()));
        }

        return CprAnalysis.builder()
            .cprWidth(cprWidth)
            .cprWidthPercent(cprWidthPercent)
            .type(type)
            .pricePosition(position)
            .breakoutProbability(breakoutProbability)
            .tc(daily.getTc())
            .bc(daily.getBc())
            .pivot(daily.getPivot())
            .build();
    }

    /**
     * Analyze confluence at a price level across all timeframes.
     */
    public ConfluenceResult analyzeConfluence(MultiTimeframePivotState state, double price) {
        if (state == null || !state.isValid()) {
            log.debug("{} No valid MTF pivot state for confluence analysis", LOG_PREFIX);
            return createEmptyConfluenceResult();
        }

        log.debug("{} Analyzing confluence at price {} for symbol {}",
            LOG_PREFIX, String.format("%.2f", price), state.getSymbol());

        List<ConfluenceLevel> levels = new ArrayList<>();
        double threshold = price * confluenceThresholdPercent / 100;

        // Check Daily Pivot levels
        if (state.getDailyPivot() != null) {
            checkAndAddLevels(levels, state.getDailyPivot(), price, threshold, "DAILY");
        }

        // Check Previous Daily Pivot levels
        if (state.getPrevDailyPivot() != null) {
            checkAndAddLevels(levels, state.getPrevDailyPivot(), price, threshold, "PREV_DAILY");
        }

        // Check Weekly Pivot levels
        if (state.getWeeklyPivot() != null) {
            checkAndAddLevels(levels, state.getWeeklyPivot(), price, threshold, "WEEKLY");
        }

        // Check Previous Weekly Pivot levels
        if (state.getPrevWeeklyPivot() != null) {
            checkAndAddLevels(levels, state.getPrevWeeklyPivot(), price, threshold, "PREV_WEEKLY");
        }

        // Check Monthly Pivot levels
        if (state.getMonthlyPivot() != null) {
            checkAndAddLevels(levels, state.getMonthlyPivot(), price, threshold, "MONTHLY");
        }

        // Determine confluence strength
        int count = levels.size();
        ConfluenceStrength strength;
        if (count >= 4) {
            strength = ConfluenceStrength.VERY_STRONG;
            log.info("{} VERY_STRONG confluence at {} | {} levels: {}",
                LOG_PREFIX, String.format("%.2f", price), count, formatLevels(levels));
        } else if (count >= 3) {
            strength = ConfluenceStrength.STRONG;
            log.info("{} STRONG confluence at {} | {} levels: {}",
                LOG_PREFIX, String.format("%.2f", price), count, formatLevels(levels));
        } else if (count >= 2) {
            strength = ConfluenceStrength.MODERATE;
            log.debug("{} MODERATE confluence at {} | {} levels: {}",
                LOG_PREFIX, String.format("%.2f", price), count, formatLevels(levels));
        } else {
            strength = ConfluenceStrength.WEAK;
            log.debug("{} WEAK/NO confluence at {} | {} levels",
                LOG_PREFIX, String.format("%.2f", price), count);
        }

        return ConfluenceResult.builder()
            .confluenceCount(count)
            .strength(strength)
            .priceLevel(price)
            .levels(levels)
            .build();
    }

    /**
     * Format levels for logging.
     */
    private String formatLevels(List<ConfluenceLevel> levels) {
        if (levels == null || levels.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (ConfluenceLevel level : levels) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(level.getTimeframe()).append("-").append(level.getLevelName())
              .append("@").append(String.format("%.2f", level.getLevelPrice()));
        }
        return sb.toString();
    }

    /**
     * Detect bounce signal at pivot levels.
     */
    public BounceSignal detectBounce(MultiTimeframePivotState state,
            List<UnifiedCandle> recentCandles, double currentPrice) {

        if (state == null || !state.isValid() || recentCandles == null || recentCandles.size() < 3) {
            log.debug("{} Insufficient data for bounce detection (candles: {})",
                LOG_PREFIX, recentCandles != null ? recentCandles.size() : 0);
            return null;
        }

        UnifiedCandle current = recentCandles.get(0);
        UnifiedCandle prev1 = recentCandles.get(1);
        UnifiedCandle prev2 = recentCandles.get(2);

        log.debug("{} Checking bounce for {} at price {} | Recent: O={} H={} L={} C={}",
            LOG_PREFIX, state.getSymbol(), String.format("%.2f", currentPrice),
            String.format("%.2f", current.getOpen()), String.format("%.2f", current.getHigh()),
            String.format("%.2f", current.getLow()), String.format("%.2f", current.getClose()));

        // Find nearest support and resistance
        NearestLevel nearestSupport = findNearestSupport(state, currentPrice);
        NearestLevel nearestResistance = findNearestResistance(state, currentPrice);

        if (nearestSupport != null) {
            log.debug("{} Nearest support: {} at {} (distance: {}%)",
                LOG_PREFIX, nearestSupport.name, String.format("%.2f", nearestSupport.price),
                String.format("%.2f", nearestSupport.distance / currentPrice * 100));
        }
        if (nearestResistance != null) {
            log.debug("{} Nearest resistance: {} at {} (distance: {}%)",
                LOG_PREFIX, nearestResistance.name, String.format("%.2f", nearestResistance.price),
                String.format("%.2f", nearestResistance.distance / currentPrice * 100));
        }

        // Check for bullish bounce at support
        if (nearestSupport != null && nearestSupport.distance < currentPrice * 0.01) { // Within 1%
            boolean touchedSupport = prev1.getLow() <= nearestSupport.price * 1.002; // Within 0.2%
            boolean bouncing = current.getClose() > prev1.getClose() &&
                              current.getClose() > current.getOpen();  // Bullish candle
            boolean volumeConfirm = current.getVolume() > prev1.getVolume() * 0.8; // Decent volume

            if (touchedSupport && bouncing && nearestSupport.confluence >= 2) {
                double bouncePercent = ((current.getClose() - prev1.getLow()) / prev1.getLow()) * 100;

                log.info("{} *** BULLISH BOUNCE DETECTED *** | Symbol: {} | Level: {} @ {} | " +
                    "Confluence: {} | Bounce: {}% | Touch: {} -> Current: {}",
                    LOG_PREFIX, state.getSymbol(), nearestSupport.name,
                    String.format("%.2f", nearestSupport.price), nearestSupport.confluence,
                    String.format("%.2f", bouncePercent),
                    String.format("%.2f", prev1.getLow()), String.format("%.2f", current.getClose()));

                return BounceSignal.builder()
                    .symbol(state.getSymbol())
                    .type(BounceSignal.BounceType.BULLISH_BOUNCE)
                    .level(nearestSupport.price)
                    .levelName(nearestSupport.name)
                    .confluence(nearestSupport.confluence)
                    .confidence(calculateConfidence(nearestSupport.confluence, bouncePercent))
                    .message("Bullish bounce at " + nearestSupport.name +
                            " (confluence: " + nearestSupport.confluence + ")")
                    .touchPrice(prev1.getLow())
                    .currentPrice(current.getClose())
                    .bouncePercent(bouncePercent)
                    .signalTime(Instant.now())
                    .candlesSinceBounce(1)
                    .build();
            }
        }

        // Check for bearish bounce at resistance
        if (nearestResistance != null && nearestResistance.distance < currentPrice * 0.01) { // Within 1%
            boolean touchedResistance = prev1.getHigh() >= nearestResistance.price * 0.998; // Within 0.2%
            boolean rejecting = current.getClose() < prev1.getClose() &&
                               current.getClose() < current.getOpen();  // Bearish candle
            boolean volumeConfirm = current.getVolume() > prev1.getVolume() * 0.8;

            if (touchedResistance && rejecting && nearestResistance.confluence >= 2) {
                double bouncePercent = ((prev1.getHigh() - current.getClose()) / prev1.getHigh()) * 100;

                log.info("{} *** BEARISH BOUNCE DETECTED *** | Symbol: {} | Level: {} @ {} | " +
                    "Confluence: {} | Rejection: {}% | Touch: {} -> Current: {}",
                    LOG_PREFIX, state.getSymbol(), nearestResistance.name,
                    String.format("%.2f", nearestResistance.price), nearestResistance.confluence,
                    String.format("%.2f", bouncePercent),
                    String.format("%.2f", prev1.getHigh()), String.format("%.2f", current.getClose()));

                return BounceSignal.builder()
                    .symbol(state.getSymbol())
                    .type(BounceSignal.BounceType.BEARISH_BOUNCE)
                    .level(nearestResistance.price)
                    .levelName(nearestResistance.name)
                    .confluence(nearestResistance.confluence)
                    .confidence(calculateConfidence(nearestResistance.confluence, bouncePercent))
                    .message("Bearish rejection at " + nearestResistance.name +
                            " (confluence: " + nearestResistance.confluence + ")")
                    .touchPrice(prev1.getHigh())
                    .currentPrice(current.getClose())
                    .bouncePercent(bouncePercent)
                    .signalTime(Instant.now())
                    .candlesSinceBounce(1)
                    .build();
            }
        }

        return null;
    }

    /**
     * Find nearest support level below current price.
     */
    private NearestLevel findNearestSupport(MultiTimeframePivotState state, double price) {
        NearestLevel nearest = null;
        double minDistance = Double.MAX_VALUE;

        // Check all support levels from all timeframes
        if (state.getDailyPivot() != null) {
            nearest = checkSupportLevels(state.getDailyPivot(), price, "Daily", nearest, minDistance);
            if (nearest != null) minDistance = nearest.distance;
        }
        if (state.getPrevDailyPivot() != null) {
            nearest = checkSupportLevels(state.getPrevDailyPivot(), price, "PrevDaily", nearest, minDistance);
            if (nearest != null) minDistance = nearest.distance;
        }
        if (state.getWeeklyPivot() != null) {
            nearest = checkSupportLevels(state.getWeeklyPivot(), price, "Weekly", nearest, minDistance);
            if (nearest != null) minDistance = nearest.distance;
        }
        if (state.getMonthlyPivot() != null) {
            nearest = checkSupportLevels(state.getMonthlyPivot(), price, "Monthly", nearest, minDistance);
        }

        // Calculate confluence for the nearest level
        if (nearest != null) {
            nearest.confluence = calculateLevelConfluence(state, nearest.price);
        }

        return nearest;
    }

    /**
     * Find nearest resistance level above current price.
     */
    private NearestLevel findNearestResistance(MultiTimeframePivotState state, double price) {
        NearestLevel nearest = null;
        double minDistance = Double.MAX_VALUE;

        // Check all resistance levels from all timeframes
        if (state.getDailyPivot() != null) {
            nearest = checkResistanceLevels(state.getDailyPivot(), price, "Daily", nearest, minDistance);
            if (nearest != null) minDistance = nearest.distance;
        }
        if (state.getPrevDailyPivot() != null) {
            nearest = checkResistanceLevels(state.getPrevDailyPivot(), price, "PrevDaily", nearest, minDistance);
            if (nearest != null) minDistance = nearest.distance;
        }
        if (state.getWeeklyPivot() != null) {
            nearest = checkResistanceLevels(state.getWeeklyPivot(), price, "Weekly", nearest, minDistance);
            if (nearest != null) minDistance = nearest.distance;
        }
        if (state.getMonthlyPivot() != null) {
            nearest = checkResistanceLevels(state.getMonthlyPivot(), price, "Monthly", nearest, minDistance);
        }

        // Calculate confluence for the nearest level
        if (nearest != null) {
            nearest.confluence = calculateLevelConfluence(state, nearest.price);
        }

        return nearest;
    }

    private NearestLevel checkSupportLevels(PivotLevels pivots, double price, String timeframe,
            NearestLevel current, double minDistance) {

        double[] supports = {pivots.getS1(), pivots.getS2(), pivots.getS3(), pivots.getBc(),
                            pivots.getCamS3(), pivots.getFibS1(), pivots.getFibS2()};
        String[] names = {"S1", "S2", "S3", "BC", "CamS3", "FibS1", "FibS2"};

        for (int i = 0; i < supports.length; i++) {
            if (supports[i] > 0 && supports[i] < price) {
                double distance = price - supports[i];
                if (distance < minDistance) {
                    minDistance = distance;
                    current = new NearestLevel(supports[i], timeframe + " " + names[i], distance, 0);
                }
            }
        }
        return current;
    }

    private NearestLevel checkResistanceLevels(PivotLevels pivots, double price, String timeframe,
            NearestLevel current, double minDistance) {

        double[] resistances = {pivots.getR1(), pivots.getR2(), pivots.getR3(), pivots.getTc(),
                               pivots.getCamR3(), pivots.getFibR1(), pivots.getFibR2()};
        String[] names = {"R1", "R2", "R3", "TC", "CamR3", "FibR1", "FibR2"};

        for (int i = 0; i < resistances.length; i++) {
            if (resistances[i] > 0 && resistances[i] > price) {
                double distance = resistances[i] - price;
                if (distance < minDistance) {
                    minDistance = distance;
                    current = new NearestLevel(resistances[i], timeframe + " " + names[i], distance, 0);
                }
            }
        }
        return current;
    }

    /**
     * Calculate how many timeframes have a level near this price.
     */
    private int calculateLevelConfluence(MultiTimeframePivotState state, double price) {
        int count = 0;
        double threshold = price * confluenceThresholdPercent / 100;

        if (state.getDailyPivot() != null && state.getDailyPivot().isNearAnyLevel(price, confluenceThresholdPercent)) {
            count++;
        }
        if (state.getPrevDailyPivot() != null && state.getPrevDailyPivot().isNearAnyLevel(price, confluenceThresholdPercent)) {
            count++;
        }
        if (state.getWeeklyPivot() != null && state.getWeeklyPivot().isNearAnyLevel(price, confluenceThresholdPercent)) {
            count++;
        }
        if (state.getPrevWeeklyPivot() != null && state.getPrevWeeklyPivot().isNearAnyLevel(price, confluenceThresholdPercent)) {
            count++;
        }
        if (state.getMonthlyPivot() != null && state.getMonthlyPivot().isNearAnyLevel(price, confluenceThresholdPercent)) {
            count++;
        }

        return count;
    }

    private void checkAndAddLevels(List<ConfluenceLevel> levels, PivotLevels pivots,
            double price, double threshold, String timeframe) {

        // Check Pivot
        if (pivots.getPivot() > 0 && Math.abs(price - pivots.getPivot()) <= threshold) {
            levels.add(createConfluenceLevel(timeframe, "Pivot", pivots.getPivot(), price));
        }
        // Check S1
        if (pivots.getS1() > 0 && Math.abs(price - pivots.getS1()) <= threshold) {
            levels.add(createConfluenceLevel(timeframe, "S1", pivots.getS1(), price));
        }
        // Check S2
        if (pivots.getS2() > 0 && Math.abs(price - pivots.getS2()) <= threshold) {
            levels.add(createConfluenceLevel(timeframe, "S2", pivots.getS2(), price));
        }
        // Check R1
        if (pivots.getR1() > 0 && Math.abs(price - pivots.getR1()) <= threshold) {
            levels.add(createConfluenceLevel(timeframe, "R1", pivots.getR1(), price));
        }
        // Check R2
        if (pivots.getR2() > 0 && Math.abs(price - pivots.getR2()) <= threshold) {
            levels.add(createConfluenceLevel(timeframe, "R2", pivots.getR2(), price));
        }
        // Check TC (Top Central)
        if (pivots.getTc() > 0 && Math.abs(price - pivots.getTc()) <= threshold) {
            levels.add(createConfluenceLevel(timeframe, "TC", pivots.getTc(), price));
        }
        // Check BC (Bottom Central)
        if (pivots.getBc() > 0 && Math.abs(price - pivots.getBc()) <= threshold) {
            levels.add(createConfluenceLevel(timeframe, "BC", pivots.getBc(), price));
        }
        // Check Camarilla S3 (important level)
        if (pivots.getCamS3() > 0 && Math.abs(price - pivots.getCamS3()) <= threshold) {
            levels.add(createConfluenceLevel(timeframe, "CamS3", pivots.getCamS3(), price));
        }
        // Check Camarilla R3 (important level)
        if (pivots.getCamR3() > 0 && Math.abs(price - pivots.getCamR3()) <= threshold) {
            levels.add(createConfluenceLevel(timeframe, "CamR3", pivots.getCamR3(), price));
        }
    }

    private ConfluenceLevel createConfluenceLevel(String timeframe, String levelName,
            double levelPrice, double currentPrice) {

        ConfluenceLevel.LevelType type;
        if (levelName.startsWith("S") || levelName.equals("BC") || levelName.startsWith("CamS") || levelName.startsWith("FibS")) {
            type = ConfluenceLevel.LevelType.SUPPORT;
        } else if (levelName.startsWith("R") || levelName.equals("TC") || levelName.startsWith("CamR") || levelName.startsWith("FibR")) {
            type = ConfluenceLevel.LevelType.RESISTANCE;
        } else if (levelName.equals("Pivot")) {
            type = ConfluenceLevel.LevelType.PIVOT;
        } else {
            type = ConfluenceLevel.LevelType.CPR;
        }

        double distancePercent = Math.abs(currentPrice - levelPrice) / currentPrice * 100;

        return ConfluenceLevel.builder()
            .timeframe(timeframe)
            .levelName(levelName)
            .levelPrice(levelPrice)
            .distancePercent(distancePercent)
            .type(type)
            .build();
    }

    private double calculateConfidence(int confluence, double bouncePercent) {
        // Base confidence from confluence
        double baseConfidence = Math.min(confluence * 0.2, 0.6);

        // Bonus from bounce magnitude
        double bounceBonus = Math.min(bouncePercent * 0.1, 0.2);

        return Math.min(baseConfidence + bounceBonus + 0.2, 1.0);
    }

    private CprAnalysis createEmptyCprAnalysis() {
        return CprAnalysis.builder()
            .cprWidth(0)
            .cprWidthPercent(0)
            .type(CprAnalysis.CprType.NORMAL)
            .pricePosition(CprAnalysis.PricePosition.INSIDE_CPR)
            .breakoutProbability(0)
            .build();
    }

    private ConfluenceResult createEmptyConfluenceResult() {
        return ConfluenceResult.builder()
            .confluenceCount(0)
            .strength(ConfluenceStrength.WEAK)
            .levels(new ArrayList<>())
            .build();
    }

    /**
     * Helper class for nearest level tracking.
     */
    private static class NearestLevel {
        double price;
        String name;
        double distance;
        int confluence;

        NearestLevel(double price, String name, double distance, int confluence) {
            this.price = price;
            this.name = name;
            this.distance = distance;
            this.confluence = confluence;
        }
    }
}
