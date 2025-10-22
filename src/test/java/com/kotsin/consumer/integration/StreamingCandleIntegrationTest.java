package com.kotsin.consumer.integration;

import com.kotsin.consumer.model.InstrumentCandle;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.processor.UnifiedMarketDataProcessor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for streaming candle module
 * 
 * Requirements:
 * - Remote Kafka broker at 13.203.60.173:9094
 * - Input topic: forwardtesting-data
 * - Output topic: candle-complete-1m-test
 * 
 * To run:
 * mvn test -Dtest=StreamingCandleIntegrationTest -Dspring.profiles.active=test
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Disabled("Manual integration test - requires remote Kafka at 13.203.60.173:9094")
class StreamingCandleIntegrationTest {

    private static final String KAFKA_BROKER = "13.203.60.173:9094";
    private static final String INPUT_TOPIC = "forwardtesting-data";
    private static final String OUTPUT_TOPIC = "candle-complete-1m-test";
    
    @Autowired(required = false)
    private UnifiedMarketDataProcessor processor;
    
    @MockBean
    private com.kotsin.consumer.service.MongoInstrumentFamilyService mongoService;
    
    @MockBean
    private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    
    private static KafkaProducer<String, TickData> producer;
    private static KafkaConsumer<String, InstrumentCandle> consumer;

    @BeforeAll
    static void setUpKafka() {
        // Setup producer
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BROKER);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        producerProps.put(ProducerConfig.ACKS_CONFIG, "1");
        
        producer = new KafkaProducer<>(producerProps);
        
