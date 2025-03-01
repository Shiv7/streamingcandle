package com.kotsin.consumer.config;

import com.kotsin.consumer.util.KafkaRecordTimestampExtractor;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.springframework.stereotype.Component;

import java.util.Properties;

@Component
public class KafkaConfig {
    private static final String BOOTSTRAP_SERVERS = "172.31.0.121:9092";

    /**
     * Retrieves Kafka Streams properties with a given application ID.
     *
     * @param appId The application ID for the Kafka Streams instance.
     * @return Properties configured for Kafka Streams.
     */
    public Properties getStreamProperties(String appId) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, appId);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);
        // Disable caching so records are processed & forwarded promptly
        props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 0);

        return props;
    }
}
