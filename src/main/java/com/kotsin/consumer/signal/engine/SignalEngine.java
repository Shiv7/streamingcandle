package com.kotsin.consumer.signal.engine;

import com.kotsin.consumer.gate.GateChain;
import com.kotsin.consumer.gate.model.GateResult.ChainResult;
import com.kotsin.consumer.indicator.calculator.TechnicalIndicatorCalculator;
import com.kotsin.consumer.indicator.model.TechnicalIndicators;
import com.kotsin.consumer.logging.TraceContext;
import com.kotsin.consumer.model.StrategyState;
import com.kotsin.consumer.model.StrategyState.*;
import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.model.MultiTimeframePivotState;
import com.kotsin.consumer.model.CprAnalysis;
import com.kotsin.consumer.model.ConfluenceResult;
import com.kotsin.consumer.model.BounceSignal;
import com.kotsin.consumer.papertrade.executor.PaperTradeExecutor;
import com.kotsin.consumer.papertrade.model.PaperTrade.TradeDirection;
import com.kotsin.consumer.regime.detector.RegimeDetector;
import com.kotsin.consumer.regime.model.MarketRegime;
import com.kotsin.consumer.regime.model.MarketRegime.TradingMode;
import com.kotsin.consumer.service.CandleService;
import com.kotsin.consumer.service.StrategyStateService;
import com.kotsin.consumer.service.PivotLevelService;
import com.kotsin.consumer.service.HistoricalDataBootstrapService;
import com.kotsin.consumer.session.tracker.SessionStructureTracker;
import com.kotsin.consumer.session.model.SessionStructure;
import com.kotsin.consumer.signal.calculator.FudkiiCalculator;
import com.kotsin.consumer.signal.model.*;
import com.kotsin.consumer.signal.processor.*;
import com.kotsin.consumer.signal.analyzer.PivotConfluenceAnalyzer;
import com.kotsin.consumer.signal.repository.TradingSignalRepository;
import com.kotsin.consumer.smc.analyzer.SMCAnalyzer;
import com.kotsin.consumer.stats.model.SignalHistory.SignalDirection;
import com.kotsin.consumer.stats.tracker.SignalStatsTracker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Optional;

/**
 * SignalEngine - Main orchestrator for trading signal generation.
 *
 * Responsibilities:
 * 1. Process incoming candles through strategy processors
 * 2. Calculate FUDKII scores
 * 3. Manage signal state transitions (IDLE -> WATCH -> ACTIVE -> COMPLETE)
 * 4. Track active signals and check for exits
 * 5. Emit signals to subscribers
 *
 * Flow:
 * Candle -> VCP/IPU/Pivot Processors -> FudkiiCalculator -> State Machine -> Signal Output
 */
@Service
@Slf4j
public class SignalEngine {

    private static final String LOG_PREFIX = "[SIGNAL-ENGINE]";

    @Autowired
    private CandleService candleService;

    @Autowired
    private StrategyStateService strategyStateService;

    @Autowired
    private VcpProcessor vcpProcessor;

    @Autowired
    private IpuProcessor ipuProcessor;

    @Autowired
    private PivotProcessor pivotProcessor;

    @Autowired
    private FudkiiCalculator fudkiiCalculator;

    @Autowired
    private TradingSignalRepository signalRepository;

    // ==================== NEW INTEGRATED COMPONENTS ====================

    @Autowired
    private TechnicalIndicatorCalculator indicatorCalculator;

    @Autowired
    private GateChain gateChain;

    @Autowired
    private RegimeDetector regimeDetector;

    @Autowired
    private SessionStructureTracker sessionTracker;

    @Autowired
    private SMCAnalyzer smcAnalyzer;

    @Autowired
    private SignalStatsTracker statsTracker;

    @Autowired
    private PaperTradeExecutor paperTradeExecutor;

    // ==================== PIVOT & HISTORICAL DATA INTEGRATION ====================

    @Autowired
    private PivotLevelService pivotLevelService;

    @Autowired
    private HistoricalDataBootstrapService historicalDataBootstrapService;

    @Autowired
    private PivotConfluenceAnalyzer pivotConfluenceAnalyzer;

    @Value("${signal.engine.enabled:true}")
    private boolean enabled;

    @Value("${signal.engine.gate.enabled:true}")
    private boolean gateEnabled;

    @Value("${signal.engine.papertrade.enabled:false}")
    private boolean paperTradeEnabled;

    @Value("${signal.engine.symbols:}")
    private String symbolsConfig;

    @Value("${signal.engine.dynamic.symbols:true}")
    private boolean useDynamicSymbols;

    @Value("${signal.engine.timeframe:5m}")
    private String primaryTimeframe;

    @Value("${signal.watch.expiry.minutes:30}")
    private int watchExpiryMinutes;

