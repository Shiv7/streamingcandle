package com.kotsin.consumer.controller;

import com.kotsin.consumer.signal.engine.SignalEngine;
import com.kotsin.consumer.signal.model.*;
import com.kotsin.consumer.signal.repository.TradingSignalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SignalController - REST API for trading signals.
 *
 * Endpoints:
 * - GET /api/v2/signals/active - Get all active signals
 * - GET /api/v2/signals/active/{symbol} - Get active signal for symbol
 * - GET /api/v2/signals/{signalId} - Get signal by ID
 * - GET /api/v2/signals/history - Get signal history
 * - GET /api/v2/signals/history/{symbol} - Get signal history for symbol
 * - POST /api/v2/signals/process/{symbol} - Force process a symbol
 * - GET /api/v2/signals/engine/stats - Get engine stats
 * - GET /api/v2/signals/analytics - Get signal analytics
 */
@RestController
@RequestMapping("/api/v2/signals")
@Slf4j
public class SignalController {

    @Autowired
    private SignalEngine signalEngine;

    @Autowired
    private TradingSignalRepository signalRepository;

    // ==================== ACTIVE SIGNALS ====================

    /**
     * Get all active signals (WATCH or ACTIVE state).
     */
    @GetMapping("/active")
    public ResponseEntity<List<TradingSignal>> getActiveSignals() {
        List<TradingSignal> signals = signalEngine.getAllActiveSignals();
        return ResponseEntity.ok(signals);
    }

