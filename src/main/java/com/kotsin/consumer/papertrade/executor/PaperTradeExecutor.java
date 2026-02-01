package com.kotsin.consumer.papertrade.executor;

import com.kotsin.consumer.papertrade.model.PaperTrade;
import com.kotsin.consumer.papertrade.model.PaperTrade.TradeDirection;
import com.kotsin.consumer.papertrade.model.PaperTrade.TradeStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * PaperTradeExecutor - Simulated trading execution engine.
 *
 * Features:
 * - Order management (market, limit, stop)
 * - Position tracking
 * - Risk management (position sizing, max positions)
 * - P&L tracking
 * - Trade history
 */
@Component
@Slf4j
public class PaperTradeExecutor {

    @Value("${papertrade.initial.capital:100000}")
    private double initialCapital;

    @Value("${papertrade.max.positions:5}")
    private int maxPositions;

    @Value("${papertrade.risk.per.trade:0.02}")
    private double riskPerTrade;  // 2% default

    @Value("${papertrade.commission.per.trade:20}")
    private double commissionPerTrade;

    // Active trades
    private final Map<String, PaperTrade> openTrades = new ConcurrentHashMap<>();

    // Closed trades history
    private final List<PaperTrade> tradeHistory = Collections.synchronizedList(new ArrayList<>());

    // Account state
    private double availableCapital;
    private double usedMargin;
    private double unrealizedPnL;
    private double realizedPnL;
    private double peakEquity;
    private double maxDrawdown;

    private boolean initialized = false;

    /**
     * Initialize the paper trading account.
     */
    public void initialize() {
        if (!initialized) {
            availableCapital = initialCapital;
            usedMargin = 0;
            unrealizedPnL = 0;
            realizedPnL = 0;
            peakEquity = initialCapital;
            maxDrawdown = 0;
            initialized = true;
            log.info("Paper trading initialized with capital: {}", initialCapital);
        }
    }

    /**
     * Execute a market order.
     */
    public PaperTrade executeMarketOrder(String symbol, TradeDirection direction,
                                          double currentPrice, double target, double stopLoss,
                                          String signalId, String signalType) {

        initialize();

        // Check if we can open new position
        if (!canOpenPosition(symbol)) {
            log.warn("Cannot open position for {}: max positions reached or existing position", symbol);
            return null;
        }

        // Calculate position size based on risk
        int quantity = calculatePositionSize(currentPrice, stopLoss, direction);
        if (quantity <= 0) {
            log.warn("Position size too small for {} at risk level", symbol);
            return null;
        }

        double positionValue = currentPrice * quantity;
        if (positionValue > availableCapital) {
            log.warn("Insufficient capital for {}: need {} have {}", symbol, positionValue, availableCapital);
            return null;
        }

        // Create trade
        PaperTrade trade = PaperTrade.builder()
            .tradeId(PaperTrade.generateTradeId(symbol, Instant.now()))
            .symbol(symbol)
            .signalId(signalId)
            .signalType(signalType)
            .direction(direction)
            .quantity(quantity)
            .positionValue(positionValue)
            .entryPrice(currentPrice)
            .currentPrice(currentPrice)
            .targetPrice(target)
            .stopLoss(stopLoss)
            .createdAt(Instant.now())
            .entryTime(Instant.now())
            .lastUpdated(Instant.now())
            .status(TradeStatus.OPEN)
            .commission(commissionPerTrade)
            .riskAmount(Math.abs(currentPrice - stopLoss) * quantity)
            .riskPercent(riskPerTrade * 100)
            .riskRewardRatio(calculateRR(currentPrice, target, stopLoss, direction))
            .isTrailingActive(false)
            .build();

        // Update account
        availableCapital -= positionValue;
        usedMargin += positionValue;

        // Store trade
        openTrades.put(trade.getTradeId(), trade);

        log.info("Opened {} {} position: {} @ {} (target: {}, stop: {}, qty: {})",
            direction, symbol, trade.getTradeId(), currentPrice, target, stopLoss, quantity);

        return trade;
    }

