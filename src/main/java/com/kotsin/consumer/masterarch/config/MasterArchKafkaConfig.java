package com.kotsin.consumer.masterarch.config;

import com.kotsin.consumer.masterarch.model.FinalOpportunityScore;
import com.kotsin.consumer.masterarch.model.IndexContextScore;
import com.kotsin.consumer.masterarch.model.SecurityContextScore;
import com.kotsin.consumer.masterarch.model.SignalStrengthScore;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * MasterArchKafkaConfig - Kafka configuration for MASTER ARCHITECTURE signal emission.
 *
 * Produces to:
 * - kotsin_FF1  : Final trade/no-trade decision
 * - masterarch-index-regime  : Index context scores
 * - masterarch-security-regime : Security context scores
 * - trade-position-size      : Position sizing recommendations
 *
 * Pattern follows existing MTISConfig and CuratedKafkaConfig.
 */
@Configuration
@EnableKafka
public class MasterArchKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${masterarch.producer.batch.size:16384}")
    private int batchSize;

    @Value("${masterarch.producer.linger.ms:5}")
    private int lingerMs;

    @Value("${masterarch.producer.buffer.memory:33554432}")
    private long bufferMemory;

    @Value("${masterarch.consumer.group-id:masterarch-consumer-v1}")
    private String consumerGroupId;

    @Value("${masterarch.consumer.auto-offset-reset:latest}")
    private String autoOffsetReset;

    // ==================== FINAL OPPORTUNITY SCORE PRODUCER ====================

    @Bean
    public ProducerFactory<String, FinalOpportunityScore> finalOpportunityScoreProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, FinalOpportunityScoreSerializer.class);
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
        configProps.put(ProducerConfig.BUFFER_MEMORY_CONFIG, bufferMemory);
        configProps.put(ProducerConfig.ACKS_CONFIG, "1");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, FinalOpportunityScore> finalOpportunityScoreProducer() {
        return new KafkaTemplate<>(finalOpportunityScoreProducerFactory());
    }

    /**
     * Custom serializer for FinalOpportunityScore - delegates to model's built-in serializer
     */
    public static class FinalOpportunityScoreSerializer implements org.apache.kafka.common.serialization.Serializer<FinalOpportunityScore> {
        
        private final FinalOpportunityScore.FinalOpportunityScoreSerializer delegate = 
                new FinalOpportunityScore.FinalOpportunityScoreSerializer();

        @Override
        public byte[] serialize(String topic, FinalOpportunityScore data) {
            return delegate.serialize(topic, data);
        }
    }

    // ==================== INDEX CONTEXT SCORE PRODUCER ====================

    @Bean
    public ProducerFactory<String, IndexContextScore> indexContextScoreProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, IndexContextScoreSerializer.class);
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
        configProps.put(ProducerConfig.ACKS_CONFIG, "1");
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, IndexContextScore> indexContextScoreProducer() {
        return new KafkaTemplate<>(indexContextScoreProducerFactory());
    }

    public static class IndexContextScoreSerializer implements org.apache.kafka.common.serialization.Serializer<IndexContextScore> {
        
        private final IndexContextScore.IndexContextScoreSerializer delegate = 
                new IndexContextScore.IndexContextScoreSerializer();

        @Override
        public byte[] serialize(String topic, IndexContextScore data) {
            return delegate.serialize(topic, data);
        }
    }

    // ==================== SECURITY CONTEXT SCORE PRODUCER ====================

    @Bean
    public ProducerFactory<String, SecurityContextScore> securityContextScoreProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, SecurityContextScoreSerializer.class);
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
        configProps.put(ProducerConfig.ACKS_CONFIG, "1");
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, SecurityContextScore> securityContextScoreProducer() {
        return new KafkaTemplate<>(securityContextScoreProducerFactory());
    }

    public static class SecurityContextScoreSerializer implements org.apache.kafka.common.serialization.Serializer<SecurityContextScore> {
        
        private final SecurityContextScore.SecurityContextScoreSerializer delegate = 
                new SecurityContextScore.SecurityContextScoreSerializer();

        @Override
        public byte[] serialize(String topic, SecurityContextScore data) {
            return delegate.serialize(topic, data);
        }
    }

    // ==================== SIGNAL STRENGTH SCORE PRODUCER ====================

    @Bean
    public ProducerFactory<String, SignalStrengthScore> signalStrengthScoreProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, SignalStrengthScoreSerializer.class);
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
        configProps.put(ProducerConfig.ACKS_CONFIG, "1");
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, SignalStrengthScore> signalStrengthScoreProducer() {
        return new KafkaTemplate<>(signalStrengthScoreProducerFactory());
    }

    public static class SignalStrengthScoreSerializer implements org.apache.kafka.common.serialization.Serializer<SignalStrengthScore> {

        private final SignalStrengthScore.SignalStrengthScoreSerializer delegate =
                new SignalStrengthScore.SignalStrengthScoreSerializer();

        @Override
        public byte[] serialize(String topic, SignalStrengthScore data) {
            return delegate.serialize(topic, data);
        }
    }

    // ==================== CONSUMER CONFIGURATION (for FUDKIIProcessor) ====================

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(2);
        return factory;
    }

    // ==================== STRING KAFKA TEMPLATE (for FUDKIIProcessor) ====================

    @Bean
    public ProducerFactory<String, String> stringProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
        configProps.put(ProducerConfig.BUFFER_MEMORY_CONFIG, bufferMemory);
        configProps.put(ProducerConfig.ACKS_CONFIG, "1");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(stringProducerFactory());
    }
}
