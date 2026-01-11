package com.kotsin.consumer.enrichment.pattern.registry;

import com.kotsin.consumer.enrichment.model.DetectedEvent;
import com.kotsin.consumer.enrichment.pattern.model.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SequenceTemplateRegistry - Central registry for all pattern templates
 *
 * Contains the 5 initial predictive patterns:
 * 1. REVERSAL_FROM_SUPPORT - Bounce from support with OFI flip
 * 2. REVERSAL_FROM_RESISTANCE - Rejection from resistance
 * 3. GAMMA_SQUEEZE_LONG - Dealer hedging drives price to strike
 * 4. BREAKOUT_CONFIRMATION - Resistance break with volume
 * 5. TREND_CONTINUATION - Pullback in trend with OFI confirmation
 *
 * Templates can be dynamically updated based on historical performance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SequenceTemplateRegistry {

    /**
     * All registered templates by ID
     */
    private final Map<String, SequenceTemplate> templates = new ConcurrentHashMap<>();

    /**
     * Templates indexed by first required event type (for fast lookup)
     */
    private final Map<DetectedEvent.EventType, List<SequenceTemplate>> templatesByFirstEvent = new ConcurrentHashMap<>();

    @PostConstruct
    public void initialize() {
        log.info("[PATTERN_REGISTRY] Initializing pattern templates...");

        // Register initial patterns
        registerTemplate(createReversalFromSupport());
        registerTemplate(createReversalFromResistance());
        registerTemplate(createGammaSqueezeLong());
        registerTemplate(createBreakoutConfirmation());
        registerTemplate(createTrendContinuation());

        log.info("[PATTERN_REGISTRY] Registered {} pattern templates", templates.size());
    }

    // ======================== PATTERN 1: REVERSAL FROM SUPPORT ========================

    private SequenceTemplate createReversalFromSupport() {
        return SequenceTemplate.builder()
                .templateId("REVERSAL_FROM_SUPPORT")
                .description("Price bounces from support after seller exhaustion and OFI flip")
                .category(SequenceTemplate.PatternCategory.REVERSAL)
                .direction(SequenceTemplate.PatternDirection.BULLISH)
                .horizon(SequenceTemplate.TradingHorizon.SCALP)

                // Required events (must occur in order)
                .requiredEvents(List.of(
                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.SELLING_EXHAUSTION)
                                .direction(DetectedEvent.EventDirection.BULLISH)
                                .minStrength(0.6)
                                .description("Selling pressure exhausting at support")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.OFI_FLIP)
                                .direction(DetectedEvent.EventDirection.BULLISH)
                                .minStrength(0.5)
                                .description("Order flow flips from negative to positive")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.ABSORPTION)
                                .direction(DetectedEvent.EventDirection.BULLISH)
                                .minStrength(0.5)
                                .description("Large buyer absorbing selling pressure")
                                .build()
                ))

                // Booster events (optional, add confidence)
                .boosterEvents(List.of(
                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.PUT_OI_UNWINDING)
                                .probabilityBoost(0.08)
                                .description("Put writers covering positions")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.CALL_OI_SURGE)
                                .probabilityBoost(0.07)
                                .description("New call buying entering")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.BB_LOWER_TOUCH)
                                .probabilityBoost(0.05)
                                .description("At lower Bollinger Band (oversold)")
                                .build()
                ))

                // Invalidation events
                .invalidationEvents(List.of(
                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.OFI_FLIP)
                                .direction(DetectedEvent.EventDirection.BEARISH)
                                .description("OFI flips back to negative")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.BUYING_EXHAUSTION)
                                .description("Buyers exhausted - reversal failing")
                                .build()
                ))

                .maxDuration(Duration.ofMinutes(30))
                .minEventSpacing(Duration.ofSeconds(30))
                .expectedOutcome(ExpectedOutcome.reversal(1.0))
                .baseConfidence(0.65)
                .maxConfidence(0.85)
                .minConfidenceToSignal(0.45) // Lowered from 0.55
                .build();
    }

    // ======================== PATTERN 2: REVERSAL FROM RESISTANCE ========================

    private SequenceTemplate createReversalFromResistance() {
        return SequenceTemplate.builder()
                .templateId("REVERSAL_FROM_RESISTANCE")
                .description("Price rejects from resistance after buyer exhaustion and OFI flip")
                .category(SequenceTemplate.PatternCategory.REVERSAL)
                .direction(SequenceTemplate.PatternDirection.BEARISH)
                .horizon(SequenceTemplate.TradingHorizon.SCALP)

                .requiredEvents(List.of(
                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.BUYING_EXHAUSTION)
                                .direction(DetectedEvent.EventDirection.BEARISH)
                                .minStrength(0.6)
                                .description("Buying pressure exhausting at resistance")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.OFI_FLIP)
                                .direction(DetectedEvent.EventDirection.BEARISH)
                                .minStrength(0.5)
                                .description("Order flow flips from positive to negative")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.ABSORPTION)
                                .direction(DetectedEvent.EventDirection.BEARISH)
                                .minStrength(0.5)
                                .description("Large seller absorbing buying pressure")
                                .build()
                ))

                .boosterEvents(List.of(
                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.CALL_OI_UNWINDING)
                                .probabilityBoost(0.08)
                                .description("Call writers covering")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.PUT_OI_SURGE)
                                .probabilityBoost(0.07)
                                .description("New put buying")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.BB_UPPER_TOUCH)
                                .probabilityBoost(0.05)
                                .description("At upper Bollinger Band (overbought)")
                                .build()
                ))

                .invalidationEvents(List.of(
                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.OFI_FLIP)
                                .direction(DetectedEvent.EventDirection.BULLISH)
                                .description("OFI flips back to positive")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.SELLING_EXHAUSTION)
                                .description("Sellers exhausted - reversal failing")
                                .build()
                ))

                .maxDuration(Duration.ofMinutes(30))
                .expectedOutcome(ExpectedOutcome.reversal(1.0))
                .baseConfidence(0.65)
                .maxConfidence(0.85)
                .build();
    }

    // ======================== PATTERN 3: GAMMA SQUEEZE LONG ========================

    private SequenceTemplate createGammaSqueezeLong() {
        return SequenceTemplate.builder()
                .templateId("GAMMA_SQUEEZE_LONG")
                .description("Dealer hedging flow drives price to strike and beyond")
                .category(SequenceTemplate.PatternCategory.SQUEEZE)
                .direction(SequenceTemplate.PatternDirection.BULLISH)
                .horizon(SequenceTemplate.TradingHorizon.SCALP)

                .requiredEvents(List.of(
                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.GAMMA_SQUEEZE_SETUP)
                                .direction(DetectedEvent.EventDirection.BULLISH)
                                .minStrength(0.6)
                                .description("Gamma squeeze conditions detected")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.CALL_OI_SURGE)
                                .minStrength(0.5)
                                .description("Call OI surging near strike")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.OFI_FLIP)
                                .direction(DetectedEvent.EventDirection.BULLISH)
                                .minStrength(0.4)
                                .description("Order flow confirms upward pressure")
                                .build()
                ))

                .boosterEvents(List.of(
                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.SUPERTREND_FLIP)
                                .direction(DetectedEvent.EventDirection.BULLISH)
                                .probabilityBoost(0.10)
                                .description("SuperTrend flips bullish")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.ABSORPTION)
                                .direction(DetectedEvent.EventDirection.BULLISH)
                                .probabilityBoost(0.08)
                                .description("Absorption of sell orders")
                                .build()
                ))

                .invalidationEvents(List.of(
                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.OFI_FLIP)
                                .direction(DetectedEvent.EventDirection.BEARISH)
                                .description("Flow reverses")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.CALL_OI_UNWINDING)
                                .minStrength(0.6)
                                .description("Call OI unwinding - squeeze failing")
                                .build()
                ))

                .maxDuration(Duration.ofMinutes(45))
                .expectedOutcome(ExpectedOutcome.gammaSqueeze(1.5))
                .baseConfidence(0.60)
                .maxConfidence(0.85)
                .build();
    }

    // ======================== PATTERN 4: BREAKOUT CONFIRMATION ========================

    private SequenceTemplate createBreakoutConfirmation() {
        return SequenceTemplate.builder()
                .templateId("BREAKOUT_CONFIRMATION")
                .description("Price breaks resistance with volume and OFI confirmation")
                .category(SequenceTemplate.PatternCategory.BREAKOUT)
                .direction(SequenceTemplate.PatternDirection.BULLISH)
                .horizon(SequenceTemplate.TradingHorizon.SWING)

                .requiredEvents(List.of(
                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.BREAKOUT_SETUP)
                                .direction(DetectedEvent.EventDirection.BULLISH)
                                .minStrength(0.5)
                                .description("Price testing resistance")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.OFI_FLIP)
                                .direction(DetectedEvent.EventDirection.BULLISH)
                                .minStrength(0.6)
                                .description("Strong order flow supporting break")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.SUPERTREND_FLIP)
                                .direction(DetectedEvent.EventDirection.BULLISH)
                                .minStrength(0.5)
                                .description("SuperTrend confirms direction")
                                .build()
                ))

                .boosterEvents(List.of(
                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.CALL_OI_SURGE)
                                .probabilityBoost(0.08)
                                .description("Options flow confirms")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.ABSORPTION)
                                .direction(DetectedEvent.EventDirection.BULLISH)
                                .probabilityBoost(0.07)
                                .description("Institutional accumulation")
                                .build()
                ))

                .invalidationEvents(List.of(
                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.OFI_FLIP)
                                .direction(DetectedEvent.EventDirection.BEARISH)
                                .description("Flow reverses - failed breakout")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.SELLING_EXHAUSTION)
                                .description("Momentum dying")
                                .build()
                ))

                .maxDuration(Duration.ofMinutes(60))
                .expectedOutcome(ExpectedOutcome.breakout(1.5))
                .baseConfidence(0.60)
                .maxConfidence(0.85)
                .build();
    }

    // ======================== PATTERN 5: TREND CONTINUATION ========================

    private SequenceTemplate createTrendContinuation() {
        return SequenceTemplate.builder()
                .templateId("TREND_CONTINUATION")
                .description("Pullback in uptrend with OFI and OI confirmation")
                .category(SequenceTemplate.PatternCategory.CONTINUATION)
                .direction(SequenceTemplate.PatternDirection.BULLISH)
                .horizon(SequenceTemplate.TradingHorizon.SWING)

                .requiredEvents(List.of(
                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.SELLING_EXHAUSTION)
                                .minStrength(0.5)
                                .description("Pullback sellers exhausting")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.OFI_FLIP)
                                .direction(DetectedEvent.EventDirection.BULLISH)
                                .minStrength(0.5)
                                .description("OFI flips back to positive")
                                .build()
                ))

                .boosterEvents(List.of(
                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.CALL_OI_SURGE)
                                .probabilityBoost(0.08)
                                .description("Call OI building")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.BB_LOWER_TOUCH)
                                .probabilityBoost(0.05)
                                .description("At lower BB (mean reversion)")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.ABSORPTION)
                                .direction(DetectedEvent.EventDirection.BULLISH)
                                .probabilityBoost(0.07)
                                .description("Absorption detected")
                                .build()
                ))

                .invalidationEvents(List.of(
                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.SUPERTREND_FLIP)
                                .direction(DetectedEvent.EventDirection.BEARISH)
                                .description("Trend breaks down")
                                .build(),

                        EventCondition.builder()
                                .eventType(DetectedEvent.EventType.BUYING_EXHAUSTION)
                                .description("Trend losing momentum")
                                .build()
                ))

                .maxDuration(Duration.ofMinutes(45))
                .expectedOutcome(ExpectedOutcome.swing(1.5, 0.8))
                .baseConfidence(0.60)
                .maxConfidence(0.82)
                .build();
    }

    // ======================== REGISTRY OPERATIONS ========================

    /**
     * Register a template
     */
    public void registerTemplate(SequenceTemplate template) {
        if (template == null || !template.isValid()) {
            log.warn("[PATTERN_REGISTRY] Invalid template, not registering");
            return;
        }

        templates.put(template.getTemplateId(), template);

        // Index by first required event type
        if (!template.getRequiredEvents().isEmpty()) {
            DetectedEvent.EventType firstEventType = template.getRequiredEvents().get(0).getEventType();
            templatesByFirstEvent
                    .computeIfAbsent(firstEventType, k -> new ArrayList<>())
                    .add(template);
        }

        log.info("[PATTERN_REGISTRY] Registered template: {}", template);
    }

    /**
     * Get template by ID
     */
    public Optional<SequenceTemplate> getTemplate(String templateId) {
        return Optional.ofNullable(templates.get(templateId));
    }

    /**
     * Get all templates
     */
    public Collection<SequenceTemplate> getAllTemplates() {
        return templates.values();
    }

    /**
     * Get templates that could start with this event type
     */
    public List<SequenceTemplate> getTemplatesStartingWith(DetectedEvent.EventType eventType) {
        return templatesByFirstEvent.getOrDefault(eventType, Collections.emptyList());
    }

    /**
     * Get templates by category
     */
    public List<SequenceTemplate> getTemplatesByCategory(SequenceTemplate.PatternCategory category) {
        return templates.values().stream()
                .filter(t -> t.getCategory() == category)
                .toList();
    }

    /**
     * Get templates by direction
     */
    public List<SequenceTemplate> getTemplatesByDirection(SequenceTemplate.PatternDirection direction) {
        return templates.values().stream()
                .filter(t -> t.getDirection() == direction)
                .toList();
    }

    /**
     * Update template with new historical stats
     */
    public void updateTemplateStats(String templateId, HistoricalStats stats) {
        SequenceTemplate template = templates.get(templateId);
        if (template != null) {
            template.setHistoricalStats(stats);
            log.debug("[PATTERN_REGISTRY] Updated stats for {}: {}", templateId, stats);
        }
    }

    /**
     * Get template count
     */
    public int getTemplateCount() {
        return templates.size();
    }

    /**
     * Check if template exists
     */
    public boolean hasTemplate(String templateId) {
        return templates.containsKey(templateId);
    }
}
