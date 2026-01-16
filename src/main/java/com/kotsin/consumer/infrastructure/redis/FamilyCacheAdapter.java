package com.kotsin.consumer.infrastructure.redis;

import com.kotsin.consumer.domain.model.InstrumentFamily;
import com.kotsin.consumer.domain.service.IFamilyDataProvider;
import com.kotsin.consumer.entity.Scrip;
import com.kotsin.consumer.infrastructure.api.ScripFinderClient;
import com.kotsin.consumer.repository.ScripRepository;
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
 * FIXED: Now implements IFamilyDataProvider interface (DIP compliance)
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
public class FamilyCacheAdapter implements IFamilyDataProvider {

    @Autowired
    private ScripFinderClient scripFinderClient;

    @Autowired
    private ScripRepository scripRepository;

    @Value("${family.cache.ttl.hours:24}")
    private int ttlHours;

    @Value("${family.cache.refresh.on.miss:true}")
    private boolean refreshOnMiss;

    // In-memory cache (can be replaced with Redis)
    private final Map<String, InstrumentFamily> familyCache = new ConcurrentHashMap<>();
    private final Map<String, String> reverseMapping = new ConcurrentHashMap<>();
    private final Map<String, Long> lastUpdated = new ConcurrentHashMap<>();

    // Per-key locks for thread-safe refresh (replaces String.intern() anti-pattern)
    private final Map<String, Object> refreshLocks = new ConcurrentHashMap<>();

    // Symbol to equity mapping - preloaded on startup
    private final Map<String, String> symbolToEquityMap = new ConcurrentHashMap<>();

    // Background refresh scheduler
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Value("${family.preload.enabled:true}")
    private boolean preloadEnabled;

    @Autowired
    private com.kotsin.consumer.repository.ScripGroupRepository scripGroupRepository;

