package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.MicrostructureData;
import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.TickData;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Accumulator for market microstructure metrics
 * 
 * CRITICAL FIXES (per refactoring.md critique):
 * 1. OFI: Implements full-depth calculation per Cont-Kukanov-Stoikov 2014
 * 2. VPIN: Uses BVC (Bulk Volume Classification) with adaptive buckets
 * 3. Kyle's Lambda: Uses signed order flow (VAR/Hasbrouck method)
 * 
 * Design Pattern: Accumulator
 * Thread-Safety: Not thread-safe (used within single Kafka Streams task)
 */
@Data
@Slf4j
public class MicrostructureAccumulator {
    
    // ========== OFI (Order Flow Imbalance) ==========
    // Per Cont-Kukanov-Stoikov 2014: OFI = ΔBid^depth - ΔAsk^depth
    private Double ofi = 0.0;
    private Map<Double, Integer> prevBidDepth = new HashMap<>();
    private Map<Double, Integer> prevAskDepth = new HashMap<>();
    private double prevBestBid = 0.0;
    private double prevBestAsk = 0.0;
    
    // ========== VPIN (Volume-Synchronized PIN) ==========
    // Per Easley-Lopez de Prado-O'Hara 2012
    private static final int VPIN_BUCKET_COUNT = 50;
    private double adaptiveBucketSize = 10000.0;  // Adjusted dynamically
    private final Deque<VPINBucket> vpinBuckets = new ArrayDeque<>(VPIN_BUCKET_COUNT);
    private double currentBucketVolume = 0.0;
    private double currentBucketBuyVolume = 0.0;
    private Double vpin = 0.0;
    private int totalBucketsCreated = 0;
    
    // ========== Kyle's Lambda (Price Impact) ==========
    // Per Hasbrouck VAR estimation
    private final Deque<PriceImpactObservation> priceImpactHistory = new ArrayDeque<>(100);
    private Double kyleLambda = 0.0;
    private double lastMidPrice = 0.0;
    
    // ========== Bid-Ask Metrics ==========
    private Double depthImbalance = 0.0;
    private Double effectiveSpread = 0.0;
    private Double midPrice = 0.0;
    private Double microprice = 0.0;
    private Double bidAskSpread = 0.0;
    
    // ========== State ==========
    private boolean complete = false;
    private int updateCount = 0;
    private static final int MIN_OBSERVATIONS = 20;
    
    /**
     * VPIN bucket for volume-synchronized calculation
     */
    @Data
    private static class VPINBucket {
        double totalVolume;
        double buyVolume;
        double sellVolume;
        
        double getImbalance() {
            return Math.abs(buyVolume - sellVolume);
        }
    }
    
    /**
     * Price impact observation for Kyle's Lambda
     */
    @Data
    private static class PriceImpactObservation {
        double priceChange;      // Change in mid-price
        double signedVolume;     // Positive for buy, negative for sell
        long timestamp;
    }
    
    /**
     * Add tick with orderbook update (preferred method)
     * CRITICAL: Use this when full orderbook snapshot is available
     */
    public void addTick(TickData tick, OrderBookSnapshot orderbook) {
        if (tick == null || tick.getLastRate() <= 0) {
            return;
        }
        
        // If orderbook provided, use full depth calculations
        if (orderbook != null && orderbook.isValid()) {
            addTickWithFullDepth(tick, orderbook);
        } else {
            // Fallback to L1 approximation
            addTickWithL1Only(tick);
        }
        
        updateCount++;
        if (updateCount >= MIN_OBSERVATIONS) {
            complete = true;
        }
    }
    
    /**
     * Add tick only (backward compatibility)
     * Uses L1 approximation when full orderbook not available
     */
    public void addTick(TickData tick) {
        addTick(tick, tick.getFullOrderbook());
    }
    
