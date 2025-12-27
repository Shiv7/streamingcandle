package com.kotsin.consumer.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * InstrumentFamily - Maps an equity to its related derivatives.
 * 
 * Used by FamilyCandleProcessor to group instruments:
 * - 1 Equity (base instrument)
 * - 1 Future (nearest expiry)
 * - 4 Options (ATM ± 1 strike, CE/PE)
 * 
 * Cached in Redis with 1-day TTL to avoid repeated API calls.
 * 
 * Data comes from ScripFinder API:
 * - GET /getRequiredFuture?equityScripCode={scripCode}
 * - GET /getRequiredOptions?equityScripCode={scripCode}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstrumentFamily {

    // ==================== EQUITY (Base Instrument) ====================
    private String equityScripCode;
    private String symbolRoot;          // e.g., "UNOMINDA"
    private String companyName;
    private double closePrice;          // Last known close for ATM strike calculation

    // ==================== FUTURE ====================
    private String futureScripCode;
    private String futureName;          // e.g., "UNOMINDA 30 DEC 2025"
    private String futureExpiry;        // e.g., "2025-12-30"
    private int futureLotSize;

    // ==================== OPTIONS ====================
    private List<OptionInfo> options;   // 4 options: ATM ± 1 strike, CE + PE
    private double atmStrike;           // Calculated ATM strike
    private double strikeInterval;      // Interval between strikes

    // ==================== METADATA ====================
    private long lastUpdated;           // Timestamp for cache invalidation
    private boolean active;             // Is this family actively traded?

    // ==================== OPTION INFO ====================
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionInfo {
        private String scripCode;
        private String name;            // e.g., "UNOMINDA 30 DEC 2025 CE 1280.00"
        private String optionType;      // "CE" or "PE"
        private double strikePrice;
        private String expiry;
        private int lotSize;
    }

    // ==================== HELPER METHODS ====================

    /**
     * Check if future is available
     */
    public boolean hasFuture() {
        return futureScripCode != null && !futureScripCode.isEmpty();
    }

    /**
     * Check if options are available
     */
    public boolean hasOptions() {
        return options != null && !options.isEmpty();
    }

    /**
     * Get number of options
     */
    public int getOptionCount() {
        return options != null ? options.size() : 0;
    }

    /**
     * Get all scripCodes in this family (for grouping)
     */
    public List<String> getAllScripCodes() {
        java.util.List<String> codes = new java.util.ArrayList<>();
        codes.add(equityScripCode);
        if (hasFuture()) {
            codes.add(futureScripCode);
        }
        if (hasOptions()) {
            options.forEach(o -> codes.add(o.getScripCode()));
        }
        return codes;
    }

    /**
     * Get call options
     */
    public List<OptionInfo> getCallOptions() {
        if (options == null) return java.util.Collections.emptyList();
        return options.stream()
            .filter(o -> "CE".equalsIgnoreCase(o.getOptionType()))
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Get put options
     */
    public List<OptionInfo> getPutOptions() {
        if (options == null) return java.util.Collections.emptyList();
        return options.stream()
            .filter(o -> "PE".equalsIgnoreCase(o.getOptionType()))
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Check if this family is fresh (updated within last 24 hours)
     */
    public boolean isFresh() {
        return System.currentTimeMillis() - lastUpdated < 24 * 60 * 60 * 1000;
    }
}