    /**
     * Get active signal for a specific symbol.
     */
    @GetMapping("/active/{symbol}")
    public ResponseEntity<TradingSignal> getActiveSignal(@PathVariable String symbol) {
        Optional<TradingSignal> signal = signalEngine.getActiveSignal(symbol);
        return signal.map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get signals by state.
     */
    @GetMapping("/state/{state}")
    public ResponseEntity<List<TradingSignal>> getSignalsByState(@PathVariable SignalState state) {
        List<TradingSignal> signals = signalEngine.getSignalsByState(state);
        return ResponseEntity.ok(signals);
    }

    // ==================== SIGNAL LOOKUP ====================

    /**
     * Get signal by ID.
     */
    @GetMapping("/{signalId}")
    public ResponseEntity<TradingSignal> getSignal(@PathVariable String signalId) {
        Optional<TradingSignal> signal = signalRepository.findBySignalId(signalId);
        return signal.map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    // ==================== SIGNAL HISTORY ====================

    /**
     * Get signal history with pagination.
     */
    @GetMapping("/history")
    public ResponseEntity<List<TradingSignal>> getSignalHistory(
            @RequestParam(defaultValue = "COMPLETE") SignalState state,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(page, size);
        List<TradingSignal> signals = signalRepository.findByStateOrderByCreatedAtDesc(state, pageable);
        return ResponseEntity.ok(signals);
    }

    /**
     * Get signal history for a specific symbol.
     */
    @GetMapping("/history/{symbol}")
    public ResponseEntity<List<TradingSignal>> getSignalHistoryBySymbol(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(page, size);
        List<TradingSignal> signals = signalRepository.findBySymbolOrderByCreatedAtDesc(symbol, pageable);
        return ResponseEntity.ok(signals);
    }

    /**
     * Get completed signals for a symbol.
     */
    @GetMapping("/history/{symbol}/completed")
    public ResponseEntity<List<TradingSignal>> getCompletedSignals(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(page, size);
        List<TradingSignal> signals = signalRepository.findCompletedBySymbol(symbol, pageable);
        return ResponseEntity.ok(signals);
    }

    /**
     * Get signals by date range.
     */
    @GetMapping("/history/range")
    public ResponseEntity<List<TradingSignal>> getSignalsByDateRange(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        Instant start = startDate != null ? Instant.parse(startDate) :
            Instant.now().minus(7, ChronoUnit.DAYS);
        Instant end = endDate != null ? Instant.parse(endDate) : Instant.now();

        List<TradingSignal> signals = signalRepository.findByCreatedAtBetween(start, end);
        return ResponseEntity.ok(signals);
    }

    // ==================== MANUAL OPERATIONS ====================

    /**
     * Force process a symbol and return FUDKII score.
     */
    @PostMapping("/process/{symbol}")
    public ResponseEntity<Map<String, Object>> forceProcess(@PathVariable String symbol) {
        log.info("[SIGNAL-API] Force processing {}", symbol);

        FudkiiScore score = signalEngine.forceProcess(symbol);

        if (score == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("symbol", symbol);
        response.put("score", score);
        response.put("processedAt", Instant.now());

        // Check for active signal after processing
        Optional<TradingSignal> active = signalEngine.getActiveSignal(symbol);
        active.ifPresent(signal -> response.put("activeSignal", signal));

        return ResponseEntity.ok(response);
    }

    // ==================== ENGINE STATS ====================

    /**
     * Get signal engine stats.
     */
    @GetMapping("/engine/stats")
    public ResponseEntity<Map<String, Object>> getEngineStats() {
        Map<String, Object> stats = signalEngine.getStats();
        return ResponseEntity.ok(stats);
    }

    /**
     * Check if engine is running.
     */
    @GetMapping("/engine/status")
    public ResponseEntity<Map<String, Object>> getEngineStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("running", signalEngine.isRunning());
        status.put("timestamp", Instant.now());
        return ResponseEntity.ok(status);
    }

    // ==================== ANALYTICS ====================

    /**
     * Get signal analytics summary.
     */
    @GetMapping("/analytics")
    public ResponseEntity<Map<String, Object>> getAnalytics() {
        Map<String, Object> analytics = new HashMap<>();

        // Counts by state
        analytics.put("watchCount", signalRepository.countByState(SignalState.WATCH));
        analytics.put("activeCount", signalRepository.countByState(SignalState.ACTIVE));
        analytics.put("completeCount", signalRepository.countByState(SignalState.COMPLETE));
        analytics.put("expiredCount", signalRepository.countByState(SignalState.EXPIRED));

        // Counts by outcome
        analytics.put("wins", signalRepository.countByOutcome(TradingSignal.Outcome.WIN));
        analytics.put("losses", signalRepository.countByOutcome(TradingSignal.Outcome.LOSS));
        analytics.put("breakeven", signalRepository.countByOutcome(TradingSignal.Outcome.BREAKEVEN));

        // Win rate
        long wins = (long) analytics.get("wins");
        long losses = (long) analytics.get("losses");
        double winRate = (wins + losses) > 0 ? (double) wins / (wins + losses) * 100 : 0;
        analytics.put("winRate", winRate);

        analytics.put("calculatedAt", Instant.now());

        return ResponseEntity.ok(analytics);
    }

    /**
     * Get analytics for a specific symbol.
     */
    @GetMapping("/analytics/{symbol}")
    public ResponseEntity<Map<String, Object>> getSymbolAnalytics(@PathVariable String symbol) {
        Map<String, Object> analytics = new HashMap<>();

        long wins = signalRepository.countWinsBySymbol(symbol);
        long losses = signalRepository.countLossesBySymbol(symbol);

        analytics.put("symbol", symbol);
        analytics.put("wins", wins);
        analytics.put("losses", losses);
        analytics.put("total", wins + losses);

        double winRate = (wins + losses) > 0 ? (double) wins / (wins + losses) * 100 : 0;
        analytics.put("winRate", winRate);

        // Check for active signal
        Optional<TradingSignal> active = signalEngine.getActiveSignal(symbol);
        analytics.put("hasActiveSignal", active.isPresent());
        active.ifPresent(signal -> {
            analytics.put("activeState", signal.getState());
            analytics.put("activeDirection", signal.getDirection());
        });

        analytics.put("calculatedAt", Instant.now());

        return ResponseEntity.ok(analytics);
    }

    /**
     * Get top performing signals.
     */
    @GetMapping("/analytics/top-performers")
    public ResponseEntity<List<TradingSignal>> getTopPerformers(
            @RequestParam(defaultValue = "2.0") double minRMultiple,
            @RequestParam(defaultValue = "20") int limit) {

        Pageable pageable = PageRequest.of(0, limit);
        List<TradingSignal> topPerformers = signalRepository.findTopPerformers(minRMultiple, pageable);
        return ResponseEntity.ok(topPerformers);
    }

    /**
     * Get high confidence signals.
     */
    @GetMapping("/analytics/high-confidence")
    public ResponseEntity<List<TradingSignal>> getHighConfidenceSignals(
            @RequestParam(defaultValue = "0.8") double minConfidence,
            @RequestParam(defaultValue = "20") int limit) {

        Pageable pageable = PageRequest.of(0, limit);
        List<TradingSignal> signals = signalRepository.findHighConfidenceSignals(minConfidence, pageable);
        return ResponseEntity.ok(signals);
    }

    // ==================== CLEANUP ====================

    /**
     * Get expired signals that need cleanup.
     */
    @GetMapping("/cleanup/expired")
    public ResponseEntity<Map<String, Object>> getExpiredSignals() {
        Instant now = Instant.now();

        List<TradingSignal> expiredWatch = signalRepository.findExpiredWatchSignals(now);
        List<TradingSignal> expiredActive = signalRepository.findExpiredActiveSignals(now);

        Map<String, Object> response = new HashMap<>();
        response.put("expiredWatchCount", expiredWatch.size());
        response.put("expiredActiveCount", expiredActive.size());
        response.put("expiredWatch", expiredWatch);
        response.put("expiredActive", expiredActive);
        response.put("checkedAt", now);

        return ResponseEntity.ok(response);
    }
}
