package com.kotsin.consumer.repository;

import com.kotsin.consumer.entity.ScripGroup;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

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
}
