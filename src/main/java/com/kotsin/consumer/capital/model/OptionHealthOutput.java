package com.kotsin.consumer.capital.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;

/**
 * OptionHealthOutput - Output from Module 12: Option Health Module
 * 
 * Analyzes options chain health for a security:
 * - IV Percentile (where current IV sits vs history)
 * - OI concentration (max pain levels)
 * - Bid-Ask spreads (liquidity)
 * - PCR (Put-Call Ratio)
 * 
 * Determines if options are suitable for trading.
 * 
 * Emits to topic: ohm-output
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OptionHealthOutput {

    // Identification
    private String scripCode;
    private String companyName;
    private long timestamp;
    
    // Core Quality Score
    private double qualityScore;       // [0,1] - overall options health
    private boolean allowNakedOptions; // Safe for naked option selling
    private boolean allowBuyOptions;   // Safe for option buying
    
    // IV Analysis
    private double ivPercentile;       // 0-100 (where current IV ranks)
    private double ivCurrent;          // Current IV
    private double ivMean;             // Mean IV (historical)
    private IVState ivState;           // Classification
    
    // OI Analysis  
    private double maxPainPrice;       // Max pain level
    private double maxPainDistance;    // Distance from current price
    private double putCallRatio;       // PCR
    private PCRState pcrState;         // Bullish/Bearish/Neutral
    
    // Liquidity Analysis
    private double avgBidAskSpread;    // Average spread as %
    private double liquidityScore;     // 0-1 liquidity rating
    private LiquidityState liquidityState;
    
    // OI Concentration
    private double callOiConcentration; // % of OI at top 3 strikes
    private double putOiConcentration;
    
    // Nearby strikes
    private double atmIV;              // ATM IV
    private double nearestCallStrike;
    private double nearestPutStrike;
    
    /**
     * IV State classification
     */
    public enum IVState {
        VERY_LOW("IV < 20th percentile, buy options", 0.2),
        LOW("IV 20-40th percentile, neutral", 0.4),
        NORMAL("IV 40-60th percentile, balanced", 0.6),
        HIGH("IV 60-80th percentile, sell options", 0.8),
        VERY_HIGH("IV > 80th percentile, premium selling", 1.0);

        private final String recommendation;
        private final double threshold;

        IVState(String recommendation, double threshold) {
            this.recommendation = recommendation;
            this.threshold = threshold;
        }

        public static IVState fromPercentile(double percentile) {
            if (percentile < 20) return VERY_LOW;
            if (percentile < 40) return LOW;
            if (percentile < 60) return NORMAL;
            if (percentile < 80) return HIGH;
            return VERY_HIGH;
        }
    }
    
    /**
     * PCR State classification
     */
    public enum PCRState {
        EXTREMELY_BEARISH("PCR > 1.5, oversold potential"),
        BEARISH("PCR 1.2-1.5, bearish sentiment"),
        NEUTRAL("PCR 0.8-1.2, balanced"),
        BULLISH("PCR 0.5-0.8, bullish sentiment"),
        EXTREMELY_BULLISH("PCR < 0.5, overbought potential");

        private final String description;

        PCRState(String description) {
            this.description = description;
        }

        public static PCRState fromPCR(double pcr) {
            if (pcr > 1.5) return EXTREMELY_BEARISH;
            if (pcr > 1.2) return BEARISH;
            if (pcr > 0.8) return NEUTRAL;
            if (pcr > 0.5) return BULLISH;
            return EXTREMELY_BULLISH;
        }
    }
    
    /**
     * Liquidity State classification
     */
    public enum LiquidityState {
        EXCELLENT("Spread < 0.5%, high volume"),
        GOOD("Spread 0.5-1%, adequate volume"),
        FAIR("Spread 1-2%, tradeable"),
        POOR("Spread 2-5%, wide spreads"),
        ILLIQUID("Spread > 5%, avoid");

        private final String description;

        LiquidityState(String description) {
            this.description = description;
        }

        public static LiquidityState fromSpread(double spreadPct) {
            if (spreadPct < 0.5) return EXCELLENT;
            if (spreadPct < 1.0) return GOOD;
            if (spreadPct < 2.0) return FAIR;
            if (spreadPct < 5.0) return POOR;
            return ILLIQUID;
        }
    }
    
    /**
     * Check if options are tradeable
     */
    public boolean isTradeable() {
        return liquidityState != LiquidityState.ILLIQUID && qualityScore >= 0.4;
    }
    
    /**
     * Get strategy recommendation
     */
    public String getStrategyRecommendation() {
        if (ivState == IVState.VERY_HIGH) {
            return "SELL_OPTIONS";
        } else if (ivState == IVState.VERY_LOW) {
            return "BUY_OPTIONS";
        } else if (pcrState == PCRState.EXTREMELY_BEARISH) {
            return "CONTRARIAN_BULLISH";
        } else if (pcrState == PCRState.EXTREMELY_BULLISH) {
            return "CONTRARIAN_BEARISH";
        }
        return "NEUTRAL";
    }
    
    // ========== Serialization ==========
    
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    public static Serde<OptionHealthOutput> serde() {
        return Serdes.serdeFrom(new OHMSerializer(), new OHMDeserializer());
    }
    
    public static class OHMSerializer implements Serializer<OptionHealthOutput> {
        @Override
        public byte[] serialize(String topic, OptionHealthOutput data) {
            try {
                return data != null ? MAPPER.writeValueAsBytes(data) : null;
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed", e);
            }
        }
    }
    
    public static class OHMDeserializer implements Deserializer<OptionHealthOutput> {
        @Override
        public OptionHealthOutput deserialize(String topic, byte[] bytes) {
            try {
                return bytes != null ? MAPPER.readValue(bytes, OptionHealthOutput.class) : null;
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed", e);
            }
        }
    }
}
