package com.kotsin.consumer.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST controller for controlling streaming candle operations from dashboard.
 */
@Slf4j
@RestController
@RequestMapping("/api/control")
public class StreamingControlApiController {

    // In-memory state for enabled/disabled scrips and timeframes
    private final Set<String> enabledScrips = ConcurrentHashMap.newKeySet();
    private final Set<String> enabledTimeframes = ConcurrentHashMap.newKeySet();

    public StreamingControlApiController() {
        // Default enabled timeframes
        enabledTimeframes.addAll(Set.of("1m", "5m", "15m"));
    }

    /**
     * Enable streaming for a scrip
     */
    @PostMapping("/scrip/{scripCode}/enable")
    public ResponseEntity<Map<String, Object>> enableScripStreaming(@PathVariable String scripCode) {
        enabledScrips.add(scripCode);
        log.info("Enabled streaming for scrip: {}", scripCode);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "scripCode", scripCode,
                "enabled", true,
                "message", "Streaming enabled for " + scripCode,
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    /**
     * Disable streaming for a scrip
     */
    @PostMapping("/scrip/{scripCode}/disable")
    public ResponseEntity<Map<String, Object>> disableScripStreaming(@PathVariable String scripCode) {
        enabledScrips.remove(scripCode);
        log.info("Disabled streaming for scrip: {}", scripCode);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "scripCode", scripCode,
                "enabled", false,
                "message", "Streaming disabled for " + scripCode,
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    /**
     * Get list of active scrips
     */
    @GetMapping("/scrip/active")
    public ResponseEntity<Map<String, Object>> getActiveScrips() {
        return ResponseEntity.ok(Map.of(
                "activeScrips", new ArrayList<>(enabledScrips),
                "count", enabledScrips.size(),
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    /**
     * Check if scrip streaming is enabled
     */
    @GetMapping("/scrip/{scripCode}/status")
    public ResponseEntity<Map<String, Object>> getScripStatus(@PathVariable String scripCode) {
        boolean enabled = enabledScrips.contains(scripCode);
        return ResponseEntity.ok(Map.of(
                "scripCode", scripCode,
                "enabled", enabled,
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    /**
     * Enable a timeframe
     */
    @PostMapping("/timeframe/{timeframe}/enable")
    public ResponseEntity<Map<String, Object>> enableTimeframe(@PathVariable String timeframe) {
        enabledTimeframes.add(timeframe);
        log.info("Enabled timeframe: {}", timeframe);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "timeframe", timeframe,
                "enabled", true,
                "message", "Timeframe " + timeframe + " enabled",
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    /**
     * Disable a timeframe
     */
    @PostMapping("/timeframe/{timeframe}/disable")
    public ResponseEntity<Map<String, Object>> disableTimeframe(@PathVariable String timeframe) {
        enabledTimeframes.remove(timeframe);
        log.info("Disabled timeframe: {}", timeframe);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "timeframe", timeframe,
                "enabled", false,
                "message", "Timeframe " + timeframe + " disabled",
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    /**
     * Get timeframe status
     */
    @GetMapping("/timeframe/status")
    public ResponseEntity<Map<String, Object>> getTimeframeStatus() {
        List<String> allTimeframes = List.of("1m", "2m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "1d");

        List<Map<String, Object>> timeframeStatus = new ArrayList<>();
        for (String tf : allTimeframes) {
            timeframeStatus.add(Map.of(
                    "timeframe", tf,
                    "enabled", enabledTimeframes.contains(tf),
                    "topicName", "family-candle-" + tf
            ));
        }

        return ResponseEntity.ok(Map.of(
                "timeframes", timeframeStatus,
                "enabledCount", enabledTimeframes.size(),
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    /**
     * Get overall streaming stats
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);

        return ResponseEntity.ok(Map.of(
                "enabledScrips", enabledScrips.size(),
                "enabledTimeframes", enabledTimeframes.size(),
                "memoryUsedMB", usedMemory,
                "availableProcessors", runtime.availableProcessors(),
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    /**
     * Check if scrip should be processed (used by processors)
     */
    public boolean isScripEnabled(String scripCode) {
        // If no scrips are explicitly enabled, process all
        if (enabledScrips.isEmpty()) {
            return true;
        }
        return enabledScrips.contains(scripCode);
    }

    /**
     * Check if timeframe is enabled (used by processors)
     */
    public boolean isTimeframeEnabled(String timeframe) {
        return enabledTimeframes.contains(timeframe);
    }
}
