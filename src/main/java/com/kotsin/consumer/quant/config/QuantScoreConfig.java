package com.kotsin.consumer.quant.config;

import com.kotsin.consumer.config.KafkaTopics;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for QuantScore calculation and signal emission.
 *
 * Note: All trading signals now publish to unified 'trading-signals-v2' topic
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "quant")
public class QuantScoreConfig {

    // ========== OUTPUT TOPICS ==========
    // Unified signal topic - all signals go to trading-signals-v2
    private String outputTopic = KafkaTopics.TRADING_SIGNALS_V2;
    private String scoresTopic = KafkaTopics.QUANT_SCORES;

    // ========== EMISSION THRESHOLDS ==========
    private Emission emission = new Emission();

    @Data
    public static class Emission {
        private double minScore = 65.0;
        private double minConfidence = 0.6;
        private int cooldownSeconds = 300;
        private int minCategoriesAboveThreshold = 4;
        private double categoryThresholdPercent = 60.0;
    }

    // ========== SCORE WEIGHTS (Total: 100) ==========
    private Weights weight = new Weights();

    @Data
    public static class Weights {
        private double greeks = 15.0;
        private double ivSurface = 12.0;
        private double microstructure = 18.0;
        private double optionsFlow = 15.0;
        private double priceAction = 12.0;
        private double volumeProfile = 8.0;
        private double crossInstrument = 10.0;
        private double confluence = 10.0;

        public double getTotal() {
            return greeks + ivSurface + microstructure + optionsFlow +
                   priceAction + volumeProfile + crossInstrument + confluence;
        }
    }

    // ========== GREEKS THRESHOLDS ==========
    private Greeks greeks = new Greeks();

    @Data
    public static class Greeks {
        private double deltaSignificantThreshold = 5000.0;
        private double gammaSqueezeDistancePercent = 2.0;
        private double gammaConcentrationThreshold = 0.6;
        private double vegaSignificantThreshold = 50000.0;
    }

    // ========== IV SURFACE THRESHOLDS ==========
    private IV iv = new IV();

    @Data
    public static class IV {
        private double rankHighThreshold = 70.0;
        private double rankLowThreshold = 30.0;
        private double extremeSkewThreshold = 5.0;
        private double crushRiskDte = 3;
    }

    // ========== MICROSTRUCTURE THRESHOLDS ==========
    private Microstructure microstructure = new Microstructure();

    @Data
    public static class Microstructure {
        private double ofiSignificantThreshold = 10000.0;
        private double vpinHighThreshold = 0.7;
        private double vpinMediumThreshold = 0.5;
        private double depthImbalanceSignificant = 0.3;
        private double kyleLambdaLiquidThreshold = 0.001;
    }

    // ========== OPTIONS FLOW THRESHOLDS ==========
    private OptionsFlow optionsFlow = new OptionsFlow();

    @Data
    public static class OptionsFlow {
        private double pcrBullishThreshold = 0.7;
        private double pcrBearishThreshold = 1.3;
        private double pcrExtremeFear = 1.5;
        private double pcrExtremeGreed = 0.5;
    }

    // ========== PRICE ACTION THRESHOLDS ==========
    private PriceAction priceAction = new PriceAction();

    @Data
    public static class PriceAction {
        private double momentumSlopeStrong = 0.3;
        private double momentumSlopeModerate = 0.1;
    }

    // ========== VOLUME PROFILE THRESHOLDS ==========
    private VolumeProfile volumeProfile = new VolumeProfile();

    @Data
    public static class VolumeProfile {
        private double pocMigrationSignificant = 0.5;
    }

    // ========== CROSS-INSTRUMENT THRESHOLDS ==========
    private CrossInstrument crossInstrument = new CrossInstrument();

    @Data
    public static class CrossInstrument {
        private double premiumSignificantPercent = 0.5;
    }

    // ========== REGIME MODIFIERS ==========
    private Regime regime = new Regime();

    @Data
    public static class Regime {
        private double bullishModifier = 1.2;
        private double bearishModifier = 1.2;
        private double neutralModifier = 1.0;
        private double volatileModifier = 0.8;
        private double minModifier = 0.7;
        private double maxModifier = 1.3;
    }

    // ========== POSITION SIZING ==========
    private Sizing sizing = new Sizing();

    @Data
    public static class Sizing {
        private double defaultRiskPercent = 1.0;
        private double maxRiskPercent = 2.0;
        private double minRiskRewardRatio = 2.0;
        private double highConvictionMultiplier = 1.5;
        private double lowConvictionMultiplier = 0.5;
    }

    // ========== CACHE SETTINGS ==========
    private Cache cache = new Cache();

    @Data
    public static class Cache {
        private int ttlSeconds = 30;
        private int maxSize = 1000;
    }
}
