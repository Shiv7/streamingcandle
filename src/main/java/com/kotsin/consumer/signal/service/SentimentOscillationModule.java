package com.kotsin.consumer.signal.service;

import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.signal.model.SOMOutput;
import com.kotsin.consumer.signal.model.SOMOutput.SentimentState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SentimentOscillationModule - Module 9: SOM
 * 
 * Detects choppy, indecisive market conditions:
 * 1. VWAP Failure Rate - how often price crosses then fails at VWAP
 * 2. Wick Rejection Score - high wicks indicate rejection/indecision
 * 3. Choppiness Index - based on directional movement vs range
 * 4. Trap Detection - identifies failed breakouts
 * 
 * Output: SOM score from -1 (extreme chop) to +1 (clean trend)
 */
@Slf4j
@Service
public class SentimentOscillationModule {

    @Value("${som.lookback.period:20}")
    private int lookbackPeriod;

    @Value("${som.wick.threshold:0.5}")
    private double wickThreshold;

    @Value("${som.choppiness.threshold:0.6}")
    private double choppinessThreshold;

    /**
     * Calculate SOM from candle history
     */
    public SOMOutput calculate(
            String scripCode,
            String companyName,
            List<UnifiedCandle> candles) {

        if (candles == null || candles.size() < 10) {
            return emptyResult(scripCode, companyName);
        }

        UnifiedCandle current = candles.get(candles.size() - 1);
        int period = Math.min(lookbackPeriod, candles.size());

        // 1. VWAP Failure Score
        double vwapFailureScore = calculateVwapFailureScore(candles, period);

        // 2. Wick Rejection Score
        double wickRejectionScore = calculateWickRejectionScore(candles, period);

        // 3. Choppiness Index
        double choppinessIndex = calculateChoppinessIndex(candles, period);

        // 4. Trap Detection
        boolean bullTrapDetected = detectBullTrap(candles);
        boolean bearTrapDetected = detectBearTrap(candles);
        double trapScore = (bullTrapDetected ? 0.3 : 0) + (bearTrapDetected ? 0.3 : 0);

        // Calculate composite SOM score
        // High wick = choppy, high choppiness = choppy, VWAP failures = choppy
        double negativeFactors = 0.30 * vwapFailureScore 
                              + 0.35 * wickRejectionScore 
                              + 0.25 * choppinessIndex
                              + 0.10 * trapScore;

        // SOM score: +1 = clean trend, -1 = extreme chop
        double somScore = 1 - (2 * negativeFactors);
        somScore = Math.max(-1, Math.min(1, somScore));

        // Determine sentiment state
        SentimentState sentimentState = SentimentState.fromScore(somScore);

        // Calculate penalty (0 to 0.5)
        double somPenalty = sentimentState.getPenalty();

        return SOMOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(System.currentTimeMillis())
                .somScore(somScore)
                .somPenalty(somPenalty)
                .vwapFailureScore(vwapFailureScore)
                .wickRejectionScore(wickRejectionScore)
                .choppinessIndex(choppinessIndex)
                .trapScore(trapScore)
                .bullTrapDetected(bullTrapDetected)
                .bearTrapDetected(bearTrapDetected)
                .sentimentState(sentimentState)
                .build();
    }

    /**
     * Calculate VWAP failure score
     * Counts how often price crosses VWAP then reverses
     */
    private double calculateVwapFailureScore(List<UnifiedCandle> candles, int period) {
        int failures = 0;
        int crossings = 0;

        for (int i = candles.size() - period + 1; i < candles.size(); i++) {
            if (i <= 0) continue;
            
            UnifiedCandle prev = candles.get(i - 1);
            UnifiedCandle curr = candles.get(i);
            
            double prevVwap = prev.getVwap() > 0 ? prev.getVwap() : prev.getClose();
            double currVwap = curr.getVwap() > 0 ? curr.getVwap() : curr.getClose();

            // Check for VWAP cross
            boolean crossedUp = prev.getClose() < prevVwap && curr.getClose() > currVwap;
            boolean crossedDown = prev.getClose() > prevVwap && curr.getClose() < currVwap;

            if (crossedUp || crossedDown) {
                crossings++;
                
                // Check if it failed (reversed in next candle)
                if (i + 1 < candles.size()) {
                    UnifiedCandle next = candles.get(i + 1);
                    double nextVwap = next.getVwap() > 0 ? next.getVwap() : next.getClose();
                    
                    if (crossedUp && next.getClose() < nextVwap) failures++;
                    if (crossedDown && next.getClose() > nextVwap) failures++;
                }
            }
        }

        return crossings > 0 ? (double) failures / crossings : 0;
    }

