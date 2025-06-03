package com.kotsin.consumer;

import com.kotsin.consumer.processor.CandlestickProcessor;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Spring Boot Application to initialize Kafka Streams for various candlestick durations.
 */
@SpringBootApplication
public class ConsumerApplication {

    @Autowired
    private CandlestickProcessor candlestickProcessor;

    // Your Kafka broker address
    private static final String BOOTSTRAP_SERVERS = "172.31.12.118:9092";

    /**
     * Mapping of unique app IDs to the desired window size, in minutes.
     * E.g.: "realtime-candle-1min" -> 1, etc.
     */
    private static final Map<String, Integer> CANDLESTICK_WINDOWS = Map.of(
            "realtime-candle-1min", 1,
            "realtime-candle-2min", 2,
            "realtime-candle-3min", 3,
            "realtime-candle-5min", 5,
            "realtime-candle-15min", 15,
            "realtime-candle-30min", 30
    );

    @PostConstruct
    public void runStream() {
        // Ensure we have all required topics created
        ensureTopicsExist();

        // Start separate Kafka Streams pipelines for each window size
        for (Map.Entry<String, Integer> entry : CANDLESTICK_WINDOWS.entrySet()) {
            String appId = entry.getKey();
            int windowSize = entry.getValue();

            // For windowSize == 1, consume raw stock events from "forwardtesting-data"
            // For all else, consume from "1-min-candle"
            String inputTopic = (windowSize == 1) ? "forwardtesting-data" : "1-min-candle";

            // Output topic is e.g. "1-min-candle", "2-min-candle", etc.
            String outputTopic = windowSize + "-min-candle";

            System.out.println("Processing window: " + windowSize
                    + " min | Input Topic: " + inputTopic
                    + " | Output Topic: " + outputTopic);

            // Build & start the stream for this window size
            candlestickProcessor.process(appId, inputTopic, outputTopic, windowSize);
        }
    }

    /**
     * Creates required topics if they do not already exist.
     */
    private void ensureTopicsExist() {
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);

        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            List<NewTopic> topicsToCreate = List.of(
                    // Candle topics (existing)
                    new NewTopic("1-min-candle", 3, (short) 1),
                    new NewTopic("2-min-candle", 3, (short) 1),
                    new NewTopic("3-min-candle", 3, (short) 1),
                    new NewTopic("5-min-candle", 3, (short) 1),
                    new NewTopic("15-min-candle", 3, (short) 1),
                    new NewTopic("30-min-candle", 3, (short) 1),
                    
                    // Indicator topics (MISSING - these are critical for the strategy!)
                    new NewTopic("1-min-candle-indicators", 3, (short) 1),
                    new NewTopic("2-min-candle-indicators", 3, (short) 1),
                    new NewTopic("3-min-candle-indicators", 3, (short) 1),
                    new NewTopic("5-min-candle-indicators", 3, (short) 1),
                    new NewTopic("15-min-candle-indicators", 3, (short) 1),
                    new NewTopic("30-min-candle-indicators", 3, (short) 1),
                    
                    // Strategy signal topics
                    new NewTopic("bb-supertrend-signals", 3, (short) 1),
                    new NewTopic("fudkii_Signal", 3, (short) 1)
            );

            adminClient.createTopics(topicsToCreate);
            System.out.println("All required topics created successfully!");
        } catch (Exception e) {
            System.err.println("Error creating topics: " + e.getMessage());
            // Don't fail startup if topics already exist
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(ConsumerApplication.class, args);
    }
}
