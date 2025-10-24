package com.kotsin.consumer.transformers;

import com.kotsin.consumer.model.TickData;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.Stores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.kafka.support.serializer.JsonSerde;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enhanced unit tests for CumToDeltaTransformer
 * 
 * CRITICAL: Verifies reset flag logic to prevent phantom volume spikes
 * Purpose: Prevent data corruption on day rollovers
 */
class CumToDeltaTransformerEnhancedTest {

    private TopologyTestDriver driver;
    private org.apache.kafka.streams.TestInputTopic<String, TickData> inputTopic;
    private org.apache.kafka.streams.TestOutputTopic<String, TickData> outputTopic;

    private static final String INPUT_TOPIC = "input";
    private static final String OUTPUT_TOPIC = "output";
    private static final String STORE_NAME = "delta-volume-store-test";

    @BeforeEach
    void setUp() {
        StreamsBuilder builder = new StreamsBuilder();

        builder.addStateStore(Stores.keyValueStoreBuilder(
            Stores.persistentKeyValueStore(STORE_NAME),
            Serdes.String(), Serdes.Long()
        ));

        JsonSerde<TickData> serde = new JsonSerde<>(TickData.class);

        KStream<String, TickData> stream = builder.stream(INPUT_TOPIC, 
            Consumed.with(Serdes.String(), serde));
        KStream<String, TickData> transformed = stream.transform(
            () -> new CumToDeltaTransformer(STORE_NAME), STORE_NAME
        );
        transformed.to(OUTPUT_TOPIC, Produced.with(Serdes.String(), serde));

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "cum-to-delta-enhanced-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        
        driver = new TopologyTestDriver(builder.build(), props);
        inputTopic = driver.createInputTopic(INPUT_TOPIC, 
            Serdes.String().serializer(), serde.serializer());
        outputTopic = driver.createOutputTopic(OUTPUT_TOPIC, 
            Serdes.String().deserializer(), serde.deserializer());
    }

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.close();
        }
    }

    // ========== Basic Delta Calculation Tests ==========

    @Test
    @DisplayName("Delta: First tick sets baseline")
    void testDelta_FirstTick() {
        TickData tick = createTick("STOCK1", 1000);
        inputTopic.pipeInput("STOCK1", tick);

        KeyValue<String, TickData> result = outputTopic.readKeyValue();
        
        assertEquals(1000, result.value.getDeltaVolume(), 
            "First tick: delta = cumulative");
        assertTrue(result.value.getResetFlag(), 
            "First tick should have reset flag = true");
    }

    @Test
    @DisplayName("Delta: Subsequent ticks compute delta correctly")
    void testDelta_SubsequentTicks() {
        inputTopic.pipeInput("STOCK1", createTick("STOCK1", 1000));
        outputTopic.readKeyValue();  // Consume first

        inputTopic.pipeInput("STOCK1", createTick("STOCK1", 1150));
        KeyValue<String, TickData> result = outputTopic.readKeyValue();

        assertEquals(150, result.value.getDeltaVolume(), "Delta should be 150");
        assertFalse(result.value.getResetFlag(), "Normal tick should have reset flag = false");
    }

    // ========== Reset Detection Tests (CRITICAL) ==========

    @Test
    @DisplayName("CRITICAL: Day rollover sets delta = 0, NOT curr")
    void testCritical_DayRollover_NoPhamtomVolume() {
        // Day 1: Build up to 50,000 cumulative
        inputTopic.pipeInput("STOCK1", createTick("STOCK1", 10000));
        outputTopic.readKeyValue();
        
        inputTopic.pipeInput("STOCK1", createTick("STOCK1", 25000));
        outputTopic.readKeyValue();
        
        inputTopic.pipeInput("STOCK1", createTick("STOCK1", 50000));
        KeyValue<String, TickData> lastDay1 = outputTopic.readKeyValue();
        assertEquals(25000, lastDay1.value.getDeltaVolume());
        
        // Day 2: Reset to 1000 (new trading day)
        inputTopic.pipeInput("STOCK1", createTick("STOCK1", 1000));
        KeyValue<String, TickData> firstDay2 = outputTopic.readKeyValue();
        
        // CRITICAL: Delta should be 0 (reset), NOT 1000 (phantom volume)
        assertEquals(0, firstDay2.value.getDeltaVolume(), 
            "CRITICAL: Reset should NOT create phantom volume spike");
        assertTrue(firstDay2.value.getResetFlag(), 
            "Reset flag should be true");
    }

    @Test
    @DisplayName("CRITICAL: Mid-day feed restart detected and handled")
    void testCritical_MidDayReset_NoPhantomVolume() {
        // Normal trading
        inputTopic.pipeInput("STOCK1", createTick("STOCK1", 5000));
        outputTopic.readKeyValue();
        
        inputTopic.pipeInput("STOCK1", createTick("STOCK1", 5500));
        outputTopic.readKeyValue();
        
        inputTopic.pipeInput("STOCK1", createTick("STOCK1", 6000));
        outputTopic.readKeyValue();
        
        // Feed restarts, cumulative resets to 100 mid-day
        inputTopic.pipeInput("STOCK1", createTick("STOCK1", 100));
        KeyValue<String, TickData> resetTick = outputTopic.readKeyValue();
        
        // CRITICAL: Should detect reset and NOT use 100 as delta
        assertEquals(0, resetTick.value.getDeltaVolume(), 
            "Mid-day reset should set delta = 0");
        assertTrue(resetTick.value.getResetFlag(), 
            "Should set reset flag = true");
    }

    @Test
    @DisplayName("Reset: After reset, normal delta calculation resumes")
    void testReset_NormalCalculationAfterReset() {
        // Initial
        inputTopic.pipeInput("STOCK1", createTick("STOCK1", 10000));
        outputTopic.readKeyValue();
        
        // Reset
        inputTopic.pipeInput("STOCK1", createTick("STOCK1", 500));
        KeyValue<String, TickData> resetResult = outputTopic.readKeyValue();
        assertEquals(0, resetResult.value.getDeltaVolume());
        assertTrue(resetResult.value.getResetFlag());
        
        // Resume normal trading
        inputTopic.pipeInput("STOCK1", createTick("STOCK1", 700));
        KeyValue<String, TickData> normalResult = outputTopic.readKeyValue();
        
        assertEquals(200, normalResult.value.getDeltaVolume(), 
            "Should resume normal delta calculation: 700 - 500 = 200");
        assertFalse(normalResult.value.getResetFlag(),
            "Reset flag should be false after reset");
    }

    // ========== Edge Cases ==========

    @Test
    @DisplayName("Edge Case: Zero cumulative volume")
    void testEdgeCase_ZeroCumulative() {
        TickData tick = createTick("STOCK1", 0);
        inputTopic.pipeInput("STOCK1", tick);

        KeyValue<String, TickData> result = outputTopic.readKeyValue();
        
        assertEquals(0, result.value.getDeltaVolume());
        assertTrue(result.value.getResetFlag());
    }

    @Test
    @DisplayName("Edge Case: Negative cumulative handled")
    void testEdgeCase_NegativeCumulative() {
        TickData tick = new TickData();
        tick.setScripCode("STOCK1");
        tick.setTotalQuantity(-100);  // Shouldn't happen, but handle gracefully
        
        inputTopic.pipeInput("STOCK1", tick);
        KeyValue<String, TickData> result = outputTopic.readKeyValue();
        
        // Should use Math.max(0, -100) = 0
        assertEquals(0, result.value.getDeltaVolume());
    }

    @Test
    @DisplayName("Edge Case: Multiple instruments independent")
    void testEdgeCase_MultipleInstrumentsIndependent() {
        // Stock 1
        inputTopic.pipeInput("STOCK1", createTick("STOCK1", 1000));
        KeyValue<String, TickData> r1 = outputTopic.readKeyValue();
        assertEquals(1000, r1.value.getDeltaVolume());
        
        // Stock 2 (different instrument)
        inputTopic.pipeInput("STOCK2", createTick("STOCK2", 2000));
        KeyValue<String, TickData> r2 = outputTopic.readKeyValue();
        assertEquals(2000, r2.value.getDeltaVolume(), 
            "Different instrument should have independent baseline");
        
        // Stock 1 again
        inputTopic.pipeInput("STOCK1", createTick("STOCK1", 1300));
        KeyValue<String, TickData> r3 = outputTopic.readKeyValue();
        assertEquals(300, r3.value.getDeltaVolume(), 
            "STOCK1 delta should be 1300 - 1000 = 300");
    }

    @Test
    @DisplayName("REGRESSION: Fallback to LastQuantity when delta is zero")
    void testRegression_FallbackToLastQuantity() {
        // First tick
        inputTopic.pipeInput("STOCK1", createTick("STOCK1", 1000));
        outputTopic.readKeyValue();
        
        // Second tick: same cumulative (no change) but has LastQuantity
        TickData sameCumTick = createTick("STOCK1", 1000);
        sameCumTick.setLastQuantity(50);  // Single trade of 50
        inputTopic.pipeInput("STOCK1", sameCumTick);
        
        KeyValue<String, TickData> result = outputTopic.readKeyValue();
        
        assertEquals(50, result.value.getDeltaVolume(), 
            "Should fallback to LastQuantity when cumulative unchanged");
        assertFalse(result.value.getResetFlag(), 
            "Should not be reset (just using fallback)");
    }

    @Test
    @DisplayName("Performance: Large volume jumps handled correctly")
    void testPerformance_LargeVolumeJumps() {
        inputTopic.pipeInput("STOCK1", createTick("STOCK1", 1000));
        outputTopic.readKeyValue();
        
        // Very large jump (e.g., block trade)
        inputTopic.pipeInput("STOCK1", createTick("STOCK1", 1000000));
        KeyValue<String, TickData> result = outputTopic.readKeyValue();
        
        assertEquals(999000, result.value.getDeltaVolume(), 
            "Should handle large delta correctly");
        assertFalse(result.value.getResetFlag(), 
            "Large jump is not a reset");
    }

    // ========== Helper Methods ==========

    private TickData createTick(String scripCode, int cumulativeVolume) {
        TickData tick = new TickData();
        tick.setScripCode(scripCode);
        tick.setTotalQuantity(cumulativeVolume);
        tick.setLastRate(100.0);
        tick.setTimestamp(System.currentTimeMillis());
        return tick;
    }
}
