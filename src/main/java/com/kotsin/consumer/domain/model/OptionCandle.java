package com.kotsin.consumer.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * OptionCandle - Comprehensive option instrument data with full microstructure.
 *
 * Used within FamilyCandle to hold option-specific data
 * for each of the 4 ATM options (CE/PE ± 1 strike).
 *
 * QUANT GRADE: Contains 70 fields (22 original + 48 microstructure)
 * capturing institutional-level market data for options trading.
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

    // ========================================================================
    // NEW: MICROSTRUCTURE FIELDS (48 fields for institutional-grade analytics)
    // ========================================================================

    // ==================== ORDER FLOW & MICROSTRUCTURE (13 fields) ====================
    /** Order Flow Imbalance - institutional footprint indicator */
    private Double ofi;
    /** dOFI/dt - acceleration of order flow */
    private Double ofiMomentum;
    /** Kyle Lambda - price impact coefficient (liquidity metric) */
    private Double kyleLambda;
    /** Fair value estimate - weighted mid-price: (bid*askVol + ask*bidVol)/(bidVol+askVol) */
    private Double microprice;
    /** VPIN - Probability of informed trading (0-1) */
    private Double vpin;
    /** Adaptive bucket size for VPIN calculation */
    private Double vpinBucketSize;
    /** Aggressive buy volume - lifted offers (institutional buying) */
    private Long aggressiveBuyVolume;
    /** Aggressive sell volume - hit bids (institutional selling) */
    private Long aggressiveSellVolume;
    /** Buy pressure = aggressive buy / total volume */
    private Double buyPressure;
    /** Sell pressure = aggressive sell / total volume */
    private Double sellPressure;
    /** Midpoint volume - potential iceberg activity */
    private Long midpointVolume;
    /** Tick-level bid-ask imbalance [-1, +1] */
    private Double tickBidAskImbalance;
    /** Data quality score for trade classification (0-1) */
    private Double classificationReliability;

    // ==================== ORDERBOOK DEPTH (12 fields) ====================
    /** Depth imbalance = (bid_qty - ask_qty) / (bid_qty + ask_qty) */
    private Double depthImbalance;
    /** Level-weighted depth imbalance (prioritizes near levels) */
    private Double weightedDepthImbalance;
    /** Best bid-ask spread in price terms */
    private Double bidAskSpread;
    /** Spread volatility - stability of spread */
    private Double spreadVolatility;
    /** Total bid-side quantity available */
    private Double averageBidDepth;
    /** Total ask-side quantity available */
    private Double averageAskDepth;
    /** Bid depth slope - liquidity curve on bid side */
    private Double bidDepthSlope;
    /** Ask depth slope - liquidity curve on ask side */
    private Double askDepthSlope;
    /** % of depth concentrated in top 3 levels */
    private Double depthConcentration;
    /** Spoofing events detected */
    private Integer spoofingCount;
    /** Hidden iceberg orders detected */
    private Boolean icebergDetected;
    /** Order cancellation rate - HFT/stress indicator */
    private Double cancelRate;

    // ==================== VOLUME PROFILE (4 fields) ====================
    /** Volume at each price level (Map<price, volume>) */
    private Map<Double, Long> volumeAtPrice;
    /** Point of Control - price with maximum volume */
    private Double poc;
    /** Value Area High - upper bound of 70% volume */
    private Double vah;
    /** Value Area Low - lower bound of 70% volume */
    private Double val;

    // ==================== IMBALANCE BARS (4 fields) ====================
    /** Cumulative signed volume imbalance */
    private double volumeImbalance;
    /** Cumulative signed dollar imbalance */
    private double dollarImbalance;
    /** Any imbalance bar triggered (VIB, DIB, TRB, VRB) */
    private Boolean imbalanceBarTriggered;
    /** Consecutive same-direction ticks */
    private Integer tickRuns;

    // ==================== EXECUTION COST (4 fields) ====================
    /** Real slippage = 2 * |trade_price - midpoint| */
    private Double averageEffectiveSpread;
    /** Fill quality - % trades with price better than quoted */
    private Double priceImprovementRatio;
    /** Price impact per unit volume (per million) */
    private Double priceImpactPerUnit;
    /** % of time spread <= 1 tick (liquidity indicator) */
    private Double tightSpreadPercent;

    // ==================== TICK INTENSITY (5 fields) ====================
    /** Ticks per second - activity level */
    private Integer ticksPerSecond;
    /** Tick acceleration - activity momentum */
    private Double tickAcceleration;
    /** Peak/average tick ratio - burst detection */
    private Double tickBurstRatio;
    /** HFT activity detected flag */
    private Boolean algoActivityDetected;
    /** Number of large trades (>10x average) - institutional blocks */
    private Integer largeTradeCount;

    // ==================== OPTIONS-SPECIFIC FLOW (6 fields) ====================
    /** Institutional options flow score (combined metrics) */
    private Double optionFlowScore;
    /** Delta-adjusted flow = flow * delta (directional exposure) */
    private Double deltaAdjustedFlow;
    /** Strike gamma exposure = gamma * OI (convexity risk) */
    private Double gammaExposure;
    /** Unusual volume flag (>10x normal) */
    private Boolean unusualVolume;
    /** IV percentile rank (0-100) */
    private Double ivRank;
    /** IV change from prior period */
    private Double ivChange;

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
     * QUANT FIX: Now copies ALL microstructure fields (48 new fields)
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

        // Build option candle with ALL microstructure data
        OptionCandle.OptionCandleBuilder builder = OptionCandle.builder()
            // ========== IDENTITY ==========
            .scripCode(candle.getScripCode())
            .symbol(candle.getCompanyName() != null ? candle.getCompanyName() : candle.getScripCode())
            .strikePrice(strike)
            .optionType(optType)
            .expiry(candle.getExpiry())

            // ========== OHLCV ==========
            .open(candle.getOpen())
            .high(candle.getHigh())
            .low(candle.getLow())
            .close(premium)
            .volume(candle.getVolume())

            // ========== OI METRICS ==========
            .openInterest(candle.getOpenInterest() != null ? candle.getOpenInterest() : 0L)
            .oiChange(candle.getOiChange() != null ? candle.getOiChange() : 0L)
            .oiChangePercent(candle.getOiChangePercent() != null ? candle.getOiChangePercent() : 0.0)

            // ========== GREEKS ==========
            .impliedVolatility(candle.getImpliedVolatility())
            .delta(candle.getDelta())
            .gamma(candle.getGamma())
            .theta(candle.getTheta())
            .vega(candle.getVega())

            // ========== ORDER FLOW & MICROSTRUCTURE (13 fields) ==========
            .ofi(candle.getOfi())
            .ofiMomentum(candle.getOfiMomentum())
            .kyleLambda(candle.getKyleLambda())
            .microprice(candle.getMicroprice())
            .vpin(candle.getVpin())
            .vpinBucketSize(candle.getVpinBucketSize())
            .aggressiveBuyVolume(candle.getAggressiveBuyVolume())
            .aggressiveSellVolume(candle.getAggressiveSellVolume())
            .buyPressure(candle.getBuyPressure())
            .sellPressure(candle.getSellPressure())
            .midpointVolume(candle.getMidpointVolume())
            .tickBidAskImbalance(candle.getTickBidAskImbalance())
            .classificationReliability(candle.getClassificationReliability())

            // ========== ORDERBOOK DEPTH (12 fields) ==========
            .depthImbalance(candle.getDepthImbalance())
            .weightedDepthImbalance(candle.getWeightedDepthImbalance())
            .bidAskSpread(candle.getBidAskSpread())
            .spreadVolatility(candle.getSpreadVolatility())
            .averageBidDepth(candle.getAverageBidDepth())
            .averageAskDepth(candle.getAverageAskDepth())
            .bidDepthSlope(candle.getBidDepthSlope())
            .askDepthSlope(candle.getAskDepthSlope())
            .depthConcentration(candle.getDepthConcentration())
            .spoofingCount(candle.getSpoofingCount())
            .icebergDetected(candle.getIcebergBidDetected() != null && candle.getIcebergBidDetected()
                          || candle.getIcebergAskDetected() != null && candle.getIcebergAskDetected())
            .cancelRate(candle.getCancelRate())

            // ========== VOLUME PROFILE (4 fields) ==========
            .volumeAtPrice(candle.getVolumeAtPrice())
            .poc(candle.getPoc())
            .vah(candle.getVah())
            .val(candle.getVal())

            // ========== IMBALANCE BARS (4 fields) ==========
            .volumeImbalance(candle.getVolumeImbalance())
            .dollarImbalance(candle.getDollarImbalance())
            .imbalanceBarTriggered(
                candle.isVibTriggered() ||
                candle.isDibTriggered() ||
                candle.isTrbTriggered() ||
                candle.isVrbTriggered()
            )
            .tickRuns(candle.getTickRuns())

            // ========== EXECUTION COST (4 fields) ==========
            .averageEffectiveSpread(candle.getAverageEffectiveSpread())
            .priceImprovementRatio(candle.getPriceImprovementRatio())
            .priceImpactPerUnit(candle.getPriceImpactPerUnit())
            .tightSpreadPercent(candle.getTightSpreadPercent())

            // ========== TICK INTENSITY (5 fields) ==========
            .ticksPerSecond(candle.getTicksPerSecond())
            .tickAcceleration(candle.getTickAcceleration())
            .tickBurstRatio(candle.getTickBurstRatio())
            .algoActivityDetected(candle.getAlgoActivityDetected())
            .largeTradeCount(candle.getLargeTradeCount());

        // ========== OPTIONS-SPECIFIC FLOW (computed fields) ==========
        // Calculate option flow score based on available metrics
        Double optFlowScore = calculateOptionFlowScore(candle);
        builder.optionFlowScore(optFlowScore);

        // Calculate delta-adjusted flow if delta is available
        if (candle.getDelta() != null && candle.getOfi() != null) {
            builder.deltaAdjustedFlow(candle.getOfi() * candle.getDelta());
        }

        // Calculate gamma exposure if gamma and OI are available
        if (candle.getGamma() != null && candle.getOpenInterest() != null) {
            builder.gammaExposure(candle.getGamma() * candle.getOpenInterest());
        }

        // Detect unusual volume (>10x typical, approximated by volume spike)
        // Note: proper implementation needs historical average
        builder.unusualVolume(candle.getVolume() > 0 && candle.getBuyPressure() != null
                            && (candle.getBuyPressure() > 0.8 || candle.getSellPressure() != null && candle.getSellPressure() > 0.8));

        // IV metrics - these may need to come from external IV percentile calculation
        // For now, copy IV change if price movement indicates it
        if (candle.getImpliedVolatility() != null) {
            builder.ivRank(null);  // Will be computed by IVSurfaceCalculator
            builder.ivChange(null); // Will be computed by IVSurfaceCalculator
        }

        // ========== DERIVED: Moneyness calculation ==========
        if (spotPrice != null && spotPrice > 0 && strike > 0 && optType != null) {
            boolean isCall = "CE".equalsIgnoreCase(optType);
            boolean isPut = "PE".equalsIgnoreCase(optType);

            // ITM: Call is ITM when spot > strike, Put is ITM when spot < strike
            boolean itm = isCall ? spotPrice > strike : isPut && spotPrice < strike;
            builder.isITM(itm);

            // ATM: Within 1% of strike (or within 50 points for index options)
            double diff = Math.abs(spotPrice - strike);
            double atmThreshold = Math.max(strike * 0.01, 50.0);
            builder.isATM(diff <= atmThreshold);

            // Intrinsic value
            double intrinsic = isCall ? Math.max(0, spotPrice - strike)
                                     : isPut ? Math.max(0, strike - spotPrice) : 0.0;
            builder.intrinsicValue(intrinsic);

            // Time value = Premium - Intrinsic
            builder.timeValue(Math.max(0, premium - intrinsic));
        }

        return builder.build();
    }

    /**
     * Calculate option flow score based on microstructure metrics
     * Combines OFI, VPIN, aggressive volume, and depth imbalance
     */
    private static Double calculateOptionFlowScore(InstrumentCandle candle) {
        if (candle == null) return null;

        double score = 0.0;
        int components = 0;

        // OFI component (normalized) - OFI is nullable Double, check for null first
        Double ofiValue = candle.getOfi();
        if (ofiValue != null && ofiValue != 0.0) {
            double ofi = ofiValue;
            // OFI is unbounded, normalize to [-1, 1] range using tanh
            double ofiNormalized = Math.tanh(ofi / 10000.0);
            score += Math.abs(ofiNormalized);
            components++;
        }

        // VPIN component (already 0-1) - vpin is primitive double
        double vpin = candle.getVpin();
        if (vpin > 0) {
            score += vpin;
            components++;
        }

        // Aggressive volume imbalance
        if (candle.getBuyPressure() != null && candle.getSellPressure() != null) {
            double imbalance = Math.abs(candle.getBuyPressure() - candle.getSellPressure());
            score += imbalance;
            components++;
        }

        // Depth imbalance component
        if (candle.getDepthImbalance() != null) {
            score += Math.abs(candle.getDepthImbalance());
            components++;
        }

        return components > 0 ? score / components : null;
    }
}
