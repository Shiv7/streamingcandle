package com.kotsin.consumer.processor.service;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Service for detecting iceberg orders in the orderbook
 * Single Responsibility: Iceberg order detection logic
 */
@Slf4j
public class IcebergDetectionService {

    private static final int HISTORY_SIZE = 20;
    private static final double CV_THRESHOLD = 0.1;
    private static final int MIN_SIZE_THRESHOLD = 1000;

    private final List<Integer> recentBidQuantities = new ArrayList<>();
    private final List<Integer> recentAskQuantities = new ArrayList<>();

    public void trackBidQuantity(Integer quantity) {
        recentBidQuantities.add(quantity);
        if (recentBidQuantities.size() > HISTORY_SIZE) {
            recentBidQuantities.remove(0);
        }
    }

    public void trackAskQuantity(Integer quantity) {
        recentAskQuantities.add(quantity);
        if (recentAskQuantities.size() > HISTORY_SIZE) {
            recentAskQuantities.remove(0);
        }
    }

    public boolean detectIcebergBid() {
        return detectIceberg(recentBidQuantities);
    }

    public boolean detectIcebergAsk() {
        return detectIceberg(recentAskQuantities);
    }

    public double calculateIcebergProbabilityBid() {
        return calculateIcebergProbability(recentBidQuantities);
    }

    public double calculateIcebergProbabilityAsk() {
        return calculateIcebergProbability(recentAskQuantities);
    }

    private boolean detectIceberg(List<Integer> recentQuantities) {
        if (recentQuantities.size() < 10) return false;

        // Iceberg: unusually consistent quantities (low variance)
        double mean = recentQuantities.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        double variance = recentQuantities.stream()
            .mapToDouble(q -> Math.pow(q - mean, 2))
            .average().orElse(0.0);

        double stdDev = Math.sqrt(variance);
        double cv = mean > 0 ? stdDev / mean : 0.0;  // Coefficient of variation

        // Low CV (< 0.1) suggests iceberg (too uniform)
        return cv < CV_THRESHOLD && mean > MIN_SIZE_THRESHOLD;
    }

    private double calculateIcebergProbability(List<Integer> recentQuantities) {
        if (recentQuantities.size() < 10) return 0.0;

        double mean = recentQuantities.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        double variance = recentQuantities.stream()
            .mapToDouble(q -> Math.pow(q - mean, 2))
            .average().orElse(0.0);

        double stdDev = Math.sqrt(variance);
        double cv = mean > 0 ? stdDev / mean : 1.0;

        // Convert CV to probability (lower CV = higher probability)
        // CV of 0 → prob 1.0, CV of 0.5+ → prob 0
        return Math.max(0.0, Math.min(1.0, (0.5 - cv) * 2));
    }
}
