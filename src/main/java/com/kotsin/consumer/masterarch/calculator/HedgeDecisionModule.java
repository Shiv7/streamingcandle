package com.kotsin.consumer.masterarch.calculator;

import com.kotsin.consumer.masterarch.model.NormalizedScore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * HedgeDecisionModule - PART 4: Hedge Decision Module
 * 
 * MASTER ARCHITECTURE Compliant Module
 * 
 * When to Hedge:
 * - Direction_Confidence < 0.65 → hedge recommended
 * 
 * Hedge Type:
 * - Directional: if Regime_Strength > 0.6 (add to winning side)
 * - Delta Neutral: if Regime_Strength ≤ 0.6 (unclear direction)
 * 
 * Hedge Exit:
 * - If main position hits T1, close hedge
 * 
 * Output Topic: trade-position-size
 */
@Slf4j
@Component
public class HedgeDecisionModule {
    
    // Thresholds per spec
    private static final double HEDGE_TRIGGER_THRESHOLD = 0.65;
    private static final double DIRECTIONAL_HEDGE_THRESHOLD = 0.60;
    
    /**
     * Hedge Decision Output
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class HedgeDecisionOutput {
        private String scripCode;
        private String companyName;
        private long timestamp;
        
        // Input scores
        private double directionConfidence;
        private double regimeStrength;
        private double finalScore;
        
        // Decision
        private boolean hedgeRecommended;
        private boolean hedgeOptional;
        private HedgeType hedgeType;
        private String hedgeRationale;
        
        // Hedge specifications
        private int hedgeLots;
        private String hedgeStrike;         // ATM, OTM1, OTM2
        private String hedgeExpiry;         // WEEKLY, MONTHLY
        private double hedgeDelta;          // Target delta for hedge
        
        // Exit conditions
        private String hedgeExitTrigger;    // T1_HIT, SL_HIT, EOD
        
        // Metadata
        private boolean isValid;
    }
    
    public enum HedgeType {
        NONE("No hedge required"),
        DIRECTIONAL("Add to winning side - regime is clear"),
        DELTA_NEUTRAL("Protect both sides - regime unclear");
        
        private final String description;
        HedgeType(String description) { this.description = description; }
        public String getDescription() { return description; }
    }
    
    /**
     * Calculate Hedge Decision
     */
    public HedgeDecisionOutput calculate(
            String scripCode,
            String companyName,
            double directionConfidence,
            double regimeStrength,
            double finalScore,
            boolean isBullish
    ) {
        long timestamp = System.currentTimeMillis();
        
        // Check if hedge is needed
        boolean hedgeRecommended = directionConfidence < HEDGE_TRIGGER_THRESHOLD;
        boolean hedgeOptional = finalScore >= 0.80 && !hedgeRecommended;
        
        HedgeType hedgeType = HedgeType.NONE;
        String hedgeRationale = "Direction confidence sufficient, no hedge needed";
        int hedgeLots = 0;
        String hedgeStrike = "N/A";
        String hedgeExpiry = "N/A";
        double hedgeDelta = 0.0;
        String hedgeExitTrigger = "N/A";
        
        if (hedgeRecommended) {
            // Determine hedge type based on regime strength
            if (regimeStrength > DIRECTIONAL_HEDGE_THRESHOLD) {
                hedgeType = HedgeType.DIRECTIONAL;
                hedgeRationale = "Regime clear but confidence low - directional hedge";
                hedgeLots = 1;
                
                // For directional hedge: buy option in same direction
                if (isBullish) {
                    hedgeStrike = "OTM1";  // Slightly OTM call
                    hedgeDelta = 0.40;
                } else {
                    hedgeStrike = "OTM1";  // Slightly OTM put
                    hedgeDelta = -0.40;
                }
                hedgeExpiry = "WEEKLY";
                hedgeExitTrigger = "T1_HIT";
                
            } else {
                hedgeType = HedgeType.DELTA_NEUTRAL;
                hedgeRationale = "Regime unclear - delta neutral hedge";
                hedgeLots = 1;
                
                // For delta neutral: straddle/strangle
                hedgeStrike = "ATM";
                hedgeDelta = 0.0;  // Net delta neutral
                hedgeExpiry = "WEEKLY";
                hedgeExitTrigger = "SL_HIT";
            }
        }
        
        return HedgeDecisionOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(timestamp)
                // Inputs
                .directionConfidence(directionConfidence)
                .regimeStrength(regimeStrength)
                .finalScore(finalScore)
                // Decision
                .hedgeRecommended(hedgeRecommended)
                .hedgeOptional(hedgeOptional)
                .hedgeType(hedgeType)
                .hedgeRationale(hedgeRationale)
                // Specs
                .hedgeLots(hedgeLots)
                .hedgeStrike(hedgeStrike)
                .hedgeExpiry(hedgeExpiry)
                .hedgeDelta(hedgeDelta)
                // Exit
                .hedgeExitTrigger(hedgeExitTrigger)
                // Meta
                .isValid(true)
                .build();
    }
}
