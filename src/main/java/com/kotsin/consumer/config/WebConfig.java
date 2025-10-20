package com.kotsin.consumer.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Web configuration for REST clients
 */
@Configuration
public class WebConfig {
    
    /**
     * Configure RestTemplate bean for external API calls
     * Used by InstrumentFamilyCacheService to fetch data from scripFinder API
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
            .setConnectTimeout(Duration.ofSeconds(10))
            .setReadTimeout(Duration.ofSeconds(30))
            .build();
    }
}

