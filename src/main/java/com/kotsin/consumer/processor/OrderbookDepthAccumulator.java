package com.kotsin.consumer.processor;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.OrderbookDepthData;
import com.kotsin.consumer.processor.service.IcebergDetectionService;
import com.kotsin.consumer.processor.service.OrderbookDepthCalculator;
import com.kotsin.consumer.processor.service.SpoofingDetectionService;
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
    // IcebergDetectionService is serialized to preserve history across restarts
    // SpoofingDetectionService and OrderbookDepthCalculator are stateless, lazily initialized
    private IcebergDetectionService icebergDetectionService;
    
    @JsonIgnore
    private transient SpoofingDetectionService spoofingDetectionService;
    
    @JsonIgnore
    private transient OrderbookDepthCalculator depthCalculator;

    // Current orderbook state
    private OrderBookSnapshot currentOrderbook;
    private OrderBookSnapshot previousOrderbook;
    private Long lastUpdateTimestamp;

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

        // Delegate to specialized services (using lazy getters)
        if (previousOrderbook != null) {
            getSpoofingService().detectSpoofing(previousOrderbook, currentOrderbook);
        }

        trackIcebergPatterns(orderbook);
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
        if (currentOrderbook == null || !currentOrderbook.isValid()) {
            return OrderbookDepthData.builder()
                .isComplete(false)
                .build();
        }

        try {
            double midPrice = currentOrderbook.getMidPrice();

            // Delegate profile building to calculator (using lazy getter)
            List<OrderbookDepthData.DepthLevel> bidProfile =
                getDepthCalculator().buildDepthProfile(currentOrderbook.getAllBids(), "BID", midPrice);
            List<OrderbookDepthData.DepthLevel> askProfile =
                getDepthCalculator().buildDepthProfile(currentOrderbook.getAllAsks(), "ASK", midPrice);

            // Delegate calculations to calculator (using lazy getter)
            List<Double> cumulativeBidDepth = getDepthCalculator().calculateCumulativeDepth(bidProfile);
            List<Double> cumulativeAskDepth = getDepthCalculator().calculateCumulativeDepth(askProfile);

            Double totalBidDepth = cumulativeBidDepth.isEmpty() ? null :
                cumulativeBidDepth.get(cumulativeBidDepth.size() - 1);
            Double totalAskDepth = cumulativeAskDepth.isEmpty() ? null :
                cumulativeAskDepth.get(cumulativeAskDepth.size() - 1);

            Double bidVWAP = bidProfile.isEmpty() ? null : getDepthCalculator().calculateSideVWAP(bidProfile);
            Double askVWAP = askProfile.isEmpty() ? null : getDepthCalculator().calculateSideVWAP(askProfile);
            Double depthPressure = (midPrice > 0 && bidVWAP != null && askVWAP != null) ? 
                (bidVWAP - askVWAP) / midPrice : null;

            Double weightedImbalance = (bidProfile.isEmpty() || askProfile.isEmpty()) ? null :
                getDepthCalculator().calculateWeightedDepthImbalance(bidProfile, askProfile);

            Double bidSlope = bidProfile.isEmpty() ? null : getDepthCalculator().calculateSlope(bidProfile);
            Double askSlope = askProfile.isEmpty() ? null : getDepthCalculator().calculateSlope(askProfile);
            Double slopeRatio = (askSlope != null && askSlope != 0 && bidSlope != null) ? bidSlope / askSlope : null;

            // Get results from detection services (using lazy getters)
            Boolean icebergBid = getIcebergService().detectIcebergBid();
            Boolean icebergAsk = getIcebergService().detectIcebergAsk();
            Double icebergProbBid = getIcebergService().calculateIcebergProbabilityBid();
            Double icebergProbAsk = getIcebergService().calculateIcebergProbabilityAsk();

            Double level1Imb = (bidProfile.isEmpty() || askProfile.isEmpty()) ? null :
                getDepthCalculator().calculateLevelImbalance(bidProfile, askProfile, 1, 1);
            Double level2to5Imb = (bidProfile.isEmpty() || askProfile.isEmpty()) ? null :
                getDepthCalculator().calculateLevelImbalance(bidProfile, askProfile, 2, 5);
            Double level6to10Imb = (bidProfile.isEmpty() || askProfile.isEmpty()) ? null :
                getDepthCalculator().calculateLevelImbalance(bidProfile, askProfile, 6, 10);

            Integer spoofCount = getSpoofingService().getSpoofingCount();
            Boolean activeSpoofBid = getSpoofingService().isActiveSpoofingBid() ? true : null;
            Boolean activeSpoofAsk = getSpoofingService().isActiveSpoofingAsk() ? true : null;

            return OrderbookDepthData.builder()
                .bidProfile(bidProfile)
                .askProfile(askProfile)
                .cumulativeBidDepth(cumulativeBidDepth)
                .cumulativeAskDepth(cumulativeAskDepth)
                .totalBidDepth(totalBidDepth)
                .totalAskDepth(totalAskDepth)
                .bidVWAP(bidVWAP)
                .askVWAP(askVWAP)
                .depthPressure(depthPressure)
                .weightedDepthImbalance(weightedImbalance)
                .level1Imbalance(level1Imb)
                .level2to5Imbalance(level2to5Imb)
                .level6to10Imbalance(level6to10Imb)
                .bidSlope(bidSlope)
                .askSlope(askSlope)
                .slopeRatio(slopeRatio)
                .icebergDetectedBid(icebergBid)
                .icebergDetectedAsk(icebergAsk)
                .icebergProbabilityBid(icebergProbBid)
                .icebergProbabilityAsk(icebergProbAsk)
                .spoofingEvents(getSpoofingService().getSpoofingEvents())
                .spoofingCountLast1Min(spoofCount)
                .activeSpoofingBid(activeSpoofBid)
                .activeSpoofingAsk(activeSpoofAsk)
                .timestamp(currentOrderbook.getTimestamp())
                .midPrice(midPrice)
                .spread(currentOrderbook.getSpread())
                .depthLevels(Math.min(bidProfile.size(), askProfile.size()))
                .isComplete(true)
                .build();

        } catch (Exception e) {
            log.error("Failed to build orderbook depth data", e);
            return OrderbookDepthData.builder().isComplete(false).build();
        }
    }
}
