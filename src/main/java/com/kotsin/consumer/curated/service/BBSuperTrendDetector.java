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

    // Cache for SuperTrend calculation state (proper band tracking)
    private final Map<String, SuperTrendState> superTrendStateCache = new ConcurrentHashMap<>();

    // Max candles to keep
    private static final int MAX_HISTORY = 30;

    // SuperTrend state record for proper band tracking
    private record SuperTrendState(
        double superTrend,
        double finalUpperBand,
        double finalLowerBand,
        boolean bullish,
        double previousClose
    ) {}

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

        // ENHANCED CONFLUENCE LOGIC (User requirement):
        // Only signal when BOTH conditions met:
        // 1. BB breakout occurs (price breaks band)
        // 2. SuperTrend FLIPS direction on SAME candle (momentum reversal)
        // 
        // BULLISH: Previous ST was bearish → NOW flips bullish + BB breakout up
        // BEARISH: Previous ST was bullish → NOW flips bearish + BB breakout down
        
        boolean bullishConfluence = bbBreakoutUp && superTrendBullish && superTrendFlipped && (prevBullish != null && !prevBullish);
        boolean bearishConfluence = bbBreakoutDown && !superTrendBullish && superTrendFlipped && (prevBullish != null && prevBullish);

        if (!bullishConfluence && !bearishConfluence) {
            return null;  // No confluence (requires BOTH flip + breakout)
        }
        
        log.debug("🔥 MOMENTUM REVERSAL DETECTED | {} {} | BB breakout {} | ST flip {} → {}",
                candle.getScripCode(), candle.getTimeframe(),
                bullishConfluence ? "UP" : "DOWN",
                prevBullish ? "BULL" : "BEAR",
                superTrendBullish ? "BULL" : "BEAR");

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
    public double[] calculateBollingerBands(List<UnifiedCandle> candles) {
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
     * Calculate ATR using Wilder's RMA (Running Moving Average) method
     * This matches TradingView's ATR calculation exactly.
     *
     * Formula:
     * - First ATR = SMA of first 'period' true ranges
     * - Subsequent ATR = (prevATR × (period-1) + currentTR) / period
     *   Or equivalently: ATR = prevATR + (currentTR - prevATR) / period
     */
    public double calculateATR(List<UnifiedCandle> candles, int period) {
        if (candles.size() < 2) return 0;

        // Calculate all True Range values
        List<Double> trueRanges = new ArrayList<>();
        for (int i = 1; i < candles.size(); i++) {
            UnifiedCandle c = candles.get(i);
            double prevClose = candles.get(i - 1).getClose();
            double tr = Math.max(c.getHigh() - c.getLow(),
                        Math.max(Math.abs(c.getHigh() - prevClose),
                                 Math.abs(c.getLow() - prevClose)));
            trueRanges.add(tr);
        }

        if (trueRanges.isEmpty()) return 0;

        // If we don't have enough data for the period, use SMA of what we have
        int actualPeriod = Math.min(period, trueRanges.size());

        // First ATR = SMA of first 'actualPeriod' true ranges
        double sum = 0;
        for (int i = 0; i < actualPeriod; i++) {
            sum += trueRanges.get(i);
        }
        double atr = sum / actualPeriod;

        // Apply Wilder's smoothing (RMA) for remaining values
        // ATR = prevATR + (currentTR - prevATR) / period
        for (int i = actualPeriod; i < trueRanges.size(); i++) {
            atr = atr + (trueRanges.get(i) - atr) / period;
        }

        return atr;
    }

    /**
     * Calculate SuperTrend (backward compatible - derives state key from candle data)
     * Returns [superTrendValue, isBullish (1 or -1)]
     */
    public double[] calculateSuperTrend(List<UnifiedCandle> candles, double atr) {
        if (candles.isEmpty()) {
            return new double[] { 0, 0 };
        }
        // Derive state key from candle data for backward compatibility
        UnifiedCandle current = candles.get(candles.size() - 1);
        String stateKey = current.getScripCode() + ":" + current.getTimeframe();
        return calculateSuperTrend(candles, atr, stateKey);
    }

    /**
     * Calculate SuperTrend with proper state tracking (TradingView standard formula)
     *
     * Key insight: Bands should RESET when price crosses through them.
     * - Upper band resets if: basicUpperBand < prevUpperBand OR prevClose > prevUpperBand
     * - Lower band resets if: basicLowerBand > prevLowerBand OR prevClose < prevLowerBand
     *
     * @param candles Price history
     * @param atr Current ATR value
     * @param stateKey Unique key for state tracking (e.g., scripCode + timeframe)
     * @return [superTrendValue, direction (1=bullish, -1=bearish)]
     */
    public double[] calculateSuperTrend(List<UnifiedCandle> candles, double atr, String stateKey) {
        if (candles.isEmpty() || atr <= 0) {
            return new double[] { 0, 0 };
        }

        UnifiedCandle current = candles.get(candles.size() - 1);
        double high = current.getHigh();
        double low = current.getLow();
        double close = current.getClose();
        double hl2 = (high + low) / 2;

        double basicUpperBand = hl2 + (superTrendMultiplier * atr);
        double basicLowerBand = hl2 - (superTrendMultiplier * atr);

        // Get previous state
        SuperTrendState prev = superTrendStateCache.get(stateKey);

        double finalUpperBand;
        double finalLowerBand;
        double superTrend;
        boolean bullish;

        if (prev == null) {
            // First calculation - initialize with basic bands
            finalUpperBand = basicUpperBand;
            finalLowerBand = basicLowerBand;
            bullish = close > hl2;
            superTrend = bullish ? finalLowerBand : finalUpperBand;
        } else {
            // Upper Band: reset if basicUB < prevUB OR prevClose crossed above prevUB
            if (basicUpperBand < prev.finalUpperBand || prev.previousClose > prev.finalUpperBand) {
                finalUpperBand = basicUpperBand;
            } else {
                finalUpperBand = prev.finalUpperBand;
            }

            // Lower Band: reset if basicLB > prevLB OR prevClose crossed below prevLB
            if (basicLowerBand > prev.finalLowerBand || prev.previousClose < prev.finalLowerBand) {
                finalLowerBand = basicLowerBand;
            } else {
                finalLowerBand = prev.finalLowerBand;
            }

            // Determine direction based on previous SuperTrend value
            if (prev.superTrend == prev.finalUpperBand) {
                // Was bearish (ST was at upper band)
                if (close > finalUpperBand) {
                    bullish = true;
                    superTrend = finalLowerBand;
                } else {
                    bullish = false;
                    superTrend = finalUpperBand;
                }
            } else {
                // Was bullish (ST was at lower band)
                if (close < finalLowerBand) {
                    bullish = false;
                    superTrend = finalUpperBand;
                } else {
                    bullish = true;
                    superTrend = finalLowerBand;
                }
            }
        }

        // Store new state
        superTrendStateCache.put(stateKey, new SuperTrendState(
            superTrend, finalUpperBand, finalLowerBand, bullish, close
        ));

        return new double[] { superTrend, bullish ? 1 : -1 };
    }

    /**
     * Calculate volume Z-score vs 20-period average
     */
    public double calculateVolumeZScore(List<UnifiedCandle> candles) {
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
        sb.append(direction).append(" REVERSAL: ");
        
        if ("BULLISH".equals(direction)) {
            sb.append("BB breakout above upper (B%=").append(String.format("%.2f", bbPercentB)).append(") ");
            sb.append("+ ST FLIP bearish→bullish");
        } else {
            sb.append("BB breakdown below lower (B%=").append(String.format("%.2f", bbPercentB)).append(") ");
            sb.append("+ ST FLIP bullish→bearish");
        }
        
        // SuperTrend flip is now REQUIRED, not optional
        if (!superTrendFlipped) {
            sb.append(" [WARNING: Should not happen - flip required]");
        }
        
        if (volumeZScore > 1.5) {
            sb.append(" + HIGH volume (Z=").append(String.format("%.1f", volumeZScore)).append(")");
        }
        
        return sb.toString();
    }
}


