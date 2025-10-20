package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;


/**
 * Multi-timeframe state aggregator
 * Maintains state for all timeframes (1m, 2m, 3m, 5m, 15m, 30m)
 * and all features (candles, OI, imbalance bars, microstructure)
 */
@Data
@Slf4j
public class MultiTimeframeState {
    
    // Candle accumulators for each timeframe
    private final Map<String, CandleAccumulator> candleAccumulators = new HashMap<>();
    
    // OI accumulators for each timeframe
    private final Map<String, OiAccumulator> oiAccumulators = new HashMap<>();
    
    // Imbalance bar state
    private final ImbalanceBarAccumulator imbalanceBarAccumulator = new ImbalanceBarAccumulator();
    
    // Microstructure state
    private final MicrostructureAccumulator microstructureAccumulator = new MicrostructureAccumulator();
    
    // Basic info
    private String scripCode;
    private String companyName;
    private String exchange;
    private String exchangeType;
    private Long firstTickTime;
    private Long lastTickTime;
    private Long messageCount = 0L;
    
    // Timeframe definitions
    private static final List<String> TIMEFRAMES = Arrays.asList("1m", "2m", "3m", "5m", "15m", "30m");
    private static final Map<String, Integer> TIMEFRAME_MINUTES = Map.of(
        "1m", 1, "2m", 2, "3m", 3, "5m", 5, "15m", 15, "30m", 30
    );
    
    public MultiTimeframeState() {
        initializeAccumulators();
    }
    
    private void initializeAccumulators() {
        // Initialize candle accumulators
        for (String timeframe : TIMEFRAMES) {
            candleAccumulators.put(timeframe, new CandleAccumulator());
            oiAccumulators.put(timeframe, new OiAccumulator());
        }
    }
    
    public void addTick(TickData tick) {
        if (scripCode == null) {
            scripCode = tick.getScripCode();
            companyName = tick.getCompanyName();
            exchange = tick.getExchange();
            exchangeType = tick.getExchangeType();
            firstTickTime = tick.getTimestamp();
        }
        
        lastTickTime = tick.getTimestamp();
        messageCount++;
        
        // Update all timeframes
        updateAllTimeframes(tick);
        
        // Update OI if available
        if (tick.getOpenInterest() != null) {
            updateAllOiTimeframes(tick);
        }
        
        // Update imbalance bars
        imbalanceBarAccumulator.addTick(tick);
        
        // Update microstructure
        microstructureAccumulator.addTick(tick);
    }
    
    private void updateAllTimeframes(TickData tick) {
        long tickTime = tick.getTimestamp();
        
        for (Map.Entry<String, CandleAccumulator> entry : candleAccumulators.entrySet()) {
            String timeframe = entry.getKey();
            CandleAccumulator accumulator = entry.getValue();
            
            int minutes = TIMEFRAME_MINUTES.get(timeframe);
            long windowStart = alignToTimeframe(tickTime, minutes);
            
            // Initialize accumulator window if not set yet
            if (accumulator.getWindowStart() == null) {
                accumulator = new CandleAccumulator(windowStart, minutes);
                candleAccumulators.put(timeframe, accumulator);
            } else if (!accumulator.getWindowStart().equals(windowStart)) {
                // New window started, mark previous as complete and rotate
                accumulator.markComplete();
                accumulator = new CandleAccumulator(windowStart, minutes);
                candleAccumulators.put(timeframe, accumulator);
            }
            
            accumulator.addTick(tick);
        }
    }
    
    private void updateAllOiTimeframes(TickData tick) {
        for (Map.Entry<String, OiAccumulator> entry : oiAccumulators.entrySet()) {
            String timeframe = entry.getKey();
            OiAccumulator accumulator = entry.getValue();
            
            int minutes = TIMEFRAME_MINUTES.get(timeframe);
            long windowStart = alignToTimeframe(tick.getTimestamp(), minutes);
            
            if (accumulator.getWindowStart() == null) {
                accumulator = new OiAccumulator(windowStart, minutes);
                oiAccumulators.put(timeframe, accumulator);
            } else if (!accumulator.getWindowStart().equals(windowStart)) {
                accumulator.markComplete();
                accumulator = new OiAccumulator(windowStart, minutes);
                oiAccumulators.put(timeframe, accumulator);
            }
            
            accumulator.addOiData(tick);
        }
    }
    
