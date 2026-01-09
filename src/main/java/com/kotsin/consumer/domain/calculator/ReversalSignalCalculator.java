package com.kotsin.consumer.domain.calculator;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.domain.model.OptionCandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * ReversalSignalCalculator - Computes advanced reversal detection signals.
 *
 * This calculator transforms raw metrics into actionable interpretations:
 * 1. OFI Velocity & Exhaustion Detection
 * 2. Delta Divergence
 * 3. OI Interpretation (Long Buildup, Short Covering, etc.)
 * 4. Options Flow Analysis
 * 5. Composite Reversal Score
 *
 * The goal is to convert raw numbers into signals like:
 * - "SELLING_EXHAUSTION" instead of just "OFI = -338"
 * - "SHORT_COVERING" instead of "OI unchanged, price up"
 * - "HIGH_CONFIDENCE_REVERSAL" instead of separate indicators
 */
public class ReversalSignalCalculator {

    private static final Logger log = LoggerFactory.getLogger(ReversalSignalCalculator.class);

    // Thresholds for signal detection
    private static final double OFI_EXHAUSTION_THRESHOLD = -500;      // OFI below this considered significant selling
    private static final double OFI_VELOCITY_FLIP_THRESHOLD = 0;       // Velocity turning positive from negative
    private static final double VOLUME_SPIKE_THRESHOLD = 1.3;          // 30% volume increase
    private static final double BUY_PRESSURE_THRESHOLD = 0.55;         // 55% buy pressure
    private static final double CALL_SURGE_THRESHOLD = 0.10;           // 10% call premium increase
    private static final double PUT_DROP_THRESHOLD = -0.05;            // 5% put premium decrease
    private static final double PRICE_CHANGE_THRESHOLD = 0.001;        // 0.1% price change considered significant
    private static final double OI_CHANGE_THRESHOLD = 0.01;            // 1% OI change considered significant
    private static final double HIGH_CONFIDENCE_SCORE = 6.0;           // Score >= 6 is high confidence

    /**
     * Calculate all reversal signals for a FamilyCandle.
     * Requires previous candle state for velocity calculations.
     *
     * @param current Current candle to analyze
     * @param previousOfi Previous candle's OFI (null if first candle)
     * @param previousOfiVelocity Previous candle's OFI velocity (null if first/second candle)
     * @param previousVolume Previous candle's volume
     * @param previousCallPremium Previous ATM call close price
     * @param previousPutPremium Previous ATM put close price
     * @param sessionLow Session low price (for divergence detection)
     * @param sessionHigh Session high price (for divergence detection)
     */
    public static void calculate(
            FamilyCandle current,
            Double previousOfi,
            Double previousOfiVelocity,
            Long previousVolume,
            Double previousCallPremium,
            Double previousPutPremium,
            Double sessionLow,
            Double sessionHigh
    ) {
        if (current == null) return;

        InstrumentCandle primary = current.getPrimaryInstrumentOrFallback();
        if (primary == null) return;

        // Initialize signal list
        List<String> signals = new ArrayList<>();
        double score = 0;

        // 1. Calculate OFI Velocity & Exhaustion
        score += calculateOfiSignals(current, primary, previousOfi, previousOfiVelocity, signals);

        // 2. Calculate Delta Divergence
        score += calculateDeltaDivergence(current, primary, sessionLow, sessionHigh, signals);

        // 3. Calculate OI Interpretation
        calculateOiInterpretation(current, primary, signals);

        // 4. Calculate Options Flow Signals
        score += calculateOptionsFlow(current, previousCallPremium, previousPutPremium, signals);

        // 5. Calculate Volume Spike Signal
        score += calculateVolumeSignal(current, primary, previousVolume, signals);

        // 6. Add exhaustion bonus if detected
        if (current.isExhaustionDetected()) {
            score += 1;
        }

        // Set final reversal score
        current.setReversalScore(score);
        current.setReversalSignals(signals);
        current.setHighConfidenceReversal(score >= HIGH_CONFIDENCE_SCORE);

        // Log significant reversals
        if (score >= HIGH_CONFIDENCE_SCORE) {
            log.info("[REVERSAL-DETECTED] {} | Score: {} | Signals: {} | OFI: {} -> velocity: {} | OI: {}",
                    current.getFamilyId(),
                    String.format("%.1f", score),
                    signals,
                    primary.getOfi() != null ? String.format("%.0f", primary.getOfi()) : "N/A",
                    current.getOfiVelocity() != null ? String.format("%.0f", current.getOfiVelocity()) : "N/A",
                    current.getOiInterpretation());
        }
    }

