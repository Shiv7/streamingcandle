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

import com.kotsin.consumer.breakout.model.BreakoutEvent;
import com.kotsin.consumer.breakout.model.BreakoutEvent.*;

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
        publish(candle, fudkiiScore, vcpState, ipuState, pivotState, detectedPatterns, 0.0, null);
    }

    /**
     * Publish a QuantScore with structural level context and pattern confidence.
     */
    public void publish(
            UnifiedCandle candle,
            FudkiiScore fudkiiScore,
            VcpState vcpState,
            IpuState ipuState,
            PivotState pivotState,
            List<String> detectedPatterns,
            double patternScore,
            List<BreakoutEvent> breakoutEvents) {

        if (!enabled || fudkiiScore == null || candle == null) {
            return;
        }

        try {
            QuantScoreDTO dto = buildQuantScore(candle, fudkiiScore, vcpState, ipuState, pivotState, detectedPatterns, patternScore, breakoutEvents);

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
            List<String> detectedPatterns,
            double patternScore,
            List<BreakoutEvent> breakoutEvents) {

        // Calculate pattern score: use actual confidence from PatternAnalyzer (0-1 range → 0-100)
        double normalizedPatternScore = 0.0;
        if (detectedPatterns != null && !detectedPatterns.isEmpty()) {
            normalizedPatternScore = patternScore > 0 ? Math.min(patternScore * 100, 100) : 50.0;
        }

        // Build score breakdown
        QuantScoreDTO.ScoreBreakdown breakdown = QuantScoreDTO.ScoreBreakdown.builder()
            .flowScore(normalize(fudkiiScore.getFlowScore()))
            .urgencyScore(normalize(fudkiiScore.getUrgencyScore()))
            .directionScore(normalize(fudkiiScore.getDirectionScore()))
            .kyleScore(normalize(fudkiiScore.getKyleScore()))
            .imbalanceScore(normalize(fudkiiScore.getImbalanceScore()))
            .intensityScore(normalize(fudkiiScore.getIntensityScore()))
            .patternScore(normalizedPatternScore)
            .trendScore(calculateTrendScore(pivotState))
            .volumeProfileScore(calculateVolumeProfileScore(vcpState))
            .microstructureScore(calculateMicrostructureScore(candle))
            .optionsFlowScore(calculateOptionsFlowScore(candle))
            .confluenceScore(calculateConfluenceScore(vcpState, ipuState, pivotState))
            .structuralLevelScore(calculateStructuralLevelScore(breakoutEvents))
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

        // Get nearest support/resistance and VCP levels
        Double nearestSupport = null;
        Double nearestResistance = null;
        Double pocPrice = null;
        Double valueAreaHigh = null;
        Double valueAreaLow = null;
        if (vcpState != null) {
            if (vcpState.getSupportClusters() != null && !vcpState.getSupportClusters().isEmpty()) {
                nearestSupport = vcpState.getSupportClusters().get(0).getPrice();
            }
            if (vcpState.getResistanceClusters() != null && !vcpState.getResistanceClusters().isEmpty()) {
                nearestResistance = vcpState.getResistanceClusters().get(0).getPrice();
            }
            if (vcpState.getPocPrice() > 0) pocPrice = vcpState.getPocPrice();
            if (vcpState.getValueAreaHigh() > 0) valueAreaHigh = vcpState.getValueAreaHigh();
            if (vcpState.getValueAreaLow() > 0) valueAreaLow = vcpState.getValueAreaLow();
        }

        // Get OI context
        String oiInterpretation = null;
        boolean oiSuggestsReversal = false;
        if (candle.isHasOI() && candle.getOiInterpretation() != null) {
            oiInterpretation = candle.getOiInterpretation().name();
            oiSuggestsReversal = candle.getOiSuggestsReversal() != null && candle.getOiSuggestsReversal();
        }

        // Calculate directional imbalance: |buyVolume - sellVolume| / totalVolume
        // Range 0-1: 0 = balanced, 1 = completely one-sided
        // Note: This measures order flow imbalance, not relative volume vs average
        double directionalImbalance = 0.0;
        if (candle.getVolume() > 0 && candle.getVolumeDelta() != 0) {
            directionalImbalance = Math.abs((double) candle.getVolumeDelta()) / candle.getVolume();
        }

        // High volume activity is indicated by strong directional imbalance (> 50% one-sided)
        boolean highVolumeActivity = directionalImbalance > 0.5;

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
            .patternConfidence(patternScore)
            .nearestSupport(nearestSupport)
            .nearestResistance(nearestResistance)
            .currentPrice(candle.getClose())
            .atrPercent(null)  // ATR not available on UnifiedCandle
            .pocPrice(pocPrice)
            .valueAreaHigh(valueAreaHigh)
            .valueAreaLow(valueAreaLow)
            .volumeRatio(directionalImbalance)  // Renamed: actually represents directional imbalance
            .highVolume(highVolumeActivity)  // True when > 50% one-sided flow
            .oiInterpretation(oiInterpretation)
            .oiSuggestsReversal(oiSuggestsReversal)
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
     * Normalize score to -100 to +100 range, preserving direction.
     * Positive = bullish signal, Negative = bearish signal.
     * The magnitude indicates strength.
     */
    private double normalize(Double value) {
        if (value == null) return 0;
        // Preserve sign for directional information
        // Clamp to -100 to +100 range
        return Math.max(-100, Math.min(100, value * 100));
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
     * Calculate volume profile quality score using cluster strength and quality metrics.
     */
    private double calculateVolumeProfileScore(VcpState vcpState) {
        if (vcpState == null) return 0;

        double score = 0;

        // Support cluster quality (max 35)
        if (vcpState.getSupportClusters() != null && !vcpState.getSupportClusters().isEmpty()) {
            double avgStrength = vcpState.getSupportClusters().stream()
                .mapToDouble(c -> c.getStrength())
                .average().orElse(0);
            // Strong clusters (strength > 0.5) score higher than weak clusters
            score += 15 + avgStrength * 20; // 15 for presence + up to 20 for quality
        }

        // Resistance cluster quality (max 35)
        if (vcpState.getResistanceClusters() != null && !vcpState.getResistanceClusters().isEmpty()) {
            double avgStrength = vcpState.getResistanceClusters().stream()
                .mapToDouble(c -> c.getStrength())
                .average().orElse(0);
            score += 15 + avgStrength * 20;
        }

        // Runway quality: directional conviction (max 30)
        double runway = Math.max(vcpState.getBullishRunway(), vcpState.getBearishRunway());
        score += runway * 30;

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
     * Calculate options flow score using OI interpretation and change magnitude.
     * Scores continuations (buildup) higher than exhaustion (unwinding/covering).
     */
    private double calculateOptionsFlowScore(UnifiedCandle candle) {
        if (!candle.isHasOI()) return 0;

        double score = 40; // Base score for having OI data

        // OI change magnitude: bigger moves = higher score
        if (candle.getOiChangePercent() != null) {
            double absChange = Math.abs(candle.getOiChangePercent());
            if (absChange > 5) score += 25;       // Very strong OI move
            else if (absChange > 2) score += 15;   // Strong OI move
            else if (absChange > 0.5) score += 5;  // Moderate OI move
        }

        // OI interpretation quality: continuation > exhaustion > neutral
        if (candle.getOiInterpretation() != null) {
            switch (candle.getOiInterpretation()) {
                case LONG_BUILDUP:
                case SHORT_BUILDUP:
                    // Fresh position buildup = strong institutional commitment
                    score += 30;
                    break;
                case SHORT_COVERING:
                case LONG_UNWINDING:
                    // Unwinding/covering = directional but exhaustive
                    score += 15;
                    break;
                case NEUTRAL:
                default:
                    // No clear interpretation
                    score += 5;
                    break;
            }
        }

        // OI confidence bonus
        if (candle.getOiInterpretationConfidence() != null && candle.getOiInterpretationConfidence() > 0.7) {
            score += 5;
        }

        return Math.min(100, score);
    }

    /**
     * Calculate structural level score.
     * - 100: Trading at confirmed retest of broken level
     * - 75: Trading at key confluence level with active breakout
     * - 50: Trading near a registered level
     * - 25: No nearby structural level
     */
    private double calculateStructuralLevelScore(List<BreakoutEvent> breakoutEvents) {
        if (breakoutEvents == null || breakoutEvents.isEmpty()) return 25;

        double bestScore = 25;
        for (BreakoutEvent event : breakoutEvents) {
            if (event.getType() == BreakoutType.RETEST && event.isRetestHeld()) {
                RetestQuality quality = event.getRetestQuality();
                if (quality == RetestQuality.PERFECT) {
                    bestScore = Math.max(bestScore, 100);
                } else if (quality == RetestQuality.GOOD) {
                    bestScore = Math.max(bestScore, 85);
                } else {
                    bestScore = Math.max(bestScore, 60);
                }
            } else if (event.getType() == BreakoutType.BREAKOUT) {
                bestScore = Math.max(bestScore, 75);
            }
        }
        return bestScore;
    }

    /**
     * Calculate multi-factor confluence score.
     * Checks alignment across VCP runway, VCP cluster OFI bias, IPU direction,
     * IPU momentum trend, and pivot structure.
     */
    private double calculateConfluenceScore(VcpState vcpState, IpuState ipuState, PivotState pivotState) {
        int factors = 0;
        int aligned = 0;

        // Check VCP runway direction
        if (vcpState != null) {
            factors++;
            double netRunway = vcpState.getBullishRunway() - vcpState.getBearishRunway();
            if (Math.abs(netRunway) > 0.1) aligned++;
        }

        // Check VCP cluster OFI bias alignment (average of support cluster biases)
        if (vcpState != null && vcpState.getSupportClusters() != null && !vcpState.getSupportClusters().isEmpty()) {
            double avgOfiBias = vcpState.getSupportClusters().stream()
                .mapToDouble(c -> c.getOfiBias())
                .average().orElse(0);
            if (Math.abs(avgOfiBias) > 0.2) {
                factors++;
                aligned++; // Clear OFI bias = aligned factor
            }
        }

        // Check IPU direction
        if (ipuState != null && ipuState.getCurrentDirection() != null) {
            factors++;
            if (!"NEUTRAL".equals(ipuState.getCurrentDirection())) aligned++;
        }

        // Check IPU momentum trend (ipuRising = bullish momentum, exhaustionBuilding = warning)
        if (ipuState != null) {
            factors++;
            if (ipuState.isIpuRising() && !ipuState.isExhaustionBuilding()) {
                aligned++; // Rising IPU without exhaustion = strong momentum alignment
            }
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
