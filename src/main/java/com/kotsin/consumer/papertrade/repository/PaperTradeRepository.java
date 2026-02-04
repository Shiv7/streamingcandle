package com.kotsin.consumer.papertrade.repository;

import com.kotsin.consumer.papertrade.model.PaperTrade;
import com.kotsin.consumer.papertrade.model.PaperTrade.TradeDirection;
import com.kotsin.consumer.papertrade.model.PaperTrade.TradeStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * PaperTradeRepository - MongoDB repository for paper trade persistence.
 *
 * Provides queries for:
 * - Open/Closed position lookup
 * - Trade history by symbol, signal, status
 * - Performance analytics (win rate, P&L)
 * - Wallet/Account reconciliation
 */
@Repository
public interface PaperTradeRepository extends MongoRepository<PaperTrade, String> {

    // ==================== POSITION QUERIES ====================

    /**
     * Find all open positions (OPEN or PARTIAL status).
     */
    @Query("{ 'status': { $in: ['OPEN', 'PARTIAL'] } }")
    List<PaperTrade> findAllOpenPositions();

    /**
     * Find open position for a symbol.
     */
    @Query("{ 'symbol': ?0, 'status': { $in: ['OPEN', 'PARTIAL'] } }")
    Optional<PaperTrade> findOpenPositionBySymbol(String symbol);

    /**
     * Find all positions by status.
     */
    List<PaperTrade> findByStatus(TradeStatus status);

    /**
     * Find positions by status with pagination.
     */
    List<PaperTrade> findByStatusOrderByCreatedAtDesc(TradeStatus status, Pageable pageable);

    /**
     * Check if open position exists for symbol.
     */
    @Query(value = "{ 'symbol': ?0, 'status': { $in: ['OPEN', 'PARTIAL'] } }", exists = true)
    boolean existsOpenPositionBySymbol(String symbol);

    // ==================== TRADE ID / SIGNAL QUERIES ====================

    /**
     * Find trade by tradeId.
     */
    Optional<PaperTrade> findByTradeId(String tradeId);

    /**
     * Find trades by signal ID (link to originating signal).
     */
    List<PaperTrade> findBySignalId(String signalId);

    /**
     * Find trades by signal type (FUDKII, PIVOT, etc.).
     */
    List<PaperTrade> findBySignalTypeOrderByCreatedAtDesc(String signalType, Pageable pageable);

    // ==================== SYMBOL QUERIES ====================

    /**
     * Find all trades for a symbol.
     */
    List<PaperTrade> findBySymbolOrderByCreatedAtDesc(String symbol, Pageable pageable);

    /**
     * Find closed trades for a symbol.
     */
    @Query("{ 'symbol': ?0, 'status': 'CLOSED' }")
    List<PaperTrade> findClosedBySymbol(String symbol, Pageable pageable);

    /**
     * Count trades for a symbol.
     */
    long countBySymbol(String symbol);

    // ==================== HISTORY QUERIES ====================

    /**
     * Find trades in time range.
     */
    @Query("{ 'createdAt': { $gte: ?0, $lt: ?1 } }")
    List<PaperTrade> findByCreatedAtBetween(Instant start, Instant end);

    /**
     * Find closed trades in time range.
     */
    @Query("{ 'status': 'CLOSED', 'exitTime': { $gte: ?0, $lt: ?1 } }")
    List<PaperTrade> findClosedBetween(Instant start, Instant end);

    /**
     * Find trades by direction.
     */
    List<PaperTrade> findByDirectionOrderByCreatedAtDesc(TradeDirection direction, Pageable pageable);

    /**
     * Find trades by exit reason.
     */
    List<PaperTrade> findByExitReasonOrderByExitTimeDesc(String exitReason, Pageable pageable);

    /**
     * Find recent closed trades.
     */
    @Query("{ 'status': 'CLOSED' }")
    List<PaperTrade> findRecentClosedTrades(Pageable pageable);

    // ==================== ANALYTICS QUERIES ====================

    /**
     * Count trades by status.
     */
    long countByStatus(TradeStatus status);

    /**
     * Count winning trades (positive realizedPnL).
     */
    @Query(value = "{ 'status': 'CLOSED', 'realizedPnL': { $gt: 0 } }", count = true)
    long countWinningTrades();

    /**
     * Count losing trades (negative realizedPnL).
     */
    @Query(value = "{ 'status': 'CLOSED', 'realizedPnL': { $lt: 0 } }", count = true)
    long countLosingTrades();

    /**
     * Count breakeven trades.
     */
    @Query(value = "{ 'status': 'CLOSED', 'realizedPnL': 0 }", count = true)
    long countBreakevenTrades();

    /**
     * Count wins by signal type.
     */
    @Query(value = "{ 'signalType': ?0, 'status': 'CLOSED', 'realizedPnL': { $gt: 0 } }", count = true)
    long countWinsBySignalType(String signalType);

    /**
     * Count losses by signal type.
     */
    @Query(value = "{ 'signalType': ?0, 'status': 'CLOSED', 'realizedPnL': { $lt: 0 } }", count = true)
    long countLossesBySignalType(String signalType);

    /**
     * Find trades with target hit.
     */
    @Query("{ 'exitReason': 'TARGET_HIT', 'status': 'CLOSED' }")
    List<PaperTrade> findTargetHitTrades(Pageable pageable);

    /**
     * Find trades with stop hit.
     */
    @Query("{ 'exitReason': 'STOP_HIT', 'status': 'CLOSED' }")
    List<PaperTrade> findStopHitTrades(Pageable pageable);

    // ==================== PERFORMANCE QUERIES ====================

    /**
     * Find best performing trades (by realized P&L).
     */
    @Query("{ 'status': 'CLOSED', 'realizedPnL': { $gt: 0 } }")
    List<PaperTrade> findTopPerformers(Pageable pageable);

    /**
     * Find worst performing trades.
     */
    @Query("{ 'status': 'CLOSED', 'realizedPnL': { $lt: 0 } }")
    List<PaperTrade> findWorstPerformers(Pageable pageable);

    /**
     * Find trades by minimum P&L percent.
     */
    @Query("{ 'status': 'CLOSED', 'realizedPnLPercent': { $gte: ?0 } }")
    List<PaperTrade> findByMinPnLPercent(double minPnLPercent, Pageable pageable);

    // ==================== CLEANUP QUERIES ====================

    /**
     * Delete old closed trades.
     */
    void deleteByStatusAndExitTimeBefore(TradeStatus status, Instant before);

    /**
     * Delete trades by symbol.
     */
    void deleteBySymbol(String symbol);
}
