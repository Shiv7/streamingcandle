package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * AggregatedCandle - MongoDB-persisted completed candle with full context.
 *
 * Mirrors UnifiedCandle fields but stored in MongoDB for nightly review.
 * Populated by CompletedCandleService on boundary events.
 * 7-day TTL for automatic cleanup.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Document(collection = "aggregated_candles")
@CompoundIndexes({
    @CompoundIndex(name = "scrip_tf_window_idx", def = "{'scripCode': 1, 'timeframe': 1, 'windowStart': 1}", unique = true),
    @CompoundIndex(name = "symbol_tf_window_idx", def = "{'symbol': 1, 'timeframe': 1, 'windowStart': -1}")
})
public class AggregatedCandle {

    @Id
    private String id;

    // ==================== IDENTITY ====================
    private String symbol;
    private String scripCode;
    private String exchange;
    private String exchangeType;
    private String companyName;
    private String timeframe;

    // ==================== TIMING ====================
    private Instant windowStart;
    private Instant windowEnd;

    @Indexed(expireAfter = "7d")
    @Builder.Default
    private Instant createdAt = Instant.now();

    // ==================== OHLCV ====================
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
    private double value;
    private double vwap;

    // ==================== TRADE CLASSIFICATION ====================
    private long buyVolume;
    private long sellVolume;
    private long volumeDelta;
    private double buyPressure;
    private double sellPressure;
    private double vpin;

    // ==================== VOLUME PROFILE ====================
    private double poc;
    private double vah;
    private double val;

    // ==================== IMBALANCE ====================
    private double volumeImbalance;
    private boolean vibTriggered;
    private boolean dibTriggered;

    // ==================== TICK STATS ====================
    private int tickCount;
    private int largeTradeCount;

    // ==================== ORDERBOOK ====================
    private boolean hasOrderbook;
    private Double ofi;
    private Double kyleLambda;
    private Double microprice;
    private Double bidAskSpread;
    private Double spreadPercent;
    private Double depthImbalance;
    private Double avgBidDepth;
    private Double avgAskDepth;
    private Integer spoofingCount;

    // ==================== OI ====================
    private boolean hasOI;
    private Long openInterest;
    private Long oiChange;
    private Double oiChangePercent;
    private String oiInterpretation;
    private Double oiInterpretationConfidence;
    private Boolean oiSuggestsReversal;
    private Double oiVelocity;

    // ==================== AGGREGATION ====================
    private int aggregatedCandleCount;
    private int expectedCandleCount;
    private double completenessRatio;

    /**
     * Build from UnifiedCandle.
     */
    public static AggregatedCandle fromUnifiedCandle(UnifiedCandle uc, String scripCode,
                                                      String symbol, Timeframe tf,
                                                      Instant windowStart, Instant windowEnd) {
        AggregatedCandleBuilder b = AggregatedCandle.builder()
            .symbol(symbol)
            .scripCode(scripCode)
            .exchange(uc.getExchange())
            .exchangeType(uc.getExchangeType())
            .companyName(uc.getCompanyName())
            .timeframe(tf.getLabel())
            .windowStart(windowStart)
            .windowEnd(windowEnd)
            .createdAt(Instant.now())
            // OHLCV
            .open(uc.getOpen())
            .high(uc.getHigh())
            .low(uc.getLow())
            .close(uc.getClose())
            .volume(uc.getVolume())
            .value(uc.getValue())
            .vwap(uc.getVwap())
            // Trade classification
            .buyVolume(uc.getBuyVolume())
            .sellVolume(uc.getSellVolume())
            .volumeDelta(uc.getVolumeDelta())
            .buyPressure(uc.getBuyPressure())
            .sellPressure(uc.getSellPressure())
            .vpin(uc.getVpin())
            // Volume profile
            .poc(uc.getPoc())
            .vah(uc.getVah())
            .val(uc.getVal())
            // Imbalance
            .volumeImbalance(uc.getVolumeImbalance())
            .vibTriggered(uc.isVibTriggered())
            .dibTriggered(uc.isDibTriggered())
            // Stats
            .tickCount(uc.getTickCount())
            .largeTradeCount(uc.getLargeTradeCount())
            // Aggregation
            .aggregatedCandleCount(uc.getAggregatedCandleCount())
            .expectedCandleCount(uc.getExpectedCandleCount())
            .completenessRatio(uc.getCompletenessRatio());

        // Orderbook
        if (uc.isHasOrderbook()) {
            b.hasOrderbook(true)
                .ofi(uc.getOfi())
                .kyleLambda(uc.getKyleLambda())
                .microprice(uc.getMicroprice())
                .bidAskSpread(uc.getBidAskSpread())
                .spreadPercent(uc.getSpreadPercent())
                .depthImbalance(uc.getDepthImbalance())
                .avgBidDepth(uc.getAvgBidDepth())
                .avgAskDepth(uc.getAvgAskDepth())
                .spoofingCount(uc.getSpoofingCount());
        }

        // OI
        if (uc.isHasOI()) {
            b.hasOI(true)
                .openInterest(uc.getOpenInterest())
                .oiChange(uc.getOiChange())
                .oiChangePercent(uc.getOiChangePercent())
                .oiInterpretation(uc.getOiInterpretation() != null ? uc.getOiInterpretation().name() : null)
                .oiInterpretationConfidence(uc.getOiInterpretationConfidence())
                .oiSuggestsReversal(uc.getOiSuggestsReversal())
                .oiVelocity(uc.getOiVelocity());
        }

        return b.build();
    }
}
