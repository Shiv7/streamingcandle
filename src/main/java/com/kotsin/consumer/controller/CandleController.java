package com.kotsin.consumer.controller;

import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.service.CandleService;
import com.kotsin.consumer.service.TimeframeAggregationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CandleController - REST API for candle data (v2 architecture).
 *
 * Endpoints:
 * - GET /api/v2/candles/{symbol}/latest?timeframe=1m
 * - GET /api/v2/candles/{symbol}/history?timeframe=1m&count=100
 * - GET /api/v2/candles/{symbol}/at?timestamp=xxx&timeframe=1m
 * - GET /api/v2/candles/symbols
 * - POST /api/v2/candles/warmup
 */
@RestController
@RequestMapping("/api/v2/candles")
@Slf4j
public class CandleController {

    @Autowired
    private CandleService candleService;

    @Autowired
    private TimeframeAggregationService timeframeAggregationService;

    /**
     * Get latest candle for a symbol.
     *
     * @param symbol    Symbol (e.g., "NIFTY", "RELIANCE")
     * @param timeframe Timeframe (default: "1m")
     * @return Latest UnifiedCandle
     */
    @GetMapping("/{symbol}/latest")
    public ResponseEntity<UnifiedCandle> getLatestCandle(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1m") String timeframe) {

        log.debug("[CANDLE-API] Getting latest candle for {} at {}", symbol, timeframe);

        try {
            Timeframe tf = Timeframe.fromLabel(timeframe);
            UnifiedCandle candle = candleService.getLatestCandle(symbol, tf);

            if (candle == null) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(candle);
        } catch (IllegalArgumentException e) {
            log.warn("[CANDLE-API] Invalid timeframe: {}", timeframe);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get candle history for a symbol.
     *
     * @param symbol    Symbol
     * @param timeframe Timeframe (default: "1m")
     * @param count     Number of candles (default: 100, max: 500)
     * @return List of UnifiedCandles (most recent first)
     */
    @GetMapping("/{symbol}/history")
    public ResponseEntity<List<UnifiedCandle>> getCandleHistory(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1m") String timeframe,
            @RequestParam(defaultValue = "100") int count) {

        log.debug("[CANDLE-API] Getting {} candles for {} at {}", count, symbol, timeframe);

        try {
            Timeframe tf = Timeframe.fromLabel(timeframe);
            int safeCount = Math.min(count, 500);  // Cap at 500

            List<UnifiedCandle> candles = candleService.getCandleHistory(symbol, tf, safeCount);

            if (candles.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(candles);
        } catch (IllegalArgumentException e) {
            log.warn("[CANDLE-API] Invalid timeframe: {}", timeframe);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get candle at specific timestamp.
     *
     * @param symbol    Symbol
     * @param timestamp Epoch milliseconds
     * @param timeframe Timeframe (default: "1m")
     * @return UnifiedCandle at timestamp
     */
    @GetMapping("/{symbol}/at")
    public ResponseEntity<UnifiedCandle> getCandleAt(
            @PathVariable String symbol,
            @RequestParam long timestamp,
            @RequestParam(defaultValue = "1m") String timeframe) {

        log.debug("[CANDLE-API] Getting candle for {} at {} (tf={})", symbol, timestamp, timeframe);

        try {
            Timeframe tf = Timeframe.fromLabel(timeframe);
            Instant ts = Instant.ofEpochMilli(timestamp);

            UnifiedCandle candle = candleService.getCandle(symbol, ts, tf);

            if (candle == null) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(candle);
        } catch (IllegalArgumentException e) {
            log.warn("[CANDLE-API] Invalid timeframe: {}", timeframe);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get batch of latest candles for multiple symbols.
     *
     * @param symbols   Comma-separated list of symbols
     * @param timeframe Timeframe (default: "1m")
     * @return Map of symbol to UnifiedCandle
     */
    @GetMapping("/batch/latest")
    public ResponseEntity<Map<String, UnifiedCandle>> getBatchLatest(
            @RequestParam String symbols,
            @RequestParam(defaultValue = "1m") String timeframe) {

        try {
            Timeframe tf = Timeframe.fromLabel(timeframe);
            List<String> symbolList = List.of(symbols.split(","));

            Map<String, UnifiedCandle> candles = candleService.getLatestCandles(symbolList, tf);

            return ResponseEntity.ok(candles);
        } catch (IllegalArgumentException e) {
            log.warn("[CANDLE-API] Invalid timeframe: {}", timeframe);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get available symbols with candle data.
     */
    @GetMapping("/symbols")
    public ResponseEntity<Set<String>> getAvailableSymbols() {
        Set<String> symbols = candleService.getAvailableSymbols();
        return ResponseEntity.ok(symbols);
    }

    /**
     * Warm up cache for specific symbols.
     *
     * @param symbols List of symbols to warm up
     * @return Status message
     */
    @PostMapping("/warmup")
    public ResponseEntity<Map<String, Object>> warmupCache(@RequestBody List<String> symbols) {
        log.info("[CANDLE-API] Warming up cache for {} symbols", symbols.size());

        timeframeAggregationService.warmupCache(symbols);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "warming");
        response.put("symbolCount", symbols.size());
        response.put("message", "Cache warmup started in background");

        return ResponseEntity.ok(response);
    }

    /**
     * Get timeframe aggregation status.
     */
    @GetMapping("/aggregation/status")
    public ResponseEntity<Map<String, Object>> getAggregationStatus() {
        return ResponseEntity.ok(timeframeAggregationService.getStatus());
    }

    /**
     * Force compute aggregated candle for symbol at timeframe.
     *
     * @param symbol    Symbol
     * @param timeframe Timeframe
     */
    @PostMapping("/{symbol}/compute")
    public ResponseEntity<Map<String, Object>> forceCompute(
            @PathVariable String symbol,
            @RequestParam String timeframe) {

        try {
            Timeframe tf = Timeframe.fromLabel(timeframe);
            timeframeAggregationService.forceCompute(symbol, tf);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "computed");
            response.put("symbol", symbol);
            response.put("timeframe", timeframe);

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("[CANDLE-API] Invalid timeframe: {}", timeframe);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get supported timeframes.
     */
    @GetMapping("/timeframes")
    public ResponseEntity<List<Map<String, Object>>> getSupportedTimeframes() {
        List<Map<String, Object>> timeframes = new java.util.ArrayList<>();

        for (Timeframe tf : Timeframe.values()) {
            Map<String, Object> tfInfo = new HashMap<>();
            tfInfo.put("label", tf.getLabel());
            tfInfo.put("minutes", tf.getMinutes());
            tfInfo.put("popular", tf.isPopular());
            timeframes.add(tfInfo);
        }

        return ResponseEntity.ok(timeframes);
    }
}
