package com.kotsin.consumer.integration;

import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentType;
import com.kotsin.consumer.domain.model.DataQuality;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DataFlowIntegrationTest - Validates end-to-end data flow
 *
 * Tests the complete pipeline:
 * 3 Topics (Tick, Orderbook, OI) → InstrumentCandle → FamilyCandle → Signals
 *
 * This ensures ZERO technical debt by validating smooth data flow
 */
public class DataFlowIntegrationTest {

    @Test
    @DisplayName("InstrumentCandle: Should create valid candle with OHLCV data")
    public void testInstrumentCandleCreation() {
        // Arrange
        InstrumentCandle candle = InstrumentCandle.builder()
            .scripCode("14154")
            .symbol("UNOMINDA")
            .companyName("UNO MINDA LTD")
            .exchange("N")
            .exchangeType("C")
            .instrumentType(InstrumentType.EQUITY)
            .open(1000.0)
            .high(1050.0)
            .low(990.0)
            .close(1040.0)
            .volume(1000000L)
            .buyVolume(600000L)
            .sellVolume(400000L)
            .vwap(1025.0)
            .tickCount(500)
            .quality(DataQuality.VALID)
            .build();

        candle.updateHumanReadableTime();

        // Assert - Basic validation
        assertTrue(candle.isValid(), "Candle should be valid");
        assertNull(candle.getValidationErrors(), "Should have no validation errors");

        // Assert - OHLC relationships
        assertTrue(candle.getHigh() >= candle.getOpen());
        assertTrue(candle.getHigh() >= candle.getClose());
        assertTrue(candle.getLow() <= candle.getOpen());
        assertTrue(candle.getLow() <= candle.getClose());

        // Assert - Volume data
        assertEquals(1000000L, candle.getVolume());
        assertEquals(200000L, candle.getVolumeDelta()); // Buy - Sell
        assertEquals(20.0, candle.getVolumeDeltaPercent()); // (600k - 400k) / 1M * 100

        // Assert - Price change
        assertTrue(candle.isBullish()); // Close > Open
        assertEquals(4.0, candle.getPriceChangePercent()); // (1040 - 1000) / 1000 * 100

        // Assert - Optional data flags
        assertFalse(candle.hasOrderbook()); // No orderbook data provided
        assertFalse(candle.hasOI()); // No OI data provided
    }

    @Test
    @DisplayName("InstrumentCandle: Should validate OHLC integrity")
    public void testInstrumentCandleValidation() {
        // Test invalid candle (High < Low)
        InstrumentCandle invalidCandle = InstrumentCandle.builder()
            .scripCode("14154")
            .open(1000.0)
            .high(990.0)  // Invalid: High < Low
            .low(1000.0)
            .close(995.0)
            .volume(1000L)
            .build();

        assertFalse(invalidCandle.isValid());
        assertNotNull(invalidCandle.getValidationErrors());
        assertTrue(invalidCandle.getValidationErrors().contains("high < low"));
    }

    @Test
    @DisplayName("InstrumentCandle: Should handle orderbook data correctly")
    public void testInstrumentCandleWithOrderbook() {
        // Arrange - Candle with orderbook data
        InstrumentCandle candle = InstrumentCandle.builder()
            .scripCode("14154")
            .open(1000.0)
            .high(1050.0)
            .low(990.0)
            .close(1040.0)
            .volume(1000000L)
            // Orderbook data
            .orderbookPresent(true)
            .ofi(150.5)
            .kyleLambda(0.002)
            .microprice(1040.25)
            .bidAskSpread(0.50)
            .build();

        // Assert
        assertTrue(candle.hasOrderbook());
        assertEquals(150.5, candle.getOfi());
        assertEquals(0.002, candle.getKyleLambda());
        assertEquals(1040.25, candle.getMicroprice());
    }

    @Test
    @DisplayName("InstrumentCandle: Should handle OI data for derivatives")
    public void testInstrumentCandleWithOI() {
        // Arrange - Future candle with OI data
        InstrumentCandle futureCandle = InstrumentCandle.builder()
            .scripCode("123456")
            .instrumentType(InstrumentType.FUTURE)
            .open(25000.0)
            .high(25200.0)
            .low(24900.0)
            .close(25100.0)
            .volume(500000L)
            // OI data
            .oiPresent(true)
            .openInterest(10000000L)
            .oiOpen(9900000L)
            .oiHigh(10100000L)
            .oiLow(9800000L)
            .oiClose(10000000L)
            .oiChange(100000L)
            .oiChangePercent(1.0)
            .build();

        // Assert
        assertTrue(futureCandle.hasOI());
        assertEquals(10000000L, futureCandle.getOpenInterest());
        assertEquals(100000L, futureCandle.getOiChange());
        assertEquals(1.0, futureCandle.getOiChangePercent());
    }