    /**
     * Close an open position.
     */
    public PaperTrade closePosition(String tradeId, double exitPrice, String reason) {
        PaperTrade trade = openTrades.remove(tradeId);
        if (trade == null) {
            log.warn("No open trade found: {}", tradeId);
            return null;
        }

        trade.close(exitPrice, reason);

        // Update account
        usedMargin -= trade.getPositionValue();
        availableCapital += trade.getPositionValue() + trade.getRealizedPnL();
        realizedPnL += trade.getRealizedPnL();

        // Update peak and drawdown
        double currentEquity = getEquity();
        if (currentEquity > peakEquity) {
            peakEquity = currentEquity;
        }
        double drawdown = (peakEquity - currentEquity) / peakEquity * 100;
        if (drawdown > maxDrawdown) {
            maxDrawdown = drawdown;
        }

        // Store in history
        tradeHistory.add(trade);

        log.info("Closed {} position: {} @ {} P&L: {} ({})",
            trade.getSymbol(), tradeId, exitPrice, trade.getRealizedPnL(), reason);

        return trade;
    }

    /**
     * Update all open positions with new prices.
     */
    public void updatePositions(Map<String, Double> priceMap) {
        unrealizedPnL = 0;

        for (PaperTrade trade : openTrades.values()) {
            Double price = priceMap.get(trade.getSymbol());
            if (price != null) {
                trade.updatePrice(price);
                unrealizedPnL += trade.getUnrealizedPnL();
            }
        }
    }

    /**
     * Update a single position and check for exit conditions.
     */
    public PaperTrade updateAndCheckExit(String symbol, double high, double low, double close) {
        PaperTrade trade = openTrades.values().stream()
            .filter(t -> t.getSymbol().equals(symbol))
            .findFirst()
            .orElse(null);

        if (trade == null) return null;

        trade.updatePrice(close);

        // Check stop loss
        if (trade.shouldStopOut(high, low)) {
            double exitPrice = trade.getEffectiveStop();
            return closePosition(trade.getTradeId(), exitPrice, "STOP_HIT");
        }

        // Check target
        if (trade.shouldTargetHit(high, low)) {
            return closePosition(trade.getTradeId(), trade.getTargetPrice(), "TARGET_HIT");
        }

        return null;
    }

    /**
     * Enable trailing stop for a position.
     */
    public void enableTrailingStop(String tradeId, double trailingPercent) {
        PaperTrade trade = openTrades.get(tradeId);
        if (trade != null) {
            trade.setTrailingActive(true);
            trade.setTrailingPercent(trailingPercent);
            trade.setTrailingStop(trade.isLong() ?
                trade.getCurrentPrice() * (1 - trailingPercent / 100) :
                trade.getCurrentPrice() * (1 + trailingPercent / 100));
            log.info("Trailing stop enabled for {}: {}%", tradeId, trailingPercent);
        }
    }

    /**
     * Modify stop loss.
     */
    public void modifyStopLoss(String tradeId, double newStop) {
        PaperTrade trade = openTrades.get(tradeId);
        if (trade != null) {
            trade.setStopLoss(newStop);
            log.info("Stop loss modified for {}: {}", tradeId, newStop);
        }
    }

    /**
     * Modify target price.
     */
    public void modifyTarget(String tradeId, double newTarget) {
        PaperTrade trade = openTrades.get(tradeId);
        if (trade != null) {
            trade.setTargetPrice(newTarget);
            log.info("Target modified for {}: {}", tradeId, newTarget);
        }
    }

    /**
     * Close all positions.
     */
    public List<PaperTrade> closeAllPositions(Map<String, Double> priceMap, String reason) {
        List<PaperTrade> closed = new ArrayList<>();

        for (String tradeId : new ArrayList<>(openTrades.keySet())) {
            PaperTrade trade = openTrades.get(tradeId);
            Double price = priceMap.get(trade.getSymbol());
            if (price != null) {
                PaperTrade closedTrade = closePosition(tradeId, price, reason);
                if (closedTrade != null) {
                    closed.add(closedTrade);
                }
            }
        }

        return closed;
    }

    // ==================== POSITION SIZING ====================

