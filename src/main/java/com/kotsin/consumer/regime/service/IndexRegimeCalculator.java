package com.kotsin.consumer.regime.service;

import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.regime.model.IndexRegime;
import com.kotsin.consumer.regime.model.IndexRegime.*;
import com.kotsin.consumer.regime.model.RegimeLabel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * IndexRegimeCalculator - Module 1: Index Regime
 * 
 * Implements the 9-step Index Regime calculation:
 * 1. VWAP_control_tf = min(abs((Close-VWAP)/ATR14), 1.5) / 1.5
 * 2. Participation_tf = clip(volumeDelta/expectedVolumeImbalance, 0, 1.5) / 1.5
 * 3. Flow Agreement (3-bar smoothed)
 * 4. Volatility State from ATR ratio
 * 5. Regime_Coherence = 1 - stdev(VWAP_ctrl, Participation, Vol_score)
 * 6. Regime_Strength = 0.4*VWAP + 0.35*Participation + 0.25*Vol
 * 7. Label classification
 * 8. Multi-TF aggregation with weights
 * 9. Session confidence modifier
 * 
 * Detects market-wide bias, coherence, and expansion readiness
 */
@Slf4j
@Service
public class IndexRegimeCalculator {

    // Timeframe weights for aggregation
    @Value("${regime.tf.weight.1d:0.35}")
    private double tfWeight1D;

    @Value("${regime.tf.weight.2h:0.30}")
    private double tfWeight2H;

    @Value("${regime.tf.weight.30m:0.30}")
    private double tfWeight30m;

    @Value("${regime.tf.weight.5m:0.05}")
    private double tfWeight5m;

    @Autowired(required = false)
    private com.kotsin.consumer.logging.PipelineTraceLogger traceLogger;

    // Index scrip codes
    public static final String NIFTY50_CODE = "999920000";
    public static final String BANKNIFTY_CODE = "999920005";
    public static final String FINNIFTY_CODE = "999920041";
    public static final String MIDCPNIFTY_CODE = "999920043";

