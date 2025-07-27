package com.kotsin.consumer.config;

import com.kotsin.consumer.util.KafkaRecordTimestampExtractor;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Properties;
import java.util.UUID;

@Component
public class KafkaConfig {

    public static final ZonedDateTime START_3RD_APRIL_915 = ZonedDateTime.of(
            2025, 4, 3, 9, 15, 0, 0, ZoneId.of("Asia/Kolkata")
    );
    public static final ZonedDateTime END_3RD_APRIL_1530 = ZonedDateTime.of(
            2025, 4, 3, 15, 30, 0, 0, ZoneId.of("Asia/Kolkata")
    );

    private static final String BOOTSTRAP_SERVERS = "172.31.12.118:9092";
    
    @Value("${spring.kafka.streams.state-dir:/var/lib/kafka-streams/streamingcandle}")
    private String baseStateDir;

    /**
     * Gets the bootstrap servers configuration.
     *
     * @return The bootstrap servers string.
     */
    public String getBootstrapServers() {
        return BOOTSTRAP_SERVERS;
    }

    /**
     * Retrieves Kafka Streams properties with a given application ID.
     * PRODUCTION FIX: Creates unique state directories to prevent conflicts.
     *
     * @param appId The application ID for the Kafka Streams instance.
     * @return Properties configured for Kafka Streams.
     */
    public Properties getStreamProperties(String appId) {
        Properties props = new Properties();
        // Use a stable application ID to allow for state restoration and prevent topic proliferation.
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, appId);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);
        
        // Configure producer to preserve record timestamps
        props.put(StreamsConfig.PRODUCER_PREFIX + ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        
        // CRITICAL: Configure timestamp handling for the producer
        // This ensures the Kafka message's timestamp matches the record's timestamp, not the producer's time
        props.put("producer.message.timestamp.type", "CreateTime");
        
        // Use improved cache configuration instead of deprecated one
        props.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 10 * 1024 * 1024); // 10MB

        // PRODUCTION FIX: Create unique state directory for each application instance
        String uniqueStateDir = createUniqueStateDir(appId);
        props.put(StreamsConfig.STATE_DIR_CONFIG, uniqueStateDir);
        
        // Configure cleanup policy
        props.put(StreamsConfig.TOPOLOGY_OPTIMIZATION_CONFIG, StreamsConfig.OPTIMIZE);
        
        // Use exactly-once semantics for production reliability
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG, 
                  LogAndContinueExceptionHandler.class.getName());
        
        // Configure auto.offset.reset for consumer
        props.put(StreamsConfig.CONSUMER_PREFIX + "auto.offset.reset", "earliest");
        
        // Production resilience configurations
        props.put(StreamsConfig.RETRY_BACKOFF_MS_CONFIG, 100);
        props.put(StreamsConfig.RECONNECT_BACKOFF_MS_CONFIG, 50);
        props.put(StreamsConfig.REQUEST_TIMEOUT_MS_CONFIG, 40000);
        
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
                System.err.println("WARNING: Could not create state directory " + baseStateDir + 
                                 ", falling back to " + fallbackPath);
                return fallbackPath;
            }
        }
        
        // Return path for this application instance
        return baseStateDir + "/" + appId;
    }
}
