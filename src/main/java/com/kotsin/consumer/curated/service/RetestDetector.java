package com.kotsin.consumer.curated.service;

import com.kotsin.consumer.curated.model.BreakoutBar;
import com.kotsin.consumer.curated.model.RetestEntry;
import com.kotsin.consumer.model.UnifiedCandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * RetestDetector - Detects when price retests the breakout pivot level
 *
 * After a breakout, we wait for price to pullback and retest the breakout level
 * (which becomes new support). This provides a better entry with lower risk.
 */
@Service
public class RetestDetector {

    private static final Logger log = LoggerFactory.getLogger(RetestDetector.class);

    private static final double PIVOT_TOLERANCE_PCT = 0.005;  // 0.5% tolerance
    private static final double MIN_RISK_REWARD = 1.5;

    /**
     * Detect retest at breakout pivot level
     *
     * Conditions:
     * 1. Current bar's low touches pivot (within tolerance)
     * 2. Current bar closes above pivot (buying pressure)
     * 3. Volume delta > 0 (buyers > sellers)
     * 4. OFI > 0 (order flow imbalance favors buyers)
     */
    public RetestEntry detectRetest(BreakoutBar breakout, UnifiedCandle currentCandle, double atr) {

        double pivotLevel = breakout.getPivotLevel();
        double currentLow = currentCandle.getLow();
        double currentClose = currentCandle.getClose();
        double currentHigh = currentCandle.getHigh();

        // 1. Check if low touches pivot (within tolerance)
        double tolerance = pivotLevel * PIVOT_TOLERANCE_PCT;
        boolean touchesPivot = Math.abs(currentLow - pivotLevel) <= tolerance;

        if (!touchesPivot) {
            return null;  // Not touching pivot
        }

        // 2. Check if closes above pivot (buying pressure)
        boolean closesAbovePivot = currentClose > pivotLevel;

        if (!closesAbovePivot) {
            log.debug("Retest rejected for {}: Closes below pivot", breakout.getScripCode());
            return null;
        }

        // 3. Check volume delta (buying pressure)
        boolean buyingPressure = currentCandle.getVolumeDelta() > 0;

        if (!buyingPressure) {
            log.debug("Retest rejected for {}: No buying pressure (volume delta)", breakout.getScripCode());
            return null;
        }

        // 4. Check OFI (order flow buying pressure)
        boolean ofiBuyingPressure = currentCandle.getOfi() > 0;

        if (!ofiBuyingPressure) {
            log.debug("Retest rejected for {}: No OFI buying pressure", breakout.getScripCode());
            return null;
        }

        // Calculate entry levels
        double entryPrice = currentClose;  // Enter at close
        double microprice = currentCandle.getMicroprice();

        // Use microprice if available and close to current close
        if (microprice > 0 && Math.abs(microprice - currentClose) < (atr * 0.1)) {
            entryPrice = microprice;
        }

        // Stop loss: Below pivot with safety margin (using ATR)
        double stopLoss = pivotLevel - atr;

        // Target: Measured move (add breakout range to breakout high)
        double breakoutRange = breakout.getBreakoutHigh() - breakout.getPivotLevel();
        double target = breakout.getBreakoutHigh() + breakoutRange;

        // Calculate risk/reward
        double riskAmount = entryPrice - stopLoss;
        double rewardAmount = target - entryPrice;
        double riskReward = riskAmount > 0 ? rewardAmount / riskAmount : 0;

        // Check minimum R:R
        if (riskReward < MIN_RISK_REWARD) {
            log.debug("Retest rejected for {}: Poor R:R={}", breakout.getScripCode(), riskReward);
            return null;
        }

        // ALL CONDITIONS MET - Valid retest entry!
        RetestEntry entry = RetestEntry.builder()
                .scripCode(breakout.getScripCode())
                .timestamp(currentCandle.getWindowEndMillis())
                .entryPrice(entryPrice)
                .stopLoss(stopLoss)
                .target(target)
                .riskReward(riskReward)
                .pivotLevel(pivotLevel)
                .microprice(microprice)
                .retestBarVolume(currentCandle.getVolume())
                .retestBarVolumeDelta(currentCandle.getVolumeDelta())
                .retestBarOFI(currentCandle.getOfi())
                .buyingPressure(true)
                .positionSizeMultiplier(1.0)  // Will be adjusted by scorer
                .build();

        log.info("✅ RETEST ENTRY CONFIRMED: {} @ {} | Stop={} | Target={} | R:R={}",
                breakout.getScripCode(),
                String.format("%.2f", entryPrice),
                String.format("%.2f", stopLoss),
                String.format("%.2f", target),
                String.format("%.2f", riskReward));

        return entry;
    }
}
