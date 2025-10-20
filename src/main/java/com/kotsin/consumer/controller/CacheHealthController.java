package com.kotsin.consumer.controller;

import com.kotsin.consumer.model.InstrumentFamily;
import com.kotsin.consumer.service.MongoInstrumentFamilyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Health check and management endpoints for instrument family cache
 */
@RestController
@RequestMapping("/api/cache")
@RequiredArgsConstructor
@Slf4j
public class CacheHealthController {
    
    private final MongoInstrumentFamilyService cacheService;
    
    /**
     * Get cache health status
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getCacheHealth() {
        try {
            Map<String, Object> health = new HashMap<>();
            Map<String, Object> stats = cacheService.getCacheStats();
            
            health.put("status", "UP");
            health.put("timestamp", System.currentTimeMillis());
            health.putAll(stats);
            
            return ResponseEntity.ok(health);
        } catch (Exception e) {
            log.error("❌ Health check failed", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "DOWN");
            error.put("error", e.getMessage());
            error.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.status(500).body(error);
        }
    }
    
    /**
     * Get specific instrument family
     */
    @GetMapping("/family/{scripCode}")
    public ResponseEntity<InstrumentFamily> getFamily(@PathVariable String scripCode) {
        try {
            InstrumentFamily family = cacheService.getFamily(scripCode);
            if (family != null) {
                return ResponseEntity.ok(family);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("❌ Failed to get family for scripCode: {}", scripCode, e);
            return ResponseEntity.status(500).build();
        }
    }
    
    /**
     * Get cache statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getCacheStats() {
        try {
            Map<String, Object> stats = cacheService.getCacheStats();
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("❌ Failed to get cache stats", e);
            return ResponseEntity.status(500).build();
        }
    }
    
    /**
     * Manual cache refresh
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refreshCache() {
        try {
            log.info("🔄 Manual cache refresh triggered");
            cacheService.refreshCache();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Cache refresh completed successfully");
            response.put("timestamp", System.currentTimeMillis());
            response.put("cacheSize", cacheService.getCacheSize());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("❌ Manual cache refresh failed", e);
            
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Cache refresh failed: " + e.getMessage());
            error.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.status(500).body(error);
        }
    }
    
    /**
     * Clear cache
     */
    @PostMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearCache() {
        try {
            log.info("🗑️ Manual cache clear triggered");
            cacheService.clearCache();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Cache cleared successfully");
            response.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("❌ Manual cache clear failed", e);
            
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Cache clear failed: " + e.getMessage());
            error.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.status(500).body(error);
        }
    }
    
    /**
     * Get sample families (for testing)
     */
    @GetMapping("/sample")
    public ResponseEntity<Map<String, Object>> getSampleFamilies(@RequestParam(defaultValue = "5") int limit) {
        try {
            Map<String, InstrumentFamily> allFamilies = cacheService.getAllFamilies();
            
            Map<String, Object> response = new HashMap<>();
            response.put("totalFamilies", allFamilies.size());
            response.put("sampleSize", Math.min(limit, allFamilies.size()));
            response.put("families", allFamilies.entrySet().stream()
                .limit(limit)
                .collect(java.util.stream.Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue
                )));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("❌ Failed to get sample families", e);
            return ResponseEntity.status(500).build();
        }
    }
}
