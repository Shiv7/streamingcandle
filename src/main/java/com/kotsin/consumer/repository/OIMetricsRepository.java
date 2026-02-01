package com.kotsin.consumer.repository;

import com.kotsin.consumer.model.OIMetrics;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * OIMetricsRepository - MongoDB repository for OI metrics.
 *
 * IMPORTANT: Queries should use scripCode (not symbol) to avoid mixing data
 * from different instruments with the same symbol name.
 */
@Repository
public interface OIMetricsRepository extends MongoRepository<OIMetrics, String> {

    /**
     * Find latest metrics for a symbol.
     */
    Optional<OIMetrics> findTopBySymbolOrderByTimestampDesc(String symbol);

    /**
     * Find latest metrics for a scripCode.
     */
    Optional<OIMetrics> findTopByScripCodeOrderByTimestampDesc(String scripCode);

    /**
     * Find metrics at specific timestamp.
     */
    Optional<OIMetrics> findBySymbolAndTimestamp(String symbol, Instant timestamp);

    /**
     * Find metrics by scripCode at specific timestamp.
     */
    Optional<OIMetrics> findByScripCodeAndTimestamp(String scripCode, Instant timestamp);

    /**
     * Find history (most recent first).
     */
    List<OIMetrics> findBySymbolOrderByTimestampDesc(String symbol, Pageable pageable);

    /**
     * Find history by scripCode (most recent first).
     */
    List<OIMetrics> findByScripCodeOrderByTimestampDesc(String scripCode, Pageable pageable);

    /**
     * Find metrics in time range.
     */
    @Query("{ 'symbol': ?0, 'timestamp': { $gte: ?1, $lt: ?2 } }")
    List<OIMetrics> findBySymbolAndTimestampBetween(String symbol, Instant start, Instant end);

    /**
     * Find metrics by scripCode in time range.
     */
    @Query("{ 'scripCode': ?0, 'timestamp': { $gte: ?1, $lt: ?2 } }")
    List<OIMetrics> findByScripCodeAndTimestampBetween(String scripCode, Instant start, Instant end);

    /**
     * Find all OI metrics for underlying (futures + options).
     */
    @Query("{ 'underlyingSymbol': ?0, 'timestamp': ?1 }")
    List<OIMetrics> findByUnderlyingSymbolAndTimestamp(String underlyingSymbol, Instant timestamp);

    /**
     * Check if metrics exist for symbol at timestamp.
     */
    boolean existsBySymbolAndTimestamp(String symbol, Instant timestamp);

    /**
     * Delete old metrics.
     */
    void deleteByTimestampBefore(Instant timestamp);
}
