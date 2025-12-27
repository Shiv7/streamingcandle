package com.kotsin.consumer.infrastructure.redis;

import com.kotsin.consumer.domain.model.InstrumentFamily;
import com.kotsin.consumer.infrastructure.api.ScripFinderClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * FamilyCacheAdapter - Caches family relationships for fast lookup.
 * 
 * Currently uses in-memory cache. Can be extended to use Redis for distributed caching.
 * 
 * Cache Structure:
 * - familyCache: equityScripCode -> InstrumentFamily
 * - reverseMapping: anyScripCode -> equityScripCode
 * 
 * TTL: 24 hours (refreshed on market open)
 */
@Component
@Slf4j
public class FamilyCacheAdapter {

    @Autowired
    private ScripFinderClient scripFinderClient;

    @Value("${family.cache.ttl.hours:24}")
    private int ttlHours;

    @Value("${family.cache.refresh.on.miss:true}")
    private boolean refreshOnMiss;

    // In-memory cache (can be replaced with Redis)
    private final Map<String, InstrumentFamily> familyCache = new ConcurrentHashMap<>();
    private final Map<String, String> reverseMapping = new ConcurrentHashMap<>();
    private final Map<String, Long> lastUpdated = new ConcurrentHashMap<>();

    // Background refresh scheduler
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     * Get family for an equity scrip code
     *
     * @param equityScripCode Equity scrip code
     * @param closePrice Current close price (for ATM calculation)
     * @return InstrumentFamily or null
     */
    public InstrumentFamily getFamily(String equityScripCode, double closePrice) {
        if (equityScripCode == null) return null;

        InstrumentFamily cached = familyCache.get(equityScripCode);
        
        // Check if cached and fresh
        if (cached != null && isFresh(equityScripCode)) {
            return cached;
        }

        // Refresh from API if enabled - use computeIfAbsent pattern for thread safety
        if (refreshOnMiss) {
            return refreshFamilyThreadSafe(equityScripCode, closePrice);
        }

        return cached;  // Return stale data if refresh disabled
    }

    /**
     * Get equity scrip code for any instrument in the family
     *
     * @param scripCode Any scrip code (equity, future, or option)
     * @return Equity scrip code or the original scripCode if not found
     */
    public String getEquityScripCode(String scripCode) {
        if (scripCode == null) return null;
        return reverseMapping.getOrDefault(scripCode, scripCode);
    }

    /**
     * Check if scripCode is an equity
     */
    public boolean isEquity(String scripCode) {
        String equityCode = reverseMapping.get(scripCode);
        return equityCode != null && equityCode.equals(scripCode);
    }

    /**
     * Check if family is cached and fresh
     */
    public boolean isFresh(String equityScripCode) {
        Long updated = lastUpdated.get(equityScripCode);
        if (updated == null) return false;
        
        long ageMs = System.currentTimeMillis() - updated;
        return ageMs < TimeUnit.HOURS.toMillis(ttlHours);
    }

    /**
     * Thread-safe refresh using double-checked locking
     */
    private InstrumentFamily refreshFamilyThreadSafe(String equityScripCode, double closePrice) {
        // Double-checked locking pattern
        InstrumentFamily cached = familyCache.get(equityScripCode);
        if (cached != null && isFresh(equityScripCode)) {
            return cached;
        }
        
        synchronized (equityScripCode.intern()) {
            // Check again inside synchronized block
            cached = familyCache.get(equityScripCode);
            if (cached != null && isFresh(equityScripCode)) {
                return cached;
            }
            return refreshFamily(equityScripCode, closePrice);
        }
    }

    /**
     * Refresh family from API
     */
    public InstrumentFamily refreshFamily(String equityScripCode, double closePrice) {
        try {
            log.debug("Refreshing family cache for: {}", equityScripCode);
            
            InstrumentFamily family = scripFinderClient.getFamily(equityScripCode, closePrice);
            if (family != null) {
                cacheFamily(family);
                return family;
            }
        } catch (Exception e) {
            log.warn("Failed to refresh family for {}: {}", equityScripCode, e.getMessage());
        }
        return familyCache.get(equityScripCode);  // Return stale if refresh fails
    }

    /**
     * Cache a family and build reverse mappings
     */
    public void cacheFamily(InstrumentFamily family) {
        if (family == null || family.getEquityScripCode() == null) return;

        String equityCode = family.getEquityScripCode();
        
        // Cache the family
        familyCache.put(equityCode, family);
        lastUpdated.put(equityCode, System.currentTimeMillis());

        // Build reverse mappings
        reverseMapping.put(equityCode, equityCode);  // Equity maps to itself

        if (family.getFutureScripCode() != null) {
            reverseMapping.put(family.getFutureScripCode(), equityCode);
        }

        if (family.getOptions() != null) {
            for (InstrumentFamily.OptionInfo opt : family.getOptions()) {
                if (opt.getScripCode() != null) {
                    reverseMapping.put(opt.getScripCode(), equityCode);
                }
            }
        }

        log.debug("Cached family for {}: future={}, options={}", 
            equityCode, family.hasFuture(), family.getOptionCount());
    }

    /**
     * Prefetch families for a list of equity scrip codes
     */
    public void prefetchFamilies(Iterable<String> equityScripCodes, double defaultClosePrice) {
        for (String code : equityScripCodes) {
            try {
                refreshFamily(code, defaultClosePrice);
            } catch (Exception e) {
                log.warn("Failed to prefetch family for {}: {}", code, e.getMessage());
            }
        }
    }

    /**
     * Schedule periodic refresh of all cached families
     */
    public void scheduleRefresh(long initialDelayMinutes, long periodMinutes) {
        scheduler.scheduleAtFixedRate(() -> {
            log.info("Refreshing {} cached families", familyCache.size());
            for (Map.Entry<String, InstrumentFamily> entry : familyCache.entrySet()) {
                try {
                    refreshFamily(entry.getKey(), entry.getValue().getClosePrice());
                } catch (Exception e) {
                    log.warn("Failed to refresh family {}: {}", entry.getKey(), e.getMessage());
                }
            }
        }, initialDelayMinutes, periodMinutes, TimeUnit.MINUTES);
    }

    /**
     * Clear all caches
     */
    public void clearAll() {
        familyCache.clear();
        reverseMapping.clear();
        lastUpdated.clear();
        log.info("Cleared all family caches");
    }

    /**
     * Get cache statistics
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("familyCount", familyCache.size());
        stats.put("reverseMappingCount", reverseMapping.size());
        stats.put("ttlHours", ttlHours);
        
        long freshCount = familyCache.keySet().stream()
            .filter(this::isFresh)
            .count();
        stats.put("freshCount", freshCount);
        stats.put("staleCount", familyCache.size() - freshCount);
        
        return stats;
    }

    /**
     * Shutdown the scheduler
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
