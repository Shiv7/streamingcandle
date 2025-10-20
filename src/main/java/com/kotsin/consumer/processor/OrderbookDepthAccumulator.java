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
    // These are lazily initialized on first access after deserialization
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

            double totalBidDepth = cumulativeBidDepth.isEmpty() ? 0.0 :
                cumulativeBidDepth.get(cumulativeBidDepth.size() - 1);
            double totalAskDepth = cumulativeAskDepth.isEmpty() ? 0.0 :
                cumulativeAskDepth.get(cumulativeAskDepth.size() - 1);

            double bidVWAP = getDepthCalculator().calculateSideVWAP(bidProfile);
            double askVWAP = getDepthCalculator().calculateSideVWAP(askProfile);
            double depthPressure = midPrice > 0 ? (bidVWAP - askVWAP) / midPrice : 0.0;

            double weightedImbalance = getDepthCalculator().calculateWeightedDepthImbalance(bidProfile, askProfile);

            double bidSlope = getDepthCalculator().calculateSlope(bidProfile);
            double askSlope = getDepthCalculator().calculateSlope(askProfile);
            double slopeRatio = askSlope != 0 ? bidSlope / askSlope : 0.0;

            // Get results from detection services (using lazy getters)
            boolean icebergBid = getIcebergService().detectIcebergBid();
            boolean icebergAsk = getIcebergService().detectIcebergAsk();
            double icebergProbBid = getIcebergService().calculateIcebergProbabilityBid();
            double icebergProbAsk = getIcebergService().calculateIcebergProbabilityAsk();

            double level1Imb = getDepthCalculator().calculateLevelImbalance(bidProfile, askProfile, 1, 1);
            double level2to5Imb = getDepthCalculator().calculateLevelImbalance(bidProfile, askProfile, 2, 5);
            double level6to10Imb = getDepthCalculator().calculateLevelImbalance(bidProfile, askProfile, 6, 10);

            int spoofCount = getSpoofingService().getSpoofingCount();
            boolean activeSpoofBid = getSpoofingService().isActiveSpoofingBid();
            boolean activeSpoofAsk = getSpoofingService().isActiveSpoofingAsk();

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
