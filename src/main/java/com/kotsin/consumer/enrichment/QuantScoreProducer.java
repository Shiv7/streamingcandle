package com.kotsin.consumer.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kotsin.consumer.model.StrategyState;
import com.kotsin.consumer.model.StrategyState.*;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.signal.model.FudkiiScore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * QuantScoreProducer - Publishes quantitative scores to Kafka and Redis.
 *
 * This component bridges the gap between the signal engine and the dashboard.
 * It converts internal FudkiiScore to dashboard-friendly QuantScoreDTO and
 * publishes to both Kafka (for real-time updates) and Redis (for API access).
 *
 * Kafka Topic: quant-scores
 * Redis Key: quant:score:{scripCode}:{timeframe}
 */
@Component
@Slf4j
public class QuantScoreProducer {

    private static final String LOG_PREFIX = "[QUANT-PRODUCER]";

    @Value("${quant.score.kafka.topic:quant-scores}")
    private String kafkaTopic;

    @Value("${quant.score.redis.prefix:quant:score:}")
    private String redisPrefix;

    @Value("${quant.score.redis.ttl.minutes:10}")
    private int redisTtlMinutes;

    @Value("${quant.score.enabled:true}")
    private boolean enabled;

    @Autowired(required = false)
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired(required = false)
    private RedisTemplate<String, String> redisTemplate;

    private final ObjectMapper objectMapper;

    public QuantScoreProducer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * Publish a QuantScore from FudkiiScore and strategy states.
     */
    public void publish(
            UnifiedCandle candle,
            FudkiiScore fudkiiScore,
            VcpState vcpState,
            IpuState ipuState,
            PivotState pivotState,
            List<String> detectedPatterns) {

        if (!enabled || fudkiiScore == null || candle == null) {
            return;
        }

        try {
            QuantScoreDTO dto = buildQuantScore(candle, fudkiiScore, vcpState, ipuState, pivotState, detectedPatterns);

            // Publish to Redis
            publishToRedis(dto);

            // Publish to Kafka
            publishToKafka(dto);

            log.debug("{} Published score for {} timeframe={} score={}",
                LOG_PREFIX, dto.getSymbol(), dto.getTimeframe(),
                String.format("%.1f", dto.getQuantScore()));

        } catch (Exception e) {
            log.error("{} Failed to publish quant score for {}: {}",
                LOG_PREFIX, candle.getSymbol(), e.getMessage());
        }
    }

