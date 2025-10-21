package com.kotsin.consumer.processor.service;

import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.OrderbookDepthData;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.Comparator;

/**
 * Service for detecting spoofing activity in the orderbook
 * Single Responsibility: Spoofing detection logic
 */
@Slf4j
public class SpoofingDetectionService {

    private static final long SPOOF_DURATION_THRESHOLD_MS = 5000;  // 5 seconds
    private static final double SPOOF_SIZE_THRESHOLD = 0.3;  // 30% of total depth
    private static final int MAX_TRACKING_ENTRIES = 20;  // Prevent unbounded growth

    private final List<OrderbookDepthData.SpoofingEvent> spoofingEvents = new ArrayList<>();
    private final Map<Double, SpoofDetectionState> bidSpoofTracking = new HashMap<>();
    private final Map<Double, SpoofDetectionState> askSpoofTracking = new HashMap<>();

    public void detectSpoofing(
        OrderBookSnapshot previousOrderbook,
        OrderBookSnapshot currentOrderbook
    ) {
        if (previousOrderbook == null || currentOrderbook == null) {
            return;
        }

        long currentTime = currentOrderbook.getTimestamp();

        // Check for large orders that disappeared on bid side
        if (previousOrderbook.getAllBids() != null && currentOrderbook.getAllBids() != null) {
            detectSpoofingOneSide(
                previousOrderbook.getAllBids(),
                currentOrderbook.getAllBids(),
                "BID",
                currentTime,
                bidSpoofTracking
            );
        }

        // Check for large orders that disappeared on ask side
        if (previousOrderbook.getAllAsks() != null && currentOrderbook.getAllAsks() != null) {
            detectSpoofingOneSide(
                previousOrderbook.getAllAsks(),
                currentOrderbook.getAllAsks(),
                "ASK",
                currentTime,
                askSpoofTracking
            );
        }

        // Clean up old spoofing events (older than 1 minute)
        long oneMinuteAgo = currentTime - 60000;
        spoofingEvents.removeIf(event -> event.getTimestamp() < oneMinuteAgo);
        
        // Clean up stale tracking entries (prevent unbounded growth)
        cleanupStaleTracking(bidSpoofTracking, currentTime);
        cleanupStaleTracking(askSpoofTracking, currentTime);
    }
    
    private void cleanupStaleTracking(Map<Double, SpoofDetectionState> tracking, long currentTime) {
        // Remove entries older than 10 seconds
        tracking.entrySet().removeIf(entry -> 
            (currentTime - entry.getValue().firstSeenTime) > 10000);
        
        // If still over limit, remove oldest entries
        if (tracking.size() > MAX_TRACKING_ENTRIES) {
            List<Map.Entry<Double, SpoofDetectionState>> entries = new ArrayList<>(tracking.entrySet());
            entries.sort(Comparator.comparingLong(e -> e.getValue().firstSeenTime));
            
            int toRemove = tracking.size() - MAX_TRACKING_ENTRIES;
            for (int i = 0; i < toRemove; i++) {
                tracking.remove(entries.get(i).getKey());
            }
        }
    }

    private void detectSpoofingOneSide(
        List<OrderBookSnapshot.OrderBookLevel> prevLevels,
        List<OrderBookSnapshot.OrderBookLevel> currLevels,
        String side,
        long currentTime,
        Map<Double, SpoofDetectionState> tracking
    ) {
        // Calculate total depth for threshold
        double totalDepth = prevLevels.stream()
            .mapToInt(OrderBookSnapshot.OrderBookLevel::getQuantity)
            .sum();

        // Check each previous level
        for (OrderBookSnapshot.OrderBookLevel prevLevel : prevLevels) {
            double price = prevLevel.getPrice();
            int quantity = prevLevel.getQuantity();

            // Is this a large order (> 30% of total depth)?
            if (quantity > totalDepth * SPOOF_SIZE_THRESHOLD) {
                // Track when we first saw this large order
                if (!tracking.containsKey(price)) {
                    tracking.put(price, new SpoofDetectionState(currentTime, quantity));
                }

                // Check if it disappeared in current orderbook
                boolean foundInCurrent = currLevels.stream()
                    .anyMatch(level -> Math.abs(level.getPrice() - price) < 0.01 &&
                                      level.getQuantity() >= quantity * 0.5);

                if (!foundInCurrent) {
                    // Large order disappeared - potential spoof
                    SpoofDetectionState state = tracking.get(price);
                    long duration = currentTime - state.firstSeenTime;

                    if (duration < SPOOF_DURATION_THRESHOLD_MS) {
                        // Disappeared quickly - likely a spoof
                        OrderbookDepthData.SpoofingEvent event = OrderbookDepthData.SpoofingEvent.builder()
                            .timestamp(currentTime)
                            .side(side)
                            .price(price)
                            .quantity(quantity)
                            .durationMs(duration)
                            .classification("CONFIRMED_SPOOF")
                            .build();

                        spoofingEvents.add(event);
                        log.warn("🚨 Spoofing detected: {} @ {} qty={} duration={}ms",
                            side, price, quantity, duration);
                    }

                    tracking.remove(price);
                }
            }
        }
    }

    public List<OrderbookDepthData.SpoofingEvent> getSpoofingEvents() {
        return new ArrayList<>(spoofingEvents);
    }

    public int getSpoofingCount() {
        return spoofingEvents.size();
    }

    public boolean isActiveSpoofingBid() {
        return isActiveSpoofing(bidSpoofTracking);
    }

    public boolean isActiveSpoofingAsk() {
        return isActiveSpoofing(askSpoofTracking);
    }

    private boolean isActiveSpoofing(Map<Double, SpoofDetectionState> tracking) {
        long now = System.currentTimeMillis();
        return tracking.values().stream()
            .anyMatch(state -> (now - state.firstSeenTime) < SPOOF_DURATION_THRESHOLD_MS);
    }

    @Data
    @AllArgsConstructor
    private static class SpoofDetectionState {
        private final long firstSeenTime;
        private final int quantity;
    }
}
