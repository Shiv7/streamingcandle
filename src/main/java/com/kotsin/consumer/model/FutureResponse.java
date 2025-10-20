package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response from getRequiredFuture API
 *
 * API returns:
 * {
 *   "status": 200,
 *   "message": "success",
 *   "response": { ... single InstrumentInfo object ... }
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FutureResponse {

    private Integer status;
    private String message;
    private InstrumentInfo response;  // ← Changed from "equity" and "future" to match API

    /**
     * Check if response is successful
     */
    public boolean isSuccess() {
        return status != null && status == 200 && "success".equalsIgnoreCase(message);
    }

    /**
     * Get equity info (for backwards compatibility)
     */
    public InstrumentInfo getEquity() {
        return response;  // The future API response contains the equity info
    }

    /**
     * Get future info (for backwards compatibility)
     */
    public InstrumentInfo getFuture() {
        return response;  // The response IS the future
    }

    /**
     * Check if future is available
     */
    public boolean hasFuture() {
        return response != null;
    }
}
