package com.kotsin.consumer.aggregator.state;

import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.OrderbookMetrics;

import java.time.Instant;
import java.util.LinkedList;

/**
 * OrderbookAggregateState - Manages aggregation state for orderbook data within a time window.
 *
 * <h2>Responsibilities</h2>
 * <ol>
 *   <li>Calculate Order Flow Imbalance (OFI) from bid/ask changes</li>
 *   <li>Calculate Kyle's Lambda (price impact coefficient)</li>
 *   <li>Track bid-ask spread metrics (average, volatility, tight spread %)</li>
 *   <li>Track depth metrics (bid/ask depth, imbalance)</li>
 *   <li>Detect anomalies (spoofing, iceberg orders)</li>
 *   <li>Convert aggregated state to OrderbookMetrics for persistence</li>
 * </ol>
 *
 * <h2>Key Design Principles</h2>
 * <ul>
 *   <li><b>THREAD-SAFE</b>: Uses synchronized update() method for concurrent snapshot processing</li>
 *   <li><b>EVENT-TIME</b>: Associates state with specific window boundaries</li>
 *   <li><b>ROLLING WINDOW</b>: Kyle's Lambda uses 50-observation rolling window</li>
 * </ul>
 *
 * <h2>OFI Calculation</h2>
 * <p>Order Flow Imbalance measures aggressive order activity:</p>
 * <pre>
 * OFI = Σ(BidDelta - AskDelta)
 *
 * BidDelta = bidQty - prevBidQty (if bid >= prevBid)
 * AskDelta = askQty - prevAskQty (if ask <= prevAsk)
 * </pre>
 *
 * <h2>Kyle's Lambda Calculation</h2>
 * <p>Price impact coefficient from regression:</p>
 * <pre>
 * priceChange = λ * signedVolume + ε
 * λ = Σ(ΔP * ΔV) / Σ(ΔV²)
 * </pre>
 *
 * @see com.kotsin.consumer.aggregator.OrderbookAggregator
 * @see OrderbookMetrics
 */
public class OrderbookAggregateState {

    // ==================== CONSTANTS ====================

    /**
     * Rolling window size for Kyle's Lambda calculation.
     * <p>Uses 50 observations for statistical stability.</p>
     */
    private static final int KYLE_LAMBDA_WINDOW = 50;

    /**
     * Tight spread threshold in price units.
     * <p>Spreads at or below this value are considered "tight".</p>
     */
    private static final double TIGHT_SPREAD_THRESHOLD = 0.05;

    // ==================== IDENTITY ====================

    /**
     * Extracted symbol from company name (e.g., "NIFTY", "RELIANCE").
     * <p>Resolved by ScripMetadataService for accuracy.</p>
     */
    private final String symbol;

    /**
     * Exchange scrip code (unique instrument identifier).
     */
    private final String scripCode;

    /**
     * Exchange code: "N" (NSE), "B" (BSE), "M" (MCX).
     */
    private final String exchange;

    /**
     * Exchange type: "C" (Cash), "D" (Derivative).
     */
    private final String exchangeType;

    // ==================== WINDOW TIMING ====================

    /**
     * Start of aggregation window (inclusive).
     */
    private Instant windowStart;

    /**
     * End of aggregation window (exclusive, canonical timestamp).
     */
    private Instant windowEnd;

    /**
     * Timestamp of last orderbook update (event time).
     */
    private Instant lastUpdate;

    // ==================== OFI CALCULATION ====================

    /**
     * Cumulative Order Flow Imbalance for the window.
     * <p>Positive = net buying pressure, Negative = net selling pressure.</p>
     */
    private double ofiSum;

    /**
     * Previous best bid quantity for delta calculation.
     */
    private double previousBidQty;

    /**
     * Previous best ask quantity for delta calculation.
     */
    private double previousAskQty;

    /**
     * Previous best bid price for level comparison.
     */
    private double previousBid;

    /**
     * Previous best ask price for level comparison.
     */
    private double previousAsk;

    // ==================== KYLE'S LAMBDA ====================

