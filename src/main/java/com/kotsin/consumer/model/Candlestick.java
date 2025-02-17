package com.kotsin.consumer.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Represents a candlestick structure for market data aggregation.
 */
@Data
public class Candlestick {

    private double open;
    private double high;
    private double low;
    private double close;
    private int volume;

    private String exchange;
    private String exchangeType;
    private String companyName;
    private String scripCode;

    public Candlestick() {
        this.open = 0;
        this.high = Double.MIN_VALUE;
        this.low = Double.MAX_VALUE;
        this.close = 0;
        this.volume = 0;
    }

    /**
     * Updates the candlestick with a single TickData entry.
     */
    public void update(TickData tick) {
        double price = tick.getLastRate();
        if (open == 0) open = price;
        high = Math.max(high, price);
        low = Math.min(low, price);
        close = price;
        this.volume += tick.getLastQuantity();

        exchange = tick.getExchange();
        exchangeType = tick.getExchangeType();
        companyName = tick.getCompanyName();
        scripCode = String.valueOf(tick.getToken());
    }

    /**
     * Merges another Candlestick into this one.
     * Useful when building multi-minute candles from smaller candles.
     */
    public void updateCandle(Candlestick other) {
        if (this.open == 0) {
            this.open = other.open;
        }
        this.high = Math.max(this.high, other.high);
        this.low = Math.min(this.low, other.low);
        this.close = other.close;
        this.volume += other.volume;

        this.exchange = other.exchange;
        this.exchangeType = other.exchangeType;
        this.companyName = other.companyName;
        scripCode = String.valueOf(other.getScripCode());
    }

    /**
     * Provides a Kafka Serde for Candlestick.
     */
    public static Serde<Candlestick> serde() {
        return Serdes.serdeFrom(new CandlestickSerializer(), new CandlestickDeserializer());
    }

    // ---------------------------------------------------
    // Internal Serializer/Deserializer
    // ---------------------------------------------------
    public static class CandlestickSerializer implements Serializer<Candlestick> {
        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public byte[] serialize(String topic, Candlestick data) {
            if (data == null) return null;
            try {
                return objectMapper.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for Candlestick", e);
            }
        }
    }

    public static class CandlestickDeserializer implements Deserializer<Candlestick> {
        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public Candlestick deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return objectMapper.readValue(bytes, Candlestick.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for Candlestick", e);
            }
        }
    }
}
