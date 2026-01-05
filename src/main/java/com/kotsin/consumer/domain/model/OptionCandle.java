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
     * Create from InstrumentCandle (backward compatible - no spot price)
     */
    public static OptionCandle fromInstrumentCandle(InstrumentCandle candle) {
        return fromInstrumentCandle(candle, null);
    }

    /**
     * Create from InstrumentCandle with spot price for moneyness calculation
     * FIX: Now calculates isITM, isATM, intrinsicValue, timeValue
     *
     * @param candle InstrumentCandle for the option
     * @param spotPrice Current spot price (equity close) for moneyness calculation
     */
    public static OptionCandle fromInstrumentCandle(InstrumentCandle candle, Double spotPrice) {
        // FIX: Check for null InstrumentType before calling isOption() (BUG-004)
        if (candle == null || candle.getInstrumentType() == null || !candle.getInstrumentType().isOption()) {
            return null;
        }

        double strike = candle.getStrikePrice() != null ? candle.getStrikePrice() : 0.0;
        String optType = candle.getOptionType();
        double premium = candle.getClose();

        // Build base option candle
        OptionCandle.OptionCandleBuilder builder = OptionCandle.builder()
            .scripCode(candle.getScripCode())
            .symbol(candle.getCompanyName() != null ? candle.getCompanyName() : candle.getScripCode())  // FIX: Use companyName as symbol
            .strikePrice(strike)
            .optionType(optType)
            .expiry(candle.getExpiry())
            .open(candle.getOpen())
            .high(candle.getHigh())
            .low(candle.getLow())
            .close(premium)
            .volume(candle.getVolume())
            .openInterest(candle.getOpenInterest() != null ? candle.getOpenInterest() : 0L)
            .oiChange(candle.getOiChange() != null ? candle.getOiChange() : 0L)
            .oiChangePercent(candle.getOiChangePercent() != null ? candle.getOiChangePercent() : 0.0)
            // Copy Greeks if available
            .impliedVolatility(candle.getImpliedVolatility())
            .delta(candle.getDelta())
            .gamma(candle.getGamma())
            .theta(candle.getTheta())
            .vega(candle.getVega());

        // FIX: Calculate moneyness if spot price and strike are available
        if (spotPrice != null && spotPrice > 0 && strike > 0 && optType != null) {
            boolean isCall = "CE".equalsIgnoreCase(optType);
            boolean isPut = "PE".equalsIgnoreCase(optType);

            // ITM: Call is ITM when spot > strike, Put is ITM when spot < strike
            boolean itm = false;
            if (isCall) {
                itm = spotPrice > strike;
            } else if (isPut) {
                itm = spotPrice < strike;
            }
            builder.isITM(itm);

            // ATM: Within 1% of strike (or within half the strike gap for index options)
            double diff = Math.abs(spotPrice - strike);
            double atmThreshold = Math.max(strike * 0.01, 50.0);  // 1% or 50 points, whichever is larger
            boolean atm = diff <= atmThreshold;
            builder.isATM(atm);

            // Intrinsic value
            double intrinsic = 0.0;
            if (isCall) {
                intrinsic = Math.max(0, spotPrice - strike);
            } else if (isPut) {
                intrinsic = Math.max(0, strike - spotPrice);
            }
            builder.intrinsicValue(intrinsic);

            // Time value = Premium - Intrinsic
            double timeValue = Math.max(0, premium - intrinsic);
            builder.timeValue(timeValue);
        }

        return builder.build();
    }
}
