package com.kotsin.consumer.mtf.analyzer;

import com.kotsin.consumer.mtf.model.MultiTimeframeData;
import com.kotsin.consumer.mtf.model.MultiTimeframeData.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MultiTimeframeAnalyzer - Analyzes and correlates data across multiple timeframes.
 *
 * Features:
 * - Trend alignment detection
 * - Key level confluence identification
 * - Signal generation based on MTF agreement
 * - Entry quality scoring
 */
@Component
@Slf4j
public class MultiTimeframeAnalyzer {

    // Standard timeframes (in order from highest to lowest)
    public static final String[] TIMEFRAMES = {"1D", "4H", "1H", "15m", "5m", "1m"};

    // Cache for MTF data
    private final Map<String, MultiTimeframeData> mtfCache = new ConcurrentHashMap<>();

    // Timeframe weights for scoring
    private static final Map<String, Double> TIMEFRAME_WEIGHTS = Map.of(
        "1D", 3.0,
        "4H", 2.5,
        "1H", 2.0,
        "15m", 1.5,
        "5m", 1.0,
        "1m", 0.5
    );

    /**
     * Analyze multiple timeframes and generate consolidated view.
     *
     * @param symbol              Symbol identifier
     * @param timeframeMetricsMap Map of timeframe to metrics
     * @return MultiTimeframeData with consolidated analysis
     */
    public MultiTimeframeData analyze(String symbol, Map<String, TimeframeMetrics> timeframeMetricsMap) {
        MultiTimeframeData mtf = MultiTimeframeData.builder()
            .symbol(symbol)
            .timestamp(Instant.now())
            .trendByTimeframe(new HashMap<>())
            .metricsByTimeframe(new HashMap<>())
            .build();

        // Process each timeframe
        for (Map.Entry<String, TimeframeMetrics> entry : timeframeMetricsMap.entrySet()) {
            String tf = entry.getKey();
            TimeframeMetrics metrics = entry.getValue();

            TrendDirection trend = metrics.determineTrend();
            mtf.addTimeframeData(tf, trend, metrics);
        }

        // Calculate alignment
        calculateAlignment(mtf);

        // Identify key levels
        identifyKeyLevels(mtf);

        // Calculate momentum
        calculateMomentum(mtf);

        // Generate signal
        generateSignal(mtf);

        // Score entry quality
        scoreEntryQuality(mtf);

        // Cache result
        mtfCache.put(symbol, mtf);

        return mtf;
    }

    /**
     * Calculate trend alignment across timeframes.
     */
    private void calculateAlignment(MultiTimeframeData mtf) {
        Map<String, TrendDirection> trends = mtf.getTrendByTimeframe();

        if (trends.isEmpty()) {
            mtf.setOverallAlignment(TrendAlignment.MIXED);
            mtf.setAlignmentScore(50);
            return;
        }

        int bullishCount = 0;
        int bearishCount = 0;
        int neutralCount = 0;
        double weightedScore = 0;
        double totalWeight = 0;

        for (Map.Entry<String, TrendDirection> entry : trends.entrySet()) {
            String tf = entry.getKey();
            TrendDirection trend = entry.getValue();
            double weight = TIMEFRAME_WEIGHTS.getOrDefault(tf, 1.0);
            totalWeight += weight;

            switch (trend) {
                case STRONG_UP:
                    bullishCount++;
                    weightedScore += weight * 100;
                    break;
                case UP:
                    bullishCount++;
                    weightedScore += weight * 75;
                    break;
                case NEUTRAL:
                    neutralCount++;
                    weightedScore += weight * 50;
                    break;
                case DOWN:
                    bearishCount++;
                    weightedScore += weight * 25;
                    break;
                case STRONG_DOWN:
                    bearishCount++;
                    weightedScore += weight * 0;
                    break;
            }
        }

        // Determine alignment
        int total = trends.size();
        if (bullishCount == total || bearishCount == total) {
            mtf.setOverallAlignment(TrendAlignment.FULLY_ALIGNED);
        } else if (bullishCount >= total * 0.7 || bearishCount >= total * 0.7) {
            mtf.setOverallAlignment(TrendAlignment.MOSTLY_ALIGNED);
        } else if (bullishCount > 0 && bearishCount > 0 && Math.abs(bullishCount - bearishCount) <= 1) {
            mtf.setOverallAlignment(TrendAlignment.CONFLICTING);
        } else {
            mtf.setOverallAlignment(TrendAlignment.MIXED);
        }

        mtf.setAlignmentScore(totalWeight > 0 ? weightedScore / totalWeight : 50);
    }

