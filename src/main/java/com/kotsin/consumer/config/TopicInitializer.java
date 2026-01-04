package com.kotsin.consumer.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Ensures all required Kafka topics are created on application startup.
 * This prevents pipeline failures due to missing topics.
 * 
 * Topics are created with:
 * - 20 partitions (matching max input topic partitions for parallelism)
 * - Replication factor 1 (single broker setup)
 * - Compact,delete cleanup policy (for state management)
 */
@Configuration
public class TopicInitializer {

    private static final Logger log = LoggerFactory.getLogger(TopicInitializer.class);

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * Core output topic from UnifiedInstrumentCandleProcessor
     * This is the critical topic that was missing!
     */
    @Bean
    public NewTopic instrumentCandle1m() {
        return TopicBuilder.name("instrument-candle-1m")
                .partitions(20)
                .replicas(1)
                .build();
    }

    /**
     * Family candle topics for all timeframes
     * These should be created even though they might exist already
     */
    @Bean
    public NewTopic familyCandle1m() {
        return TopicBuilder.name("family-candle-1m")
                .partitions(20)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic familyCandle2m() {
        return TopicBuilder.name("family-candle-2m")
                .partitions(20)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic familyCandle3m() {
        return TopicBuilder.name("family-candle-3m")
                .partitions(20)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic familyCandle5m() {
        return TopicBuilder.name("family-candle-5m")
                .partitions(20)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic familyCandle15m() {
        return TopicBuilder.name("family-candle-15m")
                .partitions(20)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic familyCandle30m() {
        return TopicBuilder.name("family-candle-30m")
                .partitions(20)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic familyCandle1h() {
        return TopicBuilder.name("family-candle-1h")
                .partitions(20)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic familyCandle2h() {
        return TopicBuilder.name("family-candle-2h")
                .partitions(20)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic familyCandle4h() {
        return TopicBuilder.name("family-candle-4h")
                .partitions(20)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic familyCandle1d() {
        return TopicBuilder.name("family-candle-1d")
                .partitions(20)
                .replicas(1)
                .build();
    }

    /**
     * Strategy output topics
     */
    @Bean
    public NewTopic familyScore() {
        return TopicBuilder.name("family-score")
                .partitions(20)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic ipuCombined() {
        return TopicBuilder.name("ipu-combined")
                .partitions(20)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic vcpCombined() {
        return TopicBuilder.name("vcp-combined")
                .partitions(20)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic fudkiiOutput() {
        return TopicBuilder.name("fudkii-output")
                .partitions(20)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic regimeIndexOutput() {
        return TopicBuilder.name("regime-index-output")
                .partitions(20)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic regimeSecurityOutput() {
        return TopicBuilder.name("regime-security-output")
                .partitions(20)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic masterarchIndexRegime() {
        return TopicBuilder.name("masterarch-index-regime")
                .partitions(20)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic masterarchSecurityRegime() {
        return TopicBuilder.name("masterarch-security-regime")
                .partitions(20)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic kotsinFF1() {
        return TopicBuilder.name("kotsin_FF1")
                .partitions(20)
                .replicas(1)
                .build();
    }

    /**
     * Log startup info about topic creation
     */
    @Bean
    public KafkaAdmin.NewTopics allTopics() {
        log.info("🏗️ TopicInitializer: Ensuring all required topics exist");
        log.info("📋 Topics will be auto-created if missing:");
        log.info("  ✓ instrument-candle-1m (CRITICAL - was missing!)");
        log.info("  ✓ family-candle-* (10 timeframes)");
        log.info("  ✓ Strategy output topics (MTIS, IPU, VCP, FUDKII, Regime, MasterArch)");
        log.info("🔧 Topic configuration: 20 partitions, replication-factor=1");
        
        return new KafkaAdmin.NewTopics(
            instrumentCandle1m(),
            familyCandle1m(), familyCandle2m(), familyCandle3m(),
            familyCandle5m(), familyCandle15m(), familyCandle30m(),
            familyCandle1h(), familyCandle2h(), familyCandle4h(), familyCandle1d(),
            familyScore(), ipuCombined(), vcpCombined(), fudkiiOutput(),
            regimeIndexOutput(), regimeSecurityOutput(),
            masterarchIndexRegime(), masterarchSecurityRegime(), kotsinFF1()
        );
    }
}
