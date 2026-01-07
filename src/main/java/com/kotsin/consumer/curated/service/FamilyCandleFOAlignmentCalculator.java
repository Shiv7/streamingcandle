package com.kotsin.consumer.curated.service;

import com.kotsin.consumer.curated.model.FuturesOptionsAlignment;
import com.kotsin.consumer.domain.model.FamilyCandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Calculate F&O alignment from FamilyCandle data (no external API needed)
 * 
 * Uses data already available in FamilyCandle:
 * - spotFuturePremium (futures premium)
 * - futuresBuildup (LONG_BUILDUP, SHORT_BUILDUP, etc.)
 * - pcr (Put/Call Ratio)
 * - totalCallOIChange, totalPutOIChange
 * 
 * This replaces FuturesOptionsService which was calling a non-existent API.
 */
@Slf4j
@Service
public class FamilyCandleFOAlignmentCalculator {

    /**
     * Calculate F&O alignment from FamilyCandle data
     */
    public FuturesOptionsAlignment calculateAlignment(FamilyCandle familyCandle) {
        if (familyCandle == null) {
            return createEmptyAlignment(null);
        }

        String familyId = familyCandle.getFamilyId();
        List<String> reasons = new ArrayList<>();

        // Check data availability
        boolean hasFuture = familyCandle.isHasFuture();
        boolean hasOptions = familyCandle.isHasOptions();
        boolean dataAvailable = hasFuture || hasOptions;
        
        // Data is fresh if FamilyCandle timestamp is recent (< 5 minutes)
        long candleAge = System.currentTimeMillis() - familyCandle.getTimestamp();
        boolean dataFresh = candleAge < (5 * 60 * 1000); // 5 minutes

        if (!dataAvailable) {
            log.debug("No F&O data in FamilyCandle for {}", familyId);
            return createEmptyAlignment(familyId);
        }

        // Calculate futures score
        double futuresScore = calculateFuturesScore(familyCandle, reasons);

        // Calculate options score
        double optionsScore = calculateOptionsScore(familyCandle, reasons);

        // Combined score (weighted average)
        double alignmentScore;
        if (hasFuture && hasOptions) {
            alignmentScore = (futuresScore * 0.6) + (optionsScore * 0.4);  // Futures weighted more
        } else if (hasFuture) {
            alignmentScore = futuresScore;
        } else {
            alignmentScore = optionsScore;
        }

        // Determine bias
        FuturesOptionsAlignment.DirectionalBias bias = determineBias(familyCandle);

        FuturesOptionsAlignment alignment = FuturesOptionsAlignment.builder()
                .scripCode(familyId)
                .timestamp(familyCandle.getTimestamp())
                .futuresData(null)  // Not needed - data is in FamilyCandle
                .optionsData(null)  // Not needed - data is in FamilyCandle
                .dataAvailable(dataAvailable)
                .dataFresh(dataFresh)
                .futuresScore(futuresScore)
                .optionsScore(optionsScore)
                .alignmentScore(alignmentScore)
                .isAligned(alignmentScore >= 0.6)
                .bias(bias)
                .reasons(reasons)
                .build();

        log.debug("F&O Alignment for {}: score={}, bias={}, aligned={}",
                familyId, String.format("%.2f", alignmentScore), bias, alignment.isAligned());

        return alignment;
    }

    /**
     * Calculate futures score (0 to 1) from FamilyCandle data
     */
    private double calculateFuturesScore(FamilyCandle familyCandle, List<String> reasons) {
        if (!familyCandle.isHasFuture()) {
            return 0.0;
        }

        double score = 0.0;

        // Premium analysis (0-0.3 points)
        Double premium = familyCandle.getSpotFuturePremium();
        if (premium != null && premium > 0) {
            score += 0.3;
            reasons.add("Futures at premium (" + String.format("%.2f", premium) + "%)");
        }

        // OI change analysis (0-0.3 points)
        Long futureOIChange = familyCandle.getFutureOIChange();
        Double futureOIChangePercent = familyCandle.getFuture() != null && 
                                       familyCandle.getFuture().getOiChangePercent() != null ?
                                       familyCandle.getFuture().getOiChangePercent() : null;
        
        if (futureOIChangePercent != null && futureOIChangePercent > 5) {
            score += 0.3;
            reasons.add("Futures OI increasing (" + String.format("%.1f", futureOIChangePercent) + "%)");
        } else if (futureOIChangePercent != null && futureOIChangePercent > 2) {
            score += 0.15;
        }

        // Buildup type analysis (0-0.4 points)
        String buildup = familyCandle.getFuturesBuildup();
        if (buildup != null) {
            switch (buildup) {
                case "LONG_BUILDUP":
                    score += 0.4;
                    reasons.add("LONG BUILDUP (Price ↑ + OI ↑)");
                    break;
                case "SHORT_COVERING":
                    score += 0.2;
                    reasons.add("SHORT COVERING (Price ↑ + OI ↓)");
                    break;
                case "SHORT_BUILDUP":
                    score += 0.0;  // Bearish, bad for long
                    reasons.add("SHORT BUILDUP (Price ↓ + OI ↑) - BEARISH");
                    break;
                case "LONG_UNWINDING":
                    score += 0.0;  // Bearish, bad for long
                    reasons.add("LONG UNWINDING (Price ↓ + OI ↓) - BEARISH");
                    break;
                default:
                    score += 0.1;
            }
        }

        return Math.min(score, 1.0);
    }

