package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * HistoricalCandle - Represents a candle from the FastAnalytics API.
 *
 * Maps the JSON response from /getHisDataFromFivePaisa endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class HistoricalCandle {

    @JsonAlias({"Datetime", "datetime", "DateTime"})
    private String datetime;

    @JsonAlias({"Open", "open"})
    private double open;

    @JsonAlias({"High", "high"})
    private double high;

    @JsonAlias({"Low", "low"})
    private double low;

    @JsonAlias({"Close", "close"})
    private double close;

    @JsonAlias({"Volume", "volume"})
    private long volume;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter PARSER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * Parse datetime string to Instant.
     */
    public Instant getTimestampAsInstant() {
        if (datetime == null || datetime.isEmpty()) {
            return Instant.now();
        }
        try {
            LocalDateTime ldt = LocalDateTime.parse(datetime, PARSER);
            return ldt.atZone(IST).toInstant();
        } catch (Exception e) {
            // Try alternative format
            try {
                return Instant.parse(datetime);
            } catch (Exception e2) {
                return Instant.now();
            }
        }
    }

    /**
     * Convert to UnifiedCandle for use in signal processing.
     */
    public UnifiedCandle toUnifiedCandle(String symbol, String scripCode, String exchange, String exchangeType) {
        return UnifiedCandle.builder()
            .symbol(symbol)
            .scripCode(scripCode)
            .exchange(exchange)
            .exchangeType(exchangeType)
            .timestamp(getTimestampAsInstant())
            .open(open)
            .high(high)
            .low(low)
            .close(close)
            .volume(volume)
            .build();
    }

    /**
     * Convert to TickCandle for storage.
     */
    public TickCandle toTickCandle(String symbol, String scripCode, String exchange, String exchangeType) {
        Instant ts = getTimestampAsInstant();

        return TickCandle.builder()
            .symbol(symbol)
            .scripCode(scripCode)
            .exchange(exchange)
            .exchangeType(exchangeType)
            .timestamp(ts)
            .windowStart(ts)
            .windowEnd(ts.plusSeconds(60))
            .open(open)
            .high(high)
            .low(low)
            .close(close)
            .volume(volume)
            .value(close * volume)
            .vwap((high + low + close) / 3)  // Approximation
            .typicalPrice((high + low + close) / 3)
            .tickCount(1)
            .quality("HISTORICAL")
            .createdAt(Instant.now())
            .build();
    }
}
