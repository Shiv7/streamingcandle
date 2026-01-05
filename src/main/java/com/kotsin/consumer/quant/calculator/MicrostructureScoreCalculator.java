package com.kotsin.consumer.quant.calculator;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.OptionCandle;
import com.kotsin.consumer.quant.config.QuantScoreConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MicrostructureScoreCalculator - Calculates microstructure subscore (0-18 points).
 *
 * Evaluates:
 * - OFI momentum (0-4)
 * - VPIN informed trading (0-4)
 * - Depth imbalance consensus (0-3)
 * - Kyle Lambda liquidity (0-3)
 * - Aggressive volume direction (0-4)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MicrostructureScoreCalculator {

    private final QuantScoreConfig config;

    /**
     * Calculate microstructure subscore
     *
     * @param family FamilyCandle with options microstructure data
     * @return Score 0-18
     */
    public double calculate(FamilyCandle family) {
        List<OptionCandle> options = family.getOptions();
        if (options == null || options.isEmpty()) {
            return 0;
        }

        double maxScore = config.getWeight().getMicrostructure();

        double ofiScore = calculateOFIScore(options);
        double vpinScore = calculateVPINScore(options);
        double depthScore = calculateDepthImbalanceScore(options);
        double lambdaScore = calculateKyleLambdaScore(options);
        double aggVolScore = calculateAggressiveVolumeScore(options);

        return Math.min(maxScore, ofiScore + vpinScore + depthScore + lambdaScore + aggVolScore);
    }

    /**
     * Calculate OFI momentum score (0-4)
     */
    private double calculateOFIScore(List<OptionCandle> options) {
        double maxPoints = 4.0;
        double totalOFI = 0;
        int count = 0;

        for (OptionCandle opt : options) {
            if (opt != null) {
                totalOFI += opt.getOfi();
                count++;
            }
        }

        if (count == 0) return 0;

        double avgOFI = totalOFI / count;
        double threshold = config.getMicrostructure().getOfiSignificantThreshold();

        // Normalize using tanh for smooth scoring
        double normalized = Math.tanh(avgOFI / threshold);

        // Score based on magnitude (direction doesn't matter for opportunity detection)
        return maxPoints * Math.abs(normalized);
    }

    /**
     * Calculate VPIN informed trading score (0-4)
     */
    private double calculateVPINScore(List<OptionCandle> options) {
        double maxPoints = 4.0;
        double totalVPIN = 0;
        int count = 0;

        for (OptionCandle opt : options) {
            if (opt != null && opt.getVpin() > 0) {
                totalVPIN += opt.getVpin();
                count++;
            }
        }

        if (count == 0) return 0;

        double avgVPIN = totalVPIN / count;

        // High VPIN = informed trading = opportunity
        if (avgVPIN > config.getMicrostructure().getVpinHighThreshold()) {
            return maxPoints;
        } else if (avgVPIN > config.getMicrostructure().getVpinMediumThreshold()) {
            return maxPoints * 0.6;
        }

        // Low VPIN - less informed activity
        return maxPoints * 0.2;
    }

    /**
     * Calculate depth imbalance consensus score (0-3)
     */
    private double calculateDepthImbalanceScore(List<OptionCandle> options) {
        double maxPoints = 3.0;
        double totalImbalance = 0;
        int count = 0;

        for (OptionCandle opt : options) {
            if (opt != null) {
                totalImbalance += opt.getDepthImbalance();
                count++;
            }
        }

        if (count == 0) return 0;

        double avgImbalance = totalImbalance / count;
        double threshold = config.getMicrostructure().getDepthImbalanceSignificant();

        // Strong imbalance in either direction is actionable
        if (Math.abs(avgImbalance) > threshold) {
            double normalized = Math.min(1.0, Math.abs(avgImbalance) / (threshold * 2));
            return maxPoints * normalized;
        }

        return maxPoints * 0.2;
    }

    /**
     * Calculate Kyle Lambda liquidity score (0-3)
     */
    private double calculateKyleLambdaScore(List<OptionCandle> options) {
        double maxPoints = 3.0;
        double totalLambda = 0;
        int count = 0;

        for (OptionCandle opt : options) {
            if (opt != null && opt.getKyleLambda() > 0) {
                totalLambda += opt.getKyleLambda();
                count++;
            }
        }

        if (count == 0) return maxPoints * 0.5;  // No data - neutral

        double avgLambda = totalLambda / count;

        // Low lambda = liquid = good for execution
        if (avgLambda < config.getMicrostructure().getKyleLambdaLiquidThreshold()) {
            return maxPoints;
        } else if (avgLambda < config.getMicrostructure().getKyleLambdaLiquidThreshold() * 5) {
            return maxPoints * 0.6;
        }

        // High lambda = illiquid = execution risk
        return maxPoints * 0.2;
    }

    /**
     * Calculate aggressive volume direction score (0-4)
     */
    private double calculateAggressiveVolumeScore(List<OptionCandle> options) {
        double maxPoints = 4.0;
        long totalBuy = 0;
        long totalSell = 0;

        for (OptionCandle opt : options) {
            if (opt != null) {
                Long buy = opt.getAggressiveBuyVolume();
                Long sell = opt.getAggressiveSellVolume();
                if (buy != null) totalBuy += buy;
                if (sell != null) totalSell += sell;
            }
        }

        long total = totalBuy + totalSell;
        if (total == 0) return 0;

        double buyRatio = (double) totalBuy / total;
        double imbalance = Math.abs(buyRatio - 0.5) * 2;  // 0-1 scale

        // Strong directional aggression is actionable
        return maxPoints * imbalance;
    }
}
