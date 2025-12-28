package com.kotsin.consumer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration validator to ensure all required properties are set
 * 
 * BEST PRACTICE: Fail fast on invalid configuration
 * PRODUCTION READY: Validate configuration on startup
 */
@Component
@Slf4j
public class ConfigurationValidator {

    @Value("${spring.profiles.active:default}")
    private String activeProfile;
    
    @Value("${spring.kafka.bootstrap-servers:}")
    private String bootstrapServers;
    
    @Value("${spring.kafka.streams.application-id:}")
    private String applicationId;
    
    @Value("${unified.input.topic.ticks:}")
    private String ticksTopic;
    
    @Value("${unified.input.topic.oi:}")
    private String oiTopic;
    
    @Value("${unified.input.topic.orderbook:}")
    private String orderbookTopic;
    
    @Value("${unified.output.topic.instrument:}")
    private String instrumentOutputTopic;
    
    @Value("${spring.data.mongodb.uri:}")
    private String mongoUri;
    
    @Value("${trading.hours.nse.start:09:15}")
    private String nseStartTime;
    
    @Value("${trading.hours.nse.end:15:30}")
    private String nseEndTime;

    @EventListener(ApplicationReadyEvent.class)
    public void validateConfiguration() {
        // Skip validation in test mode
        if ("test".equals(activeProfile)) {
            log.info("⏭️ Skipping configuration validation in test mode");
            return;
        }
        
        log.info("🔍 Validating application configuration...");
        
        List<String> errors = new ArrayList<>();
        
        // Validate Kafka configuration
        if (isNullOrEmpty(bootstrapServers)) {
            errors.add("spring.kafka.bootstrap-servers is not configured");
        }
        
        if (isNullOrEmpty(applicationId)) {
            errors.add("spring.kafka.streams.application-id is not configured");
        }
        
        // Validate input topics
        if (isNullOrEmpty(ticksTopic)) {
            errors.add("unified.input.topic.ticks is not configured");
        }
        
        if (isNullOrEmpty(oiTopic)) {
            errors.add("unified.input.topic.oi is not configured");
        }
        
        if (isNullOrEmpty(orderbookTopic)) {
            errors.add("unified.input.topic.orderbook is not configured");
        }
        
        // Validate output topics (NEW: instrument candle topic instead of legacy)
        if (isNullOrEmpty(instrumentOutputTopic)) {
            log.warn("⚠️ unified.output.topic.instrument is not configured - using default 'instrument-candle-1m'");
        }
        
        // Validate MongoDB configuration
        if (isNullOrEmpty(mongoUri)) {
            log.warn("⚠️ spring.data.mongodb.uri is not configured - MongoDB features will be disabled");
        }
        
        // Validate trading hours
        try {
            LocalTime.parse(nseStartTime);
            LocalTime.parse(nseEndTime);
        } catch (Exception e) {
            errors.add("Invalid trading hours format: " + e.getMessage());
        }
        
        // Report validation results
        if (!errors.isEmpty()) {
            log.error("❌ Configuration validation failed with {} errors:", errors.size());
            errors.forEach(error -> log.error("  - {}", error));
            throw new IllegalStateException("Configuration validation failed. Please fix the errors above.");
        }
        
        log.info("✅ Configuration validation passed");
        logConfigurationSummary();
    }

    private void logConfigurationSummary() {
        log.info("📋 Configuration Summary:");
        log.info("  Kafka Bootstrap Servers: {}", bootstrapServers);
        log.info("  Application ID: {}", applicationId);
        log.info("  Input Topics: ticks={}, oi={}, orderbook={}", ticksTopic, oiTopic, orderbookTopic);
        log.info("  Output Topics: instrument={}", instrumentOutputTopic);
        log.info("  MongoDB URI: {}", maskUri(mongoUri));
        log.info("  Trading Hours: {} - {}", nseStartTime, nseEndTime);
    }

    private boolean isNullOrEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }

    private String maskUri(String uri) {
        if (isNullOrEmpty(uri)) {
            return "not configured";
        }
        // Mask password in URI
        return uri.replaceAll(":[^:@]+@", ":****@");
    }
}
