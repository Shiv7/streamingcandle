package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.*;
import com.kotsin.consumer.monitoring.Timeframe;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * State aggregator for orderbook data only.
 * Produces WindowedOrderbookSignals after window closes.
 */
@Data
@Slf4j
public class OrderbookWindowState {

    private OrderbookDepthAccumulator orderbookAccumulator;

    // Basic instrument info (set from first orderbook)
    private String scripCode;

    // Window info
    private Long windowStartMillis;
    private Long windowEndMillis;

    // Default constructor for Jackson
    public OrderbookWindowState() {
        this.orderbookAccumulator = new OrderbookDepthAccumulator();
    }

    public void addOrderbook(OrderBookSnapshot orderbook) {
        if (scripCode == null && orderbook.getToken() != null) {
            scripCode = String.valueOf(orderbook.getToken());
        }

        // Add to accumulator
        if (orderbook.isValid()) {
            orderbookAccumulator.addOrderbook(orderbook);
        }
    }

    public WindowedOrderbookSignals extractOrderbookSignals(Timeframe timeframe, long windowStart, long windowEnd) {
        this.windowStartMillis = windowStart;
        this.windowEndMillis = windowEnd;

        OrderbookDepthData depthData = orderbookAccumulator.toOrderbookDepthData();

        return WindowedOrderbookSignals.builder()
            .scripCode(scripCode)
            .windowStartMillis(windowStart)
            .windowEndMillis(windowEnd)
            .timeframe(timeframe.getLabel())
            .orderbookDepth(depthData)
            .build();
    }
}
