package com.kotsin.consumer.signal.trigger;

import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.model.MultiTimeframePivotState;
import com.kotsin.consumer.model.ConfluenceResult;
import com.kotsin.consumer.mtf.analyzer.MultiTimeframeAnalyzer;
import com.kotsin.consumer.mtf.model.MultiTimeframeData;
import com.kotsin.consumer.mtf.model.MultiTimeframeData.*;
import com.kotsin.consumer.service.CandleService;
import com.kotsin.consumer.service.PivotLevelService;
import com.kotsin.consumer.smc.analyzer.SMCAnalyzer;
import com.kotsin.consumer.smc.analyzer.SMCAnalyzer.SMCResult;
import com.kotsin.consumer.smc.analyzer.SMCAnalyzer.CandleData;
import com.kotsin.consumer.smc.model.OrderBlock;
import com.kotsin.consumer.smc.model.FairValueGap;
import com.kotsin.consumer.smc.model.LiquidityZone;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PivotConfluenceTrigger - Handles Strategy 2: Pure Price Action with MTF confluence.
 *
 * FLOW:
 * 1. HTF (Daily, 4H) → Determine BULLISH/BEARISH bias (DO NOT counter-trade)
 * 2. LTF (15m, 5m) → Confirm entry in HTF direction only
 * 3. Pivot Confluence → Price at multiple pivot levels
 * 4. SMC Zones → Order Blocks, FVG, Liquidity Zones
 * 5. Volume confirmation
 * 6. Risk:Reward validation (min 1.5:1)
 *
 * TRIGGER:
 * - HTF bias + LTF confirmation + Pivot confluence + SMC zone + Good R:R = TRIGGER
 */
@Component
@Slf4j
public class PivotConfluenceTrigger {

    private static final String LOG_PREFIX = "[PIVOT-CONFLUENCE]";

    @Autowired
    private CandleService candleService;

    @Autowired
    private PivotLevelService pivotLevelService;

    @Autowired
    private MultiTimeframeAnalyzer mtfAnalyzer;

    @Autowired
    private SMCAnalyzer smcAnalyzer;

    @Value("${pivot.confluence.enabled:true}")
    private boolean enabled;

    @Value("${pivot.confluence.min.levels:2}")
    private int minConfluenceLevels;

    @Value("${pivot.confluence.min.rr:1.5}")
    private double minRiskReward;

    @Value("${pivot.confluence.htf.weight:0.6}")
    private double htfWeight;

    @Value("${pivot.confluence.ltf.weight:0.4}")
    private double ltfWeight;

    // Cache for HTF bias
    private final Map<String, DirectionBias> htfBiasCache = new ConcurrentHashMap<>();

