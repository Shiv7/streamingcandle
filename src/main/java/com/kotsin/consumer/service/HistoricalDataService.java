package com.kotsin.consumer.service;

import com.kotsin.consumer.entity.HistoricalTickData;
import com.kotsin.consumer.model.TickData;
import com.kotsin.consumer.repository.HistoricalTickDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for processing historical tick data
 */
@Service
public class HistoricalDataService {

    private static final Logger LOGGER = LoggerFactory.getLogger(HistoricalDataService.class);
    private static final int BATCH_SIZE = 5000;
    
    @Autowired
    private HistoricalTickDataRepository tickRepository;
    
    @Autowired
    private KafkaTemplate<String, TickData> kafkaTemplate;
    
    /**
     * Processes historical data for a specific script and date range by
     * sending it to the Kafka topic used by real-time processing
     */
    public void processHistoricalData(String scripCode, LocalDate startDate, LocalDate endDate, int speedFactor) {
        LOGGER.info("[Historical Processing] START - Processing historical data for {} from {} to {} with speed factor {}", 
                scripCode, startDate, endDate, speedFactor);
        
        // Count total ticks for progress tracking
        long totalTicks = 0;
        try {
            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                long countForDay = tickRepository.countByScripCodeAndTradeDate(scripCode, date);
                totalTicks += countForDay;
                LOGGER.debug("[Historical Processing] Found {} ticks for {} on date {}", countForDay, scripCode, date);
            }
            
            LOGGER.info("[Historical Processing] Found {} total ticks to process for {}", totalTicks, scripCode);
            
            if (totalTicks == 0) {
                LOGGER.warn("[Historical Processing] No historical data found for {} in date range {} to {}", 
                        scripCode, startDate, endDate);
                return;
            }
            
            AtomicInteger processedCount = new AtomicInteger(0);
            
            // Process each day in batches
            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                LOGGER.info("[Historical Processing] Starting to process date: {} for {}", date, scripCode);
                processHistoricalDateInBatches(scripCode, date, speedFactor, processedCount, totalTicks);
                LOGGER.info("[Historical Processing] Completed processing date: {} for {}", date, scripCode);
            }
            
