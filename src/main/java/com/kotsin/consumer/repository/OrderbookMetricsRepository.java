package com.kotsin.consumer.repository;

import com.kotsin.consumer.model.OrderbookMetrics;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * OrderbookMetricsRepository - MongoDB repository for orderbook metrics.
 */
@Repository
public interface OrderbookMetricsRepository extends MongoRepository<OrderbookMetrics, String> {

    /**
     * Find latest metrics for a symbol.
     */
    Optional<OrderbookMetrics> findTopBySymbolOrderByTimestampDesc(String symbol);

    /**
     * Find metrics at specific timestamp.
     */
    Optional<OrderbookMetrics> findBySymbolAndTimestamp(String symbol, Instant timestamp);

    /**
     * Find history (most recent first).
     */
    List<OrderbookMetrics> findBySymbolOrderByTimestampDesc(String symbol, Pageable pageable);

    /**
     * Find metrics in time range.
     */
    @Query("{ 'symbol': ?0, 'timestamp': { $gte: ?1, $lt: ?2 } }")
    List<OrderbookMetrics> findBySymbolAndTimestampBetween(String symbol, Instant start, Instant end);

    /**
     * Check if metrics exist for symbol at timestamp.
     */
    boolean existsBySymbolAndTimestamp(String symbol, Instant timestamp);

    /**
     * Delete old metrics.
     */
    void deleteByTimestampBefore(Instant timestamp);
}
