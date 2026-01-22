package com.kotsin.consumer.trading.model;

import com.kotsin.consumer.enrichment.signal.model.TradingSignal.Direction;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Position - Represents an active trading position.
 *
 * Tracks:
 * - Entry details (price, time, direction)
 * - Risk parameters (stop loss, targets)
 * - Current P&L
 * - Position management state
 */
@Data
@Builder
public class Position {

    // Identity
    private String positionId;
    private String familyId;
    private String scripCode;
    private String strategyId;
    private String signalId;

    // Direction
    private Direction direction;

    // Entry
    private double entryPrice;
    private Instant entryTime;
    private int quantity;

    // Risk Management
    private double initialStopLoss;
    private double currentStopLoss;  // Can be trailed
    private double target1;
    private double target2;
    private double riskAmount;       // entryPrice - stopLoss (for LONG)

    // Current State
    private double currentPrice;
    private double unrealizedPnL;
    private double maxFavorableExcursion;   // Best P&L reached
    private double maxAdverseExcursion;     // Worst P&L reached
    private boolean target1Hit;
    private int quantityRemaining;          // After partial exits

    // Time
    private Instant lastUpdateTime;
    private long positionDurationMs;

    /**
     * Update position with new price
     */
    public void updatePrice(double newPrice) {
        this.currentPrice = newPrice;
        this.lastUpdateTime = Instant.now();
        this.positionDurationMs = lastUpdateTime.toEpochMilli() - entryTime.toEpochMilli();

        // Calculate unrealized P&L
        if (direction == Direction.LONG) {
            this.unrealizedPnL = (newPrice - entryPrice) * quantityRemaining;
        } else {
            this.unrealizedPnL = (entryPrice - newPrice) * quantityRemaining;
        }

        // Track MFE/MAE
        if (unrealizedPnL > maxFavorableExcursion) {
            maxFavorableExcursion = unrealizedPnL;
        }
        if (unrealizedPnL < maxAdverseExcursion) {
            maxAdverseExcursion = unrealizedPnL;
        }
    }

    /**
     * Check if stop loss is hit
     */
    public boolean isStopLossHit() {
        if (direction == Direction.LONG) {
            return currentPrice <= currentStopLoss;
        } else {
            return currentPrice >= currentStopLoss;
        }
    }

    /**
     * Check if target 1 is hit
     */
    public boolean isTarget1Hit() {
        if (target1Hit) return false; // Already hit
        if (direction == Direction.LONG) {
            return currentPrice >= target1;
        } else {
            return currentPrice <= target1;
        }
    }

    /**
     * Check if target 2 is hit
     */
    public boolean isTarget2Hit() {
        if (target2 <= 0) return false;
        if (direction == Direction.LONG) {
            return currentPrice >= target2;
        } else {
            return currentPrice <= target2;
        }
    }

    /**
     * Trail stop loss (only moves in favorable direction)
     */
    public void trailStopLoss(double newStop) {
        if (direction == Direction.LONG) {
            if (newStop > currentStopLoss) {
                currentStopLoss = newStop;
            }
        } else {
            if (newStop < currentStopLoss) {
                currentStopLoss = newStop;
            }
        }
    }

    /**
     * Get R-multiple (current P&L / risk)
     */
    public double getRMultiple() {
        if (riskAmount == 0) return 0;
        return unrealizedPnL / Math.abs(riskAmount);
    }

    /**
     * Get P&L percentage
     */
    public double getPnLPercent() {
        if (entryPrice == 0) return 0;
        if (direction == Direction.LONG) {
            return ((currentPrice - entryPrice) / entryPrice) * 100;
        } else {
            return ((entryPrice - currentPrice) / entryPrice) * 100;
        }
    }

    /**
     * Create position from trading signal
     */
    public static Position fromSignal(com.kotsin.consumer.enrichment.signal.model.TradingSignal signal, int quantity) {
        double riskAmt = signal.getDirection() == Direction.LONG ?
                signal.getEntryPrice() - signal.getStopLoss() :
                signal.getStopLoss() - signal.getEntryPrice();

        return Position.builder()
                .positionId(java.util.UUID.randomUUID().toString())
                .familyId(signal.getFamilyId())
                .scripCode(signal.getScripCode())
                .strategyId(signal.getSetupId())
                .signalId(signal.getSignalId())
                .direction(signal.getDirection())
                .entryPrice(signal.getEntryPrice())
                .entryTime(Instant.now())
                .quantity(quantity)
                .quantityRemaining(quantity)
                .initialStopLoss(signal.getStopLoss())
                .currentStopLoss(signal.getStopLoss())
                .target1(signal.getTarget1())
                .target2(signal.getTarget2() > 0 ? signal.getTarget2() : 0)
                .riskAmount(riskAmt * quantity)
                .currentPrice(signal.getEntryPrice())
                .unrealizedPnL(0)
                .maxFavorableExcursion(0)
                .maxAdverseExcursion(0)
                .target1Hit(false)
                .lastUpdateTime(Instant.now())
                .positionDurationMs(0)
                .build();
    }
}