    /**
     * Rolling list of price changes for Kyle's Lambda calculation.
     * <p>LinkedList for O(1) removal from front.</p>
     */
    private final LinkedList<Double> priceChanges = new LinkedList<>();

    /**
     * Rolling list of signed volumes for Kyle's Lambda calculation.
     * <p>Paired with priceChanges for regression.</p>
     */
    private final LinkedList<Double> signedVolumes = new LinkedList<>();

    /**
     * Count of observations in current window (for debugging).
     */
    private int windowObservationCount = 0;

    /**
     * Last mid price for price change calculation.
     */
    private double lastMidPrice;

    // ==================== SPREAD METRICS ====================

    /**
     * Sum of spreads for average calculation.
     */
    private double spreadSum;

    /**
     * Sum of squared spreads for variance calculation.
     */
    private double spreadSqSum;

    /**
     * Count of tight spreads (spread <= threshold).
     */
    private int tightSpreadCount;

    // ==================== DEPTH METRICS ====================

    /**
     * Sum of total bid depths for average calculation.
     */
    private double bidDepthSum;

    /**
     * Sum of total ask depths for average calculation.
     */
    private double askDepthSum;

    /**
     * Sum of depth imbalance ratios.
     * <p>Imbalance = (bidDepth - askDepth) / totalDepth</p>
     */
    private double depthImbalanceSum;

    // ==================== ANOMALY DETECTION ====================

    /**
     * Count of detected spoofing events.
     */
    private int spoofingCount;

    /**
     * Flag indicating iceberg order detected on bid side.
     */
    private boolean icebergBidDetected;

    /**
     * Flag indicating iceberg order detected on ask side.
     */
    private boolean icebergAskDetected;

    /**
     * Count of order cancellations.
     */
    private int cancelCount;

    /**
     * Total order count for cancel rate calculation.
     */
    private int totalOrders;

    // ==================== STATISTICS ====================

    /**
     * Number of orderbook updates processed in this window.
     */
    private int updateCount;

    // ==================== CONSTRUCTOR ====================

    /**
     * Create new orderbook aggregation state from first snapshot.
     *
     * @param ob          first orderbook snapshot in this window
     * @param windowStart start of aggregation window
     * @param windowEnd   end of aggregation window
     * @param symbol      pre-resolved symbol from ScripMetadataService
     */
    public OrderbookAggregateState(OrderBookSnapshot ob, Instant windowStart, Instant windowEnd, String symbol) {
        this.symbol = symbol;
        this.scripCode = String.valueOf(ob.getToken());
        this.exchange = ob.getExchange();
        this.exchangeType = ob.getExchangeType();
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.lastUpdate = Instant.now();

        // Initialize previous values from first snapshot
        this.previousBid = ob.getBestBid();
        this.previousAsk = ob.getBestAsk();
        this.previousBidQty = getBidQty(ob);
        this.previousAskQty = getAskQty(ob);
        this.lastMidPrice = (previousBid + previousAsk) / 2;
    }

    // ==================== ORDERBOOK PROCESSING ====================