    private long alignToTimeframe(long timestamp, int minutes) {
        return (timestamp / (minutes * 60_000)) * (minutes * 60_000);
    }
    
    public boolean hasAnyCompleteWindow() {
        return candleAccumulators.values().stream()
            .anyMatch(CandleAccumulator::isComplete);
    }
    
    public Set<String> getCompleteWindows() {
        return candleAccumulators.entrySet().stream()
            .filter(entry -> entry.getValue().isComplete())
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }
    
    public Map<String, CandleData> getMultiTimeframeCandles() {
        Map<String, CandleData> candles = new HashMap<>();
        
        for (Map.Entry<String, CandleAccumulator> entry : candleAccumulators.entrySet()) {
            String timeframe = entry.getKey();
            CandleAccumulator accumulator = entry.getValue();
            
            candles.put(timeframe, accumulator.toCandleData(exchange, exchangeType));
        }
        
        return candles;
    }
    
    public Map<String, OpenInterestTimeframeData> getOpenInterest() {
        Map<String, OpenInterestTimeframeData> oiData = new HashMap<>();
        
        for (Map.Entry<String, OiAccumulator> entry : oiAccumulators.entrySet()) {
            String timeframe = entry.getKey();
            OiAccumulator accumulator = entry.getValue();
            
            oiData.put(timeframe, accumulator.toOiTimeframeData());
        }
        
        return oiData;
    }
    
    public ImbalanceBarData getImbalanceBars() {
        return imbalanceBarAccumulator.toImbalanceBarData();
    }
    
    public MicrostructureData getMicrostructure() {
        return microstructureAccumulator.toMicrostructureData();
    }
    
    public String getDataQuality() {
        if (messageCount < 10) return "LOW";
        if (messageCount < 50) return "MEDIUM";
        return "HIGH";
    }
    
    public long getProcessingLatency() {
        return System.currentTimeMillis() - lastTickTime;
    }
}

/**
 * Candle accumulator for a specific timeframe
 */
@Data
class CandleAccumulator {
    private Long windowStart;
    private Double open;
    private Double high;
    private Double low;
    private Double close;
    private Long volume = 0L;
    private Long windowEnd;
    private int windowMinutes = 1;
    private boolean complete = false;
    private int tickCount = 0;

    // Extra metrics for advanced candle features
    private Double volumeSum = 0.0;  // For VWAP
    private Double priceVolumeSum = 0.0;  // For VWAP
    private Double previousClose = null;  // For log returns
    
    public CandleAccumulator() {
        this.windowStart = null;
    }
    
    public CandleAccumulator(Long windowStart, int minutes) {
        this.windowStart = windowStart;
        this.windowMinutes = Math.max(1, minutes);
        this.windowEnd = windowStart + (windowMinutes * 60L * 1000L);
    }
    
    public void addTick(TickData tick) {
        if (windowStart == null) {
            windowStart = alignToMinute(tick.getTimestamp());
            windowEnd = windowStart + (windowMinutes * 60L * 1000L);
        }

        tickCount++;

        if (open == null) {
            open = tick.getLastRate();
        }

        close = tick.getLastRate();

        if (high == null || tick.getLastRate() > high) {
            high = tick.getLastRate();
        }

        if (low == null || tick.getLastRate() < low) {
            low = tick.getLastRate();
        }

        if (tick.getDeltaVolume() != null) {
            volume += tick.getDeltaVolume();

            // Track for VWAP calculation
            volumeSum += tick.getDeltaVolume();
            priceVolumeSum += tick.getLastRate() * tick.getDeltaVolume();
        }
    }
    
    public void markComplete() {
        complete = true;
    }
    
    public boolean isComplete() {
        return complete;
    }
    
    private long alignToMinute(long timestamp) {
        return (timestamp / 60_000) * 60_000;
    }
    
