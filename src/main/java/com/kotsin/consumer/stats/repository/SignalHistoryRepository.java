package com.kotsin.consumer.stats.repository;

import com.kotsin.consumer.stats.model.SignalHistory;
import com.kotsin.consumer.stats.model.SignalHistory.SignalDirection;
import com.kotsin.consumer.stats.model.SignalHistory.SignalOutcome;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * SignalHistoryRepository - MongoDB repository for signal history persistence.
 */
@Repository
public interface SignalHistoryRepository extends MongoRepository<SignalHistory, String> {

    // ==================== FIND BY SYMBOL ====================

    List<SignalHistory> findBySymbolOrderByGeneratedAtDesc(String symbol);

    Page<SignalHistory> findBySymbol(String symbol, Pageable pageable);

    List<SignalHistory> findBySymbolAndGeneratedAtBetween(
        String symbol, Instant startTime, Instant endTime);

    // ==================== FIND BY SIGNAL TYPE ====================

    List<SignalHistory> findBySignalTypeOrderByGeneratedAtDesc(String signalType);

    List<SignalHistory> findBySymbolAndSignalType(String symbol, String signalType);

    // ==================== FIND BY OUTCOME ====================

    List<SignalHistory> findByOutcome(SignalOutcome outcome);

    List<SignalHistory> findBySymbolAndOutcome(String symbol, SignalOutcome outcome);

    @Query("{ 'symbol': ?0, 'profitLoss': { $gt: 0 } }")
    List<SignalHistory> findWinningSignals(String symbol);

    @Query("{ 'symbol': ?0, 'profitLoss': { $lt: 0 } }")
    List<SignalHistory> findLosingSignals(String symbol);

    // ==================== FIND BY DIRECTION ====================

    List<SignalHistory> findByDirection(SignalDirection direction);

    List<SignalHistory> findBySymbolAndDirection(String symbol, SignalDirection direction);

    // ==================== FIND BY TIME RANGE ====================

    List<SignalHistory> findByGeneratedAtBetween(Instant startTime, Instant endTime);

    @Query("{ 'generatedAt': { $gte: ?0 } }")
    List<SignalHistory> findRecentSignals(Instant since);

    // ==================== COUNT QUERIES ====================

    long countBySymbol(String symbol);

    long countBySignalType(String signalType);

    long countByOutcome(SignalOutcome outcome);

    long countBySymbolAndOutcome(String symbol, SignalOutcome outcome);

    // ==================== AGGREGATION QUERIES ====================

    @Aggregation(pipeline = {
        "{ $match: { 'symbol': ?0 } }",
        "{ $group: { _id: null, totalPnL: { $sum: '$profitLoss' }, count: { $sum: 1 } } }"
    })
    PnLSummary getTotalPnLBySymbol(String symbol);

    @Aggregation(pipeline = {
        "{ $match: { 'signalType': ?0 } }",
        "{ $group: { _id: null, totalPnL: { $sum: '$profitLoss' }, count: { $sum: 1 }, " +
        "wins: { $sum: { $cond: [{ $gt: ['$profitLoss', 0] }, 1, 0] } } } }"
    })
    TypePerformance getPerformanceBySignalType(String signalType);

    @Aggregation(pipeline = {
        "{ $group: { _id: '$symbol', totalPnL: { $sum: '$profitLoss' }, count: { $sum: 1 } } }",
        "{ $sort: { totalPnL: -1 } }"
    })
    List<SymbolPnL> getPnLBySymbolRanking();

    @Aggregation(pipeline = {
        "{ $group: { _id: '$signalType', totalPnL: { $sum: '$profitLoss' }, count: { $sum: 1 }, " +
        "wins: { $sum: { $cond: [{ $gt: ['$profitLoss', 0] }, 1, 0] } } } }",
        "{ $sort: { totalPnL: -1 } }"
    })
    List<TypePerformance> getPerformanceByAllTypes();

    // ==================== DELETE QUERIES ====================

    void deleteBySymbol(String symbol);

    void deleteByGeneratedAtBefore(Instant cutoffTime);

    // ==================== PROJECTION INTERFACES ====================

    interface PnLSummary {
        Double getTotalPnL();
        Integer getCount();
    }

    interface SymbolPnL {
        String getId();  // Symbol
        Double getTotalPnL();
        Integer getCount();
    }

    interface TypePerformance {
        String getId();  // Signal Type
        Double getTotalPnL();
        Integer getCount();
        Integer getWins();

        default double getWinRate() {
            return getCount() > 0 ? (double) getWins() / getCount() * 100 : 0;
        }
    }
}
