package com.kotsin.consumer.controller;

import com.kotsin.consumer.model.StrategyState;
import com.kotsin.consumer.model.StrategyState.*;
import com.kotsin.consumer.service.StrategyStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * StrategyController - REST API for strategy state management (v2 architecture).
 *
 * Endpoints:
 * - GET /api/v2/strategy/{symbol}/vcp?timeframe=5m
 * - GET /api/v2/strategy/{symbol}/ipu?timeframe=5m
 * - GET /api/v2/strategy/{symbol}/pivot?timeframe=5m
 * - GET /api/v2/strategy/{symbol}/state?type=VCP&timeframe=5m
 * - DELETE /api/v2/strategy/{symbol}
 * - GET /api/v2/strategy/stats
 * - GET /api/v2/strategy/active/vcp
 * - GET /api/v2/strategy/active/ipu?minScore=0.6
 */
@RestController
@RequestMapping("/api/v2/strategy")
@Slf4j
public class StrategyController {

    @Autowired
    private StrategyStateService strategyStateService;

    // ==================== VCP ENDPOINTS ====================

    /**
     * Get VCP state for a symbol.
     */
    @GetMapping("/{symbol}/vcp")
    public ResponseEntity<VcpState> getVcpState(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "5m") String timeframe) {

        log.debug("[STRATEGY-API] Getting VCP state for {} at {}", symbol, timeframe);

        Optional<VcpState> state = strategyStateService.getVcpState(symbol, timeframe);

        return state.map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get VCP clusters for a symbol.
     */
    @GetMapping("/{symbol}/vcp/clusters")
    public ResponseEntity<Map<String, Object>> getVcpClusters(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "5m") String timeframe) {

        Optional<VcpState> state = strategyStateService.getVcpState(symbol, timeframe);

        if (state.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        VcpState vcp = state.get();
        Map<String, Object> response = new HashMap<>();
        response.put("symbol", symbol);
        response.put("timeframe", timeframe);
        response.put("supportClusters", vcp.getSupportClusters());
        response.put("resistanceClusters", vcp.getResistanceClusters());
        response.put("poc", vcp.getPocPrice());
        response.put("vah", vcp.getValueAreaHigh());
        response.put("val", vcp.getValueAreaLow());
        response.put("bullishRunway", vcp.getBullishRunway());
        response.put("bearishRunway", vcp.getBearishRunway());
        response.put("calculatedAt", vcp.getCalculatedAt());

        return ResponseEntity.ok(response);
    }

    // ==================== IPU ENDPOINTS ====================

    /**
     * Get IPU state for a symbol.
     */
    @GetMapping("/{symbol}/ipu")
    public ResponseEntity<IpuState> getIpuState(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "5m") String timeframe) {

        log.debug("[STRATEGY-API] Getting IPU state for {} at {}", symbol, timeframe);

        Optional<IpuState> state = strategyStateService.getIpuState(symbol, timeframe);

        return state.map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get IPU history for a symbol.
     */
    @GetMapping("/{symbol}/ipu/history")
    public ResponseEntity<List<IpuSnapshot>> getIpuHistory(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "5m") String timeframe,
            @RequestParam(defaultValue = "20") int count) {

        Optional<IpuState> state = strategyStateService.getIpuState(symbol, timeframe);

        if (state.isEmpty() || state.get().getHistory() == null) {
            return ResponseEntity.notFound().build();
        }

        List<IpuSnapshot> history = state.get().getHistory();
        int safeCount = Math.min(count, history.size());

        return ResponseEntity.ok(history.subList(0, safeCount));
    }

    /**
     * Get current IPU summary for a symbol.
     */
    @GetMapping("/{symbol}/ipu/current")
    public ResponseEntity<Map<String, Object>> getCurrentIpu(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "5m") String timeframe) {

        Optional<IpuState> state = strategyStateService.getIpuState(symbol, timeframe);

        if (state.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        IpuState ipu = state.get();
        Map<String, Object> response = new HashMap<>();
        response.put("symbol", symbol);
        response.put("timeframe", timeframe);
        response.put("currentScore", ipu.getCurrentIpuScore());
        response.put("exhaustion", ipu.getCurrentExhaustion());
        response.put("momentum", ipu.getCurrentMomentumState());
        response.put("direction", ipu.getCurrentDirection());
        response.put("avgScore10", ipu.getAvgIpuScore10());
        response.put("avgScore20", ipu.getAvgIpuScore20());
        response.put("ipuRising", ipu.isIpuRising());
        response.put("exhaustionBuilding", ipu.isExhaustionBuilding());
        response.put("calculatedAt", ipu.getCalculatedAt());

        return ResponseEntity.ok(response);
    }

    // ==================== PIVOT ENDPOINTS ====================

    /**
     * Get Pivot state for a symbol.
     */
    @GetMapping("/{symbol}/pivot")
    public ResponseEntity<PivotState> getPivotState(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "5m") String timeframe) {

        log.debug("[STRATEGY-API] Getting Pivot state for {} at {}", symbol, timeframe);

        Optional<PivotState> state = strategyStateService.getPivotState(symbol, timeframe);

        return state.map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get market structure for a symbol.
     */
    @GetMapping("/{symbol}/pivot/structure")
    public ResponseEntity<Map<String, Object>> getMarketStructure(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "5m") String timeframe) {

        Optional<PivotState> state = strategyStateService.getPivotState(symbol, timeframe);

        if (state.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        PivotState pivot = state.get();
        Map<String, Object> response = new HashMap<>();
        response.put("symbol", symbol);
        response.put("timeframe", timeframe);
        response.put("structure", pivot.getStructure());
        response.put("higherHighs", pivot.isHigherHighs());
        response.put("higherLows", pivot.isHigherLows());
        response.put("lowerHighs", pivot.isLowerHighs());
        response.put("lowerLows", pivot.isLowerLows());
        response.put("lastSwingHigh", pivot.getLastSwingHigh());
        response.put("lastSwingLow", pivot.getLastSwingLow());
        response.put("supportLevels", pivot.getSupportLevels());
        response.put("resistanceLevels", pivot.getResistanceLevels());
        response.put("calculatedAt", pivot.getCalculatedAt());

        return ResponseEntity.ok(response);
    }

    // ==================== GENERIC STATE ENDPOINTS ====================

    /**
     * Get full strategy state for a symbol.
     */
    @GetMapping("/{symbol}/state")
    public ResponseEntity<StrategyState> getFullState(
            @PathVariable String symbol,
            @RequestParam StrategyType type,
            @RequestParam(defaultValue = "5m") String timeframe) {

        Optional<StrategyState> state = strategyStateService.getState(symbol, type, timeframe);

        return state.map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delete all state for a symbol.
     */
    @DeleteMapping("/{symbol}")
    public ResponseEntity<Map<String, Object>> deleteSymbolState(@PathVariable String symbol) {
        log.info("[STRATEGY-API] Deleting all state for {}", symbol);

        strategyStateService.deleteState(symbol);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "deleted");
        response.put("symbol", symbol);

        return ResponseEntity.ok(response);
    }

    // ==================== AGGREGATE ENDPOINTS ====================

    /**
     * Get symbols with active VCP state.
     */
    @GetMapping("/active/vcp")
    public ResponseEntity<List<String>> getActiveVcpSymbols() {
        List<String> symbols = strategyStateService.getSymbolsWithActiveVcp();
        return ResponseEntity.ok(symbols);
    }

    /**
     * Get symbols with high IPU score.
     */
    @GetMapping("/active/ipu")
    public ResponseEntity<List<String>> getHighIpuSymbols(
            @RequestParam(defaultValue = "0.6") double minScore) {
        List<String> symbols = strategyStateService.getSymbolsWithHighIpu(minScore);
        return ResponseEntity.ok(symbols);
    }

    /**
     * Get strategy state statistics.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<StrategyType, Long> counts = strategyStateService.getStateCounts();

        Map<String, Object> response = new HashMap<>();
        response.put("stateCounts", counts);
        response.put("totalStates", counts.values().stream().mapToLong(Long::longValue).sum());

        return ResponseEntity.ok(response);
    }

    /**
     * Cleanup stale states.
     */
    @PostMapping("/cleanup")
    public ResponseEntity<Map<String, Object>> cleanupStaleStates(
            @RequestParam(defaultValue = "60") int staleMinutes) {

        log.info("[STRATEGY-API] Cleaning up states older than {} minutes", staleMinutes);

        int cleaned = strategyStateService.cleanupStaleStates(staleMinutes);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "cleaned");
        response.put("removedCount", cleaned);
        response.put("thresholdMinutes", staleMinutes);

        return ResponseEntity.ok(response);
    }

    /**
     * Clear caches (for debugging/testing).
     */
    @PostMapping("/cache/clear")
    public ResponseEntity<Map<String, Object>> clearCaches() {
        log.info("[STRATEGY-API] Clearing strategy state caches");

        strategyStateService.clearCaches();

        Map<String, Object> response = new HashMap<>();
        response.put("status", "cleared");

        return ResponseEntity.ok(response);
    }
}
