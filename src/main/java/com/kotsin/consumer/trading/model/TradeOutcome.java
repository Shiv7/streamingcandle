package com.kotsin.consumer.trading.model;

import com.kotsin.consumer.enrichment.signal.model.TradingSignal.Direction;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * TradeOutcome - Records the complete result of a closed trade.
 *
 * Used for:
 * - Performance tracking
 * - Strategy evaluation
 * - Learning and optimization
 */
@Data
@Builder
public class TradeOutcome {

    // Identity
    private String tradeId;
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

    // Exit
    private double exitPrice;
    private Instant exitTime;
    private ExitReason exitReason;

    // P&L
    private double realizedPnL;
    private double pnlPercent;
    private double rMultiple;

    // Risk
    private double riskAmount;
    private double initialStopLoss;
    private double maxFavorableExcursion;
    private double maxAdverseExcursion;

    // Duration
    private long durationMs;

    // Outcome
    private boolean isWin;

    /**
     * Exit reasons
     */
    public enum ExitReason {
        STOP_LOSS_HIT,
        TARGET_1_HIT,
        TARGET_2_HIT,
        TIME_STOP,          // Max duration reached
        MANUAL_EXIT,
        INVALIDATION,       // Setup conditions invalidated
        END_OF_DAY,
        SYSTEM_SHUTDOWN
    }

    /**
     * Create outcome from position
     */
    public static TradeOutcome fromPosition(Position position, double exitPrice, ExitReason reason) {
        double pnl;
        if (position.getDirection() == Direction.LONG) {
            pnl = (exitPrice - position.getEntryPrice()) * position.getQuantityRemaining();
        } else {
            pnl = (position.getEntryPrice() - exitPrice) * position.getQuantityRemaining();
        }

        double pnlPct = (position.getDirection() == Direction.LONG) ?
                ((exitPrice - position.getEntryPrice()) / position.getEntryPrice()) * 100 :
                ((position.getEntryPrice() - exitPrice) / position.getEntryPrice()) * 100;

        double rMult = position.getRiskAmount() != 0 ? pnl / Math.abs(position.getRiskAmount()) : 0;

        long duration = Instant.now().toEpochMilli() - position.getEntryTime().toEpochMilli();

        return TradeOutcome.builder()
                .tradeId(position.getPositionId())
                .familyId(position.getFamilyId())
                .scripCode(position.getScripCode())
                .strategyId(position.getStrategyId())
                .signalId(position.getSignalId())
                .direction(position.getDirection())
                .entryPrice(position.getEntryPrice())
                .entryTime(position.getEntryTime())
                .quantity(position.getQuantityRemaining())
                .exitPrice(exitPrice)
                .exitTime(Instant.now())
                .exitReason(reason)
                .realizedPnL(pnl)
                .pnlPercent(pnlPct)
                .rMultiple(rMult)
                .riskAmount(position.getRiskAmount())
                .initialStopLoss(position.getInitialStopLoss())
                .maxFavorableExcursion(position.getMaxFavorableExcursion())
                .maxAdverseExcursion(position.getMaxAdverseExcursion())
                .durationMs(duration)
                .isWin(pnl > 0)
                .build();
    }

    /**
     * Get trade summary string
     */
    public String getSummary() {
        return String.format("%s %s %s | Entry=%.2f Exit=%.2f | P&L=%.2f (%.2f%%) | R=%.2f | %s | %s",
                familyId,
                direction,
                strategyId,
                entryPrice,
                exitPrice,
                realizedPnL,
                pnlPercent,
                rMultiple,
                exitReason,
                isWin ? "WIN" : "LOSS");
    }
}
