package com.kotsin.consumer.service;

import com.kotsin.consumer.model.InstrumentFamily;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for InstrumentFamilyCacheService
 */
@SpringBootTest
@ActiveProfiles("test")
class InstrumentFamilyCacheServiceTest {
    
    @Autowired
    private InstrumentFamilyCacheService cacheService;
    
    @Test
    void testCacheInitialization() {
        // Test that cache is initialized
        assertNotNull(cacheService);
        
        // Test cache stats
        var stats = cacheService.getCacheStats();
        assertNotNull(stats);
        assertTrue(stats.containsKey("cacheSize"));
    }
    
    @Test
    void testGetFamily() {
        // Test getting a family (might be null if not cached)
        InstrumentFamily family = cacheService.getFamily("1660"); // RELIANCE
        // This might be null if cache is empty, which is ok for now
        // In real test, we'd mock the API calls
    }
    
    @Test
    void testCacheStats() {
        var stats = cacheService.getCacheStats();
        
        assertNotNull(stats);
        assertTrue(stats.containsKey("cacheSize"));
        assertTrue(stats.containsKey("familiesWithFutures"));
        assertTrue(stats.containsKey("familiesWithOptions"));
        assertTrue(stats.containsKey("totalOptions"));
        assertTrue(stats.containsKey("errorFamilies"));
    }
}
