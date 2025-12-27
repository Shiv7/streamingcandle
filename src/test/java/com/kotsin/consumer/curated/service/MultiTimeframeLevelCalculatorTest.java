package com.kotsin.consumer.curated.service;

import com.kotsin.consumer.curated.model.MultiTimeframeLevels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MultiTimeframeLevelCalculator
 *
 * Tests cover:
 * - Fibonacci retracement calculation (23.6%, 38.2%, 50%, 61.8%, 78.6%)
 * - Fibonacci extension calculation (127.2%, 161.8%, 200%)
 * - Classic pivot point calculation (P, R1-R4, S1-S4)
 * - CPR (Central Pivot Range) calculation and classification
 * - Nearest support/resistance finding
 * - Significant level detection
 * - Cache management (period-based expiry)
 * - Error handling and graceful degradation
 */
@ExtendWith(MockitoExtension.class)
class MultiTimeframeLevelCalculatorTest {

    @Mock
    private RestTemplate restTemplate;

    private MultiTimeframeLevelCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new MultiTimeframeLevelCalculator();
        ReflectionTestUtils.setField(calculator, "historicalApiBaseUrl", "http://localhost:8080/api/historical");
        ReflectionTestUtils.setField(calculator, "apiTimeoutMs", 3000L);
        ReflectionTestUtils.setField(calculator, "levelsEnabled", true);
        ReflectionTestUtils.setField(calculator, "restTemplate", restTemplate);
    }

    // ========== Fibonacci Calculation Tests ==========

    @Test
    void testCalculateFibonacciRetracements() {
        // Given: Swing high = 2500, Swing low = 2400
        String scripCode = "RELIANCE";
        SwingData swingData = new SwingData(2500.0, 2400.0);

        when(restTemplate.getForObject(contains("/swing/"), any()))
                .thenReturn(swingData);

        // When
        MultiTimeframeLevels levels = calculator.calculateLevels(scripCode, 2450.0);

        // Then: Verify Fibonacci retracement levels
        assertNotNull(levels);
        assertNotNull(levels.getDailyFib());

        MultiTimeframeLevels.FibonacciLevels fib = levels.getDailyFib();
        double range = 2500.0 - 2400.0;  // 100

        // Retracements (from high to low)
        assertEquals(2500.0 - (range * 0.236), fib.getFib236(), 0.01);  // 2476.4
        assertEquals(2500.0 - (range * 0.382), fib.getFib382(), 0.01);  // 2461.8
        assertEquals(2500.0 - (range * 0.5), fib.getFib50(), 0.01);     // 2450.0
        assertEquals(2500.0 - (range * 0.618), fib.getFib618(), 0.01);  // 2438.2 (Golden ratio)
        assertEquals(2500.0 - (range * 0.786), fib.getFib786(), 0.01);  // 2421.4
    }

    @Test
    void testCalculateFibonacciExtensions() {
        // Given: Swing high = 2500, Swing low = 2400
        String scripCode = "RELIANCE";
        SwingData swingData = new SwingData(2500.0, 2400.0);

        when(restTemplate.getForObject(contains("/swing/"), any()))
                .thenReturn(swingData);

        // When
        MultiTimeframeLevels levels = calculator.calculateLevels(scripCode, 2450.0);

        // Then: Verify Fibonacci extension levels
        assertNotNull(levels);
        MultiTimeframeLevels.FibonacciLevels fib = levels.getDailyFib();
        double range = 2500.0 - 2400.0;  // 100

        // Extensions (beyond high)
        assertEquals(2500.0 + (range * 0.272), fib.getFib1272(), 0.01);  // 2527.2
        assertEquals(2500.0 + (range * 0.618), fib.getFib1618(), 0.01);  // 2561.8 (Golden ratio)
        assertEquals(2500.0 + range, fib.getFib200(), 0.01);             // 2600.0 (200%)
    }

    @Test
    void testCalculateFibonacci_InvalidData() {
        // Given: Invalid swing data (high = low = 0)
        String scripCode = "RELIANCE";
        SwingData invalidData = new SwingData(0.0, 0.0);

        when(restTemplate.getForObject(contains("/swing/"), any()))
                .thenReturn(invalidData);

        // When
        MultiTimeframeLevels levels = calculator.calculateLevels(scripCode, 2450.0);

        // Then: Should handle gracefully
        // The service should either return null or levels with null Fibonacci data
        if (levels != null) {
            assertTrue(levels.getDailyFib() == null || levels.getDailyFib().getRange() == 0);
        }
    }

    // ========== Pivot Point Calculation Tests ==========

    @Test
    void testCalculateClassicPivots() {
        // Given: Previous OHLC = O:2420, H:2480, L:2410, C:2450
        String scripCode = "RELIANCE";
        OHLCData ohlcData = new OHLCData(2420.0, 2480.0, 2410.0, 2450.0);

        when(restTemplate.getForObject(contains("/ohlc/"), any()))
                .thenReturn(ohlcData);

        // When
        MultiTimeframeLevels levels = calculator.calculateLevels(scripCode, 2450.0);

        // Then: Verify pivot point calculations
        assertNotNull(levels);
        assertNotNull(levels.getDailyPivot());

        MultiTimeframeLevels.PivotLevels pivot = levels.getDailyPivot();

        double expectedPivot = (2480.0 + 2410.0 + 2450.0) / 3.0;  // 2446.67
        assertEquals(expectedPivot, pivot.getPivot(), 0.01);

        // Resistance levels
        double expectedR1 = (2 * expectedPivot) - 2410.0;  // 2483.33
        double expectedR2 = expectedPivot + (2480.0 - 2410.0);  // 2516.67
        assertEquals(expectedR1, pivot.getR1(), 0.01);
        assertEquals(expectedR2, pivot.getR2(), 0.01);

        // Support levels
        double expectedS1 = (2 * expectedPivot) - 2480.0;  // 2413.33
        double expectedS2 = expectedPivot - (2480.0 - 2410.0);  // 2376.67
        assertEquals(expectedS1, pivot.getS1(), 0.01);
        assertEquals(expectedS2, pivot.getS2(), 0.01);
    }

    @Test
    void testCalculateCPR_NarrowRange() {
        // Given: OHLC with narrow range (CPR < 0.3%)
        String scripCode = "RELIANCE";
        OHLCData ohlcData = new OHLCData(2445.0, 2450.0, 2440.0, 2447.0);  // Narrow 10-point range

        when(restTemplate.getForObject(contains("/ohlc/"), any()))
                .thenReturn(ohlcData);

        // When
        MultiTimeframeLevels levels = calculator.calculateLevels(scripCode, 2447.0);

        // Then: Should classify as NARROW CPR
        assertNotNull(levels);
        MultiTimeframeLevels.PivotLevels pivot = levels.getDailyPivot();

        // CPR width should be < 0.3%
        assertTrue(pivot.getCprWidth() < 0.3);
        assertEquals(MultiTimeframeLevels.PivotLevels.CPRWidth.NARROW, pivot.getCprType());
    }

    @Test
    void testCalculateCPR_WideRange() {
        // Given: OHLC with wide range (CPR > 0.7%)
        String scripCode = "RELIANCE";
        OHLCData ohlcData = new OHLCData(2400.0, 2500.0, 2380.0, 2450.0);  // Wide 120-point range

        when(restTemplate.getForObject(contains("/ohlc/"), any()))
                .thenReturn(ohlcData);

        // When
        MultiTimeframeLevels levels = calculator.calculateLevels(scripCode, 2450.0);

        // Then: Should classify as WIDE CPR
        assertNotNull(levels);
        MultiTimeframeLevels.PivotLevels pivot = levels.getDailyPivot();

        assertTrue(pivot.getCprWidth() > 0.7);
        assertEquals(MultiTimeframeLevels.PivotLevels.CPRWidth.WIDE, pivot.getCprType());
    }

    @Test
    void testCalculateCPR_NormalRange() {
        // Given: OHLC with normal range (0.3% < CPR < 0.7%)
        String scripCode = "RELIANCE";
        OHLCData ohlcData = new OHLCData(2430.0, 2470.0, 2420.0, 2450.0);  // Moderate 50-point range

        when(restTemplate.getForObject(contains("/ohlc/"), any()))
                .thenReturn(ohlcData);

        // When
        MultiTimeframeLevels levels = calculator.calculateLevels(scripCode, 2450.0);

        // Then: Should classify as NORMAL CPR
        assertNotNull(levels);
        MultiTimeframeLevels.PivotLevels pivot = levels.getDailyPivot();

        assertTrue(pivot.getCprWidth() >= 0.3 && pivot.getCprWidth() <= 0.7);
        assertEquals(MultiTimeframeLevels.PivotLevels.CPRWidth.NORMAL, pivot.getCprType());
    }

    // ========== Multi-Timeframe Tests ==========

    @Test
    void testCalculateLevels_AllTimeframes() {
        // Given: Data for all timeframes (daily, weekly, monthly)
        String scripCode = "RELIANCE";
        SwingData swingData = new SwingData(2500.0, 2400.0);
        OHLCData ohlcData = new OHLCData(2420.0, 2480.0, 2410.0, 2450.0);

        when(restTemplate.getForObject(anyString(), any()))
                .thenReturn(swingData, ohlcData, swingData, ohlcData, swingData, ohlcData);

        // When
        MultiTimeframeLevels levels = calculator.calculateLevels(scripCode, 2450.0);

        // Then: All timeframes should be populated
        assertNotNull(levels);
        assertNotNull(levels.getDailyFib());
        assertNotNull(levels.getDailyPivot());
        assertNotNull(levels.getWeeklyFib());
        assertNotNull(levels.getWeeklyPivot());
        assertNotNull(levels.getMonthlyFib());
        assertNotNull(levels.getMonthlyPivot());
    }

    // ========== Level Finding Tests ==========

    @Test
    void testGetNearestSupport() {
        // Given: Current price = 2450, with support levels below
        String scripCode = "RELIANCE";
        SwingData swingData = new SwingData(2500.0, 2400.0);
        OHLCData ohlcData = new OHLCData(2420.0, 2480.0, 2410.0, 2450.0);

        when(restTemplate.getForObject(anyString(), any()))
                .thenReturn(swingData, ohlcData, swingData, ohlcData, swingData, ohlcData);

        // When
        MultiTimeframeLevels levels = calculator.calculateLevels(scripCode, 2450.0);
        double nearestSupport = levels.getNearestSupport(2450.0);

        // Then: Should find closest support below current price
        assertTrue(nearestSupport > 0);
        assertTrue(nearestSupport < 2450.0);
    }

    @Test
    void testGetNearestResistance() {
        // Given: Current price = 2450, with resistance levels above
        String scripCode = "RELIANCE";
        SwingData swingData = new SwingData(2500.0, 2400.0);
        OHLCData ohlcData = new OHLCData(2420.0, 2480.0, 2410.0, 2450.0);

        when(restTemplate.getForObject(anyString(), any()))
                .thenReturn(swingData, ohlcData, swingData, ohlcData, swingData, ohlcData);

        // When
        MultiTimeframeLevels levels = calculator.calculateLevels(scripCode, 2450.0);
        double nearestResistance = levels.getNearestResistance(2450.0);

        // Then: Should find closest resistance above current price
        assertTrue(nearestResistance > 0);
        assertTrue(nearestResistance > 2450.0);
    }

    @Test
    void testIsNearSignificantLevel_AtPivot() {
        // Given: Current price near pivot point (within 0.5%)
        String scripCode = "RELIANCE";
        SwingData swingData = new SwingData(2500.0, 2400.0);
        OHLCData ohlcData = new OHLCData(2420.0, 2480.0, 2410.0, 2450.0);

        when(restTemplate.getForObject(anyString(), any()))
                .thenReturn(swingData, ohlcData, swingData, ohlcData, swingData, ohlcData);

        // When
        MultiTimeframeLevels levels = calculator.calculateLevels(scripCode, 2450.0);
        double pivot = levels.getDailyPivot().getPivot();

        // Then: Should detect near significant level
        assertTrue(levels.isNearSignificantLevel(pivot));
        assertTrue(levels.isNearSignificantLevel(pivot + 1.0));  // Within tolerance
        assertFalse(levels.isNearSignificantLevel(pivot + 50.0));  // Too far
    }

    @Test
    void testIsNearSignificantLevel_AtFibonacci() {
        // Given: Current price near Fib 0.618 level
        String scripCode = "RELIANCE";
        SwingData swingData = new SwingData(2500.0, 2400.0);
        OHLCData ohlcData = new OHLCData(2420.0, 2480.0, 2410.0, 2450.0);

        when(restTemplate.getForObject(anyString(), any()))
                .thenReturn(swingData, ohlcData, swingData, ohlcData, swingData, ohlcData);

        // When
        MultiTimeframeLevels levels = calculator.calculateLevels(scripCode, 2450.0);
        double fib618 = levels.getDailyFib().getFib618();

        // Then: Should detect near significant Fibonacci level
        assertTrue(levels.isNearSignificantLevel(fib618));
    }

    // ========== Cache Management Tests ==========

    @Test
    void testCache_SamePerio d_UsesCache() {
        // Given: Same scrip, same period
        String scripCode = "RELIANCE";
        SwingData swingData = new SwingData(2500.0, 2400.0);
        OHLCData ohlcData = new OHLCData(2420.0, 2480.0, 2410.0, 2450.0);

        when(restTemplate.getForObject(anyString(), any()))
                .thenReturn(swingData, ohlcData, swingData, ohlcData, swingData, ohlcData);

        // When: Call twice in same period
        MultiTimeframeLevels levels1 = calculator.calculateLevels(scripCode, 2450.0);
        MultiTimeframeLevels levels2 = calculator.calculateLevels(scripCode, 2450.0);

        // Then: Second call should use cache (6 API calls total, not 12)
        assertNotNull(levels1);
        assertNotNull(levels2);
        verify(restTemplate, times(6)).getForObject(anyString(), any());
    }

    @Test
    void testClearCache() {
        // Given: Cached levels
        String scripCode = "RELIANCE";
        SwingData swingData = new SwingData(2500.0, 2400.0);
        OHLCData ohlcData = new OHLCData(2420.0, 2480.0, 2410.0, 2450.0);

        when(restTemplate.getForObject(anyString(), any()))
                .thenReturn(swingData, ohlcData, swingData, ohlcData, swingData, ohlcData);

        calculator.calculateLevels(scripCode, 2450.0);

        // When: Clear cache
        calculator.clearCache();

        // When: Calculate again
        calculator.calculateLevels(scripCode, 2450.0);

        // Then: Should make new API calls (12 total)
        verify(restTemplate, times(12)).getForObject(anyString(), any());
    }

    // ========== Error Handling Tests ==========

    @Test
    void testCalculateLevels_Disabled() {
        // Given: Levels calculation disabled
        ReflectionTestUtils.setField(calculator, "levelsEnabled", false);

        // When
        MultiTimeframeLevels levels = calculator.calculateLevels("RELIANCE", 2450.0);

        // Then: Should return null
        assertNull(levels);
        verify(restTemplate, never()).getForObject(anyString(), any());
    }

    @Test
    void testCalculateLevels_ApiFailure() {
        // Given: API throws exception
        when(restTemplate.getForObject(anyString(), any()))
                .thenThrow(new RuntimeException("API error"));

        // When
        MultiTimeframeLevels levels = calculator.calculateLevels("RELIANCE", 2450.0);

        // Then: Should return null (graceful degradation)
        assertNull(levels);
    }

    // ========== Helper Classes (Matching service DTOs) ==========

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    private static class SwingData {
        private double high;
        private double low;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    private static class OHLCData {
        private double open;
        private double high;
        private double low;
        private double close;
    }
}
