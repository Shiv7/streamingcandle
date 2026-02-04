package com.kotsin.consumer.repository;

import com.kotsin.consumer.model.TickCandle;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * TickCandleRepository - MongoDB repository for 1-minute tick candles.
 *
 * Key queries:
 * - Get latest candle for symbol
 * - Get history (last N candles)
 * - Get candles in time range
 * - Get candles for aggregation
 */
@Repository
public interface TickCandleRepository extends MongoRepository<TickCandle, String> {

    /**
     * Find latest candle for a symbol.
     */
    Optional<TickCandle> findTopBySymbolOrderByTimestampDesc(String symbol);

    /**
     * Find latest candle for a scripCode.
     */
    Optional<TickCandle> findTopByScripCodeOrderByTimestampDesc(String scripCode);

    /**
     * Find candle at specific timestamp.
     */
    Optional<TickCandle> findBySymbolAndTimestamp(String symbol, Instant timestamp);

    /**
     * Find candle by scripCode at specific timestamp.
     */
    Optional<TickCandle> findByScripCodeAndTimestamp(String scripCode, Instant timestamp);

    /**
     * Find history (most recent first).
     */
    List<TickCandle> findBySymbolOrderByTimestampDesc(String symbol, Pageable pageable);

    /**
     * Find history by scripCode (most recent first).
     */
    List<TickCandle> findByScripCodeOrderByTimestampDesc(String scripCode, Pageable pageable);

    /**
     * Find candles in time range (for aggregation).
     */
    @Query("{ 'symbol': ?0, 'timestamp': { $gte: ?1, $lt: ?2 } }")
    List<TickCandle> findBySymbolAndTimestampBetween(String symbol, Instant start, Instant end);

    /**
     * Find candles by scripCode in time range.
     */
    @Query("{ 'scripCode': ?0, 'timestamp': { $gte: ?1, $lt: ?2 } }")
    List<TickCandle> findByScripCodeAndTimestampBetween(String scripCode, Instant start, Instant end);

    /**
     * Find all candles in time range (for batch processing).
     */
    @Query("{ 'timestamp': { $gte: ?0, $lt: ?1 } }")
    List<TickCandle> findByTimestampBetween(Instant start, Instant end);

    /**
     * Find options candles for a symbol (all strikes/expiries).
     */
    @Query("{ 'symbol': { $regex: ?0 }, 'instrumentType': { $in: ['OPTION_CE', 'OPTION_PE'] }, 'timestamp': ?1 }")
    List<TickCandle> findOptionsBySymbolPrefixAndTimestamp(String symbolPrefix, Instant timestamp);

    /**
     * Count candles for a symbol.
     */
    long countBySymbol(String symbol);

    /**
     * Count candles for a scripCode.
     */
    long countByScripCode(String scripCode);

    /**
     * Delete old candles (for manual cleanup if TTL not used).
     */
    void deleteByTimestampBefore(Instant timestamp);

    /**
     * Check if candle exists for symbol at timestamp.
     */
    boolean existsBySymbolAndTimestamp(String symbol, Instant timestamp);

    // ==================== WINDOW-BASED QUERIES (for 30m aggregation) ====================

    /**
     * Find candles by scripCode where windowStart is between two timestamps.
     * Used for aggregating 1m candles into 30m candles.
     */
    @Query("{ 'scripCode': ?0, 'windowStart': { $gte: ?1, $lt: ?2 } }")
    List<TickCandle> findByScripCodeAndWindowStartBetween(String scripCode, Instant start, Instant end);

    /**
     * Find candles by scripCode where windowStart is after a timestamp.
     * Used for fetching historical data for aggregation.
     */
    @Query("{ 'scripCode': ?0, 'windowStart': { $gte: ?1 } }")
    List<TickCandle> findByScripCodeAndWindowStartAfter(String scripCode, Instant after);

    /**
     * Find recent candles by scripCode ordered by windowStart descending.
     * Used for fetching the most recent candles.
     */
    List<TickCandle> findByScripCodeOrderByWindowStartDesc(String scripCode, Pageable pageable);

    /**
     * Find recent candles by scripCode (no Pageable, returns recent candles).
     */
    @Query(value = "{ 'scripCode': ?0 }", sort = "{ 'windowStart': -1 }")
    List<TickCandle> findByScripCodeOrderByWindowStartDesc(String scripCode);
}
