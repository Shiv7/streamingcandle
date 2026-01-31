package com.kotsin.consumer.domain.validator;

import com.kotsin.consumer.domain.model.DataQuality;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import lombok.extern.slf4j.Slf4j;

/**
 * TradeOrderbookValidator - Cross-validates trade imbalance against OFI.
 * 
 * This is a critical data quality check:
 * - Trade imbalance = buyVolume - sellVolume (from tick classification)
 * - OFI = Order Flow Imbalance (from orderbook changes)
 * 
 * If these two metrics conflict (opposite signs), the data quality is questionable.
 * 
 * Validation Levels:
 * - VALID: Both agree on direction and have high correlation
 * - WARNING: Low correlation but same direction
 * - CONFLICT: Opposite signs - one says buying, other says selling
 * - STALE: Missing or old data
 */
@Slf4j
public class TradeOrderbookValidator {

    // Thresholds for validation
    private static final double MIN_CORRELATION = 0.3;
    private static final double HIGH_CORRELATION = 0.7;
    private static final double EPSILON = 0.05;  // FIX: Increased from 0.01 to 0.05 to ignore small noise

    // FIX: Use RELATIVE thresholds instead of absolute values
    // This works correctly for both high-volume and low-volume instruments
    private static final double MIN_TRADE_IMBALANCE_RATIO = 0.20;  // 20% of total volume
    private static final double MIN_OFI_RATIO = 0.15;  // 15% of average orderbook depth

    /**
     * Validation result with quality and reason
     */
    public static class ValidationResult {
        public final DataQuality quality;
        public final String reason;
        public final double correlation;

        public ValidationResult(DataQuality quality, String reason, double correlation) {
            this.quality = quality;
            this.reason = reason;
            this.correlation = correlation;
        }
    }

    /**
     * Validate trade imbalance against OFI
     *
     * @param candle InstrumentCandle with both trade and orderbook data
     * @return ValidationResult with quality classification
     */
    public static ValidationResult validate(InstrumentCandle candle) {
        if (candle == null) {
            return new ValidationResult(DataQuality.STALE, "Null candle", 0.0);
        }

        // If no orderbook data, can't validate
        if (!candle.hasOrderbook()) {
            return new ValidationResult(DataQuality.INSUFFICIENT, 
                "No orderbook data available for validation", 0.0);
        }

        Double ofi = candle.getOfi();
        if (ofi == null) {
            return new ValidationResult(DataQuality.INSUFFICIENT,
                "OFI is null", 0.0);
        }

        long tradeImbalance = candle.getVolumeDelta();  // buyVolume - sellVolume

        // Check for tiny values (noise)
        boolean tradeNearZero = Math.abs(tradeImbalance) < candle.getVolume() * EPSILON;
        boolean ofiNearZero = Math.abs(ofi) < 1.0;

        if (tradeNearZero && ofiNearZero) {
            return new ValidationResult(DataQuality.VALID, 
                "Both trade and OFI neutral", 0.0);
        }

        // Check directional agreement
        boolean tradePositive = tradeImbalance > 0;
        boolean ofiPositive = ofi > 0;

        // FIX: Use RELATIVE thresholds - significant relative to the instrument's activity
        // Trade imbalance is significant if it's >= 20% of total volume
        long totalVolume = candle.getVolume();
        double tradeImbalanceRatio = totalVolume > 0 ? Math.abs(tradeImbalance) / (double) totalVolume : 0.0;
        boolean tradeSignificant = tradeImbalanceRatio >= MIN_TRADE_IMBALANCE_RATIO;

        // OFI is significant if it's >= 15% of average orderbook depth
        Double avgBidDepth = candle.getAverageBidDepth();
        Double avgAskDepth = candle.getAverageAskDepth();
        double avgDepth = (avgBidDepth != null && avgAskDepth != null)
            ? (avgBidDepth + avgAskDepth) / 2
            : 10000.0;  // Fallback if no depth data
        double ofiRatio = avgDepth > 0 ? Math.abs(ofi) / avgDepth : 0.0;
        boolean ofiSignificant = ofiRatio >= MIN_OFI_RATIO;

        if (tradePositive != ofiPositive && !tradeNearZero && !ofiNearZero) {
            // Only CONFLICT if both signals are significant relative to the instrument's activity
            if (tradeSignificant && ofiSignificant) {
                // CONFLICT: Trade says buy, OFI says sell (or vice versa) with significant magnitude
                return new ValidationResult(DataQuality.CONFLICT,
                    String.format("Trade imbalance %d (%.1f%% of vol) conflicts with OFI %.2f (%.1f%% of depth)",
                        tradeImbalance, tradeImbalanceRatio * 100, ofi, ofiRatio * 100),
                    -0.5);  // Negative correlation indicates conflict
            } else {
                // FIX: Downgrade to WARNING if signals are relatively weak
                return new ValidationResult(DataQuality.WARNING,
                    String.format("Weak directional conflict: trade=%d (%.1f%%), ofi=%.2f (%.1f%%)",
                        tradeImbalance, tradeImbalanceRatio * 100, ofi, ofiRatio * 100),
                    -0.2);
            }
        }

        // Same direction - calculate normalized correlation
        double normalizedTrade = normalizeImbalance(tradeImbalance, candle.getVolume());
        double normalizedOfi = normalizeOfi(ofi, candle.getAverageBidDepth(), candle.getAverageAskDepth());

        // Simple correlation estimate (same sign = positive)
        double correlation = Math.min(Math.abs(normalizedTrade), Math.abs(normalizedOfi));
        if (normalizedTrade * normalizedOfi < 0) {
            correlation = -correlation;
        }

        if (correlation >= HIGH_CORRELATION) {
            return new ValidationResult(DataQuality.VALID,
                String.format("High agreement: trade=%.2f, ofi=%.2f", 
                    normalizedTrade, normalizedOfi),
                correlation);
        } else if (correlation >= MIN_CORRELATION) {
            return new ValidationResult(DataQuality.WARNING,
                String.format("Low correlation: trade=%.2f, ofi=%.2f", 
                    normalizedTrade, normalizedOfi),
                correlation);
        } else {
            return new ValidationResult(DataQuality.WARNING,
                String.format("Very low correlation but same direction: trade=%.2f, ofi=%.2f", 
                    normalizedTrade, normalizedOfi),
                correlation);
        }
    }

