package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.*;
import com.kotsin.consumer.processor.service.TimeframeStateManager;
import lombok.Data;

import java.util.*;

/**
 * Multi-timeframe state aggregator (Facade Pattern)
 * Delegates to TimeframeStateManager for actual aggregation logic
 *
 * Design Pattern: Facade
 * Purpose: Simplify interface to complex subsystem
 */
@Data
public class MultiTimeframeState {

    private final TimeframeStateManager stateManager = new TimeframeStateManager();

    // Delegate all operations to state manager
    public void addTick(TickData tick) {
        stateManager.addTick(tick);
    }

    public boolean hasAnyCompleteWindow() {
        return stateManager.hasAnyCompleteWindow();
    }

    public Set<String> getCompleteWindows() {
        return stateManager.getCompleteWindows();
    }

    public Map<String, CandleData> getMultiTimeframeCandles() {
        return stateManager.getMultiTimeframeCandles();
    }

    public Map<String, OpenInterestTimeframeData> getOpenInterest() {
        return stateManager.getOpenInterest();
    }

    public ImbalanceBarData getImbalanceBars() {
        return stateManager.getImbalanceBars();
    }

    public MicrostructureData getMicrostructure() {
        return stateManager.getMicrostructure();
    }

    public OrderbookDepthData getOrderbookDepth() {
        return stateManager.getOrderbookDepth();
    }

    public String getDataQuality() {
        return stateManager.getDataQuality();
    }

    public long getProcessingLatency() {
        return stateManager.getProcessingLatency();
    }

    // Expose getters for backward compatibility
    public String getScripCode() {
        return stateManager.getScripCode();
    }

    public String getCompanyName() {
        return stateManager.getCompanyName();
    }

    public String getExchange() {
        return stateManager.getExchange();
    }

    public String getExchangeType() {
        return stateManager.getExchangeType();
    }

    public Long getLastTickTime() {
        return stateManager.getLastTickTime();
    }

    public Long getMessageCount() {
        return stateManager.getMessageCount();
    }

    public EnumMap<Timeframe, CandleAccumulator> getCandleAccumulators() {
        return stateManager.getCandleAccumulators();
    }

    /**
     * Force completion of all windows for finalized candle emission
     * This is needed when suppression is removed
     */
    public void forceCompleteWindows() {
        stateManager.forceCompleteWindows();
    }
}
