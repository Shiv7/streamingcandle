package com.kotsin.consumer.curated.service;

import com.kotsin.consumer.curated.model.BBSuperTrendSignal;
import com.kotsin.consumer.model.UnifiedCandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BBSuperTrendDetector - Detects Bollinger Band + SuperTrend confluence signals
 * 
 * When both indicators align, produces to: bb-supertrend-signals
 * 
 * Bollinger Bands: 20-period SMA with 2 standard deviation bands
 * SuperTrend: ATR-based trend indicator (10-period ATR, multiplier 3)
 */
@Service
public class BBSuperTrendDetector {

    private static final Logger log = LoggerFactory.getLogger(BBSuperTrendDetector.class);

    @Value("${bb.period:20}")
    private int bbPeriod;

    @Value("${bb.stddev:2.0}")
    private double bbStdDev;

    @Value("${supertrend.atr.period:10}")
    private int superTrendAtrPeriod;

    @Value("${supertrend.multiplier:3.0}")
    private double superTrendMultiplier;

    @Autowired
    private KafkaTemplate<String, BBSuperTrendSignal> bbSuperTrendProducer;

    // Cache for candle history per scrip/timeframe
    private final Map<String, Deque<UnifiedCandle>> candleHistory = new ConcurrentHashMap<>();
    
    // Cache for SuperTrend state (to detect flips)
    private final Map<String, Boolean> prevSuperTrendBullish = new ConcurrentHashMap<>();
    
    // Max candles to keep
    private static final int MAX_HISTORY = 30;

    /**
     * Process a new candle and check for BB+SuperTrend confluence
     * 
     * @param candle UnifiedCandle to process
     * @return BBSuperTrendSignal if confluence detected, null otherwise
     */
    public BBSuperTrendSignal processCandle(UnifiedCandle candle) {
        if (candle == null || candle.getClose() <= 0) {
            return null;
        }

        String key = candle.getScripCode() + ":" + candle.getTimeframe();
        
        // Update history
        candleHistory.computeIfAbsent(key, k -> new LinkedList<>()).addLast(candle);
        Deque<UnifiedCandle> history = candleHistory.get(key);
        while (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }

        // Need at least 20 candles for BB calculation
        if (history.size() < bbPeriod) {
            return null;
        }

        List<UnifiedCandle> historyList = new ArrayList<>(history);
        
        // Calculate Bollinger Bands
        double[] bb = calculateBollingerBands(historyList);
        double bbUpper = bb[0];
        double bbMiddle = bb[1];
        double bbLower = bb[2];
        
        double close = candle.getClose();
        double bbWidth = bbMiddle > 0 ? (bbUpper - bbLower) / bbMiddle : 0;
        double bbPercentB = (bbUpper - bbLower) > 0 ? (close - bbLower) / (bbUpper - bbLower) : 0.5;
        
        boolean bbBreakoutUp = close > bbUpper;
        boolean bbBreakoutDown = close < bbLower;

        // Calculate SuperTrend
        double atr = calculateATR(historyList, superTrendAtrPeriod);
        double[] superTrendResult = calculateSuperTrend(historyList, atr);
        double superTrend = superTrendResult[0];
        boolean superTrendBullish = superTrendResult[1] > 0;
        
        // Check for SuperTrend flip
        Boolean prevBullish = prevSuperTrendBullish.get(key);
        boolean superTrendFlipped = prevBullish != null && prevBullish != superTrendBullish;
        prevSuperTrendBullish.put(key, superTrendBullish);

        // Calculate volume Z-score
        double volumeZScore = calculateVolumeZScore(historyList);

        // Check for confluence
        boolean bullishConfluence = bbBreakoutUp && superTrendBullish;
        boolean bearishConfluence = bbBreakoutDown && !superTrendBullish;

        if (!bullishConfluence && !bearishConfluence) {
            return null;  // No confluence
        }

        String direction = bullishConfluence ? "BULLISH" : "BEARISH";
        
        // Calculate confluence score (0-1)
        double confluenceScore = calculateConfluenceScore(
                bbBreakoutUp, bbBreakoutDown, superTrendBullish, 
                superTrendFlipped, volumeZScore, bbPercentB);
        
        // Build confluence reason
        String reason = buildConfluenceReason(direction, bbPercentB, superTrendFlipped, volumeZScore);

        BBSuperTrendSignal signal = BBSuperTrendSignal.builder()
                .scripCode(candle.getScripCode())
                .companyName(candle.getCompanyName())
                .timeframe(candle.getTimeframe())
                .timestamp(candle.getWindowEndMillis())
                .direction(direction)
                .signalStrength(confluenceScore)
                // BB data
                .bbUpper(bbUpper)
                .bbMiddle(bbMiddle)
                .bbLower(bbLower)
                .bbWidth(bbWidth)
                .bbPercentB(bbPercentB)
                .bbBreakoutUp(bbBreakoutUp)
                .bbBreakoutDown(bbBreakoutDown)
                // SuperTrend data
                .superTrend(superTrend)
                .atr(atr)
                .superTrendBullish(superTrendBullish)
                .superTrendFlipped(superTrendFlipped)
                // Price context
                .open(candle.getOpen())
                .high(candle.getHigh())
                .low(candle.getLow())
                .close(candle.getClose())
                .volume(candle.getVolume())
                .volumeZScore(volumeZScore)
                // Confluence
                .confluenceScore(confluenceScore)
                .confluenceReason(reason)
                .build();

        // Publish to Kafka
        bbSuperTrendProducer.send("bb-supertrend-signals", candle.getScripCode(), signal);

        log.info("🎯 BB+SUPERTREND CONFLUENCE | {} {} | dir={} | score={} | BB%B={} | STflip={} | volZ={}",
                candle.getScripCode(), candle.getTimeframe(),
                direction,
                String.format("%.2f", confluenceScore),
                String.format("%.2f", bbPercentB),
                superTrendFlipped,
                String.format("%.1f", volumeZScore));

        return signal;
    }

