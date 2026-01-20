package com.kotsin.consumer.enrichment.intelligence.registry;

import com.kotsin.consumer.enrichment.intelligence.model.SetupCondition;
import com.kotsin.consumer.enrichment.intelligence.model.SetupDefinition;
import com.kotsin.consumer.enrichment.intelligence.model.SetupDefinition.*;
import com.kotsin.consumer.enrichment.model.DetectedEvent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * SetupDefinitionRegistry - Central registry for all trading setup definitions
 *
 * Contains the initial setup library:
 * 1. SCALP_REVERSAL_LONG - Long reversal from support
 * 2. SCALP_REVERSAL_SHORT - Short reversal from resistance
 * 3. SWING_LONG - Swing trade long with trend
 * 4. SWING_SHORT - Swing trade short with trend
 * 5. BREAKOUT_LONG - Breakout above resistance
 * 6. BREAKOUT_SHORT - Breakout below support
 * 7. MEAN_REVERSION_LONG - Buy oversold bounce
 * 8. MEAN_REVERSION_SHORT - Sell overbought fade
 */
@Slf4j
@Component
public class SetupDefinitionRegistry {

    /**
     * All registered setups by ID
     */
    private final Map<String, SetupDefinition> setups = new ConcurrentHashMap<>();

    /**
     * Setups by horizon for filtering
     */
    private final Map<SetupHorizon, List<SetupDefinition>> setupsByHorizon = new ConcurrentHashMap<>();

    /**
     * Setups by direction
     */
    private final Map<SetupDirection, List<SetupDefinition>> setupsByDirection = new ConcurrentHashMap<>();

    @PostConstruct
    public void initialize() {
        log.info("[SETUP_REGISTRY] Initializing setup definitions...");

        // Register all initial setups
        registerSetup(createScalpReversalLong());
        registerSetup(createScalpReversalShort());
        registerSetup(createSwingLong());
        registerSetup(createSwingShort());
        registerSetup(createBreakoutLong());
        registerSetup(createBreakoutShort());
        registerSetup(createMeanReversionLong());
        registerSetup(createMeanReversionShort());

        log.info("[SETUP_REGISTRY] Registered {} setup definitions", setups.size());
    }

    // ======================== SETUP 1: SCALP REVERSAL LONG ========================

