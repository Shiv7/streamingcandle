package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
 * Aggregated Open Interest data with microstructure/quant features.
 * Computed over time windows (1m, 2m, 3m, 5m, 15m, 30m).
 * 
 * Quant Features:
 * - OI Delta: Change in OI (positive = net long buildup, negative = net short buildup or unwinding)
 * - OI Momentum: Rate of change in OI
 * - OI Velocity: OI change per unit volume (divergence indicator)
 * - OI Concentration: Ratio of OI at window end vs start (buildup intensity)
 * - Cumulative OI Change: Total OI change across the window
 * 
 * Trading Signals:
 * - Rising OI + Rising Price = Bullish (long buildup)
 * - Rising OI + Falling Price = Bearish (short buildup)
 * - Falling OI + Rising Price = Short covering
 * - Falling OI + Falling Price = Long unwinding
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenInterestAggregation {

    @JsonProperty("token")
    private int token;

    @JsonProperty("companyName")
    private String companyName;

    @JsonProperty("exchange")
    private String exchange;

    @JsonProperty("exchangeType")
    private String exchangeType;

    @JsonProperty("windowStartTime")
    private long windowStartTime;

    @JsonProperty("windowEndTime")
    private long windowEndTime;

    @JsonProperty("windowSizeMinutes")
    private int windowSizeMinutes;

    // Core OI metrics
    @JsonProperty("openInterestStart")
    private long openInterestStart;

    @JsonProperty("openInterestEnd")
    private long openInterestEnd;

    @JsonProperty("openInterestHigh")
    private long openInterestHigh;

    @JsonProperty("openInterestLow")
    private long openInterestLow;

    @JsonProperty("oiChangeAbsolute")
    private long oiChangeAbsolute;  // OI Delta

    @JsonProperty("oiChangePercent")
    private double oiChangePercent;

    // Advanced quant features
    @JsonProperty("oiMomentum")
    private double oiMomentum;  // OI change per minute

    @JsonProperty("oiConcentration")
    private double oiConcentration;  // End/Start ratio (buildup intensity)

    @JsonProperty("cumulativeOiChange")
    private long cumulativeOiChange;  // Sum of all OI changes in window

    @JsonProperty("oiVolatility")
    private double oiVolatility;  // Standard deviation of OI changes

    @JsonProperty("updateCount")
    private int updateCount;  // Number of OI updates in window

    @JsonProperty("avgOiPerUpdate")
    private double avgOiPerUpdate;

    // For correlation with price/volume (to be enriched by downstream services)
    @JsonProperty("lastReceivedTimestamp")
    private long lastReceivedTimestamp;

    /**
     * Provides Kafka Serde for OpenInterestAggregation.
     */
    public static Serde<OpenInterestAggregation> serde() {
        return Serdes.serdeFrom(new OpenInterestAggregationSerializer(), new OpenInterestAggregationDeserializer());
    }

    // ---------------------------------------------------
    // Internal Serializer/Deserializer
    // ---------------------------------------------------
    public static class OpenInterestAggregationSerializer implements Serializer<OpenInterestAggregation> {
        private final ObjectMapper objectMapper = new ObjectMapper();
        @Override
        public byte[] serialize(String topic, OpenInterestAggregation data) {
            if (data == null) return null;
            try {
                return objectMapper.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for OpenInterestAggregation", e);
            }
        }
    }

    public static class OpenInterestAggregationDeserializer implements Deserializer<OpenInterestAggregation> {
        private final ObjectMapper objectMapper = new ObjectMapper();
        @Override
        public OpenInterestAggregation deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return objectMapper.readValue(bytes, OpenInterestAggregation.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for OpenInterestAggregation", e);
            }
        }
    }
}

