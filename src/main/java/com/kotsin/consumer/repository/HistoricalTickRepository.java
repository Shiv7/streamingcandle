package com.kotsin.consumer.repository;

import com.kotsin.consumer.entity.HistoricalTickData;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

/**
 * Repository for accessing historical tick data from the database.
 */
@Repository
public interface HistoricalTickRepository extends MongoRepository<HistoricalTickData, String> {
    
    /**
     * Find all historical tick data for a specific scrip code between two dates
     */
    List<HistoricalTickData> findByScripCodeAndTradeDateBetweenOrderByTimestamp(
        String scripCode, LocalDate startDate, LocalDate endDate);
    
    /**
     * Find historical ticks for a scrip within a specific time range
     */
    @Query("{'scripCode': ?0, 'timestamp': {$gte: ?1, $lte: ?2}}")
    List<HistoricalTickData> findTicksInTimeRange(String scripCode, Date startTime, Date endTime);
    
    /**
     * Count ticks for a specific scrip on a specific date
     */
    long countByScripCodeAndTradeDate(String scripCode, LocalDate date);
    
    /**
     * Find historical ticks for a scrip on a specific date, ordered by timestamp
     */
    List<HistoricalTickData> findByScripCodeAndTradeDateOrderByTimestamp(String scripCode, LocalDate date);
    
    /**
     * Find historical ticks for a scrip on a specific date, with pagination
     */
    List<HistoricalTickData> findByScripCodeAndTradeDate(String scripCode, LocalDate date, Pageable pageable);
} 