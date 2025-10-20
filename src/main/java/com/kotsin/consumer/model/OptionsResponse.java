package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Collections;

/**
 * Response from getRequiredOptions API
 *
 * API returns:
 * {
 *   "status": 200,
 *   "message": "success",
 *   "response": [ ... array of InstrumentInfo objects ... ]
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionsResponse {

    private Integer status;
    private String message;
    private List<InstrumentInfo> response;  // ← Changed from "options" to match API

    /**
     * Check if response is successful
     */
    public boolean isSuccess() {
        return status != null && status == 200 && "success".equalsIgnoreCase(message);
    }

    /**
     * Get options (for backwards compatibility)
     */
    public List<InstrumentInfo> getOptions() {
        return response != null ? response : Collections.emptyList();
    }

    /**
     * Check if options are available
     */
    public boolean hasOptions() {
        return response != null && !response.isEmpty();
    }

    /**
     * Get options count
     */
    public int getOptionsCount() {
        return response != null ? response.size() : 0;
    }
    
    /**
     * Get call options only
     */
    public List<InstrumentInfo> getCallOptions() {
        if (response == null) return List.of();
        return response.stream()
            .filter(InstrumentInfo::isCallOption)
            .toList();
    }

    /**
     * Get put options only
     */
    public List<InstrumentInfo> getPutOptions() {
        if (response == null) return List.of();
        return response.stream()
            .filter(InstrumentInfo::isPutOption)
            .toList();
    }
}
