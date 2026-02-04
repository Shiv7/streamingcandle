package com.kotsin.consumer.signal.repository;

import com.kotsin.consumer.signal.model.FudkiiScore;
import com.kotsin.consumer.signal.model.SignalState;
import com.kotsin.consumer.signal.model.TradingSignal;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * TradingSignalRepository - MongoDB repository for trading signals.
 *
 * Provides queries for:
 * - Active signal lookup by symbol
 * - Signal history by state
 * - Performance analytics
 */
@Repository
public interface TradingSignalRepository extends MongoRepository<TradingSignal, String> {

    // ==================== ACTIVE SIGNAL QUERIES ====================

    /**
     * Find active signal for a symbol (WATCH or ACTIVE state).
     */
    @Query("{ 'symbol': ?0, 'state': { $in: ['WATCH', 'ACTIVE'] } }")
    Optional<TradingSignal> findActiveBySymbol(String symbol);

    /**
     * Find all active signals (WATCH or ACTIVE state).
     */
    @Query("{ 'state': { $in: ['WATCH', 'ACTIVE'] } }")
    List<TradingSignal> findAllActive();

    /**
     * Find signals by state.
     */
    List<TradingSignal> findByState(SignalState state);

    /**
     * Find signals by state with pagination.
     */
    List<TradingSignal> findByStateOrderByCreatedAtDesc(SignalState state, Pageable pageable);

    /**
     * Find WATCH signals that have expired.
     */
    @Query("{ 'state': 'WATCH', 'watchExpiry': { $lt: ?0 } }")
    List<TradingSignal> findExpiredWatchSignals(Instant now);

    /**
     * Find ACTIVE signals that have expired.
     */
    @Query("{ 'state': 'ACTIVE', 'activeExpiry': { $lt: ?0 } }")
    List<TradingSignal> findExpiredActiveSignals(Instant now);

    // ==================== SYMBOL QUERIES ====================

    /**
     * Find signals for a symbol.
     */
    List<TradingSignal> findBySymbolOrderByCreatedAtDesc(String symbol, Pageable pageable);

    /**
     * Find completed signals for a symbol.
     */
    @Query("{ 'symbol': ?0, 'state': { $in: ['COMPLETE', 'EXPIRED'] } }")
    List<TradingSignal> findCompletedBySymbol(String symbol, Pageable pageable);

    /**
     * Find signals by symbol and timeframe.
     */
    List<TradingSignal> findBySymbolAndTimeframeOrderByCreatedAtDesc(
            String symbol, String timeframe, Pageable pageable);

    // ==================== HISTORY QUERIES ====================

    /**
     * Find signals in time range.
     */
    @Query("{ 'createdAt': { $gte: ?0, $lt: ?1 } }")
    List<TradingSignal> findByCreatedAtBetween(Instant start, Instant end);

    /**
     * Find completed signals in time range.
     */
    @Query("{ 'state': { $in: ['COMPLETE', 'EXPIRED'] }, 'completedAt': { $gte: ?0, $lt: ?1 } }")
    List<TradingSignal> findCompletedBetween(Instant start, Instant end);

    /**
     * Find signals by direction.
     */
    List<TradingSignal> findByDirectionOrderByCreatedAtDesc(
            FudkiiScore.Direction direction, Pageable pageable);

    /**
     * Find signals by outcome.
     */
    List<TradingSignal> findByOutcomeOrderByCompletedAtDesc(
            TradingSignal.Outcome outcome, Pageable pageable);

    // ==================== ANALYTICS QUERIES ====================

    /**
     * Count signals by state.
     */
    long countByState(SignalState state);

    /**
     * Count signals by outcome.
     */
    long countByOutcome(TradingSignal.Outcome outcome);

    /**
     * Count wins for a symbol.
     */
    @Query(value = "{ 'symbol': ?0, 'outcome': 'WIN' }", count = true)
    long countWinsBySymbol(String symbol);

    /**
     * Count losses for a symbol.
     */
    @Query(value = "{ 'symbol': ?0, 'outcome': 'LOSS' }", count = true)
    long countLossesBySymbol(String symbol);

    /**
     * Find top performing signals (by R-multiple).
     */
    @Query("{ 'outcome': 'WIN', 'rMultiple': { $gte: ?0 } }")
    List<TradingSignal> findTopPerformers(double minRMultiple, Pageable pageable);

    /**
     * Find signals with high confidence.
     */
    @Query("{ 'directionConfidence': { $gte: ?0 } }")
    List<TradingSignal> findHighConfidenceSignals(double minConfidence, Pageable pageable);

    // ==================== CLEANUP QUERIES ====================

    /**
     * Delete old completed signals.
     */
    void deleteByStateAndCompletedAtBefore(SignalState state, Instant before);

    /**
     * Find signals by signal ID.
     */
    Optional<TradingSignal> findBySignalId(String signalId);

    /**
     * Check if active signal exists for symbol.
     */
    @Query(value = "{ 'symbol': ?0, 'state': { $in: ['WATCH', 'ACTIVE'] } }", exists = true)
    boolean existsActiveBySymbol(String symbol);
}