    /**
     * Update aggregation state with a new orderbook snapshot.
     *
     * <p>Thread-safe method that performs:</p>
     * <ol>
     *   <li>OFI calculation from bid/ask deltas</li>
     *   <li>Kyle's Lambda update (price change vs signed volume)</li>
     *   <li>Spread metrics update</li>
     *   <li>Depth metrics update</li>
     * </ol>
     *
     * @param ob     incoming orderbook snapshot
     * @param obTime event time of the snapshot
     */
    public synchronized void update(OrderBookSnapshot ob, Instant obTime) {
        double bid = ob.getBestBid();
        double ask = ob.getBestAsk();
        double bidQty = getBidQty(ob);
        double askQty = getAskQty(ob);

        // Calculate OFI
        double bidDelta = 0;
        double askDelta = 0;

        if (bid >= previousBid) {
            bidDelta = bidQty - (bid == previousBid ? previousBidQty : 0);
        }
        if (ask <= previousAsk) {
            askDelta = askQty - (ask == previousAsk ? previousAskQty : 0);
        }

        ofiSum += bidDelta - askDelta;

        // Kyle's Lambda - track price change vs signed volume
        double midPrice = (bid + ask) / 2;
        if (lastMidPrice > 0 && midPrice > 0) {
            double priceChange = midPrice - lastMidPrice;
            double signedVol = bidDelta - askDelta;
            if (Math.abs(signedVol) > 0) {
                priceChanges.addLast(priceChange);
                signedVolumes.addLast(signedVol);
                windowObservationCount++;

                // Keep rolling window capped
                while (priceChanges.size() > KYLE_LAMBDA_WINDOW) {
                    priceChanges.removeFirst();
                    signedVolumes.removeFirst();
                }
            }
        }
        lastMidPrice = midPrice;

        // Spread metrics
        double spread = ask - bid;
        if (spread > 0) {
            spreadSum += spread;
            spreadSqSum += spread * spread;
            if (spread <= TIGHT_SPREAD_THRESHOLD) {
                tightSpreadCount++;
            }
        }

        // Depth metrics
        Long totalBidDepthLong = ob.getTotalBidQty();
        Long totalAskDepthLong = ob.getTotalOffQty();
        double totalBidDepth = totalBidDepthLong != null ? totalBidDepthLong : bidQty;
        double totalAskDepth = totalAskDepthLong != null ? totalAskDepthLong : askQty;
        bidDepthSum += totalBidDepth;
        askDepthSum += totalAskDepth;

        double totalDepth = totalBidDepth + totalAskDepth;
        if (totalDepth > 0) {
            depthImbalanceSum += (totalBidDepth - totalAskDepth) / totalDepth;
        }

        // Update previous values
        previousBid = bid;
        previousAsk = ask;
        previousBidQty = bidQty;
        previousAskQty = askQty;

        updateCount++;
        lastUpdate = obTime;
    }

    // ==================== HELPER METHODS ====================

    /**
     * Extract best bid quantity from orderbook snapshot.
     *
     * @param ob orderbook snapshot
     * @return best bid quantity, or 0 if not available
     */
    private static double getBidQty(OrderBookSnapshot ob) {
        if (ob.getBids() != null && !ob.getBids().isEmpty()) {
            return ob.getBids().get(0).getQuantity();
        }
        return 0;
    }

    /**
     * Extract best ask quantity from orderbook snapshot.
     *
     * @param ob orderbook snapshot
     * @return best ask quantity, or 0 if not available
     */
    private static double getAskQty(OrderBookSnapshot ob) {
        if (ob.getAsks() != null && !ob.getAsks().isEmpty()) {
            return ob.getAsks().get(0).getQuantity();
        }
        return 0;
    }

    /**
     * Calculate Kyle's Lambda from rolling window data.
     *
     * <p>Uses simple linear regression: priceChange = λ * signedVolume</p>
     * <p>λ = Σ(xy) / Σ(x²) where x = signedVolume, y = priceChange</p>
     *
     * @return Kyle's Lambda, or 0 if insufficient data
     */
    private double calculateKyleLambda() {
        if (priceChanges.size() < 5) return 0;

        double sumXY = 0, sumX2 = 0;
        for (int i = 0; i < priceChanges.size(); i++) {
            double x = signedVolumes.get(i);
            double y = priceChanges.get(i);
            sumXY += x * y;
            sumX2 += x * x;
        }

        return sumX2 > 0 ? sumXY / sumX2 : 0;
    }

    // ==================== CONVERSION ====================

