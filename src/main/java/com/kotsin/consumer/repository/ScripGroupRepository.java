package com.kotsin.consumer.repository;

import com.kotsin.consumer.entity.ScripGroup;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScripGroupRepository extends MongoRepository<ScripGroup, String> {
    
    /**
     * Find all ScripGroups by trading type (EQUITY, FUTURE, OPTION)
     */
    List<ScripGroup> findByTradingType(String tradingType);
    
    /**
     * Find ScripGroup by equity scrip code
     */
    ScripGroup findByEquityScripCode(String equityScripCode);
    
    /**
     * Find ScripGroup containing a specific scrip code in futures
     */
    @Query("{ 'futures.scripCode': ?0 }")
    List<ScripGroup> findByFuturesScripCode(String scripCode);
    
    /**
     * Find ScripGroup containing a specific scrip code in options
     */
    @Query("{ 'options.scripCode': ?0 }")
    List<ScripGroup> findByOptionsScripCode(String scripCode);
    
    /**
     * Find ScripGroup by company name (case insensitive)
     */
    List<ScripGroup> findByCompanyNameIgnoreCase(String companyName);
}
