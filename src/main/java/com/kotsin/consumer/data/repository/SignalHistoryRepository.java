package com.kotsin.consumer.data.repository;

import com.kotsin.consumer.data.model.SignalHistory;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for SignalHistory MongoDB documents
 */
@Repository
public interface SignalHistoryRepository extends MongoRepository<SignalHistory, String> {

    /**
     * Find by signalId (for linking outcomes)
     */
    Optional<SignalHistory> findBySignalId(String signalId);

    /**
     * Find signals by scripCode
     */
    List<SignalHistory> findByScripCode(String scripCode);

    /**
     * Find signals by signalType
     */
    List<SignalHistory> findBySignalType(String signalType);

    /**
     * Find emitted signals
     */
    List<SignalHistory> findBySignalEmittedTrue();

    /**
     * Find signals without outcomes yet
     */
    List<SignalHistory> findBySignalEmittedTrueAndWasWinIsNull();

    /**
     * Find signals in date range
     */
    List<SignalHistory> findByTimestampBetween(LocalDateTime start, LocalDateTime end);

    /**
     * Find signals by gate failure
     */
    List<SignalHistory> findByHardGatePassedFalse();
    List<SignalHistory> findByMtfGatePassedFalse();
    List<SignalHistory> findByQualityGatePassedFalse();
    List<SignalHistory> findByStatsGatePassedFalse();

    /**
     * Find recent signals for a scrip
     */
    List<SignalHistory> findTop50ByScripCodeOrderByTimestampDesc(String scripCode);

    /**
     * Count signals by direction
     */
    long countByDirection(String direction);

    /**
     * Find winning signals
     */
    List<SignalHistory> findByWasWinTrue();

    /**
     * Find losing signals  
     */
    List<SignalHistory> findByWasWinFalse();
}

