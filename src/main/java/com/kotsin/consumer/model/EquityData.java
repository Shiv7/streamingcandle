package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Individual equity data from getDesiredWebSocket API
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EquityData {
    
    private String equityScripCode;
    private String tradingType;
    private String companyName;
    private InstrumentInfo equity;
    private List<InstrumentInfo> futures;
    private List<InstrumentInfo> options;
    private Double closePrice;
    private String insertionDate;
    
    /**
     * Get scrip code for API calls
     */
    public String getScripCodeForApi() {
        return equityScripCode;
    }
    
    /**
     * Check if equity has futures
     */
    public boolean hasFutures() {
        return futures != null && !futures.isEmpty();
    }
    
    /**
     * Check if equity has options
     */
    public boolean hasOptions() {
        return options != null && !options.isEmpty();
    }
}
