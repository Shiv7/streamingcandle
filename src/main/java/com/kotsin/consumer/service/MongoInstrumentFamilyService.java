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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MongoDB-based service to cache instrument families (equity + future + options)
 * Fetches from MongoDB ScripGroup collection and caches in in-memory LocalHashMap
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MongoInstrumentFamilyService {
    
    private final ScripGroupRepository scripGroupRepository;
    private final Map<String, InstrumentFamily> localCache = new ConcurrentHashMap<>();
    
    private static final Duration CACHE_TTL = Duration.ofDays(1);
    
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
        return InstrumentInfo.builder()
            .scripCode(scrip.getScripCode())
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
     * Resolve instrument family for derivatives by looking up the underlying equity
     */
    public InstrumentFamily resolveFamily(String scripCode, String exchangeType, String companyName) {
        try {
            // Non-derivative: normal equity lookup
            if (exchangeType == null || !"D".equalsIgnoreCase(exchangeType)) {
                return getFamily(scripCode);
            }
            
            // For derivatives, try to find the underlying equity family
            // Method 1: Look for ScripGroup containing this scripCode in futures or options
            List<ScripGroup> futureGroups = scripGroupRepository.findByFuturesScripCode(scripCode);
            if (!futureGroups.isEmpty()) {
                InstrumentFamily family = convertScripGroupToFamily(futureGroups.get(0));
                if (family != null) {
                    localCache.put(scripCode, family);
                    return family;
                }
            }
            
            List<ScripGroup> optionGroups = scripGroupRepository.findByOptionsScripCode(scripCode);
            if (!optionGroups.isEmpty()) {
                InstrumentFamily family = convertScripGroupToFamily(optionGroups.get(0));
                if (family != null) {
                    localCache.put(scripCode, family);
                    return family;
                }
            }
            
            // Method 2: Parse company name to extract symbol root and find by company name
            if (companyName != null && !companyName.isBlank()) {
                String symbolRoot = extractSymbolRoot(companyName);
                if (symbolRoot != null) {
                    List<ScripGroup> groups = scripGroupRepository.findByCompanyNameIgnoreCase(symbolRoot);
                    if (!groups.isEmpty()) {
                        InstrumentFamily family = convertScripGroupToFamily(groups.get(0));
                        if (family != null) {
                            localCache.put(scripCode, family);
                            return family;
                        }
                    }
                }
            }
            
            // Method 3: Fallback to direct lookup (might be an equity)
            return getFamily(scripCode);
            
        } catch (Exception e) {
            log.warn("Derivative resolution fallback for scripCode {}: {}", scripCode, e.toString());
            return getFamily(scripCode);
        }
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
