package com.kotsin.consumer.signal.repository;

import com.kotsin.consumer.signal.model.FukaaAudit;
import com.kotsin.consumer.signal.model.FukaaAudit.FukaaOutcome;
import com.kotsin.consumer.signal.model.FukaaAudit.PassedCandle;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * FukaaAuditRepository - MongoDB repository for FUKAA audit trail.
 *
 * Provides queries for:
 * - Audit lookup by scripCode and time
 * - Analytics by outcome and passed candle
 * - Performance analysis
 */
@Repository
public interface FukaaAuditRepository extends MongoRepository<FukaaAudit, String> {

    // ==================== LOOKUP QUERIES ====================

    /**
     * Find audit records for a scripCode ordered by signal time.
     */
    List<FukaaAudit> findByScripCodeOrderBySignalTimeDesc(String scripCode, Pageable pageable);

    /**
     * Find audit records by outcome.
     */
    List<FukaaAudit> findByOutcomeOrderBySignalTimeDesc(FukaaOutcome outcome, Pageable pageable);

    /**
     * Find audit records by passed candle type.
     */
    List<FukaaAudit> findByPassedCandleOrderBySignalTimeDesc(PassedCandle passedCandle, Pageable pageable);

    /**
     * Find audit records within a time range.
     */
    List<FukaaAudit> findBySignalTimeBetweenOrderBySignalTimeDesc(Instant start, Instant end);

    // ==================== ANALYTICS QUERIES ====================

    /**
     * Count by outcome for statistics.
     */
    long countByOutcome(FukaaOutcome outcome);

    /**
     * Count by passed candle type.
     */
    long countByPassedCandle(PassedCandle passedCandle);

    /**
     * Find high-rank signals (top performers by volume surge).
     */
    @Query("{ 'outcome': { $in: ['IMMEDIATE_PASS', 'T_PLUS_1_PASS'] }, 'rank': { $gte: ?0 } }")
    List<FukaaAudit> findHighRankSignals(double minRank, Pageable pageable);

    /**
     * Find signals that passed at T+1 (for analyzing watching mode effectiveness).
     */
    @Query("{ 'outcome': 'T_PLUS_1_PASS' }")
    List<FukaaAudit> findTPlus1Passes(Pageable pageable);

    /**
     * Find expired signals (for analyzing filter strictness).
     */
    @Query("{ 'outcome': 'EXPIRED' }")
    List<FukaaAudit> findExpiredSignals(Pageable pageable);
}
