package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Unified market data message containing all timeframes and features
 * Replaces 19 separate topics with 1 comprehensive message
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnrichedMarketData {
    
    // Basic identification
    private String scripCode;
    private String companyName;
    private String exchange;
    private String exchangeType;
    private Long timestamp;
    
    // Instrument family (from cache)
    private InstrumentFamily instrumentFamily;
    
    // Multi-timeframe candles (1m, 2m, 3m, 5m, 15m, 30m)
    private Map<String, CandleData> multiTimeframeCandles;
    
    // Open Interest (all timeframes)
    private Map<String, OpenInterestTimeframeData> openInterest;
    
    // Imbalance bars (real-time progress)
    private ImbalanceBarData imbalanceBars;
    
    // Microstructure features
    private MicrostructureData microstructure;
    
    // Metadata
    private MessageMetadata metadata;
    
    /**
     * Get complete timeframes only
     */
    public Map<String, CandleData> getCompleteCandles() {
        if (multiTimeframeCandles == null) return Map.of();
        
        return multiTimeframeCandles.entrySet().stream()
            .filter(entry -> entry.getValue().getIsComplete())
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue
            ));
    }
    
    /**
     * Get partial timeframes only
     */
    public Map<String, CandleData> getPartialCandles() {
        if (multiTimeframeCandles == null) return Map.of();
        
        return multiTimeframeCandles.entrySet().stream()
            .filter(entry -> !entry.getValue().getIsComplete())
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue
            ));
    }
    
    /**
     * Check if any timeframe is complete
     */
    public boolean hasAnyCompleteTimeframe() {
        return multiTimeframeCandles != null && 
               multiTimeframeCandles.values().stream()
                   .anyMatch(CandleData::getIsComplete);
    }
    
    /**
     * Get complete timeframes count
     */
    public int getCompleteTimeframesCount() {
        return (int) multiTimeframeCandles.values().stream()
            .mapToInt(candle -> candle.getIsComplete() ? 1 : 0)
            .sum();
    }
}
