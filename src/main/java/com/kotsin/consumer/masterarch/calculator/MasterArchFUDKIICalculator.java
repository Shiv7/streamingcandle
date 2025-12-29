package com.kotsin.consumer.masterarch.calculator;

import com.kotsin.consumer.masterarch.model.NormalizedScore;
import com.kotsin.consumer.model.UnifiedCandle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MasterArchFUDKIICalculator - PART 2A: FUDKII Module (Structural Trigger)
 * 
 * MASTER ARCHITECTURE Compliant Calculator
 * 
 * Formulas:
 * 1. BB_Score_Bullish = max(close_30 - BB_upper_30, 0) / ATR_14_30m
 * 2. BB_Score_Bearish = max(BB_lower_30 - close_30, 0) / ATR_14_30m
 * 3. ST_Flip_Strength_Bullish = |close_30 - ST_line_30| / ATR_14_30m
 *    (Only valid if previous ST signal was SELL and current is BUY)
 * 4. ST_Flip_Strength_Bearish = |close_30 - ST_line_30| / ATR_14_30m
 *    (Only valid if previous ST signal was BUY and current is SELL)
 * 5. Simultaneity_Weight: same bar = 1.0, one bar apart = 0.5
 * 6. Fudkii_Strength = min(1, BB_Score + ST_Score) × Simultaneity_Weight
 * 
 * Patience Logic:
 * - If Fudkii_Strength < 0.55 → wait ±2 bars
 * - If still < threshold → REJECT SIGNAL
 * 
 * Output Topic: signal-fudkii-output
 */
@Slf4j
@Component
public class MasterArchFUDKIICalculator {
    
    // Bollinger Bands settings
    private static final int BB_PERIOD = 20;
    private static final double BB_STD_MULTIPLIER = 2.0;
    
    // SuperTrend settings
    private static final int ST_ATR_PERIOD = 10;
    private static final double ST_MULTIPLIER = 3.0;
    
    // ATR for normalization
    private static final int ATR_PERIOD = 14;
    
    // Patience threshold
    private static final double FUDKII_THRESHOLD = 0.55;
    private static final int PATIENCE_BARS = 2;
    