    /**
     * Calculate full Index Regime from multi-timeframe candle data
     *
     * @param indexName Name of the index (NIFTY50, BANKNIFTY, etc.)
     * @param scripCode Scrip code for the index
     * @param candles1D 1D candle history (most recent last)
     * @param candles2H 2H candle history
     * @param candles30m 30m candle history
     * @param candles5m 5m candle history
     * @return IndexRegime with all computed metrics
     */
    public IndexRegime calculate(
            String indexName,
            String scripCode,
            List<InstrumentCandle> candles1D,
            List<InstrumentCandle> candles2H,
            List<InstrumentCandle> candles30m,
            List<InstrumentCandle> candles5m) {

        // Calculate per-timeframe data
        TimeframeRegimeData tf1D = calculateForTimeframe("1D", candles1D);
        TimeframeRegimeData tf2H = calculateForTimeframe("2H", candles2H);
        TimeframeRegimeData tf30m = calculateForTimeframe("30m", candles30m);
        TimeframeRegimeData tf5m = calculateForTimeframe("5m", candles5m);

        // Step 8: Multi-TF aggregation
        double aggregatedStrength = calculateAggregatedStrength(tf1D, tf2H, tf30m, tf5m);
        double aggregatedCoherence = calculateAggregatedCoherence(tf1D, tf2H, tf30m, tf5m);
        int netFlowAgreement = calculateNetFlowAgreement(tf1D, tf2H, tf30m, tf5m);
        VolatilityState dominantVolState = calculateDominantVolatilityState(tf1D, tf2H, tf30m, tf5m);

        // Count bullish/bearish TFs
        int bullishCount = 0, bearishCount = 0;
        if (tf1D != null && tf1D.getLabel() != null && tf1D.getLabel().isBullish()) bullishCount++;
        if (tf2H != null && tf2H.getLabel() != null && tf2H.getLabel().isBullish()) bullishCount++;
        if (tf30m != null && tf30m.getLabel() != null && tf30m.getLabel().isBullish()) bullishCount++;
        if (tf5m != null && tf5m.getLabel() != null && tf5m.getLabel().isBullish()) bullishCount++;
        if (tf1D != null && tf1D.getLabel() != null && tf1D.getLabel().isBearish()) bearishCount++;
        if (tf2H != null && tf2H.getLabel() != null && tf2H.getLabel().isBearish()) bearishCount++;
        if (tf30m != null && tf30m.getLabel() != null && tf30m.getLabel().isBearish()) bearishCount++;
        if (tf5m != null && tf5m.getLabel() != null && tf5m.getLabel().isBearish()) bearishCount++;

        // Multi-TF agreement score
        // FIX: Use actual available timeframe count instead of hardcoded 4
        int availableTfCount = 0;
        if (tf1D != null) availableTfCount++;
        if (tf2H != null) availableTfCount++;
        if (tf30m != null) availableTfCount++;
        if (tf5m != null) availableTfCount++;

        int maxCount = Math.max(bullishCount, bearishCount);
        double mtfAgreementScore = availableTfCount > 0 ? (double) maxCount / availableTfCount : 0;

        // Determine overall label
        int direction = bullishCount > bearishCount ? 1 : (bearishCount > bullishCount ? -1 : 0);
        RegimeLabel overallLabel = RegimeLabel.fromStrengthAndDirection(aggregatedStrength, direction);

        // Step 9: Session confidence modifier
        // FIX: Use event time from candle instead of wall clock for historical replay
        // For now, use wall clock but TODO: pass event time from processor
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        // TODO: Extract timestamp from candles30m.get(candles30m.size()-1) and use that
        SessionPhase sessionPhase = SessionPhase.fromTime(now.getHour(), now.getMinute());
        double sessionModifier = sessionPhase.getConfidenceMultiplier();

        // MASTER ARCHITECTURE - Calculate Index_Context_Score
        // Index_Context_Score = Index_Trend_Dir × Index_Regime_Strength
        double indexContextScore = direction * (aggregatedStrength * sessionModifier);

        log.info("[IndexRegime] {} | EMA-based formula | trendDir={} strength={:.3f} contextScore={:.3f} session={}",
            indexName, direction, aggregatedStrength * sessionModifier, indexContextScore, sessionPhase);

        IndexRegime result = IndexRegime.builder()
                .indexName(indexName)
                .scripCode(scripCode)
                .timestamp(System.currentTimeMillis())
                .tf1D(tf1D)
                .tf2H(tf2H)
                .tf30m(tf30m)
                .tf5m(tf5m)
                .regimeStrength(aggregatedStrength * sessionModifier)
                .regimeCoherence(aggregatedCoherence)
                .label(overallLabel)
                .flowAgreement(netFlowAgreement)
                .volatilityState(dominantVolState)
                .multiTfAgreementScore(mtfAgreementScore)
                .bullishTfCount(bullishCount)
                .bearishTfCount(bearishCount)
                .sessionPhase(sessionPhase)
                .sessionConfidenceModifier(sessionModifier)
                .indexContextScore(indexContextScore)
                .build();

        // Log index regime calculation with integrated logging
        if (traceLogger != null) {
            traceLogger.logIndexRegimeCalculated(
                scripCode, indexName,
                result.getTimestamp(),
                result.getLabel() != null ? result.getLabel().name() : "N/A",
                result.getRegimeStrength(),
                result.getFlowAgreement(),
                result.getVolatilityState() != null ? result.getVolatilityState().name() : "N/A"
            );
        }

        return result;
    }

