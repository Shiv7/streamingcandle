package com.kotsin.consumer.controller;

import com.kotsin.consumer.regime.detector.RegimeDetector;
import com.kotsin.consumer.regime.model.MarketRegime;
import com.kotsin.consumer.regime.model.MarketRegime.TradingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * RegimeController - REST API for market regime detection.
 */
@RestController
@RequestMapping("/api/v1/regime")
@RequiredArgsConstructor
public class RegimeController {

    private final RegimeDetector regimeDetector;

    @GetMapping("/{symbol}")
    public ResponseEntity<MarketRegime> getRegime(@PathVariable String symbol) {
        MarketRegime regime = regimeDetector.getCurrentRegime(symbol);
        if (regime == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(regime);
    }

    @GetMapping("/{symbol}/summary")
    public ResponseEntity<Map<String, Object>> getRegimeSummary(@PathVariable String symbol) {
        MarketRegime regime = regimeDetector.getCurrentRegime(symbol);
        if (regime == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("symbol", symbol);
        summary.put("regimeType", regime.getRegimeType());
        summary.put("trendStrength", regime.getTrendStrength());
        summary.put("volatilityState", regime.getVolatilityState());
        summary.put("momentumState", regime.getMomentumState());
        summary.put("recommendedMode", regime.getRecommendedMode());
        summary.put("confidence", regime.getRegimeConfidence());
        summary.put("isTradeable", regime.isTradeable());
        summary.put("summary", regime.getRegimeSummary());

        return ResponseEntity.ok(summary);
    }

    @GetMapping("/{symbol}/is-trending")
    public ResponseEntity<Map<String, Boolean>> isTrending(@PathVariable String symbol) {
        return ResponseEntity.ok(Map.of("isTrending", regimeDetector.isTrending(symbol)));
    }

    @GetMapping("/{symbol}/is-ranging")
    public ResponseEntity<Map<String, Boolean>> isRanging(@PathVariable String symbol) {
        return ResponseEntity.ok(Map.of("isRanging", regimeDetector.isRanging(symbol)));
    }

    @GetMapping("/{symbol}/is-tradeable")
    public ResponseEntity<Map<String, Boolean>> isTradeable(@PathVariable String symbol) {
        return ResponseEntity.ok(Map.of("isTradeable", regimeDetector.isTradeable(symbol)));
    }

    @GetMapping("/{symbol}/recommended-mode")
    public ResponseEntity<Map<String, TradingMode>> getRecommendedMode(@PathVariable String symbol) {
        return ResponseEntity.ok(Map.of("recommendedMode", regimeDetector.getRecommendedMode(symbol)));
    }

    @GetMapping("/{symbol}/strategies")
    public ResponseEntity<Map<String, Object>> getStrategies(@PathVariable String symbol) {
        MarketRegime regime = regimeDetector.getCurrentRegime(symbol);
        if (regime == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> strategies = new HashMap<>();
        strategies.put("suitable", regime.getSuitableStrategies());
        strategies.put("avoid", regime.getAvoidStrategies());

        return ResponseEntity.ok(strategies);
    }

    @PostMapping("/{symbol}/detect")
    public ResponseEntity<MarketRegime> detectRegime(
            @PathVariable String symbol,
            @RequestParam String timeframe,
            @RequestParam double adx,
            @RequestParam double plusDI,
            @RequestParam double minusDI,
            @RequestParam double rsi,
            @RequestParam double macdHist,
            @RequestParam double atr,
            @RequestParam double atrPct,
            @RequestParam double bbWidth) {

        MarketRegime regime = regimeDetector.detect(
            symbol, timeframe,
            adx, plusDI, minusDI,
            rsi, macdHist,
            atr, atrPct, bbWidth,
            new double[0]
        );

        return ResponseEntity.ok(regime);
    }

    @PostMapping("/{symbol}/detect-simple")
    public ResponseEntity<MarketRegime> detectRegimeSimple(
            @PathVariable String symbol,
            @RequestParam String timeframe,
            @RequestParam double adx,
            @RequestParam boolean trendUp,
            @RequestParam double rsi,
            @RequestParam double atrPct) {

        MarketRegime regime = regimeDetector.detectSimple(
            symbol, timeframe, adx, trendUp, rsi, atrPct
        );

        return ResponseEntity.ok(regime);
    }
}
