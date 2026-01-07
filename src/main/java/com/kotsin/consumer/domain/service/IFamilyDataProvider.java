package com.kotsin.consumer.domain.service;

import com.kotsin.consumer.domain.model.InstrumentFamily;

/**
 * IFamilyDataProvider - Interface for family relationship data providers
 *
 * FIXED: Dependency Inversion Principle compliance
 *
 * Abstracts the source of instrument family data, allowing:
 * - API-based providers (ScripFinderClient)
 * - Database-based providers
 * - Mock providers for testing
 * - Cached providers
 *
 * This interface allows high-level modules (processors) to depend on
 * abstractions rather than concrete implementations.
 */
public interface IFamilyDataProvider {

    /**
     * Get instrument family for an equity scrip code
     *
     * @param equityScripCode Equity scrip code (e.g., "14154")
     * @param closePrice Current close price for ATM calculation
     * @return InstrumentFamily with future and options, or null if not found
     * @throws IllegalArgumentException if inputs are invalid
     */
    InstrumentFamily getFamily(String equityScripCode, double closePrice);

    /**
     * Get equity scrip code from any instrument in the family
     * (Reverse lookup: derivative → equity)
     *
     * @param scripCode Any scrip code (equity, future, or option)
     * @return Equity scrip code, or the same scripCode if not found
     */
    String getEquityScripCode(String scripCode);

    /**
     * Cache a family relationship for future lookups
     *
     * @param family InstrumentFamily to cache
     */
    void cacheFamily(InstrumentFamily family);

    /**
     * Clear all cached data
     */
    void clearCache();

    /**
     * Check if a family is cached and fresh
     *
     * @param equityScripCode Equity scrip code
     * @return true if cached and within TTL
     */
    boolean isFamilyCached(String equityScripCode);

    /**
     * 🛡️ CRITICAL FIX: Symbol to ScripCode Lookup
     *
     * Find equity scripCode by symbol name (reverse lookup: symbol → scripCode)
     * Essential for mapping options to their equity family when option arrives
     * before equity in the stream.
     *
     * Examples:
     * - "RELIANCE" → "N:C:738"
     * - "BANKNIFTY" → "N:D:26009"
     * - "TCS" → "N:C:3456"
     *
     * @param symbol Symbol name (case-insensitive)
     * @return Equity scripCode, or null if not found
     */
    String findEquityBySymbol(String symbol);
}