    /**
     * Calculate regime data for a single timeframe
     */
    private TimeframeRegimeData calculateForTimeframe(String timeframe, List<InstrumentCandle> candles) {
        if (candles == null || candles.isEmpty()) {
            return TimeframeRegimeData.builder()
                    .timeframe(timeframe)
                    .ema20(0.0)
                    .ema50(0.0)
                    .indexTrendDir(0)
                    .indexTrendStrength(0.0)
                    .indexPersistence(0.0)
                    .atrPct(0.0)
                    .volumeROC5(0.0)
                    .regimeStrength(0)
                    .regimeCoherence(0)
                    .label(RegimeLabel.NEUTRAL)
                    .flowAgreement(0)
                    .volState(VolatilityState.NORMAL)
                    .build();
        }

        InstrumentCandle current = candles.get(candles.size() - 1);
        double close = current.getClose();
        
        // Calculate ATR14
        double atr14 = calculateATR(candles, 14);
        double avgAtr = calculateAverageATR(candles, 20);
        double minAtr = close * 0.001; // 0.1% of price as minimum ATR
        double effectiveAtr = Math.max(atr14, minAtr);

        // MASTER ARCHITECTURE - EMA-based Index Regime Formula
        
        // Step 1: Calculate EMA20 and EMA50
        double ema20 = calculateEMA(candles, 20);
        double ema50 = calculateEMA(candles, 50);
        
        // Step 2: Index_Trend_Dir = sign(EMA20 - EMA50)
        int indexTrendDir = (ema20 > ema50) ? 1 : ((ema20 < ema50) ? -1 : 0);
        
        // Step 3: Index_Trend_Strength = |EMA20 - EMA50| / ATR14 (clamp to [0,1])
        double emaDiff = Math.abs(ema20 - ema50);
        double indexTrendStrength = effectiveAtr > 0 ? Math.min(emaDiff / effectiveAtr, 1.0) : 0.0;
        
        // Step 4: Index_Persistence = Consecutive bars with same Trend_Dir / 20
        int consecutiveBars = calculatePersistence(candles, 20);
        double indexPersistence = Math.min((double) consecutiveBars / 20.0, 1.0);
        
        // Step 5: ATR_Pct = ATR14 / SMA(ATR14, 50)
        double atrSMA50 = calculateSMA_ATR(candles, 14, 50);
        double atrPct = atrSMA50 > 0 ? atr14 / atrSMA50 : 1.0;
        
        // Step 6: Volume_ROC_5 = (Vol_t - Vol_t-5) / Vol_t-5
        double volumeROC5 = calculateVolumeROC(candles, 5);
        
        // Step 7: Index_Regime_Strength = 0.40*Index_Trend_Strength + 0.35*Index_Persistence + 0.25*ATR_Pct
        double regimeStrength = 0.40 * indexTrendStrength + 0.35 * indexPersistence + 0.25 * atrPct;
        regimeStrength = Math.min(regimeStrength, 1.0); // Clamp to [0,1]
        
        // Step 8: Flow Agreement = sign(Volume_ROC_5)
        int flowAgreement = (volumeROC5 > 0) ? 1 : ((volumeROC5 < 0) ? -1 : 0);
        
        // Step 9: Volatility State (keep existing calculation)
        VolatilityState volState = VolatilityState.fromATRRatio(atr14, avgAtr);
        
        // Step 10: Regime Coherence (simplified for EMA-based formula)
        // Coherence based on consistency of trend direction vs strength
        double regimeCoherence = 1.0; // Default coherence (can be enhanced later)
        
        // Step 11: Label classification
        int direction = indexTrendDir;
        RegimeLabel label = RegimeLabel.fromStrengthAndDirection(regimeStrength, direction);
        
        // Logging at each step
        log.debug("[IndexRegime-{}] EMA20={:.2f} EMA50={:.2f} trendDir={} trendStrength={:.3f}", 
            timeframe, ema20, ema50, indexTrendDir, indexTrendStrength);
        log.debug("[IndexRegime-{}] persistence={:.3f} ({} bars) atrPct={:.3f} volumeROC5={:.3f}", 
            timeframe, indexPersistence, consecutiveBars, atrPct, volumeROC5);
        log.debug("[IndexRegime-{}] NEW EMA-based regimeStrength={:.3f}", 
            timeframe, regimeStrength);

        // Get VWAP for legacy fields (still stored but not used in new formula)
        double vwap = current.getVwap() > 0 ? current.getVwap() : current.getClose();
        
        return TimeframeRegimeData.builder()
                .timeframe(timeframe)
                // MASTER ARCHITECTURE - EMA-based fields
                .ema20(ema20)
                .ema50(ema50)
                .indexTrendDir(indexTrendDir)
                .indexTrendStrength(indexTrendStrength)
                .indexPersistence(indexPersistence)
                .atrPct(atrPct)
                .volumeROC5(volumeROC5)
                // Legacy fields (kept for backward reference, not used in new formula)
                .vwapControl(0.0) // Not used in new formula
                .participation(0.0) // Not used in new formula
                .flowAgreement(flowAgreement)
                .volState(volState)
                .regimeStrength(regimeStrength)
                .regimeCoherence(regimeCoherence)
                .label(label)
                .close(close)
                .atr14(atr14)
                .vwap(vwap)
                .build();
    }

