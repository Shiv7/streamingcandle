package com.kotsin.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


/**
 * Spring Boot Application to initialize Kafka Streams for various candlestick durations.
 */
@SpringBootApplication(scanBasePackages = {"com.kotsin.consumer", "com.kotsin.replay"})
public class ConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsumerApplication.class, args);
    }
}
