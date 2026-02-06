package com.kotsin.consumer.repository;

import com.kotsin.consumer.metadata.model.ScripGroup;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * ScripGroupRepository - Read-only access to ScripGroup collection.
 *
 * ScripGroup documents are created by the scripFinder service.
 * This repository provides lookups for the consumer (streamingCandle) service.
 *
 * Key usage: Given equity scripCode, get its FUT scripCode + options chain instruments.
 */
@Repository
public interface ScripGroupRepository extends MongoRepository<ScripGroup, String> {

    List<ScripGroup> findByTradingType(String tradingType);

    ScripGroup findByEquityScripCode(String equityScripCode);
}
