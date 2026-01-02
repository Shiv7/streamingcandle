package com.kotsin.consumer.domain.model;

/**
 * InstrumentType - Classification based on exchange type and data availability.
 *
 * NSE Exchange Types:
 * - C = Cash (Equity)
 * - D = Derivative
 * 
 * Data Availability:
 * - INDEX: OHLCV only, no orderbook, no OI
 * - EQUITY: OHLCV + Orderbook, no OI
 * - FUTURE/OPTIONS: OHLCV + Orderbook + OI
 */
public enum InstrumentType {
    /**
     * Index instruments (NIFTY50, BANKNIFTY, etc.)
     * Data: OHLCV only
     */
    INDEX,

    /**
     * Cash equity (ExchType = C)
     * Data: OHLCV + Orderbook
     * Example: RELIANCE, TCS
     */
    EQUITY,

    /**
     * Futures contract (ExchType = D, name contains FUT)
     * Data: OHLCV + Orderbook + OI
     * Example: RELIANCE 30 DEC 2025
     */
    FUTURE,

    /**
     * Call option (ExchType = D, name contains CE)
     * Data: OHLCV + Orderbook + OI
     * Example: RELIANCE 30 DEC 2025 CE 1280.00
     */
    OPTION_CE,

    /**
     * Put option (ExchType = D, name contains PE)
     * Data: OHLCV + Orderbook + OI
     * Example: RELIANCE 30 DEC 2025 PE 1280.00
     */
    OPTION_PE;

    /**
     * Detect instrument type from exchange, exchangeType, and companyName
     *
     * @param exchange Exchange code (N for NSE, M for MCX)
     * @param exchType Exchange type (C for Cash, D for Derivative)
     * @param companyName Full instrument name
     * @return InstrumentType
     */
    public static InstrumentType detect(String exchange, String exchType, String companyName) {
        if (companyName == null) {
            return EQUITY;  // Default
        }

        String upperName = companyName.toUpperCase();

        // CRITICAL FIX: Check derivatives FIRST before index detection!
        // Otherwise "NIFTY 27 JAN 2026 CE 26150.00" would be detected as INDEX
        // because isIndex() checks if name contains "NIFTY"
        
        // Derivatives (ExchType = D) - check FIRST
        if ("D".equalsIgnoreCase(exchType)) {
            if (upperName.contains(" CE ") || upperName.endsWith("CE") || 
                upperName.contains("-CE-") || upperName.contains("CE ")) {
                return OPTION_CE;
            }
            if (upperName.contains(" PE ") || upperName.endsWith("PE") || 
                upperName.contains("-PE-") || upperName.contains("PE ")) {
                return OPTION_PE;
            }
            // Default derivative is future
            return FUTURE;
        }

        // Now check for index instruments (ExchType = C)
        if (isIndex(upperName)) {
            return INDEX;
        }

        // Cash segment (ExchType = C)
        return EQUITY;
    }

    /**
     * Legacy method for backward compatibility
     */
    public static InstrumentType fromExchange(String exchType, String fullName) {
        return detect(null, exchType, fullName);
    }

    /**
     * Check if instrument is an index
     */
    private static boolean isIndex(String upperName) {
        return upperName.contains("NIFTY") 
            || upperName.equals("NIFTY 50")
            || upperName.contains("BANKNIFTY")
            || upperName.contains("FINNIFTY")
            || upperName.contains("MIDCPNIFTY")
            || upperName.equals("SENSEX")
            || upperName.equals("BANKEX");
    }

    /**
     * Check if this instrument type typically has orderbook data available.
     * INDEX instruments don't have orderbook data.
     */
    public boolean hasOrderbook() {
        return this != INDEX;
    }

    /**
     * Check if this instrument type typically has Open Interest data available.
     * Only derivatives (futures and options) have OI data.
     */
    public boolean hasOI() {
        return this == FUTURE || this == OPTION_CE || this == OPTION_PE;
    }

    /**
     * Check if this instrument is a derivative.
     */
    public boolean isDerivative() {
        return this == FUTURE || this == OPTION_CE || this == OPTION_PE;
    }

    /**
     * Check if this instrument is an option.
     */
    public boolean isOption() {
        return this == OPTION_CE || this == OPTION_PE;
    }
}
