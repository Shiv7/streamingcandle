package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Represents tick data received from the market.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TickData {

    @JsonProperty("Exch")
    private String exchange;

    @JsonProperty("ExchType")
    private String exchangeType;

    @JsonProperty("Token")
    private int token;

    @JsonProperty("LastRate")
    private double lastRate;

    @JsonProperty("LastQty")
    private int lastQuantity;

    @JsonProperty("TotalQty")
    private int totalQuantity;

    @JsonProperty("High")
    private double high;

    @JsonProperty("Low")
    private double low;

    @JsonProperty("OpenRate")
    private double openRate;

    @JsonProperty("PClose")
    private double previousClose;

    @JsonProperty("AvgRate")
    private double averageRate;

    @JsonProperty("Time")
    private long time;

    @JsonProperty("BidQty")
    private int bidQuantity;

    @JsonProperty("BidRate")
    private double bidRate;

    @JsonProperty("OffQty")
    private int offerQuantity;

    @JsonProperty("OffRate")
    private double offerRate;

    @JsonProperty("TBidQ")
    private int totalBidQuantity;

    @JsonProperty("TOffQ")
    private int totalOfferQuantity;

    @JsonProperty("TickDt")
    private String tickDt;

    @JsonProperty("ChgPcnt")
    private double changePercent;

    @JsonProperty("companyName")
    private String companyName;

    private long timestamp;

    /**
     * Parses timestamp from TickDt field.
     */
    public void parseTimestamp() {
        if (tickDt != null && tickDt.startsWith("/Date(")) {
            try {
                this.timestamp = Long.parseLong(tickDt.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                this.timestamp = 0;
                System.err.println("Failed to parse TickDt: " + tickDt);
            }
        }
    }

    /**
     * Provides Kafka Serde for TickData.
     */
    public static Serde<TickData> serde() {
        return Serdes.serdeFrom(new TickDataSerializer(), new TickDataDeserializer());
    }

    // ---------------------------------------------------
    // Internal Serializer/Deserializer
    // ---------------------------------------------------
    public static class TickDataSerializer implements Serializer<TickData> {
        private final ObjectMapper objectMapper = new ObjectMapper();
        @Override
        public byte[] serialize(String topic, TickData data) {
            if (data == null) return null;
            try {
                return objectMapper.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for TickData", e);
            }
        }
    }

    public static class TickDataDeserializer implements Deserializer<TickData> {
        private final ObjectMapper objectMapper = new ObjectMapper();
        @Override
        public TickData deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                TickData data = objectMapper.readValue(bytes, TickData.class);
                data.parseTimestamp();
                return data;
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for TickData", e);
            }
        }
    }
}