    /**
     * Identify key support/resistance levels across timeframes.
     */
    private void identifyKeyLevels(MultiTimeframeData mtf) {
        Map<String, TimeframeMetrics> metrics = mtf.getMetricsByTimeframe();

        // Get HTF levels (daily/4H)
        TimeframeMetrics daily = metrics.get("1D");
        TimeframeMetrics h4 = metrics.get("4H");
        TimeframeMetrics htf = daily != null ? daily : h4;

        // Get LTF levels (5m/15m)
        TimeframeMetrics m5 = metrics.get("5m");
        TimeframeMetrics m15 = metrics.get("15m");
        TimeframeMetrics ltf = m5 != null ? m5 : m15;

        if (htf != null) {
            mtf.setHtfResistance(htf.getHigh());
            mtf.setHtfSupport(htf.getLow());
        }

        if (ltf != null) {
            mtf.setLtfResistance(ltf.getHigh());
            mtf.setLtfSupport(ltf.getLow());
        }

        // Check for confluence
        if (htf != null && ltf != null) {
            double tolerance = htf.getClose() * 0.002;  // 0.2% tolerance

            boolean resistanceConfluence = Math.abs(htf.getHigh() - ltf.getHigh()) < tolerance;
            boolean supportConfluence = Math.abs(htf.getLow() - ltf.getLow()) < tolerance;

            mtf.setHasLevelConfluence(resistanceConfluence || supportConfluence);
        }
    }

    /**
     * Calculate momentum across timeframes.
     */
    private void calculateMomentum(MultiTimeframeData mtf) {
        Map<String, TimeframeMetrics> metrics = mtf.getMetricsByTimeframe();

        // HTF momentum (daily/4H RSI and MACD)
        double htfMomentum = 0;
        int htfCount = 0;

        TimeframeMetrics daily = metrics.get("1D");
        if (daily != null) {
            htfMomentum += normalizeMomentum(daily.getRsi(), daily.getMacdHistogram());
            htfCount++;
        }

        TimeframeMetrics h4 = metrics.get("4H");
        if (h4 != null) {
            htfMomentum += normalizeMomentum(h4.getRsi(), h4.getMacdHistogram());
            htfCount++;
        }

        if (htfCount > 0) {
            mtf.setHtfMomentum(htfMomentum / htfCount);
        }

        // LTF momentum (5m/15m RSI and MACD)
        double ltfMomentum = 0;
        int ltfCount = 0;

        TimeframeMetrics m5 = metrics.get("5m");
        if (m5 != null) {
            ltfMomentum += normalizeMomentum(m5.getRsi(), m5.getMacdHistogram());
            ltfCount++;
        }

        TimeframeMetrics m15 = metrics.get("15m");
        if (m15 != null) {
            ltfMomentum += normalizeMomentum(m15.getRsi(), m15.getMacdHistogram());
            ltfCount++;
        }

        if (ltfCount > 0) {
            mtf.setLtfMomentum(ltfMomentum / ltfCount);
        }

        // Check alignment
        boolean bothBullish = mtf.getHtfMomentum() > 0 && mtf.getLtfMomentum() > 0;
        boolean bothBearish = mtf.getHtfMomentum() < 0 && mtf.getLtfMomentum() < 0;
        mtf.setMomentumAligned(bothBullish || bothBearish);
    }

    private double normalizeMomentum(double rsi, double macdHist) {
        // RSI: 0-100, center at 50
        double rsiScore = (rsi - 50) / 50;  // -1 to 1

        // MACD: normalize based on typical values
        double macdScore = Math.max(-1, Math.min(1, macdHist / 10));

        return (rsiScore + macdScore) / 2;
    }

