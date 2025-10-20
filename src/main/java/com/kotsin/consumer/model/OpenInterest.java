package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAlias;
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
    @JsonAlias({"exchange"})
    private String exchange;

    @JsonProperty("ExchType")
    @JsonAlias({"exchangeType"})
    private String exchangeType;

    @JsonProperty("Token")
    @JsonAlias({"token"})
    private int token;

    @JsonProperty("OpenInterest")
    @JsonAlias({"openInterest"})
    private Long openInterest;

    // Optional fields (may not be present in producer payload)
    @JsonAlias({"oiChange"})
    private Long oiChange;
    @JsonAlias({"oiChangePercent"})
    private Double oiChangePercent;
    @JsonAlias({"lastRate"})
    private Double lastRate;
    @JsonAlias({"volume"})
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
