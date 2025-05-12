package com.kotsin.consumer.repository;

import com.kotsin.consumer.entity.HistoricalTickData;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for accessing historical tick data from the database.
 * Provides methods for querying tick data by scrip code and time range.
 */
@Repository
public interface HistoricalTickDataRepository extends MongoRepository<HistoricalTickData, String> {
    
    /**
     * Find historical tick data for a specific scrip code within a time range,
     * ordered by timestamp.
     *
     * @param scripCode The scrip code to search for
     * @param startTime The start time (inclusive)
     * @param endTime The end time (exclusive)
     * @return List of matching tick data ordered by timestamp
     */
    List<HistoricalTickData> findByScripCodeAndTimestampBetweenOrderByTimestamp(
            String scripCode, 
            LocalDateTime startTime, 
            LocalDateTime endTime);
    
    /**
     * Count the number of ticks for a specific scrip code within a time range.
     *
     * @param scripCode The scrip code to search for
     * @param startTime The start time (inclusive)
     * @param endTime The end time (exclusive)
     * @return Count of matching ticks
     */
    @Query(value = "{ 'scripCode': ?0, 'timestamp': { $gte: ?1, $lt: ?2 } }", count = true)
    long countTicksInRange(String scripCode, LocalDateTime startTime, LocalDateTime endTime);
    
    /**
     * Find distinct scrip codes that have historical data.
     *
     * @return List of distinct scrip codes
     */
    @Query(value = "{}", fields = "{ 'scripCode': 1 }")
    List<String> findDistinctScripCodes();
    
    /**
     * Find the earliest timestamp for a specific scrip code.
     *
     * @param scripCode The scrip code to search for
     * @return The earliest timestamp
     */
    @Query(value = "{ 'scripCode': ?0 }", sort = "{ 'timestamp': 1 }", fields = "{ 'timestamp': 1 }")
    LocalDateTime findEarliestTimestamp(String scripCode);
    
    /**
     * Find the latest timestamp for a specific scrip code.
     *
     * @param scripCode The scrip code to search for
     * @return The latest timestamp
     */
    @Query(value = "{ 'scripCode': ?0 }", sort = "{ 'timestamp': -1 }", fields = "{ 'timestamp': 1 }")
    LocalDateTime findLatestTimestamp(String scripCode);
} 