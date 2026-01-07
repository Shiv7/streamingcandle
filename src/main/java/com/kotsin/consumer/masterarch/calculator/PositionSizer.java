package com.kotsin.consumer.masterarch.calculator;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PositionSizer - PART 4: Lot Sizing Module
 * 
 * MASTER ARCHITECTURE Compliant Module
 * 
 * Lot Sizing Table:
 * - ≥0.80 → 2 lots + hedge optional
 * - 0.65–0.80 → 1 lot + hedge recommended
 * - 0.55–0.65 → 1 lot futures only (no options)
 * - <0.55 → NO TRADE
 * 
 * Targets Logic:
 * - T1 = Next pivot ± 0.5*ATR if Volume_Exp ≥ 0.7
 * - T2 = Next pivot ± 1.0*ATR if Volume_Exp ≥ 0.8
 * - T3 = Next pivot ± 1.5*ATR if Volume_Exp ≥ 0.9
 * 
 * Stop Loss:
 * - Bullish_SL = Swing_Low - 0.25*ATR
 * - Bearish_SL = Swing_High + 0.25*ATR
 * 
 * Output Topic: trade-position-size
 */
@Slf4j
@Component
public class PositionSizer {
    
    // Score thresholds per spec
    private static final double THRESHOLD_2_LOTS = 0.80;
    private static final double THRESHOLD_1_LOT_HEDGE = 0.65;
    private static final double THRESHOLD_1_LOT_FUTURES = 0.55;
    
    // Volume expansion thresholds for targets
    private static final double VOL_EXP_T1 = 0.70;
    private static final double VOL_EXP_T2 = 0.80;
    private static final double VOL_EXP_T3 = 0.90;
    
    // ATR multipliers (FF1 Spec)
    // T1 = Nearest pivot (no ATR offset)
    // T2 = Next higher TF pivot if volume continues
    // T3 = 2.5-3.0 ATR multiple if no decay
    private static final double SL_ATR_MULT = 0.25;
    private static final double T3_ATR_MULT_MIN = 2.50;
    private static final double T3_ATR_MULT_MAX = 3.00;
    
