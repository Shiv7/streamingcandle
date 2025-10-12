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
 * Represents Open Interest data received from the market.
 * Example JSON:
 * {
 *   "companyName": "ZINC 31 OCT 2025",
 *   "receivedTimestamp": 1760196281770,
 *   "Exch": "M",
 *   "ExchType": "D",
 *   "Token": 458300,
 *   "OpenInterest": 3299
 * }
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenInterestData {

    @JsonProperty("companyName")
    private String companyName;

    @JsonProperty("receivedTimestamp")
    private long receivedTimestamp;

    @JsonProperty("Exch")
    private String exchange;

    @JsonProperty("ExchType")
    private String exchangeType;

    @JsonProperty("Token")
    private int token;

    @JsonProperty("OpenInterest")
    private long openInterest;

    /**
     * Get timestamp for Kafka Streams processing.
     * Use receivedTimestamp as the event time.
     */
    public long getTimestamp() {
        return receivedTimestamp;
    }

    /**
     * Get scrip identifier (using token).
     */
    public String getScripKey() {
        return String.valueOf(token);
    }

    /**
     * Provides Kafka Serde for OpenInterestData.
     */
    public static Serde<OpenInterestData> serde() {
        return Serdes.serdeFrom(new OpenInterestDataSerializer(), new OpenInterestDataDeserializer());
    }

    // ---------------------------------------------------
    // Internal Serializer/Deserializer
    // ---------------------------------------------------
    public static class OpenInterestDataSerializer implements Serializer<OpenInterestData> {
        private final ObjectMapper objectMapper = new ObjectMapper();
        @Override
        public byte[] serialize(String topic, OpenInterestData data) {
            if (data == null) return null;
            try {
                return objectMapper.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for OpenInterestData", e);
            }
        }
    }

    public static class OpenInterestDataDeserializer implements Deserializer<OpenInterestData> {
        private final ObjectMapper objectMapper = new ObjectMapper();
        @Override
        public OpenInterestData deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return objectMapper.readValue(bytes, OpenInterestData.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for OpenInterestData", e);
            }
        }
    }
}

