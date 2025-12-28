package com.kotsin.consumer.curated.config;

import com.kotsin.consumer.capital.model.FinalMagnitude;
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
     * Consumer Factory for Curated Signal Processor
     * Uses a UNIQUE consumer group (configurable via properties)
     * Default: reads from EARLIEST for playback testing
     *
     * IMPORTANT: Supports multiple message types via message converter
     */
    @Bean
    public ConsumerFactory<String, Object> curatedConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class); // Read as String first
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);

        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * ObjectMapper for JSON conversion
     */
    @Bean
    public com.fasterxml.jackson.databind.ObjectMapper curatedObjectMapper() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        return mapper;
    }

    /**
     * Kafka Listener Container Factory for Curated Signal Processor
     * Uses StringJsonMessageConverter to handle JSON -> POJO conversion
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> curatedKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(curatedConsumerFactory());
        factory.setConcurrency(3);
        
        // Use StringJsonMessageConverter for proper JSON -> POJO conversion
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
}
