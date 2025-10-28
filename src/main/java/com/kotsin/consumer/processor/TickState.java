package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.InstrumentCandle;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.monitoring.Timeframe;
import com.kotsin.consumer.service.InstrumentStateManager;
import lombok.Data;

/**
 * Simplified State for Ticks Stream ONLY
 * No orderbook, no OI - just tick data
 */
@Data
public class TickState {
    private final InstrumentStateManager stateManager = new InstrumentStateManager();

    public void addTick(TickData tick) {
        stateManager.addTick(tick);
    }

    public InstrumentCandle extractFinalizedCandle(Timeframe timeframe) {
        return stateManager.extractFinalizedCandle(timeframe);
    }

    public String getScripCode() {
        return stateManager.getScripCode();
    }

    public void forceCompleteWindows(long kafkaWindowEnd) {
        stateManager.forceCompleteWindows(kafkaWindowEnd);
    }
}

