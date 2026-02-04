package com.kotsin.consumer.papertrade.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * PaperTrade - Represents a simulated trade for paper trading.
 *
 * Tracks:
 * - Position details
 * - Entry/Exit information
 * - P&L calculations
 * - Risk management
 *
 * Persisted to MongoDB with indexes for:
 * - Fast lookup by tradeId, symbol, signalId
 * - Status-based queries for open/closed positions
 * - Time-range queries for history
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "paper_trades")
@CompoundIndexes({
    @CompoundIndex(name = "symbol_status_idx", def = "{'symbol': 1, 'status': 1}"),
    @CompoundIndex(name = "status_exitTime_idx", def = "{'status': 1, 'exitTime': -1}"),
    @CompoundIndex(name = "signalType_status_idx", def = "{'signalType': 1, 'status': 1}"),
    @CompoundIndex(name = "createdAt_idx", def = "{'createdAt': -1}")
})
public class PaperTrade {

    @Id
    private String id;

    // ==================== IDENTIFICATION ====================
    @Indexed(unique = true)
    private String tradeId;

    @Indexed
    private String symbol;

    @Indexed
    private String signalId;             // Link to originating signal

    @Indexed
    private String signalType;

    // ==================== POSITION DETAILS ====================
    private TradeDirection direction;
    private int quantity;
    private double lotSize;
    private double positionValue;        // Quantity * Entry Price

    // ==================== PRICES ====================
    private double entryPrice;
    private double exitPrice;
    private double averagePrice;         // For partial fills
    private double currentPrice;         // Last known price
    private double targetPrice;
    private double stopLoss;
    private double trailingStop;

    // ==================== TIMING ====================
    private Instant createdAt;
    private Instant entryTime;
    private Instant exitTime;
    private Instant lastUpdated;
    private long holdingPeriodMs;

    // ==================== P&L ====================
    private double unrealizedPnL;
    private double realizedPnL;
    private double unrealizedPnLPercent;
    private double realizedPnLPercent;
    private double maxProfit;            // Max favorable excursion
    private double maxLoss;              // Max adverse excursion
    private double commission;           // Simulated commission

    // ==================== STATUS ====================
    @Indexed
    private TradeStatus status;

    @Indexed
    private String exitReason;
    private int fillCount;               // Partial fills

    // ==================== RISK MANAGEMENT ====================
    private double riskAmount;           // Max loss allowed
    private double riskPercent;          // Risk as % of capital
    private double riskRewardRatio;
    private boolean isTrailingActive;
    private double trailingPercent;

    // ==================== ENUMS ====================

    public enum TradeDirection {
        LONG,
        SHORT
    }

    public enum TradeStatus {
        PENDING,         // Order placed, not filled
        OPEN,            // Position active
        PARTIAL,         // Partially filled/closed
        CLOSED,          // Position closed
        CANCELLED        // Order cancelled
    }

    // ==================== HELPER METHODS ====================

    public boolean isLong() {
        return direction == TradeDirection.LONG;
    }

    public boolean isShort() {
        return direction == TradeDirection.SHORT;
    }

    public boolean isOpen() {
        return status == TradeStatus.OPEN || status == TradeStatus.PARTIAL;
    }

    public boolean isClosed() {
        return status == TradeStatus.CLOSED;
    }

    public void updatePrice(double price) {
        this.currentPrice = price;
        this.lastUpdated = Instant.now();
        calculateUnrealizedPnL();
        updateMaxExcursion();
        checkTrailingStop();
    }

    private void calculateUnrealizedPnL() {
        if (!isOpen() || entryPrice == 0) return;

        if (isLong()) {
            unrealizedPnL = (currentPrice - entryPrice) * quantity;
        } else {
            unrealizedPnL = (entryPrice - currentPrice) * quantity;
        }
        unrealizedPnLPercent = entryPrice > 0 ? unrealizedPnL / (entryPrice * quantity) * 100 : 0;
    }

    private void updateMaxExcursion() {
        if (unrealizedPnL > maxProfit) {
            maxProfit = unrealizedPnL;
        }
        if (unrealizedPnL < -maxLoss) {
            maxLoss = Math.abs(unrealizedPnL);
        }
    }

    private void checkTrailingStop() {
        if (!isTrailingActive || trailingPercent <= 0) return;

        if (isLong() && unrealizedPnL > 0) {
            double newStop = currentPrice * (1 - trailingPercent / 100);
            if (newStop > trailingStop) {
                trailingStop = newStop;
            }
        } else if (isShort() && unrealizedPnL > 0) {
            double newStop = currentPrice * (1 + trailingPercent / 100);
            if (newStop < trailingStop || trailingStop == 0) {
                trailingStop = newStop;
            }
        }
    }

    public void fill(double price, int qty) {
        if (entryPrice == 0) {
            entryPrice = price;
            entryTime = Instant.now();
        } else {
            // Average price for partial fills
            double totalValue = (averagePrice * (quantity - qty)) + (price * qty);
            averagePrice = totalValue / quantity;
        }
        fillCount++;
        positionValue = quantity * entryPrice;
        status = TradeStatus.OPEN;
    }

    public void close(double price, String reason) {
        this.exitPrice = price;
        this.exitTime = Instant.now();
        this.exitReason = reason;
        this.status = TradeStatus.CLOSED;
        this.holdingPeriodMs = exitTime.toEpochMilli() - entryTime.toEpochMilli();

        // Calculate realized P&L
        if (isLong()) {
            realizedPnL = (exitPrice - entryPrice) * quantity - commission;
        } else {
            realizedPnL = (entryPrice - exitPrice) * quantity - commission;
        }
        realizedPnLPercent = positionValue > 0 ? realizedPnL / positionValue * 100 : 0;

        unrealizedPnL = 0;
        unrealizedPnLPercent = 0;
    }

    public boolean shouldStopOut(double high, double low) {
        double effectiveStop = isTrailingActive && trailingStop > 0 ?
            Math.max(stopLoss, trailingStop) : stopLoss;

        if (isLong()) {
            return low <= effectiveStop;
        } else {
            return high >= effectiveStop;
        }
    }

    public boolean shouldTargetHit(double high, double low) {
        if (isLong()) {
            return high >= targetPrice;
        } else {
            return low <= targetPrice;
        }
    }

    public double getEffectiveStop() {
        if (isTrailingActive && trailingStop > 0) {
            return isLong() ? Math.max(stopLoss, trailingStop) : Math.min(stopLoss, trailingStop);
        }
        return stopLoss;
    }

    public static String generateTradeId(String symbol, Instant time) {
        return String.format("PT_%s_%d", symbol, time.toEpochMilli());
    }
}
