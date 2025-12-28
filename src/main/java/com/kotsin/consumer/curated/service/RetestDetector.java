package com.kotsin.consumer.curated.service;

import com.kotsin.consumer.curated.model.BreakoutBar;
import com.kotsin.consumer.curated.model.MultiTimeframeLevels;
import com.kotsin.consumer.curated.model.RetestEntry;
import com.kotsin.consumer.model.UnifiedCandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * RetestDetector - Detects when price retests the breakout pivot level
 *
 * After a breakout, we wait for price to pullback and retest the breakout level
 * (which becomes new support/resistance). This provides a better entry with lower risk.
 * 
 * FIX: Now supports both BULLISH and BEARISH retest detection
 */
@Service
public class RetestDetector {

    private static final Logger log = LoggerFactory.getLogger(RetestDetector.class);

    private static final double PIVOT_TOLERANCE_PCT = 0.005;  // 0.5% tolerance
    private static final double MIN_RISK_REWARD = 1.5;

    /**
     * Detect retest at breakout pivot level (auto-detects direction from breakout)
     *
     * @param breakout The breakout bar containing pivot and direction info
     * @param currentCandle Current candle to check for retest
     * @param atr Average True Range for stop calculation
     * @return RetestEntry if valid retest detected, null otherwise
     */
    public RetestEntry detectRetest(BreakoutBar breakout, UnifiedCandle currentCandle, double atr) {
        // BUG-FIX: Use BreakoutBar's direction field instead of inferring
        if (breakout.isBullish()) {
            return detectBullishRetest(breakout, currentCandle, atr);
        } else {
            return detectBearishRetest(breakout, currentCandle, atr);
        }
    }

    /**
     * Detect BULLISH retest - price pulls back to support after breakout up
     *
     * Conditions:
     * 1. Current bar's low touches pivot (within tolerance)
     * 2. Current bar closes above pivot (buying pressure)
     * 3. Volume delta > 0 (buyers > sellers)
     * 4. OFI > 0 (order flow imbalance favors buyers)
     */
    private RetestEntry detectBullishRetest(BreakoutBar breakout, UnifiedCandle currentCandle, double atr) {
        double pivotLevel = breakout.getPivotLevel();
        double currentLow = currentCandle.getLow();
        double currentClose = currentCandle.getClose();

        // 1. Check if low touches pivot (within tolerance)
        double tolerance = pivotLevel * PIVOT_TOLERANCE_PCT;
        boolean touchesPivot = Math.abs(currentLow - pivotLevel) <= tolerance;

        if (!touchesPivot) {
            return null;  // Not touching pivot
        }

        // 2. Check if closes above pivot (buying pressure)
        boolean closesAbovePivot = currentClose > pivotLevel;

        if (!closesAbovePivot) {
            log.debug("Bullish retest rejected for {}: Closes below pivot", breakout.getScripCode());
            return null;
        }

        // 3. Check volume delta (buying pressure)
        // BUG-FIX: Allow when volumeDelta == 0 (no data available)
        // Only reject if there's actual selling pressure (volumeDelta < 0)
        long volumeDelta = currentCandle.getVolumeDelta();
        boolean hasVolumeData = currentCandle.getBuyVolume() > 0 || currentCandle.getSellVolume() > 0;
        boolean buyingPressure = !hasVolumeData || volumeDelta >= 0;  // Allow if no data or buying

        if (!buyingPressure) {
            log.debug("Bullish retest rejected for {}: Selling pressure (volumeDelta={})", 
                    breakout.getScripCode(), volumeDelta);
            return null;
        }

        // 4. Check OFI (order flow buying pressure)
        // BUG-FIX: Allow when OFI == 0 (no data), not just NaN
        // Only reject if there's actual selling pressure (OFI < 0)
        double ofi = currentCandle.getOfi();
        boolean hasOFIData = !Double.isNaN(ofi) && ofi != 0;
        boolean ofiBuyingPressure = !hasOFIData || ofi > 0;  // Allow if no data or buying

        if (!ofiBuyingPressure) {
            log.debug("Bullish retest rejected for {}: OFI selling pressure (ofi={})", 
                    breakout.getScripCode(), ofi);
            return null;
        }

        // Calculate entry levels
        double entryPrice = currentClose;
        double microprice = currentCandle.getMicroprice();

        if (microprice > 0 && Math.abs(microprice - currentClose) < (atr * 0.1)) {
            entryPrice = microprice;
        }

        // Stop loss: Below pivot with safety margin
        double stopLoss = pivotLevel - atr;

        // Target: Measured move (add breakout range to breakout high)
        double breakoutRange = breakout.getBreakoutHigh() - breakout.getPivotLevel();
        double target = breakout.getBreakoutHigh() + breakoutRange;

        // Calculate risk/reward
        double riskAmount = entryPrice - stopLoss;
        double rewardAmount = target - entryPrice;
        double riskReward = riskAmount > 0 ? rewardAmount / riskAmount : 0;

        if (riskReward < MIN_RISK_REWARD) {
            log.debug("Bullish retest rejected for {}: Poor R:R={}", breakout.getScripCode(), riskReward);
            return null;
        }

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
                .retestBarOFI(ofi)
                .buyingPressure(true)
                .positionSizeMultiplier(1.0)
                .build();

        log.info("✅ BULLISH RETEST ENTRY: {} @ {} | Stop={} | Target={} | R:R={}",
                breakout.getScripCode(),
                String.format("%.2f", entryPrice),
                String.format("%.2f", stopLoss),
                String.format("%.2f", target),
                String.format("%.2f", riskReward));

        return entry;
    }

