package com.kotsin.consumer.processor;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.OrderbookDepthData;
import com.kotsin.consumer.service.IcebergDetectionService;
import com.kotsin.consumer.service.OrderbookDepthCalculator;
import com.kotsin.consumer.service.SpoofingDetectionService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Orderbook Depth Accumulator (Facade Pattern)
 * Delegates to specialized services for depth analytics
 *
 * Design Pattern: Facade + Composition
 * Purpose: Coordinate multiple services without complex logic
 * 
 * Serialization Note: Service fields are marked @JsonIgnore and lazily initialized
 * to avoid Jackson serialization errors in Kafka Streams state stores.
 */
@Data
@Slf4j
public class OrderbookDepthAccumulator {

    // Composed services (marked transient for Kafka Streams serialization)
    // CRITICAL: All services must be @JsonIgnore to avoid serialization issues
    // Services are stateless or maintain serializable state separately
    @JsonIgnore
    private transient IcebergDetectionService icebergDetectionService;
    
    @JsonIgnore
    private transient SpoofingDetectionService spoofingDetectionService;
    
    @JsonIgnore
    private transient OrderbookDepthCalculator depthCalculator;

    // Current orderbook state
    private OrderBookSnapshot currentOrderbook;
    private OrderBookSnapshot previousOrderbook;
    private Long lastUpdateTimestamp;
    // CRITICAL: Don't mark as @JsonIgnore! Kafka Streams needs to serialize this
    private SnapshotMetrics lastMetrics;

    // Serializable accumulated values (sum + count for averaging)
    private double spreadSum = 0.0;
    private long spreadCount = 0L;
    private double totalBidDepthSum = 0.0;
    private long totalBidDepthCount = 0L;
    private double totalAskDepthSum = 0.0;
    private long totalAskDepthCount = 0L;
    private double bidVwapSum = 0.0;
    private long bidVwapCount = 0L;
    private double askVwapSum = 0.0;
    private long askVwapCount = 0L;
    private double depthPressureSum = 0.0;
    private long depthPressureCount = 0L;
    private double weightedImbalanceSum = 0.0;
    private long weightedImbalanceCount = 0L;
    private double bidSlopeSum = 0.0;
    private long bidSlopeCount = 0L;
    private double askSlopeSum = 0.0;
    private long askSlopeCount = 0L;
    private double slopeRatioSum = 0.0;
    private long slopeRatioCount = 0L;
    private double level1ImbSum = 0.0;
    private long level1ImbCount = 0L;
    private double level2to5ImbSum = 0.0;
    private long level2to5ImbCount = 0L;
    private double level6to10ImbSum = 0.0;
    private long level6to10ImbCount = 0L;
    private double midPriceSum = 0.0;
    private long midPriceCount = 0L;
    private long sampleCount = 0L;

    /**
     * Lazy initialization for services (called after deserialization)
     */
    private IcebergDetectionService getIcebergService() {
        if (icebergDetectionService == null) {
            icebergDetectionService = new IcebergDetectionService();
        }
        return icebergDetectionService;
    }

    private SpoofingDetectionService getSpoofingService() {
        if (spoofingDetectionService == null) {
            spoofingDetectionService = new SpoofingDetectionService();
        }
        return spoofingDetectionService;
    }

    private OrderbookDepthCalculator getDepthCalculator() {
        if (depthCalculator == null) {
            depthCalculator = new OrderbookDepthCalculator();
        }
        return depthCalculator;
    }

