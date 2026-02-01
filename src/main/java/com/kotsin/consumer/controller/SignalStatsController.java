package com.kotsin.consumer.controller;

import com.kotsin.consumer.stats.model.SignalHistory;
import com.kotsin.consumer.stats.model.SignalStats;
import com.kotsin.consumer.stats.repository.SignalHistoryRepository;
import com.kotsin.consumer.stats.tracker.SignalStatsTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SignalStatsController - REST API for signal statistics and history.
 */
@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
public class SignalStatsController {

    private final SignalStatsTracker statsTracker;
    private final SignalHistoryRepository historyRepository;

    // ==================== STATS ENDPOINTS ====================

    @GetMapping("/global")
    public ResponseEntity<SignalStats> getGlobalStats() {
        return ResponseEntity.ok(statsTracker.getGlobalStats());
    }

    @GetMapping("/symbol/{symbol}")
    public ResponseEntity<SignalStats> getSymbolStats(@PathVariable String symbol) {
        SignalStats stats = statsTracker.getSymbolStats(symbol);
        if (stats == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/type/{signalType}")
    public ResponseEntity<SignalStats> getTypeStats(@PathVariable String signalType) {
        SignalStats stats = statsTracker.getTypeStats(signalType);
        if (stats == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/report")
    public ResponseEntity<Map<String, Object>> getReport() {
        return ResponseEntity.ok(statsTracker.generateReport());
    }

    @GetMapping("/best-performers")
    public ResponseEntity<List<Map.Entry<String, SignalStats>>> getBestPerformers(
            @RequestParam(defaultValue = "5") int limit) {
        return ResponseEntity.ok(statsTracker.getBestPerformingTypes(limit));
    }

    @GetMapping("/worst-performers")
    public ResponseEntity<List<Map.Entry<String, SignalStats>>> getWorstPerformers(
            @RequestParam(defaultValue = "5") int limit) {
        return ResponseEntity.ok(statsTracker.getWorstPerformingTypes(limit));
    }

    @GetMapping("/equity-curve")
    public ResponseEntity<List<SignalStatsTracker.EquityPoint>> getEquityCurve() {
        return ResponseEntity.ok(statsTracker.getEquityCurve());
    }

    // ==================== HISTORY ENDPOINTS ====================

    @GetMapping("/history/{symbol}")
    public ResponseEntity<List<SignalHistory>> getHistory(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(statsTracker.getHistory(symbol, limit));
    }

    @GetMapping("/history/{symbol}/today")
    public ResponseEntity<List<SignalHistory>> getTodaySignals(@PathVariable String symbol) {
        return ResponseEntity.ok(statsTracker.getTodaySignals(symbol));
    }

    @GetMapping("/history/recent")
    public ResponseEntity<List<SignalHistory>> getRecentSignals(
            @RequestParam(defaultValue = "24") int hours) {
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);
        return ResponseEntity.ok(historyRepository.findRecentSignals(since));
    }

    // ==================== ACTIVE SIGNALS ====================

    @GetMapping("/active")
    public ResponseEntity<Map<String, Object>> getActiveSignals() {
        Map<String, Object> response = new HashMap<>();
        response.put("count", statsTracker.getActiveSignalsCount());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/active/{symbol}")
    public ResponseEntity<List<SignalHistory>> getActiveSignalsForSymbol(@PathVariable String symbol) {
        return ResponseEntity.ok(statsTracker.getActiveSignals(symbol));
    }

    // ==================== AGGREGATIONS ====================

    @GetMapping("/pnl/by-symbol")
    public ResponseEntity<List<SignalHistoryRepository.SymbolPnL>> getPnLBySymbol() {
        return ResponseEntity.ok(historyRepository.getPnLBySymbolRanking());
    }

    @GetMapping("/pnl/by-type")
    public ResponseEntity<List<SignalHistoryRepository.TypePerformance>> getPnLByType() {
        return ResponseEntity.ok(historyRepository.getPerformanceByAllTypes());
    }
}
