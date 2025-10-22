package com.kotsin.consumer.util;

import com.kotsin.consumer.model.InstrumentCandle;
import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.TickData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DEFENSIVE TESTS FOR ValidationUtils
 * 
 * GOAL: Make it impossible for interns to break null safety
 * APPROACH: Test every edge case, null case, and boundary condition
 */
@DisplayName("ValidationUtils - Defensive Tests")
class ValidationUtilsTest {

    // ========== NULL SAFETY TESTS ==========

    @Test
    @DisplayName("Should handle null TickData gracefully")
    void testIsValid_NullTickData() {
        assertFalse(ValidationUtils.isValid((TickData) null));
    }

    @Test
    @DisplayName("Should handle null OrderBookSnapshot gracefully")
    void testIsValid_NullOrderBook() {
        assertFalse(ValidationUtils.isValid((OrderBookSnapshot) null));
    }

    @Test
    @DisplayName("Should handle null InstrumentCandle gracefully")
    void testIsValid_NullCandle() {
        assertFalse(ValidationUtils.isValid((InstrumentCandle) null));
    }

    // ========== TICKDATA VALIDATION TESTS ==========

    @Test
    @DisplayName("Should reject TickData with whitespace scripCode")
    void testIsValid_TickData_WhitespaceScripCode() {
        TickData tick = new TickData();
        tick.setScripCode("   "); // Only whitespace
        tick.setTimestamp(System.currentTimeMillis());
        tick.setLastRate(100.0);
        
        assertFalse(ValidationUtils.isValid(tick),
            "TickData with whitespace-only scripCode should be invalid");
    }

    @Test
    @DisplayName("Should reject TickData with zero timestamp")
    void testIsValid_TickData_ZeroTimestamp() {
        TickData tick = new TickData();
        tick.setScripCode("TEST");
        tick.setTimestamp(0L);
        tick.setLastRate(100.0);
        
        assertFalse(ValidationUtils.isValid(tick),
            "TickData with zero timestamp should be invalid");
    }

    @Test
    @DisplayName("Should reject TickData with zero or negative lastRate")
    void testIsValid_TickData_InvalidLastRate() {
        TickData tick2 = new TickData();
        tick2.setScripCode("TEST");
        tick2.setTimestamp(System.currentTimeMillis());
        tick2.setLastRate(0.0);
        
        TickData tick3 = new TickData();
        tick3.setScripCode("TEST");
        tick3.setTimestamp(System.currentTimeMillis());
        tick3.setLastRate(-10.0);
        
        assertFalse(ValidationUtils.isValid(tick2), "zero lastRate should be invalid");
        assertFalse(ValidationUtils.isValid(tick3), "negative lastRate should be invalid");
    }

    @Test
    @DisplayName("Should accept valid TickData")
    void testIsValid_TickData_Valid() {
        TickData tick = new TickData();
        tick.setScripCode("TEST");
        tick.setTimestamp(System.currentTimeMillis());
        tick.setLastRate(100.0);
        
        assertTrue(ValidationUtils.isValid(tick),
            "Valid TickData should be accepted");
    }

    // ========== ORDERBOOK VALIDATION TESTS ==========

    @Test
    @DisplayName("Should reject OrderBook with empty bids/asks")
    void testIsValid_OrderBook_EmptyData() {
        OrderBookSnapshot orderbook = new OrderBookSnapshot();
        orderbook.setToken("TEST");
        orderbook.setBids(new ArrayList<>());
        orderbook.setAsks(new ArrayList<>());
        
        assertFalse(ValidationUtils.isValid(orderbook),
            "OrderBook with empty bids/asks should be invalid");
    }

