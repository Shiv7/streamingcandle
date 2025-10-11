package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Information-driven bar (VIB, DIB, TRB, VRB)
 * Based on "Advances in Financial Machine Learning" Chapter 2
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class InformationBar {
    
    @JsonProperty("barType")
    private String barType;  // "VIB", "DIB", "TRB", "VRB"
    
    @JsonProperty("scripCode")
    private String scripCode;
    
    @JsonProperty("token")
    private int token;
    
    @JsonProperty("exchange")
    private String exchange;
    
    @JsonProperty("companyName")
    private String companyName;
    
    // OHLC
    @JsonProperty("open")
    private double open;
    
    @JsonProperty("high")
    private double high;
    
    @JsonProperty("low")
    private double low;
    
    @JsonProperty("close")
    private double close;
    
    // Volume metrics
    @JsonProperty("volume")
    private int volume;
    
    @JsonProperty("buyVolume")
    private int buyVolume;
    
    @JsonProperty("sellVolume")
    private int sellVolume;
    
    @JsonProperty("dollarVolume")
    private double dollarVolume;
    
    @JsonProperty("buyDollarVolume")
    private double buyDollarVolume;
    
    @JsonProperty("sellDollarVolume")
    private double sellDollarVolume;
    
    // Tick counts
    @JsonProperty("tickCount")
    private int tickCount;
    
    @JsonProperty("buyTicks")
    private int buyTicks;
    
    @JsonProperty("sellTicks")
    private int sellTicks;
    
    // Imbalance metrics
    @JsonProperty("imbalance")
    private double imbalance;  // Absolute imbalance that triggered bar
    
    @JsonProperty("expectedImbalance")
    private double expectedImbalance;  // Expected imbalance threshold
    
    @JsonProperty("imbalanceRatio")
    private double imbalanceRatio;  // actual / expected
    
    // Run metrics
    @JsonProperty("runLength")
    private int runLength;  // Length of run that triggered bar
    
    @JsonProperty("runVolume")
    private double runVolume;  // Volume in run that triggered bar
    
    @JsonProperty("expectedRunLength")
    private double expectedRunLength;  // Expected run threshold
    
    @JsonProperty("runDirection")
    private int runDirection;  // 1 = buy run, -1 = sell run
    
    // Timestamps
    @JsonProperty("windowStartMillis")
    private long windowStartMillis;
    
    @JsonProperty("windowEndMillis")
    private long windowEndMillis;
    
    @JsonProperty("timestamp")
    private long timestamp;
    
    /**
     * Update OHLC with new tick
     */
    public void updateOHLC(TickData tick) {
        double price = tick.getLastRate();
        
        if (open == 0) {
            open = price;
        }
        
        if (price > high || high == 0) {
            high = price;
        }
        
        if (price < low || low == 0) {
            low = price;
        }
        
        close = price;
        timestamp = tick.getTimestamp();
    }
    
    /**
     * Add volume from classified tick
     * Uses deltaVolume (computed by CumToDeltaTransformer)
     */
    public void addVolume(TickData tick, int direction) {
        // CRITICAL: Use deltaVolume, not lastQuantity
        Integer deltaVol = tick.getDeltaVolume();
        if (deltaVol == null || deltaVol == 0) {
            return;  // Skip quote updates (no actual trade)
        }
        
        int qty = deltaVol;
        double dollarValue = tick.getLastRate() * qty;
        
        volume += qty;
        dollarVolume += dollarValue;
        tickCount++;
        
        if (direction > 0) {
            buyVolume += qty;
            buyDollarVolume += dollarValue;
            buyTicks++;
        } else if (direction < 0) {
            sellVolume += qty;
            sellDollarVolume += dollarValue;
            sellTicks++;
        }
    }
    
    /**
     * Calculate imbalance metrics
     */
    public void calculateImbalanceMetrics() {
        if (barType != null && barType.endsWith("IB")) {
            if ("VIB".equals(barType)) {
                imbalance = Math.abs(buyVolume - sellVolume);
            } else if ("DIB".equals(barType)) {
                imbalance = Math.abs(buyDollarVolume - sellDollarVolume);
            }
            
            if (expectedImbalance > 0) {
                imbalanceRatio = imbalance / expectedImbalance;
            }
        }
    }
    
    /**
     * Kafka Serde
     */
    public static Serde<InformationBar> serde() {
        return Serdes.serdeFrom(new InformationBarSerializer(), new InformationBarDeserializer());
    }
    
    public static class InformationBarSerializer implements Serializer<InformationBar> {
        private final ObjectMapper objectMapper = new ObjectMapper();
        @Override
        public byte[] serialize(String topic, InformationBar data) {
            if (data == null) return null;
            try {
                return objectMapper.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for InformationBar", e);
            }
        }
    }
    
    public static class InformationBarDeserializer implements Deserializer<InformationBar> {
        private final ObjectMapper objectMapper = new ObjectMapper();
        @Override
        public InformationBar deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return objectMapper.readValue(bytes, InformationBar.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for InformationBar", e);
            }
        }
    }
}

