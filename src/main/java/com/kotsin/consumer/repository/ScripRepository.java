package com.kotsin.consumer.repository;

import com.kotsin.consumer.entity.Scrip;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ScripRepository extends MongoRepository<Scrip, String> {
    Optional<Scrip> findFirstByExchAndExchTypeAndScripCode(String exch, String exchType, String scripCode);
    Optional<Scrip> findFirstByExchAndExchTypeAndName(String exch, String exchType, String name);

    /**
     * Find equity by symbolRoot - critical for options-to-equity mapping
     * ExchType "C" = Cash segment = Equity
     */
    Optional<Scrip> findFirstBySymbolRootAndExchType(String symbolRoot, String exchType);

    /**
     * FIX: Find any Scrip by scripCode directly (without requiring exch/exchType)
     * Used as fallback when ScripGroup lookup fails for options not in ScripGroup.options[]
     * ScripCode is unique across all exchanges, so this is safe
     */
    Optional<Scrip> findByScripCode(String scripCode);
}