    private SetupDefinition createScalpReversalLong() {
        return SetupDefinition.builder()
                .setupId("SCALP_REVERSAL_LONG")
                .description("Scalp long entry at support with order flow confirmation")
                .horizon(SetupHorizon.SCALP)
                .direction(SetupDirection.LONG)
                .category(SetupCategory.REVERSAL)

                .requiredConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("AT_SUPPORT")
                                .description("Price at support level")
                                .type(SetupCondition.ConditionType.PRICE_AT_SUPPORT)
                                .maxDistancePct(0.3)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("OFI_POS")
                                .description("Order flow positive")
                                .type(SetupCondition.ConditionType.OFI_POSITIVE)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("VOL_DELTA_POS")
                                .description("Volume delta positive")
                                .type(SetupCondition.ConditionType.VOLUME_DELTA_POSITIVE)
                                .build()
                ))

                .boosterConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("ST_BULLISH")
                                .description("SuperTrend bullish")
                                .type(SetupCondition.ConditionType.SUPERTREND_BULLISH)
                                .confidenceBoost(0.10)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("PUT_UNWIND")
                                .description("Put OI unwinding")
                                .type(SetupCondition.ConditionType.PUT_OI_UNWINDING)
                                .confidenceBoost(0.08)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("ABSORPTION")
                                .description("Absorption detected")
                                .type(SetupCondition.ConditionType.ABSORPTION_DETECTED)
                                .confidenceBoost(0.07)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("PRIME_SESSION")
                                .description("Prime trading session")
                                .type(SetupCondition.ConditionType.PRIME_SESSION)
                                .minSessionQuality(0.7)
                                .confidenceBoost(0.05)
                                .build()
                ))

                .invalidationConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("OFI_FLIP_BEAR")
                                .description("OFI flips bearish")
                                .type(SetupCondition.ConditionType.OFI_FLIP_BEARISH)
                                .build()
                ))

                .entryLogic("Enter at current price on OFI flip confirmation")
                .stopLogic("Stop below support level (0.3-0.5%)")
                .targetLogic("Target 1: 0.5%, Target 2: 1.0%")
                .baseConfidence(0.60)
                .maxConfidence(0.90)
                .defaultStopPct(0.4)
                .defaultTargetPct(0.8)
                .defaultRiskReward(2.0)
                .build();
    }

    // ======================== SETUP 2: SCALP REVERSAL SHORT ========================

    private SetupDefinition createScalpReversalShort() {
        return SetupDefinition.builder()
                .setupId("SCALP_REVERSAL_SHORT")
                .description("Scalp short entry at resistance with order flow confirmation")
                .horizon(SetupHorizon.SCALP)
                .direction(SetupDirection.SHORT)
                .category(SetupCategory.REVERSAL)

                .requiredConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("AT_RESISTANCE")
                                .description("Price at resistance level")
                                .type(SetupCondition.ConditionType.PRICE_AT_RESISTANCE)
                                .maxDistancePct(0.3)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("OFI_NEG")
                                .description("Order flow negative")
                                .type(SetupCondition.ConditionType.OFI_NEGATIVE)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("VOL_DELTA_NEG")
                                .description("Volume delta negative")
                                .type(SetupCondition.ConditionType.VOLUME_DELTA_NEGATIVE)
                                .build()
                ))

                .boosterConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("ST_BEARISH")
                                .description("SuperTrend bearish")
                                .type(SetupCondition.ConditionType.SUPERTREND_BEARISH)
                                .confidenceBoost(0.10)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("CALL_UNWIND")
                                .description("Call OI unwinding")
                                .type(SetupCondition.ConditionType.CALL_OI_UNWINDING)
                                .confidenceBoost(0.08)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("PRIME_SESSION")
                                .description("Prime trading session")
                                .type(SetupCondition.ConditionType.PRIME_SESSION)
                                .minSessionQuality(0.7)
                                .confidenceBoost(0.05)
                                .build()
                ))

                .invalidationConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("OFI_FLIP_BULL")
                                .description("OFI flips bullish")
                                .type(SetupCondition.ConditionType.OFI_FLIP_BULLISH)
                                .build()
                ))

                .entryLogic("Enter at current price on OFI flip confirmation")
                .stopLogic("Stop above resistance level (0.3-0.5%)")
                .targetLogic("Target 1: 0.5%, Target 2: 1.0%")
                .baseConfidence(0.60)
                .maxConfidence(0.88)
                .defaultStopPct(0.4)
                .defaultTargetPct(0.8)
                .build();
    }

    // ======================== SETUP 3: SWING LONG ========================

    private SetupDefinition createSwingLong() {
        return SetupDefinition.builder()
                .setupId("SWING_LONG")
                .description("Swing long with SuperTrend and OFI alignment")
                .horizon(SetupHorizon.SWING)
                .direction(SetupDirection.LONG)
                .category(SetupCategory.CONTINUATION)

                .requiredConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("ST_BULLISH")
                                .description("SuperTrend bullish")
                                .type(SetupCondition.ConditionType.SUPERTREND_BULLISH)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("OFI_STRONG_BUY")
                                .description("OFI in strong buy regime")
                                .type(SetupCondition.ConditionType.OFI_STRONG_BUY)
                                .build()
                ))

                .boosterConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("CALL_BUILD")
                                .description("Call OI building")
                                .type(SetupCondition.ConditionType.CALL_OI_BUILDING)
                                .confidenceBoost(0.08)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("GEX_TREND")
                                .description("GEX trending regime")
                                .type(SetupCondition.ConditionType.GEX_TRENDING)
                                .confidenceBoost(0.07)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("AT_SUPPORT")
                                .description("Pullback to support")
                                .type(SetupCondition.ConditionType.PRICE_AT_SUPPORT)
                                .maxDistancePct(0.5)
                                .confidenceBoost(0.10)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("PRIME_SESSION")
                                .description("Prime session")
                                .type(SetupCondition.ConditionType.PRIME_SESSION)
                                .confidenceBoost(0.05)
                                .build()
                ))

                .invalidationConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("ST_FLIP_BEAR")
                                .description("SuperTrend flips bearish")
                                .type(SetupCondition.ConditionType.SUPERTREND_BEARISH)
                                .build(),
                        // FIX: Add OFI invalidation - if OFI flips bearish, invalidate SWING_LONG
                        SetupCondition.builder()
                                .conditionId("OFI_FLIP_BEAR")
                                .description("OFI flips to strong sell")
                                .type(SetupCondition.ConditionType.OFI_STRONG_SELL)
                                .build()
                ))

                .entryLogic("Enter on pullback to SuperTrend line")
                .stopLogic("Stop below SuperTrend value")
                .targetLogic("Target: 2x risk or trailing stop")
                .baseConfidence(0.62)
                .maxConfidence(0.92)
                .defaultStopPct(0.8)
                .defaultTargetPct(1.6)
                .build();
    }

    // ======================== SETUP 4: SWING SHORT ========================

    private SetupDefinition createSwingShort() {
        return SetupDefinition.builder()
                .setupId("SWING_SHORT")
                .description("Swing short with SuperTrend and OFI alignment")
                .horizon(SetupHorizon.SWING)
                .direction(SetupDirection.SHORT)
                .category(SetupCategory.CONTINUATION)

                .requiredConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("ST_BEARISH")
                                .description("SuperTrend bearish")
                                .type(SetupCondition.ConditionType.SUPERTREND_BEARISH)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("OFI_STRONG_SELL")
                                .description("OFI in strong sell regime")
                                .type(SetupCondition.ConditionType.OFI_STRONG_SELL)
                                .build()
                ))

                .boosterConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("PUT_BUILD")
                                .description("Put OI building")
                                .type(SetupCondition.ConditionType.PUT_OI_BUILDING)
                                .confidenceBoost(0.08)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("GEX_TREND")
                                .description("GEX trending regime")
                                .type(SetupCondition.ConditionType.GEX_TRENDING)
                                .confidenceBoost(0.07)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("AT_RESISTANCE")
                                .description("Pullback to resistance")
                                .type(SetupCondition.ConditionType.PRICE_AT_RESISTANCE)
                                .maxDistancePct(0.5)
                                .confidenceBoost(0.10)
                                .build()
                ))

                .invalidationConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("ST_FLIP_BULL")
                                .description("SuperTrend flips bullish")
                                .type(SetupCondition.ConditionType.SUPERTREND_BULLISH)
                                .build(),
                        // FIX: Add OFI invalidation - if OFI flips bullish, invalidate SWING_SHORT
                        SetupCondition.builder()
                                .conditionId("OFI_FLIP_BULL")
                                .description("OFI flips to strong buy")
                                .type(SetupCondition.ConditionType.OFI_STRONG_BUY)
                                .build()
                ))

                .entryLogic("Enter on pullback to SuperTrend line")
                .stopLogic("Stop above SuperTrend value")
                .targetLogic("Target: 2x risk or trailing stop")
                .baseConfidence(0.62)
                .maxConfidence(0.90)
                .defaultStopPct(0.8)
                .defaultTargetPct(1.6)
                .build();
    }

    // ======================== SETUP 5: BREAKOUT LONG ========================

    private SetupDefinition createBreakoutLong() {
        return SetupDefinition.builder()
                .setupId("BREAKOUT_LONG")
                .description("Long breakout above resistance with volume confirmation")
                .horizon(SetupHorizon.SWING)
                .direction(SetupDirection.LONG)
                .category(SetupCategory.BREAKOUT)

                .requiredConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("ST_BULLISH")
                                .description("SuperTrend confirms bullish")
                                .type(SetupCondition.ConditionType.SUPERTREND_BULLISH)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("OFI_POS")
                                .description("Strong order flow")
                                .type(SetupCondition.ConditionType.OFI_STRONG_BUY)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("EVENT_BREAKOUT")
                                .description("Breakout event detected")
                                .type(SetupCondition.ConditionType.EVENT_PRESENT)
                                .requiredEventTypes(List.of(DetectedEvent.EventType.RESISTANCE_BREAK))
                                .build()
                ))

                .boosterConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("VOL_SURGE")
                                .description("Volume surge")
                                .type(SetupCondition.ConditionType.EVENT_PRESENT)
                                .requiredEventTypes(List.of(DetectedEvent.EventType.VOLUME_SURGE))
                                .confidenceBoost(0.10)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("GEX_TREND")
                                .description("GEX trending")
                                .type(SetupCondition.ConditionType.GEX_TRENDING)
                                .confidenceBoost(0.08)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("CALL_BUILD")
                                .description("Call OI building")
                                .type(SetupCondition.ConditionType.CALL_OI_BUILDING)
                                .confidenceBoost(0.07)
                                .build()
                ))

                .invalidationConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("OFI_FLIP")
                                .description("OFI flips bearish")
                                .type(SetupCondition.ConditionType.OFI_FLIP_BEARISH)
                                .build()
                ))

                .entryLogic("Enter on breakout confirmation above resistance")
                .stopLogic("Stop below broken resistance (0.3%)")
                .targetLogic("Target: Measured move or 2:1 R:R")
                .baseConfidence(0.58)
                .maxConfidence(0.85)
                .defaultStopPct(0.3)
                .defaultTargetPct(1.0)
                .defaultRiskReward(3.0)
                .build();
    }

    // ======================== SETUP 6: BREAKOUT SHORT ========================

    private SetupDefinition createBreakoutShort() {
        return SetupDefinition.builder()
                .setupId("BREAKOUT_SHORT")
                .description("Short breakdown below support with volume confirmation")
                .horizon(SetupHorizon.SWING)
                .direction(SetupDirection.SHORT)
                .category(SetupCategory.BREAKOUT)

                .requiredConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("ST_BEARISH")
                                .description("SuperTrend confirms bearish")
                                .type(SetupCondition.ConditionType.SUPERTREND_BEARISH)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("OFI_NEG")
                                .description("Strong selling flow")
                                .type(SetupCondition.ConditionType.OFI_STRONG_SELL)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("EVENT_BREAKDOWN")
                                .description("Support break detected")
                                .type(SetupCondition.ConditionType.EVENT_PRESENT)
                                .requiredEventTypes(List.of(DetectedEvent.EventType.SUPPORT_BREAK))
                                .build()
                ))

                .boosterConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("VOL_SURGE")
                                .description("Volume surge")
                                .type(SetupCondition.ConditionType.EVENT_PRESENT)
                                .requiredEventTypes(List.of(DetectedEvent.EventType.VOLUME_SURGE))
                                .confidenceBoost(0.10)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("GEX_TREND")
                                .description("GEX trending")
                                .type(SetupCondition.ConditionType.GEX_TRENDING)
                                .confidenceBoost(0.08)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("PUT_BUILD")
                                .description("Put OI building")
                                .type(SetupCondition.ConditionType.PUT_OI_BUILDING)
                                .confidenceBoost(0.07)
                                .build()
                ))

                .invalidationConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("OFI_FLIP")
                                .description("OFI flips bullish")
                                .type(SetupCondition.ConditionType.OFI_FLIP_BULLISH)
                                .build()
                ))

                .entryLogic("Enter on breakdown confirmation below support")
                .stopLogic("Stop above broken support (0.3%)")
                .targetLogic("Target: Measured move or 2:1 R:R")
                .baseConfidence(0.58)
                .maxConfidence(0.85)
                .defaultStopPct(0.3)
                .defaultTargetPct(1.0)
                .build();
    }

    // ======================== SETUP 7: MEAN REVERSION LONG ========================

    private SetupDefinition createMeanReversionLong() {
        return SetupDefinition.builder()
                .setupId("MEAN_REVERSION_LONG")
                .description("Long oversold bounce with BB and GEX confirmation")
                .horizon(SetupHorizon.SCALP)
                .direction(SetupDirection.LONG)
                .category(SetupCategory.MEAN_REVERSION)

                .requiredConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("BB_OVERSOLD")
                                .description("BB %B < 0.2 (oversold)")
                                .type(SetupCondition.ConditionType.BB_OVERSOLD)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("GEX_MR")
                                .description("GEX mean reverting regime")
                                .type(SetupCondition.ConditionType.GEX_MEAN_REVERTING)
                                .build()
                ))

                .boosterConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("OFI_FLIP")
                                .description("OFI flipping positive")
                                .type(SetupCondition.ConditionType.OFI_FLIP_BULLISH)
                                .confidenceBoost(0.12)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("EXHAUST")
                                .description("Selling exhaustion")
                                .type(SetupCondition.ConditionType.EVENT_PRESENT)
                                .requiredEventTypes(List.of(DetectedEvent.EventType.SELLING_EXHAUSTION))
                                .confidenceBoost(0.10)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("AT_SUPPORT")
                                .description("At support level")
                                .type(SetupCondition.ConditionType.PRICE_AT_SUPPORT)
                                .maxDistancePct(0.3)
                                .confidenceBoost(0.08)
                                .build()
                ))

                .invalidationConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("SUPPORT_BREAK")
                                .description("Support breaks")
                                .type(SetupCondition.ConditionType.EVENT_PRESENT)
                                .requiredEventTypes(List.of(DetectedEvent.EventType.SUPPORT_BREAK))
                                .build()
                ))

                .entryLogic("Enter at lower BB with reversal confirmation")
                .stopLogic("Stop below recent low or 0.5%")
                .targetLogic("Target: Middle BB or 0.8%")
                .baseConfidence(0.55)
                .maxConfidence(0.85)
                .defaultStopPct(0.5)
                .defaultTargetPct(0.8)
                .build();
    }

    // ======================== SETUP 8: MEAN REVERSION SHORT ========================

    private SetupDefinition createMeanReversionShort() {
        return SetupDefinition.builder()
                .setupId("MEAN_REVERSION_SHORT")
                .description("Short overbought fade with BB and GEX confirmation")
                .horizon(SetupHorizon.SCALP)
                .direction(SetupDirection.SHORT)
                .category(SetupCategory.MEAN_REVERSION)

                .requiredConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("BB_OVERBOUGHT")
                                .description("BB %B > 0.8 (overbought)")
                                .type(SetupCondition.ConditionType.BB_OVERBOUGHT)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("GEX_MR")
                                .description("GEX mean reverting regime")
                                .type(SetupCondition.ConditionType.GEX_MEAN_REVERTING)
                                .build()
                ))

                .boosterConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("OFI_FLIP")
                                .description("OFI flipping negative")
                                .type(SetupCondition.ConditionType.OFI_FLIP_BEARISH)
                                .confidenceBoost(0.12)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("EXHAUST")
                                .description("Buying exhaustion")
                                .type(SetupCondition.ConditionType.EVENT_PRESENT)
                                .requiredEventTypes(List.of(DetectedEvent.EventType.BUYING_EXHAUSTION))
                                .confidenceBoost(0.10)
                                .build(),
                        SetupCondition.builder()
                                .conditionId("AT_RESISTANCE")
                                .description("At resistance level")
                                .type(SetupCondition.ConditionType.PRICE_AT_RESISTANCE)
                                .maxDistancePct(0.3)
                                .confidenceBoost(0.08)
                                .build()
                ))

                .invalidationConditions(List.of(
                        SetupCondition.builder()
                                .conditionId("RESISTANCE_BREAK")
                                .description("Resistance breaks")
                                .type(SetupCondition.ConditionType.EVENT_PRESENT)
                                .requiredEventTypes(List.of(DetectedEvent.EventType.RESISTANCE_BREAK))
                                .build()
                ))

                .entryLogic("Enter at upper BB with reversal confirmation")
                .stopLogic("Stop above recent high or 0.5%")
                .targetLogic("Target: Middle BB or 0.8%")
                .baseConfidence(0.55)
                .maxConfidence(0.85)
                .defaultStopPct(0.5)
                .defaultTargetPct(0.8)
                .build();
    }

    // ======================== REGISTRY OPERATIONS ========================

    /**
     * Register a setup definition
     */
    public void registerSetup(SetupDefinition setup) {
        if (setup == null || !setup.isValid()) {
            log.warn("[SETUP_REGISTRY] Invalid setup definition, not registering");
            return;
        }

        setups.put(setup.getSetupId(), setup);

        // Index by horizon
        setupsByHorizon.computeIfAbsent(setup.getHorizon(), k -> new ArrayList<>()).add(setup);

        // Index by direction
        setupsByDirection.computeIfAbsent(setup.getDirection(), k -> new ArrayList<>()).add(setup);

        log.info("[SETUP_REGISTRY] Registered setup: {}", setup);
    }

    /**
     * Get setup by ID
     */
    public Optional<SetupDefinition> getSetup(String setupId) {
        return Optional.ofNullable(setups.get(setupId));
    }

    /**
     * Get all setups
     */
    public Collection<SetupDefinition> getAllSetups() {
        return setups.values();
    }

    /**
     * Get setups by horizon
     */
    public List<SetupDefinition> getSetupsByHorizon(SetupHorizon horizon) {
        return setupsByHorizon.getOrDefault(horizon, Collections.emptyList());
    }

    /**
     * Get setups by direction
     */
    public List<SetupDefinition> getSetupsByDirection(SetupDirection direction) {
        return setupsByDirection.getOrDefault(direction, Collections.emptyList());
    }

    /**
     * Get setups by category
     */
    public List<SetupDefinition> getSetupsByCategory(SetupCategory category) {
        return setups.values().stream()
                .filter(s -> s.getCategory() == category)
                .collect(Collectors.toList());
    }

    /**
     * Get setup count
     */
    public int getSetupCount() {
        return setups.size();
    }
}
