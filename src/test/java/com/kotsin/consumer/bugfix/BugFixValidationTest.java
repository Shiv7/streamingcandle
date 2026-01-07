package com.kotsin.consumer.bugfix;

import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.service.VCPCalculator;
import com.kotsin.consumer.util.TTLCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BugFixValidationTest - Comprehensive tests for critical bug fixes
 * 
 * This test suite validates that all identified bugs have been properly fixed.
 * Each nested class corresponds to a specific bug fix.
 * 
 * Bug Fixes Validated:
 * 1. IPU Cache TTL (memory leak prevention)
 * 2. VCP Empty runway score
 * 3. InstrumentCandle NaN/Infinity validation
 */
public class BugFixValidationTest {

    @Nested
    @DisplayName("Bug #2: IPU Cache TTL (Memory Leak Prevention)")
    class IPUCacheTTLTest {

        @Test
        @DisplayName("TTLCache should expire entries after TTL")
        void shouldExpireEntriesAfterTTL() throws InterruptedException {
            // Given: TTL cache with short TTL (100ms)
            TTLCache<String, String> cache = new TTLCache<>("test-expire", 100, 100, 50);
            
            // When: Put an entry
            cache.put("key1", "value1");
            assertEquals("value1", cache.get("key1"), "Entry should exist immediately after put");
            
            // Wait for TTL to expire
            Thread.sleep(200);
            
            // Then: Entry should be expired
            assertNull(cache.get("key1"), "Entry should be expired after TTL");
            
            cache.shutdown();
        }

        @Test
        @DisplayName("TTLCache should respect max size with LRU eviction")
        void shouldRespectMaxSizeWithLRU() {
            // Given: TTL cache with max size 3
            TTLCache<String, String> cache = new TTLCache<>("test-lru", 60000, 3, 60000);
            
            // When: Add 4 entries
            cache.put("key1", "value1");
            cache.put("key2", "value2");
            cache.put("key3", "value3");
            cache.put("key4", "value4"); // Should evict oldest (key1)
            
            // Then: Oldest entry should be evicted
            assertNull(cache.get("key1"), "Oldest entry should be evicted");
            assertEquals("value4", cache.get("key4"), "Newest entry should exist");
            assertEquals(3, cache.size(), "Cache size should be at max");
            
            cache.shutdown();
        }

        @Test
        @DisplayName("TTLCache should track statistics correctly")
        void shouldTrackStatistics() {
            // Given: TTL cache
            TTLCache<String, String> cache = new TTLCache<>("test-stats", 60000, 100, 60000);
            
            // When: Perform operations
            cache.put("key1", "value1");
            cache.get("key1");  // Hit
            cache.get("key1");  // Hit
            cache.get("missing"); // Miss
            
            // Then: Stats should be tracked
            String stats = cache.getStats();
            assertNotNull(stats);
            assertTrue(stats.contains("hits=2"), "Should have 2 hits");
            assertTrue(stats.contains("misses=1"), "Should have 1 miss");
            
            cache.shutdown();
        }
    }

    @Nested
    @DisplayName("Bug #4: VCP Empty Runway Score")
    class VCPEmptyRunwayTest {

        @Test
        @DisplayName("Empty VCPResult should have runwayScore = 0 (not 1.0)")
        void emptyVCPResultShouldHaveZeroRunway() {
            // When: Create empty VCP result
            VCPCalculator.VCPResult empty = VCPCalculator.VCPResult.empty();
            
            // Then: Runway score should be 0 (not 1.0)
            // FIX: Previously returned 1.0 which meant "safe to trade"
            // Now returns 0 which means "unknown state"
            assertEquals(0.0, empty.getRunwayScore(), 0.001, 
                "Empty VCPResult should have runwayScore = 0 (unknown state)");
        }

        @Test
        @DisplayName("Empty VCPResult should indicate it's empty")
        void emptyVCPResultShouldBeIdentifiable() {
            // When: Create empty VCP result
            VCPCalculator.VCPResult empty = VCPCalculator.VCPResult.empty();
            
            // Then: Should be identifiable as empty
            assertTrue(empty.isEmpty(), "Empty result should return true for isEmpty()");
        }

        @Test
        @DisplayName("Empty VCPResult should have safe default values")
        void emptyVCPResultShouldHaveSafeDefaults() {
            // When: Create empty VCP result
            VCPCalculator.VCPResult empty = VCPCalculator.VCPResult.empty();
            
            // Then: All scores should be safe defaults
            assertEquals(0.0, empty.getRunwayScore(), 0.001);
            assertEquals(0.0, empty.getScore(), 0.001);
            assertTrue(empty.getClusters() == null || empty.getClusters().isEmpty(),
                "Empty result should have no clusters");
        }
    }

    @Nested
    @DisplayName("Bug #15: InstrumentCandle NaN/Infinity Validation")
    class InstrumentCandleValidationTest {

        @Test
        @DisplayName("Should reject NaN prices")
        void shouldRejectNaNPrices() {
            // Given: Candle with NaN close price
            InstrumentCandle nanCandle = InstrumentCandle.builder()
                .scripCode("RELIANCE")
                .open(100.0)
                .high(105.0)
                .low(99.0)
                .close(Double.NaN)  // NaN price
                .volume(10000)
                .build();
            
            // When/Then: Should be invalid
            assertFalse(nanCandle.isValid(), 
                "Candle with NaN close price should be invalid");
        }