    /**
     * Calculate OFI velocity, acceleration, and exhaustion signals.
     *
     * OFI Velocity = current OFI - previous OFI
     * OFI Acceleration = current velocity - previous velocity
     *
     * Exhaustion is detected when:
     * - SELLING_EXHAUSTION: OFI was very negative but velocity turns positive (sellers giving up)
     * - BUYING_EXHAUSTION: OFI was very positive but velocity turns negative (buyers giving up)
     */
    private static double calculateOfiSignals(
            FamilyCandle current,
            InstrumentCandle primary,
            Double previousOfi,
            Double previousOfiVelocity,
            List<String> signals
    ) {
        double score = 0;
        Double currentOfi = primary.getOfi();

        // Store previous OFI for next iteration
        current.setPreviousOfi(currentOfi);

        if (currentOfi == null) {
            current.setExhaustionType("NONE");
            return 0;
        }

        // Calculate velocity
        if (previousOfi != null) {
            double velocity = currentOfi - previousOfi;
            current.setOfiVelocity(velocity);

            // Calculate acceleration
            if (previousOfiVelocity != null) {
                double acceleration = velocity - previousOfiVelocity;
                current.setOfiAcceleration(acceleration);
            }

            // Detect OFI flip (most important signal - worth 3 points)
            boolean wasNegative = previousOfi < OFI_EXHAUSTION_THRESHOLD;
            boolean nowPositive = currentOfi > 0;
            boolean flipToPositive = wasNegative && nowPositive;

            boolean wasPositive = previousOfi > -OFI_EXHAUSTION_THRESHOLD; // Using inverse for buying
            boolean nowNegative = currentOfi < 0;
            boolean flipToNegative = wasPositive && nowNegative;

            if (flipToPositive) {
                signals.add("OFI_FLIP_POSITIVE");
                score += 3;
                log.debug("[OFI-FLIP] {} | Sellers exhausted | Previous: {} -> Current: {}",
                        current.getFamilyId(), previousOfi, currentOfi);
            } else if (flipToNegative) {
                signals.add("OFI_FLIP_NEGATIVE");
                score += 3;
                log.debug("[OFI-FLIP] {} | Buyers exhausted | Previous: {} -> Current: {}",
                        current.getFamilyId(), previousOfi, currentOfi);
            }

            // Detect exhaustion (velocity deceleration)
            // SELLING_EXHAUSTION: OFI was very negative but velocity turns positive
            if (previousOfi < OFI_EXHAUSTION_THRESHOLD && velocity > 0) {
                current.setExhaustionDetected(true);
                current.setExhaustionType("SELLING_EXHAUSTION");
                if (!signals.contains("OFI_FLIP_POSITIVE")) {
                    signals.add("SELLING_EXHAUSTION");
                    score += 1;
                }
                log.debug("[EXHAUSTION] {} | SELLING_EXHAUSTION | OFI: {} | Velocity: {} (turning positive)",
                        current.getFamilyId(), currentOfi, velocity);
            }
            // BUYING_EXHAUSTION: OFI was very positive but velocity turns negative
            else if (previousOfi > -OFI_EXHAUSTION_THRESHOLD && velocity < 0) {
                current.setExhaustionDetected(true);
                current.setExhaustionType("BUYING_EXHAUSTION");
                if (!signals.contains("OFI_FLIP_NEGATIVE")) {
                    signals.add("BUYING_EXHAUSTION");
                    score += 1;
                }
                log.debug("[EXHAUSTION] {} | BUYING_EXHAUSTION | OFI: {} | Velocity: {} (turning negative)",
                        current.getFamilyId(), currentOfi, velocity);
            } else {
                current.setExhaustionDetected(false);
                current.setExhaustionType("NONE");
            }
        } else {
            current.setExhaustionType("NONE");
        }

        return score;
    }

