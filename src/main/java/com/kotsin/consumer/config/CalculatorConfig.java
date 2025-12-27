package com.kotsin.consumer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Centralized configuration for all calculators and algorithms
 * FIXED: Externalized all magic numbers and thresholds
 */
@Configuration
@ConfigurationProperties(prefix = "calculator")
@Data
public class CalculatorConfig {

    /**
     * VPIN (Volume-Synchronized Probability of Informed Trading) configuration
     */
    private VpinConfig vpin = new VpinConfig();

    /**
     * OI Signal Detection configuration
     */
    private OiSignalConfig oiSignal = new OiSignalConfig();

    /**
     * Futures Buildup Detection configuration
     */
    private FuturesBuildupConfig futuresBuildup = new FuturesBuildupConfig();

    /**
     * Orderbook Microstructure configuration
     */
    private OrderbookConfig orderbook = new OrderbookConfig();

    /**
     * Imbalance Bars configuration
     */
    private ImbalanceBarConfig imbalanceBar = new ImbalanceBarConfig();

    @Data
    public static class VpinConfig {
        /**
         * Number of buckets to create per trading day
         */
        private int bucketsPerDay = 50;

        /**
         * Maximum number of buckets to keep in rolling window
         */
        private int maxBuckets = 50;

        /**
         * Minimum bucket size for low-volume instruments
         */
        private double minBucketSize = 100.0;

        /**
         * EWMA alpha for adaptive bucket sizing
         */
        private double ewmaAlpha = 0.05;
    }

    @Data
    public static class OiSignalConfig {
        /**
         * Minimum price change percentage to consider (e.g., 0.1%)
         */
        private double priceThreshold = 0.1;

        /**
         * Minimum OI change percentage for futures (e.g., 2%)
         */
        private double oiThreshold = 2.0;

        /**
         * Minimum absolute OI change for options
         */
        private long optionOiMinChange = 1000L;
    }

    @Data
    public static class FuturesBuildupConfig {
        /**
         * Price change threshold for buildup detection (%)
         */
        private double priceChangeThreshold = 0.1;

        /**
         * OI change threshold for buildup detection (%)
         */
        private double oiChangeThreshold = 1.0;
    }

    @Data
    public static class OrderbookConfig {
        /**
         * Rolling window size for Kyle's Lambda calculation
         */
        private int lambdaWindowSize = 100;

        /**
         * Recalculate lambda every N updates
         */
        private int lambdaCalcFrequency = 20;

        /**
         * Minimum observations required for lambda calculation
         */
        private int lambdaMinObservations = 10;

        /**
         * Iceberg detection - history size
         */
        private int icebergHistorySize = 20;

        /**
         * Iceberg detection - coefficient of variation threshold
         */
        private double icebergCvThreshold = 0.1;

        /**
         * Iceberg detection - minimum size
         */
        private int icebergMinSize = 1000;

        /**
         * Spoofing detection - duration threshold (ms)
         */
        private long spoofDurationThresholdMs = 5000L;

        /**
         * Spoofing detection - size threshold (relative to avg)
         */
        private double spoofSizeThreshold = 0.3;
    }

    @Data
    public static class ImbalanceBarConfig {
        /**
         * EWMA alpha for imbalance bar thresholds
         */
        private double ewmaAlpha = 0.1;

        /**
         * Initial expected volume imbalance
         */
        private double initVolumeImbalance = 1000.0;

        /**
         * Initial expected dollar imbalance
         */
        private double initDollarImbalance = 100000.0;

        /**
         * Initial expected tick runs
         */
        private double initTickRuns = 10.0;

        /**
         * Initial expected volume runs
         */
        private double initVolumeRuns = 5000.0;

        /**
         * Z-score threshold for imbalance bars
         */
        private double zScoreThreshold = 1.645;
    }
}
