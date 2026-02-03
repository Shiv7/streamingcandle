package com.kotsin.consumer.pattern;

import com.kotsin.consumer.model.UnifiedCandle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * PatternAnalyzer - Detects candlestick and technical patterns.
 *
 * Detects:
 * 1. Single-candle patterns: Doji, Hammer, Shooting Star, Marubozu
 * 2. Two-candle patterns: Engulfing, Harami, Piercing, Dark Cloud
 * 3. Three-candle patterns: Morning Star, Evening Star, Three White Soldiers
 * 4. Technical patterns: Double Top/Bottom, Head & Shoulders (simplified)
 *
 * Results are published to Kafka topic: pattern-signals
 * And cached in Redis: pattern:{scripCode}:latest
 */
@Component
@Slf4j
public class PatternAnalyzer {

    private static final String LOG_PREFIX = "[PATTERN]";

    @Value("${pattern.detection.enabled:true}")
    private boolean enabled;

    @Value("${pattern.min.confidence:0.6}")
    private double minConfidence;

    @Value("${pattern.body.ratio.threshold:0.3}")
    private double bodyRatioThreshold;

    @Value("${pattern.doji.body.ratio:0.1}")
    private double dojiBodyRatio;

    /**
     * Analyze candles for patterns (legacy method, uses 5m timeframe).
     */
    public PatternResult analyze(UnifiedCandle current, List<UnifiedCandle> history) {
        return analyze(current, history, "5m");
    }

    /**
     * Analyze candles for patterns on a specific timeframe.
     * Supports multi-timeframe pattern detection.
     */
    public PatternResult analyze(UnifiedCandle current, List<UnifiedCandle> history, String timeframe) {
        if (!enabled || current == null) {
            return PatternResult.builder()
                .patterns(new ArrayList<>())
                .timeframe(timeframe != null ? timeframe : "5m")
                .timestamp(Instant.now())
                .build();
        }

        List<DetectedPattern> patterns = new ArrayList<>();

        // Single candle patterns
        detectSingleCandlePatterns(current, patterns);

        // Multi-candle patterns (need history)
        if (history != null && !history.isEmpty()) {
            detectTwoCandlePatterns(current, history, patterns);

            if (history.size() >= 2) {
                detectThreeCandlePatterns(current, history, patterns);
            }
        }

        // Filter by confidence
        patterns.removeIf(p -> p.getConfidence() < minConfidence);

        // Calculate overall pattern score
        double patternScore = patterns.isEmpty() ? 0 :
            patterns.stream().mapToDouble(DetectedPattern::getConfidence).max().orElse(0);

        // Determine overall bias
        String overallBias = determineOverallBias(patterns);

        String effectiveTimeframe = timeframe != null ? timeframe : "5m";

        log.debug("{} {} [{}] detected {} patterns, score={}, bias={}",
            LOG_PREFIX, current.getSymbol(), effectiveTimeframe, patterns.size(),
            String.format("%.2f", patternScore), overallBias);

        return PatternResult.builder()
            .scripCode(current.getScripCode())
            .symbol(current.getSymbol())
            .timeframe(effectiveTimeframe)
            .patterns(patterns)
            .patternScore(patternScore)
            .overallBias(overallBias)
            .timestamp(Instant.now())
            .hasHighConfidencePattern(patternScore >= 0.7)
            .build();
    }

    // ==================== SINGLE CANDLE PATTERNS ====================

