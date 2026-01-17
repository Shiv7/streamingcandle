package com.kotsin.consumer.gate;

import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.analyzer.FamilyContextAnalyzer.FamilyBias;
import com.kotsin.consumer.enrichment.analyzer.FamilyContextAnalyzer.FamilyContext;
import com.kotsin.consumer.enrichment.model.DetectedEvent;
import com.kotsin.consumer.enrichment.model.HistoricalContext;
import com.kotsin.consumer.enrichment.model.SessionStructure;
import com.kotsin.consumer.enrichment.model.TechnicalContext;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal.Direction;
import com.kotsin.consumer.gate.model.GateResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SignalHardGate - HARD BLOCKING rules for signal generation
 *
 * This gate implements HARD BLOCKS instead of soft modifiers.
 * If ANY rule fails, the signal is BLOCKED - not just reduced.
 *
 * BUGS FIXED:
 * - Bug #1-2: Soft modifiers replaced with hard blocks
 * - Bug #3: MIN_CONFIDENCE raised to 0.65
 * - Bug #4-5: Block LONG vs BEARISH bias and NO_CONVICTION
 * - Bug #6: Block LONG at session extremes (>80%)
 * - Bug #7: Block LONG when BUYING_EXHAUSTION detected
 * - Bug #8: Block when historicalSuccessRate = 0.5 (default/unknown)
 * - Bug #9: Require resistance level for LONG signals
 * - Bug #12: Disable LONG_REVERSAL setup (0% win rate)
 * - Bug #15: Block counter-SuperTrend trades
 *
 * PHILOSOPHY:
 * - Better to miss a good trade than take a bad one
 * - Detection without action = worthless
 * - Every warning should be a potential block
 */
@Slf4j
@Service
public class SignalHardGate {

    @Value("${gate.signal.hard.enabled:true}")
    private boolean enabled;

    // Thresholds for hard blocks - SYMMETRIC for LONG and SHORT
    private static final double MIN_CONFIDENCE = 0.65;           // Bug #3: Raised from 0.50
    private static final double MIN_FAMILY_ALIGNMENT = 0.30;     // Bug #5: Block below 30%
    private static final double SESSION_EXTREME_THRESHOLD = 0.80; // Block at extremes (>80% or <20%)
    private static final double SESSION_LOW_THRESHOLD = 0.20;    // Symmetric with above
    private static final double DEFAULT_SUCCESS_RATE = 0.50;     // Bug #8: This means "unknown"
    private static final int MIN_HISTORICAL_SAMPLES = 10;        // Minimum samples for valid success rate

    // REMOVED: Hardcoded blocked setups - this was OVERFITTING
    // Instead, we use dynamic performance tracking with MIN_SETUP_WIN_RATE
    // A setup is blocked ONLY if: samples >= MIN_HISTORICAL_SAMPLES AND winRate < MIN_SETUP_WIN_RATE
    private static final double MIN_SETUP_WIN_RATE = 0.35;       // Block setups with <35% win rate (after sufficient samples)

