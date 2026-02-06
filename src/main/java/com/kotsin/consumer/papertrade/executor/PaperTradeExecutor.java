package com.kotsin.consumer.papertrade.executor;

import com.kotsin.consumer.papertrade.model.PaperTrade;
import com.kotsin.consumer.papertrade.model.PaperTrade.TradeDirection;
import com.kotsin.consumer.papertrade.model.PaperTrade.TradeStatus;
import com.kotsin.consumer.papertrade.repository.PaperTradeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * PaperTradeExecutor - Simulated trading execution engine with MongoDB persistence.
 *
 * Features:
 * - Order management (market, limit, stop)
 * - Position tracking with DB persistence
 * - Risk management (position sizing, max positions)
 * - P&L tracking
 * - Trade history persisted to MongoDB
 * - Auto-recovery on restart from DB state
 */
@Component
@Slf4j
public class PaperTradeExecutor {

    private static final String LOG_PREFIX = "[PAPER-TRADE]";

    @Autowired
    private PaperTradeRepository paperTradeRepository;

    @Value("${papertrade.initial.capital:100000}")
    private double initialCapital;

    @Value("${papertrade.max.positions:5}")
    private int maxPositions;

    @Value("${papertrade.risk.per.trade:0.02}")
    private double riskPerTrade;  // 2% default

    @Value("${papertrade.commission.per.trade:20}")
    private double commissionPerTrade;

    @Value("${papertrade.ml.mode:false}")
    private boolean mlMode;

    // In-memory cache for fast access (backed by MongoDB)
    private final Map<String, PaperTrade> openTrades = new ConcurrentHashMap<>();

    // Account state
    private double availableCapital;
    private double usedMargin;
    private double unrealizedPnL;
    private double realizedPnL;
    private double peakEquity;
    private double maxDrawdown;

    private boolean initialized = false;

    /**
     * Initialize the paper trading account and load state from DB.
     */
    @PostConstruct
    public void initialize() {
        if (!initialized) {
            log.info("{} Initializing paper trading executor...", LOG_PREFIX);

            // Load open positions from database
            List<PaperTrade> dbOpenTrades = paperTradeRepository.findAllOpenPositions();

            if (!dbOpenTrades.isEmpty()) {
                log.info("{} Recovered {} open positions from database", LOG_PREFIX, dbOpenTrades.size());
                for (PaperTrade trade : dbOpenTrades) {
                    openTrades.put(trade.getTradeId(), trade);
                }

                // Calculate used margin from open positions
                usedMargin = dbOpenTrades.stream()
                    .mapToDouble(PaperTrade::getPositionValue)
                    .sum();

                // Calculate unrealized P&L
                unrealizedPnL = dbOpenTrades.stream()
                    .mapToDouble(PaperTrade::getUnrealizedPnL)
                    .sum();
            }

            // Calculate realized P&L from closed trades
            long winningTrades = paperTradeRepository.countWinningTrades();
            long losingTrades = paperTradeRepository.countLosingTrades();

            // Get all closed trades for P&L calculation
            List<PaperTrade> closedTrades = paperTradeRepository.findByStatus(TradeStatus.CLOSED);
            realizedPnL = closedTrades.stream()
                .mapToDouble(PaperTrade::getRealizedPnL)
                .sum();

            // Initialize capital (initial - used + realized)
            availableCapital = initialCapital - usedMargin + realizedPnL;
            peakEquity = Math.max(initialCapital, getEquity());
            maxDrawdown = 0;

            initialized = true;

            log.info("{} Paper trading initialized - Capital: {}, Open Positions: {}, Realized P&L: {}, Total Trades: {}",
                LOG_PREFIX, availableCapital, openTrades.size(), realizedPnL, closedTrades.size());
            log.info("{} Historical Stats - Wins: {}, Losses: {}", LOG_PREFIX, winningTrades, losingTrades);
        }
    }

