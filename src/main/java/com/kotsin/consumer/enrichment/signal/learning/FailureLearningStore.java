package com.kotsin.consumer.enrichment.signal.learning;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.signal.learning.FailureContext.FailureType;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * FailureLearningStore - Learns from SL hits to improve future signals
 *
 * PHILOSOPHY:
 * Every failure is a learning opportunity. By tracking WHAT conditions were
 * present at entry when trades fail, we can learn to AVOID similar setups.
 *
 * LEARNING LEVELS:
 * 1. Per-Scrip: Each scrip has different characteristics
 * 2. Per-Setup: Some setups work better for certain scrips
 * 3. Per-Condition: Certain conditions are dangerous for certain scrips
 * 4. Per-Time: Some scrips fail more at certain times
 *
 * AUTO-GENERATED RULES:
 * - "RELIANCE: Avoid LONG when OI=SHORT_BUILDUP (80% failure rate)"
 * - "BANKNIFTY: Avoid in first 30 minutes (75% failure rate)"
 * - "NIFTY: Avoid LONG at session position > 85% (70% failure rate)"
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FailureLearningStore {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // Per-scrip failure profiles
    private final Map<String, ScripFailureProfile> scripProfiles = new ConcurrentHashMap<>();

    // Recent failures (last 1000) for pattern analysis
    private final List<FailureContext> recentFailures = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_RECENT_FAILURES = 1000;

    // Statistics
    private final AtomicInteger totalFailuresRecorded = new AtomicInteger(0);
    private final AtomicInteger totalSuccessesRecorded = new AtomicInteger(0);
    private final AtomicInteger signalsBlockedByLearning = new AtomicInteger(0);

    @Value("${learning.enabled:true}")
    private boolean enabled;

    @Value("${learning.min-samples-for-rule:5}")
    private int minSamplesForRule;

    @Value("${learning.max-failure-rate-to-block:0.70}")
    private double maxFailureRateToBlock;

    @Value("${learning.scrip-specific:true}")
    private boolean scripSpecificLearning;

    // ======================== RECORD OUTCOMES ========================

    /**
     * Record a failure (SL hit) with full context
     */
    public void recordFailure(FailureContext failure) {
        if (!enabled || failure == null) return;

        int failureCount = totalFailuresRecorded.incrementAndGet();

        // Add to recent failures
        synchronized (recentFailures) {
            recentFailures.add(failure);
            if (recentFailures.size() > MAX_RECENT_FAILURES) {
                recentFailures.remove(0);
            }
        }

        // Update scrip profile
        String scripCode = failure.getScripCode();
        ScripFailureProfile profile = scripProfiles.computeIfAbsent(scripCode,
                k -> new ScripFailureProfile(scripCode));
        profile.recordFailure(failure);

        // Enhanced logging with full context
        log.info("[LEARNING] FAILURE #{} | {} {} {} | type={} | timeToSL={}min | MAE={}% MFE={}% | OI={} session={}% | tags={}",
                failureCount,
                failure.getScripCode(),
                failure.getDirection(),
                failure.getSetupType(),
                failure.getFailureType(),
                failure.getTimeToSLMinutes(),
                String.format("%.2f", failure.getMaxAdverseExcursionPct()),
                String.format("%.2f", failure.getMaxFavorableExcursionPct()),
                failure.getOiInterpretationAtEntry(),
                String.format("%.0f", failure.getSessionPositionAtEntry() * 100),
                failure.getLearningTags() != null ? String.join(",", failure.getLearningTags()) : "none");

        log.debug("[LEARNING] {} profile: {} total trades | {}% failure rate | top failure type={}",
                scripCode,
                profile.getTotalTrades(),
                String.format("%.1f", profile.getOverallFailureRate() * 100),
                profile.getMostCommonFailureType(failure.getDirection()));

        // Check if we should generate new rules
        if (profile.getTotalTrades() % 10 == 0) {
            generateRulesForScrip(scripCode, profile);
        }
    }

    /**
     * Record a success (target hit) to balance learning
     */
    public void recordSuccess(String scripCode, String direction, String setupType,
                               String oiInterpretation, double sessionPosition, int hour) {
        if (!enabled) return;

        int successCount = totalSuccessesRecorded.incrementAndGet();

        ScripFailureProfile profile = scripProfiles.computeIfAbsent(scripCode,
                k -> new ScripFailureProfile(scripCode));
        profile.recordSuccess(direction, setupType, oiInterpretation, sessionPosition, hour);

        // Enhanced logging
        log.info("[LEARNING] SUCCESS #{} | {} {} {} | OI={} session={}% hour={}",
                successCount, scripCode, direction, setupType,
                oiInterpretation, String.format("%.0f", sessionPosition * 100), hour);

        log.debug("[LEARNING] {} profile updated: {} trades | {}% success rate | setup {} success rate={}%",
                scripCode,
                profile.getTotalTrades(),
                String.format("%.1f", (1 - profile.getOverallFailureRate()) * 100),
                setupType,
                String.format("%.1f", (1 - profile.getSetupFailureRate(setupType)) * 100));
    }

    // ======================== AVOIDANCE LOGIC ========================

    /**
     * Check if a signal should be avoided based on learned patterns
     *
     * @param signal The trading signal to evaluate
     * @param candle Current market data
     * @param quantScore Technical analysis
     * @return List of violation reasons, empty if signal is OK
     */
    public List<String> checkAvoidanceRules(TradingSignal signal, FamilyCandle candle,
                                             EnrichedQuantScore quantScore) {
        List<String> violations = new ArrayList<>();

        if (!enabled || !scripSpecificLearning) {
            return violations;
        }

        String scripCode = signal.getScripCode();
        ScripFailureProfile profile = scripProfiles.get(scripCode);

        if (profile == null || profile.getTotalTrades() < minSamplesForRule) {
            return violations; // Not enough data to make rules
        }

        String direction = signal.getDirection().name();
        String setupType = signal.getSetupId();
        String oiInterpretation = candle != null ? candle.getOiInterpretation() : null;
        double sessionPosition = quantScore != null && quantScore.getSessionStructure() != null ?
                quantScore.getSessionStructure().getPositionInRange() : 0.5;
        int hour = LocalTime.now(IST).getHour();

        // Rule 1: Setup type has high failure rate for this scrip
        if (setupType != null) {
            double setupFailureRate = profile.getSetupFailureRate(setupType);
            int setupSamples = profile.getSetupSampleCount(setupType);
            if (setupSamples >= minSamplesForRule && setupFailureRate >= maxFailureRateToBlock) {
                violations.add(String.format("SETUP_HIGH_FAILURE:%s(%.0f%%/%d)",
                        setupType, setupFailureRate * 100, setupSamples));
            }
        }

        // Rule 2: OI interpretation conflicts with learned patterns
        if (oiInterpretation != null) {
            double oiFailureRate = profile.getOiFailureRate(direction, oiInterpretation);
            int oiSamples = profile.getOiSampleCount(direction, oiInterpretation);
            if (oiSamples >= minSamplesForRule && oiFailureRate >= maxFailureRateToBlock) {
                violations.add(String.format("OI_CONFLICT:%s_%s(%.0f%%/%d)",
                        direction, oiInterpretation, oiFailureRate * 100, oiSamples));
            }
        }

        // Rule 3: Session position is risky for this direction + scrip
        // FIX: Add override for gap-up scenarios during opening with strong microstructure
        String sessionBucket = getSessionBucket(sessionPosition);
        double sessionFailureRate = profile.getSessionFailureRate(direction, sessionBucket);
        int sessionSamples = profile.getSessionSampleCount(direction, sessionBucket);

        // Check for strong microstructure override
        boolean strongMicrostructure = false;
        if (quantScore != null && quantScore.getHistoricalContext() != null &&
            quantScore.getHistoricalContext().getOfiContext() != null) {
            double ofiZScore = quantScore.getHistoricalContext().getOfiContext().getZscore();
            boolean isLong = "LONG".equals(direction);
            // Strong microstructure = OFI z-score > 1.5 aligned with direction
            strongMicrostructure = (isLong && ofiZScore > 1.5) || (!isLong && ofiZScore < -1.5);
        }

        // Opening hour can have valid gap-up/gap-down trades at session extremes
        boolean isOpeningWithMomentum = "OPENING".equals(getHourBucket(hour)) && strongMicrostructure;

        if (sessionSamples >= minSamplesForRule && sessionFailureRate >= maxFailureRateToBlock
            && !isOpeningWithMomentum) {
            violations.add(String.format("SESSION_RISKY:%s_%s(%.0f%%/%d)",
                    direction, sessionBucket, sessionFailureRate * 100, sessionSamples));
        } else if (isOpeningWithMomentum && sessionFailureRate >= maxFailureRateToBlock) {
            log.debug("[LEARNING] {} {} {} session risk OVERRIDDEN by opening momentum | OFI strong",
                    scripCode, direction, sessionBucket);
        }

        // Rule 4: Hour is risky for this scrip + direction
        String hourBucket = getHourBucket(hour);
        double hourFailureRate = profile.getHourFailureRate(direction, hourBucket);
        int hourSamples = profile.getHourSampleCount(direction, hourBucket);
        if (hourSamples >= minSamplesForRule && hourFailureRate >= maxFailureRateToBlock) {
            violations.add(String.format("HOUR_RISKY:%s_%s(%.0f%%/%d)",
                    direction, hourBucket, hourFailureRate * 100, hourSamples));
        }

        // Rule 5: Failure type patterns
        FailureType mostCommonFailure = profile.getMostCommonFailureType(direction);
        if (mostCommonFailure != null) {
            // Check if current conditions match the common failure pattern
            boolean matchesPattern = checkFailurePatternMatch(mostCommonFailure, signal, candle, quantScore);
            if (matchesPattern) {
                violations.add(String.format("FAILURE_PATTERN:%s_%s",
                        direction, mostCommonFailure));
            }
        }

        if (!violations.isEmpty()) {
            signalsBlockedByLearning.incrementAndGet();
            log.warn("[LEARNING] {} {} blocked: {}", scripCode, direction, violations);
        }

        return violations;
    }

    /**
     * Simple check - should this signal be avoided?
     */
    public boolean shouldAvoidSignal(TradingSignal signal, FamilyCandle candle,
                                      EnrichedQuantScore quantScore) {
        return !checkAvoidanceRules(signal, candle, quantScore).isEmpty();
    }

    /**
     * Lightweight check for SignalGenerator - avoids needing to construct TradingSignal
     *
     * @param scripCode The scrip code
     * @param direction LONG or SHORT
     * @param setupId The setup ID
     * @param quantScore Technical analysis data
     * @return true if signal should be avoided based on learned patterns
     */
    public boolean shouldAvoidSetup(String scripCode, String direction, String setupId,
                                     EnrichedQuantScore quantScore) {
        if (!enabled || !scripSpecificLearning) {
            return false;
        }

        ScripFailureProfile profile = scripProfiles.get(scripCode);
        if (profile == null || profile.getTotalTrades() < minSamplesForRule) {
            return false; // Not enough data
        }

        double sessionPosition = quantScore != null && quantScore.getSessionStructure() != null ?
                quantScore.getSessionStructure().getPositionInRange() : 0.5;
        int hour = LocalTime.now(IST).getHour();

        List<String> violations = new ArrayList<>();

        // Rule 1: Setup type failure rate (setup key doesn't include direction)
        double setupFailureRate = profile.getSetupFailureRate(setupId);
        int setupSamples = profile.getSetupSampleCount(setupId);
        if (setupSamples >= minSamplesForRule && setupFailureRate > maxFailureRateToBlock) {
            violations.add(String.format("SETUP_RISKY:%s_%s(%.0f%%/%d)",
                    direction, setupId, setupFailureRate * 100, setupSamples));
        }

        // Rule 2: Session position risk (LONG at high, SHORT at low)
        // FIX: Add override for gap-up scenarios during opening with strong microstructure
        String sessionBucket = getSessionBucket(sessionPosition);
        double sessionFailureRate = profile.getSessionFailureRate(direction, sessionBucket);
        int sessionSamples = profile.getSessionSampleCount(direction, sessionBucket);

        // Check for strong microstructure override
        boolean strongMicrostructure = false;
        if (quantScore != null && quantScore.getHistoricalContext() != null &&
            quantScore.getHistoricalContext().getOfiContext() != null) {
            double ofiZScore = quantScore.getHistoricalContext().getOfiContext().getZscore();
            boolean isLong = "LONG".equals(direction);
            // Strong microstructure = OFI z-score > 1.5 aligned with direction
            strongMicrostructure = (isLong && ofiZScore > 1.5) || (!isLong && ofiZScore < -1.5);
        }

        // Opening hour can have valid gap-up/gap-down trades at session extremes
        boolean isOpeningWithMomentum = "OPENING".equals(getHourBucket(hour)) && strongMicrostructure;

        if (sessionSamples >= minSamplesForRule && sessionFailureRate > maxFailureRateToBlock
            && !isOpeningWithMomentum) {
            violations.add(String.format("SESSION_RISKY:%s_%s(%.0f%%/%d)",
                    direction, sessionBucket, sessionFailureRate * 100, sessionSamples));
        }

        // Rule 3: Hour risk
        String hourBucket = getHourBucket(hour);
        double hourFailureRate = profile.getHourFailureRate(direction, hourBucket);
        int hourSamples = profile.getHourSampleCount(direction, hourBucket);
        if (hourSamples >= minSamplesForRule && hourFailureRate > maxFailureRateToBlock) {
            violations.add(String.format("HOUR_RISKY:%s_%s(%.0f%%/%d)",
                    direction, hourBucket, hourFailureRate * 100, hourSamples));
        }

        if (!violations.isEmpty()) {
            signalsBlockedByLearning.incrementAndGet();
            log.warn("[LEARNING] {} {} {} blocked by setup check: {}", scripCode, direction, setupId, violations);
            return true;
        }

        return false;
    }

    // ======================== RULE GENERATION ========================

    /**
     * Generate learning rules for a scrip based on failure patterns
     */
    private void generateRulesForScrip(String scripCode, ScripFailureProfile profile) {
        List<String> rules = new ArrayList<>();

        // Analyze setup patterns
        for (Map.Entry<String, int[]> entry : profile.setupStats.entrySet()) {
            String setup = entry.getKey();
            int[] stats = entry.getValue(); // [failures, successes]
            int total = stats[0] + stats[1];
            if (total >= minSamplesForRule) {
                double failureRate = (double) stats[0] / total;
                if (failureRate >= maxFailureRateToBlock) {
                    rules.add(String.format("Avoid %s (%.0f%% failure, %d trades)",
                            setup, failureRate * 100, total));
                }
            }
        }

        // Analyze OI patterns
        for (Map.Entry<String, int[]> entry : profile.oiStats.entrySet()) {
            String oiKey = entry.getKey(); // e.g., "LONG_SHORT_BUILDUP"
            int[] stats = entry.getValue();
            int total = stats[0] + stats[1];
            if (total >= minSamplesForRule) {
                double failureRate = (double) stats[0] / total;
                if (failureRate >= maxFailureRateToBlock) {
                    rules.add(String.format("Avoid %s (%.0f%% failure, %d trades)",
                            oiKey.replace("_", " when OI="), failureRate * 100, total));
                }
            }
        }

        // Analyze session patterns
        for (Map.Entry<String, int[]> entry : profile.sessionStats.entrySet()) {
            String sessionKey = entry.getKey();
            int[] stats = entry.getValue();
            int total = stats[0] + stats[1];
            if (total >= minSamplesForRule) {
                double failureRate = (double) stats[0] / total;
                if (failureRate >= maxFailureRateToBlock) {
                    rules.add(String.format("Avoid %s (%.0f%% failure, %d trades)",
                            sessionKey.replace("_", " at "), failureRate * 100, total));
                }
            }
        }

        if (!rules.isEmpty()) {
            log.info("[LEARNING] {} Rules: {}", scripCode, rules);
        }
    }

    // ======================== HELPER METHODS ========================

    private String getSessionBucket(double position) {
        if (position < 0.15) return "SESSION_LOW";
        if (position < 0.35) return "LOWER_THIRD";
        if (position < 0.65) return "MIDDLE";
        if (position < 0.85) return "UPPER_THIRD";
        return "SESSION_HIGH";
    }

    private String getHourBucket(int hour) {
        if (hour < 10) return "OPENING";
        if (hour < 12) return "MORNING";
        if (hour < 14) return "MIDDAY";
        if (hour < 15) return "AFTERNOON";
        return "CLOSING";
    }

    private boolean checkFailurePatternMatch(FailureType failureType, TradingSignal signal,
                                              FamilyCandle candle, EnrichedQuantScore quantScore) {
        if (failureType == null) return false;

        switch (failureType) {
            case SESSION_TRAP:
                // Check if entering at session extreme
                // FIX: Add override for strong microstructure (valid gap-up/gap-down scenarios)
                if (quantScore != null && quantScore.getSessionStructure() != null) {
                    double pos = quantScore.getSessionStructure().getPositionInRange();
                    boolean isLong = signal.isLong();
                    boolean atExtreme = (isLong && pos > 0.85) || (!isLong && pos < 0.15);

                    if (!atExtreme) return false;

                    // Check for strong microstructure override
                    boolean strongMicrostructure = false;
                    if (quantScore.getHistoricalContext() != null &&
                        quantScore.getHistoricalContext().getOfiContext() != null) {
                        double ofiZScore = quantScore.getHistoricalContext().getOfiContext().getZscore();
                        // Strong microstructure = OFI z-score > 1.5 aligned with direction
                        strongMicrostructure = (isLong && ofiZScore > 1.5) || (!isLong && ofiZScore < -1.5);
                    }

                    // If strong microstructure supports direction, don't flag as trap
                    return !strongMicrostructure;
                }
                break;

            case OI_DIVERGENCE_IGNORED:
                // Check if OI contradicts direction
                if (candle != null) {
                    String oi = candle.getOiInterpretation();
                    boolean isLong = signal.isLong();
                    if (isLong && "SHORT_BUILDUP".equals(oi)) return true;
                    if (!isLong && "LONG_BUILDUP".equals(oi)) return true;
                }
                break;

            case COUNTER_TREND_FAILED:
                // Check if counter to SuperTrend
                if (quantScore != null && quantScore.getTechnicalContext() != null) {
                    boolean stBullish = quantScore.getTechnicalContext().isSuperTrendBullish();
                    boolean isLong = signal.isLong();
                    return (isLong && !stBullish) || (!isLong && stBullish);
                }
                break;

            case OPENING_VOLATILITY:
                // Check if in first 30 minutes
                int hour = LocalTime.now(IST).getHour();
                int minute = LocalTime.now(IST).getMinute();
                return hour == 9 && minute < 45;

            default:
                return false;
        }
        return false;
    }

    // ======================== STATISTICS ========================

    /**
     * Get profile for a scrip
     */
    public ScripFailureProfile getProfile(String scripCode) {
        return scripProfiles.get(scripCode);
    }

    /**
     * Get overall statistics
     */
    public String getStats() {
        int totalScripProfiles = scripProfiles.size();
        int scripWithRules = (int) scripProfiles.values().stream()
                .filter(p -> p.getTotalTrades() >= minSamplesForRule)
                .count();

        return String.format("[LEARNING] scrips=%d (with_rules=%d) | failures=%d | successes=%d | blocked=%d",
                totalScripProfiles, scripWithRules,
                totalFailuresRecorded.get(), totalSuccessesRecorded.get(),
                signalsBlockedByLearning.get());
    }

    /**
     * Periodic logging of learning stats
     */
    @Scheduled(fixedDelayString = "${learning.stats.interval.ms:300000}")
    public void logStats() {
        if (enabled && totalFailuresRecorded.get() > 0) {
            log.info(getStats());

            // Log top failing scrips
            List<ScripFailureProfile> topFailures = scripProfiles.values().stream()
                    .filter(p -> p.getTotalTrades() >= minSamplesForRule)
                    .sorted(Comparator.comparingDouble(ScripFailureProfile::getOverallFailureRate).reversed())
                    .limit(5)
                    .collect(Collectors.toList());

            if (!topFailures.isEmpty()) {
                log.info("[LEARNING] Top failing scrips: {}",
                        topFailures.stream()
                                .map(p -> String.format("%s(%.0f%%/%d)",
                                        p.getScripCode(), p.getOverallFailureRate() * 100, p.getTotalTrades()))
                                .collect(Collectors.joining(", ")));
            }
        }
    }

    // ======================== SCRIP FAILURE PROFILE ========================

    @Data
    public static class ScripFailureProfile {
        private final String scripCode;
        private int totalFailures = 0;
        private int totalSuccesses = 0;

        // Setup -> [failures, successes]
        private final Map<String, int[]> setupStats = new ConcurrentHashMap<>();

        // Direction_OIType -> [failures, successes]
        private final Map<String, int[]> oiStats = new ConcurrentHashMap<>();

        // Direction_SessionBucket -> [failures, successes]
        private final Map<String, int[]> sessionStats = new ConcurrentHashMap<>();

        // Direction_HourBucket -> [failures, successes]
        private final Map<String, int[]> hourStats = new ConcurrentHashMap<>();

        // FailureType counts for pattern analysis
        private final Map<FailureType, Integer> failureTypeCounts = new ConcurrentHashMap<>();

        // Per-direction failure type tracking
        private final Map<String, Map<FailureType, Integer>> directionFailureTypes = new ConcurrentHashMap<>();

        public ScripFailureProfile(String scripCode) {
            this.scripCode = scripCode;
        }

        public void recordFailure(FailureContext failure) {
            totalFailures++;

            // Setup stats
            if (failure.getSetupType() != null) {
                setupStats.computeIfAbsent(failure.getSetupType(), k -> new int[2])[0]++;
            }

            // OI stats
            if (failure.getOiInterpretationAtEntry() != null) {
                String oiKey = failure.getDirection() + "_" + failure.getOiInterpretationAtEntry();
                oiStats.computeIfAbsent(oiKey, k -> new int[2])[0]++;
            }

            // Session stats
            String sessionBucket = getSessionBucket(failure.getSessionPositionAtEntry());
            String sessionKey = failure.getDirection() + "_" + sessionBucket;
            sessionStats.computeIfAbsent(sessionKey, k -> new int[2])[0]++;

            // Hour stats
            int hour = failure.getEntryTime() != null ?
                    failure.getEntryTime().atZone(ZoneId.of("Asia/Kolkata")).getHour() : 12;
            String hourBucket = getHourBucket(hour);
            String hourKey = failure.getDirection() + "_" + hourBucket;
            hourStats.computeIfAbsent(hourKey, k -> new int[2])[0]++;

            // Failure type
            if (failure.getFailureType() != null) {
                failureTypeCounts.merge(failure.getFailureType(), 1, Integer::sum);

                // Per-direction failure type
                directionFailureTypes
                        .computeIfAbsent(failure.getDirection(), k -> new ConcurrentHashMap<>())
                        .merge(failure.getFailureType(), 1, Integer::sum);
            }
        }

        public void recordSuccess(String direction, String setupType, String oiInterpretation,
                                   double sessionPosition, int hour) {
            totalSuccesses++;

            if (setupType != null) {
                setupStats.computeIfAbsent(setupType, k -> new int[2])[1]++;
            }

            if (oiInterpretation != null) {
                String oiKey = direction + "_" + oiInterpretation;
                oiStats.computeIfAbsent(oiKey, k -> new int[2])[1]++;
            }

            String sessionBucket = getSessionBucket(sessionPosition);
            String sessionKey = direction + "_" + sessionBucket;
            sessionStats.computeIfAbsent(sessionKey, k -> new int[2])[1]++;

            String hourBucket = getHourBucket(hour);
            String hourKey = direction + "_" + hourBucket;
            hourStats.computeIfAbsent(hourKey, k -> new int[2])[1]++;
        }

        private static String getSessionBucket(double position) {
            if (position < 0.15) return "SESSION_LOW";
            if (position < 0.35) return "LOWER_THIRD";
            if (position < 0.65) return "MIDDLE";
            if (position < 0.85) return "UPPER_THIRD";
            return "SESSION_HIGH";
        }

        private static String getHourBucket(int hour) {
            if (hour < 10) return "OPENING";
            if (hour < 12) return "MORNING";
            if (hour < 14) return "MIDDAY";
            if (hour < 15) return "AFTERNOON";
            return "CLOSING";
        }

        public int getTotalTrades() {
            return totalFailures + totalSuccesses;
        }

        public double getOverallFailureRate() {
            int total = getTotalTrades();
            return total > 0 ? (double) totalFailures / total : 0;
        }

        public double getSetupFailureRate(String setup) {
            int[] stats = setupStats.get(setup);
            if (stats == null) return 0;
            int total = stats[0] + stats[1];
            return total > 0 ? (double) stats[0] / total : 0;
        }

        public int getSetupSampleCount(String setup) {
            int[] stats = setupStats.get(setup);
            return stats != null ? stats[0] + stats[1] : 0;
        }

        public double getOiFailureRate(String direction, String oiType) {
            String key = direction + "_" + oiType;
            int[] stats = oiStats.get(key);
            if (stats == null) return 0;
            int total = stats[0] + stats[1];
            return total > 0 ? (double) stats[0] / total : 0;
        }

        public int getOiSampleCount(String direction, String oiType) {
            String key = direction + "_" + oiType;
            int[] stats = oiStats.get(key);
            return stats != null ? stats[0] + stats[1] : 0;
        }

        public double getSessionFailureRate(String direction, String sessionBucket) {
            String key = direction + "_" + sessionBucket;
            int[] stats = sessionStats.get(key);
            if (stats == null) return 0;
            int total = stats[0] + stats[1];
            return total > 0 ? (double) stats[0] / total : 0;
        }

        public int getSessionSampleCount(String direction, String sessionBucket) {
            String key = direction + "_" + sessionBucket;
            int[] stats = sessionStats.get(key);
            return stats != null ? stats[0] + stats[1] : 0;
        }

        public double getHourFailureRate(String direction, String hourBucket) {
            String key = direction + "_" + hourBucket;
            int[] stats = hourStats.get(key);
            if (stats == null) return 0;
            int total = stats[0] + stats[1];
            return total > 0 ? (double) stats[0] / total : 0;
        }

        public int getHourSampleCount(String direction, String hourBucket) {
            String key = direction + "_" + hourBucket;
            int[] stats = hourStats.get(key);
            return stats != null ? stats[0] + stats[1] : 0;
        }

        public FailureType getMostCommonFailureType(String direction) {
            Map<FailureType, Integer> dirTypes = directionFailureTypes.get(direction);
            if (dirTypes == null || dirTypes.isEmpty()) return null;

            return dirTypes.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }
    }
}
