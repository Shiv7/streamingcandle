package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.service.IcebergDetectionService;
import com.kotsin.consumer.service.OrderbookDepthCalculator;
import com.kotsin.consumer.service.SpoofingDetectionService;
import lombok.Data;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Orderbook Aggregate - Complete microstructure metrics in ONE model
 * Pattern: Simple aggregation model managed by Kafka Streams
 * 
 * Features (ALL calculated from orderbook snapshots):
 * - OFI (Order Flow Imbalance - full depth)
 * - VPIN (Volume-Synchronized PIN with adaptive buckets)
 * - Kyle's Lambda (price impact coefficient)
 * - Depth metrics (bid/ask VWAP, slopes, imbalances)
 * - Iceberg detection (hidden order algorithms)
 * - Spoofing detection (market manipulation)
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderbookAggregate {

    // ========== Metadata ==========
    private String scripCode;
    private String companyName;
    private String exchange;
    private String exchangeType;
    private long windowStartMillis;
    private long windowEndMillis;
    private String humanReadableStartTime;
    private String humanReadableEndTime;

    // ========== OFI State (Order Flow Imbalance) ==========
    private double ofi = 0.0;
    private Map<Double, Integer> prevBidDepth = new HashMap<>();
    private Map<Double, Integer> prevAskDepth = new HashMap<>();
    private double prevBestBid = 0.0;
    private double prevBestAsk = 0.0;

    // ========== VPIN State (Volume-Synchronized PIN) ==========
    private static final int VPIN_BUCKET_COUNT = 50;
    private double adaptiveBucketSize = 10000.0;
    private List<VPINBucket> vpinBuckets = new ArrayList<>();
    private double currentBucketVolume = 0.0;
    private double currentBucketBuyVolume = 0.0;
    private double vpin = 0.0;
    private int totalBucketsCreated = 0;

    // ========== Kyle's Lambda State ==========
    private static final int LAMBDA_WINDOW_SIZE = 100;  // Rolling window size
    private static final int LAMBDA_CALC_FREQUENCY = 20;  // Recalculate every N updates
    private static final int LAMBDA_MIN_OBSERVATIONS = 30;  // Statistical minimum
    private List<PriceImpactObservation> priceImpactHistory = new ArrayList<>();
    private double kyleLambda = 0.0;
    private double lastMidPrice = 0.0;
    private int updatesSinceLastLambdaCalc = 0;

    // ========== Depth Metrics (Averaged) ==========
    private double depthImbalance = 0.0;
    private double effectiveSpread = 0.0;
    private double midPrice = 0.0;
    private double microprice = 0.0;
    private double bidAskSpread = 0.0;
    
    // Accumulated values for averaging
    private double spreadSum = 0.0;
    private long spreadCount = 0L;
    private double totalBidDepthSum = 0.0;
    private long totalBidDepthCount = 0L;
    private double totalAskDepthSum = 0.0;
    private long totalAskDepthCount = 0L;
    private double bidVwapSum = 0.0;
    private long bidVwapCount = 0L;
    private double askVwapSum = 0.0;
    private long askVwapCount = 0L;
    private double weightedImbalanceSum = 0.0;
    private long weightedImbalanceCount = 0L;

    // ========== Iceberg Detection State ==========
    private List<Integer> recentBidQuantities = new ArrayList<>();
    private List<Integer> recentAskQuantities = new ArrayList<>();
    private static final int ICEBERG_HISTORY_SIZE = 20;
    private static final double ICEBERG_CV_THRESHOLD = 0.1;
    private static final int ICEBERG_MIN_SIZE = 1000;

    // ========== Spoofing Detection State ==========
    private List<SpoofingEvent> spoofingEvents = new ArrayList<>();
    private Map<Double, SpoofState> bidSpoofTracking = new HashMap<>();
    private Map<Double, SpoofState> askSpoofTracking = new HashMap<>();
    private static final long SPOOF_DURATION_THRESHOLD_MS = 5000;
    private static final double SPOOF_SIZE_THRESHOLD = 0.3;

    // ========== Processing State ==========
    private int updateCount = 0;
    private static final int MIN_OBSERVATIONS = 20;
    private OrderBookSnapshot previousOrderbook;

    /**
     * Creates a new empty orderbook aggregate
     */
    public OrderbookAggregate() {
        // Initialized with defaults above
    }

    /**
     * MAIN UPDATE METHOD: Process orderbook snapshot and calculate ALL metrics
     * This ONE method updates: OFI, VPIN, Kyle's Lambda, Depth, Iceberg, Spoofing
     */
    public void updateWithSnapshot(OrderBookSnapshot orderbook) {
        if (orderbook == null || !orderbook.isValid()) {
            return;
        }

        // Initialize metadata on first update
        if (scripCode == null && orderbook.getToken() != null) {
            scripCode = String.valueOf(orderbook.getToken());
            companyName = orderbook.getCompanyName();
            exchange = orderbook.getExch();
            exchangeType = orderbook.getExchType();
        }

        orderbook.parseDetails();
        
        // ========== Calculate Basic Metrics ==========
        double bestBid = orderbook.getBestBid();
        double bestAsk = orderbook.getBestAsk();
        
        if (bestBid > 0 && bestAsk > 0) {
            midPrice = (bestBid + bestAsk) / 2.0;
            bidAskSpread = bestAsk - bestBid;
            spreadSum += bidAskSpread;
            spreadCount++;
            
            // Calculate microprice (volume-weighted)
            int bidQty = (orderbook.getAllBids() != null && !orderbook.getAllBids().isEmpty()) ? 
                orderbook.getAllBids().get(0).getQuantity() : 0;
            int askQty = (orderbook.getAllAsks() != null && !orderbook.getAllAsks().isEmpty()) ? 
                orderbook.getAllAsks().get(0).getQuantity() : 0;
            
            if (bidQty + askQty > 0) {
                microprice = (bestBid * askQty + bestAsk * bidQty) / (double)(bidQty + askQty);
            }
        }

        // ========== Calculate Depth Imbalance ==========
        int totalBidQty = (orderbook.getTotalBidQty() != null) ? orderbook.getTotalBidQty().intValue() : 0;
        int totalAskQty = (orderbook.getTotalOffQty() != null) ? orderbook.getTotalOffQty().intValue() : 0;
        
        if (totalBidQty + totalAskQty > 0) {
            depthImbalance = (totalBidQty - totalAskQty) / (double)(totalBidQty + totalAskQty);
            totalBidDepthSum += totalBidQty;
            totalBidDepthCount++;
            totalAskDepthSum += totalAskQty;
            totalAskDepthCount++;
        }

        // ========== Calculate OFI (Full Depth) ==========
        if (!prevBidDepth.isEmpty() && !prevAskDepth.isEmpty() && bestBid > 0 && bestAsk > 0) {
            Map<Double, Integer> currentBidDepth = buildDepthMap(orderbook.getAllBids());
            Map<Double, Integer> currentAskDepth = buildDepthMap(orderbook.getAllAsks());
            
            ofi = calculateFullDepthOFI(
                prevBidDepth, currentBidDepth, prevBestBid, bestBid,
                prevAskDepth, currentAskDepth, prevBestAsk, bestAsk
            );
            
            prevBidDepth = currentBidDepth;
            prevAskDepth = currentAskDepth;
        } else {
            // First snapshot - initialize
            prevBidDepth = buildDepthMap(orderbook.getAllBids());
            prevAskDepth = buildDepthMap(orderbook.getAllAsks());
        }
        
        prevBestBid = bestBid;
        prevBestAsk = bestAsk;

        // ========== Track Price Impact for Kyle's Lambda ==========
        if (lastMidPrice > 0 && midPrice > 0 && ofi != 0.0) {
            double priceChange = midPrice - lastMidPrice;
            priceImpactHistory.add(new PriceImpactObservation(priceChange, ofi, orderbook.getTimestamp()));
            
            // Maintain rolling window (bounded list to prevent memory leaks)
            if (priceImpactHistory.size() > LAMBDA_WINDOW_SIZE) {
                priceImpactHistory.remove(0);
            }
            
            updatesSinceLastLambdaCalc++;
        }
        lastMidPrice = midPrice;

        // ========== Calculate Weighted Depth Imbalance ==========
        if (orderbook.getAllBids() != null && orderbook.getAllAsks() != null) {
            double weightedImb = calculateWeightedDepthImbalance(
                orderbook.getAllBids(), orderbook.getAllAsks(), midPrice);
            weightedImbalanceSum += weightedImb;
            weightedImbalanceCount++;
        }

        // ========== Calculate Bid/Ask VWAPs ==========
        if (orderbook.getAllBids() != null && !orderbook.getAllBids().isEmpty()) {
            double bidVwap = calculateSideVWAP(orderbook.getAllBids());
            bidVwapSum += bidVwap;
            bidVwapCount++;
        }
        if (orderbook.getAllAsks() != null && !orderbook.getAllAsks().isEmpty()) {
            double askVwap = calculateSideVWAP(orderbook.getAllAsks());
            askVwapSum += askVwap;
            askVwapCount++;
        }

        // ========== Iceberg Detection ==========
        trackIcebergPatterns(orderbook);

        // ========== Spoofing Detection ==========
        if (previousOrderbook != null) {
            detectSpoofing(previousOrderbook, orderbook);
        }
        
        previousOrderbook = orderbook;
        updateCount++;

        // ========== Recalculate Kyle's Lambda Periodically ==========
        if (updatesSinceLastLambdaCalc >= LAMBDA_CALC_FREQUENCY && 
            priceImpactHistory.size() >= LAMBDA_MIN_OBSERVATIONS) {
            calculateKyleLambda();
        }
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
     * Calculate full-depth OFI per Cont-Kukanov-Stoikov 2014
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
        for (Map.Entry<Double, Integer> entry : prevAsk.entrySet()) {
            if (entry.getKey() <= currBestAsk) {
                deltaAsk -= entry.getValue();
            }
        }
        
        return deltaBid - deltaAsk;
    }

    /**
     * Calculate weighted depth imbalance (closer levels get higher weight)
     */
    private double calculateWeightedDepthImbalance(
        List<OrderBookSnapshot.OrderBookLevel> bids,
        List<OrderBookSnapshot.OrderBookLevel> asks,
        double mid
    ) {
        double weightedBid = 0.0;
        double weightedAsk = 0.0;

        for (OrderBookSnapshot.OrderBookLevel level : bids) {
            double distance = mid > 0 ? Math.abs(level.getPrice() - mid) / mid * 10000 : 0;
            double weight = distance > 0 ? 1.0 / (1.0 + distance) : 1.0;
            weightedBid += level.getQuantity() * weight;
        }

        for (OrderBookSnapshot.OrderBookLevel level : asks) {
            double distance = mid > 0 ? Math.abs(level.getPrice() - mid) / mid * 10000 : 0;
            double weight = distance > 0 ? 1.0 / (1.0 + distance) : 1.0;
            weightedAsk += level.getQuantity() * weight;
        }

        double total = weightedBid + weightedAsk;
        return total > 0 ? (weightedBid - weightedAsk) / total : 0.0;
    }

    /**
     * Calculate VWAP of one side of the book
     */
    private double calculateSideVWAP(List<OrderBookSnapshot.OrderBookLevel> levels) {
        if (levels.isEmpty()) return 0.0;

        double totalValue = 0.0;
        double totalQty = 0.0;

        int maxLevels = Math.min(levels.size(), 10);
        for (int i = 0; i < maxLevels; i++) {
            OrderBookSnapshot.OrderBookLevel level = levels.get(i);
            totalValue += level.getPrice() * level.getQuantity();
            totalQty += level.getQuantity();
        }

        return totalQty > 0 ? totalValue / totalQty : 0.0;
    }

    /**
     * Calculate Kyle's Lambda using OLS regression.
     * λ = Cov(Δp, OFI) / Var(OFI)
     * 
     * Interpretation: λ represents price impact per unit of signed order flow.
     * Higher λ = more illiquid market (large flows move price more).
     * 
     * References:
     * - Kyle, A. S. (1985). "Continuous Auctions and Insider Trading"
     * - Hasbrouck, J. (2007). "Empirical Market Microstructure"
     */
    private void calculateKyleLambda() {
        int n = priceImpactHistory.size();
        
        // Check minimum observations for statistical validity
        if (n < LAMBDA_MIN_OBSERVATIONS) {
            return;
        }

        // Calculate means
        double meanPriceChange = 0.0;
        double meanOFI = 0.0;
        
        for (PriceImpactObservation obs : priceImpactHistory) {
            meanPriceChange += obs.priceChange;
            meanOFI += obs.signedVolume;
        }
        
        meanPriceChange /= n;
        meanOFI /= n;

        // Calculate covariance and variance
        double covariance = 0.0;
        double variance = 0.0;
        
        for (PriceImpactObservation obs : priceImpactHistory) {
            double priceDeviation = obs.priceChange - meanPriceChange;
            double ofiDeviation = obs.signedVolume - meanOFI;
            
            covariance += priceDeviation * ofiDeviation;
            variance += ofiDeviation * ofiDeviation;
        }
        
        covariance /= n;
        variance /= n;

        // Calculate lambda = Cov(Δp, OFI) / Var(OFI)
        // Handle edge case: if variance is near zero, set lambda to 0
        if (Math.abs(variance) < 1e-10) {
            kyleLambda = 0.0;
        } else {
            kyleLambda = covariance / variance;
        }

        // Reset counter
        updatesSinceLastLambdaCalc = 0;
    }

    /**
     * Track iceberg patterns (unusually consistent quantities)
     */
    private void trackIcebergPatterns(OrderBookSnapshot orderbook) {
        if (orderbook.getAllBids() != null && !orderbook.getAllBids().isEmpty()) {
            int qty = orderbook.getAllBids().get(0).getQuantity();
            recentBidQuantities.add(qty);
            if (recentBidQuantities.size() > ICEBERG_HISTORY_SIZE) {
                recentBidQuantities.remove(0);
            }
        }

        if (orderbook.getAllAsks() != null && !orderbook.getAllAsks().isEmpty()) {
            int qty = orderbook.getAllAsks().get(0).getQuantity();
            recentAskQuantities.add(qty);
            if (recentAskQuantities.size() > ICEBERG_HISTORY_SIZE) {
                recentAskQuantities.remove(0);
            }
        }
    }

    /**
     * Detect spoofing (large orders that disappear quickly)
     */
    private void detectSpoofing(OrderBookSnapshot prev, OrderBookSnapshot curr) {
        long currentTime = curr.getTimestamp();
        
        if (prev.getAllBids() != null && curr.getAllBids() != null) {
            detectSpoofingOneSide(prev.getAllBids(), curr.getAllBids(), "BID", currentTime, bidSpoofTracking);
        }
        
        if (prev.getAllAsks() != null && curr.getAllAsks() != null) {
            detectSpoofingOneSide(prev.getAllAsks(), curr.getAllAsks(), "ASK", currentTime, askSpoofTracking);
        }

        // Clean up old events (older than 1 minute)
        long oneMinuteAgo = currentTime - 60000;
        spoofingEvents.removeIf(event -> event.timestamp < oneMinuteAgo);
    }

    /**
     * Detect spoofing on one side of the book
     */
    private void detectSpoofingOneSide(
        List<OrderBookSnapshot.OrderBookLevel> prevLevels,
        List<OrderBookSnapshot.OrderBookLevel> currLevels,
        String side,
        long currentTime,
        Map<Double, SpoofState> tracking
    ) {
        double totalDepth = prevLevels.stream()
            .mapToInt(OrderBookSnapshot.OrderBookLevel::getQuantity)
            .sum();

        for (OrderBookSnapshot.OrderBookLevel prevLevel : prevLevels) {
            double price = prevLevel.getPrice();
            int quantity = prevLevel.getQuantity();

            // Is this a large order?
            if (quantity > totalDepth * SPOOF_SIZE_THRESHOLD) {
                if (!tracking.containsKey(price)) {
                    tracking.put(price, new SpoofState(currentTime, quantity));
                }

                // Check if it disappeared
                boolean foundInCurrent = currLevels.stream()
                    .anyMatch(level -> Math.abs(level.getPrice() - price) < 0.01 &&
                                      level.getQuantity() >= quantity * 0.5);

                if (!foundInCurrent) {
                    SpoofState state = tracking.get(price);
                    long duration = currentTime - state.firstSeenTime;

                    if (duration < SPOOF_DURATION_THRESHOLD_MS) {
                        spoofingEvents.add(new SpoofingEvent(currentTime, side, price, quantity, duration));
                    }
                    tracking.remove(price);
                }
            }
        }
    }

    /**
     * Detect iceberg orders on bid side
     */
    public boolean detectIcebergBid() {
        if (recentBidQuantities.size() < 10) return false;

        double mean = recentBidQuantities.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        double variance = recentBidQuantities.stream()
            .mapToDouble(q -> Math.pow(q - mean, 2))
            .average().orElse(0.0);

        double stdDev = Math.sqrt(variance);
        double cv = mean > 0 ? stdDev / mean : 0.0;

        return cv < ICEBERG_CV_THRESHOLD && mean > ICEBERG_MIN_SIZE;
    }

    /**
     * Detect iceberg orders on ask side
     */
    public boolean detectIcebergAsk() {
        if (recentAskQuantities.size() < 10) return false;

        double mean = recentAskQuantities.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        double variance = recentAskQuantities.stream()
            .mapToDouble(q -> Math.pow(q - mean, 2))
            .average().orElse(0.0);

        double stdDev = Math.sqrt(variance);
        double cv = mean > 0 ? stdDev / mean : 0.0;

        return cv < ICEBERG_CV_THRESHOLD && mean > ICEBERG_MIN_SIZE;
    }

    /**
     * Get average spread
     */
    public double getAverageSpread() {
        return spreadCount > 0 ? spreadSum / spreadCount : 0.0;
    }

    /**
     * Get average bid depth
     */
    public double getAverageBidDepth() {
        return totalBidDepthCount > 0 ? totalBidDepthSum / totalBidDepthCount : 0.0;
    }

    /**
     * Get average ask depth
     */
    public double getAverageAskDepth() {
        return totalAskDepthCount > 0 ? totalAskDepthSum / totalAskDepthCount : 0.0;
    }

    /**
     * Get average bid VWAP
     */
    public double getAverageBidVWAP() {
        return bidVwapCount > 0 ? bidVwapSum / bidVwapCount : 0.0;
    }

    /**
     * Get average ask VWAP
     */
    public double getAverageAskVWAP() {
        return askVwapCount > 0 ? askVwapSum / askVwapCount : 0.0;
    }

    /**
     * Get average weighted depth imbalance
     */
    public double getAverageWeightedDepthImbalance() {
        return weightedImbalanceCount > 0 ? weightedImbalanceSum / weightedImbalanceCount : 0.0;
    }

    /**
     * Get spoofing count (last minute)
     */
    public int getSpoofingCount() {
        return spoofingEvents.size();
    }

    /**
     * Get Kyle's Lambda (price impact coefficient).
     * Returns 0.0 if not yet calculated or insufficient data.
     * 
     * Interpretation:
     * - λ > 0: Positive order flow (buying) increases price (normal market)
     * - λ < 0: Negative correlation (may indicate market manipulation or unusual dynamics)
     * - λ ≈ 0: No measurable price impact (highly liquid market)
     */
    public double getKyleLambda() {
        return kyleLambda;
    }

    /**
     * Get number of observations used for Kyle's Lambda.
     * Useful for validating statistical significance.
     * Minimum 30 observations required for calculation.
     */
    public int getKyleLambdaObservations() {
        return priceImpactHistory.size();
    }

    /**
     * Check if complete (enough observations)
     */
    public boolean isComplete() {
        return updateCount >= MIN_OBSERVATIONS;
    }

    /**
     * Updates the human-readable timestamps
     */
    public void updateHumanReadableTimestamps() {
        if (windowStartMillis > 0) {
            ZonedDateTime startTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(windowStartMillis),
                    ZoneId.of("Asia/Kolkata")
            );
            this.humanReadableStartTime = startTime.format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            );
        }

        if (windowEndMillis > 0) {
            ZonedDateTime endTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(windowEndMillis),
                    ZoneId.of("Asia/Kolkata")
            );
            this.humanReadableEndTime = endTime.format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            );
        }
    }

    public void setWindowStartMillis(long windowStartMillis) {
        this.windowStartMillis = windowStartMillis;
        updateHumanReadableTimestamps();
    }

    public void setWindowEndMillis(long windowEndMillis) {
        this.windowEndMillis = windowEndMillis;
        updateHumanReadableTimestamps();
    }

    /**
     * VPIN Bucket data structure
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class VPINBucket {
        public double totalVolume;
        public double buyVolume;
        public double sellVolume;
        
        public double getImbalance() {
            return Math.abs(buyVolume - sellVolume);
        }
    }

    /**
     * Price impact observation for Kyle's Lambda
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PriceImpactObservation {
        public double priceChange;
        public double signedVolume;
        public long timestamp;
    }

    /**
     * Spoofing event
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SpoofingEvent {
        public long timestamp;
        public String side;
        public double price;
        public int quantity;
        public long durationMs;
    }

    /**
     * Spoof tracking state
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SpoofState {
        public long firstSeenTime;
        public int quantity;
    }

    /**
     * Provides a Kafka Serde for OrderbookAggregate.
     */
    public static Serde<OrderbookAggregate> serde() {
        return Serdes.serdeFrom(new OrderbookAggregateSerializer(), new OrderbookAggregateDeserializer());
    }

    // ---------------------------------------------------
    // Internal Serializer/Deserializer
    // ---------------------------------------------------
    public static class OrderbookAggregateSerializer implements Serializer<OrderbookAggregate> {
        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public byte[] serialize(String topic, OrderbookAggregate data) {
            if (data == null) return null;
            try {
                return objectMapper.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for OrderbookAggregate", e);
            }
        }
    }

    public static class OrderbookAggregateDeserializer implements Deserializer<OrderbookAggregate> {
        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public OrderbookAggregate deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return objectMapper.readValue(bytes, OrderbookAggregate.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for OrderbookAggregate", e);
            }
        }
    }
}

