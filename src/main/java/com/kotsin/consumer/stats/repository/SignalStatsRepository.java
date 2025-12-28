package com.kotsin.consumer.stats.repository;

import com.kotsin.consumer.stats.model.SignalStats;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for SignalStats MongoDB documents
 */
@Repository
public interface SignalStatsRepository extends MongoRepository<SignalStats, String> {

    /**
     * Find stats by scripCode
     */
    List<SignalStats> findByScripCode(String scripCode);

    /**
     * Find stats by signalType
     */
    List<SignalStats> findBySignalType(String signalType);

    /**
     * Find stats by scripCode and signalType
     */
    Optional<SignalStats> findByScripCodeAndSignalType(String scripCode, String signalType);

    /**
     * Find all stats that haven't been reset today
     */
    List<SignalStats> findByStatsDateNot(LocalDate date);

    /**
     * Find top performing signal types by win rate
     */
    List<SignalStats> findByTotalTradesGreaterThanOrderByWinsDesc(int minTrades);

    /**
     * Find underperforming signals
     */
    List<SignalStats> findByTotalTradesGreaterThanAndTodayLossesGreaterThan(int minTrades, int maxLosses);
}