    /**
     * FIX: Detect BEARISH retest - price rallies back to resistance after breakdown
     *
     * Conditions:
     * 1. Current bar's high touches pivot (within tolerance)
     * 2. Current bar closes below pivot (selling pressure)
     * 3. Volume delta < 0 (sellers > buyers)
     * 4. OFI < 0 (order flow imbalance favors sellers)
     */
    private RetestEntry detectBearishRetest(BreakoutBar breakout, UnifiedCandle currentCandle, double atr) {
        double pivotLevel = breakout.getPivotLevel();
        double currentHigh = currentCandle.getHigh();
        double currentClose = currentCandle.getClose();

        // 1. Check if high touches pivot (within tolerance)
        double tolerance = pivotLevel * PIVOT_TOLERANCE_PCT;
        boolean touchesPivot = Math.abs(currentHigh - pivotLevel) <= tolerance;

        if (!touchesPivot) {
            return null;  // Not touching pivot
        }

        // 2. Check if closes below pivot (selling pressure)
        boolean closesBelowPivot = currentClose < pivotLevel;

        if (!closesBelowPivot) {
            log.debug("Bearish retest rejected for {}: Closes above pivot", breakout.getScripCode());
            return null;
        }

        // 3. Check volume delta (selling pressure)
        // BUG-FIX: Allow when volumeDelta == 0 (no data available)
        // Only reject if there's actual buying pressure (volumeDelta > 0)
        long volumeDelta = currentCandle.getVolumeDelta();
        boolean hasVolumeData = currentCandle.getBuyVolume() > 0 || currentCandle.getSellVolume() > 0;
        boolean sellingPressure = !hasVolumeData || volumeDelta <= 0;  // Allow if no data or selling

        if (!sellingPressure) {
            log.debug("Bearish retest rejected for {}: Buying pressure (volumeDelta={})", 
                    breakout.getScripCode(), volumeDelta);
            return null;
        }

        // 4. Check OFI (order flow selling pressure)
        // BUG-FIX: Allow when OFI == 0 (no data), not just NaN
        // Only reject if there's actual buying pressure (OFI > 0)
        double ofi = currentCandle.getOfi();
        boolean hasOFIData = !Double.isNaN(ofi) && ofi != 0;
        boolean ofiSellingPressure = !hasOFIData || ofi < 0;  // Allow if no data or selling

        if (!ofiSellingPressure) {
            log.debug("Bearish retest rejected for {}: OFI buying pressure (ofi={})", 
                    breakout.getScripCode(), ofi);
            return null;
        }

        // Calculate entry levels for SHORT
        double entryPrice = currentClose;
        double microprice = currentCandle.getMicroprice();

        if (microprice > 0 && Math.abs(microprice - currentClose) < (atr * 0.1)) {
            entryPrice = microprice;
        }

        // Stop loss: Above pivot with safety margin (for short)
        double stopLoss = pivotLevel + atr;

        // Target: Measured move DOWN (subtract breakdown range from breakdown low)
        double breakdownLow = breakout.getBreakoutLow() != 0 ? breakout.getBreakoutLow() : pivotLevel - atr;
        double breakdownRange = breakout.getPivotLevel() - breakdownLow;
        double target = breakdownLow - breakdownRange;

        // Calculate risk/reward (for short: risk = stop - entry, reward = entry - target)
        double riskAmount = stopLoss - entryPrice;
        double rewardAmount = entryPrice - target;
        double riskReward = riskAmount > 0 ? rewardAmount / riskAmount : 0;

        if (riskReward < MIN_RISK_REWARD) {
            log.debug("Bearish retest rejected for {}: Poor R:R={}", breakout.getScripCode(), riskReward);
            return null;
        }

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
                .retestBarOFI(ofi)
                .buyingPressure(false)  // FIX: Selling pressure for shorts
                .positionSizeMultiplier(1.0)
                .build();

        log.info("✅ BEARISH RETEST ENTRY (SHORT): {} @ {} | Stop={} | Target={} | R:R={}",
                breakout.getScripCode(),
                String.format("%.2f", entryPrice),
                String.format("%.2f", stopLoss),
                String.format("%.2f", target),
                String.format("%.2f", riskReward));

        return entry;
    }

