package com.kotsin.consumer.regime.service;

import com.kotsin.consumer.model.EnrichedCandlestick;
import com.kotsin.consumer.regime.model.IndexRegime;
import com.kotsin.consumer.regime.model.IndexRegime.*;
import com.kotsin.consumer.regime.model.RegimeLabel;
import lombok.extern.slf4j.Slf4j;
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
            List<EnrichedCandlestick> candles1D,
            List<EnrichedCandlestick> candles2H,
            List<EnrichedCandlestick> candles30m,
            List<EnrichedCandlestick> candles5m) {

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
        int maxCount = Math.max(bullishCount, bearishCount);
        double mtfAgreementScore = maxCount / 4.0;

        // Determine overall label
        int direction = bullishCount > bearishCount ? 1 : (bearishCount > bullishCount ? -1 : 0);
        RegimeLabel overallLabel = RegimeLabel.fromStrengthAndDirection(aggregatedStrength, direction);

        // Step 9: Session confidence modifier
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        SessionPhase sessionPhase = SessionPhase.fromTime(now.getHour(), now.getMinute());
        double sessionModifier = sessionPhase.getConfidenceMultiplier();

        return IndexRegime.builder()
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
                .build();
    }

    /**
     * Calculate regime data for a single timeframe
     */
    private TimeframeRegimeData calculateForTimeframe(String timeframe, List<EnrichedCandlestick> candles) {
        if (candles == null || candles.isEmpty()) {
            return TimeframeRegimeData.builder()
                    .timeframe(timeframe)
                    .regimeStrength(0)
                    .regimeCoherence(0)
                    .label(RegimeLabel.NEUTRAL)
                    .flowAgreement(0)
                    .volState(VolatilityState.NORMAL)
                    .build();
        }

        EnrichedCandlestick current = candles.get(candles.size() - 1);
        
        // Calculate ATR14
        double atr14 = calculateATR(candles, 14);
        double avgAtr = calculateAverageATR(candles, 20);
        
        // Get VWAP
        double vwap = current.getVwap() > 0 ? current.getVwap() : current.getClose();
        double close = current.getClose();

        // Step 1: VWAP Control Score
        // VWAP_control = min(|VWAP_bias|, 1.5) / 1.5
        // High deviation = high control (directional control)
        double vwapDeviation = Math.abs(close - vwap);
        double vwapControl = Math.min(vwapDeviation / (atr14 + 0.0001), 1.5) / 1.5;
        // NOTE: Per spec, high deviation = high control, so NO inversion

        // Step 2: Participation Score
        // For INDICES: buyVolume/sellVolume are 0, use price-based fallback
        double volumeDelta = current.getBuyVolume() - current.getSellVolume();
        double participation;
        
        if (Math.abs(volumeDelta) > 0) {
            // Normal case: use volume delta
            double expectedImbalance = calculateExpectedVolumeImbalance(candles);
            participation = Math.min(Math.abs(volumeDelta) / (expectedImbalance + 1), 1.5) / 1.5;
        } else {
            // INDEX FALLBACK: Use price range participation
            // Participation = how much of ATR the price moved
            double priceMove = Math.abs(current.getClose() - current.getOpen());
            double range = current.getHigh() - current.getLow();
            double rangeRatio = range / (atr14 + 0.0001);
            
            // High range = high participation (volatility expansion = institutional activity)
            participation = Math.min(rangeRatio, 1.5) / 1.5;
            
            // Boost if close near high/low (directional conviction)
            double closePosition = (current.getClose() - current.getLow()) / (range + 0.0001);
            if (closePosition > 0.7 || closePosition < 0.3) {
                participation = Math.min(participation * 1.2, 1.0);
            }
        }

        // Step 3: Flow Agreement (3-bar smoothed)
        int flowAgreement = calculateFlowAgreement(candles, 3);

        // Step 4: Volatility State
        VolatilityState volState = VolatilityState.fromATRRatio(atr14, avgAtr);
        double volScore = volState == VolatilityState.EXPANDING ? 0.8 :
                         (volState == VolatilityState.COMPRESSED ? 0.3 : 0.5);

        // Step 5: Regime Coherence = 1 - stdev(vwapControl, participation, volScore)
        double mean = (vwapControl + participation + volScore) / 3.0;
        double variance = (Math.pow(vwapControl - mean, 2) + 
                          Math.pow(participation - mean, 2) + 
                          Math.pow(volScore - mean, 2)) / 3.0;
        double stdev = Math.sqrt(variance);
        double regimeCoherence = 1 - Math.min(stdev, 1.0);

        // Step 6: Regime Strength = weighted combination
        double regimeStrength = 0.40 * vwapControl + 0.35 * participation + 0.25 * volScore;

        // Step 7: Label classification
        int direction = flowAgreement;
        RegimeLabel label = RegimeLabel.fromStrengthAndDirection(regimeStrength, direction);

        return TimeframeRegimeData.builder()
                .timeframe(timeframe)
                .vwapControl(vwapControl)
                .participation(participation)
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
     * Calculate ATR for given period
     */
    private double calculateATR(List<EnrichedCandlestick> candles, int period) {
        int actualPeriod = Math.min(period, candles.size());
        if (actualPeriod <= 1) return 0;

        double sum = 0;
        for (int i = candles.size() - actualPeriod; i < candles.size(); i++) {
            if (i < 0) continue;
            EnrichedCandlestick c = candles.get(i);
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
    private double calculateAverageATR(List<EnrichedCandlestick> candles, int period) {
        if (candles.size() < period) {
            return calculateATR(candles, candles.size());
        }
        
        // Calculate ATRs for each position and average them
        double sum = 0;
        int count = 0;
        for (int i = 14; i < candles.size(); i++) {
            List<EnrichedCandlestick> sublist = candles.subList(Math.max(0, i - 14), i + 1);
            sum += calculateATR(sublist, 14);
            count++;
        }
        return count > 0 ? sum / count : calculateATR(candles, 14);
    }

    /**
     * Calculate expected volume imbalance (average absolute delta)
     */
    private double calculateExpectedVolumeImbalance(List<EnrichedCandlestick> candles) {
        if (candles.size() < 5) return 1;
        
        double sum = 0;
        int period = Math.min(20, candles.size());
        for (int i = candles.size() - period; i < candles.size(); i++) {
            EnrichedCandlestick c = candles.get(i);
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
    private int calculateFlowAgreement(List<EnrichedCandlestick> candles, int bars) {
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
            EnrichedCandlestick c = candles.get(i);
            if (Math.abs(c.getBuyVolume() - c.getSellVolume()) > 0) {
                hasVolumeData = true;
                break;
            }
        }
        
        int sumFlowRaw = 0;
        for (int i = candles.size() - bars; i < candles.size(); i++) {
            EnrichedCandlestick c = candles.get(i);
            
            // Step 3.1: Price Sign (ATR normalized)
            double priceChangeNorm = (c.getClose() - c.getOpen()) / (atr + 0.0001);
            int priceSign = priceChangeNorm > EPSILON_PRICE ? 1 : 
                           (priceChangeNorm < -EPSILON_PRICE ? -1 : 0);
            
            int volumeSign;
            if (hasVolumeData) {
                // Normal case: use volume delta
                double volumeDelta = c.getBuyVolume() - c.getSellVolume();
                double volNorm = volumeDelta / (expectedImbalance + 1);
                volumeSign = volNorm > EPSILON_VOLUME ? 1 : 
                            (volNorm < -EPSILON_VOLUME ? -1 : 0);
            } else {
                // INDEX FALLBACK: Use close position within range as proxy
                // If close near high = bullish flow, close near low = bearish flow
                double range = c.getHigh() - c.getLow();
                if (range > 0) {
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
        double totalWeight = 0;
        double weightedSum = 0;
        
        if (tf1D != null && tf1D.getRegimeStrength() > 0) {
            weightedSum += tfWeight1D * tf1D.getRegimeStrength();
            totalWeight += tfWeight1D;
        }
        if (tf2H != null && tf2H.getRegimeStrength() > 0) {
            weightedSum += tfWeight2H * tf2H.getRegimeStrength();
            totalWeight += tfWeight2H;
        }
        if (tf30m != null && tf30m.getRegimeStrength() > 0) {
            weightedSum += tfWeight30m * tf30m.getRegimeStrength();
            totalWeight += tfWeight30m;
        }
        if (tf5m != null && tf5m.getRegimeStrength() > 0) {
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
        
        double mean = strengths.stream().mapToDouble(d -> d).average().orElse(0);
        double variance = strengths.stream().mapToDouble(d -> Math.pow(d - mean, 2)).average().orElse(0);
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
