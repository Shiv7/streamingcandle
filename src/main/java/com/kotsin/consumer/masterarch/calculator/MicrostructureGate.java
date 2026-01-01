package com.kotsin.consumer.masterarch.calculator;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.masterarch.model.NormalizedScore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MicrostructureGate - Uses computed microstructure features
 * 
 * Integrates:
 * 1. VPIN - Volume-Synchronized Probability of Informed Trading (toxicity)
 * 2. OFI - Order Flow Imbalance (institutional flow)
 * 3. Kyle's Lambda - Price impact coefficient (market depth)
 * 4. VIB/DIB Triggers - Imbalance bar signals
 * 5. PCR - Put-Call Ratio from FamilyCandle
 * 6. OI Signals - Open Interest buildup from FamilyCandle
 * 7. Spot-Future Premium - Arbitrage signal from FamilyCandle
 * 
 * Output: MicrostructureScore with confirmation/rejection signals
 */
@Slf4j
@Component
public class MicrostructureGate {

    // VPIN thresholds
    private static final double VPIN_TOXIC = 0.70;      // High toxicity warning
    private static final double VPIN_SAFE = 0.40;       // Safe zone
    
    // OFI thresholds
    private static final double OFI_STRONG = 0.60;      // Strong directional flow
    private static final double OFI_WEAK = 0.20;        // Weak/neutral
    
    // Kyle's Lambda thresholds
    private static final double LAMBDA_HIGH = 0.0001;   // High price impact (thin book)
    private static final double LAMBDA_LOW = 0.00001;   // Low price impact (deep book)
    
    // PCR thresholds
    private static final double PCR_BULLISH = 0.70;     // Low PCR = bullish
    private static final double PCR_BEARISH = 1.30;     // High PCR = bearish
    
    // Spot-Future premium threshold
    private static final double PREMIUM_SIGNIFICANT = 0.50;  // 0.5% premium is significant

    @Data
    @Builder
    @AllArgsConstructor
    public static class MicrostructureOutput {
        private String scripCode;
        private String companyName;
        private long timestamp;
        
        // VPIN Analysis
        private double vpin;
        private boolean vpinToxic;
        private boolean vpinSafe;
        private String vpinSignal;  // "TOXIC", "WARNING", "SAFE"
        
        // OFI Analysis
        private double ofi;
        private boolean ofiStrong;
        private String ofiDirection;  // "BUY", "SELL", "NEUTRAL"
        
        // Kyle's Lambda
        private double kyleLambda;
        private boolean thinBook;
        private boolean deepBook;
        
        // Imbalance Bars
        private boolean vibTriggered;  // Volume imbalance
        private boolean dibTriggered;  // Dollar imbalance
        private boolean anyImbalance;
        
        // Options Flow (from FamilyCandle)
        private double pcr;
        private String pcrSignal;  // "BULLISH", "BEARISH", "NEUTRAL"
        private boolean oiBuildingUp;
        private String oiSignal;
        
        // Spot-Future Premium
        private double spotFuturePremium;
        private boolean premiumSignificant;
        private String premiumSignal;  // "CONTANGO", "BACKWARDATION", "NEUTRAL"
        
        // Final Composite
        private double microstructureScore;  // 0-1 confirmation strength
        private double microstructureMultiplier;  // Multiplier for strategy
        private boolean confirmed;
        private boolean rejected;
        private String reason;
        
        private boolean isValid;
    }

