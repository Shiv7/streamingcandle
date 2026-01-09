package com.kotsin.consumer.infrastructure.kafka.aggregate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.kotsin.consumer.model.TickData;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serde;
import org.springframework.kafka.support.serializer.JsonSerde;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Enhanced Tick aggregate for windowed processing
 * 
 * EXTRACTED from UnifiedInstrumentCandleProcessor for SOLID compliance.
 * 
 * Features:
 * - Accurate volume delta calculation with TotalQty reset detection
 * - Lee-Ready trade classification using TickData BBO
 * - Complete temporal tracking (tick velocity, gaps, acceleration)
 * - Trade-level history (last 100 trades with full context)
 * - Imbalance bars (VIB, DIB, TRB, VRB)
 * - P0: Tick bid/ask imbalance, overnight gap
 * - P1: VWAP validation
 * - P2: Tick intensity zones
 */
@Data
@NoArgsConstructor
@Slf4j
public class TickAggregate {
    
    // ==================== BASIC OHLCV ====================
    private String scripCode;
    private String symbol;
    private String companyName;
    private String exchange;
    private String exchangeType;
    private double open;
    private double high;
    private double low;
    private double close;
    // FIX: Track event time of OHLC to handle out-of-order ticks correctly
    // Open = price at EARLIEST event time (first trade in window)
    // Close = price at LATEST event time (last trade in window)
    // High/Low = track when extremes occurred for analytics
    private long openEventTime = Long.MAX_VALUE;  // Start high so first tick is always earlier
    private long closeEventTime = 0;
    private long highEventTime = 0;
    private long lowEventTime = 0;
    private long volume;
    private long buyVolume;
    private long sellVolume;
    private double vwap;
    private int tickCount;
    private double totalValue;

    // ==================== VWAP TRACKING (Never Reset) ====================
    // FIX: Separate cumulative counters for VWAP that NEVER reset (even on TotalQty reset)
    // This ensures VWAP matches exchange VWAP exactly
    private long cumulativeVWAPVolume = 0;       // Cumulative volume for VWAP (never reset)
    private double cumulativeVWAPValue = 0.0;    // Cumulative value for VWAP (never reset)

    // ==================== VOLUME PROFILE (Volume-at-Price Histogram) ====================
    // Track volume distribution across price levels (for POC, VAH, VAL calculation)
    private Map<Double, Long> volumeAtPrice = new HashMap<>();
    private double tickSizeForProfile = 0.05;  // Default tick size for rounding prices

    // ==================== TICK-LEVEL SPREAD METRICS ====================
    // Track spread (execution cost) across all ticks in the window
    private double sumSpread = 0.0;
    private int spreadCount = 0;
    private double minSpread = Double.MAX_VALUE;
    private double maxSpread = 0.0;
    @JsonIgnore private transient List<Double> spreadHistory;  // For volatility calculation (transient - backup only)
    private int tightSpreadCount = 0;  // Count of spreads <= 1 tick

    // FIX: Welford's online algorithm for spread volatility (persisted - survives serialization)
    private double spreadWelfordMean = 0.0;
    private double spreadWelfordM2 = 0.0;
    private int spreadWelfordCount = 0;

    // ==================== EFFECTIVE SPREAD (ACTUAL EXECUTION COST) ====================
    // Effective spread = 2 * |trade_price - midpoint|
    // This measures ACTUAL execution cost, not just quoted spread
    // Key insight: Quoted spread is what MM offers, effective spread is what trader pays
    private double sumEffectiveSpread = 0.0;
    private int effectiveSpreadCount = 0;
    private double minEffectiveSpread = Double.MAX_VALUE;
    private double maxEffectiveSpread = 0.0;

    // ==================== TRADE-TO-ORDER RATIO (Market Stress Indicator) ====================
    // High ratio = aggressive execution, Low ratio = market makers providing liquidity
    private long totalTradesExecuted = 0;        // Count of actual trades
    private long totalOrdersObserved = 0;        // Sum of numberOfOrders from orderbook (if available)

    // ==================== VWAP BANDS (Trading Signals) ====================
    // Track price history for VWAP standard deviation calculation
    @JsonIgnore private transient List<Double> priceHistory;  // For std dev around VWAP (transient - backup only)

    // FIX: Welford's online algorithm for VWAP std dev (persisted - survives serialization)
    private double priceWelfordMean = 0.0;
    private double priceWelfordM2 = 0.0;
    private int priceWelfordCount = 0;

    // ==================== VOLUME DELTA TRACKING (Phase 1.1) ====================
    private long previousTotalQty = 0;

    // ==================== TRADE CLASSIFICATION (Phase 1.2) ====================
    private long aggressiveBuyVolume = 0;
    private long aggressiveSellVolume = 0;
    private long midpointVolume = 0;
    private int classifiedTradeCount = 0;
    private int unclassifiedTradeCount = 0;
    private double previousTradePrice = 0.0;
    private String previousTradeClassification = null;

