package com.kotsin.consumer.signal.trigger;

import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.model.TimeframeBoundary;
import com.kotsin.consumer.model.MultiTimeframePivotState;
import com.kotsin.consumer.model.ConfluenceResult;
import com.kotsin.consumer.event.CandleBoundaryEvent;
import com.kotsin.consumer.mtf.analyzer.MultiTimeframeAnalyzer;
import com.kotsin.consumer.mtf.model.MultiTimeframeData;
import com.kotsin.consumer.mtf.model.MultiTimeframeData.*;
import com.kotsin.consumer.service.ATRService;
import com.kotsin.consumer.service.CandleService;
import com.kotsin.consumer.service.CompletedCandleService;
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
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
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

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ATRService atrService;

    @Autowired
    private CompletedCandleService completedCandleService;

    @Value("${pivot.confluence.enabled:true}")
    private boolean enabled;

    @Value("${pivot.confluence.kafka.topic:pivot-confluence-signals}")
    private String pivotKafkaTopic;

    @Value("${pivot.confluence.min.levels:2}")
    private int minConfluenceLevels;

    @Value("${pivot.confluence.min.rr:1.5}")
    private double minRiskReward;

    @Value("${pivot.confluence.htf.weight:0.6}")
    private double htfWeight;

    @Value("${pivot.confluence.ltf.weight:0.4}")
    private double ltfWeight;

    @Value("${pivot.confluence.tolerance.percent:0.5}")
    private double confluenceTolerancePercent;  // Default 0.5% (was 0.3% which was too tight)

    // Cache for HTF bias with TTL
    private final Map<String, CachedBias> htfBiasCache = new ConcurrentHashMap<>();

    // Cache for LTF confirmation with TTL
    private final Map<String, CachedLTFConfirmation> ltfConfirmCache = new ConcurrentHashMap<>();

    // Cache TTLs
    private static final Duration HTF_BIAS_TTL = Duration.ofHours(4);     // Recalculate every 4H
    private static final Duration LTF_CONFIRM_TTL = Duration.ofMinutes(15); // Recalculate every 15m

    /**
     * Event listener for 15m boundary - update LTF confirmation.
     */
    @EventListener
    public void on15mBoundary(CandleBoundaryEvent event) {
        if (!enabled || event.getTimeframe() != Timeframe.M15) {
            return;
        }
        String scripCode = event.getScripCode();
        log.debug("{} {} 15m boundary crossed - updating LTF confirmation", LOG_PREFIX, scripCode);
        // Invalidate LTF cache to trigger recalculation on next check
        ltfConfirmCache.remove(scripCode);
    }

    /**
     * Event listener for 4H boundary - update HTF bias.
     */
    @EventListener
    public void on4hBoundary(CandleBoundaryEvent event) {
        if (!enabled || event.getTimeframe() != Timeframe.H4) {
            return;
        }
        String scripCode = event.getScripCode();
        log.info("{} {} 4H boundary crossed - recalculating HTF bias", LOG_PREFIX, scripCode);
        // Invalidate HTF cache to trigger recalculation on next check
        htfBiasCache.remove(scripCode);
    }

    /**
     * Event listener for Daily boundary - recalculate everything.
     */
    @EventListener
    public void onDailyBoundary(CandleBoundaryEvent event) {
        if (!enabled || event.getTimeframe() != Timeframe.D1) {
            return;
        }
        String scripCode = event.getScripCode();
        log.info("{} {} Daily boundary crossed - clearing all caches", LOG_PREFIX, scripCode);
        htfBiasCache.remove(scripCode);
        ltfConfirmCache.remove(scripCode);
    }

    /**
     * Cached bias wrapper with timestamp.
     */
    @Data
    @Builder
    private static class CachedBias {
        private DirectionBias bias;
        private Instant calculatedAt;
    }

    /**
     * Cached LTF confirmation wrapper.
     */
    @Data
    @Builder
    private static class CachedLTFConfirmation {
        private LTFConfirmation confirmation;
        private Instant calculatedAt;
    }

    /**
     * Check if Pivot Confluence signal should trigger.
     */
    public PivotTriggerResult checkTrigger(String scripCode, String exch, String exchType) {
        if (!enabled) {
            return PivotTriggerResult.noTrigger("Pivot confluence trigger disabled");
        }

        try {
            log.info("{} {} Starting pivot confluence analysis", LOG_PREFIX, scripCode);

            // Step 1: Determine HTF Bias (Daily, 4H) - USE CACHED IF VALID
            DirectionBias htfBias = getCachedOrComputeHTFBias(scripCode);
            log.info("{} {} HTF Bias: direction={}, strength={}, reason={}",
                LOG_PREFIX, scripCode, htfBias.direction,
                String.format("%.2f", htfBias.strength), htfBias.reason);

            if (htfBias.direction == BiasDirection.NEUTRAL) {
                return PivotTriggerResult.noTrigger("HTF bias is NEUTRAL - no clear direction");
            }

            // Step 2: Analyze LTF for confirmation (15m, 5m) - USE CACHED IF VALID
            LTFConfirmation ltfConfirm = getCachedOrComputeLTFConfirmation(scripCode, htfBias.direction);
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

            // Build result
            PivotTriggerResult result = PivotTriggerResult.builder()
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

            // Publish to Kafka
            publishToKafka(scripCode, result);

            return result;

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

        // Cache with timestamp
        htfBiasCache.put(scripCode, CachedBias.builder()
            .bias(bias)
            .calculatedAt(Instant.now())
            .build());
        return bias;
    }

    /**
     * Get cached HTF bias or compute fresh if expired.
     */
    private DirectionBias getCachedOrComputeHTFBias(String scripCode) {
        CachedBias cached = htfBiasCache.get(scripCode);
        if (cached != null) {
            Duration age = Duration.between(cached.getCalculatedAt(), Instant.now());
            if (age.compareTo(HTF_BIAS_TTL) < 0) {
                log.debug("{} {} Using cached HTF bias (age: {}m)", LOG_PREFIX, scripCode, age.toMinutes());
                return cached.getBias();
            }
        }
        return analyzeHTFBias(scripCode);
    }

    /**
     * Get cached LTF confirmation or compute fresh if expired.
     */
    private LTFConfirmation getCachedOrComputeLTFConfirmation(String scripCode, BiasDirection htfDirection) {
        CachedLTFConfirmation cached = ltfConfirmCache.get(scripCode);
        if (cached != null && cached.getConfirmation().getDirection() == htfDirection) {
            Duration age = Duration.between(cached.getCalculatedAt(), Instant.now());
            if (age.compareTo(LTF_CONFIRM_TTL) < 0) {
                log.debug("{} {} Using cached LTF confirmation (age: {}m)", LOG_PREFIX, scripCode, age.toMinutes());
                return cached.getConfirmation();
            }
        }
        LTFConfirmation confirmation = analyzeLTFConfirmation(scripCode, htfDirection);
        ltfConfirmCache.put(scripCode, CachedLTFConfirmation.builder()
            .confirmation(confirmation)
            .calculatedAt(Instant.now())
            .build());
        return confirmation;
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
        double tolerance = currentPrice * (confluenceTolerancePercent / 100.0); // Configurable tolerance (default 0.5%)

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
     * Calculate Risk:Reward using proper pivot-to-pivot distances.
     *
     * IMPROVED: Uses actual pivot levels for stop/target with ATR validation.
     * Stop is placed below nearest support (LONG) or above nearest resistance (SHORT).
     * Target is the next pivot level that provides at least 1.5R.
     */
    private RiskRewardCalculation calculateRiskReward(String scripCode, BiasDirection direction,
                                                       PivotConfluenceAnalysis pivotAnalysis,
                                                       SMCZoneAnalysis smcAnalysis) {

        double entryPrice = pivotAnalysis.currentPrice;
        boolean isBullish = direction == BiasDirection.BULLISH;
        MultiTimeframePivotState pivots = pivotAnalysis.pivotState;

        // Get ATR for minimum stop distance validation
        ATRService.RiskRewardResult atrResult = atrService.calculateRiskReward(
            scripCode, Timeframe.M15, entryPrice, isBullish, 1.5, 2.0);
        double atr = atrResult != null ? atrResult.getAtr() : entryPrice * 0.015; // Fallback 1.5%
        double minStopDistance = atr * 1.5; // Minimum 1.5x ATR to avoid whipsaw

        double stopLoss = 0;
        double target = 0;
        String stopSource = "DEFAULT";
        String targetSource = "DEFAULT";

        // ==================== STOP LOSS: Use pivot levels ====================
        if (pivots != null) {
            if (isBullish) {
                // LONG: Stop below nearest support pivot
                double bestSupport = findBestPivotSupport(entryPrice, pivots, minStopDistance);
                if (bestSupport > 0) {
                    stopLoss = bestSupport * 0.998; // Slightly below
                    stopSource = "PIVOT_SUPPORT";
                }
            } else {
                // SHORT: Stop above nearest resistance pivot
                double bestResistance = findBestPivotResistance(entryPrice, pivots, minStopDistance);
                if (bestResistance > 0) {
                    stopLoss = bestResistance * 1.002; // Slightly above
                    stopSource = "PIVOT_RESISTANCE";
                }
            }
        }

        // Fallback to ATR-based if no pivot found
        if (stopLoss == 0) {
            stopLoss = isBullish ? entryPrice - (atr * 2.0) : entryPrice + (atr * 2.0);
            stopSource = "ATR_BASED";
        }

        // Validate stop is not too tight
        double actualStopDistance = Math.abs(entryPrice - stopLoss);
        if (actualStopDistance < atr) {
            stopLoss = isBullish ? entryPrice - (atr * 1.5) : entryPrice + (atr * 1.5);
            stopSource = "ATR_ADJUSTED";
            actualStopDistance = atr * 1.5;
        }

        // ==================== TARGET: Use pivot levels ====================
        if (pivots != null) {
            if (isBullish) {
                target = findBestPivotTarget(entryPrice, pivots, actualStopDistance, true);
                if (target > 0) targetSource = "PIVOT_RESISTANCE";
            } else {
                target = findBestPivotTarget(entryPrice, pivots, actualStopDistance, false);
                if (target > 0) targetSource = "PIVOT_SUPPORT";
            }
        }

        // Fallback to R-multiple if no valid pivot target
        if (target == 0) {
            target = isBullish ? entryPrice + (actualStopDistance * 2) : entryPrice - (actualStopDistance * 2);
            targetSource = "R_MULTIPLE";
        }

        double risk = Math.abs(entryPrice - stopLoss);
        double reward = Math.abs(target - entryPrice);
        double riskReward = risk > 0 ? reward / risk : 0;

        log.info("{} {} Pivot-based R:R: stop={} ({}), target={} ({}), R:R={:.2f}, ATR={:.2f}, Risk%={:.2f}%",
            LOG_PREFIX, scripCode,
            String.format("%.2f", stopLoss), stopSource,
            String.format("%.2f", target), targetSource,
            riskReward, atr, (risk / entryPrice) * 100);

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
     * Find the best support pivot for stop placement.
     */
    private double findBestPivotSupport(double price, MultiTimeframePivotState pivots, double minDistance) {
        List<Double> supports = new ArrayList<>();

        // Daily pivots
        if (pivots.getDailyPivot() != null) {
            addIfValid(supports, pivots.getDailyPivot().getS1(), price);
            addIfValid(supports, pivots.getDailyPivot().getS2(), price);
            addIfValid(supports, pivots.getDailyPivot().getS3(), price);
            addIfValid(supports, pivots.getDailyPivot().getBc(), price);
            addIfValid(supports, pivots.getDailyPivot().getCamS3(), price);
        }

        // Weekly pivots (stronger levels)
        if (pivots.getWeeklyPivot() != null) {
            addIfValid(supports, pivots.getWeeklyPivot().getS1(), price);
            addIfValid(supports, pivots.getWeeklyPivot().getPivot(), price);
        }

        // Monthly pivots (strongest levels)
        if (pivots.getMonthlyPivot() != null) {
            addIfValid(supports, pivots.getMonthlyPivot().getS1(), price);
        }

        // Sort descending (nearest first)
        supports.sort((a, b) -> Double.compare(b, a));

        // Find support with appropriate distance
        for (double s : supports) {
            double distance = price - s;
            if (distance >= minDistance && distance <= price * 0.05) { // Max 5%
                return s;
            }
        }

        // If none found, return nearest if reasonable
        if (!supports.isEmpty() && (price - supports.get(0)) >= minDistance * 0.5) {
            return supports.get(0);
        }

        return 0;
    }

    /**
     * Find the best resistance pivot for stop placement (SHORT trades).
     */
    private double findBestPivotResistance(double price, MultiTimeframePivotState pivots, double minDistance) {
        List<Double> resistances = new ArrayList<>();

        if (pivots.getDailyPivot() != null) {
            addIfValidResistance(resistances, pivots.getDailyPivot().getR1(), price);
            addIfValidResistance(resistances, pivots.getDailyPivot().getR2(), price);
            addIfValidResistance(resistances, pivots.getDailyPivot().getR3(), price);
            addIfValidResistance(resistances, pivots.getDailyPivot().getTc(), price);
            addIfValidResistance(resistances, pivots.getDailyPivot().getCamR3(), price);
        }

        if (pivots.getWeeklyPivot() != null) {
            addIfValidResistance(resistances, pivots.getWeeklyPivot().getR1(), price);
            addIfValidResistance(resistances, pivots.getWeeklyPivot().getPivot(), price);
        }

        if (pivots.getMonthlyPivot() != null) {
            addIfValidResistance(resistances, pivots.getMonthlyPivot().getR1(), price);
        }

        // Sort ascending (nearest first)
        resistances.sort(Double::compare);

        for (double r : resistances) {
            double distance = r - price;
            if (distance >= minDistance && distance <= price * 0.05) {
                return r;
            }
        }

        if (!resistances.isEmpty() && (resistances.get(0) - price) >= minDistance * 0.5) {
            return resistances.get(0);
        }

        return 0;
    }

    /**
     * Find the best pivot target that provides at least 1.5R.
     */
    private double findBestPivotTarget(double price, MultiTimeframePivotState pivots, double risk, boolean isBullish) {
        List<Double> levels = new ArrayList<>();
        double minReward = risk * 1.5;

        if (isBullish) {
            // LONG: Target at resistance
            if (pivots.getDailyPivot() != null) {
                addIfValidResistance(levels, pivots.getDailyPivot().getR1(), price);
                addIfValidResistance(levels, pivots.getDailyPivot().getR2(), price);
                addIfValidResistance(levels, pivots.getDailyPivot().getR3(), price);
                addIfValidResistance(levels, pivots.getDailyPivot().getTc(), price);
            }
            if (pivots.getWeeklyPivot() != null) {
                addIfValidResistance(levels, pivots.getWeeklyPivot().getR1(), price);
                addIfValidResistance(levels, pivots.getWeeklyPivot().getR2(), price);
            }
            levels.sort(Double::compare);

            for (double r : levels) {
                if ((r - price) >= minReward) {
                    return r;
                }
            }
        } else {
            // SHORT: Target at support
            if (pivots.getDailyPivot() != null) {
                addIfValid(levels, pivots.getDailyPivot().getS1(), price);
                addIfValid(levels, pivots.getDailyPivot().getS2(), price);
                addIfValid(levels, pivots.getDailyPivot().getS3(), price);
                addIfValid(levels, pivots.getDailyPivot().getBc(), price);
            }
            if (pivots.getWeeklyPivot() != null) {
                addIfValid(levels, pivots.getWeeklyPivot().getS1(), price);
                addIfValid(levels, pivots.getWeeklyPivot().getS2(), price);
            }
            levels.sort((a, b) -> Double.compare(b, a));

            for (double s : levels) {
                if ((price - s) >= minReward) {
                    return s;
                }
            }
        }

        return 0;
    }

    private void addIfValid(List<Double> list, double level, double price) {
        if (level > 0 && level < price) {
            list.add(level);
        }
    }

    private void addIfValidResistance(List<Double> list, double level, double price) {
        if (level > 0 && level > price) {
            list.add(level);
        }
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

    /**
     * Publish Pivot Confluence trigger result to Kafka.
     */
    private void publishToKafka(String scripCode, PivotTriggerResult result) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("scripCode", scripCode);
            payload.put("triggered", result.isTriggered());
            payload.put("direction", result.getDirection() != null ? result.getDirection().name() : null);
            payload.put("reason", result.getReason());
            payload.put("score", result.getScore());
            payload.put("triggerTime", result.getTriggerTime() != null ? result.getTriggerTime().toString() : null);

            // HTF Bias details
            if (result.getHtfBias() != null) {
                DirectionBias htf = result.getHtfBias();
                payload.put("htfDirection", htf.getDirection() != null ? htf.getDirection().name() : null);
                payload.put("htfStrength", htf.getStrength());
                payload.put("htfBullishScore", htf.getBullishScore());
                payload.put("htfBearishScore", htf.getBearishScore());
                payload.put("htfReason", htf.getReason());
            }

            // LTF Confirmation
            if (result.getLtfConfirmation() != null) {
                LTFConfirmation ltf = result.getLtfConfirmation();
                payload.put("ltfConfirmed", ltf.isConfirmed());
                payload.put("ltfAlignmentScore", ltf.getAlignmentScore());
                payload.put("ltfReason", ltf.getReason());
            }

            // Pivot Analysis
            if (result.getPivotAnalysis() != null) {
                PivotConfluenceAnalysis pivot = result.getPivotAnalysis();
                payload.put("pivotCurrentPrice", pivot.getCurrentPrice());
                payload.put("pivotConfluenceLevels", pivot.getConfluenceLevels());
                payload.put("pivotNearbyLevels", pivot.getNearbyLevels());
                payload.put("cprPosition", pivot.getCprPosition());
            }

            // SMC Analysis
            if (result.getSmcAnalysis() != null) {
                SMCZoneAnalysis smc = result.getSmcAnalysis();
                payload.put("smcInOrderBlock", smc.isInOrderBlock());
                payload.put("smcNearFVG", smc.isNearFVG());
                payload.put("smcAtLiquidityZone", smc.isAtLiquidityZone());
                payload.put("smcBias", smc.getSmcBias() != null ? smc.getSmcBias().name() : null);
            }

            // Risk:Reward
            if (result.getRrCalc() != null) {
                RiskRewardCalculation rr = result.getRrCalc();
                payload.put("entryPrice", rr.getEntryPrice());
                payload.put("stopLoss", rr.getStopLoss());
                payload.put("target", rr.getTarget());
                payload.put("risk", rr.getRisk());
                payload.put("reward", rr.getReward());
                payload.put("riskReward", rr.getRiskReward());
            }

            payload.put("timestamp", System.currentTimeMillis());

            kafkaTemplate.send(pivotKafkaTopic, scripCode, payload);
            log.info("{} {} Published Pivot Confluence trigger to Kafka topic: {}", LOG_PREFIX, scripCode, pivotKafkaTopic);

        } catch (Exception e) {
            log.error("{} {} Failed to publish to Kafka: {}", LOG_PREFIX, scripCode, e.getMessage());
        }
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
        CachedBias cached = htfBiasCache.get(scripCode);
        return cached != null ? Optional.of(cached.getBias()) : Optional.empty();
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