    // ========== STRING VALIDATION TESTS ==========

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "\t", "\n"})
    @DisplayName("Should identify null or empty strings")
    void testIsNullOrEmpty(String input) {
        assertTrue(ValidationUtils.isNullOrEmpty(input),
            "Input '" + input + "' should be considered null or empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "a", "  a  "})
    @DisplayName("Should identify non-empty strings")
    void testIsNotNullOrEmpty(String input) {
        assertTrue(ValidationUtils.isNotNullOrEmpty(input),
            "Input '" + input + "' should be considered non-empty");
    }

    // ========== NUMBER VALIDATION TESTS ==========

    @Test
    @DisplayName("Should handle null Double as non-positive")
    void testIsPositive_NullDouble() {
        assertFalse(ValidationUtils.isPositive((Double) null));
    }

    @Test
    @DisplayName("Should handle zero and negative Double as non-positive")
    void testIsPositive_NonPositiveDouble() {
        assertFalse(ValidationUtils.isPositive(0.0));
        assertFalse(ValidationUtils.isPositive(-1.0));
        assertFalse(ValidationUtils.isPositive(-0.1));
    }

    @Test
    @DisplayName("Should identify positive Double")
    void testIsPositive_PositiveDouble() {
        assertTrue(ValidationUtils.isPositive(0.1));
        assertTrue(ValidationUtils.isPositive(1.0));
        assertTrue(ValidationUtils.isPositive(1000.0));
    }

    @Test
    @DisplayName("Should handle null Integer as non-positive")
    void testIsPositive_NullInteger() {
        assertFalse(ValidationUtils.isPositive((Integer) null));
    }

    @Test
    @DisplayName("Should handle zero and negative Integer as non-positive")
    void testIsPositive_NonPositiveInteger() {
        assertFalse(ValidationUtils.isPositive(0));
        assertFalse(ValidationUtils.isPositive(-1));
    }

    @Test
    @DisplayName("Should identify positive Integer")
    void testIsPositive_PositiveInteger() {
        assertTrue(ValidationUtils.isPositive(1));
        assertTrue(ValidationUtils.isPositive(1000));
    }

    // ========== GETORDEFAULT TESTS ==========

    @Test
    @DisplayName("Should return default value when input is null")
    void testGetOrDefault_NullValue() {
        String result = ValidationUtils.getOrDefault(null, "default");
        assertEquals("default", result);
    }

    @Test
    @DisplayName("Should return input value when not null")
    void testGetOrDefault_NonNullValue() {
        String result = ValidationUtils.getOrDefault("value", "default");
        assertEquals("value", result);
    }

    @Test
    @DisplayName("Should handle null default value")
    void testGetOrDefault_NullDefault() {
        String result = ValidationUtils.getOrDefault(null, null);
        assertNull(result);
    }

    // ========== OPTIONAL TESTS ==========

    @Test
    @DisplayName("Should create empty Optional for null value")
    void testToOptional_NullValue() {
        Optional<String> result = ValidationUtils.toOptional(null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should create present Optional for non-null value")
    void testToOptional_NonNullValue() {
        Optional<String> result = ValidationUtils.toOptional("value");
        assertTrue(result.isPresent());
        assertEquals("value", result.get());
    }

    // ========== REQUIRENONNULL TESTS ==========

    @Test
    @DisplayName("Should throw exception for null value with message")
    void testRequireNonNull_NullValue() {
        NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> ValidationUtils.requireNonNull(null, "Custom error message")
        );
        assertEquals("Custom error message", exception.getMessage());
    }

    @Test
    @DisplayName("Should return value for non-null input")
    void testRequireNonNull_NonNullValue() {
        String result = ValidationUtils.requireNonNull("value", "Error");
        assertEquals("value", result);
    }

    // ========== EDGE CASE TESTS (INTERN-PROOF) ==========

    @Test
    @DisplayName("INTERN TEST: Should handle extremely long strings")
    void testEdgeCase_LongStrings() {
        String longString = "a".repeat(10000);
        assertTrue(ValidationUtils.isNotNullOrEmpty(longString));
    }

    @Test
    @DisplayName("INTERN TEST: Should handle special characters in strings")
    void testEdgeCase_SpecialCharacters() {
        String special = "!@#$%^&*()_+-=[]{}|;:',.<>?/~`";
        assertTrue(ValidationUtils.isNotNullOrEmpty(special));
    }

    @Test
    @DisplayName("INTERN TEST: Should handle unicode strings")
    void testEdgeCase_UnicodeStrings() {
        String unicode = "测试 🎉 テスト";
        assertTrue(ValidationUtils.isNotNullOrEmpty(unicode));
    }

    @Test
    @DisplayName("INTERN TEST: Should handle Double.MAX_VALUE")
    void testEdgeCase_MaxDouble() {
        assertTrue(ValidationUtils.isPositive(Double.MAX_VALUE));
    }

    @Test
    @DisplayName("INTERN TEST: Should handle Double.MIN_VALUE (positive)")
    void testEdgeCase_MinDouble() {
        assertTrue(ValidationUtils.isPositive(Double.MIN_VALUE));
    }

    @Test
    @DisplayName("INTERN TEST: Should handle Integer.MAX_VALUE")
    void testEdgeCase_MaxInteger() {
        assertTrue(ValidationUtils.isPositive(Integer.MAX_VALUE));
    }

    // ========== BOUNDARY CONDITION TESTS ==========

    @Test
    @DisplayName("BOUNDARY: Should handle empty string after trim")
    void testBoundary_EmptyAfterTrim() {
        assertTrue(ValidationUtils.isNullOrEmpty("   "));
    }

    @Test
    @DisplayName("BOUNDARY: Should handle single character string")
    void testBoundary_SingleCharacter() {
        assertTrue(ValidationUtils.isNotNullOrEmpty("a"));
    }

    @Test
    @DisplayName("BOUNDARY: Should handle string with only whitespace before valid char")
    void testBoundary_WhitespaceBeforeChar() {
        assertTrue(ValidationUtils.isNotNullOrEmpty("  a"));
    }
}
