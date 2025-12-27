package com.kotsin.consumer.curated.service;

import com.kotsin.consumer.curated.model.FuturesData;
import com.kotsin.consumer.curated.model.FuturesOptionsAlignment;
import com.kotsin.consumer.curated.model.OptionsData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FuturesOptionsService
 *
 * Tests cover:
 * - Futures data fetching with timeout
 * - Options data fetching with timeout
 * - Buildup detection logic
 * - Alignment score calculation
 * - Directional bias determination
 * - Cache management
 * - Error handling and graceful degradation
 */
@ExtendWith(MockitoExtension.class)
class FuturesOptionsServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private FuturesOptionsService service;

    @BeforeEach
    void setUp() {
        service = new FuturesOptionsService();
        ReflectionTestUtils.setField(service, "foApiBaseUrl", "http://localhost:8080/api/fo");
        ReflectionTestUtils.setField(service, "apiTimeoutMs", 3000L);
        ReflectionTestUtils.setField(service, "foEnabled", true);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
    }

    // ========== Futures Data Tests ==========

    @Test
    void testFetchFuturesData_LongBuildup() {
        // Given: Price ↑ + OI ↑ = LONG_BUILDUP
        String scripCode = "RELIANCE";
        FuturesData mockData = createFuturesData(scripCode, 2450.0, 2455.0, 0.20, 5000000, 250000, 1.5);

        when(restTemplate.getForObject(anyString(), eq(FuturesData.class)))
                .thenReturn(mockData);

        // When
        FuturesData result = service.fetchFuturesData(scripCode);

        // Then
        assertNotNull(result);
        assertEquals(scripCode, result.getScripCode());
        assertEquals(FuturesData.BuildupType.LONG_BUILDUP, result.getBuildup());
        assertTrue(result.isPremiumPositive());
        assertTrue(result.isFresh());
    }

    @Test
    void testFetchFuturesData_ShortBuildup() {
        // Given: Price ↓ + OI ↑ = SHORT_BUILDUP
        String scripCode = "RELIANCE";
        FuturesData mockData = createFuturesData(scripCode, 2450.0, 2445.0, -0.20, 5000000, 250000, -1.5);

        when(restTemplate.getForObject(anyString(), eq(FuturesData.class)))
                .thenReturn(mockData);

        // When
        FuturesData result = service.fetchFuturesData(scripCode);

        // Then
        assertNotNull(result);
        assertEquals(FuturesData.BuildupType.SHORT_BUILDUP, result.getBuildup());
        assertFalse(result.isPremiumPositive());
    }

    @Test
    void testFetchFuturesData_ShortCovering() {
        // Given: Price ↑ + OI ↓ = SHORT_COVERING
        String scripCode = "RELIANCE";
        FuturesData mockData = createFuturesData(scripCode, 2450.0, 2455.0, 0.20, 5000000, -250000, 1.5);

        when(restTemplate.getForObject(anyString(), eq(FuturesData.class)))
                .thenReturn(mockData);

        // When
        FuturesData result = service.fetchFuturesData(scripCode);

        // Then
        assertNotNull(result);
        assertEquals(FuturesData.BuildupType.SHORT_COVERING, result.getBuildup());
    }

    @Test
    void testFetchFuturesData_LongUnwinding() {
        // Given: Price ↓ + OI ↓ = LONG_UNWINDING
        String scripCode = "RELIANCE";
        FuturesData mockData = createFuturesData(scripCode, 2450.0, 2445.0, -0.20, 5000000, -250000, -1.5);

        when(restTemplate.getForObject(anyString(), eq(FuturesData.class)))
                .thenReturn(mockData);

        // When
        FuturesData result = service.fetchFuturesData(scripCode);

        // Then
        assertNotNull(result);
        assertEquals(FuturesData.BuildupType.LONG_UNWINDING, result.getBuildup());
    }

    @Test
    void testFetchFuturesData_Cache() {
        // Given: First call returns data
        String scripCode = "RELIANCE";
        FuturesData mockData = createFuturesData(scripCode, 2450.0, 2455.0, 0.20, 5000000, 250000, 1.5);

        when(restTemplate.getForObject(anyString(), eq(FuturesData.class)))
                .thenReturn(mockData);

        // When: Call twice
        FuturesData result1 = service.fetchFuturesData(scripCode);
        FuturesData result2 = service.fetchFuturesData(scripCode);

        // Then: Should use cache on second call (only 1 API call)
        assertNotNull(result1);
        assertNotNull(result2);
        verify(restTemplate, times(1)).getForObject(anyString(), eq(FuturesData.class));
    }

    @Test
    void testFetchFuturesData_Disabled() {
        // Given: F&O service disabled
        ReflectionTestUtils.setField(service, "foEnabled", false);

        // When
        FuturesData result = service.fetchFuturesData("RELIANCE");

        // Then
        assertNull(result);
        verify(restTemplate, never()).getForObject(anyString(), eq(FuturesData.class));
    }

    @Test
    void testFetchFuturesData_ApiFailure() {
        // Given: API throws exception
        when(restTemplate.getForObject(anyString(), eq(FuturesData.class)))
                .thenThrow(new RuntimeException("API error"));

        // When
        FuturesData result = service.fetchFuturesData("RELIANCE");

        // Then: Should return null (graceful degradation)
        assertNull(result);
    }

    // ========== Options Data Tests ==========

    @Test
    void testFetchOptionsData_Bullish() {
        // Given: PCR < 0.7 = BULLISH
        String scripCode = "RELIANCE";
        OptionsData mockData = createOptionsData(scripCode, 2450, 3000000, 2000000, 150000, 50000);

        when(restTemplate.getForObject(anyString(), eq(OptionsData.class)))
                .thenReturn(mockData);

        // When
        OptionsData result = service.fetchOptionsData(scripCode, 2450.0);

        // Then
        assertNotNull(result);
        assertEquals(OptionsData.Sentiment.BULLISH, result.getSentiment());
        assertTrue(result.getPcr() < 0.7);
    }

    @Test
    void testFetchOptionsData_Bearish() {
        // Given: PCR > 1.3 = BEARISH
        String scripCode = "RELIANCE";
        OptionsData mockData = createOptionsData(scripCode, 2450, 2000000, 3000000, 50000, 150000);

        when(restTemplate.getForObject(anyString(), eq(OptionsData.class)))
                .thenReturn(mockData);

        // When
        OptionsData result = service.fetchOptionsData(scripCode, 2450.0);

        // Then
        assertNotNull(result);
        assertEquals(OptionsData.Sentiment.BEARISH, result.getSentiment());
        assertTrue(result.getPcr() > 1.3);
    }

    @Test
    void testFetchOptionsData_Neutral() {
        // Given: 0.7 <= PCR <= 1.3 = NEUTRAL
        String scripCode = "RELIANCE";
        OptionsData mockData = createOptionsData(scripCode, 2450, 2500000, 2500000, 100000, 100000);

        when(restTemplate.getForObject(anyString(), eq(OptionsData.class)))
                .thenReturn(mockData);

        // When
        OptionsData result = service.fetchOptionsData(scripCode, 2450.0);

        // Then
        assertNotNull(result);
        assertEquals(OptionsData.Sentiment.NEUTRAL, result.getSentiment());
    }

    // ========== Alignment Score Tests ==========

    @Test
    void testCalculateAlignment_StrongBullish() {
        // Given: LONG_BUILDUP + BULLISH sentiment
        String scripCode = "RELIANCE";
        double spotPrice = 2450.0;

        FuturesData futuresData = createFuturesData(scripCode, 2450.0, 2455.0, 0.25, 5000000, 300000, 2.0);
        OptionsData optionsData = createOptionsData(scripCode, 2450, 3500000, 2000000, 200000, 50000);

        when(restTemplate.getForObject(contains("/futures/"), eq(FuturesData.class)))
                .thenReturn(futuresData);
        when(restTemplate.getForObject(contains("/options/"), eq(OptionsData.class)))
                .thenReturn(optionsData);

        // When
        FuturesOptionsAlignment alignment = service.calculateAlignment(scripCode, spotPrice);

        // Then
        assertNotNull(alignment);
        assertTrue(alignment.isAligned());
        assertEquals(FuturesOptionsAlignment.DirectionalBias.STRONG_BULLISH, alignment.getBias());
        assertTrue(alignment.getAlignmentScore() >= 0.6);
        assertTrue(alignment.isUsable());
    }

    @Test
    void testCalculateAlignment_Bearish() {
        // Given: SHORT_BUILDUP + BEARISH sentiment
        String scripCode = "RELIANCE";
        double spotPrice = 2450.0;

        FuturesData futuresData = createFuturesData(scripCode, 2450.0, 2445.0, -0.25, 5000000, 300000, -2.0);
        OptionsData optionsData = createOptionsData(scripCode, 2450, 2000000, 3500000, 50000, 200000);

        when(restTemplate.getForObject(contains("/futures/"), eq(FuturesData.class)))
                .thenReturn(futuresData);
        when(restTemplate.getForObject(contains("/options/"), eq(OptionsData.class)))
                .thenReturn(optionsData);

        // When
        FuturesOptionsAlignment alignment = service.calculateAlignment(scripCode, spotPrice);

        // Then
        assertNotNull(alignment);
        assertTrue(alignment.getBias() == FuturesOptionsAlignment.DirectionalBias.BEARISH ||
                   alignment.getBias() == FuturesOptionsAlignment.DirectionalBias.STRONG_BEARISH);
    }

    @Test
    void testCalculateAlignment_NoDataAvailable() {
        // Given: API returns null
        when(restTemplate.getForObject(anyString(), any()))
                .thenReturn(null);

        // When
        FuturesOptionsAlignment alignment = service.calculateAlignment("RELIANCE", 2450.0);

        // Then
        assertNotNull(alignment);
        assertFalse(alignment.isDataAvailable());
        assertFalse(alignment.isAligned());
        assertEquals(FuturesOptionsAlignment.DirectionalBias.NEUTRAL, alignment.getBias());
        assertEquals(0.0, alignment.getAlignmentScore());
    }

    @Test
    void testCalculateAlignment_MixedSignals() {
        // Given: LONG_BUILDUP but BEARISH sentiment (conflicting)
        String scripCode = "RELIANCE";
        FuturesData futuresData = createFuturesData(scripCode, 2450.0, 2455.0, 0.20, 5000000, 250000, 1.5);
        OptionsData optionsData = createOptionsData(scripCode, 2450, 2000000, 3500000, 50000, 150000);

        when(restTemplate.getForObject(contains("/futures/"), eq(FuturesData.class)))
                .thenReturn(futuresData);
        when(restTemplate.getForObject(contains("/options/"), eq(OptionsData.class)))
                .thenReturn(optionsData);

        // When
        FuturesOptionsAlignment alignment = service.calculateAlignment(scripCode, 2450.0);

        // Then: Should have lower alignment score due to conflict
        assertNotNull(alignment);
        assertTrue(alignment.getAlignmentScore() < 0.8);  // Not strongly aligned
    }

    // ========== Cache Management Tests ==========

    @Test
    void testClearStaleCache() {
        // Given: Stale data in cache (timestamp more than 5 min old)
        String scripCode = "RELIANCE";
        FuturesData staleData = createFuturesData(scripCode, 2450.0, 2455.0, 0.20, 5000000, 250000, 1.5);
        staleData.setTimestamp(System.currentTimeMillis() - (6 * 60 * 1000));  // 6 minutes old

        when(restTemplate.getForObject(anyString(), eq(FuturesData.class)))
                .thenReturn(staleData);

        service.fetchFuturesData(scripCode);

        // When: Clear stale cache
        service.clearStaleCache();

        // When: Fetch again
        service.fetchFuturesData(scripCode);

        // Then: Should make new API call (cache was cleared)
        verify(restTemplate, times(2)).getForObject(anyString(), eq(FuturesData.class));
    }

    // ========== Helper Methods ==========

    private FuturesData createFuturesData(String scripCode, double spotPrice, double futuresPrice,
                                          double premium, long openInterest, long oiChange, double priceChangePercent) {
        FuturesData data = new FuturesData();
        data.setScripCode(scripCode);
        data.setSpotPrice(spotPrice);
        data.setFuturesPrice(futuresPrice);
        data.setPremium(premium);
        data.setOpenInterest(openInterest);
        data.setOiChange(oiChange);
        data.setPriceChangePercent(priceChangePercent);
        data.setTimestamp(System.currentTimeMillis());
        return data;
    }

    private OptionsData createOptionsData(String scripCode, int atmStrike, long totalCallOI, long totalPutOI,
                                          long callOIChange, long putOIChange) {
        OptionsData data = new OptionsData();
        data.setScripCode(scripCode);
        data.setAtmStrike(atmStrike);
        data.setTotalCallOI(totalCallOI);
        data.setTotalPutOI(totalPutOI);
        data.setTotalCallOIChange(callOIChange);
        data.setTotalPutOIChange(putOIChange);
        data.setTimestamp(System.currentTimeMillis());
        return data;
    }
}
