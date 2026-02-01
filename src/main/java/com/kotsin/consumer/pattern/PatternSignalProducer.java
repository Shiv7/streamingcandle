package com.kotsin.consumer.pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kotsin.consumer.pattern.PatternAnalyzer.PatternResult;
import com.kotsin.consumer.pattern.PatternAnalyzer.DetectedPattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PatternSignalProducer - Publishes pattern detection results to Kafka and Redis.
 *
 * Kafka Topic: pattern-signals
 * Redis Key: pattern:{scripCode}:latest
 *
 * This component converts PatternResult to PatternSignalDTO for dashboard consumption.
 */
@Component
@Slf4j
public class PatternSignalProducer {

    private static final String LOG_PREFIX = "[PATTERN-PRODUCER]";

    @Value("${pattern.signal.kafka.topic:pattern-signals}")
    private String kafkaTopic;

    @Value("${pattern.signal.redis.prefix:pattern:}")
    private String redisPrefix;

    @Value("${pattern.signal.redis.ttl.minutes:30}")
    private int redisTtlMinutes;

    @Value("${pattern.signal.enabled:true}")
    private boolean enabled;

    @Value("${pattern.signal.min.confidence:0.6}")
    private double minConfidence;

    @Autowired(required = false)
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired(required = false)
    private RedisTemplate<String, String> redisTemplate;

    private final ObjectMapper objectMapper;

    public PatternSignalProducer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * Publish pattern results if they contain high-confidence patterns.
     */
    public void publish(PatternResult result) {
        if (!enabled || result == null || result.getPatterns().isEmpty()) {
            return;
        }

        // Filter to high-confidence patterns only
        List<DetectedPattern> significantPatterns = result.getPatterns().stream()
            .filter(p -> p.getConfidence() >= minConfidence)
            .toList();

        if (significantPatterns.isEmpty()) {
            return;
        }

        try {
            // Create signal for each significant pattern
            for (DetectedPattern pattern : significantPatterns) {
                PatternSignalDTO signal = buildPatternSignal(result, pattern);

                // Publish to Redis
                publishToRedis(signal);

                // Publish to Kafka
                publishToKafka(signal);

                log.debug("{} Published pattern {} for {} confidence={}",
                    LOG_PREFIX, pattern.getPatternName(), result.getSymbol(),
                    String.format("%.2f", pattern.getConfidence()));
            }

        } catch (Exception e) {
            log.error("{} Failed to publish pattern for {}: {}",
                LOG_PREFIX, result.getSymbol(), e.getMessage());
        }
    }

    /**
     * Build PatternSignalDTO from pattern result.
     */
    private PatternSignalDTO buildPatternSignal(PatternResult result, DetectedPattern pattern) {
        return PatternSignalDTO.builder()
            .id(UUID.randomUUID().toString())
            .scripCode(result.getScripCode())
            .symbol(result.getSymbol())
            .timeframe(result.getTimeframe())
            .patternType(pattern.getPatternType().name())
            .patternName(pattern.getPatternName())
            .direction(pattern.getDirection())
            .confidence(pattern.getConfidence())
            .description(pattern.getDescription())
            .detectedAt(result.getTimestamp())
            .isActive(true)
            .patternScore(result.getPatternScore())
            .allPatterns(result.getPatternNames())
            .overallBias(result.getOverallBias())
            .build();
    }

    /**
     * Publish to Redis for API access.
     */
    private void publishToRedis(PatternSignalDTO signal) {
        if (redisTemplate == null) return;

        try {
            // Store latest pattern
            String key = redisPrefix + signal.getScripCode() + ":latest";
            String json = objectMapper.writeValueAsString(signal);
            redisTemplate.opsForValue().set(key, json, Duration.ofMinutes(redisTtlMinutes));

            // Add to active patterns list
            String listKey = redisPrefix + "active:" + signal.getTimeframe();
            redisTemplate.opsForList().leftPush(listKey, json);
            redisTemplate.opsForList().trim(listKey, 0, 99); // Keep last 100
            redisTemplate.expire(listKey, Duration.ofMinutes(redisTtlMinutes));

            // Add to pattern type index
            String typeKey = redisPrefix + "type:" + signal.getPatternType();
            redisTemplate.opsForSet().add(typeKey, signal.getScripCode());
            redisTemplate.expire(typeKey, Duration.ofMinutes(redisTtlMinutes));

        } catch (Exception e) {
            log.warn("{} Redis publish failed: {}", LOG_PREFIX, e.getMessage());
        }
    }

    /**
     * Publish to Kafka for real-time updates.
     */
    private void publishToKafka(PatternSignalDTO signal) {
        if (kafkaTemplate == null) return;

        try {
            kafkaTemplate.send(kafkaTopic, signal.getScripCode(), signal);
        } catch (Exception e) {
            log.warn("{} Kafka publish failed: {}", LOG_PREFIX, e.getMessage());
        }
    }

    /**
     * PatternSignalDTO - Pattern signal for dashboard.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PatternSignalDTO {
        private String id;
        private String scripCode;
        private String symbol;
        private String timeframe;
        private String patternType;
        private String patternName;
        private String direction;           // BULLISH, BEARISH, NEUTRAL
        private double confidence;
        private String description;
        private Instant detectedAt;
        private boolean isActive;
        private double patternScore;
        private List<String> allPatterns;   // All patterns detected in same candle
        private String overallBias;

        // Optional context
        private Double entryPrice;
        private Double targetPrice;
        private Double stopLoss;
        private String outcome;             // WIN, LOSS, PENDING
        private Double pnl;
    }
}