    /**
     * CRITICAL FIX #1: Full-depth OFI calculation
     * Per Cont-Kukanov-Stoikov 2014
     */
    private void addTickWithFullDepth(TickData tick, OrderBookSnapshot orderbook) {
        // Parse orderbook to get full depth
        orderbook.parseDetails();
        
        // Calculate mid-price and microprice
        double bestBid = orderbook.getBestBid();
        double bestAsk = orderbook.getBestAsk();
        
        if (bestBid > 0 && bestAsk > 0) {
            midPrice = (bestBid + bestAsk) / 2.0;
            bidAskSpread = bestAsk - bestBid;
            
            // Calculate microprice (volume-weighted)
            // Extract quantities from first level
            int bidQty = (orderbook.getAllBids() != null && !orderbook.getAllBids().isEmpty()) ? 
                orderbook.getAllBids().get(0).getQuantity() : 0;
            int askQty = (orderbook.getAllAsks() != null && !orderbook.getAllAsks().isEmpty()) ? 
                orderbook.getAllAsks().get(0).getQuantity() : 0;
            
            if (bidQty + askQty > 0) {
                microprice = (bestBid * askQty + bestAsk * bidQty) / (double)(bidQty + askQty);
            }
            
            // Calculate effective spread
            double tradePrice = tick.getLastRate();
            effectiveSpread = 2.0 * Math.abs(tradePrice - midPrice);
        }
        
        // Calculate depth imbalance
        // Sum all bid/ask quantities
        int totalBidQty = (orderbook.getTotalBidQty() != null) ? 
            orderbook.getTotalBidQty().intValue() : 0;
        int totalAskQty = (orderbook.getTotalOffQty() != null) ? 
            orderbook.getTotalOffQty().intValue() : 0;
        
        if (totalBidQty + totalAskQty > 0) {
            depthImbalance = (totalBidQty - totalAskQty) / (double)(totalBidQty + totalAskQty);
        }
        
        // Build depth maps from orderbook
        Map<Double, Integer> currentBidDepth = buildDepthMap(orderbook.getAllBids());
        Map<Double, Integer> currentAskDepth = buildDepthMap(orderbook.getAllAsks());
        
        // Calculate OFI (full depth)
        if (!prevBidDepth.isEmpty() && !prevAskDepth.isEmpty()) {
            ofi = calculateFullDepthOFI(
                prevBidDepth, currentBidDepth, prevBestBid, bestBid,
                prevAskDepth, currentAskDepth, prevBestAsk, bestAsk
            );
        }
        
        // Update previous state
        prevBidDepth = currentBidDepth;
        prevAskDepth = currentAskDepth;
        prevBestBid = bestBid;
        prevBestAsk = bestAsk;
        
        // Update VPIN with BVC trade classification
        if (tick.getDeltaVolume() != null && tick.getDeltaVolume() > 0) {
            boolean isBuy = classifyTradeBVC(tick, orderbook);
            updateVPIN(tick.getDeltaVolume(), isBuy);
        }
        
        // Update Kyle's Lambda with signed volume
        if (midPrice > 0 && tick.getDeltaVolume() != null) {
            updateKyleLambda(tick, midPrice);
        }
    }
    
    /**
     * Fallback: L1-only approximation (when full orderbook not available)
     */
    private void addTickWithL1Only(TickData tick) {
        double bidPrice = tick.getBidRate();
        double askPrice = tick.getOfferRate();
        int bidQty = tick.getBidQuantity();
        int askQty = tick.getOfferQuantity();
        
        if (bidPrice > 0 && askPrice > 0) {
            midPrice = (bidPrice + askPrice) / 2.0;
            bidAskSpread = askPrice - bidPrice;
            
            if (bidQty + askQty > 0) {
                microprice = (bidPrice * askQty + askPrice * bidQty) / (double)(bidQty + askQty);
            }
            
            effectiveSpread = 2.0 * Math.abs(tick.getLastRate() - midPrice);
        }
        
        // Simplified depth imbalance from L1
        int totalBidQty = tick.getTotalBidQuantity();
        int totalAskQty = tick.getTotalOfferQuantity();
        if (totalBidQty + totalAskQty > 0) {
            depthImbalance = (totalBidQty - totalAskQty) / (double)(totalBidQty + totalAskQty);
        }
        
        // Simplified OFI (L1 only)
        if (prevBestBid > 0 && prevBestAsk > 0) {
            ofi = calculateL1OFI(bidPrice, bidQty, prevBestBid, askPrice, askQty, prevBestAsk);
        }
        
        prevBestBid = bidPrice;
        prevBestAsk = askPrice;
        
        // VPIN with simple tick rule
        if (tick.getDeltaVolume() != null && tick.getDeltaVolume() > 0) {
            boolean isBuy = tick.getLastRate() >= midPrice;
            updateVPIN(tick.getDeltaVolume(), isBuy);
        }
        
        // Kyle's Lambda
        if (midPrice > 0 && tick.getDeltaVolume() != null) {
            updateKyleLambda(tick, midPrice);
        }
    }
    