            LOGGER.info("[Historical Processing] COMPLETE - Processed {} of {} ticks for {} from {} to {}", 
                    processedCount.get(), totalTicks, scripCode, startDate, endDate);
        } catch (Exception e) {
            LOGGER.error("[Historical Processing] ERROR processing historical data for {} from {} to {}: {}", 
                    scripCode, startDate, endDate, e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Process a specific date in batches to avoid memory issues
     */
    private void processHistoricalDateInBatches(String scripCode, LocalDate date, int speedFactor, 
                                               AtomicInteger processedCount, long totalTicks) {
        LOGGER.info("[Historical Processing] Processing date: {} for {}", date, scripCode);
        
        // Set up time window for market hours
        LocalDateTime startTime = date.atTime(9, 0);  // 9:00 AM
        LocalDateTime endTime = date.atTime(16, 0);   // 4:00 PM
        
        ZoneId zoneId = ZoneId.of("Asia/Kolkata");
        Date start = Date.from(startTime.atZone(zoneId).toInstant());
        Date end = Date.from(endTime.atZone(zoneId).toInstant());
        
        LOGGER.debug("[Historical Processing] Market hours window: {} to {} for {}", startTime, endTime, scripCode);
        
        // Process in batches
        int page = 0;
        boolean hasMoreData = true;
        
        while (hasMoreData) {
            // Get batch of ticks
            List<HistoricalTickData> tickBatch;
            try {
                tickBatch = tickRepository.findTicksInTimeRange(scripCode, start, end);
                LOGGER.debug("[Historical Processing] Batch {}: Retrieved {} ticks for {} on {}", 
                        page, tickBatch.size(), scripCode, date);
            } catch (Exception e) {
                LOGGER.error("[Historical Processing] ERROR retrieving tick batch {} for {} on {}: {}", 
                        page, scripCode, date, e.getMessage(), e);
                throw e;
            }
            
            if (tickBatch.isEmpty()) {
                LOGGER.debug("[Historical Processing] No more data for {} on {}", scripCode, date);
                hasMoreData = false;
                continue;
            }
            
            // Track tick processing start time
            long batchStartTime = System.currentTimeMillis();
            
            // Send ticks to Kafka
            int sentInBatch = 0;
            for (HistoricalTickData histTick : tickBatch) {
                try {
                    TickData tick = convertToTickData(histTick);
                    kafkaTemplate.send("forwardtesting-data", tick.getScripCode(), tick);
                    sentInBatch++;
                    
                    // Update progress counter
                    int current = processedCount.incrementAndGet();
                    if (current % 1000 == 0) {
                        double progress = (double) current / totalTicks * 100.0;
                        LOGGER.info("[Historical Processing] Progress: {}% ({}/{}) for {}", 
                                String.format("%.2f", progress), current, totalTicks, scripCode);
                    }
                    
                    // Control processing speed
                    if (speedFactor < 1000) {
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            LOGGER.warn("[Historical Processing] Processing interrupted for {}", scripCode);
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("[Historical Processing] ERROR processing tick for {} on {} ({}): {}", 
                            scripCode, date, histTick.getTickDt(), e.getMessage(), e);
                }
            }
            
            // Log batch processing metrics
            long batchProcessingTime = System.currentTimeMillis() - batchStartTime;
            double ticksPerSecond = (sentInBatch * 1000.0) / Math.max(batchProcessingTime, 1);
            
            LOGGER.info("[Historical Processing] Batch {}: Sent {} ticks in {}ms ({} ticks/sec) for {} on {}", 
                    page, sentInBatch, batchProcessingTime, String.format("%.2f", ticksPerSecond), scripCode, date);
            
            // Move to next batch
            page++;
        }
        
        LOGGER.info("[Historical Processing] Completed processing date {} for {}", date, scripCode);
    }
    
    /**
     * Convert MongoDB entity to TickData model
     */
    private TickData convertToTickData(HistoricalTickData histTick) {
        try {
            TickData tick = new TickData();
            
            // Set basic information
            tick.setScripCode(histTick.getScripCode());
            tick.setExchange(histTick.getExchange());
            tick.setExchangeType(histTick.getExchangeType());
            tick.setCompanyName(histTick.getCompanyName());
            
            // Set price data
            tick.setLastRate(histTick.getLastRate());
            tick.setOpenRate(histTick.getOpenRate());
            tick.setHigh(histTick.getHigh());
            tick.setLow(histTick.getLow());
            tick.setPreviousClose(histTick.getPreviousClose());
            
            // Set volume data
            tick.setLastQuantity(histTick.getLastQuantity());
            tick.setTotalQuantity(histTick.getTotalQuantity());
            tick.setTotalBidQuantity(histTick.getTotalBidQuantity());
            tick.setTotalOfferQuantity(histTick.getTotalOfferQuantity());
            
            // Set timestamp
            tick.setTimestamp(histTick.getTimestamp().getTime());
            tick.setTickDt(histTick.getTickDt());
            
            return tick;
        } catch (Exception e) {
            LOGGER.error("[Historical Processing] ERROR converting historical tick data to TickData: {}", e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Get historical tick data for a specific scrip and date range
     */
    public List<TickData> getHistoricalTickData(String scripCode, LocalDate startDate, LocalDate endDate) {
        LOGGER.info("[Historical Query] Fetching historical tick data for {} from {} to {}", scripCode, startDate, endDate);
        
        List<HistoricalTickData> histTicks;
        try {
            histTicks = tickRepository.findByScripCodeAndTradeDateBetweenOrderByTimestamp(
                    scripCode, startDate, endDate);
            LOGGER.info("[Historical Query] Retrieved {} historical ticks for {} from {} to {}", 
                    histTicks.size(), scripCode, startDate, endDate);
        } catch (Exception e) {
            LOGGER.error("[Historical Query] ERROR retrieving historical tick data for {} from {} to {}: {}", 
                    scripCode, startDate, endDate, e.getMessage(), e);
            throw e;
        }
        
        if (histTicks.isEmpty()) {
            LOGGER.warn("[Historical Query] No historical tick data found for {} from {} to {}", 
                    scripCode, startDate, endDate);
            return new ArrayList<>();
        }
        
        List<TickData> ticks = new ArrayList<>();
        for (HistoricalTickData histTick : histTicks) {
            try {
                TickData tick = convertToTickData(histTick);
                ticks.add(tick);
            } catch (Exception e) {
                LOGGER.error("[Historical Query] ERROR converting tick for {} at {}: {}", 
                        scripCode, histTick.getTickDt(), e.getMessage());
            }
        }
        
        LOGGER.info("[Historical Query] Converted {} ticks for {} from {} to {}", 
                ticks.size(), scripCode, startDate, endDate);
        return ticks;
    }
} 