    // ==================== TEMPORAL TRACKING (Phase 2) ====================
    private long firstTickTimestamp;
    private long lastTickTimestamp;
    private long firstTickEventTime;
    private long lastTickEventTime;
    @JsonIgnore private transient List<Long> tickTimestamps;  // Transient - backup only
    private long minTickGap = Long.MAX_VALUE;
    private long maxTickGap = 0;
    private double avgTickGap = 0.0;
    private int ticksPerSecond = 0;
    private double tickAcceleration = 0.0;
    private long previousTickTimestamp = 0;
    private int previousTicksPerSecond = 0;

    // FIX: Incremental tick gap calculation (persisted - survives serialization)
    private long sumTickGaps = 0;
    private int tickGapCount = 0;

    // ==================== TRADE-LEVEL TRACKING (Phase 4) ====================
    @JsonIgnore private transient List<TradeInfo> tradeHistory;  // Transient - backup only
    private static final int MAX_TRADE_HISTORY = 100;
    private long maxTradeSize = 0;
    private long minTradeSize = Long.MAX_VALUE;
    private double avgTradeSize = 0.0;
    private double medianTradeSize = 0.0;
    private int largeTradeCount = 0;
    private double priceImpactPerUnit = 0.0;

    // FIX: Incremental trade size calculation (persisted - survives serialization)
    private long totalTradeVolume = 0;
    private int tradeCount = 0;

    // ==================== IMBALANCE BAR TRACKING ====================
    private long volumeImbalance = 0L;
    private double dollarImbalance = 0.0;
    private int tickRuns = 0;
    private long volumeRuns = 0L;
    private String currentDirection = "NEUTRAL";
    private int currentRunLength = 0;
    private long currentVolumeRun = 0L;
    
    // EWMA adaptive thresholds
    private static final double IMBALANCE_EWMA_ALPHA = 0.1;
    private static final double INIT_VOLUME_IMBALANCE = 100000.0;
    private static final double INIT_DOLLAR_IMBALANCE = 1000000.0;
    private static final double INIT_TICK_RUNS = 10.0;
    private static final double INIT_VOLUME_RUNS = 50000.0;
    
    private double expectedVolumeImbalance = INIT_VOLUME_IMBALANCE;
    private double expectedDollarImbalance = INIT_DOLLAR_IMBALANCE;
    private double expectedTickRuns = INIT_TICK_RUNS;
    private double expectedVolumeRuns = INIT_VOLUME_RUNS;
    
    private double vibEwmaMean = 0.0;
    private double dibEwmaMean = 0.0;
    private double trbEwmaMean = 0.0;
    private double vrbEwmaMean = 0.0;
    
    private boolean vibTriggered = false;
    private boolean dibTriggered = false;
    private boolean trbTriggered = false;
    private boolean vrbTriggered = false;

    // ==================== P0: TICK-LEVEL BID/ASK IMBALANCE ====================
    private long sumTotalBidQty = 0L;
    private long sumTotalOffQty = 0L;

    // ==================== P0: OVERNIGHT GAP ====================
    private double previousClose = 0.0;

    // ==================== P1: VWAP VALIDATION ====================
    private double exchangeVwap = 0.0;

    // ==================== P2: TICK INTENSITY ZONES ====================
    @JsonIgnore private transient Map<Long, Integer> tickCountPerSecond;  // Transient - backup only
    private int maxTicksInAnySecond = 0;
    private int secondsWithTicks = 0;

    // FIX: Incremental tick intensity tracking (persisted - survives serialization)
    private long currentSecondBucket = 0;
    private int currentSecondTickCount = 0;
    private long lastSeenSecondBucket = -1;