        @Test
        @DisplayName("Should reject NaN in open price")
        void shouldRejectNaNOpen() {
            // Given: Candle with NaN open price
            InstrumentCandle nanCandle = InstrumentCandle.builder()
                .scripCode("RELIANCE")
                .open(Double.NaN)  // NaN
                .high(105.0)
                .low(99.0)
                .close(100.0)
                .volume(10000)
                .build();
            
            // When/Then: Should be invalid
            assertFalse(nanCandle.isValid(), 
                "Candle with NaN open price should be invalid");
        }

        @Test
        @DisplayName("Should reject Infinity in high price")
        void shouldRejectInfinityHigh() {
            // Given: Candle with infinite high price
            InstrumentCandle infCandle = InstrumentCandle.builder()
                .scripCode("RELIANCE")
                .open(100.0)
                .high(Double.POSITIVE_INFINITY)  // Infinity
                .low(99.0)
                .close(101.0)
                .volume(10000)
                .build();
            
            // When/Then: Should be invalid
            assertFalse(infCandle.isValid(), 
                "Candle with infinite high price should be invalid");
        }

        @Test
        @DisplayName("Should reject negative infinity")
        void shouldRejectNegativeInfinity() {
            // Given: Candle with negative infinity low
            InstrumentCandle infCandle = InstrumentCandle.builder()
                .scripCode("RELIANCE")
                .open(100.0)
                .high(105.0)
                .low(Double.NEGATIVE_INFINITY)  // Negative infinity
                .close(101.0)
                .volume(10000)
                .build();
            
            // When/Then: Should be invalid
            assertFalse(infCandle.isValid(), 
                "Candle with negative infinite low price should be invalid");
        }

        @Test
        @DisplayName("Should accept valid candle")
        void shouldAcceptValidCandle() {
            // Given: Valid candle with proper OHLCV
            InstrumentCandle validCandle = InstrumentCandle.builder()
                .scripCode("RELIANCE")
                .open(100.0)
                .high(105.0)
                .low(99.0)
                .close(103.0)
                .volume(10000)
                .build();
            
            // When/Then: Should be valid
            assertTrue(validCandle.isValid(), 
                "Valid candle should pass validation");
        }

        @Test
        @DisplayName("Should reject high < low (impossible candle)")
        void shouldRejectInvalidHighLow() {
            // Given: Candle with high < low (physically impossible)
            InstrumentCandle badCandle = InstrumentCandle.builder()
                .scripCode("RELIANCE")
                .open(100.0)
                .high(95.0)   // High < Low - impossible
                .low(100.0)
                .close(98.0)
                .volume(10000)
                .build();
            
            // When/Then: Should be invalid
            assertFalse(badCandle.isValid(), 
                "Candle with high < low should be invalid");
        }

        @Test
        @DisplayName("Should reject negative volume")
        void shouldRejectNegativeVolume() {
            // Given: Candle with negative volume
            InstrumentCandle badCandle = InstrumentCandle.builder()
                .scripCode("RELIANCE")
                .open(100.0)
                .high(105.0)
                .low(99.0)
                .close(103.0)
                .volume(-100)  // Negative volume
                .build();
            
            // When/Then: Should be invalid
            assertFalse(badCandle.isValid(), 
                "Candle with negative volume should be invalid");
        }

        @Test
        @DisplayName("Should reject empty scripCode")
        void shouldRejectEmptyScripCode() {
            // Given: Candle with empty scripCode
            InstrumentCandle badCandle = InstrumentCandle.builder()
                .scripCode("")  // Empty
                .open(100.0)
                .high(105.0)
                .low(99.0)
                .close(103.0)
                .volume(10000)
                .build();
            
            // When/Then: Should be invalid
            assertFalse(badCandle.isValid(), 
                "Candle with empty scripCode should be invalid");
        }

        @Test
        @DisplayName("Should provide detailed validation errors")
        void shouldProvideValidationErrors() {
            // Given: Candle with multiple issues
            InstrumentCandle badCandle = InstrumentCandle.builder()
                .scripCode("RELIANCE")
                .open(Double.NaN)
                .high(90.0)  // High < Low
                .low(100.0)
                .close(Double.NEGATIVE_INFINITY)
                .volume(-100)
                .build();
            
            // When: Get validation errors
            String errors = badCandle.getValidationErrors();
            
            // Then: Should have multiple errors
            assertNotNull(errors, "Error string should not be null");
            assertFalse(errors.isEmpty(), "Should have validation errors");
            assertTrue(errors.contains(";"), "Should contain multiple error indicators");
        }
    }

    @Nested
    @DisplayName("Additional Validation Tests")
    class AdditionalTests {

        @Test
        @DisplayName("Valid candle should have empty error string")
        void validCandleShouldHaveNoErrors() {
            // Given: Valid candle
            InstrumentCandle validCandle = InstrumentCandle.builder()
                .scripCode("RELIANCE")
                .open(100.0)
                .high(105.0)
                .low(99.0)
                .close(103.0)
                .volume(10000)
                .build();
            
            // When: Get validation errors
            String errors = validCandle.getValidationErrors();
            
            // Then: Should be empty (null or empty string)
            assertTrue(errors == null || errors.isEmpty(), 
                "Valid candle should have no validation errors");
        }
    }
}
