package com.kotsin.consumer.repository;

import com.kotsin.consumer.entity.Scrip;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ScripRepository extends MongoRepository<Scrip, String> {
    Optional<Scrip> findFirstByExchAndExchTypeAndScripCode(String exch, String exchType, String scripCode);
    Optional<Scrip> findFirstByExchAndExchTypeAndName(String exch, String exchType, String name);
}
