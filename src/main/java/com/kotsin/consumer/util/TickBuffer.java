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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Buffer for tick data to handle late-arriving ticks and improve candle accuracy.
 * This class holds ticks for a short period before releasing them to be processed,
 * which helps ensure that candles have all relevant ticks even if some arrive with slight delays.
 */
public class TickBuffer {
    private static final Logger LOGGER = LoggerFactory.getLogger(TickBuffer.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final ZoneId INDIA_ZONE = ZoneId.of("Asia/Kolkata");
    
    // Buffer delay in milliseconds (how long to hold ticks before releasing)
    private final long bufferDelayMs;
    
    // Map of symbol to list of buffered ticks
    private final Map<String, List<TickData>> tickBuffer = new ConcurrentHashMap<>();
    
    // Callback interface for when ticks are ready to be processed
    private final TickProcessor tickProcessor;
    
    // Executor for scheduled tick processing
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    // Metrics
    private final Map<String, Integer> ticksPerSymbolCount = new ConcurrentHashMap<>();
    private final Map<String, Long> lastProcessedTickTime = new ConcurrentHashMap<>();
    
    /**
     * Callback interface for processing ticks
     */
    public interface TickProcessor {
        void processTicks(String symbol, List<TickData> ticks);
    }
    
    /**
     * Creates a new TickBuffer with the specified delay
     * 
     * @param bufferDelayMs How long to buffer ticks before releasing them (in milliseconds)
     * @param tickProcessor Callback for processing ticks once they're ready
     */
    public TickBuffer(long bufferDelayMs, TickProcessor tickProcessor) {
        this.bufferDelayMs = bufferDelayMs;
        this.tickProcessor = tickProcessor;
        
        // Schedule metrics logging
        scheduler.scheduleAtFixedRate(this::logMetrics, 60, 60, TimeUnit.SECONDS);
    }
    
    /**
     * Adds a tick to the buffer
     * 
     * @param tick The tick to buffer
     */
    public void addTick(TickData tick) {
        if (tick == null || tick.getCompanyName() == null) {
            LOGGER.warn("Received null tick or tick with null company name");
            return;
        }
        
        String symbol = tick.getCompanyName();
        
        // Update metrics
        ticksPerSymbolCount.compute(symbol, (k, v) -> (v == null) ? 1 : v + 1);
        
        // Add tick to buffer
        tickBuffer.computeIfAbsent(symbol, k -> new ArrayList<>()).add(tick);
        
        // Schedule processing of this symbol's ticks after the buffer delay
        scheduler.schedule(() -> processTicks(symbol), bufferDelayMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Process all buffered ticks for a symbol
     * 
     * @param symbol The symbol to process
     */
    private void processTicks(String symbol) {
        List<TickData> ticks = tickBuffer.remove(symbol);
        if (ticks == null || ticks.isEmpty()) {
            return;
        }
        
        // Sort ticks by timestamp to ensure correct order
        ticks.sort(Comparator.comparingLong(TickData::getTimestamp));
        
        // Update last processed time
        if (!ticks.isEmpty()) {
            lastProcessedTickTime.put(symbol, ticks.get(ticks.size() - 1).getTimestamp());
        }
        
        // Log tick count and time range
        if (LOGGER.isDebugEnabled() && ticks.size() > 0) {
            ZonedDateTime firstTickTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(ticks.get(0).getTimestamp()), INDIA_ZONE);
            ZonedDateTime lastTickTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(ticks.get(ticks.size() - 1).getTimestamp()), INDIA_ZONE);
            
            LOGGER.debug("Processing {} ticks for {}, time range: {} to {}", 
                    ticks.size(), symbol, 
                    firstTickTime.format(TIME_FORMAT),
                    lastTickTime.format(TIME_FORMAT));
        }
        
        // Process ticks through callback
        tickProcessor.processTicks(symbol, ticks);
    }
    
    /**
     * Logs metrics about tick processing
     */
    private void logMetrics() {
        StringBuilder sb = new StringBuilder("Tick processing metrics:\n");
        
        // Log tick counts per symbol
        sb.append("Ticks per symbol (last minute):\n");
        ticksPerSymbolCount.forEach((symbol, count) -> {
            sb.append(String.format("  %s: %d ticks\n", symbol, count));
        });
        
        // Log last processed times
        sb.append("Last processed tick time:\n");
        lastProcessedTickTime.forEach((symbol, time) -> {
            ZonedDateTime tickTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(time), INDIA_ZONE);
            sb.append(String.format("  %s: %s\n", symbol, tickTime.format(TIME_FORMAT)));
        });
        
        LOGGER.info(sb.toString());
        
        // Reset tick counts for next period
        ticksPerSymbolCount.clear();
    }
    
    /**
     * Shutdown the buffer scheduler
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