    private void detectSingleCandlePatterns(UnifiedCandle candle, List<DetectedPattern> patterns) {
        double open = candle.getOpen();
        double close = candle.getClose();
        double high = candle.getHigh();
        double low = candle.getLow();

        double body = Math.abs(close - open);
        double range = high - low;

        if (range == 0) return;

        double bodyRatio = body / range;
        double upperWick = high - Math.max(open, close);
        double lowerWick = Math.min(open, close) - low;

        // Doji - very small body
        if (bodyRatio < dojiBodyRatio) {
            patterns.add(DetectedPattern.builder()
                .patternType(PatternType.DOJI)
                .patternName("Doji")
                .direction("NEUTRAL")
                .confidence(0.7 - bodyRatio)
                .description("Indecision candle - small body relative to range")
                .build());
        }

        // Hammer - small body at top, long lower wick
        if (bodyRatio < 0.35 && lowerWick > body * 2 && upperWick < body * 0.5) {
            boolean bullish = close > open || (high - close) < (close - low) * 0.3;
            patterns.add(DetectedPattern.builder()
                .patternType(PatternType.HAMMER)
                .patternName(bullish ? "Hammer" : "Hanging Man")
                .direction(bullish ? "BULLISH" : "BEARISH")
                .confidence(Math.min(0.8, lowerWick / (body * 3)))
                .description("Long lower wick indicates buying pressure at lows")
                .build());
        }

        // Shooting Star - small body at bottom, long upper wick
        if (bodyRatio < 0.35 && upperWick > body * 2 && lowerWick < body * 0.5) {
            boolean bearish = close < open || (close - low) < (high - close) * 0.3;
            patterns.add(DetectedPattern.builder()
                .patternType(PatternType.SHOOTING_STAR)
                .patternName(bearish ? "Shooting Star" : "Inverted Hammer")
                .direction(bearish ? "BEARISH" : "BULLISH")
                .confidence(Math.min(0.8, upperWick / (body * 3)))
                .description("Long upper wick indicates selling pressure at highs")
                .build());
        }

        // Marubozu - full body, no wicks
        if (bodyRatio > 0.9) {
            boolean bullish = close > open;
            patterns.add(DetectedPattern.builder()
                .patternType(PatternType.MARUBOZU)
                .patternName(bullish ? "Bullish Marubozu" : "Bearish Marubozu")
                .direction(bullish ? "BULLISH" : "BEARISH")
                .confidence(bodyRatio)
                .description("Strong momentum candle with minimal wicks")
                .build());
        }

        // Spinning Top - small body, balanced wicks
        if (bodyRatio > dojiBodyRatio && bodyRatio < 0.3) {
            double wickBalance = Math.min(upperWick, lowerWick) / Math.max(upperWick, lowerWick);
            if (wickBalance > 0.5) {
                patterns.add(DetectedPattern.builder()
                    .patternType(PatternType.SPINNING_TOP)
                    .patternName("Spinning Top")
                    .direction("NEUTRAL")
                    .confidence(0.5 + wickBalance * 0.2)
                    .description("Small body with balanced wicks - indecision")
                    .build());
            }
        }
    }

    // ==================== TWO CANDLE PATTERNS ====================

