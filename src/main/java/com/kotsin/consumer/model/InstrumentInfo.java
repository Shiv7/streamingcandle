package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Individual instrument information
 * Used for equity, future, and options
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstrumentInfo {
    
    private String scripCode;
    private String token;
    private String name;
    private String fullName;
    private String exchange;
    private String exchangeType;
    private String series;
    private String expiry;
    private String scripType;
    private Double strikeRate;
    private Double tickSize;
    private Integer lotSize;
    private String isin;
    private String symbolRoot;
    private String bocoallowed;
    private String id;
    private String scriptTypeKotsin;
    private String insertionDate;
    private String multiplier;
    private String qtyLimit;
    private String scripData;
    
    /**
     * Check if this is an equity instrument
     */
    public boolean isEquity() {
        return "C".equals(exchangeType) && "XX".equals(scripType);
    }
    
    /**
     * Check if this is a future instrument
     */
    public boolean isFuture() {
        return "D".equals(exchangeType) && "XX".equals(scripType);
    }
    
    /**
     * Check if this is an option instrument
     */
    public boolean isOption() {
        return "D".equals(exchangeType) && ("CE".equals(scripType) || "PE".equals(scripType));
    }
    
    /**
     * Check if this is a call option
     */
    public boolean isCallOption() {
        return "CE".equals(scripType);
    }
    
    /**
     * Check if this is a put option
     */
    public boolean isPutOption() {
        return "PE".equals(scripType);
    }
    
    /**
     * Get display name for logging
     */
    public String getDisplayName() {
        if (isOption()) {
            return String.format("%s %s %.2f %s", name, expiry, strikeRate, scripType);
        }
        return name;
    }
}
