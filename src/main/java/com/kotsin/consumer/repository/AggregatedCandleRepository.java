package com.kotsin.consumer.repository;

import com.kotsin.consumer.model.AggregatedCandle;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AggregatedCandleRepository extends MongoRepository<AggregatedCandle, String> {

    List<AggregatedCandle> findByScripCodeAndTimeframeAndWindowStartBetween(
        String scripCode, String timeframe, Instant start, Instant end);

    List<AggregatedCandle> findBySymbolAndTimeframeOrderByWindowStartDesc(
        String symbol, String timeframe, Pageable pageable);

    List<AggregatedCandle> findBySymbolAndTimeframeAndWindowStartBetweenOrderByWindowStartAsc(
        String symbol, String timeframe, Instant start, Instant end);
}
