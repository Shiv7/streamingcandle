package com.kotsin.consumer.enrichment.signal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.config.KafkaTopics;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal.ConfidenceBreakdown;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal.SignalSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TradingSignalPublisher - Publishes enhanced trading signals to Kafka
 *
 * Publishes to:
 * - trading-signals-v2: Enhanced signals with full context
 * - trading-signals-high-priority: Immediate urgency signals
 * - trading-signals-alerts: Signals for alerting systems
 *
 * Features:
 * - Async publishing with callbacks
 * - Validation before publishing
 * - Metrics tracking
 * - Dead letter handling for failed publishes
 */
@Slf4j
@Component
public class TradingSignalPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final SignalValidator signalValidator;

    public TradingSignalPublisher(
            @Qualifier("stringKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            SignalValidator signalValidator) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.signalValidator = signalValidator;
    }

    // Topic names - using centralized constants
    private static final String TOPIC_SIGNALS_V2 = KafkaTopics.TRADING_SIGNALS_V2;
    private static final String TOPIC_HIGH_PRIORITY = KafkaTopics.TRADING_SIGNALS_HIGH_PRIORITY;
    private static final String TOPIC_ALERTS = KafkaTopics.TRADING_SIGNALS_ALERTS;

    // DISABLED: The InstrumentStateManager is now the ONLY signal generator.
    // TradingSignalPublisher was part of the spam generator (700+ signals/day).
    // All signal generation now goes through state machine with ONE signal per trade lifecycle.
    private static final boolean DISABLED = true;

    // Statistics
    private final AtomicLong totalPublished = new AtomicLong(0);
    private final AtomicLong publishSuccess = new AtomicLong(0);
    private final AtomicLong publishFailed = new AtomicLong(0);
    private final AtomicLong validationFailed = new AtomicLong(0);
    private final AtomicLong highPriorityCount = new AtomicLong(0);

    // ======================== MAIN PUBLISHING ========================

    /**
     * Publish a single trading signal
     * DISABLED - Replaced by InstrumentStateManager state machine
     *
     * @param signal Signal to publish
     * @return true if published successfully
     */
    public boolean publishSignal(TradingSignal signal) {
        if (DISABLED) {
            log.debug("[SIGNAL_PUB] DISABLED - State machine is the only signal generator now");
            return false;
        }
        if (signal == null) {
            return false;
        }

        // Validate signal
        if (!signalValidator.isPublishable(signal)) {
            log.warn("[SIGNAL_PUB] Signal {} failed validation, not publishing", signal.getSignalId());
            validationFailed.incrementAndGet();
            return false;
        }

        try {
            // FIX: Convert TradingSignal to QuantTradingSignal-compatible format
            // The TradeExecutionModule's QuantSignalConsumer expects specific fields:
            // - actionable (boolean) - checked at line 92
            // - quantScore (double) - checked at line 99 against minScore (default 65)
            // - confidence (double) - checked at line 105 against minConfidence (default 0.6)
            // - timestamp (long) - used for staleness check at line 112
            // - direction (String) - used for logging and position side
            Map<String, Object> quantSignal = convertToQuantSignalFormat(signal);
            String payload = objectMapper.writeValueAsString(quantSignal);
            String key = buildMessageKey(signal);

            // Publish to main topic
            publishAsync(TOPIC_SIGNALS_V2, key, payload, signal.getSignalId());

            // High priority signals go to dedicated topic
            if (signal.isImmediatePriority()) {
                publishAsync(TOPIC_HIGH_PRIORITY, key, payload, signal.getSignalId());
                highPriorityCount.incrementAndGet();
            }

            // High quality signals trigger alerts
            if (signal.isHighQuality()) {
                publishAlert(signal);
            }

            totalPublished.incrementAndGet();

            log.info("[SIGNAL_PUB] Published signal {} for {} direction={} quantScore={} actionable={}",
                    signal.getSignalId(), signal.getFamilyId(), signal.getDirection(),
                    quantSignal.get("quantScore"), quantSignal.get("actionable"));

            return true;

        } catch (JsonProcessingException e) {
            log.error("[SIGNAL_PUB] Failed to serialize signal {}: {}", signal.getSignalId(), e.getMessage());
            publishFailed.incrementAndGet();
            return false;
        }
    }

    /**
     * Publish multiple signals
     *
     * @param signals List of signals to publish
     * @return Number of successfully published signals
     */
    public int publishSignals(List<TradingSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return 0;
        }

        int successCount = 0;
        for (TradingSignal signal : signals) {
            if (publishSignal(signal)) {
                successCount++;
            }
        }

        log.info("[SIGNAL_PUB] Published {}/{} signals", successCount, signals.size());
        return successCount;
    }

    /**
     * Publish signal synchronously (blocking)
     *
     * @param signal Signal to publish
     * @return true if published successfully
     */
    public boolean publishSignalSync(TradingSignal signal) {
        if (signal == null) {
            return false;
        }

        // Validate signal
        if (!signalValidator.isPublishable(signal)) {
            log.warn("[SIGNAL_PUB] Signal {} failed validation, not publishing", signal.getSignalId());
            validationFailed.incrementAndGet();
            return false;
        }

        try {
            // FIX: Convert to QuantTradingSignal-compatible format (same as async method)
            Map<String, Object> quantSignal = convertToQuantSignalFormat(signal);
            String payload = objectMapper.writeValueAsString(quantSignal);
            String key = buildMessageKey(signal);

            // Synchronous send
            SendResult<String, String> result = kafkaTemplate.send(TOPIC_SIGNALS_V2, key, payload).get();

            log.info("[SIGNAL_PUB] Published signal {} to partition {} offset {} (quantScore={}, actionable={})",
                    signal.getSignalId(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset(),
                    quantSignal.get("quantScore"),
                    quantSignal.get("actionable"));

            totalPublished.incrementAndGet();
            publishSuccess.incrementAndGet();

            // High priority
            if (signal.isImmediatePriority()) {
                kafkaTemplate.send(TOPIC_HIGH_PRIORITY, key, payload).get();
                highPriorityCount.incrementAndGet();
            }

            return true;

        } catch (Exception e) {
            log.error("[SIGNAL_PUB] Failed to publish signal {}: {}", signal.getSignalId(), e.getMessage());
            publishFailed.incrementAndGet();
            return false;
        }
    }

    // ======================== HELPER METHODS ========================

    /**
     * Async publish with callback
     */
    private void publishAsync(String topic, String key, String payload, String signalId) {
        CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(topic, key, payload);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                publishSuccess.incrementAndGet();
                log.debug("[SIGNAL_PUB] Published {} to {} partition {} offset {}",
                        signalId, topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            } else {
                publishFailed.incrementAndGet();
                log.error("[SIGNAL_PUB] Failed to publish {} to {}: {}",
                        signalId, topic, ex.getMessage());
            }
        });
    }

    /**
     * Publish alert for high-quality signals
     */
    private void publishAlert(TradingSignal signal) {
        try {
            // Build compact alert payload
            SignalAlert alert = SignalAlert.builder()
                    .signalId(signal.getSignalId())
                    .familyId(signal.getFamilyId())
                    .direction(signal.getDirection().name())
                    .category(signal.getCategory() != null ? signal.getCategory().name() : null)
                    .headline(signal.getHeadline())
                    .entryPrice(signal.getEntryPrice())
                    .stopLoss(signal.getStopLoss())
                    .target(signal.getTarget2())
                    .confidence(signal.getConfidence())
                    .qualityScore(signal.getQualityScore())
                    .urgency(signal.getUrgency().name())
                    .riskReward(signal.getRiskRewardRatio())
                    .build();

            String payload = objectMapper.writeValueAsString(alert);
            String key = signal.getFamilyId() + "_" + signal.getSignalId();

            kafkaTemplate.send(TOPIC_ALERTS, key, payload);

            log.debug("[SIGNAL_PUB] Published alert for high-quality signal {}", signal.getSignalId());

        } catch (JsonProcessingException e) {
            log.warn("[SIGNAL_PUB] Failed to publish alert for {}: {}", signal.getSignalId(), e.getMessage());
        }
    }

    /**
     * Build message key for partitioning
     */
    private String buildMessageKey(TradingSignal signal) {
        // Key by family to ensure ordered processing per family
        return signal.getFamilyId() + "_" + signal.getDirection().name();
    }

    // ======================== SCHEMA CONVERSION ========================

    /**
     * Convert TradingSignal to QuantTradingSignal-compatible format.
     *
     * FIX: The TradeExecutionModule's QuantSignalConsumer expects a different schema
     * with specific fields like 'actionable', 'quantScore', 'timestamp' (long), etc.
     * This conversion ensures the published signal matches the expected consumer format.
     *
     * @param signal TradingSignal from SignalGenerator
     * @return Map representing QuantTradingSignal-compatible JSON structure
     */
    private Map<String, Object> convertToQuantSignalFormat(TradingSignal signal) {
        Map<String, Object> quantSignal = new HashMap<>();

        // ========== SIGNAL IDENTITY ==========
        quantSignal.put("signalId", signal.getSignalId());
        quantSignal.put("scripCode", signal.getScripCode());
        quantSignal.put("familyId", signal.getFamilyId());
        quantSignal.put("symbol", signal.getFamilyId()); // Use familyId as symbol
        quantSignal.put("companyName", signal.getCompanyName());

        // FIX: Consumer expects 'timestamp' as long epoch millis, not Instant
        quantSignal.put("timestamp", signal.getGeneratedAt() != null ?
                signal.getGeneratedAt().toEpochMilli() : System.currentTimeMillis());
        quantSignal.put("humanReadableTime", signal.getHumanReadableTime());

        // ========== CRITICAL: Fields required by QuantSignalConsumer ==========

        // FIX: Consumer checks signal.isActionable() which requires this boolean field
        // QuantTradingSignal.isActionable() = actionable && signalType != null && entryPrice > 0 && stopLoss > 0 && target1 > 0
        quantSignal.put("actionable", signal.isActionable());

        // Use REAL quality score - no cheating
        // Signal must earn its score through actual setup/pattern quality
        double quantScore = signal.getQualityScore();
        String quantLabel;
        if (quantScore >= 80) {
            quantLabel = "STRONG_SIGNAL";
        } else if (quantScore >= 65) {
            quantLabel = "MODERATE_SIGNAL";
        } else if (quantScore >= 50) {
            quantLabel = "WEAK_SIGNAL";
        } else {
            quantLabel = "LOW_QUALITY";
        }
        quantSignal.put("quantScore", quantScore);
        quantSignal.put("quantLabel", quantLabel);

        // FIX: Consumer checks signal.getConfidence() < minConfidence (default 0.6)
        quantSignal.put("confidence", signal.getConfidence());

        // ========== SIGNAL CLASSIFICATION ==========

        // FIX: Consumer uses signal.getDirection() as String, not enum
        quantSignal.put("direction", signal.getDirection() != null ?
                signal.getDirection().name() : "LONG");
        quantSignal.put("directionalStrength", signal.getConfidence());

        // Map source to signalType enum name
        String signalType = mapSourceToSignalType(signal);
        quantSignal.put("signalType", signalType);

        // Pattern identification
        quantSignal.put("patternId", signal.getPatternId());
        quantSignal.put("sequenceId", signal.getSequenceId());
        quantSignal.put("category", signal.getCategory() != null ? signal.getCategory().name() : "MOMENTUM");
        quantSignal.put("horizon", signal.getHorizon() != null ? signal.getHorizon().name() : "INTRADAY");

        // Quality metrics
        // FIX: patternConfidence should be the ORIGINAL confidence, not final confidence
        // The final confidence (signal.getConfidence()) is already in "confidence" field
        // Use confidenceBreakdown to get the appropriate original value
        double patternConf = signal.getConfidence(); // Default fallback
        if (signal.getConfidenceBreakdown() != null) {
            // Note: ConfidenceBreakdown uses primitive double, so check > 0 instead of != null
            if (signal.getSource() == SignalSource.SETUP && signal.getConfidenceBreakdown().getSetupConfidence() > 0) {
                patternConf = signal.getConfidenceBreakdown().getSetupConfidence();
            } else if (signal.getSource() == SignalSource.PATTERN && signal.getConfidenceBreakdown().getPatternConfidence() > 0) {
                patternConf = signal.getConfidenceBreakdown().getPatternConfidence();
            }
        }
        quantSignal.put("patternConfidence", patternConf);
        quantSignal.put("historicalSuccessRate", signal.getHistoricalSuccessRate());
        quantSignal.put("qualityScore", signal.getQualityScore());
        quantSignal.put("rationale", signal.getNarrative());

        // ========== ENTRY PARAMETERS ==========
        quantSignal.put("entryPrice", signal.getEntryPrice());

        // FIX: Entry range should be direction-aware to prevent risk miscalculation
        // For SHORT: we want to sell HIGHER, so range is [entry, entry+0.4%]
        // For LONG: we want to buy LOWER, so range is [entry-0.4%, entry]
        boolean isShort = signal.getDirection() == TradingSignal.Direction.SHORT;
        double entryRangeHigh, entryRangeLow;
        if (isShort) {
            // SHORT: accept entries at or above entry price (better for shorts)
            entryRangeLow = signal.getEntryPrice();
            entryRangeHigh = signal.getEntryPrice() * 1.004; // +0.4% above entry
        } else {
            // LONG: accept entries at or below entry price (better for longs)
            entryRangeLow = signal.getEntryPrice() * 0.996;  // -0.4% below entry
            entryRangeHigh = signal.getEntryPrice();
        }
        quantSignal.put("entryRangeHigh", entryRangeHigh);
        quantSignal.put("entryRangeLow", entryRangeLow);

        // ========== EXIT PARAMETERS ==========
        quantSignal.put("stopLoss", signal.getStopLoss());
        // FIX: Calculate stop distance from WORST entry point, not base entry
        // For SHORT: worst entry is entryRangeLow (lowest sell price)
        // For LONG: worst entry is entryRangeHigh (highest buy price)
        double worstEntry = isShort ? entryRangeLow : entryRangeHigh;
        double stopLossDistance = Math.abs(worstEntry - signal.getStopLoss());
        quantSignal.put("stopLossDistance", stopLossDistance);
        double stopLossPercent = (stopLossDistance / worstEntry) * 100;
        quantSignal.put("stopLossPercent", stopLossPercent);
        quantSignal.put("target1", signal.getTarget1());
        quantSignal.put("target2", signal.getTarget2());
        quantSignal.put("target3", signal.getTarget3());
        quantSignal.put("riskRewardRatio", signal.getRiskRewardRatio());

        // ========== POSITION SIZING ==========
        Map<String, Object> sizing = new HashMap<>();
        sizing.put("quantity", 1); // Default to 1, let consumer calculate
        sizing.put("lots", 1);
        sizing.put("lotSize", 1);
        sizing.put("positionValue", signal.getEntryPrice());
        // FIX: Use worstEntry-based risk calculation for accurate position sizing
        sizing.put("riskAmount", stopLossDistance);
        sizing.put("riskPercent", stopLossPercent);
        sizing.put("positionSizeMultiplier", signal.getPositionSizeMultiplier());
        sizing.put("sizingMethod", "RISK_BASED");
        quantSignal.put("sizing", sizing);

        // ========== TRAILING STOP ==========
        if (signal.getTrailingStopActivation() != null) {
            Map<String, Object> trailingStop = new HashMap<>();
            trailingStop.put("enabled", true);
            trailingStop.put("type", "PCT");
            trailingStop.put("value", signal.getTrailingStopPct() != null ? signal.getTrailingStopPct() : 1.0);
            trailingStop.put("activationPrice", signal.getTrailingStopActivation());
            quantSignal.put("trailingStop", trailingStop);
        }

        // ========== HEDGING ==========
        // No hedging by default from TradingSignal
        Map<String, Object> hedging = new HashMap<>();
        hedging.put("recommended", false);
        quantSignal.put("hedging", hedging);

        // ========== TIME CONSTRAINTS ==========
        Map<String, Object> timeConstraints = new HashMap<>();
        timeConstraints.put("preferredSession", signal.getSession());
        timeConstraints.put("daysToExpiry", signal.getDaysToExpiry() != null ? signal.getDaysToExpiry() : 0);
        timeConstraints.put("intraday", signal.getHorizon() == TradingSignal.Horizon.SCALP ||
                                        signal.getHorizon() == TradingSignal.Horizon.INTRADAY);
        timeConstraints.put("marketHoursOnly", true);
        quantSignal.put("timeConstraints", timeConstraints);

        // ========== EXCHANGE ==========
        // FIX: Use exchange field from signal (properly propagated from InstrumentCandle)
        // Fall back to deriving from scripCode only if exchange is null
        String exchange = signal.getExchange();
        if (exchange == null || exchange.isEmpty()) {
            // Legacy fallback - derive from scripCode patterns
            exchange = "N"; // Default NSE
            if (signal.getScripCode() != null) {
                if (signal.getScripCode().contains("MCX") || signal.getScripCode().contains("_M_")) {
                    exchange = "M";
                } else if (signal.getScripCode().contains("_B_")) {
                    exchange = "B";
                }
            }
        }
        quantSignal.put("exchange", exchange);

        // ========== CONTEXT-AWARE ENHANCEMENTS (SMTIS v2.0) ==========

        // Session Context - extracted from signal metadata or rationale
        if (signal.getMetadata() != null) {
            quantSignal.put("sessionPosition", signal.getMetadata().get("sessionPosition"));
            quantSignal.put("sessionPositionDesc", signal.getMetadata().get("sessionPositionDesc"));
            quantSignal.put("vBottomDetected", signal.getMetadata().get("vBottomDetected"));
            quantSignal.put("vTopDetected", signal.getMetadata().get("vTopDetected"));
            quantSignal.put("failedBreakoutCount", signal.getMetadata().get("failedBreakoutCount"));
            quantSignal.put("failedBreakdownCount", signal.getMetadata().get("failedBreakdownCount"));
        }
        quantSignal.put("currentSession", signal.getSession());

        // Family Context - from signal rationale metadata
        if (signal.getRationale() != null) {
            quantSignal.put("familyBias", signal.getRationale().getFamilyBias());
            quantSignal.put("familyAlignment", signal.getRationale().getFamilyAlignment());
            quantSignal.put("fullyAligned", signal.getRationale().isFullyAligned());
            quantSignal.put("hasDivergence", signal.getRationale().isHasDivergence());
            quantSignal.put("divergences", signal.getRationale().getDivergenceDetails());
            quantSignal.put("shortSqueezeSetup", signal.getRationale().isShortSqueezeSetup());
            quantSignal.put("longSqueezeSetup", signal.getRationale().isLongSqueezeSetup());
            quantSignal.put("familyInterpretation", signal.getRationale().getFamilyInterpretation());
        }

        // Event Tracking
        quantSignal.put("triggerEvents", signal.getMatchedEvents());
        quantSignal.put("eventCount", signal.getMatchedEvents() != null ? signal.getMatchedEvents().size() : 0);
        quantSignal.put("matchedEvents", signal.getMatchedEvents());
        quantSignal.put("eventConfirmationRate",
                signal.getHistoricalSuccessRate() * 100); // Use historical success as proxy

        // Adaptive Modifiers
        // FIX: originalConfidence was using patternConfidence which is null for SETUP signals
        // Now we use the appropriate confidence based on signal source
        // Note: ConfidenceBreakdown uses primitive double, so compare with > 0 not != null
        if (signal.getConfidenceBreakdown() != null) {
            ConfidenceBreakdown cb = signal.getConfidenceBreakdown();
            quantSignal.put("contextModifier", cb.getContextConfidence());

            // FIX: Use setupConfidence for SETUP signals, patternConfidence for PATTERN signals
            double originalConf = 0.0;
            if (signal.getSource() == SignalSource.SETUP && cb.getSetupConfidence() > 0) {
                originalConf = cb.getSetupConfidence();
            } else if (signal.getSource() == SignalSource.PATTERN && cb.getPatternConfidence() > 0) {
                originalConf = cb.getPatternConfidence();
            }
            // Fallback to signal's main confidence if breakdown value is 0
            if (originalConf == 0.0) {
                originalConf = signal.getConfidence();
            }
            quantSignal.put("originalConfidence", originalConf);
        } else {
            // No breakdown available - use signal confidence
            quantSignal.put("contextModifier", 0.0);
            quantSignal.put("originalConfidence", signal.getConfidence());
        }

        // Technical Context
        quantSignal.put("superTrendDirection", signal.getSuperTrendDirection());
        quantSignal.put("bbSqueeze", signal.getMetadata() != null ?
                signal.getMetadata().get("bbSqueeze") : null);
        quantSignal.put("nearestSupport", signal.getNearestSupport());
        quantSignal.put("nearestResistance", signal.getNearestResistance());
        quantSignal.put("dailyPivot", signal.getMetadata() != null ?
                signal.getMetadata().get("dailyPivot") : null);
        quantSignal.put("maxPainLevel", signal.getMaxPainLevel());
        quantSignal.put("gammaFlipLevel", signal.getGammaFlipLevel());
        quantSignal.put("gexRegime", signal.getGexRegime());

        // FIX: Add flag to indicate if options data was available
        // When false, gammaFlipLevel/maxPainLevel being null means "no options data" not "no level calculated"
        // This helps frontend show "N/A" instead of empty when appropriate
        boolean hasOptionsData = (signal.getGammaFlipLevel() != null && signal.getGammaFlipLevel() > 0) ||
                                 (signal.getMaxPainLevel() != null && signal.getMaxPainLevel() > 0) ||
                                 (signal.getGexRegime() != null && !"NEUTRAL".equals(signal.getGexRegime()));
        quantSignal.put("hasOptionsData", hasOptionsData);

        // FIX: Add VPIN and Lambda microstructure data
        quantSignal.put("vpin", signal.getVpin());
        quantSignal.put("vpinPercentile", signal.getVpinPercentile());
        quantSignal.put("kyleLambda", signal.getKyleLambda());
        quantSignal.put("lambdaPercentile", signal.getLambdaPercentile());
        // Flag to indicate if microstructure data was available
        boolean hasMicrostructureData = (signal.getVpin() != null && signal.getVpin() > 0) ||
                                        (signal.getKyleLambda() != null && signal.getKyleLambda() > 0);
        quantSignal.put("hasMicrostructureData", hasMicrostructureData);

        // Invalidation Monitoring
        if (signal.getInvalidationWatch() != null && !signal.getInvalidationWatch().isEmpty()) {
            quantSignal.put("invalidationWatch", signal.getInvalidationWatch());
        }
        quantSignal.put("invalidationPrice", signal.getInvalidationPrice());

        // Predictions
        quantSignal.put("predictedEvents", signal.getPredictedEvents());
        quantSignal.put("expectedPriceAction", signal.getExpectedPriceAction());

        log.debug("[SIGNAL_PUB] Converted TradingSignal {} to QuantSignal format: quantScore={}, actionable={}, direction={}",
                signal.getSignalId(), quantScore, signal.isActionable(), signal.getDirection());

        return quantSignal;
    }

    /**
     * Map TradingSignal source/category to QuantTradingSignal.SignalType enum name
     */
    private String mapSourceToSignalType(TradingSignal signal) {
        if (signal.getSource() == null) {
            return "CUSTOM";
        }

        // Map based on source and category
        TradingSignal.SignalSource source = signal.getSource();
        TradingSignal.SignalCategory category = signal.getCategory();

        if (category != null) {
            return switch (category) {
                case REVERSAL -> signal.isLong() ? "FLOW_REVERSAL_LONG" : "FLOW_REVERSAL_SHORT";
                case BREAKOUT -> "CONFLUENCE_BREAKOUT";
                case BREAKDOWN -> "CONFLUENCE_BREAKDOWN";
                case SQUEEZE -> signal.isLong() ? "GAMMA_SQUEEZE_LONG" : "GAMMA_SQUEEZE_SHORT";
                case MOMENTUM -> signal.isLong() ? "OFI_MOMENTUM" : "OFI_MOMENTUM";
                case MEAN_REVERSION -> "REVERSAL_PATTERN";
                case EXHAUSTION -> signal.isLong() ? "WYCKOFF_ACCUMULATION" : "WYCKOFF_DISTRIBUTION";
                case CONTINUATION -> "MULTI_TIMEFRAME_ALIGNMENT";
            };
        }

        return switch (source) {
            case PATTERN -> "REVERSAL_PATTERN";
            case SETUP -> "MULTI_TIMEFRAME_ALIGNMENT";
            case FORECAST -> "CONFLUENCE_BREAKOUT";
            case INTELLIGENCE -> "SMART_MONEY_ACCUMULATION";
            case MANUAL -> "CUSTOM";
        };
    }

    // ======================== STATISTICS ========================

    /**
     * Get publisher statistics
     */
    public PublisherStats getStats() {
        return PublisherStats.builder()
                .totalPublished(totalPublished.get())
                .publishSuccess(publishSuccess.get())
                .publishFailed(publishFailed.get())
                .validationFailed(validationFailed.get())
                .highPriorityCount(highPriorityCount.get())
                .successRate(calculateSuccessRate())
                .build();
    }

    /**
     * Reset statistics
     */
    public void resetStats() {
        totalPublished.set(0);
        publishSuccess.set(0);
        publishFailed.set(0);
        validationFailed.set(0);
        highPriorityCount.set(0);
    }

    private double calculateSuccessRate() {
        long total = totalPublished.get();
        if (total == 0) return 0;
        return (double) publishSuccess.get() / total;
    }

    // ======================== MODELS ========================

    @lombok.Data
    @lombok.Builder
    @lombok.AllArgsConstructor
    public static class SignalAlert {
        private String signalId;
        private String familyId;
        private String direction;
        private String category;
        private String headline;
        private double entryPrice;
        private double stopLoss;
        private double target;
        private double confidence;
        private int qualityScore;
        private String urgency;
        private double riskReward;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.AllArgsConstructor
    public static class PublisherStats {
        private long totalPublished;
        private long publishSuccess;
        private long publishFailed;
        private long validationFailed;
        private long highPriorityCount;
        private double successRate;

        @Override
        public String toString() {
            return String.format("SignalPublisher: %d published, %d success, %d failed, %d validation failed, " +
                            "%d high-priority (%.1f%% success rate)",
                    totalPublished, publishSuccess, publishFailed, validationFailed,
                    highPriorityCount, successRate * 100);
        }
    }
}
