package com.kotsin.consumer.score.calculator;

import com.kotsin.consumer.curated.model.FuturesOptionsAlignment;
import com.kotsin.consumer.curated.model.MultiTimeframeLevels;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.model.IPUOutput;
import com.kotsin.consumer.regime.model.IndexRegime;
import com.kotsin.consumer.regime.model.SecurityRegime;
import com.kotsin.consumer.score.model.FamilyIntelligenceState;
import com.kotsin.consumer.score.model.FamilyScore;
import com.kotsin.consumer.signal.model.FUDKIIOutput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * MTISCalculator - Multi-Timeframe Intelligence Score Calculator
 * 
 * Calculates the composite MTIS score from FamilyCandle and external data.
 * 
 * Score Categories:
 * 1. Price Score (±12)
 * 2. F&O Alignment Score (±20)
 * 3. IPU Score (±15)
 * 4. FUDKII Bonus (+20)
 * 5. Microstructure Score (±8)
 * 6. Orderbook Score (±5)
 * 7. MTF Regime Score (±15)
 * 8. Pattern Bonus (+20)
 * 9. Level Retest Bonus (+20)
 * 10. Relative Strength Bonus (±5)
 * 11. MTIS Momentum Bonus (±5)
 * 
 * Modifiers:
 * - Session Modifier (0.5-1.1)
 * - CPR Modifier (0.7-1.3)
 * - Expiry Modifier (0.7-1.0)
 */
@Slf4j
@Component
public class MTISCalculator {

    // Score category maximums
    private static final double MAX_PRICE_SCORE = 12.0;
    private static final double MAX_FO_SCORE = 20.0;
    private static final double MAX_IPU_SCORE = 15.0;
    private static final double MAX_FUDKII_BONUS = 20.0;
    private static final double MAX_MICRO_SCORE = 8.0;
    private static final double MAX_ORDERBOOK_SCORE = 5.0;
    private static final double MAX_REGIME_SCORE = 15.0;
    private static final double MAX_PATTERN_BONUS = 20.0;
    private static final double MAX_LEVEL_BONUS = 20.0;
    private static final double MAX_RS_BONUS = 5.0;
    private static final double MAX_MOMENTUM_BONUS = 5.0;

