package com.kotsin.consumer.enrichment.signal.confirmation;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.analyzer.FamilyContextAnalyzer.FamilyBias;
import com.kotsin.consumer.enrichment.analyzer.FamilyContextAnalyzer.FamilyContext;
import com.kotsin.consumer.enrichment.model.DetectedEvent;
import com.kotsin.consumer.enrichment.model.SessionStructure;
import com.kotsin.consumer.enrichment.model.TechnicalContext;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * PriceActionConfirmer - Validates that price action aligns with signal direction
 *
 * PHILOSOPHY:
 * A good R:R alone is not enough. The PRICE ACTION must confirm the direction.
 * This prevents taking trades where R:R looks good but market structure disagrees.
 *
 * CONFIRMATION CHECKS (Scored 0-100):
 * 1. Retest Validation (0-20): Price tested and held support/resistance
 * 2. Candle Pattern (0-15): Reversal/continuation patterns align with direction
 * 3. OFI/Volume Delta (0-20): Order flow confirms direction
 * 4. Options Flow (0-15): OI buildup/unwinding supports direction
 * 5. Session Structure (0-10): Not at dangerous session extremes
 * 6. Multi-TF Confluence (0-10): Multiple timeframes agree
 * 7. SuperTrend/BB (0-10): Technical indicators align
 *
 * MINIMUM SCORE TO PROMOTE: 60/100
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceActionConfirmer {

    @Value("${confirmation.enabled:true}")
    private boolean enabled;

    @Value("${confirmation.min-score:60}")
    private int minScore;

    // Check weights (must sum to 100)
    private static final int WEIGHT_RETEST = 20;
    private static final int WEIGHT_CANDLE_PATTERN = 15;
    private static final int WEIGHT_OFI = 20;
    private static final int WEIGHT_OPTIONS = 15;
    private static final int WEIGHT_SESSION = 10;
    private static final int WEIGHT_MTF = 10;
    private static final int WEIGHT_TECHNICAL = 10;

    /**
     * Confirm if price action aligns with signal direction
     *
     * @param signal The trading signal to confirm
     * @param candle Current FamilyCandle with all market data
     * @param quantScore EnrichedQuantScore with technical analysis
     * @return ConfirmationResult with score and detailed breakdown
     */
    public ConfirmationResult confirm(TradingSignal signal, FamilyCandle candle, EnrichedQuantScore quantScore) {
        if (!enabled) {
            return ConfirmationResult.builder()
                    .confirmed(true)
                    .score(100)
                    .recommendation(Recommendation.PROMOTE)
                    .note("Confirmation disabled")
                    .build();
        }

        boolean isLong = signal.isLong();
        String familyId = signal.getFamilyId();
        List<String> passedChecks = new ArrayList<>();
        List<String> failedChecks = new ArrayList<>();
        int totalScore = 0;

        // === CHECK 1: RETEST VALIDATION (0-20 points) ===
        int retestScore = checkRetestValidation(signal, candle, quantScore, isLong, passedChecks, failedChecks);
        totalScore += retestScore;

        // === CHECK 2: CANDLE PATTERN ALIGNMENT (0-15 points) ===
        int candleScore = checkCandlePattern(candle, quantScore, isLong, passedChecks, failedChecks);
        totalScore += candleScore;

        // === CHECK 3: OFI/VOLUME DELTA ALIGNMENT (0-20 points) ===
        int ofiScore = checkOfiAlignment(candle, quantScore, isLong, passedChecks, failedChecks);
        totalScore += ofiScore;

        // === CHECK 4: OPTIONS FLOW CONFIRMATION (0-15 points) ===
        int optionsScore = checkOptionsFlow(candle, quantScore, isLong, passedChecks, failedChecks);
        totalScore += optionsScore;

        // === CHECK 5: SESSION STRUCTURE CHECK (0-10 points) ===
        int sessionScore = checkSessionStructure(candle, quantScore, isLong, passedChecks, failedChecks);
        totalScore += sessionScore;

        // === CHECK 6: MULTI-TIMEFRAME CONFLUENCE (0-10 points) ===
        int mtfScore = checkMtfConfluence(candle, quantScore, isLong, passedChecks, failedChecks);
        totalScore += mtfScore;

        // === CHECK 7: SUPERTREND/BB ALIGNMENT (0-10 points) ===
        int technicalScore = checkTechnicalAlignment(candle, quantScore, isLong, passedChecks, failedChecks);
        totalScore += technicalScore;

        // === DETERMINE RECOMMENDATION ===
        Recommendation recommendation;
        boolean confirmed;

        if (totalScore >= minScore) {
            confirmed = true;
            recommendation = Recommendation.PROMOTE;
        } else if (totalScore >= minScore - 15) {
            // Close to threshold - wait for more confirmation
            confirmed = false;
            recommendation = Recommendation.WAIT;
        } else if (hasContradictorySignals(passedChecks, failedChecks, isLong)) {
            // Strong contradictory signals - invalidate
            confirmed = false;
            recommendation = Recommendation.INVALIDATE;
        } else {
            confirmed = false;
            recommendation = Recommendation.WAIT;
        }

        String note = buildConfirmationNote(totalScore, passedChecks, failedChecks, recommendation);

        // Enhanced logging with individual check scores
        log.info("[CONFIRM] {} {} | score={}/{} | {} | RETEST={}/{} CANDLE={}/{} OFI={}/{} OPTIONS={}/{} SESSION={}/{} MTF={}/{} TECH={}/{}",
                familyId, isLong ? "LONG" : "SHORT",
                totalScore, minScore, recommendation,
                retestScore, WEIGHT_RETEST,
                candleScore, WEIGHT_CANDLE_PATTERN,
                ofiScore, WEIGHT_OFI,
                optionsScore, WEIGHT_OPTIONS,
                sessionScore, WEIGHT_SESSION,
                mtfScore, WEIGHT_MTF,
                technicalScore, WEIGHT_TECHNICAL);

        // Debug: Log passed checks with details
        if (!passedChecks.isEmpty()) {
            log.debug("[CONFIRM] {} PASSED ({}): {}", familyId, passedChecks.size(), String.join(", ", passedChecks));
        }

        // Debug: Log failed checks with details
        if (!failedChecks.isEmpty()) {
            log.debug("[CONFIRM] {} FAILED ({}): {}", familyId, failedChecks.size(), String.join(", ", failedChecks));
        }

        // Log invalidation reasons at warn level
        if (recommendation == Recommendation.INVALIDATE) {
            log.warn("[CONFIRM] {} {} INVALIDATED | score={}/{} | contradictory signals detected: {}",
                    familyId, isLong ? "LONG" : "SHORT", totalScore, minScore,
                    String.join(", ", failedChecks));
        }

        return ConfirmationResult.builder()
                .confirmed(confirmed)
                .score(totalScore)
                .minScore(minScore)
                .passedChecks(passedChecks)
                .failedChecks(failedChecks)
                .recommendation(recommendation)
                .note(note)
                // Individual scores for analysis
                .retestScore(retestScore)
                .candleScore(candleScore)
                .ofiScore(ofiScore)
                .optionsScore(optionsScore)
                .sessionScore(sessionScore)
                .mtfScore(mtfScore)
                .technicalScore(technicalScore)
                .build();
    }

    // ======================== CHECK IMPLEMENTATIONS ========================

    /**
     * CHECK 1: Retest Validation (0-20 points)
     * LONG: Price pulled back to support and held
     * SHORT: Price rallied to resistance and rejected
     */
    private int checkRetestValidation(TradingSignal signal, FamilyCandle candle,
                                       EnrichedQuantScore quantScore, boolean isLong,
                                       List<String> passed, List<String> failed) {
        int score = 0;

        // Check if price is at support/resistance
        if (quantScore != null) {
            Double nearestSupport = quantScore.getNearestSupportPrice();
            Double nearestResistance = quantScore.getNearestResistancePrice();
            double currentPrice = signal.getCurrentPrice();

            if (isLong) {
                // For LONG: Want price near support (pulled back)
                if (nearestSupport != null && nearestSupport > 0) {
                    double distanceToSupport = Math.abs(currentPrice - nearestSupport) / currentPrice * 100;
                    if (distanceToSupport < 0.5) {
                        score += 15;
                        passed.add("RETEST:AT_SUPPORT(" + String.format("%.2f", distanceToSupport) + "%)");
                    } else if (distanceToSupport < 1.0) {
                        score += 10;
                        passed.add("RETEST:NEAR_SUPPORT(" + String.format("%.2f", distanceToSupport) + "%)");
                    } else if (distanceToSupport < 2.0) {
                        score += 5;
                        passed.add("RETEST:APPROACHING_SUPPORT");
                    }
                }

                // Check if at confluence zone (multiple levels)
                if (quantScore.isInConfluenceZone() && quantScore.isAtSupport()) {
                    score += 5;
                    passed.add("RETEST:CONFLUENCE_SUPPORT");
                }
            } else {
                // For SHORT: Want price near resistance (rallied up)
                if (nearestResistance != null && nearestResistance > 0) {
                    double distanceToResistance = Math.abs(currentPrice - nearestResistance) / currentPrice * 100;
                    if (distanceToResistance < 0.5) {
                        score += 15;
                        passed.add("RETEST:AT_RESISTANCE(" + String.format("%.2f", distanceToResistance) + "%)");
                    } else if (distanceToResistance < 1.0) {
                        score += 10;
                        passed.add("RETEST:NEAR_RESISTANCE(" + String.format("%.2f", distanceToResistance) + "%)");
                    } else if (distanceToResistance < 2.0) {
                        score += 5;
                        passed.add("RETEST:APPROACHING_RESISTANCE");
                    }
                }

                // Check if at confluence zone
                if (quantScore.isInConfluenceZone() && quantScore.isAtResistance()) {
                    score += 5;
                    passed.add("RETEST:CONFLUENCE_RESISTANCE");
                }
            }
        }

        if (score == 0) {
            failed.add("RETEST:NO_LEVEL_TEST");
        }

        return Math.min(score, WEIGHT_RETEST);
    }

    /**
     * CHECK 2: Candle Pattern Alignment (0-15 points)
     * Check reversal score and pattern signals from FamilyCandle
     */
    private int checkCandlePattern(FamilyCandle candle, EnrichedQuantScore quantScore,
                                    boolean isLong, List<String> passed, List<String> failed) {
        int score = 0;

        if (candle == null) {
            failed.add("CANDLE:NO_DATA");
            return 0;
        }

        // Check reversal score from FamilyCandle
        Double reversalScore = candle.getReversalScore();
        if (reversalScore != null && reversalScore >= 6) {
            // High confidence reversal - good for counter-trend entries
            score += 10;
            passed.add("CANDLE:HIGH_REVERSAL_SCORE(" + String.format("%.1f", reversalScore) + ")");
        } else if (reversalScore != null && reversalScore >= 4) {
            score += 5;
            passed.add("CANDLE:MED_REVERSAL_SCORE(" + String.format("%.1f", reversalScore) + ")");
        }

        // Check if showing reversal pattern
        if (candle.isEquityShowingReversal()) {
            String reversalType = candle.getReversalType();
            // Verify reversal direction matches signal direction
            if (reversalType != null) {
                boolean bullishReversal = reversalType.contains("MORNING") || reversalType.contains("HAMMER") ||
                        reversalType.contains("BULLISH") || reversalType.contains("CALL");
                boolean bearishReversal = reversalType.contains("EVENING") || reversalType.contains("SHOOTING") ||
                        reversalType.contains("BEARISH") || reversalType.contains("PUT");

                if ((isLong && bullishReversal) || (!isLong && bearishReversal)) {
                    score += 5;
                    passed.add("CANDLE:PATTERN_ALIGNED(" + reversalType + ")");
                } else if ((isLong && bearishReversal) || (!isLong && bullishReversal)) {
                    // Pattern contradicts direction
                    failed.add("CANDLE:PATTERN_CONTRADICTS(" + reversalType + ")");
                }
            }
        }

        // Check high confidence reversal flag
        if (candle.isHighConfidenceReversal()) {
            score += 5;
            passed.add("CANDLE:HIGH_CONF_REVERSAL");
        }

        if (score == 0) {
            failed.add("CANDLE:NO_PATTERN");
        }

        return Math.min(score, WEIGHT_CANDLE_PATTERN);
    }

    /**
     * CHECK 3: OFI/Volume Delta Alignment (0-20 points)
     * LONG: OFI positive, buying pressure, no selling exhaustion ignored
     * SHORT: OFI negative, selling pressure, no buying exhaustion ignored
     */
    private int checkOfiAlignment(FamilyCandle candle, EnrichedQuantScore quantScore,
                                   boolean isLong, List<String> passed, List<String> failed) {
        int score = 0;

        if (candle == null) {
            failed.add("OFI:NO_DATA");
            return 0;
        }

        Double ofiVelocity = candle.getOfiVelocity();
        Double ofiAcceleration = candle.getOfiAcceleration();
        String exhaustionType = candle.getExhaustionType();
        boolean exhaustionDetected = candle.isExhaustionDetected();

        // Check OFI velocity alignment
        if (ofiVelocity != null) {
            if (isLong && ofiVelocity > 0) {
                score += 10;
                passed.add("OFI:POSITIVE_VELOCITY(" + String.format("%.2f", ofiVelocity) + ")");
            } else if (!isLong && ofiVelocity < 0) {
                score += 10;
                passed.add("OFI:NEGATIVE_VELOCITY(" + String.format("%.2f", ofiVelocity) + ")");
            } else if (isLong && ofiVelocity < -0.5) {
                // Strong negative OFI for LONG = contradictory
                failed.add("OFI:NEGATIVE_FOR_LONG(" + String.format("%.2f", ofiVelocity) + ")");
            } else if (!isLong && ofiVelocity > 0.5) {
                // Strong positive OFI for SHORT = contradictory
                failed.add("OFI:POSITIVE_FOR_SHORT(" + String.format("%.2f", ofiVelocity) + ")");
            }
        }

        // Check OFI acceleration (momentum building)
        if (ofiAcceleration != null) {
            if (isLong && ofiAcceleration > 0) {
                score += 5;
                passed.add("OFI:ACCELERATING_BUYING");
            } else if (!isLong && ofiAcceleration < 0) {
                score += 5;
                passed.add("OFI:ACCELERATING_SELLING");
            }
        }

        // Check exhaustion - this is GOOD for reversal setups
        if (exhaustionDetected && exhaustionType != null) {
            if (isLong && "SELLING_EXHAUSTION".equals(exhaustionType)) {
                score += 5;
                passed.add("OFI:SELLING_EXHAUSTION_DETECTED");
            } else if (!isLong && "BUYING_EXHAUSTION".equals(exhaustionType)) {
                score += 5;
                passed.add("OFI:BUYING_EXHAUSTION_DETECTED");
            } else if (isLong && "BUYING_EXHAUSTION".equals(exhaustionType)) {
                // Buying exhaustion when going LONG = bad
                failed.add("OFI:BUYING_EXHAUSTION_CONTRA_LONG");
            } else if (!isLong && "SELLING_EXHAUSTION".equals(exhaustionType)) {
                // Selling exhaustion when going SHORT = bad
                failed.add("OFI:SELLING_EXHAUSTION_CONTRA_SHORT");
            }
        }

        // Check delta divergence
        if (candle.isDeltaDivergenceDetected()) {
            String divergenceType = candle.getDeltaDivergenceType();
            if (isLong && "BULLISH_DIVERGENCE".equals(divergenceType)) {
                score += 5;
                passed.add("OFI:BULLISH_DIVERGENCE");
            } else if (!isLong && "BEARISH_DIVERGENCE".equals(divergenceType)) {
                score += 5;
                passed.add("OFI:BEARISH_DIVERGENCE");
            }
        }

        if (score == 0) {
            failed.add("OFI:NO_ALIGNMENT");
        }

        return Math.min(score, WEIGHT_OFI);
    }

    /**
     * CHECK 4: Options Flow Confirmation (0-15 points)
     * LONG: Call OI building, Put unwinding, PCR declining
     * SHORT: Put OI building, Call unwinding, PCR rising
     */
    private int checkOptionsFlow(FamilyCandle candle, EnrichedQuantScore quantScore,
                                  boolean isLong, List<String> passed, List<String> failed) {
        int score = 0;

        if (candle == null) {
            failed.add("OPTIONS:NO_DATA");
            return 0;
        }

        // Check OI buildup patterns
        String oiInterpretation = candle.getOiInterpretation();
        Double oiConfidence = candle.getOiInterpretationConfidence();

        if (oiInterpretation != null && oiConfidence != null && oiConfidence > 0.5) {
            if (isLong) {
                // For LONG: LONG_BUILDUP or SHORT_COVERING is good
                if ("LONG_BUILDUP".equals(oiInterpretation)) {
                    score += 10;
                    passed.add("OPTIONS:LONG_BUILDUP(conf=" + String.format("%.2f", oiConfidence) + ")");
                } else if ("SHORT_COVERING".equals(oiInterpretation)) {
                    score += 8;
                    passed.add("OPTIONS:SHORT_COVERING");
                } else if ("SHORT_BUILDUP".equals(oiInterpretation)) {
                    // SHORT_BUILDUP when going LONG = contradictory
                    failed.add("OPTIONS:SHORT_BUILDUP_CONTRA_LONG");
                }
            } else {
                // For SHORT: SHORT_BUILDUP or LONG_UNWINDING is good
                if ("SHORT_BUILDUP".equals(oiInterpretation)) {
                    score += 10;
                    passed.add("OPTIONS:SHORT_BUILDUP(conf=" + String.format("%.2f", oiConfidence) + ")");
                } else if ("LONG_UNWINDING".equals(oiInterpretation)) {
                    score += 8;
                    passed.add("OPTIONS:LONG_UNWINDING");
                } else if ("LONG_BUILDUP".equals(oiInterpretation)) {
                    // LONG_BUILDUP when going SHORT = contradictory
                    failed.add("OPTIONS:LONG_BUILDUP_CONTRA_SHORT");
                }
            }
        }

        // Check call/put OI buildup flags (using available fields)
        if (isLong) {
            if (candle.isCallOiBuildingUp()) {
                score += 3;
                passed.add("OPTIONS:CALL_OI_BUILDING");
            }
            if (candle.isPutOiUnwinding()) {
                score += 2;
                passed.add("OPTIONS:PUT_OI_UNWINDING");
            }
        } else {
            // For SHORT: Check if call OI is NOT building (inverse of bullish signal)
            // and put OI is NOT unwinding (puts being held/built)
            if (!candle.isCallOiBuildingUp() && !candle.isPutOiUnwinding()) {
                score += 3;
                passed.add("OPTIONS:BEARISH_OI_PATTERN");
            }
        }

        // Check options flow confirms reversal
        if (candle.isOptionsFlowConfirmsReversal()) {
            score += 3;
            passed.add("OPTIONS:FLOW_CONFIRMS_REVERSAL");
        }

        // Check short squeeze setup (good for LONG)
        if (isLong && candle.isShortSqueezeDetected()) {
            score += 5;
            passed.add("OPTIONS:SHORT_SQUEEZE_SETUP");
        }

        // Check if OI suggests reversal
        if (candle.isOiSuggestsReversal()) {
            score += 2;
            passed.add("OPTIONS:OI_SUGGESTS_REVERSAL");
        }

        if (score == 0) {
            failed.add("OPTIONS:NO_CONFIRMATION");
        }

        return Math.min(score, WEIGHT_OPTIONS);
    }

    /**
     * CHECK 5: Session Structure Check (0-10 points)
     * LONG: Not at session high (avoid buying at top)
     * SHORT: Not at session low (avoid selling at bottom)
     */
    private int checkSessionStructure(FamilyCandle candle, EnrichedQuantScore quantScore,
                                       boolean isLong, List<String> passed, List<String> failed) {
        int score = 0;

        SessionStructure session = quantScore != null ? quantScore.getSessionStructure() : null;
        if (session == null) {
            // No session data - give partial score
            score = 5;
            passed.add("SESSION:NO_DATA_PARTIAL");
            return score;
        }

        double positionInRange = session.getPositionInRange();

        if (isLong) {
            // For LONG: Good to be at lower half, bad to be at top
            if (positionInRange < 0.30) {
                score += 10;
                passed.add("SESSION:LONG_AT_LOW(" + String.format("%.0f", positionInRange * 100) + "%)");
            } else if (positionInRange < 0.50) {
                score += 7;
                passed.add("SESSION:LONG_LOWER_HALF(" + String.format("%.0f", positionInRange * 100) + "%)");
            } else if (positionInRange < 0.70) {
                score += 4;
                passed.add("SESSION:LONG_MIDDLE(" + String.format("%.0f", positionInRange * 100) + "%)");
            } else if (positionInRange > 0.85) {
                // LONG at session high = dangerous
                failed.add("SESSION:LONG_AT_HIGH(" + String.format("%.0f", positionInRange * 100) + "%)");
            }

            // Bonus for failed breakdown (shorts trapped at support)
            if (session.getFailedBreakdownCount() > 0) {
                score += 3;
                passed.add("SESSION:FAILED_BREAKDOWN_BULLISH");
            }
        } else {
            // For SHORT: Good to be at upper half, bad to be at bottom
            if (positionInRange > 0.70) {
                score += 10;
                passed.add("SESSION:SHORT_AT_HIGH(" + String.format("%.0f", positionInRange * 100) + "%)");
            } else if (positionInRange > 0.50) {
                score += 7;
                passed.add("SESSION:SHORT_UPPER_HALF(" + String.format("%.0f", positionInRange * 100) + "%)");
            } else if (positionInRange > 0.30) {
                score += 4;
                passed.add("SESSION:SHORT_MIDDLE(" + String.format("%.0f", positionInRange * 100) + "%)");
            } else if (positionInRange < 0.15) {
                // SHORT at session low = dangerous
                failed.add("SESSION:SHORT_AT_LOW(" + String.format("%.0f", positionInRange * 100) + "%)");
            }

            // Bonus for failed breakout (longs trapped at resistance)
            if (session.getFailedBreakoutCount() > 0) {
                score += 3;
                passed.add("SESSION:FAILED_BREAKOUT_BEARISH");
            }
        }

        return Math.min(score, WEIGHT_SESSION);
    }

    /**
     * CHECK 6: Multi-Timeframe Confluence (0-10 points)
     * Check if multiple timeframes agree on direction
     */
    private int checkMtfConfluence(FamilyCandle candle, EnrichedQuantScore quantScore,
                                    boolean isLong, List<String> passed, List<String> failed) {
        int score = 0;

        if (candle == null) {
            return 0;
        }

        // Check directional bias from FamilyCandle
        String directionalBias = candle.getDirectionalBias();
        double biasConfidence = candle.getBiasConfidence();

        if (directionalBias != null) {
            if (isLong) {
                if ("STRONG_BULLISH".equals(directionalBias)) {
                    score += 10;
                    passed.add("MTF:STRONG_BULLISH_BIAS");
                } else if ("BULLISH".equals(directionalBias)) {
                    score += 7;
                    passed.add("MTF:BULLISH_BIAS");
                } else if ("STRONG_BEARISH".equals(directionalBias)) {
                    failed.add("MTF:STRONG_BEARISH_CONTRA_LONG");
                } else if ("BEARISH".equals(directionalBias)) {
                    failed.add("MTF:BEARISH_CONTRA_LONG");
                }
            } else {
                if ("STRONG_BEARISH".equals(directionalBias)) {
                    score += 10;
                    passed.add("MTF:STRONG_BEARISH_BIAS");
                } else if ("BEARISH".equals(directionalBias)) {
                    score += 7;
                    passed.add("MTF:BEARISH_BIAS");
                } else if ("STRONG_BULLISH".equals(directionalBias)) {
                    failed.add("MTF:STRONG_BULLISH_CONTRA_SHORT");
                } else if ("BULLISH".equals(directionalBias)) {
                    failed.add("MTF:BULLISH_CONTRA_SHORT");
                }
            }
        }

        // Check family context alignment
        if (quantScore != null && quantScore.getFamilyContext() != null) {
            FamilyContext ctx = quantScore.getFamilyContext();
            FamilyBias familyBias = ctx.getOverallBias();

            if (isLong && (familyBias == FamilyBias.BULLISH || familyBias == FamilyBias.WEAK_BULLISH)) {
                score += 3;
                passed.add("MTF:FAMILY_BULLISH");
            } else if (!isLong && (familyBias == FamilyBias.BEARISH || familyBias == FamilyBias.WEAK_BEARISH)) {
                score += 3;
                passed.add("MTF:FAMILY_BEARISH");
            }
        }

        if (score == 0) {
            failed.add("MTF:NO_CONFLUENCE");
        }

        return Math.min(score, WEIGHT_MTF);
    }

    /**
     * CHECK 7: SuperTrend/BB Technical Alignment (0-10 points)
     */
    private int checkTechnicalAlignment(FamilyCandle candle, EnrichedQuantScore quantScore,
                                         boolean isLong, List<String> passed, List<String> failed) {
        int score = 0;

        TechnicalContext tech = quantScore != null ? quantScore.getTechnicalContext() : null;
        if (tech == null) {
            failed.add("TECH:NO_DATA");
            return 0;
        }

        // Check SuperTrend alignment
        boolean stBullish = tech.isSuperTrendBullish();
        if (isLong && stBullish) {
            score += 5;
            passed.add("TECH:ST_BULLISH");
        } else if (!isLong && !stBullish) {
            score += 5;
            passed.add("TECH:ST_BEARISH");
        } else if (isLong && !stBullish) {
            // Counter-trend - not necessarily bad but note it
            failed.add("TECH:ST_BEARISH_CONTRA_LONG");
        } else if (!isLong && stBullish) {
            failed.add("TECH:ST_BULLISH_CONTRA_SHORT");
        }

        // Check SuperTrend flip (fresh signal)
        if (tech.isSuperTrendFlip()) {
            if ((isLong && stBullish) || (!isLong && !stBullish)) {
                score += 3;
                passed.add("TECH:ST_FRESH_FLIP");
            }
        }

        // Check BB position
        Double bbPercentB = tech.getBbPercentB();
        if (bbPercentB != null) {
            if (isLong && bbPercentB < 0.3) {
                // Near lower BB - good for LONG
                score += 2;
                passed.add("TECH:NEAR_LOWER_BB");
            } else if (!isLong && bbPercentB > 0.7) {
                // Near upper BB - good for SHORT
                score += 2;
                passed.add("TECH:NEAR_UPPER_BB");
            }
        }

        if (score == 0) {
            failed.add("TECH:NO_ALIGNMENT");
        }

        return Math.min(score, WEIGHT_TECHNICAL);
    }

    // ======================== HELPER METHODS ========================

    /**
     * Check if there are strong contradictory signals warranting invalidation
     */
    private boolean hasContradictorySignals(List<String> passed, List<String> failed, boolean isLong) {
        int contradictCount = 0;

        for (String fail : failed) {
            // Strong contradictory signals
            if (fail.contains("CONTRA_LONG") && isLong) contradictCount++;
            if (fail.contains("CONTRA_SHORT") && !isLong) contradictCount++;
            if (fail.contains("SHORT_BUILDUP_CONTRA") || fail.contains("LONG_BUILDUP_CONTRA")) contradictCount++;
            if (fail.contains("SESSION:LONG_AT_HIGH") || fail.contains("SESSION:SHORT_AT_LOW")) contradictCount++;
        }

        // Invalidate if 3+ strong contradictions
        return contradictCount >= 3;
    }

    /**
     * Build human-readable confirmation note
     */
    private String buildConfirmationNote(int score, List<String> passed, List<String> failed, Recommendation rec) {
        StringBuilder sb = new StringBuilder();
        sb.append("Score ").append(score).append("/").append(minScore);
        sb.append(" → ").append(rec);

        if (!passed.isEmpty()) {
            sb.append(" | +").append(passed.size()).append(" checks passed");
        }
        if (!failed.isEmpty()) {
            sb.append(" | -").append(failed.size()).append(" checks failed");
        }

        return sb.toString();
    }

    // ======================== RESULT MODEL ========================

    public enum Recommendation {
        PROMOTE,     // R:R good AND price action confirmed - execute trade
        WAIT,        // R:R good but price action not yet confirmed - keep tracking
        INVALIDATE   // Strong contradictory signals - abandon this setup
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class ConfirmationResult {
        private boolean confirmed;
        private int score;
        private int minScore;
        private List<String> passedChecks;
        private List<String> failedChecks;
        private Recommendation recommendation;
        private String note;

        // Individual check scores for analysis
        private int retestScore;
        private int candleScore;
        private int ofiScore;
        private int optionsScore;
        private int sessionScore;
        private int mtfScore;
        private int technicalScore;
    }
}
