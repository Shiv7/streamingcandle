package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.*;
import com.kotsin.consumer.service.InstrumentFamilyCacheService;
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
class UnifiedMarketDataProcessorTest {
    
    @Autowired
    private UnifiedMarketDataProcessor processor;
    
    @MockBean
    private InstrumentFamilyCacheService cacheService;
    
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
        
        accumulator.addTick(tick);
        
        assertNotNull(accumulator.getOpen());
        assertNotNull(accumulator.getClose());
        assertNotNull(accumulator.getHigh());
        assertNotNull(accumulator.getLow());
        assertNotNull(accumulator.getVolume());
    }
}