    /**
     * FUDKII Output
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class FUDKIIOutput {
        private String scripCode;
        private String companyName;
        private long timestamp;
        
        // BB Scores
        private double bbScoreBullish;
        private double bbScoreBearish;
        private double bbUpper;
        private double bbLower;
        private double bbMiddle;
        
        // SuperTrend
        private double stFlipStrength;
        private double stLine;
        private boolean stCurrentBullish;
        private boolean stPreviousBullish;
        private boolean stFlipped;
        
        // Simultaneity
        private double simultaneityWeight;
        private int bbSignalBar;
        private int stSignalBar;
        
        // Final FUDKII
        private NormalizedScore fudkiiScore;
        private String direction;       // BULLISH, BEARISH, NEUTRAL
        
        // Patience tracking
        private boolean waitingForConfirmation;
        private int barsWaited;
        private boolean rejected;
        private String rejectReason;
        
        // Metadata
        private boolean isValid;
        private String invalidReason;
        
        public double getStrength() {
            return fudkiiScore != null ? Math.abs(fudkiiScore.getCurrent()) : 0.0;
        }
    }
    
    /**
     * Calculate FUDKII structural trigger
     */
    public FUDKIIOutput calculate(
            String scripCode,
            String companyName,
            List<UnifiedCandle> candles30m,
            double previousFudkii
    ) {
        long timestamp = System.currentTimeMillis();
        
        // Validate data
        if (candles30m == null || candles30m.size() < BB_PERIOD + 5) {
            return FUDKIIOutput.builder()
                    .scripCode(scripCode)
                    .companyName(companyName)
                    .timestamp(timestamp)
                    .fudkiiScore(NormalizedScore.neutral(timestamp))
                    .isValid(false)
                    .invalidReason("Insufficient candles: need " + (BB_PERIOD + 5))
                    .build();
        }
        
        UnifiedCandle current = candles30m.get(candles30m.size() - 1);
        double close = current.getClose();
        
        // ======================== ATR FOR NORMALIZATION ========================
        double atr14 = calculateATR(candles30m, ATR_PERIOD);
        if (atr14 <= 0) {
            atr14 = 0.01 * close; // Fallback to 1% of price
        }
        
        // ======================== BOLLINGER BANDS ========================
        double[] bb = calculateBollingerBands(candles30m, BB_PERIOD, BB_STD_MULTIPLIER);
        double bbUpper = bb[0];
        double bbMiddle = bb[1];
        double bbLower = bb[2];
        
        // BB_Score_Bullish = max(close - BB_upper, 0) / ATR_14
        double bbScoreBullish = Math.max(close - bbUpper, 0) / atr14;
        bbScoreBullish = Math.min(bbScoreBullish, 1.0);
        
        // BB_Score_Bearish = max(BB_lower - close, 0) / ATR_14
        double bbScoreBearish = Math.max(bbLower - close, 0) / atr14;
        bbScoreBearish = Math.min(bbScoreBearish, 1.0);
        
        // Determine BB signal bar (0 = current, 1 = previous, etc.)
        int bbSignalBar = -1;
        if (bbScoreBullish > 0 || bbScoreBearish > 0) {
            bbSignalBar = 0;
        } else if (candles30m.size() >= BB_PERIOD + 6) {
            // Check previous bar
            UnifiedCandle prev = candles30m.get(candles30m.size() - 2);
            double[] prevBB = calculateBollingerBandsAtIndex(candles30m, BB_PERIOD, BB_STD_MULTIPLIER, candles30m.size() - 2);
            double prevBBBullish = Math.max(prev.getClose() - prevBB[0], 0) / atr14;
            double prevBBBearish = Math.max(prevBB[2] - prev.getClose(), 0) / atr14;
            if (prevBBBullish > 0 || prevBBBearish > 0) {
                bbSignalBar = 1;
            }
        }
        
        // ======================== SUPERTREND ========================
        double[] stCurrent = calculateSuperTrend(candles30m, ST_ATR_PERIOD, ST_MULTIPLIER);
        double stLine = stCurrent[0];
        boolean stCurrentBullish = stCurrent[1] > 0;
        
        // Check previous SuperTrend
        List<UnifiedCandle> prevCandles = candles30m.subList(0, candles30m.size() - 1);
        double[] stPrev = calculateSuperTrend(prevCandles, ST_ATR_PERIOD, ST_MULTIPLIER);
        boolean stPreviousBullish = stPrev[1] > 0;
        
        boolean stFlipped = stCurrentBullish != stPreviousBullish;
        
        // ST_Flip_Strength = |close - ST_line| / ATR_14
        double stFlipStrength = 0.0;
        if (stFlipped) {
            stFlipStrength = Math.abs(close - stLine) / atr14;
            stFlipStrength = Math.min(stFlipStrength, 1.0);
        }
        
        // Determine ST signal bar
        int stSignalBar = stFlipped ? 0 : -1;
        
        // ======================== SIMULTANEITY WEIGHT ========================
        // Same bar = 1.0, one bar apart = 0.5
        double simultaneityWeight;
        if (bbSignalBar == 0 && stSignalBar == 0) {
            simultaneityWeight = 1.0;  // Same bar
        } else if ((bbSignalBar == 0 && stSignalBar == 1) || (bbSignalBar == 1 && stSignalBar == 0)) {
            simultaneityWeight = 0.5;  // One bar apart
        } else if (bbSignalBar >= 0 || stSignalBar >= 0) {
            simultaneityWeight = 0.3;  // Partial signal
        } else {
            simultaneityWeight = 0.0;  // No signals
        }
        
        // ======================== FUDKII STRENGTH ========================
        // Direction determination
        String direction;
        double bbScore, stScore;
        if (bbScoreBullish >= bbScoreBearish && stCurrentBullish) {
            direction = "BULLISH";
            bbScore = bbScoreBullish;
            stScore = stFlipStrength;
        } else if (bbScoreBearish > bbScoreBullish && !stCurrentBullish) {
            direction = "BEARISH";
            bbScore = bbScoreBearish;
            stScore = stFlipStrength;
        } else {
            direction = "NEUTRAL";
            bbScore = 0;
            stScore = 0;
        }
        
        // Fudkii_Strength = min(1, BB_Score + ST_Score) × Simultaneity_Weight
        double rawFudkii = Math.min(1.0, bbScore + stScore) * simultaneityWeight;
        
        // Apply direction
        double fudkiiValue = "BEARISH".equals(direction) ? -rawFudkii : rawFudkii;
        
        // ======================== PATIENCE LOGIC ========================
        boolean waiting = false;
        boolean rejected = false;
        String rejectReason = null;
        
        if (Math.abs(rawFudkii) > 0 && Math.abs(rawFudkii) < FUDKII_THRESHOLD) {
            waiting = true;
            // In real implementation, track bars waited in state store
        }
        
        NormalizedScore fudkiiScore = NormalizedScore.directional(fudkiiValue, previousFudkii, timestamp);
        
        return FUDKIIOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(timestamp)
                // BB
                .bbScoreBullish(bbScoreBullish)
                .bbScoreBearish(bbScoreBearish)
                .bbUpper(bbUpper)
                .bbMiddle(bbMiddle)
                .bbLower(bbLower)
                // ST
                .stFlipStrength(stFlipStrength)
                .stLine(stLine)
                .stCurrentBullish(stCurrentBullish)
                .stPreviousBullish(stPreviousBullish)
                .stFlipped(stFlipped)
                // Simultaneity
                .simultaneityWeight(simultaneityWeight)
                .bbSignalBar(bbSignalBar)
                .stSignalBar(stSignalBar)
                // Final
                .fudkiiScore(fudkiiScore)
                .direction(direction)
                // Patience
                .waitingForConfirmation(waiting)
                .barsWaited(0)
                .rejected(rejected)
                .rejectReason(rejectReason)
                // Meta
                .isValid(true)
                .build();
    }
    
