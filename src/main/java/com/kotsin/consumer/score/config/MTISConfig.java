package com.kotsin.consumer.score.config;

import com.kotsin.consumer.score.model.FamilyScore;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * MTISConfig - Configuration for MTIS processor.
 * 
 * Creates KafkaTemplate for FamilyScore output.
 */
@Configuration
public class MTISConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9094}")
    private String bootstrapServers;

    @Value("${mtis.producer.batch.size:16384}")
    private int batchSize;

    @Value("${mtis.producer.linger.ms:5}")
    private int lingerMs;

    @Value("${mtis.producer.buffer.memory:33554432}")
    private long bufferMemory;

    @Bean
    public ProducerFactory<String, FamilyScore> familyScoreProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, FamilyScoreSerializer.class);
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
        configProps.put(ProducerConfig.BUFFER_MEMORY_CONFIG, bufferMemory);
        configProps.put(ProducerConfig.ACKS_CONFIG, "1");  // At least leader ack
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, FamilyScore> familyScoreProducer() {
        return new KafkaTemplate<>(familyScoreProducerFactory());
    }

    /**
     * Custom serializer for FamilyScore
     */
    public static class FamilyScoreSerializer implements org.apache.kafka.common.serialization.Serializer<FamilyScore> {
        
        private final FamilyScore.FamilyScoreSerializer delegate = new FamilyScore.FamilyScoreSerializer();

        @Override
        public byte[] serialize(String topic, FamilyScore data) {
            return delegate.serialize(topic, data);
        }
    }
}