    /**
     * Check if Pivot Confluence signal should trigger.
     */
    public PivotTriggerResult checkTrigger(String scripCode, String exch, String exchType) {
        if (!enabled) {
            return PivotTriggerResult.noTrigger("Pivot confluence trigger disabled");
        }

        try {
            log.info("{} {} Starting pivot confluence analysis", LOG_PREFIX, scripCode);

            // Step 1: Determine HTF Bias (Daily, 4H)
            DirectionBias htfBias = analyzeHTFBias(scripCode);
            log.info("{} {} HTF Bias: direction={}, strength={}, reason={}",
                LOG_PREFIX, scripCode, htfBias.direction,
                String.format("%.2f", htfBias.strength), htfBias.reason);

            if (htfBias.direction == BiasDirection.NEUTRAL) {
                return PivotTriggerResult.noTrigger("HTF bias is NEUTRAL - no clear direction");
            }

            // Step 2: Analyze LTF for confirmation (15m, 5m)
            LTFConfirmation ltfConfirm = analyzeLTFConfirmation(scripCode, htfBias.direction);
            log.info("{} {} LTF Confirmation: confirmed={}, direction={}, alignment={}",
                LOG_PREFIX, scripCode, ltfConfirm.confirmed, ltfConfirm.direction,
                String.format("%.2f", ltfConfirm.alignmentScore));

            if (!ltfConfirm.confirmed) {
                return PivotTriggerResult.noTrigger("LTF does not confirm HTF direction: " + ltfConfirm.reason);
            }

            // Step 3: Check Pivot Confluence
            PivotConfluenceAnalysis pivotAnalysis = analyzePivotConfluence(scripCode, exch, exchType);
            log.info("{} {} Pivot Confluence: levels={}, nearbyLevels={}, cprPosition={}",
                LOG_PREFIX, scripCode, pivotAnalysis.confluenceLevels.size(),
                pivotAnalysis.nearbyLevels, pivotAnalysis.cprPosition);

            if (pivotAnalysis.nearbyLevels < minConfluenceLevels) {
                return PivotTriggerResult.noTrigger(String.format(
                    "Insufficient pivot confluence: %d levels (min %d required)",
                    pivotAnalysis.nearbyLevels, minConfluenceLevels));
            }

            // Step 4: Check SMC Zones
            SMCZoneAnalysis smcAnalysis = analyzeSMCZones(scripCode, htfBias.direction);
            log.info("{} {} SMC Zones: inOrderBlock={}, nearFVG={}, atLiquidity={}, bias={}",
                LOG_PREFIX, scripCode, smcAnalysis.inOrderBlock, smcAnalysis.nearFVG,
                smcAnalysis.atLiquidityZone, smcAnalysis.smcBias);

            // Step 5: Calculate Risk:Reward
            RiskRewardCalculation rrCalc = calculateRiskReward(scripCode, htfBias.direction,
                pivotAnalysis, smcAnalysis);
            log.info("{} {} Risk:Reward: entry={}, stop={}, target={}, R:R={}",
                LOG_PREFIX, scripCode,
                String.format("%.2f", rrCalc.entryPrice),
                String.format("%.2f", rrCalc.stopLoss),
                String.format("%.2f", rrCalc.target),
                String.format("%.2f", rrCalc.riskReward));

            if (rrCalc.riskReward < minRiskReward) {
                return PivotTriggerResult.noTrigger(String.format(
                    "R:R too low: %.2f (min %.2f required)", rrCalc.riskReward, minRiskReward));
            }

            // Step 6: Calculate overall score
            double triggerScore = calculateTriggerScore(htfBias, ltfConfirm, pivotAnalysis, smcAnalysis, rrCalc);
            log.info("{} {} Trigger Score: {}", LOG_PREFIX, scripCode, String.format("%.2f", triggerScore));

            // All conditions met - TRIGGER!
            log.info("{} {} *** PIVOT CONFLUENCE TRIGGER *** direction={}, score={}, R:R={}",
                LOG_PREFIX, scripCode, htfBias.direction,
                String.format("%.2f", triggerScore),
                String.format("%.2f", rrCalc.riskReward));

            return PivotTriggerResult.builder()
                .triggered(true)
                .direction(htfBias.direction == BiasDirection.BULLISH ?
                    TriggerDirection.BULLISH : TriggerDirection.BEARISH)
                .reason(buildTriggerReason(htfBias, pivotAnalysis, smcAnalysis, rrCalc))
                .score(triggerScore)
                .htfBias(htfBias)
                .ltfConfirmation(ltfConfirm)
                .pivotAnalysis(pivotAnalysis)
                .smcAnalysis(smcAnalysis)
                .rrCalc(rrCalc)
                .triggerTime(Instant.now())
                .build();

        } catch (Exception e) {
            log.error("{} {} Error in pivot confluence check: {}", LOG_PREFIX, scripCode, e.getMessage(), e);
            return PivotTriggerResult.noTrigger("Error: " + e.getMessage());
        }
    }