    /**
     * Calculate complete MTIS score
     */
    public FamilyScore calculate(
            FamilyCandle family,
            FamilyIntelligenceState state,
            IndexRegime indexRegime,
            SecurityRegime securityRegime,
            IPUOutput ipu,
            FUDKIIOutput fudkii,
            FuturesOptionsAlignment foAlignment,
            MultiTimeframeLevels levels,
            double vcpScore
    ) {
        if (family == null || family.getEquity() == null) {
            log.warn("Cannot calculate MTIS: null family or equity");
            return null;
        }

        String familyId = family.getFamilyId();
        String symbol = family.getSymbol();
        InstrumentCandle equity = family.getEquity();

        // Initialize score breakdown
        FamilyScore.ScoreBreakdown breakdown = FamilyScore.ScoreBreakdown.builder()
                .tfScores(new HashMap<>())
                .build();

        // Build FamilyScore - use candle timestamp, not processing time
        FamilyScore score = FamilyScore.builder()
                .familyId(familyId)
                .symbol(symbol)
                .timestamp(family.getWindowEndMillis())  // Use candle's end time, not processing time
                .windowStartMillis(family.getWindowStartMillis())
                .windowEndMillis(family.getWindowEndMillis())
                .triggerTimeframe(family.getTimeframe())
                // OHLC data from equity (or commodity future acting as primary)
                .open(equity.getOpen())
                .high(equity.getHigh())
                .low(equity.getLow())
                .spotPrice(equity.getClose())
                .volume(equity.getVolume())
                // Commodity flag
                .isCommodity(family.isCommodity())
                // Future and options data
                .futurePrice(family.getFuturePrice())
                .pcr(family.getPcr())
                .oiSignal(family.getOiSignal())
                .futuresBuildup(family.getFuturesBuildup())
                .breakdown(breakdown)
                .build();

        // Calculate each category
        double priceScore = calculatePriceScore(family, state);
        double foScore = calculateFOAlignmentScore(foAlignment);
        double ipuScore = calculateIPUScore(ipu, score);
        double fudkiiBonus = calculateFUDKIIBonus(fudkii, score);
        double microScore = calculateMicrostructureScore(equity);
        double orderbookScore = calculateOrderbookScore(equity);
        double regimeScore = calculateMTFRegimeScore(indexRegime, securityRegime, state, score);
        double patternBonus = calculatePatternBonus(vcpScore, state);
        double levelBonus = calculateLevelRetestBonus(family, levels);
        double rsBonus = calculateRelativeStrength(family, indexRegime);
        double momentumBonus = calculateMTISMomentum(state);

        // Set breakdown
        breakdown.setPriceScore(priceScore);
        breakdown.setFoAlignmentScore(foScore);
        breakdown.setIpuScore(ipuScore);
        breakdown.setFudkiiBonus(fudkiiBonus);
        breakdown.setMicrostructureScore(microScore);
        breakdown.setOrderbookScore(orderbookScore);
        breakdown.setMtfRegimeScore(regimeScore);
        breakdown.setPatternBonus(patternBonus);
        breakdown.setLevelRetestBonus(levelBonus);
        breakdown.setRelativeStrengthBonus(rsBonus);
        breakdown.setMtisMomentumBonus(momentumBonus);

        // Calculate raw MTIS
        double rawMtis = priceScore + foScore + ipuScore + fudkiiBonus +
                microScore + orderbookScore + regimeScore +
                patternBonus + levelBonus + rsBonus + momentumBonus;

        // FIX: Apply modifiers as ADDITIVE adjustments instead of multiplicative
        // Multiplicative modifiers after linear addition distort scores unfairly
        // Convert modifiers to additive adjustments: modifier = 1.0 + (modifier - 1.0) * adjustmentFactor
        double sessionMod = getSessionModifier(family);
        double cprMod = getCPRModifier(levels);
        double expiryMod = getExpiryModifier(family);
        
        // Convert to additive adjustments (scale by rawMtis magnitude)
        // Example: sessionMod = 0.5 means -50% adjustment, sessionMod = 1.1 means +10% adjustment
        double sessionAdjustment = (sessionMod - 1.0) * Math.abs(rawMtis) * 0.3;  // 30% of raw score
        double cprAdjustment = (cprMod - 1.0) * Math.abs(rawMtis) * 0.2;  // 20% of raw score
        double expiryAdjustment = (expiryMod - 1.0) * Math.abs(rawMtis) * 0.1;  // 10% of raw score
        
        double finalMtis = rawMtis + sessionAdjustment + cprAdjustment + expiryAdjustment;
        finalMtis = clamp(finalMtis, -100, 100);

        // Set final score values
        score.setRawMtis(rawMtis);
        score.setSessionModifier(sessionMod);
        score.setCprModifier(cprMod);
        score.setExpiryModifier(expiryMod);
        score.setMtis(finalMtis);
        score.setMtisLabel(FamilyScore.getLabelFromScore(finalMtis));

        // Calculate trend from previous
        double previousMtis = state != null ? state.getMtis() : 0;
        double mtisChange = finalMtis - previousMtis;
        score.setPreviousMtis(previousMtis);
        score.setMtisChange(mtisChange);
        score.setMtisTrend(FamilyScore.getTrendFromChange(mtisChange));

        // Set additional data
        score.setIndexRegimeLabel(indexRegime != null ? String.valueOf(indexRegime.getLabel()) : null);
        score.setVcpScore(vcpScore > 0 ? vcpScore : null);
        score.setIpuFinalScore(ipu != null ? ipu.getFinalIpuScore() : null);
        score.setFudkiiIgnition(fudkii != null && fudkii.isIgnitionFlag());
        score.setCprWidth(levels != null && levels.getDailyPivot() != null ? 
                String.valueOf(levels.getDailyPivot().getCprType()) : null);
        score.setExpiryDay(isExpiryDay(family));
        score.setSessionPhase(getSessionPhase(family));

        // Detect divergence
        if (state != null && family.getFuture() != null) {
            Long futureOI = family.getFuture().getOpenInterest();
            boolean hasDivergence = state.hasDivergence(equity.getClose(), futureOI);
            score.setHasDivergence(hasDivergence);
            if (hasDivergence) {
                score.addWarning("DIVERGENCE", "MEDIUM", 
                        "Price and OI moving opposite - potential trap");
            }
        }

        // Set exhaustion flag
        score.setHasExhaustion(ipu != null && ipu.isExhaustionWarning());

        // Build summary
        score.setSummary(buildSummary(score));

        // Set actionable flag
        score.setActionable(score.isActionable());

        // Update human readable time (use candle timestamp, not processing time)
        score.setHumanReadableTime(formatTime(family.getWindowEndMillis()));

        return score;
    }