    private int calculatePositionSize(double entryPrice, double stopLoss, TradeDirection direction) {
        double riskAmount = availableCapital * riskPerTrade;
        double riskPerShare = Math.abs(entryPrice - stopLoss);

        if (riskPerShare <= 0) {
            return 0;
        }

        int size = (int) (riskAmount / riskPerShare);
        return Math.max(1, size);
    }

    private boolean canOpenPosition(String symbol) {
        // Check max positions
        if (openTrades.size() >= maxPositions) {
            return false;
        }

        // Check if already have position in symbol
        return openTrades.values().stream()
            .noneMatch(t -> t.getSymbol().equals(symbol));
    }

    private double calculateRR(double entry, double target, double stop, TradeDirection dir) {
        double risk = Math.abs(entry - stop);
        double reward = Math.abs(target - entry);
        return risk > 0 ? reward / risk : 0;
    }

    // ==================== ACCOUNT METHODS ====================

    public double getEquity() {
        return availableCapital + usedMargin + unrealizedPnL;
    }

    public double getAvailableCapital() {
        return availableCapital;
    }

    public double getUsedMargin() {
        return usedMargin;
    }

    public double getUnrealizedPnL() {
        return unrealizedPnL;
    }

    public double getRealizedPnL() {
        return realizedPnL;
    }

    public double getMaxDrawdown() {
        return maxDrawdown;
    }

    public int getOpenPositionCount() {
        return openTrades.size();
    }

    public List<PaperTrade> getOpenPositions() {
        return new ArrayList<>(openTrades.values());
    }

    public PaperTrade getOpenPosition(String symbol) {
        return openTrades.values().stream()
            .filter(t -> t.getSymbol().equals(symbol))
            .findFirst()
            .orElse(null);
    }

    public List<PaperTrade> getTradeHistory() {
        return new ArrayList<>(tradeHistory);
    }

    public List<PaperTrade> getRecentTrades(int limit) {
        int start = Math.max(0, tradeHistory.size() - limit);
        return new ArrayList<>(tradeHistory.subList(start, tradeHistory.size()));
    }

    // ==================== STATISTICS ====================

    public Map<String, Object> getAccountSummary() {
        Map<String, Object> summary = new HashMap<>();

        summary.put("initialCapital", initialCapital);
        summary.put("currentEquity", getEquity());
        summary.put("availableCapital", availableCapital);
        summary.put("usedMargin", usedMargin);
        summary.put("unrealizedPnL", unrealizedPnL);
        summary.put("realizedPnL", realizedPnL);
        summary.put("maxDrawdown", String.format("%.2f%%", maxDrawdown));
        summary.put("openPositions", openTrades.size());
        summary.put("totalTrades", tradeHistory.size());

        // Win rate
        long wins = tradeHistory.stream().filter(t -> t.getRealizedPnL() > 0).count();
        double winRate = tradeHistory.size() > 0 ? (double) wins / tradeHistory.size() * 100 : 0;
        summary.put("winRate", String.format("%.2f%%", winRate));

        // Profit factor
        double totalWins = tradeHistory.stream()
            .filter(t -> t.getRealizedPnL() > 0)
            .mapToDouble(PaperTrade::getRealizedPnL)
            .sum();
        double totalLosses = Math.abs(tradeHistory.stream()
            .filter(t -> t.getRealizedPnL() < 0)
            .mapToDouble(PaperTrade::getRealizedPnL)
            .sum());
        double profitFactor = totalLosses > 0 ? totalWins / totalLosses : totalWins > 0 ? Double.MAX_VALUE : 0;
        summary.put("profitFactor", String.format("%.2f", profitFactor));

        return summary;
    }

    public Map<String, Double> getPnLBySymbol() {
        return tradeHistory.stream()
            .collect(Collectors.groupingBy(
                PaperTrade::getSymbol,
                Collectors.summingDouble(PaperTrade::getRealizedPnL)
            ));
    }

    public Map<String, Double> getPnLBySignalType() {
        return tradeHistory.stream()
            .filter(t -> t.getSignalType() != null)
            .collect(Collectors.groupingBy(
                PaperTrade::getSignalType,
                Collectors.summingDouble(PaperTrade::getRealizedPnL)
            ));
    }
}