    /**
     * Analyze Higher Timeframe bias (Daily, 4H).
     */
    private DirectionBias analyzeHTFBias(String scripCode) {
        log.debug("{} {} Analyzing HTF bias (Daily, 4H)", LOG_PREFIX, scripCode);

        double bullishScore = 0;
        double bearishScore = 0;
        List<String> reasons = new ArrayList<>();

        // Get Daily candles
        List<UnifiedCandle> dailyCandles = candleService.getCandleHistory(scripCode, Timeframe.D1, 20);
        if (dailyCandles != null && dailyCandles.size() >= 5) {
            UnifiedCandle latest = dailyCandles.get(0);
            double close = latest.getClose();

            // EMA check (using simple moving avg of closes)
            double ema20 = calculateEMA(dailyCandles, 20);
            double ema50 = dailyCandles.size() >= 50 ? calculateEMA(dailyCandles, 50) : ema20;

            if (close > ema20 && close > ema50) {
                bullishScore += 30;
                reasons.add("Daily: Price above EMA20 & EMA50");
            } else if (close < ema20 && close < ema50) {
                bearishScore += 30;
                reasons.add("Daily: Price below EMA20 & EMA50");
            }

            // Trend direction
            if (dailyCandles.size() >= 5) {
                double fiveDayChange = (close - dailyCandles.get(4).getClose()) / dailyCandles.get(4).getClose() * 100;
                if (fiveDayChange > 2) {
                    bullishScore += 20;
                    reasons.add(String.format("Daily: 5-day up trend (+%.1f%%)", fiveDayChange));
                } else if (fiveDayChange < -2) {
                    bearishScore += 20;
                    reasons.add(String.format("Daily: 5-day down trend (%.1f%%)", fiveDayChange));
                }
            }

            log.debug("{} {} Daily analysis: close={}, ema20={}, bullish={}, bearish={}",
                LOG_PREFIX, scripCode, String.format("%.2f", close),
                String.format("%.2f", ema20), bullishScore, bearishScore);
        }

        // Get 4H candles
        List<UnifiedCandle> h4Candles = candleService.getCandleHistory(scripCode, Timeframe.H4, 20);
        if (h4Candles != null && h4Candles.size() >= 5) {
            UnifiedCandle latest = h4Candles.get(0);
            double close = latest.getClose();
            double ema20 = calculateEMA(h4Candles, 20);

            if (close > ema20) {
                bullishScore += 20;
                reasons.add("4H: Price above EMA20");
            } else if (close < ema20) {
                bearishScore += 20;
                reasons.add("4H: Price below EMA20");
            }

            // Higher highs / Lower lows
            if (h4Candles.size() >= 3) {
                boolean higherHighs = h4Candles.get(0).getHigh() > h4Candles.get(1).getHigh() &&
                                     h4Candles.get(1).getHigh() > h4Candles.get(2).getHigh();
                boolean lowerLows = h4Candles.get(0).getLow() < h4Candles.get(1).getLow() &&
                                   h4Candles.get(1).getLow() < h4Candles.get(2).getLow();

                if (higherHighs) {
                    bullishScore += 15;
                    reasons.add("4H: Higher highs pattern");
                }
                if (lowerLows) {
                    bearishScore += 15;
                    reasons.add("4H: Lower lows pattern");
                }
            }

            log.debug("{} {} 4H analysis: bullish={}, bearish={}", LOG_PREFIX, scripCode, bullishScore, bearishScore);
        }

        // Determine bias
        BiasDirection direction;
        double strength;

        if (bullishScore > bearishScore && bullishScore >= 30) {
            direction = BiasDirection.BULLISH;
            strength = bullishScore / (bullishScore + bearishScore + 1);
        } else if (bearishScore > bullishScore && bearishScore >= 30) {
            direction = BiasDirection.BEARISH;
            strength = bearishScore / (bullishScore + bearishScore + 1);
        } else {
            direction = BiasDirection.NEUTRAL;
            strength = 0.5;
        }

        DirectionBias bias = DirectionBias.builder()
            .direction(direction)
            .strength(strength)
            .bullishScore(bullishScore)
            .bearishScore(bearishScore)
            .reason(String.join("; ", reasons))
            .build();

        htfBiasCache.put(scripCode, bias);
        return bias;
    }

