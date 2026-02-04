package com.kotsin.consumer.controller;

import com.kotsin.consumer.smc.analyzer.SMCAnalyzer;
import com.kotsin.consumer.smc.analyzer.SMCAnalyzer.MarketStructure;
import com.kotsin.consumer.smc.analyzer.SMCAnalyzer.SMCResult;
import com.kotsin.consumer.smc.model.FairValueGap;
import com.kotsin.consumer.smc.model.LiquidityZone;
import com.kotsin.consumer.smc.model.OrderBlock;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SMCController - REST API for Smart Money Concepts analysis.
 */
@RestController
@RequestMapping("/api/v1/smc")
@RequiredArgsConstructor
public class SMCController {

    private final SMCAnalyzer smcAnalyzer;

    // ==================== COMBINED ====================

    @GetMapping("/{symbol}/all")
    public ResponseEntity<Map<String, Object>> getAllStructures(@PathVariable String symbol) {
        Map<String, Object> response = new HashMap<>();
        response.put("orderBlocks", smcAnalyzer.getValidOrderBlocks(symbol));
        response.put("fairValueGaps", smcAnalyzer.getValidFairValueGaps(symbol));
        response.put("liquidityZones", smcAnalyzer.getUnsweptLiquidityZones(symbol));
        response.put("marketStructure", smcAnalyzer.getMarketStructure(symbol));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{symbol}/summary")
    public ResponseEntity<Map<String, Object>> getSummary(@PathVariable String symbol) {
        Map<String, Object> summary = new HashMap<>();

        List<OrderBlock> obs = smcAnalyzer.getValidOrderBlocks(symbol);
        List<FairValueGap> fvgs = smcAnalyzer.getValidFairValueGaps(symbol);
        List<LiquidityZone> lzs = smcAnalyzer.getUnsweptLiquidityZones(symbol);
        MarketStructure ms = smcAnalyzer.getMarketStructure(symbol);

        summary.put("validOrderBlocks", obs.size());
        summary.put("bullishOBs", obs.stream().filter(OrderBlock::isBullish).count());
        summary.put("bearishOBs", obs.stream().filter(OrderBlock::isBearish).count());
        summary.put("validFVGs", fvgs.size());
        summary.put("bullishFVGs", fvgs.stream().filter(FairValueGap::isBullish).count());
        summary.put("bearishFVGs", fvgs.stream().filter(FairValueGap::isBearish).count());
        summary.put("unsweptLiquidity", lzs.size());
        summary.put("buySideLiquidity", lzs.stream().filter(LiquidityZone::isBuySide).count());
        summary.put("sellSideLiquidity", lzs.stream().filter(LiquidityZone::isSellSide).count());

        if (ms != null) {
            summary.put("trend", ms.getTrend());
            summary.put("lastSwingHigh", ms.getLastSwingHigh());
            summary.put("lastSwingLow", ms.getLastSwingLow());
        }

        return ResponseEntity.ok(summary);
    }

    // ==================== ORDER BLOCKS ====================

    @GetMapping("/{symbol}/orderblocks")
    public ResponseEntity<List<OrderBlock>> getOrderBlocks(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "true") boolean validOnly) {

        List<OrderBlock> obs = validOnly ?
            smcAnalyzer.getValidOrderBlocks(symbol) :
            smcAnalyzer.getOrderBlocks(symbol);

        return ResponseEntity.ok(obs);
    }

    @GetMapping("/{symbol}/orderblocks/bullish")
    public ResponseEntity<List<OrderBlock>> getBullishOrderBlocks(@PathVariable String symbol) {
        List<OrderBlock> obs = smcAnalyzer.getValidOrderBlocks(symbol).stream()
            .filter(OrderBlock::isBullish)
            .toList();
        return ResponseEntity.ok(obs);
    }

    @GetMapping("/{symbol}/orderblocks/bearish")
    public ResponseEntity<List<OrderBlock>> getBearishOrderBlocks(@PathVariable String symbol) {
        List<OrderBlock> obs = smcAnalyzer.getValidOrderBlocks(symbol).stream()
            .filter(OrderBlock::isBearish)
            .toList();
        return ResponseEntity.ok(obs);
    }

    // ==================== FAIR VALUE GAPS ====================

