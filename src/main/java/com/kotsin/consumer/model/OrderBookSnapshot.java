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
    
    // Derived fields (calculated from Details)
    private transient List<OrderBookLevel> bids;
    private transient List<OrderBookLevel> asks;
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
     * Parse and separate bids/asks from details
     */
    public void parseDetails() {
        if (details == null || details.isEmpty()) {
            bids = new ArrayList<>();
            asks = new ArrayList<>();
            return;
        }
        
        bids = new ArrayList<>();
        asks = new ArrayList<>();
        
        for (OrderBookLevel level : details) {
            double normalizedPrice = level.getNormalizedPrice();
            OrderBookLevel normalized = new OrderBookLevel(
                normalizedPrice,
                level.getQuantity(),
                level.getNumberOfOrders(),
                level.getBbBuySellFlag()
            );
            
            if (level.isBid()) {
                bids.add(normalized);
            } else if (level.isAsk()) {
                asks.add(normalized);
            }
        }
        
        // Sort: bids descending (highest first), asks ascending (lowest first)
        bids.sort((a, b) -> Double.compare(b.getPrice(), a.getPrice()));
        asks.sort((a, b) -> Double.compare(a.getPrice(), b.getPrice()));
        
        // Calculate derived fields
        bestBid = bids.isEmpty() ? 0.0 : bids.get(0).getPrice();
        bestAsk = asks.isEmpty() ? Double.MAX_VALUE : asks.get(0).getPrice();
        
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
    public List<OrderBookLevel> getBids(int levels) {
        if (bids == null) {
            parseDetails();
        }
        return bids.subList(0, Math.min(levels, bids.size()));
    }
    
    /**
     * Get ask levels (top N)
     */
    public List<OrderBookLevel> getAsks(int levels) {
        if (asks == null) {
            parseDetails();
        }
        return asks.subList(0, Math.min(levels, asks.size()));
    }
    
    /**
     * Get all bids
     */
    public List<OrderBookLevel> getAllBids() {
        if (bids == null) {
            parseDetails();
        }
        return bids;
    }
    
    /**
     * Get all asks
     */
    public List<OrderBookLevel> getAllAsks() {
        if (asks == null) {
            parseDetails();
        }
        return asks;
    }
    
    /**
     * Get best bid price
     */
    public double getBestBid() {
        if (bids == null) {
            parseDetails();
        }
        return bestBid;
    }
    
    /**
     * Get best ask price
     */
    public double getBestAsk() {
        if (asks == null) {
            parseDetails();
        }
        return bestAsk;
    }
    
    /**
     * Get mid price
     */
    public double getMidPrice() {
        if (bids == null) {
            parseDetails();
        }
        return midPrice;
    }
    
    /**
     * Get spread
     */
    public double getSpread() {
        if (bids == null) {
            parseDetails();
        }
        return spread;
    }
    
    /**
     * Calculate microprice (volume-weighted mid)
     */
    public double getMicroprice() {
        if (bids == null) {
            parseDetails();
        }
        
        if (bids.isEmpty() || asks.isEmpty()) {
            return midPrice;
        }
        
        OrderBookLevel bestBidLevel = bids.get(0);
        OrderBookLevel bestAskLevel = asks.get(0);
        
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
        
        if (bids == null) {
            parseDetails();
        }
        
        return !bids.isEmpty() && !asks.isEmpty() && midPrice > 0;
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

