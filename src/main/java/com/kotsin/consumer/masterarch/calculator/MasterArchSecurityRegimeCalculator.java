package com.kotsin.consumer.masterarch.calculator;

import com.kotsin.consumer.masterarch.model.IndexContextScore;
import com.kotsin.consumer.masterarch.model.NormalizedScore;
import com.kotsin.consumer.masterarch.model.SecurityContextScore;
import com.kotsin.consumer.model.UnifiedCandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MasterArchSecurityRegimeCalculator - PART 1B: Security Regime Module
 * 
 * MASTER ARCHITECTURE Compliant Calculator
 * 
 * Formula:
 * Raw_Security_Strength =
 *   0.30*Trend_Direction
 * + 0.20*Trend_Persistence*Trend_Direction
 * + 0.20*Relative_Strength_vs_Index
 * + 0.15*ATR_Expansion*Trend_Direction
 * + 0.10*Structure_Quality
 * + 0.05*Breakout_Quality*Trend_Direction
 * 
 * Security_Regime_Strength = Raw × Index_Regime_Strength × Flow_Alignment_Multiplier
 * 
 * Flow Alignment:
 * - Aligned with index → ×1.10
 * - Misaligned → ×0.75
 * 
 * Output Topic: regime-security-output
 */
@Slf4j
@Component
public class MasterArchSecurityRegimeCalculator {
    
    // EMA periods per spec
    private static final int EMA_FAST_PERIOD = 20;
    private static final int EMA_SLOW_PERIOD = 50;
    private static final int ATR_PERIOD = 14;
    private static final int ATR_SMA_PERIOD = 20;
    private static final int PERSISTENCE_LOOKBACK = 20;
    private static final int ROC_PERIOD = 20;
    private static final double EPSILON = 0.001;
    