    /**
     * Calculate Delta Divergence.
     *
     * BULLISH_DIVERGENCE: Price makes new low but volume delta is positive (buyers stepping in)
     * BEARISH_DIVERGENCE: Price makes new high but volume delta is negative (sellers stepping in)
     */
    private static double calculateDeltaDivergence(
            FamilyCandle current,
            InstrumentCandle primary,
            Double sessionLow,
            Double sessionHigh,
            List<String> signals
    ) {
        double score = 0;

        // Calculate volume delta
        long buyVolume = primary.getBuyVolume();
        long sellVolume = primary.getSellVolume();
        long volumeDelta = buyVolume - sellVolume;

        double currentLow = primary.getLow();
        double currentHigh = primary.getHigh();

        // Check for bullish divergence: price at/near session low but buyers dominating
        if (sessionLow != null && currentLow <= sessionLow * 1.002 && volumeDelta > 0) {
            current.setDeltaDivergenceDetected(true);
            current.setDeltaDivergenceType("BULLISH_DIVERGENCE");
            signals.add("DELTA_DIVERGENCE_BULLISH");
            score += 1;
            log.debug("[DIVERGENCE] {} | BULLISH | Price at low {} but delta positive {}",
                    current.getFamilyId(), currentLow, volumeDelta);
        }
        // Check for bearish divergence: price at/near session high but sellers dominating
        else if (sessionHigh != null && currentHigh >= sessionHigh * 0.998 && volumeDelta < 0) {
            current.setDeltaDivergenceDetected(true);
            current.setDeltaDivergenceType("BEARISH_DIVERGENCE");
            signals.add("DELTA_DIVERGENCE_BEARISH");
            score += 1;
            log.debug("[DIVERGENCE] {} | BEARISH | Price at high {} but delta negative {}",
                    current.getFamilyId(), currentHigh, volumeDelta);
        } else {
            current.setDeltaDivergenceDetected(false);
            current.setDeltaDivergenceType("NONE");
        }

        return score;
    }