    /**
     * Calculate EMA for given period
     */
    private double calculateEMA(List<InstrumentCandle> candles, int period) {
        if (candles == null || candles.isEmpty()) return 0;
        
        int actualPeriod = Math.min(period, candles.size());
        if (actualPeriod <= 0) return candles.get(candles.size() - 1).getClose();
        
        double multiplier = 2.0 / (actualPeriod + 1);
        
        // Start with SMA for initial value
        double sum = 0;
        for (int i = 0; i < actualPeriod && i < candles.size(); i++) {
            sum += candles.get(i).getClose();
        }
        double ema = actualPeriod > 0 ? sum / actualPeriod : candles.get(candles.size() - 1).getClose();
        
        // Calculate EMA for remaining candles
        for (int i = actualPeriod; i < candles.size(); i++) {
            ema = (candles.get(i).getClose() - ema) * multiplier + ema;
        }
        
        return ema;
    }

    /**
     * Calculate persistence: count consecutive bars with same trend direction
     * 
     * @param candles Candle history
     * @param lookback Maximum lookback period (default 20)
     * @return Number of consecutive bars with same trend direction
     */
    private int calculatePersistence(List<InstrumentCandle> candles, int lookback) {
        if (candles == null || candles.size() < 2) return 0;
        
        int actualLookback = Math.min(lookback, candles.size() - 1);
        if (actualLookback <= 0) return 0;
        
        // Calculate EMA20 and EMA50 to determine current trend
        double ema20 = calculateEMA(candles, 20);
        double ema50 = calculateEMA(candles, 50);
        int currentTrendDir = (ema20 > ema50) ? 1 : ((ema20 < ema50) ? -1 : 0);
        
        if (currentTrendDir == 0) return 0;
        
        // Count consecutive bars with same trend direction
        int consecutiveCount = 0;
        for (int i = candles.size() - 1; i > 0 && consecutiveCount < actualLookback; i--) {
            // Need enough candles to calculate EMA for this bar
            if (i < 20) break;
            
            List<InstrumentCandle> sublist = candles.subList(0, i + 1);
            double prevEma20 = calculateEMA(sublist, 20);
            double prevEma50 = calculateEMA(sublist, 50);
            int prevTrendDir = (prevEma20 > prevEma50) ? 1 : ((prevEma20 < prevEma50) ? -1 : 0);
            
            if (prevTrendDir == currentTrendDir) {
                consecutiveCount++;
            } else {
                break;
            }
        }
        
        return consecutiveCount;
    }

    /**
     * Calculate Volume ROC (Rate of Change) over N periods
     * Volume_ROC_5 = (Vol_t - Vol_t-5) / Vol_t-5
     * 
     * For indices (where volume is 0 or unreliable), fallback to price-based calculation
     */
    private double calculateVolumeROC(List<InstrumentCandle> candles, int period) {
        if (candles == null || candles.size() < period + 1) return 0.0;
        
        InstrumentCandle current = candles.get(candles.size() - 1);
        InstrumentCandle lag = candles.get(candles.size() - 1 - period);
        
        long volCurrent = current.getVolume();
        long volLag = lag.getVolume();
        
        // Check if we have valid volume data
        if (volLag > 0) {
            // Normal case: use volume ROC
            return (volCurrent - volLag) / (double) volLag;
        } else {
            // INDEX FALLBACK: Use price-based ROC as proxy
            // This approximates volume movement using price momentum
            double priceROC = (current.getClose() - lag.getClose()) / lag.getClose();
            return priceROC * 0.5; // Scale down to approximate volume sensitivity
        }
    }

    /**
     * Calculate Simple Moving Average of ATR values
     * Used for ATR_Pct calculation: ATR14 / SMA(ATR14, 50)
     */
    private double calculateSMA_ATR(List<InstrumentCandle> candles, int atrPeriod, int smaPeriod) {
        if (candles == null || candles.size() < atrPeriod + smaPeriod) {
            // Not enough data, return current ATR
            return calculateATR(candles, atrPeriod);
        }
        
        double sum = 0.0;
        int count = 0;
        
        // Calculate ATR for each position and average them
        for (int i = atrPeriod; i < candles.size() && count < smaPeriod; i++) {
            List<InstrumentCandle> sublist = candles.subList(0, i + 1);
            double atr = calculateATR(sublist, atrPeriod);
            sum += atr;
            count++;
        }
        
        return count > 0 ? sum / count : calculateATR(candles, atrPeriod);
    }