    public CandleData toCandleData(String exchange, String exchangeType) {
        return CandleData.builder()
            .open(open)
            .high(high)
            .low(low)
            .close(close)
            .volume(volume)
            .windowStart(windowStart)
            .windowEnd(windowEnd)
            .isComplete(complete)
            .exchange(exchange)
            .exchangeType(exchangeType)
            .build();
    }

    /**
     * Convert to finalized Candlestick (for candle-complete-{tf} topics)
     * Includes optional extras based on feature flags
     */
    public Candlestick toFinalizedCandlestick(String scripCode, String companyName,
                                               String exchange, String exchangeType,
                                               boolean includeExtras) {
        Candlestick candle = new Candlestick();

        // Required fields
        candle.setScripCode(scripCode);
        candle.setCompanyName(companyName);
        candle.setExchange(exchange);
        candle.setExchangeType(exchangeType);
        candle.setWindowStartMillis(windowStart != null ? windowStart : 0);
        candle.setWindowEndMillis(windowEnd != null ? windowEnd : 0);
        candle.setIsComplete(complete);

        // OHLCV
        candle.setOpen(open != null ? open : 0.0);
        candle.setHigh(high != null ? high : 0.0);
        candle.setLow(low != null ? low : 0.0);
        candle.setClose(close != null ? close : 0.0);
        candle.setVolume(volume != null ? volume.intValue() : 0);

        // Optional extras (if enabled)
        if (includeExtras) {
            // VWAP
            if (volumeSum > 0) {
                candle.setVwap(priceVolumeSum / volumeSum);
            }

            // HLC3
            if (high != null && low != null && close != null) {
                candle.setHlc3((high + low + close) / 3.0);
            }

            // Log return from previous bar
            if (previousClose != null && close != null && previousClose > 0) {
                candle.setLogReturnFromPrevBar(Math.log(close / previousClose));
            }

            // Ticks in window
            candle.setTicksInWindow(tickCount);

            // Window latency
            candle.setWindowLatencyMs(System.currentTimeMillis() - windowEnd);
        }

        return candle;
    }

    public void setPreviousClose(Double prevClose) {
        this.previousClose = prevClose;
    }
}

/**
 * OI accumulator for a specific timeframe
 */
@Data
class OiAccumulator {
    private Long windowStart;
    private Long windowEnd;
    private Long oiStart;
    private Long oiEnd;
    private Long oiChange;
    private Double oiChangePercent;
    private Double oiMomentum;
    private Double oiConcentration;
    private boolean complete = false;
    
    private int windowMinutes = 1;
    
    public OiAccumulator() {
        this.windowStart = null;
    }
    
    public OiAccumulator(Long windowStart, int minutes) {
        this.windowStart = windowStart;
        this.windowMinutes = Math.max(1, minutes);
        this.windowEnd = windowStart + (windowMinutes * 60L * 1000L);
    }
    
    public void addOiData(TickData tick) {
        if (windowStart == null) {
            windowStart = alignToMinute(tick.getTimestamp());
            windowEnd = windowStart + (windowMinutes * 60L * 1000L);
        }
        
        if (oiStart == null) {
            oiStart = tick.getOpenInterest();
        }
        
        oiEnd = tick.getOpenInterest();
    }
    
    public void markComplete() {
        complete = true;
        calculateMetrics();
    }
    
    private void calculateMetrics() {
        if (oiStart != null && oiEnd != null) {
            oiChange = oiEnd - oiStart;
            oiChangePercent = oiStart != 0 ? (double) oiChange / oiStart * 100.0 : 0.0;
            
            if (windowStart != null && windowEnd != null) {
                long windowMinutes = (windowEnd - windowStart) / (60 * 1000);
                oiMomentum = windowMinutes > 0 ? (double) oiChange / windowMinutes : 0.0;
            }
        }
    }
    
    private long alignToMinute(long timestamp) {
        return (timestamp / 60_000) * 60_000;
    }
    