    /**
     * Calculate Security Context Score from 30m candle data and parent index regime
     * 
     * @param scripCode Security's scrip code
     * @param companyName Security's name
     * @param candles30m 30-minute candles (most recent last)
     * @param indexRegime Parent index regime for relative strength and flow alignment
     * @param previousScore Previous security context score for delta
     * @return SecurityContextScore with all MASTER ARCHITECTURE metrics
     */
    public SecurityContextScore calculate(
            String scripCode,
            String companyName,
            List<UnifiedCandle> candles30m,
            IndexContextScore indexRegime,
            double previousScore
    ) {
        long timestamp = System.currentTimeMillis();
        
        // Validate minimum data
        if (candles30m == null || candles30m.size() < EMA_SLOW_PERIOD + 5) {
            return SecurityContextScore.invalid(scripCode, companyName, timestamp,
                    "Insufficient candles: need " + (EMA_SLOW_PERIOD + 5));
        }
        
        UnifiedCandle current = candles30m.get(candles30m.size() - 1);
        
        // ======================== COMPONENT 1: TREND DIRECTION ========================
        // Trend_Direction = sign(EMA_20_30m - EMA_50_30m)
        double ema20 = calculateEMA(candles30m, EMA_FAST_PERIOD);
        double ema50 = calculateEMA(candles30m, EMA_SLOW_PERIOD);
        
        double emaDiff = ema20 - ema50;
        int trendDirection;
        if (emaDiff > EPSILON * current.getClose()) {
            trendDirection = 1;
        } else if (emaDiff < -EPSILON * current.getClose()) {
            trendDirection = -1;
        } else {
            trendDirection = 0;
        }
        
        // ======================== COMPONENT 2: TREND PERSISTENCE ========================
        // Trend_Persistence = Consecutive_Bars_Same_Trend / Lookback_Bars
        int consecutiveBars = calculateConsecutiveTrendBars(candles30m, trendDirection);
        double trendPersistence = Math.min((double) consecutiveBars / PERSISTENCE_LOOKBACK, 1.0);
        
        // Override: If persistence ≥ 0.8 and Volume_Certainty ≥ 0.75, boost by ×1.05
        // (Volume certainty check happens at integration layer)
        
        // ======================== COMPONENT 3: RELATIVE STRENGTH VS INDEX ========================
        // Relative_Strength_vs_Index = (Security_ROC_20 − Index_ROC_20) / max(|Index_ROC_20|, ε)
        double securityRoc20 = calculateROC(candles30m, ROC_PERIOD);
        double indexRoc20 = indexRegime != null ? calculateIndexROC(indexRegime) : 0.0;
        
        double relativeStrength;
        if (Math.abs(indexRoc20) > EPSILON) {
            relativeStrength = (securityRoc20 - indexRoc20) / Math.abs(indexRoc20);
        } else {
            relativeStrength = securityRoc20 > 0.1 ? 0.5 : (securityRoc20 < -0.1 ? -0.5 : 0.0);
        }
        relativeStrength = clamp(relativeStrength, -1.0, 1.0);
        
        // ======================== COMPONENT 4: ATR EXPANSION ========================
        // ATR_Expansion = (ATR_14 - SMA(ATR_14,20)) / SMA(ATR_14,20)
        double atr14 = calculateATR(candles30m, ATR_PERIOD);
        double atrSma20 = calculateATRSMA(candles30m, ATR_PERIOD, ATR_SMA_PERIOD);
        
        double atrExpansion;
        if (atrSma20 > EPSILON) {
            atrExpansion = (atr14 - atrSma20) / atrSma20;
        } else {
            atrExpansion = 0.0;
        }
        atrExpansion = clamp(atrExpansion, -1.0, 1.0);
        
        // ======================== COMPONENT 5: STRUCTURE QUALITY ========================
        // Structure_Quality = 0.5 * Structure_30m + 0.5 * Structure_1D
        // For now, only 30m data available; 1D would come from separate candles
        int structure30m = calculateStructure(candles30m);
        int structure1D = 0; // Would need daily candles
        double structureQuality = 0.5 * structure30m + 0.5 * structure1D;
        
        // Override: If structure flips within last 3 bars, reduce magnitude by 0.7
        boolean structureFlipRecent = checkStructureFlip(candles30m, 3);
        if (structureFlipRecent) {
            structureQuality *= 0.7;
        }
        
        // ======================== COMPONENT 6: BREAKOUT QUALITY ========================
        // Breakout_Quality_bull = max(close - Resistance, 0) / ATR_14_30m
        // For now, use recent high as resistance proxy
        double recentHigh = calculateRecentHigh(candles30m, 20);
        double recentLow = calculateRecentLow(candles30m, 20);
        
        double breakoutQuality;
        if (trendDirection > 0) {
            // Bullish: check upside breakout
            breakoutQuality = Math.max(current.getClose() - recentHigh, 0) / (atr14 + EPSILON);
        } else if (trendDirection < 0) {
            // Bearish: check downside breakout
            breakoutQuality = Math.max(recentLow - current.getClose(), 0) / (atr14 + EPSILON);
        } else {
            breakoutQuality = 0.0;
        }
        breakoutQuality = clamp(breakoutQuality, 0.0, 1.0);
        
        // ======================== FLOW ALIGNMENT ========================
        int securityFlowSign = calculateFlowSign(candles30m, 5);
        int indexFlowAgreement = indexRegime != null ? indexRegime.getFlowAgreement30m() : 0;
        boolean isFlowAligned = (securityFlowSign == indexFlowAgreement) || 
                                (securityFlowSign == 0 || indexFlowAgreement == 0);
        double flowMultiplier = isFlowAligned ? 1.10 : 0.75;
        
        // ======================== RAW SECURITY STRENGTH ========================
        double rawStrength = 0.30 * trendDirection
                           + 0.20 * trendPersistence * trendDirection
                           + 0.20 * relativeStrength
                           + 0.15 * atrExpansion * trendDirection
                           + 0.10 * structureQuality
                           + 0.05 * breakoutQuality * trendDirection;
        rawStrength = clamp(rawStrength, -1.0, 1.0);
        
        // ======================== FINAL SECURITY REGIME STRENGTH ========================
        double indexRegimeStrength = indexRegime != null ? indexRegime.getRegimeStrength() : 0.5;
        
        // ======================== MICRO-LEADER OVERRIDE (FF1 SPEC) ========================
        // Allow high-quality, low-beta leaders to express strength even when index is weak
        // Conditions (ALL must be true):
        // 1. Security_Weight < 0.05 (not a heavy-weight stock - assumed true for non-index)
        // 2. Correlation(Security, Index) < 0.30 (low correlation - estimated from relative strength)
        // 3. Raw_Security_Strength > 0.75 (strong security)
        // 4. Index_Regime_Strength < 0.55 (weak index)
        // Note: Volume_Certainty > 0.80 check happens at orchestrator level
        
        boolean microLeaderOverrideApplied = false;
        double estimatedCorrelation = 1.0 - Math.abs(relativeStrength); // Proxy: stronger outperformance = lower correlation
        
        boolean microLeaderConditions = 
            estimatedCorrelation < 0.30 &&
            Math.abs(rawStrength) > 0.75 &&
            indexRegimeStrength < 0.55 &&
            // Hard guardrail: Never apply when index is strongly opposite
            !(indexRegime != null && indexRegime.getContextScore().getCurrent() <= -0.50 && rawStrength > 0);
        
        double finalStrength;
        if (microLeaderConditions) {
            // Determine decouple factor (0.30-0.40)
            // Higher decouple if breakoutQuality and structureQuality are strong
            double decoupleFactor = (breakoutQuality >= 0.75 && structureQuality >= 0.50) ? 0.40 : 0.30;
            
            // Effective index coupling: never let index become irrelevant (min 0.35)
            double effectiveIndexCoupling = Math.max(0.35, indexRegimeStrength * (1 - decoupleFactor));
            
            finalStrength = rawStrength * effectiveIndexCoupling * flowMultiplier;
            microLeaderOverrideApplied = true;
        } else {
            // Normal calculation
            finalStrength = rawStrength * indexRegimeStrength * flowMultiplier;
        }
        finalStrength = clamp(finalStrength, -1.0, 1.0);
        
        return SecurityContextScore.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(timestamp)
                // Trend
                .trendDirection(trendDirection)
                .ema20_30m(ema20)
                .ema50_30m(ema50)
                .trendPersistence(trendPersistence)
                .consecutiveBars(consecutiveBars)
                // Relative strength
                .relativeStrength(relativeStrength)
                .securityRoc20(securityRoc20)
                .indexRoc20(indexRoc20)
                // Volatility
                .atrExpansion(atrExpansion)
                .atr14(atr14)
                .atrSma20(atrSma20)
                // Structure
                .structureQuality(structureQuality)
                .structure30m(structure30m)
                .structureFlipRecent(structureFlipRecent)
                // Breakout
                .breakoutQuality(breakoutQuality)
                // Flow
                .flowAlignmentMultiplier(flowMultiplier)
                .securityFlowSign(securityFlowSign)
                .indexFlowAgreement(indexFlowAgreement)
                .isFlowAligned(isFlowAligned)
                // Composite
                .rawSecurityStrength(rawStrength)
                .indexRegimeStrength(indexRegimeStrength)
                .contextScore(NormalizedScore.directional(finalStrength, previousScore, timestamp))
                .parentIndexName(indexRegime != null ? indexRegime.getIndexName() : "UNKNOWN")
                .isValid(true)
                .build();
    }
    
