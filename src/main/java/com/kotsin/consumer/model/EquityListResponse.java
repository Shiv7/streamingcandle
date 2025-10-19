package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response from getDesiredWebSocket API
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EquityListResponse {
    
    private Integer status;
    private String message;
    private List<EquityData> response;
    
    /**
     * Check if response is successful
     */
    public boolean isSuccess() {
        return status != null && status == 200 && response != null;
    }
    
    /**
     * Get response size
     */
    public int getResponseSize() {
        return response != null ? response.size() : 0;
    }
}
