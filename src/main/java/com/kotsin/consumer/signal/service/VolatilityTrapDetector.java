package com.kotsin.consumer.signal.service;

import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.signal.model.VTDOutput;
import com.kotsin.consumer.signal.model.VTDOutput.TrapType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * VolatilityTrapDetector - Module 10: VTD
 * 
 * Detects fake/unsustainable volatility expansion:
 * 1. ATR Expansion without Volume - price moves but volume doesn't confirm
 * 2. Spike Patterns - gap up/down then immediate fade
 * 3. OI Divergence - OI decreasing during "expansion" (closing positions)
 * 4. IV/ATR Divergence - implied vs realized mismatch (requires options data)
 * 
 * Traps indicate potential reversal or stall.
 */
@Slf4j
@Service
public class VolatilityTrapDetector {

    @Value("${vtd.lookback.period:14}")
    private int lookbackPeriod;

    @Value("${vtd.atr.expansion.threshold:1.5}")
    private double atrExpansionThreshold;

    @Value("${vtd.volume.confirmation.threshold:1.3}")
    private double volumeConfirmationThreshold;

    @Value("${vtd.spike.fade.threshold:0.5}")
    private double spikeFadeThreshold;

    /**
     * Calculate VTD from candle history
     */
    public VTDOutput calculate(
            String scripCode,
            String companyName,
            List<UnifiedCandle> candles,
            Double ivPercentile) {  // Optional IV from options

        if (candles == null || candles.size() < 10) {
            return emptyResult(scripCode, companyName);
        }

        UnifiedCandle current = candles.get(candles.size() - 1);
        int period = Math.min(lookbackPeriod, candles.size());

        // Calculate current and average ATR
        double currentATR = calculateCurrentATR(candles);
        double averageATR = calculateAverageATR(candles, period);
        double atrRatio = averageATR > 0 ? currentATR / averageATR : 1.0;

        // 1. ATR Expansion without Volume Confirmation
        double volumeConfirmation = calculateVolumeConfirmation(candles, period, atrRatio);

        // 2. Spike Pattern Detection
        double spikePattern = detectSpikePattern(candles);

        // 3. OI Divergence
        double oiDivergence = calculateOIDivergence(candles, period);

        // 4. IV/ATR Divergence (if IV available)
        double ivAtrDivergence = 0;
        if (ivPercentile != null && ivPercentile > 0) {
            ivAtrDivergence = calculateIVATRDivergence(atrRatio, ivPercentile);
        }

        // Calculate VTD score
        int trapSignals = 0;
        if (volumeConfirmation < 0.5) trapSignals++;
        if (spikePattern > 0.5) trapSignals++;
        if (oiDivergence > 0.5) trapSignals++;
        if (ivAtrDivergence > 0.5) trapSignals++;

        double vtdScore = 0.25 * (1 - volumeConfirmation)
                        + 0.30 * spikePattern
                        + 0.25 * oiDivergence
                        + 0.20 * ivAtrDivergence;
        vtdScore = Math.min(vtdScore, 1.0);

        // Determine trap type
        TrapType trapType;
        if (trapSignals >= 3) {
            trapType = TrapType.MAJOR_TRAP;
        } else if (ivAtrDivergence > 0.6 && ivPercentile != null && ivPercentile > 80) {
            trapType = TrapType.IV_CRUSH_RISK;
        } else if (spikePattern > 0.6) {
            trapType = TrapType.SPIKE_TRAP;
        } else if (volumeConfirmation < 0.4) {
            trapType = TrapType.VOLUME_TRAP;
        } else if (vtdScore > 0.3) {
            trapType = TrapType.MILD_DIVERGENCE;
        } else {
            trapType = TrapType.NO_TRAP;
        }

        boolean trapActive = trapType != TrapType.NO_TRAP;
        double vtdPenalty = trapType.getPenalty();

        return VTDOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(System.currentTimeMillis())
                .vtdScore(vtdScore)
                .vtdPenalty(vtdPenalty)
                .ivAtrDivergence(ivAtrDivergence)
                .volumeConfirmation(volumeConfirmation)
                .spikePattern(spikePattern)
                .oiDivergence(oiDivergence)
                .trapType(trapType)
                .trapActive(trapActive)
                .currentATR(currentATR)
                .averageATR(averageATR)
                .atrRatio(atrRatio)
                .impliedVolatility(ivPercentile != null ? ivPercentile : 0)
                .ivPercentile(ivPercentile != null ? ivPercentile : 0)
                .build();
    }

    /**
     * Calculate current ATR (5-period for responsiveness)
     */
    private double calculateCurrentATR(List<UnifiedCandle> candles) {
        int period = Math.min(5, candles.size());
        double sum = 0;

        for (int i = candles.size() - period; i < candles.size(); i++) {
            if (i < 0) continue;
            UnifiedCandle c = candles.get(i);
            double tr = c.getHigh() - c.getLow();
            
            if (i > 0) {
                double prevClose = candles.get(i - 1).getClose();
                tr = Math.max(tr, Math.abs(c.getHigh() - prevClose));
                tr = Math.max(tr, Math.abs(c.getLow() - prevClose));
            }
            sum += tr;
        }
        return sum / period;
    }

