package com.kotsin.consumer.repository;

import com.kotsin.consumer.entity.ScripGroup;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for ScripGroup collection
 * Provides access to equity -> futures/options mappings
 */
@Repository
public interface ScripGroupRepository extends MongoRepository<ScripGroup, String> {
    
    /**
     * Find all ScripGroups (for startup preload)
     * @return all family mappings
     */
    List<ScripGroup> findAll();
    
    /**
     * Find by equity scripCode
     */
    ScripGroup findByEquityScripCode(String scripCode);
    
    /**
     * Find ScripGroup by future or option scripCode
     * Returns the equity scripCode (_id) for any derivative
     * 
     * Query searches in:
     * - futures[].ScripCode
     * - options[].ScripCode
     * - equity.ScripCode
     */
    @Query("{ $or: [ " +
           "{ 'futures.ScripCode': ?0 }, " +
           "{ 'options.ScripCode': ?0 }, " +
           "{ 'equity.ScripCode': ?0 } " +
           "] }")
    Optional<ScripGroup> findByScripCode(String scripCode);
}