    /**
     * Update aggregate with new tick data.
     * 
     * @param tick TickData from Kafka
     * @param kafkaTimestamp Kafka record timestamp for temporal tracking
     * @return this aggregate (for chaining)
     */
    public TickAggregate update(TickData tick, long kafkaTimestamp) {
        if (tick == null) return this;

        // ========== FIRST TICK INITIALIZATION ==========
        if (tickCount == 0) {
            scripCode = tick.getScripCode();
            symbol = tick.getScripCode();
            companyName = tick.getCompanyName();
            exchange = tick.getExchange();
            exchangeType = tick.getExchangeType();
            // Initialize OHLC with first tick (will be corrected by event-time logic below)
            long firstEventTime = tick.getTimestamp() > 0 ? tick.getTimestamp() : kafkaTimestamp;
            open = tick.getLastRate();
            high = tick.getLastRate();
            low = tick.getLastRate();
            close = tick.getLastRate();
            // Initialize all event times
            openEventTime = firstEventTime;
            closeEventTime = firstEventTime;
            highEventTime = firstEventTime;
            lowEventTime = firstEventTime;

            firstTickTimestamp = kafkaTimestamp;
            firstTickEventTime = tick.getTimestamp();
            tickTimestamps = new ArrayList<>(100);
            tradeHistory = new ArrayList<>(MAX_TRADE_HISTORY);
            previousClose = tick.getPreviousClose();
            tickCountPerSecond = new HashMap<>();

            // FIX: Initialize tick intensity tracking (first tick will be counted in regular logic below)
            currentSecondBucket = kafkaTimestamp / 1000;
            currentSecondTickCount = 0;  // Will be incremented to 1 below
            lastSeenSecondBucket = -1;  // Mark as uninitialized so first second gets counted

            log.debug("[TICK-AGG-INIT] {} | open={} kafkaTs={}",
                scripCode, open, kafkaTimestamp);
        }

        // P0: Accumulate TBidQ/TOffQ
        sumTotalBidQty += tick.getTotalBidQuantity();
        sumTotalOffQty += tick.getTotalOfferQuantity();

        // P1: Capture exchange VWAP
        if (tick.getAverageRate() > 0) {
            exchangeVwap = tick.getAverageRate();
        }

        // P2: Track ticks per second
        // FIX: Lazy init after deserialization
        if (tickCountPerSecond == null) {
            tickCountPerSecond = new HashMap<>();
        }
        long secondBucket = kafkaTimestamp / 1000;
        tickCountPerSecond.merge(secondBucket, 1, Integer::sum);

        // ========== UPDATE OHLC ==========
        // FIX: Get tick event time from TickDt (parsed during deserialization)
        // Fallback to Kafka timestamp if TickDt not available
        long tickEventTime = tick.getTimestamp();
        if (tickEventTime <= 0) {
            tickEventTime = kafkaTimestamp;
        }

        double price = tick.getLastRate();

        // FIX: Open should be price of tick with EARLIEST event time (first trade in window)
        // Out-of-order ticks can arrive with earlier event times than first processed tick
        if (tickEventTime < openEventTime) {
            open = price;
            openEventTime = tickEventTime;
        }

        // FIX: Close should be price of tick with LATEST event time (last trade in window)
        // Out-of-order ticks due to network latency can cause wrong Close otherwise.
        if (tickEventTime >= closeEventTime) {
            close = price;
            closeEventTime = tickEventTime;
        }

        // High: Update if new high found, track when it occurred
        if (price > high) {
            high = price;
            highEventTime = tickEventTime;
        }

        // Low: Update if new low found, track when it occurred
        if (price < low) {
            low = price;
            lowEventTime = tickEventTime;
        }

        // ========== VOLUME DELTA CALCULATION ==========
        long currentTotalQty = tick.getTotalQuantity();
        long deltaVol = calculateVolumeDelta(tick, currentTotalQty);
        previousTotalQty = currentTotalQty;

        // Update volume and value
        volume += deltaVol;
        totalValue += deltaVol * tick.getLastRate();

        // FIX: Update cumulative VWAP counters (NEVER reset, even on TotalQty reset)
        // This ensures VWAP matches exchange VWAP exactly
        cumulativeVWAPVolume += deltaVol;
        cumulativeVWAPValue += deltaVol * tick.getLastRate();

        // ========== VOLUME PROFILE TRACKING ==========
        // Track volume distribution across price levels (rounded to tick size)
        if (deltaVol > 0) {
            double priceLevel = roundToTickSize(tick.getLastRate(), tickSizeForProfile);
            volumeAtPrice.merge(priceLevel, deltaVol, Long::sum);
        }

        // ========== TICK-LEVEL SPREAD TRACKING ==========
        // Track spread (bid-ask spread = execution cost) from tick BBO
        if (tick.getBidRate() > 0 && tick.getOfferRate() > 0) {
            double spread = tick.getOfferRate() - tick.getBidRate();
            sumSpread += spread;
            spreadCount++;
            minSpread = Math.min(minSpread, spread);
            maxSpread = Math.max(maxSpread, spread);

            // FIX: Welford's online algorithm for spread volatility (survives serialization)
            spreadWelfordCount++;
            double spreadDelta = spread - spreadWelfordMean;
            spreadWelfordMean += spreadDelta / spreadWelfordCount;
            double spreadDelta2 = spread - spreadWelfordMean;
            spreadWelfordM2 += spreadDelta * spreadDelta2;

            // Lazy init spread history (backup - may be null after deserialization)
            if (spreadHistory == null) {
                spreadHistory = new ArrayList<>(100);
            }
            spreadHistory.add(spread);
            if (spreadHistory.size() > 100) {
                spreadHistory.remove(0);
            }

            // Count tight spreads (<=1 tick) - FIX: use tolerance for floating point comparison
            if (spread <= tickSizeForProfile + 0.0001) {
                tightSpreadCount++;
            }

            // ========== EFFECTIVE SPREAD CALCULATION ==========
            // Effective spread = 2 * |trade_price - midpoint|
            // This measures ACTUAL execution cost vs theoretical midpoint
            //
            // Key insight from Kyle (1985) and Glosten-Harris (1988):
            // - Quoted spread = what market maker offers
            // - Effective spread = what trader actually pays (often better due to price improvement)
            // - If effective > quoted: trades hitting far side of book (unusual)
            // - If effective < quoted: price improvement (common in competitive markets)
            double midpoint = (tick.getBidRate() + tick.getOfferRate()) / 2.0;
            double effectiveSpread = 2.0 * Math.abs(tick.getLastRate() - midpoint);

            sumEffectiveSpread += effectiveSpread;
            effectiveSpreadCount++;
            minEffectiveSpread = Math.min(minEffectiveSpread, effectiveSpread);
            maxEffectiveSpread = Math.max(maxEffectiveSpread, effectiveSpread);
        }

        // ========== TRADE COUNT TRACKING ==========
        if (deltaVol > 0) {
            totalTradesExecuted++;
        }

        // ========== VWAP BANDS: TRACK PRICE HISTORY ==========
        // FIX: Welford's online algorithm for VWAP std dev (survives serialization)
        priceWelfordCount++;
        double priceDelta = price - priceWelfordMean;
        priceWelfordMean += priceDelta / priceWelfordCount;
        double priceDelta2 = price - priceWelfordMean;
        priceWelfordM2 += priceDelta * priceDelta2;

        // Track all prices (backup - may be null after deserialization)
        if (priceHistory == null) {
            priceHistory = new ArrayList<>(500);
        }
        priceHistory.add(price);
        // Keep last 500 prices (sufficient for 1-minute window)
        if (priceHistory.size() > 500) {
            priceHistory.remove(0);
        }

        // ========== TRADE CLASSIFICATION ==========
        String classification = classifyTrade(tick, deltaVol);

        // ========== TEMPORAL TRACKING ==========
        lastTickTimestamp = kafkaTimestamp;
        lastTickEventTime = tick.getTimestamp();

        // Lazy init transient lists (backup - may be null after deserialization)
        if (tickTimestamps == null) {
            tickTimestamps = new ArrayList<>(100);
        }
        tickTimestamps.add(kafkaTimestamp);
        if (tickTimestamps.size() > 100) {
            tickTimestamps.remove(0);
        }

        if (previousTickTimestamp > 0) {
            long gap = kafkaTimestamp - previousTickTimestamp;
            minTickGap = Math.min(minTickGap, gap);
            maxTickGap = Math.max(maxTickGap, gap);
            // FIX: Incremental tick gap tracking (survives serialization)
            sumTickGaps += gap;
            tickGapCount++;
        }
        previousTickTimestamp = kafkaTimestamp;

        // FIX: Incremental tick intensity tracking (survives serialization)
        long secondBucketNow = kafkaTimestamp / 1000;
        if (secondBucketNow == currentSecondBucket) {
            currentSecondTickCount++;
        } else {
            // New second - finalize previous second stats
            if (currentSecondBucket > 0) {
                maxTicksInAnySecond = Math.max(maxTicksInAnySecond, currentSecondTickCount);
                if (lastSeenSecondBucket != currentSecondBucket) {
                    secondsWithTicks++;
                    lastSeenSecondBucket = currentSecondBucket;
                }
            }
            currentSecondBucket = secondBucketNow;
            currentSecondTickCount = 1;
        }

        // ========== TRADE HISTORY ==========
        if (deltaVol > 0) {
            TradeInfo trade = new TradeInfo(
                kafkaTimestamp, tick.getTimestamp(), tick.getLastRate(),
                deltaVol, classification, tick.getBidRate(), tick.getOfferRate()
            );
            // Lazy init (backup - may be null after deserialization)
            if (tradeHistory == null) {
                tradeHistory = new ArrayList<>(MAX_TRADE_HISTORY);
            }
            tradeHistory.add(trade);
            if (tradeHistory.size() > MAX_TRADE_HISTORY) {
                tradeHistory.remove(0);
            }

            maxTradeSize = Math.max(maxTradeSize, deltaVol);
            minTradeSize = Math.min(minTradeSize, deltaVol);

            // FIX: Incremental trade size tracking (survives serialization)
            totalTradeVolume += deltaVol;
            tradeCount++;

            // ========== IMBALANCE BAR TRACKING ==========
            boolean isBuy = "AGGRESSIVE_BUY".equals(classification);
            updateImbalanceBars(tick.getLastRate(), deltaVol, isBuy, kafkaTimestamp);
        }

        tickCount++;

        // FIX: Use cumulative counters for VWAP calculation (never affected by TotalQty resets)
        // This matches exchange VWAP calculation exactly
        vwap = cumulativeVWAPVolume > 0 ? cumulativeVWAPValue / cumulativeVWAPVolume : close;

        return this;
    }