    /**
     * Analyze Lower Timeframe confirmation (15m, 5m).
     */
    private LTFConfirmation analyzeLTFConfirmation(String scripCode, BiasDirection htfDirection) {
        log.debug("{} {} Analyzing LTF confirmation for {} bias", LOG_PREFIX, scripCode, htfDirection);

        boolean confirms = false;
        double alignmentScore = 0;
        List<String> reasons = new ArrayList<>();

        // Get 15m candles
        List<UnifiedCandle> m15Candles = candleService.getCandleHistory(scripCode, Timeframe.M15, 20);
        if (m15Candles != null && m15Candles.size() >= 5) {
            UnifiedCandle latest = m15Candles.get(0);
            double close = latest.getClose();
            double ema9 = calculateEMA(m15Candles, 9);

            boolean ltfBullish = close > ema9;
            boolean ltfBearish = close < ema9;

            if (htfDirection == BiasDirection.BULLISH && ltfBullish) {
                alignmentScore += 40;
                confirms = true;
                reasons.add("15m: Price above EMA9 (confirms bullish)");
            } else if (htfDirection == BiasDirection.BEARISH && ltfBearish) {
                alignmentScore += 40;
                confirms = true;
                reasons.add("15m: Price below EMA9 (confirms bearish)");
            } else {
                reasons.add("15m: Does not confirm HTF direction");
            }

            log.debug("{} {} 15m: close={}, ema9={}, confirms={}", LOG_PREFIX, scripCode,
                String.format("%.2f", close), String.format("%.2f", ema9), confirms);
        }

        // Get 5m candles
        List<UnifiedCandle> m5Candles = candleService.getCandleHistory(scripCode, Timeframe.M5, 20);
        if (m5Candles != null && m5Candles.size() >= 5) {
            UnifiedCandle latest = m5Candles.get(0);
            double close = latest.getClose();
            double ema9 = calculateEMA(m5Candles, 9);

            boolean ltfBullish = close > ema9;
            boolean ltfBearish = close < ema9;

            if (htfDirection == BiasDirection.BULLISH && ltfBullish) {
                alignmentScore += 30;
                reasons.add("5m: Price above EMA9 (confirms bullish)");
            } else if (htfDirection == BiasDirection.BEARISH && ltfBearish) {
                alignmentScore += 30;
                reasons.add("5m: Price below EMA9 (confirms bearish)");
            }
        }

        // Momentum check on 5m
        if (m5Candles != null && m5Candles.size() >= 3) {
            boolean momentum = htfDirection == BiasDirection.BULLISH ?
                m5Candles.get(0).getClose() > m5Candles.get(2).getClose() :
                m5Candles.get(0).getClose() < m5Candles.get(2).getClose();

            if (momentum) {
                alignmentScore += 20;
                reasons.add("5m: Momentum in HTF direction");
            }
        }

        return LTFConfirmation.builder()
            .confirmed(confirms && alignmentScore >= 50)
            .direction(htfDirection)
            .alignmentScore(alignmentScore)
            .reason(String.join("; ", reasons))
            .build();
    }

