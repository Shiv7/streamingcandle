package com.kotsin.consumer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Centralized configuration for instrument metadata and defaults
 * FIXED: Externalized all hardcoded instrument-specific values
 */
@Configuration
@ConfigurationProperties(prefix = "instrument")
@Data
public class InstrumentConfig {

    /**
     * Average daily volume defaults by instrument type
     */
    private VolumeDefaults volume = new VolumeDefaults();

    /**
     * Tick size defaults by exchange type
     */
    private TickSizeDefaults tickSize = new TickSizeDefaults();

    /**
     * Trade classification thresholds
     */
    private TradeClassification tradeClassification = new TradeClassification();

    @Data
    public static class VolumeDefaults {
        /**
         * Default average daily volume for equity instruments
         */
        private double equity = 1_000_000.0;

        /**
         * Default average daily volume for derivative instruments
         */
        private double derivatives = 5_000_000.0;

        /**
         * Default average daily volume for index instruments
         */
        private double index = 10_000_000.0;
    }

    @Data
    public static class TickSizeDefaults {
        /**
         * Default tick size for cash market (equity)
         */
        private double cash = 0.05;

        /**
         * Default tick size for derivatives (futures/options)
         */
        private double derivatives = 0.05;
    }

    @Data
    public static class TradeClassification {
        /**
         * Minimum absolute threshold for trade classification
         */
        private double minAbsolute = 0.01;

        /**
         * Basis points threshold for trade classification
         */
        private double basisPoints = 0.0001;

        /**
         * Spread multiplier for trade classification
         */
        private double spreadMultiplier = 0.15;
    }
}
