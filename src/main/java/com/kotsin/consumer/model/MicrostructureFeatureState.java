package com.kotsin.consumer.model;

import com.kotsin.consumer.service.MicrostructureMetricsCalculator;
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

    // Calculator service (transient because it's not part of state)
    private transient MicrostructureMetricsCalculator calculator = new MicrostructureMetricsCalculator();

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
        // Convert token from String to int
        try {
            currentFeature.setToken(Integer.parseInt(current.getToken()));
        } catch (NumberFormatException e) {
            log.warn("Invalid token format: {}", current.getToken());
            currentFeature.setToken(0);
        }
        currentFeature.setScripCode(current.getToken());
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
        
        // Ensure calculator is initialized (important after deserialization)
        if (calculator == null) {
            calculator = new MicrostructureMetricsCalculator();
        }

        // OFI (need previous snapshot)
        if (orderBookHistory.size() >= 2) {
            OrderBookSnapshot previous = getPreviousSnapshot();
            if (previous != null) {
                currentFeature.setOfi1(calculator.calculateOFI(previous, current, 1));
                currentFeature.setOfi5(calculator.calculateOFI(previous, current, 5));
                currentFeature.setOfi20(calculator.calculateOFI(previous, current, 20));
            }
        }

        // Depth Imbalance
        currentFeature.setDepthImbalance1(calculator.calculateDepthImbalance(current, 1));
        currentFeature.setDepthImbalance5(calculator.calculateDepthImbalance(current, 5));
        currentFeature.setDepthImbalance20(calculator.calculateDepthImbalance(current, 20));

        // VPIN (requires signed volume history)
        currentFeature.setVpin(calculator.calculateVPIN(signedVolumeHistory, minObservations));

        // Kyle's Lambda (requires price change history)
        currentFeature.setKyleLambda(calculator.calculateKyleLambda(priceHistory, signedVolumeHistory, minObservations));
        
        // Effective Spread (simplified)
        currentFeature.setEffectiveSpread(current.getSpread());
        
        log.debug("Calculated features for {}: OFI5={:.2f}, DepthImb5={:.3f}, VPIN={:.4f}",
            current.getCompanyName(),
            currentFeature.getOfi5(),
            currentFeature.getDepthImbalance5(),
            currentFeature.getVpin());
    }
    
    /**
     * Get total volume from list of levels (delegates to calculator)
     */
    private int getTotalVolume(java.util.List<OrderBookSnapshot.OrderBookLevel> levels) {
        if (calculator == null) {
            calculator = new MicrostructureMetricsCalculator();
        }
        return calculator.getTotalVolume(levels);
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

