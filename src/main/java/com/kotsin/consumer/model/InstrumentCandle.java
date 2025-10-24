package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.kafka.support.serializer.JsonSerde;

/**
 * Per-Instrument Candle
 * Represents a single candle for ONE specific instrument (equity, future, or option)
 *
 * Key Design: Each scripCode gets its own separate candle
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstrumentCandle {

    // Instrument identification
    private String scripCode;                    // Unique instrument identifier
    private String instrumentType;               // EQUITY, FUTURE, OPTION, INDEX
    private String underlyingEquityScripCode;    // For derivatives: maps to underlying
    private String companyName;
    private String exchange;
    private String exchangeType;

    // Derivative-specific fields
    private String expiry;                       // For futures/options
    private Double strikePrice;                  // For options only
    private String optionType;                   // CE or PE (for options only)

    // OHLCV data
    private Double open;
    private Double high;
    private Double low;
    private Double close;
    private Long volume;

    // Buy/Sell volume breakdown
    private Long buyVolume;
    private Long sellVolume;
    private Double volumeDelta;
    private Double volumeDeltaPercent;

    // Advanced metrics
    private Double vwap;
    private Double hlc3;
    private Integer tickCount;

    // Window information
    private Long windowStartMillis;
    private Long windowEndMillis;
    private Boolean isComplete;

    // Timestamp info
    private String humanReadableStartTime;
    private String humanReadableEndTime;

    // Metadata
    private Long processingTimestamp;
    private String timeframe;                    // 1m, 2m, 3m, 5m, 15m, 30m

    // Optional: Per-instrument OI snapshot
    private Long openInterest;
    private Long oiChange;
    private Double oiChangePercent;

    // Optional: Per-instrument orderbook/microstructure snapshots
    private OrderbookDepthData orderbookDepth;
    private MicrostructureData microstructure;
    private ImbalanceBarData imbalanceBars;

    // Optional: Volume profile snapshot (POC, Value Area, distribution stats)
    private VolumeProfileData volumeProfile;

    /**
     * Get Kafka Serde for serialization/deserialization
     */
    public static JsonSerde<InstrumentCandle> serde() {
        return new JsonSerde<>(InstrumentCandle.class);
    }

    /**
     * Validate if this candle has valid OHLCV data
     */
    public boolean isValid() {
        return open != null && high != null && low != null && close != null
            && volume != null && volume > 0;
    }

    /**
     * Check if this is a derivative instrument
     */
    public boolean isDerivative() {
        return "D".equalsIgnoreCase(exchangeType);
    }

    /**
     * Check if this is an option
     */
    public boolean isOption() {
        return "OPTION".equalsIgnoreCase(instrumentType);
    }

    /**
     * Check if this is a future
     */
    public boolean isFuture() {
        return "FUTURE".equalsIgnoreCase(instrumentType);
    }

    /**
     * Check if this is an equity
     */
    public boolean isEquity() {
        return "EQUITY".equalsIgnoreCase(instrumentType);
    }
}
