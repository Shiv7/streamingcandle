package com.kotsin.consumer.masterarch.calculator;

import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.masterarch.model.IndexContextScore;
import com.kotsin.consumer.masterarch.model.NormalizedScore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MasterArchIndexRegimeCalculator - PART 1A: Index Regime Module
 * 
 * MASTER ARCHITECTURE Compliant Calculator
 * 
 * Formulas:
 * 1. Index_Trend_Dir = sign(EMA_20_30m - EMA_50_30m)
 * 2. Index_Trend_Strength = |EMA_20 - EMA_50| / ATR_14_30m (clamped 0..1)
 * 3. Index_Persistence = Consecutive bars with same Trend_Dir / 20
 * 4. ATR_Pct = ATR_14_30m / SMA(ATR_14_30m, 50)
 * 5. Index_Regime_Strength = 0.40*Trend_Strength + 0.35*Persistence + 0.25*ATR_Pct
 * 6. Index_flow_agreement_30m = sign(Volume_ROC_5_30m)
 * 7. Index_Context_Score = Index_Trend_Dir × Index_Regime_Strength
 * 
 * Output Topic: regime-index-output
 */
@Slf4j
@Component
public class MasterArchIndexRegimeCalculator {
    
    // EMA periods per spec
    private static final int EMA_FAST_PERIOD = 20;
    private static final int EMA_SLOW_PERIOD = 50;
    private static final int ATR_PERIOD = 14;
    private static final int ATR_SMA_PERIOD = 50;
    private static final int PERSISTENCE_LOOKBACK = 20;
    private static final int VOLUME_ROC_PERIOD = 5;
    
    // Index codes
    public static final String NIFTY50_CODE = "999920000";
    public static final String BANKNIFTY_CODE = "999920005";
    public static final String FINNIFTY_CODE = "999920041";
    public static final String MIDCPNIFTY_CODE = "999920043";
    
    /**
     * Calculate Index Context Score from 30m candle data
     * 
     * @param indexName Name of the index (NIFTY50, BANKNIFTY, etc.)
     * @param scripCode Scrip code for the index
     * @param candles30m 30-minute candles (most recent last)
     * @param previousScore Previous Index_Context_Score for delta calculation
     * @return IndexContextScore with all MASTER ARCHITECTURE metrics
     */
    public IndexContextScore calculate(
            String indexName,
            String scripCode,
            List<InstrumentCandle> candles30m,
            double previousScore
    ) {
        long timestamp = System.currentTimeMillis();
        
        // Validate minimum data requirements
        if (candles30m == null || candles30m.size() < EMA_SLOW_PERIOD + 5) {
            log.debug("Insufficient data for IndexRegime: {} has {} candles, need {}", 
                    indexName, candles30m != null ? candles30m.size() : 0, EMA_SLOW_PERIOD + 5);
            return IndexContextScore.invalid(indexName, scripCode, timestamp, 
                    "Insufficient candles: need " + (EMA_SLOW_PERIOD + 5));
        }
        
        InstrumentCandle current = candles30m.get(candles30m.size() - 1);
        
        // Step 1: Calculate EMAs
        double ema20 = calculateEMA(candles30m, EMA_FAST_PERIOD);
        double ema50 = calculateEMA(candles30m, EMA_SLOW_PERIOD);
        
        // Index_Trend_Dir = sign(EMA_20_30m - EMA_50_30m)
        double emaDiff = ema20 - ema50;
        int trendDirection;
        if (emaDiff > 0.001 * current.getClose()) {
            trendDirection = 1;  // Bullish
        } else if (emaDiff < -0.001 * current.getClose()) {
            trendDirection = -1; // Bearish
        } else {
            trendDirection = 0;  // Neutral
        }
        
        // Step 2: Index_Trend_Strength = |EMA_20 - EMA_50| / ATR_14_30m
        double atr14 = calculateATR(candles30m, ATR_PERIOD);
        double trendStrength = atr14 > 0 
                ? Math.min(Math.abs(emaDiff) / atr14, 1.0)
                : 0.0;
        
        // Step 3: Index_Persistence = Consecutive bars with same Trend_Dir / 20
        int consecutiveBars = calculateConsecutiveTrendBars(candles30m, trendDirection);
        double persistence = Math.min((double) consecutiveBars / PERSISTENCE_LOOKBACK, 1.0);
        
        // Step 4: ATR_Pct = ATR_14_30m / SMA(ATR_14_30m, 50)
        double atrSma50 = calculateATRSMA(candles30m, ATR_PERIOD, ATR_SMA_PERIOD);
        double atrPct = atrSma50 > 0 ? atr14 / atrSma50 : 1.0;
        // Note: atrPct > 1 = high volatility, < 1 = low volatility
        // For regime strength, we use min(atrPct, 1.0) per spec to clamp
        
        // Step 5: Index_Regime_Strength = 0.40*Trend_Strength + 0.35*Persistence + 0.25*ATR_Pct
        double regimeStrength = 0.40 * trendStrength 
                              + 0.35 * persistence 
                              + 0.25 * Math.min(atrPct, 1.0);
        regimeStrength = Math.min(1.0, Math.max(0.0, regimeStrength));
        
        // Step 6: Index_flow_agreement_30m = sign(Volume_ROC_5_30m)
        double volumeRoc5 = calculateVolumeROC(candles30m, VOLUME_ROC_PERIOD);
        int flowAgreement;
        if (volumeRoc5 > 0.1) {
            flowAgreement = 1;  // Expanding volume
        } else if (volumeRoc5 < -0.1) {
            flowAgreement = -1; // Contracting
        } else {
            flowAgreement = 0;  // Flat
        }
        
        // Step 7: Index_Context_Score = Index_Trend_Dir × Index_Regime_Strength
        double currentScore = trendDirection * regimeStrength;
        
        // Build result with full metadata
        return IndexContextScore.builder()
                .indexName(indexName)
                .scripCode(scripCode)
                .timestamp(timestamp)
                // Trend components
                .trendDirection(trendDirection)
                .ema20_30m(ema20)
                .ema50_30m(ema50)
                .trendStrength(trendStrength)
                // Persistence
                .persistence(persistence)
                .consecutiveBars(consecutiveBars)
                // Volatility
                .atrPct(atrPct)
                .atr14_30m(atr14)
                .atrSma50(atrSma50)
                // Flow
                .flowAgreement30m(flowAgreement)
                .volumeRoc5_30m(volumeRoc5)
                // Composite
                .regimeStrength(regimeStrength)
                .contextScore(NormalizedScore.directional(currentScore, previousScore, timestamp))
                .timeframe("30m")
                .isValid(true)
                .build();
    }
    