    /**
     * Position Size Output
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class PositionSizeOutput {
        private String scripCode;
        private String companyName;
        private long timestamp;
        
        // Input
        private double finalScore;
        private double volumeExpStrength;
        private double atr;
        private double currentPrice;
        private double swingLow;
        private double swingHigh;
        private double nextPivot;
        private boolean isBullish;
        
        // Position sizing
        private int recommendedLots;
        private boolean hedgeRecommended;
        private boolean hedgeOptional;
        private boolean futuresOnly;
        private boolean noTrade;
        private String sizeRationale;
        
        // Targets
        private double target1;
        private boolean target1Active;
        private double target2;
        private boolean target2Active;
        private double target3;
        private boolean target3Active;
        
        // Stop Loss
        private double stopLoss;
        
        // Risk/Reward
        private double riskAmount;
        private double rewardT1;
        private double riskRewardT1;
        
        // Metadata
        private boolean isValid;
    }
    
    /**
     * Calculate Position Size and Targets
     */
    public PositionSizeOutput calculate(
            String scripCode,
            String companyName,
            double finalScore,
            double volumeExpStrength,
            double atr,
            double currentPrice,
            double swingLow,
            double swingHigh,
            double nextPivot,
            boolean isBullish
    ) {
        long timestamp = System.currentTimeMillis();
        
        double absScore = Math.abs(finalScore);
        
        // ======================== LOT SIZING ========================
        int lots;
        boolean hedgeRecommended = false;
        boolean hedgeOptional = false;
        boolean futuresOnly = false;
        boolean noTrade = false;
        String rationale;
        
        if (absScore >= THRESHOLD_2_LOTS) {
            lots = 2;
            hedgeOptional = true;
            rationale = "High confidence (≥0.80) - 2 lots, hedge optional";
        } else if (absScore >= THRESHOLD_1_LOT_HEDGE) {
            lots = 1;
            hedgeRecommended = true;
            rationale = "Medium confidence (0.65-0.80) - 1 lot with hedge";
        } else if (absScore >= THRESHOLD_1_LOT_FUTURES) {
            lots = 1;
            futuresOnly = true;
            rationale = "Low confidence (0.55-0.65) - 1 lot futures only";
        } else {
            lots = 0;
            noTrade = true;
            rationale = "Insufficient confidence (<0.55) - NO TRADE";
        }
        
        // ======================== TARGETS ========================
        double t1 = 0, t2 = 0, t3 = 0;
        boolean t1Active = false, t2Active = false, t3Active = false;
        
        if (!noTrade) {
            if (isBullish) {
                // Bullish targets per FF1 Spec
                // T1 = Nearest resistance pivot
                if (volumeExpStrength >= VOL_EXP_T1) {
                    t1 = nextPivot;  // Pivot directly, no ATR offset
                    t1Active = true;
                }
                // T2 = Next higher TF pivot if volume continues
                if (volumeExpStrength >= VOL_EXP_T2) {
                    t2 = nextPivot * 1.02;  // Approximate next pivot (2% above)
                    t2Active = true;
                }
                // T3 = 2.5-3.0 ATR multiple if strong
                if (volumeExpStrength >= VOL_EXP_T3) {
                    double atrMult = volumeExpStrength >= 0.95 ? T3_ATR_MULT_MAX : T3_ATR_MULT_MIN;
                    t3 = currentPrice + atrMult * atr;
                    t3Active = true;
                }
            } else {
                // Bearish targets per FF1 Spec
                // T1 = Nearest support pivot
                if (volumeExpStrength >= VOL_EXP_T1) {
                    t1 = nextPivot;  // Pivot directly
                    t1Active = true;
                }
                // T2 = Lower TF pivot if volume continues
                if (volumeExpStrength >= VOL_EXP_T2) {
                    t2 = nextPivot * 0.98;  // Approximate next pivot (2% below)
                    t2Active = true;
                }
                // T3 = 2.5-3.0 ATR multiple downside
                if (volumeExpStrength >= VOL_EXP_T3) {
                    double atrMult = volumeExpStrength >= 0.95 ? T3_ATR_MULT_MAX : T3_ATR_MULT_MIN;
                    t3 = currentPrice - atrMult * atr;
                    t3Active = true;
                }
            }
        }
        
        // ======================== STOP LOSS ========================
        double stopLoss;
        if (isBullish) {
            // Bullish_SL = Swing_Low - 0.25*ATR
            stopLoss = swingLow - SL_ATR_MULT * atr;
        } else {
            // Bearish_SL = Swing_High + 0.25*ATR
            stopLoss = swingHigh + SL_ATR_MULT * atr;
        }
        
        // ======================== RISK/REWARD ========================
        double riskAmount = Math.abs(currentPrice - stopLoss);
        double rewardT1 = t1Active ? Math.abs(t1 - currentPrice) : 0;
        double riskRewardT1 = riskAmount > 0 ? rewardT1 / riskAmount : 0;
        
        return PositionSizeOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(timestamp)
                // Input
                .finalScore(finalScore)
                .volumeExpStrength(volumeExpStrength)
                .atr(atr)
                .currentPrice(currentPrice)
                .swingLow(swingLow)
                .swingHigh(swingHigh)
                .nextPivot(nextPivot)
                .isBullish(isBullish)
                // Sizing
                .recommendedLots(lots)
                .hedgeRecommended(hedgeRecommended)
                .hedgeOptional(hedgeOptional)
                .futuresOnly(futuresOnly)
                .noTrade(noTrade)
                .sizeRationale(rationale)
                // Targets
                .target1(t1)
                .target1Active(t1Active)
                .target2(t2)
                .target2Active(t2Active)
                .target3(t3)
                .target3Active(t3Active)
                // SL
                .stopLoss(stopLoss)
                // Risk
                .riskAmount(riskAmount)
                .rewardT1(rewardT1)
                .riskRewardT1(riskRewardT1)
                // Meta
                .isValid(true)
                .build();
    }
}