    /**
     * Calculate ATR for given period
     */
    private double calculateATR(List<InstrumentCandle> candles, int period) {
        int actualPeriod = Math.min(period, candles.size());
        if (actualPeriod <= 1) return 0;

        double sum = 0;
        for (int i = candles.size() - actualPeriod; i < candles.size(); i++) {
            if (i < 0) continue;
            InstrumentCandle c = candles.get(i);
            double tr = c.getHigh() - c.getLow();
            
            if (i > 0) {
                double prevClose = candles.get(i - 1).getClose();
                tr = Math.max(tr, Math.abs(c.getHigh() - prevClose));
                tr = Math.max(tr, Math.abs(c.getLow() - prevClose));
            }
            sum += tr;
        }
        return sum / actualPeriod;
    }

    /**
     * Calculate average ATR over longer period for comparison
     */
    private double calculateAverageATR(List<InstrumentCandle> candles, int period) {
        if (candles.size() < period) {
            return calculateATR(candles, candles.size());
        }
        
        // Calculate ATRs for each position and average them
        double sum = 0;
        int count = 0;
        for (int i = 14; i < candles.size(); i++) {
            List<InstrumentCandle> sublist = candles.subList(Math.max(0, i - 14), i + 1);
            sum += calculateATR(sublist, 14);
            count++;
        }
        return count > 0 ? sum / count : calculateATR(candles, 14);
    }

    /**
     * Calculate expected volume imbalance (average absolute delta)
     */
    private double calculateExpectedVolumeImbalance(List<InstrumentCandle> candles) {
        if (candles.size() < 5) return 1;
        
        double sum = 0;
        int period = Math.min(20, candles.size());
        for (int i = candles.size() - period; i < candles.size(); i++) {
            InstrumentCandle c = candles.get(i);
            sum += Math.abs(c.getBuyVolume() - c.getSellVolume());
        }
        return sum / period;
    }

    /**
     * Calculate flow agreement over N bars (+1 = bullish, -1 = bearish, 0 = neutral)
     * Uses epsilon thresholds per spec:
     * - epsilon_price = 0.05 (ATR normalized)
     * - epsilon_volume = 0.10 (expected imbalance normalized)
     *
     * For INDICES without buy/sell volume: uses price momentum consistency
     */
    private int calculateFlowAgreement(List<InstrumentCandle> candles, int bars) {
        if (candles.size() < bars) return 0;
        
        // Calculate ATR for normalization
        double atr = calculateATR(candles, 14);
        double expectedImbalance = calculateExpectedVolumeImbalance(candles);
        
        // Epsilon thresholds per spec
        final double EPSILON_PRICE = 0.05;
        final double EPSILON_VOLUME = 0.10;
        
        // Check if this is an INDEX (no volume data)
        boolean hasVolumeData = false;
        for (int i = candles.size() - bars; i < candles.size(); i++) {
            InstrumentCandle c = candles.get(i);
            if (Math.abs(c.getBuyVolume() - c.getSellVolume()) > 0) {
                hasVolumeData = true;
                break;
            }
        }

        int sumFlowRaw = 0;
        for (int i = candles.size() - bars; i < candles.size(); i++) {
            InstrumentCandle c = candles.get(i);
            
            // Step 3.1: Price Sign (ATR normalized)
            // FIX: Use minimum ATR threshold instead of epsilon
            double minAtr = c.getClose() * 0.001;
            double effectiveAtr = Math.max(atr, minAtr);
            double priceChangeNorm = effectiveAtr > 0 ? (c.getClose() - c.getOpen()) / effectiveAtr : 0;
            int priceSign = priceChangeNorm > EPSILON_PRICE ? 1 : 
                           (priceChangeNorm < -EPSILON_PRICE ? -1 : 0);
            
            int volumeSign;
            if (hasVolumeData) {
                // Normal case: use volume delta
                // FIX: Use minimum threshold instead of adding 1
                double volumeDelta = c.getBuyVolume() - c.getSellVolume();
                double minImbalance = Math.max(expectedImbalance, c.getClose() * 0.0001); // 0.01% of price
                double volNorm = minImbalance > 0 ? volumeDelta / minImbalance : 0;
                volumeSign = volNorm > EPSILON_VOLUME ? 1 : 
                            (volNorm < -EPSILON_VOLUME ? -1 : 0);
            } else {
                // INDEX FALLBACK: Use close position within range as proxy
                // If close near high = bullish flow, close near low = bearish flow
                // FIX: Already handled range > 0 check
                double range = c.getHigh() - c.getLow();
                if (range > 0.0001) {
                    double closePosition = (c.getClose() - c.getLow()) / range;
                    volumeSign = closePosition > 0.6 ? 1 : (closePosition < 0.4 ? -1 : 0);
                } else {
                    volumeSign = 0;
                }
            }
            
            // Step 3.3: Raw Flow Agreement
            int flowRaw = priceSign * volumeSign;
            sumFlowRaw += flowRaw;
        }
        
        // Step 3.4: Smoothed (round of mean)
        double mean = (double) sumFlowRaw / bars;
        return (int) Math.round(mean);
    }