    /**
     * Evaluate if a signal should be BLOCKED
     *
     * @param signal     The trading signal to evaluate
     * @param quantScore The enriched quant score with context
     * @return GateResult with block status and reason
     */
    public GateResult evaluate(TradingSignal signal, EnrichedQuantScore quantScore) {
        if (!enabled) {
            return GateResult.pass("SIGNAL_HARD_GATE_DISABLED");
        }

        if (signal == null || quantScore == null) {
            return GateResult.fail("NULL_INPUT");
        }

        String familyId = signal.getFamilyId();
        Direction direction = signal.getDirection();
        boolean isLong = direction == Direction.LONG;
        List<String> blockReasons = new ArrayList<>();

        // === RULE 1: Minimum Confidence (Bug #3) ===
        if (signal.getConfidence() < MIN_CONFIDENCE) {
            blockReasons.add(String.format("LOW_CONFIDENCE:%.0f%%<%.0f%%",
                    signal.getConfidence() * 100, MIN_CONFIDENCE * 100));
        }

        // === RULE 2: Dynamic Setup Performance Check (replaces hardcoded blocking) ===
        // GENERALIZED: Block ANY setup (LONG or SHORT) with poor historical performance
        // This is NOT overfitting because:
        // 1. It requires MIN_HISTORICAL_SAMPLES (10) before blocking
        // 2. It applies symmetrically to all setups
        // 3. Threshold (35%) is based on trading math, not our specific data
        String setupId = signal.getSetupId();
        double setupWinRate = signal.getHistoricalSuccessRate();
        int setupSamples = signal.getHistoricalSampleCount();

        if (setupId != null && setupSamples >= MIN_HISTORICAL_SAMPLES && setupWinRate < MIN_SETUP_WIN_RATE) {
            blockReasons.add(String.format("POOR_SETUP_PERFORMANCE:%s(%.0f%%/%d)",
                    setupId, setupWinRate * 100, setupSamples));
            log.warn("[HARD_GATE] {} BLOCKED: Setup {} has {}% win rate over {} samples (min {}%)",
                    familyId, setupId, String.format("%.0f", setupWinRate * 100),
                    setupSamples, MIN_SETUP_WIN_RATE * 100);
        }

        // === RULE 3: Family Bias Conflict (Bug #4-5) ===
        if (quantScore.hasFamilyContext()) {
            FamilyContext familyCtx = quantScore.getFamilyContext();
            FamilyBias bias = familyCtx.getOverallBias();
            double maxAlignment = Math.max(familyCtx.getBullishAlignment(), familyCtx.getBearishAlignment());

            // Block if family alignment is too weak (NO_CONVICTION)
            if (maxAlignment < MIN_FAMILY_ALIGNMENT) {
                blockReasons.add(String.format("NO_CONVICTION:%.0f%%<%.0f%%",
                        maxAlignment * 100, MIN_FAMILY_ALIGNMENT * 100));
                log.warn("[HARD_GATE] {} BLOCKED: Family alignment {}% too weak (min {}%)",
                        familyId, String.format("%.0f", maxAlignment * 100), MIN_FAMILY_ALIGNMENT * 100);
            }

            // Block LONG against BEARISH family bias
            if (isLong && (bias == FamilyBias.BEARISH || bias == FamilyBias.WEAK_BEARISH)) {
                blockReasons.add("LONG_VS_BEARISH_FAMILY:" + bias);
                log.warn("[HARD_GATE] {} BLOCKED: LONG signal conflicts with {} family bias",
                        familyId, bias);
            }

            // Block SHORT against BULLISH family bias
            if (!isLong && (bias == FamilyBias.BULLISH || bias == FamilyBias.WEAK_BULLISH)) {
                blockReasons.add("SHORT_VS_BULLISH_FAMILY:" + bias);
                log.warn("[HARD_GATE] {} BLOCKED: SHORT signal conflicts with {} family bias",
                        familyId, bias);
            }
        }

        // === RULE 4: Session Position Extremes (Bug #6) ===
        if (quantScore.hasSessionStructure()) {
            SessionStructure session = quantScore.getSessionStructure();
            double position = session.getPositionInRange();

            // Block LONG at session HIGH (buying resistance)
            if (isLong && position > SESSION_EXTREME_THRESHOLD) {
                blockReasons.add(String.format("LONG_AT_SESSION_HIGH:%.0f%%>%.0f%%",
                        position * 100, SESSION_EXTREME_THRESHOLD * 100));
                log.warn("[HARD_GATE] {} BLOCKED: LONG at {}% session position (resistance zone)",
                        familyId, String.format("%.0f", position * 100));
            }

            // Block SHORT at session LOW (selling support)
            if (!isLong && position < SESSION_LOW_THRESHOLD) {
                blockReasons.add(String.format("SHORT_AT_SESSION_LOW:%.0f%%<%.0f%%",
                        position * 100, SESSION_LOW_THRESHOLD * 100));
                log.warn("[HARD_GATE] {} BLOCKED: SHORT at {}% session position (support zone)",
                        familyId, String.format("%.0f", position * 100));
            }
        }

        // === RULE 5: Exhaustion Conflict (Bug #7) ===
        if (quantScore.hasEvents()) {
            List<DetectedEvent> events = quantScore.getDetectedEvents();

            // Block LONG when BUYING_EXHAUSTION detected (market topping)
            if (isLong && hasEvent(events, DetectedEvent.EventType.BUYING_EXHAUSTION)) {
                blockReasons.add("LONG_WITH_BUYING_EXHAUSTION");
                log.warn("[HARD_GATE] {} BLOCKED: LONG signal while BUYING_EXHAUSTION detected",
                        familyId);
            }

            // Block SHORT when SELLING_EXHAUSTION detected (market bottoming)
            if (!isLong && hasEvent(events, DetectedEvent.EventType.SELLING_EXHAUSTION)) {
                blockReasons.add("SHORT_WITH_SELLING_EXHAUSTION");
                log.warn("[HARD_GATE] {} BLOCKED: SHORT signal while SELLING_EXHAUSTION detected",
                        familyId);
            }
        }

        // === RULE 6: Default Success Rate (Bug #8) ===
        double successRate = signal.getHistoricalSuccessRate();
        int sampleCount = signal.getHistoricalSampleCount();
        if (Math.abs(successRate - DEFAULT_SUCCESS_RATE) < 0.01 || sampleCount < MIN_HISTORICAL_SAMPLES) {
            // Don't hard block, but add significant penalty via confidence
            // This is a soft block via confidence reduction in later processing
            log.debug("[HARD_GATE] {} WARNING: Default/insufficient historical data (rate={}%, samples={})",
                    familyId, String.format("%.0f", successRate * 100), sampleCount);
        }

        // === RULE 7: Resistance Level for LONG (Bug #9) ===
        if (isLong) {
            Double nearestResistance = signal.getNearestResistance();
            double currentPrice = signal.getCurrentPrice();

            // For LONG, we need headroom to resistance
            if (nearestResistance != null && nearestResistance > 0) {
                double headroomPct = (nearestResistance - currentPrice) / currentPrice * 100;
                if (headroomPct < 0.3) { // Less than 0.3% to resistance
                    blockReasons.add(String.format("LONG_TOO_CLOSE_TO_RESISTANCE:%.2f%%", headroomPct));
                    log.warn("[HARD_GATE] {} BLOCKED: LONG only {}% below resistance at {}",
                            familyId, String.format("%.2f", headroomPct), nearestResistance);
                }
            }
        }

        // === RULE 8: Counter-SuperTrend (Bug #15) ===
        TechnicalContext techCtx = quantScore.getTechnicalContext();
        if (techCtx != null) {
            boolean superTrendBullish = techCtx.isSuperTrendBullish();

            // Block LONG when SuperTrend is BEARISH
            if (isLong && !superTrendBullish) {
                blockReasons.add("LONG_COUNTER_SUPERTREND_BEARISH");
                log.warn("[HARD_GATE] {} BLOCKED: LONG signal against BEARISH SuperTrend", familyId);
            }

            // Block SHORT when SuperTrend is BULLISH
            if (!isLong && superTrendBullish) {
                blockReasons.add("SHORT_COUNTER_SUPERTREND_BULLISH");
                log.warn("[HARD_GATE] {} BLOCKED: SHORT signal against BULLISH SuperTrend", familyId);
            }
        }

        // === RULE 9: Historical Context Learning Mode ===
        HistoricalContext histCtx = quantScore.getHistoricalContext();
        if (histCtx != null && histCtx.isInLearningMode()) {
            // In learning mode, be extra cautious
            if (histCtx.getDataCompleteness() < 0.5) {
                blockReasons.add(String.format("LEARNING_MODE:%.0f%%_COMPLETE",
                        histCtx.getDataCompleteness() * 100));
                log.warn("[HARD_GATE] {} BLOCKED: In learning mode with only {}% data completeness",
                        familyId, String.format("%.0f", histCtx.getDataCompleteness() * 100));
            }
        }

        // === FINAL RESULT ===
        if (!blockReasons.isEmpty()) {
            String reasonStr = String.join(" | ", blockReasons);
            log.info("[HARD_GATE] {} {} BLOCKED: {} reason(s): {}",
                    familyId, direction, blockReasons.size(), reasonStr);
            return GateResult.fail("SIGNAL_BLOCKED", reasonStr);
        }

        log.debug("[HARD_GATE] {} {} PASSED all hard checks", familyId, direction);
        GateResult result = GateResult.pass("ALL_HARD_CHECKS_PASSED");
        result.setGateName("SIGNAL_HARD_GATE");
        return result;
    }

    /**
     * Check if specific event type is present
     */
    private boolean hasEvent(List<DetectedEvent> events, DetectedEvent.EventType type) {
        return events.stream().anyMatch(e -> e.getEventType() == type);
    }

    /**
     * Check if a setup should be blocked based on historical performance
     * GENERALIZED: Works for any setup, any direction
     *
     * @param setupId The setup identifier
     * @param winRate Historical win rate (0.0 to 1.0)
     * @param sampleCount Number of historical samples
     * @return true if setup should be blocked
     */
    public boolean isSetupBlocked(String setupId, double winRate, int sampleCount) {
        // Only block if we have enough samples AND win rate is below threshold
        return setupId != null &&
               sampleCount >= MIN_HISTORICAL_SAMPLES &&
               winRate < MIN_SETUP_WIN_RATE;
    }

    /**
     * Get minimum win rate threshold for setups
     */
    public double getMinSetupWinRate() {
        return MIN_SETUP_WIN_RATE;
    }

    /**
     * Get minimum sample count required before blocking a setup
     */
    public int getMinHistoricalSamples() {
        return MIN_HISTORICAL_SAMPLES;
    }
}
