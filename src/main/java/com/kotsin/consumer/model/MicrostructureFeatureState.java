package com.kotsin.consumer.model;

import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * State for calculating microstructure features
 * Based on "Advances in Financial Machine Learning" Chapter 19
 * 
 * Maintains rolling window of order book snapshots to calculate:
 * - OFI (Order Flow Imbalance)
 * - VPIN (Volume-Synchronized Probability of Informed Trading)
 * - Kyle's Lambda (price impact)
 * - Depth Imbalance
 */
@Slf4j
public class MicrostructureFeatureState implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    // Configuration
    private final int windowSize;
    private final int minObservations;
    private final long emitIntervalMs;
    
    // History - using ArrayList instead of Deque for better serialization
    private final java.util.List<OrderBookSnapshot> orderBookHistory;
    private final java.util.List<Double> priceHistory;
    private final java.util.List<Double> signedVolumeHistory;
    
    // Current feature (to be emitted)
    private MicrostructureFeature currentFeature;
    private boolean isReady = false;
    
    // Time-based emission tracking
    private long lastEmitTimestamp = 0;
    
    /**
     * Constructor
     * 
     * @param windowSize Number of snapshots to keep for rolling calculations
     * @param minObservations Minimum observations before emitting features
     * @param emitIntervalMs Emit features at most once per this interval (milliseconds)
     */
    public MicrostructureFeatureState(int windowSize, int minObservations, long emitIntervalMs) {
        this.windowSize = windowSize;
        this.minObservations = minObservations;
        this.emitIntervalMs = emitIntervalMs;
        this.orderBookHistory = new java.util.ArrayList<>(windowSize);
        this.priceHistory = new java.util.ArrayList<>(windowSize);
        this.signedVolumeHistory = new java.util.ArrayList<>(windowSize);
        this.currentFeature = new MicrostructureFeature();
    }
    
    /**
     * Update state with new order book snapshot
     */
    public void update(OrderBookSnapshot snapshot) {
        try {
            // Validate
            if (snapshot == null || !snapshot.isValid()) {
                log.warn("Invalid order book snapshot for token {}", 
                    snapshot != null ? snapshot.getToken() : "null");
                return;
            }
            
            // Parse details if not already done
            if (snapshot.getAllBids() == null) {
                snapshot.parseDetails();
            }
            
            // Add to history (maintain window size)
            if (orderBookHistory.size() >= windowSize) {
                orderBookHistory.remove(0);
            }
            orderBookHistory.add(snapshot);
            
            // Add price to history
            if (priceHistory.size() >= windowSize) {
                priceHistory.remove(0);
            }
            priceHistory.add(snapshot.getMidPrice());
            
            // Calculate features if we have enough history
            if (orderBookHistory.size() >= minObservations) {
                calculateFeatures(snapshot);
                
                // Check if enough time has passed since last emit
                long currentTimestamp = snapshot.getTimestamp();
                if (currentTimestamp - lastEmitTimestamp >= emitIntervalMs) {
                    isReady = true;
                    lastEmitTimestamp = currentTimestamp;
                } else {
                    isReady = false;  // Throttle: not ready yet
                }
            }
            
        } catch (Exception e) {
            log.error("Error updating microstructure state for token {}: {}", 
                snapshot != null ? snapshot.getToken() : "null", 
                e.getMessage(), e);
        }
    }
    
    /**
     * Calculate all microstructure features
     */
    private void calculateFeatures(OrderBookSnapshot current) {
        currentFeature = new MicrostructureFeature();
        
        // Identity
        currentFeature.setToken(current.getToken());
        currentFeature.setScripCode(String.valueOf(current.getToken()));
        currentFeature.setCompanyName(current.getCompanyName());
        currentFeature.setExchange(current.getExch());
        currentFeature.setExchangeType(current.getExchType());
        currentFeature.setTimestamp(current.getTimestamp());
        
        // Book state
        currentFeature.setBestBid(current.getBestBid());
        currentFeature.setBestAsk(current.getBestAsk());
        currentFeature.setMidPrice(current.getMidPrice());
        currentFeature.setSpread(current.getSpread());
        currentFeature.setMicroprice(current.getMicroprice());
        currentFeature.calculateSpreadBps();
        
        // Volume metrics
        if (!current.getAllBids().isEmpty()) {
            currentFeature.setBidVolume1(current.getAllBids().get(0).getQuantity());
            currentFeature.setBidOrders1(current.getAllBids().get(0).getNumberOfOrders());
        }
        if (!current.getAllAsks().isEmpty()) {
            currentFeature.setAskVolume1(current.getAllAsks().get(0).getQuantity());
            currentFeature.setAskOrders1(current.getAllAsks().get(0).getNumberOfOrders());
        }
        
        currentFeature.setTotalBidVolume5(getTotalVolume(current.getTopBids(5)));
        currentFeature.setTotalAskVolume5(getTotalVolume(current.getTopAsks(5)));
        currentFeature.setTotalBidVolume20(getTotalVolume(current.getTopBids(20)));
        currentFeature.setTotalAskVolume20(getTotalVolume(current.getTopAsks(20)));
        
        // OFI (need previous snapshot)
        if (orderBookHistory.size() >= 2) {
            OrderBookSnapshot previous = getPreviousSnapshot();
            if (previous != null) {
                currentFeature.setOfi1(calculateOFI(previous, current, 1));
                currentFeature.setOfi5(calculateOFI(previous, current, 5));
                currentFeature.setOfi20(calculateOFI(previous, current, 20));
            }
        }
        
        // Depth Imbalance
        currentFeature.setDepthImbalance1(calculateDepthImbalance(current, 1));
        currentFeature.setDepthImbalance5(calculateDepthImbalance(current, 5));
        currentFeature.setDepthImbalance20(calculateDepthImbalance(current, 20));
        
        // VPIN (requires signed volume history)
        currentFeature.setVpin(calculateVPIN());
        
        // Kyle's Lambda (requires price change history)
        currentFeature.setKyleLambda(calculateKyleLambda());
        
        // Effective Spread (simplified)
        currentFeature.setEffectiveSpread(current.getSpread());
        
        log.debug("Calculated features for {}: OFI5={:.2f}, DepthImb5={:.3f}, VPIN={:.4f}",
            current.getCompanyName(),
            currentFeature.getOfi5(),
            currentFeature.getDepthImbalance5(),
            currentFeature.getVpin());
    }
    
    /**
     * Calculate Order Flow Imbalance (OFI)
     * 
     * Formula: OFI = Σ[ΔQ_bid * I{P_bid >= P_bid_prev}] - Σ[ΔQ_ask * I{P_ask <= P_ask_prev}]
     */
    private double calculateOFI(OrderBookSnapshot prev, OrderBookSnapshot curr, int levels) {
        double ofi = 0.0;
        
        try {
            // Bid side OFI
            for (int i = 0; i < Math.min(levels, curr.getAllBids().size()); i++) {
                if (i >= prev.getAllBids().size()) break;
                
                OrderBookSnapshot.OrderBookLevel bidPrev = prev.getAllBids().get(i);
                OrderBookSnapshot.OrderBookLevel bidCurr = curr.getAllBids().get(i);
                
                double deltaQty;
                if (bidCurr.getPrice() == bidPrev.getPrice()) {
                    // Price unchanged: use quantity difference
                    deltaQty = bidCurr.getQuantity() - bidPrev.getQuantity();
                } else if (bidCurr.getPrice() > bidPrev.getPrice()) {
                    // Price increased: aggressive buy
                    deltaQty = bidCurr.getQuantity();
                } else {
                    // Price decreased: demand removed
                    deltaQty = -bidPrev.getQuantity();
                }
                
                ofi += deltaQty * bidCurr.getPrice();
            }
            
            // Ask side OFI
            for (int i = 0; i < Math.min(levels, curr.getAllAsks().size()); i++) {
                if (i >= prev.getAllAsks().size()) break;
                
                OrderBookSnapshot.OrderBookLevel askPrev = prev.getAllAsks().get(i);
                OrderBookSnapshot.OrderBookLevel askCurr = curr.getAllAsks().get(i);
                
                double deltaQty;
                if (askCurr.getPrice() == askPrev.getPrice()) {
                    // Price unchanged: use quantity difference
                    deltaQty = askCurr.getQuantity() - askPrev.getQuantity();
                } else if (askCurr.getPrice() < askPrev.getPrice()) {
                    // Price decreased: aggressive sell
                    deltaQty = askCurr.getQuantity();
                } else {
                    // Price increased: supply removed
                    deltaQty = -askPrev.getQuantity();
                }
                
                ofi -= deltaQty * askCurr.getPrice();
            }
            
        } catch (Exception e) {
            log.error("Error calculating OFI: {}", e.getMessage());
        }
        
        return ofi;
    }
    
    /**
     * Calculate Depth Imbalance
     * 
     * Formula: DI = (bid_qty - ask_qty) / (bid_qty + ask_qty)
     * Returns value between -1 (all asks) and +1 (all bids)
     */
    private double calculateDepthImbalance(OrderBookSnapshot snapshot, int levels) {
        try {
            int bidQty = getTotalVolume(snapshot.getTopBids(levels));
            int askQty = getTotalVolume(snapshot.getTopAsks(levels));
            int totalQty = bidQty + askQty;
            
            if (totalQty == 0) {
                return 0.0;
            }
            
            return (double) (bidQty - askQty) / totalQty;
            
        } catch (Exception e) {
            log.error("Error calculating depth imbalance: {}", e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Calculate VPIN (Volume-Synchronized Probability of Informed Trading)
     * 
     * Formula: VPIN = |V_buy - V_sell| / V_total
     * Measures toxicity of order flow
     */
    private double calculateVPIN() {
        if (signedVolumeHistory.size() < minObservations) {
            return 0.0;
        }
        
        try {
            double buyVolume = 0.0;
            double sellVolume = 0.0;
            
            for (Double signedVol : signedVolumeHistory) {
                if (signedVol > 0) {
                    buyVolume += signedVol;
                } else {
                    sellVolume += Math.abs(signedVol);
                }
            }
            
            double totalVolume = buyVolume + sellVolume;
            if (totalVolume == 0) {
                return 0.0;
            }
            
            return Math.abs(buyVolume - sellVolume) / totalVolume;
            
        } catch (Exception e) {
            log.error("Error calculating VPIN: {}", e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Calculate Kyle's Lambda (price impact per unit volume)
     * 
     * Formula: λ = Cov(Δp, V_signed) / Var(V_signed)
     * Measures market liquidity
     */
    private double calculateKyleLambda() {
        if (priceHistory.size() < minObservations || signedVolumeHistory.size() < minObservations) {
            return 0.0;
        }
        
        try {
            // Calculate price changes
            Double[] prices = priceHistory.toArray(new Double[0]);
            double[] priceChanges = new double[prices.length - 1];
            for (int i = 1; i < prices.length; i++) {
                priceChanges[i - 1] = prices[i] - prices[i - 1];
            }
            
            // Get signed volumes
            Double[] signedVols = signedVolumeHistory.toArray(new Double[0]);
            int minLen = Math.min(priceChanges.length, signedVols.length);
            
            if (minLen < minObservations) {
                return 0.0;
            }
            
            // Calculate means
            double meanPriceChange = 0.0;
            double meanSignedVol = 0.0;
            for (int i = 0; i < minLen; i++) {
                meanPriceChange += priceChanges[i];
                meanSignedVol += signedVols[i];
            }
            meanPriceChange /= minLen;
            meanSignedVol /= minLen;
            
            // Calculate covariance and variance
            double covariance = 0.0;
            double variance = 0.0;
            for (int i = 0; i < minLen; i++) {
                double priceDev = priceChanges[i] - meanPriceChange;
                double volDev = signedVols[i] - meanSignedVol;
                covariance += priceDev * volDev;
                variance += volDev * volDev;
            }
            covariance /= minLen;
            variance /= minLen;
            
            if (variance == 0 || Double.isNaN(variance)) {
                return 0.0;
            }
            
            double kyleLambda = covariance / variance;
            
            // Sanity check
            if (kyleLambda < 0 || kyleLambda > 0.1) {
                log.debug("Unusual Kyle's Lambda: {:.6f}", kyleLambda);
            }
            
            return kyleLambda;
            
        } catch (Exception e) {
            log.error("Error calculating Kyle's Lambda: {}", e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Get total volume from list of levels
     */
    private int getTotalVolume(java.util.List<OrderBookSnapshot.OrderBookLevel> levels) {
        return levels.stream()
            .mapToInt(OrderBookSnapshot.OrderBookLevel::getQuantity)
            .sum();
    }
    
    /**
     * Get previous snapshot from history
     */
    private OrderBookSnapshot getPreviousSnapshot() {
        if (orderBookHistory.size() < 2) {
            return null;
        }
        // Get second-to-last element
        OrderBookSnapshot[] array = orderBookHistory.toArray(new OrderBookSnapshot[0]);
        return array[array.length - 2];
    }
    
    /**
     * Check if features are ready to emit
     */
    public boolean hasFeature() {
        return isReady && currentFeature != null && currentFeature.isValid();
    }
    
    /**
     * Get current feature and reset
     */
    public MicrostructureFeature emitAndReset() {
        MicrostructureFeature featureToEmit = currentFeature;
        isReady = false;
        return featureToEmit;
    }
    
    /**
     * Kafka Serde
     */
    public static org.apache.kafka.common.serialization.Serde<MicrostructureFeatureState> serde() {
        return new org.springframework.kafka.support.serializer.JsonSerde<>(MicrostructureFeatureState.class);
    }
}

