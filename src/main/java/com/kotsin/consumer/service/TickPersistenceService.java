package com.kotsin.consumer.service;

import com.kotsin.consumer.entity.HistoricalTickData;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.repository.HistoricalTickDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for persisting tick data to MongoDB
 */
@Service
public class TickPersistenceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TickPersistenceService.class);
    private final AtomicLong tickCounter = new AtomicLong(0);
    private final AtomicLong savedCounter = new AtomicLong(0);
    private long lastLogTime = System.currentTimeMillis();
    
    @Autowired
    private HistoricalTickDataRepository tickDataRepository;
    
    /**
     * Listens to the market data stream and persists each tick to MongoDB
     */
    @KafkaListener(topics = "forwardtesting-data", groupId = "streamingcandle-tick-persistence")
    public void consumeAndPersistTick(TickData tickData) {
        tickCounter.incrementAndGet();
        
        try {
            HistoricalTickData historicalTick = convertToHistoricalTickData(tickData);
            tickDataRepository.save(historicalTick);
            savedCounter.incrementAndGet();
            
            // Log progress periodically
            long now = System.currentTimeMillis();
            if (now - lastLogTime > 10000) { // Every 10 seconds
                LOGGER.info("[Tick Persistence] Saved {}/{} ticks to MongoDB", 
                        savedCounter.get(), tickCounter.get());
                lastLogTime = now;
            }
        } catch (Exception e) {
            LOGGER.error("[Tick Persistence] Error saving tick data for {}: {}", 
                    tickData.getScripCode(), e.getMessage(), e);
        }
    }
    
    /**
     * Converts a TickData object to a HistoricalTickData entity
     */
    private HistoricalTickData convertToHistoricalTickData(TickData tickData) {
        HistoricalTickData historicalTick = new HistoricalTickData();
        
        // Set basic information
        historicalTick.setScripCode(tickData.getScripCode());
        historicalTick.setExchange(tickData.getExchange());
        historicalTick.setExchangeType(tickData.getExchangeType());
        historicalTick.setCompanyName(tickData.getCompanyName());
        
        // Set price data
        historicalTick.setLastRate(tickData.getLastRate());
        historicalTick.setOpenRate(tickData.getOpenRate());
        historicalTick.setHigh(tickData.getHigh());
        historicalTick.setLow(tickData.getLow());
        historicalTick.setPreviousClose(tickData.getPreviousClose());
        
        // Set volume data
        historicalTick.setLastQuantity(tickData.getLastQuantity());
        historicalTick.setTotalQuantity(tickData.getTotalQuantity());
        historicalTick.setTotalBidQuantity(tickData.getTotalBidQuantity());
        historicalTick.setTotalOfferQuantity(tickData.getTotalOfferQuantity());
        
        // Set timestamp
        historicalTick.setTimestamp(new Date(tickData.getTimestamp()));
        historicalTick.setTickDt(tickData.getTickDt());
        
        // Set trade date (for querying by date)
        historicalTick.setTradeDate(
                LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(tickData.getTimestamp()), 
                        ZoneId.of("Asia/Kolkata")).toLocalDate()
        );
        
        return historicalTick;
    }
} 