    // ======================== EMA CALCULATION ========================
    
    /**
     * Calculate Exponential Moving Average
     */
    private double calculateEMA(List<InstrumentCandle> candles, int period) {
        if (candles == null || candles.size() < period) {
            return 0.0;
        }
        
        double multiplier = 2.0 / (period + 1);
        
        // Start with SMA for first EMA value
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += candles.get(i).getClose();
        }
        double ema = sum / period;
        
        // Calculate EMA for remaining bars
        for (int i = period; i < candles.size(); i++) {
            double close = candles.get(i).getClose();
            ema = (close - ema) * multiplier + ema;
        }
        
        return ema;
    }
    
    // ======================== ATR CALCULATION ========================
    
    /**
     * Calculate Average True Range
     */
    private double calculateATR(List<InstrumentCandle> candles, int period) {
        if (candles == null || candles.size() < period + 1) {
            return 0.0;
        }
        
        // Calculate True Range for each bar and take average
        double sum = 0;
        int start = Math.max(1, candles.size() - period);
        int count = 0;
        
        for (int i = start; i < candles.size(); i++) {
            InstrumentCandle current = candles.get(i);
            InstrumentCandle prev = candles.get(i - 1);
            
            double tr1 = current.getHigh() - current.getLow();
            double tr2 = Math.abs(current.getHigh() - prev.getClose());
            double tr3 = Math.abs(current.getLow() - prev.getClose());
            double tr = Math.max(tr1, Math.max(tr2, tr3));
            
            sum += tr;
            count++;
        }
        
        return count > 0 ? sum / count : 0.0;
    }
    
    /**
     * Calculate SMA of ATR values over longer period
     */
    private double calculateATRSMA(List<InstrumentCandle> candles, int atrPeriod, int smaPeriod) {
        if (candles == null || candles.size() < atrPeriod + smaPeriod) {
            return calculateATR(candles, atrPeriod); // Return current ATR as fallback
        }
        
        // Calculate ATR at multiple points and average
        double sum = 0;
        int count = 0;
        
        for (int i = atrPeriod + 1; i <= candles.size() && count < smaPeriod; i++) {
            List<InstrumentCandle> subset = candles.subList(0, i);
            double atr = calculateATR(subset, atrPeriod);
            if (atr > 0) {
                sum += atr;
                count++;
            }
        }
        
        return count > 0 ? sum / count : calculateATR(candles, atrPeriod);
    }
    
    // ======================== PERSISTENCE CALCULATION ========================
    
    /**
     * Count consecutive bars with same trend direction
     * Looking backward from current bar
     */
    private int calculateConsecutiveTrendBars(List<InstrumentCandle> candles, int currentDirection) {
        if (candles == null || candles.size() < EMA_SLOW_PERIOD + 2 || currentDirection == 0) {
            return 0;
        }
        
        int count = 0;
        
        // Need to calculate EMA at each historical point to determine direction
        // For efficiency, only check up to PERSISTENCE_LOOKBACK bars
        for (int offset = 1; offset <= Math.min(PERSISTENCE_LOOKBACK, candles.size() - EMA_SLOW_PERIOD - 1); offset++) {
            int endIndex = candles.size() - offset;
            List<InstrumentCandle> subset = candles.subList(0, endIndex);
            
            if (subset.size() < EMA_SLOW_PERIOD) break;
            
            double historicalEma20 = calculateEMA(subset, EMA_FAST_PERIOD);
            double historicalEma50 = calculateEMA(subset, EMA_SLOW_PERIOD);
            
            int historicalDirection;
            double diff = historicalEma20 - historicalEma50;
            double threshold = 0.001 * subset.get(subset.size() - 1).getClose();
            
            if (diff > threshold) {
                historicalDirection = 1;
            } else if (diff < -threshold) {
                historicalDirection = -1;
            } else {
                historicalDirection = 0;
            }
            
            if (historicalDirection == currentDirection) {
                count++;
            } else {
                break; // Direction changed, stop counting
            }
        }
        
        return count + 1; // Include current bar
    }
    
    // ======================== VOLUME ROC ========================
    
    /**
     * Calculate Volume Rate of Change
     * Vol_ROC_5 = (Vol_t - Vol_t-5) / Vol_t-5
     */
    private double calculateVolumeROC(List<InstrumentCandle> candles, int period) {
        if (candles == null || candles.size() <= period) {
            return 0.0;
        }
        
        long currentVolume = candles.get(candles.size() - 1).getVolume();
        long previousVolume = candles.get(candles.size() - 1 - period).getVolume();
        
        if (previousVolume <= 0) {
            return 0.0;
        }
        
        return (double) (currentVolume - previousVolume) / previousVolume;
    }
}