    /**
     * Calculate aggregated strength across timeframes
     */
    private double calculateAggregatedStrength(TimeframeRegimeData tf1D, TimeframeRegimeData tf2H,
                                               TimeframeRegimeData tf30m, TimeframeRegimeData tf5m) {
        // FIX: Include all timeframes, not just those with strength > 0
        // Zero strength is valid (neutral regime), should be included
        double totalWeight = 0;
        double weightedSum = 0;
        
        if (tf1D != null) {
            weightedSum += tfWeight1D * tf1D.getRegimeStrength();
            totalWeight += tfWeight1D;
        }
        if (tf2H != null) {
            weightedSum += tfWeight2H * tf2H.getRegimeStrength();
            totalWeight += tfWeight2H;
        }
        if (tf30m != null) {
            weightedSum += tfWeight30m * tf30m.getRegimeStrength();
            totalWeight += tfWeight30m;
        }
        if (tf5m != null) {
            weightedSum += tfWeight5m * tf5m.getRegimeStrength();
            totalWeight += tfWeight5m;
        }
        
        return totalWeight > 0 ? weightedSum / totalWeight : 0;
    }

    /**
     * Calculate aggregated coherence
     */
    private double calculateAggregatedCoherence(TimeframeRegimeData tf1D, TimeframeRegimeData tf2H,
                                                TimeframeRegimeData tf30m, TimeframeRegimeData tf5m) {
        List<Double> strengths = new ArrayList<>();
        if (tf1D != null) strengths.add(tf1D.getRegimeStrength());
        if (tf2H != null) strengths.add(tf2H.getRegimeStrength());
        if (tf30m != null) strengths.add(tf30m.getRegimeStrength());
        if (tf5m != null) strengths.add(tf5m.getRegimeStrength());
        
        if (strengths.isEmpty()) return 0;
        if (strengths.size() == 1) return 1.0; // Perfect coherence with single TF
        
        // FIX: Use sample variance (n-1) instead of population variance (n)
        double mean = strengths.stream().mapToDouble(d -> d).average().orElse(0);
        double variance = strengths.stream()
                .mapToDouble(d -> Math.pow(d - mean, 2))
                .sum() / (strengths.size() - 1); // Sample variance
        double stdev = Math.sqrt(variance);
        
        return 1 - Math.min(stdev, 1.0);
    }

    /**
     * Calculate net flow agreement across timeframes
     */
    private int calculateNetFlowAgreement(TimeframeRegimeData tf1D, TimeframeRegimeData tf2H,
                                          TimeframeRegimeData tf30m, TimeframeRegimeData tf5m) {
        int sum = 0;
        if (tf1D != null) sum += tf1D.getFlowAgreement();
        if (tf2H != null) sum += tf2H.getFlowAgreement();
        if (tf30m != null) sum += tf30m.getFlowAgreement();
        if (tf5m != null) sum += tf5m.getFlowAgreement();
        
        if (sum >= 2) return 1;
        if (sum <= -2) return -1;
        return 0;
    }

    /**
     * Determine dominant volatility state (higher TF dominates)
     */
    private VolatilityState calculateDominantVolatilityState(TimeframeRegimeData tf1D, TimeframeRegimeData tf2H,
                                                             TimeframeRegimeData tf30m, TimeframeRegimeData tf5m) {
        if (tf1D != null && tf1D.getVolState() == VolatilityState.EXPANDING) return VolatilityState.EXPANDING;
        if (tf2H != null && tf2H.getVolState() == VolatilityState.EXPANDING) return VolatilityState.EXPANDING;
        if (tf1D != null && tf1D.getVolState() == VolatilityState.COMPRESSED) return VolatilityState.COMPRESSED;
        if (tf2H != null && tf2H.getVolState() == VolatilityState.COMPRESSED) return VolatilityState.COMPRESSED;
        return VolatilityState.NORMAL;
    }
}