    /**
     * Calculate Bollinger Bands (20-period SMA, 2 StdDev)
     * Returns [upper, middle, lower]
     */
    private double[] calculateBollingerBands(List<UnifiedCandle> candles) {
        int period = Math.min(bbPeriod, candles.size());
        int start = candles.size() - period;
        
        // Calculate SMA
        double sum = 0;
        for (int i = start; i < candles.size(); i++) {
            sum += candles.get(i).getClose();
        }
        double sma = sum / period;
        
        // Calculate Standard Deviation
        double sqSum = 0;
        for (int i = start; i < candles.size(); i++) {
            double diff = candles.get(i).getClose() - sma;
            sqSum += diff * diff;
        }
        double stdDev = Math.sqrt(sqSum / period);
        
        double upper = sma + (bbStdDev * stdDev);
        double lower = sma - (bbStdDev * stdDev);
        
        return new double[] { upper, sma, lower };
    }

    /**
     * Calculate ATR (Average True Range)
     */
    private double calculateATR(List<UnifiedCandle> candles, int period) {
        int actualPeriod = Math.min(period, candles.size());
        if (actualPeriod <= 1) return 0;

        double sum = 0;
        for (int i = candles.size() - actualPeriod; i < candles.size(); i++) {
            if (i < 0) continue;
            UnifiedCandle c = candles.get(i);
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
     * Calculate SuperTrend
     * Returns [superTrendValue, isBullish (1 or -1)]
     */
    private double[] calculateSuperTrend(List<UnifiedCandle> candles, double atr) {
        if (candles.isEmpty() || atr <= 0) {
            return new double[] { 0, 0 };
        }

        UnifiedCandle current = candles.get(candles.size() - 1);
        double hl2 = (current.getHigh() + current.getLow()) / 2;
        
        double basicUpperBand = hl2 + (superTrendMultiplier * atr);
        double basicLowerBand = hl2 - (superTrendMultiplier * atr);
        
        // Simple SuperTrend calculation
        // In full implementation, we'd track previous upper/lower bands
        double close = current.getClose();
        
        if (close > basicUpperBand) {
            // Bullish - use lower band as support
            return new double[] { basicLowerBand, 1 };
        } else if (close < basicLowerBand) {
            // Bearish - use upper band as resistance
            return new double[] { basicUpperBand, -1 };
        } else {
            // Within bands - check trend from recent closes
            int upCount = 0, downCount = 0;
            int lookback = Math.min(5, candles.size());
            for (int i = candles.size() - lookback; i < candles.size(); i++) {
                if (i <= 0) continue;
                if (candles.get(i).getClose() > candles.get(i - 1).getClose()) {
                    upCount++;
                } else {
                    downCount++;
                }
            }
            boolean bullish = upCount >= downCount;
            return new double[] { bullish ? basicLowerBand : basicUpperBand, bullish ? 1 : -1 };
        }
    }

    /**
     * Calculate volume Z-score vs 20-period average
     */
    private double calculateVolumeZScore(List<UnifiedCandle> candles) {
        int period = Math.min(20, candles.size());
        if (period < 2) return 0;
        
        int start = candles.size() - period;
        
        // Calculate mean volume
        double sum = 0;
        for (int i = start; i < candles.size(); i++) {
            sum += candles.get(i).getVolume();
        }
        double mean = sum / period;
        
        // Calculate standard deviation
        double sqSum = 0;
        for (int i = start; i < candles.size(); i++) {
            double diff = candles.get(i).getVolume() - mean;
            sqSum += diff * diff;
        }
        double stdDev = Math.sqrt(sqSum / period);
        
        // Current volume Z-score
        long currentVolume = candles.get(candles.size() - 1).getVolume();
        return stdDev > 0 ? (currentVolume - mean) / stdDev : 0;
    }

    /**
     * Calculate confluence score (0-1)
     */
    private double calculateConfluenceScore(boolean bbBreakoutUp, boolean bbBreakoutDown,
                                            boolean superTrendBullish, boolean superTrendFlipped,
                                            double volumeZScore, double bbPercentB) {
        double score = 0;
        
        // Base score for alignment
        if ((bbBreakoutUp && superTrendBullish) || (bbBreakoutDown && !superTrendBullish)) {
            score += 0.5;
        }
        
        // Bonus for SuperTrend flip (momentum change)
        if (superTrendFlipped) {
            score += 0.15;
        }
        
        // Bonus for strong volume
        if (volumeZScore > 1.5) {
            score += 0.15;
        } else if (volumeZScore > 1.0) {
            score += 0.10;
        }
        
        // Bonus for extreme BB%B (strong breakout)
        if (bbPercentB > 1.1 || bbPercentB < -0.1) {
            score += 0.10;
        }
        
        // Cap at 1.0
        return Math.min(1.0, score);
    }

    /**
     * Build human-readable confluence reason
     */
    private String buildConfluenceReason(String direction, double bbPercentB,
                                         boolean superTrendFlipped, double volumeZScore) {
        StringBuilder sb = new StringBuilder();
        sb.append(direction).append(" confluence: ");
        
        if ("BULLISH".equals(direction)) {
            sb.append("BB breakout above upper band (B%=").append(String.format("%.2f", bbPercentB)).append(") ");
            sb.append("+ SuperTrend bullish");
        } else {
            sb.append("BB breakdown below lower band (B%=").append(String.format("%.2f", bbPercentB)).append(") ");
            sb.append("+ SuperTrend bearish");
        }
        
        if (superTrendFlipped) {
            sb.append(" + ST flip");
        }
        
        if (volumeZScore > 1.5) {
            sb.append(" + HIGH volume (Z=").append(String.format("%.1f", volumeZScore)).append(")");
        }
        
        return sb.toString();
    }
}

