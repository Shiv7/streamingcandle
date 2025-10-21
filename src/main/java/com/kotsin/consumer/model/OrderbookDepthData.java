package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import org.springframework.kafka.support.serializer.JsonSerde;

/**
 * Comprehensive orderbook depth analytics
 * Implements professional trading orderbook features
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderbookDepthData {

    // 1. Depth Profile (levels 1-10)
    private List<DepthLevel> bidProfile;      // Bid quantities at each level
    private List<DepthLevel> askProfile;      // Ask quantities at each level

    // 2. Order book imbalance by level (weighted by distance from mid)
    private Double weightedDepthImbalance;     // Imbalance weighted by distance
    private Double level1Imbalance;            // Imbalance at best bid/ask
    private Double level2to5Imbalance;         // Imbalance at levels 2-5
    private Double level6to10Imbalance;        // Imbalance at levels 6-10

    // 3. Cumulative depth (sum of quantities at each level)
    private List<Double> cumulativeBidDepth;   // [level1, level1+2, level1+2+3, ...]
    private List<Double> cumulativeAskDepth;
    private Double totalBidDepth;              // Sum of all bid levels
    private Double totalAskDepth;              // Sum of all ask levels

    // 4. Depth pressure (volume-weighted average price of book)
    private Double bidVWAP;                    // VWAP of bid side (support level)
    private Double askVWAP;                    // VWAP of ask side (resistance level)
    private Double depthPressure;              // (bidVWAP - askVWAP) / midPrice

    // 5. Order book slope (rate of quantity decay by level)
    private Double bidSlope;                   // Negative = steep decay (weak support)
    private Double askSlope;                   // Negative = steep decay (weak resistance)
    private Double slopeRatio;                 // bidSlope / askSlope

    // 6. Iceberg detection (large orders split across levels)
    private Boolean icebergDetectedBid;        // Unusual uniformity on bid side
    private Boolean icebergDetectedAsk;        // Unusual uniformity on ask side
    private Double icebergProbabilityBid;      // 0-1 probability score
    private Double icebergProbabilityAsk;

    // 7. Spoofing detection (large orders that disappear quickly)
    private List<SpoofingEvent> spoofingEvents; // Recent spoofing events
    private Integer spoofingCountLast1Min;     // Number of events in last minute
    private Boolean activeSpoofingBid;         // Currently detecting spoof on bid
    private Boolean activeSpoofingAsk;         // Currently detecting spoof on ask

    // Metadata
    private Long timestamp;
    private Double midPrice;
    private Double spread;
    private Integer depthLevels;               // How many levels captured (typically 10)
    private Boolean isComplete;

    /**
     * Depth Level - represents aggregated data at a price level
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DepthLevel {
        private Integer level;                 // 1 = best, 2 = second best, etc.
        private Double price;
        private Integer quantity;
        private Integer numberOfOrders;
        private Double distanceFromMid;        // Price distance from mid (bps)
        private Double percentOfTotalDepth;    // What % of total depth is at this level
    }

    /**
     * Spoofing Event - captures a large order that disappeared
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SpoofingEvent {
        private Long timestamp;
        private String side;                   // "BID" or "ASK"
        private Double price;
        private Integer quantity;
        private Long durationMs;               // How long the order lasted
        private String classification;         // "POSSIBLE_SPOOF", "CONFIRMED_SPOOF"
    }

    /**
     * Check if depth data is valid
     */
    public boolean isValid() {
        return bidProfile != null && !bidProfile.isEmpty() &&
               askProfile != null && !askProfile.isEmpty() &&
               totalBidDepth != null && totalAskDepth != null;
    }

    /**
     * Get depth imbalance at best level (level 1)
     */
    public Double getLevel1DepthImbalance() {
        if (bidProfile == null || askProfile == null ||
            bidProfile.isEmpty() || askProfile.isEmpty()) {
            return 0.0;
        }

        double bidQty = bidProfile.get(0).getQuantity();
        double askQty = askProfile.get(0).getQuantity();

        if (bidQty + askQty == 0) {
            return 0.0;
        }

        return (bidQty - askQty) / (bidQty + askQty);
    }

    /**
     * Check if there's strong buying pressure (weighted imbalance > threshold)
     */
    public Boolean hasStrongBuyingPressure() {
        return weightedDepthImbalance != null && weightedDepthImbalance > 0.3;
    }

    /**
     * Check if there's strong selling pressure
     */
    public Boolean hasStrongSellingPressure() {
        return weightedDepthImbalance != null && weightedDepthImbalance < -0.3;
    }

    /**
     * Check if orderbook is balanced
     */
    public Boolean isBalanced() {
        return weightedDepthImbalance != null &&
               Math.abs(weightedDepthImbalance) < 0.1;
    }

    /**
     * Get support level (bid VWAP)
     */
    public Double getSupportLevel() {
        return bidVWAP;
    }

    /**
     * Get resistance level (ask VWAP)
     */
    public Double getResistanceLevel() {
        return askVWAP;
    }

    /**
     * Check if iceberg orders detected
     */
    public Boolean hasIcebergOrders() {
        return (icebergDetectedBid != null && icebergDetectedBid) ||
               (icebergDetectedAsk != null && icebergDetectedAsk);
    }

    /**
     * Check if spoofing activity detected recently
     */
    public Boolean hasSpoofingActivity() {
        return (spoofingCountLast1Min != null && spoofingCountLast1Min > 0) ||
               (activeSpoofingBid != null && activeSpoofingBid) ||
               (activeSpoofingAsk != null && activeSpoofingAsk);
    }

    /**
     * Get display string for logging
     */
    public String getDisplayString() {
        return String.format("Depth[Imb:%.2f,Slope:%.2f/%.2f,Ice:%s/%s,Spoof:%d] %s",
            weightedDepthImbalance != null ? weightedDepthImbalance : 0.0,
            bidSlope != null ? bidSlope : 0.0,
            askSlope != null ? askSlope : 0.0,
            icebergDetectedBid != null && icebergDetectedBid ? "Y" : "N",
            icebergDetectedAsk != null && icebergDetectedAsk ? "Y" : "N",
            spoofingCountLast1Min != null ? spoofingCountLast1Min : 0,
            isComplete != null && isComplete ? "COMPLETE" : "PARTIAL");
    }

    public static org.apache.kafka.common.serialization.Serde<OrderbookDepthData> serde() {
        return new JsonSerde<>(OrderbookDepthData.class);
    }
}
