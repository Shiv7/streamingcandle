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
     */
    private double calculateSpotFuturePremiumScore(FamilyCandle family, double priceDirection) {
        double maxPoints = 5.0;

        Double premium = family.getSpotFuturePremium();
        if (premium == null) {
            return 0;
        }

        double threshold = config.getCrossInstrument().getPremiumSignificantPercent();

        // Check premium alignment with direction
        boolean aligned = (premium > threshold && priceDirection > 0) ||
                         (premium < -threshold && priceDirection < 0);

        if (aligned) {
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
     */
    private double calculateInstrumentAgreementScore(FamilyCandle family) {
        double maxPoints = 5.0;

        InstrumentCandle equity = family.getEquity();
        InstrumentCandle future = family.getFuture();

        int agreementCount = 0;
        int totalInstruments = 0;

        // Determine equity direction
        Boolean equityBullish = null;
        if (equity != null && equity.getOpen() > 0) {
            equityBullish = equity.getClose() > equity.getOpen();
            totalInstruments++;
        }

        // Check future agreement
        if (future != null && future.getOpen() > 0) {
            boolean futureBullish = future.getClose() > future.getOpen();
            totalInstruments++;
            if (equityBullish != null && futureBullish == equityBullish) {
                agreementCount++;
            }
        }

        // Use volume confluence as proxy for options agreement
        double volumeConf = family.getVolumeConfluence();

        // Calculate agreement ratio
        double agreementRatio = totalInstruments > 0 ?
            (double) (agreementCount + (volumeConf > 0.5 ? 1 : 0)) / (totalInstruments + 1) : 0;

        // Score based on family completeness and agreement
        double completenessBonus = family.isComplete() ? 0.2 : 0;

        return maxPoints * (agreementRatio + completenessBonus);
    }
}
