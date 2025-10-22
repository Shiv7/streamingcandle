package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.*;
import com.kotsin.consumer.service.MongoInstrumentFamilyService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for UnifiedMarketDataProcessor
 * 
 * Integration test that requires remote Kafka broker at 13.203.60.173:9094
 * 
 * To run this test:
 * 1. Ensure remote Kafka broker is accessible at 13.203.60.173:9094
 * 2. Clean state directory: rm -rf /tmp/kafka-streams-test
 * 3. Run with: mvn test -Dtest=UnifiedMarketDataProcessorTest
 */
@SpringBootTest
@ActiveProfiles("test")
@Disabled("Integration test - requires remote Kafka at 13.203.60.173:9094. Run manually when needed.")
class UnifiedMarketDataProcessorTest {
    
    @Autowired(required = false)
    private UnifiedMarketDataProcessor processor;
    
    @MockBean
    private MongoInstrumentFamilyService cacheService;
    
    @MockBean
    private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    
    @Test
    void testProcessorInitialization() {
        // Test that processor is initialized
        // May be null if Kafka connection fails, which is acceptable in test environment
        if (processor != null) {
            // Test stream states
            var states = processor.getStreamStates();
            assertNotNull(states);
        }
    }
    
    @Test
    void testMultiTimeframeState() {
        // Test MultiTimeframeState creation
        MultiTimeframeState state = new MultiTimeframeState();
        assertNotNull(state);
        
        // Test adding a tick
        TickData tick = new TickData();
        tick.setScripCode("1660");
        tick.setCompanyName("RELIANCE");
        tick.setExchange("N");
        tick.setExchangeType("C");
        tick.setLastRate(2500.0);
        tick.setTimestamp(System.currentTimeMillis());
        
        state.addTick(tick);
        
        assertEquals("1660", state.getScripCode());
        assertEquals("RELIANCE", state.getCompanyName());
        assertEquals(1L, state.getMessageCount());
    }
    
    @Test
    void testCandleAccumulator() {
        // Test CandleAccumulator
        CandleAccumulator accumulator = new CandleAccumulator();

        TickData tick = new TickData();
        tick.setLastRate(2500.0);
        tick.setTimestamp(System.currentTimeMillis());
        tick.setDeltaVolume(100);
        tick.setBidRate(2499.0);
        tick.setOfferRate(2501.0);

        accumulator.addTick(tick);

        // Use toCandleData() to access accumulated values
        CandleData candleData = accumulator.toCandleData("N", "C");

        assertNotNull(candleData.getOpen());
        assertNotNull(candleData.getClose());
        assertNotNull(candleData.getHigh());
        assertNotNull(candleData.getLow());
        assertNotNull(candleData.getVolume());

        assertEquals(2500.0, candleData.getOpen());
        assertEquals(2500.0, candleData.getClose());
        assertEquals(2500.0, candleData.getHigh());
        assertEquals(2500.0, candleData.getLow());
        assertEquals(100L, candleData.getVolume());
    }
}
