package com.kotsin.consumer.processor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kotsin.consumer.model.*;
import com.kotsin.consumer.monitoring.Timeframe;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * State aggregator for tick data only.
 * Produces WindowedOHLCV after window closes.
 */
@Data
@Slf4j
public class TickWindowState {

    private CandleAccumulator candleAccumulator;
    private MicrostructureAccumulator microAccumulator;
    private ImbalanceBarAccumulator imbalanceAccumulator;

    // Basic instrument info (set from first tick)
    private String scripCode;
    private String companyName;
    private String exchange;
    private String exchangeType;

    // Window info
    private Long windowStartMillis;
    private Long windowEndMillis;

    // Default constructor for Jackson
    public TickWindowState() {
        this.candleAccumulator = new CandleAccumulator();
        this.microAccumulator = new MicrostructureAccumulator();
        this.imbalanceAccumulator = new ImbalanceBarAccumulator();
    }

    public void addTick(TickData tick) {
        if (scripCode == null) {
            scripCode = tick.getScripCode();
            companyName = tick.getCompanyName();
            exchange = tick.getExchange();
            exchangeType = tick.getExchangeType();
        }

        // Add to accumulators
        candleAccumulator.addTick(tick);
        microAccumulator.addTick(tick);  // Use simple tick-only version
        imbalanceAccumulator.addTick(tick);
    }

    public WindowedOHLCV extractOHLCV(Timeframe timeframe, long windowStart, long windowEnd) {
        this.windowStartMillis = windowStart;
        this.windowEndMillis = windowEnd;

        CandleData candleData = candleAccumulator.toCandleData(exchange, exchangeType);
        MicrostructureData microData = microAccumulator.toMicrostructureData(windowStart, windowEnd);
        ImbalanceBarData imbalanceData = imbalanceAccumulator.toImbalanceBarData();

        return WindowedOHLCV.builder()
            .scripCode(scripCode)
            .companyName(companyName)
            .exchange(exchange)
            .exchangeType(exchangeType)
            .windowStartMillis(windowStart)
            .windowEndMillis(windowEnd)
            .timeframe(timeframe.getLabel())
            .open(candleData != null ? candleData.getOpen() : null)
            .high(candleData != null ? candleData.getHigh() : null)
            .low(candleData != null ? candleData.getLow() : null)
            .close(candleData != null ? candleData.getClose() : null)
            .volume(candleData != null ? candleData.getVolume() : null)
            .buyVolume(candleData != null ? candleData.getBuyVolume() : null)
            .sellVolume(candleData != null ? candleData.getSellVolume() : null)
            .vwap(candleData != null ? candleData.getVwap() : null)
            .tickCount(candleData != null ? candleData.getTickCount() : null)
            .imbalanceBars(imbalanceData)
            .microstructure(microData)
            .build();
    }
}
