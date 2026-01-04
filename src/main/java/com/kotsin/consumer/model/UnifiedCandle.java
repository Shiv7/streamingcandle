package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * UnifiedCandle - Combined OHLCV + Orderbook Microstructure + OI in ONE model
 * 
 * This model aggregates data from three sources:
 * - EnrichedCandlestick: OHLCV, buy/sell volume, VWAP, volumeAtPrice, imbalance bars, VPIN
 * - OrderbookAggregate: OFI, depth imbalance, Kyle's Lambda, microprice, spread
 * - OIAggregate: OI OHLC, put/call OI, OI change metrics
 * 
 * Emitted per scripCode per timeframe (1m, 2m, 3m, 5m, 15m, 30m)
 * Topic: unified-candle-{timeframe}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UnifiedCandle {

    // ========== Metadata ==========
    private String scripCode;
    private String companyName;
    private String exchange;
    private String exchangeType;
    private String timeframe;  // "1m", "2m", "3m", "5m", "15m", "30m"
    private long windowStartMillis;
    private long windowEndMillis;
    private String humanReadableStartTime;
    private String humanReadableEndTime;

    // ========== OHLCV (from EnrichedCandlestick) ==========
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
    private long buyVolume;
    private long sellVolume;
    private double vwap;
    private int tickCount;

    // ========== Volume Profile (from EnrichedCandlestick) ==========
    @Builder.Default
    private Map<Double, Long> volumeAtPrice = new HashMap<>();
    private Double poc;  // Point of Control
    private Double valueAreaHigh;
    private Double valueAreaLow;
    
    // ========== PHASE 1: Aggressive Volume Classification ==========
    private Long aggressiveBuyVolume;   // Market orders that lifted offers (TRUE buy intent)
    private Long aggressiveSellVolume;  // Market orders that hit bids (TRUE sell intent)
    
    // ========== PHASE 1: Imbalance Bar Triggers ==========
    private Boolean vibTriggered;  // Volume Imbalance Bar triggered
    private Boolean dibTriggered;  // Dollar Imbalance Bar triggered  
    private Boolean trbTriggered;  // Tick Run Bar triggered
    private Boolean vrbTriggered;  // Volume Run Bar triggered

    // ========== Imbalance Metrics (from EnrichedCandlestick) ==========
    private long volumeImbalance;
    private double dollarImbalance;
    private double vpin;

    // ========== Orderbook Microstructure (from OrderbookAggregate) ==========
    private double ofi;  // Order Flow Imbalance (full-depth)
    private double depthImbalance;
    private double kyleLambda;  // Price impact coefficient
    private double midPrice;
    private double microprice;
    private double bidAskSpread;
    private double totalBidDepth;
    private double totalAskDepth;
    private double averageBidVwap;
    private double averageAskVwap;
    private double weightedDepthImbalance;
    
    // Current depth snapshot (for cluster validation)
    @Builder.Default
    private Map<Double, Integer> bidDepthSnapshot = new HashMap<>();
    @Builder.Default
    private Map<Double, Integer> askDepthSnapshot = new HashMap<>();

    // ========== OI Metrics (from OIAggregate) ==========
    private Long oiOpen;
    private Long oiHigh;
    private Long oiLow;
    private Long oiClose;
    private Long oiChange;
    private Double oiChangePercent;
    // REMOVED: putOI, callOI, putCallRatio - meaningless at instrument level
    // Put/Call ratio should be calculated at underlying/family level, not per instrument

    // ========== GAP ANALYSIS (PHASE 1 Enhancement) ==========
    private Double previousClose;      // Previous session close price
    private Double overnightGap;       // (open - previousClose) / previousClose * 100
    private Boolean isGapUp;           // gap > 0.5%
    private Boolean isGapDown;         // gap < -0.5%

    // ========== TEMPORAL METRICS (Critical for Algo Detection) ==========
    private Long firstTickTimestamp;        // First tick in window
    private Long lastTickTimestamp;         // Last tick in window
    private Long minTickGap;                // Min gap between ticks (ms)
    private Long maxTickGap;                // Max gap between ticks (ms)
    private Double avgTickGap;              // Avg gap between ticks (ms)
    private Integer ticksPerSecond;         // Event frequency
    private Double tickAcceleration;        // Change in tick frequency

    // ========== TRADE SIZE DISTRIBUTION (Institutional Detection) ==========
    private Long maxTradeSize;              // Largest trade in window
    private Long minTradeSize;              // Smallest trade in window
    private Double avgTradeSize;            // Average trade size
    private Double medianTradeSize;         // Median trade size
    private Integer largeTradeCount;        // Trades > 10x average
    private Double priceImpactPerUnit;      // Price change per unit volume

    // ========== DEPTH FRAGMENTATION (Smart Money Detection) ==========
    private Integer totalBidOrders;         // Total orders on bid side
    private Integer totalAskOrders;         // Total orders on ask side
    private Integer ordersAtBestBid;        // Orders at best bid
    private Integer ordersAtBestAsk;        // Orders at best ask
    private Double avgBidOrderSize;         // Avg size per bid order
    private Double avgAskOrderSize;         // Avg size per ask order
    private Double depthConcentration;      // % volume in top 3 levels
    private Integer maxDepthLevels;         // Max levels observed

    // ========== ICEBERG & SPOOFING DETECTION ==========
    private Boolean icebergBidDetected;     // Iceberg order on bid side
    private Boolean icebergAskDetected;     // Iceberg order on ask side
    private Boolean icebergAtBestBid;       // Iceberg specifically at best bid
    private Boolean icebergAtBestAsk;       // Iceberg specifically at best ask
    private Integer spoofingCount;          // Detected spoofing events

    // ========== SPREAD DYNAMICS ==========
    private Double spreadVolatility;        // Spread volatility in window
    private Double maxSpread;               // Maximum spread observed
    private Double minSpread;               // Minimum spread observed
    private Double spreadChangeRate;        // Rate of spread change
    private Double orderbookMomentum;       // Depth change momentum
    private Integer orderbookUpdateCount;   // OB updates in window

    // ========== DEPTH SLOPE (Liquidity Curve) ==========
    private Double bidDepthSlope;           // Qty per price unit (bid side)
    private Double askDepthSlope;           // Qty per price unit (ask side)

    // ========== ALGO/HFT DETECTION ==========
    private Integer maxTicksInAnySecond;    // Peak tick rate
    private Integer secondsWithTicks;       // Active seconds in window
    private Double tickBurstRatio;          // Peak / avg tick rate
    private Boolean algoActivityDetected;   // Algo activity flag (burst > 3x)

    // ========== OPTIONS GREEKS ==========
    private Double delta;                   // Price sensitivity
    private Double gamma;                   // Delta sensitivity
    private Double vega;                    // Volatility sensitivity
    private Double theta;                   // Time decay
    private Double impliedVolatility;       // Estimated IV

    // ========== VWAP BANDS (Mean Reversion Signals) ==========
    private Double vwapUpperBand;           // VWAP + 2σ
    private Double vwapLowerBand;           // VWAP - 2σ
    private Double vwapStdDev;              // Std dev around VWAP
    private String vwapSignal;              // OVERBOUGHT/OVERSOLD/NEUTRAL
    private Double exchangeVwap;            // Exchange-provided VWAP
    private Double vwapDrift;               // Our VWAP vs exchange VWAP

    // ========== OI VELOCITY & DYNAMICS ==========
    private Double oiVelocity;              // OI change per minute
    private Double oiAcceleration;          // Velocity change
    private Integer oiUpdateCount;          // OI updates in window
    private Long oiUpdateLatency;           // Time since last OI update

    // ========== TICK-LEVEL BID/ASK IMBALANCE ==========
    private Long sumTotalBidQty;            // Sum of TBidQ from all ticks
    private Long sumTotalOffQty;            // Sum of TOffQ from all ticks
    private Double tickBidAskImbalance;     // (TBidQ - TOffQ) / total

    // ========== TRADE CLASSIFICATION QUALITY ==========
    private Long midpointVolume;            // Volume at midpoint
    private Double classificationReliability; // % trades with valid BBO
    private Double buyPressure;             // Aggressive buy / total
    private Double sellPressure;            // Aggressive sell / total

    // ========== TICK SPREAD METRICS (Execution Cost) ==========
    private Double averageTickSpread;       // Avg spread across ticks
    private Double minTickSpread;           // Min spread observed
    private Double maxTickSpread;           // Max spread observed
    private Double spreadVolatilityTick;    // Spread std dev
    private Double tightSpreadPercent;      // % time spread <= 1 tick

    // ========== LEVEL-WEIGHTED METRICS (Institutional Bias) ==========
    private Double levelWeightedBidDepth;   // Bid depth weighted by 1/(level+1)
    private Double levelWeightedAskDepth;   // Ask depth weighted by 1/(level+1)
    private Double levelWeightedImbalance;  // Weighted imbalance
    private Double bidFragmentation;        // Bid qty / bid orders
    private Double askFragmentation;        // Ask qty / ask orders
    private Double institutionalBias;       // askFrag / bidFrag

    // ========== CROSS-STREAM LATENCY ==========
    private Long tickToOrderbookLatency;    // ms between tick and OB
    private Long tickToOILatency;           // ms between tick and OI
    private Boolean tickStale;              // Tick > 5 sec old
    private Boolean orderbookStale;         // OB > 5 sec old
    private Boolean oiStale;                // OI > 5 min old
    private Long maxDataAge;                // Oldest data point age
    private String stalenessReason;         // Why data is stale

    // ========== DATA QUALITY ==========
    private String dataQuality;             // GOOD/ACCEPTABLE/POOR/STALE
    private String qualityReason;           // Explanation

    // ========== Derived Convenience Fields ==========
    private double volumeDeltaPercent;  // (buyVolume - sellVolume) / volume
    private double range;  // high - low
    private boolean isBullish;  // close > open

    /**
     * Factory method to build UnifiedCandle from component aggregates.
     * Handles null components gracefully.
     */
    public static UnifiedCandle from(EnrichedCandlestick candle,
                                     OrderbookAggregate orderbook,
                                     OIAggregate oi,
                                     String timeframe) {
        UnifiedCandleBuilder builder = UnifiedCandle.builder()
                .timeframe(timeframe);

        // Populate from EnrichedCandlestick
        if (candle != null) {
            builder.scripCode(candle.getScripCode())
                   .companyName(candle.getCompanyName())
                   .exchange(candle.getExchange())
                   .exchangeType(candle.getExchangeType())
                   .windowStartMillis(candle.getWindowStartMillis())
                   .windowEndMillis(candle.getWindowEndMillis())
                   .humanReadableStartTime(candle.getHumanReadableStartTime())
                   .humanReadableEndTime(candle.getHumanReadableEndTime())
                   .open(candle.getOpen())
                   .high(candle.getHigh())
                   .low(candle.getLow())
                   .close(candle.getClose())
                   .volume(candle.getVolume())
                   .buyVolume(candle.getBuyVolume())
                   .sellVolume(candle.getSellVolume())
                   .vwap(candle.getVwap())
                   .tickCount(candle.getTickCount())
                   .volumeAtPrice(candle.getVolumeAtPrice() != null ? 
                                  new HashMap<>(candle.getVolumeAtPrice()) : new HashMap<>())
                   .volumeImbalance(candle.getVolumeImbalance())
                   .dollarImbalance(candle.getDollarImbalance())
                   .vpin(candle.getVpin());

            // Calculate POC and Value Area if volumeAtPrice exists
            Double poc = candle.getPOC();
            builder.poc(poc);
            
            EnrichedCandlestick.ValueArea va = candle.getValueArea();
            if (va != null) {
                builder.valueAreaHigh(va.high);
                builder.valueAreaLow(va.low);
            }

            // Derived fields
            if (candle.getVolume() > 0) {
                builder.volumeDeltaPercent(
                    (double)(candle.getBuyVolume() - candle.getSellVolume()) / candle.getVolume() * 100.0);
            }
            builder.range(candle.getHigh() - candle.getLow());
            builder.isBullish(candle.getClose() > candle.getOpen());
            
            // PHASE 1 Enhancement: Gap analysis fields from EnrichedCandlestick
            builder.previousClose(candle.getPreviousClose())
                   .overnightGap(candle.getOvernightGap())
                   .isGapUp(candle.getIsGapUp())
                   .isGapDown(candle.getIsGapDown());
            
            // PHASE 1 Enhancement: Aggressive volume and imbalance triggers
            builder.aggressiveBuyVolume(candle.getAggressiveBuyVolume())
                   .aggressiveSellVolume(candle.getAggressiveSellVolume())
                   .vibTriggered(candle.isVibTriggered())
                   .dibTriggered(candle.isDibTriggered())
                   .trbTriggered(candle.isTrbTriggered())
                   .vrbTriggered(candle.isVrbTriggered());
        }

        // Populate from OrderbookAggregate
        if (orderbook != null) {
            builder.ofi(orderbook.getOfi())
                   .depthImbalance(orderbook.getDepthImbalance())
                   .kyleLambda(orderbook.getKyleLambda())
                   .midPrice(orderbook.getMidPrice())
                   .microprice(orderbook.getMicroprice())
                   .bidAskSpread(orderbook.getBidAskSpread())
                   .totalBidDepth(orderbook.getAverageBidDepth())
                   .totalAskDepth(orderbook.getAverageAskDepth())
                   .averageBidVwap(orderbook.getAverageBidVWAP())
                   .averageAskVwap(orderbook.getAverageAskVWAP())
                   .weightedDepthImbalance(orderbook.getAverageWeightedDepthImbalance());

            // Copy depth snapshots for cluster validation
            // BUG-FIX: Use getCurrentXxxDepth() instead of getPrevXxxDepth()
            if (orderbook.getCurrentBidDepth() != null && !orderbook.getCurrentBidDepth().isEmpty()) {
                builder.bidDepthSnapshot(new HashMap<>(orderbook.getCurrentBidDepth()));
            }
            if (orderbook.getCurrentAskDepth() != null && !orderbook.getCurrentAskDepth().isEmpty()) {
                builder.askDepthSnapshot(new HashMap<>(orderbook.getCurrentAskDepth()));
            }

            // Use scripCode from orderbook if not already set
            if (candle == null && orderbook.getScripCode() != null) {
                builder.scripCode(orderbook.getScripCode())
                       .companyName(orderbook.getCompanyName())
                       .exchange(orderbook.getExchange())
                       .exchangeType(orderbook.getExchangeType())
                       .windowStartMillis(orderbook.getWindowStartMillis())
                       .windowEndMillis(orderbook.getWindowEndMillis());
            }
        }

        // Populate from OIAggregate
        if (oi != null) {
            builder.oiOpen(oi.getOiOpen())
                   .oiHigh(oi.getOiHigh())
                   .oiLow(oi.getOiLow())
                   .oiClose(oi.getOiClose())
                   .oiChange(oi.getOiChange())
                   .oiChangePercent(oi.getOiChangePercent());
                   // REMOVED: putOI, callOI, putCallRatio - meaningless at instrument level

            // Use scripCode from OI if not already set
            if (candle == null && orderbook == null && oi.getScripCode() != null) {
                builder.scripCode(oi.getScripCode())
                       .companyName(oi.getCompanyName())
                       .exchange(oi.getExchange())
                       .exchangeType(oi.getExchangeType())
                       .windowStartMillis(oi.getWindowStartMillis())
                       .windowEndMillis(oi.getWindowEndMillis());
            }
        }

        UnifiedCandle result = builder.build();
        result.updateHumanReadableTimestamps();
        return result;
    }

    /**
     * Get volume delta (buyVolume - sellVolume)
     */
    public long getVolumeDelta() {
        return buyVolume - sellVolume;
    }

    /**
     * Check if this candle has valid price data
     */
    public boolean hasValidPrice() {
        return high > 0 && low > 0 && open > 0 && close > 0;
    }

    /**
     * Check if this candle has orderbook data
     */
    public boolean hasOrderbookData() {
        return midPrice > 0 || microprice > 0;
    }

    /**
     * Check if this candle has OI data
     */
    public boolean hasOIData() {
        return oiOpen != null && oiClose != null;
    }

    /**
     * Update human-readable timestamps
     */
    public void updateHumanReadableTimestamps() {
        if (windowStartMillis > 0 && humanReadableStartTime == null) {
            ZonedDateTime startTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(windowStartMillis),
                    ZoneId.of("Asia/Kolkata")
            );
            this.humanReadableStartTime = startTime.format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            );
        }

        if (windowEndMillis > 0 && humanReadableEndTime == null) {
            ZonedDateTime endTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(windowEndMillis),
                    ZoneId.of("Asia/Kolkata")
            );
            this.humanReadableEndTime = endTime.format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            );
        }
    }

    // ========== Kafka Serde ==========
    private static final ObjectMapper SHARED_OBJECT_MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    public static Serde<UnifiedCandle> serde() {
        return Serdes.serdeFrom(new UnifiedCandleSerializer(), new UnifiedCandleDeserializer());
    }

    public static class UnifiedCandleSerializer implements Serializer<UnifiedCandle> {
        @Override
        public byte[] serialize(String topic, UnifiedCandle data) {
            if (data == null) return null;
            try {
                return SHARED_OBJECT_MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for UnifiedCandle", e);
            }
        }
    }

    public static class UnifiedCandleDeserializer implements Deserializer<UnifiedCandle> {
        @Override
        public UnifiedCandle deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return SHARED_OBJECT_MAPPER.readValue(bytes, UnifiedCandle.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for UnifiedCandle", e);
            }
        }
    }
    
    // ========== FACTORY METHODS ==========
    
    /**
     * Create UnifiedCandle from InstrumentCandle (for MTF sub-candle tracking)
     * PHASE 2: Used by FamilyCandleProcessor to track sub-candles in aggregation window
     *
     * COMPLETE IMPLEMENTATION: Now copies ALL microstructure data from InstrumentCandle!
     * This ensures VCP, IPU, and signal generation have access to full calculated data.
     */
    public static UnifiedCandle from(com.kotsin.consumer.domain.model.InstrumentCandle ic) {
        if (ic == null) return null;

        UnifiedCandleBuilder builder = UnifiedCandle.builder()
                // ========== METADATA ==========
                .scripCode(ic.getScripCode())
                .companyName(ic.getCompanyName())
                .exchange(ic.getExchange())
                .exchangeType(ic.getExchangeType())
                .timeframe(ic.getTimeframe())
                .windowStartMillis(ic.getWindowStartMillis())
                .windowEndMillis(ic.getWindowEndMillis())

                // ========== OHLCV ==========
                .open(ic.getOpen())
                .high(ic.getHigh())
                .low(ic.getLow())
                .close(ic.getClose())
                .volume(ic.getVolume())
                .buyVolume(ic.getBuyVolume())
                .sellVolume(ic.getSellVolume())
                .vwap(ic.getVwap())
                .tickCount(ic.getTickCount())

                // ========== VOLUME PROFILE ==========
                .volumeAtPrice(ic.getVolumeAtPrice() != null ?
                        new java.util.HashMap<>(ic.getVolumeAtPrice()) : new java.util.HashMap<>())
                .poc(ic.getPoc())
                .valueAreaHigh(ic.getVah())
                .valueAreaLow(ic.getVal())

                // ========== VPIN ==========
                .vpin(ic.getVpin())

                // ========== TRADE CLASSIFICATION ==========
                .aggressiveBuyVolume(ic.getAggressiveBuyVolume())
                .aggressiveSellVolume(ic.getAggressiveSellVolume())
                .midpointVolume(ic.getMidpointVolume())
                .classificationReliability(ic.getClassificationReliability())
                .buyPressure(ic.getBuyPressure())
                .sellPressure(ic.getSellPressure())

                // ========== IMBALANCE BARS ==========
                .volumeImbalance(ic.getVolumeImbalance())
                .dollarImbalance(ic.getDollarImbalance())
                .vibTriggered(ic.isVibTriggered())
                .dibTriggered(ic.isDibTriggered())
                .trbTriggered(ic.isTrbTriggered())
                .vrbTriggered(ic.isVrbTriggered())

                // ========== TICK-LEVEL BID/ASK IMBALANCE ==========
                .sumTotalBidQty(ic.getSumTotalBidQty())
                .sumTotalOffQty(ic.getSumTotalOffQty())
                .tickBidAskImbalance(ic.getTickBidAskImbalance())

                // ========== GAP ANALYSIS ==========
                .previousClose(ic.getPreviousClose())
                .overnightGap(ic.getOvernightGap())
                .isGapUp(ic.getIsGapUp())
                .isGapDown(ic.getIsGapDown())

                // ========== ORDERBOOK METRICS ==========
                .ofi(ic.getOfi() != null ? ic.getOfi() : 0.0)
                .kyleLambda(ic.getKyleLambda() != null ? ic.getKyleLambda() : 0.0)
                .depthImbalance(ic.getDepthImbalance() != null ? ic.getDepthImbalance() : 0.0)
                .weightedDepthImbalance(ic.getWeightedDepthImbalance() != null ? ic.getWeightedDepthImbalance() : 0.0)
                .microprice(ic.getMicroprice() != null ? ic.getMicroprice() : 0.0)
                .bidAskSpread(ic.getBidAskSpread() != null ? ic.getBidAskSpread() : 0.0)
                .totalBidDepth(ic.getAverageBidDepth() != null ? ic.getAverageBidDepth().longValue() : 0L)
                .totalAskDepth(ic.getAverageAskDepth() != null ? ic.getAverageAskDepth().longValue() : 0L)

                // ========== DEPTH FRAGMENTATION ==========
                .totalBidOrders(ic.getTotalBidOrders())
                .totalAskOrders(ic.getTotalAskOrders())
                .ordersAtBestBid(ic.getOrdersAtBestBid())
                .ordersAtBestAsk(ic.getOrdersAtBestAsk())
                .avgBidOrderSize(ic.getAvgBidOrderSize())
                .avgAskOrderSize(ic.getAvgAskOrderSize())
                .depthConcentration(ic.getDepthConcentration())
                .maxDepthLevels(ic.getMaxDepthLevels())

                // ========== ICEBERG & SPOOFING ==========
                .icebergBidDetected(ic.getIcebergBidDetected())
                .icebergAskDetected(ic.getIcebergAskDetected())
                .icebergAtBestBid(ic.getIcebergAtBestBid())
                .icebergAtBestAsk(ic.getIcebergAtBestAsk())
                .spoofingCount(ic.getSpoofingCount())

                // ========== SPREAD DYNAMICS ==========
                .spreadVolatility(ic.getSpreadVolatility())
                .maxSpread(ic.getMaxSpread())
                .minSpread(ic.getMinSpread())
                .spreadChangeRate(ic.getSpreadChangeRate())
                .orderbookMomentum(ic.getOrderbookMomentum())
                .orderbookUpdateCount(ic.getOrderbookUpdateCount())

                // ========== DEPTH SLOPE (LIQUIDITY CURVE) ==========
                .bidDepthSlope(ic.getBidDepthSlope())
                .askDepthSlope(ic.getAskDepthSlope())

                // ========== TEMPORAL METRICS ==========
                .firstTickTimestamp(ic.getFirstTickTimestamp())
                .lastTickTimestamp(ic.getLastTickTimestamp())
                .minTickGap(ic.getMinTickGap())
                .maxTickGap(ic.getMaxTickGap())
                .avgTickGap(ic.getAvgTickGap())
                .ticksPerSecond(ic.getTicksPerSecond())
                .tickAcceleration(ic.getTickAcceleration())

                // ========== TRADE SIZE DISTRIBUTION ==========
                .maxTradeSize(ic.getMaxTradeSize())
                .minTradeSize(ic.getMinTradeSize())
                .avgTradeSize(ic.getAvgTradeSize())
                .medianTradeSize(ic.getMedianTradeSize())
                .largeTradeCount(ic.getLargeTradeCount())
                .priceImpactPerUnit(ic.getPriceImpactPerUnit())

                // ========== ALGO/HFT DETECTION ==========
                .maxTicksInAnySecond(ic.getMaxTicksInAnySecond())
                .secondsWithTicks(ic.getSecondsWithTicks())
                .tickBurstRatio(ic.getTickBurstRatio())
                .algoActivityDetected(ic.getAlgoActivityDetected())

                // ========== TICK SPREAD METRICS ==========
                .averageTickSpread(ic.getAverageTickSpread())
                .minTickSpread(ic.getMinTickSpread())
                .maxTickSpread(ic.getMaxTickSpread())
                .spreadVolatilityTick(ic.getSpreadVolatilityTick())
                .tightSpreadPercent(ic.getTightSpreadPercent())

                // ========== VWAP BANDS ==========
                .vwapUpperBand(ic.getVwapUpperBand())
                .vwapLowerBand(ic.getVwapLowerBand())
                .vwapStdDev(ic.getVwapStdDev())
                .vwapSignal(ic.getVwapSignal())
                .exchangeVwap(ic.getExchangeVwap())
                .vwapDrift(ic.getVwapDrift())

                // ========== LEVEL-WEIGHTED METRICS ==========
                .levelWeightedBidDepth(ic.getLevelWeightedBidDepth())
                .levelWeightedAskDepth(ic.getLevelWeightedAskDepth())
                .levelWeightedImbalance(ic.getLevelWeightedImbalance())
                .bidFragmentation(ic.getBidFragmentation())
                .askFragmentation(ic.getAskFragmentation())
                .institutionalBias(ic.getInstitutionalBias())

                // ========== OI METRICS ==========
                .oiOpen(ic.getOiOpen())
                .oiHigh(ic.getOiHigh())
                .oiLow(ic.getOiLow())
                .oiClose(ic.getOiClose())
                .oiChange(ic.getOiChange())
                .oiChangePercent(ic.getOiChangePercent())
                .oiVelocity(ic.getOiVelocity())
                .oiAcceleration(ic.getOiAcceleration())
                .oiUpdateCount(ic.getOiUpdateCount())
                .oiUpdateLatency(ic.getOiUpdateLatency())

                // ========== OPTIONS GREEKS ==========
                .delta(ic.getDelta())
                .gamma(ic.getGamma())
                .vega(ic.getVega())
                .theta(ic.getTheta())
                .impliedVolatility(ic.getImpliedVolatility())

                // ========== CROSS-STREAM LATENCY ==========
                .tickToOrderbookLatency(ic.getTickToOrderbookLatency())
                .tickToOILatency(ic.getTickToOILatency())
                .tickStale(ic.getTickStale())
                .orderbookStale(ic.getOrderbookStale())
                .oiStale(ic.getOiStale())
                .maxDataAge(ic.getMaxDataAge())
                .stalenessReason(ic.getStalenessReason())

                // ========== DATA QUALITY ==========
                .dataQuality(ic.getQuality() != null ? ic.getQuality().name() : null)
                .qualityReason(ic.getQualityReason());

        // ========== DERIVED FIELDS ==========
        if (ic.getVolume() > 0) {
            builder.volumeDeltaPercent(
                    (double) (ic.getBuyVolume() - ic.getSellVolume()) / ic.getVolume() * 100.0);
        }
        builder.range(ic.getHigh() - ic.getLow());
        builder.isBullish(ic.getClose() > ic.getOpen());

        UnifiedCandle result = builder.build();
        result.updateHumanReadableTimestamps();
        return result;
    }
}
