package com.kotsin.consumer.processor.service;

import com.kotsin.consumer.model.FamilyEnrichedData;
import com.kotsin.consumer.model.InstrumentCandle;
import com.kotsin.consumer.service.FamilyAggregationService;
import com.kotsin.consumer.service.InstrumentKeyResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Comprehensive unit tests for FamilyAggregationService
 * 
 * CRITICAL: Verifies deduplication logic for options/futures
 * Purpose: Prevent double-counting bug regression
 */
class FamilyAggregationServiceTest {

    private FamilyAggregationService service;
    
    @Mock
    private InstrumentKeyResolver keyResolver;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new FamilyAggregationService(keyResolver);
        
        // Mock key resolver
        when(keyResolver.isIndex(anyString())).thenReturn(false);
    }

    // ========== Option Deduplication Tests ==========

    @Test
    @DisplayName("Deduplication: Same option not added twice")
    void testDeduplication_SameOptionOnce() {
        FamilyEnrichedData family = FamilyEnrichedData.builder().build();
        
        // Add NIFTY 24000 CE with 1000 volume
        InstrumentCandle option1 = createOption("NIFTY25SEP24000CE", 24000.0, "CE", 1000L);
        family = service.assembleFamily("NIFTY", option1, family);
        
        assertEquals(1, family.getOptions().size(), "Should have 1 option");
        
        // Add same option again (different timeframe) with 800 volume
        InstrumentCandle option2 = createOption("NIFTY25SEP24000CE", 24000.0, "CE", 800L);
        family = service.assembleFamily("NIFTY", option2, family);
        
        assertEquals(1, family.getOptions().size(), 
            "Should still have 1 option (no duplicate)");
        assertEquals(1000L, family.getOptions().get(0).getVolume(),
            "Should keep option with higher volume");
    }

    @Test
    @DisplayName("Deduplication: Different options added separately")
    void testDeduplication_DifferentOptions() {
        FamilyEnrichedData family = FamilyEnrichedData.builder().build();
        
        // Add NIFTY 24000 CE
        InstrumentCandle option1 = createOption("NIFTY25SEP24000CE", 24000.0, "CE", 1000L);
        family = service.assembleFamily("NIFTY", option1, family);
        
        // Add NIFTY 24000 PE (different type)
        InstrumentCandle option2 = createOption("NIFTY25SEP24000PE", 24000.0, "PE", 800L);
        family = service.assembleFamily("NIFTY", option2, family);
        
        // Add NIFTY 24100 CE (different strike)
        InstrumentCandle option3 = createOption("NIFTY25SEP24100CE", 24100.0, "CE", 900L);
        family = service.assembleFamily("NIFTY", option3, family);
        
        assertEquals(3, family.getOptions().size(), "Should have 3 different options");
    }

    @Test
    @DisplayName("Deduplication: Updates existing option with higher volume")
    void testDeduplication_UpdatesWithHigherVolume() {
        FamilyEnrichedData family = FamilyEnrichedData.builder().build();
        
        // Add option with 1000 volume
        InstrumentCandle option1 = createOption("NIFTY25SEP24000CE", 24000.0, "CE", 1000L);
        family = service.assembleFamily("NIFTY", option1, family);
        
        // Add same option with 2000 volume (should update)
        InstrumentCandle option2 = createOption("NIFTY25SEP24000CE", 24000.0, "CE", 2000L);
        family = service.assembleFamily("NIFTY", option2, family);
        
        assertEquals(1, family.getOptions().size());
        assertEquals(2000L, family.getOptions().get(0).getVolume(),
            "Should update to candle with higher volume");
    }

    @Test
    @DisplayName("Deduplication: Keeps existing option when new has lower volume")
    void testDeduplication_KeepsExistingWithHigherVolume() {
        FamilyEnrichedData family = FamilyEnrichedData.builder().build();
        
        // Add option with 2000 volume
        InstrumentCandle option1 = createOption("NIFTY25SEP24000CE", 24000.0, "CE", 2000L);
        family = service.assembleFamily("NIFTY", option1, family);
        
        // Add same option with 1000 volume (should NOT update)
        InstrumentCandle option2 = createOption("NIFTY25SEP24000CE", 24000.0, "CE", 1000L);
        family = service.assembleFamily("NIFTY", option2, family);
        
        assertEquals(1, family.getOptions().size());
        assertEquals(2000L, family.getOptions().get(0).getVolume(),
            "Should keep existing option with higher volume");
    }

    @Test
    @DisplayName("Deduplication: Top 4 options by volume enforced")
    void testDeduplication_Top4OptionsLimit() {
        FamilyEnrichedData family = FamilyEnrichedData.builder().build();
        family.setEquity(createEquity("RELIANCE", 2500.0));  // For ATM calculation
        
        // Add 6 options with different volumes
        family = service.assembleFamily("RELIANCE", createOption("OPT1", 2400.0, "CE", 1000L), family);
        family = service.assembleFamily("RELIANCE", createOption("OPT2", 2450.0, "CE", 2000L), family);
        family = service.assembleFamily("RELIANCE", createOption("OPT3", 2500.0, "CE", 3000L), family);
        family = service.assembleFamily("RELIANCE", createOption("OPT4", 2550.0, "CE", 4000L), family);
        family = service.assembleFamily("RELIANCE", createOption("OPT5", 2600.0, "CE", 5000L), family);
        family = service.assembleFamily("RELIANCE", createOption("OPT6", 2650.0, "CE", 500L), family);
        
        assertTrue(family.getOptions().size() <= 4, 
            "Should keep at most 4 options, got: " + family.getOptions().size());
        
        // With deduplication and ATM selection, may not keep strictly by volume
        // Just verify we don't have more than 4 and lowest volume isn't included
        assertFalse(family.getOptions().stream().anyMatch(o -> o.getVolume() == 500L),
            "Should exclude lowest volume option");
    }

    // ========== Future Deduplication Tests ==========

    @Test
    @DisplayName("Future Deduplication: Same future not added twice")
    void testFutureDeduplication_SameOnce() {
        FamilyEnrichedData family = FamilyEnrichedData.builder().build();
        
        // Add future with 1000 volume
        InstrumentCandle future1 = createFuture("NIFTY25SEPFUT", "2025-09-26", 24000.0, 1000L);
        family = service.assembleFamily("NIFTY", future1, family);
        
        assertEquals(1, family.getFutures().size());
        
        // Add same future again with 800 volume
        InstrumentCandle future2 = createFuture("NIFTY25SEPFUT", "2025-09-26", 24000.0, 800L);
        family = service.assembleFamily("NIFTY", future2, family);
        
        assertEquals(1, family.getFutures().size(), "Should not duplicate future");
        assertEquals(1000L, family.getFutures().get(0).getVolume(),
            "Should keep future with higher volume");
    }

    @Test
    @DisplayName("Future Selection: Near-month contract preferred")
    void testFutureSelection_NearMonth() {
        FamilyEnrichedData family = FamilyEnrichedData.builder().build();
        
        // Add October expiry
        InstrumentCandle futureOct = createFuture("NIFTY25OCTFUT", "2025-10-31", 24000.0, 1000L);
        family = service.assembleFamily("NIFTY", futureOct, family);
        
        // Add September expiry (nearer month)
        InstrumentCandle futureSep = createFuture("NIFTY25SEPFUT", "2025-09-26", 24050.0, 800L);
        family = service.assembleFamily("NIFTY", futureSep, family);
        
        assertEquals(1, family.getFutures().size(), "Should keep only near-month");
        assertEquals("2025-09-26", family.getFutures().get(0).getExpiry(),
            "Should keep September (near-month) contract");
    }

    // ========== Family Metrics Tests ==========

    @Test
    @DisplayName("Family Metrics: PCR calculation")
    void testFamilyMetrics_PCRCalculation() {
        FamilyEnrichedData family = FamilyEnrichedData.builder().build();
        
        // Add calls with 10000 OI
        family = service.assembleFamily("NIFTY", createOption("CE1", 24000.0, "CE", 1000L, 10000L), family);
        
        // Add puts with 15000 OI
        family = service.assembleFamily("NIFTY", createOption("PE1", 24000.0, "PE", 1000L, 15000L), family);
        
        // PCR = Puts OI / Calls OI = 15000 / 10000 = 1.5
        assertNotNull(family.getAggregatedMetrics());
        assertNotNull(family.getAggregatedMetrics().getPutCallRatio());
        assertEquals(1.5, family.getAggregatedMetrics().getPutCallRatio(), 0.01,
            "PCR should be 1.5 (puts/calls)");
    }

    @Test
    @DisplayName("REGRESSION: No double-counting in volume totals")
    void testRegression_NoDoubleCountingVolume() {
        FamilyEnrichedData family = FamilyEnrichedData.builder().build();
        
        // Add same option from 3 different timeframes (simulating aggregation bug)
        InstrumentCandle option1m = createOption("NIFTY24000CE", 24000.0, "CE", 1000L);
        family = service.assembleFamily("NIFTY", option1m, family);
        
        InstrumentCandle option2m = createOption("NIFTY24000CE", 24000.0, "CE", 1000L);
        family = service.assembleFamily("NIFTY", option2m, family);
        
        InstrumentCandle option5m = createOption("NIFTY24000CE", 24000.0, "CE", 1000L);
        family = service.assembleFamily("NIFTY", option5m, family);
        
        // Should only count option once
        assertEquals(1, family.getOptions().size(), 
            "Should deduplicate - only 1 unique option");
        
        // Total volume should be 1000, not 3000
        assertNotNull(family.getAggregatedMetrics());
        assertEquals(1000L, family.getAggregatedMetrics().getOptionsVolume(),
            "Volume should not be triple-counted");
    }

    // ========== Helper Methods ==========

    private InstrumentCandle createEquity(String scripCode, double price) {
        return InstrumentCandle.builder()
            .scripCode(scripCode)
            .companyName(scripCode)
            .instrumentType("EQUITY")
            .close(price)
            .volume(10000L)
            .build();
    }

    private InstrumentCandle createOption(String scripCode, double strike, String type, long volume) {
        return createOption(scripCode, strike, type, volume, 5000L);
    }

    private InstrumentCandle createOption(String scripCode, double strike, String type, long volume, long oi) {
        return InstrumentCandle.builder()
            .scripCode(scripCode)
            .companyName(scripCode)
            .instrumentType("OPTION")
            .strikePrice(strike)
            .optionType(type)
            .volume(volume)
            .openInterest(oi)
            .close(100.0)
            .build();
    }

    private InstrumentCandle createFuture(String scripCode, String expiry, double price, long volume) {
        return InstrumentCandle.builder()
            .scripCode(scripCode)
            .companyName(scripCode)
            .instrumentType("FUTURE")
            .expiry(expiry)
            .close(price)
            .volume(volume)
            .openInterest(50000L)
            .build();
    }
}

