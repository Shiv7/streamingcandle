package com.kotsin.consumer.controller;

import com.kotsin.consumer.papertrade.executor.PaperTradeExecutor;
import com.kotsin.consumer.papertrade.model.PaperTrade;
import com.kotsin.consumer.papertrade.model.PaperTrade.TradeDirection;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * PaperTradeController - REST API for paper trading operations.
 */
@RestController
@RequestMapping("/api/v1/papertrade")
@RequiredArgsConstructor
public class PaperTradeController {

    private final PaperTradeExecutor executor;

    // ==================== ACCOUNT ====================

    @GetMapping("/account")
    public ResponseEntity<Map<String, Object>> getAccountSummary() {
        return ResponseEntity.ok(executor.getAccountSummary());
    }

    @GetMapping("/account/equity")
    public ResponseEntity<Map<String, Double>> getEquity() {
        return ResponseEntity.ok(Map.of(
            "equity", executor.getEquity(),
            "availableCapital", executor.getAvailableCapital(),
            "usedMargin", executor.getUsedMargin(),
            "unrealizedPnL", executor.getUnrealizedPnL(),
            "realizedPnL", executor.getRealizedPnL(),
            "maxDrawdown", executor.getMaxDrawdown()
        ));
    }

    // ==================== POSITIONS ====================

    @GetMapping("/positions")
    public ResponseEntity<List<PaperTrade>> getOpenPositions() {
        return ResponseEntity.ok(executor.getOpenPositions());
    }

    @GetMapping("/positions/{symbol}")
    public ResponseEntity<PaperTrade> getPosition(@PathVariable String symbol) {
        PaperTrade position = executor.getOpenPosition(symbol);
        if (position == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(position);
    }

    @GetMapping("/positions/count")
    public ResponseEntity<Map<String, Integer>> getPositionCount() {
        return ResponseEntity.ok(Map.of("openPositions", executor.getOpenPositionCount()));
    }

    // ==================== ORDERS ====================

    @PostMapping("/order")
    public ResponseEntity<PaperTrade> placeOrder(@RequestBody OrderRequest request) {
        PaperTrade trade = executor.executeMarketOrder(
            request.getSymbol(),
            request.getDirection(),
            request.getCurrentPrice(),
            request.getTarget(),
            request.getStopLoss(),
            request.getSignalId(),
            request.getSignalType()
        );

        if (trade == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(trade);
    }

    @PostMapping("/close/{tradeId}")
    public ResponseEntity<PaperTrade> closePosition(
            @PathVariable String tradeId,
            @RequestParam double exitPrice,
            @RequestParam(defaultValue = "MANUAL") String reason) {

        PaperTrade trade = executor.closePosition(tradeId, exitPrice, reason);
        if (trade == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(trade);
    }

    @PostMapping("/close-all")
    public ResponseEntity<List<PaperTrade>> closeAllPositions(
            @RequestBody Map<String, Double> priceMap,
            @RequestParam(defaultValue = "CLOSE_ALL") String reason) {

        List<PaperTrade> closed = executor.closeAllPositions(priceMap, reason);
        return ResponseEntity.ok(closed);
    }

    // ==================== MODIFICATIONS ====================

    @PutMapping("/{tradeId}/stop-loss")
    public ResponseEntity<Void> modifyStopLoss(
            @PathVariable String tradeId,
            @RequestParam double newStop) {

        executor.modifyStopLoss(tradeId, newStop);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{tradeId}/target")
    public ResponseEntity<Void> modifyTarget(
            @PathVariable String tradeId,
            @RequestParam double newTarget) {

        executor.modifyTarget(tradeId, newTarget);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{tradeId}/trailing-stop")
    public ResponseEntity<Void> enableTrailingStop(
            @PathVariable String tradeId,
            @RequestParam double trailingPercent) {

        executor.enableTrailingStop(tradeId, trailingPercent);
        return ResponseEntity.ok().build();
    }

    // ==================== HISTORY ====================

    @GetMapping("/history")
    public ResponseEntity<List<PaperTrade>> getTradeHistory() {
        return ResponseEntity.ok(executor.getTradeHistory());
    }

    @GetMapping("/history/recent")
    public ResponseEntity<List<PaperTrade>> getRecentTrades(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(executor.getRecentTrades(limit));
    }

    // ==================== ANALYTICS ====================

    @GetMapping("/analytics/by-symbol")
    public ResponseEntity<Map<String, Double>> getPnLBySymbol() {
        return ResponseEntity.ok(executor.getPnLBySymbol());
    }

    @GetMapping("/analytics/by-type")
    public ResponseEntity<Map<String, Double>> getPnLBySignalType() {
        return ResponseEntity.ok(executor.getPnLBySignalType());
    }

    // ==================== REQUEST CLASSES ====================

    @Data
    public static class OrderRequest {
        private String symbol;
        private TradeDirection direction;
        private double currentPrice;
        private double target;
        private double stopLoss;
        private String signalId;
        private String signalType;
    }
}