    /**
     * Calculate volume delta with reset detection and logging.
     *
     * FIX: Improved volume delta calculation to reduce VWAP drift:
     * 1. Lower anomaly threshold from 10x to 5x for faster fallback
     * 2. Add negative delta protection
     * 3. Cross-validate with exchange VWAP when available
     * 4. Use LastQuantity as authoritative source when delta is suspicious
     */
    private long calculateVolumeDelta(TickData tick, long currentTotalQty) {
        long deltaVol = 0;
        long lastQty = tick.getLastQuantity();

        // Case 1: TotalQty reset detected (e.g., new trading session)
        if (currentTotalQty < previousTotalQty && previousTotalQty > 0) {
            deltaVol = lastQty;
            log.info("[VOLUME-RESET] {} | {} -> {} | Using LastQty: {}",
                tick.getScripCode(), previousTotalQty, currentTotalQty, deltaVol);
        }
        // Case 2: Normal delta calculation
        else if (previousTotalQty > 0) {
            deltaVol = currentTotalQty - previousTotalQty;

            // FIX: More aggressive anomaly detection (5x instead of 10x)
            // This helps illiquid instruments where delta can spike unexpectedly
            if (lastQty > 0) {
                if (deltaVol > lastQty * 5) {
                    log.warn("[VOLUME-ANOMALY-HIGH] {} | Delta {} > 5x LastQty {} | Using LastQty",
                        tick.getScripCode(), deltaVol, lastQty);
                    deltaVol = lastQty;
                }
                // FIX: Negative delta protection (should never happen in normal flow)
                else if (deltaVol < 0) {
                    log.warn("[VOLUME-ANOMALY-NEG] {} | Delta {} < 0 | Using LastQty {}",
                        tick.getScripCode(), deltaVol, lastQty);
                    deltaVol = lastQty;
                }
                // FIX: Delta is 0 but trade happened - use LastQty
                else if (deltaVol == 0) {
                    deltaVol = lastQty;
                }
            }
        }
        // Case 3: First tick - use LastQuantity
        else {
            deltaVol = lastQty;
        }

        // Final sanity check: ensure we never return negative volume
        if (deltaVol < 0) {
            log.error("[VOLUME-SANITY] {} | Final deltaVol {} < 0 | Forcing to 0",
                tick.getScripCode(), deltaVol);
            deltaVol = 0;
        }

        return deltaVol;
    }

