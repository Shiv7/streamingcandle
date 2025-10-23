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
    @JsonIgnore
    private SnapshotMetrics lastMetrics;

    @JsonIgnore private final MetricAccumulator spreadAcc = new MetricAccumulator();
    @JsonIgnore private final MetricAccumulator totalBidDepthAcc = new MetricAccumulator();
    @JsonIgnore private final MetricAccumulator totalAskDepthAcc = new MetricAccumulator();
    @JsonIgnore private final MetricAccumulator bidVwapAcc = new MetricAccumulator();
    @JsonIgnore private final MetricAccumulator askVwapAcc = new MetricAccumulator();
    @JsonIgnore private final MetricAccumulator depthPressureAcc = new MetricAccumulator();
    @JsonIgnore private final MetricAccumulator weightedImbalanceAcc = new MetricAccumulator();
    @JsonIgnore private final MetricAccumulator bidSlopeAcc = new MetricAccumulator();
    @JsonIgnore private final MetricAccumulator askSlopeAcc = new MetricAccumulator();
    @JsonIgnore private final MetricAccumulator slopeRatioAcc = new MetricAccumulator();
    @JsonIgnore private final MetricAccumulator level1ImbAcc = new MetricAccumulator();
    @JsonIgnore private final MetricAccumulator level2to5ImbAcc = new MetricAccumulator();
    @JsonIgnore private final MetricAccumulator level6to10ImbAcc = new MetricAccumulator();
    @JsonIgnore private final MetricAccumulator midPriceAcc = new MetricAccumulator();
    @JsonIgnore private long sampleCount = 0L;

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
            return;
        }

        orderbook.parseDetails();
        previousOrderbook = currentOrderbook;
        currentOrderbook = orderbook;
        lastUpdateTimestamp = orderbook.getTimestamp();

        SnapshotMetrics metrics = analyzeSnapshot(orderbook);
        lastMetrics = metrics;
        accumulateMetrics(metrics);

        // Delegate to specialized services (using lazy getters)
        if (previousOrderbook != null) {
            getSpoofingService().detectSpoofing(previousOrderbook, currentOrderbook);
        }

        trackIcebergPatterns(orderbook);
    }

    private void accumulateMetrics(SnapshotMetrics metrics) {
        sampleCount++;
        spreadAcc.add(metrics.spread);
        totalBidDepthAcc.add(metrics.totalBidDepth);
        totalAskDepthAcc.add(metrics.totalAskDepth);
        bidVwapAcc.add(metrics.bidVWAP);
        askVwapAcc.add(metrics.askVWAP);
        depthPressureAcc.add(metrics.depthPressure);
        weightedImbalanceAcc.add(metrics.weightedDepthImbalance);
        bidSlopeAcc.add(metrics.bidSlope);
        askSlopeAcc.add(metrics.askSlope);
        slopeRatioAcc.add(metrics.slopeRatio);
        level1ImbAcc.add(metrics.level1Imbalance);
        level2to5ImbAcc.add(metrics.level2to5Imbalance);
        level6to10ImbAcc.add(metrics.level6to10Imbalance);
        midPriceAcc.add(metrics.midPrice);
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
        if (sampleCount == 0 || lastMetrics == null) {
            return OrderbookDepthData.builder()
                .isComplete(false)
                .build();
        }

        try {
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
                .totalBidDepth(totalBidDepthAcc.average())
                .totalAskDepth(totalAskDepthAcc.average())
                .bidVWAP(bidVwapAcc.average())
                .askVWAP(askVwapAcc.average())
                .depthPressure(depthPressureAcc.average())
                .weightedDepthImbalance(weightedImbalanceAcc.average())
                .level1Imbalance(level1ImbAcc.average())
                .level2to5Imbalance(level2to5ImbAcc.average())
                .level6to10Imbalance(level6to10ImbAcc.average())
                .bidSlope(bidSlopeAcc.average())
                .askSlope(askSlopeAcc.average())
                .slopeRatio(slopeRatioAcc.average())
                .icebergDetectedBid(icebergBid)
                .icebergDetectedAsk(icebergAsk)
                .icebergProbabilityBid(icebergProbBid)
                .icebergProbabilityAsk(icebergProbAsk)
                .spoofingEvents(getSpoofingService().getSpoofingEvents())
                .spoofingCountLast1Min(spoofCount)
                .activeSpoofingBid(activeSpoofBid)
                .activeSpoofingAsk(activeSpoofAsk)
                .timestamp(lastUpdateTimestamp)
                .midPrice(midPriceAcc.average())
                .spread(spreadAcc.average())
                .depthLevels(lastMetrics.depthLevels)
                .isComplete(true)
                .build();

        } catch (Exception e) {
            log.error("Failed to build orderbook depth data", e);
            return OrderbookDepthData.builder().isComplete(false).build();
        }
    }

    private static class MetricAccumulator {
        private double sum = 0.0;
        private long count = 0L;

        void add(Double value) {
            if (value != null) {
                sum += value;
                count++;
            }
        }

        Double average() {
            return count > 0 ? sum / count : null;
        }
    }

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
