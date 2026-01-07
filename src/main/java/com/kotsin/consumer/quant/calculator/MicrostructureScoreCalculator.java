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
     *
     * ENHANCED: When OFI is 0 or missing, use depth imbalance as a proxy
     * because depth imbalance indicates order flow direction.
     */
    private double calculateOFIScore(List<OptionCandle> options) {
        double maxPoints = 4.0;
        double totalOFI = 0;
        double totalDepthImbalance = 0;
        int ofiCount = 0;
        int depthCount = 0;

        for (OptionCandle opt : options) {
            if (opt != null) {
                // Primary: use OFI if available and non-zero
                if (opt.getOfi() != null && opt.getOfi() != 0) {
                    totalOFI += opt.getOfi();
                    ofiCount++;
                }
                // Fallback: collect depth imbalance for proxy calculation
                if (opt.getDepthImbalance() != null) {
                    totalDepthImbalance += opt.getDepthImbalance();
                    depthCount++;
                }
            }
        }

        // If we have valid OFI data, use it
        if (ofiCount > 0) {
            double avgOFI = totalOFI / ofiCount;
            double threshold = config.getMicrostructure().getOfiSignificantThreshold();
            double normalized = Math.tanh(avgOFI / threshold);
            return maxPoints * Math.abs(normalized);
        }

        // FALLBACK: Use depth imbalance as OFI proxy
        // Depth imbalance is [-1, +1], represents order book pressure
        if (depthCount > 0) {
            double avgDepth = totalDepthImbalance / depthCount;
            // Strong depth imbalance indicates order flow pressure
            // Give partial credit (up to 60% of max score)
            return maxPoints * 0.6 * Math.abs(avgDepth);
        }

        // No data available
        return 0;
    }

    /**
     * Calculate VPIN informed trading score (0-4)
     */
    private double calculateVPINScore(List<OptionCandle> options) {
        double maxPoints = 4.0;
        double totalVPIN = 0;
        int count = 0;

        for (OptionCandle opt : options) {
            if (opt != null && opt.getVpin() != null && opt.getVpin() > 0) {
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
            if (opt != null && opt.getDepthImbalance() != null) {
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
     *
     * ENHANCED: When Kyle Lambda is missing, use bid-ask spread as a liquidity proxy.
     * Tight spreads indicate good liquidity (low price impact).
     */
    private double calculateKyleLambdaScore(List<OptionCandle> options) {
        double maxPoints = 3.0;
        double totalLambda = 0;
        double totalSpread = 0;
        double totalPrice = 0;
        int lambdaCount = 0;
        int spreadCount = 0;

        for (OptionCandle opt : options) {
            if (opt != null) {
                // Primary: use Kyle Lambda if available
                if (opt.getKyleLambda() != null && opt.getKyleLambda() > 0) {
                    totalLambda += opt.getKyleLambda();
                    lambdaCount++;
                }
                // Fallback: collect spread data
                if (opt.getBidAskSpread() != null && opt.getBidAskSpread() > 0 && opt.getClose() > 0) {
                    totalSpread += opt.getBidAskSpread();
                    totalPrice += opt.getClose();
                    spreadCount++;
                }
            }
        }

        // If we have valid Kyle Lambda data, use it
        if (lambdaCount > 0) {
            double avgLambda = totalLambda / lambdaCount;
            if (avgLambda < config.getMicrostructure().getKyleLambdaLiquidThreshold()) {
                return maxPoints;
            } else if (avgLambda < config.getMicrostructure().getKyleLambdaLiquidThreshold() * 5) {
                return maxPoints * 0.6;
            }
            return maxPoints * 0.2;
        }

        // FALLBACK: Use bid-ask spread as liquidity proxy
        // Lower spread relative to price = better liquidity = higher score
        if (spreadCount > 0 && totalPrice > 0) {
            double avgSpread = totalSpread / spreadCount;
            double avgPrice = totalPrice / spreadCount;
            double spreadPercent = avgSpread / avgPrice;

            // Spreads: <0.5% = very liquid, <1% = liquid, <2% = moderate, >2% = illiquid
            if (spreadPercent < 0.005) {
                return maxPoints;
            } else if (spreadPercent < 0.01) {
                return maxPoints * 0.8;
            } else if (spreadPercent < 0.02) {
                return maxPoints * 0.5;
            } else {
                return maxPoints * 0.2;
            }
        }

        // No data - return neutral score
        return maxPoints * 0.5;
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
