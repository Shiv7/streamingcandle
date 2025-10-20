package com.kotsin.consumer.processor.service;

import com.kotsin.consumer.model.*;
import com.kotsin.consumer.processor.CandleAccumulator;
import com.kotsin.consumer.processor.OiAccumulator;
import com.kotsin.consumer.processor.ImbalanceBarAccumulator;
import com.kotsin.consumer.processor.MicrostructureAccumulator;
import com.kotsin.consumer.processor.OrderbookDepthAccumulator;
import com.kotsin.consumer.processor.Timeframe;
import com.kotsin.consumer.processor.WindowRotationService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages multi-timeframe state aggregation
 * Single Responsibility: Coordinate aggregation across all timeframes and features
 */
@Data
@Slf4j
public class TimeframeStateManager {

    // Candle accumulators for each timeframe
    private final EnumMap<Timeframe, CandleAccumulator> candleAccumulators = new EnumMap<>(Timeframe.class);

    // OI accumulators for each timeframe
    private final EnumMap<Timeframe, OiAccumulator> oiAccumulators = new EnumMap<>(Timeframe.class);

    // Feature accumulators (delegated to specialized services)
    private final ImbalanceBarAccumulator imbalanceBarAccumulator = new ImbalanceBarAccumulator();
    private final MicrostructureAccumulator microstructureAccumulator = new MicrostructureAccumulator();
    private final OrderbookDepthAccumulator orderbookDepthAccumulator = new OrderbookDepthAccumulator();

    // Basic info
    private String scripCode;
    private String companyName;
    private String exchange;
    private String exchangeType;
    private Long firstTickTime;
    private Long lastTickTime;
    private Long messageCount = 0L;

    // Timeframe definitions
    private static final Timeframe[] TIMEFRAMES = {
        Timeframe.ONE_MIN, Timeframe.TWO_MIN, Timeframe.THREE_MIN,
        Timeframe.FIVE_MIN, Timeframe.FIFTEEN_MIN, Timeframe.THIRTY_MIN
    };

    public TimeframeStateManager() {
        initializeAccumulators();
    }

    private void initializeAccumulators() {
        // Initialize candle accumulators
        for (Timeframe timeframe : TIMEFRAMES) {
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

        // Update orderbook depth analytics
        if (tick.getFullOrderbook() != null) {
            orderbookDepthAccumulator.addOrderbook(tick.getFullOrderbook());
        }
    }

    private void updateAllTimeframes(TickData tick) {
        long tickTime = tick.getTimestamp();

        for (Map.Entry<Timeframe, CandleAccumulator> entry : candleAccumulators.entrySet()) {
            Timeframe timeframe = entry.getKey();
            CandleAccumulator acc = entry.getValue();
            int minutes = timeframe.getMinutes();
            acc = WindowRotationService.rotateCandleIfNeeded(acc, tickTime, minutes);
            candleAccumulators.put(timeframe, acc);
            acc.addTick(tick);
        }
    }

    private void updateAllOiTimeframes(TickData tick) {
        for (Map.Entry<Timeframe, OiAccumulator> entry : oiAccumulators.entrySet()) {
            Timeframe timeframe = entry.getKey();
            OiAccumulator acc = entry.getValue();
            int minutes = timeframe.getMinutes();
            acc = WindowRotationService.rotateOiIfNeeded(acc, tick.getTimestamp(), minutes);
            oiAccumulators.put(timeframe, acc);
            acc.addOiData(tick);
        }
    }

    public boolean hasAnyCompleteWindow() {
        return candleAccumulators.values().stream()
            .anyMatch(CandleAccumulator::isComplete);
    }

    public Set<String> getCompleteWindows() {
        return candleAccumulators.entrySet().stream()
            .filter(entry -> entry.getValue().isComplete())
            .map(e -> e.getKey().getLabel())
            .collect(Collectors.toSet());
    }

    public Map<String, CandleData> getMultiTimeframeCandles() {
        Map<String, CandleData> candles = new HashMap<>();

        for (Map.Entry<Timeframe, CandleAccumulator> entry : candleAccumulators.entrySet()) {
            Timeframe timeframe = entry.getKey();
            CandleAccumulator accumulator = entry.getValue();

            candles.put(timeframe.getLabel(), accumulator.toCandleData(exchange, exchangeType));
        }

        return candles;
    }

    public Map<String, OpenInterestTimeframeData> getOpenInterest() {
        Map<String, OpenInterestTimeframeData> oiData = new HashMap<>();

        for (Map.Entry<Timeframe, OiAccumulator> entry : oiAccumulators.entrySet()) {
            Timeframe timeframe = entry.getKey();
            OiAccumulator accumulator = entry.getValue();

            oiData.put(timeframe.getLabel(), accumulator.toOiTimeframeData());
        }

        return oiData;
    }

    public ImbalanceBarData getImbalanceBars() {
        return imbalanceBarAccumulator.toImbalanceBarData();
    }

    public MicrostructureData getMicrostructure() {
        return microstructureAccumulator.toMicrostructureData();
    }

    public OrderbookDepthData getOrderbookDepth() {
        return orderbookDepthAccumulator.toOrderbookDepthData();
    }

    public String getDataQuality() {
        if (messageCount < 10) return "LOW";
        if (messageCount < 50) return "MEDIUM";
        return "HIGH";
    }

    public long getProcessingLatency() {
        return System.currentTimeMillis() - lastTickTime;
    }

    public EnumMap<Timeframe, CandleAccumulator> getCandleAccumulators() {
        return candleAccumulators;
    }

    /**
     * Force completion of all windows for finalized candle emission
     * This is needed when suppression is removed and windows don't naturally close
     * Uses tick timestamp for historical data processing
     */
    public void forceCompleteWindows() {
        // Use last tick time for historical data processing, fallback to system time
        long currentTime = (lastTickTime != null) ? lastTickTime : System.currentTimeMillis();
        
        log.debug("🔍 forceCompleteWindows: lastTickTime={}, currentTime={}", lastTickTime, currentTime);
        
        for (Map.Entry<Timeframe, CandleAccumulator> entry : candleAccumulators.entrySet()) {
            Timeframe timeframe = entry.getKey();
            CandleAccumulator accumulator = entry.getValue();
            
            log.debug("🔍 Checking {}: windowStart={}, windowEnd={}, complete={}", 
                timeframe.getLabel(), accumulator.getWindowStart(), accumulator.getWindowEnd(), accumulator.isComplete());
            
            if (accumulator.getWindowStart() != null && 
                accumulator.getWindowEnd() != null && 
                currentTime >= accumulator.getWindowEnd() && 
                !accumulator.isComplete()) {
                
                accumulator.markComplete();
                log.debug("✅ Forced completion of {} window ending at {} (current: {})", 
                    timeframe.getLabel(), accumulator.getWindowEnd(), currentTime);
            }
        }
    }
}