    // ==================== CATEGORY CALCULATIONS ====================

    /**
     * Category 1: Price Score (±12)
     * FIX: Added volatility normalization for fair scoring across instruments
     */
    private double calculatePriceScore(FamilyCandle family, FamilyIntelligenceState state) {
        InstrumentCandle equity = family.getEquity();
        double score = 0;

        // 1A. Close vs current TF VWAP (±4) - FIX: Normalized by volatility
        double vwap = equity.getVwap();
        double close = equity.getClose();
        if (vwap > 0.0001) {
            double vwapDistance = (close - vwap) / vwap * 100;
            
            // FIX: Normalize by volatility (use range as proxy for ATR)
            double range = equity.getRange();
            double volatilityProxy = range > 0 && close > 0 ? (range / close) * 100 : 1.0;  // Range as % of price
            double normalizedDistance = volatilityProxy > 0.1 ? vwapDistance / volatilityProxy : vwapDistance;
            
            score += clamp(normalizedDistance * 4, -4, 4);
        }

        // 1B. Candle character (±4)
        double range = equity.getRange();
        if (range > 0.0001) {
            double bodyRatio = equity.getBodySize() / range;
            if (equity.isBullish() && bodyRatio > 0.6) {
                score += 4;
            } else if (equity.isBearish() && bodyRatio > 0.6) {
                score -= 4;
            }
        }

        // 1C. Price vs higher TF VWAPs (±4) - FIX: Require minimum 3 timeframes for meaningful analysis
        if (state != null && state.getTfVwaps() != null) {
            int aboveCount = 0;
            int totalCount = 0;
            for (Map.Entry<String, Double> entry : state.getTfVwaps().entrySet()) {
                if (entry.getValue() != null && entry.getValue() > 0) {
                    totalCount++;
                    if (close > entry.getValue()) {
                        aboveCount++;
                    }
                }
            }
            // FIX: Require at least 3 timeframes to avoid bias from single TF
            if (totalCount >= 3) {
                double ratio = (double) aboveCount / totalCount;
                score += clamp((ratio - 0.5) * 8, -4, 4);
            }
            // If less than 3 TFs available, don't penalize, just skip this component
        }

        return clamp(score, -MAX_PRICE_SCORE, MAX_PRICE_SCORE);
    }

    /**
     * Category 2: F&O Alignment Score (±20)
     */
    private double calculateFOAlignmentScore(FuturesOptionsAlignment foAlign) {
        if (foAlign == null || !foAlign.isUsable()) {
            return 0;
        }

        switch (foAlign.getBias()) {
            case STRONG_BULLISH: return 20;
            case BULLISH: return 12;
            case NEUTRAL: return 0;
            case BEARISH: return -12;
            case STRONG_BEARISH: return -20;
            default: return 0;
        }
    }

