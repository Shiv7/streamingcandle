package com.kotsin.consumer.masterarch.calculator;

import com.kotsin.consumer.masterarch.model.NormalizedScore;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.service.VCPCalculator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * StructuralValidator - PART 3: TYPE A - Structural Validation
 * 
 * MASTER ARCHITECTURE Compliant Validator
 * 
 * Driver Selection (Priority Order):
 * 1. VCP (if VCP score ≥ 0.7)
 * 2. VWAP (if VWAP_Reclaim/Rejection ≥ 0.6)
 * 3. Pivots (if Pivot proximity within 0.5*ATR)
 * 
 * Supporter Logic:
 * - Aligned (same direction) → ×1.05
 * - Neutral → ×1.00
 * - Misaligned (opposite) → ×0.90
 * 
 * Structural_Score = Driver_Strength × Π(Supporter_Multipliers)
 * 
 * Hard reject: Structural_Score < 0.45 → REJECT
 * 
 * Output Topic: validation-structural-output
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StructuralValidator {
    
    private static final double VCP_THRESHOLD = 0.70;
    private static final double VWAP_THRESHOLD = 0.60;
    private static final double PIVOT_PROXIMITY_ATR = 0.50;
    private static final double REJECT_THRESHOLD = 0.45;
    
    // Supporter multipliers per spec
    private static final double ALIGNED_MULTIPLIER = 1.05;
    private static final double NEUTRAL_MULTIPLIER = 1.00;
    private static final double MISALIGNED_MULTIPLIER = 0.90;
    
    /**
     * Structural Validation Output
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class StructuralValidationOutput {
        private String scripCode;
        private String companyName;
        private long timestamp;
        
        // Driver Selection
        private String selectedDriver;      // VCP, VWAP, PIVOT, NONE
        private double driverStrength;
        
        // VCP Analysis
        private double vcpScore;
        private boolean vcpSelected;
        
        // VWAP Analysis
        private double vwapReclaimStrength;
        private double vwapRejectionStrength;
        private boolean vwapSelected;
        private double vwap;
        private double vwapDistance;
        
        // Pivot Analysis
        private double pivotProximity;
        private double nearestPivot;
        private String pivotType;           // SUPPORT, RESISTANCE
        private boolean pivotSelected;
        
        // Supporter Analysis
        private double supporter1Multiplier;
        private String supporter1;
        private double supporter2Multiplier;
        private String supporter2;
        
        // Final Score
        private NormalizedScore structuralScore;
        
        // Rejection
        private boolean rejected;
        private String rejectReason;
        
        // Metadata
        private boolean isValid;
        private String invalidReason;
        
        public double getStructuralStrength() {
            return structuralScore != null ? Math.abs(structuralScore.getCurrent()) : 0.0;
        }
    }
    
    /**
     * Calculate Structural Validation
     */
    public StructuralValidationOutput calculate(
            String scripCode,
            String companyName,
            List<UnifiedCandle> candles30m,
            double vcpScore,
            double pivotDistance,
            double nearestPivot,
            String pivotType,
            double previousScore
    ) {
        long timestamp = System.currentTimeMillis();
        
        // Validate data
        if (candles30m == null || candles30m.isEmpty()) {
            return StructuralValidationOutput.builder()
                    .scripCode(scripCode)
                    .companyName(companyName)
                    .timestamp(timestamp)
                    .structuralScore(NormalizedScore.neutral(timestamp))
                    .isValid(false)
                    .invalidReason("No candle data")
                    .build();
        }
        
        UnifiedCandle current = candles30m.get(candles30m.size() - 1);
        double atr = calculateATR(candles30m, 14);
        double vwap = current.getVwap() > 0 ? current.getVwap() : current.getClose();
        double close = current.getClose();
        
        // ======================== VWAP ANALYSIS ========================
        double vwapDistance = (close - vwap) / (atr + 0.001);
        
        // VWAP_Reclaim_Strength (bullish): max(close - VWAP, 0) / ATR
        double vwapReclaimStrength = Math.max(close - vwap, 0) / (atr + 0.001);
        vwapReclaimStrength = Math.min(vwapReclaimStrength, 1.0);
        
        // VWAP_Rejection_Strength (bearish): max(VWAP - close, 0) / ATR
        double vwapRejectionStrength = Math.max(vwap - close, 0) / (atr + 0.001);
        vwapRejectionStrength = Math.min(vwapRejectionStrength, 1.0);
        
        double vwapStrength = Math.max(vwapReclaimStrength, vwapRejectionStrength);
        
        // ======================== PIVOT ANALYSIS ========================
        double pivotProximity = 0.0;
        if (pivotDistance > 0 && atr > 0) {
            pivotProximity = 1.0 - Math.min(pivotDistance / (PIVOT_PROXIMITY_ATR * atr), 1.0);
        }
        
        // ======================== DRIVER SELECTION ========================
        String selectedDriver = "NONE";
        double driverStrength = 0.0;
        boolean vcpSelected = false;
        boolean vwapSelected = false;
        boolean pivotSelected = false;
        
        // Priority 1: VCP
        if (vcpScore >= VCP_THRESHOLD) {
            selectedDriver = "VCP";
            driverStrength = vcpScore;
            vcpSelected = true;
        }
        // Priority 2: VWAP
        else if (vwapStrength >= VWAP_THRESHOLD) {
            selectedDriver = "VWAP";
            driverStrength = vwapStrength;
            vwapSelected = true;
        }
        // Priority 3: Pivots
        else if (pivotProximity >= 0.5) {
            selectedDriver = "PIVOT";
            driverStrength = pivotProximity;
            pivotSelected = true;
        }
        // Fallback: use best available
        else {
            double best = Math.max(vcpScore, Math.max(vwapStrength, pivotProximity));
            if (vcpScore == best && vcpScore > 0) {
                selectedDriver = "VCP";
                driverStrength = vcpScore;
                vcpSelected = true;
            } else if (vwapStrength == best && vwapStrength > 0) {
                selectedDriver = "VWAP";
                driverStrength = vwapStrength;
                vwapSelected = true;
            } else if (pivotProximity > 0) {
                selectedDriver = "PIVOT";
                driverStrength = pivotProximity;
                pivotSelected = true;
            }
        }
        
        // ======================== SUPPORTER ANALYSIS ========================
        // Supporters are the non-selected levels
        String supporter1 = "NONE";
        double supporter1Mult = NEUTRAL_MULTIPLIER;
        String supporter2 = "NONE";
        double supporter2Mult = NEUTRAL_MULTIPLIER;
        
        // Determine signal direction from VWAP
        boolean bullish = close > vwap;
        
        if (!vcpSelected && vcpScore > 0.3) {
            supporter1 = "VCP";
            // VCP is direction-neutral, so always neutral multiplier
            supporter1Mult = NEUTRAL_MULTIPLIER;
        }
        
        if (!vwapSelected && vwapStrength > 0.2) {
            String vwapDir = vwapReclaimStrength > vwapRejectionStrength ? "BULLISH" : "BEARISH";
            supporter2 = "VWAP";
            if (bullish && "BULLISH".equals(vwapDir)) {
                supporter2Mult = ALIGNED_MULTIPLIER;
            } else if (!bullish && "BEARISH".equals(vwapDir)) {
                supporter2Mult = ALIGNED_MULTIPLIER;
            } else {
                supporter2Mult = MISALIGNED_MULTIPLIER;
            }
        }
        
        if (!pivotSelected && pivotProximity > 0.3) {
            String pivotDir = "SUPPORT".equals(pivotType) ? "BULLISH" : "BEARISH";
            if (supporter1.equals("NONE")) {
                supporter1 = "PIVOT";
                if ((bullish && "BULLISH".equals(pivotDir)) || (!bullish && "BEARISH".equals(pivotDir))) {
                    supporter1Mult = ALIGNED_MULTIPLIER;
                } else {
                    supporter1Mult = MISALIGNED_MULTIPLIER;
                }
            } else {
                supporter2 = "PIVOT";
                if ((bullish && "BULLISH".equals(pivotDir)) || (!bullish && "BEARISH".equals(pivotDir))) {
                    supporter2Mult = ALIGNED_MULTIPLIER;
                } else {
                    supporter2Mult = MISALIGNED_MULTIPLIER;
                }
            }
        }
        
        // ======================== STRUCTURAL SCORE ========================
        // Structural_Score = Driver_Strength × Π(Supporter_Multipliers)
        double structuralValue = driverStrength * supporter1Mult * supporter2Mult;
        structuralValue = Math.min(1.0, Math.max(0.0, structuralValue));
        
        // Apply direction
        if (!bullish) {
            structuralValue = -structuralValue;
        }
        
        // ======================== REJECTION CHECK ========================
        boolean rejected = false;
        String rejectReason = null;
        
        if (Math.abs(structuralValue) < REJECT_THRESHOLD) {
            rejected = true;
            rejectReason = "Structural_Score " + String.format("%.2f", Math.abs(structuralValue)) + " < 0.45";
            log.debug("Structural validation rejected for {}: {}", scripCode, rejectReason);
        }
        
        NormalizedScore structuralScore = NormalizedScore.directional(structuralValue, previousScore, timestamp);
        
        return StructuralValidationOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(timestamp)
                // Driver
                .selectedDriver(selectedDriver)
                .driverStrength(driverStrength)
                // VCP
                .vcpScore(vcpScore)
                .vcpSelected(vcpSelected)
                // VWAP
                .vwap(vwap)
                .vwapDistance(vwapDistance)
                .vwapReclaimStrength(vwapReclaimStrength)
                .vwapRejectionStrength(vwapRejectionStrength)
                .vwapSelected(vwapSelected)
                // Pivot
                .pivotProximity(pivotProximity)
                .nearestPivot(nearestPivot)
                .pivotType(pivotType)
                .pivotSelected(pivotSelected)
                // Supporters
                .supporter1(supporter1)
                .supporter1Multiplier(supporter1Mult)
                .supporter2(supporter2)
                .supporter2Multiplier(supporter2Mult)
                // Score
                .structuralScore(structuralScore)
                // Rejection
                .rejected(rejected)
                .rejectReason(rejectReason)
                // Meta
                .isValid(true)
                .build();
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
}
