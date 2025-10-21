package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Order Book Snapshot model
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderBookSnapshot {

    @JsonProperty("Exch")
    @JsonAlias({"exchange"})
    private String exchange;

    @JsonProperty("ExchType")
    @JsonAlias({"exchangeType"})
    private String exchangeType;

    @JsonProperty("Token")
    @JsonAlias({"token"})
    private String token;

    // Backward-compat arrays (may be absent)
    @JsonAlias({"bidRate"})
    private List<Double> bidRate;
    @JsonAlias({"bidQty"})
    private List<Long> bidQty;
    @JsonAlias({"offRate","askRate"})
    private List<Double> offRate;
    @JsonAlias({"offQty","askQty"})
    private List<Long> offQty;

    @JsonProperty("TBidQ")
    @JsonAlias({"totalBidQty"})
    private Long totalBidQty;

    @JsonProperty("TOffQ")
    @JsonAlias({"totalOffQty"})
    private Long totalOffQty;

    @JsonProperty("companyName")
    private String companyName;

    @JsonProperty("receivedTimestamp")
    private Long receivedTimestamp;

    // NEW: Object array format (bids/asks from new producer)
    @JsonProperty("bids")
    private List<OrderBookLevel> bids;
    
    @JsonProperty("asks")
    private List<OrderBookLevel> asks;

    // Parsed details (unified view - combines both formats)
    private List<OrderBookLevel> allBids;
    private List<OrderBookLevel> allAsks;

    /**
     * Order Book Level - represents a single price level in the order book
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderBookLevel {
        @JsonProperty("Price")
        private double price;
        @JsonProperty("Quantity")
        private int quantity;
        @JsonProperty("NumberOfOrders")
        private int numberOfOrders;
        // Flags in producer (ignored for now): bid/ask booleans
    }

    /**
     * Parse raw bid/ask data into OrderBookLevel objects
     * Handles BOTH formats:
     * 1. NEW: bids/asks arrays of objects (with Price, Quantity, NumberOfOrders)
     * 2. OLD: bidRate/bidQty/offRate/offQty separate arrays
     */
    public void parseDetails() {
        // Priority 1: Use new format (bids/asks objects) if available
        if (bids != null && !bids.isEmpty()) {
            allBids = new ArrayList<>(bids);
        } 
        // Fallback: Use old format (bidRate/bidQty arrays)
        else if (allBids == null && bidRate != null && bidQty != null) {
            allBids = new ArrayList<>();
            int minSize = Math.min(bidRate.size(), bidQty.size());
            for (int i = 0; i < minSize; i++) {
                allBids.add(OrderBookLevel.builder()
                    .price(bidRate.get(i))
                    .quantity(bidQty.get(i).intValue())
                    .numberOfOrders(1) // Default to 1 as we don't have order count in raw data
                    .build());
            }
        }
        
        // Priority 1: Use new format (asks objects) if available
        if (asks != null && !asks.isEmpty()) {
            allAsks = new ArrayList<>(asks);
        }
        // Fallback: Use old format (offRate/offQty arrays)
        else if (allAsks == null && offRate != null && offQty != null) {
            allAsks = new ArrayList<>();
            int minSize = Math.min(offRate.size(), offQty.size());
            for (int i = 0; i < minSize; i++) {
                allAsks.add(OrderBookLevel.builder()
                    .price(offRate.get(i))
                    .quantity(offQty.get(i).intValue())
                    .numberOfOrders(1) // Default to 1 as we don't have order count in raw data
                    .build());
            }
        }
    }

    /**
     * Get top N bid levels
     */
    public List<OrderBookLevel> getTopBids(int n) {
        if (allBids == null || allBids.isEmpty()) {
            return new ArrayList<>();
        }
        return allBids.stream()
            .limit(n)
            .collect(Collectors.toList());
    }

    /**
     * Get top N ask levels
     */
    public List<OrderBookLevel> getTopAsks(int n) {
        if (allAsks == null || allAsks.isEmpty()) {
            return new ArrayList<>();
        }
        return allAsks.stream()
            .limit(n)
            .collect(Collectors.toList());
    }

    /**
     * Get best bid price
     */
    public double getBestBid() {
        if (allBids != null && !allBids.isEmpty()) {
            return allBids.get(0).getPrice();
        }
        return bidRate != null && !bidRate.isEmpty() ? bidRate.get(0) : 0.0;
    }

    /**
     * Get best ask price
     */
    public double getBestAsk() {
        if (allAsks != null && !allAsks.isEmpty()) {
            return allAsks.get(0).getPrice();
        }
        return offRate != null && !offRate.isEmpty() ? offRate.get(0) : 0.0;
    }

    /**
     * Get spread (ask - bid)
     */
    public double getSpread() {
        return getBestAsk() - getBestBid();
    }

    /**
     * Get microprice (weighted mid price)
     */
    public double getMicroprice() {
        double bestBid = getBestBid();
        double bestAsk = getBestAsk();

        if (bestBid == 0 || bestAsk == 0) {
            return 0.0;
        }

        int bidQty = allBids != null && !allBids.isEmpty() ? allBids.get(0).getQuantity() : 0;
        int askQty = allAsks != null && !allAsks.isEmpty() ? allAsks.get(0).getQuantity() : 0;

        if (bidQty + askQty == 0) {
            return (bestBid + bestAsk) / 2.0;
        }

        return (bestBid * askQty + bestAsk * bidQty) / (bidQty + askQty);
    }

    /**
     * Get mid price (simple average of best bid and ask)
     */
    public double getMidPrice() {
        double bestBid = getBestBid();
        double bestAsk = getBestAsk();
        
        if (bestBid == 0 && bestAsk == 0) {
            return 0.0;
        }
        
        return (bestBid + bestAsk) / 2.0;
    }

    /**
     * Get timestamp (using receivedTimestamp)
     */
    public long getTimestamp() {
        return receivedTimestamp != null ? receivedTimestamp : 0L;
    }

    /**
     * Get exchange (alias for getExchange for compatibility)
     */
    public String getExch() {
        return exchange;
    }

    /**
     * Get exchange type (alias for getExchangeType for compatibility)
     */
    public String getExchType() {
        return exchangeType;
    }

    /**
     * Check if this snapshot is valid
     * Supports BOTH old and new formats
     */
    public boolean isValid() {
        if (token == null || token.isEmpty()) {
            return false;
        }
        
        // Check new format (bids/asks objects)
        boolean hasNewBids = bids != null && !bids.isEmpty();
        boolean hasNewAsks = asks != null && !asks.isEmpty();
        
        // Check old format (bidRate/bidQty arrays)
        boolean hasOldBids = bidRate != null && !bidRate.isEmpty() && bidQty != null && !bidQty.isEmpty();
        boolean hasOldAsks = offRate != null && !offRate.isEmpty() && offQty != null && !offQty.isEmpty();
        
        boolean hasLevels = hasNewBids || hasNewAsks || hasOldBids || hasOldAsks;
        boolean hasTotals = (totalBidQty != null && totalBidQty > 0) || (totalOffQty != null && totalOffQty > 0);
        
        if (!hasLevels && !hasTotals) { return false; }
        if (receivedTimestamp == null || receivedTimestamp <= 0) { return false; }
        return true;
    }

    /**
     * True if we have any bid/ask price levels present
     * Supports BOTH old and new formats
     */
    public boolean hasBookLevels() {
        // Check new format (bids/asks objects)
        boolean hasNewBids = bids != null && !bids.isEmpty();
        boolean hasNewAsks = asks != null && !asks.isEmpty();
        
        // Check old format (bidRate/bidQty arrays)
        boolean hasOldBids = bidRate != null && !bidRate.isEmpty() && bidQty != null && !bidQty.isEmpty();
        boolean hasOldAsks = offRate != null && !offRate.isEmpty() && offQty != null && !offQty.isEmpty();
        
        return hasNewBids || hasNewAsks || hasOldBids || hasOldAsks;
    }

    /**
     * Create serde for Kafka Streams
     */
    public static org.apache.kafka.common.serialization.Serde<OrderBookSnapshot> serde() {
        return new org.springframework.kafka.support.serializer.JsonSerde<>(OrderBookSnapshot.class);
    }
}