    public void addOrderbook(OrderBookSnapshot orderbook) {
        if (orderbook == null || !orderbook.isValid()) {
            log.warn("⚠️ OrderbookDepthAccumulator: Invalid orderbook, skipping");
            return;
        }

        orderbook.parseDetails();
        previousOrderbook = currentOrderbook;
        currentOrderbook = orderbook;
        lastUpdateTimestamp = orderbook.getTimestamp();

        SnapshotMetrics metrics = analyzeSnapshot(orderbook);
        lastMetrics = metrics;
        accumulateMetrics(metrics);
        
        log.debug("✅ OrderbookDepthAccumulator: Added orderbook, sampleCount={}, spread={}, bidDepth={}, askDepth={}", 
            sampleCount, metrics.spread, metrics.totalBidDepth, metrics.totalAskDepth);

        // Delegate to specialized services (using lazy getters)
        if (previousOrderbook != null) {
            getSpoofingService().detectSpoofing(previousOrderbook, currentOrderbook);
        }

        trackIcebergPatterns(orderbook);
    }
    
    /**
     * Check if this accumulator has received any orderbook data
     */
    public boolean hasData() {
        return currentOrderbook != null || sampleCount > 0;
    }

    private void accumulateMetrics(SnapshotMetrics metrics) {
        sampleCount++;
        if (metrics.spread != null) { spreadSum += metrics.spread; spreadCount++; }
        if (metrics.totalBidDepth != null) { totalBidDepthSum += metrics.totalBidDepth; totalBidDepthCount++; }
        if (metrics.totalAskDepth != null) { totalAskDepthSum += metrics.totalAskDepth; totalAskDepthCount++; }
        if (metrics.bidVWAP != null) { bidVwapSum += metrics.bidVWAP; bidVwapCount++; }
        if (metrics.askVWAP != null) { askVwapSum += metrics.askVWAP; askVwapCount++; }
        if (metrics.depthPressure != null) { depthPressureSum += metrics.depthPressure; depthPressureCount++; }
        if (metrics.weightedDepthImbalance != null) { weightedImbalanceSum += metrics.weightedDepthImbalance; weightedImbalanceCount++; }
        if (metrics.bidSlope != null) { bidSlopeSum += metrics.bidSlope; bidSlopeCount++; }
        if (metrics.askSlope != null) { askSlopeSum += metrics.askSlope; askSlopeCount++; }
        if (metrics.slopeRatio != null) { slopeRatioSum += metrics.slopeRatio; slopeRatioCount++; }
        if (metrics.level1Imbalance != null) { level1ImbSum += metrics.level1Imbalance; level1ImbCount++; }
        if (metrics.level2to5Imbalance != null) { level2to5ImbSum += metrics.level2to5Imbalance; level2to5ImbCount++; }
        if (metrics.level6to10Imbalance != null) { level6to10ImbSum += metrics.level6to10Imbalance; level6to10ImbCount++; }
        if (metrics.midPrice != null) { midPriceSum += metrics.midPrice; midPriceCount++; }
    }

