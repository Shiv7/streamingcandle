package com.kotsin.consumer.processor.service;

import com.kotsin.consumer.model.InstrumentFamily;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.service.MongoInstrumentFamilyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service for resolving instrument keys (derivatives to underlying equity)
 * Single Responsibility: Key resolution logic
 *
 * Spring Best Practice: @Service annotation for business logic
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InstrumentKeyResolver {

    private final MongoInstrumentFamilyService cacheService;

    /**
     * Get scripCode for per-INSTRUMENT keying (Stream 1)
     * Each instrument gets its own separate key - NO MAPPING
     */
    public String getInstrumentKey(TickData tick) {
        if (tick == null) {
            return null;
        }
        // Use the instrument's OWN scripCode - each instrument is separate
        return tick.getScripCode();
    }

    /**
     * Get underlying equity scripCode for FAMILY-LEVEL aggregation (Stream 2)
     * Maps derivatives to underlying equity, handles indices correctly
     */
    public String getFamilyKey(TickData tick) {
        if (tick == null) {
            return null;
        }

        // For indices, return the index scripCode itself as the family key
        if (isIndex(tick.getScripCode())) {
            log.debug("📍 Index {} used as its own family key", tick.getScripCode());
            return tick.getScripCode();
        }

        // If it's a derivative, resolve to underlying equity
        if ("D".equalsIgnoreCase(tick.getExchangeType())) {
            InstrumentFamily family = cacheService.resolveFamily(
                tick.getScripCode(),
                tick.getExchangeType(),
                tick.getCompanyName()
            );

            if (family != null && family.getEquityScripCode() != null) {
                log.debug("📍 Mapped derivative {} to family key {}",
                    tick.getScripCode(), family.getEquityScripCode());
                return family.getEquityScripCode();
            }
        }

        // For equities or if resolution fails, use the tick's own scripCode
        return tick.getScripCode();
    }

    /**
     * Check if a scripCode represents an index
     * Indices typically start with 99992 (e.g., NIFTY, BANKNIFTY)
     */
    public boolean isIndex(String scripCode) {
        if (scripCode == null || scripCode.isEmpty()) {
            return false;
        }
        // Common patterns for indices:
        // - 99992xxxx for NSE indices (NIFTY, BANKNIFTY, FINNIFTY, etc.)
        // - Can be extended based on actual index scripCode patterns
        return scripCode.startsWith("99992");
    }

    /**
     * Determine instrument type from tick data
     */
    public String getInstrumentType(TickData tick) {
        if (tick == null) {
            return "UNKNOWN";
        }

        // Check if index
        if (isIndex(tick.getScripCode())) {
            return "INDEX";
        }

        // Check exchange type
        if ("D".equalsIgnoreCase(tick.getExchangeType())) {
            // Derivative - need to determine if future or option
            // Typically, options have strike price in company name
            String companyName = tick.getCompanyName();
            if (companyName != null) {
                if (companyName.contains(" CE ") || companyName.contains(" PE ")) {
                    return "OPTION";
                } else if (companyName.contains(" FUT") || companyName.matches(".*\\d{2}\\s+[A-Z]{3}\\s+\\d{4}.*")) {
                    return "FUTURE";
                }
            }
            return "DERIVATIVE";  // Generic if can't determine
        }

        // Default to equity
        return "EQUITY";
    }

    /**
     * DEPRECATED: Use getFamilyKey() instead
     * Kept for backward compatibility
     */
    @Deprecated
    public String getUnderlyingEquityScripCode(TickData tick) {
        return getFamilyKey(tick);
    }
}