    /**
     * Build QuantScoreDTO from internal models.
     */
    private QuantScoreDTO buildQuantScore(
            UnifiedCandle candle,
            FudkiiScore fudkiiScore,
            VcpState vcpState,
            IpuState ipuState,
            PivotState pivotState,
            List<String> detectedPatterns) {

        // Build score breakdown
        QuantScoreDTO.ScoreBreakdown breakdown = QuantScoreDTO.ScoreBreakdown.builder()
            .flowScore(normalize(fudkiiScore.getFlowScore()))
            .urgencyScore(normalize(fudkiiScore.getUrgencyScore()))
            .directionScore(normalize(fudkiiScore.getDirectionScore()))
            .kyleScore(normalize(fudkiiScore.getKyleScore()))
            .imbalanceScore(normalize(fudkiiScore.getImbalanceScore()))
            .intensityScore(normalize(fudkiiScore.getIntensityScore()))
            .patternScore(detectedPatterns != null && !detectedPatterns.isEmpty() ? 70.0 : 0.0)
            .trendScore(calculateTrendScore(pivotState))
            .volumeProfileScore(calculateVolumeProfileScore(vcpState))
            .microstructureScore(calculateMicrostructureScore(candle))
            .optionsFlowScore(calculateOptionsFlowScore(candle))
            .confluenceScore(calculateConfluenceScore(vcpState, ipuState, pivotState))
            .build();

        // Determine trend direction
        String trendDirection = "SIDEWAYS";
        if (pivotState != null) {
            if ("UPTREND".equals(pivotState.getStructure())) trendDirection = "UPTREND";
            else if ("DOWNTREND".equals(pivotState.getStructure())) trendDirection = "DOWNTREND";
        }

        // Determine momentum state
        String momentumState = "STEADY";
        if (ipuState != null && ipuState.getCurrentMomentumState() != null) {
            momentumState = ipuState.getCurrentMomentumState();
        }

        // Get exhaustion score
        double exhaustionScore = 0;
        if (ipuState != null) {
            exhaustionScore = ipuState.getCurrentExhaustion();
        }

        // Get nearest support/resistance
        Double nearestSupport = null;
        Double nearestResistance = null;
        if (vcpState != null) {
            if (vcpState.getSupportClusters() != null && !vcpState.getSupportClusters().isEmpty()) {
                nearestSupport = vcpState.getSupportClusters().get(0).getPrice();
            }
            if (vcpState.getResistanceClusters() != null && !vcpState.getResistanceClusters().isEmpty()) {
                nearestResistance = vcpState.getResistanceClusters().get(0).getPrice();
            }
        }

        // Calculate volume ratio (using volume delta as proxy for activity)
        double volumeRatio = 1.0;
        // Note: avgVolume20 not available on UnifiedCandle, use volumeDelta ratio instead
        if (candle.getVolume() > 0 && candle.getVolumeDelta() != 0) {
            // Use volume delta as a proxy for activity level
            volumeRatio = Math.abs((double) candle.getVolumeDelta()) / candle.getVolume();
        }

        return QuantScoreDTO.builder()
            .scripCode(candle.getScripCode())
            .symbol(candle.getSymbol())
            .companyName(candle.getCompanyName())
            .timeframe(fudkiiScore.getTimeframe() != null ? fudkiiScore.getTimeframe() : "5m")
            .timestamp(Instant.now())
            .quantScore(fudkiiScore.getCompositeScore())
            .direction(fudkiiScore.getDirection() != null ? fudkiiScore.getDirection().name() : "NEUTRAL")
            .confidence(fudkiiScore.getConfidence())
            .signalStrength(fudkiiScore.getStrength() != null ? fudkiiScore.getStrength().name() : "WEAK")
            .breakdown(breakdown)
            .isWatchSetup(fudkiiScore.isWatchSetup())
            .isActiveTrigger(fudkiiScore.isActiveTrigger())
            .reason(fudkiiScore.getReason())
            .trendDirection(trendDirection)
            .momentumState(momentumState)
            .exhaustionScore(exhaustionScore)
            .detectedPatterns(detectedPatterns != null ? detectedPatterns : new ArrayList<>())
            .patternConfidence(detectedPatterns != null && !detectedPatterns.isEmpty() ? 0.7 : 0.0)
            .nearestSupport(nearestSupport)
            .nearestResistance(nearestResistance)
            .currentPrice(candle.getClose())
            .atrPercent(null)  // ATR not available on UnifiedCandle
            .volumeRatio(volumeRatio)
            .highVolume(volumeRatio > 0.3)  // Adjusted threshold for delta ratio
            .build();
    }

    /**
     * Publish to Redis for API access.
     */
    private void publishToRedis(QuantScoreDTO dto) {
        if (redisTemplate == null) return;

        try {
            String key = redisPrefix + dto.getScripCode() + ":" + dto.getTimeframe();
            String json = objectMapper.writeValueAsString(dto);
            redisTemplate.opsForValue().set(key, json, Duration.ofMinutes(redisTtlMinutes));

            // Also maintain a sorted set for ranking
            String rankKey = "quant:rank:" + dto.getTimeframe();
            redisTemplate.opsForZSet().add(rankKey, dto.getScripCode(), dto.getQuantScore());
            redisTemplate.expire(rankKey, Duration.ofMinutes(redisTtlMinutes));

        } catch (Exception e) {
            log.warn("{} Redis publish failed: {}", LOG_PREFIX, e.getMessage());
        }
    }

