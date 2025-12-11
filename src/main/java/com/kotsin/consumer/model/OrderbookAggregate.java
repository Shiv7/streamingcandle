package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    // BUG-022 FIX: Removed unused VPIN fields (VPIN is calculated in EnrichedCandlestick, not here)

    // ========== Kyle's Lambda State ==========
    private static final int LAMBDA_WINDOW_SIZE = 100;  // Rolling window size
    private static final int LAMBDA_CALC_FREQUENCY = 20;  // Recalculate every N updates
    private static final int LAMBDA_MIN_OBSERVATIONS = 30;  // Statistical minimum
    @JsonIgnore private Deque<PriceImpactObservation> priceImpactHistory = new ArrayDeque<>(); // ring buffer for rolling window
    private double kyleLambda = 0.0;
    @JsonIgnore private double lastMidPrice = 0.0;
    @JsonIgnore private int updatesSinceLastLambdaCalc = 0;
    // Rolling stats for Welford-style mergeable lambda
    @JsonIgnore private long lambdaObsCount = 0;
    @JsonIgnore private double sumOFI = 0.0, sumDP = 0.0, sumOFI2 = 0.0, sumDP2 = 0.0, sumOFIDP = 0.0;

    // ========== Depth Metrics (Averaged) ==========
    private double depthImbalance = 0.0;
    @JsonIgnore private double effectiveSpread = 0.0;
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
    @JsonIgnore private OrderBookSnapshot previousOrderbook;

    // ========== Configurable Parameters ==========
    @JsonIgnore private double instrumentTickSize = 0.0; // per-instance tick size
    @JsonIgnore private Double instrumentSpoofSizeRatio = null;
    @JsonIgnore private Double instrumentSpoofEpsilonTicks = null;

    // BUG-013 FIX: Use AtomicReference for thread-safe configuration
    private static final java.util.concurrent.atomic.AtomicReference<OrderbookConfig> CONFIG =
        new java.util.concurrent.atomic.AtomicReference<>(new OrderbookConfig());

    /**
     * Thread-safe configuration holder
     */
    public static class OrderbookConfig {
        public final double tickSize;
        public final double spoofSizeRatio;
        public final int spoofConfirmSnapshots;
        public final double spoofPriceEpsilonTicks;
        public final int lambdaMinObs;
        public final int lambdaFreq;
        public final double lambdaOfiEps;

        public OrderbookConfig() {
            this(0.05, 0.3, 2, 1.0, 10, 5, 1.0);
        }

        public OrderbookConfig(double tickSize, double spoofSizeRatio, int spoofConfirmSnapshots,
                               double spoofPriceEpsilonTicks, int lambdaMinObs, int lambdaFreq, double lambdaOfiEps) {
            this.tickSize = tickSize;
            this.spoofSizeRatio = spoofSizeRatio;
            this.spoofConfirmSnapshots = spoofConfirmSnapshots;
            this.spoofPriceEpsilonTicks = spoofPriceEpsilonTicks;
            this.lambdaMinObs = lambdaMinObs;
            this.lambdaFreq = lambdaFreq;
            this.lambdaOfiEps = lambdaOfiEps;
        }
    }

    public static void configure(double tickSize,
                                 double spoofSizeRatio,
                                 int spoofConfirmSnapshots,
                                 int lambdaMinObs,
                                 int lambdaCalcFreq,
                                 double lambdaOfiEps) {
        OrderbookConfig cfg = CONFIG.get();
        CONFIG.set(new OrderbookConfig(tickSize, spoofSizeRatio, spoofConfirmSnapshots,
                                       cfg.spoofPriceEpsilonTicks, lambdaMinObs, lambdaCalcFreq, lambdaOfiEps));
    }

    public static void setSpoofPriceEpsilonTicks(double v) {
        OrderbookConfig cfg = CONFIG.get();
        CONFIG.set(new OrderbookConfig(cfg.tickSize, cfg.spoofSizeRatio, cfg.spoofConfirmSnapshots,
                                       v, cfg.lambdaMinObs, cfg.lambdaFreq, cfg.lambdaOfiEps));
    }

    private static OrderbookConfig getConfig() {
        return CONFIG.get();
    }

    public void setInstrumentTickSize(double tick) { this.instrumentTickSize = tick; }
    public double getEffectiveTickSize() {
        return instrumentTickSize > 0 ? instrumentTickSize : getConfig().tickSize;
    }
    public void setInstrumentSpoofSizeRatio(Double v) { this.instrumentSpoofSizeRatio = v; }
    public void setInstrumentSpoofEpsilonTicks(Double v) { this.instrumentSpoofEpsilonTicks = v; }
    public double getEffectiveSpoofSizeRatio() {
        return instrumentSpoofSizeRatio != null ? instrumentSpoofSizeRatio : getConfig().spoofSizeRatio;
    }
    public double getEffectiveSpoofEpsilonTicks() {
        return instrumentSpoofEpsilonTicks != null ? instrumentSpoofEpsilonTicks : getConfig().spoofPriceEpsilonTicks;
    }

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
            
            ofi += calculateFullDepthOFI(
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
        OrderbookConfig cfg = getConfig();
        if (lastMidPrice > 0 && midPrice > 0 && Math.abs(ofi) > cfg.lambdaOfiEps) {
            double dp = midPrice - lastMidPrice;

            // Add new observation (dp, ofi) and update rolling sums
            PriceImpactObservation obs = new PriceImpactObservation(dp, ofi, orderbook.getReceivedTimestamp());
            priceImpactHistory.addLast(obs);
            sumOFI += ofi; sumDP += dp; sumOFI2 += ofi * ofi; sumDP2 += dp * dp; sumOFIDP += ofi * dp;
            lambdaObsCount++;

            // BUG-009 FIX: Use priceImpactHistory.size() as source of truth
            while (priceImpactHistory.size() > LAMBDA_WINDOW_SIZE) {
                PriceImpactObservation old = priceImpactHistory.removeFirst();
                double oDP = old.priceChange;
                double oOFI = old.signedVolume;
                sumOFI -= oOFI; sumDP -= oDP; sumOFI2 -= oOFI * oOFI; sumDP2 -= oDP * oDP; sumOFIDP -= oOFI * oDP;
                lambdaObsCount--;  // Keep in sync
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
        OrderbookConfig config = getConfig();
        if (updatesSinceLastLambdaCalc >= config.lambdaFreq && lambdaObsCount >= config.lambdaMinObs) {
            calculateKyleLambda();
        }
    }

    /**
     * Merge another aggregate into this one for multi-minute windows.
     * Sums accumulators and keeps latest point-in-time fields.
     */
    public void merge(OrderbookAggregate other) {
        if (other == null) return;

        // Sum OFI and accumulators
        this.ofi += other.ofi;
        this.spreadSum += other.spreadSum; this.spreadCount += other.spreadCount;
        this.totalBidDepthSum += other.totalBidDepthSum; this.totalBidDepthCount += other.totalBidDepthCount;
        this.totalAskDepthSum += other.totalAskDepthSum; this.totalAskDepthCount += other.totalAskDepthCount;
        this.weightedImbalanceSum += other.weightedImbalanceSum; this.weightedImbalanceCount += other.weightedImbalanceCount;

        // Merge lambda rolling sums
        this.lambdaObsCount += other.lambdaObsCount;
        this.sumOFI += other.sumOFI;
        this.sumDP += other.sumDP;
        this.sumOFI2 += other.sumOFI2;
        this.sumDP2 += other.sumDP2;
        this.sumOFIDP += other.sumOFIDP;

        // Latest market snapshot style fields
        this.midPrice = other.midPrice;
        this.microprice = other.microprice;
        this.bidAskSpread = other.bidAskSpread;
        this.depthImbalance = other.depthImbalance;

        // Metadata
        this.exchange = other.exchange;
        this.exchangeType = other.exchangeType;
        this.companyName = other.companyName;
        this.scripCode = other.scripCode;

        // Window times (outer aggregator sets window times)
        this.updateCount += other.updateCount;
    }

    /**
     * Build depth map from orderbook levels
     */
    private Map<Double, Integer> buildDepthMap(List<OrderBookSnapshot.OrderBookLevel> levels) {
        Map<Double, Integer> depthMap = new HashMap<>();
        if (levels != null) {
            for (OrderBookSnapshot.OrderBookLevel level : levels) {
                if (level.getPrice() > 0 && level.getQuantity() > 0) {
                    double qPrice = quantize(level.getPrice(), getEffectiveTickSize());
                    depthMap.merge(qPrice, level.getQuantity(), Integer::sum);
                }
            }
        }
        return depthMap;
    }

    private double quantize(double price, double step) {
        if (step <= 0) return price;
        java.math.BigDecimal p = java.math.BigDecimal.valueOf(price);
        java.math.BigDecimal s = java.math.BigDecimal.valueOf(step);
        return p.divide(s, 0, java.math.RoundingMode.HALF_UP).multiply(s).doubleValue();
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
        
        double bidBand = Math.min(prevBestBid, currBestBid);
        double askBand = Math.max(prevBestAsk, currBestAsk);

        for (Map.Entry<Double, Integer> entry : currBid.entrySet()) {
            if (entry.getKey() >= bidBand) {
                int prevQty = prevBid.getOrDefault(entry.getKey(), 0);
                deltaBid += (entry.getValue() - prevQty);
            }
        }
        for (Map.Entry<Double, Integer> entry : prevBid.entrySet()) {
            if (entry.getKey() >= bidBand && !currBid.containsKey(entry.getKey())) {
                deltaBid -= entry.getValue();
            }
        }

        for (Map.Entry<Double, Integer> entry : currAsk.entrySet()) {
            if (entry.getKey() <= askBand) {
                int prevQty = prevAsk.getOrDefault(entry.getKey(), 0);
                deltaAsk += (entry.getValue() - prevQty);
            }
        }
        for (Map.Entry<Double, Integer> entry : prevAsk.entrySet()) {
            if (entry.getKey() <= askBand && !currAsk.containsKey(entry.getKey())) {
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
        long n = lambdaObsCount;
        OrderbookConfig cfg = getConfig();
        if (n < cfg.lambdaMinObs) return;
        double meanOFI = sumOFI / n;
        double meanDP = sumDP / n;
        double varOFI = (sumOFI2 - sumOFI * sumOFI / n) / n;
        double cov = (sumOFIDP - sumOFI * sumDP / n) / n;
        kyleLambda = (Math.abs(varOFI) < 1e-10) ? 0.0 : (cov / varOFI);
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
        long prevTime = prev.getTimestamp();
        
        if (prev.getAllBids() != null && curr.getAllBids() != null) {
            detectSpoofingOneSide(prev.getAllBids(), curr.getAllBids(), "BID", prevTime, currentTime, bidSpoofTracking);
        }
        
        if (prev.getAllAsks() != null && curr.getAllAsks() != null) {
            detectSpoofingOneSide(prev.getAllAsks(), curr.getAllAsks(), "ASK", prevTime, currentTime, askSpoofTracking);
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
        long prevTime,
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
            if (totalDepth > 0 && quantity > totalDepth * getEffectiveSpoofSizeRatio()) {
                if (!tracking.containsKey(price)) {
                    tracking.put(price, new SpoofState(prevTime, quantity, 0));
                }

                // Check if it disappeared
                boolean foundInCurrent = currLevels.stream()
                    .anyMatch(level -> Math.abs(level.getPrice() - price) <= (getEffectiveTickSize() * getEffectiveSpoofEpsilonTicks()) &&
                                      level.getQuantity() >= quantity * 0.5);

                if (!foundInCurrent) {
                    SpoofState state = tracking.get(price);
                    state.missCount += 1;
                    OrderbookConfig cfg = getConfig();
                    if (state.missCount >= cfg.spoofConfirmSnapshots) {
                        long duration = currentTime - state.firstSeenTime;
                        if (duration < SPOOF_DURATION_THRESHOLD_MS) {
                            spoofingEvents.add(new SpoofingEvent(currentTime, side, price, quantity, duration));
                        }
                        tracking.remove(price);
                    } else {
                        tracking.put(price, state);
                    }
                } else {
                    // BUG-010 FIX: Reset miss count if order reappears
                    if (tracking.containsKey(price)) {
                        SpoofState state = tracking.get(price);
                        state.missCount = 0;  // Reset - order is back
                        tracking.put(price, state);
                    }
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
    public int getKyleLambdaObservations() { return (int) lambdaObsCount; }

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
        public int missCount;
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
    // BUG-015 FIX: Shared, thread-safe ObjectMapper
    private static final ObjectMapper SHARED_OBJECT_MAPPER = new ObjectMapper()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    public static class OrderbookAggregateSerializer implements Serializer<OrderbookAggregate> {
        @Override
        public byte[] serialize(String topic, OrderbookAggregate data) {
            if (data == null) return null;
            try {
                return SHARED_OBJECT_MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for OrderbookAggregate", e);
            }
        }
    }

    public static class OrderbookAggregateDeserializer implements Deserializer<OrderbookAggregate> {
        @Override
        public OrderbookAggregate deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return SHARED_OBJECT_MAPPER.readValue(bytes, OrderbookAggregate.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for OrderbookAggregate", e);
            }
        }
    }
}
