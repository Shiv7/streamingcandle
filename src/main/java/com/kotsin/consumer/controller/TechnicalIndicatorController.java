package com.kotsin.consumer.controller;

import com.kotsin.consumer.dto.TechnicalIndicatorDTO;
import com.kotsin.consumer.service.TechnicalIndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST Controller for Technical Indicators (Bollinger Bands, VWAP, SuperTrend).
 * Provides endpoints for dashboard display and chart overlays.
 */
@Slf4j
@RestController
@RequestMapping("/api/technical-indicators")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class TechnicalIndicatorController {

    private final TechnicalIndicatorService indicatorService;

    /**
     * Get latest technical indicators for a single scrip.
     *
     * @param scripCode Scrip code (e.g., "RELIANCE", "500325")
     * @param timeframe Timeframe (default: "5m")
     * @return TechnicalIndicatorDTO with BB, VWAP, SuperTrend values
     */
    @GetMapping("/{scripCode}")
    public ResponseEntity<TechnicalIndicatorDTO> getIndicators(
            @PathVariable String scripCode,
            @RequestParam(defaultValue = "5m") String timeframe) {

        log.debug("[TECH_API] GET indicators for {} [{}]", scripCode, timeframe);

        TechnicalIndicatorDTO indicators = indicatorService.getIndicators(scripCode, timeframe);

        if (indicators == null) {
            log.debug("[TECH_API] No indicators found for {} [{}]", scripCode, timeframe);
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(indicators);
    }

    /**
     * Get indicator history for chart overlays.
     *
     * @param scripCode Scrip code
     * @param timeframe Timeframe (default: "5m")
     * @param limit Maximum number of history points (default: 100)
     * @return List of TechnicalIndicatorDTO for chart rendering
     */
    @GetMapping("/{scripCode}/history")
    public ResponseEntity<List<TechnicalIndicatorDTO>> getIndicatorHistory(
            @PathVariable String scripCode,
            @RequestParam(defaultValue = "5m") String timeframe,
            @RequestParam(defaultValue = "100") int limit) {

        log.debug("[TECH_API] GET history for {} [{}], limit={}", scripCode, timeframe, limit);

        List<TechnicalIndicatorDTO> history = indicatorService.getIndicatorHistory(scripCode, timeframe, limit);

        return ResponseEntity.ok(history);
    }

    /**
     * Batch fetch indicators for multiple scrips (watchlist).
     *
     * @param scripCodes List of scrip codes
     * @param timeframe Timeframe (default: "5m")
     * @return Map of scripCode -> TechnicalIndicatorDTO
     */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, TechnicalIndicatorDTO>> getBatchIndicators(
            @RequestBody List<String> scripCodes,
            @RequestParam(defaultValue = "5m") String timeframe) {

        log.debug("[TECH_API] POST batch indicators for {} scrips [{}]", scripCodes.size(), timeframe);

        Map<String, TechnicalIndicatorDTO> indicators = indicatorService.getIndicatorsForWatchlist(scripCodes, timeframe);

        return ResponseEntity.ok(indicators);
    }

    /**
     * Get all available scrip codes with cached indicators.
     *
     * @param timeframe Timeframe (default: "5m")
     * @return Set of scrip codes
     */
    @GetMapping("/available")
    public ResponseEntity<Set<String>> getAvailableScrips(
            @RequestParam(defaultValue = "5m") String timeframe) {

        Set<String> scrips = indicatorService.getAvailableScrips(timeframe);
        return ResponseEntity.ok(scrips);
    }

    /**
     * Get cache statistics for monitoring.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getCacheStats() {
        Map<String, Object> stats = indicatorService.getCacheStats();
        return ResponseEntity.ok(stats);
    }

    /**
     * Clear cache for a specific scrip (admin operation).
     */
    @DeleteMapping("/cache/{scripCode}")
    public ResponseEntity<Map<String, String>> clearScripCache(@PathVariable String scripCode) {
        indicatorService.clearCache(scripCode);
        return ResponseEntity.ok(Map.of(
                "status", "cleared",
                "scripCode", scripCode
        ));
    }

    /**
     * Clear all caches (admin operation).
     */
    @DeleteMapping("/cache")
    public ResponseEntity<Map<String, String>> clearAllCaches() {
        indicatorService.clearAllCaches();
        return ResponseEntity.ok(Map.of("status", "all caches cleared"));
    }
}
