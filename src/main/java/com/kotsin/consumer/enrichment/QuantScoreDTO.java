package com.kotsin.consumer.enrichment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * QuantScoreDTO - Quantitative score for dashboard display.
 *
 * Published to Kafka topic: quant-scores
 * Cached in Redis: quant:score:{scripCode}:{timeframe}
 *
 * This DTO is consumed by the trading-dashboard backend to display
 * the quant-scores page with detailed breakdown of signal components.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuantScoreDTO {

    // Identification
    private String scripCode;
    private String symbol;
    private String companyName;
    private String timeframe;
    private Instant timestamp;

    // Composite Score (0-100)
    private double quantScore;
    private String direction;           // BULLISH, BEARISH, NEUTRAL
    private double confidence;          // 0-1
    private String signalStrength;      // WEAK, MODERATE, STRONG, VERY_STRONG

    // Score Breakdown (each 0-100)
    private ScoreBreakdown breakdown;

    // Signal Status
    private boolean isWatchSetup;
    private boolean isActiveTrigger;
    private boolean isActionable;
    private String reason;

    // Trend Context
    private String trendDirection;      // UPTREND, DOWNTREND, SIDEWAYS
    private String momentumState;       // ACCELERATING, DECELERATING, STEADY, EXHAUSTED
    private double exhaustionScore;     // 0-1

    // Pattern Context
    private List<String> detectedPatterns;
    private double patternConfidence;

    // Technical Levels
    private Double nearestSupport;
    private Double nearestResistance;
    private Double currentPrice;
    private Double atrPercent;

    // Volume Profile
    private double volumeRatio;         // Current vs average
    private boolean highVolume;

    // Additional Metadata
    private Map<String, Object> metadata;

    /**
     * Score breakdown by category.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScoreBreakdown {
        // FUDKII Components (0-100)
        private double flowScore;           // F - Order Flow Imbalance
        private double urgencyScore;        // U - IPU Score
        private double directionScore;      // D - VCP + Structure
        private double kyleScore;           // K - Price Impact
        private double imbalanceScore;      // I - Volume Imbalance
        private double intensityScore;      // I - OI Intensity

        // Additional Components (0-100)
        private double patternScore;        // Candlestick/Technical patterns
        private double trendScore;          // Trend alignment
        private double volumeProfileScore;  // VCP quality
        private double microstructureScore; // Orderbook quality
        private double optionsFlowScore;    // Options OI analysis
        private double greeksScore;         // Options greeks (if applicable)
        private double ivSurfaceScore;      // IV analysis (if applicable)
        private double crossInstrumentScore; // Family correlation
        private double confluenceScore;     // Multi-timeframe confluence
    }

    /**
     * Check if score is actionable.
     */
    public boolean isActionable() {
        return quantScore >= 60 && confidence >= 0.6 &&
               !"NEUTRAL".equals(direction) &&
               !"EXHAUSTED".equals(momentumState);
    }

    /**
     * Get signal strength from score.
     */
    public static String calculateSignalStrength(double score) {
        if (score >= 80) return "VERY_STRONG";
        if (score >= 60) return "STRONG";
        if (score >= 40) return "MODERATE";
        return "WEAK";
    }
}
