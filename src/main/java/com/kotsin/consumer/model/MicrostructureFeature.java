package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.kafka.common.serialization.Serde;
import org.springframework.kafka.support.serializer.JsonSerde;

import java.io.Serializable;

/**
 * Market Microstructure Features
 * Based on "Advances in Financial Machine Learning" Chapter 19
 * 
 * Measures order flow dynamics, toxicity, and price impact
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MicrostructureFeature implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    // Identity
    @JsonProperty("token")
    private int token;
    
    @JsonProperty("scripCode")
    private String scripCode;
    
    @JsonProperty("companyName")
    private String companyName;
    
    @JsonProperty("exchange")
    private String exchange;
    
    @JsonProperty("exchangeType")
    private String exchangeType;
    
    @JsonProperty("timestamp")
    private long timestamp;
    
    // Order Flow Imbalance (OFI) - Chapter 19
    // Measures aggressive buying vs selling pressure
    @JsonProperty("ofi1")
    private double ofi1;  // Top-of-book (most sensitive)
    
    @JsonProperty("ofi5")
    private double ofi5;  // Top 5 levels
    
    @JsonProperty("ofi20")
    private double ofi20;  // Top 20 levels (full depth)
    
    // Depth Imbalance - Measures resting liquidity imbalance
    // Value between -1 (all asks) and +1 (all bids)
    @JsonProperty("depthImbalance1")
    private double depthImbalance1;  // Level 1
    
    @JsonProperty("depthImbalance5")
    private double depthImbalance5;  // Top 5 levels
    
    @JsonProperty("depthImbalance20")
    private double depthImbalance20;  // Top 20 levels
    
    // VPIN - Volume-Synchronized Probability of Informed Trading
    // Measures toxicity of order flow (0-1, higher = more toxic)
    @JsonProperty("vpin")
    private double vpin;
    
    // Kyle's Lambda - Price impact per unit volume
    // Measures market liquidity (higher = less liquid)
    @JsonProperty("kyleLambda")
    private double kyleLambda;
    
    // Microprice - Volume-weighted mid price
    // More accurate than simple mid for true value
    @JsonProperty("microprice")
    private double microprice;
    
    // Effective Spread - Actual cost of trading
    @JsonProperty("effectiveSpread")
    private double effectiveSpread;
    
    // Book State (for reference)
    @JsonProperty("bestBid")
    private double bestBid;
    
    @JsonProperty("bestAsk")
    private double bestAsk;
    
    @JsonProperty("midPrice")
    private double midPrice;
    
    @JsonProperty("spread")
    private double spread;
    
    @JsonProperty("spreadBps")
    private double spreadBps;  // Spread in basis points
    
    // Volume metrics
    @JsonProperty("bidVolume1")
    private int bidVolume1;  // Volume at best bid
    
    @JsonProperty("askVolume1")
    private int askVolume1;  // Volume at best ask
    
    @JsonProperty("totalBidVolume5")
    private int totalBidVolume5;  // Total bid volume (top 5)
    
    @JsonProperty("totalAskVolume5")
    private int totalAskVolume5;  // Total ask volume (top 5)
    
    @JsonProperty("totalBidVolume20")
    private int totalBidVolume20;  // Total bid volume (top 20)
    
    @JsonProperty("totalAskVolume20")
    private int totalAskVolume20;  // Total ask volume (top 20)
    
    // Number of orders (order book depth quality)
    @JsonProperty("bidOrders1")
    private int bidOrders1;
    
    @JsonProperty("askOrders1")
    private int askOrders1;
    
    /**
     * Kafka Serde for serialization
     */
    public static Serde<MicrostructureFeature> serde() {
        return new JsonSerde<>(MicrostructureFeature.class);
    }
    
    /**
     * Calculate spread in basis points
     */
    public void calculateSpreadBps() {
        if (midPrice > 0 && spread > 0) {
            this.spreadBps = (spread / midPrice) * 10000.0;
        } else {
            this.spreadBps = 0.0;
        }
    }
    
    /**
     * Validate feature values (for debugging)
     */
    public boolean isValid() {
        return token > 0 
            && timestamp > 0 
            && midPrice > 0
            && !Double.isNaN(ofi5)
            && !Double.isNaN(depthImbalance5)
            && !Double.isInfinite(ofi5)
            && !Double.isInfinite(depthImbalance5);
    }
    
    @Override
    public String toString() {
        return String.format(
            "MicrostructureFeature{token=%d, company=%s, timestamp=%d, " +
            "ofi5=%.2f, depthImb5=%.3f, vpin=%.4f, kyleLambda=%.6f, " +
            "mid=%.2f, spread=%.2f}",
            token, companyName, timestamp,
            ofi5, depthImbalance5, vpin, kyleLambda,
            midPrice, spread
        );
    }
}