    public OpenInterestTimeframeData toOiTimeframeData() {
        return OpenInterestTimeframeData.builder()
            .oi(oiEnd)
            .oiChange(oiChange)
            .oiChangePercent(oiChangePercent)
            .oiMomentum(oiMomentum)
            .oiConcentration(oiConcentration)
            .isComplete(complete)
            .windowStart(windowStart)
            .windowEnd(windowEnd)
            .build();
    }
}

/**
 * Imbalance bar accumulator
 * Implements AFML Chapter 2 - Information-Driven Bars
 */
@Data
class ImbalanceBarAccumulator {
    // Current imbalance accumulators
    private Long volumeImbalance = 0L;
    private Long dollarImbalance = 0L;
    private Integer tickRuns = 0;
    private Long volumeRuns = 0L;

    // Direction tracking
    private String currentDirection = "NEUTRAL";
    private Double lastPrice = null;

    // Expected thresholds (EWMA-based)
    private Double expectedVolumeImbalance = 1000.0;  // Initial estimate
    private Double expectedDollarImbalance = 100000.0;  // Initial estimate
    private Double expectedTickRuns = 10.0;  // Initial estimate
    private Double expectedVolumeRuns = 5000.0;  // Initial estimate

    // EWMA alpha for threshold adaptation
    private static final double EWMA_ALPHA = 0.1;

    // Counters for bar completions
    private int vibCount = 0;
    private int dibCount = 0;
    private int trbCount = 0;
    private int vrbCount = 0;

    public void addTick(TickData tick) {
        if (tick.getDeltaVolume() == null || tick.getDeltaVolume() == 0) {
            return;
        }

        // Determine tick direction using tick rule
        String direction = determineDirection(tick);
        int directionSign = "BUY".equals(direction) ? 1 : -1;

        // Volume Imbalance (VIB)
        long signedVolume = tick.getDeltaVolume() * directionSign;
        volumeImbalance += signedVolume;

        // Dollar Imbalance (DIB)
        long dollarVolume = (long)(tick.getDeltaVolume() * tick.getLastRate());
        dollarImbalance += dollarVolume * directionSign;

        // Tick Runs (TRB)
        if (direction.equals(currentDirection)) {
            tickRuns++;
        } else {
            tickRuns = 1;
            currentDirection = direction;
        }

        // Volume Runs (VRB)
        if (direction.equals(currentDirection)) {
            volumeRuns += tick.getDeltaVolume().longValue();
        } else {
            volumeRuns = tick.getDeltaVolume().longValue();
        }

        lastPrice = tick.getLastRate();

        // Check thresholds and update EWMA estimates
        checkAndUpdateThresholds();
    }

    private String determineDirection(TickData tick) {
        if (lastPrice == null) {
            return "NEUTRAL";
        }

        double currentPrice = tick.getLastRate();
        if (currentPrice > lastPrice) {
            return "BUY";
        } else if (currentPrice < lastPrice) {
            return "SELL";
        } else {
            return currentDirection;  // No change, keep current direction
        }
    }

    private void checkAndUpdateThresholds() {
        // Volume Imbalance Bar threshold
        if (Math.abs(volumeImbalance) >= expectedVolumeImbalance) {
            // Update EWMA estimate
            expectedVolumeImbalance = EWMA_ALPHA * Math.abs(volumeImbalance)
                                    + (1 - EWMA_ALPHA) * expectedVolumeImbalance;
            vibCount++;
            volumeImbalance = 0L;  // Reset after bar emission
        }

        // Dollar Imbalance Bar threshold
        if (Math.abs(dollarImbalance) >= expectedDollarImbalance) {
            expectedDollarImbalance = EWMA_ALPHA * Math.abs(dollarImbalance)
                                    + (1 - EWMA_ALPHA) * expectedDollarImbalance;
            dibCount++;
            dollarImbalance = 0L;
        }

        // Tick Runs Bar threshold
        if (Math.abs(tickRuns) >= expectedTickRuns) {
            expectedTickRuns = EWMA_ALPHA * Math.abs(tickRuns)
                             + (1 - EWMA_ALPHA) * expectedTickRuns;
            trbCount++;
            tickRuns = 0;
        }

        // Volume Runs Bar threshold
        if (Math.abs(volumeRuns) >= expectedVolumeRuns) {
            expectedVolumeRuns = EWMA_ALPHA * Math.abs(volumeRuns)
                               + (1 - EWMA_ALPHA) * expectedVolumeRuns;
            vrbCount++;
            volumeRuns = 0L;
        }
    }

