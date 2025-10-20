package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.kafka.support.serializer.JsonSerde;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FamilyStructuredAll {

    private String familyKey;
    private String familyName;

    // timeframe -> candle
    @Builder.Default
    private Map<String, InstrumentCandle> equity = new HashMap<>();

    @Builder.Default
    private Map<String, InstrumentCandle> future = new HashMap<>();

    // scripCode -> (timeframe -> candle)
    @Builder.Default
    private Map<String, Map<String, InstrumentCandle>> options = new HashMap<>();

    private Long processingTimestamp;

    public static JsonSerde<FamilyStructuredAll> serde() {
        return new JsonSerde<>(FamilyStructuredAll.class);
    }
}
