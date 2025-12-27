package com.kotsin.consumer.domain.calculator;

import java.util.Map;

/**
 * OFICalculator - Order Flow Imbalance Calculator
 * 
 * Extracted from OrderbookAggregate.java for Single Responsibility.
 * 
 * Implements full-depth OFI calculation per Cont-Kukanov-Stoikov 2014.
 * OFI = Δbid - Δask (change in bid depth minus change in ask depth)
 * 
 * Interpretation:
 * - OFI > 0: Buying pressure (bids increasing, asks decreasing)
 * - OFI < 0: Selling pressure (asks increasing, bids decreasing)
 * - OFI ≈ 0: Balanced order flow
 * 
 * References:
 * - Cont, R., Kukanov, A., & Stoikov, S. (2014). "The Price Impact of Order Book Events"
 */
public class OFICalculator {

    /**
     * Calculate full-depth OFI from orderbook changes
     *
     * @param prevBid Previous bid depth map (price -> quantity)
     * @param currBid Current bid depth map
     * @param prevBestBid Previous best bid price
     * @param currBestBid Current best bid price
     * @param prevAsk Previous ask depth map
     * @param currAsk Current ask depth map
     * @param prevBestAsk Previous best ask price
     * @param currBestAsk Current best ask price
     * @return OFI value (positive = buying pressure, negative = selling pressure)
     */
    public static double calculate(
        Map<Double, Integer> prevBid, Map<Double, Integer> currBid,
        double prevBestBid, double currBestBid,
        Map<Double, Integer> prevAsk, Map<Double, Integer> currAsk,
        double prevBestAsk, double currBestAsk
    ) {
        if (prevBid == null || currBid == null || prevAsk == null || currAsk == null) {
            return 0.0;
        }

        double deltaBid = 0.0;
        double deltaAsk = 0.0;

        // Band limits: only consider levels within the touched spread
        double bidBand = Math.min(prevBestBid, currBestBid);
        double askBand = Math.max(prevBestAsk, currBestAsk);

        // Calculate bid side changes
        for (Map.Entry<Double, Integer> entry : currBid.entrySet()) {
            if (entry.getKey() >= bidBand) {
                int prevQty = prevBid.getOrDefault(entry.getKey(), 0);
                deltaBid += (entry.getValue() - prevQty);
            }
        }
        // Account for removed levels
        for (Map.Entry<Double, Integer> entry : prevBid.entrySet()) {
            if (entry.getKey() >= bidBand && !currBid.containsKey(entry.getKey())) {
                deltaBid -= entry.getValue();
            }
        }

        // Calculate ask side changes
        for (Map.Entry<Double, Integer> entry : currAsk.entrySet()) {
            if (entry.getKey() <= askBand) {
                int prevQty = prevAsk.getOrDefault(entry.getKey(), 0);
                deltaAsk += (entry.getValue() - prevQty);
            }
        }
        // Account for removed levels
        for (Map.Entry<Double, Integer> entry : prevAsk.entrySet()) {
            if (entry.getKey() <= askBand && !currAsk.containsKey(entry.getKey())) {
                deltaAsk -= entry.getValue();
            }
        }

        return deltaBid - deltaAsk;
    }

    /**
     * Calculate simple OFI from best bid/ask changes only
     * Faster but less accurate than full-depth calculation
     *
     * @param prevBestBidQty Previous best bid quantity
     * @param currBestBidQty Current best bid quantity
     * @param prevBestAskQty Previous best ask quantity
     * @param currBestAskQty Current best ask quantity
     * @return Simple OFI value
     */
    public static double calculateSimple(
        int prevBestBidQty, int currBestBidQty,
        int prevBestAskQty, int currBestAskQty
    ) {
        int deltaBid = currBestBidQty - prevBestBidQty;
        int deltaAsk = currBestAskQty - prevBestAskQty;
        return deltaBid - deltaAsk;
    }

    /**
     * Normalize OFI to [-1, 1] range for comparison across instruments
     *
     * @param ofi Raw OFI value
     * @param avgDepth Average total book depth
     * @return Normalized OFI between -1 and 1
     */
    public static double normalize(double ofi, double avgDepth) {
        if (avgDepth <= 0) return 0.0;
        return Math.max(-1.0, Math.min(1.0, ofi / avgDepth));
    }

    /**
     * Classify OFI into pressure category
     *
     * @param normalizedOfi Normalized OFI value (-1 to 1)
     * @return Pressure category string
     */
    public static String classify(double normalizedOfi) {
        if (normalizedOfi >= 0.5) {
            return "STRONG_BUYING";
        } else if (normalizedOfi >= 0.2) {
            return "BUYING";
        } else if (normalizedOfi >= -0.2) {
            return "NEUTRAL";
        } else if (normalizedOfi >= -0.5) {
            return "SELLING";
        } else {
            return "STRONG_SELLING";
        }
    }

    /**
     * Calculate cumulative OFI over multiple snapshots
     *
     * @param cumulativeOFI Previous cumulative OFI
     * @param newOFI New OFI observation
     * @param decay Decay factor (0.9-0.99 typical)
     * @return Updated cumulative OFI with decay
     */
    public static double accumulateWithDecay(double cumulativeOFI, double newOFI, double decay) {
        return cumulativeOFI * decay + newOFI;
    }
}
