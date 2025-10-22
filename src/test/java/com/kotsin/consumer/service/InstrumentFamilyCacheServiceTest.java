package com.kotsin.consumer.service;

import com.kotsin.consumer.model.InstrumentFamily;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for InstrumentFamilyCacheService
 * 
 * Integration test that requires:
 * 1. Remote Kafka broker at 13.203.60.173:9094
 * 2. MongoDB connection
 * 
 * To run this test:
 * 1. Ensure MongoDB is running at localhost:27017
 * 2. Ensure remote Kafka broker is accessible
 * 3. Run with: mvn test -Dtest=InstrumentFamilyCacheServiceTest
 */
@SpringBootTest
@ActiveProfiles("test")
@Disabled("Integration test - requires MongoDB and remote Kafka. Run manually when needed.")
class InstrumentFamilyCacheServiceTest {
    
    @Autowired
    private MongoInstrumentFamilyService cacheService;
    
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
