package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.kafka.support.serializer.JsonSerde;

import java.util.ArrayList;
import java.util.List;

/**
 * Family-Level Enriched Data
 * Contains ALL instruments (equity + futures + options) in a single family
 *
 * Key Design: All instruments grouped by underlying equity scripCode
 * Used by strategies to analyze entire instrument family together
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FamilyEnrichedData {

    // Family identification
    private String familyKey;                    // Underlying equity scripCode (or index for NIFTY/BANKNIFTY)
    private String familyName;                   // Company name or index name
    private String instrumentType;               // EQUITY_FAMILY, INDEX_FAMILY

    // Window information
    private Long windowStartMillis;
    private Long windowEndMillis;
    private String timeframe;                    // 1m, 2m, 3m, 5m, 15m, 30m

    // Equity data (null for index families)
    private InstrumentCandle equity;

    // Futures data (list of all active futures contracts)
    @Builder.Default
    private List<InstrumentCandle> futures = new ArrayList<>();

    // Options data (list of all active options)
    @Builder.Default
    private List<InstrumentCandle> options = new ArrayList<>();

    // Aggregated metrics across all instruments
    private FamilyAggregatedMetrics aggregatedMetrics;

    // Microstructure and orderbook data (aggregated across family)
    private MicrostructureData microstructure;
    private OrderbookDepthData orderbookDepth;
    private ImbalanceBarData imbalanceBars;

    // Metadata
    private Long processingTimestamp;
    private Integer totalInstrumentsCount;
    private String dataQuality;                  // HIGH, MEDIUM, LOW

    /**
     * Get Kafka Serde for serialization/deserialization
     */
    public static JsonSerde<FamilyEnrichedData> serde() {
        return new JsonSerde<>(FamilyEnrichedData.class);
    }

    /**
     * Check if this family has equity data
     */
    public boolean hasEquity() {
        return equity != null && equity.isValid();
    }

    /**
     * Check if this family has any futures
     */
    public boolean hasFutures() {
        return futures != null && !futures.isEmpty();
    }

    /**
     * Check if this family has any options
     */
    public boolean hasOptions() {
        return options != null && !options.isEmpty();
    }

    /**
     * Get near-month future (nearest expiry)
     */
    public InstrumentCandle getNearMonthFuture() {
        if (futures == null || futures.isEmpty()) {
            return null;
        }

        // Return future with earliest expiry
        return futures.stream()
            .filter(f -> f.getExpiry() != null)
            .min((f1, f2) -> f1.getExpiry().compareTo(f2.getExpiry()))
            .orElse(futures.get(0));
    }

    /**
     * Get ATM options (around spot price)
     */
    public List<InstrumentCandle> getAtmOptions(double spotPrice, int strikeRange) {
        if (options == null || options.isEmpty()) {
            return new ArrayList<>();
        }

        List<InstrumentCandle> atmOptions = new ArrayList<>();
        for (InstrumentCandle option : options) {
            if (option.getStrikePrice() != null) {
                double diff = Math.abs(option.getStrikePrice() - spotPrice);
                if (diff <= strikeRange * 50) {  // Assuming 50 point strike intervals
                    atmOptions.add(option);
                }
            }
        }
        return atmOptions;
    }

    /**
     * Count instruments by type
     */
    public int getFuturesCount() {
        return futures != null ? futures.size() : 0;
    }

    public int getOptionsCount() {
        return options != null ? options.size() : 0;
    }

    /**
     * Calculate total family count
     */
    public int calculateTotalCount() {
        int count = 0;
        if (hasEquity()) count++;
        count += getFuturesCount();
        count += getOptionsCount();
        return count;
    }
}