    private SnapshotMetrics analyzeSnapshot(OrderBookSnapshot orderbook) {
        SnapshotMetrics metrics = new SnapshotMetrics();

        double midPrice = orderbook.getMidPrice();
        metrics.midPrice = midPrice > 0 ? midPrice : null;
        metrics.spread = orderbook.getSpread();

        List<OrderbookDepthData.DepthLevel> bidProfile =
            getDepthCalculator().buildDepthProfile(orderbook.getAllBids(), "BID", midPrice);
        List<OrderbookDepthData.DepthLevel> askProfile =
            getDepthCalculator().buildDepthProfile(orderbook.getAllAsks(), "ASK", midPrice);

        List<Double> cumulativeBidDepth = getDepthCalculator().calculateCumulativeDepth(bidProfile);
        List<Double> cumulativeAskDepth = getDepthCalculator().calculateCumulativeDepth(askProfile);

        Double totalBidDepth = cumulativeBidDepth.isEmpty() ? null :
            cumulativeBidDepth.get(cumulativeBidDepth.size() - 1);
        Double totalAskDepth = cumulativeAskDepth.isEmpty() ? null :
            cumulativeAskDepth.get(cumulativeAskDepth.size() - 1);

        Double bidVWAP = bidProfile.isEmpty() ? null : getDepthCalculator().calculateSideVWAP(bidProfile);
        Double askVWAP = askProfile.isEmpty() ? null : getDepthCalculator().calculateSideVWAP(askProfile);
        Double depthPressure = (metrics.midPrice != null && bidVWAP != null && askVWAP != null)
            ? (bidVWAP - askVWAP) / metrics.midPrice
            : null;

        Double weightedImbalance = (bidProfile.isEmpty() || askProfile.isEmpty()) ? null :
            getDepthCalculator().calculateWeightedDepthImbalance(bidProfile, askProfile);

        Double bidSlope = bidProfile.isEmpty() ? null : getDepthCalculator().calculateSlope(bidProfile);
        Double askSlope = askProfile.isEmpty() ? null : getDepthCalculator().calculateSlope(askProfile);
        Double slopeRatio = (askSlope != null && askSlope != 0 && bidSlope != null) ? bidSlope / askSlope : null;

        Double level1Imb = (bidProfile.isEmpty() || askProfile.isEmpty()) ? null :
            getDepthCalculator().calculateLevelImbalance(bidProfile, askProfile, 1, 1);
        Double level2to5Imb = (bidProfile.isEmpty() || askProfile.isEmpty()) ? null :
            getDepthCalculator().calculateLevelImbalance(bidProfile, askProfile, 2, 5);
        Double level6to10Imb = (bidProfile.isEmpty() || askProfile.isEmpty()) ? null :
            getDepthCalculator().calculateLevelImbalance(bidProfile, askProfile, 6, 10);

        metrics.bidProfile = bidProfile;
        metrics.askProfile = askProfile;
        metrics.cumulativeBidDepth = cumulativeBidDepth;
        metrics.cumulativeAskDepth = cumulativeAskDepth;
        metrics.totalBidDepth = totalBidDepth;
        metrics.totalAskDepth = totalAskDepth;
        metrics.bidVWAP = bidVWAP;
        metrics.askVWAP = askVWAP;
        metrics.depthPressure = depthPressure;
        metrics.weightedDepthImbalance = weightedImbalance;
        metrics.bidSlope = bidSlope;
        metrics.askSlope = askSlope;
        metrics.slopeRatio = slopeRatio;
        metrics.level1Imbalance = level1Imb;
        metrics.level2to5Imbalance = level2to5Imb;
        metrics.level6to10Imbalance = level6to10Imb;
        metrics.depthLevels = Math.min(bidProfile.size(), askProfile.size());

        return metrics;
    }

    private void trackIcebergPatterns(OrderBookSnapshot orderbook) {
        // Delegate to iceberg detection service (using lazy getter)
        if (orderbook.getAllBids() != null && !orderbook.getAllBids().isEmpty()) {
            getIcebergService().trackBidQuantity(orderbook.getAllBids().get(0).getQuantity());
        }

        if (orderbook.getAllAsks() != null && !orderbook.getAllAsks().isEmpty()) {
            getIcebergService().trackAskQuantity(orderbook.getAllAsks().get(0).getQuantity());
        }
    }

