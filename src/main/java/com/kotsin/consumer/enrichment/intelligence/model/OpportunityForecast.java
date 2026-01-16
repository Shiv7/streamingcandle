package com.kotsin.consumer.enrichment.intelligence.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * OpportunityForecast - Complete forecast for a family
 *
 * Contains multiple predictions with overall assessment
 * of upcoming opportunities and risks.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpportunityForecast {

    /**
     * Family this forecast applies to
     */
    private String familyId;

    /**
     * When forecast was generated
     */
    private Instant generatedAt;

    /**
     * When forecast expires
     */
    private Instant validUntil;

    // ======================== PREDICTIONS ========================

    /**
     * All predictions in this forecast
     */
    @Builder.Default
    private List<PredictedEvent> predictions = new ArrayList<>();

    /**
     * Top bullish prediction
     */
    private PredictedEvent topBullishPrediction;

    /**
     * Top bearish prediction
     */
    private PredictedEvent topBearishPrediction;

    /**
     * Most imminent prediction
     */
    private PredictedEvent mostImminentPrediction;

    // ======================== SUMMARY ========================

    /**
     * Overall forecast direction
     */
    private ForecastDirection overallDirection;

    /**
     * Confidence in overall direction
     */
    private double overallConfidence;

    /**
     * Expected next 30 min move percentage
     */
    private double expectedMove30Min;

    /**
     * Expected next hour move percentage
     */
    private double expectedMoveHour;

    /**
     * Expected volatility level
     */
    private VolatilityForecast volatilityForecast;

    // ======================== KEY LEVELS ========================

    /**
     * Immediate upside target
     */
    private Double immediateUpsideTarget;

    /**
     * Immediate downside target
     */
    private Double immediateDownsideTarget;

    /**
     * Key resistance to watch
     */
    private Double keyResistance;

    /**
     * Key support to watch
     */
    private Double keySupport;

    /**
     * Breakout level
     */
    private Double breakoutLevel;

    /**
     * Breakdown level
     */
    private Double breakdownLevel;

    // ======================== RISK ASSESSMENT ========================

    /**
     * Current risk level
     */
    private RiskLevel riskLevel;

    /**
     * Key risks identified
     */
    @Builder.Default
    private List<String> keyRisks = new ArrayList<>();

    /**
     * Suggested position sizing (0-1)
     */
    private double suggestedPositionSize;

    // ======================== OPPORTUNITY SCORE ========================

    /**
     * Long opportunity score (0-100)
     */
    private double longOpportunityScore;

    /**
     * Short opportunity score (0-100)
     */
    private double shortOpportunityScore;

    /**
     * Best opportunity type
     */
    private String bestOpportunityType;

    /**
     * Best opportunity description
     */
    private String bestOpportunityDescription;

    // ======================== ENUMS ========================

    public enum ForecastDirection {
        STRONGLY_BULLISH,
        BULLISH,
        NEUTRAL,
        BEARISH,
        STRONGLY_BEARISH
    }

    public enum VolatilityForecast {
        VERY_LOW,       // Expect minimal moves
        LOW,            // Below average volatility
        NORMAL,         // Average volatility
        HIGH,           // Above average volatility
        VERY_HIGH,      // Expect large moves
        EXPANSION       // Volatility currently low but expecting expansion
    }

    public enum RiskLevel {
        LOW,            // Safe to trade normally
        MODERATE,       // Caution advised
        HIGH,           // Reduce position sizes
        VERY_HIGH,      // Avoid new positions
        EXTREME         // Close existing positions if possible
    }

    // ======================== HELPER METHODS ========================

    /**
     * Add a prediction
     */
    public void addPrediction(PredictedEvent prediction) {
        predictions.add(prediction);
        updateTopPredictions();
    }

    /**
     * Update top predictions after changes
     */
    private void updateTopPredictions() {
        // Top bullish
        topBullishPrediction = predictions.stream()
                .filter(p -> p.getDirection() == PredictedEvent.PredictionDirection.BULLISH)
                .filter(PredictedEvent::isActive)
                .max(Comparator.comparingDouble(PredictedEvent::getConfidence))
                .orElse(null);

        // Top bearish
        topBearishPrediction = predictions.stream()
                .filter(p -> p.getDirection() == PredictedEvent.PredictionDirection.BEARISH)
                .filter(PredictedEvent::isActive)
                .max(Comparator.comparingDouble(PredictedEvent::getConfidence))
                .orElse(null);

        // Most imminent
        mostImminentPrediction = predictions.stream()
                .filter(PredictedEvent::isActive)
                .filter(p -> p.getTimeFrame() == PredictedEvent.TimeFrame.IMMEDIATE ||
                             p.getTimeFrame() == PredictedEvent.TimeFrame.SHORT_TERM)
                .max(Comparator.comparingDouble(PredictedEvent::getConfidence))
                .orElse(null);
    }

    /**
     * Get active predictions
     */
    public List<PredictedEvent> getActivePredictions() {
        return predictions.stream()
                .filter(PredictedEvent::isActive)
                .collect(Collectors.toList());
    }

    /**
     * Get high-confidence predictions (>= 0.7)
     */
    public List<PredictedEvent> getHighConfidencePredictions() {
        return predictions.stream()
                .filter(PredictedEvent::isActive)
                .filter(p -> p.getConfidence() >= 0.7)
                .collect(Collectors.toList());
    }

    /**
     * Get predictions by direction
     */
    public List<PredictedEvent> getPredictionsByDirection(PredictedEvent.PredictionDirection direction) {
        return predictions.stream()
                .filter(PredictedEvent::isActive)
                .filter(p -> p.getDirection() == direction)
                .collect(Collectors.toList());
    }

    /**
     * Get predictions by time frame
     */
    public List<PredictedEvent> getPredictionsByTimeFrame(PredictedEvent.TimeFrame timeFrame) {
        return predictions.stream()
                .filter(PredictedEvent::isActive)
                .filter(p -> p.getTimeFrame() == timeFrame)
                .collect(Collectors.toList());
    }

    /**
     * Get immediate predictions (< 15 min)
     */
    public List<PredictedEvent> getImmediatePredictions() {
        return getPredictionsByTimeFrame(PredictedEvent.TimeFrame.IMMEDIATE);
    }

    /**
     * Check if forecast is still valid
     */
    public boolean isValid() {
        return validUntil == null || Instant.now().isBefore(validUntil);
    }

    /**
     * Check if there are actionable opportunities
     */
    public boolean hasActionableOpportunities() {
        return longOpportunityScore >= 60 || shortOpportunityScore >= 60;
    }

    /**
     * Get dominant opportunity direction
     */
    public String getDominantDirection() {
        if (longOpportunityScore > shortOpportunityScore + 10) return "LONG";
        if (shortOpportunityScore > longOpportunityScore + 10) return "SHORT";
        return "NEUTRAL";
    }

    /**
     * Count predictions by status
     */
    public int countByStatus(PredictedEvent.PredictionStatus status) {
        return (int) predictions.stream()
                .filter(p -> p.getStatus() == status)
                .count();
    }

    /**
     * Get summary string
     */
    public String getSummary() {
        int active = countByStatus(PredictedEvent.PredictionStatus.ACTIVE);
        int inProgress = countByStatus(PredictedEvent.PredictionStatus.IN_PROGRESS);

        return String.format("Forecast[%s]: %s (%.0f%% conf), %d predictions (%d active, %d in-progress), " +
                        "Long:%.0f Short:%.0f, Risk:%s",
                familyId, overallDirection, overallConfidence * 100,
                predictions.size(), active, inProgress,
                longOpportunityScore, shortOpportunityScore, riskLevel);
    }

    /**
     * Create empty forecast
     */
    public static OpportunityForecast empty(String familyId) {
        return OpportunityForecast.builder()
                .familyId(familyId)
                .generatedAt(Instant.now())
                .validUntil(Instant.now().plusSeconds(300)) // 5 min validity
                .predictions(new ArrayList<>())
                .overallDirection(ForecastDirection.NEUTRAL)
                .overallConfidence(0.0)
                .expectedMove30Min(0.0)
                .expectedMoveHour(0.0)
                .volatilityForecast(VolatilityForecast.NORMAL)
                .riskLevel(RiskLevel.MODERATE)
                .keyRisks(new ArrayList<>())
                .suggestedPositionSize(0.5)
                .longOpportunityScore(50)
                .shortOpportunityScore(50)
                .bestOpportunityType("NONE")
                .bestOpportunityDescription("No clear opportunities identified")
                .build();
    }

    @Override
    public String toString() {
        return getSummary();
    }
}
