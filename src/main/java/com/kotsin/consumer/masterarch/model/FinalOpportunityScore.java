package com.kotsin.consumer.masterarch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;

/**
 * FinalOpportunityScore - MASTER ARCHITECTURE Final System Score
 * 
 * Formula:
 * Final_Opportunity_Score = Index_Context_Score × Security_Context_Score × Signal_Strength_Score × Direction_Confidence
 * 
 * Interpretation:
 * - ≥0.75 → Enter now
 * - 0.60–0.75 → Watchlist
 * - 0.45–0.60 → Monitor
 * - <0.45 → Reject
 * 
 * Score Decay:
 * If no follow through after N bars: Score_t = Score_t−1 × 0.90
 * Default N = 3 bars
 * 
 * Output Topic: score-final-opportunity
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FinalOpportunityScore {
    
    // ======================== IDENTIFICATION ========================
    
    private String scripCode;
    private String companyName;
    private long timestamp;
    
    // ======================== COMPONENT SCORES ========================
    
    /**
     * Part 1A: Index Context Score
     * Range: [-1.0, +1.0]
     */
    private double indexContextScore;
    
    /**
     * Part 1B: Security Context Score  
     * Range: [-1.0, +1.0]
     */
    private double securityContextScore;
    
    /**
     * Part 3: Signal Strength Score
     * = Fudkii × Volume_Certainty × Velocity_Adjusted × Structural_Score × Behaviour_Mult × CG_Mult
     * Range: [0.0, 1.0]
     */
    private double signalStrengthScore;
    
    /**
     * Part 4: Direction Confidence
     * = 0.40*Security_Context + 0.35*Signal_Strength + 0.25*Index_Context
     * Range: [0.0, 1.0]
     */
    private double directionConfidence;
    
    // ======================== FINAL SCORE ========================
    
    /**
     * Final multiplicative score with current/previous/delta tracking
     */
    private NormalizedScore finalScore;
    
    // ======================== DECISION ========================
    
    /**
     * Trade decision based on thresholds
     */
    private TradeDecision decision;
    private String decisionReason;
    
    // ======================== LOT SIZING ========================
    
    /**
     * Lot sizing based on final score:
     * - ≥0.80 → 2 lots + hedge optional
     * - 0.65–0.80 → 1 lot + hedge
     * - 0.55–0.65 → 1 lot futures only
     * - <0.55 → no trade
     */
    private int recommendedLots;
    private boolean hedgeRecommended;
    private boolean hedgeOptional;
    private String hedgeType;           // DIRECTIONAL, DELTA_NEUTRAL
    
    // ======================== DECAY TRACKING ========================
    
    /**
     * Bars since signal with no follow-through
     */
    private int barsWithoutFollowThrough;
    private boolean decayApplied;
    private int decayCount;
    
    // ======================== METADATA ========================
    
    private boolean isValid;
    private String invalidReason;
    private boolean isActionable;       // True if decision is ENTER_NOW
    
    // ======================== ENUMS ========================
    
    public enum TradeDecision {
        ENTER_NOW("≥0.75", "Execute trade immediately"),
        WATCHLIST("0.60-0.75", "Add to watchlist, wait for confirmation"),
        MONITOR("0.45-0.60", "Monitor only, do not trade"),
        REJECT("<0.45", "Reject signal");
        
        private final String threshold;
        private final String description;
        
        TradeDecision(String threshold, String description) {
            this.threshold = threshold;
            this.description = description;
        }
        
        public String getThreshold() { return threshold; }
        public String getDescription() { return description; }
    }
    
    // ======================== FACTORY METHODS ========================
    
    /**
     * Calculate Final Opportunity Score from all components
     */
    public static FinalOpportunityScore calculate(
            String scripCode,
            String companyName,
            long timestamp,
            double indexContextScore,
            double securityContextScore,
            double signalStrengthScore,
            double previousFinalScore
    ) {
        // Calculate direction confidence
        double directionConfidence = 0.40 * Math.abs(securityContextScore)
                                   + 0.35 * signalStrengthScore
                                   + 0.25 * Math.abs(indexContextScore);
        directionConfidence = clamp(directionConfidence, 0.0, 1.0);
        
        // Calculate final score (multiplicative)
        double rawFinal = Math.abs(indexContextScore) 
                        * Math.abs(securityContextScore) 
                        * signalStrengthScore 
                        * directionConfidence;
        
        // Apply direction sign (if both index and security agree)
        int indexSign = indexContextScore >= 0 ? 1 : -1;
        int securitySign = securityContextScore >= 0 ? 1 : -1;
        int direction = (indexSign == securitySign) ? indexSign : 0;
        
        double currentFinal = direction * rawFinal;
        currentFinal = clamp(currentFinal, -1.0, 1.0);
        
        NormalizedScore finalScore = NormalizedScore.directional(currentFinal, previousFinalScore, timestamp);
        
        // Determine decision
        double absScore = Math.abs(currentFinal);
        TradeDecision decision;
        if (absScore >= 0.75) {
            decision = TradeDecision.ENTER_NOW;
        } else if (absScore >= 0.60) {
            decision = TradeDecision.WATCHLIST;
        } else if (absScore >= 0.45) {
            decision = TradeDecision.MONITOR;
        } else {
            decision = TradeDecision.REJECT;
        }
        
        // Determine lot sizing
        int lots;
        boolean hedge;
        boolean hedgeOptional;
        if (absScore >= 0.80) {
            lots = 2;
            hedge = false;
            hedgeOptional = true;
        } else if (absScore >= 0.65) {
            lots = 1;
            hedge = true;
            hedgeOptional = false;
        } else if (absScore >= 0.55) {
            lots = 1;
            hedge = false;
            hedgeOptional = false;
        } else {
            lots = 0;
            hedge = false;
            hedgeOptional = false;
        }
        
        return FinalOpportunityScore.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(timestamp)
                .indexContextScore(indexContextScore)
                .securityContextScore(securityContextScore)
                .signalStrengthScore(signalStrengthScore)
                .directionConfidence(directionConfidence)
                .finalScore(finalScore)
                .decision(decision)
                .decisionReason(decision.getDescription())
                .recommendedLots(lots)
                .hedgeRecommended(hedge)
                .hedgeOptional(hedgeOptional)
                .isValid(true)
                .isActionable(decision == TradeDecision.ENTER_NOW)
                .build();
    }
    
    /**
     * Apply decay when no follow-through
     * Per spec: Score_t = Score_t−1 × 0.90
     */
    public FinalOpportunityScore applyDecay(long newTimestamp) {
        NormalizedScore decayedScore = finalScore.decay(0.90, newTimestamp);
        
        // Recalculate decision with decayed score
        double absScore = Math.abs(decayedScore.getCurrent());
        TradeDecision newDecision;
        if (absScore >= 0.75) {
            newDecision = TradeDecision.ENTER_NOW;
        } else if (absScore >= 0.60) {
            newDecision = TradeDecision.WATCHLIST;
        } else if (absScore >= 0.45) {
            newDecision = TradeDecision.MONITOR;
        } else {
            newDecision = TradeDecision.REJECT;
        }
        
        return FinalOpportunityScore.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(newTimestamp)
                .indexContextScore(indexContextScore)
                .securityContextScore(securityContextScore)
                .signalStrengthScore(signalStrengthScore)
                .directionConfidence(directionConfidence)
                .finalScore(decayedScore)
                .decision(newDecision)
                .decisionReason("Decayed: " + newDecision.getDescription())
                .barsWithoutFollowThrough(barsWithoutFollowThrough + 1)
                .decayApplied(true)
                .decayCount(decayCount + 1)
                .isValid(true)
                .isActionable(newDecision == TradeDecision.ENTER_NOW)
                .build();
    }
    
    public static FinalOpportunityScore invalid(String scripCode, String companyName, long timestamp, String reason) {
        return FinalOpportunityScore.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(timestamp)
                .finalScore(NormalizedScore.neutral(timestamp))
                .decision(TradeDecision.REJECT)
                .decisionReason(reason)
                .isValid(false)
                .invalidReason(reason)
                .isActionable(false)
                .build();
    }
    
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
    
    // ======================== SERIALIZATION ========================
    
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    public static Serde<FinalOpportunityScore> serde() {
        return Serdes.serdeFrom(new FinalOpportunityScoreSerializer(), new FinalOpportunityScoreDeserializer());
    }
    
    public static class FinalOpportunityScoreSerializer implements Serializer<FinalOpportunityScore> {
        @Override
        public byte[] serialize(String topic, FinalOpportunityScore data) {
            try {
                return data != null ? MAPPER.writeValueAsBytes(data) : null;
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed", e);
            }
        }
    }
    
    public static class FinalOpportunityScoreDeserializer implements Deserializer<FinalOpportunityScore> {
        @Override
        public FinalOpportunityScore deserialize(String topic, byte[] bytes) {
            try {
                return bytes != null ? MAPPER.readValue(bytes, FinalOpportunityScore.class) : null;
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed", e);
            }
        }
    }
}
