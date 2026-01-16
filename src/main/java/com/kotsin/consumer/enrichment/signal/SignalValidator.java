package com.kotsin.consumer.enrichment.signal;

import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SignalValidator - Validates trading signals before publishing
 *
 * Validation checks:
 * 1. Required fields present
 * 2. Trade parameters valid (entry, stop, targets)
 * 3. Risk/reward acceptable
 * 4. Confidence thresholds met
 * 5. Signal not expired
 * 6. Signal not duplicate
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalValidator {

    // Configuration thresholds
    private static final double MIN_CONFIDENCE = 0.50;
    private static final double MIN_RISK_REWARD = 1.0;
    private static final double MAX_RISK_REWARD = 20.0;
    private static final double MAX_RISK_PCT = 3.0;
    private static final int MIN_QUALITY_SCORE = 30;

    // FIX: Staleness threshold - signals older than this are rejected
    // Prevents publishing outdated signals from replayed/backfilled data
    private static final Duration MAX_SIGNAL_AGE = Duration.ofMinutes(5);

    // ======================== MAIN VALIDATION ========================

    /**
     * Validate a trading signal
     *
     * @param signal Signal to validate
     * @return Validation result
     */
    public ValidationResult validate(TradingSignal signal) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Required fields
        validateRequiredFields(signal, errors);

        // Trade parameters
        validateTradeParameters(signal, errors, warnings);

        // Risk metrics
        validateRiskMetrics(signal, errors, warnings);

        // Confidence
        validateConfidence(signal, errors, warnings);

        // Timing
        validateTiming(signal, errors, warnings);

        // Completeness
        validateCompleteness(signal, warnings);

        boolean isValid = errors.isEmpty();
        boolean hasWarnings = !warnings.isEmpty();

        ValidationResult result = ValidationResult.builder()
                .valid(isValid)
                .hasWarnings(hasWarnings)
                .errors(errors)
                .warnings(warnings)
                .qualityScore(signal.getQualityScore())
                .build();

        if (!isValid) {
            log.warn("[VALIDATOR] Signal {} failed validation: {}", signal.getSignalId(), errors);
        } else if (hasWarnings) {
            log.debug("[VALIDATOR] Signal {} valid with warnings: {}", signal.getSignalId(), warnings);
        }

        return result;
    }

    /**
     * Check if signal is publishable (valid, meets quality threshold, and not stale)
     * FIX: Added staleness check to prevent publishing outdated signals
     */
    public boolean isPublishable(TradingSignal signal) {
        ValidationResult result = validate(signal);

        // FIX: Staleness check - reject signals older than MAX_SIGNAL_AGE
        if (isStale(signal)) {
            log.warn("[VALIDATOR] Signal {} rejected as stale - generated at {}",
                    signal.getSignalId(), signal.getGeneratedAt());
            return false;
        }

        return result.isValid() && signal.getQualityScore() >= MIN_QUALITY_SCORE;
    }

    /**
     * FIX: Check if signal is stale (generated too long ago)
     * This prevents publishing outdated signals from replayed/backfilled data
     */
    private boolean isStale(TradingSignal signal) {
        if (signal.getGeneratedAt() == null) {
            return false;  // Can't determine staleness without timestamp
        }

        Instant now = Instant.now();
        Instant generatedAt = signal.getGeneratedAt();
        Duration age = Duration.between(generatedAt, now);

        // Signal is stale if it was generated more than MAX_SIGNAL_AGE ago
        // Also check if signal is from the future (clock skew) - treat as not stale
        if (age.isNegative()) {
            log.debug("[VALIDATOR] Signal {} has future timestamp - possible clock skew", signal.getSignalId());
            return false;
        }

        return age.compareTo(MAX_SIGNAL_AGE) > 0;
    }

    /**
     * Validate a batch of signals
     */
    public BatchValidationResult validateBatch(List<TradingSignal> signals) {
        List<TradingSignal> validSignals = new ArrayList<>();
        List<TradingSignal> invalidSignals = new ArrayList<>();
        List<ValidationResult> results = new ArrayList<>();

        for (TradingSignal signal : signals) {
            ValidationResult result = validate(signal);
            results.add(result);

            if (result.isValid()) {
                validSignals.add(signal);
            } else {
                invalidSignals.add(signal);
            }
        }

        return BatchValidationResult.builder()
                .totalSignals(signals.size())
                .validSignals(validSignals)
                .invalidSignals(invalidSignals)
                .validCount(validSignals.size())
                .invalidCount(invalidSignals.size())
                .results(results)
                .build();
    }

    // ======================== VALIDATION CHECKS ========================

    private void validateRequiredFields(TradingSignal signal, List<String> errors) {
        if (signal.getSignalId() == null || signal.getSignalId().isEmpty()) {
            errors.add("Signal ID is required");
        }

        if (signal.getFamilyId() == null || signal.getFamilyId().isEmpty()) {
            errors.add("Family ID is required");
        }

        if (signal.getDirection() == null) {
            errors.add("Direction is required");
        }

        if (signal.getSource() == null) {
            errors.add("Signal source is required");
        }

        if (signal.getGeneratedAt() == null) {
            errors.add("Generation timestamp is required");
        }
    }

    private void validateTradeParameters(TradingSignal signal, List<String> errors, List<String> warnings) {
        double entry = signal.getEntryPrice();
        double stop = signal.getStopLoss();
        double target1 = signal.getTarget1();
        double target2 = signal.getTarget2();
        double current = signal.getCurrentPrice();

        // Entry price
        if (entry <= 0) {
            errors.add("Entry price must be positive");
        }

        // Stop loss
        if (stop <= 0) {
            errors.add("Stop loss must be positive");
        }

        // Targets
        if (target1 <= 0) {
            warnings.add("Target1 is not set");
        }

        if (target2 <= 0) {
            errors.add("Target2 (primary target) must be positive");
        }

        // Direction consistency
        if (signal.getDirection() != null && entry > 0 && stop > 0 && target2 > 0) {
            boolean isLong = signal.getDirection() == TradingSignal.Direction.LONG;

            if (isLong) {
                if (stop >= entry) {
                    errors.add("Long signal: stop must be below entry");
                }
                if (target2 <= entry) {
                    errors.add("Long signal: target2 must be above entry");
                }
                if (target1 > 0 && target1 <= entry) {
                    warnings.add("Long signal: target1 should be above entry");
                }
            } else {
                if (stop <= entry) {
                    errors.add("Short signal: stop must be above entry");
                }
                if (target2 >= entry) {
                    errors.add("Short signal: target2 must be below entry");
                }
                if (target1 > 0 && target1 >= entry) {
                    warnings.add("Short signal: target1 should be below entry");
                }
            }
        }

        // Entry vs current price
        if (entry > 0 && current > 0) {
            double deviation = Math.abs(entry - current) / current * 100;
            if (deviation > 2.0) {
                warnings.add(String.format("Entry price deviates %.1f%% from current price", deviation));
            }
        }
    }

    private void validateRiskMetrics(TradingSignal signal, List<String> errors, List<String> warnings) {
        double rr = signal.getRiskRewardRatio();
        double riskPct = signal.getRiskPct();

        // Risk/Reward ratio
        if (rr < MIN_RISK_REWARD) {
            errors.add(String.format("Risk/Reward ratio %.2f below minimum %.2f", rr, MIN_RISK_REWARD));
        } else if (rr < 1.5) {
            warnings.add(String.format("Risk/Reward ratio %.2f is marginal", rr));
        }

        if (rr > MAX_RISK_REWARD) {
            warnings.add(String.format("Risk/Reward ratio %.2f seems unrealistic", rr));
        }

        // Risk percentage
        if (riskPct > MAX_RISK_PCT) {
            errors.add(String.format("Risk percentage %.2f%% exceeds maximum %.2f%%", riskPct, MAX_RISK_PCT));
        } else if (riskPct > 2.0) {
            warnings.add(String.format("Risk percentage %.2f%% is elevated", riskPct));
        }

        if (riskPct <= 0) {
            warnings.add("Risk percentage calculation may be invalid");
        }

        // Position sizing
        double posSize = signal.getPositionSizeMultiplier();
        if (posSize <= 0 || posSize > 2.0) {
            warnings.add(String.format("Position size multiplier %.2f may need review", posSize));
        }
    }

    private void validateConfidence(TradingSignal signal, List<String> errors, List<String> warnings) {
        double confidence = signal.getConfidence();

        if (confidence < 0 || confidence > 1) {
            errors.add("Confidence must be between 0 and 1");
        } else if (confidence < MIN_CONFIDENCE) {
            errors.add(String.format("Confidence %.2f below minimum threshold %.2f", confidence, MIN_CONFIDENCE));
        } else if (confidence < 0.60) {
            warnings.add(String.format("Confidence %.2f is low", confidence));
        }

        // Historical success rate
        double histRate = signal.getHistoricalSuccessRate();
        if (histRate > 0 && histRate < 0.40) {
            warnings.add(String.format("Historical success rate %.1f%% is low", histRate * 100));
        }

        // Quality score
        int qualityScore = signal.getQualityScore();
        if (qualityScore < 25) {
            errors.add(String.format("Quality score %d is too low", qualityScore));
        } else if (qualityScore < 40) {
            warnings.add(String.format("Quality score %d is marginal", qualityScore));
        }
    }

    private void validateTiming(TradingSignal signal, List<String> errors, List<String> warnings) {
        // Expiration
        if (signal.hasExpired()) {
            errors.add("Signal has expired");
        }

        // Expiry not set
        if (signal.getExpiresAt() == null) {
            warnings.add("Signal has no expiry time");
        }

        // Urgency vs horizon
        if (signal.getUrgency() == TradingSignal.Urgency.IMMEDIATE &&
            signal.getHorizon() == TradingSignal.Horizon.POSITIONAL) {
            warnings.add("Immediate urgency with positional horizon may be inconsistent");
        }
    }

    private void validateCompleteness(TradingSignal signal, List<String> warnings) {
        // Narrative
        if (signal.getNarrative() == null || signal.getNarrative().isEmpty()) {
            warnings.add("Signal has no narrative");
        }

        if (signal.getHeadline() == null || signal.getHeadline().isEmpty()) {
            warnings.add("Signal has no headline");
        }

        // Entry reasons
        if (signal.getEntryReasons() == null || signal.getEntryReasons().isEmpty()) {
            warnings.add("No entry reasons provided");
        }

        // Invalidation
        if (signal.getInvalidationWatch() == null || signal.getInvalidationWatch().isEmpty()) {
            warnings.add("No invalidation conditions specified");
        }

        // Predictions
        if (signal.getPredictedEvents() == null || signal.getPredictedEvents().isEmpty()) {
            warnings.add("No predicted events specified");
        }

        // Sequence basis
        if ((signal.getPatternId() == null || signal.getPatternId().isEmpty()) &&
            (signal.getSetupId() == null || signal.getSetupId().isEmpty())) {
            warnings.add("Signal has no pattern or setup basis");
        }

        // Key levels
        if (signal.getKeyLevels() == null || signal.getKeyLevels().isEmpty()) {
            warnings.add("No key levels specified");
        }
    }

    // ======================== RESULT MODELS ========================

    @Data
    @Builder
    @AllArgsConstructor
    public static class ValidationResult {
        private boolean valid;
        private boolean hasWarnings;
        private List<String> errors;
        private List<String> warnings;
        private int qualityScore;

        public boolean isPublishable() {
            return valid && qualityScore >= MIN_QUALITY_SCORE;
        }

        @Override
        public String toString() {
            if (valid && !hasWarnings) {
                return String.format("VALID (quality: %d)", qualityScore);
            } else if (valid) {
                return String.format("VALID with %d warnings (quality: %d)", warnings.size(), qualityScore);
            } else {
                return String.format("INVALID: %d errors, %d warnings", errors.size(), warnings.size());
            }
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class BatchValidationResult {
        private int totalSignals;
        private List<TradingSignal> validSignals;
        private List<TradingSignal> invalidSignals;
        private int validCount;
        private int invalidCount;
        private List<ValidationResult> results;

        public double getValidRate() {
            return totalSignals > 0 ? (double) validCount / totalSignals : 0;
        }

        @Override
        public String toString() {
            return String.format("BatchValidation: %d/%d valid (%.1f%%)",
                    validCount, totalSignals, getValidRate() * 100);
        }
    }
}