    public OrderbookDepthData toOrderbookDepthData() {
        log.debug("📊 OrderbookDepthAccumulator.toOrderbookDepthData(): sampleCount={}, lastMetrics={}", 
            sampleCount, lastMetrics != null ? "present" : "null");
        
        if (sampleCount == 0 || lastMetrics == null) {
            log.warn("⚠️ OrderbookDepthAccumulator: No data to output (sampleCount={}, lastMetrics={})", 
                sampleCount, lastMetrics != null ? "present" : "null");
            return OrderbookDepthData.builder()
                .isComplete(false)
                .build();
        }

        try {
            log.debug("📊 Computing averages: spreadCount={}, bidDepthCount={}, askDepthCount={}", 
                spreadCount, totalBidDepthCount, totalAskDepthCount);
            Boolean icebergBid = getIcebergService().detectIcebergBid();
            Boolean icebergAsk = getIcebergService().detectIcebergAsk();
            Double icebergProbBid = getIcebergService().calculateIcebergProbabilityBid();
            Double icebergProbAsk = getIcebergService().calculateIcebergProbabilityAsk();

            Integer spoofCount = getSpoofingService().getSpoofingCount();
            Boolean activeSpoofBid = getSpoofingService().isActiveSpoofingBid() ? true : null;
            Boolean activeSpoofAsk = getSpoofingService().isActiveSpoofingAsk() ? true : null;

            return OrderbookDepthData.builder()
                .bidProfile(lastMetrics.bidProfile)
                .askProfile(lastMetrics.askProfile)
                .cumulativeBidDepth(lastMetrics.cumulativeBidDepth)
                .cumulativeAskDepth(lastMetrics.cumulativeAskDepth)
                .totalBidDepth(totalBidDepthCount > 0 ? totalBidDepthSum / totalBidDepthCount : null)
                .totalAskDepth(totalAskDepthCount > 0 ? totalAskDepthSum / totalAskDepthCount : null)
                .bidVWAP(bidVwapCount > 0 ? bidVwapSum / bidVwapCount : null)
                .askVWAP(askVwapCount > 0 ? askVwapSum / askVwapCount : null)
                .depthPressure(depthPressureCount > 0 ? depthPressureSum / depthPressureCount : null)
                .weightedDepthImbalance(weightedImbalanceCount > 0 ? weightedImbalanceSum / weightedImbalanceCount : null)
                .level1Imbalance(level1ImbCount > 0 ? level1ImbSum / level1ImbCount : null)
                .level2to5Imbalance(level2to5ImbCount > 0 ? level2to5ImbSum / level2to5ImbCount : null)
                .level6to10Imbalance(level6to10ImbCount > 0 ? level6to10ImbSum / level6to10ImbCount : null)
                .bidSlope(bidSlopeCount > 0 ? bidSlopeSum / bidSlopeCount : null)
                .askSlope(askSlopeCount > 0 ? askSlopeSum / askSlopeCount : null)
                .slopeRatio(slopeRatioCount > 0 ? slopeRatioSum / slopeRatioCount : null)
                .icebergDetectedBid(icebergBid)
                .icebergDetectedAsk(icebergAsk)
                .icebergProbabilityBid(icebergProbBid)
                .icebergProbabilityAsk(icebergProbAsk)
                .spoofingEvents(getSpoofingService().getSpoofingEvents())
                .spoofingCountLast1Min(spoofCount)
                .activeSpoofingBid(activeSpoofBid)
                .activeSpoofingAsk(activeSpoofAsk)
                .timestamp(lastUpdateTimestamp)
                .midPrice(midPriceCount > 0 ? midPriceSum / midPriceCount : null)
                .spread(spreadCount > 0 ? spreadSum / spreadCount : null)
                .depthLevels(lastMetrics.depthLevels)
                .isComplete(true)
                .build();

        } catch (Exception e) {
            log.error("Failed to build orderbook depth data", e);
            return OrderbookDepthData.builder().isComplete(false).build();
        }
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    private static class SnapshotMetrics {
        private List<OrderbookDepthData.DepthLevel> bidProfile;
        private List<OrderbookDepthData.DepthLevel> askProfile;
        private List<Double> cumulativeBidDepth;
        private List<Double> cumulativeAskDepth;
        private Double totalBidDepth;
        private Double totalAskDepth;
        private Double bidVWAP;
        private Double askVWAP;
        private Double depthPressure;
        private Double weightedDepthImbalance;
        private Double bidSlope;
        private Double askSlope;
        private Double slopeRatio;
        private Double level1Imbalance;
        private Double level2to5Imbalance;
        private Double level6to10Imbalance;
        private Double midPrice;
        private Double spread;
        private Integer depthLevels;
    }
}