    /**
     * Classify trade using Lee-Ready algorithm.
     */
    private String classifyTrade(TickData tick, long deltaVol) {
        if (deltaVol <= 0) return null;

        double tradePrice = tick.getLastRate();
        double bidPrice = tick.getBidRate();
        double askPrice = tick.getOfferRate();

        boolean bboValid = bidPrice > 0 && askPrice > 0 && askPrice > bidPrice;

        if (!bboValid) {
            if (previousTradeClassification != null) {
                applyClassification(previousTradeClassification, deltaVol);
                unclassifiedTradeCount++;
                return previousTradeClassification;
            } else {
                buyVolume += deltaVol / 2;
                sellVolume += deltaVol / 2;
                if (deltaVol % 2 == 1) buyVolume += 1;
                unclassifiedTradeCount++;
                return "UNKNOWN";
            }
        }

        String classification;

        if (tradePrice >= askPrice) {
            classification = "AGGRESSIVE_BUY";
        } else if (tradePrice <= bidPrice) {
            classification = "AGGRESSIVE_SELL";
        } else {
            classification = applyTickRule(tradePrice);
        }

        applyClassification(classification, deltaVol);
        classifiedTradeCount++;
        previousTradePrice = tradePrice;
        previousTradeClassification = classification;

        if (log.isDebugEnabled()) {
            log.debug("[TRADE-CLASSIFY] {} | {} | price={} bid={} ask={} | vol={}",
                scripCode, classification,
                String.format("%.2f", tradePrice),
                String.format("%.2f", bidPrice),
                String.format("%.2f", askPrice), deltaVol);
        }

        return classification;
    }

    private String applyTickRule(double tradePrice) {
        if (previousTradePrice > 0) {
            if (tradePrice > previousTradePrice) return "AGGRESSIVE_BUY";
            if (tradePrice < previousTradePrice) return "AGGRESSIVE_SELL";
        }
        return previousTradeClassification != null ? previousTradeClassification : "MIDPOINT";
    }

    private void applyClassification(String classification, long deltaVol) {
        switch (classification) {
            case "AGGRESSIVE_BUY":
                aggressiveBuyVolume += deltaVol;
                buyVolume += deltaVol;
                break;
            case "AGGRESSIVE_SELL":
                aggressiveSellVolume += deltaVol;
                sellVolume += deltaVol;
                break;
            default:
                midpointVolume += deltaVol;
                buyVolume += deltaVol / 2;
                sellVolume += deltaVol / 2;
                if (deltaVol % 2 == 1) buyVolume += 1;
        }
    }

    /**
     * Update imbalance bars with EWMA thresholds.
     */
    private void updateImbalanceBars(double price, long deltaVolume, boolean isBuy, long eventTime) {
        String direction = isBuy ? "BUY" : "SELL";
        int directionSign = isBuy ? 1 : -1;

        volumeImbalance += deltaVolume * directionSign;
        dollarImbalance += (double) deltaVolume * price * directionSign;

        if (direction.equals(currentDirection)) {
            currentRunLength++;
        } else {
            tickRuns += currentRunLength;
            currentRunLength = 1;
            currentDirection = direction;
        }

        if (direction.equals(currentDirection)) {
            currentVolumeRun += deltaVolume;
        } else {
            volumeRuns += currentVolumeRun;
            currentVolumeRun = deltaVolume;
        }

        checkImbalanceThresholds(eventTime);
    }

