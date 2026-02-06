package com.kotsin.consumer.repository;


import com.kotsin.consumer.metadata.model.Scrip;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScripRepository extends MongoRepository<Scrip, String> {
    List<Scrip> findByScriptTypeKotsin(String scriptTypeKotsin);

    @Query("{ 'ScripCode': ?0, 'Exch': ?1, 'ExchType': ?2 }")
    List<Scrip> findByScripCodeAndExchAndExchType(String scripCode, String exch, String exchType);


    @Query("{ 'ScripCode': ?0 }")
    List<Scrip> findByScripCode(String scripCode);

}
