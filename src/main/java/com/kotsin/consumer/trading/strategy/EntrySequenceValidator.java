package com.kotsin.consumer.trading.strategy;

import com.kotsin.consumer.trading.gate.FlowAlignmentGate.FlowGateResult;
import com.kotsin.consumer.trading.mtf.HierarchicalMtfAnalyzer.HierarchicalContext;
import com.kotsin.consumer.trading.mtf.SwingRangeCalculator.ZonePosition;
import com.kotsin.consumer.trading.smc.SmcContext.MarketBias;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * EntrySequenceValidator - Enforces proper SMC entry sequence.
 *
 * THE PROBLEM WITH OLD APPROACH:
 * - Old code: if (hasLiquiditySweep || hasZoneRejection || hasPivotRetest) → VALID
 * - This is WRONG because it accepts ANY single trigger
 * - Real SMC: You need the FULL SEQUENCE, not just one condition
 *
 * THE FIX - ENFORCED SEQUENCE:
 * For LONG setup, you need ALL of:
 * 1. HTF_BIAS:        HTF structure is BULLISH (context)
 * 2. ZONE_LOCATION:   Price is in discount or at HTF demand (location)
 * 3. FLOW_ALIGNED:    F&O flow supports direction (not SHORT_BUILDUP)
 * 4. LIQUIDITY_SWEEP: LTF swept sell-side liquidity (manipulation)
 * 5. STRUCTURE_BREAK: LTF CHoCH/BOS confirms bullish (confirmation)
 *
 * For SHORT setup, you need ALL of:
 * 1. HTF_BIAS:        HTF structure is BEARISH
 * 2. ZONE_LOCATION:   Price is in premium or at HTF supply
 * 3. FLOW_ALIGNED:    F&O flow supports direction (not LONG_BUILDUP)
 * 4. LIQUIDITY_SWEEP: LTF swept buy-side liquidity
 * 5. STRUCTURE_BREAK: LTF CHoCH/BOS confirms bearish
 *
 * KEY INSIGHT:
 * - HTF_BIAS and ZONE_LOCATION are NON-NEGOTIABLE (must have both)
 * - FLOW_ALIGNED is NON-NEGOTIABLE if flow data exists
 * - LIQUIDITY_SWEEP and STRUCTURE_BREAK are ideal but can be relaxed for B/C tier
 */
@Slf4j
@Service
public class EntrySequenceValidator {

    /**
     * Steps in the entry sequence.
     */
    public enum SequenceStep {
        HTF_BIAS("HTF bias aligned with trade direction"),
        ZONE_LOCATION("Price in appropriate zone (discount for LONG, premium for SHORT)"),
        FLOW_ALIGNED("F&O flow confirms direction"),
        LIQUIDITY_SWEEP("LTF liquidity swept in direction"),
        STRUCTURE_BREAK("LTF CHoCH/BOS confirms direction");

        private final String description;

