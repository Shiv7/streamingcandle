package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * UnifiedCandle - Merged view of Tick + Orderbook + OI data.
 *
 * This is NOT stored in MongoDB. It's computed at query time by
 * merging TickCandle + OrderbookMetrics + OIMetrics.
 *
 * Benefits of query-time merge:
 * - No complex Kafka joins
 * - No join failures or fallback logic
 * - Each data source stored independently
 * - Explicit control over what data is available
 * - Clear data lineage
 *
 * Usage:
 * UnifiedCandle candle = candleService.getCandle("NIFTY", Timeframe.M5, timestamp);
 * // candle.hasTick() - always true (tick is mandatory)
 * // candle.hasOrderbook() - true if orderbook data available
 * // candle.hasOI() - true if OI data available
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UnifiedCandle {

    // ==================== IDENTITY ====================
    private String symbol;
    private String scripCode;
    private String exchange;
    private String exchangeType;
    private String companyName;
    private TickCandle.InstrumentType instrumentType;
    private Timeframe timeframe;

    // ==================== TIMING ====================
    private Instant timestamp;
    private Instant windowStart;
    private Instant windowEnd;

    // ==================== TICK DATA (Always present) ====================
    // OHLCV
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
    private double value;
    private double vwap;

    // Trade Classification
    private long buyVolume;
    private long sellVolume;
    private long volumeDelta;
    private double buyPressure;
    private double sellPressure;

    // VPIN
    private double vpin;

    // Volume Profile
    private double poc;
    private double vah;
    private double val;

    // Imbalance
    private double volumeImbalance;
    private boolean vibTriggered;
    private boolean dibTriggered;

    // Tick Stats
    private int tickCount;
    private int largeTradeCount;

    // ==================== ORDERBOOK DATA (Optional) ====================
    private boolean hasOrderbook;

    // OFI
    private Double ofi;
    private Double ofiMomentum;

    // Kyle's Lambda
    private Double kyleLambda;

    // Microprice
    private Double microprice;

    // Spread
    private Double bidAskSpread;
    private Double spreadPercent;

    // Depth
    private Double depthImbalance;
    private Double avgBidDepth;
    private Double avgAskDepth;

    // Anomalies
    private Integer spoofingCount;
    private Boolean icebergDetected;

    // ==================== OI DATA (Optional, derivatives only) ====================
    private boolean hasOI;

    // OI
    private Long openInterest;
    private Long oiChange;
    private Double oiChangePercent;

    // OI Interpretation
    private OIMetrics.OIInterpretation oiInterpretation;
    private Double oiInterpretationConfidence;
    private Boolean oiSuggestsReversal;

    // OI Velocity
    private Double oiVelocity;

    // ==================== OPTIONS DATA (Optional) ====================
    private Double strikePrice;
    private String optionType;
    private String expiry;
    private Integer daysToExpiry;

    // Greeks (computed on-demand if needed)
    private Double delta;
    private Double gamma;
    private Double theta;
    private Double vega;
    private Double impliedVolatility;

    // ==================== DATA QUALITY ====================
    private String quality;
    private long tickStaleness;
    private Long orderbookStaleness;
    private Long oiStaleness;

    // ==================== AGGREGATION INFO ====================
    /**
     * For aggregated candles (5m, 15m, etc.):
     * Number of 1m candles used in aggregation.
     */
    private int aggregatedCandleCount;

    /**
     * Expected candle count for this timeframe.
     * Used to detect incomplete candles.
     */
    private int expectedCandleCount;

    /**
     * Completeness ratio (0-1).
     * 1.0 = all expected candles present.
     */
    private double completenessRatio;

    // ==================== STATIC FACTORY METHODS ====================

    /**
     * Create UnifiedCandle from TickCandle only.
     */
    public static UnifiedCandle fromTick(TickCandle tick, Timeframe tf) {
        return UnifiedCandle.builder()
            .symbol(tick.getSymbol())
            .scripCode(tick.getScripCode())
            .exchange(tick.getExchange())
            .exchangeType(tick.getExchangeType())
            .companyName(tick.getCompanyName())
            .instrumentType(tick.getInstrumentType())
            .timeframe(tf)
            .timestamp(tick.getTimestamp())
            .windowStart(tick.getWindowStart())
            .windowEnd(tick.getWindowEnd())
            // OHLCV
            .open(tick.getOpen())
            .high(tick.getHigh())
            .low(tick.getLow())
            .close(tick.getClose())
            .volume(tick.getVolume())
            .value(tick.getValue())
            .vwap(tick.getVwap())
            // Trade Classification
            .buyVolume(tick.getBuyVolume())
            .sellVolume(tick.getSellVolume())
            .volumeDelta(tick.getVolumeDelta())
            .buyPressure(tick.getBuyPressure())
            .sellPressure(tick.getSellPressure())
            // VPIN
            .vpin(tick.getVpin())
            // Volume Profile
            .poc(tick.getPoc())
            .vah(tick.getVah())
            .val(tick.getVal())
            // Imbalance
            .volumeImbalance(tick.getVolumeImbalance())
            .vibTriggered(tick.isVibTriggered())
            .dibTriggered(tick.isDibTriggered())
            // Tick Stats
            .tickCount(tick.getTickCount())
            .largeTradeCount(tick.getLargeTradeCount())
            // Options
            .strikePrice(tick.getStrikePrice())
            .optionType(tick.getOptionType())
            .expiry(tick.getExpiry())
            .daysToExpiry(tick.getDaysToExpiry())
            // Quality
            .quality(tick.getQuality())
            .tickStaleness(tick.getProcessingLatencyMs())
            // Flags
            .hasOrderbook(false)
            .hasOI(false)
            // Aggregation
            .aggregatedCandleCount(1)
            .expectedCandleCount(1)
            .completenessRatio(1.0)
            .build();
    }

    /**
     * Merge OrderbookMetrics into UnifiedCandle.
     */
    public UnifiedCandle withOrderbook(OrderbookMetrics ob) {
        if (ob == null) return this;

        this.hasOrderbook = true;
        this.ofi = ob.getOfi();
        this.ofiMomentum = ob.getOfiMomentum();
        this.kyleLambda = ob.getKyleLambda();
        this.microprice = ob.getMicroprice();
        this.bidAskSpread = ob.getBidAskSpread();
        this.spreadPercent = ob.getSpreadPercent();
        this.depthImbalance = ob.getDepthImbalance();
        this.avgBidDepth = ob.getAvgBidDepth();
        this.avgAskDepth = ob.getAvgAskDepth();
        this.spoofingCount = ob.getSpoofingCount();
        this.icebergDetected = ob.isIcebergBidDetected() || ob.isIcebergAskDetected();
        this.orderbookStaleness = ob.getStaleness();

        return this;
    }

    /**
     * Merge OIMetrics into UnifiedCandle.
     */
    public UnifiedCandle withOI(OIMetrics oi) {
        if (oi == null) return this;

        this.hasOI = true;
        this.openInterest = oi.getOpenInterest();
        this.oiChange = oi.getOiChange();
        this.oiChangePercent = oi.getOiChangePercent();
        this.oiInterpretation = oi.getInterpretation();
        this.oiInterpretationConfidence = oi.getInterpretationConfidence();
        this.oiSuggestsReversal = oi.isSuggestsReversal();
        this.oiVelocity = oi.getOiVelocity();
        this.oiStaleness = oi.getStaleness();

        return this;
    }

    // ==================== HELPER METHODS ====================

    /**
     * Check if this is a derivative instrument.
     */
    public boolean isDerivative() {
        return instrumentType == TickCandle.InstrumentType.FUTURE ||
               instrumentType == TickCandle.InstrumentType.OPTION_CE ||
               instrumentType == TickCandle.InstrumentType.OPTION_PE;
    }

    /**
     * Check if this is an option.
     */
    public boolean isOption() {
        return instrumentType == TickCandle.InstrumentType.OPTION_CE ||
               instrumentType == TickCandle.InstrumentType.OPTION_PE;
    }

    /**
     * Get the range (high - low).
     */
    public double getRange() {
        return high - low;
    }

    /**
     * Check if bullish candle.
     */
    public boolean isBullish() {
        return close > open;
    }

    /**
     * Check if all data sources are fresh.
     */
    public boolean isAllDataFresh() {
        boolean tickFresh = tickStaleness < 10000;
        boolean obFresh = !hasOrderbook || (orderbookStaleness != null && orderbookStaleness < 10000);
        boolean oiFresh = !hasOI || (oiStaleness != null && oiStaleness < 30000);
        return tickFresh && obFresh && oiFresh;
    }

    /**
     * Check if this candle has complete data.
     */
    public boolean isComplete() {
        return completenessRatio >= 0.95;
    }

    /**
     * Get average depth (bid + ask) / 2.
     */
    public Double getAvgDepth() {
        if (avgBidDepth == null || avgAskDepth == null) return null;
        return (avgBidDepth + avgAskDepth) / 2;
    }

    /**
     * Get normalized OFI relative to depth.
     */
    public Double getNormalizedOfi() {
        if (ofi == null) return null;
        Double avgDepth = getAvgDepth();
        if (avgDepth == null || avgDepth == 0) return null;
        return ofi / avgDepth;
    }
}
