package com.kotsin.consumer.aggregator;

import com.kotsin.consumer.model.TickCandle;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.repository.TickCandleRepository;
import com.kotsin.consumer.service.RedisCacheService;
import com.kotsin.consumer.service.ScripMetadataService;
import com.kotsin.consumer.signal.trigger.FudkiiSignalTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TickAggregatorTest {

    @Mock
    private TickCandleRepository tickCandleRepository;

    @Mock
    private RedisCacheService redisCacheService;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private FudkiiSignalTrigger fudkiiSignalTrigger;

    @Mock
    private ScripMetadataService scripMetadataService;

    @InjectMocks
    private TickAggregator tickAggregator;

    @BeforeEach
    void setUp() {
        // Set configuration values
        ReflectionTestUtils.setField(tickAggregator, "enabled", true);
        ReflectionTestUtils.setField(tickAggregator, "outputTopic", "tick-candles-1m");
        ReflectionTestUtils.setField(tickAggregator, "numThreads", 2); // Set threads!

        // Configure ScripMetadataService mock to return the scripCode as symbol
        when(scripMetadataService.getSymbolRoot(anyString()))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // Initialize aggregator (start method)
        tickAggregator.start();
    }

    @Test
    void testStrictEventTimeLogic() {
        // 1. Initial State: No windows
        assertTrue(tickAggregator.aggregationState.isEmpty());

        // 2. Process First Tick (T1 = 10:00:05)
        // Window should be 10:00:00 - 10:01:00
        TickData t1 = createTick("RELIANCE", 100.0, "2024-01-01T10:00:05Z");
        tickAggregator.processRecord(t1, Instant.parse("2024-01-01T10:00:05Z").toEpochMilli());

        // Verify State Created
        assertEquals(1, tickAggregator.aggregationState.size());
        
        // Run emission check - nothing should emit yet because maxEventTime is 10:00:05
        tickAggregator.checkWindowEmission();
        verify(kafkaTemplate, never()).send(any(), any(), any());

        // 3. Process Second Tick (T2 = 10:00:55)
        TickData t2 = createTick("RELIANCE", 101.0, "2024-01-01T10:00:55Z");
        tickAggregator.processRecord(t2, Instant.parse("2024-01-01T10:00:55Z").toEpochMilli());
        
        // Still same window, no emission
        tickAggregator.checkWindowEmission();
        verify(kafkaTemplate, never()).send(any(), any(), any());

        // 4. Process Late Tick (T3 = 10:01:05) -> Moves maxEventTime to 10:01:05
        // This makes 10:00:00 window "closed" (since 10:01:05 > 10:01:00 + buffer)
        // Buffer is 2 seconds in code? "currentWindowEnd.plusSeconds(2)"
        // Window End is 10:01:00. Ref Time is 10:01:05. 10:01:05 > 10:01:02. YES.
        TickData t3 = createTick("RELIANCE", 102.0, "2024-01-01T10:01:05Z");
        tickAggregator.processRecord(t3, Instant.parse("2024-01-01T10:01:05Z").toEpochMilli());

        // Now check emission
        tickAggregator.checkWindowEmission();

        // 5. Verify Emission
        // Should have emitted the 10:00-10:01 candle
        verify(kafkaTemplate, times(1)).send(eq("tick-candles-1m"), eq("RELIANCE"), any(TickCandle.class));
        verify(tickCandleRepository, times(1)).saveAll(any());
        
        // Verify Idempotency ID
        // Capture the argument
        // (Simplified verifying that saveAll was called)
    }

    private TickData createTick(String symbol, double price, String isoTime) {
        long epoch = Instant.parse(isoTime).toEpochMilli();
        TickData tick = new TickData();
        tick.setScripCode(symbol); // Using Symbol as ScripCode for test simplicity
        tick.setCompanyName(symbol); // Needed for detecting type
        tick.setExchange("NSE");
        tick.setExchangeType("CASH");
        tick.setLastRate(price);
        tick.setLastQuantity(100);
        // JSON format for date containing Date(...) often used in code, 
        // but parseEventTime falls back to Kafka timestamp if format fails, 
        // so we can rely on kafkaTimestamp argument in processTick
        tick.setTickDt("/Date(" + epoch + ")/"); 
        return tick;
    }
}