    public ImbalanceBarData toImbalanceBarData() {
        return ImbalanceBarData.create(
            volumeImbalance, dollarImbalance, tickRuns, volumeRuns,
            currentDirection, expectedVolumeImbalance, expectedDollarImbalance,
            expectedTickRuns, expectedVolumeRuns
        );
    }
}

/**
 * Microstructure accumulator
 * Implements AFML Chapter 19 - Microstructure Features
 */
@Data
class MicrostructureAccumulator {
    // Order Flow Imbalance (OFI)
    private Double ofi = 0.0;
    private Double prevBidQty = 0.0;
    private Double prevAskQty = 0.0;
    private Double prevBidPrice = 0.0;
    private Double prevAskPrice = 0.0;

    // VPIN calculation
    private List<Double> volumeBuckets = new ArrayList<>();
    private List<Double> buyVolumeInBuckets = new ArrayList<>();
    private double currentBucketVolume = 0.0;
    private double currentBucketBuyVolume = 0.0;
    private Double vpin = 0.0;  // VPIN value
    private static final double BUCKET_SIZE = 10000.0;  // Volume per bucket
    private static final int VPIN_WINDOW = 50;  // Number of buckets for VPIN

    // Depth imbalance
    private Double depthImbalance = 0.0;
    private Double totalBidQty = 0.0;
    private Double totalAskQty = 0.0;

    // Kyle's Lambda (price impact)
    private List<Double> priceChanges = new ArrayList<>();
    private List<Double> volumes = new ArrayList<>();
    private Double kyleLambda = 0.0;

    // Effective spread
    private Double effectiveSpread = 0.0;
    private Double midPrice = 0.0;

    // Microprice
    private Double microprice = 0.0;

    private boolean complete = false;
    private int updateCount = 0;

    public void addTick(TickData tick) {
        if (tick.getLastRate() <= 0 || tick.getDeltaVolume() == null) {
            return;
        }

        updateCount++;

        // Update bid/ask from tick data
        double bidPrice = tick.getBidRate();
        double askPrice = tick.getOfferRate();
        double bidQty = (double) tick.getBidQuantity();
        double askQty = (double) tick.getOfferQuantity();

        // Calculate mid price
        if (bidPrice > 0 && askPrice > 0) {
            midPrice = (bidPrice + askPrice) / 2.0;

            // Calculate microprice (weighted by depth)
            if (bidQty + askQty > 0) {
                microprice = (bidPrice * askQty + askPrice * bidQty) / (bidQty + askQty);
            }

            // Calculate effective spread
            double tradePrice = tick.getLastRate();
            effectiveSpread = 2.0 * Math.abs(tradePrice - midPrice);
        }

        // Update depth imbalance
        totalBidQty = (double) tick.getTotalBidQuantity();
        totalAskQty = (double) tick.getTotalOfferQuantity();
        if (totalBidQty + totalAskQty > 0) {
            depthImbalance = (totalBidQty - totalAskQty) / (totalBidQty + totalAskQty);
        }

        // Calculate OFI (Order Flow Imbalance)
        // OFI = ΔBid - ΔAsk
        if (prevBidPrice > 0 && prevAskPrice > 0) {
            double deltaBid = 0.0;
            double deltaAsk = 0.0;

            // If bid price increased, add bid quantity
            if (bidPrice >= prevBidPrice) {
                deltaBid = bidQty - (bidPrice == prevBidPrice ? prevBidQty : 0);
            } else {
                deltaBid = -prevBidQty;  // Bid was removed
            }

            // If ask price decreased, add ask quantity
            if (askPrice <= prevAskPrice) {
                deltaAsk = askQty - (askPrice == prevAskPrice ? prevAskQty : 0);
            } else {
                deltaAsk = -prevAskQty;  // Ask was removed
            }

            ofi = deltaBid - deltaAsk;
        }

        // Update previous values for OFI
        prevBidPrice = bidPrice;
        prevAskPrice = askPrice;
        prevBidQty = bidQty;
        prevAskQty = askQty;

        // VPIN calculation (volume bucketing)
        updateVPIN(tick);

        // Kyle's Lambda (price impact estimation)
        updateKyleLambda(tick);

        if (updateCount >= 10) {
            complete = true;
        }
    }

