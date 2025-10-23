package com.kotsin.consumer.service;

import com.kotsin.consumer.entity.Scrip;
import com.kotsin.consumer.entity.ScripGroup;
import com.kotsin.consumer.model.InstrumentFamily;
import com.kotsin.consumer.model.InstrumentInfo;
import com.kotsin.consumer.repository.ScripGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MongoDB-based service to cache instrument families (equity + future + options)
 * Fetches from MongoDB ScripGroup collection and caches in in-memory LocalHashMap
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MongoInstrumentFamilyService {
    
    private final ScripGroupRepository scripGroupRepository;
    
    // OPTIMIZED: Two-level cache for O(1) lookups
    private final Map<String, InstrumentFamily> localCache = new ConcurrentHashMap<>();
    
    // NEW: Scrip-to-FamilyKey lookup map (scripCode → equityScripCode)
    // This eliminates ALL blocking DB calls during real-time processing
    private final Map<String, String> scripToFamilyMap = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void initializeCache() {
        log.info("🚀 Initializing MongoDB-based instrument family cache...");
        try {
            refreshCache();
            log.info("✅ MongoDB instrument family cache initialized successfully");
        } catch (Exception e) {
            log.error("❌ Failed to initialize MongoDB instrument family cache", e);
        }
    }
    
    /**
     * Daily refresh at 3 AM IST
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void dailyRefresh() {
        log.info("🔄 Daily MongoDB cache refresh started...");
        refreshCache();
    }
    
    /**
     * Manual cache refresh
     */
    public void refreshCache() {
        try {
            long startTime = System.currentTimeMillis();
            
            // 1. Get all ScripGroups from MongoDB
            List<ScripGroup> scripGroups = scripGroupRepository.findByTradingType("EQUITY");
            log.info("📊 Found {} ScripGroups from MongoDB for tradingType=EQUITY", scripGroups.size());
            
            // Debug: Try to get all ScripGroups to see what's in the database
            List<ScripGroup> allScripGroups = scripGroupRepository.findAll();
            log.info("📊 Total ScripGroups in database: {}", allScripGroups.size());
            if (!allScripGroups.isEmpty()) {
                ScripGroup first = allScripGroups.get(0);
                log.info("📊 Sample ScripGroup - tradingType: '{}', equityScripCode: '{}', companyName: '{}'", 
                    first.getTradingType(), first.getEquityScripCode(), first.getCompanyName());
            } else {
                log.warn("⚠️ No ScripGroups found in database at all! Check database name and collection name.");
                log.warn("⚠️ Current MongoDB URI: mongodb://localhost:27017/tradeIngestion");
                log.warn("⚠️ Expected collection: ScripGroup");
                log.warn("⚠️ Expected tradingType values: EQUITY, FUTURE, OPTION");
            }
            
            if (scripGroups.isEmpty()) {
                log.warn("⚠️ No ScripGroups found in MongoDB, skipping cache refresh");
                return;
            }
            
            // 2. Convert ScripGroups to InstrumentFamilies
            Map<String, InstrumentFamily> families = convertScripGroupsToFamilies(scripGroups);
            
            // 3. Update local cache (in-memory only)
            localCache.clear();
            localCache.putAll(families);
            
            // 4. Build scrip-to-family lookup map for ALL derivatives
            scripToFamilyMap.clear();
            buildScripToFamilyMap(scripGroups);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("✅ MongoDB cache refresh complete. Loaded {} families in {}ms", 
                families.size(), duration);
            
        } catch (Exception e) {
            log.error("❌ MongoDB cache refresh failed", e);
        }
    }
    
    private Map<String, InstrumentFamily> convertScripGroupsToFamilies(List<ScripGroup> scripGroups) {
        Map<String, InstrumentFamily> families = new ConcurrentHashMap<>();
        
        for (ScripGroup scripGroup : scripGroups) {
            try {
                InstrumentFamily family = convertScripGroupToFamily(scripGroup);
                if (family != null) {
                    families.put(scripGroup.getEquityScripCode(), family);
                }
            } catch (Exception e) {
                log.error("❌ Failed to convert ScripGroup to InstrumentFamily: {}", 
                    scripGroup.getEquityScripCode(), e);
            }
        }
        
        return families;
    }
    
    /**
     * Build scrip-to-family lookup map for O(1) derivative resolution
     * Maps: derivativeScripCode → underlyingEquityScripCode
     * 
     * Example for PNB family:
     * - "10666" (equity) → "10666" (itself)
     * - "49067" (future) → "10666" (PNB equity)
     * - "112921" (option) → "10666" (PNB equity)
     */
    private void buildScripToFamilyMap(List<ScripGroup> scripGroups) {
        int equityCount = 0;
        int futureCount = 0;
        int optionCount = 0;
        
        for (ScripGroup group : scripGroups) {
            String equityScripCode = group.getEquityScripCode();
            if (equityScripCode == null || equityScripCode.isBlank()) {
                continue;
            }
            
            // Map equity to itself
            if (group.getEquity() != null && group.getEquity().getScripCode() != null) {
                scripToFamilyMap.put(group.getEquity().getScripCode(), equityScripCode);
                equityCount++;
            }
            
            // Map all futures to equity
            if (group.getFutures() != null) {
                for (Scrip future : group.getFutures()) {
                    if (future.getScripCode() != null) {
                        scripToFamilyMap.put(future.getScripCode(), equityScripCode);
                        futureCount++;
                    }
                }
            }
            
            // Map all options to equity
            if (group.getOptions() != null) {
                for (Scrip option : group.getOptions()) {
                    if (option.getScripCode() != null) {
                        scripToFamilyMap.put(option.getScripCode(), equityScripCode);
                        optionCount++;
                    }
                }
            }
        }
        
        log.info("📊 Built scrip-to-family map: {} equities, {} futures, {} options = {} total mappings",
            equityCount, futureCount, optionCount, scripToFamilyMap.size());
    }
    
    private InstrumentFamily convertScripGroupToFamily(ScripGroup scripGroup) {
        try {
            // Convert equity Scrip to InstrumentInfo
            InstrumentInfo equityInfo = null;
            if (scripGroup.getEquity() != null) {
                equityInfo = convertScripToInstrumentInfo(scripGroup.getEquity());
            }
            
            // Convert futures
            InstrumentInfo futureInfo = null;
            if (scripGroup.getFutures() != null && !scripGroup.getFutures().isEmpty()) {
                futureInfo = convertScripToInstrumentInfo(scripGroup.getFutures().get(0));
            }
            
            // Convert options
            List<InstrumentInfo> optionsInfo = new ArrayList<>();
            if (scripGroup.getOptions() != null) {
                for (Scrip option : scripGroup.getOptions()) {
                    optionsInfo.add(convertScripToInstrumentInfo(option));
                }
            }
            
            return InstrumentFamily.builder()
                .equityScripCode(scripGroup.getEquityScripCode())
                .companyName(scripGroup.getCompanyName())
                .equity(equityInfo)
                .future(futureInfo)
                .options(optionsInfo)
                .lastUpdated(System.currentTimeMillis())
                .dataSource("MONGODB")
                .build();
                
        } catch (Exception e) {
            log.error("❌ Failed to convert ScripGroup to InstrumentFamily: {}", 
                scripGroup.getEquityScripCode(), e);
            return null;
        }
    }
    
    private InstrumentInfo convertScripToInstrumentInfo(Scrip scrip) {
        // Attempt to extract token from scripData JSON if present (non-fatal if absent)
        String token = null;
        try {
            if (scrip.getScripData() != null && !scrip.getScripData().isBlank()) {
                String data = scrip.getScripData();
                // naive extract: "Token": 12345 or "token":"12345"
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"[Tt]oken\"\s*:\s*\"?(\\d+)\"?").matcher(data);
                if (m.find()) {
                    token = m.group(1);
                }
            }
        } catch (Exception ignore) {}

        return InstrumentInfo.builder()
            .scripCode(scrip.getScripCode())
            .token(token)
            .name(scrip.getName())
            .fullName(scrip.getFullName())
            .exchange(scrip.getExch())
            .exchangeType(scrip.getExchType())
            .series(scrip.getSeries())
            .expiry(scrip.getExpiry())
            .scripType(scrip.getScripType())
            .strikeRate(scrip.getStrikeRate() != null ? Double.parseDouble(scrip.getStrikeRate()) : null)
            .tickSize(scrip.getTickSize() != null ? Double.parseDouble(scrip.getTickSize()) : null)
            .lotSize(scrip.getLotSize() != null ? Integer.parseInt(scrip.getLotSize()) : null)
            .isin(scrip.getISIN())
            .symbolRoot(scrip.getSymbolRoot())
            .bocoallowed(scrip.getBOCOAllowed())
            .id(scrip.getId())
            .scriptTypeKotsin(scrip.getScriptTypeKotsin())
            .insertionDate(scrip.getInsertionDate() != null ? scrip.getInsertionDate().toString() : null)
            .multiplier(scrip.getMultiplier())
            .qtyLimit(scrip.getQtyLimit())
            .scripData(scrip.getScripData())
            .build();
    }
    
    // Redis removed; in-memory only
    
    /**
     * Get instrument family by scripCode (equity lookup)
     */
    public InstrumentFamily getFamily(String scripCode) {
        if (scripCode == null || scripCode.trim().isEmpty()) {
            return null;
        }
        
        // Try local cache first
        InstrumentFamily family = localCache.get(scripCode);
        if (family != null) {
            return family;
        }
        
        return null;
    }
    
    /**
     * OPTIMIZED: Resolve instrument family using in-memory lookup ONLY
     * NO BLOCKING DB CALLS - Uses pre-built scripToFamilyMap
     * 
     * Performance: O(1) lookup vs O(n) DB query
     */
    public InstrumentFamily resolveFamily(String scripCode, String exchangeType, String companyName) {
        if (scripCode == null || scripCode.isBlank()) {
            return null;
        }
        
        // Step 1: Try scrip-to-family map (O(1) lookup)
        String familyKey = scripToFamilyMap.get(scripCode);
        if (familyKey != null) {
            InstrumentFamily family = localCache.get(familyKey);
            if (family != null) {
                log.debug("✅ Resolved derivative {} → family {} (cache hit)", scripCode, familyKey);
                return family;
            }
        }
        
        // Step 2: Fallback for equities or non-mapped scrips
        InstrumentFamily family = localCache.get(scripCode);
        if (family != null) {
            log.debug("✅ Found equity family for {} (direct lookup)", scripCode);
            return family;
        }
        
        // Step 3: Last resort - extract from company name (NO DB CALL)
        if (companyName != null && !companyName.isBlank()) {
            String symbolRoot = extractSymbolRoot(companyName);
            if (symbolRoot != null) {
                family = localCache.get(symbolRoot);
                if (family != null) {
                    log.debug("✅ Resolved {} → family {} (name extraction)", scripCode, symbolRoot);
                    return family;
                }
            }
        }
        
        log.debug("❌ No family found for scripCode: {} (not in cache)", scripCode);
        return null;
    }
    
    private String extractSymbolRoot(String companyName) {
        if (companyName == null || companyName.isBlank()) {
            return null;
        }
        
        // Extract first word as symbol root (e.g., "LT 28 OCT 2025 CE 3850.00" -> "LT")
        String[] parts = companyName.split("\\s+");
        if (parts.length > 0) {
            return parts[0].replaceAll("[^A-Za-z0-9&]", "");
        }
        
        return null;
    }
    
    /**
     * Get all cached families
     */
    public Map<String, InstrumentFamily> getAllFamilies() {
        return new HashMap<>(localCache);
    }

    /**
     * Fast lookup for instrument details by scripCode using the in-memory cache only.
     * Returns the matching InstrumentInfo (equity/future/option) if present.
     * No Mongo queries are performed here.
     */
    public InstrumentInfo getInstrumentInfoByScripCode(String scripCode) {
        if (scripCode == null || scripCode.isBlank()) {
            return null;
        }

        // If the scrip maps to an equity family key, search within that family
        String familyKey = scripToFamilyMap.get(scripCode);
        InstrumentFamily family;
        if (familyKey != null) {
            family = localCache.get(familyKey);
        } else {
            // It might be an equity itself
            family = localCache.get(scripCode);
        }

        if (family == null) {
            return null;
        }

        // Equity
        if (family.getEquity() != null && scripCode.equals(family.getEquity().getScripCode())) {
            return family.getEquity();
        }

        // Future
        if (family.getFuture() != null && scripCode.equals(family.getFuture().getScripCode())) {
            return family.getFuture();
        }

        // Options
        if (family.getOptions() != null) {
            for (InstrumentInfo opt : family.getOptions()) {
                if (scripCode.equals(opt.getScripCode())) {
                    return opt;
                }
            }
        }

        return null;
    }
    
    /**
     * Get cache size
     */
    public int getCacheSize() {
        return localCache.size();
    }
    
    /**
     * Get cache statistics
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cacheSize", localCache.size());
        stats.put("familiesWithFutures", localCache.values().stream()
            .mapToInt(f -> f.hasFuture() ? 1 : 0)
            .sum());
        stats.put("familiesWithOptions", localCache.values().stream()
            .mapToInt(f -> f.hasOptions() ? 1 : 0)
            .sum());
        stats.put("totalOptions", localCache.values().stream()
            .mapToInt(InstrumentFamily::getOptionsCount)
            .sum());
        stats.put("mongodbFamilies", localCache.values().stream()
            .mapToInt(f -> "MONGODB".equals(f.getDataSource()) ? 1 : 0)
            .sum());
        return stats;
    }
    
    /**
     * Clear cache
     */
    public void clearCache() {
        localCache.clear();
        log.info("🗑️ Local cache cleared");
    }
}
