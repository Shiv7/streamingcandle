package com.kotsin.consumer.enrichment.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.LocalTime;
import java.util.Map;
import java.util.Set;

/**
 * CommodityConfig - Dedicated configuration for MCX (commodity) handling
 *
 * Commodities differ from NSE equities in:
 * 1. Trading hours (9:00-23:30 vs 9:15-15:30)
 * 2. Session quality windows (evening prime 18:00-21:00)
 * 3. OI thresholds (lower liquidity = lower absolute thresholds)
 * 4. Expiry cycles (monthly only, no weekly)
 * 5. Price volatility (typically higher than equities)
 *
 * This module centralizes all commodity-specific logic.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "commodity")
public class CommodityConfig {

    // ======================== EXCHANGE IDENTIFICATION ========================

    /**
     * MCX exchange codes
     */
    private Set<String> mcxExchanges = Set.of("M", "MCX", "m", "mcx");

    /**
     * Known MCX commodity families (symbol -> commodity type)
     */
    private Map<String, CommodityType> commodityTypes = Map.of(
            "NATURALGAS", CommodityType.ENERGY,
            "CRUDEOIL", CommodityType.ENERGY,
            "GOLD", CommodityType.PRECIOUS_METAL,
            "SILVER", CommodityType.PRECIOUS_METAL,
            "COPPER", CommodityType.BASE_METAL,
            "ALUMINIUM", CommodityType.BASE_METAL,
            "ZINC", CommodityType.BASE_METAL,
            "LEAD", CommodityType.BASE_METAL,
            "NICKEL", CommodityType.BASE_METAL,
            "COTTON", CommodityType.AGRICULTURAL
    );

    // ======================== SESSION CONFIGURATION ========================

    /**
     * MCX trading sessions
     */
    private MCXSession morningSession = new MCXSession(
            LocalTime.of(9, 0),
            LocalTime.of(17, 0),
            0.7,
            "Track domestic equity markets"
    );

    private MCXSession eveningOpenSession = new MCXSession(
            LocalTime.of(17, 0),
            LocalTime.of(18, 0),
            0.8,
            "International market open"
    );

    private MCXSession eveningPrimeSession = new MCXSession(
            LocalTime.of(18, 0),
            LocalTime.of(21, 0),
            1.0,
            "Max international overlap - PRIME"
    );

    private MCXSession eveningLateSession = new MCXSession(
            LocalTime.of(21, 0),
            LocalTime.of(23, 30),
            0.6,
            "US market tracking"
    );

    // ======================== THRESHOLD CONFIGURATION ========================

    /**
     * OI threshold multipliers by commodity type
     * Lower liquidity = lower thresholds
     */
    private Map<CommodityType, Double> oiThresholdMultipliers = Map.of(
            CommodityType.ENERGY, 0.5,           // Most liquid MCX commodity
            CommodityType.PRECIOUS_METAL, 0.6,   // Gold/Silver fairly liquid
            CommodityType.BASE_METAL, 0.3,       // Lower liquidity
            CommodityType.AGRICULTURAL, 0.2      // Lowest liquidity
    );

    /**
     * Price threshold multipliers
     */
    private Map<CommodityType, Double> priceThresholdMultipliers = Map.of(
            CommodityType.ENERGY, 0.3,           // 0.3% vs 1% for equity
            CommodityType.PRECIOUS_METAL, 0.4,
            CommodityType.BASE_METAL, 0.3,
            CommodityType.AGRICULTURAL, 0.5
    );

    /**
     * Minimum OI change for signal (absolute, per commodity type)
     * Much lower than NSE defaults
     */
    private Map<CommodityType, Long> minOiChangeAbsolute = Map.of(
            CommodityType.ENERGY, 50L,
            CommodityType.PRECIOUS_METAL, 100L,
            CommodityType.BASE_METAL, 30L,
            CommodityType.AGRICULTURAL, 20L
    );

    // ======================== EXPIRY CONFIGURATION ========================

    /**
     * MCX expiry is typically last trading day of month
     * No weekly expiry like NSE
     */
    private int expiryDayOfMonth = -1; // -1 means last day of month

    /**
     * Days before expiry when gamma effects become significant
     */
    private int gammaEffectDays = 5;

    /**
     * OI weight reduction near expiry
     */
    private Map<Integer, Double> expiryOiWeights = Map.of(
            7, 0.8,   // 7 days before: 80% weight
            5, 0.5,   // 5 days before: 50% weight
            3, 0.3,   // 3 days before: 30% weight
            1, 0.1,   // 1 day before: 10% weight
            0, 0.0    // Expiry day: ignore OI
    );

    // ======================== HELPER METHODS ========================

    /**
     * Check if exchange is MCX
     */
    public boolean isMCXExchange(String exchange) {
        return exchange != null && mcxExchanges.contains(exchange.toUpperCase());
    }

    /**
     * Get commodity type for a symbol
     */
    public CommodityType getCommodityType(String symbol) {
        if (symbol == null) return CommodityType.ENERGY; // Default
        return commodityTypes.getOrDefault(symbol.toUpperCase(), CommodityType.ENERGY);
    }

    /**
     * Get current MCX session
     */
    public MCXSession getCurrentSession(LocalTime time) {
        if (isInSession(time, morningSession)) return morningSession;
        if (isInSession(time, eveningOpenSession)) return eveningOpenSession;
        if (isInSession(time, eveningPrimeSession)) return eveningPrimeSession;
        if (isInSession(time, eveningLateSession)) return eveningLateSession;
        return null; // Outside trading hours
    }

    /**
     * Get session quality for current time
     */
    public double getSessionQuality(LocalTime time) {
        MCXSession session = getCurrentSession(time);
        return session != null ? session.quality : 0.0;
    }

    /**
     * Get OI threshold multiplier for a commodity
     */
    public double getOiThresholdMultiplier(String symbol) {
        CommodityType type = getCommodityType(symbol);
        return oiThresholdMultipliers.getOrDefault(type, 0.5);
    }

    /**
     * Get price threshold multiplier for a commodity
     */
    public double getPriceThresholdMultiplier(String symbol) {
        CommodityType type = getCommodityType(symbol);
        return priceThresholdMultipliers.getOrDefault(type, 0.3);
    }

    /**
     * Get minimum absolute OI change for a commodity
     */
    public long getMinOiChange(String symbol) {
        CommodityType type = getCommodityType(symbol);
        return minOiChangeAbsolute.getOrDefault(type, 50L);
    }

    /**
     * Get OI weight based on days to expiry
     */
    public double getOiWeight(int daysToExpiry) {
        if (daysToExpiry > 7) return 1.0;
        return expiryOiWeights.getOrDefault(daysToExpiry, 1.0);
    }

    /**
     * Check if we're in prime trading session
     */
    public boolean isPrimeSession(LocalTime time) {
        MCXSession session = getCurrentSession(time);
        return session != null && session.quality >= 0.9;
    }

    private boolean isInSession(LocalTime time, MCXSession session) {
        return !time.isBefore(session.start) && time.isBefore(session.end);
    }

    // ======================== NESTED CLASSES ========================

    public enum CommodityType {
        ENERGY,
        PRECIOUS_METAL,
        BASE_METAL,
        AGRICULTURAL
    }

    @Data
    public static class MCXSession {
        private LocalTime start;
        private LocalTime end;
        private double quality;
        private String description;

        public MCXSession() {}

        public MCXSession(LocalTime start, LocalTime end, double quality, String description) {
            this.start = start;
            this.end = end;
            this.quality = quality;
            this.description = description;
        }
    }
}