    @GetMapping("/{symbol}/fvg")
    public ResponseEntity<List<FairValueGap>> getFairValueGaps(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "true") boolean validOnly) {

        List<FairValueGap> fvgs = validOnly ?
            smcAnalyzer.getValidFairValueGaps(symbol) :
            smcAnalyzer.getFairValueGaps(symbol);

        return ResponseEntity.ok(fvgs);
    }

    @GetMapping("/{symbol}/fvg/bullish")
    public ResponseEntity<List<FairValueGap>> getBullishFVGs(@PathVariable String symbol) {
        List<FairValueGap> fvgs = smcAnalyzer.getValidFairValueGaps(symbol).stream()
            .filter(FairValueGap::isBullish)
            .toList();
        return ResponseEntity.ok(fvgs);
    }

    @GetMapping("/{symbol}/fvg/bearish")
    public ResponseEntity<List<FairValueGap>> getBearishFVGs(@PathVariable String symbol) {
        List<FairValueGap> fvgs = smcAnalyzer.getValidFairValueGaps(symbol).stream()
            .filter(FairValueGap::isBearish)
            .toList();
        return ResponseEntity.ok(fvgs);
    }

    // ==================== LIQUIDITY ZONES ====================

    @GetMapping("/{symbol}/liquidity")
    public ResponseEntity<List<LiquidityZone>> getLiquidityZones(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "true") boolean unsweptOnly) {

        List<LiquidityZone> lzs = unsweptOnly ?
            smcAnalyzer.getUnsweptLiquidityZones(symbol) :
            smcAnalyzer.getLiquidityZones(symbol);

        return ResponseEntity.ok(lzs);
    }

    @GetMapping("/{symbol}/liquidity/buy-side")
    public ResponseEntity<List<LiquidityZone>> getBuySideLiquidity(@PathVariable String symbol) {
        List<LiquidityZone> lzs = smcAnalyzer.getUnsweptLiquidityZones(symbol).stream()
            .filter(LiquidityZone::isBuySide)
            .toList();
        return ResponseEntity.ok(lzs);
    }

    @GetMapping("/{symbol}/liquidity/sell-side")
    public ResponseEntity<List<LiquidityZone>> getSellSideLiquidity(@PathVariable String symbol) {
        List<LiquidityZone> lzs = smcAnalyzer.getUnsweptLiquidityZones(symbol).stream()
            .filter(LiquidityZone::isSellSide)
            .toList();
        return ResponseEntity.ok(lzs);
    }

    // ==================== MARKET STRUCTURE ====================

    @GetMapping("/{symbol}/structure")
    public ResponseEntity<MarketStructure> getMarketStructure(@PathVariable String symbol) {
        MarketStructure ms = smcAnalyzer.getMarketStructure(symbol);
        if (ms == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ms);
    }

    // ==================== PROXIMITY CHECKS ====================

    @GetMapping("/{symbol}/near-ob")
    public ResponseEntity<Map<String, Object>> getNearbyOrderBlocks(
            @PathVariable String symbol,
            @RequestParam double price,
            @RequestParam(defaultValue = "1.0") double tolerancePercent) {

        List<OrderBlock> nearbyOBs = smcAnalyzer.getValidOrderBlocks(symbol).stream()
            .filter(ob -> ob.getDistancePercent(price) <= tolerancePercent)
            .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("price", price);
        response.put("nearbyOrderBlocks", nearbyOBs);
        response.put("inOrderBlock", nearbyOBs.stream().anyMatch(ob -> ob.isPriceInZone(price)));

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{symbol}/near-fvg")
    public ResponseEntity<Map<String, Object>> getNearbyFVGs(
            @PathVariable String symbol,
            @RequestParam double price,
            @RequestParam(defaultValue = "1.0") double tolerancePercent) {

        List<FairValueGap> nearbyFVGs = smcAnalyzer.getValidFairValueGaps(symbol).stream()
            .filter(fvg -> fvg.getDistancePercent(price) <= tolerancePercent)
            .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("price", price);
        response.put("nearbyFVGs", nearbyFVGs);
        response.put("inFVG", nearbyFVGs.stream().anyMatch(fvg -> fvg.isPriceInGap(price)));

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{symbol}/near-liquidity")
    public ResponseEntity<Map<String, Object>> getNearbyLiquidity(
            @PathVariable String symbol,
            @RequestParam double price,
            @RequestParam(defaultValue = "0.5") double tolerancePercent) {

        List<LiquidityZone> nearbyLZs = smcAnalyzer.getUnsweptLiquidityZones(symbol).stream()
            .filter(lz -> lz.isPriceNearLevel(price, tolerancePercent))
            .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("price", price);
        response.put("nearbyLiquidity", nearbyLZs);

        return ResponseEntity.ok(response);
    }
}
