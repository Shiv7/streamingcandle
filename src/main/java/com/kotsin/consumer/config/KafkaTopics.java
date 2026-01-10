package com.kotsin.consumer.config;

/**
 * KafkaTopics - Centralized Kafka topic name constants
 * 
 * All topic names should be defined here to avoid scattered string literals.
 */
public final class KafkaTopics {

    private KafkaTopics() {} // Prevent instantiation

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
    /** @deprecated Use {@link #TRADING_SIGNALS_V2} - unified signal topic for all signal types */
    @Deprecated
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

    // ========== NEW: MTIS Family Score Topic ==========
    public static final String FAMILY_SCORE = "family-score";

    // ========== NEW: Master Architecture Topics ==========
    // Context Foundation (Part 1)
    public static final String MASTER_ARCH_INDEX_REGIME = "masterarch-index-regime";
    public static final String MASTER_ARCH_SECURITY_REGIME = "masterarch-security-regime";
    
    // Signal Generation (Part 2)
    public static final String MASTER_ARCH_FUDKII = "masterarch-fudkii-output";
    public static final String MASTER_ARCH_VOLUME = "masterarch-volume-output";
    public static final String MASTER_ARCH_VELOCITY = "masterarch-velocity-output";
    
    // Signal Validation (Part 3)
    public static final String MASTER_ARCH_STRUCTURAL = "masterarch-structural-validation";
    public static final String MASTER_ARCH_BEHAVIOURAL = "masterarch-behavioural-validation";
    public static final String MASTER_ARCH_CORRELATION = "masterarch-correlation-governor";
    
    // Final Score & Trade Construction (Part 4)
    public static final String KOTSIN_FF1 = "kotsin_FF1";
    public static final String KOTSIN_FUDKII = "kotsin_FUDKII";  // Standalone FUDKII strategy
    public static final String TRADE_POSITION_SIZE = "trade-position-size";

    // ========== NEW: Quant Score Topics ==========
    public static final String QUANT_SCORES = "quant-scores";
    /** @deprecated Use {@link #TRADING_SIGNALS_V2} - unified signal topic for all signal types */
    @Deprecated
    public static final String QUANT_TRADING_SIGNALS = "quant-trading-signals";

    // ========== NEW: Enrichment Pipeline Topics (SMTIS v2.0) ==========
    // Signal topics - UNIFIED: All signal types (pattern, quant, enrichment) publish here
    public static final String TRADING_SIGNALS_V2 = "trading-signals-v2";
    public static final String TRADING_SIGNALS_HIGH_PRIORITY = "trading-signals-high-priority";
    public static final String TRADING_SIGNALS_ALERTS = "trading-signals-alerts";

    // Pattern topics
    public static final String PATTERN_SIGNALS = "pattern-signals";
    public static final String PATTERN_OUTCOMES = "pattern-outcomes";

    // Outcome topics
    public static final String TRADE_OUTCOMES = "trade-outcomes";
    public static final String PAPER_TRADE_OUTCOMES = "paper-trade-outcomes";
    public static final String SIGNAL_EXPIRATIONS = "signal-expirations";

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