    private void updateVPIN(TickData tick) {
        // Classify trade direction using tick rule
        boolean isBuy = determineIsBuy(tick);
        double volume = tick.getDeltaVolume();

        currentBucketVolume += volume;
        if (isBuy) {
            currentBucketBuyVolume += volume;
        }

        // If bucket is full, close it and start new one
        if (currentBucketVolume >= BUCKET_SIZE) {
            volumeBuckets.add(currentBucketVolume);
            buyVolumeInBuckets.add(currentBucketBuyVolume);

            // Keep only last VPIN_WINDOW buckets
            if (volumeBuckets.size() > VPIN_WINDOW) {
                volumeBuckets.remove(0);
                buyVolumeInBuckets.remove(0);
            }

            // Reset current bucket
            currentBucketVolume = 0.0;
            currentBucketBuyVolume = 0.0;

            // Calculate VPIN
            calculateVPIN();
        }
    }

    private void calculateVPIN() {
        if (volumeBuckets.isEmpty()) {
            return;
        }

        double totalVolume = 0.0;
        double totalAbsImbalance = 0.0;

        for (int i = 0; i < volumeBuckets.size(); i++) {
            double bucketVol = volumeBuckets.get(i);
            double buyVol = buyVolumeInBuckets.get(i);
            double sellVol = bucketVol - buyVol;

            totalVolume += bucketVol;
            totalAbsImbalance += Math.abs(buyVol - sellVol);
        }

        if (totalVolume > 0) {
            this.vpin = totalAbsImbalance / totalVolume;
        }
    }

    private boolean determineIsBuy(TickData tick) {
        // Use tick rule: if trade price >= mid, it's a buy
        if (midPrice > 0) {
            return tick.getLastRate() >= midPrice;
        }
        // Fallback: if price went up, assume buy
        return tick.getLastRate() > (prevBidPrice + prevAskPrice) / 2.0;
    }

    private void updateKyleLambda(TickData tick) {
        // Kyle's Lambda: λ = Cov(ΔP, V) / Var(V)
        // Simplified: track price changes and volumes for regression

        double priceChange = tick.getLastRate() - (prevBidPrice + prevAskPrice) / 2.0;
        double volume = tick.getDeltaVolume();

        priceChanges.add(priceChange);
        volumes.add(volume);

        // Keep only last 100 observations
        if (priceChanges.size() > 100) {
            priceChanges.remove(0);
            volumes.remove(0);
        }

        // Calculate Kyle's Lambda (simple covariance / variance)
        if (priceChanges.size() >= 20) {
            double meanPriceChange = priceChanges.stream().mapToDouble(d -> d).average().orElse(0.0);
            double meanVolume = volumes.stream().mapToDouble(d -> d).average().orElse(0.0);

            double covariance = 0.0;
            double varianceVolume = 0.0;

            for (int i = 0; i < priceChanges.size(); i++) {
                double pDev = priceChanges.get(i) - meanPriceChange;
                double vDev = volumes.get(i) - meanVolume;
                covariance += pDev * vDev;
                varianceVolume += vDev * vDev;
            }

            if (varianceVolume > 0) {
                kyleLambda = covariance / varianceVolume;
            }
        }
    }

    public void markComplete() {
        complete = true;
    }

    public MicrostructureData toMicrostructureData() {
        return MicrostructureData.builder()
            .ofi(ofi)
            .vpin(vpin)
            .depthImbalance(depthImbalance)
            .kyleLambda(kyleLambda)
            .effectiveSpread(effectiveSpread)
            .microprice(microprice)
            .isComplete(complete)
            .build();
    }
}