    /**
     * Analyze Pivot Confluence.
     */
    private PivotConfluenceAnalysis analyzePivotConfluence(String scripCode, String exch, String exchType) {
        log.debug("{} {} Analyzing pivot confluence", LOG_PREFIX, scripCode);

        List<UnifiedCandle> candles = candleService.getCandleHistory(scripCode, Timeframe.M5, 5);
        double currentPrice = candles != null && !candles.isEmpty() ? candles.get(0).getClose() : 0;

        MultiTimeframePivotState pivotState = pivotLevelService.getOrLoadPivotLevels(scripCode, exch, exchType).orElse(null);

        List<String> confluenceLevels = new ArrayList<>();
        int nearbyLevels = 0;
        double tolerance = currentPrice * 0.003; // 0.3% tolerance

        if (pivotState != null) {
            // Check daily pivot
            if (pivotState.getDailyPivot() != null) {
                double pivot = pivotState.getDailyPivot().getPivot();
                if (Math.abs(currentPrice - pivot) <= tolerance) {
                    nearbyLevels++;
                    confluenceLevels.add(String.format("Daily Pivot: %.2f", pivot));
                }

                // Check S1, R1
                double s1 = pivotState.getDailyPivot().getS1();
                double r1 = pivotState.getDailyPivot().getR1();
                if (Math.abs(currentPrice - s1) <= tolerance) {
                    nearbyLevels++;
                    confluenceLevels.add(String.format("Daily S1: %.2f", s1));
                }
                if (Math.abs(currentPrice - r1) <= tolerance) {
                    nearbyLevels++;
                    confluenceLevels.add(String.format("Daily R1: %.2f", r1));
                }
            }

            // Check weekly pivot
            if (pivotState.getWeeklyPivot() != null) {
                double pivot = pivotState.getWeeklyPivot().getPivot();
                if (Math.abs(currentPrice - pivot) <= tolerance) {
                    nearbyLevels++;
                    confluenceLevels.add(String.format("Weekly Pivot: %.2f", pivot));
                }
            }

            // Check CPR
            String cprPosition = "OUTSIDE";
            if (pivotState.getDailyPivot() != null) {
                double tc = pivotState.getDailyPivot().getTc();
                double bc = pivotState.getDailyPivot().getBc();
                if (currentPrice >= bc && currentPrice <= tc) {
                    cprPosition = "INSIDE_CPR";
                    nearbyLevels++;
                    confluenceLevels.add("Inside Daily CPR");
                } else if (currentPrice > tc) {
                    cprPosition = "ABOVE_CPR";
                } else {
                    cprPosition = "BELOW_CPR";
                }
            }

            log.debug("{} {} Pivot analysis: price={}, nearbyLevels={}, cpr={}",
                LOG_PREFIX, scripCode, String.format("%.2f", currentPrice), nearbyLevels, cprPosition);

            return PivotConfluenceAnalysis.builder()
                .currentPrice(currentPrice)
                .confluenceLevels(confluenceLevels)
                .nearbyLevels(nearbyLevels)
                .cprPosition(cprPosition)
                .pivotState(pivotState)
                .build();
        }

        return PivotConfluenceAnalysis.builder()
            .currentPrice(currentPrice)
            .confluenceLevels(new ArrayList<>())
            .nearbyLevels(0)
            .cprPosition("NO_DATA")
            .build();
    }