    /**
     * 🆕 ENHANCED: Optimize entry/stop/target using multi-timeframe Fibonacci & Pivot levels
     *
     * This method refines the basic retest entry by:
     * 1. Entry: Align with Fibonacci retracement levels (0.618, 0.5, 0.382) if near
     * 2. Stop: Place below nearest multi-TF support (Pivot S1, Fib levels)
     * 3. Target: Aim for nearest multi-TF resistance (Pivot R1/R2, Fib extensions)
     * 4. R:R: Validate minimum 2:1, extend target if needed
     */
    public RetestEntry optimizeEntryWithLevels(RetestEntry basicEntry, MultiTimeframeLevels levels, double atr) {
        if (levels == null) {
            log.debug("No levels available for {}, using basic entry", basicEntry.getScripCode());
            return basicEntry;  // Return basic entry if no levels
        }

        String scripCode = basicEntry.getScripCode();
        double currentPrice = basicEntry.getEntryPrice();
        double tolerance = currentPrice * 0.01;  // 1% tolerance for level matching

        // 🆕 Step 1: Optimize Entry - Check if near significant Fibonacci level
        double optimizedEntry = currentPrice;
        String entryReason = "current price";

        // Check daily Fibonacci levels first (most relevant for intraday)
        if (levels.getDailyFib() != null) {
            MultiTimeframeLevels.FibonacciLevels dailyFib = levels.getDailyFib();

            if (Math.abs(currentPrice - dailyFib.getFib618()) <= tolerance) {
                optimizedEntry = dailyFib.getFib618();
                entryReason = "Daily Fib 0.618 (Golden ratio)";
            } else if (Math.abs(currentPrice - dailyFib.getFib50()) <= tolerance) {
                optimizedEntry = dailyFib.getFib50();
                entryReason = "Daily Fib 0.50";
            } else if (Math.abs(currentPrice - dailyFib.getFib382()) <= tolerance) {
                optimizedEntry = dailyFib.getFib382();
                entryReason = "Daily Fib 0.382";
            }
        }

        // Check daily pivot levels if no Fib match
        if (optimizedEntry == currentPrice && levels.getDailyPivot() != null) {
            MultiTimeframeLevels.PivotLevels dailyPivot = levels.getDailyPivot();

            if (Math.abs(currentPrice - dailyPivot.getS1()) <= tolerance) {
                optimizedEntry = dailyPivot.getS1();
                entryReason = "Daily Pivot S1";
            } else if (Math.abs(currentPrice - dailyPivot.getPivot()) <= tolerance) {
                optimizedEntry = dailyPivot.getPivot();
                entryReason = "Daily Pivot";
            }
        }

        // 🆕 Step 2: Optimize Stop - Find nearest support across all timeframes
        double nearestSupport = levels.getNearestSupport(currentPrice);
        double optimizedStop = nearestSupport > 0 ? nearestSupport - (0.5 * atr) : basicEntry.getStopLoss();

        // Ensure stop is below entry
        if (optimizedStop >= optimizedEntry) {
            optimizedStop = optimizedEntry - (1.5 * atr);  // Fallback to ATR-based stop
        }

        // 🆕 Step 3: Optimize Target - Find nearest resistance across all timeframes
        double nearestResistance = levels.getNearestResistance(currentPrice);
        double optimizedTarget = nearestResistance > 0 ? nearestResistance : basicEntry.getTarget();

        // 🆕 Step 4: Validate R:R - Minimum 2:1
        double riskAmount = optimizedEntry - optimizedStop;
        double rewardAmount = optimizedTarget - optimizedEntry;
        double riskReward = riskAmount > 0 ? rewardAmount / riskAmount : 0;

        // If R:R < 2.0, extend target to next resistance level
        if (riskReward < 2.0) {
            // Try to find a higher resistance level
            double extendedTarget = findNextResistance(levels, optimizedTarget);
            if (extendedTarget > optimizedTarget) {
                optimizedTarget = extendedTarget;
                rewardAmount = optimizedTarget - optimizedEntry;
                riskReward = riskAmount > 0 ? rewardAmount / riskAmount : 0;
                log.debug("Extended target to {} for better R:R", String.format("%.2f", extendedTarget));
            }
        }

        // Build optimized entry
        RetestEntry optimizedRetestEntry = RetestEntry.builder()
                .scripCode(scripCode)
                .timestamp(basicEntry.getTimestamp())
                .entryPrice(optimizedEntry)
                .stopLoss(optimizedStop)
                .target(optimizedTarget)
                .riskReward(riskReward)
                .pivotLevel(basicEntry.getPivotLevel())
                .microprice(basicEntry.getMicroprice())
                .retestBarVolume(basicEntry.getRetestBarVolume())
                .retestBarVolumeDelta(basicEntry.getRetestBarVolumeDelta())
                .retestBarOFI(basicEntry.getRetestBarOFI())
                .buyingPressure(basicEntry.isBuyingPressure())
                .positionSizeMultiplier(basicEntry.getPositionSizeMultiplier())
                .build();

        log.info("🎯 ENTRY OPTIMIZED WITH LEVELS: {} | Entry {} -> {} ({}) | Stop {} -> {} | Target {} -> {} | R:R {} -> {}",
                scripCode,
                String.format("%.2f", basicEntry.getEntryPrice()),
                String.format("%.2f", optimizedEntry),
                entryReason,
                String.format("%.2f", basicEntry.getStopLoss()),
                String.format("%.2f", optimizedStop),
                String.format("%.2f", basicEntry.getTarget()),
                String.format("%.2f", optimizedTarget),
                String.format("%.2f", basicEntry.getRiskReward()),
                String.format("%.2f", riskReward));

        return optimizedRetestEntry;
    }

