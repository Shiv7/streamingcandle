package com.kotsin.consumer.infrastructure.api;

import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * IndustryClient - Fetches industry/sector mapping from ScripFinder service.
 * 
 * ScripFinder has industry data loaded from NSE_nifty_symbol_seggregation.xlsx
 * stored in MongoDB collection 'nifty100_stocks'.
 * 
 * Used by CorrelationGovernor for dynamic cluster formation.
 * 
 * Caches results in-memory to avoid repeated API calls.
 */
@Slf4j
@Service
public class IndustryClient {

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    
    @Value("${scripfinder.base-url:http://localhost:8081}")
    private String scripFinderBaseUrl;
    
    // In-memory cache for fast lookups (companyName -> industry)
    private final Map<String, String> industryCache = new ConcurrentHashMap<>();
    
    // Default clusters if API fails or company not found
    private static final Map<String, String> FALLBACK_CLUSTERS = Map.ofEntries(
        // Banks
        Map.entry("HDFCBANK", "BANK"),
        Map.entry("ICICIBANK", "BANK"),
        Map.entry("KOTAKBANK", "BANK"),
        Map.entry("AXISBANK", "BANK"),
        Map.entry("SBIN", "BANK"),
        // IT
        Map.entry("INFY", "IT"),
        Map.entry("TCS", "IT"),
        Map.entry("WIPRO", "IT"),
        Map.entry("HCLTECH", "IT"),
        Map.entry("TECHM", "IT"),
        // Auto
        Map.entry("MARUTI", "AUTO"),
        Map.entry("TATAMOTORS", "AUTO"),
        Map.entry("M&M", "AUTO"),
        Map.entry("BAJAJ-AUTO", "AUTO"),
        // Metal
        Map.entry("TATASTEEL", "METAL"),
        Map.entry("JSWSTEEL", "METAL"),
        Map.entry("HINDALCO", "METAL"),
        // Pharma
        Map.entry("SUNPHARMA", "PHARMA"),
        Map.entry("DRREDDY", "PHARMA"),
        Map.entry("CIPLA", "PHARMA"),
        // Energy
        Map.entry("RELIANCE", "ENERGY"),
        Map.entry("ONGC", "ENERGY"),
        Map.entry("POWERGRID", "ENERGY"),
        // FMCG
        Map.entry("HINDUNILVR", "FMCG"),
        Map.entry("ITC", "FMCG"),
        Map.entry("NESTLEIND", "FMCG")
    );
    
    @PostConstruct
    public void init() {
        log.info("IndustryClient initialized with ScripFinder URL: {}", scripFinderBaseUrl);
        // Pre-populate with fallback data
        industryCache.putAll(FALLBACK_CLUSTERS);
    }

    /**
     * Get industry for a company name.
     * First checks cache, then calls ScripFinder API, then uses fallback.
     * 
     * @param companyName Company name (e.g., "HDFCBANK", "INFY")
     * @return Industry name (e.g., "BANK", "IT") or "UNKNOWN"
     */
    public String getIndustry(String companyName) {
        if (companyName == null || companyName.isEmpty()) {
            return "UNKNOWN";
        }
        
        String normalized = companyName.toUpperCase().trim();
        
        // Check cache first
        String cached = industryCache.get(normalized);
        if (cached != null) {
            return cached;
        }
        
        // Try ScripFinder API
        String industry = fetchFromScripFinder(normalized);
        if (industry != null && !industry.equals("INDEX") && !industry.equals("UNKNOWN")) {
            industryCache.put(normalized, industry);
            return industry;
        }
        
        // Fallback: try partial matching on known clusters
        for (Map.Entry<String, String> entry : FALLBACK_CLUSTERS.entrySet()) {
            if (normalized.contains(entry.getKey()) || entry.getKey().contains(normalized)) {
                industryCache.put(normalized, entry.getValue());
                return entry.getValue();
            }
        }
        
        return "UNKNOWN";
    }

    /**
     * Fetch industry from ScripFinder API
     */
    private String fetchFromScripFinder(String companyName) {
        try {
            HttpUrl url = HttpUrl.parse(scripFinderBaseUrl)
                    .newBuilder()
                    .addPathSegment("getIndustry")
                    .addQueryParameter("companyName", companyName)
                    .build();
            
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String body = response.body().string().trim();
                    // Remove quotes if present
                    body = body.replace("\"", "");
                    if (!body.isEmpty() && !body.equalsIgnoreCase("null")) {
                        log.debug("Industry for {} from ScripFinder: {}", companyName, body);
                        return body;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to fetch industry from ScripFinder for {}: {}", companyName, e.getMessage());
        }
        return null;
    }
    
    /**
     * Normalize an industry name to a standard cluster name
     */
    public String normalizeIndustry(String industry) {
        if (industry == null) return "UNKNOWN";
        
        String upper = industry.toUpperCase();
        
        // Normalize to standard clusters
        if (upper.contains("BANK") || upper.contains("FINANC")) return "BANK";
        if (upper.contains("SOFTWARE") || upper.contains("INFO") || upper.contains("TECH")) return "IT";
        if (upper.contains("AUTO") || upper.contains("VEHICLE")) return "AUTO";
        if (upper.contains("METAL") || upper.contains("STEEL") || upper.contains("ALUMIN")) return "METAL";
        if (upper.contains("PHARMA") || upper.contains("HEALTH") || upper.contains("DRUG")) return "PHARMA";
        if (upper.contains("OIL") || upper.contains("GAS") || upper.contains("ENERGY") || upper.contains("POWER")) return "ENERGY";
        if (upper.contains("FMCG") || upper.contains("CONSUMER") || upper.contains("FOOD")) return "FMCG";
        if (upper.contains("REAL") || upper.contains("CONSTRUCT") || upper.contains("CEMENT")) return "REALTY";
        if (upper.contains("TELECOM") || upper.contains("COMMUNIC")) return "TELECOM";
        
        return industry;
    }
    
    /**
     * Get cluster (normalized industry) for a company
     */
    public String getCluster(String companyName) {
        String industry = getIndustry(companyName);
        return normalizeIndustry(industry);
    }
    
    /**
     * Check if two companies are in the same cluster
     */
    public boolean areSameCluster(String company1, String company2) {
        String cluster1 = getCluster(company1);
        String cluster2 = getCluster(company2);
        return cluster1.equals(cluster2) && !cluster1.equals("UNKNOWN");
    }
    
    /**
     * Get cache stats
     */
    public int getCacheSize() {
        return industryCache.size();
    }
}