    /**
     * Calculate average ATR over period
     */
    private double calculateAverageATR(List<UnifiedCandle> candles, int period) {
        double sum = 0;

        for (int i = candles.size() - period; i < candles.size(); i++) {
            if (i < 0) continue;
            UnifiedCandle c = candles.get(i);
            double tr = c.getHigh() - c.getLow();
            
            if (i > 0) {
                double prevClose = candles.get(i - 1).getClose();
                tr = Math.max(tr, Math.abs(c.getHigh() - prevClose));
                tr = Math.max(tr, Math.abs(c.getLow() - prevClose));
            }
            sum += tr;
        }
        return sum / period;
    }

    /**
     * Check if volume confirms volatility expansion
     */
    private double calculateVolumeConfirmation(List<UnifiedCandle> candles, int period, double atrRatio) {
        if (atrRatio < atrExpansionThreshold) {
            return 1.0;  // No expansion, no confirmation needed
        }

        // Calculate volume ratio
        double avgVolume = 0;
        for (int i = candles.size() - period; i < candles.size() - 1; i++) {
            if (i >= 0) avgVolume += candles.get(i).getVolume();
        }
        avgVolume /= (period - 1);

        UnifiedCandle current = candles.get(candles.size() - 1);
        double volumeRatio = avgVolume > 0 ? current.getVolume() / avgVolume : 1.0;

        // Volume should expand proportionally to ATR
        double expectedVolumeRatio = atrRatio * 0.8;  // 80% of ATR expansion
        double confirmation = volumeRatio / expectedVolumeRatio;
        
        return Math.min(confirmation, 1.0);
    }

    /**
     * Detect spike and fade pattern
     */
    private double detectSpikePattern(List<UnifiedCandle> candles) {
        if (candles.size() < 3) return 0;

        UnifiedCandle c2 = candles.get(candles.size() - 3);
        UnifiedCandle c1 = candles.get(candles.size() - 2);
        UnifiedCandle c0 = candles.get(candles.size() - 1);

        double avgRange = (c2.getHigh() - c2.getLow() + c1.getHigh() - c1.getLow()) / 2;
        if (avgRange <= 0) return 0;

        // Check for gap spike
        double gapUp = c1.getOpen() - c2.getClose();
        double gapDown = c2.getClose() - c1.getOpen();
        double gap = Math.max(gapUp, gapDown);
        
        boolean hasSignificantGap = gap > avgRange * 0.5;

        if (hasSignificantGap) {
            // Check for fade
            double fadeAmount = 0;
            if (gapUp > 0) {
                // Gap up, check if faded down
                fadeAmount = (c1.getHigh() - c0.getClose()) / gap;
            } else if (gapDown > 0) {
                // Gap down, check if faded up
                fadeAmount = (c0.getClose() - c1.getLow()) / gap;
            }

            if (fadeAmount > spikeFadeThreshold) {
                return Math.min(fadeAmount, 1.0);
            }
        }

        // Also check for intrabar spike (long wick)
        double currentRange = c0.getHigh() - c0.getLow();
        if (currentRange > 0) {
            double body = Math.abs(c0.getClose() - c0.getOpen());
            double wickRatio = 1 - (body / currentRange);
            if (wickRatio > 0.7 && currentRange > avgRange * 1.5) {
                return wickRatio;
            }
        }

        return 0;
    }

    /**
     * Calculate OI divergence (decreasing OI during expansion = closing positions)
     */
    private double calculateOIDivergence(List<UnifiedCandle> candles, int period) {
        if (candles.size() < 3) return 0;

        UnifiedCandle current = candles.get(candles.size() - 1);
        UnifiedCandle prev = candles.get(candles.size() - 2);

        Double currentOiChange = current.getOiChangePercent();
        Double prevOiChange = prev.getOiChangePercent();

        if (currentOiChange == null || prevOiChange == null) return 0;

        // Check if price is moving but OI is decreasing
        double priceChange = Math.abs(current.getClose() - prev.getClose());
        double avgPrice = (current.getClose() + prev.getClose()) / 2;
        double priceChangePct = priceChange / avgPrice * 100;

        // Significant price move with declining OI = divergence
        if (priceChangePct > 0.5 && currentOiChange < 0) {
            return Math.min(Math.abs(currentOiChange) / 5, 1.0);  // Cap at 5% decline
        }

        return 0;
    }

    /**
     * Calculate IV/ATR divergence
     * High IV but low realized ATR = IV crush risk
     */
    private double calculateIVATRDivergence(double atrRatio, double ivPercentile) {
        // If IV is in top 20% but ATR is normal/low, there's divergence
        if (ivPercentile > 80 && atrRatio < 1.2) {
            return (ivPercentile - 60) / 40;  // Scale 60-100 to 0-1
        }
        // If IV is in top 50% and ATR is well below
        if (ivPercentile > 50 && atrRatio < 0.8) {
            return 0.3;
        }
        return 0;
    }

    private VTDOutput emptyResult(String scripCode, String companyName) {
        return VTDOutput.builder()
                .scripCode(scripCode)
                .companyName(companyName)
                .timestamp(System.currentTimeMillis())
                .vtdScore(0)
                .vtdPenalty(0)
                .trapType(TrapType.NO_TRAP)
                .trapActive(false)
                .volumeConfirmation(1.0)
                .build();
    }
}