    /**
     * CRITICAL FIX: Full-depth OFI calculation
     * Formula: OFI = ΔBid^depth - ΔAsk^depth
     * where ΔBid^depth = Σ_{p≥p^b_{t-1}} q^b_t(p) - Σ_{p≥p^b_t} q^b_{t-1}(p)
     */
    private double calculateFullDepthOFI(
        Map<Double, Integer> prevBid, Map<Double, Integer> currBid, double prevBestBid, double currBestBid,
        Map<Double, Integer> prevAsk, Map<Double, Integer> currAsk, double prevBestAsk, double currBestAsk
    ) {
        double deltaBid = 0.0;
        double deltaAsk = 0.0;
        
        // Calculate bid side: sum over prices >= prev_best_bid
        for (Map.Entry<Double, Integer> entry : currBid.entrySet()) {
            if (entry.getKey() >= prevBestBid) {
                deltaBid += entry.getValue();
            }
        }
        
        // Subtract quantity at prices >= curr_best_bid from previous snapshot
        for (Map.Entry<Double, Integer> entry : prevBid.entrySet()) {
            if (entry.getKey() >= currBestBid) {
                deltaBid -= entry.getValue();
            }
        }
        
        // Calculate ask side: sum over prices <= prev_best_ask
        for (Map.Entry<Double, Integer> entry : currAsk.entrySet()) {
            if (entry.getKey() <= prevBestAsk) {
                deltaAsk += entry.getValue();
            }
        }
        
        // Subtract quantity at prices <= curr_best_ask from previous snapshot
        for (Map.Entry<Double, Integer> entry : prevAsk.entrySet()) {
            if (entry.getKey() <= currBestAsk) {
                deltaAsk -= entry.getValue();
            }
        }
        
        return deltaBid - deltaAsk;
    }
    
    /**
     * Simplified L1 OFI (fallback when full depth not available)
     */
    private double calculateL1OFI(double bidPrice, int bidQty, double prevBidPrice,
                                   double askPrice, int askQty, double prevAskPrice) {
        double deltaBid = (bidPrice >= prevBidPrice) ? bidQty : -bidQty;
        double deltaAsk = (askPrice <= prevAskPrice) ? askQty : -askQty;
        return deltaBid - deltaAsk;
    }
    
    /**
     * Build depth map from orderbook levels
     */
    private Map<Double, Integer> buildDepthMap(List<OrderBookSnapshot.OrderBookLevel> levels) {
        Map<Double, Integer> depthMap = new HashMap<>();
        if (levels != null) {
            for (OrderBookSnapshot.OrderBookLevel level : levels) {
                if (level.getPrice() > 0 && level.getQuantity() > 0) {
                    depthMap.put(level.getPrice(), level.getQuantity());
                }
            }
        }
        return depthMap;
    }
    
    /**
     * CRITICAL FIX #2: BVC (Bulk Volume Classification) for trade direction
     * Uses microprice instead of simple mid-price
     */
    private boolean classifyTradeBVC(TickData tick, OrderBookSnapshot orderbook) {
        double tradePrice = tick.getLastRate();
        
        // Calculate microprice (more accurate than mid-price)
        double mp = microprice;
        if (mp == 0.0) {
            // Fallback to mid-price
            mp = midPrice;
        }
        
        // BVC rule: compare to microprice
        if (Math.abs(tradePrice - mp) < 0.0001) {
            // On microprice → use previous classification or tick rule
            return tradePrice >= midPrice;
        }
        
        return tradePrice > mp;  // Above microprice = buy
    }
    
    /**
     * CRITICAL FIX #3: VPIN with adaptive bucket sizing
     */
    private void updateVPIN(int volume, boolean isBuy) {
        currentBucketVolume += volume;
        if (isBuy) {
            currentBucketBuyVolume += volume;
        }
        
        // Adaptive bucket size (increases with market activity)
        if (totalBucketsCreated > 0 && totalBucketsCreated % 20 == 0) {
            // Recalculate adaptive bucket size based on recent volume
            double avgVolume = vpinBuckets.stream()
                .mapToDouble(VPINBucket::getTotalVolume)
                .average()
                .orElse(10000.0);
            adaptiveBucketSize = avgVolume * 1.2; // 20% buffer
        }
        
        // Close bucket when full
        if (currentBucketVolume >= adaptiveBucketSize) {
            VPINBucket bucket = new VPINBucket();
            bucket.totalVolume = currentBucketVolume;
            bucket.buyVolume = currentBucketBuyVolume;
            bucket.sellVolume = currentBucketVolume - currentBucketBuyVolume;
            
            vpinBuckets.addLast(bucket);
            totalBucketsCreated++;
            
            // Keep only last N buckets (O(1) removal with Deque)
            if (vpinBuckets.size() > VPIN_BUCKET_COUNT) {
                vpinBuckets.removeFirst();
            }
            
            // Reset current bucket
            currentBucketVolume = 0.0;
            currentBucketBuyVolume = 0.0;
            
            // Calculate VPIN
            calculateVPIN();
        }
    }
    
