package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class VpinState {
    public List<EnrichedCandlestick.VPINBucket> buckets = new ArrayList<>();
    public double currentBucketVolume = 0.0;
    public double currentBucketBuyVolume = 0.0;
    public double bucketSize = 10000.0; // default
    // BUG-002 FIX: Add lastUpdateTime field for daily reset detection
    public long lastUpdateTime = 0L;

    // BUG-015 FIX: Shared, thread-safe ObjectMapper
    private static final ObjectMapper SHARED_MAPPER = new ObjectMapper()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    public static Serde<VpinState> serde() {
        return Serdes.serdeFrom(new VpinStateSerializer(), new VpinStateDeserializer());
    }

    public static class VpinStateSerializer implements Serializer<VpinState> {
        @Override
        public byte[] serialize(String topic, VpinState data) {
            if (data == null) return null;
            try {
                return SHARED_MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for VpinState", e);
            }
        }
    }

    public static class VpinStateDeserializer implements Deserializer<VpinState> {
        @Override
        public VpinState deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return SHARED_MAPPER.readValue(bytes, VpinState.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for VpinState", e);
            }
        }
    }
}

