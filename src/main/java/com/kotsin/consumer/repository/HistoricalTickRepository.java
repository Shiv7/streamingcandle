package com.kotsin.consumer.repository;

import com.kotsin.consumer.entity.HistoricalTickData;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

/**
 * MongoDB repository for accessing historical tick data
 */
@Repository
public interface HistoricalTickRepository extends MongoRepository<HistoricalTickData, String> {
    
    /**
     * Find ticks for a specific script within a date range
     */
    List<HistoricalTickData> findByScripCodeAndTradeDateBetweenOrderByTimestamp(
            String scripCode, LocalDate startDate, LocalDate endDate);
    
    /**
     * Find ticks within a specific time range
     */
    @Query("{ 'scripCode': ?0, 'timestamp': { $gte: ?1, $lte: ?2 } }")
    List<HistoricalTickData> findTicksInTimeRange(
            String scripCode, Date startTime, Date endTime);
    
    /**
     * Count ticks for a script on a specific date
     */
    @Query(value = "{ 'scripCode': ?0, 'tradeDate': ?1 }", count = true)
    long countByScripCodeAndTradeDate(String scripCode, LocalDate tradeDate);
    
    /**
     * Find all unique script codes for a specific date
     */
    @Query(value = "{ 'tradeDate': ?0 }", fields = "{ 'scripCode': 1 }")
    List<String> findDistinctScripCodesByTradeDate(LocalDate tradeDate);
} 