package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Open Interest data model
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenInterest {
    
    @JsonProperty("Exch")
    private String exchange;

    @JsonProperty("ExchType")
    private String exchangeType;

    @JsonProperty("Token")
    private int token;

    @JsonProperty("OpenInterest")
    private Long openInterest;

    // Optional fields (may not be present in producer payload)
    private Long oiChange;
    private Double oiChangePercent;
    private Double lastRate;
    private Long volume;

    @JsonProperty("companyName")
    private String companyName;

    @JsonProperty("receivedTimestamp")
    private Long receivedTimestamp;
    
    /**
     * Create serde for Kafka Streams
     */
    public static org.apache.kafka.common.serialization.Serde<OpenInterest> serde() {
        return new org.springframework.kafka.support.serializer.JsonSerde<>(OpenInterest.class);
    }
}
