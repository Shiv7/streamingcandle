package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.kafka.support.serializer.JsonSerde;

/**
 * Windowed orderbook signals from orderbook aggregation only.
 * Represents the output of orderbook window aggregation before joining with OHLCV/OI.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WindowedOrderbookSignals {

    private String scripCode;

    // Window info
    private Long windowStartMillis;
    private Long windowEndMillis;
    private String timeframe;

    // Orderbook depth data
    private OrderbookDepthData orderbookDepth;

    public static JsonSerde<WindowedOrderbookSignals> serde() {
        return new JsonSerde<>(WindowedOrderbookSignals.class);
    }

    public boolean isValid() {
        return scripCode != null
            && windowStartMillis != null
            && windowEndMillis != null
            && orderbookDepth != null;
    }
}