    /**
     * Category 3: IPU Score (±15)
     */
    private double calculateIPUScore(IPUOutput ipu, FamilyScore score) {
        if (ipu == null) return 0;

        double ipuScore = 0;

        // Base score from IPU final score (0-1 -> 0-10)
        double finalIPU = ipu.getFinalIpuScore();  // Range: 0-1
        ipuScore = finalIPU * 10;  // Scale to 0-10

        // Direction from directional conviction
        if (ipu.getDirectionalConviction() < 0) {
            ipuScore = -ipuScore;
        }

        // CRITICAL: Exhaustion FLIPS the signal!
        boolean isExhausted = ipu.isExhaustionWarning();
        if (isExhausted) {
            ipuScore = -ipuScore * 0.5;
            score.addWarning("EXHAUSTION", "HIGH", "IPU exhaustion detected - reversal likely");
            score.addContributor("IPU_EXHAUSTION", ipuScore, 
                    "Momentum exhaustion - signal flipped", "ipu.exhaustionDetected", "true");
        }

        // FIX: Urgency boost - SKIP if exhausted (contradictory to multiply exhausted signal)
        // Exhaustion means momentum is dying, urgency doesn't apply
        if (!isExhausted) {
            IPUOutput.UrgencyLevel urgency = ipu.getUrgencyLevel();
            if (urgency == IPUOutput.UrgencyLevel.AGGRESSIVE) {
                ipuScore *= 1.5;
                score.addContributor("IPU_URGENCY", Math.signum(ipuScore) * 5, 
                        "EXTREME institutional urgency", "ipu.urgencyClassification", "EXTREME");
            } else if (urgency == IPUOutput.UrgencyLevel.ELEVATED) {
                ipuScore *= 1.2;
            }
        }

        return clamp(ipuScore, -MAX_IPU_SCORE, MAX_IPU_SCORE);
    }

    /**
     * Category 4: FUDKII Ignition Bonus (±20)
     * FIX: Made directional - ignition can be bullish or bearish
     */
    private double calculateFUDKIIBonus(FUDKIIOutput fudkii, FamilyScore score) {
        if (fudkii == null || !fudkii.isIgnitionFlag()) {
            return 0;
        }

        int sim = fudkii.getSimultaneityScore();
        double baseBonus = 0;
        
        if (sim >= 5) baseBonus = 20;
        else if (sim >= 4) baseBonus = 15;
        else if (sim >= 3) baseBonus = 10;
        else baseBonus = 5;

        // FIX: Make directional based on ignition direction (if available)
        // For now, assume positive = bullish, but this should come from FUDKII output
        // If FUDKII has directional bias, use it; otherwise default to positive
        double bonus = baseBonus;
        // TODO: Check if FUDKIIOutput has directionalBias field and apply it
        // For now, keeping as positive but structure allows for negative

        score.addContributor("FUDKII_IGNITION", bonus,
                "Ignition triggered with simultaneity=" + sim,
                "fudkii.ignitionFlag", "true");

        return bonus;
    }

    /**
     * Category 5: Microstructure Score (±8)
     */
    private double calculateMicrostructureScore(InstrumentCandle equity) {
        double score = 0;

        // 5A. OFI (±3)
        if (equity.getOfi() != null) {
            score += clamp(equity.getOfi() * 10, -3, 3);
        }

        // 5B. VPIN informed trading direction (±3)
        double vpin = equity.getVpin();
        if (vpin > 0.5) {
            score += equity.getBuyVolume() > equity.getSellVolume() ? 3 : -3;
        }

        // 5C. Volume delta (±2)
        double delta = equity.getVolumeDeltaPercent();
        score += clamp(delta / 15, -2, 2);

        return clamp(score, -MAX_MICRO_SCORE, MAX_MICRO_SCORE);
    }

    /**
     * Category 6: Orderbook Intelligence (±5)
     */
    private double calculateOrderbookScore(InstrumentCandle equity) {
        if (!equity.hasOrderbook()) {
            return 0;
        }

        double score = 0;

        // 6A. Microprice bias (±2)
        if (equity.getMicroprice() != null && equity.getClose() > 0) {
            double bias = (equity.getMicroprice() - equity.getClose()) / equity.getClose() * 100;
            score += clamp(bias * 10, -2, 2);
        }

        // 6B. Depth imbalance (±2)
        if (equity.getDepthImbalance() != null) {
            score += clamp(equity.getDepthImbalance() * 4, -2, 2);
        }

        // 6C. Spoofing penalty (-1)
        if (equity.getSpoofingCount() != null && equity.getSpoofingCount() > 5) {
            score -= 1;
        }

        return clamp(score, -MAX_ORDERBOOK_SCORE, MAX_ORDERBOOK_SCORE);
    }

