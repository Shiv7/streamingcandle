package com.kotsin.consumer.stats.repository;

import com.kotsin.consumer.stats.model.TradeOutcome;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for TradeOutcome MongoDB documents
 */
@Repository
public interface TradeOutcomeRepository extends MongoRepository<TradeOutcome, String> {

    /**
     * Find outcome by signalId
     */
    Optional<TradeOutcome> findBySignalId(String signalId);

    /**
     * Find outcomes by scripCode
     */
    List<TradeOutcome> findByScripCode(String scripCode);

    /**
     * Find outcomes by signalType
     */
    List<TradeOutcome> findBySignalType(String signalType);

    /**
     * Find outcomes in date range
     */
    List<TradeOutcome> findByExitTimeBetween(LocalDateTime start, LocalDateTime end);

    /**
     * Find recent outcomes for a scrip
     */
    List<TradeOutcome> findTop20ByScripCodeOrderByExitTimeDesc(String scripCode);

    /**
     * Find all wins
     */
    List<TradeOutcome> findByWinTrue();

    /**
     * Find all losses
     */
    List<TradeOutcome> findByWinFalse();

    /**
     * Count outcomes by exit reason
     */
    long countByExitReason(String exitReason);
}