    /**
     * Find next higher resistance level beyond current target
     */
    private double findNextResistance(MultiTimeframeLevels levels, double currentTarget) {
        double nextResistance = Double.MAX_VALUE;

        // Check daily pivots
        if (levels.getDailyPivot() != null) {
            MultiTimeframeLevels.PivotLevels pivot = levels.getDailyPivot();
            if (pivot.getR1() > currentTarget && pivot.getR1() < nextResistance) nextResistance = pivot.getR1();
            if (pivot.getR2() > currentTarget && pivot.getR2() < nextResistance) nextResistance = pivot.getR2();
            if (pivot.getR3() > currentTarget && pivot.getR3() < nextResistance) nextResistance = pivot.getR3();
        }

        // Check weekly pivots
        if (levels.getWeeklyPivot() != null) {
            MultiTimeframeLevels.PivotLevels pivot = levels.getWeeklyPivot();
            if (pivot.getR1() > currentTarget && pivot.getR1() < nextResistance) nextResistance = pivot.getR1();
            if (pivot.getR2() > currentTarget && pivot.getR2() < nextResistance) nextResistance = pivot.getR2();
        }

        // Check Fibonacci extensions
        if (levels.getDailyFib() != null) {
            MultiTimeframeLevels.FibonacciLevels fib = levels.getDailyFib();
            if (fib.getFib1272() > currentTarget && fib.getFib1272() < nextResistance) nextResistance = fib.getFib1272();
            if (fib.getFib1618() > currentTarget && fib.getFib1618() < nextResistance) nextResistance = fib.getFib1618();
            if (fib.getFib200() > currentTarget && fib.getFib200() < nextResistance) nextResistance = fib.getFib200();
        }

        return nextResistance == Double.MAX_VALUE ? currentTarget : nextResistance;
    }
}
