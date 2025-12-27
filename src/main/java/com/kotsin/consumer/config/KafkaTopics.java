package com.kotsin.consumer.config;

/**
 * KafkaTopics - Centralized Kafka topic name constants
 * 
 * All topic names should be defined here to avoid scattered string literals.
 */
public final class KafkaTopics {

    private KafkaTopics() {} // Prevent instantiation

    // ========== Candle Topics ==========
    public static final String CANDLE_1M = "candle-ohlcv-1m";
    public static final String CANDLE_2M = "candle-ohlcv-2m";
    public static final String CANDLE_3M = "candle-ohlcv-3m";
    public static final String CANDLE_5M = "candle-ohlcv-5m";
    public static final String CANDLE_15M = "candle-ohlcv-15m";
    public static final String CANDLE_30M = "candle-ohlcv-30m";
    public static final String CANDLE_2H = "candle-ohlcv-2h";
    public static final String CANDLE_1D = "candle-ohlcv-1d";

    // ========== Unified Candle Topics ==========
    public static final String UNIFIED_1M = "unified-candle-1m";
    public static final String UNIFIED_2M = "unified-candle-2m";
    public static final String UNIFIED_3M = "unified-candle-3m";
    public static final String UNIFIED_5M = "unified-candle-5m";
    public static final String UNIFIED_15M = "unified-candle-15m";
    public static final String UNIFIED_30M = "unified-candle-30m";

    // ========== IPU Topics ==========
    public static final String IPU_5M = "ipu-signals-5m";
    public static final String IPU_15M = "ipu-signals-15m";
    public static final String IPU_30M = "ipu-signals-30m";
    public static final String IPU_COMBINED = "ipu-combined";

    // ========== VCP Topics ==========
    public static final String VCP_5M = "vcp-output-5m";
    public static final String VCP_15M = "vcp-output-15m";
    public static final String VCP_30M = "vcp-output-30m";
    public static final String VCP_COMBINED = "vcp-combined";

    // ========== Regime Topics (Module 1-3) ==========
    public static final String REGIME_INDEX = "regime-index-output";
    public static final String REGIME_SECURITY = "regime-security-output";
    public static final String REGIME_ACL = "regime-acl-output";

    // ========== Signal Topics (Module 5-10) ==========
    public static final String FUDKII_OUTPUT = "fudkii-output";
    public static final String CSS_OUTPUT = "css-output";
    public static final String SOM_OUTPUT = "som-output";
    public static final String VTD_OUTPUT = "vtd-output";
    public static final String OHM_OUTPUT = "ohm-output";

    // ========== Capital Topics (Module 13-16) ==========
    public static final String CG_OUTPUT = "cg-output";
    public static final String MAGNITUDE_FINAL = "magnitude-final";
    public static final String WATCHLIST_RANKED = "watchlist-ranked";

    // ========== Trading Signal Topics ==========
    public static final String TRADING_SIGNALS = "trading-signals";

    // ========== NEW: Instrument Candle Topics ==========
    public static final String INSTRUMENT_CANDLE_1M = "instrument-candle-1m";

    // ========== NEW: Family Candle Topics (Unified Architecture) ==========
    public static final String FAMILY_CANDLE_1M = "family-candle-1m";
    public static final String FAMILY_CANDLE_2M = "family-candle-2m";
    public static final String FAMILY_CANDLE_3M = "family-candle-3m";
    public static final String FAMILY_CANDLE_5M = "family-candle-5m";
    public static final String FAMILY_CANDLE_15M = "family-candle-15m";
    public static final String FAMILY_CANDLE_30M = "family-candle-30m";
    public static final String FAMILY_CANDLE_1H = "family-candle-1h";
    public static final String FAMILY_CANDLE_2H = "family-candle-2h";
    public static final String FAMILY_CANDLE_4H = "family-candle-4h";
    public static final String FAMILY_CANDLE_1D = "family-candle-1d";
    public static final String FAMILY_CANDLE_1WK = "family-candle-1wk";
    public static final String FAMILY_CANDLE_1MO = "family-candle-1mo";

    // ========== State Store Names ==========
    public static final String STORE_FMA_HISTORY = "fma-candle-history";
    public static final String STORE_FUDKII_HISTORY = "fudkii-candle-history";
    public static final String STORE_REGIME_SECURITY_HISTORY = "regime-security-history";
    public static final String STORE_REGIME_ACL_HISTORY = "regime-acl-history";
    public static final String STORE_CSS_HISTORY = "css-candle-history";
    public static final String STORE_SOM_HISTORY = "som-candle-history";
    public static final String STORE_VTD_HISTORY = "vtd-candle-history";
    public static final String STORE_SIGNAL_HISTORY = "signal-candle-history";
}
