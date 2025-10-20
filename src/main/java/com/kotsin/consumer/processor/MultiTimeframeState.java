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
                accumulator = new CandleAccumulator(windowStart);
                candleAccumulators.put(timeframe, accumulator);
            } else if (!accumulator.getWindowStart().equals(windowStart)) {
                // New window started, mark previous as complete and rotate
                accumulator.markComplete();
                accumulator = new CandleAccumulator(windowStart);
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
                accumulator = new OiAccumulator(windowStart);
                oiAccumulators.put(timeframe, accumulator);
            } else if (!accumulator.getWindowStart().equals(windowStart)) {
                accumulator.markComplete();
                accumulator = new OiAccumulator(windowStart);
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
    private boolean complete = false;
    private int tickCount = 0;
    
    public CandleAccumulator() {
        this.windowStart = null;
    }
    
    public CandleAccumulator(Long windowStart) {
        this.windowStart = windowStart;
        this.windowEnd = windowStart + (60 * 1000); // Default 1 minute
    }
    
    public void addTick(TickData tick) {
        if (windowStart == null) {
            windowStart = alignToMinute(tick.getTimestamp());
            windowEnd = windowStart + (60 * 1000);
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
    
    public OiAccumulator() {
        this.windowStart = null;
    }
    
    public OiAccumulator(Long windowStart) {
        this.windowStart = windowStart;
        this.windowEnd = windowStart + (60 * 1000);
    }
    
    public void addOiData(TickData tick) {
        if (windowStart == null) {
            windowStart = alignToMinute(tick.getTimestamp());
            windowEnd = windowStart + (60 * 1000);
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
 */
@Data
class ImbalanceBarAccumulator {
    private Long volumeImbalance = 0L;
    private Long dollarImbalance = 0L;
    private Integer tickRuns = 0;
    private Long volumeRuns = 0L;
    private String currentDirection = "NEUTRAL";
    
    public void addTick(TickData tick) {
        // Simplified implementation
        // In real implementation, this would calculate VIB, DIB, TRB, VRB
        if (tick.getDeltaVolume() != null) {
            volumeImbalance += tick.getDeltaVolume();
        }
    }
    
    public ImbalanceBarData toImbalanceBarData() {
        // Create ImbalanceBarData with only the volumeImbalance field set
        // Note: VolumeImbalanceData is package-private in model package, so we can't use it directly here
        // This is a simplified version that returns basic ImbalanceBarData
        return ImbalanceBarData.builder()
            .build();
    }
}

/**
 * Microstructure accumulator
 */
@Data
class MicrostructureAccumulator {
    private Double ofi = 0.0;
    private Double vpin = 0.0;
    private Double depthImbalance = 0.0;
    private Double kyleLambda = 0.0;
    private Double effectiveSpread = 0.0;
    private Double microprice = 0.0;
    private boolean complete = false;
    
    public void addTick(TickData tick) {
        // Simplified implementation
        // In real implementation, this would calculate actual microstructure features
        if (tick.getLastRate() > 0) {
            microprice = tick.getLastRate();
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