    /**
     * Calculate proper OI Interpretation based on price change + OI change.
     *
     * This fixes the issue where the system showed "NEUTRAL" when it should show "SHORT_COVERING".
     *
     * LONG_BUILDUP:    Price ↑ + OI ↑  (new longs entering - bullish continuation)
     * SHORT_COVERING:  Price ↑ + OI ↓  (shorts exiting - bullish, but may exhaust)
     * SHORT_BUILDUP:   Price ↓ + OI ↑  (new shorts entering - bearish continuation)
     * LONG_UNWINDING:  Price ↓ + OI ↓  (longs exiting - bearish, but may exhaust)
     */
    private static void calculateOiInterpretation(
            FamilyCandle current,
            InstrumentCandle primary,
            List<String> signals
    ) {
        InstrumentCandle future = current.getFuture();
        if (future == null || !future.hasOI()) {
            current.setOiInterpretation("NO_OI_DATA");
            current.setOiInterpretationConfidence(0.0);
            return;
        }

        // Get price change from primary instrument
        double priceChange = primary.getClose() - primary.getOpen();
        double priceChangePct = primary.getOpen() > 0 ? priceChange / primary.getOpen() : 0;

        // Get OI change from future
        Long oiChange = future.getOiChange();
        Double oiChangePct = future.getOiChangePercent();

        if (oiChange == null) {
            current.setOiInterpretation("NO_OI_CHANGE");
            current.setOiInterpretationConfidence(0.0);
            return;
        }

        // Determine interpretation
        boolean priceUp = priceChangePct > PRICE_CHANGE_THRESHOLD;
        boolean priceDown = priceChangePct < -PRICE_CHANGE_THRESHOLD;
        boolean oiUp = oiChange > 0;
        boolean oiDown = oiChange < 0;

        String interpretation;
        boolean suggestsReversal = false;

        if (priceUp && oiUp) {
            interpretation = "LONG_BUILDUP";
            // Long buildup is continuation, not reversal
        } else if (priceUp && oiDown) {
            interpretation = "SHORT_COVERING";
            // Short covering after downtrend suggests bullish reversal
            suggestsReversal = true;
            signals.add("OI_SHORT_COVERING");
        } else if (priceDown && oiUp) {
            interpretation = "SHORT_BUILDUP";
            // Short buildup is continuation, not reversal
        } else if (priceDown && oiDown) {
            interpretation = "LONG_UNWINDING";
            // Long unwinding after uptrend suggests bearish reversal
            suggestsReversal = true;
            signals.add("OI_LONG_UNWINDING");
        } else {
            interpretation = "NEUTRAL";
        }

        // Calculate confidence based on magnitude of changes
        double priceConfidence = Math.min(1.0, Math.abs(priceChangePct) / 0.01); // Max at 1% move
        double oiConfidence = oiChangePct != null ? Math.min(1.0, Math.abs(oiChangePct) / 5.0) : 0.5; // Max at 5% OI change
        double confidence = (priceConfidence + oiConfidence) / 2;

        current.setOiInterpretation(interpretation);
        current.setOiInterpretationConfidence(confidence);
        current.setOiSuggestsReversal(suggestsReversal);

        // Also update the legacy futuresBuildup field for compatibility
        current.setFuturesBuildup(interpretation);

        log.debug("[OI-INTERP] {} | {} | Price: {}% | OI: {} ({}%) | Confidence: {} | Reversal: {}",
                current.getFamilyId(), interpretation,
                String.format("%.2f", priceChangePct * 100),
                oiChange, oiChangePct != null ? String.format("%.2f", oiChangePct) : "N/A",
                String.format("%.2f", confidence),
                suggestsReversal);
    }

