package com.kotsin.consumer.util;

import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.InstrumentCandle;

import java.util.Objects;
import java.util.Optional;

/**
 * Utility class for consistent null handling and validation
 * 
 * BEST PRACTICE: Centralized validation logic
 * NULL SAFETY: Using Optional and Objects.nonNull consistently
 */
public final class ValidationUtils {

    private ValidationUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Validate TickData is not null and has required fields
     */
    public static boolean isValid(TickData tick) {
        if (Objects.isNull(tick)) {
            return false;
        }
        if (Objects.isNull(tick.getScripCode()) || tick.getScripCode().trim().isEmpty()) {
            return false;
        }
        if (tick.getTimestamp() <= 0) {
            return false;
        }
        if (Objects.isNull(tick.getLastRate()) || tick.getLastRate() <= 0) {
            return false;
        }
        return true;
    }

    /**
     * Validate OrderBookSnapshot is not null and has data
     */
    public static boolean isValid(OrderBookSnapshot orderbook) {
        return Objects.nonNull(orderbook) &&
               orderbook.isValid() &&
               Objects.nonNull(orderbook.getBids()) &&
               Objects.nonNull(orderbook.getAsks()) &&
               !orderbook.getBids().isEmpty() &&
               !orderbook.getAsks().isEmpty();
    }

    /**
     * Validate InstrumentCandle is not null and has required fields
     */
    public static boolean isValid(InstrumentCandle candle) {
        return Objects.nonNull(candle) &&
               Objects.nonNull(candle.getScripCode()) &&
               Objects.nonNull(candle.getOpen()) &&
               Objects.nonNull(candle.getClose()) &&
               Objects.nonNull(candle.getHigh()) &&
               Objects.nonNull(candle.getLow());
    }

    /**
     * Safe get with default value
     */
    public static <T> T getOrDefault(T value, T defaultValue) {
        return Objects.nonNull(value) ? value : defaultValue;
    }

    /**
     * Safe get as Optional
     */
    public static <T> Optional<T> toOptional(T value) {
        return Optional.ofNullable(value);
    }

    /**
     * Require non-null with custom message
     */
    public static <T> T requireNonNull(T obj, String message) {
        return Objects.requireNonNull(obj, message);
    }

    /**
     * Check if string is null or empty
     */
    public static boolean isNullOrEmpty(String str) {
        return Objects.isNull(str) || str.trim().isEmpty();
    }

    /**
     * Check if string is not null and not empty
     */
    public static boolean isNotNullOrEmpty(String str) {
        return Objects.nonNull(str) && !str.trim().isEmpty();
    }

    /**
     * Safe double comparison
     */
    public static boolean isPositive(Double value) {
        return Objects.nonNull(value) && value > 0;
    }

    /**
     * Safe integer comparison
     */
    public static boolean isPositive(Integer value) {
        return Objects.nonNull(value) && value > 0;
    }

    /**
     * Safe long comparison
     */
    public static boolean isPositive(Long value) {
        return Objects.nonNull(value) && value > 0;
    }
}