    private void detectTwoCandlePatterns(UnifiedCandle current, List<UnifiedCandle> history,
                                          List<DetectedPattern> patterns) {
        UnifiedCandle prev = history.get(0);
        if (prev == null) return;

        double currOpen = current.getOpen();
        double currClose = current.getClose();
        double currBody = Math.abs(currClose - currOpen);

        double prevOpen = prev.getOpen();
        double prevClose = prev.getClose();
        double prevBody = Math.abs(prevClose - prevOpen);

        boolean currBullish = currClose > currOpen;
        boolean prevBullish = prevClose > prevOpen;

        // Bullish Engulfing
        if (currBullish && !prevBullish &&
            currOpen <= prevClose && currClose >= prevOpen &&
            currBody > prevBody * 1.2) {

            patterns.add(DetectedPattern.builder()
                .patternType(PatternType.BULLISH_ENGULFING)
                .patternName("Bullish Engulfing")
                .direction("BULLISH")
                .confidence(Math.min(0.85, currBody / prevBody * 0.5))
                .description("Current candle completely engulfs previous bearish candle")
                .build());
        }

        // Bearish Engulfing
        if (!currBullish && prevBullish &&
            currOpen >= prevClose && currClose <= prevOpen &&
            currBody > prevBody * 1.2) {

            patterns.add(DetectedPattern.builder()
                .patternType(PatternType.BEARISH_ENGULFING)
                .patternName("Bearish Engulfing")
                .direction("BEARISH")
                .confidence(Math.min(0.85, currBody / prevBody * 0.5))
                .description("Current candle completely engulfs previous bullish candle")
                .build());
        }

        // Bullish Harami
        if (currBullish && !prevBullish &&
            currOpen > prevClose && currClose < prevOpen &&
            currBody < prevBody * 0.5) {

            patterns.add(DetectedPattern.builder()
                .patternType(PatternType.BULLISH_HARAMI)
                .patternName("Bullish Harami")
                .direction("BULLISH")
                .confidence(0.65)
                .description("Small bullish candle inside previous bearish candle")
                .build());
        }

        // Bearish Harami
        if (!currBullish && prevBullish &&
            currOpen < prevClose && currClose > prevOpen &&
            currBody < prevBody * 0.5) {

            patterns.add(DetectedPattern.builder()
                .patternType(PatternType.BEARISH_HARAMI)
                .patternName("Bearish Harami")
                .direction("BEARISH")
                .confidence(0.65)
                .description("Small bearish candle inside previous bullish candle")
                .build());
        }

        // Piercing Line
        if (currBullish && !prevBullish &&
            currOpen < prevClose && currClose > (prevOpen + prevClose) / 2 &&
            currClose < prevOpen) {

            double penetration = (currClose - prevClose) / prevBody;
            patterns.add(DetectedPattern.builder()
                .patternType(PatternType.PIERCING_LINE)
                .patternName("Piercing Line")
                .direction("BULLISH")
                .confidence(Math.min(0.75, penetration))
                .description("Bullish candle pierces more than halfway into previous bearish candle")
                .build());
        }

        // Dark Cloud Cover
        if (!currBullish && prevBullish &&
            currOpen > prevClose && currClose < (prevOpen + prevClose) / 2 &&
            currClose > prevOpen) {

            double penetration = (prevClose - currClose) / prevBody;
            patterns.add(DetectedPattern.builder()
                .patternType(PatternType.DARK_CLOUD_COVER)
                .patternName("Dark Cloud Cover")
                .direction("BEARISH")
                .confidence(Math.min(0.75, penetration))
                .description("Bearish candle pierces more than halfway into previous bullish candle")
                .build());
        }

        // Tweezer patterns
        double highDiff = Math.abs(current.getHigh() - prev.getHigh()) / prev.getHigh();
        double lowDiff = Math.abs(current.getLow() - prev.getLow()) / prev.getLow();

        if (highDiff < 0.001 && currBullish != prevBullish) {
            patterns.add(DetectedPattern.builder()
                .patternType(PatternType.TWEEZER_TOP)
                .patternName("Tweezer Top")
                .direction("BEARISH")
                .confidence(0.65)
                .description("Two candles with matching highs - potential resistance")
                .build());
        }

        if (lowDiff < 0.001 && currBullish != prevBullish) {
            patterns.add(DetectedPattern.builder()
                .patternType(PatternType.TWEEZER_BOTTOM)
                .patternName("Tweezer Bottom")
                .direction("BULLISH")
                .confidence(0.65)
                .description("Two candles with matching lows - potential support")
                .build());
        }
    }

    // ==================== THREE CANDLE PATTERNS ====================