    /**
     * Validate and update candle quality
     *
     * @param candle InstrumentCandle to validate
     * @return Updated InstrumentCandle with quality set
     */
    public static InstrumentCandle validateAndUpdate(InstrumentCandle candle) {
        ValidationResult result = validate(candle);
        candle.setQuality(result.quality);
        candle.setQualityReason(result.reason);
        return candle;
    }

    /**
     * Normalize trade imbalance to [-1, 1] range
     */
    private static double normalizeImbalance(long imbalance, long totalVolume) {
        if (totalVolume <= 0) return 0.0;
        return Math.max(-1.0, Math.min(1.0, (double) imbalance / totalVolume));
    }

    /**
     * Normalize OFI to [-1, 1] range based on average depth
     */
    private static double normalizeOfi(double ofi, Double avgBidDepth, Double avgAskDepth) {
        double avgDepth = 0.0;
        if (avgBidDepth != null && avgAskDepth != null) {
            avgDepth = (avgBidDepth + avgAskDepth) / 2;
        }
        if (avgDepth <= 0) {
            avgDepth = 10000;  // Default fallback
        }
        return Math.max(-1.0, Math.min(1.0, ofi / avgDepth));
    }

    /**
     * Quick check if trade and OFI agree on direction
     *
     * @param tradeImbalance buyVolume - sellVolume
     * @param ofi Order Flow Imbalance
     * @return True if same sign (both positive or both negative)
     */
    public static boolean agreeOnDirection(long tradeImbalance, double ofi) {
        if (Math.abs(tradeImbalance) < 100 || Math.abs(ofi) < 1.0) {
            return true;  // Tiny values = no signal
        }
        return (tradeImbalance > 0) == (ofi > 0);
    }

    /**
     * Calculate simple agreement score
     *
     * @param tradeImbalance Trade volume imbalance
     * @param volume Total volume
     * @param ofi Order Flow Imbalance
     * @param avgDepth Average orderbook depth
     * @return Agreement score 0.0 to 1.0
     */
    public static double calculateAgreementScore(long tradeImbalance, long volume, 
                                                   double ofi, double avgDepth) {
        double normTrade = normalizeImbalance(tradeImbalance, volume);
        double normOfi = normalizeOfi(ofi, avgDepth, avgDepth);

        // If same sign, agreement is the minimum of absolute values
        if (normTrade * normOfi > 0) {
            return Math.min(Math.abs(normTrade), Math.abs(normOfi));
        }
        // Opposite signs = no agreement
        return 0.0;
    }
}