    /**
     * Analyze SMC Zones.
     */
    private SMCZoneAnalysis analyzeSMCZones(String scripCode, BiasDirection direction) {
        log.debug("{} {} Analyzing SMC zones", LOG_PREFIX, scripCode);

        List<UnifiedCandle> candles = candleService.getCandleHistory(scripCode, Timeframe.M5, 100);
        if (candles == null || candles.isEmpty()) {
            return SMCZoneAnalysis.builder()
                .inOrderBlock(false)
                .nearFVG(false)
                .atLiquidityZone(false)
                .smcBias(BiasDirection.NEUTRAL)
                .build();
        }

        double currentPrice = candles.get(0).getClose();

        // Convert UnifiedCandle to CandleData for SMCAnalyzer
        List<CandleData> smcCandles = candles.stream()
            .map(c -> new CandleData(
                c.getTimestamp(),
                c.getOpen(),
                c.getHigh(),
                c.getLow(),
                c.getClose(),
                c.getVolume()
            ))
            .toList();

        SMCResult smcResult = smcAnalyzer.analyze(scripCode, "5m", smcCandles);

        boolean inOrderBlock = false;
        boolean nearFVG = false;
        boolean atLiquidity = false;
        BiasDirection smcBias = BiasDirection.NEUTRAL;

        if (smcResult != null) {
            // Check order blocks
            for (OrderBlock ob : smcResult.getOrderBlocks()) {
                if (ob.isPriceInZone(currentPrice)) {
                    inOrderBlock = true;
                    smcBias = ob.isBullish() ? BiasDirection.BULLISH : BiasDirection.BEARISH;
                    log.debug("{} {} In {} order block at {}",
                        LOG_PREFIX, scripCode, ob.getType(), String.format("%.2f", currentPrice));
                    break;
                }
            }

            // Check FVGs
            for (FairValueGap fvg : smcResult.getFairValueGaps()) {
                if (fvg.isPriceNearGap(currentPrice, 0.5)) {
                    nearFVG = true;
                    log.debug("{} {} Near {} FVG", LOG_PREFIX, scripCode, fvg.getType());
                    break;
                }
            }

            // Check liquidity zones
            for (LiquidityZone lz : smcResult.getLiquidityZones()) {
                if (Math.abs(currentPrice - lz.getLevel()) / lz.getLevel() * 100 < 0.5) {
                    atLiquidity = true;
                    log.debug("{} {} At liquidity zone: {}", LOG_PREFIX, scripCode, String.format("%.2f", lz.getLevel()));
                    break;
                }
            }
        }

        return SMCZoneAnalysis.builder()
            .inOrderBlock(inOrderBlock)
            .nearFVG(nearFVG)
            .atLiquidityZone(atLiquidity)
            .smcBias(smcBias)
            .smcResult(smcResult)
            .build();
    }

    /**
     * Calculate Risk:Reward.
     */
    private RiskRewardCalculation calculateRiskReward(String scripCode, BiasDirection direction,
                                                       PivotConfluenceAnalysis pivotAnalysis,
                                                       SMCZoneAnalysis smcAnalysis) {

        double entryPrice = pivotAnalysis.currentPrice;
        double stopLoss, target;

        MultiTimeframePivotState pivots = pivotAnalysis.pivotState;

        if (direction == BiasDirection.BULLISH) {
            // Stop below nearest support
            stopLoss = entryPrice * 0.99; // Default 1% stop
            if (pivots != null && pivots.getDailyPivot() != null) {
                double s1 = pivots.getDailyPivot().getS1();
                if (s1 < entryPrice) {
                    stopLoss = s1 * 0.998; // Just below S1
                }
            }

            // Target at nearest resistance
            target = entryPrice * 1.02; // Default 2% target
            if (pivots != null && pivots.getDailyPivot() != null) {
                double r1 = pivots.getDailyPivot().getR1();
                if (r1 > entryPrice) {
                    target = r1;
                }
            }
        } else {
            // Stop above nearest resistance
            stopLoss = entryPrice * 1.01;
            if (pivots != null && pivots.getDailyPivot() != null) {
                double r1 = pivots.getDailyPivot().getR1();
                if (r1 > entryPrice) {
                    stopLoss = r1 * 1.002;
                }
            }

            // Target at nearest support
            target = entryPrice * 0.98;
            if (pivots != null && pivots.getDailyPivot() != null) {
                double s1 = pivots.getDailyPivot().getS1();
                if (s1 < entryPrice) {
                    target = s1;
                }
            }
        }

        double risk = Math.abs(entryPrice - stopLoss);
        double reward = Math.abs(target - entryPrice);
        double riskReward = risk > 0 ? reward / risk : 0;

        return RiskRewardCalculation.builder()
            .entryPrice(entryPrice)
            .stopLoss(stopLoss)
            .target(target)
            .risk(risk)
            .reward(reward)
            .riskReward(riskReward)
            .build();
    }

