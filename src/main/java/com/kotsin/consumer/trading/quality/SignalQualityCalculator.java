package com.kotsin.consumer.trading.quality;

import com.kotsin.consumer.trading.gate.FlowAlignmentGate.FlowGateResult;
import com.kotsin.consumer.trading.mtf.HierarchicalMtfAnalyzer.HierarchicalContext;
import com.kotsin.consumer.trading.mtf.SwingRangeCalculator.SwingRange;
import com.kotsin.consumer.trading.strategy.EntrySequenceValidator.SequenceStep;
import com.kotsin.consumer.trading.strategy.EntrySequenceValidator.SequenceValidation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * SignalQualityCalculator - Replaces fake confidence with honest quality tiers.
 *
 * THE PROBLEM WITH OLD APPROACH:
 * - Old code: confidence = 0.75 + (htfAligned ? 0.05 : 0) + (flowGood ? 0.08 : 0) + ...
 * - This produces "95% confidence" with 0.0 historical backing
 * - It's arbitrary number manipulation, not statistical confidence
 * - Users see "95% confidence" and think it's reliable
 *
 * THE FIX - HONEST QUALITY TIERS:
 * - A+ (Perfect):    5/5 conditions met - all stars aligned
 * - A  (Strong):     4/5 conditions met - missing one non-critical condition
 * - B  (Decent):     3/5 conditions met - core requirements + one extra
 * - C  (Weak):       3/5 core met - just barely valid
 * - REJECT:          Missing critical conditions (HTF, Zone, or Flow blocked)
 *
 * NO MORE FAKE PERCENTAGES:
 * - We don't say "92% confidence"
 * - We say "A tier setup with HTF bullish, discount zone, sweep confirmed"
 * - Users can decide based on actual conditions, not arbitrary numbers
 */
@Slf4j
@Service
public class SignalQualityCalculator {

    /**
     * Quality tiers for signals.
     * NO CONFIDENCE PERCENTAGE - just honest tier descriptions.
     */
    public enum QualityTier {
        A_PLUS("Perfect setup - all conditions aligned", 5),
        A("Strong setup - missing one non-critical condition", 4),
        B("Decent setup - core requirements met with partial confirmation", 3),
        C("Marginal setup - core requirements barely met", 2),
        REJECT("Invalid setup - missing critical conditions", 0);

        private final String description;
        private final int minSteps;

        QualityTier(String description, int minSteps) {
            this.description = description;
            this.minSteps = minSteps;
        }

        public String getDescription() {
            return description;
        }

        public int getMinSteps() {
            return minSteps;
        }
    }

    /**
     * Calculate signal quality based on sequence validation.
     *
     * @param sequence   Entry sequence validation result
     * @param flowResult Flow alignment gate result
     * @param ctx        Hierarchical MTF context
     * @return SignalQuality with tier and reasoning
     */
    public SignalQuality calculate(SequenceValidation sequence, FlowGateResult flowResult,
                                    HierarchicalContext ctx) {
        List<String> reasoning = new ArrayList<>();

        // ========== REJECT CONDITIONS (Non-negotiable) ==========

        // 1. Flow blocked = REJECT
        if (flowResult.isBlocked()) {
            return new SignalQuality(
                    QualityTier.REJECT,
                    "Flow contradicts direction",
                    List.of("BLOCKED: " + flowResult.reason()),
                    sequence.completedSteps(),
                    sequence.totalSteps()
            );
        }

        // 2. HTF bias not aligned = REJECT
        if (!sequence.hasStep(SequenceStep.HTF_BIAS)) {
            return new SignalQuality(
                    QualityTier.REJECT,
                    "HTF bias not aligned - cannot trade against higher timeframe",
                    List.of("BLOCKED: " + sequence.reasons().stream()
                            .filter(r -> r.contains("HTF"))
                            .findFirst()
                            .orElse("HTF bias missing")),
                    sequence.completedSteps(),
                    sequence.totalSteps()
            );
        }

        // 3. Zone location wrong = REJECT
        if (!sequence.hasStep(SequenceStep.ZONE_LOCATION)) {
            String direction = sequence.isLong() ? "LONG" : "SHORT";
            String badZone = sequence.isLong() ? "premium" : "discount";
            return new SignalQuality(
                    QualityTier.REJECT,
                    direction + " signal in " + badZone + " zone - invalid location",
                    List.of("BLOCKED: " + sequence.reasons().stream()
                            .filter(r -> r.contains("zone") || r.contains("Zone"))
                            .findFirst()
                            .orElse("Zone location invalid")),
                    sequence.completedSteps(),
                    sequence.totalSteps()
            );
        }

        // ========== QUALITY TIER CALCULATION ==========

        // Core requirements are met at this point (HTF + Zone + Flow)
        int completedSteps = sequence.completedSteps();

        // Add reasoning for each completed step
        for (String reason : sequence.reasons()) {
            if (!reason.startsWith("No ") && !reason.contains("need ")) {
                reasoning.add(reason);
            }
        }

        // Determine tier based on completed steps
        QualityTier tier;
        String summary;

        if (completedSteps >= 5) {
            tier = QualityTier.A_PLUS;
            summary = "Perfect " + sequence.getDirection() + " setup - all conditions aligned";
        } else if (completedSteps == 4) {
            tier = QualityTier.A;
            String missingStep = sequence.getMissingStepsString();
            summary = "Strong " + sequence.getDirection() + " setup - missing: " + missingStep;
        } else if (completedSteps == 3 && sequence.coreRequirementsMet()) {
            tier = QualityTier.B;
            String missingSteps = sequence.getMissingStepsString();
            summary = "Decent " + sequence.getDirection() + " setup - missing: " + missingSteps;
        } else {
            tier = QualityTier.C;
            String missingSteps = sequence.getMissingStepsString();
            summary = "Marginal " + sequence.getDirection() + " setup - missing: " + missingSteps;
        }

        // Add context-specific bonuses/notes
        addContextNotes(reasoning, ctx, flowResult, sequence);

        SignalQuality quality = new SignalQuality(tier, summary, reasoning, completedSteps, sequence.totalSteps());

        // Log the quality assessment
        String direction = sequence.isLong() ? "LONG" : "SHORT";
        if (tier == QualityTier.REJECT) {
            log.info("[QUALITY] {} REJECTED | {} | reason={}",
                    direction, quality.getCompletionRatio(), summary);
        } else if (tier == QualityTier.A_PLUS || tier == QualityTier.A) {
            log.info("[QUALITY] {} tier={} | {} | {}",
                    direction, quality.getTierDisplay(), quality.getCompletionRatio(), summary);
        } else {
            log.debug("[QUALITY] {} tier={} | {} | {}",
                    direction, quality.getTierDisplay(), quality.getCompletionRatio(), summary);
        }

        return quality;
    }

