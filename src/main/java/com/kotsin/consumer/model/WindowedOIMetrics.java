package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.kafka.support.serializer.JsonSerde;

/**
 * Windowed Open Interest metrics from OI aggregation only.
 * Represents the output of OI window aggregation before joining with OHLCV/orderbook.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WindowedOIMetrics {

    private String scripCode;

    // Window info
    private Long windowStartMillis;
    private Long windowEndMillis;
    private String timeframe;

    // OI metrics
    private Long openInterest;
    private Long oiChange;
    private Double oiChangePercent;

    public static JsonSerde<WindowedOIMetrics> serde() {
        return new JsonSerde<>(WindowedOIMetrics.class);
    }

    public boolean isValid() {
        return scripCode != null
            && windowStartMillis != null
            && windowEndMillis != null;
    }
}
