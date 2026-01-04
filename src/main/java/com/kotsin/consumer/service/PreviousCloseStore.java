package com.kotsin.consumer.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.common.serialization.Serdes;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

/**
 * PreviousCloseStore - RocksDB-backed persistent storage for previous day closing prices
 * 
 * CRITICAL FIX: Gap analysis requires previous day close, which wasn't stored anywhere.
 * This service provides persistent storage across application restarts.
 * 
 * Storage Strategy:
 * - Key: scripCode (e.g., "RELIANCE", "NIFTY")
 * - Value: previous day's closing price
 * - Backend: RocksDB (persistent across restarts)
 * - TTL: No expiry (historical reference)
 * 
 * Usage:
 * 1. At end of each 1m candle: save close price
 * 2. At first candle of next day: retrieve previous close
 * 3. Calculate gap: (open - prevClose) / prevClose * 100
 */
@Slf4j
@Service
public class PreviousCloseStore {
    
    public static final String STORE_NAME = "previous-close-store";
    
    private final StoreBuilder<KeyValueStore<String, Double>> storeBuilder;
    
    public PreviousCloseStore() {
        // Create RocksDB-backed persistent key-value store
        this.storeBuilder = Stores.keyValueStoreBuilder(
            Stores.persistentKeyValueStore(STORE_NAME),
            Serdes.String(),
            Serdes.Double()
        ).withCachingEnabled()  // Enable caching for performance
         .withLoggingEnabled(null);  // Enable changelog for recovery
        
        log.info("✅ PreviousCloseStore initialized with persistent RocksDB backend");
    }
    
    /**
     * Add state store to Kafka Streams topology
     * Must be called during topology building
     */
    public void addToTopology(StreamsBuilder builder) {
        builder.addStateStore(storeBuilder);
        log.info("📦 Added {} to Kafka Streams topology", STORE_NAME);
    }
    
    /**
     * Get the store builder for use in transformers
     */
    public StoreBuilder<KeyValueStore<String, Double>> getStoreBuilder() {
        return storeBuilder;
    }
    
    /**
     * Helper class for use within Kafka Streams transformers
     */
    public static class StoreAccessor {
        private final KeyValueStore<String, Double> store;
        
        public StoreAccessor(ProcessorContext context) {
            this.store = context.getStateStore(STORE_NAME);
            if (store == null) {
                throw new IllegalStateException("State store " + STORE_NAME + " not found! " +
                        "Ensure addToTopology() was called during topology building.");
            }
        }
        
        /**
         * Save previous close price for a script
         */
        public void savePreviousClose(String scripCode, double closePrice) {
            if (scripCode == null || scripCode.isEmpty()) {
                return;
            }
            
            store.put(scripCode, closePrice);
            log.debug("💾 Saved previous close for {}: {:.2f}", scripCode, closePrice);
        }
        
        /**
         * Retrieve previous close price for a script
         * Returns null if not found (first day of trading or new instrument)
         */
        public Double getPreviousClose(String scripCode) {
            if (scripCode == null || scripCode.isEmpty()) {
                return null;
            }
            
            Double prevClose = store.get(scripCode);
            if (prevClose != null) {
                log.debug("📖 Retrieved previous close for {}: {:.2f}", scripCode, prevClose);
            } else {
                log.debug("⚠️ No previous close found for {} (first day or new instrument)", scripCode);
            }
            
            return prevClose;
        }
        
        /**
         * Check if previous close exists for a script
         */
        public boolean hasPreviousClose(String scripCode) {
            return getPreviousClose(scripCode) != null;
        }
    }
}
