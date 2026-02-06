package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * OrderbookMetrics - Orderbook-derived metrics for 1-minute window.
 *
 * Contains ONLY orderbook-derived data (~25 fields):
 * - Order Flow Imbalance (OFI)
 * - Kyle's Lambda (price impact)
 * - Microprice (fair value estimate)
 * - Spread metrics
 * - Depth analysis
 * - Anomaly detection (spoofing, iceberg)
 *
 * Separate from TickCandle because:
 * - Different data source (Orderbook topic vs forwardtesting-data)
 * - Different update frequency (orderbook updates less frequently)
 * - Some instruments don't have orderbook data
 * - Cleaner separation of concerns
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Document(collection = "orderbook_metrics_1m")
@CompoundIndexes({
    @CompoundIndex(name = "symbol_timestamp_idx", def = "{'symbol': 1, 'timestamp': -1}"),
    @CompoundIndex(name = "scripCode_timestamp_idx", def = "{'scripCode': 1, 'timestamp': -1}"),
    // Bug #6 FIX: Include exchange in unique index to avoid MCX/NSE scripCode collisions
    @CompoundIndex(name = "exchange_scripCode_windowStart_unique_idx",
                   def = "{'exchange': 1, 'scripCode': 1, 'windowStart': 1}",
                   unique = true)
})
public class OrderbookMetrics {

    @Id
    private String id;

    // ==================== IDENTITY ====================
    private String symbol;
    private String scripCode;
    private String exchange;
    private String exchangeType;

    // ==================== TIMING ====================
    @Indexed(expireAfter = "90d")
    private Instant timestamp;        // Window end time
    private Instant windowStart;
    private Instant windowEnd;

    // ==================== ORDER FLOW IMBALANCE ====================
    /**
     * OFI = Order Flow Imbalance
     * Measures net buying/selling pressure from orderbook changes.
     * Positive = buying pressure, Negative = selling pressure.
     *
     * Formula: sum of (bidQty changes when bid improves) - (askQty changes when ask improves)
     */
    private double ofi;

    /**
     * OFI Momentum = current OFI - previous OFI
     * Shows acceleration/deceleration of order flow.
     */
    private double ofiMomentum;

    // ==================== KYLE'S LAMBDA ====================
    /**
     * Kyle's Lambda = price impact coefficient
     * Measures how much price moves per unit of order flow.
     * Higher lambda = less liquid, more price impact.
     *
     * Formula: regression coefficient of price_change vs signed_volume
     */
    private double kyleLambda;

    // ==================== MICROPRICE ====================
    /**
     * Microprice = fair value estimate from orderbook
     * Weighted average of bid/ask by opposite side depth.
     *
     * Formula: (bid * askQty + ask * bidQty) / (bidQty + askQty)
     */
    private double microprice;

    // ==================== SPREAD METRICS ====================
    /**
     * Best bid-ask spread in price terms.
     */
    private double bidAskSpread;

    /**
     * Spread as percentage of midpoint.
     */
    private double spreadPercent;

    /**
     * Spread volatility (std dev of spread over window).
     */
    private double spreadVolatility;

    /**
     * Percentage of time spread was tight (≤ 1 tick).
     */
    private double tightSpreadPercent;

    // ==================== DEPTH ANALYSIS ====================
    /**
     * Depth Imbalance = (bidDepth - askDepth) / (bidDepth + askDepth)
     * Range: -1 (all asks) to +1 (all bids)
     */
    private double depthImbalance;

    /**
     * Weighted Depth Imbalance (prioritizes near-touch levels).
     */
    private double weightedDepthImbalance;

    /**
     * Average total bid-side depth across all levels.
     */
    private double avgBidDepth;

    /**
     * Average total ask-side depth across all levels.
     */
    private double avgAskDepth;

    /**
     * Bid depth slope (how depth changes away from best bid).
     * Negative = depth decreases away from best.
     */
    private double bidDepthSlope;

    /**
     * Ask depth slope.
     */
    private double askDepthSlope;

    /**
     * Depth concentration in top 3 levels (0-1).
     * High concentration = liquidity concentrated near touch.
     */
    private double depthConcentration;

    // ==================== ANOMALY DETECTION ====================
    /**
     * Count of suspected spoofing events.
     * Detected when large orders appear and disappear quickly.
     */
    private int spoofingCount;

    /**
     * Iceberg order detected on bid side.
     * Detected when size replenishes after partial fill.
     */
    private boolean icebergBidDetected;

    /**
     * Iceberg order detected on ask side.
     */
    private boolean icebergAskDetected;

    /**
     * Order cancellation rate (cancelled / total orders).
     * High rate may indicate HFT or stress.
     */
    private double cancelRate;

    // ==================== UPDATE STATS ====================
    /**
     * Number of orderbook updates received in window.
     */
    private int updateCount;

    /**
     * Timestamp of last orderbook update.
     */
    private Instant lastUpdateTimestamp;

    // ==================== QUALITY ====================
    private String quality;           // VALID, STALE, INSUFFICIENT
    private long staleness;           // Milliseconds since last update

    // ==================== METADATA ====================
    private Instant createdAt;

    // ==================== HELPER METHODS ====================

    /**
     * Check if orderbook data is fresh (< 10 seconds old).
     */
    public boolean isFresh() {
        return staleness < 10000;
    }

    /**
     * Get total depth (bid + ask).
     */
    public double getTotalDepth() {
        return avgBidDepth + avgAskDepth;
    }

    /**
     * Check if there's significant buying pressure from orderbook.
     */
    public boolean hasBuyingPressure() {
        return ofi > 0 && depthImbalance > 0.1;
    }

    /**
     * Check if there's significant selling pressure from orderbook.
     */
    public boolean hasSellingPressure() {
        return ofi < 0 && depthImbalance < -0.1;
    }

    /**
     * Get normalized OFI (relative to average depth).
     */
    public double getNormalizedOfi() {
        double avgDepth = getTotalDepth() / 2;
        return avgDepth > 0 ? ofi / avgDepth : 0;
    }
}