    private void checkImbalanceThresholds(long eventTime) {
        vibEwmaMean = IMBALANCE_EWMA_ALPHA * Math.abs(volumeImbalance) + (1 - IMBALANCE_EWMA_ALPHA) * vibEwmaMean;
        dibEwmaMean = IMBALANCE_EWMA_ALPHA * Math.abs(dollarImbalance) + (1 - IMBALANCE_EWMA_ALPHA) * dibEwmaMean;
        trbEwmaMean = IMBALANCE_EWMA_ALPHA * Math.abs(tickRuns) + (1 - IMBALANCE_EWMA_ALPHA) * trbEwmaMean;
        vrbEwmaMean = IMBALANCE_EWMA_ALPHA * Math.abs(volumeRuns) + (1 - IMBALANCE_EWMA_ALPHA) * vrbEwmaMean;

        expectedVolumeImbalance = Math.max(INIT_VOLUME_IMBALANCE, vibEwmaMean * 1.5);
        expectedDollarImbalance = Math.max(INIT_DOLLAR_IMBALANCE, dibEwmaMean * 1.5);
        expectedTickRuns = Math.max(INIT_TICK_RUNS, trbEwmaMean * 1.5);
        expectedVolumeRuns = Math.max(INIT_VOLUME_RUNS, vrbEwmaMean * 1.5);

        if (Math.abs(volumeImbalance) >= expectedVolumeImbalance) {
            vibTriggered = true;
            log.debug("[VIB-TRIGGER] {} | imbalance={} threshold={}", 
                scripCode, volumeImbalance, expectedVolumeImbalance);
            volumeImbalance = 0L;
        }
        if (Math.abs(dollarImbalance) >= expectedDollarImbalance) {
            dibTriggered = true;
            log.debug("[DIB-TRIGGER] {} | imbalance={} threshold={}",
                scripCode, String.format("%.0f", dollarImbalance), String.format("%.0f", expectedDollarImbalance));
            dollarImbalance = 0.0;
        }
        if (Math.abs(tickRuns) >= expectedTickRuns) {
            trbTriggered = true;
            log.debug("[TRB-TRIGGER] {} | runs={} threshold={}", 
                scripCode, tickRuns, expectedTickRuns);
            tickRuns = 0;
        }
        if (Math.abs(volumeRuns) >= expectedVolumeRuns) {
            vrbTriggered = true;
            log.debug("[VRB-TRIGGER] {} | volumeRuns={} threshold={}", 
                scripCode, volumeRuns, expectedVolumeRuns);
            volumeRuns = 0L;
        }
    }

    /**
     * Calculate temporal metrics at end of window.
     * FIX: Uses persisted incremental fields that survive serialization.
     */
    public void calculateTemporalMetrics() {
        // FIX: Use persisted incremental fields instead of transient list
        // tickGapCount and sumTickGaps are persisted, tickTimestamps is transient
        if (tickGapCount > 0) {
            avgTickGap = (double) sumTickGaps / tickGapCount;
        } else if (tickTimestamps != null && tickTimestamps.size() >= 2) {
            // Fallback to transient list if available
            long totalGap = 0;
            for (int i = 1; i < tickTimestamps.size(); i++) {
                totalGap += tickTimestamps.get(i) - tickTimestamps.get(i - 1);
            }
            avgTickGap = (double) totalGap / (tickTimestamps.size() - 1);
        } else {
            avgTickGap = 0.0;
        }

        // Calculate ticksPerSecond from persisted timestamps
        // FIX: Avoid integer division returning 0 when ticks < 1 per second
        long windowDurationMs = lastTickTimestamp - firstTickTimestamp;
        if (windowDurationMs > 0 && tickCount > 0) {
            // Calculate as ticks per second, minimum 1 if any ticks exist
            double tps = (double) tickCount * 1000.0 / windowDurationMs;
            ticksPerSecond = (int) Math.ceil(tps);  // Round up - if 0.5 tps, report 1
        } else {
            ticksPerSecond = tickCount;  // All ticks in same millisecond
        }

        tickAcceleration = ticksPerSecond - previousTicksPerSecond;
        previousTicksPerSecond = ticksPerSecond;

        // FIX: Finalize current second bucket for tick intensity stats
        if (currentSecondBucket > 0 && currentSecondTickCount > 0) {
            maxTicksInAnySecond = Math.max(maxTicksInAnySecond, currentSecondTickCount);
            if (lastSeenSecondBucket != currentSecondBucket) {
                secondsWithTicks++;
            }
        }
    }

