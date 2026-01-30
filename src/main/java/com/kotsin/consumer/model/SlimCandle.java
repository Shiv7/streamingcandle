package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SlimCandle - Lightweight candle for state store history.
 *
 * PROBLEM: UnifiedCandle has ~100+ fields including large Maps (volumeAtPrice,
 * bidDepthSnapshot, askDepthSnapshot). When 48 UnifiedCandles are stored in
 * CandleHistory and serialized to Kafka changelog topics, it exceeds the
 * max message size (1MB default), causing RecordTooLargeException.
 *
 * SOLUTION: This slim version stores only what VCP/IPU calculations need:
 * - OHLCV data
 * - Volume profile essentials (POC, VAH, VAL)
 * - Buy/sell volume split
 *
 * Typical size: ~200 bytes vs ~20KB for full UnifiedCandle
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SlimCandle {

    // Essential fields for VCP/IPU calculation
    private long windowStartMillis;
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
    private long buyVolume;
    private long sellVolume;
    private double vwap;

    // Volume profile essentials (pre-calculated, not the full map)
    private Double poc;           // Point of Control price
    private Double valueAreaHigh; // VAH
    private Double valueAreaLow;  // VAL

    // For IPU calculation
    private Double ofi;           // Order Flow Imbalance
    private Double depthImbalance;

    /**
     * Convert from UnifiedCandle to SlimCandle
     */
    public static SlimCandle from(UnifiedCandle candle) {
        if (candle == null) return null;

        return SlimCandle.builder()
                .windowStartMillis(candle.getWindowStartMillis())
                .open(candle.getOpen())
                .high(candle.getHigh())
                .low(candle.getLow())
                .close(candle.getClose())
                .volume(candle.getVolume())
                .buyVolume(candle.getBuyVolume())
                .sellVolume(candle.getSellVolume())
                .vwap(candle.getVwap())
                .poc(candle.getPoc())
                .valueAreaHigh(candle.getValueAreaHigh())
                .valueAreaLow(candle.getValueAreaLow())
                .ofi(candle.getOfi())
                .depthImbalance(candle.getDepthImbalance())
                .build();
    }

    /**
     * Convert to UnifiedCandle (for backward compatibility with calculators)
     */
    public UnifiedCandle toUnifiedCandle() {
        return UnifiedCandle.builder()
                .windowStartMillis(windowStartMillis)
                .open(open)
                .high(high)
                .low(low)
                .close(close)
                .volume(volume)
                .buyVolume(buyVolume)
                .sellVolume(sellVolume)
                .vwap(vwap)
                .poc(poc)
                .valueAreaHigh(valueAreaHigh)
                .valueAreaLow(valueAreaLow)
                .ofi(ofi != null ? ofi : 0.0)
                .depthImbalance(depthImbalance != null ? depthImbalance : 0.0)
                .range(high - low)
                .isBullish(close > open)
                .volumeDeltaPercent(volume > 0 ? (double)(buyVolume - sellVolume) / volume * 100.0 : 0.0)
                .build();
    }
}