    /**
     * Calculate microstructure confirmation for a signal
     * 
     * @param candle InstrumentCandle with microstructure data
     * @param family FamilyCandle with cross-instrument data
     * @param isBullish Expected direction
     * @return MicrostructureOutput with all signals
     */
    public MicrostructureOutput calculate(
            InstrumentCandle candle,
            FamilyCandle family,
            boolean isBullish
    ) {
        long timestamp = System.currentTimeMillis();
        String scripCode = candle.getScripCode();
        String companyName = candle.getCompanyName();
        
        // ======================== VPIN ANALYSIS ========================
        double vpin = candle.getVpin();
        boolean vpinToxic = vpin >= VPIN_TOXIC;
        boolean vpinSafe = vpin <= VPIN_SAFE;
        String vpinSignal = vpinToxic ? "TOXIC" : (vpinSafe ? "SAFE" : "WARNING");
        
        // ======================== OFI ANALYSIS ========================
        Double ofiValue = candle.getOfi();
        double ofi = ofiValue != null ? ofiValue : 0.0;
        boolean ofiStrong = Math.abs(ofi) >= OFI_STRONG;
        String ofiDirection = ofi > OFI_WEAK ? "BUY" : (ofi < -OFI_WEAK ? "SELL" : "NEUTRAL");
        boolean ofiAligned = (isBullish && ofi > 0) || (!isBullish && ofi < 0);
        
        // ======================== KYLE'S LAMBDA ========================
        Double lambdaValue = candle.getKyleLambda();
        double kyleLambda = lambdaValue != null ? lambdaValue : 0.0;
        boolean thinBook = kyleLambda >= LAMBDA_HIGH;
        boolean deepBook = kyleLambda <= LAMBDA_LOW;
        
        // ======================== IMBALANCE BARS ========================
        boolean vibTriggered = candle.isVibTriggered();
        boolean dibTriggered = candle.isDibTriggered();
        boolean anyImbalance = vibTriggered || dibTriggered;
        
        // ======================== PCR (from FamilyCandle) ========================
        double pcr = 1.0;
        String pcrSignal = "NEUTRAL";
        if (family != null && family.getPcr() != null) {
            pcr = family.getPcr();
            if (pcr < PCR_BULLISH) {
                pcrSignal = "BULLISH";
            } else if (pcr > PCR_BEARISH) {
                pcrSignal = "BEARISH";
            }
        }
        boolean pcrAligned = (isBullish && pcrSignal.equals("BULLISH")) || 
                             (!isBullish && pcrSignal.equals("BEARISH"));
        
        // ======================== OI SIGNAL (from FamilyCandle) ========================
        boolean oiBuildingUp = false;
        String oiSignal = "NEUTRAL";
        if (family != null) {
            // For bullish: callOiBuildingUp OR putOiUnwinding
            // For bearish: NOT callOiBuildingUp AND NOT putOiUnwinding (i.e., put building + call unwinding)
            if (isBullish) {
                oiBuildingUp = family.isCallOiBuildingUp() || family.isPutOiUnwinding();
            } else {
                // Bearish confirmation = calls NOT building AND puts NOT unwinding
                oiBuildingUp = !family.isCallOiBuildingUp() && !family.isPutOiUnwinding();
            }
            oiSignal = family.getOiSignal() != null ? family.getOiSignal() : "NEUTRAL";
        }
        
        // ======================== SPOT-FUTURE PREMIUM ========================
        double spotFuturePremium = 0.0;
        boolean premiumSignificant = false;
        String premiumSignal = "NEUTRAL";
        if (family != null && family.getSpotFuturePremium() != null) {
            spotFuturePremium = family.getSpotFuturePremium();
            premiumSignificant = Math.abs(spotFuturePremium) >= PREMIUM_SIGNIFICANT;
            if (spotFuturePremium > PREMIUM_SIGNIFICANT) {
                premiumSignal = "CONTANGO";
            } else if (spotFuturePremium < -PREMIUM_SIGNIFICANT) {
                premiumSignal = "BACKWARDATION";
            }
        }
        
        // ======================== COMPOSITE SCORE ========================
        // Build confirmation score based on alignment
        double score = 0.5;  // Base neutral
        String reason = "";
        
        // VPIN: Penalize toxic, boost safe
        if (vpinToxic) {
            score -= 0.15;
            reason += "VPIN_TOXIC;";
        } else if (vpinSafe) {
            score += 0.05;
        }
        
        // OFI: Strong aligned flow is confirming
        if (ofiStrong && ofiAligned) {
            score += 0.15;
            reason += "OFI_CONFIRM;";
        } else if (ofiStrong && !ofiAligned) {
            score -= 0.10;
            reason += "OFI_AGAINST;";
        }
        
        // Imbalance bars: Boost if triggered
        if (anyImbalance) {
            score += 0.10;
            reason += "IMBALANCE_TRIGGER;";
        }
        
        // PCR alignment
        if (pcrAligned) {
            score += 0.10;
            reason += "PCR_CONFIRM;";
        }
        
        // OI buildup
        if (oiBuildingUp) {
            score += 0.10;
            reason += "OI_BUILDUP;";
        }
        
        // Clamp score
        score = Math.max(0.0, Math.min(1.0, score));
        
        // Determine multiplier and confirmation
        double multiplier = 1.0;
        boolean confirmed = score >= 0.60;
        boolean rejected = score < 0.35 || vpinToxic;
        
        if (confirmed) {
            multiplier = 1.0 + (score - 0.60) * 0.5;  // Up to 1.20
        } else if (rejected) {
            multiplier = 0.85;
        }
        
        return MicrostructureOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(timestamp)
                // VPIN
                .vpin(vpin)
                .vpinToxic(vpinToxic)
                .vpinSafe(vpinSafe)
                .vpinSignal(vpinSignal)
                // OFI
                .ofi(ofi)
                .ofiStrong(ofiStrong)
                .ofiDirection(ofiDirection)
                // Lambda
                .kyleLambda(kyleLambda)
                .thinBook(thinBook)
                .deepBook(deepBook)
                // Imbalance
                .vibTriggered(vibTriggered)
                .dibTriggered(dibTriggered)
                .anyImbalance(anyImbalance)
                // PCR
                .pcr(pcr)
                .pcrSignal(pcrSignal)
                .oiBuildingUp(oiBuildingUp)
                .oiSignal(oiSignal)
                // Premium
                .spotFuturePremium(spotFuturePremium)
                .premiumSignificant(premiumSignificant)
                .premiumSignal(premiumSignal)
                // Composite
                .microstructureScore(score)
                .microstructureMultiplier(multiplier)
                .confirmed(confirmed)
                .rejected(rejected)
                .reason(reason.isEmpty() ? "NEUTRAL" : reason)
                .isValid(true)
                .build();
    }
}