    /**
     * Publish to Kafka for real-time updates.
     */
    private void publishToKafka(QuantScoreDTO dto) {
        if (kafkaTemplate == null) return;

        try {
            kafkaTemplate.send(kafkaTopic, dto.getScripCode(), dto);
        } catch (Exception e) {
            log.warn("{} Kafka publish failed: {}", LOG_PREFIX, e.getMessage());
        }
    }

    // ==================== HELPER SCORE CALCULATIONS ====================

    /**
     * Normalize score to 0-100 range.
     */
    private double normalize(Double value) {
        if (value == null) return 0;
        return Math.max(0, Math.min(100, Math.abs(value) * 100));
    }

    /**
     * Calculate trend alignment score.
     */
    private double calculateTrendScore(PivotState pivotState) {
        if (pivotState == null) return 50;

        double score = 50;
        if ("UPTREND".equals(pivotState.getStructure()) || "DOWNTREND".equals(pivotState.getStructure())) {
            score = 80;
        } else if ("CONSOLIDATION".equals(pivotState.getStructure())) {
            score = 40;
        }

        // Boost for clear structure
        if (pivotState.isHigherHighs() && pivotState.isHigherLows()) score += 10;
        if (pivotState.isLowerHighs() && pivotState.isLowerLows()) score += 10;

        return Math.min(100, score);
    }

    /**
     * Calculate volume profile quality score.
     */
    private double calculateVolumeProfileScore(VcpState vcpState) {
        if (vcpState == null) return 0;

        double score = 0;

        // Has clear clusters
        if (vcpState.getSupportClusters() != null && !vcpState.getSupportClusters().isEmpty()) {
            score += 30;
        }
        if (vcpState.getResistanceClusters() != null && !vcpState.getResistanceClusters().isEmpty()) {
            score += 30;
        }

        // Runway quality
        double runway = Math.max(vcpState.getBullishRunway(), vcpState.getBearishRunway());
        score += runway * 40;

        return Math.min(100, score);
    }

    /**
     * Calculate microstructure quality score.
     */
    private double calculateMicrostructureScore(UnifiedCandle candle) {
        if (!candle.isHasOrderbook()) return 0;

        double score = 50; // Base score for having orderbook

        // OFI quality
        if (candle.getOfi() != null && Math.abs(candle.getOfi()) > 0) {
            score += 20;
        }

        // Kyle's Lambda (lower = better liquidity)
        if (candle.getKyleLambda() != null && candle.getKyleLambda() < 0.01) {
            score += 20;
        }

        // Spread tightness
        if (candle.getSpreadPercent() != null && candle.getSpreadPercent() < 0.1) {
            score += 10;
        }

        return Math.min(100, score);
    }

    /**
     * Calculate options flow score.
     */
    private double calculateOptionsFlowScore(UnifiedCandle candle) {
        if (!candle.isHasOI()) return 0;

        double score = 50; // Base score for having OI

        // OI change significance
        if (candle.getOiChangePercent() != null && Math.abs(candle.getOiChangePercent()) > 2) {
            score += 25;
        }

        // Clear interpretation
        if (candle.getOiInterpretation() != null) {
            score += 25;
        }

        return Math.min(100, score);
    }

    /**
     * Calculate multi-factor confluence score.
     */
    private double calculateConfluenceScore(VcpState vcpState, IpuState ipuState, PivotState pivotState) {
        int factors = 0;
        int aligned = 0;

        // Check VCP direction
        if (vcpState != null) {
            factors++;
            double netRunway = vcpState.getBullishRunway() - vcpState.getBearishRunway();
            if (Math.abs(netRunway) > 0.1) aligned++;
        }

        // Check IPU direction
        if (ipuState != null && ipuState.getCurrentDirection() != null) {
            factors++;
            if (!"NEUTRAL".equals(ipuState.getCurrentDirection())) aligned++;
        }

        // Check Pivot structure
        if (pivotState != null && pivotState.getStructure() != null) {
            factors++;
            if ("UPTREND".equals(pivotState.getStructure()) || "DOWNTREND".equals(pivotState.getStructure())) {
                aligned++;
            }
        }

        if (factors == 0) return 0;
        return (aligned / (double) factors) * 100;
    }
}
