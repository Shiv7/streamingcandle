package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.kafka.support.serializer.JsonSerde;

/**
 * Windowed OHLCV candle data from tick aggregation only.
 * Represents the output of tick window aggregation before joining with orderbook/OI.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WindowedOHLCV {

    private String scripCode;
    private String companyName;
    private String exchange;
    private String exchangeType;

    // Window info
    private Long windowStartMillis;
    private Long windowEndMillis;
    private String timeframe;

    // OHLCV data
    private Double open;
    private Double high;
    private Double low;
    private Double close;
    private Long volume;
    private Long buyVolume;
    private Long sellVolume;
    private Double vwap;
    private Integer tickCount;

    // Imbalance bars (computed from ticks)
    private ImbalanceBarData imbalanceBars;

    // Microstructure (computed from ticks)
    private MicrostructureData microstructure;

    public static JsonSerde<WindowedOHLCV> serde() {
        return new JsonSerde<>(WindowedOHLCV.class);
    }

    public boolean isValid() {
        return scripCode != null
            && windowStartMillis != null
            && windowEndMillis != null
            && open != null
            && high != null
            && low != null
            && close != null
            && volume != null
            && volume > 0;
    }
}
