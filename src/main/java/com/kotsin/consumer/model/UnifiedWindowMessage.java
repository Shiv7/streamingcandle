package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedWindowMessage {

    // Header
    private String scripCode;
    private String companyName;
    private String exchange;
    private String exchangeType;
    private String timeframe; // 1m/2m/3m/5m/15m/30m
    private Long windowStartMillis;
    private Long windowEndMillis;
    private String startTimeIST;
    private String endTimeIST;

    // Candle section
    private CandleSection candle;

    // Orderbook + microstructure section (top-10 style summary)
    private OrderbookSignals orderbookSignals;

    // Imbalance bars (VIB/DIB/TRB/VRB)
    private ImbalanceBarData imbalanceBars;

    // Open Interest section (available fields)
    private OpenInterestSection openInterest;

    // Volume Profile section (POC, Value Area, stats)
    private VolumeProfileData volumeProfile;

    public static String toIstString(Long ts) {
        if (ts == null) return null;
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.of("Asia/Kolkata"))
                .format(Instant.ofEpochMilli(ts));
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CandleSection {
        private Double open;
        private Double high;
        private Double low;
        private Double close;
        private Long volume;
        private Long buyVolume;
        private Long sellVolume;
        private Double vwap;
        private Integer tickCount;
        private Boolean isComplete;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderbookSignals {
        // Microstructure summary
        private String vpinLevel;              // from MicrostructureData.getVpinLevel()
        private Boolean depthBuyImbalanced;    // from MicrostructureData.isDepthBuyImbalanced()
        private Boolean depthSellImbalanced;   // from MicrostructureData.isDepthSellImbalanced()
        private String spreadLevel;            // from MicrostructureData.getSpreadLevel()

        // Depth + OFI + spread derived
        private Double ofi;                    // from MicrostructureData.ofi
        private Double depthImbalance;         // from MicrostructureData.depthImbalance or OrderbookDepthData.weightedDepthImbalance
        private Double spreadAvg;              // from OrderbookDepthData.spread (avg proxy)
        private Double bidDepthSum;            // from OrderbookDepthData.totalBidDepth
        private Double askDepthSum;            // from OrderbookDepthData.totalAskDepth
        private Double bidVWAP;                // from OrderbookDepthData.bidVWAP
        private Double askVWAP;                // from OrderbookDepthData.askVWAP
        private Double microprice;             // from MicrostructureData.microprice or OrderbookDepthData.midPrice
        private Boolean icebergBid;            // from OrderbookDepthData.icebergDetectedBid
        private Boolean icebergAsk;            // from OrderbookDepthData.icebergDetectedAsk
        private Integer spoofingCount;         // from OrderbookDepthData.spoofingCountLast1Min
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OpenInterestSection {
        private Long oiClose;          // latest OI in window (mapped from candle if present)
        private Long oiChange;         // from candle.oiChange
        private Double oiChangePercent;// from candle.oiChangePercent
    }
}