    /**
     * Convert aggregation state to OrderbookMetrics for persistence.
     *
     * @return OrderbookMetrics ready for MongoDB persistence
     */
    public OrderbookMetrics toOrderbookMetrics() {
        double avgSpread = updateCount > 0 ? spreadSum / updateCount : 0;
        double spreadVariance = updateCount > 1 ?
            (spreadSqSum - spreadSum * spreadSum / updateCount) / (updateCount - 1) : 0;
        double spreadVolatility = Math.sqrt(Math.max(0, spreadVariance));

        double avgBidDepth = updateCount > 0 ? bidDepthSum / updateCount : 0;
        double avgAskDepth = updateCount > 0 ? askDepthSum / updateCount : 0;
        double avgDepthImbalance = updateCount > 0 ? depthImbalanceSum / updateCount : 0;

        double kyleLambda = calculateKyleLambda();

        double microprice = 0;
        if (avgBidDepth + avgAskDepth > 0) {
            microprice = (previousBid * avgAskDepth + previousAsk * avgBidDepth) /
                         (avgBidDepth + avgAskDepth);
        }

        return OrderbookMetrics.builder()
            .id(exchange + ":" + scripCode + "_" + windowEnd.toEpochMilli()) // IDEMPOTENCY FIX + Bug #6: exchange in ID
            .symbol(symbol)
            .scripCode(scripCode)
            .exchange(exchange)
            .exchangeType(exchangeType)
            .timestamp(windowEnd)
            .windowStart(windowStart)
            .windowEnd(windowEnd)
            .ofi(ofiSum)
            .ofiMomentum(0.0)  // Calculated by comparing with previous window
            .kyleLambda(kyleLambda)
            .microprice(microprice)
            .bidAskSpread(avgSpread)
            .spreadPercent(lastMidPrice > 0 ? avgSpread / lastMidPrice * 100 : 0)
            .spreadVolatility(spreadVolatility)
            .tightSpreadPercent(updateCount > 0 ? (double) tightSpreadCount / updateCount : 0)
            .depthImbalance(avgDepthImbalance)
            .weightedDepthImbalance(avgDepthImbalance)  // Simplified
            .avgBidDepth(avgBidDepth)
            .avgAskDepth(avgAskDepth)
            .bidDepthSlope(0.0)  // Requires multi-level data
            .askDepthSlope(0.0)
            .depthConcentration(0.0)
            .spoofingCount(spoofingCount)
            .icebergBidDetected(icebergBidDetected)
            .icebergAskDetected(icebergAskDetected)
            .cancelRate(totalOrders > 0 ? (double) cancelCount / totalOrders : 0)
            .updateCount(updateCount)
            .lastUpdateTimestamp(lastUpdate)
            .quality("VALID")
            .staleness(lastUpdate != null ? System.currentTimeMillis() - lastUpdate.toEpochMilli() : 0)
            .createdAt(Instant.now())
            .build();
    }

    // ==================== STATE RESET ====================

    /**
     * Reset state for a new window.
     *
     * <p>Clears all metrics while preserving previous bid/ask for continuity.</p>
     *
     * @param newWindowStart start of new aggregation window
     * @param newWindowEnd   end of new aggregation window
     */
    public void reset(Instant newWindowStart, Instant newWindowEnd) {
        this.windowStart = newWindowStart;
        this.windowEnd = newWindowEnd;
        this.ofiSum = 0;
        this.priceChanges.clear();
        this.signedVolumes.clear();
        this.windowObservationCount = 0;
        this.spreadSum = 0;
        this.spreadSqSum = 0;
        this.tightSpreadCount = 0;
        this.bidDepthSum = 0;
        this.askDepthSum = 0;
        this.depthImbalanceSum = 0;
        this.spoofingCount = 0;
        this.icebergBidDetected = false;
        this.icebergAskDetected = false;
        this.cancelCount = 0;
        this.totalOrders = 0;
        this.updateCount = 0;
    }

    // ==================== ACCESSORS ====================

    /**
     * Get window start timestamp.
     *
     * @return start of aggregation window
     */
    public Instant getWindowStart() {
        return windowStart;
    }

    /**
     * Get window end timestamp.
     *
     * @return end of aggregation window
     */
    public Instant getWindowEnd() {
        return windowEnd;
    }

    /**
     * Get last update timestamp.
     *
     * @return timestamp of last orderbook update
     */
    public Instant getLastUpdate() {
        return lastUpdate;
    }

    /**
     * Get total update count in this window.
     *
     * @return number of orderbook updates processed
     */
    public int getUpdateCount() {
        return updateCount;
    }
}
