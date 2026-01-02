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
    private int token;  // Fixed: Changed from String to int to match TickData/OpenInterest

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

    // Bids and asks arrays
    @JsonProperty("bids")
    private List<OrderBookLevel> bids;
    
    @JsonProperty("asks")
    private List<OrderBookLevel> asks;

    // Parsed details (unified view)
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
     */
    public void parseDetails() {
        if (bids != null && !bids.isEmpty()) {
            allBids = new ArrayList<>(bids);
        }

        if (asks != null && !asks.isEmpty()) {
            allAsks = new ArrayList<>(asks);
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
        return 0.0;
    }

    /**
     * Get best ask price
     */
    public double getBestAsk() {
        if (allAsks != null && !allAsks.isEmpty()) {
            return allAsks.get(0).getPrice();
        }
        return 0.0;
    }

    /**
     * Get spread (ask - bid)
     * FIX: Returns 0 if bid/ask are invalid or book is crossed
     */
    public double getSpread() {
        double bid = getBestBid();
        double ask = getBestAsk();
        
        // Return 0 if either side is invalid or book is crossed
        if (bid <= 0 || ask <= 0 || bid >= ask) {
            return 0.0;
        }
        return ask - bid;
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
     */
    public boolean isValid() {
        // Fixed: token is now int, so check > 0 instead of null/empty
        if (token <= 0) {
            return false;
        }

        // Ensure we have a plausible timestamp
        if (receivedTimestamp == null || receivedTimestamp <= 0) {
            return false;
        }

        int bidLevels = bids != null ? bids.size() : 0;
        int askLevels = asks != null ? asks.size() : 0;

        // BUG-012 FIX: Accept any book with at least 1 level on each side (more lenient for illiquid instruments)
        return bidLevels >= 1 && askLevels >= 1;
    }

    /**
     * True if we have any bid/ask price levels present
     */
    public boolean hasBookLevels() {
        boolean hasBids = bids != null && !bids.isEmpty();
        boolean hasAsks = asks != null && !asks.isEmpty();

        return hasBids || hasAsks;
    }

    /**
     * Create serde for Kafka Streams
     */
    public static org.apache.kafka.common.serialization.Serde<OrderBookSnapshot> serde() {
        return new org.springframework.kafka.support.serializer.JsonSerde<>(OrderBookSnapshot.class);
    }
}