    /**
     * Calculate options score (0 to 1) from FamilyCandle data
     */
    private double calculateOptionsScore(FamilyCandle familyCandle, List<String> reasons) {
        if (!familyCandle.isHasOptions()) {
            return 0.0;
        }

        double score = 0.0;

        // PCR analysis (0-0.4 points)
        Double pcr = familyCandle.getPcr();
        if (pcr != null) {
            // PCR < 0.7 = Bullish (more calls than puts)
            // PCR > 1.3 = Bearish (more puts than calls)
            if (pcr < 0.7) {
                score += 0.4;
                reasons.add("Options BULLISH (PCR=" + String.format("%.2f", pcr) + ")");
            } else if (pcr >= 0.7 && pcr <= 1.3) {
                score += 0.2;
                reasons.add("Options NEUTRAL (PCR=" + String.format("%.2f", pcr) + ")");
            } else {
                score += 0.0;
                reasons.add("Options BEARISH (PCR=" + String.format("%.2f", pcr) + ")");
            }
        }

        // OI change analysis (0-0.3 points)
        Long totalCallOIChange = familyCandle.getTotalCallOIChange();
        Long totalPutOIChange = familyCandle.getTotalPutOIChange();
        
        if (totalCallOIChange != null && totalPutOIChange != null) {
            if (totalCallOIChange > totalPutOIChange) {
                score += 0.3;
                reasons.add("Call OI increasing faster than Put OI");
            } else if (totalCallOIChange > 0) {
                score += 0.15;
            }
        }

        // Strong directional move (0-0.3 points)
        if (totalCallOIChange != null && totalPutOIChange != null) {
            long netOIChange = totalCallOIChange - totalPutOIChange;
            long totalOIChange = Math.abs(totalCallOIChange) + Math.abs(totalPutOIChange);
            
            if (totalOIChange > 0) {
                double changeRatio = Math.abs((double) netOIChange / totalOIChange);
                if (changeRatio > 0.6) {  // > 60% skew in one direction
                    score += 0.3;
                    reasons.add("Strong directional OI positioning detected");
                }
            }
        }

        // Max pain check (penalty)
        Double maxPain = familyCandle.getMaxPain();
        Double spotPrice = familyCandle.getSpotPrice();
        if (maxPain != null && spotPrice != null && spotPrice > 0) {
            double distancePercent = Math.abs((spotPrice - maxPain) / spotPrice) * 100;
            if (distancePercent < 2.0) {  // Within 2% of max pain
                score *= 0.7;  // Reduce score by 30% if near max pain (pinning risk)
                reasons.add("Near max pain - pinning risk");
            }
        }

        return Math.min(score, 1.0);
    }

    /**
     * Determine directional bias from FamilyCandle F&O data
     */
    private FuturesOptionsAlignment.DirectionalBias determineBias(FamilyCandle familyCandle) {
        int bullishPoints = 0;
        int bearishPoints = 0;

        // Futures analysis
        if (familyCandle.isHasFuture()) {
            String buildup = familyCandle.getFuturesBuildup();
            if ("LONG_BUILDUP".equals(buildup)) {
                bullishPoints += 2;
            } else if ("SHORT_COVERING".equals(buildup)) {
                bullishPoints += 1;
            } else if ("SHORT_BUILDUP".equals(buildup)) {
                bearishPoints += 2;
            } else if ("LONG_UNWINDING".equals(buildup)) {
                bearishPoints += 1;
            }

            Double premium = familyCandle.getSpotFuturePremium();
            if (premium != null) {
                if (premium > 0) {
                    bullishPoints += 1;
                } else {
                    bearishPoints += 1;
                }
            }
        }

        // Options analysis
        if (familyCandle.isHasOptions()) {
            Double pcr = familyCandle.getPcr();
            if (pcr != null) {
                if (pcr < 0.7) {  // More calls than puts
                    bullishPoints += 2;
                } else if (pcr > 1.3) {  // More puts than calls
                    bearishPoints += 2;
                }
            }
        }

        // Determine bias
        int netPoints = bullishPoints - bearishPoints;

        if (netPoints >= 3) return FuturesOptionsAlignment.DirectionalBias.STRONG_BULLISH;
        if (netPoints >= 1) return FuturesOptionsAlignment.DirectionalBias.BULLISH;
        if (netPoints <= -3) return FuturesOptionsAlignment.DirectionalBias.STRONG_BEARISH;
        if (netPoints <= -1) return FuturesOptionsAlignment.DirectionalBias.BEARISH;
        return FuturesOptionsAlignment.DirectionalBias.NEUTRAL;
    }

    /**
     * Create empty alignment when no data available
     */
    private FuturesOptionsAlignment createEmptyAlignment(String scripCode) {
        return FuturesOptionsAlignment.builder()
                .scripCode(scripCode)
                .timestamp(System.currentTimeMillis())
                .futuresData(null)
                .optionsData(null)
                .dataAvailable(false)
                .dataFresh(false)
                .futuresScore(0.0)
                .optionsScore(0.0)
                .alignmentScore(0.0)
                .isAligned(false)
                .bias(FuturesOptionsAlignment.DirectionalBias.NEUTRAL)
                .reasons(new ArrayList<>())
                .build();
    }
}

