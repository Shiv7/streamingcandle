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
     * CRITICAL FIX: Now copies volumeAtPrice, POC, VAH, VAL from InstrumentCandle!
     * This was causing VCP to use fallback "fake" volume profiles instead of real tick data.
     */
    public static UnifiedCandle from(com.kotsin.consumer.domain.model.InstrumentCandle ic) {
        if (ic == null) return null;

        return UnifiedCandle.builder()
                .scripCode(ic.getScripCode())
                .companyName(ic.getCompanyName())
                .exchange(ic.getExchange())
                .exchangeType(ic.getExchangeType())
                .timeframe(ic.getTimeframe())
                .windowStartMillis(ic.getWindowStartMillis())
                .windowEndMillis(ic.getWindowEndMillis())
                // OHLCV
                .open(ic.getOpen())
                .high(ic.getHigh())
                .low(ic.getLow())
                .close(ic.getClose())
                .volume(ic.getVolume())
                .buyVolume(ic.getBuyVolume())
                .sellVolume(ic.getSellVolume())
                .vwap(ic.getVwap())
                .tickCount(ic.getTickCount())
                // VOLUME PROFILE - CRITICAL: Was missing, causing fake volume profiles!
                .volumeAtPrice(ic.getVolumeAtPrice() != null ?
                        new java.util.HashMap<>(ic.getVolumeAtPrice()) : new java.util.HashMap<>())
                .poc(ic.getPoc())
                .valueAreaHigh(ic.getVah())
                .valueAreaLow(ic.getVal())
                // VPIN
                .vpin(ic.getVpin())
                // Trade Classification
                .aggressiveBuyVolume(ic.getAggressiveBuyVolume())
                .aggressiveSellVolume(ic.getAggressiveSellVolume())
                // Imbalance Bars
                .volumeImbalance(ic.getVolumeImbalance())
                .dollarImbalance(ic.getDollarImbalance())
                // Orderbook metrics (if present)
                .ofi(ic.getOfi())
                .kyleLambda(ic.getKyleLambda())
                .depthImbalance(ic.getDepthImbalance())
                .microprice(ic.getMicroprice())
                .bidAskSpread(ic.getBidAskSpread())
                .totalBidDepth(ic.getAverageBidDepth() != null ? ic.getAverageBidDepth().longValue() : 0L)
                .totalAskDepth(ic.getAverageAskDepth() != null ? ic.getAverageAskDepth().longValue() : 0L)
                .build();
    }
}
