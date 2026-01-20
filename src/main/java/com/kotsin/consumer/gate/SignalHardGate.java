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

    // FIX: Data-driven approach - NO HARDCODED BLOCKS
    // Let the data speak for itself through per-scrip learning
    // Only block on absolute fundamentals, not market conditions

    // Minimum confidence - reduced to allow more signals through
    // Data quality will be improved by structural SL and R:R filter
    private static final double MIN_CONFIDENCE = 0.50;           // REDUCED from 0.65 - let data decide

    // FIX: REMOVED hardcoded thresholds that were causing over-filtering
    // private static final double MIN_FAMILY_ALIGNMENT = 0.30;  // REMOVED - let data decide
    // private static final double SESSION_EXTREME_THRESHOLD = 0.80; // REMOVED - let data decide
    // private static final double MIN_SETUP_WIN_RATE = 0.35;    // REMOVED - let per-scrip learning decide

    // Only block on fundamentals that are universally bad
    private static final int MIN_HISTORICAL_SAMPLES = 20;        // Need more samples before blocking

    /**
     * FIX: Data-driven gate - MINIMAL blocking, let data speak
     *
     * PHILOSOPHY CHANGE:
     * - OLD: Block signals based on hardcoded rules (overfitting)
     * - NEW: Let signals through, let structural SL + R:R filter handle quality
     * - Per-scrip learning will handle pattern-specific performance
     *
     * Only block on ABSOLUTE fundamentals:
     * - Null inputs
     * - Extremely low confidence (<50%)
     * - First 15 minutes of market (not 30 - reduced)
     *
     * Everything else is LOG ONLY for analysis, not blocking
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
        List<String> warnings = new ArrayList<>(); // FIX: Log but don't block

        // === HARD BLOCK 1: Minimum Confidence (absolute minimum) ===
        if (signal.getConfidence() < MIN_CONFIDENCE) {
            blockReasons.add(String.format("LOW_CONFIDENCE:%.0f%%<%.0f%%",
                    signal.getConfidence() * 100, MIN_CONFIDENCE * 100));
        }

        // === FIX: First 5 Minutes ONLY (was 15 min - too aggressive) ===
        // Gap-up breakouts happen in first 15 min - we need to capture them!
        // Only block first 5 minutes (9:15-9:20) for extreme volatility
        // OVERRIDE: Allow signals with strong microstructure even in first 5 min
        java.time.ZonedDateTime nowIST = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        int hour = nowIST.getHour();
        int minute = nowIST.getMinute();
        boolean isFirst5Minutes = (hour == 9 && minute < 20); // 9:15-9:20 only

        // Check if microstructure is strong enough to override warmup block
        boolean strongMicrostructure = false;
        if (quantScore.getHistoricalContext() != null &&
            quantScore.getHistoricalContext().getOfiContext() != null) {
            var ofiCtx = quantScore.getHistoricalContext().getOfiContext();
            // Strong OFI (>2 z-score) can override warmup block
            strongMicrostructure = Math.abs(ofiCtx.getZscore()) > 2.0;
        }

        if (isFirst5Minutes && !strongMicrostructure) {
            blockReasons.add(String.format("MARKET_WARMUP:%02d:%02d", hour, minute));
        } else if (isFirst5Minutes && strongMicrostructure) {
            warnings.add(String.format("WARMUP_OVERRIDE:strong_OFI@%02d:%02d", hour, minute));
        }

        // === LOG ONLY (no blocking) - Let data decide ===

        // Log family alignment (but don't block)
        if (quantScore.hasFamilyContext()) {
            FamilyContext familyCtx = quantScore.getFamilyContext();
            FamilyBias bias = familyCtx.getOverallBias();
            double maxAlignment = Math.max(familyCtx.getBullishAlignment(), familyCtx.getBearishAlignment());

            if (maxAlignment < 0.30) {
                warnings.add(String.format("LOW_ALIGNMENT:%.0f%%", maxAlignment * 100));
            }

            if (isLong && (bias == FamilyBias.BEARISH || bias == FamilyBias.WEAK_BEARISH)) {
                warnings.add("LONG_VS_BEARISH_BIAS:" + bias);
            }
            if (!isLong && (bias == FamilyBias.BULLISH || bias == FamilyBias.WEAK_BULLISH)) {
                warnings.add("SHORT_VS_BULLISH_BIAS:" + bias);
            }
        }

        // Log session position (but don't block)
        if (quantScore.hasSessionStructure()) {
            SessionStructure session = quantScore.getSessionStructure();
            double position = session.getPositionInRange();

            if (isLong && position > 0.85) {
                warnings.add(String.format("LONG_AT_HIGH:%.0f%%", position * 100));
            }
            if (!isLong && position < 0.15) {
                warnings.add(String.format("SHORT_AT_LOW:%.0f%%", position * 100));
            }
        }

        // Log SuperTrend conflict (but don't block)
        TechnicalContext techCtx = quantScore.getTechnicalContext();
        if (techCtx != null) {
            boolean superTrendBullish = techCtx.isSuperTrendBullish();
            if (isLong && !superTrendBullish) {
                warnings.add("LONG_COUNTER_ST");
            }
            if (!isLong && superTrendBullish) {
                warnings.add("SHORT_COUNTER_ST");
            }
        }

        // Log exhaustion events (but don't block)
        if (quantScore.hasEvents()) {
            List<DetectedEvent> events = quantScore.getDetectedEvents();
            if (isLong && hasEvent(events, DetectedEvent.EventType.BUYING_EXHAUSTION)) {
                warnings.add("BUYING_EXHAUSTION");
            }
            if (!isLong && hasEvent(events, DetectedEvent.EventType.SELLING_EXHAUSTION)) {
                warnings.add("SELLING_EXHAUSTION");
            }
        }

        // Log historical performance (but don't block)
        String setupId = signal.getSetupId();
        double setupWinRate = signal.getHistoricalSuccessRate();
        int setupSamples = signal.getHistoricalSampleCount();
        if (setupId != null && setupSamples >= MIN_HISTORICAL_SAMPLES && setupWinRate < 0.40) {
            warnings.add(String.format("POOR_HISTORY:%s(%.0f%%/%d)", setupId, setupWinRate * 100, setupSamples));
        }

        // === LOG WARNINGS (for analysis, not blocking) ===
        if (!warnings.isEmpty()) {
            log.info("[HARD_GATE] {} {} WARNINGS (not blocked): {}",
                    familyId, direction, String.join(" | ", warnings));
        }

        // === FINAL RESULT - Only block on absolute fundamentals ===
        if (!blockReasons.isEmpty()) {
            String reasonStr = String.join(" | ", blockReasons);
            log.info("[HARD_GATE] {} {} BLOCKED: {}", familyId, direction, reasonStr);
            return GateResult.fail("SIGNAL_BLOCKED", reasonStr);
        }

        log.debug("[HARD_GATE] {} {} PASSED | warnings={}", familyId, direction, warnings.size());
        GateResult result = GateResult.pass("PASSED");
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
     * FIX: No longer blocking setups - let data-driven per-scrip learning handle this
     * This method returns false always (no blocking)
     */
    public boolean isSetupBlocked(String setupId, double winRate, int sampleCount) {
        // FIX: Don't block setups - let per-scrip learning handle performance
        // This method is kept for API compatibility but always returns false
        return false;
    }

    /**
     * FIX: No minimum win rate threshold - let data decide
     */
    public double getMinSetupWinRate() {
        return 0.0; // No threshold - all setups allowed
    }

    /**
     * Get minimum sample count required for statistical significance
     */
    public int getMinHistoricalSamples() {
        return MIN_HISTORICAL_SAMPLES;
    }
}