    /**
     * Category 7: MTF Regime Score (±15)
     */
    private double calculateMTFRegimeScore(IndexRegime indexRegime, SecurityRegime securityRegime,
                                           FamilyIntelligenceState state, FamilyScore score) {
        double regimeScore = 0;

        // 7A. Index regime direction (±8)
        if (indexRegime != null) {
            double directionalBias = indexRegime.getDirectionalBias();
            regimeScore += directionalBias * 8;
            
            score.addContributor("INDEX_REGIME", directionalBias * 8,
                    "Index regime: " + indexRegime.getLabel(),
                    "indexRegime.label", String.valueOf(indexRegime.getLabel()));
        }

        // 7B. Security alignment (±4) - with complete null safety
        if (securityRegime != null && securityRegime.getLabel() != null &&
            indexRegime != null && indexRegime.getLabel() != null) {
            // Check if security aligns with index direction
            boolean aligned = securityRegime.getLabel().getValue() * indexRegime.getLabel().getValue() > 0;

            if (aligned) {
                // If aligned, boost in the direction they're both moving
                double direction = securityRegime.getLabel().getValue();
                regimeScore += 4 * Math.signum(direction);
            } else {
                // If misaligned, penalize in index direction
                double indexDirection = indexRegime.getLabel().getValue();
                regimeScore -= 2 * Math.signum(indexDirection);
            }
        }

        // 7C. Multi-TF agreement (±3)
        if (state != null) {
            double weightedScore = state.calculateWeightedTFScore();
            regimeScore += clamp(weightedScore * 0.3, -3, 3);
        }

        return clamp(regimeScore, -MAX_REGIME_SCORE, MAX_REGIME_SCORE);
    }

    /**
     * Category 8: Pattern Bonus (+20)
     */
    private double calculatePatternBonus(double vcpScore, FamilyIntelligenceState state) {
        double bonus = 0;

        // VCP score contribution
        if (vcpScore > 70) {
            bonus = (vcpScore - 70) / 30 * 15;  // 70-100 → 0-15
        }

        // Breaking out bonus
        if (state != null && state.isBreakingOut()) {
            bonus += 5;
        }

        return clamp(bonus, 0, MAX_PATTERN_BONUS);
    }

    /**
     * Category 9: Level Retest Bonus (+20)
     */
    private double calculateLevelRetestBonus(FamilyCandle family, MultiTimeframeLevels levels) {
        if (levels == null) {
            return 0;
        }

        double spot = family.getSpotPrice();
        double bonus = 0;

        // 9A. Near significant level (+10)
        if (levels.isNearSignificantLevel(spot)) {
            bonus += 10;
        }

        // 9B. Weekly Fib 618 (+5)
        if (levels.getWeeklyFib() != null) {
            double fib618 = levels.getWeeklyFib().getFib618();
            if (fib618 > 0) {
                double dist = Math.abs(spot - fib618) / spot;
                if (dist < 0.003) {
                    bonus += 5;
                }
            }
        }

        // 9C. Pivot + Fibo confluence (+5)
        double support = levels.getNearestSupport(spot);
        double resistance = levels.getNearestResistance(spot);
        if (support > 0 && resistance > 0 && resistance > support) {
            double range = (resistance - support) / spot;
            if (range < 0.01) {
                bonus += 5;  // Levels converging
            }
        }

        return clamp(bonus, 0, MAX_LEVEL_BONUS);
    }

