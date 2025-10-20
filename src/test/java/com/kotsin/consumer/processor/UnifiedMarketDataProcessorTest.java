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
 */
@SpringBootTest
@ActiveProfiles("test")
@Disabled("Disabled temporarily while refactor stabilizes; relies on full Spring context and Kafka")
class UnifiedMarketDataProcessorTest {
    
    @Autowired
    private UnifiedMarketDataProcessor processor;
    
    @MockBean
    private MongoInstrumentFamilyService cacheService;
    
    @Test
    void testProcessorInitialization() {
        // Test that processor is initialized
        assertNotNull(processor);
        
        // Test stream states
        var states = processor.getStreamStates();
        assertNotNull(states);
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