    /**
     * Calculate Options Flow signals.
     *
     * Detects:
     * - Call surge + Put drop = Bullish reversal (short squeeze)
     * - Put surge + Call drop = Bearish reversal
     */
    private static double calculateOptionsFlow(
            FamilyCandle current,
            Double previousCallPremium,
            Double previousPutPremium,
            List<String> signals
    ) {
        double score = 0;
        List<OptionCandle> options = current.getOptions();

        if (options == null || options.isEmpty()) {
            return 0;
        }

        // Find ATM CE and PE
        OptionCandle atmCE = null;
        OptionCandle atmPE = null;
        for (OptionCandle opt : options) {
            if (opt.isATM()) {
                if ("CE".equals(opt.getOptionType())) {
                    atmCE = opt;
                } else if ("PE".equals(opt.getOptionType())) {
                    atmPE = opt;
                }
            }
        }

        // Calculate premium changes
        if (atmCE != null && previousCallPremium != null && previousCallPremium > 0) {
            double callChange = (atmCE.getClose() - previousCallPremium) / previousCallPremium;
            current.setCallPremiumChange(callChange * 100); // Store as percentage

            if (callChange > CALL_SURGE_THRESHOLD) {
                signals.add(String.format("CALL_SURGE_%.0f%%", callChange * 100));
            }
        }

        if (atmPE != null && previousPutPremium != null && previousPutPremium > 0) {
            double putChange = (atmPE.getClose() - previousPutPremium) / previousPutPremium;
            current.setPutPremiumChange(putChange * 100); // Store as percentage

            if (putChange < PUT_DROP_THRESHOLD) {
                signals.add(String.format("PUT_DROP_%.0f%%", putChange * 100));
            }
        }

        // Check for options flow confirmation
        Double callChange = current.getCallPremiumChange();
        Double putChange = current.getPutPremiumChange();

        if (callChange != null && putChange != null) {
            // Bullish reversal: calls surge, puts drop
            if (callChange > CALL_SURGE_THRESHOLD * 100 && putChange < PUT_DROP_THRESHOLD * 100) {
                current.setOptionsFlowConfirmsReversal(true);
                signals.add("OPTIONS_FLOW_BULLISH");
                score += 2;

                // Check for short squeeze specifically
                InstrumentCandle primary = current.getPrimaryInstrumentOrFallback();
                if (primary != null) {
                    double buyPressure = primary.getVolume() > 0 ?
                            (double) primary.getBuyVolume() / primary.getVolume() : 0.5;
                    if (buyPressure > 0.6) {
                        current.setShortSqueezeDetected(true);
                        signals.add("SHORT_SQUEEZE_DETECTED");
                        log.info("[SHORT-SQUEEZE] {} | Call: +{}% | Put: {}% | BuyPressure: {}%",
                                current.getFamilyId(),
                                String.format("%.1f", callChange),
                                String.format("%.1f", putChange),
                                String.format("%.1f", buyPressure * 100));
                    }
                }
            }
            // Bearish reversal: puts surge, calls drop
            else if (putChange > CALL_SURGE_THRESHOLD * 100 && callChange < PUT_DROP_THRESHOLD * 100) {
                current.setOptionsFlowConfirmsReversal(true);
                signals.add("OPTIONS_FLOW_BEARISH");
                score += 2;
            }
        }

        return score;
    }

    /**
     * Calculate volume spike signal.
     *
     * Volume spike on reversal = high volume + flip to buy/sell pressure
     */
    private static double calculateVolumeSignal(
            FamilyCandle current,
            InstrumentCandle primary,
            Long previousVolume,
            List<String> signals
    ) {
        double score = 0;

        if (previousVolume == null || previousVolume == 0) {
            return 0;
        }

        long currentVolume = primary.getVolume();
        double volumeRatio = (double) currentVolume / previousVolume;

        // Calculate buy pressure
        double buyPressure = currentVolume > 0 ?
                (double) primary.getBuyVolume() / currentVolume : 0.5;

        // Volume spike with high buy pressure = bullish reversal signal
        if (volumeRatio > VOLUME_SPIKE_THRESHOLD && buyPressure > BUY_PRESSURE_THRESHOLD) {
            signals.add(String.format("VOLUME_SPIKE_%.0f%%_BUY_%.0f%%",
                    (volumeRatio - 1) * 100, buyPressure * 100));
            score += 2;
            log.debug("[VOLUME-SPIKE] {} | Volume: +{}% | BuyPressure: {}%",
                    current.getFamilyId(),
                    String.format("%.0f", (volumeRatio - 1) * 100),
                    String.format("%.0f", buyPressure * 100));
        }
        // Volume spike with high sell pressure = bearish reversal signal
        else if (volumeRatio > VOLUME_SPIKE_THRESHOLD && buyPressure < (1 - BUY_PRESSURE_THRESHOLD)) {
            signals.add(String.format("VOLUME_SPIKE_%.0f%%_SELL_%.0f%%",
                    (volumeRatio - 1) * 100, (1 - buyPressure) * 100));
            score += 2;
        }

        return score;
    }

    /**
     * Convenience method for stateless calculation (no velocity/previous data).
     * Use this when you don't have access to previous candle state.
     * OFI velocity and options premium change will not be calculated.
     */
    public static void calculateStateless(FamilyCandle current) {
        calculate(current, null, null, null, null, null, null, null);
    }
}