        SequenceStep(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Validate LONG entry sequence.
     *
     * @param ctx        Hierarchical MTF context
     * @param flowResult Flow alignment gate result
     * @return SequenceValidation with completed/missing steps
     */
    public SequenceValidation validateLongSequence(HierarchicalContext ctx, FlowGateResult flowResult) {
        List<SequenceStep> completed = new ArrayList<>();
        List<SequenceStep> missing = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        // Step 1: HTF Bias (NON-NEGOTIABLE)
        if (ctx.getHtfBias() == MarketBias.BULLISH) {
            completed.add(SequenceStep.HTF_BIAS);
            reasons.add("HTF bias is BULLISH");
        } else {
            missing.add(SequenceStep.HTF_BIAS);
            reasons.add("HTF bias is " + ctx.getHtfBias() + " (need BULLISH for LONG)");
        }

        // Step 2: Zone Location (NON-NEGOTIABLE)
        ZonePosition zone = ctx.getZonePosition();
        if (zone == ZonePosition.DISCOUNT || zone == ZonePosition.BELOW_RANGE) {
            completed.add(SequenceStep.ZONE_LOCATION);
            reasons.add("Price in DISCOUNT zone (ideal for LONG)");
        } else if (zone == ZonePosition.EQUILIBRIUM) {
            completed.add(SequenceStep.ZONE_LOCATION);
            reasons.add("Price in EQUILIBRIUM zone (acceptable for LONG)");
        } else if (ctx.isAtHtfDemand()) {
            completed.add(SequenceStep.ZONE_LOCATION);
            reasons.add("Price at HTF demand zone (valid LONG location)");
        } else {
            missing.add(SequenceStep.ZONE_LOCATION);
            reasons.add("Price in " + zone + " zone (LONG should be in discount/equilibrium)");
        }

        // Step 3: Flow Alignment (NON-NEGOTIABLE if flow data exists)
        if (flowResult.passed()) {
            completed.add(SequenceStep.FLOW_ALIGNED);
            if (flowResult.flowAligned()) {
                reasons.add("Flow confirms LONG: " + flowResult.reason());
            } else if (!flowResult.hasFlowData()) {
                reasons.add("No flow data (proceeding without flow confirmation)");
            } else {
                reasons.add("Flow neutral: " + flowResult.reason());
            }
        } else {
            missing.add(SequenceStep.FLOW_ALIGNED);
            reasons.add("Flow BLOCKS LONG: " + flowResult.reason());
        }

        // Step 4: Liquidity Sweep (ideal but can be relaxed)
        if (ctx.isLtfSweepDetected() && ctx.getLtfLastSweep() != null && !ctx.getLtfLastSweep().isBuySide()) {
            completed.add(SequenceStep.LIQUIDITY_SWEEP);
            reasons.add("LTF sell-side liquidity swept (stops taken below)");
        } else if (ctx.isLtfSweepDetected()) {
            // Wrong side sweep
            missing.add(SequenceStep.LIQUIDITY_SWEEP);
            reasons.add("LTF sweep detected but on buy-side (need sell-side for LONG)");
        } else {
            missing.add(SequenceStep.LIQUIDITY_SWEEP);
            reasons.add("No LTF liquidity sweep detected");
        }

        // Step 5: Structure Break (ideal but can be relaxed)
        if (ctx.isLtfRecentChoch() && ctx.isLtfChochBullish()) {
            completed.add(SequenceStep.STRUCTURE_BREAK);
            reasons.add("LTF bullish CHoCH confirmed (reversal signal)");
        } else if (ctx.isLtfRecentBos() && ctx.isLtfBosBullish()) {
            completed.add(SequenceStep.STRUCTURE_BREAK);
            reasons.add("LTF bullish BOS confirmed (continuation signal)");
        } else if (ctx.isLtfStructureBullish()) {
            // Structure is bullish but no recent break
            completed.add(SequenceStep.STRUCTURE_BREAK);
            reasons.add("LTF structure bullish (no recent break but aligned)");
        } else {
            missing.add(SequenceStep.STRUCTURE_BREAK);
            if (ctx.isLtfRecentChoch() || ctx.isLtfRecentBos()) {
                reasons.add("LTF structure break is bearish (need bullish for LONG)");
            } else {
                reasons.add("No LTF structure break detected");
            }
        }

        return buildValidation(completed, missing, reasons, true);
    }

    /**
     * Validate SHORT entry sequence.
     *
     * @param ctx        Hierarchical MTF context
     * @param flowResult Flow alignment gate result
     * @return SequenceValidation with completed/missing steps
     */
    public SequenceValidation validateShortSequence(HierarchicalContext ctx, FlowGateResult flowResult) {
        List<SequenceStep> completed = new ArrayList<>();
        List<SequenceStep> missing = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        // Step 1: HTF Bias (NON-NEGOTIABLE)
        if (ctx.getHtfBias() == MarketBias.BEARISH) {
            completed.add(SequenceStep.HTF_BIAS);
            reasons.add("HTF bias is BEARISH");
        } else {
            missing.add(SequenceStep.HTF_BIAS);
            reasons.add("HTF bias is " + ctx.getHtfBias() + " (need BEARISH for SHORT)");
        }

        // Step 2: Zone Location (NON-NEGOTIABLE)
        ZonePosition zone = ctx.getZonePosition();
        if (zone == ZonePosition.PREMIUM || zone == ZonePosition.ABOVE_RANGE) {
            completed.add(SequenceStep.ZONE_LOCATION);
            reasons.add("Price in PREMIUM zone (ideal for SHORT)");
        } else if (zone == ZonePosition.EQUILIBRIUM) {
            completed.add(SequenceStep.ZONE_LOCATION);
            reasons.add("Price in EQUILIBRIUM zone (acceptable for SHORT)");
        } else if (ctx.isAtHtfSupply()) {
            completed.add(SequenceStep.ZONE_LOCATION);
            reasons.add("Price at HTF supply zone (valid SHORT location)");
        } else {
            missing.add(SequenceStep.ZONE_LOCATION);
            reasons.add("Price in " + zone + " zone (SHORT should be in premium/equilibrium)");
        }

        // Step 3: Flow Alignment (NON-NEGOTIABLE if flow data exists)
        if (flowResult.passed()) {
            completed.add(SequenceStep.FLOW_ALIGNED);
            if (flowResult.flowAligned()) {
                reasons.add("Flow confirms SHORT: " + flowResult.reason());
            } else if (!flowResult.hasFlowData()) {
                reasons.add("No flow data (proceeding without flow confirmation)");
            } else {
                reasons.add("Flow neutral: " + flowResult.reason());
            }
        } else {
            missing.add(SequenceStep.FLOW_ALIGNED);
            reasons.add("Flow BLOCKS SHORT: " + flowResult.reason());
        }

        // Step 4: Liquidity Sweep (ideal but can be relaxed)
        if (ctx.isLtfSweepDetected() && ctx.getLtfLastSweep() != null && ctx.getLtfLastSweep().isBuySide()) {
            completed.add(SequenceStep.LIQUIDITY_SWEEP);
            reasons.add("LTF buy-side liquidity swept (stops taken above)");
        } else if (ctx.isLtfSweepDetected()) {
            // Wrong side sweep
            missing.add(SequenceStep.LIQUIDITY_SWEEP);
            reasons.add("LTF sweep detected but on sell-side (need buy-side for SHORT)");
        } else {
            missing.add(SequenceStep.LIQUIDITY_SWEEP);
            reasons.add("No LTF liquidity sweep detected");
        }

        // Step 5: Structure Break (ideal but can be relaxed)
        if (ctx.isLtfRecentChoch() && !ctx.isLtfChochBullish()) {
            completed.add(SequenceStep.STRUCTURE_BREAK);
            reasons.add("LTF bearish CHoCH confirmed (reversal signal)");
        } else if (ctx.isLtfRecentBos() && !ctx.isLtfBosBullish()) {
            completed.add(SequenceStep.STRUCTURE_BREAK);
            reasons.add("LTF bearish BOS confirmed (continuation signal)");
        } else if (!ctx.isLtfStructureBullish()) {
            // Structure is bearish but no recent break
            completed.add(SequenceStep.STRUCTURE_BREAK);
            reasons.add("LTF structure bearish (no recent break but aligned)");
        } else {
            missing.add(SequenceStep.STRUCTURE_BREAK);
            if (ctx.isLtfRecentChoch() || ctx.isLtfRecentBos()) {
                reasons.add("LTF structure break is bullish (need bearish for SHORT)");
            } else {
                reasons.add("No LTF structure break detected");
            }
        }

        return buildValidation(completed, missing, reasons, false);
    }

    /**
     * Build the validation result.
     */
    private SequenceValidation buildValidation(List<SequenceStep> completed, List<SequenceStep> missing,
                                                List<String> reasons, boolean isLong) {
        int completedCount = completed.size();
        int totalSteps = SequenceStep.values().length;

        // Check for non-negotiable steps
        boolean hasHtfBias = completed.contains(SequenceStep.HTF_BIAS);
        boolean hasZoneLocation = completed.contains(SequenceStep.ZONE_LOCATION);
        boolean hasFlowAligned = completed.contains(SequenceStep.FLOW_ALIGNED);

        // Core requirements (HTF + Zone + Flow if data exists)
        boolean coreRequirementsMet = hasHtfBias && hasZoneLocation && hasFlowAligned;

        // Full sequence requires all steps
        boolean fullSequenceMet = missing.isEmpty();

        SequenceValidation validation = new SequenceValidation(
                completedCount,
                totalSteps,
                completed,
                missing,
                reasons,
                coreRequirementsMet,
                fullSequenceMet,
                isLong
        );

        // Log the validation result
        String direction = isLong ? "LONG" : "SHORT";
        if (fullSequenceMet) {
            log.info("[ENTRY_SEQ] {} PERFECT {}/{}  | All conditions met for {} entry",
                    direction, completedCount, totalSteps, direction);
        } else if (coreRequirementsMet) {
            log.info("[ENTRY_SEQ] {} VALID {}/{} | Missing: {} | Core requirements met",
                    direction, completedCount, totalSteps, validation.getMissingStepsString());
        } else {
            log.info("[ENTRY_SEQ] {} INVALID {}/{} | Missing CRITICAL: {} | Core requirements NOT met",
                    direction, completedCount, totalSteps, validation.getMissingStepsString());
        }

        // Log individual reasons at debug level
        for (String reason : reasons) {
            log.debug("[ENTRY_SEQ] {} - {}", direction, reason);
        }

        return validation;
    }

    // ============ MODELS ============

    /**
     * Result of sequence validation.
     */
    public record SequenceValidation(
            int completedSteps,
            int totalSteps,
            List<SequenceStep> completed,
            List<SequenceStep> missing,
            List<String> reasons,
            boolean coreRequirementsMet,
            boolean fullSequenceMet,
            boolean isLong
    ) {
        /**
         * Is this sequence valid for trading?
         * At minimum, core requirements must be met.
         */
        public boolean isValid() {
            return coreRequirementsMet;
        }

        /**
         * Is this a perfect A+ setup?
         */
        public boolean isPerfect() {
            return fullSequenceMet;
        }

        /**
         * Check if a specific step is completed.
         */
        public boolean hasStep(SequenceStep step) {
            return completed.contains(step);
        }

        /**
         * Get completion percentage.
         */
        public double getCompletionPercent() {
            return (double) completedSteps / totalSteps * 100;
        }

        /**
         * Get missing steps as comma-separated string.
         */
        public String getMissingStepsString() {
            if (missing.isEmpty()) return "NONE";
            return String.join(", ", missing.stream().map(Enum::name).toList());
        }

        /**
         * Get reasons as newline-separated string.
         */
        public String getReasonsString() {
            return String.join("\n", reasons);
        }

        /**
         * Get direction string.
         */
        public String getDirection() {
            return isLong ? "LONG" : "SHORT";
        }
    }
}
