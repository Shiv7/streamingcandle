package com.kotsin.consumer.model;

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
public class OpenInterest {
    
    private String exchange;
    private String exchangeType;
    private String token;
    private Long openInterest;
    private Long oiChange;
    private Double oiChangePercent;
    private Double lastRate;
    private Long volume;
    private String companyName;
    private Long receivedTimestamp;
    
    /**
     * Create serde for Kafka Streams
     */
    public static org.apache.kafka.common.serialization.Serde<OpenInterest> serde() {
        return new org.springframework.kafka.support.serializer.JsonSerde<>(OpenInterest.class);
    }
}
