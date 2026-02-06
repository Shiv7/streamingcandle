package com.kotsin.consumer.signal.repository;

import com.kotsin.consumer.signal.model.TradingSetup;
import com.kotsin.consumer.signal.model.TradingSetup.SetupState;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TradingSetupRepository extends MongoRepository<TradingSetup, String> {

    List<TradingSetup> findBySymbolAndState(String symbol, SetupState state);

    List<TradingSetup> findBySymbolAndStateIn(String symbol, List<SetupState> states);

    List<TradingSetup> findByState(SetupState state);
}
