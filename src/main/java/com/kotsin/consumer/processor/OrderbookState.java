package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.InstrumentCandle;
import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.monitoring.Timeframe;
import com.kotsin.consumer.service.InstrumentStateManager;
import lombok.Data;

/**
 * Simplified State for Orderbook Stream ONLY
 * No ticks, no OI - just orderbook data
 */
@Data
public class OrderbookState {
    private final InstrumentStateManager stateManager = new InstrumentStateManager();

    public void addOrderbook(OrderBookSnapshot orderbook) {
        stateManager.addOrderbook(orderbook);
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

