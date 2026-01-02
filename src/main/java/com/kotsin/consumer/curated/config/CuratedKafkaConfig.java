package com.kotsin.consumer.curated.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kotsin.consumer.capital.model.FinalMagnitude;
import com.kotsin.consumer.curated.model.BBSuperTrendSignal;
import com.kotsin.consumer.curated.model.CuratedSignal;
import com.kotsin.consumer.model.IPUOutput;
import com.kotsin.consumer.model.MTVCPOutput;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.regime.model.ACLOutput;
import com.kotsin.consumer.regime.model.IndexRegime;
import com.kotsin.consumer.regime.model.SecurityRegime;
import com.kotsin.consumer.signal.model.CSSOutput;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * CuratedKafkaConfig - Kafka configuration for the Curated Signal System
 *
 * This creates a SEPARATE consumer group and producer for the curated system.
 * It does NOT interfere with existing Kafka configurations.
 */
@Configuration
@EnableKafka
public class CuratedKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${curated.consumer.group-id:curated-signal-processor-v1}")
    private String consumerGroupId;

    @Value("${curated.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    /**
     * ObjectMapper for JSON conversion - shared across all consumers
     */
    @Bean
    public ObjectMapper curatedObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    /**
     * Consumer Factory for Curated Signal Processor
     * Uses StringDeserializer for values - conversion happens in MessageConverter
     */
    @Bean
    public ConsumerFactory<String, String> curatedConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        
        // CRITICAL: Increase poll interval to prevent timeout during async processing
        // Default is 5 minutes, but with async processing we can handle longer operations
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000); // 5 minutes
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10); // Process smaller batches
        
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Kafka Listener Container Factory for Curated Signal Processor
     * StringJsonMessageConverter converts JSON String -> target POJO based on method parameter type
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> curatedKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(curatedConsumerFactory());
        factory.setConcurrency(3);
        
        // StringJsonMessageConverter uses method parameter type for conversion
        org.springframework.kafka.support.converter.StringJsonMessageConverter converter = 
            new org.springframework.kafka.support.converter.StringJsonMessageConverter(curatedObjectMapper());
        factory.setRecordMessageConverter(converter);
        
        return factory;
    }

    /**
     * Producer Factory for Curated Signals
     * Produces to NEW topic: trading-signals-curated
     */
    @Bean
    public ProducerFactory<String, CuratedSignal> curatedSignalProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);

        return new DefaultKafkaProducerFactory<>(props);
    }

    /**
     * Kafka Template for sending Curated Signals
     */
    @Bean
    public KafkaTemplate<String, CuratedSignal> curatedSignalKafkaTemplate() {
        return new KafkaTemplate<>(curatedSignalProducerFactory());
    }

    // ========== BB + SuperTrend Producer Configuration ==========

    /**
     * Producer Factory for BB+SuperTrend Signals
     * Produces to NEW topic: bb-supertrend-signals
     */
    @Bean
    public ProducerFactory<String, BBSuperTrendSignal> bbSuperTrendProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);

        return new DefaultKafkaProducerFactory<>(props);
    }

    /**
     * Kafka Template for sending BB+SuperTrend Signals
     */
    @Bean
    public KafkaTemplate<String, BBSuperTrendSignal> bbSuperTrendKafkaTemplate() {
        return new KafkaTemplate<>(bbSuperTrendProducerFactory());
    }
}
