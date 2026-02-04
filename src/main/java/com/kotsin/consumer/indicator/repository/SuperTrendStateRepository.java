package com.kotsin.consumer.indicator.repository;

import com.kotsin.consumer.indicator.model.SuperTrendState;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * SuperTrendStateRepository - MongoDB repository for SuperTrend state persistence.
 *
 * Stores the last calculated SuperTrend state per scripCode+timeframe combination,
 * allowing the calculator to resume from the correct state after restarts.
 */
@Repository
public interface SuperTrendStateRepository extends MongoRepository<SuperTrendState, String> {

    /**
     * Find state for a specific scripCode and timeframe.
     */
    Optional<SuperTrendState> findByScripCodeAndTimeframe(String scripCode, String timeframe);

    /**
     * Find all states for a scripCode (across all timeframes).
     */
    List<SuperTrendState> findByScripCode(String scripCode);

    /**
     * Find all states for a timeframe (for bulk operations).
     */
    List<SuperTrendState> findByTimeframe(String timeframe);

    /**
     * Delete state for a specific scripCode and timeframe.
     */
    void deleteByScripCodeAndTimeframe(String scripCode, String timeframe);

    /**
     * Check if state exists for scripCode and timeframe.
     */
    boolean existsByScripCodeAndTimeframe(String scripCode, String timeframe);
}