    /**
     * Add context-specific notes to reasoning.
     */
    private void addContextNotes(List<String> reasoning, HierarchicalContext ctx,
                                  FlowGateResult flowResult, SequenceValidation sequence) {
        // Flow alignment bonus
        if (flowResult.flowAligned()) {
            reasoning.add("BONUS: Flow strongly confirms direction");
        }

        // HTF POI bonus
        if (ctx != null) {
            if (sequence.isLong() && ctx.isAtHtfDemand()) {
                reasoning.add("BONUS: At HTF demand zone");
            }
            if (!sequence.isLong() && ctx.isAtHtfSupply()) {
                reasoning.add("BONUS: At HTF supply zone");
            }

            // Bias alignment bonus
            if (ctx.isBiasAligned()) {
                reasoning.add("BONUS: HTF and LTF bias aligned");
            }

            // Zone position context
            if (ctx.getSwingRange() != null && ctx.getSwingRange().isValid()) {
                double rangePos = ctx.getRangePosition() * 100;
                reasoning.add(String.format("Range position: %.0f%% (%.2f to %.2f)",
                        rangePos,
                        ctx.getSwingRange().getDiscountBottom(),
                        ctx.getSwingRange().getPremiumTop()));
            }
        }
    }

    /**
     * Check if a signal should be published based on quality.
     *
     * @param quality The calculated signal quality
     * @param minTier Minimum tier required for publication (default: C)
     * @return true if signal should be published
     */
    public boolean shouldPublish(SignalQuality quality, QualityTier minTier) {
        if (quality.tier() == QualityTier.REJECT) {
            return false;
        }
        return quality.tier().ordinal() <= minTier.ordinal();  // Lower ordinal = better tier
    }

    /**
     * Check if signal should be published (default: B tier minimum).
     */
    public boolean shouldPublish(SignalQuality quality) {
        return shouldPublish(quality, QualityTier.B);
    }

    // ============ MODELS ============

    /**
     * Signal quality result.
     */
    public record SignalQuality(
            QualityTier tier,
            String summary,
            List<String> reasoning,
            int completedSteps,
            int totalSteps
    ) {
        /**
         * Is this a valid (publishable) signal?
         */
        public boolean isValid() {
            return tier != QualityTier.REJECT;
        }

        /**
         * Is this a high-quality signal (A+ or A)?
         */
        public boolean isHighQuality() {
            return tier == QualityTier.A_PLUS || tier == QualityTier.A;
        }

        /**
         * Get tier as display string.
         */
        public String getTierDisplay() {
            return switch (tier) {
                case A_PLUS -> "A+";
                case A -> "A";
                case B -> "B";
                case C -> "C";
                case REJECT -> "REJECT";
            };
        }

        /**
         * Get quality score (for backwards compatibility with old code).
         * This is NOT confidence - it's a 0-100 quality score.
         */
        public int getQualityScore() {
            return switch (tier) {
                case A_PLUS -> 95;
                case A -> 80;
                case B -> 65;
                case C -> 50;
                case REJECT -> 0;
            };
        }

        /**
         * Get reasoning as newline-separated string.
         */
        public String getReasoningString() {
            return String.join("\n", reasoning);
        }

        /**
         * Get completion ratio string.
         */
        public String getCompletionRatio() {
            return completedSteps + "/" + totalSteps;
        }
    }
}