    /**
     * Execute a market order and persist to database.
     */
    public PaperTrade executeMarketOrder(String symbol, TradeDirection direction,
                                          double currentPrice, double target, double stopLoss,
                                          String signalId, String signalType) {

        initialize();

        // Check if we can open new position
        if (!canOpenPosition(symbol)) {
            log.warn("{} Cannot open position for {}: max positions reached or existing position", LOG_PREFIX, symbol);
            return null;
        }

        // Calculate position size based on risk
        int quantity = calculatePositionSize(currentPrice, stopLoss, direction);
        if (quantity <= 0) {
            log.warn("{} Position size too small for {} at risk level", LOG_PREFIX, symbol);
            return null;
        }

        double positionValue = currentPrice * quantity;
        if (positionValue > availableCapital) {
            if (!mlMode) {
                log.warn("{} Insufficient capital for {}: need {} have {}", LOG_PREFIX, symbol, positionValue, availableCapital);
                return null;
            }
            log.info("{} [ML-MODE] Virtual capital for {}: need {} have {} - proceeding for ML data",
                LOG_PREFIX, symbol, positionValue, availableCapital);
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

        // Persist to database FIRST
        try {
            trade = paperTradeRepository.save(trade);
            log.info("{} Trade persisted to database: {}", LOG_PREFIX, trade.getTradeId());
        } catch (Exception e) {
            log.error("{} Failed to persist trade to database: {}", LOG_PREFIX, e.getMessage(), e);
            return null;
        }

        // Update account state
        availableCapital -= positionValue;
        usedMargin += positionValue;

        // Store in memory cache
        openTrades.put(trade.getTradeId(), trade);

        log.info("{} Opened {} {} position: {} @ {} (target: {}, stop: {}, qty: {})",
            LOG_PREFIX, direction, symbol, trade.getTradeId(), currentPrice, target, stopLoss, quantity);

        return trade;
    }

    /**
     * Close an open position and persist to database.
     */
    public PaperTrade closePosition(String tradeId, double exitPrice, String reason) {
        PaperTrade trade = openTrades.remove(tradeId);
        if (trade == null) {
            // Try to find in database
            Optional<PaperTrade> dbTrade = paperTradeRepository.findByTradeId(tradeId);
            if (dbTrade.isEmpty() || dbTrade.get().getStatus() != TradeStatus.OPEN) {
                log.warn("{} No open trade found: {}", LOG_PREFIX, tradeId);
                return null;
            }
            trade = dbTrade.get();
        }

        trade.close(exitPrice, reason);

        // Persist closed trade to database
        try {
            trade = paperTradeRepository.save(trade);
            log.info("{} Closed trade persisted to database: {} - Reason: {}", LOG_PREFIX, tradeId, reason);
        } catch (Exception e) {
            log.error("{} Failed to persist closed trade: {}", LOG_PREFIX, e.getMessage(), e);
            // Re-add to open trades since persistence failed
            openTrades.put(tradeId, trade);
            return null;
        }

        // Update account state
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

        log.info("{} Closed {} position: {} @ {} P&L: {} ({}) | Total Realized: {}",
            LOG_PREFIX, trade.getSymbol(), tradeId, exitPrice, trade.getRealizedPnL(), reason, realizedPnL);

        return trade;
    }

    /**
     * Update all open positions with new prices and persist changes.
     */
    public void updatePositions(Map<String, Double> priceMap) {
        unrealizedPnL = 0;

        for (PaperTrade trade : openTrades.values()) {
            Double price = priceMap.get(trade.getSymbol());
            if (price != null) {
                trade.updatePrice(price);
                unrealizedPnL += trade.getUnrealizedPnL();

                // Persist updated trade
                try {
                    paperTradeRepository.save(trade);
                } catch (Exception e) {
                    log.error("{} Failed to persist price update for {}: {}",
                        LOG_PREFIX, trade.getTradeId(), e.getMessage());
                }
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

        // Persist the price update
        try {
            paperTradeRepository.save(trade);
        } catch (Exception e) {
            log.error("{} Failed to persist price update: {}", LOG_PREFIX, e.getMessage());
        }

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

            // Persist change
            paperTradeRepository.save(trade);
            log.info("{} Trailing stop enabled for {}: {}%", LOG_PREFIX, tradeId, trailingPercent);
        }
    }

    /**
     * Modify stop loss.
     */
    public void modifyStopLoss(String tradeId, double newStop) {
        PaperTrade trade = openTrades.get(tradeId);
        if (trade != null) {
            trade.setStopLoss(newStop);
            trade.setLastUpdated(Instant.now());

            // Persist change
            paperTradeRepository.save(trade);
            log.info("{} Stop loss modified for {}: {}", LOG_PREFIX, tradeId, newStop);
        }
    }

    /**
     * Modify target price.
     */
    public void modifyTarget(String tradeId, double newTarget) {
        PaperTrade trade = openTrades.get(tradeId);
        if (trade != null) {
            trade.setTargetPrice(newTarget);
            trade.setLastUpdated(Instant.now());

            // Persist change
            paperTradeRepository.save(trade);
            log.info("{} Target modified for {}: {}", LOG_PREFIX, tradeId, newTarget);
        }
    }

    /**
     * Move stop to breakeven.
     */
    public void moveStopToBreakeven(String tradeId) {
        PaperTrade trade = openTrades.get(tradeId);
        if (trade != null && trade.getUnrealizedPnL() > 0) {
            trade.setStopLoss(trade.getEntryPrice());
            trade.setLastUpdated(Instant.now());

            // Persist change
            paperTradeRepository.save(trade);
            log.info("{} Stop moved to breakeven for {}: {}", LOG_PREFIX, tradeId, trade.getEntryPrice());
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
        // In ML mode, use initial capital for sizing so trades aren't starved
        double capitalForSizing = mlMode ? Math.max(availableCapital, initialCapital) : availableCapital;
        double riskAmount = capitalForSizing * riskPerTrade;
        double riskPerShare = Math.abs(entryPrice - stopLoss);

        if (riskPerShare <= 0) {
            return 0;
        }

        int size = (int) (riskAmount / riskPerShare);

        // Cap position value at 20% of initial capital to prevent absurd quantities
        if (entryPrice > 0) {
            int maxSize = (int) (initialCapital * 0.20 / entryPrice);
            if (maxSize > 0) {
                size = Math.min(size, maxSize);
            }
        }

        return Math.max(1, size);
    }

    private boolean canOpenPosition(String symbol) {
        // Check max positions (skip in ML mode to collect all signal data)
        if (!mlMode && openTrades.size() >= maxPositions) {
            return false;
        }

        // Still prevent duplicate positions per symbol
        if (openTrades.values().stream().anyMatch(t -> t.getSymbol().equals(symbol))) {
            return false;
        }

        // Also check database for safety
        return !paperTradeRepository.existsOpenPositionBySymbol(symbol);
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

    /**
     * Get trade history from database (not in-memory).
     */
    public List<PaperTrade> getTradeHistory() {
        return paperTradeRepository.findByStatus(TradeStatus.CLOSED);
    }

    /**
     * Get recent trades from database.
     */
    public List<PaperTrade> getRecentTrades(int limit) {
        return paperTradeRepository.findRecentClosedTrades(
            org.springframework.data.domain.PageRequest.of(0, limit,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "exitTime")));
    }

    /**
     * Get trade by signal ID.
     */
    public List<PaperTrade> getTradesBySignalId(String signalId) {
        return paperTradeRepository.findBySignalId(signalId);
    }

    // ==================== STATISTICS ====================

    public Map<String, Object> getAccountSummary() {
        Map<String, Object> summary = new HashMap<>();

        // Get counts from database for accuracy
        long totalTrades = paperTradeRepository.countByStatus(TradeStatus.CLOSED);
        long wins = paperTradeRepository.countWinningTrades();
        long losses = paperTradeRepository.countLosingTrades();
        long breakeven = paperTradeRepository.countBreakevenTrades();

        summary.put("initialCapital", initialCapital);
        summary.put("currentEquity", getEquity());
        summary.put("availableCapital", availableCapital);
        summary.put("usedMargin", usedMargin);
        summary.put("unrealizedPnL", unrealizedPnL);
        summary.put("realizedPnL", realizedPnL);
        summary.put("maxDrawdown", String.format("%.2f%%", maxDrawdown));
        summary.put("openPositions", openTrades.size());
        summary.put("totalTrades", totalTrades);
        summary.put("wins", wins);
        summary.put("losses", losses);
        summary.put("breakeven", breakeven);

        // Win rate
        double winRate = (wins + losses) > 0 ? (double) wins / (wins + losses) * 100 : 0;
        summary.put("winRate", String.format("%.2f%%", winRate));

        // Profit factor from database
        List<PaperTrade> closedTrades = paperTradeRepository.findByStatus(TradeStatus.CLOSED);
        double totalWins = closedTrades.stream()
            .filter(t -> t.getRealizedPnL() > 0)
            .mapToDouble(PaperTrade::getRealizedPnL)
            .sum();
        double totalLosses = Math.abs(closedTrades.stream()
            .filter(t -> t.getRealizedPnL() < 0)
            .mapToDouble(PaperTrade::getRealizedPnL)
            .sum());
        double profitFactor = totalLosses > 0 ? totalWins / totalLosses : totalWins > 0 ? Double.MAX_VALUE : 0;
        summary.put("profitFactor", String.format("%.2f", profitFactor));

        return summary;
    }

    public Map<String, Double> getPnLBySymbol() {
        List<PaperTrade> closedTrades = paperTradeRepository.findByStatus(TradeStatus.CLOSED);
        return closedTrades.stream()
            .collect(Collectors.groupingBy(
                PaperTrade::getSymbol,
                Collectors.summingDouble(PaperTrade::getRealizedPnL)
            ));
    }

    public Map<String, Double> getPnLBySignalType() {
        List<PaperTrade> closedTrades = paperTradeRepository.findByStatus(TradeStatus.CLOSED);
        return closedTrades.stream()
            .filter(t -> t.getSignalType() != null)
            .collect(Collectors.groupingBy(
                PaperTrade::getSignalType,
                Collectors.summingDouble(PaperTrade::getRealizedPnL)
            ));
    }

    /**
     * Get win rate by signal type.
     */
    public Map<String, Double> getWinRateBySignalType() {
        Map<String, Double> winRates = new HashMap<>();

        // Get unique signal types
        List<PaperTrade> allTrades = paperTradeRepository.findByStatus(TradeStatus.CLOSED);
        Set<String> signalTypes = allTrades.stream()
            .map(PaperTrade::getSignalType)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        for (String signalType : signalTypes) {
            long wins = paperTradeRepository.countWinsBySignalType(signalType);
            long losses = paperTradeRepository.countLossesBySignalType(signalType);
            double winRate = (wins + losses) > 0 ? (double) wins / (wins + losses) * 100 : 0;
            winRates.put(signalType, winRate);
        }

        return winRates;
    }
}
