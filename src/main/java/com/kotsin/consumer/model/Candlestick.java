package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * Represents a market data candlestick (OHLC + volume) used for technical analysis.
 * 
 * This class stores:
 * - Price data (open, high, low, close)
 * - Volume data
 * - Metadata (exchange, symbol, etc.)
 * - Window timing information (for debugging and data analysis)
 * 
 * The candlestick can be built either:
 * - Directly from raw TickData (for 1-minute candles)
 * - By aggregating smaller timeframe candles (for multi-minute candles)
 */
@Data
public class Candlestick {

    // Price data
    private double open;
    private double high;
    private double low;
    private double close;
    private int volume;

    // Metadata
    private String exchange;
    private String exchangeType;
    private String companyName;
    private String scripCode;

    // Window timing information (in epoch millis)
    private long windowStartMillis;
    private long windowEndMillis;

    /**
     * Creates a new empty candlestick with default values.
     */
    public Candlestick() {
        this.open = 0;
        this.high = Double.MIN_VALUE;
        this.low = Double.MAX_VALUE;
        this.close = 0;
        this.volume = 0;
    }

    /**
     * Updates the candlestick with a single TickData entry.
     * Used when building 1-minute candles from raw tick data.
     * 
     * @param tick The tick data to incorporate into this candle
     */
    public void update(TickData tick) {
        double price = tick.getLastRate();
        
        // Set open price only once (first tick)
        if (open == 0) open = price;
        
        // Update high/low prices
        high = Math.max(high, price);
        low = Math.min(low, price);
        
        // Always update close price (last tick)
        close = price;
        
        // Accumulate volume
        this.volume += tick.getLastQuantity();

        // Update metadata
        exchange = tick.getExchange();
        exchangeType = tick.getExchangeType();
        companyName = tick.getCompanyName();
        scripCode = String.valueOf(tick.getToken());
    }

    /**
     * Merges another Candlestick into this one.
     * Used when building multi-minute candles from smaller timeframe candles.
     * 
     * @param other The candle to merge into this one
     */
    public void updateCandle(Candlestick other) {
        // Set open price only for the first candle in the window
        if (this.open == 0) {
            this.open = other.open;
        }
        
        // Take highest high and lowest low
        this.high = Math.max(this.high, other.high);
        this.low = Math.min(this.low, other.low);
        
        // Always update close to the latest candle's close
        this.close = other.close;
        
        // Accumulate volume
        this.volume += other.volume;

        // Update metadata
        this.exchange = other.exchange;
        this.exchangeType = other.exchangeType;
        this.companyName = other.companyName;
        this.scripCode = other.scripCode;
    }
    
    /**
     * Returns a formatted string representation of the candle's time window.
     * Useful for debugging or display.
     * 
     * @return String in format "09:15-09:45" (or empty if window times aren't set)
     */
    @JsonIgnore
    public String getFormattedTimeWindow() {
        if (windowStartMillis == 0 || windowEndMillis == 0) {
            return "";
        }
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
        ZoneId istZone = ZoneId.of("Asia/Kolkata");
        
        ZonedDateTime start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(windowStartMillis), istZone);
        ZonedDateTime end = ZonedDateTime.ofInstant(Instant.ofEpochMilli(windowEndMillis), istZone);
        
        return start.format(formatter) + "-" + end.format(formatter);
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