    /**
     * Calculate VPIN from buckets
     * CORRECT Formula per Easley-López de Prado-O'Hara (2012):
     * VPIN = (1/n) × Σ|V_buy - V_sell| / V_bucket
     * 
     * This gives average order imbalance per bucket normalized by bucket size
     */
    private void calculateVPIN() {
        if (vpinBuckets.size() < 10) {
            vpin = 0.0;
            return;
        }
        
        int n = vpinBuckets.size();
        
        // Sum of absolute imbalances: Σ|V_buy - V_sell|
        double totalImbalance = vpinBuckets.stream()
            .mapToDouble(VPINBucket::getImbalance)
            .sum();
        
        // Average bucket volume
        double avgBucketVolume = vpinBuckets.stream()
            .mapToDouble(VPINBucket::getTotalVolume)
            .average()
            .orElse(1.0);
        
        // VPIN = (1/n) × Σ|V_buy - V_sell| / V_bucket
        vpin = (avgBucketVolume > 0) ? ((1.0 / n) * (totalImbalance / avgBucketVolume)) : 0.0;
    }
    
    /**
     * CRITICAL FIX #4: Kyle's Lambda with signed order flow
     * Uses VAR/Hasbrouck estimation: λ = Cov(ΔP, q) / Var(q)
     * where q is SIGNED volume (positive for buy, negative for sell)
     */
    private void updateKyleLambda(TickData tick, double currentMidPrice) {
        if (lastMidPrice == 0.0) {
            lastMidPrice = currentMidPrice;
            return;
        }
        
        double priceChange = currentMidPrice - lastMidPrice;
        
        // Classify trade direction for signed volume
        boolean isBuy = (microprice > 0) ? 
            tick.getLastRate() > microprice : 
            tick.getLastRate() >= midPrice;
        
        double signedVolume = isBuy ? 
            tick.getDeltaVolume() : 
            -tick.getDeltaVolume();
        
        // Store observation
        PriceImpactObservation obs = new PriceImpactObservation();
        obs.priceChange = priceChange;
        obs.signedVolume = signedVolume;
        obs.timestamp = tick.getTimestamp();
        
        priceImpactHistory.addLast(obs);
        
        // Keep only last 100 observations (O(1) removal with Deque)
        if (priceImpactHistory.size() > 100) {
            priceImpactHistory.removeFirst();
        }
        
        // Calculate Kyle's Lambda (regression coefficient)
        if (priceImpactHistory.size() >= MIN_OBSERVATIONS) {
            kyleLambda = calculateKyleLambdaRegression();
        }
        
        lastMidPrice = currentMidPrice;
    }
    
    /**
     * Calculate Kyle's Lambda via OLS regression
     * λ = Cov(ΔP, q) / Var(q)
     */
    private double calculateKyleLambdaRegression() {
        // Calculate means
        double meanPriceChange = priceImpactHistory.stream()
            .mapToDouble(PriceImpactObservation::getPriceChange)
            .average()
            .orElse(0.0);
        
        double meanSignedVolume = priceImpactHistory.stream()
            .mapToDouble(PriceImpactObservation::getSignedVolume)
            .average()
            .orElse(0.0);
        
        // Calculate covariance and variance
        double covariance = 0.0;
        double varianceVolume = 0.0;
        
        for (PriceImpactObservation obs : priceImpactHistory) {
            double pDev = obs.priceChange - meanPriceChange;
            double vDev = obs.signedVolume - meanSignedVolume;
            covariance += pDev * vDev;
            varianceVolume += vDev * vDev;
        }
        
        int n = priceImpactHistory.size();
        covariance /= n;
        varianceVolume /= n;
        
        // Kyle's Lambda = Cov / Var
        return (varianceVolume > 0) ? (covariance / varianceVolume) : 0.0;
    }
    
    /**
     * Mark as complete
     */
    public void markComplete() {
        complete = true;
    }
    
    /**
     * Build microstructure data output
     */
    public MicrostructureData toMicrostructureData(Long windowStart, Long windowEnd) {
        return MicrostructureData.builder()
            .ofi(ofi)
            .vpin(vpin)
            .depthImbalance(depthImbalance)
            .kyleLambda(kyleLambda)
            .effectiveSpread(effectiveSpread)
            .microprice(microprice)
            .bidAskSpread(bidAskSpread)
            .midPrice(midPrice)
            .isComplete(complete)
            .windowStart(windowStart)
            .windowEnd(windowEnd)
            .build();
    }
}