    // ======================== HELPER METHODS ========================
    
    private double calculateEMA(List<UnifiedCandle> candles, int period) {
        if (candles == null || candles.size() < period) return 0.0;
        
        double multiplier = 2.0 / (period + 1);
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += candles.get(i).getClose();
        }
        double ema = sum / period;
        
        for (int i = period; i < candles.size(); i++) {
            ema = (candles.get(i).getClose() - ema) * multiplier + ema;
        }
        return ema;
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
    
    private double calculateATRSMA(List<UnifiedCandle> candles, int atrPeriod, int smaPeriod) {
        if (candles == null || candles.size() < atrPeriod + smaPeriod) {
            return calculateATR(candles, atrPeriod);
        }
        
        double sum = 0;
        int count = 0;
        for (int i = atrPeriod + 1; i <= candles.size() && count < smaPeriod; i++) {
            sum += calculateATR(candles.subList(0, i), atrPeriod);
            count++;
        }
        return count > 0 ? sum / count : calculateATR(candles, atrPeriod);
    }
    
    private int calculateConsecutiveTrendBars(List<UnifiedCandle> candles, int currentDirection) {
        if (candles == null || candles.size() < EMA_SLOW_PERIOD + 2 || currentDirection == 0) return 0;
        
        int count = 0;
        for (int offset = 1; offset <= Math.min(PERSISTENCE_LOOKBACK, candles.size() - EMA_SLOW_PERIOD - 1); offset++) {
            List<UnifiedCandle> subset = candles.subList(0, candles.size() - offset);
            if (subset.size() < EMA_SLOW_PERIOD) break;
            
            double histEma20 = calculateEMA(subset, EMA_FAST_PERIOD);
            double histEma50 = calculateEMA(subset, EMA_SLOW_PERIOD);
            double diff = histEma20 - histEma50;
            double threshold = EPSILON * subset.get(subset.size() - 1).getClose();
            
            int histDir = diff > threshold ? 1 : (diff < -threshold ? -1 : 0);
            if (histDir == currentDirection) count++;
            else break;
        }
        return count + 1;
    }
    
