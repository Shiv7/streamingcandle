package com.kotsin.consumer.smc.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * LiquidityZone - Smart Money Concept liquidity detection.
 *
 * Liquidity pools exist where stop losses cluster:
 * - Above swing highs (buy-side liquidity)
 * - Below swing lows (sell-side liquidity)
 * - Equal highs/lows
 * - Round numbers
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LiquidityZone {

    private String symbol;
    private Instant timestamp;
    private String timeframe;

    // ==================== LIQUIDITY ZONE ====================
    private LiquidityType type;
    private double level;         // Main liquidity level
    private double zoneHigh;      // Zone upper bound
    private double zoneLow;       // Zone lower bound

    // ==================== CHARACTERISTICS ====================
    private int touchCount;       // Times price touched this level
    private boolean isSwept;      // Liquidity has been taken
    private Instant sweptAt;
    private double sweptPrice;    // Price that swept the liquidity

    // ==================== SOURCE ====================
    private LiquiditySource source;
    @Builder.Default
    private List<Instant> touchTimestamps = new ArrayList<>();
    private double totalVolume;   // Volume at touches

    // ==================== STRENGTH ====================
    private LiquidityStrength strength;
    private int ageInCandles;     // How old is this level
    private boolean isRejected;   // Price rejected at this level
    private int rejectionCount;

    // ==================== CONTEXT ====================
    private boolean hasOrderBlock;    // OB near this level
    private boolean hasFVG;           // FVG near this level
    private boolean isNearRoundNumber;
    private double nearestRoundNumber;

    // ==================== ENUMS ====================

    public enum LiquidityType {
        BUY_SIDE,    // Above swing highs (stops from shorts)
        SELL_SIDE    // Below swing lows (stops from longs)
    }

    public enum LiquiditySource {
        SWING_HIGH,
        SWING_LOW,
        EQUAL_HIGHS,
        EQUAL_LOWS,
        DOUBLE_TOP,
        DOUBLE_BOTTOM,
        ROUND_NUMBER,
        PDH_PDL,      // Previous day high/low
        PWH_PWL       // Previous week high/low
    }

    public enum LiquidityStrength {
        STRONG,      // Multiple touches, old level, confluence
        MODERATE,    // Clear swing point
        WEAK         // Single touch, new level
    }

    // ==================== HELPER METHODS ====================

    public boolean isBuySide() {
        return type == LiquidityType.BUY_SIDE;
    }

    public boolean isSellSide() {
        return type == LiquidityType.SELL_SIDE;
    }

    public boolean isStrong() {
        return strength == LiquidityStrength.STRONG;
    }

    public double getZoneSize() {
        return zoneHigh - zoneLow;
    }

    public boolean isPriceInZone(double price) {
        return price >= zoneLow && price <= zoneHigh;
    }

    public boolean isPriceNearLevel(double price, double tolerancePercent) {
        double tolerance = level * tolerancePercent / 100;
        return Math.abs(price - level) <= tolerance;
    }

    public double getDistanceFromLevel(double price) {
        return Math.abs(price - level);
    }

    public double getDistancePercent(double price) {
        return level > 0 ? Math.abs(price - level) / level * 100 : 0;
    }

    public void recordTouch(Instant time) {
        touchCount++;
        if (touchTimestamps == null) {
            touchTimestamps = new ArrayList<>();
        }
        touchTimestamps.add(time);
        updateStrength();
    }

    public void recordSweep(Instant time, double price) {
        isSwept = true;
        sweptAt = time;
        sweptPrice = price;
    }

    public void recordRejection() {
        isRejected = true;
        rejectionCount++;
        updateStrength();
    }

    private void updateStrength() {
        if (touchCount >= 3 || rejectionCount >= 2) {
            strength = LiquidityStrength.STRONG;
        } else if (touchCount >= 2 || rejectionCount >= 1) {
            strength = LiquidityStrength.MODERATE;
        } else {
            strength = LiquidityStrength.WEAK;
        }
    }

    /**
     * Check if this level creates confluence with another.
     */
    public boolean hasConfluenceWith(double otherLevel, double tolerancePercent) {
        return isPriceNearLevel(otherLevel, tolerancePercent);
    }
}