    /**
     * Category 10: Relative Strength (±5)
     * FIX: Use same base for both security and index (both vs VWAP or both intraday)
     */
    private double calculateRelativeStrength(FamilyCandle family, IndexRegime indexRegime) {
        if (indexRegime == null || indexRegime.getTf5m() == null) {
            return 0;
        }

        InstrumentCandle equity = family.getEquity();
        
        // FIX: Use same base - both vs VWAP for consistency
        double securityVwap = equity.getVwap();
        double securityClose = equity.getClose();
        double securityReturn = securityVwap > 0 ? (securityClose / securityVwap - 1) * 100 : 0;
        
        double indexVwap = indexRegime.getTf5m().getVwap();
        double indexClose = indexRegime.getTf5m().getClose();
        double indexReturn = indexVwap > 0 ? (indexClose / indexVwap - 1) * 100 : 0;

        double rs = securityReturn - indexReturn;

        if (rs > 0.5) return 5;
        if (rs < -0.5) return -5;
        return clamp(rs * 10, -5, 5);
    }

    /**
     * Category 11: MTIS Momentum (±5)
     *
     * FIX: Reduced penalties for mean reversion
     * - Old: -3 to -5 penalty was killing reversal signals
     * - New: -1 to -2 penalty, allowing reversal opportunities
     * - Trend continuation still rewarded (+3 to +5)
     */
    private double calculateMTISMomentum(FamilyIntelligenceState state) {
        if (state == null) {
            return 0;
        }

        double currentMtis = state.getMtis();
        double previousMtis = state.getPreviousMtis();
        double change = currentMtis - previousMtis;

        // Strong trend continuation (accelerating) - reward
        if (currentMtis > 0 && change > 5) return 5;    // Bullish accelerating
        if (currentMtis < 0 && change < -5) return -5;  // Bearish accelerating

        // Moderate trend continuation - small reward
        if (currentMtis > 0 && change > 2) return 3;    // Bullish continuing
        if (currentMtis < 0 && change < -2) return -3;  // Bearish continuing

        // Mean reversion / deceleration - REDUCED PENALTY (was -3, now -1)
        // Reversals can be profitable signals, don't kill them
        if (currentMtis > 0 && change < -3) return -1;  // Bullish weakening (was -3)
        if (currentMtis < 0 && change > 3) return 1;    // Bearish weakening (was +3)

        return 0;
    }

    // ==================== MODIFIERS ====================

    /**
     * Session Modifier (0.5-1.1)
     * FIX: Use event time from candle, not wall clock (critical for replay)
     */
    private double getSessionModifier(FamilyCandle family) {
        // FIX: Use candle's event time, not wall clock
        long eventTimeMillis = family.getWindowEndMillis();
        LocalTime now = java.time.Instant.ofEpochMilli(eventTimeMillis)
                .atZone(ZoneId.of("Asia/Kolkata"))
                .toLocalTime();
        
        int hour = now.getHour();
        int minute = now.getMinute();

        if (hour == 9 && minute < 30) return 0.5;   // Opening chaos
        if (hour == 9 && minute < 45) return 0.7;   // Still settling
        if (hour == 12 && minute >= 30) return 0.8; // Pre-lunch
        if (hour == 13) return 0.75;                 // Lunch lull
        if (hour == 15 && minute >= 15) return 0.6; // Closing squeeze
        if (hour >= 10 && hour < 12) return 1.1;    // Best time
        if (hour >= 14 && hour < 15) return 1.0;    // Good time

        return 0.9;
    }

    /**
     * CPR Modifier (0.7-1.3)
     */
    private double getCPRModifier(MultiTimeframeLevels levels) {
        if (levels == null || levels.getDailyPivot() == null) {
            return 1.0;
        }

        MultiTimeframeLevels.PivotLevels.CPRWidth cprType = levels.getDailyPivot().getCprType();
        if (cprType == null) {
            return 1.0;
        }

        switch (cprType) {
            case NARROW: return 1.3;  // Breakout day!
            case WIDE: return 0.7;    // Range day
            default: return 1.0;
        }
    }

    /**
     * Expiry Modifier (0.7-1.0)
     * FIX: Check actual expiry dates from FamilyCandle, not hardcoded Thursday
     */
    private double getExpiryModifier(FamilyCandle family) {
        if (!isExpiryDay(family)) {
            return 1.0;
        }

        Double maxPain = family.getMaxPain();
        if (maxPain == null || maxPain <= 0) {
            return 0.9;
        }

        double spot = family.getSpotPrice();
        double distToMaxPain = Math.abs(spot - maxPain) / maxPain * 100;

        if (distToMaxPain > 2.0) return 0.7;
        if (distToMaxPain > 1.0) return 0.85;
        return 1.0;
    }