    // ======================== BOLLINGER BANDS ========================
    
    private double[] calculateBollingerBands(List<UnifiedCandle> candles, int period, double stdMultiplier) {
        return calculateBollingerBandsAtIndex(candles, period, stdMultiplier, candles.size() - 1);
    }
    
    private double[] calculateBollingerBandsAtIndex(List<UnifiedCandle> candles, int period, double stdMultiplier, int endIndex) {
        int start = endIndex - period + 1;
        if (start < 0) start = 0;
        
        double sum = 0;
        int count = 0;
        for (int i = start; i <= endIndex; i++) {
            sum += candles.get(i).getClose();
            count++;
        }
        double sma = sum / count;
        
        double variance = 0;
        for (int i = start; i <= endIndex; i++) {
            double diff = candles.get(i).getClose() - sma;
            variance += diff * diff;
        }
        double stdDev = Math.sqrt(variance / count);
        
        double upper = sma + stdMultiplier * stdDev;
        double lower = sma - stdMultiplier * stdDev;
        
        return new double[] { upper, sma, lower };
    }
    
    // ======================== SUPERTREND ========================
    
    private double[] calculateSuperTrend(List<UnifiedCandle> candles, int atrPeriod, double multiplier) {
        if (candles.size() < atrPeriod + 1) {
            return new double[] { 0, 0 };
        }
        
        double atr = calculateATR(candles, atrPeriod);
        UnifiedCandle current = candles.get(candles.size() - 1);
        double hl2 = (current.getHigh() + current.getLow()) / 2;
        
        double upperBand = hl2 + multiplier * atr;
        double lowerBand = hl2 - multiplier * atr;
        
        // Simplified SuperTrend: use close vs bands
        double close = current.getClose();
        boolean isBullish = close > lowerBand;
        
        double stLine = isBullish ? lowerBand : upperBand;
        
        return new double[] { stLine, isBullish ? 1 : -1 };
    }
    
    // ======================== ATR ========================
    
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
