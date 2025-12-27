package com.kotsin.consumer.config;


import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Properties;

@Component
@Slf4j
public class KafkaConfig {

    // All configuration injected from application.properties
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.streams.state-dir:/tmp/kafka-streams/streamingcandle}")
    private String baseStateDir;

    @Value("${spring.kafka.streams.properties.commit.interval.ms:100}")
    private int commitIntervalMs;

    @Value("${spring.kafka.streams.properties.statestore.cache.max.bytes:104857600}")
    private long statestoreCacheMaxBytes;

    @Value("${spring.kafka.streams.properties.num.stream.threads:2}")
    private int numStreamThreads;

    @Value("${spring.kafka.streams.properties.processing.guarantee:at_least_once}")
    private String processingGuarantee;

    @Value("${spring.kafka.streams.properties.auto.offset.reset:latest}")
    private String autoOffsetReset;

    @Value("${spring.kafka.streams.properties.retry.backoff.ms:100}")
    private long retryBackoffMs;

    @Value("${spring.kafka.streams.properties.reconnect.backoff.ms:50}")
    private long reconnectBackoffMs;

    @Value("${spring.kafka.streams.properties.request.timeout.ms:40000}")
    private int requestTimeoutMs;

    @Value("${spring.kafka.streams.properties.topology.optimization:all}")
    private String topologyOptimization;

    @Value("${spring.kafka.streams.properties.default.deserialization.exception.handler:org.apache.kafka.streams.errors.LogAndContinueExceptionHandler}")
    private String deserializationExceptionHandler;

    @Value("${spring.kafka.streams.properties.producer.message.timestamp.type:CreateTime}")
    private String producerTimestampType;

    /**
     * Gets the bootstrap servers configuration.
     *
     * @return The bootstrap servers string.
     */
    public String getBootstrapServers() {
        return bootstrapServers;
    }

    /**
     * Retrieves Kafka Streams properties with a given application ID.
     * All settings are now configurable via application.properties.
     *
     * @param appId The application ID for the Kafka Streams instance.
     * @return Properties configured for Kafka Streams.
     */
    public Properties getStreamProperties(String appId) {
        Properties props = new Properties();

        // Core configuration
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, appId);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());

        // State directory (unique per application instance)
        String uniqueStateDir = createUniqueStateDir(appId);
        props.put(StreamsConfig.STATE_DIR_CONFIG, uniqueStateDir);

        // Performance tuning (all configurable from properties)
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, commitIntervalMs);
        props.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, statestoreCacheMaxBytes);
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, numStreamThreads);

        // Processing guarantee (at_least_once or exactly_once_v2)
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, processingGuarantee);

        // Topology optimization
        props.put(StreamsConfig.TOPOLOGY_OPTIMIZATION_CONFIG, topologyOptimization);

        // Exception handling
        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                deserializationExceptionHandler);

        // Consumer configuration
        props.put(StreamsConfig.CONSUMER_PREFIX + "auto.offset.reset", autoOffsetReset);

        // Producer configuration
        props.put("producer.message.timestamp.type", producerTimestampType);

        // Resilience configuration
        props.put(StreamsConfig.RETRY_BACKOFF_MS_CONFIG, retryBackoffMs);
        props.put(StreamsConfig.RECONNECT_BACKOFF_MS_CONFIG, reconnectBackoffMs);
        props.put(StreamsConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs);

        return props;
    }
    
    /**
     * Creates a unique state directory for the Kafka Streams application to prevent conflicts.
     * PRODUCTION FIX: Ensures each instance gets its own state directory.
     */
    private String createUniqueStateDir(String appId) {
        // Create base directory if it doesn't exist
        File baseDir = new File(baseStateDir);
        if (!baseDir.exists()) {
            boolean created = baseDir.mkdirs();
            if (!created) {
                // Fallback to temp directory if we can't create in /var/lib
                String fallbackPath = System.getProperty("java.io.tmpdir") + "/kafka-streams/" + appId;
                log.warn("Could not create state directory {}, falling back to {}", baseStateDir, fallbackPath);
                return fallbackPath;
            }
        }

        // Return path for this application instance
        return baseStateDir + "/" + appId;
    }
}
