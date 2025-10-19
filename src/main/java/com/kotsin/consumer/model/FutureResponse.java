package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response from getRequiredFuture API
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FutureResponse {
    
    private Integer status;
    private String message;
    private InstrumentInfo equity;
    private InstrumentInfo future;
    
    /**
     * Check if response is successful
     */
    public boolean isSuccess() {
        return status != null && status == 200;
    }
    
    /**
     * Check if future is available
     */
    public boolean hasFuture() {
        return future != null;
    }
}
