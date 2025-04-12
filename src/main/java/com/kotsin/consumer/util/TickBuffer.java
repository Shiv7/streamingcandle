package com.kotsin.consumer.util;

import com.kotsin.consumer.model.TickData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Buffers tick data to handle delayed ticks and ensure accurate candle generation
 * 
 * The buffer:
 * 1. Holds ticks for a configurable delay period (e.g., 500ms)
 * 2. Sorts ticks by timestamp before processing
 * 3. Handles out-of-order ticks gracefully
 * 4. Provides detailed metrics about tick processing
 * 5. Prioritizes boundary ticks (at xx:59.xxx) for accurate close prices
 */
public class TickBuffer {
    private static final Logger LOGGER = LoggerFactory.getLogger(TickBuffer.class);
    private static final ZoneId INDIA_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    
    private final long bufferDelayMs;
    private final BiConsumer<String, List<TickData>> tickProcessor;
    private final Map<String, List<TickData>> tickBuffer = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    // Tracking scheduled processing to avoid duplicates
    private final CopyOnWriteArraySet<String> scheduledSymbols = new CopyOnWriteArraySet<>();
    
    // Metrics
    private final Map<String, Integer> ticksPerSymbolCount = new ConcurrentHashMap<>();
    private final Map<String, Long> lastProcessedTickTime = new ConcurrentHashMap<>();
    private final Map<String, Long> latestTickTime = new ConcurrentHashMap<>();
    
    /**
     * Creates a new tick buffer with the specified delay
     * 
     * @param bufferDelayMs Delay in milliseconds to buffer ticks
     * @param tickProcessor Callback to process ticks after buffering
     */
    public TickBuffer(long bufferDelayMs, BiConsumer<String, List<TickData>> tickProcessor) {
        this.bufferDelayMs = bufferDelayMs;
        this.tickProcessor = tickProcessor;
        
        LOGGER.info("Initialized TickBuffer with {}ms delay", bufferDelayMs);
    }
    
    /**
     * Adds a tick to the buffer and processes ticks when ready
     */
    public void addTick(TickData tick) {
        if (tick == null || tick.getCompanyName() == null) {
            return;
        }
        
        String symbol = tick.getCompanyName();
        long tickTime = tick.getTime();
        
        // Update metrics
        ticksPerSymbolCount.compute(symbol, (k, v) -> (v == null) ? 1 : v + 1);
        
        // Get or create buffer for this symbol
        List<TickData> symbolBuffer = tickBuffer.computeIfAbsent(symbol, k -> new ArrayList<>());
        
        // Add tick to buffer
        symbolBuffer.add(tick);
        
        // Update the latest tick time seen for this symbol
        latestTickTime.put(symbol, Math.max(tickTime, latestTickTime.getOrDefault(symbol, 0L)));
        
        // If we have boundary case (tick at xx:59.xxx), process immediately to ensure
        // it's included in the correct window
        ZonedDateTime tickDateTime = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(tickTime),
                INDIA_ZONE
        );
        
        if (isBoundaryTick(tickDateTime)) {
            LOGGER.debug("Processing boundary tick immediately: {} at {}", 
                    symbol, 
                    tickDateTime.format(TIME_FORMAT));
            processTicks(symbol);
            return;
        }
        
        // Schedule buffer processing after delay if not already scheduled
        if (!scheduledSymbols.contains(symbol)) {
            scheduledSymbols.add(symbol);
            
            // Schedule processing after delay
            scheduler.schedule(() -> {
                processTicks(symbol);
            }, bufferDelayMs, TimeUnit.MILLISECONDS);
        }
    }
    
    /**
     * Checks if a tick is at a minute boundary (xx:59.xxx seconds)
     * which requires immediate processing to ensure proper window attribution
     */
    private boolean isBoundaryTick(ZonedDateTime time) {
        // Check if tick is in the last second of a minute (xx:59.xxx)
        return time.getSecond() >= 59;
    }
    
    /**
     * Process ticks for a specific symbol
     */
    private void processTicks(String symbol) {
        // Remove symbol from scheduled set
        scheduledSymbols.remove(symbol);
        
        // Get and clear the buffer
        List<TickData> ticks = tickBuffer.remove(symbol);
        if (ticks == null || ticks.isEmpty()) {
            return;
        }
        
        // Sort ticks by timestamp to ensure correct order
        ticks.sort(Comparator.comparingLong(TickData::getTime));
        
        // Update last processed time
        if (!ticks.isEmpty()) {
            lastProcessedTickTime.put(symbol, ticks.get(ticks.size() - 1).getTime());
        }
        
        // Log tick count and time range
        if (LOGGER.isDebugEnabled() && !ticks.isEmpty()) {
            ZonedDateTime firstTickTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(ticks.get(0).getTime()), INDIA_ZONE);
            ZonedDateTime lastTickTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(ticks.get(ticks.size() - 1).getTime()), INDIA_ZONE);
            
            LOGGER.debug("Processing {} ticks for {}, time range: {} to {}", 
                    ticks.size(), symbol, 
                    firstTickTime.format(TIME_FORMAT),
                    lastTickTime.format(TIME_FORMAT));
        }
        
        // Check if there's a boundary tick in this batch (close to the minute change)
        boolean hasBoundaryTick = ticks.stream()
                .anyMatch(tick -> {
                    ZonedDateTime tickTime = ZonedDateTime.ofInstant(
                            Instant.ofEpochMilli(tick.getTime()), 
                            INDIA_ZONE);
                    return tickTime.getSecond() >= 59;
                });
                
        if (hasBoundaryTick) {
            LOGGER.debug("Boundary tick detected in batch for {}, ensuring precise candle close values", symbol);
        }
        
        // Call the processor callback with the buffered ticks
        tickProcessor.accept(symbol, ticks);
    }
    
    /**
     * Process all symbols immediately
     */
    public void processAllSymbols() {
        // Get all symbols with buffered ticks
        List<String> symbols = new ArrayList<>(tickBuffer.keySet());
        
        // Process each symbol
        for (String symbol : symbols) {
            processTicks(symbol);
        }
        
        LOGGER.info("Processed all buffered ticks for {} symbols", symbols.size());
    }
    
    /**
     * Get buffer statistics for monitoring
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("bufferDelayMs", bufferDelayMs);
        stats.put("activeSymbolCount", tickBuffer.size());
        stats.put("totalBufferedTicks", tickBuffer.values().stream().mapToInt(List::size).sum());
        stats.put("symbolStats", ticksPerSymbolCount);
        return stats;
    }
    
    /**
     * Shutdown the buffer and process any remaining ticks
     */
    public void shutdown() {
        LOGGER.info("Shutting down TickBuffer");
        processAllSymbols();
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while waiting for scheduler to terminate", e);
            Thread.currentThread().interrupt();
        }
    }
}
