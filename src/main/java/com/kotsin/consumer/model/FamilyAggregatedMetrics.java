package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aggregated metrics across all instruments in an instrument family
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FamilyAggregatedMetrics {

    // Volume metrics
    private Long totalVolume;                    // Sum of all instrument volumes
    private Long equityVolume;
    private Long futuresVolume;
    private Long optionsVolume;

    // Open Interest metrics
    private Long totalOpenInterest;
    private Long futuresOI;
    private Long callsOI;
    private Long putsOI;
    private Long futuresOIChange;
    private Long callsOIChange;
    private Long putsOIChange;

    // Options metrics
    private Double putCallRatio;                 // Puts OI / Calls OI
    private Double putCallVolumeRatio;           // Puts Volume / Calls Volume
    private Integer activeOptionsCount;          // Number of options with volume > 0

    // Price correlation
    private Double spotPrice;                    // Equity or index spot price
    private Double nearMonthFuturePrice;         // Nearest expiry future price
    private Double futuresBasis;                 // Future - Spot
    private Double futuresBasisPercent;          // (Future - Spot) / Spot * 100

    // Futures chain metrics
    private Integer activeFuturesCount;          // Number of futures contracts
    private String nearMonthExpiry;              // Nearest expiry date

    // Orderbook metrics
    private Double avgBidAskSpread;              // Average across all instruments
    private Long totalBidVolume;
    private Long totalAskVolume;
    private Double bidAskImbalance;              // (Bid - Ask) / (Bid + Ask)

    // Timestamp
    private Long calculatedAt;
}
