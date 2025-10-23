package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.InstrumentCandle;
import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.monitoring.Timeframe;
import com.kotsin.consumer.service.InstrumentStateManager;
import lombok.Data;

import java.util.Set;

/**
 * Per-Instrument State Aggregator (Facade Pattern)
 * Delegates to InstrumentStateManager for actual aggregation logic
 *
 * Design Pattern: Facade
 * Purpose: Simplify interface to per-instrument aggregation
 */
@Data
public class InstrumentState {

    private final InstrumentStateManager stateManager = new InstrumentStateManager();
    private OrderBookSnapshot latestOrderbook;

    // Delegate all operations to state manager
    public void addTick(TickData tick) {
        stateManager.addTick(tick);
    }

    public void addOrderbook(OrderBookSnapshot orderbook) {
        this.latestOrderbook = orderbook;
        // Delegate to manager
        stateManager.addOrderbook(orderbook);
    }

    public boolean hasAnyCompleteWindow() {
        return stateManager.hasAnyCompleteWindow();
    }

    public Set<String> getCompleteWindows() {
        return stateManager.getCompleteWindows();
    }

    public InstrumentCandle extractFinalizedCandle(Timeframe timeframe) {
        return stateManager.extractFinalizedCandle(timeframe);
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

    public String getInstrumentType() {
        return stateManager.getInstrumentType();
    }

    public void setInstrumentType(String instrumentType) {
        stateManager.setInstrumentType(instrumentType);
    }

    public void setUnderlyingEquityScripCode(String underlyingEquityScripCode) {
        stateManager.setUnderlyingEquityScripCode(underlyingEquityScripCode);
    }

    public Long getLastTickTime() {
        return stateManager.getLastTickTime();
    }

    public Long getMessageCount() {
        return stateManager.getMessageCount();
    }

    /**
     * Force completion of all windows for finalized candle emission
     *
     * @param kafkaWindowEnd The end time of the Kafka tumbling window (1m)
     */
    public void forceCompleteWindows(long kafkaWindowEnd) {
        stateManager.forceCompleteWindows(kafkaWindowEnd);
    }

    // Metadata setters for derivative enrichment
    public void setExpiry(String expiry) { stateManager.setExpiry(expiry); }
    public void setStrikePrice(Double strikePrice) { stateManager.setStrikePrice(strikePrice); }
    public void setOptionType(String optionType) { stateManager.setOptionType(optionType); }
}
