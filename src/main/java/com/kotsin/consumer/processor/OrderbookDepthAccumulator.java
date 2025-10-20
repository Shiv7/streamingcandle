package com.kotsin.consumer.processor;

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
 */
@Data
@Slf4j
public class OrderbookDepthAccumulator {

    // Composed services (Dependency Injection ready)
    private final IcebergDetectionService icebergDetectionService = new IcebergDetectionService();
    private final SpoofingDetectionService spoofingDetectionService = new SpoofingDetectionService();
    private final OrderbookDepthCalculator depthCalculator = new OrderbookDepthCalculator();

    // Current orderbook state
    private OrderBookSnapshot currentOrderbook;
    private OrderBookSnapshot previousOrderbook;
    private Long lastUpdateTimestamp;

    public void addOrderbook(OrderBookSnapshot orderbook) {
        if (orderbook == null || !orderbook.isValid()) {
            return;
        }

        orderbook.parseDetails();
        previousOrderbook = currentOrderbook;
        currentOrderbook = orderbook;
        lastUpdateTimestamp = orderbook.getTimestamp();

        // Delegate to specialized services
        if (previousOrderbook != null) {
            spoofingDetectionService.detectSpoofing(previousOrderbook, currentOrderbook);
        }

        trackIcebergPatterns(orderbook);
    }

    private void trackIcebergPatterns(OrderBookSnapshot orderbook) {
        // Delegate to iceberg detection service
        if (orderbook.getAllBids() != null && !orderbook.getAllBids().isEmpty()) {
            icebergDetectionService.trackBidQuantity(orderbook.getAllBids().get(0).getQuantity());
        }

        if (orderbook.getAllAsks() != null && !orderbook.getAllAsks().isEmpty()) {
            icebergDetectionService.trackAskQuantity(orderbook.getAllAsks().get(0).getQuantity());
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

            // Delegate profile building to calculator
            List<OrderbookDepthData.DepthLevel> bidProfile =
                depthCalculator.buildDepthProfile(currentOrderbook.getAllBids(), "BID", midPrice);
            List<OrderbookDepthData.DepthLevel> askProfile =
                depthCalculator.buildDepthProfile(currentOrderbook.getAllAsks(), "ASK", midPrice);

            // Delegate calculations to calculator
            List<Double> cumulativeBidDepth = depthCalculator.calculateCumulativeDepth(bidProfile);
            List<Double> cumulativeAskDepth = depthCalculator.calculateCumulativeDepth(askProfile);

            double totalBidDepth = cumulativeBidDepth.isEmpty() ? 0.0 :
                cumulativeBidDepth.get(cumulativeBidDepth.size() - 1);
            double totalAskDepth = cumulativeAskDepth.isEmpty() ? 0.0 :
                cumulativeAskDepth.get(cumulativeAskDepth.size() - 1);

            double bidVWAP = depthCalculator.calculateSideVWAP(bidProfile);
            double askVWAP = depthCalculator.calculateSideVWAP(askProfile);
            double depthPressure = midPrice > 0 ? (bidVWAP - askVWAP) / midPrice : 0.0;

            double weightedImbalance = depthCalculator.calculateWeightedDepthImbalance(bidProfile, askProfile);

            double bidSlope = depthCalculator.calculateSlope(bidProfile);
            double askSlope = depthCalculator.calculateSlope(askProfile);
            double slopeRatio = askSlope != 0 ? bidSlope / askSlope : 0.0;

            // Get results from detection services
            boolean icebergBid = icebergDetectionService.detectIcebergBid();
            boolean icebergAsk = icebergDetectionService.detectIcebergAsk();
            double icebergProbBid = icebergDetectionService.calculateIcebergProbabilityBid();
            double icebergProbAsk = icebergDetectionService.calculateIcebergProbabilityAsk();

            double level1Imb = depthCalculator.calculateLevelImbalance(bidProfile, askProfile, 1, 1);
            double level2to5Imb = depthCalculator.calculateLevelImbalance(bidProfile, askProfile, 2, 5);
            double level6to10Imb = depthCalculator.calculateLevelImbalance(bidProfile, askProfile, 6, 10);

            int spoofCount = spoofingDetectionService.getSpoofingCount();
            boolean activeSpoofBid = spoofingDetectionService.isActiveSpoofingBid();
            boolean activeSpoofAsk = spoofingDetectionService.isActiveSpoofingAsk();

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
                .spoofingEvents(spoofingDetectionService.getSpoofingEvents())
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