    private double calculateROC(List<UnifiedCandle> candles, int period) {
        if (candles == null || candles.size() <= period) return 0.0;
        
        double current = candles.get(candles.size() - 1).getClose();
        double previous = candles.get(candles.size() - 1 - period).getClose();
        return previous > EPSILON ? (current - previous) / previous * 100 : 0.0;
    }
    
    private double calculateIndexROC(IndexContextScore indexRegime) {
        // Approximate from regime data if available
        if (indexRegime.getEma20_30m() > 0 && indexRegime.getEma50_30m() > 0) {
            return ((indexRegime.getEma20_30m() / indexRegime.getEma50_30m()) - 1.0) * 100;
        }
        return 0.0;
    }
    
    private int calculateStructure(List<UnifiedCandle> candles) {
        if (candles == null || candles.size() < 4) return 0;
        
        int size = candles.size();
        UnifiedCandle c0 = candles.get(size - 1);
        UnifiedCandle c1 = candles.get(size - 2);
        UnifiedCandle c2 = candles.get(size - 3);
        
        boolean higherHigh = c0.getHigh() > c1.getHigh();
        boolean higherLow = c0.getLow() > c1.getLow();
        boolean lowerHigh = c0.getHigh() < c1.getHigh();
        boolean lowerLow = c0.getLow() < c1.getLow();
        
        if (higherHigh && higherLow) return 1;
        if (lowerHigh && lowerLow) return -1;
        return 0;
    }
    
    private boolean checkStructureFlip(List<UnifiedCandle> candles, int bars) {
        if (candles == null || candles.size() < bars + 4) return false;
        
        int currentStructure = calculateStructure(candles);
        for (int i = 1; i <= bars; i++) {
            List<UnifiedCandle> subset = candles.subList(0, candles.size() - i);
            if (subset.size() < 4) continue;
            int histStructure = calculateStructure(subset);
            if (histStructure != 0 && histStructure != currentStructure) return true;
        }
        return false;
    }
    
    private double calculateRecentHigh(List<UnifiedCandle> candles, int period) {
        if (candles == null || candles.isEmpty()) return 0.0;
        int start = Math.max(0, candles.size() - period - 1);
        int end = candles.size() - 1;
        double high = 0;
        for (int i = start; i < end; i++) {
            high = Math.max(high, candles.get(i).getHigh());
        }
        return high;
    }
    
    private double calculateRecentLow(List<UnifiedCandle> candles, int period) {
        if (candles == null || candles.isEmpty()) return Double.MAX_VALUE;
        int start = Math.max(0, candles.size() - period - 1);
        int end = candles.size() - 1;
        double low = Double.MAX_VALUE;
        for (int i = start; i < end; i++) {
            low = Math.min(low, candles.get(i).getLow());
        }
        return low != Double.MAX_VALUE ? low : 0.0;
    }
    
    private int calculateFlowSign(List<UnifiedCandle> candles, int bars) {
        if (candles == null || candles.size() < bars) return 0;
        
        int bullish = 0, bearish = 0;
        for (int i = candles.size() - bars; i < candles.size(); i++) {
            UnifiedCandle c = candles.get(i);
            if (c.getClose() > c.getOpen()) bullish++;
            else if (c.getClose() < c.getOpen()) bearish++;
        }
        if (bullish > bearish + 1) return 1;
        if (bearish > bullish + 1) return -1;
        return 0;
    }
    
    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