    @Value("${signal.active.expiry.hours:4}")
    private int activeExpiryHours;

    @Value("${logging.trace.symbols:}")
    private String traceSymbolsStr;
    private final java.util.concurrent.atomic.AtomicLong discoveryLogCounter = new java.util.concurrent.atomic.AtomicLong(0);

    // Active signals by symbol
    private final ConcurrentHashMap<String, TradingSignal> activeSignals = new ConcurrentHashMap<>();

    // Processing state
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    // Signal listeners
    private final List<SignalListener> listeners = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("{} Disabled by configuration", LOG_PREFIX);
            return;
        }

        log.info("{} Starting with dynamicSymbols={}, configuredSymbols={}, timeframe={}",
            LOG_PREFIX, useDynamicSymbols, symbolsConfig, primaryTimeframe);

        running.set(true);

        // Start processing scheduler
        scheduler = Executors.newScheduledThreadPool(2);

        // Process signals every 5 seconds
        scheduler.scheduleAtFixedRate(this::processAllSymbols, 5, 5, TimeUnit.SECONDS);

        // Check active signals for exits every second
        scheduler.scheduleAtFixedRate(this::checkActiveSignals, 1, 1, TimeUnit.SECONDS);

        log.info("{} Started successfully", LOG_PREFIX);
    }

    @PreDestroy
    public void stop() {
        log.info("{} Stopping...", LOG_PREFIX);
        running.set(false);

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        log.info("{} Stopped", LOG_PREFIX);
    }

    /**
     * Process all symbols - either from dynamic discovery or configuration.
     */
    private void processAllSymbols() {
        if (!running.get()) return;

        Set<String> symbols = getSymbolsToProcess();
        if (symbols.isEmpty()) {
            log.debug("{} No symbols to process", LOG_PREFIX);
            return;
        }

        Timeframe tf = Timeframe.fromLabel(primaryTimeframe);
        log.info("{} Processing {} symbols: {}", LOG_PREFIX, symbols.size(), symbols);

        for (String symbol : symbols) {
            try {
                processSymbol(symbol, tf);
            } catch (Exception e) {
                log.error("{} Error processing symbol={}: {}", LOG_PREFIX, symbol, e.getMessage());
            }
        }
    }

    /**
     * Get symbols to process - either dynamically from Redis or from config.
     */
    private Set<String> getSymbolsToProcess() {
        if (useDynamicSymbols) {
            // Get all symbols that have candle data in Redis
            Set<String> cachedKeys = candleService.getAvailableSymbols();
            
            // [DISCOVERY] Log every 12th scan (approx 1 minute)
            long runCount = discoveryLogCounter.incrementAndGet();
            if (runCount % 12 == 1) { // Log on 1st, 13th, etc.
                if (cachedKeys == null || cachedKeys.isEmpty()) {
                     log.info("[DISCOVERY] Scan found 0 symbols in Redis. Keys pattern: tick:*:1m:latest");
                } else {
                     log.info("[DISCOVERY] Scan found {} symbols in Redis", cachedKeys.size());
                }
            }

            if (cachedKeys == null || cachedKeys.isEmpty()) {
                log.debug("{} No symbols found in Redis cache", LOG_PREFIX);
                return Set.of();
            }

            // Extract symbol names from keys (format: tick:SYMBOL:1m:latest)
            Set<String> symbols = new HashSet<>();
            for (String key : cachedKeys) {
                String[] parts = key.split(":");
                if (parts.length >= 2) {
                    symbols.add(parts[1]);
                }
            }
            log.debug("{} Dynamic symbols discovered from Redis: {}", LOG_PREFIX, symbols.size());
            return symbols;
        } else {
            // Use configured symbols
            if (symbolsConfig == null || symbolsConfig.trim().isEmpty()) {
                return Set.of();
            }
            Set<String> symbols = new HashSet<>();
            for (String s : symbolsConfig.split(",")) {
                symbols.add(s.trim());
            }
            return symbols;
        }
    }

    /**
     * Process a single symbol.
     */
    public void processSymbol(String symbol, Timeframe timeframe) {
        // Start trace context for this processing cycle
        String traceId = TraceContext.start(symbol, timeframe.getLabel());

        try {
            log.debug("{} {} Processing started", LOG_PREFIX, TraceContext.getShortPrefix());

            // Get recent candles
            List<UnifiedCandle> candles = candleService.getCandleHistory(symbol, timeframe, 100);
            if (candles == null || candles.isEmpty()) {
                log.debug("{} {} No candles available, skipping", LOG_PREFIX, TraceContext.getShortPrefix());
                return;
            }

            UnifiedCandle current = candles.get(0);
            List<UnifiedCandle> history = candles.size() > 1 ? candles.subList(1, candles.size()) : List.of();

            log.debug("{} {} Candles loaded: count={}, price={}",
                LOG_PREFIX, TraceContext.getShortPrefix(),
                candles.size(), String.format("%.2f", current.getClose()));

            // Update session structure
            TraceContext.addStage("SESSION");
            updateSessionStructure(symbol, current);

            // Calculate technical indicators
            TraceContext.addStage("INDICATORS");
            TechnicalIndicators indicators = calculateIndicators(symbol, timeframe.getLabel(), candles);

            // Update regime detection
            TraceContext.addStage("REGIME");
            updateRegime(symbol, timeframe.getLabel(), indicators);

            // Get or compute strategy states
            VcpState vcpState = getOrComputeVcpState(symbol, current, history, timeframe);
            IpuState ipuState = getOrComputeIpuState(symbol, current, history, timeframe);
            PivotState pivotState = getOrComputePivotState(symbol, candles, timeframe);

            // Calculate FUDKII score
            FudkiiScore score = fudkiiCalculator.calculate(
                current, history, vcpState, ipuState, pivotState);

            // ==================== PIVOT CONFLUENCE ANALYSIS ====================
            TraceContext.addStage("PIVOT_MTF");
            analyzePivotConfluence(symbol, current, candles, score);

            // Get current signal for this symbol
            TradingSignal signal = activeSignals.get(symbol);

            // Process state machine
            TraceContext.addStage("STATE_MACHINE");
            signal = processStateMachine(symbol, current, score, signal, pivotState, indicators);

            // Update active signals map
            if (signal != null && signal.getState().isActive()) {
                activeSignals.put(symbol, signal);
            } else if (signal != null && signal.getState().isTerminal()) {
                activeSignals.remove(symbol);
            }

            // Update paper trade positions
            if (paperTradeEnabled) {
                TraceContext.addStage("PAPER_TRADE");
                paperTradeExecutor.updateAndCheckExit(symbol, current.getHigh(), current.getLow(), current.getClose());
            }

            log.debug("{} {} Processing complete: stages={}, score={}",
                LOG_PREFIX, TraceContext.getShortPrefix(),
                TraceContext.getStageCount(),
                String.format("%.1f", score.getCompositeScore()));

        } finally {
            // Always clear trace context
            TraceContext.clear();
        }
    }

    /**
     * Update session structure from candle data.
     */
    private void updateSessionStructure(String symbol, UnifiedCandle candle) {
        try {
            sessionTracker.update(
                symbol,
                candle.getTimestamp(),
                candle.getOpen(),
                candle.getHigh(),
                candle.getLow(),
                candle.getClose(),
                candle.getVolume()
            );
        } catch (Exception e) {
            log.debug("{} {} Session structure update failed: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), e.getMessage());
        }
    }

    /**
     * Calculate technical indicators from candles.
     */
    private TechnicalIndicators calculateIndicators(String symbol, String timeframe, List<UnifiedCandle> candles) {
        try {
            double[] closes = candles.stream().mapToDouble(UnifiedCandle::getClose).toArray();
            double[] highs = candles.stream().mapToDouble(UnifiedCandle::getHigh).toArray();
            double[] lows = candles.stream().mapToDouble(UnifiedCandle::getLow).toArray();
            double[] volumes = candles.stream().mapToDouble(UnifiedCandle::getVolume).toArray();

            return indicatorCalculator.calculateAll(symbol, timeframe, closes, highs, lows, volumes);
        } catch (Exception e) {
            log.debug("{} {} Indicator calculation failed: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), e.getMessage());
            return null;
        }
    }

    /**
     * Update regime detection.
     */
    private void updateRegime(String symbol, String timeframe, TechnicalIndicators indicators) {
        if (indicators == null) return;

        try {
            regimeDetector.detectSimple(
                symbol,
                timeframe,
                indicators.getAdx(),
                indicators.getSuperTrendDirection() == TechnicalIndicators.SuperTrendDirection.UP,
                indicators.getRsi(),
                indicators.getAtrPercent()
            );
        } catch (Exception e) {
            log.debug("{} {} Regime detection failed: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), e.getMessage());
        }
    }

    /**
     * Analyze multi-timeframe pivot confluence and detect bounce signals.
     * This enhances the FUDKII score based on:
     * 1. CPR width analysis (thin CPR = high breakout probability)
     * 2. Confluence detection (multiple pivots at same price = strong S/R)
     * 3. Bounce detection (price reversal at pivot = top/bottom signal)
     */
    private void analyzePivotConfluence(String symbol, UnifiedCandle current,
            List<UnifiedCandle> candles, FudkiiScore score) {
        try {
            // Get multi-timeframe pivot levels (exch and exchType from candle)
            String exch = current.getExchange() != null ? current.getExchange() : "N";
            String exchType = current.getExchangeType() != null ? current.getExchangeType() : "C";
            String scripCode = current.getScripCode() != null ? current.getScripCode() : symbol;

            // Diagnostic logging for scripCode mismatch debugging
            log.debug("{} {} Pivot lookup: symbol={}, scripCode={}, exch={}, exchType={}, price={}",
                LOG_PREFIX, TraceContext.getShortPrefix(),
                symbol, scripCode, exch, exchType,
                String.format("%.2f", current.getClose()));

            Optional<MultiTimeframePivotState> pivotStateOpt = pivotLevelService.getOrLoadPivotLevels(
                scripCode, exch, exchType);

            if (pivotStateOpt.isEmpty() || !pivotStateOpt.get().isValid()) {
                log.debug("{} {} No pivot levels available for {}",
                    LOG_PREFIX, TraceContext.getShortPrefix(), symbol);
                return;
            }

            MultiTimeframePivotState mtfPivots = pivotStateOpt.get();
            double currentPrice = current.getClose();

            // Sanity check: pivot should be within 50% of current price
            // If not, there's likely a scripCode mismatch
            if (mtfPivots.getDailyPivot() != null && mtfPivots.getDailyPivot().getPivot() > 0) {
                double pivotPrice = mtfPivots.getDailyPivot().getPivot();
                double deviation = Math.abs(currentPrice - pivotPrice) / pivotPrice;
                if (deviation > 0.5) {
                    log.warn("{} {} SCRIPCODE MISMATCH? symbol={}, scripCode={}, price={}, pivot={}, deviation={}%",
                        LOG_PREFIX, TraceContext.getShortPrefix(),
                        symbol, scripCode,
                        String.format("%.2f", currentPrice),
                        String.format("%.2f", pivotPrice),
                        String.format("%.1f", deviation * 100));
                    return; // Skip pivot analysis for this symbol - data is invalid
                }
            }

            // 1. Analyze CPR characteristics
            CprAnalysis cprAnalysis = pivotConfluenceAnalyzer.analyzeCpr(
                mtfPivots.getDailyPivot(), currentPrice);

            if (cprAnalysis != null && cprAnalysis.getCprWidthPercent() > 0) {
                // Log CPR analysis for thin CPR (high probability setups)
                if (cprAnalysis.getType() == CprAnalysis.CprType.ULTRA_THIN ||
                    cprAnalysis.getType() == CprAnalysis.CprType.THIN) {
                    log.info("{} {} CPR Alert: {} ({}%) - {} - Breakout Prob: {}%",
                        LOG_PREFIX, TraceContext.getShortPrefix(),
                        cprAnalysis.getType().name(),
                        String.format("%.3f", cprAnalysis.getCprWidthPercent()),
                        cprAnalysis.getPricePosition().name(),
                        String.format("%.0f", cprAnalysis.getBreakoutProbability() * 100));

                    // Boost score for thin CPR breakout scenarios
                    if (cprAnalysis.getPricePosition() == CprAnalysis.PricePosition.ABOVE_CPR &&
                        score.getDirection() == FudkiiScore.Direction.BULLISH) {
                        score.addBoost("THIN_CPR_BULLISH", cprAnalysis.getBreakoutProbability() * 15);
                    } else if (cprAnalysis.getPricePosition() == CprAnalysis.PricePosition.BELOW_CPR &&
                        score.getDirection() == FudkiiScore.Direction.BEARISH) {
                        score.addBoost("THIN_CPR_BEARISH", cprAnalysis.getBreakoutProbability() * 15);
                    }
                }
            }

            // 2. Check confluence at current price
            ConfluenceResult confluence = pivotConfluenceAnalyzer.analyzeConfluence(
                mtfPivots, currentPrice);

            if (confluence != null && confluence.isHighConviction()) {
                log.info("{} {} CONFLUENCE: {} at {} - {}",
                    LOG_PREFIX, TraceContext.getShortPrefix(),
                    confluence.getStrength().name(),
                    String.format("%.2f", currentPrice),
                    confluence.getDescription());

                // Boost score for strong confluence
                score.addBoost("CONFLUENCE_" + confluence.getStrength().name(),
                    confluence.getConfidenceScore() * 10);
            }

            // 3. Detect bounce signals at pivot levels
            BounceSignal bounce = pivotConfluenceAnalyzer.detectBounce(
                mtfPivots, candles, currentPrice);

            if (bounce != null && bounce.isHighConfidence()) {
                log.info("{} {} BOUNCE SIGNAL: {} at {} ({}) - Confluence: {}, Quality: {}",
                    LOG_PREFIX, TraceContext.getShortPrefix(),
                    bounce.getType().name(),
                    bounce.getLevelName(),
                    String.format("%.2f", bounce.getLevel()),
                    bounce.getConfluence(),
                    bounce.getQualityScore());

                // Boost score based on bounce signal
                if (bounce.isBullish() && score.getDirection() == FudkiiScore.Direction.BULLISH) {
                    score.addBoost("BULLISH_BOUNCE", bounce.getConfidence() * 20);
                } else if (bounce.isBearish() && score.getDirection() == FudkiiScore.Direction.BEARISH) {
                    score.addBoost("BEARISH_BOUNCE", bounce.getConfidence() * 20);
                } else if (bounce.isBullish() && score.getDirection() != FudkiiScore.Direction.BULLISH) {
                    // Bounce signal suggests direction change
                    log.info("{} {} Direction conflict: Bounce suggests BULLISH but score is {}",
                        LOG_PREFIX, TraceContext.getShortPrefix(), score.getDirection());
                } else if (bounce.isBearish() && score.getDirection() != FudkiiScore.Direction.BEARISH) {
                    log.info("{} {} Direction conflict: Bounce suggests BEARISH but score is {}",
                        LOG_PREFIX, TraceContext.getShortPrefix(), score.getDirection());
                }
            }

        } catch (Exception e) {
            log.debug("{} {} Pivot confluence analysis failed: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), e.getMessage());
        }
    }

    /**
     * Validate signal through gate chain.
     */
    private ChainResult validateThroughGates(String symbol, FudkiiScore score,
                                              TechnicalIndicators indicators, double price,
                                              double target, double stopLoss) {
        if (!gateEnabled || indicators == null) {
            return null;
        }

        try {
            boolean isLong = score.getDirection() == FudkiiScore.Direction.BULLISH;
            SessionStructure session = sessionTracker.getSession(symbol);
            MarketRegime regime = regimeDetector.getCurrentRegime(symbol);

            Map<String, Object> context = GateChain.contextBuilder()
                .signalDirection(isLong ? "LONG" : "SHORT")
                .prices(price, target, stopLoss)
                .volume(indicators.getVolume(), indicators.getAvgVolume20())
                .emas(indicators.isAboveEma20(), indicators.isAboveEma50())
                .superTrend(indicators.getSuperTrendDirection() == TechnicalIndicators.SuperTrendDirection.UP)
                .htfTrend(regime != null ? regime.getRegimeType().name() : "NEUTRAL")
                .rsi(indicators.getRsi())
                .vwap(session != null ? session.getVwap() : 0,
                      session != null && session.getSessionClose() > session.getVwap())
                .fudkii(score.getCompositeScore())
                .custom("macdHistogram", indicators.getMacdHistogram())
                .build();

            return gateChain.evaluate(symbol, "FUDKII", context);
        } catch (Exception e) {
            log.debug("{} {} Gate validation failed: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), e.getMessage());
            return null;
        }
    }

    /**
     * Process signal state machine.
     */
    private TradingSignal processStateMachine(
            String symbol,
            UnifiedCandle candle,
            FudkiiScore score,
            TradingSignal currentSignal,
            PivotState pivotState,
            TechnicalIndicators indicators) {

        double price = candle.getClose();

        // Check regime - avoid trading in AVOID mode
        MarketRegime regime = regimeDetector.getCurrentRegime(symbol);
        if (regime != null && regime.getRecommendedMode() == TradingMode.AVOID) {
            log.debug("{} {} Skipping - market regime suggests AVOID",
                LOG_PREFIX, TraceContext.getShortPrefix());
            return currentSignal;
        }

        // No active signal - check for new WATCH
        if (currentSignal == null || currentSignal.getState().isTerminal()) {
            if (score.isWatchSetup()) {
                // Create new WATCH signal
                TradingSignal newSignal = createWatchSignal(symbol, candle, score);
                saveAndNotify(newSignal, SignalEvent.WATCH_ENTERED);
                return newSignal;
            }
            return null;
        }

        // Current state: WATCH
        if (currentSignal.getState() == SignalState.WATCH) {
            // Check for ACTIVE trigger
            if (score.isActiveTrigger()) {
                // Calculate entry levels
                EntryLevels levels = calculateEntryLevels(score, pivotState, price);

                // Validate through gate chain before activating
                ChainResult gateResult = validateThroughGates(
                    symbol, score, indicators, price, levels.target1, levels.stop);

                if (gateResult != null && !gateResult.isPassed()) {
                    log.info("{} {} Signal blocked by gates: {}",
                        LOG_PREFIX, TraceContext.getShortPrefix(), gateResult.getFailureReason());
                    // Don't activate, but keep watching
                    currentSignal.updatePrice(price, score);
                    return currentSignal;
                }

                // Gate passed or gates disabled - activate signal
                currentSignal.enterActive(score, price,
                    levels.entry, levels.stop, levels.target1, levels.target2);

                // Record to stats tracker
                recordSignalToStats(currentSignal, score);

                // Execute paper trade if enabled
                executePaperTrade(currentSignal, levels);

                saveAndNotify(currentSignal, SignalEvent.ACTIVE_TRIGGERED);

                if (gateResult != null) {
                    log.info("{} {} Gate score: {} ({})",
                        LOG_PREFIX, TraceContext.getShortPrefix(),
                        String.format("%.1f", gateResult.getTotalScore()),
                        gateResult.isHighQuality() ? "HIGH_QUALITY" : "STANDARD");
                }

                return currentSignal;
            }

            // Check for expiry
            if (currentSignal.isWatchExpired()) {
                currentSignal.enterExpired("WATCH timeout");
                saveAndNotify(currentSignal, SignalEvent.EXPIRED);
                return currentSignal;
            }

            // Check for invalidation (score dropped significantly)
            if (score.getCompositeScore() < 20 || !isDirectionAligned(score, currentSignal)) {
                currentSignal.enterExpired("Setup invalidated");
                saveAndNotify(currentSignal, SignalEvent.EXPIRED);
                return currentSignal;
            }

            // Update current score
            currentSignal.updatePrice(price, score);
            return currentSignal;
        }

        // Current state: ACTIVE
        if (currentSignal.getState() == SignalState.ACTIVE) {
            // Check for stop loss
            if (currentSignal.isStopHit(price)) {
                currentSignal.enterComplete(TradingSignal.ExitReason.STOP_HIT, price);
                recordExitToStats(currentSignal, price, "STOP_HIT");
                saveAndNotify(currentSignal, SignalEvent.STOPPED_OUT);
                return currentSignal;
            }

            // Check for target hit
            if (currentSignal.isTargetHit(price)) {
                currentSignal.enterComplete(TradingSignal.ExitReason.TARGET_HIT, price);
                recordExitToStats(currentSignal, price, "TARGET_HIT");
                saveAndNotify(currentSignal, SignalEvent.TARGET_HIT);
                return currentSignal;
            }

            // Check for time expiry
            if (currentSignal.isActiveExpired()) {
                currentSignal.enterComplete(TradingSignal.ExitReason.TIME_EXPIRY, price);
                recordExitToStats(currentSignal, price, "TIME_EXPIRY");
                saveAndNotify(currentSignal, SignalEvent.EXPIRED);
                return currentSignal;
            }

            // Check for reversal signal
            if (isReversalTriggered(score, currentSignal)) {
                currentSignal.enterComplete(TradingSignal.ExitReason.REVERSAL, price);
                recordExitToStats(currentSignal, price, "REVERSAL");
                saveAndNotify(currentSignal, SignalEvent.REVERSED);
                return currentSignal;
            }

            // Update tracking
            currentSignal.updatePrice(price, score);
            return currentSignal;
        }

        return currentSignal;
    }

    /**
     * Create a new WATCH signal.
     */
    private TradingSignal createWatchSignal(String symbol, UnifiedCandle candle, FudkiiScore score) {
        TradingSignal signal = TradingSignal.builder()
            .signalId(TradingSignal.generateSignalId(symbol, primaryTimeframe))
            .symbol(symbol)
            .scripCode(candle.getScripCode())
            .exchange(candle.getExchange())
            .companyName(candle.getCompanyName())
            .timeframe(primaryTimeframe)
            .state(SignalState.IDLE)
            .createdAt(Instant.now())
            .version(0)
            .build();

        signal.enterWatch(score, candle.getClose(), watchExpiryMinutes);
        return signal;
    }

    /**
     * Calculate entry, stop, and target levels.
     */
    private EntryLevels calculateEntryLevels(FudkiiScore score, PivotState pivotState, double price) {
        EntryLevels levels = new EntryLevels();

        boolean bullish = score.getDirection() == FudkiiScore.Direction.BULLISH;

        // Entry is current price
        levels.entry = price;

        // Stop loss from nearest support/resistance
        if (bullish && pivotState != null && !pivotState.getSupportLevels().isEmpty()) {
            PriceLevel nearestSupport = pivotState.getSupportLevels().get(0);
            levels.stop = nearestSupport.getPrice() * 0.998; // Slightly below
        } else if (!bullish && pivotState != null && !pivotState.getResistanceLevels().isEmpty()) {
            PriceLevel nearestResistance = pivotState.getResistanceLevels().get(0);
            levels.stop = nearestResistance.getPrice() * 1.002; // Slightly above
        } else {
            // Default: 1% stop
            levels.stop = bullish ? price * 0.99 : price * 1.01;
        }

        // Targets based on risk-reward
        double risk = Math.abs(levels.entry - levels.stop);
        if (bullish) {
            levels.target1 = levels.entry + (risk * 2);   // 2R target
            levels.target2 = levels.entry + (risk * 3);   // 3R target
        } else {
            levels.target1 = levels.entry - (risk * 2);
            levels.target2 = levels.entry - (risk * 3);
        }

        return levels;
    }

    /**
     * Check if direction is still aligned.
     */
    private boolean isDirectionAligned(FudkiiScore score, TradingSignal signal) {
        if (score.getDirection() == FudkiiScore.Direction.NEUTRAL) {
            return true; // Neutral doesn't invalidate
        }
        return score.getDirection() == signal.getDirection();
    }

    /**
     * Check if reversal signal triggered.
     */
    private boolean isReversalTriggered(FudkiiScore score, TradingSignal signal) {
        // Opposite direction with high score
        if (score.getCompositeScore() >= 60 && score.isActiveTrigger()) {
            return score.getDirection() != signal.getDirection() &&
                   score.getDirection() != FudkiiScore.Direction.NEUTRAL;
        }
        return false;
    }

    /**
     * Check active signals for exit conditions.
     */
    private void checkActiveSignals() {
        for (Map.Entry<String, TradingSignal> entry : activeSignals.entrySet()) {
            try {
                String symbol = entry.getKey();
                TradingSignal signal = entry.getValue();

                if (signal.getState() != SignalState.ACTIVE) continue;

                // Get current price
                UnifiedCandle current = candleService.getLatestCandle(
                    symbol, Timeframe.fromLabel(primaryTimeframe));
                if (current == null) continue;

                double price = current.getClose();

                // Quick exit checks
                if (signal.isStopHit(price)) {
                    signal.enterComplete(TradingSignal.ExitReason.STOP_HIT, price);
                    saveAndNotify(signal, SignalEvent.STOPPED_OUT);
                    activeSignals.remove(symbol);
                } else if (signal.isTargetHit(price)) {
                    signal.enterComplete(TradingSignal.ExitReason.TARGET_HIT, price);
                    saveAndNotify(signal, SignalEvent.TARGET_HIT);
                    activeSignals.remove(symbol);
                }
            } catch (Exception e) {
                log.error("{} Error checking signal for symbol={}: {}",
                    LOG_PREFIX, entry.getKey(), e.getMessage());
            }
        }
    }

    // ==================== STATS & PAPER TRADE INTEGRATION ====================

    /**
     * Record signal to stats tracker.
     */
    private void recordSignalToStats(TradingSignal signal, FudkiiScore score) {
        try {
            SignalDirection direction = score.getDirection() == FudkiiScore.Direction.BULLISH ?
                SignalDirection.LONG : SignalDirection.SHORT;

            statsTracker.recordSignal(
                signal.getSymbol(),
                "FUDKII",
                signal.getTimeframe(),
                direction,
                signal.getEntryPrice(),
                signal.getTarget1(),
                signal.getStopLoss(),
                score.getCompositeScore(),
                score.getCompositeScore(),
                score.getDirection().name()
            );
        } catch (Exception e) {
            log.debug("{} {} Stats recording failed: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), e.getMessage());
        }
    }

    /**
     * Execute paper trade for activated signal.
     */
    private void executePaperTrade(TradingSignal signal, EntryLevels levels) {
        if (!paperTradeEnabled) return;

        try {
            TradeDirection direction = signal.getDirection() == FudkiiScore.Direction.BULLISH ?
                TradeDirection.LONG : TradeDirection.SHORT;

            paperTradeExecutor.executeMarketOrder(
                signal.getSymbol(),
                direction,
                levels.entry,
                levels.target1,
                levels.stop,
                signal.getSignalId(),
                "FUDKII"
            );
        } catch (Exception e) {
            log.debug("{} {} Paper trade execution failed: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), e.getMessage());
        }
    }

    /**
     * Record signal exit to stats.
     */
    private void recordExitToStats(TradingSignal signal, double exitPrice, String reason) {
        try {
            // Find active signal in stats tracker and record exit
            List<com.kotsin.consumer.stats.model.SignalHistory> activeSignals =
                statsTracker.getActiveSignals(signal.getSymbol());

            if (!activeSignals.isEmpty()) {
                com.kotsin.consumer.stats.model.SignalHistory history = activeSignals.get(0);
                statsTracker.recordExit(history.getSignalId(), exitPrice, reason);
            }
        } catch (Exception e) {
            log.debug("{} {} Stats exit recording failed: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), e.getMessage());
        }
    }

    // ==================== STRATEGY STATE HELPERS ====================

    private VcpState getOrComputeVcpState(String symbol, UnifiedCandle current,
                                           List<UnifiedCandle> history, Timeframe tf) {
        Optional<VcpState> cached = strategyStateService.getVcpState(symbol, tf.getLabel());
        if (cached.isPresent() && !cached.get().getCalculatedAt().isBefore(
                Instant.now().minusSeconds(60))) {
            return cached.get();
        }

        // Compute fresh
        List<UnifiedCandle> allCandles = new ArrayList<>();
        allCandles.add(current);
        allCandles.addAll(history);

        VcpState state = vcpProcessor.calculate(allCandles, current.getClose());

        // Save asynchronously
        strategyStateService.saveVcpState(symbol, current.getScripCode(), tf.getLabel(), state);

        return state;
    }

    private IpuState getOrComputeIpuState(String symbol, UnifiedCandle current,
                                           List<UnifiedCandle> history, Timeframe tf) {
        Optional<IpuState> cached = strategyStateService.getIpuState(symbol, tf.getLabel());
        IpuState state = cached.orElse(null);

        // Always compute new snapshot
        IpuSnapshot snapshot = ipuProcessor.calculate(current, history);
        state = ipuProcessor.updateState(state, snapshot);

        // Save
        strategyStateService.saveIpuState(symbol, current.getScripCode(), tf.getLabel(), state);

        return state;
    }

    private PivotState getOrComputePivotState(String symbol, List<UnifiedCandle> candles,
                                               Timeframe tf) {
        Optional<PivotState> cached = strategyStateService.getPivotState(symbol, tf.getLabel());

        PivotState state = pivotProcessor.calculate(candles, cached.orElse(null));

        // Save
        if (!candles.isEmpty()) {
            strategyStateService.savePivotState(symbol,
                candles.get(0).getScripCode(), tf.getLabel(), state);
        }

        return state;
    }

    // ==================== PERSISTENCE & NOTIFICATION ====================

    private void saveAndNotify(TradingSignal signal, SignalEvent event) {
        try {
            signalRepository.save(signal);
        } catch (Exception e) {
            log.error("{} {} Failed to save signal: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), e.getMessage());
        }

        // Notify listeners
        for (SignalListener listener : listeners) {
            try {
                listener.onSignalEvent(signal, event);
            } catch (Exception e) {
                log.error("{} {} Listener error: {}",
                    LOG_PREFIX, TraceContext.getShortPrefix(), e.getMessage());
            }
        }

        log.info("{} {} {} | direction={} | price={} | score={}",
            LOG_PREFIX, TraceContext.getShortPrefix(),
            event, signal.getDirection(),
            String.format("%.2f", signal.getCurrentPrice()),
            String.format("%.1f", signal.getCurrentScore().getCompositeScore()));
    }

    // ==================== PUBLIC API ====================

    /**
     * Get active signal for symbol.
     */
    public Optional<TradingSignal> getActiveSignal(String symbol) {
        return Optional.ofNullable(activeSignals.get(symbol));
    }

    /**
     * Get all active signals.
     */
    public List<TradingSignal> getAllActiveSignals() {
        return new ArrayList<>(activeSignals.values());
    }

    /**
     * Get signals by state.
     */
    public List<TradingSignal> getSignalsByState(SignalState state) {
        return activeSignals.values().stream()
            .filter(s -> s.getState() == state)
            .toList();
    }

    /**
     * Add signal listener.
     */
    public void addListener(SignalListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove signal listener.
     */
    public void removeListener(SignalListener listener) {
        listeners.remove(listener);
    }

    /**
     * Force process a symbol (for testing/manual trigger).
     */
    public FudkiiScore forceProcess(String symbol) {
        Timeframe tf = Timeframe.fromLabel(primaryTimeframe);
        List<UnifiedCandle> candles = candleService.getCandleHistory(symbol, tf, 100);

        if (candles == null || candles.isEmpty()) {
            return null;
        }

        UnifiedCandle current = candles.get(0);
        List<UnifiedCandle> history = candles.size() > 1 ? candles.subList(1, candles.size()) : List.of();

        VcpState vcpState = getOrComputeVcpState(symbol, current, history, tf);
        IpuState ipuState = getOrComputeIpuState(symbol, current, history, tf);
        PivotState pivotState = getOrComputePivotState(symbol, candles, tf);

        return fudkiiCalculator.calculate(current, history, vcpState, ipuState, pivotState);
    }

    /**
     * Check if engine is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Get engine stats.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("running", running.get());
        stats.put("enabled", enabled);
        stats.put("symbols", symbolsConfig);
        stats.put("timeframe", primaryTimeframe);
        stats.put("activeSignals", activeSignals.size());
        stats.put("watchSignals", getSignalsByState(SignalState.WATCH).size());
        stats.put("activeTriggeredSignals", getSignalsByState(SignalState.ACTIVE).size());
        return stats;
    }

    // ==================== INNER CLASSES ====================

    private static class EntryLevels {
        double entry;
        double stop;
        double target1;
        double target2;
    }

    public enum SignalEvent {
        WATCH_ENTERED,
        ACTIVE_TRIGGERED,
        TARGET_HIT,
        STOPPED_OUT,
        EXPIRED,
        REVERSED
    }

    public interface SignalListener {
        void onSignalEvent(TradingSignal signal, SignalEvent event);
    }
}