    /**
     * Preload all family mappings on startup from MongoDB ScripGroup collection
     * ScripGroup contains complete family: equity + futures[] + options[]
     * Much faster than making 3 API calls per equity!
     */
    @jakarta.annotation.PostConstruct
    public void preloadAllFamilies() {
        if (!preloadEnabled) {
            log.info("Family preload is disabled");
            return;
        }
        
        log.info("🚀 Starting family preload from MongoDB ScripGroup collection...");
        
        try {
            java.util.List<com.kotsin.consumer.entity.ScripGroup> scripGroups = scripGroupRepository.findAll();
            
            if (scripGroups == null || scripGroups.isEmpty()) {
                log.warn("No ScripGroups found in MongoDB, falling back to API preload");
                preloadFromApi();
                return;
            }
            
            int equityCount = 0;
            int futureCount = 0;
            int optionCount = 0;
            
            for (com.kotsin.consumer.entity.ScripGroup group : scripGroups) {
                String equityScripCode = group.getId();  // _id is equityScripCode
                if (equityScripCode == null) continue;
                
                // Map equity to itself
                reverseMapping.put(equityScripCode, equityScripCode);
                equityCount++;
                
                // Map symbol to equity
                if (group.getEquity() != null && group.getEquity().getSymbolRoot() != null) {
                    String symbolRoot = group.getEquity().getSymbolRoot().toUpperCase();
                    symbolToEquityMap.put(symbolRoot, equityScripCode);
                }
                
                // Map companyName to equity (for fallback)
                if (group.getCompanyName() != null) {
                    symbolToEquityMap.put(group.getCompanyName().toUpperCase(), equityScripCode);
                }
                
                // Map ONLY near-month future to equity
                // FIX: ScripGroup.futures contains ALL expiries (JAN, FEB, MAR...)
                // but FamilyCandle expects only 1 future (near-month).
                // If we map all futures, they get merged together corrupting data.
                // Solution: Sort by expiry, map only the earliest (near-month) future.
                if (group.getFutures() != null && !group.getFutures().isEmpty()) {
                    com.kotsin.consumer.entity.Scrip nearMonthFuture = group.getFutures().stream()
                        .filter(f -> f.getScripCode() != null && f.getExpiry() != null && !f.getExpiry().isEmpty())
                        .min((f1, f2) -> f1.getExpiry().compareTo(f2.getExpiry()))
                        .orElse(null);

                    if (nearMonthFuture != null) {
                        reverseMapping.put(nearMonthFuture.getScripCode(), equityScripCode);
                        futureCount++;
                        log.debug("[PRELOAD] Family {} mapped near-month future {} (expiry: {}), skipped {} far-month futures",
                            equityScripCode, nearMonthFuture.getScripCode(), nearMonthFuture.getExpiry(),
                            group.getFutures().size() - 1);
                    }
                }
                
                // Map all options to equity
                if (group.getOptions() != null) {
                    for (com.kotsin.consumer.entity.Scrip option : group.getOptions()) {
                        if (option.getScripCode() != null) {
                            reverseMapping.put(option.getScripCode(), equityScripCode);
                            optionCount++;
                        }
                    }
                }
            }
            
            int totalMappings = equityCount + futureCount + optionCount;
            log.info("✅ Family preload from MongoDB complete: {} equities, {} futures, {} options, {} symbols mapped, {} total reverse mappings",
                    equityCount, futureCount, optionCount, symbolToEquityMap.size(), totalMappings);
            
            // Log cache statistics after preload
            log.info("📊 Reverse mapping cache initialized with {} entries", reverseMapping.size());
            
            // Schedule periodic cache statistics logging (every 5 minutes)
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    logCacheStatistics();
                } catch (Exception e) {
                    log.warn("Error logging cache statistics: {}", e.getMessage());
                }
            }, 5, 5, TimeUnit.MINUTES);
                    
        } catch (Exception e) {
            log.error("❌ Failed to preload from MongoDB, falling back to API: {}", e.getMessage());
            preloadFromApi();
        }
    }
    
    /**
     * Log cache statistics for monitoring
     */
    private void logCacheStatistics() {
        java.util.Map<String, Object> stats = getCacheStats();
        log.info("📊 FamilyCacheAdapter Statistics: cacheHits={}, cacheMisses={}, cacheHitRate={}, " +
                 "mongoDbQueries={}, mongoDbHits={}, mongoDbMisses={}, mongoDbHitRate={}, cacheSize={}",
            stats.get("cacheHits"), stats.get("cacheMisses"), stats.get("cacheHitRate"),
            stats.get("mongoDbQueries"), stats.get("mongoDbHits"), stats.get("mongoDbMisses"),
            stats.get("mongoDbHitRate"), stats.get("reverseMappingSize"));
    }

    /**
     * Fallback: Preload from ScripFinder API if MongoDB fails
     */
    private void preloadFromApi() {
        log.info("🔄 Fallback: Starting family preload from ScripFinder API...");
        
        try {
            ScripFinderClient.AllEquitiesResponse response = scripFinderClient.getAllEquities();
            
            if (response == null || response.getResponse() == null) {
                log.warn("No equities returned from ScripFinder API");
                return;
            }
            
            int equityCount = 0;
            int futureCount = 0;
            int optionCount = 0;
            
            for (ScripFinderClient.EquityInfo equity : response.getResponse()) {
                String equityScripCode = equity.getEquityScripCode();
                if (equityScripCode == null) continue;
                
                reverseMapping.put(equityScripCode, equityScripCode);
                equityCount++;
                
                if (equity.getEquity() != null && equity.getEquity().getSymbolRoot() != null) {
                    symbolToEquityMap.put(equity.getEquity().getSymbolRoot().toUpperCase(), equityScripCode);
                }
                if (equity.getCompanyName() != null) {
                    symbolToEquityMap.put(equity.getCompanyName().toUpperCase(), equityScripCode);
                }
                
                // Fetch futures and options via API
                try {
                    ScripFinderClient.FutureResponse futureResp = scripFinderClient.getFuture(equityScripCode);
                    if (futureResp != null && futureResp.getResponse() != null && futureResp.getResponse().getScripCode() != null) {
                        reverseMapping.put(futureResp.getResponse().getScripCode(), equityScripCode);
                        futureCount++;
                    }
                } catch (Exception ignored) {}
                
                try {
                    ScripFinderClient.OptionsResponse optionsResp = scripFinderClient.getOptions(equityScripCode);
                    if (optionsResp != null && optionsResp.getResponse() != null) {
                        for (ScripFinderClient.ScripInfo option : optionsResp.getResponse()) {
                            if (option.getScripCode() != null) {
                                reverseMapping.put(option.getScripCode(), equityScripCode);
                                optionCount++;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            
            log.info("✅ API fallback preload complete: {} equities, {} futures, {} options mapped",
                    equityCount, futureCount, optionCount);
                    
        } catch (Exception e) {
            log.error("❌ API fallback also failed: {}", e.getMessage());
        }
    }

    /**
     * Get family for an equity scrip code
     *
     * @param equityScripCode Equity scrip code
     * @param closePrice Current close price (for ATM calculation)
     * @return InstrumentFamily or null
     * @throws IllegalArgumentException if closePrice is invalid
     */
    @Override
    public InstrumentFamily getFamily(String equityScripCode, double closePrice) {
        if (equityScripCode == null || equityScripCode.trim().isEmpty()) {
            log.warn("getFamily called with null or empty equityScripCode");
            return null;
        }
        if (closePrice <= 0) {
            log.warn("getFamily called with invalid closePrice: {} for scripCode: {}", closePrice, equityScripCode);
            return null;
        }

        InstrumentFamily cached = familyCache.get(equityScripCode);

        // Check if cached and fresh
        if (cached != null && isFresh(equityScripCode)) {
            log.debug("[FAMILY-CACHE-HIT] equityScripCode: {} | futureScripCode: {} | optionCount: {} | cached: true",
                equityScripCode,
                cached.getFutureScripCode() != null ? cached.getFutureScripCode() : "NULL",
                cached.getOptionCount());
            return cached;
        }

        // Log cache miss
        log.info("[FAMILY-CACHE-MISS] equityScripCode: {} | cached: {} | fresh: {} | refreshOnMiss: {}",
            equityScripCode,
            cached != null ? "yes" : "no",
            cached != null ? isFresh(equityScripCode) : "N/A",
            refreshOnMiss);

        // Refresh from API if enabled - use computeIfAbsent pattern for thread safety
        if (refreshOnMiss) {
            InstrumentFamily refreshed = refreshFamilyThreadSafe(equityScripCode, closePrice);
            if (refreshed != null) {
                log.info("[FAMILY-CACHE-REFRESHED] equityScripCode: {} | futureScripCode: {} | optionCount: {} | symbolRoot: {}",
                    equityScripCode,
                    refreshed.getFutureScripCode() != null ? refreshed.getFutureScripCode() : "NULL",
                    refreshed.getOptionCount(),
                    refreshed.getSymbolRoot() != null ? refreshed.getSymbolRoot() : "NULL");
            } else {
                log.warn("[FAMILY-CACHE-REFRESH-FAILED] equityScripCode: {} | refreshFamilyThreadSafe returned NULL",
                    equityScripCode);
            }
            return refreshed;
        }

        return cached;  // Return stale data if refresh disabled
    }

    // Metrics for tracking cache performance
    private long cacheHits = 0;
    private long cacheMisses = 0;
    private long mongoDbQueries = 0;
    private long mongoDbHits = 0;
    private long mongoDbMisses = 0;

    /**
     * Get equity scrip code for any instrument in the family
     *
     * @param scripCode Any scrip code (equity, future, or option)
     * @return Equity scrip code or null if not found
     */
    @Override
    public String getEquityScripCode(String scripCode) {
        if (scripCode == null) return null;
        
        // Fast path: Check cache first
        String cached = reverseMapping.get(scripCode);
        if (cached != null) {
            cacheHits++;
            if (log.isTraceEnabled()) {
                log.trace("[CACHE-HIT] scripCode: {} -> equity: {}", scripCode, cached);
            }
            return cached;
        }
        
        // Cache miss - need to query MongoDB
        cacheMisses++;
        log.debug("[CACHE-MISS] scripCode: {} not in cache, querying MongoDB...", scripCode);
        
        // Slow path: Query MongoDB ScripGroup collection directly
        try {
            mongoDbQueries++;
            long startTime = System.currentTimeMillis();
            java.util.Optional<com.kotsin.consumer.entity.ScripGroup> group = scripGroupRepository.findByScripCode(scripCode);
            long queryTime = System.currentTimeMillis() - startTime;
            
            if (group.isPresent()) {
                mongoDbHits++;
                String equityScripCode = group.get().getId(); // _id is equity scripCode
                // Cache for future lookups
                reverseMapping.put(scripCode, equityScripCode);
                log.info("[MONGODB-HIT] scripCode: {} -> equity: {} (queryTime: {}ms, cacheSize: {})",
                    scripCode, equityScripCode, queryTime, reverseMapping.size());
                return equityScripCode;
            } else {
                mongoDbMisses++;
                log.debug("[MONGODB-MISS] scripCode: {} not found in ScripGroup, trying Scrip collection fallback...",
                    scripCode);
            }
        } catch (Exception e) {
            mongoDbMisses++;
            log.error("[MONGODB-ERROR] Failed to query ScripGroup for scripCode: {} - {}",
                scripCode, e.getMessage(), e);
        }

        // ═══════════════════════════════════════════════════════════════════════════════
        // FIX: FALLBACK - Query Scrip collection directly when ScripGroup lookup fails
        // ═══════════════════════════════════════════════════════════════════════════════
        // Problem: ScripGroup.options[] may be incomplete (new strikes, new expiries)
        // Solution: Query Scrip collection directly, extract symbolRoot, find equity
        // ═══════════════════════════════════════════════════════════════════════════════
        try {
            java.util.Optional<Scrip> scripOpt = scripRepository.findByScripCode(scripCode);
            if (scripOpt.isPresent()) {
                Scrip scrip = scripOpt.get();
                String symbolRoot = scrip.getSymbolRoot();

                if (symbolRoot != null && !symbolRoot.isEmpty()) {
                    // Use symbolRoot to find equity scripCode
                    String equityScripCode = findEquityBySymbol(symbolRoot);
                    if (equityScripCode != null) {
                        // Cache for future lookups
                        reverseMapping.put(scripCode, equityScripCode);
                        log.info("[SCRIP-FALLBACK-SUCCESS] scripCode: {} -> symbolRoot: {} -> equity: {} (cached)",
                            scripCode, symbolRoot, equityScripCode);
                        return equityScripCode;
                    } else {
                        log.warn("[SCRIP-FALLBACK-PARTIAL] scripCode: {} found with symbolRoot: {} but no equity mapping",
                            scripCode, symbolRoot);
                    }
                } else {
                    // Try to extract symbol from fullName/name as last resort
                    // Scrip entity has 'name' (short) and 'fullName' (detailed)
                    String name = scrip.getFullName() != null ? scrip.getFullName() : scrip.getName();
                    if (name != null && !name.isEmpty()) {
                        String extractedSymbol = extractSymbolFromCompanyName(name);
                        if (extractedSymbol != null) {
                            String equityScripCode = findEquityBySymbol(extractedSymbol);
                            if (equityScripCode != null) {
                                reverseMapping.put(scripCode, equityScripCode);
                                log.info("[SCRIP-FALLBACK-NAME] scripCode: {} -> name: {} -> symbol: {} -> equity: {}",
                                    scripCode, name, extractedSymbol, equityScripCode);
                                return equityScripCode;
                            }
                        }
                    }
                    log.warn("[SCRIP-FALLBACK-FAILED] scripCode: {} found but no symbolRoot or valid name", scripCode);
                }
            } else {
                log.debug("[SCRIP-FALLBACK-NOTFOUND] scripCode: {} not found in Scrip collection either", scripCode);
            }
        } catch (Exception e) {
            log.error("[SCRIP-FALLBACK-ERROR] Failed to query Scrip for scripCode: {} - {}", scripCode, e.getMessage());
        }

        return null; // Not found in any collection
    }
    
    /**
     * Get cache statistics for monitoring
     */
    public java.util.Map<String, Object> getCacheStats() {
        long totalRequests = cacheHits + cacheMisses;
        double cacheHitRate = totalRequests > 0 ? (double) cacheHits / totalRequests * 100 : 0.0;
        double mongoDbHitRate = mongoDbQueries > 0 ? (double) mongoDbHits / mongoDbQueries * 100 : 0.0;
        
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("cacheHits", cacheHits);
        stats.put("cacheMisses", cacheMisses);
        stats.put("cacheHitRate", String.format("%.2f%%", cacheHitRate));
        stats.put("mongoDbQueries", mongoDbQueries);
        stats.put("mongoDbHits", mongoDbHits);
        stats.put("mongoDbMisses", mongoDbMisses);
        stats.put("mongoDbHitRate", String.format("%.2f%%", mongoDbHitRate));
        stats.put("reverseMappingSize", reverseMapping.size());
        return stats;
    }

    /**
     * Check if scripCode is an equity
     */
    public boolean isEquity(String scripCode) {
        String equityCode = reverseMapping.get(scripCode);
        return equityCode != null && equityCode.equals(scripCode);
    }

    /**
     * Check if family is cached and fresh (implements IFamilyDataProvider)
     */
    @Override
    public boolean isFamilyCached(String equityScripCode) {
        return isFresh(equityScripCode);
    }

    /**
     * Check if family is cached and fresh (internal helper)
     */
    private boolean isFresh(String equityScripCode) {
        Long updated = lastUpdated.get(equityScripCode);
        if (updated == null) return false;

        long ageMs = System.currentTimeMillis() - updated;
        return ageMs < TimeUnit.HOURS.toMillis(ttlHours);
    }

    /**
     * Thread-safe refresh using double-checked locking with per-key locks
     * FIXED: Replaced String.intern() anti-pattern with ConcurrentHashMap-based locks
     */
    private InstrumentFamily refreshFamilyThreadSafe(String equityScripCode, double closePrice) {
        // Double-checked locking pattern
        InstrumentFamily cached = familyCache.get(equityScripCode);
        if (cached != null && isFresh(equityScripCode)) {
            return cached;
        }

        // Get or create lock object for this key (no memory leak like String.intern())
        Object lock = refreshLocks.computeIfAbsent(equityScripCode, k -> new Object());

        synchronized (lock) {
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
    @Override
    public void cacheFamily(InstrumentFamily family) {
        if (family == null) {
            log.warn("cacheFamily called with null family");
            return;
        }
        if (family.getEquityScripCode() == null || family.getEquityScripCode().trim().isEmpty()) {
            log.warn("cacheFamily called with family having null/empty equityScripCode");
            return;
        }

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
     * Clear all caches (implements IFamilyDataProvider)
     */
    @Override
    public void clearCache() {
        familyCache.clear();
        reverseMapping.clear();
        lastUpdated.clear();
        refreshLocks.clear();
        log.info("Cleared all family caches");
    }

    /**
     * Alias for clearCache() for backward compatibility
     */
    public void clearAll() {
        clearCache();
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
     * 🛡️ CRITICAL FIX: Symbol to ScripCode Lookup Implementation
     *
     * Find equity scripCode by symbol name (implements IFamilyDataProvider)
     *
     * Strategy:
     * 1. Search in-memory cache for family with matching symbolRoot
     * 2. If not found, return null (API lookup not available yet)
     *
     * @param symbol Symbol name (e.g., "RELIANCE", "BANKNIFTY")
     * @return Equity scripCode or null if not found
     */
    @Override
    public String findEquityBySymbol(String symbol) {
        if (symbol == null || symbol.trim().isEmpty()) {
            return null;
        }

        String symbolUpper = symbol.toUpperCase().trim();

        // Strategy 1: Check preloaded symbolToEquityMap (fastest - O(1))
        String mapped = symbolToEquityMap.get(symbolUpper);
        if (mapped != null) {
            log.debug("Found equity by preloaded symbol map: {} -> {}", symbolUpper, mapped);
            return mapped;
        }

        // Strategy 2: Search cached families for matching symbolRoot
        for (Map.Entry<String, InstrumentFamily> entry : familyCache.entrySet()) {
            InstrumentFamily family = entry.getValue();
            if (family.getSymbolRoot() != null &&
                family.getSymbolRoot().toUpperCase().equals(symbolUpper)) {
                return family.getEquityScripCode();
            }
        }

        // Strategy 3: Query MongoDB for equity by symbolRoot (fallback)
        // ExchType "C" = Cash segment = Equity
        try {
            java.util.Optional<Scrip> scrip = scripRepository.findFirstBySymbolRootAndExchType(symbolUpper, "C");
            if (scrip.isPresent()) {
                String equityScripCode = scrip.get().getScripCode();
                log.info("Found equity by symbolRoot from MongoDB: {} -> {}", symbolUpper, equityScripCode);
                // Cache in symbolToEquityMap for future lookups
                symbolToEquityMap.put(symbolUpper, equityScripCode);
                return equityScripCode;
            }
        } catch (Exception e) {
            log.warn("Failed to query MongoDB for symbol {}: {}", symbolUpper, e.getMessage());
        }

        log.debug("No equity scripCode found for symbol: {}", symbolUpper);
        return null;
    }

    /**
     * Extract symbol from option/future company name
     * Handles formats like:
     * - "ADANIENT 30 DEC 2025 CE 2260.00" -> "ADANIENT"
     * - "BANKNIFTY 31 JAN 2026 CE 51500" -> "BANKNIFTY"
     * - "RELIANCE JAN FUT" -> "RELIANCE"
     */
    private String extractSymbolFromCompanyName(String companyName) {
        if (companyName == null || companyName.isEmpty()) {
            return null;
        }

        String trimmed = companyName.trim().toUpperCase();

        // Handle special multi-word symbols
        if (trimmed.startsWith("BANK NIFTY") || trimmed.startsWith("BANKNIFTY")) {
            return "BANKNIFTY";
        }
        if (trimmed.startsWith("NIFTY BANK")) {
            return "BANKNIFTY";
        }
        if (trimmed.startsWith("FIN NIFTY") || trimmed.startsWith("FINNIFTY")) {
            return "FINNIFTY";
        }
        if (trimmed.startsWith("MIDCP NIFTY") || trimmed.startsWith("MIDCPNIFTY")) {
            return "MIDCPNIFTY";
        }

        // Split by whitespace and take first part
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 0) {
            return null;
        }

        // First part is usually the symbol
        String firstPart = parts[0];

        // Validate: symbol should be alphabetic (possibly with & for M&M, L&T etc)
        if (firstPart.matches("[A-Z&]+")) {
            return firstPart;
        }

        return null;
    }

    /**
     * Shutdown the scheduler
     * FIXED: Added @PreDestroy to ensure cleanup on Spring shutdown
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        log.info("Shutting down FamilyCacheAdapter scheduler...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
                log.warn("Scheduler did not terminate in 10 seconds, forced shutdown");
            } else {
                log.info("Scheduler shut down successfully");
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for scheduler shutdown", e);
        }
    }
}