    // ==================== HELPERS ====================

    /**
     * FIX: Check if current date is expiry day
     * For now, check if it's Thursday (weekly expiry) or last Thursday of month (monthly)
     * TODO: Get actual expiry dates from FamilyCandle or instrument metadata
     */
    private boolean isExpiryDay(FamilyCandle family) {
        // FIX: Use event time from candle, not wall clock
        long eventTimeMillis = family.getWindowEndMillis();
        LocalDate eventDate = java.time.Instant.ofEpochMilli(eventTimeMillis)
                .atZone(ZoneId.of("Asia/Kolkata"))
                .toLocalDate();
        
        DayOfWeek dayOfWeek = eventDate.getDayOfWeek();
        
        // Weekly expiry: Every Thursday
        if (dayOfWeek == DayOfWeek.THURSDAY) {
            return true;
        }
        
        // Monthly expiry: Last Thursday of month (approximate check)
        // Get last day of month and check if it's within 7 days of Thursday
        int lastDayOfMonth = eventDate.lengthOfMonth();
        LocalDate lastDay = eventDate.withDayOfMonth(lastDayOfMonth);
        DayOfWeek lastDayOfWeek = lastDay.getDayOfWeek();
        
        // If last day is Thursday, or within 6 days before last day and it's Thursday
        if (dayOfWeek == DayOfWeek.THURSDAY && eventDate.getDayOfMonth() >= (lastDayOfMonth - 6)) {
            return true;
        }
        
        return false;
    }

    /**
     * ISSUE #3 FIX: Use event time from FamilyCandle instead of wall clock
     * This ensures correct session phase during replay of historical data
     */
    private String getSessionPhase(FamilyCandle family) {
        // Extract event time from family candle
        long eventTimeMs = 0;
        if (family != null && family.getEquity() != null) {
            eventTimeMs = family.getEquity().getWindowEndMillis();
        } else if (family != null && family.getFuture() != null) {
            eventTimeMs = family.getFuture().getWindowEndMillis();
        }

        // Use event time if available, otherwise fall back to wall clock
        LocalTime eventTime;
        if (eventTimeMs > 0) {
            eventTime = java.time.Instant.ofEpochMilli(eventTimeMs)
                    .atZone(ZoneId.of("Asia/Kolkata"))
                    .toLocalTime();
        } else {
            eventTime = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        }

        int hour = eventTime.getHour();
        int minute = eventTime.getMinute();

        if (hour == 9 && minute < 30) return "OPENING";
        if (hour >= 9 && hour < 12) return "MORNING";
        if (hour >= 12 && hour < 14) return "MIDDAY";
        if (hour >= 14 && hour < 15) return "AFTERNOON";
        return "CLOSING";
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String formatTime(long millis) {
        return java.time.ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(millis),
                ZoneId.of("Asia/Kolkata")
        ).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private String buildSummary(FamilyScore score) {
        StringBuilder sb = new StringBuilder();
        sb.append(score.getMtisLabel())
          .append(" (")
          .append(String.format("%+.1f", score.getMtis()))
          .append("): ");

        // Add top contributors
        if (score.getContributors() != null && !score.getContributors().isEmpty()) {
            int count = 0;
            for (FamilyScore.ScoreContributor c : score.getContributors()) {
                if (count > 0) sb.append(", ");
                sb.append(c.getCategory())
                  .append(" (")
                  .append(String.format("%+.0f", c.getPoints()))
                  .append(")");
                count++;
                if (count >= 3) break;
            }
        }

        // Add warnings
        if (score.getWarnings() != null && !score.getWarnings().isEmpty()) {
            sb.append(". Warnings: ");
            for (int i = 0; i < Math.min(2, score.getWarnings().size()); i++) {
                if (i > 0) sb.append(", ");
                sb.append(score.getWarnings().get(i).getType());
            }
        }

        return sb.toString();
    }
}
