package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Complete instrument family for an equity scrip
 * Contains equity + future + options in one structure
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstrumentFamily {
    
    private String equityScripCode;
    private String companyName;
    private InstrumentInfo equity;
    private InstrumentInfo future;
    private List<InstrumentInfo> options;
    private Long lastUpdated;
    private String dataSource; // "API", "CACHE", "ERROR"
    
    /**
     * Get total number of instruments in this family
     */
    public int getTotalInstruments() {
        int count = 1; // equity
        if (future != null) count++;
        if (options != null) count += options.size();
        return count;
    }
    
    /**
     * Check if family has options
     */
    public boolean hasOptions() {
        return options != null && !options.isEmpty();
    }
    
    /**
     * Check if family has future
     */
    public boolean hasFuture() {
        return future != null;
    }
    
    /**
     * Get options count
     */
    public int getOptionsCount() {
        return options != null ? options.size() : 0;
    }
}