    /**
     * Calculate wick rejection score
     * High wicks relative to body = indecision
     */
    private double calculateWickRejectionScore(List<UnifiedCandle> candles, int period) {
        double totalWickRatio = 0;

        for (int i = candles.size() - period; i < candles.size(); i++) {
            if (i < 0) continue;
            
            UnifiedCandle c = candles.get(i);
            double range = c.getHigh() - c.getLow();
            if (range <= 0) continue;

            double body = Math.abs(c.getClose() - c.getOpen());
            double upperWick = c.getHigh() - Math.max(c.getOpen(), c.getClose());
            double lowerWick = Math.min(c.getOpen(), c.getClose()) - c.getLow();
            double totalWick = upperWick + lowerWick;

            double wickRatio = totalWick / range;
            totalWickRatio += wickRatio;
        }

        return Math.min(totalWickRatio / period, 1.0);
    }

    /**
     * Calculate Choppiness Index
     * Based on ATR sum vs directional movement
     */
    private double calculateChoppinessIndex(List<UnifiedCandle> candles, int period) {
        if (candles.size() < period + 1) return 0.5;

        double sumTR = 0;
        double highestHigh = 0;
        double lowestLow = Double.MAX_VALUE;

        for (int i = candles.size() - period; i < candles.size(); i++) {
            if (i < 0) continue;
            
            UnifiedCandle c = candles.get(i);
            double tr = c.getHigh() - c.getLow();
            
            if (i > 0) {
                double prevClose = candles.get(i - 1).getClose();
                tr = Math.max(tr, Math.abs(c.getHigh() - prevClose));
                tr = Math.max(tr, Math.abs(c.getLow() - prevClose));
            }
            
            sumTR += tr;
            highestHigh = Math.max(highestHigh, c.getHigh());
            lowestLow = Math.min(lowestLow, c.getLow());
        }

        double channelRange = highestHigh - lowestLow;
        if (channelRange <= 0 || sumTR <= 0) return 0.5;

        // Choppiness Index: 100 * LOG10(SUM(TR,n) / (Highest High - Lowest Low)) / LOG10(n)
        double choppiness = 100 * Math.log10(sumTR / channelRange) / Math.log10(period);
        
        // Normalize to 0-1 (typical CI ranges 0-100, with 61.8 as threshold)
        return Math.min(Math.max(choppiness / 100, 0), 1);
    }

    /**
     * Detect bull trap (price breaks above resistance then crashes)
     */
    private boolean detectBullTrap(List<UnifiedCandle> candles) {
        if (candles.size() < 5) return false;

        // Look for pattern: breakout high followed by lower closes
        int lookback = Math.min(5, candles.size());
        UnifiedCandle peak = candles.get(candles.size() - lookback);
        double peakHigh = peak.getHigh();

        // Find if any candle broke to new high then reversed
        for (int i = candles.size() - lookback + 1; i < candles.size() - 1; i++) {
            UnifiedCandle c = candles.get(i);
            if (c.getHigh() > peakHigh * 1.005) {  // 0.5% new high
                // Check if next candles are lower
                UnifiedCandle next = candles.get(i + 1);
                if (next.getClose() < c.getLow()) {
                    return true;
                }
            }
            peakHigh = Math.max(peakHigh, c.getHigh());
        }
        return false;
    }

    /**
     * Detect bear trap (price breaks below support then rallies)
     */
    private boolean detectBearTrap(List<UnifiedCandle> candles) {
        if (candles.size() < 5) return false;

        int lookback = Math.min(5, candles.size());
        UnifiedCandle trough = candles.get(candles.size() - lookback);
        double troughLow = trough.getLow();

        for (int i = candles.size() - lookback + 1; i < candles.size() - 1; i++) {
            UnifiedCandle c = candles.get(i);
            if (c.getLow() < troughLow * 0.995) {  // 0.5% new low
                UnifiedCandle next = candles.get(i + 1);
                if (next.getClose() > c.getHigh()) {
                    return true;
                }
            }
            troughLow = Math.min(troughLow, c.getLow());
        }
        return false;
    }

    private SOMOutput emptyResult(String scripCode, String companyName) {
        return SOMOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(System.currentTimeMillis())
                .somScore(0)
                .somPenalty(0.25)
                .sentimentState(SentimentState.CHOPPY)
                .build();
    }
}
