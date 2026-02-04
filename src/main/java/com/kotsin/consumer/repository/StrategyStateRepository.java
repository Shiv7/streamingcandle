package com.kotsin.consumer.repository;

import com.kotsin.consumer.model.StrategyState;
import com.kotsin.consumer.model.StrategyState.StrategyType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * StrategyStateRepository - MongoDB repository for strategy state persistence.
 */
@Repository
public interface StrategyStateRepository extends MongoRepository<StrategyState, String> {

    /**
     * Find state by symbol, strategy type, and timeframe.
     * This is the primary lookup method.
     */
    Optional<StrategyState> findBySymbolAndStrategyTypeAndTimeframe(
        String symbol, StrategyType strategyType, String timeframe);

    /**
     * Find all states for a symbol.
     */
    List<StrategyState> findBySymbol(String symbol);

    /**
     * Find all states for a symbol and strategy type.
     */
    List<StrategyState> findBySymbolAndStrategyType(String symbol, StrategyType strategyType);

    /**
     * Find all states for a strategy type (across all symbols).
     */
    List<StrategyState> findByStrategyType(StrategyType strategyType);

    /**
     * Find recently updated states for a strategy type.
     */
    List<StrategyState> findByStrategyTypeOrderByLastUpdatedDesc(
        StrategyType strategyType, Pageable pageable);

    /**
     * Find active states (updated within threshold).
     */
    @Query("{ 'strategyType': ?0, 'lastUpdated': { $gte: ?1 } }")
    List<StrategyState> findActiveStates(StrategyType strategyType, Instant updatedAfter);

    /**
     * Find stale states (not updated since threshold).
     */
    @Query("{ 'strategyType': ?0, 'lastUpdated': { $lt: ?1 } }")
    List<StrategyState> findStaleStates(StrategyType strategyType, Instant updatedBefore);

    /**
     * Check if state exists.
     */
    boolean existsBySymbolAndStrategyTypeAndTimeframe(
        String symbol, StrategyType strategyType, String timeframe);

    /**
     * Delete state for a symbol.
     */
    void deleteBySymbol(String symbol);

    /**
     * Delete state for a symbol and strategy type.
     */
    void deleteBySymbolAndStrategyType(String symbol, StrategyType strategyType);

    /**
     * Delete stale states.
     */
    void deleteByLastUpdatedBefore(Instant threshold);

    /**
     * Count states by strategy type.
     */
    long countByStrategyType(StrategyType strategyType);

    /**
     * Find symbols with active VCP state.
     */
    @Query(value = "{ 'strategyType': 'VCP', 'vcpState.supportClusters': { $exists: true, $ne: [] } }",
           fields = "{ 'symbol': 1 }")
    List<StrategyState> findSymbolsWithActiveVcpState();

    /**
     * Find symbols with high IPU score.
     */
    @Query("{ 'strategyType': 'IPU', 'ipuState.currentIpuScore': { $gte: ?0 } }")
    List<StrategyState> findSymbolsWithHighIpuScore(double minScore);
}
