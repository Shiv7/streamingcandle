package com.kotsin.consumer.processor.service;

import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.OrderbookDepthData;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Service for calculating orderbook depth metrics
 * Single Responsibility: Depth calculations (VWAP, slopes, imbalances)
 */
@Slf4j
public class OrderbookDepthCalculator {

    private static final int MAX_DEPTH_LEVELS = 10;

    /**
     * Build depth profile for one side of the book
     */
    public List<OrderbookDepthData.DepthLevel> buildDepthProfile(
        List<OrderBookSnapshot.OrderBookLevel> levels,
        String side,
        double midPrice
    ) {
        if (levels == null || levels.isEmpty()) {
            return new ArrayList<>();
        }

        List<OrderbookDepthData.DepthLevel> profile = new ArrayList<>();
        int totalQty = levels.stream().mapToInt(OrderBookSnapshot.OrderBookLevel::getQuantity).sum();

        for (int i = 0; i < Math.min(levels.size(), MAX_DEPTH_LEVELS); i++) {
            OrderBookSnapshot.OrderBookLevel level = levels.get(i);
            double distanceFromMid = midPrice > 0 ?
                Math.abs(level.getPrice() - midPrice) / midPrice * 10000 : 0.0;  // in bps

            profile.add(OrderbookDepthData.DepthLevel.builder()
                .level(i + 1)
                .price(level.getPrice())
                .quantity(level.getQuantity())
                .numberOfOrders(level.getNumberOfOrders())
                .distanceFromMid(distanceFromMid)
                .percentOfTotalDepth(totalQty > 0 ? (double) level.getQuantity() / totalQty * 100 : 0.0)
                .build());
        }

        return profile;
    }

    /**
     * Calculate cumulative depth
     */
    public List<Double> calculateCumulativeDepth(List<OrderbookDepthData.DepthLevel> profile) {
        List<Double> cumulative = new ArrayList<>();
        double sum = 0.0;

        for (OrderbookDepthData.DepthLevel level : profile) {
            sum += level.getQuantity();
            cumulative.add(sum);
        }

        return cumulative;
    }

    /**
     * Calculate VWAP of one side of the book
     */
    public double calculateSideVWAP(List<OrderbookDepthData.DepthLevel> profile) {
        if (profile.isEmpty()) return 0.0;

        double totalValue = 0.0;
        double totalQty = 0.0;

        for (OrderbookDepthData.DepthLevel level : profile) {
            totalValue += level.getPrice() * level.getQuantity();
            totalQty += level.getQuantity();
        }

        return totalQty > 0 ? totalValue / totalQty : 0.0;
    }

    /**
     * Calculate weighted depth imbalance (closer levels get higher weight)
     */
    public double calculateWeightedDepthImbalance(
        List<OrderbookDepthData.DepthLevel> bidProfile,
        List<OrderbookDepthData.DepthLevel> askProfile
    ) {
        double weightedBid = 0.0;
        double weightedAsk = 0.0;

        // Weight by inverse of distance from mid (closer levels get higher weight)
        for (OrderbookDepthData.DepthLevel level : bidProfile) {
            double weight = level.getDistanceFromMid() > 0 ?
                1.0 / (1.0 + level.getDistanceFromMid()) : 1.0;
            weightedBid += level.getQuantity() * weight;
        }

        for (OrderbookDepthData.DepthLevel level : askProfile) {
            double weight = level.getDistanceFromMid() > 0 ?
                1.0 / (1.0 + level.getDistanceFromMid()) : 1.0;
            weightedAsk += level.getQuantity() * weight;
        }

        double total = weightedBid + weightedAsk;
        return total > 0 ? (weightedBid - weightedAsk) / total : 0.0;
    }

    /**
     * Calculate imbalance for specific level range
     */
    public double calculateLevelImbalance(
        List<OrderbookDepthData.DepthLevel> bidProfile,
        List<OrderbookDepthData.DepthLevel> askProfile,
        int startLevel, int endLevel
    ) {
        double bidQty = bidProfile.stream()
            .filter(l -> l.getLevel() >= startLevel && l.getLevel() <= endLevel)
            .mapToInt(OrderbookDepthData.DepthLevel::getQuantity)
            .sum();

        double askQty = askProfile.stream()
            .filter(l -> l.getLevel() >= startLevel && l.getLevel() <= endLevel)
            .mapToInt(OrderbookDepthData.DepthLevel::getQuantity)
            .sum();

        double total = bidQty + askQty;
        return total > 0 ? (bidQty - askQty) / total : 0.0;
    }

    /**
     * Calculate slope using linear regression (quantity vs level)
     */
    public double calculateSlope(List<OrderbookDepthData.DepthLevel> profile) {
        if (profile.size() < 2) return 0.0;

        // Linear regression: quantity vs level
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        int n = profile.size();

        for (OrderbookDepthData.DepthLevel level : profile) {
            double x = level.getLevel();
            double y = level.getQuantity();
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        // Slope = (n*Σxy - Σx*Σy) / (n*Σx² - (Σx)²)
        double denominator = n * sumX2 - sumX * sumX;
        return denominator != 0 ? (n * sumXY - sumX * sumY) / denominator : 0.0;
    }
}