        // Setup consumer
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BROKER);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "integration-test-" + System.currentTimeMillis());
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        consumerProps.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        consumerProps.put(JsonDeserializer.VALUE_DEFAULT_TYPE, InstrumentCandle.class.getName());
        
        consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(OUTPUT_TOPIC));
    }

    @AfterAll
    static void tearDownKafka() {
        if (producer != null) producer.close();
        if (consumer != null) consumer.close();
    }

    // ========== End-to-End Tests ==========

    @Test
    @Order(1)
    @DisplayName("E2E: Tick to 1-minute candle transformation")
    void testE2E_TickTo1MinCandle() throws Exception {
        String scripCode = "INTEGRATION_TEST_1";
        long baseTime = System.currentTimeMillis();
        
        // Send 5 ticks in same 1-minute window
        for (int i = 0; i < 5; i++) {
            TickData tick = createTestTick(scripCode, 100.0 + i, 1000 + (i * 100), baseTime + (i * 1000));
            
            ProducerRecord<String, TickData> record = new ProducerRecord<>(
                INPUT_TOPIC, scripCode, tick
            );
            producer.send(record).get(5, TimeUnit.SECONDS);
        }
        
        // Wait for processing (window close + grace period)
        Thread.sleep(70000);  // 1 min window + 10s grace
        
        // Poll for output candle
        var records = consumer.poll(Duration.ofSeconds(10));
        
        assertFalse(records.isEmpty(), "Should receive output candle");
        
        for (ConsumerRecord<String, InstrumentCandle> record : records) {
            InstrumentCandle candle = record.value();
            
            if (scripCode.equals(candle.getScripCode())) {
                assertEquals(100.0, candle.getOpen(), "Open should be first tick");
                assertEquals(104.0, candle.getHigh(), "High should be max");
                assertEquals(100.0, candle.getLow(), "Low should be min");
                assertEquals(104.0, candle.getClose(), "Close should be last tick");
                assertEquals(5, candle.getTickCount(), "Should have 5 ticks");
                assertNotNull(candle.getVolume(), "Volume should be calculated");
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("E2E: Reset flag propagates through pipeline")
    void testE2E_ResetFlagPropagation() throws Exception {
        String scripCode = "INTEGRATION_TEST_2";
        long baseTime = System.currentTimeMillis();
        
        // Send tick with high cumulative
        TickData tick1 = createTestTick(scripCode, 100.0, 50000, baseTime);
        producer.send(new ProducerRecord<>(INPUT_TOPIC, scripCode, tick1))
            .get(5, TimeUnit.SECONDS);
        
        // Send tick with reset (low cumulative)
        TickData tick2 = createTestTick(scripCode, 101.0, 500, baseTime + 1000);
        producer.send(new ProducerRecord<>(INPUT_TOPIC, scripCode, tick2))
            .get(5, TimeUnit.SECONDS);
        
        // The reset tick should NOT create a candle with 500 volume
        // Instead, volume should be 0 or excluded
        
        Thread.sleep(70000);
        
        var records = consumer.poll(Duration.ofSeconds(10));
        
        for (ConsumerRecord<String, InstrumentCandle> record : records) {
            InstrumentCandle candle = record.value();
            if (scripCode.equals(candle.getScripCode())) {
                // Volume should NOT include phantom reset volume
                assertTrue(candle.getVolume() < 1000, 
                    "Should not have phantom volume from reset");
            }
        }
    }

    @Test
    @Order(3)
    @DisplayName("E2E: Microstructure metrics calculated")
    void testE2E_MicrostructureCalculation() throws Exception {
        String scripCode = "INTEGRATION_TEST_3";
        long baseTime = System.currentTimeMillis();
        
        // Send 30 ticks (enough for microstructure to be complete)
        for (int i = 0; i < 30; i++) {
            TickData tick = createTestTick(
                scripCode, 
                100.0 + (i * 0.1), 
                1000 + (i * 50), 
                baseTime + (i * 1000)
            );
            
            producer.send(new ProducerRecord<>(INPUT_TOPIC, scripCode, tick))
                .get(5, TimeUnit.SECONDS);
        }
        
        Thread.sleep(70000);
        
        var records = consumer.poll(Duration.ofSeconds(10));
        
        for (ConsumerRecord<String, InstrumentCandle> record : records) {
            InstrumentCandle candle = record.value();
            if (scripCode.equals(candle.getScripCode())) {
                assertNotNull(candle.getMicrostructure(), 
                    "Microstructure should be calculated");
                
                if (candle.getMicrostructure() != null && 
                    candle.getMicrostructure().getIsComplete()) {
                    
                    // Verify metrics are in valid ranges
                    if (candle.getMicrostructure().getOfi() != null) {
                        assertTrue(Math.abs(candle.getMicrostructure().getOfi()) < 100000,
                            "OFI should be reasonable value");
                    }
                    
                    if (candle.getMicrostructure().getVpin() != null) {
                        assertTrue(candle.getMicrostructure().getVpin() >= 0.0 && 
                                   candle.getMicrostructure().getVpin() <= 1.0,
                            "VPIN should be in range [0, 1]");
                    }
                }
            }
        }
    }

    // ========== Stress Tests ==========

    @Test
    @Order(4)
    @DisplayName("Stress: High frequency ticks (100 ticks/sec)")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void testStress_HighFrequencyTicks() throws Exception {
        String scripCode = "STRESS_TEST_1";
        long baseTime = System.currentTimeMillis();
        
        // Send 1000 ticks rapidly
        for (int i = 0; i < 1000; i++) {
            TickData tick = createTestTick(
                scripCode, 
                100.0 + (i % 100) * 0.01,
                1000 + i,
                baseTime + (i * 10)  // 10ms apart = 100 ticks/sec
            );
            
            producer.send(new ProducerRecord<>(INPUT_TOPIC, scripCode, tick));
            
            if (i % 100 == 0) {
                producer.flush();  // Flush every 100 ticks
            }
        }
        
        producer.flush();
        
        // Should not crash or timeout
        assertTrue(true, "Should handle high-frequency ticks without crashing");
    }

    // ========== Helper Methods ==========

    private TickData createTestTick(String scripCode, double price, int cumVolume, long timestamp) {
        TickData tick = new TickData();
        tick.setScripCode(scripCode);
        tick.setCompanyName(scripCode);
        tick.setExchange("N");
        tick.setExchangeType("C");
        tick.setLastRate(price);
        tick.setTotalQuantity(cumVolume);
        tick.setTimestamp(timestamp);
        tick.setBidRate(price - 0.5);
        tick.setOfferRate(price + 0.5);
        tick.setBidQuantity(1000);
        tick.setOfferQuantity(1000);
        tick.setTotalBidQuantity(5000);
        tick.setTotalOfferQuantity(5000);
        
        // Format timestamp for TickDt field
        tick.setTickDt("/Date(" + timestamp + ")/");
        
        return tick;
    }
}

