package com.kotsin.consumer.config;

import java.time.Duration;

/**
 * Central constants for market data processing
 * 
 * BEST PRACTICE: Extract magic numbers to named constants
 * MAINTAINABILITY: Single source of truth for configuration values
 */
public final class ProcessingConstants {

    private ProcessingConstants() {
        throw new UnsupportedOperationException("Constants class");
    }

    // ========== TIMEFRAME CONSTANTS ==========
    
    public static final Duration TIMEFRAME_1M = Duration.ofMinutes(1);
    public static final Duration TIMEFRAME_2M = Duration.ofMinutes(2);
    public static final Duration TIMEFRAME_3M = Duration.ofMinutes(3);
    public static final Duration TIMEFRAME_5M = Duration.ofMinutes(5);
    public static final Duration TIMEFRAME_15M = Duration.ofMinutes(15);
    public static final Duration TIMEFRAME_30M = Duration.ofMinutes(30);
    
    public static final Duration WINDOW_GRACE_PERIOD = Duration.ofSeconds(10);
    public static final Duration HALT_TIMEOUT = Duration.ofMinutes(20);
    
    // ========== TRADING HOURS CONSTANTS ==========
    
    public static final int TRADING_HOURS_BUFFER_MINUTES = 15;
    public static final int SATURDAY_DAY_OF_WEEK = 6;
    public static final int SUNDAY_DAY_OF_WEEK = 7;
    public static final int MIN_TRADING_HOUR = 8;
    public static final int MAX_TRADING_HOUR = 17;
    
    // ========== MICROSTRUCTURE CONSTANTS ==========
    
    public static final int MIN_OBSERVATIONS = 10;
    public static final int VPIN_BUCKET_SIZE = 50;
    public static final int ORDERBOOK_HISTORY_SIZE = 20;
    public static final int PRICE_CHANGES_HISTORY_SIZE = 50;
    public static final int SIGNED_VOLUMES_HISTORY_SIZE = 50;
    
    // ========== FAMILY AGGREGATION CONSTANTS ==========
    
    public static final int MAX_OPTIONS_PER_FAMILY = 4;
    public static final int MAX_FUTURES_PER_FAMILY = 1;
    
    // ========== VALIDATION CONSTANTS ==========
    
    public static final long MAX_TIMESTAMP_DEVIATION_MS = 7L * 24 * 3600 * 1000; // 7 days
    public static final double MIN_VALID_PRICE = 0.01;
    public static final int MIN_VALID_VOLUME = 0;
    public static final int MAX_ORDERBOOK_LEVELS = 20;
    
    // ========== CACHE CONSTANTS ==========
    
    public static final Duration CACHE_TTL = Duration.ofDays(1);
    public static final int CACHE_REFRESH_HOUR = 3; // 3 AM
    
    // ========== KAFKA CONSTANTS ==========
    
    public static final int MAX_POLL_RECORDS = 100;
    public static final int COMMIT_INTERVAL_MS = 1000;
    public static final int SESSION_TIMEOUT_MS = 30000;
    public static final int MAX_POLL_INTERVAL_MS = 300000;
    
    // ========== BACKPRESSURE CONSTANTS ==========
    
    public static final long LAG_THRESHOLD = 1000;
    public static final double THROTTLE_FACTOR = 0.5;
    public static final double MAX_LAG_PERCENTAGE = 0.1; // 10%
    
    // ========== PERFORMANCE CONSTANTS ==========
    
    public static final int THREAD_POOL_SIZE = 4;
    public static final int QUEUE_CAPACITY = 1000;
    public static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);
    
    // ========== RETRY CONSTANTS ==========
    
    public static final int MAX_RETRY_ATTEMPTS = 3;
    public static final long INITIAL_RETRY_DELAY_MS = 100;
    public static final double RETRY_BACKOFF_MULTIPLIER = 2.0;
    public static final long MAX_RETRY_DELAY_MS = 10000;
    
    // ========== TIMEOUT CONSTANTS ==========
    
    public static final Duration DATABASE_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration NETWORK_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration PROCESSING_TIMEOUT = Duration.ofSeconds(30);
    
    // ========== MONITORING CONSTANTS ==========
    
    public static final int METRICS_REPORT_INTERVAL_SECONDS = 60;
    public static final int HEALTH_CHECK_INTERVAL_SECONDS = 30;
    public static final double ERROR_RATE_THRESHOLD = 0.05; // 5%
    
    // ========== ORDERBOOK ANALYTICS CONSTANTS ==========
    
    public static final double ICEBERG_THRESHOLD = 0.3; // 30% hidden volume
    public static final double SPOOFING_THRESHOLD = 0.5; // 50% volume cancelled
    public static final int MIN_SPOOFING_EVENTS = 3;
    public static final Duration SPOOFING_TIME_WINDOW = Duration.ofMinutes(5);
    
    // ========== INDEX PATTERNS ==========
    
    public static final String INDEX_SCRIP_CODE_PREFIX = "99992";
    
    // ========== EXCHANGE CODES ==========
    
    public static final String EXCHANGE_NSE = "NSE";
    public static final String EXCHANGE_MCX = "MCX";
    public static final String EXCHANGE_TYPE_DERIVATIVE = "D";
    
    // ========== OPTION TYPES ==========
    
    public static final String OPTION_TYPE_CALL = "CE";
    public static final String OPTION_TYPE_PUT = "PE";
    
    // ========== LOGGING CONSTANTS ==========
    
    public static final int MAX_LOG_MESSAGE_LENGTH = 1000;
    public static final String LOG_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS";
    
    // ========== VALIDATION THRESHOLDS ==========
    
    public static final double MIN_EFFECTIVE_SPREAD = 0.0;
    public static final double MAX_EFFECTIVE_SPREAD = 1.0;
    public static final double MIN_DEPTH_IMBALANCE = -1.0;
    public static final double MAX_DEPTH_IMBALANCE = 1.0;
    public static final double MIN_OFI = -1000000.0;
    public static final double MAX_OFI = 1000000.0;
    
    // ========== GRACEFUL SHUTDOWN CONSTANTS ==========
    
    public static final Duration SHUTDOWN_GRACE_PERIOD = Duration.ofSeconds(2);
    public static final int SHUTDOWN_STEPS = 5;
}