    /**
     * Calculate trade size distribution at end of window.
     * FIX: Uses persisted incremental fields that survive serialization.
     */
    public void calculateTradeSizeDistribution() {
        // FIX: Use persisted incremental fields instead of transient list
        // tradeCount and totalTradeVolume are persisted, tradeHistory is transient
        if (tradeCount > 0) {
            avgTradeSize = (double) totalTradeVolume / tradeCount;
        } else if (tradeHistory != null && !tradeHistory.isEmpty()) {
            // Fallback to transient list if available
            long totalVol = 0;
            for (TradeInfo trade : tradeHistory) {
                totalVol += trade.quantity;
            }
            avgTradeSize = (double) totalVol / tradeHistory.size();
        } else {
            avgTradeSize = 0.0;
        }

        // Median calculation - only possible with history
        if (tradeHistory != null && !tradeHistory.isEmpty()) {
            List<Long> sizes = new ArrayList<>();
            for (TradeInfo trade : tradeHistory) {
                sizes.add(trade.quantity);
            }
            sizes.sort(Long::compareTo);
            int mid = sizes.size() / 2;
            medianTradeSize = sizes.size() % 2 == 0 ?
                (sizes.get(mid - 1) + sizes.get(mid)) / 2.0 : sizes.get(mid);

            double largeThreshold = avgTradeSize * 10;
            largeTradeCount = 0;
            for (TradeInfo trade : tradeHistory) {
                if (trade.quantity > largeThreshold) {
                    largeTradeCount++;
                    log.debug("[LARGE-TRADE] {} | size={} threshold={}",
                        scripCode, trade.quantity, String.format("%.0f", largeThreshold));
                }
            }
        } else {
            // FIX: Approximate median from min/max/avg when history unavailable
            // Use avgTradeSize as approximation (better than 0)
            medianTradeSize = avgTradeSize;
        }

        if (volume > 0) {
            double priceChange = close - open;
            priceImpactPerUnit = priceChange / volume * 1000000;
        }
    }

    // ========== GETTERS ==========

    public double getClassificationReliability() {
        int total = classifiedTradeCount + unclassifiedTradeCount;
        return total > 0 ? (double) classifiedTradeCount / total : 0.0;
    }

    public long getMinTickGap() { return minTickGap == Long.MAX_VALUE ? 0 : minTickGap; }
    public long getMinTradeSize() { return minTradeSize == Long.MAX_VALUE ? 0 : minTradeSize; }

    // Event time getters for OHLC (useful for analytics - when did open/high/low/close occur?)
    public long getOpenEventTime() { return openEventTime == Long.MAX_VALUE ? 0 : openEventTime; }
    public long getHighEventTime() { return highEventTime; }
    public long getLowEventTime() { return lowEventTime; }
    public long getCloseEventTime() { return closeEventTime; }
    
    public List<TradeInfo> getTradeHistory() {
        return tradeHistory != null ? new ArrayList<>(tradeHistory) : new ArrayList<>();
    }

    // Imbalance getters
    public int getTickRuns() { return tickRuns + currentRunLength; }
    public long getVolumeRuns() { return volumeRuns + currentVolumeRun; }

    // P0 getters
    public double getTickBidAskImbalance() {
        long total = sumTotalBidQty + sumTotalOffQty;
        return total > 0 ? (double)(sumTotalBidQty - sumTotalOffQty) / total : 0.0;
    }
    public double getOvernightGap() {
        return previousClose > 0 ? (open - previousClose) / previousClose * 100.0 : 0.0;
    }
    public boolean isGapUp() { return getOvernightGap() > 0.5; }
    public boolean isGapDown() { return getOvernightGap() < -0.5; }

    // P1 getters
    public double getVwapDrift() {
        return exchangeVwap > 0 ? (vwap - exchangeVwap) / exchangeVwap * 100.0 : 0.0;
    }

    // P2 getters
    public int getMaxTicksInAnySecond() {
        if (tickCountPerSecond == null || tickCountPerSecond.isEmpty()) return 0;
        return tickCountPerSecond.values().stream().max(Integer::compareTo).orElse(0);
    }
    public int getSecondsWithTicks() {
        return tickCountPerSecond != null ? tickCountPerSecond.size() : 0;
    }
    public double getTickBurstRatio() {
        int max = getMaxTicksInAnySecond();
        int seconds = getSecondsWithTicks();
        if (seconds == 0) return 0.0;
        double avg = (double) tickCount / seconds;
        return avg > 0 ? max / avg : 0.0;
    }
    public boolean isAlgoActivityDetected() {
        return getTickBurstRatio() > 3.0;
    }

    // ==================== VOLUME PROFILE HELPERS ====================

    /**
     * Set tick size for volume profile price rounding
     */
    public void setTickSizeForProfile(double tickSize) {
        this.tickSizeForProfile = tickSize > 0 ? tickSize : 0.05;
    }

    /**
     * Round price to tick size for volume profile histogram
     */
    private double roundToTickSize(double price, double tickSize) {
        if (tickSize <= 0) return price;
        return Math.round(price / tickSize) * tickSize;
    }

    /**
     * Get volume-at-price histogram (for POC/VAH/VAL calculation)
     */
    public Map<Double, Long> getVolumeAtPrice() {
        return new HashMap<>(volumeAtPrice);  // Return copy to prevent external modification
    }

    // ==================== SPREAD METRICS GETTERS ====================

    /**
     * Get average tick-level spread (execution cost)
     */
    public double getAverageTickSpread() {
        return spreadCount > 0 ? sumSpread / spreadCount : 0.0;
    }

