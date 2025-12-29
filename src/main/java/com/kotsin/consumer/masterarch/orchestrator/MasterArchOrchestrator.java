package com.kotsin.consumer.masterarch.orchestrator;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.masterarch.calculator.*;
import com.kotsin.consumer.masterarch.model.*;
import com.kotsin.consumer.model.UnifiedCandle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * MasterArchOrchestrator - Wires all MASTER ARCHITECTURE modules together
 * 
 * Flow:
 * 1. Index Regime → Index_Context_Score
 * 2. Security Regime → Security_Context_Score  
 * 3. FUDKII → Fudkii_Strength
 * 4. Volume Canonical → Volume_Certainty
 * 5. Velocity MMS → Velocity_Adjusted
 * 6. Structural Validation → Structural_Score
 * 7. Behavioural Validation → Behaviour_Multiplier
 * 8. Correlation Governor → CG_Multiplier
 * 9. Signal Strength Score Calculation
 * 10. Direction Confidence Calculation
 * 11. Final Opportunity Score
 * 12. Position Sizing + Hedge Decision
 * 
 * Output: Final trade recommendation with all scores
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MasterArchOrchestrator {
    
    // Calculators
    private final MasterArchIndexRegimeCalculator indexRegimeCalculator;
    private final MasterArchSecurityRegimeCalculator securityRegimeCalculator;
    private final MasterArchFUDKIICalculator fudkiiCalculator;
    private final VolumeCanonicalCalculator volumeCalculator;
    private final VelocityMMSCalculator velocityCalculator;
    private final StructuralValidator structuralValidator;
    private final BehaviouralValidator behaviouralValidator;
    private final CorrelationGovernor correlationGovernor;
    private final HedgeDecisionModule hedgeModule;
    private final PositionSizer positionSizer;
    private final ScoreDecayManager decayManager;
    
    /**
     * Full orchestration result
     */
    @lombok.Data
    @lombok.Builder
    public static class MasterArchResult {
        // Identification
        private String scripCode;
        private String companyName;
        private long timestamp;
        
        // Part 1: Context Foundation
        private IndexContextScore indexContext;
        private SecurityContextScore securityContext;
        
        // Part 2: Signal Generation
        private MasterArchFUDKIICalculator.FUDKIIOutput fudkii;
        private VolumeCanonicalCalculator.VolumeOutput volume;
        private VelocityMMSCalculator.VelocityMMSOutput velocity;
        
        // Part 3: Signal Validation
        private StructuralValidator.StructuralValidationOutput structural;
        private BehaviouralValidator.BehaviouralValidationOutput behavioural;
        private CorrelationGovernor.CorrelationGovernorOutput correlation;
        
        // Part 4: Signal Strength & Final
        private SignalStrengthScore signalStrength;
        private FinalOpportunityScore finalScore;
        
        // Trade Construction
        private HedgeDecisionModule.HedgeDecisionOutput hedge;
        private PositionSizer.PositionSizeOutput position;
        
        // Summary
        private boolean isActionable;
        private String decision;
        private String rationale;
    }
    
    /**
     * Full processing pipeline for FamilyCandle
     */
    public MasterArchResult process(
            FamilyCandle familyCandle,
            List<InstrumentCandle> indexCandles30m,
            double vcpScore,
            double nearestPivot,
            double pivotDistance,
            String pivotType,
            double ohmScore,
            boolean optionBuyerFriendly
    ) {
        String scripCode = familyCandle.getFamilyId();
        String companyName = familyCandle.getSymbol();
        long timestamp = System.currentTimeMillis();
        
        // Convert to UnifiedCandle list
        List<UnifiedCandle> candles30m = convertToUnifiedCandles(familyCandle);
        
        // ======================== PART 1: CONTEXT FOUNDATION ========================
        
        // 1A. Index Regime
        IndexContextScore indexContext = indexRegimeCalculator.calculate(
                "NIFTY50",  // Would come from family mapping
                "999920000",
                indexCandles30m,
                0.0  // Previous score
        );
        
        // 1B. Security Regime
        SecurityContextScore securityContext = securityRegimeCalculator.calculate(
                scripCode,
                companyName,
                candles30m,
                indexContext,
                0.0
        );
        
        // ======================== PART 2: SIGNAL GENERATION ========================
        
        // 2A. FUDKII
        MasterArchFUDKIICalculator.FUDKIIOutput fudkii = fudkiiCalculator.calculate(
                scripCode, companyName, candles30m, 0.0
        );
        
        // 2B. Volume Canonical
        VolumeCanonicalCalculator.VolumeOutput volume = volumeCalculator.calculate(
                scripCode, companyName, candles30m, 0.0
        );
        
        // 2C. Velocity MMS
        VelocityMMSCalculator.VelocityMMSOutput velocity = velocityCalculator.calculate(
                candles30m, null, null,  // 30m only for now
                scripCode, companyName, 0.0
        );
        
        // ======================== PART 3: SIGNAL VALIDATION ========================
        
        // Type A: Structural
        StructuralValidator.StructuralValidationOutput structural = structuralValidator.calculate(
                scripCode, companyName, candles30m,
                vcpScore, pivotDistance, nearestPivot, pivotType, 0.0
        );
        
        // Check for hard reject
        if (structural.isRejected()) {
            return buildRejectResult(scripCode, companyName, timestamp, 
                    "Structural validation failed: " + structural.getRejectReason(), structural);
        }
        
        // Type B: Behavioural
        BehaviouralValidator.BehaviouralValidationOutput behavioural = behaviouralValidator.calculate(
                scripCode, companyName, candles30m,
                ohmScore, optionBuyerFriendly, volume.getVolumeCertainty(), 0.0
        );
        
        // Correlation Governor
        CorrelationGovernor.CorrelationGovernorOutput correlation = correlationGovernor.calculate(
                scripCode, companyName, Math.abs(fudkii.getStrength()), 0.0
        );
        
        // ======================== CALCULATE SIGNAL STRENGTH ========================
        
        SignalStrengthScore signalStrength = SignalStrengthScore.calculate(
                scripCode, companyName, timestamp,
                fudkii.getStrength(),
                volume.getVolumeCertainty(),
                velocity.getVelocityScore() != null ? velocity.getVelocityScore().getCurrent() : 0.5,
                structural.getStructuralStrength(),
                behavioural.getBehaviourResult(),
                correlation.isSameClusterActive(),
                correlation.isOppositeClusterActive(),
                0.0
        );
        
        // Check for signal rejection
        if (!signalStrength.isValid()) {
            return buildRejectResult(scripCode, companyName, timestamp,
                    signalStrength.getInvalidReason(), structural);
        }
        
        // ======================== CALCULATE FINAL SCORE ========================
        
        FinalOpportunityScore finalScore = FinalOpportunityScore.calculate(
                scripCode, companyName, timestamp,
                indexContext.getContextScore().getCurrent(),
                securityContext.getContextScore().getCurrent(),
                signalStrength.getSignalScore().getCurrent(),
                0.0
        );
        
        // Apply decay if needed
        double currentPrice = candles30m.get(candles30m.size() - 1).getClose();
        boolean hasFollowThrough = checkFollowThrough(candles30m, securityContext.isBullish());
        finalScore = decayManager.applyDecayIfNeeded(finalScore, hasFollowThrough, timestamp);
        
        // ======================== PART 4: TRADE CONSTRUCTION ========================
        
        // Hedge Decision
        HedgeDecisionModule.HedgeDecisionOutput hedge = hedgeModule.calculate(
                scripCode, companyName,
                finalScore.getDirectionConfidence(),
                indexContext.getRegimeStrength(),
                finalScore.getFinalScore().getCurrent(),
                securityContext.isBullish()
        );
        
        // Position Sizing
        double atr = calculateATR(candles30m, 14);
        double swingLow = calculateSwingLow(candles30m, 20);
        double swingHigh = calculateSwingHigh(candles30m, 20);
        
        PositionSizer.PositionSizeOutput position = positionSizer.calculate(
                scripCode, companyName,
                finalScore.getFinalScore().getCurrent(),
                volume.getVolExpStrength(),
                atr, currentPrice, swingLow, swingHigh, nearestPivot,
                securityContext.isBullish()
        );
        
        // ======================== BUILD RESULT ========================
        
        return MasterArchResult.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(timestamp)
                // Context
                .indexContext(indexContext)
                .securityContext(securityContext)
                // Signal Gen
                .fudkii(fudkii)
                .volume(volume)
                .velocity(velocity)
                // Validation
                .structural(structural)
                .behavioural(behavioural)
                .correlation(correlation)
                // Scores
                .signalStrength(signalStrength)
                .finalScore(finalScore)
                // Trade
                .hedge(hedge)
                .position(position)
                // Summary
                .isActionable(finalScore.isActionable())
                .decision(finalScore.getDecision().name())
                .rationale(buildRationale(finalScore, signalStrength, position))
                .build();
    }
    
    // ======================== HELPER METHODS ========================
    
    private MasterArchResult buildRejectResult(String scripCode, String companyName, long timestamp,
                                               String reason, StructuralValidator.StructuralValidationOutput structural) {
        return MasterArchResult.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(timestamp)
                .structural(structural)
                .finalScore(FinalOpportunityScore.invalid(scripCode, companyName, timestamp, reason))
                .isActionable(false)
                .decision("REJECT")
                .rationale(reason)
                .build();
    }
    
    private List<UnifiedCandle> convertToUnifiedCandles(FamilyCandle family) {
        // TODO: This should fetch historical candles from Redis/StateStore
        // For now, just convert the current equity candle
        if (family.getEquity() == null) {
            return List.of();
        }

        UnifiedCandle current = UnifiedCandle.builder()
                .scripCode(family.getEquity().getScripCode())
                .companyName(family.getEquity().getCompanyName())
                .exchange(family.getEquity().getExchange())
                .exchangeType(family.getEquity().getExchangeType())
                .timeframe(family.getEquity().getTimeframe())
                .windowStartMillis(family.getEquity().getWindowStartMillis())
                .windowEndMillis(family.getEquity().getWindowEndMillis())
                .open(family.getEquity().getOpen())
                .high(family.getEquity().getHigh())
                .low(family.getEquity().getLow())
                .close(family.getEquity().getClose())
                .volume(family.getEquity().getVolume())
                .buyVolume(family.getEquity().getBuyVolume())
                .sellVolume(family.getEquity().getSellVolume())
                .vwap(family.getEquity().getVwap())
                .build();

        return List.of(current);
    }
    
    private boolean checkFollowThrough(List<UnifiedCandle> candles, boolean bullish) {
        if (candles.size() < 2) return false;
        UnifiedCandle current = candles.get(candles.size() - 1);
        UnifiedCandle prev = candles.get(candles.size() - 2);
        
        if (bullish) {
            return current.getClose() > prev.getHigh();  // Broke above previous high
        } else {
            return current.getClose() < prev.getLow();   // Broke below previous low
        }
    }
    
    private double calculateATR(List<UnifiedCandle> candles, int period) {
        if (candles == null || candles.size() < period + 1) return 0.0;
        
        double sum = 0;
        int start = Math.max(1, candles.size() - period);
        int count = 0;
        
        for (int i = start; i < candles.size(); i++) {
            UnifiedCandle curr = candles.get(i);
            UnifiedCandle prev = candles.get(i - 1);
            double tr = Math.max(curr.getHigh() - curr.getLow(),
                       Math.max(Math.abs(curr.getHigh() - prev.getClose()),
                                Math.abs(curr.getLow() - prev.getClose())));
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 0.0;
    }
    
    private double calculateSwingLow(List<UnifiedCandle> candles, int lookback) {
        if (candles == null || candles.isEmpty()) return 0.0;
        int start = Math.max(0, candles.size() - lookback);
        double low = Double.MAX_VALUE;
        for (int i = start; i < candles.size(); i++) {
            low = Math.min(low, candles.get(i).getLow());
        }
        return low != Double.MAX_VALUE ? low : 0.0;
    }
    
    private double calculateSwingHigh(List<UnifiedCandle> candles, int lookback) {
        if (candles == null || candles.isEmpty()) return 0.0;
        int start = Math.max(0, candles.size() - lookback);
        double high = 0;
        for (int i = start; i < candles.size(); i++) {
            high = Math.max(high, candles.get(i).getHigh());
        }
        return high;
    }
    
    private String buildRationale(FinalOpportunityScore finalScore, 
                                  SignalStrengthScore signalStrength,
                                  PositionSizer.PositionSizeOutput position) {
        StringBuilder sb = new StringBuilder();
        sb.append(finalScore.getDecision().name());
        sb.append(": Score=").append(String.format("%.2f", finalScore.getFinalScore().getCurrent()));
        sb.append(", Confidence=").append(String.format("%.2f", finalScore.getDirectionConfidence()));
        
        if (position.isNoTrade()) {
            sb.append(" | NO TRADE - confidence too low");
        } else {
            sb.append(" | ").append(position.getRecommendedLots()).append(" lots");
            if (position.isHedgeRecommended()) sb.append(" + hedge");
            if (position.isFuturesOnly()) sb.append(" (futures only)");
            sb.append(" | SL=").append(String.format("%.2f", position.getStopLoss()));
            if (position.isTarget1Active()) {
                sb.append(" T1=").append(String.format("%.2f", position.getTarget1()));
            }
        }
        
        return sb.toString();
    }
}