    /**
     * Calculate overall trigger score.
     */
    private double calculateTriggerScore(DirectionBias htfBias, LTFConfirmation ltfConfirm,
                                          PivotConfluenceAnalysis pivotAnalysis,
                                          SMCZoneAnalysis smcAnalysis,
                                          RiskRewardCalculation rrCalc) {

        double score = 0;

        // HTF bias strength (max 30)
        score += htfBias.strength * 30;

        // LTF alignment (max 25)
        score += ltfConfirm.alignmentScore * 0.25;

        // Pivot confluence (max 20)
        score += Math.min(pivotAnalysis.nearbyLevels * 5, 20);

        // SMC zones (max 15)
        if (smcAnalysis.inOrderBlock) score += 8;
        if (smcAnalysis.nearFVG) score += 4;
        if (smcAnalysis.atLiquidityZone) score += 3;

        // R:R bonus (max 10)
        score += Math.min(rrCalc.riskReward * 3, 10);

        return Math.min(score, 100);
    }

    private String buildTriggerReason(DirectionBias htfBias, PivotConfluenceAnalysis pivotAnalysis,
                                       SMCZoneAnalysis smcAnalysis, RiskRewardCalculation rrCalc) {
        return String.format("HTF %s (%.0f%%) + %d pivot levels + SMC[OB=%s,FVG=%s] + R:R=%.1f",
            htfBias.direction, htfBias.strength * 100,
            pivotAnalysis.nearbyLevels,
            smcAnalysis.inOrderBlock, smcAnalysis.nearFVG,
            rrCalc.riskReward);
    }

    private double calculateEMA(List<UnifiedCandle> candles, int period) {
        if (candles == null || candles.size() < period) {
            return candles != null && !candles.isEmpty() ? candles.get(0).getClose() : 0;
        }
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += candles.get(i).getClose();
        }
        return sum / period;
    }

    /**
     * Get cached HTF bias for symbol.
     */
    public Optional<DirectionBias> getHTFBias(String scripCode) {
        return Optional.ofNullable(htfBiasCache.get(scripCode));
    }

    // ==================== RESULT CLASSES ====================

    @Data
    @Builder
    public static class PivotTriggerResult {
        private boolean triggered;
        private TriggerDirection direction;
        private String reason;
        private double score;
        private DirectionBias htfBias;
        private LTFConfirmation ltfConfirmation;
        private PivotConfluenceAnalysis pivotAnalysis;
        private SMCZoneAnalysis smcAnalysis;
        private RiskRewardCalculation rrCalc;
        private Instant triggerTime;

        public static PivotTriggerResult noTrigger(String reason) {
            return PivotTriggerResult.builder()
                .triggered(false)
                .direction(TriggerDirection.NONE)
                .reason(reason)
                .build();
        }
    }

    @Data
    @Builder
    public static class DirectionBias {
        private BiasDirection direction;
        private double strength;
        private double bullishScore;
        private double bearishScore;
        private String reason;
    }

    @Data
    @Builder
    public static class LTFConfirmation {
        private boolean confirmed;
        private BiasDirection direction;
        private double alignmentScore;
        private String reason;
    }

    @Data
    @Builder
    public static class PivotConfluenceAnalysis {
        private double currentPrice;
        private List<String> confluenceLevels;
        private int nearbyLevels;
        private String cprPosition;
        private MultiTimeframePivotState pivotState;
    }

    @Data
    @Builder
    public static class SMCZoneAnalysis {
        private boolean inOrderBlock;
        private boolean nearFVG;
        private boolean atLiquidityZone;
        private BiasDirection smcBias;
        private SMCResult smcResult;
    }

    @Data
    @Builder
    public static class RiskRewardCalculation {
        private double entryPrice;
        private double stopLoss;
        private double target;
        private double risk;
        private double reward;
        private double riskReward;
    }

    public enum BiasDirection {
        BULLISH, BEARISH, NEUTRAL
    }

    public enum TriggerDirection {
        BULLISH, BEARISH, NONE
    }
}