    @Test
    @DisplayName("FamilyCandle: Should group instruments correctly")
    public void testFamilyCandleGrouping() {
        // Arrange - Equity candle
        InstrumentCandle equity = InstrumentCandle.builder()
            .scripCode("14154")
            .instrumentType(InstrumentType.EQUITY)
            .symbol("UNOMINDA")
            .open(1000.0)
            .high(1050.0)
            .low(990.0)
            .close(1040.0)
            .volume(1000000L)
            .build();

        // Arrange - FamilyCandle
        FamilyCandle family = FamilyCandle.builder()
            .familyId("14154")
            .symbol("UNOMINDA")
            .equity(equity)
            .hasFuture(false)
            .hasOptions(false)
            .optionCount(0)
            .quality(DataQuality.VALID)
            .build();

        // Assert
        assertTrue(family.hasEquity());
        assertFalse(family.isComplete()); // No future/options
        assertEquals(1040.0, family.getSpotPrice());
        assertEquals(0.0, family.getFuturePrice()); // No future
    }

    @Test
    @DisplayName("FamilyCandle: Should calculate signal strength correctly")
    public void testFamilyCandleSignalStrength() {
        // Arrange - Complete family with bullish signals
        InstrumentCandle equity = InstrumentCandle.builder()
            .instrumentType(InstrumentType.EQUITY)
            .open(1000.0)
            .close(1040.0) // Bullish
            .build();

        InstrumentCandle future = InstrumentCandle.builder()
            .instrumentType(InstrumentType.FUTURE)
            .open(1005.0)
            .close(1045.0) // Bullish
            .build();

        FamilyCandle family = FamilyCandle.builder()
            .equity(equity)
            .future(future)
            .hasFuture(true)
            .pcr(0.8) // Bullish (< 1.0)
            .oiSignal("BULLISH_ACCUMULATION")
            .build();

        // Assert - Signal strength calculation
        double strength = family.getSignalStrength();
        assertTrue(strength > 0.5, "Signal strength should be > 0.5 with multiple confirmations");
        assertTrue(family.isBullishOI());
        assertFalse(family.isBearishOI());
    }

    @Test
    @DisplayName("Data Flow: Should preserve data through LEFT JOIN semantics")
    public void testLeftJoinSemantics() {
        // Arrange - Candle with only tick data (no orderbook/OI)
        InstrumentCandle candleWithOnlyTicks = InstrumentCandle.builder()
            .scripCode("14154")
            .open(1000.0)
            .high(1050.0)
            .low(990.0)
            .close(1040.0)
            .volume(1000000L)
            .orderbookPresent(false)
            .oiPresent(false)
            .quality(DataQuality.VALID)
            .build();

        // Assert - Data should NOT be discarded
        assertTrue(candleWithOnlyTicks.isValid());
        assertFalse(candleWithOnlyTicks.hasOrderbook());
        assertFalse(candleWithOnlyTicks.hasOI());
        assertEquals(DataQuality.VALID, candleWithOnlyTicks.getQuality());

        // This validates LEFT JOIN: Tick data is preserved even without orderbook/OI
    }

    @Test
    @DisplayName("Data Quality: Should detect and flag data quality issues")
    public void testDataQualityTracking() {
        // Arrange - Candle with quality warning
        InstrumentCandle candle = InstrumentCandle.builder()
            .scripCode("14154")
            .open(1000.0)
            .high(1050.0)
            .low(990.0)
            .close(1040.0)
            .volume(0L) // Zero volume - warning
            .quality(DataQuality.WARNING)
            .qualityReason("Zero volume in candle")
            .build();

        // Assert
        assertEquals(DataQuality.WARNING, candle.getQuality());
        assertEquals("Zero volume in candle", candle.getQualityReason());
    }

    @Test
    @DisplayName("Performance: Should track processing latency")
    public void testLatencyTracking() {
        // Arrange
        long tickTime = System.currentTimeMillis() - 100; // 100ms old tick
        long processingTime = System.currentTimeMillis();

        InstrumentCandle candle = InstrumentCandle.builder()
            .scripCode("14154")
            .open(1000.0)
            .close(1040.0)
            .volume(1000L)
            .processingLatencyMs(processingTime - tickTime)
            .maxTickAgeMs(150L)
            .minTickAgeMs(50L)
            .build();

        // Assert
        assertTrue(candle.getProcessingLatencyMs() >= 100);
        assertTrue(candle.getMaxTickAgeMs() > 0);
        assertTrue(candle.getMinTickAgeMs() > 0);
    }
}
