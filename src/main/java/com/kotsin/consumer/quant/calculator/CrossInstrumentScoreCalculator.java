package com.kotsin.consumer.quant.calculator;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.quant.config.QuantScoreConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CrossInstrumentScoreCalculator - Calculates cross-instrument subscore (0-10 points).
 *
 * Evaluates:
 * - Spot-future premium alignment (0-5)
 * - All instruments direction agreement (0-5)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CrossInstrumentScoreCalculator {

    private final QuantScoreConfig config;

    /**
     * Calculate cross-instrument subscore
     *
     * @param family FamilyCandle with equity, future, options
     * @param priceDirection Price direction for alignment check
     * @return Score 0-10
     */
    public double calculate(FamilyCandle family, double priceDirection) {
        double maxScore = config.getWeight().getCrossInstrument();

        double premiumScore = calculateSpotFuturePremiumScore(family, priceDirection);
        double agreementScore = calculateInstrumentAgreementScore(family);

        return Math.min(maxScore, premiumScore + agreementScore);
    }

    /**
     * Calculate spot-future premium alignment score (0-5)
     *
     * FIX: For commodities (no equity, hence no spot-future premium),
     * calculate score based on future's price action and OI buildup instead.
     */
    private double calculateSpotFuturePremiumScore(FamilyCandle family, double priceDirection) {
        double maxPoints = 5.0;

        Double premium = family.getSpotFuturePremium();

        // FIX: For commodities, use futures buildup and direction instead of spot-future premium
        if (premium == null) {
            // Check if this is a commodity with futures data
            if (family.isCommodity() && family.isHasFuture()) {
                double score = maxPoints * 0.3;  // Base score for having futures data

                // Check futures buildup type
                String buildup = family.getFuturesBuildup();
                if (buildup != null) {
                    // Bullish buildups aligned with bullish direction
                    if (("LONG_BUILDUP".equals(buildup) && priceDirection > 0) ||
                        ("SHORT_COVERING".equals(buildup) && priceDirection > 0)) {
                        score += maxPoints * 0.4;
                    }
                    // Bearish buildups aligned with bearish direction
                    else if (("SHORT_BUILDUP".equals(buildup) && priceDirection < 0) ||
                             ("LONG_UNWINDING".equals(buildup) && priceDirection < 0)) {
                        score += maxPoints * 0.4;
                    }
                    // Any buildup is somewhat actionable
                    else if (!buildup.isEmpty() && !"NEUTRAL".equals(buildup)) {
                        score += maxPoints * 0.2;
                    }
                }

                // Future OI building up adds confidence
                if (family.isFutureOiBuildingUp()) {
                    score += maxPoints * 0.1;
                }

                return Math.min(maxPoints, score);
            }
            return 0;
        }

        double threshold = config.getCrossInstrument().getPremiumSignificantPercent();

        // Check premium alignment with direction
        boolean aligned = (premium > threshold && priceDirection > 0) ||
                         (premium < -threshold && priceDirection < 0);

        if (aligned) {
            // FIX: Guard against division by zero when threshold = 0
            if (threshold <= 0) {
                // Can't normalize, return base score for alignment
                return maxPoints * 0.7;
            }
            double magnitude = Math.min(1.0, Math.abs(premium) / (threshold * 3));
            return maxPoints * (0.6 + 0.4 * magnitude);
        }

        // Check if premium is expanding
        Double premiumChange = family.getSpotFuturePremiumChange();
        if (premiumChange != null) {
            boolean expanding = family.isPremiumExpanding();
            if (expanding && Math.abs(premium) > threshold) {
                return maxPoints * 0.5;
            }
        }

        // Neutral/contrarian premium
        return maxPoints * 0.2;
    }

    /**
     * Calculate instrument agreement score (0-5)
     *
     * FIX: For commodities (no equity), use primaryInstrument (future) as the baseline
     * and calculate agreement based on futures buildup, OI signals, and options flow.
     */
    private double calculateInstrumentAgreementScore(FamilyCandle family) {
        double maxPoints = 5.0;

        InstrumentCandle equity = family.getEquity();
        InstrumentCandle future = family.getFuture();

        int agreementCount = 0;
        int totalInstruments = 0;

        // FIX: For commodities, use primaryInstrument (future) as the baseline
        Boolean primaryBullish = null;
        InstrumentCandle primary = family.getPrimaryInstrumentOrFallback();

        if (primary != null && primary.getOpen() > 0) {
            primaryBullish = primary.getClose() > primary.getOpen();
            totalInstruments++;
        }

        // For equity-based families: check equity-future agreement
        if (equity != null && future != null && equity.getOpen() > 0 && future.getOpen() > 0) {
            boolean equityBullish = equity.getClose() > equity.getOpen();
            boolean futureBullish = future.getClose() > future.getOpen();
            totalInstruments++;
            if (equityBullish == futureBullish) {
                agreementCount++;
            }
        }

        // FIX: For commodities, check OI signal alignment with price direction
        if (family.isCommodity() && primaryBullish != null) {
            // OI signal agreement
            String oiSignal = family.getOiSignal();
            if (oiSignal != null) {
                boolean oiBullish = "BULLISH_ACCUMULATION".equals(oiSignal) || "SHORT_COVERING_RALLY".equals(oiSignal);
                boolean oiBearish = "BEARISH_DISTRIBUTION".equals(oiSignal) || "LONG_UNWINDING".equals(oiSignal);

                if ((oiBullish && primaryBullish) || (oiBearish && !primaryBullish)) {
                    agreementCount++;
                }
                totalInstruments++;
            }

            // Futures buildup agreement
            String buildup = family.getFuturesBuildup();
            if (buildup != null && !buildup.isEmpty() && !"NEUTRAL".equals(buildup)) {
                boolean buildupBullish = "LONG_BUILDUP".equals(buildup) || "SHORT_COVERING".equals(buildup);
                boolean buildupBearish = "SHORT_BUILDUP".equals(buildup) || "LONG_UNWINDING".equals(buildup);

                if ((buildupBullish && primaryBullish) || (buildupBearish && !primaryBullish)) {
                    agreementCount++;
                }
                totalInstruments++;
            }
        }

        // Use volume confluence as proxy for options agreement
        double volumeConf = family.getVolumeConfluence();

        // Calculate agreement ratio
        double agreementRatio = totalInstruments > 0 ?
            (double) (agreementCount + (volumeConf > 0.5 ? 1 : 0)) / (totalInstruments + 1) : 0;

        // Score based on family completeness and agreement
        // FIX: For commodities, having futures data is considered "complete enough"
        double completenessBonus = family.isComplete() ? 0.2 :
                                   (family.isCommodity() && family.isHasFuture()) ? 0.1 : 0;

        return maxPoints * (agreementRatio + completenessBonus);
    }
}
