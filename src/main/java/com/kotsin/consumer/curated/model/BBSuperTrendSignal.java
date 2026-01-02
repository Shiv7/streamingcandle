package com.kotsin.consumer.curated.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 * BBSuperTrendSignal - Signal emitted when Bollinger Band breakout 
 * AND SuperTrend signal occur together
 * 
 * Topic: bb-supertrend-signals
 * 
 * This is a high-confluence signal as both indicators agreeing 
 * provides stronger confirmation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BBSuperTrendSignal {

    // ========== Metadata ==========
    private String scripCode;
    private String companyName;
    private String timeframe;
    private long timestamp;
    
    // ========== Signal Direction ==========
    private String direction;  // "BULLISH" or "BEARISH"
    private double signalStrength;  // 0-1 score
    
    // ========== Bollinger Band Data ==========
    private double bbUpper;
    private double bbMiddle;  // 20 SMA
    private double bbLower;
    private double bbWidth;  // (upper - lower) / middle
    private double bbPercentB;  // (close - lower) / (upper - lower)
    private boolean bbBreakoutUp;   // close > upper
    private boolean bbBreakoutDown; // close < lower
    
    // ========== SuperTrend Data ==========
    private double superTrend;
    private double atr;
    private boolean superTrendBullish;  // price > superTrend
    private boolean superTrendFlipped;  // direction just changed
    
    // ========== Price Context ==========
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
    private double volumeZScore;  // vs 20-period average
    
    // ========== Confluence Score ==========
    private double confluenceScore;  // Combined BB + ST + Volume
    private String confluenceReason;
    
    /**
     * Check if both BB breakout and SuperTrend agree on BULLISH
     */
    public boolean isBullishConfluence() {
        return bbBreakoutUp && superTrendBullish;
    }
    
    /**
     * Check if both BB breakout and SuperTrend agree on BEARISH
     */
    public boolean isBearishConfluence() {
        return bbBreakoutDown && !superTrendBullish;
    }
    
    /**
     * Check if this is a valid confluence signal
     */
    public boolean isValid() {
        return isBullishConfluence() || isBearishConfluence();
    }

    // ========== Kafka Serde ==========
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    public static Serde<BBSuperTrendSignal> serde() {
        return Serdes.serdeFrom(new BBSuperTrendSignalSerializer(), new BBSuperTrendSignalDeserializer());
    }

    public static class BBSuperTrendSignalSerializer implements Serializer<BBSuperTrendSignal> {
        @Override
        public byte[] serialize(String topic, BBSuperTrendSignal data) {
            if (data == null) return null;
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for BBSuperTrendSignal", e);
            }
        }
    }

    public static class BBSuperTrendSignalDeserializer implements Deserializer<BBSuperTrendSignal> {
        @Override
        public BBSuperTrendSignal deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return MAPPER.readValue(bytes, BBSuperTrendSignal.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for BBSuperTrendSignal", e);
            }
        }
    }
}


