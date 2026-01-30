package com.kotsin.consumer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for exposing technical indicators (Bollinger Bands, VWAP, SuperTrend) to frontend.
 * Contains all indicator data needed for dashboard display and chart overlays.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TechnicalIndicatorDTO {

    // ==================== IDENTITY ====================
    private String scripCode;
    private String symbol;
    private String companyName;
    private String timeframe;
    private long timestamp;
    private double currentPrice;

    // ==================== BOLLINGER BANDS ====================
    private Double bbUpper;           // Upper band
    private Double bbMiddle;          // SMA20 (middle band)
    private Double bbLower;           // Lower band
    private Double bbWidth;           // Upper - Lower (absolute)
    private Double bbWidthPct;        // Width as % of middle (for squeeze detection)
    private Double bbPercentB;        // (close - lower) / (upper - lower), 0-1 scale
    private Boolean bbSqueeze;        // Is width < 2% (low volatility, breakout expected)

    // ==================== VWAP ====================
    private Double vwap;              // Volume Weighted Average Price
    private Double vwapUpperBand;     // VWAP + 1 StdDev
    private Double vwapLowerBand;     // VWAP - 1 StdDev
    private Double vwapStdDev;        // Standard deviation from VWAP
    private String vwapSignal;        // ABOVE_UPPER, ABOVE_VWAP, BELOW_VWAP, BELOW_LOWER
    private Double vwapDeviation;     // % deviation from VWAP

    // ==================== SUPERTREND ====================
    private Double superTrendValue;        // Current SuperTrend level
    private String superTrendDirection;    // BULLISH or BEARISH
    private Boolean superTrendReversal;    // Just flipped direction
    private Integer candlesSinceFlip;      // Candles since last direction change
    private Double atr;                    // ATR(14) value
    private Double atrPercent;             // ATR as % of price

    // ==================== DATA QUALITY ====================
    private Boolean dataQualitySufficient; // true if enough history for valid indicators
    private Integer actualCandleCount;     // Number of candles used for calculation
    private Integer requiredCandleCount;   // Minimum candles needed (typically 20)

    // ==================== HELPER METHODS ====================

    /**
     * Calculate VWAP signal based on price position
     */
    public static String calculateVwapSignal(double price, Double vwap, Double upperBand, Double lowerBand) {
        if (vwap == null || upperBand == null || lowerBand == null) {
            return "NEUTRAL";
        }
        if (price > upperBand) return "ABOVE_UPPER";
        if (price > vwap) return "ABOVE_VWAP";
        if (price > lowerBand) return "BELOW_VWAP";
        return "BELOW_LOWER";
    }

    /**
     * Create from TechnicalContext and InstrumentCandle data
     */
    public static TechnicalIndicatorDTO fromTechnicalContext(
            com.kotsin.consumer.enrichment.model.TechnicalContext ctx,
            com.kotsin.consumer.domain.model.InstrumentCandle candle) {

        if (ctx == null || candle == null) {
            return null;
        }

        double price = candle.getClose();

        // VWAP signal calculation
        String vwapSignal = calculateVwapSignal(
                price,
                candle.getVwap(),
                candle.getVwapUpperBand(),
                candle.getVwapLowerBand()
        );

        // VWAP deviation calculation
        Double vwapDeviation = null;
        if (candle.getVwap() > 0) {
            vwapDeviation = ((price - candle.getVwap()) / candle.getVwap()) * 100;
        }

        return TechnicalIndicatorDTO.builder()
                // Identity
                .scripCode(candle.getScripCode())
                .symbol(candle.getSymbol())
                .companyName(candle.getCompanyName())
                .timeframe(candle.getTimeframe())
                .timestamp(candle.getWindowEndMillis())
                .currentPrice(price)
                // Bollinger Bands
                .bbUpper(ctx.getBbUpper())
                .bbMiddle(ctx.getBbMiddle())
                .bbLower(ctx.getBbLower())
                .bbWidth(ctx.getBbWidth())
                .bbWidthPct(ctx.getBbWidthPct())
                .bbPercentB(ctx.getBbPercentB())
                .bbSqueeze(ctx.isBbSqueezing())
                // VWAP
                .vwap(candle.getVwap())
                .vwapUpperBand(candle.getVwapUpperBand())
                .vwapLowerBand(candle.getVwapLowerBand())
                .vwapStdDev(candle.getVwapStdDev())
                .vwapSignal(vwapSignal)
                .vwapDeviation(vwapDeviation)
                // SuperTrend
                .superTrendValue(ctx.getSuperTrendValue())
                .superTrendDirection(ctx.isSuperTrendBullish() ? "BULLISH" : "BEARISH")
                .superTrendReversal(ctx.isSuperTrendFlip())
                .candlesSinceFlip(ctx.getCandlesSinceStFlip())
                .atr(ctx.getAtr())
                .atrPercent(ctx.getAtrPct())
                // Data Quality
                .dataQualitySufficient(ctx.isDataQualitySufficient())
                .actualCandleCount(ctx.getActualCandleCount())
                .requiredCandleCount(ctx.getRequiredCandleCount())
                .build();
    }
}
