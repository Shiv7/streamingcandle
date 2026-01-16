package com.kotsin.consumer.enrichment.signal.outcome;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SignalPerformanceAnalyzer - Advanced analysis of signal performance
 *
 * Provides:
 * - Time-based analysis (by session, day of week, expiry phase)
 * - Edge decay analysis (how quickly signals lose edge)
 * - Confidence calibration (are stated confidences accurate?)
 * - Target optimization (are targets set correctly?)
 * - Strategy recommendations
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalPerformanceAnalyzer {

    private final MongoTemplate mongoTemplate;

    private static final String COLLECTION = "signal_outcomes";

    // Cached analysis results
    private TimeAnalysis cachedTimeAnalysis;
    private ConfidenceCalibration cachedCalibration;
    private Instant lastAnalysisTime;

    // ======================== TIME-BASED ANALYSIS ========================

    /**
     * Analyze performance by session
     */
    public Map<String, SessionPerformance> analyzeBySession(int days) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);

        Query query = Query.query(Criteria.where("closedAt").gte(cutoff)
                .and("outcome").in(SignalOutcome.Outcome.WIN, SignalOutcome.Outcome.LOSS)
                .and("sessionAtSignal").exists(true));

        List<SignalOutcome> outcomes = mongoTemplate.find(query, SignalOutcome.class, COLLECTION);

        return outcomes.stream()
                .filter(o -> o.getSessionAtSignal() != null)
                .collect(Collectors.groupingBy(
                        SignalOutcome::getSessionAtSignal,
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                this::calculateSessionPerformance
                        )
                ));
    }

    /**
     * Analyze performance by day of week
     */
    public Map<String, DayPerformance> analyzeByDayOfWeek(int days) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);

        Query query = Query.query(Criteria.where("closedAt").gte(cutoff)
                .and("outcome").in(SignalOutcome.Outcome.WIN, SignalOutcome.Outcome.LOSS));

        List<SignalOutcome> outcomes = mongoTemplate.find(query, SignalOutcome.class, COLLECTION);

        return outcomes.stream()
                .filter(o -> o.getClosedAt() != null)
                .collect(Collectors.groupingBy(
                        o -> o.getClosedAt().atZone(java.time.ZoneId.systemDefault())
                                .getDayOfWeek().toString(),
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                this::calculateDayPerformance
                        )
                ));
    }

    /**
     * Analyze performance relative to expiry
     */
    public ExpiryAnalysis analyzeExpiryImpact(int days) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);

        Query query = Query.query(Criteria.where("closedAt").gte(cutoff)
                .and("outcome").in(SignalOutcome.Outcome.WIN, SignalOutcome.Outcome.LOSS)
                .and("daysToExpiryAtSignal").exists(true));

        List<SignalOutcome> outcomes = mongoTemplate.find(query, SignalOutcome.class, COLLECTION);

        // Group by DTE buckets: 0-1 (expiry), 2-5 (near), 6+ (far)
        Map<String, List<SignalOutcome>> byDte = new HashMap<>();
        byDte.put("EXPIRY_DAY", new ArrayList<>());
        byDte.put("NEAR_EXPIRY", new ArrayList<>());
        byDte.put("FAR_FROM_EXPIRY", new ArrayList<>());

        for (SignalOutcome o : outcomes) {
            if (o.getDaysToExpiryAtSignal() == null) continue;
            int dte = o.getDaysToExpiryAtSignal();
            if (dte <= 1) {
                byDte.get("EXPIRY_DAY").add(o);
            } else if (dte <= 5) {
                byDte.get("NEAR_EXPIRY").add(o);
            } else {
                byDte.get("FAR_FROM_EXPIRY").add(o);
            }
        }

        Map<String, ExpiryPhasePerformance> phasePerformance = byDte.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> calculateExpiryPhasePerformance(e.getValue())
                ));

        return ExpiryAnalysis.builder()
                .phasePerformance(phasePerformance)
                .totalAnalyzed(outcomes.size())
                .analyzedDays(days)
                .build();
    }

    // ======================== CONFIDENCE CALIBRATION ========================

    /**
     * Analyze if stated confidences match actual win rates
     */
    public ConfidenceCalibration analyzeConfidenceCalibration(int days) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);

        Query query = Query.query(Criteria.where("closedAt").gte(cutoff)
                .and("outcome").in(SignalOutcome.Outcome.WIN, SignalOutcome.Outcome.LOSS)
                .and("signalConfidence").exists(true));

        List<SignalOutcome> outcomes = mongoTemplate.find(query, SignalOutcome.class, COLLECTION);

        // Bucket by confidence levels: 50-60, 60-70, 70-80, 80-90, 90+
        Map<String, CalibrationBucket> buckets = new LinkedHashMap<>();
        buckets.put("50-60%", new CalibrationBucket(0.5, 0.6));
        buckets.put("60-70%", new CalibrationBucket(0.6, 0.7));
        buckets.put("70-80%", new CalibrationBucket(0.7, 0.8));
        buckets.put("80-90%", new CalibrationBucket(0.8, 0.9));
        buckets.put("90%+", new CalibrationBucket(0.9, 1.0));

        for (SignalOutcome o : outcomes) {
            double conf = o.getSignalConfidence();
            for (CalibrationBucket bucket : buckets.values()) {
                if (conf >= bucket.minConf && conf < bucket.maxConf) {
                    bucket.addOutcome(o);
                    break;
                }
            }
        }

        // Calculate calibration error for each bucket
        Map<String, CalibrationResult> results = new LinkedHashMap<>();
        double totalCalibrationError = 0;

        for (Map.Entry<String, CalibrationBucket> entry : buckets.entrySet()) {
            CalibrationBucket bucket = entry.getValue();
            if (bucket.total > 0) {
                double expectedWinRate = (bucket.minConf + bucket.maxConf) / 2;
                double actualWinRate = (double) bucket.wins / bucket.total;
                double calibrationError = actualWinRate - expectedWinRate;

                results.put(entry.getKey(), CalibrationResult.builder()
                        .expectedWinRate(expectedWinRate)
                        .actualWinRate(actualWinRate)
                        .calibrationError(calibrationError)
                        .totalSignals(bucket.total)
                        .wins(bucket.wins)
                        .build());

                totalCalibrationError += Math.abs(calibrationError);
            }
        }

        double avgCalibrationError = results.isEmpty() ? 0 : totalCalibrationError / results.size();

        return ConfidenceCalibration.builder()
                .bucketResults(results)
                .avgCalibrationError(avgCalibrationError)
                .isWellCalibrated(avgCalibrationError < 0.1) // Within 10% is well calibrated
                .totalAnalyzed(outcomes.size())
                .analyzedDays(days)
                .recommendation(generateCalibrationRecommendation(results, avgCalibrationError))
                .build();
    }

    // ======================== EDGE DECAY ANALYSIS ========================

    /**
     * Analyze how signal edge decays over time
     */
    public EdgeDecayAnalysis analyzeEdgeDecay(int days) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);

        Query query = Query.query(Criteria.where("closedAt").gte(cutoff)
                .and("outcome").in(SignalOutcome.Outcome.WIN, SignalOutcome.Outcome.LOSS)
                .and("timeInTradeMs").exists(true));

        List<SignalOutcome> outcomes = mongoTemplate.find(query, SignalOutcome.class, COLLECTION);

        // Bucket by time in trade: <5m, 5-15m, 15-30m, 30-60m, >60m
        Map<String, List<SignalOutcome>> byDuration = new LinkedHashMap<>();
        byDuration.put("<5min", new ArrayList<>());
        byDuration.put("5-15min", new ArrayList<>());
        byDuration.put("15-30min", new ArrayList<>());
        byDuration.put("30-60min", new ArrayList<>());
        byDuration.put(">60min", new ArrayList<>());

        for (SignalOutcome o : outcomes) {
            long mins = o.getTimeInTradeMs() / 60000;
            if (mins < 5) {
                byDuration.get("<5min").add(o);
            } else if (mins < 15) {
                byDuration.get("5-15min").add(o);
            } else if (mins < 30) {
                byDuration.get("15-30min").add(o);
            } else if (mins < 60) {
                byDuration.get("30-60min").add(o);
            } else {
                byDuration.get(">60min").add(o);
            }
        }

        Map<String, DurationPerformance> durationPerformance = byDuration.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> calculateDurationPerformance(e.getValue()),
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        // Find optimal exit timing
        String optimalDuration = durationPerformance.entrySet().stream()
                .max(Comparator.comparingDouble(e -> e.getValue().getExpectedValue()))
                .map(Map.Entry::getKey)
                .orElse("N/A");

        return EdgeDecayAnalysis.builder()
                .durationPerformance(durationPerformance)
                .optimalDuration(optimalDuration)
                .totalAnalyzed(outcomes.size())
                .analyzedDays(days)
                .recommendation(generateEdgeDecayRecommendation(durationPerformance, optimalDuration))
                .build();
    }

    // ======================== TARGET OPTIMIZATION ========================

    /**
     * Analyze if targets are set optimally
     */
    public TargetOptimization analyzeTargetOptimization(int days) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);

        Query query = Query.query(Criteria.where("closedAt").gte(cutoff)
                .and("outcome").in(SignalOutcome.Outcome.WIN, SignalOutcome.Outcome.LOSS));

        List<SignalOutcome> outcomes = mongoTemplate.find(query, SignalOutcome.class, COLLECTION);

        // Analyze MFE vs actual exit
        double avgMfePct = outcomes.stream()
                .filter(o -> o.getMfePct() > 0)
                .mapToDouble(SignalOutcome::getMfePct)
                .average().orElse(0);

        double avgPnlPct = outcomes.stream()
                .mapToDouble(SignalOutcome::getPnlPct)
                .average().orElse(0);

        double avgTradeEfficiency = outcomes.stream()
                .filter(o -> o.getMfePct() > 0)
                .mapToDouble(SignalOutcome::getTradeEfficiency)
                .average().orElse(0);

        // Target hit rates
        double target1HitRate = (double) outcomes.stream().filter(SignalOutcome::isTarget1Hit).count() / outcomes.size();
        double target2HitRate = (double) outcomes.stream().filter(SignalOutcome::isTarget2Hit).count() / outcomes.size();
        double target3HitRate = (double) outcomes.stream().filter(SignalOutcome::isTarget3Hit).count() / outcomes.size();

        // Calculate optimal target based on MFE distribution
        double mfeP50 = calculatePercentile(outcomes.stream()
                .filter(o -> o.getMfePct() > 0)
                .mapToDouble(SignalOutcome::getMfePct)
                .toArray(), 50);
        double mfeP75 = calculatePercentile(outcomes.stream()
                .filter(o -> o.getMfePct() > 0)
                .mapToDouble(SignalOutcome::getMfePct)
                .toArray(), 75);

        return TargetOptimization.builder()
                .avgMfePct(avgMfePct)
                .avgPnlPct(avgPnlPct)
                .avgTradeEfficiency(avgTradeEfficiency)
                .target1HitRate(target1HitRate)
                .target2HitRate(target2HitRate)
                .target3HitRate(target3HitRate)
                .suggestedTarget1Pct(mfeP50 * 0.6) // 60% of median MFE
                .suggestedTarget2Pct(mfeP50) // Median MFE
                .suggestedTarget3Pct(mfeP75) // 75th percentile MFE
                .totalAnalyzed(outcomes.size())
                .analyzedDays(days)
                .recommendation(generateTargetRecommendation(avgTradeEfficiency, target1HitRate, target2HitRate))
                .build();
    }

    // ======================== HELPER METHODS ========================

    private SessionPerformance calculateSessionPerformance(List<SignalOutcome> outcomes) {
        if (outcomes.isEmpty()) return SessionPerformance.empty();

        long wins = outcomes.stream().filter(SignalOutcome::isProfitable).count();
        double totalPnl = outcomes.stream().mapToDouble(SignalOutcome::getPnlPct).sum();
        double avgRMultiple = outcomes.stream().mapToDouble(SignalOutcome::getRMultiple).average().orElse(0);

        return SessionPerformance.builder()
                .totalTrades(outcomes.size())
                .wins((int) wins)
                .winRate((double) wins / outcomes.size())
                .totalPnlPct(totalPnl)
                .avgPnlPct(totalPnl / outcomes.size())
                .avgRMultiple(avgRMultiple)
                .build();
    }

    private DayPerformance calculateDayPerformance(List<SignalOutcome> outcomes) {
        if (outcomes.isEmpty()) return DayPerformance.empty();

        long wins = outcomes.stream().filter(SignalOutcome::isProfitable).count();
        double totalPnl = outcomes.stream().mapToDouble(SignalOutcome::getPnlPct).sum();

        return DayPerformance.builder()
                .totalTrades(outcomes.size())
                .wins((int) wins)
                .winRate((double) wins / outcomes.size())
                .totalPnlPct(totalPnl)
                .build();
    }

    private ExpiryPhasePerformance calculateExpiryPhasePerformance(List<SignalOutcome> outcomes) {
        if (outcomes.isEmpty()) return ExpiryPhasePerformance.empty();

        long wins = outcomes.stream().filter(SignalOutcome::isProfitable).count();
        double totalPnl = outcomes.stream().mapToDouble(SignalOutcome::getPnlPct).sum();

        return ExpiryPhasePerformance.builder()
                .totalTrades(outcomes.size())
                .wins((int) wins)
                .winRate((double) wins / outcomes.size())
                .totalPnlPct(totalPnl)
                .build();
    }

    private DurationPerformance calculateDurationPerformance(List<SignalOutcome> outcomes) {
        if (outcomes.isEmpty()) return DurationPerformance.empty();

        long wins = outcomes.stream().filter(SignalOutcome::isProfitable).count();
        double winRate = (double) wins / outcomes.size();
        double avgWin = outcomes.stream().filter(o -> o.getPnlPct() > 0).mapToDouble(SignalOutcome::getPnlPct).average().orElse(0);
        double avgLoss = outcomes.stream().filter(o -> o.getPnlPct() < 0).mapToDouble(SignalOutcome::getPnlPct).average().orElse(0);
        double expectedValue = winRate * avgWin + (1 - winRate) * avgLoss;

        return DurationPerformance.builder()
                .totalTrades(outcomes.size())
                .wins((int) wins)
                .winRate(winRate)
                .avgWinPct(avgWin)
                .avgLossPct(avgLoss)
                .expectedValue(expectedValue)
                .build();
    }

    private double calculatePercentile(double[] values, int percentile) {
        if (values.length == 0) return 0;
        Arrays.sort(values);
        int index = (int) Math.ceil(percentile / 100.0 * values.length) - 1;
        return values[Math.max(0, Math.min(index, values.length - 1))];
    }

    private String generateCalibrationRecommendation(Map<String, CalibrationResult> results, double avgError) {
        if (avgError < 0.05) {
            return "Confidence calibration is excellent. Stated confidences accurately reflect actual win rates.";
        } else if (avgError < 0.10) {
            return "Confidence calibration is good but could be improved. Consider minor adjustments.";
        } else {
            StringBuilder sb = new StringBuilder("Confidence needs recalibration. ");
            for (Map.Entry<String, CalibrationResult> entry : results.entrySet()) {
                if (entry.getValue().getCalibrationError() > 0.1) {
                    sb.append(String.format("%s bucket is over-confident by %.0f%%. ",
                            entry.getKey(), entry.getValue().getCalibrationError() * 100));
                } else if (entry.getValue().getCalibrationError() < -0.1) {
                    sb.append(String.format("%s bucket is under-confident by %.0f%%. ",
                            entry.getKey(), -entry.getValue().getCalibrationError() * 100));
                }
            }
            return sb.toString().trim();
        }
    }

    private String generateEdgeDecayRecommendation(Map<String, DurationPerformance> performance, String optimal) {
        return String.format("Optimal trade duration is %s. Consider implementing time-based exits for trades exceeding optimal duration.", optimal);
    }

    private String generateTargetRecommendation(double efficiency, double t1Rate, double t2Rate) {
        StringBuilder sb = new StringBuilder();
        if (efficiency < 0.5) {
            sb.append("Trade efficiency is low. Consider tighter targets or trailing stops. ");
        }
        if (t1Rate > 0.7 && t2Rate < 0.4) {
            sb.append("Target 1 is hit often but Target 2 rarely. Consider moving Target 2 closer. ");
        }
        if (t1Rate < 0.3) {
            sb.append("Target 1 hit rate is low. Consider lowering targets. ");
        }
        return sb.length() > 0 ? sb.toString().trim() : "Target settings appear optimal.";
    }

    // ======================== SCHEDULED REFRESH ========================

    @Scheduled(fixedRate = 3600_000) // Every hour
    public void refreshAnalysis() {
        log.info("[PERFORMANCE_ANALYZER] Refreshing performance analysis...");
        cachedTimeAnalysis = null;
        cachedCalibration = null;
    }

    // ======================== MODELS ========================

    @Data
    @Builder
    @AllArgsConstructor
    public static class SessionPerformance {
        private int totalTrades;
        private int wins;
        private double winRate;
        private double totalPnlPct;
        private double avgPnlPct;
        private double avgRMultiple;

        public static SessionPerformance empty() {
            return SessionPerformance.builder().build();
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class DayPerformance {
        private int totalTrades;
        private int wins;
        private double winRate;
        private double totalPnlPct;

        public static DayPerformance empty() {
            return DayPerformance.builder().build();
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class ExpiryAnalysis {
        private Map<String, ExpiryPhasePerformance> phasePerformance;
        private int totalAnalyzed;
        private int analyzedDays;
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class ExpiryPhasePerformance {
        private int totalTrades;
        private int wins;
        private double winRate;
        private double totalPnlPct;

        public static ExpiryPhasePerformance empty() {
            return ExpiryPhasePerformance.builder().build();
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class ConfidenceCalibration {
        private Map<String, CalibrationResult> bucketResults;
        private double avgCalibrationError;
        private boolean isWellCalibrated;
        private int totalAnalyzed;
        private int analyzedDays;
        private String recommendation;
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class CalibrationResult {
        private double expectedWinRate;
        private double actualWinRate;
        private double calibrationError;
        private int totalSignals;
        private int wins;
    }

    private static class CalibrationBucket {
        double minConf, maxConf;
        int total = 0;
        int wins = 0;

        CalibrationBucket(double min, double max) {
            this.minConf = min;
            this.maxConf = max;
        }

        void addOutcome(SignalOutcome o) {
            total++;
            if (o.isProfitable()) wins++;
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class EdgeDecayAnalysis {
        private Map<String, DurationPerformance> durationPerformance;
        private String optimalDuration;
        private int totalAnalyzed;
        private int analyzedDays;
        private String recommendation;
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class DurationPerformance {
        private int totalTrades;
        private int wins;
        private double winRate;
        private double avgWinPct;
        private double avgLossPct;
        private double expectedValue;

        public static DurationPerformance empty() {
            return DurationPerformance.builder().build();
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class TargetOptimization {
        private double avgMfePct;
        private double avgPnlPct;
        private double avgTradeEfficiency;
        private double target1HitRate;
        private double target2HitRate;
        private double target3HitRate;
        private double suggestedTarget1Pct;
        private double suggestedTarget2Pct;
        private double suggestedTarget3Pct;
        private int totalAnalyzed;
        private int analyzedDays;
        private String recommendation;
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class TimeAnalysis {
        private Map<String, SessionPerformance> bySession;
        private Map<String, DayPerformance> byDayOfWeek;
        private Instant calculatedAt;
    }
}
