package com.kotsin.consumer.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OptionCandle - Specific fields for option instruments.
 * 
 * Used within FamilyCandle to hold option-specific data
 * for each of the 4 ATM options (CE/PE ± 1 strike).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OptionCandle {

    // ==================== IDENTITY ====================
    private String scripCode;
    private String symbol;
    private double strikePrice;
    private String optionType;       // "CE" or "PE"
    private String expiry;           // "2025-12-30"

    // ==================== OHLCV ====================
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;

    // ==================== OI METRICS ====================
    private long openInterest;
    private long oiChange;
    private double oiChangePercent;

    // ==================== GREEKS (if available) ====================
    private Double impliedVolatility;
    private Double delta;
    private Double gamma;
    private Double theta;
    private Double vega;

    // ==================== DERIVED ====================
    private Double intrinsicValue;
    private Double timeValue;
    private boolean isITM;           // In-The-Money
    private boolean isATM;           // At-The-Money

    // ==================== HELPER METHODS ====================

    /**
     * Check if this is a call option
     */
    public boolean isCall() {
        return "CE".equalsIgnoreCase(optionType);
    }

    /**
     * Check if this is a put option
     */
    public boolean isPut() {
        return "PE".equalsIgnoreCase(optionType);
    }

    /**
     * Calculate intrinsic value given spot price
     */
    public double calculateIntrinsicValue(double spotPrice) {
        if (isCall()) {
            return Math.max(0, spotPrice - strikePrice);
        } else {
            return Math.max(0, strikePrice - spotPrice);
        }
    }

    /**
     * Calculate time value (premium - intrinsic)
     */
    public double calculateTimeValue(double spotPrice) {
        double intrinsic = calculateIntrinsicValue(spotPrice);
        return Math.max(0, close - intrinsic);
    }

    /**
     * Check if option is in-the-money given spot price
     */
    public boolean checkITM(double spotPrice) {
        if (isCall()) {
            return spotPrice > strikePrice;
        } else {
            return spotPrice < strikePrice;
        }
    }

    /**
     * Check if option is at-the-money (within 1% of strike)
     */
    public boolean checkATM(double spotPrice) {
        double diff = Math.abs(spotPrice - strikePrice) / strikePrice;
        return diff < 0.01;  // Within 1%
    }

    /**
     * Create from InstrumentCandle
     */
    public static OptionCandle fromInstrumentCandle(InstrumentCandle candle) {
        if (candle == null || !candle.getInstrumentType().isOption()) {
            return null;
        }

        return OptionCandle.builder()
            .scripCode(candle.getScripCode())
            .symbol(candle.getSymbol())
            .strikePrice(candle.getStrikePrice() != null ? candle.getStrikePrice() : 0.0)
            .optionType(candle.getOptionType())
            .expiry(candle.getExpiry())
            .open(candle.getOpen())
            .high(candle.getHigh())
            .low(candle.getLow())
            .close(candle.getClose())
            .volume(candle.getVolume())
            .openInterest(candle.getOpenInterest() != null ? candle.getOpenInterest() : 0L)
            .oiChange(candle.getOiChange() != null ? candle.getOiChange() : 0L)
            .oiChangePercent(candle.getOiChangePercent() != null ? candle.getOiChangePercent() : 0.0)
            .build();
    }
}
