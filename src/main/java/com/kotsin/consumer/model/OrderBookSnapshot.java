package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.kafka.common.serialization.Serde;
import org.springframework.kafka.support.serializer.JsonSerde;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Order Book Snapshot from Kafka "Orderbook" topic
 * 
 * Structure based on orderbookAnalyzer/consumer.py normalization
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderBookSnapshot implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    @JsonProperty("Token")
    private int token;
    
    @JsonProperty("Exch")
    private String exch;
    
    @JsonProperty("ExchType")
    private String exchType;
    
    @JsonProperty("companyName")
    private String companyName;
    
    @JsonProperty("receivedTimestamp")
    private long timestamp;
    
    @JsonProperty("TBidQ")
    private int totalBidQty;
    
    @JsonProperty("TOffQ")
    private int totalAskQty;
    
    @JsonProperty("Details")
    private List<OrderBookLevel> details = new ArrayList<>();
    
    // Alternative format: separate bids/asks arrays (used by some brokers)
    // CRITICAL FIX: Don't use @JsonProperty to avoid getter conflicts with Lombok
    // Jackson will map "bids" from JSON directly to this field via setter
    private List<OrderBookLevel> bids;
    
    private List<OrderBookLevel> asks;
    
    // Derived/parsed fields (calculated from Details or bids/asks after parsing)
    private transient List<OrderBookLevel> parsedBids;
    private transient List<OrderBookLevel> parsedAsks;
    private transient double bestBid;
    private transient double bestAsk;
    private transient double midPrice;
    private transient double spread;
    
    /**
     * Order Book Level
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderBookLevel implements Serializable {
        
        private static final long serialVersionUID = 1L;
        
        @JsonProperty("Price")
        private double price;
        
        @JsonProperty("Quantity")
        private int quantity;
        
        @JsonProperty("NumberOfOrders")
        private int numberOfOrders;
        
        @JsonProperty("BbBuySellFlag")
        private int bbBuySellFlag;  // 66=Bid, 83=Ask
        
        public boolean isBid() {
            return bbBuySellFlag == 66;
        }
        
        public boolean isAsk() {
            return bbBuySellFlag == 83;
        }
        
        /**
         * Convert price from paise to rupees if needed
         */
        public double getNormalizedPrice() {
            // If price > 100000, likely in paise
            if (price > 100000) {
                return price / 100.0;
            }
            return price;
        }
    }
    
    /**
     * Parse and separate bids/asks from details or bids/asks arrays
     * SUPPORTS TWO FORMATS:
     * 1. "Details" array with BbBuySellFlag (66=bid, 83=ask)
     * 2. Separate "bids" and "asks" arrays (from JSON directly)
     */
    public void parseDetails() {
        parsedBids = new ArrayList<>();
        parsedAsks = new ArrayList<>();
        
        // PRIORITY 1: Use bids/asks if available (newer format - directly from JSON)
        if ((bids != null && !bids.isEmpty()) || (asks != null && !asks.isEmpty())) {
            if (bids != null) {
                for (OrderBookLevel level : bids) {
                    double normalizedPrice = level.getNormalizedPrice();
                    parsedBids.add(new OrderBookLevel(
                        normalizedPrice,
                        level.getQuantity(),
                        level.getNumberOfOrders(),
                        66  // Bid flag
                    ));
                }
            }
            if (asks != null) {
                for (OrderBookLevel level : asks) {
                    double normalizedPrice = level.getNormalizedPrice();
                    parsedAsks.add(new OrderBookLevel(
                        normalizedPrice,
                        level.getQuantity(),
                        level.getNumberOfOrders(),
                        83  // Ask flag
                    ));
                }
            }
        }
        // PRIORITY 2: Fall back to Details array
        else if (details != null && !details.isEmpty()) {
            for (OrderBookLevel level : details) {
                double normalizedPrice = level.getNormalizedPrice();
                OrderBookLevel normalized = new OrderBookLevel(
                    normalizedPrice,
                    level.getQuantity(),
                    level.getNumberOfOrders(),
                    level.getBbBuySellFlag()
                );
                
                if (level.isBid()) {
                    parsedBids.add(normalized);
                } else if (level.isAsk()) {
                    parsedAsks.add(normalized);
                }
            }
        }
        // PRIORITY 3: Empty order book
        else {
            // Already initialized to empty ArrayLists above
            return;
        }
        
        // Sort: bids descending (highest first), asks ascending (lowest first)
        parsedBids.sort((a, b) -> Double.compare(b.getPrice(), a.getPrice()));
        parsedAsks.sort((a, b) -> Double.compare(a.getPrice(), b.getPrice()));
        
        // Calculate derived fields
        bestBid = parsedBids.isEmpty() ? 0.0 : parsedBids.get(0).getPrice();
        bestAsk = parsedAsks.isEmpty() ? Double.MAX_VALUE : parsedAsks.get(0).getPrice();
        
        if (bestBid > 0 && bestAsk < Double.MAX_VALUE) {
            midPrice = (bestBid + bestAsk) / 2.0;
            spread = bestAsk - bestBid;
        } else {
            midPrice = 0.0;
            spread = 0.0;
        }
    }
    
    /**
     * Get bid levels (top N)
     */
    public List<OrderBookLevel> getTopBids(int levels) {
        if (parsedBids == null) {
            parseDetails();
        }
        return parsedBids.subList(0, Math.min(levels, parsedBids.size()));
    }
    
    /**
     * Get ask levels (top N)
     */
    public List<OrderBookLevel> getTopAsks(int levels) {
        if (parsedAsks == null) {
            parseDetails();
        }
        return parsedAsks.subList(0, Math.min(levels, parsedAsks.size()));
    }
    
    /**
     * Get all bids
     */
    public List<OrderBookLevel> getAllBids() {
        if (parsedBids == null) {
            parseDetails();
        }
        return parsedBids;
    }
    
    /**
     * Get all asks
     */
    public List<OrderBookLevel> getAllAsks() {
        if (parsedAsks == null) {
            parseDetails();
        }
        return parsedAsks;
    }
    
    /**
     * Get best bid price
     */
    public double getBestBid() {
        if (parsedBids == null) {
            parseDetails();
        }
        return bestBid;
    }
    
    /**
     * Get best ask price
     */
    public double getBestAsk() {
        if (parsedAsks == null) {
            parseDetails();
        }
        return bestAsk;
    }
    
    /**
     * Get mid price
     */
    public double getMidPrice() {
        if (parsedBids == null) {
            parseDetails();
        }
        return midPrice;
    }
    
    /**
     * Get spread
     */
    public double getSpread() {
        if (parsedBids == null) {
            parseDetails();
        }
        return spread;
    }
    
    /**
     * Calculate microprice (volume-weighted mid)
     */
    public double getMicroprice() {
        if (parsedBids == null) {
            parseDetails();
        }
        
        if (parsedBids.isEmpty() || parsedAsks.isEmpty()) {
            return midPrice;
        }
        
        OrderBookLevel bestBidLevel = parsedBids.get(0);
        OrderBookLevel bestAskLevel = parsedAsks.get(0);
        
        int bidQty = bestBidLevel.getQuantity();
        int askQty = bestAskLevel.getQuantity();
        int totalQty = bidQty + askQty;
        
        if (totalQty == 0) {
            return midPrice;
        }
        
        // Microprice = (askPrice * bidQty + bidPrice * askQty) / (bidQty + askQty)
        return (bestAskLevel.getPrice() * bidQty + bestBidLevel.getPrice() * askQty) / totalQty;
    }
    
    /**
     * Validate order book
     */
    public boolean isValid() {
        if (token <= 0 || timestamp <= 0) {
            return false;
        }
        
        if (parsedBids == null) {
            parseDetails();
        }
        
        return !parsedBids.isEmpty() && !parsedAsks.isEmpty() && midPrice > 0;
    }
    
    /**
     * Kafka Serde
     */
    public static Serde<OrderBookSnapshot> serde() {
        return new JsonSerde<>(OrderBookSnapshot.class);
    }
    
    @Override
    public String toString() {
        if (bids == null) {
            parseDetails();
        }
        return String.format(
            "OrderBook{token=%d, company=%s, timestamp=%d, " +
            "bid=%.2f, ask=%.2f, mid=%.2f, spread=%.2f, " +
            "bidLevels=%d, askLevels=%d}",
            token, companyName, timestamp,
            bestBid, bestAsk, midPrice, spread,
            bids.size(), asks.size()
        );
    }
}

