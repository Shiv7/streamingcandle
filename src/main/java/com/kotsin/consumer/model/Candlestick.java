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
    
    // Human-readable window timestamps
    private String humanReadableStartTime;
    private String humanReadableEndTime;

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
        
        // FIXED: Better initialization logic to handle single tick scenarios
        if (open == 0) {
            open = price;
            // Initialize high and low with first price to prevent MIN/MAX values
            if (high == Double.MIN_VALUE) high = price;
            if (low == Double.MAX_VALUE) low = price;
        }
        
        // Update high/low prices
        high = Math.max(high, price);
        low = Math.min(low, price);
        
        // Always update close price (last tick)
        close = price;
        
        // CRITICAL FIX: Use getTotalQuantity() instead of getLastQuantity()
        // getTotalQuantity() represents cumulative volume for the period
        // getLastQuantity() is just the individual trade quantity
        this.volume = tick.getTotalQuantity();

        // Update metadata
        exchange = tick.getExchange();
        
        // Handle exchangeType - derive from exchange if null
        if (tick.getExchangeType() != null) {
            exchangeType = tick.getExchangeType();
        } else {
            // If exchangeType is not available, set a default based on exchange
            if ("N".equals(tick.getExchange())) {
                exchangeType = "EQUITY"; // Default for NSE
            } else if ("M".equals(tick.getExchange())) {
                exchangeType = "COMMODITY"; // Default for MCX
            } else {
                exchangeType = "UNKNOWN";
            }
        }
        
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
        
        // Handle exchangeType - ensure it's not null
        if (other.exchangeType != null) {
            this.exchangeType = other.exchangeType;
        } else if (this.exchangeType == null) {
            // If both are null, set a default based on exchange
            if ("N".equals(other.exchange)) {
                this.exchangeType = "EQUITY"; // Default for NSE
            } else if ("M".equals(other.exchange)) {
                this.exchangeType = "COMMODITY"; // Default for MCX
            } else {
                this.exchangeType = "UNKNOWN";
            }
        }
        
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
     * Updates the human-readable timestamps based on windowStartMillis and windowEndMillis
     * with improved formatting and alignment to ensure exact minute boundaries
     */
    public void updateHumanReadableTimestamps() {
        if (windowStartMillis > 0) {
            ZonedDateTime startTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(windowStartMillis),
                    ZoneId.of("Asia/Kolkata")
            );
            
            // Ensure alignment to exact minute boundaries
            startTime = startTime.withSecond(0).withNano(0);
            
            this.humanReadableStartTime = startTime.format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            );
        }
        
        if (windowEndMillis > 0) {
            ZonedDateTime endTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(windowEndMillis),
                    ZoneId.of("Asia/Kolkata")
            );
            
            // Ensure alignment to exact minute boundaries
            endTime = endTime.withSecond(0).withNano(0);
            
            this.humanReadableEndTime = endTime.format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            );
        }
    }
    
    /**
     * Sets the window start time in milliseconds and updates the human-readable representation.
     * 
     * @param windowStartMillis The window start time in epoch milliseconds
     */
    public void setWindowStartMillis(long windowStartMillis) {
        this.windowStartMillis = windowStartMillis;
        updateHumanReadableTimestamps();
    }
    
    /**
     * Sets the window end time in milliseconds and updates the human-readable representation.
     * 
     * @param windowEndMillis The window end time in epoch milliseconds
     */
    public void setWindowEndMillis(long windowEndMillis) {
        this.windowEndMillis = windowEndMillis;
        updateHumanReadableTimestamps();
    }

    /**
     * Validates the quality of this candlestick data.
     * Checks for logical consistency in OHLC values and volume.
     * 
     * @return true if the candle data is valid, false otherwise
     */
    public boolean isValidCandle() {
        // Check for basic data integrity
        if (open <= 0 || close <= 0 || high <= 0 || low <= 0) {
            return false;
        }
        
        // Check OHLC relationships
        if (high < Math.max(open, close) || low > Math.min(open, close)) {
            return false;
        }
        
        // Check for reasonable price ranges (high should be >= low)
        if (high < low) {
            return false;
        }
        
        // Volume should be non-negative
        if (volume < 0) {
            return false;
        }
        
        // Check for extreme price differences (possible data corruption)
        double priceRange = high - low;
        double avgPrice = (high + low) / 2;
        if (avgPrice > 0 && priceRange / avgPrice > 0.2) { // 20% range seems excessive for 1-minute candles
            // This is a warning, not a failure
            // Large ranges can happen during volatile periods
        }
        
        return true;
    }
    
    /**
     * Returns a summary of candle validation issues for debugging.
     * 
     * @return A string describing any validation issues found
     */
    public String getValidationIssues() {
        StringBuilder issues = new StringBuilder();
        
        if (open <= 0) issues.append("Invalid open price: ").append(open).append("; ");
        if (close <= 0) issues.append("Invalid close price: ").append(close).append("; ");
        if (high <= 0) issues.append("Invalid high price: ").append(high).append("; ");
        if (low <= 0) issues.append("Invalid low price: ").append(low).append("; ");
        
        if (high < Math.max(open, close)) {
            issues.append("High (").append(high).append(") is less than max(open, close): ")
                   .append(Math.max(open, close)).append("; ");
        }
        
        if (low > Math.min(open, close)) {
            issues.append("Low (").append(low).append(") is greater than min(open, close): ")
                   .append(Math.min(open, close)).append("; ");
        }
        
        if (high < low) {
            issues.append("High (").append(high).append(") is less than low (").append(low).append("); ");
        }
        
        if (volume < 0) {
            issues.append("Negative volume: ").append(volume).append("; ");
        }
        
        return issues.length() > 0 ? issues.toString() : "No validation issues found";
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
