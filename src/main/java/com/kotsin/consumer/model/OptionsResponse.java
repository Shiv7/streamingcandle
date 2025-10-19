package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response from getRequiredOptions API
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionsResponse {
    
    private Integer status;
    private String message;
    private List<InstrumentInfo> options;
    
    /**
     * Check if response is successful
     */
    public boolean isSuccess() {
        return status != null && status == 200;
    }
    
    /**
     * Check if options are available
     */
    public boolean hasOptions() {
        return options != null && !options.isEmpty();
    }
    
    /**
     * Get options count
     */
    public int getOptionsCount() {
        return options != null ? options.size() : 0;
    }
    
    /**
     * Get call options only
     */
    public List<InstrumentInfo> getCallOptions() {
        if (options == null) return List.of();
        return options.stream()
            .filter(InstrumentInfo::isCallOption)
            .toList();
    }
    
    /**
     * Get put options only
     */
    public List<InstrumentInfo> getPutOptions() {
        if (options == null) return List.of();
        return options.stream()
            .filter(InstrumentInfo::isPutOption)
            .toList();
    }
}