    private void detectThreeCandlePatterns(UnifiedCandle current, List<UnifiedCandle> history,
                                            List<DetectedPattern> patterns) {
        UnifiedCandle prev1 = history.get(0);
        UnifiedCandle prev2 = history.size() > 1 ? history.get(1) : null;
        if (prev1 == null || prev2 == null) return;

        boolean curr = current.getClose() > current.getOpen();
        boolean p1 = prev1.getClose() > prev1.getOpen();
        boolean p2 = prev2.getClose() > prev2.getOpen();

        double currBody = Math.abs(current.getClose() - current.getOpen());
        double p1Body = Math.abs(prev1.getClose() - prev1.getOpen());
        double p2Body = Math.abs(prev2.getClose() - prev2.getOpen());

        // Morning Star (Bullish reversal)
        if (!p2 && p1Body < Math.min(currBody, p2Body) * 0.5 && curr &&
            current.getClose() > (prev2.getOpen() + prev2.getClose()) / 2) {

            patterns.add(DetectedPattern.builder()
                .patternType(PatternType.MORNING_STAR)
                .patternName("Morning Star")
                .direction("BULLISH")
                .confidence(0.8)
                .description("Three-candle bullish reversal pattern")
                .build());
        }

        // Evening Star (Bearish reversal)
        if (p2 && p1Body < Math.min(currBody, p2Body) * 0.5 && !curr &&
            current.getClose() < (prev2.getOpen() + prev2.getClose()) / 2) {

            patterns.add(DetectedPattern.builder()
                .patternType(PatternType.EVENING_STAR)
                .patternName("Evening Star")
                .direction("BEARISH")
                .confidence(0.8)
                .description("Three-candle bearish reversal pattern")
                .build());
        }

        // Three White Soldiers
        if (curr && p1 && p2 &&
            current.getClose() > prev1.getClose() &&
            prev1.getClose() > prev2.getClose() &&
            current.getOpen() > prev1.getOpen() &&
            prev1.getOpen() > prev2.getOpen()) {

            patterns.add(DetectedPattern.builder()
                .patternType(PatternType.THREE_WHITE_SOLDIERS)
                .patternName("Three White Soldiers")
                .direction("BULLISH")
                .confidence(0.85)
                .description("Three consecutive bullish candles - strong uptrend")
                .build());
        }

        // Three Black Crows
        if (!curr && !p1 && !p2 &&
            current.getClose() < prev1.getClose() &&
            prev1.getClose() < prev2.getClose() &&
            current.getOpen() < prev1.getOpen() &&
            prev1.getOpen() < prev2.getOpen()) {

            patterns.add(DetectedPattern.builder()
                .patternType(PatternType.THREE_BLACK_CROWS)
                .patternName("Three Black Crows")
                .direction("BEARISH")
                .confidence(0.85)
                .description("Three consecutive bearish candles - strong downtrend")
                .build());
        }
    }

    // ==================== HELPER METHODS ====================

    private String determineOverallBias(List<DetectedPattern> patterns) {
        if (patterns.isEmpty()) return "NEUTRAL";

        int bullish = 0;
        int bearish = 0;

        for (DetectedPattern p : patterns) {
            if ("BULLISH".equals(p.getDirection())) bullish++;
            else if ("BEARISH".equals(p.getDirection())) bearish++;
        }

        if (bullish > bearish) return "BULLISH";
        if (bearish > bullish) return "BEARISH";
        return "NEUTRAL";
    }

    // ==================== RESULT CLASSES ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PatternResult {
        private String scripCode;
        private String symbol;
        private String timeframe;
        private List<DetectedPattern> patterns;
        private double patternScore;
        private String overallBias;
        private Instant timestamp;
        private boolean hasHighConfidencePattern;

        public List<String> getPatternNames() {
            if (patterns == null) return new ArrayList<>();
            return patterns.stream().map(DetectedPattern::getPatternName).toList();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DetectedPattern {
        private PatternType patternType;
        private String patternName;
        private String direction;       // BULLISH, BEARISH, NEUTRAL
        private double confidence;      // 0-1
        private String description;
    }

    public enum PatternType {
        // Single candle
        DOJI, HAMMER, SHOOTING_STAR, MARUBOZU, SPINNING_TOP,
        INVERTED_HAMMER, HANGING_MAN,

        // Two candle
        BULLISH_ENGULFING, BEARISH_ENGULFING,
        BULLISH_HARAMI, BEARISH_HARAMI,
        PIERCING_LINE, DARK_CLOUD_COVER,
        TWEEZER_TOP, TWEEZER_BOTTOM,

        // Three candle
        MORNING_STAR, EVENING_STAR,
        THREE_WHITE_SOLDIERS, THREE_BLACK_CROWS,

        // Technical
        DOUBLE_TOP, DOUBLE_BOTTOM,
        HEAD_AND_SHOULDERS, INVERSE_HEAD_AND_SHOULDERS
    }
}
