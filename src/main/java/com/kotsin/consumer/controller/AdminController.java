package com.kotsin.consumer.controller;

import com.kotsin.consumer.infrastructure.redis.RedisCandleHistoryService;
import com.kotsin.consumer.trading.mtf.HtfCandleAggregator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AdminController - Administrative endpoints for bootstrap management.
 *
 * Endpoints:
 * - POST /admin/bootstrap/clear/{scripCode}     - Clear bootstrap state for one instrument
 * - POST /admin/bootstrap/clear-all             - Clear ALL bootstrap states (use with caution)
 * - POST /admin/bootstrap/clear-incomplete      - Clear only incomplete instruments
 * - POST /admin/bootstrap/trigger/{scripCode}   - Manually trigger bootstrap
 * - GET  /admin/bootstrap/status                - Get bootstrap statistics
 * - GET  /admin/bootstrap/incomplete            - List instruments with incomplete data
 * - GET  /admin/bootstrap/completeness/{scripCode} - Get detailed completeness for one instrument
 *
 * Security Note: These endpoints should be protected in production.
 * Consider adding @PreAuthorize or similar security annotations.
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final RedisCandleHistoryService candleHistoryService;
    private final HtfCandleAggregator htfCandleAggregator;

    /**
     * Clear bootstrap state for a specific scripCode.
     * The instrument will be re-bootstrapped on next candle access.
     *
     * @param scripCode Scrip code to clear
     * @return Success message
     */
    @PostMapping("/bootstrap/clear/{scripCode}")
    public ResponseEntity<Map<String, Object>> clearBootstrapState(@PathVariable String scripCode) {
        log.info("[ADMIN] Clearing bootstrap state for scripCode={}", scripCode);

        Map<String, Long> beforeState = candleHistoryService.getDataCompleteness(scripCode);
        candleHistoryService.clearBootstrapState(scripCode);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("scripCode", scripCode);
        response.put("action", "bootstrap_state_cleared");
        response.put("previousData", beforeState);
        response.put("message", "ScripCode " + scripCode + " will re-bootstrap on next access");

        return ResponseEntity.ok(response);
    }

    /**
     * Clear ALL bootstrap states.
     * WARNING: This will cause ALL instruments to re-bootstrap on next access.
     * Use with caution in production.
     *
     * @return Success message with count
     */
    @PostMapping("/bootstrap/clear-all")
    public ResponseEntity<Map<String, Object>> clearAllBootstrapStates() {
        log.warn("[ADMIN] Clearing ALL bootstrap states - this will trigger mass re-bootstrap!");

        Map<String, Object> beforeStats = candleHistoryService.getBootstrapStats();
        candleHistoryService.clearAllBootstrapState();
        Map<String, Object> afterStats = candleHistoryService.getBootstrapStats();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("action", "all_bootstrap_states_cleared");
        response.put("beforeStats", beforeStats);
        response.put("afterStats", afterStats);
        response.put("message", "All instruments will re-bootstrap on next access");
        response.put("warning", "This may cause high API load during re-bootstrap");

        return ResponseEntity.ok(response);
    }

    /**
     * Clear bootstrap state only for instruments with incomplete data.
     * This is safer than clear-all as it only affects problematic instruments.
     *
     * @return Count of cleared instruments
     */
    @PostMapping("/bootstrap/clear-incomplete")
    public ResponseEntity<Map<String, Object>> clearIncompleteBootstrapStates() {
        log.info("[ADMIN] Clearing bootstrap states for incomplete instruments");

        List<String> incompleteBefore = candleHistoryService.getIncompleteInstruments(100);
        int cleared = candleHistoryService.clearIncompleteBootstrapStates();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("action", "incomplete_bootstrap_states_cleared");
        response.put("clearedCount", cleared);
        response.put("clearedScripCodes", incompleteBefore);
        response.put("message", cleared + " instruments will re-bootstrap on next access");

        return ResponseEntity.ok(response);
    }

    /**
     * Manually trigger bootstrap for a specific scripCode.
     * Useful for testing or forcing immediate re-bootstrap.
     *
     * @param scripCode Scrip code to bootstrap
     * @param exchange  Exchange code (default: N for NSE)
     * @param exchType  Exchange type (default: C for Cash)
     * @return Bootstrap trigger status
     */
    @PostMapping("/bootstrap/trigger/{scripCode}")
    public ResponseEntity<Map<String, Object>> triggerBootstrap(
            @PathVariable String scripCode,
            @RequestParam(defaultValue = "N") String exchange,
            @RequestParam(defaultValue = "C") String exchType) {

        log.info("[ADMIN] Manually triggering bootstrap for scripCode={} exchange={}:{}",
                scripCode, exchange, exchType);

        // Clear existing state first
        candleHistoryService.clearBootstrapState(scripCode);

        // Trigger async bootstrap
        candleHistoryService.bootstrapFromApiAsync(scripCode, exchange, exchType);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "triggered");
        response.put("scripCode", scripCode);
        response.put("exchange", exchange);
        response.put("exchangeType", exchType);
        response.put("message", "Bootstrap triggered asynchronously. Check /admin/bootstrap/completeness/" +
                scripCode + " for progress.");

        return ResponseEntity.ok(response);
    }

    /**
     * Get bootstrap statistics.
     *
     * @return Bootstrap stats (version, counts by state)
     */
    @GetMapping("/bootstrap/status")
    public ResponseEntity<Map<String, Object>> getBootstrapStatus() {
        Map<String, Object> stats = candleHistoryService.getBootstrapStats();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("bootstrapStats", stats);

        return ResponseEntity.ok(response);
    }

    /**
     * List instruments with incomplete data.
     *
     * @param limit Maximum number to return (default: 50)
     * @return List of scripCodes with incomplete data
     */
    @GetMapping("/bootstrap/incomplete")
    public ResponseEntity<Map<String, Object>> getIncompleteInstruments(
            @RequestParam(defaultValue = "50") int limit) {

        List<String> incomplete = candleHistoryService.getIncompleteInstruments(limit);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("incompleteCount", incomplete.size());
        response.put("limit", limit);
        response.put("incompleteScripCodes", incomplete);

        if (incomplete.isEmpty()) {
            response.put("message", "No incomplete instruments found");
        } else {
            response.put("message", "Found " + incomplete.size() + " instruments with incomplete data. " +
                    "Use POST /admin/bootstrap/clear-incomplete to trigger re-bootstrap.");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get detailed data completeness for a specific scripCode.
     *
     * @param scripCode Scrip code to check
     * @return Candle counts per timeframe
     */
    @GetMapping("/bootstrap/completeness/{scripCode}")
    public ResponseEntity<Map<String, Object>> getDataCompleteness(@PathVariable String scripCode) {
        Map<String, Long> completeness = candleHistoryService.getDataCompleteness(scripCode);
        RedisCandleHistoryService.BootstrapState state = candleHistoryService.getBootstrapState(scripCode);
        boolean isComplete = candleHistoryService.hasCompleteData(scripCode);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("scripCode", scripCode);
        response.put("bootstrapState", state.name());
        response.put("isComplete", isComplete);
        response.put("candleCountsByTimeframe", completeness);

        // Calculate total candles
        long totalCandles = completeness.values().stream().mapToLong(Long::longValue).sum();
        response.put("totalCandles", totalCandles);

        // List missing timeframes
        List<String> missingTimeframes = completeness.entrySet().stream()
                .filter(e -> e.getValue() < 10)
                .map(Map.Entry::getKey)
                .toList();
        response.put("missingOrLowTimeframes", missingTimeframes);

        if (isComplete) {
            response.put("message", "All timeframes have sufficient data");
        } else {
            response.put("message", "Missing or insufficient data for: " + String.join(", ", missingTimeframes));
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Health check endpoint for the bootstrap system.
     */
    @GetMapping("/bootstrap/health")
    public ResponseEntity<Map<String, Object>> bootstrapHealth() {
        Map<String, Object> stats = candleHistoryService.getBootstrapStats();

        int inProgress = (int) stats.getOrDefault("inProgress", 0);
        int failed = (int) stats.getOrDefault("failed", 0);
        int success = (int) stats.getOrDefault("success", 0);

        String healthStatus = "healthy";
        if (failed > success / 10) {  // More than 10% failed
            healthStatus = "degraded";
        }
        if (inProgress > 50) {  // Too many in progress (backlog)
            healthStatus = "degraded";
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", healthStatus);
        response.put("bootstrapVersion", stats.get("bootstrapVersion"));
        response.put("success", success);
        response.put("failed", failed);
        response.put("inProgress", inProgress);
        response.put("totalTracked", stats.get("totalTracked"));

        return ResponseEntity.ok(response);
    }

    // =============================================================================
    // HTF CANDLE AGGREGATOR BOOTSTRAP MANAGEMENT
    // =============================================================================

    /**
     * Clear HTF bootstrap attempts for a specific scripCode.
     * The instrument will retry bootstrap on next candle access.
     *
     * @param scripCode Scrip code to clear
     * @return Success message
     */
    @PostMapping("/htf/clear/{scripCode}")
    public ResponseEntity<Map<String, Object>> clearHtfBootstrapAttempts(@PathVariable String scripCode) {
        log.info("[ADMIN] Clearing HTF bootstrap attempts for scripCode={}", scripCode);

        int cleared = htfCandleAggregator.clearBootstrapAttempts(scripCode);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("scripCode", scripCode);
        response.put("clearedEntries", cleared);
        response.put("message", "ScripCode " + scripCode + " will retry HTF bootstrap on next candle");

        return ResponseEntity.ok(response);
    }

    /**
     * Clear HTF bootstrap attempts for all MCX instruments (scripCode >= 400000).
     * Use this after fixing MCX bootstrap issues.
     *
     * @return Count of cleared entries
     */
    @PostMapping("/htf/clear-mcx")
    public ResponseEntity<Map<String, Object>> clearMcxHtfBootstrapAttempts() {
        log.info("[ADMIN] Clearing HTF bootstrap attempts for all MCX instruments (scripCode >= 400000)");

        Map<String, Object> beforeStats = htfCandleAggregator.getBootstrapAttemptStats();
        int cleared = htfCandleAggregator.clearMcxBootstrapAttempts();
        Map<String, Object> afterStats = htfCandleAggregator.getBootstrapAttemptStats();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("action", "mcx_htf_bootstrap_cleared");
        response.put("clearedEntries", cleared);
        response.put("beforeStats", beforeStats);
        response.put("afterStats", afterStats);
        response.put("message", "MCX instruments (scripCode >= 400000) will retry HTF bootstrap on next candle");

        return ResponseEntity.ok(response);
    }

    /**
     * Get HTF bootstrap attempt statistics.
     *
     * @return Bootstrap attempt stats
     */
    @GetMapping("/htf/stats")
    public ResponseEntity<Map<String, Object>> getHtfBootstrapStats() {
        Map<String, Object> stats = htfCandleAggregator.getBootstrapAttemptStats();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("htfBootstrapStats", stats);

        return ResponseEntity.ok(response);
    }
}