    /**
     * Get minimum spread observed in window
     */
    public double getMinTickSpread() {
        return minSpread == Double.MAX_VALUE ? 0.0 : minSpread;
    }

    /**
     * Get maximum spread observed in window
     */
    public double getMaxTickSpread() {
        return maxSpread;
    }

    /**
     * Calculate spread volatility (standard deviation)
     * FIX: Uses Welford's algorithm (persisted) that survives serialization.
     */
    public double getSpreadVolatility() {
        // FIX: Use Welford's algorithm (persisted) instead of transient list
        if (spreadWelfordCount >= 2) {
            double variance = spreadWelfordM2 / (spreadWelfordCount - 1);
            return Math.sqrt(variance);
        }
        // Fallback to transient list if available
        if (spreadHistory != null && spreadHistory.size() >= 2) {
            double mean = getAverageTickSpread();
            double variance = spreadHistory.stream()
                .mapToDouble(s -> Math.pow(s - mean, 2))
                .average()
                .orElse(0.0);
            return Math.sqrt(variance);
        }
        return 0.0;
    }

    /**
     * Get percentage of time spread was tight (<= 1 tick)
     * High % = liquid market with competitive quotes
     */
    public double getTightSpreadPercent() {
        return spreadCount > 0 ? (double) tightSpreadCount / spreadCount * 100.0 : 0.0;
    }

    // ==================== EFFECTIVE SPREAD GETTERS ====================

    /**
     * Get average effective spread = 2 * |trade_price - midpoint|
     *
     * This measures ACTUAL execution cost vs theoretical midpoint.
     *
     * Key insight:
     * - If effective < quoted: Traders getting price improvement
     * - If effective = quoted: Trades at BBO as expected
     * - If effective > quoted: Trades walking the book (unusual)
     *
     * Reference: Glosten & Harris (1988), Huang & Stoll (1996)
     */
    public double getAverageEffectiveSpread() {
        return effectiveSpreadCount > 0 ? sumEffectiveSpread / effectiveSpreadCount : 0.0;
    }

    public double getMinEffectiveSpread() {
        return minEffectiveSpread == Double.MAX_VALUE ? 0.0 : minEffectiveSpread;
    }

    public double getMaxEffectiveSpread() {
        return maxEffectiveSpread;
    }

    /**
     * Get price improvement ratio = (quoted - effective) / quoted
     *
     * Positive = traders are getting price improvement
     * Negative = traders are paying MORE than quoted (bad)
     * Zero = executing at quoted spread
     */
    public double getPriceImprovementRatio() {
        double quotedSpread = getAverageTickSpread();
        double effectiveSpread = getAverageEffectiveSpread();

        if (quotedSpread <= 0) return 0.0;

        return (quotedSpread - effectiveSpread) / quotedSpread;
    }

    /**
     * Get total trades executed in window
     */
    public long getTotalTradesExecuted() {
        return totalTradesExecuted;
    }

    // ==================== VWAP BANDS CALCULATION ====================

    /**
     * Calculate VWAP standard deviation (price volatility around VWAP)
     * FIX: Uses Welford's algorithm (persisted) that survives serialization.
     */
    public double getVWAPStdDev() {
        // FIX: Use Welford's algorithm (persisted) instead of transient list
        if (priceWelfordCount >= 2) {
            double variance = priceWelfordM2 / (priceWelfordCount - 1);
            return Math.sqrt(variance);
        }
        // Fallback to transient list if available
        if (priceHistory != null && priceHistory.size() >= 2) {
            double mean = vwap;  // Use VWAP as the mean
            double variance = priceHistory.stream()
                .mapToDouble(p -> Math.pow(p - mean, 2))
                .average()
                .orElse(0.0);
            return Math.sqrt(variance);
        }
        return 0.0;
    }

    /**
     * Get VWAP upper band (VWAP + 2σ)
     * Price > Upper Band = Overbought → Potential sell signal
     */
    public double getVWAPUpperBand() {
        return vwap + 2 * getVWAPStdDev();
    }

    /**
     * Get VWAP lower band (VWAP - 2σ)
     * Price < Lower Band = Oversold → Potential buy signal
     */
    public double getVWAPLowerBand() {
        return vwap - 2 * getVWAPStdDev();
    }

    /**
     * Get VWAP signal based on close price vs bands
     * @return "OVERBOUGHT" if close > upper band, "OVERSOLD" if close < lower band, "NEUTRAL" otherwise
     */
    public String getVWAPSignal() {
        if (priceHistory == null || priceHistory.size() < 2) return "NEUTRAL";

        double upper = getVWAPUpperBand();
        double lower = getVWAPLowerBand();

        if (close > upper) return "OVERBOUGHT";
        if (close < lower) return "OVERSOLD";
        return "NEUTRAL";
    }

    /**
     * Trade information holder
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TradeInfo {
        private long kafkaTimestamp;
        private long eventTimestamp;
        private double price;
        private long quantity;
        private String classification;
        private double bidPrice;
        private double askPrice;
    }

    public static Serde<TickAggregate> serde() {
        return new JsonSerde<>(TickAggregate.class);
    }
}