    /**
     * Generate trading signal based on MTF analysis.
     */
    private void generateSignal(MultiTimeframeData mtf) {
        double alignmentScore = mtf.getAlignmentScore();
        TrendAlignment alignment = mtf.getOverallAlignment();
        boolean momentumAligned = mtf.isMomentumAligned();
        double htfMomentum = mtf.getHtfMomentum();

        // Determine signal based on alignment and momentum
        MTFSignal signal;
        String reason;

        if (alignment == TrendAlignment.FULLY_ALIGNED && momentumAligned) {
            if (alignmentScore > 75) {
                signal = MTFSignal.STRONG_BUY;
                reason = "Full trend alignment with strong bullish momentum";
            } else if (alignmentScore < 25) {
                signal = MTFSignal.STRONG_SELL;
                reason = "Full trend alignment with strong bearish momentum";
            } else if (htfMomentum > 0) {
                signal = MTFSignal.BUY;
                reason = "Aligned trends with bullish momentum";
            } else {
                signal = MTFSignal.SELL;
                reason = "Aligned trends with bearish momentum";
            }
        } else if (alignment == TrendAlignment.MOSTLY_ALIGNED) {
            if (htfMomentum > 0.3) {
                signal = MTFSignal.BUY;
                reason = "Most timeframes bullish with positive momentum";
            } else if (htfMomentum < -0.3) {
                signal = MTFSignal.SELL;
                reason = "Most timeframes bearish with negative momentum";
            } else {
                signal = MTFSignal.NEUTRAL;
                reason = "Partial alignment, weak momentum";
            }
        } else if (alignment == TrendAlignment.CONFLICTING) {
            signal = MTFSignal.NEUTRAL;
            reason = "Conflicting trends across timeframes";
        } else {
            signal = MTFSignal.NEUTRAL;
            reason = "Mixed signals, no clear direction";
        }

        mtf.setSignal(signal);
        mtf.setSignalReason(reason);
        mtf.setSignalStrength(Math.abs(alignmentScore - 50) * 2);  // 0-100 scale
        mtf.setSignalTimeframe(findDominantTimeframe(mtf));
    }

    private String findDominantTimeframe(MultiTimeframeData mtf) {
        // Return the highest timeframe with data
        for (String tf : TIMEFRAMES) {
            if (mtf.getMetricsByTimeframe().containsKey(tf)) {
                return tf;
            }
        }
        return "5m";
    }

    /**
     * Score entry quality based on multiple factors.
     */
    private void scoreEntryQuality(MultiTimeframeData mtf) {
        int qualityScore = 0;

        // Trend alignment (+30)
        if (mtf.getOverallAlignment() == TrendAlignment.FULLY_ALIGNED) {
            qualityScore += 30;
        } else if (mtf.getOverallAlignment() == TrendAlignment.MOSTLY_ALIGNED) {
            qualityScore += 20;
        }

        // Momentum alignment (+20)
        if (mtf.isMomentumAligned()) {
            qualityScore += 20;
        }

        // Level confluence (+20)
        if (mtf.isHasLevelConfluence()) {
            qualityScore += 20;
        }

        // Signal strength (+30)
        MTFSignal signal = mtf.getSignal();
        if (signal == MTFSignal.STRONG_BUY || signal == MTFSignal.STRONG_SELL) {
            qualityScore += 30;
        } else if (signal == MTFSignal.BUY || signal == MTFSignal.SELL) {
            qualityScore += 15;
        }

        // Determine quality level
        EntryQuality quality;
        if (qualityScore >= 80) {
            quality = EntryQuality.EXCELLENT;
        } else if (qualityScore >= 60) {
            quality = EntryQuality.GOOD;
        } else if (qualityScore >= 40) {
            quality = EntryQuality.AVERAGE;
        } else {
            quality = EntryQuality.POOR;
        }

        mtf.setEntryQuality(quality);
        mtf.setOptimalEntry(quality == EntryQuality.EXCELLENT || quality == EntryQuality.GOOD);

        // Calculate risk/reward (simplified)
        calculateRiskReward(mtf);
    }

    private void calculateRiskReward(MultiTimeframeData mtf) {
        double htfResistance = mtf.getHtfResistance();
        double htfSupport = mtf.getHtfSupport();

        if (htfResistance > 0 && htfSupport > 0) {
            // Get current price from any available metrics
            TimeframeMetrics anyMetrics = mtf.getMetricsByTimeframe().values().stream()
                .findFirst().orElse(null);

            if (anyMetrics != null) {
                double price = anyMetrics.getClose();
                double distanceToResistance = htfResistance - price;
                double distanceToSupport = price - htfSupport;

                if (distanceToSupport > 0) {
                    mtf.setRiskRewardRatio(distanceToResistance / distanceToSupport);
                }
            }
        }
    }

    // ==================== PUBLIC GETTERS ====================

    public MultiTimeframeData getMTFData(String symbol) {
        return mtfCache.get(symbol);
    }

    public boolean isOptimalEntry(String symbol) {
        MultiTimeframeData mtf = mtfCache.get(symbol);
        return mtf != null && mtf.isOptimalEntry();
    }

    public boolean isTrendAligned(String symbol) {
        MultiTimeframeData mtf = mtfCache.get(symbol);
        return mtf != null && (mtf.getOverallAlignment() == TrendAlignment.FULLY_ALIGNED
            || mtf.getOverallAlignment() == TrendAlignment.MOSTLY_ALIGNED);
    }

    public MTFSignal getSignal(String symbol) {
        MultiTimeframeData mtf = mtfCache.get(symbol);
        return mtf != null ? mtf.getSignal() : MTFSignal.NEUTRAL;
    }
}
