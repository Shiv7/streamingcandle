package com.kotsin.consumer.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;

import java.util.HashMap;
import java.util.Map;

/**
 * CommonKafkaConfig - Shared Kafka configuration for all processors
 *
 * Provides:
 * - commonKafkaListenerContainerFactory: For JSON message consumption
 * - curatedKafkaListenerContainerFactory: Alias for backward compatibility
 * - Shared ObjectMapper configuration
 */
@Configuration
@EnableKafka
public class CommonKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${kafka.consumer.group-id:streaming-candle-processor-v2}")
    private String consumerGroupId;

    @Value("${kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    /**
     * Shared ObjectMapper for JSON conversion
     */
    @Bean
    @Primary
    public ObjectMapper commonObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    /**
     * Consumer Factory for all processors
     */
    @Bean
    public ConsumerFactory<String, String> commonConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000); // 5 minutes
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);

        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Primary Kafka Listener Container Factory
     * Uses StringJsonMessageConverter for automatic JSON -> POJO conversion
     */
    @Bean
    @Primary
    public ConcurrentKafkaListenerContainerFactory<String, String> commonKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(commonConsumerFactory());
        factory.setConcurrency(3);

        StringJsonMessageConverter converter = new StringJsonMessageConverter(commonObjectMapper());
        factory.setRecordMessageConverter(converter);

        return factory;
    }

    /**
     * Alias for backward compatibility with existing processors
     * that use curatedKafkaListenerContainerFactory
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> curatedKafkaListenerContainerFactory() {
        return commonKafkaListenerContainerFactory();
    }
}
