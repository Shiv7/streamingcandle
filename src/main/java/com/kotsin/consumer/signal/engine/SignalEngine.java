package com.kotsin.consumer.signal.engine;

import com.kotsin.consumer.breakout.detector.BreakoutDetector;
import com.kotsin.consumer.breakout.model.BreakoutEvent;
import com.kotsin.consumer.breakout.model.BreakoutEvent.*;
import com.kotsin.consumer.event.CandleBoundaryEvent;
import com.kotsin.consumer.gate.GateChain;
import com.kotsin.consumer.gate.model.GateResult.ChainResult;
import com.kotsin.consumer.indicator.calculator.TechnicalIndicatorCalculator;
import com.kotsin.consumer.indicator.model.TechnicalIndicators;
import com.kotsin.consumer.logging.TraceContext;
import com.kotsin.consumer.model.StrategyState.*;
import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.model.MultiTimeframePivotState;
import com.kotsin.consumer.model.PivotLevels;
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
import com.kotsin.consumer.enrichment.QuantScoreProducer;
import com.kotsin.consumer.pattern.PatternAnalyzer;
import com.kotsin.consumer.pattern.PatternAnalyzer.PatternResult;
import com.kotsin.consumer.pattern.PatternSignalProducer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
    private com.kotsin.consumer.mtf.analyzer.MultiTimeframeAnalyzer mtfAnalyzer;

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

    @Autowired
    private com.kotsin.consumer.service.ATRService atrService;

    // ==================== BREAKOUT DETECTION ====================

    @Autowired
    private BreakoutDetector breakoutDetector;

    // Per-symbol breakout events cache (updated each processSymbol cycle)
    private final ConcurrentHashMap<String, List<BreakoutEvent>> symbolBreakoutEvents = new ConcurrentHashMap<>();

    // ==================== STRATEGY TRIGGERS ====================

    @Autowired
    private com.kotsin.consumer.signal.trigger.FudkiiSignalTrigger fudkiiTrigger;

    @Autowired
    private com.kotsin.consumer.signal.trigger.PivotConfluenceTrigger pivotConfluenceTrigger;

    @Autowired
    private com.kotsin.consumer.signal.trigger.MicroAlphaTrigger microAlphaTrigger;

    @Autowired
    private com.kotsin.consumer.indicator.calculator.BBSuperTrendCalculator bbstCalculator;

    // ==================== PATTERN & QUANT SCORE PRODUCERS ====================

    @Autowired
    private PatternAnalyzer patternAnalyzer;

    @Autowired
    private PatternSignalProducer patternSignalProducer;

    @Autowired
    private QuantScoreProducer quantScoreProducer;

    // ==================== OPTIONS INTEGRATION ====================

    @Autowired
    private com.kotsin.consumer.options.service.OptionStrikeSelector optionStrikeSelector;

    @Autowired
    private com.kotsin.consumer.options.service.ScripGroupService scripGroupService;

    @Autowired
    private com.kotsin.consumer.options.calculator.OptionsAnalyticsCalculator optionsAnalyticsCalculator;

    // ==================== DERIVATIVE DATA PIPELINE (MicroAlpha) ====================

    @Autowired
    private com.kotsin.consumer.repository.ScripGroupRepository scripGroupRepository;

    @Autowired
    private com.kotsin.consumer.repository.OIMetricsRepository oiMetricsRepository;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${signal.engine.enabled:true}")
    private boolean enabled;

    @Value("${signal.engine.kafka.topic:trading-signals-v2}")
    private String tradingSignalsTopic;

    @Value("${signal.engine.option.recommendations.topic:option-recommendations}")
    private String optionRecommendationsTopic;

    @Value("${signal.engine.option.selection.enabled:true}")
    private boolean optionSelectionEnabled;

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

    // Multi-timeframe pattern detection configuration
    @Value("${pattern.detection.timeframes:5m,15m,30m,1h,4h,1d}")
    private String patternDetectionTimeframes;

    @Value("${pattern.detection.mtf.enabled:true}")
    private boolean mtfPatternDetectionEnabled;

    @Value("${signal.watch.expiry.minutes:30}")
    private int watchExpiryMinutes;

    @Value("${signal.active.expiry.hours:4}")
    private int activeExpiryHours;

    // Active signals: symbol → strategyName → signal (allows concurrent strategies per symbol)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, TradingSignal>> activeSignals = new ConcurrentHashMap<>();

    // Processing state
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    // Signal listeners
    private final List<SignalListener> listeners = new CopyOnWriteArrayList<>();

    // Bootstrap status tracking (avoid noisy repeated logs)
    private volatile int lastLoggedBootstrapSuccess = -1;
    private volatile boolean loggedBootstrapReady = false;

    // Cache: equity scripCode → ScripGroup (refreshed hourly)
    // Bug #22: Bounded cache to prevent unbounded memory growth
    private static final int MAX_SCRIP_GROUP_CACHE_SIZE = 500;
    private final Map<String, com.kotsin.consumer.metadata.model.ScripGroup> scripGroupCache = new ConcurrentHashMap<>();
    private final Map<String, Long> scripGroupCacheTimestamp = new ConcurrentHashMap<>();
    private static final long SCRIP_GROUP_CACHE_TTL_MS = 3600_000; // 1 hour

    // Bug #20: Thread pool for parallel symbol processing
    private ExecutorService symbolProcessorPool;

    // Bug #28: Track bootstrap completion to skip per-symbol checks
    private volatile boolean bootstrapComplete = false;

    // Bug #11: Event-driven flag (set to false to revert to polling)
    @Value("${signal.engine.event.driven:true}")
    private boolean eventDriven;

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("{} Disabled by configuration", LOG_PREFIX);
            return;
        }

        log.info("{} Starting with dynamicSymbols={}, configuredSymbols={}, timeframe={}",
            LOG_PREFIX, useDynamicSymbols, symbolsConfig, primaryTimeframe);

        running.set(true);

        // Bug #20: Create thread pool for parallel symbol processing
        symbolProcessorPool = Executors.newFixedThreadPool(8);

        // Start processing scheduler
        scheduler = Executors.newScheduledThreadPool(2);

        if (eventDriven) {
            // Bug #11: Event-driven mode — no periodic polling of processAllSymbols
            // Signals are processed via onCandleBoundary() event listener
            log.info("{} Running in EVENT-DRIVEN mode (no 5s polling)", LOG_PREFIX);
        } else {
            // Fallback: poll-based mode for safety
            log.info("{} Running in POLL mode (every 5s)", LOG_PREFIX);
            scheduler.scheduleAtFixedRate(this::processAllSymbols, 30, 5, TimeUnit.SECONDS);
        }

        // Check active signals for exits every second (start after 35s)
        scheduler.scheduleAtFixedRate(this::checkActiveSignals, 35, 1, TimeUnit.SECONDS);

        log.info("{} Started successfully", LOG_PREFIX);
    }

    @PreDestroy
    public void stop() {
        log.info("{} Stopping...", LOG_PREFIX);
        running.set(false);

        if (symbolProcessorPool != null) {
            symbolProcessorPool.shutdown();
        }

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

        // Check bootstrap status — only log when status changes (not every 5s)
        var bootstrapStats = historicalDataBootstrapService.getStats();
        int currentSuccess = bootstrapStats.success();

        if (currentSuccess != lastLoggedBootstrapSuccess) {
            log.info("{} Bootstrap status: {} total, {} success, {} in-progress, {} failed",
                LOG_PREFIX, bootstrapStats.total(), currentSuccess,
                bootstrapStats.inProgress(), bootstrapStats.failed());
            lastLoggedBootstrapSuccess = currentSuccess;
        }

        if (currentSuccess == 0) {
            log.debug("{} No symbols bootstrapped yet, waiting...", LOG_PREFIX);
            return;
        }

        Set<String> symbols = getSymbolsToProcess();
        if (symbols.isEmpty()) {
            log.debug("{} No symbols to process", LOG_PREFIX);
            return;
        }

        // Bug #28: Once bootstrap is complete, skip per-symbol check
        Set<String> bootstrappedSymbols;
        if (bootstrapComplete) {
            bootstrappedSymbols = symbols;
        } else {
            bootstrappedSymbols = symbols.stream()
                .filter(s -> historicalDataBootstrapService.isBootstrapped(s))
                .collect(java.util.stream.Collectors.toSet());

            if (bootstrappedSymbols.isEmpty()) {
                log.debug("{} No bootstrapped symbols to process yet", LOG_PREFIX);
                return;
            }

            // Log "ready to trade" once when bootstrap is complete
            if (!loggedBootstrapReady && historicalDataBootstrapService.isBootstrapComplete()) {
                log.info("{} All bootstrap complete — processing {} symbols on timeframe={}",
                    LOG_PREFIX, bootstrappedSymbols.size(), primaryTimeframe);
                loggedBootstrapReady = true;
                bootstrapComplete = true;
            }
        }

        Timeframe tf = Timeframe.fromLabel(primaryTimeframe);
        long cycleStart = System.currentTimeMillis();

        // Bug #20: Process symbols in parallel
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String symbol : bootstrappedSymbols) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    processSymbol(symbol, tf);
                } catch (Exception e) {
                    log.error("{} Error processing symbol={}: {}", LOG_PREFIX, symbol, e.getMessage());
                }
            }, symbolProcessorPool));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long cycleMs = System.currentTimeMillis() - cycleStart;
        if (cycleMs > 2000) {
            log.warn("{} Slow processing cycle: {} symbols in {}ms ({}ms/symbol)",
                LOG_PREFIX, bootstrappedSymbols.size(), cycleMs,
                bootstrappedSymbols.isEmpty() ? 0 : cycleMs / bootstrappedSymbols.size());
        } else {
            log.debug("{} Processed {} symbols in {}ms", LOG_PREFIX, bootstrappedSymbols.size(), cycleMs);
        }
    }

    /**
     * Get scripCodes to process - either dynamically from Redis or from config.
     * Note: The variable names use "symbol" for backward compatibility but values are scripCodes.
     */
    private Set<String> getSymbolsToProcess() {
        if (useDynamicSymbols) {
            // Get all scripCodes that have candle data in Redis
            Set<String> cachedKeys = candleService.getAvailableScripCodes();
            if (cachedKeys == null || cachedKeys.isEmpty()) {
                log.debug("{} No scripCodes found in Redis cache", LOG_PREFIX);
                return Set.of();
            }

            // Extract scripCode from keys (format: tick:SCRIPCODE:1m:latest)
            Set<String> scripCodes = new HashSet<>();
            for (String key : cachedKeys) {
                String[] parts = key.split(":");
                if (parts.length >= 2) {
                    scripCodes.add(parts[1]);
                }
            }
            log.debug("{} Dynamic scripCodes discovered from Redis: {}", LOG_PREFIX, scripCodes.size());
            return scripCodes;
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
     * Event-driven processing for candle boundary events.
     * This is triggered when a timeframe boundary is crossed (e.g., 15m, 30m, 1h).
     * More efficient than poll-based as it only runs when needed.
     */
    @EventListener
    public void onCandleBoundary(CandleBoundaryEvent event) {
        if (!enabled || !running.get()) {
            return;
        }

        CandleBoundaryEvent.CandleBoundaryData data = event.getData();
        String scripCode = data.getScripCode();
        Timeframe timeframe = data.getTimeframe();

        // Skip if symbol not bootstrapped
        if (!historicalDataBootstrapService.isBootstrapped(scripCode)) {
            return;
        }

        log.debug("{} [EVENT] {} boundary crossed for {} at {}",
            LOG_PREFIX, timeframe.getLabel(), scripCode, data.getWindowEnd());

        // Bug #27: Actually process the symbol on primary timeframe boundaries
        Timeframe primaryTf = Timeframe.fromLabel(primaryTimeframe);
        if (timeframe == primaryTf) {
            // Process this symbol on the primary timeframe boundary
            CompletableFuture.runAsync(() -> {
                try {
                    processSymbol(scripCode, primaryTf);
                } catch (Exception e) {
                    log.error("{} Error processing symbol={} on {} boundary: {}",
                        LOG_PREFIX, scripCode, timeframe.getLabel(), e.getMessage());
                }
            }, symbolProcessorPool);
        }

        // Route to appropriate processing based on timeframe
        switch (timeframe) {
            case M15:
                log.debug("{} {} 15m boundary - LTF confirmation will be refreshed", LOG_PREFIX, scripCode);
                break;

            case M30:
                log.debug("{} {} 30m boundary - FUDKII trigger processed via onCandleClose", LOG_PREFIX, scripCode);
                break;

            case H1:
            case H4:
                log.info("{} {} {} boundary - HTF bias will be refreshed", LOG_PREFIX, scripCode, timeframe.getLabel());
                break;

            case D1:
                log.info("{} {} Daily close - full strategy refresh", LOG_PREFIX, scripCode);
                break;

            default:
                break;
        }
    }

    /**
     * Process a single symbol.
     */
    public void processSymbol(String symbol, Timeframe timeframe) {
        // Start trace context for this processing cycle
        String traceId = TraceContext.start(symbol, timeframe.getLabel());

        try {
            // CRITICAL: Skip processing if symbol not bootstrapped yet
            if (!historicalDataBootstrapService.isBootstrapped(symbol)) {
                log.debug("{} {} Skipping - not yet bootstrapped", LOG_PREFIX, symbol);
                TraceContext.clear();
                return;
            }

            log.debug("{} {} Processing started", LOG_PREFIX, TraceContext.getShortPrefix());

            // Get recent candles
            List<UnifiedCandle> candles = candleService.getCandleHistory(symbol, timeframe, 100);
            if (candles == null || candles.isEmpty()) {
                log.debug("{} {} No candles available, skipping", LOG_PREFIX, TraceContext.getShortPrefix());
                return;
            }

            // Bug #3 & #12 FIX: Safe access to candles list
            UnifiedCandle current = candles.get(0);
            if (current == null) {
                log.debug("{} {} First candle is null, skipping", LOG_PREFIX, TraceContext.getShortPrefix());
                return;
            }
            List<UnifiedCandle> history = candles.size() > 1 ? candles.subList(1, candles.size()) : List.of();

            log.debug("{} {} Candles loaded: count={}, price={}",
                LOG_PREFIX, TraceContext.getShortPrefix(),
                candles.size(), String.format("%.2f", current.getClose()));

            // Update session structure (exchange-aware for MCX/NSE)
            TraceContext.addStage("SESSION");
            String exchange = current.getExchange() != null ? current.getExchange() : "N";
            updateSessionStructure(symbol, current, exchange);

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

            // ==================== PATTERN ANALYSIS ====================
            TraceContext.addStage("PATTERN");
            // Pass the actual timeframe to pattern analyzer (was hardcoded to 5m before)
            PatternResult patternResult = patternAnalyzer.analyze(current, history, timeframe.getLabel());
            List<String> detectedPatterns = patternResult.getPatternNames();

            // Publish pattern signals if patterns detected
            if (patternResult.isHasHighConfidencePattern()) {
                patternSignalProducer.publish(patternResult);
            }

            // ==================== MULTI-TIMEFRAME PATTERN DETECTION ====================
            // Detect patterns on additional timeframes (15m, 30m, 1h, 4h, 1d)
            if (mtfPatternDetectionEnabled) {
                detectMTFPatterns(symbol, timeframe.getLabel());
            }

            // ==================== PUBLISH QUANT SCORE ====================
            TraceContext.addStage("QUANT_PUBLISH");
            quantScoreProducer.publish(
                current, score, vcpState, ipuState, pivotState, detectedPatterns);

            // ==================== PIVOT CONFLUENCE ANALYSIS ====================
            TraceContext.addStage("PIVOT_MTF");
            analyzePivotConfluence(symbol, current, candles, score);

            // ==================== SMC ANALYSIS (Order Blocks, FVG, Liquidity) ====================
            TraceContext.addStage("SMC");
            analyzeSMC(symbol, primaryTimeframe, candles, score);

            // ==================== MTF ANALYSIS (Multi-Timeframe) ====================
            TraceContext.addStage("MTF");
            analyzeMTF(symbol, candles, score);

            // ==================== BREAKOUT DETECTION ====================
            TraceContext.addStage("BREAKOUT_DETECTION");
            List<BreakoutEvent> breakoutEvents = detectBreakouts(symbol, current, candles, indicators);
            // Cache for gate chain and other components
            if (breakoutEvents != null && !breakoutEvents.isEmpty()) {
                symbolBreakoutEvents.put(symbol, breakoutEvents);
            } else {
                symbolBreakoutEvents.remove(symbol);
            }

            // ==================== STRATEGY 1: FUDKII TRIGGER (ST + BB on 30m) ====================
            TraceContext.addStage("FUDKII_TRIGGER");
            var fudkiiResult = checkFudkiiTrigger(symbol, current);

            // ==================== STRATEGY 2: PIVOT CONFLUENCE TRIGGER ====================
            TraceContext.addStage("PIVOT_TRIGGER");
            pivotConfluenceTrigger.setBreakoutContext(symbol, breakoutEvents);
            var pivotResult = checkPivotConfluenceTrigger(symbol, current);

            // ==================== STRATEGY 3: MICROALPHA TRIGGER (Microstructure Alpha) ====================
            TraceContext.addStage("MICROALPHA_TRIGGER");
            microAlphaTrigger.setLevelContext(symbol, breakoutEvents);
            var microAlphaResult = checkMicroAlphaTrigger(symbol, current, indicators);

            // Process all strategy triggers independently (concurrent signal tracking)
            TraceContext.addStage("STATE_MACHINE");
            processAllStrategyTriggers(symbol, current, score, pivotState,
                indicators, patternResult, fudkiiResult, pivotResult, microAlphaResult);

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
     * Update session structure from candle data (exchange-aware).
     */
    private void updateSessionStructure(String symbol, UnifiedCandle candle, String exchange) {
        try {
            sessionTracker.update(
                symbol,
                candle.getTimestamp(),
                candle.getOpen(),
                candle.getHigh(),
                candle.getLow(),
                candle.getClose(),
                candle.getVolume(),
                exchange  // Pass exchange for MCX/NSE-aware session tracking
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
     * Analyze Smart Money Concepts (Order Blocks, FVG, Liquidity Zones).
     * Boosts FUDKII score based on SMC alignment.
     */
    private void analyzeSMC(String symbol, String timeframe, List<UnifiedCandle> candles, FudkiiScore score) {
        try {
            if (candles == null || candles.size() < 5) {
                return;
            }

            // Convert UnifiedCandle to SMCAnalyzer.CandleData
            List<SMCAnalyzer.CandleData> smcCandles = new ArrayList<>();
            for (UnifiedCandle c : candles) {
                smcCandles.add(new SMCAnalyzer.CandleData(
                    c.getTimestamp(),
                    c.getOpen(),
                    c.getHigh(),
                    c.getLow(),
                    c.getClose(),
                    c.getVolume()
                ));
            }

            // Run SMC analysis
            SMCAnalyzer.SMCResult smcResult = smcAnalyzer.analyze(symbol, timeframe, smcCandles);

            if (smcResult == null) {
                return;
            }

            double currentPrice = candles.get(0).getClose();
            boolean isBullish = score.getDirection() == FudkiiScore.Direction.BULLISH;

            // Check for Order Block proximity
            List<com.kotsin.consumer.smc.model.OrderBlock> validOBs = smcAnalyzer.getValidOrderBlocks(symbol);
            for (com.kotsin.consumer.smc.model.OrderBlock ob : validOBs) {
                if (ob.isPriceInZone(currentPrice) || ob.isPriceNearZone(currentPrice, 0.5)) {
                    if (ob.isBullish() && isBullish) {
                        score.addBoost("BULLISH_OB", 15);
                        log.info("{} {} SMC: Price at BULLISH Order Block zone {}",
                            LOG_PREFIX, TraceContext.getShortPrefix(),
                            String.format("%.2f-%.2f", ob.getLow(), ob.getHigh()));
                    } else if (ob.isBearish() && !isBullish) {
                        score.addBoost("BEARISH_OB", 15);
                        log.info("{} {} SMC: Price at BEARISH Order Block zone {}",
                            LOG_PREFIX, TraceContext.getShortPrefix(),
                            String.format("%.2f-%.2f", ob.getLow(), ob.getHigh()));
                    }
                    break; // Only boost for one OB
                }
            }

            // Check for Fair Value Gap proximity
            List<com.kotsin.consumer.smc.model.FairValueGap> validFVGs = smcAnalyzer.getValidFairValueGaps(symbol);
            for (com.kotsin.consumer.smc.model.FairValueGap fvg : validFVGs) {
                if (fvg.isPriceInGap(currentPrice) || fvg.isPriceNearGap(currentPrice, 0.3)) {
                    if (fvg.isBullish() && isBullish) {
                        score.addBoost("BULLISH_FVG", 10);
                        log.info("{} {} SMC: Price at BULLISH FVG zone {}",
                            LOG_PREFIX, TraceContext.getShortPrefix(),
                            String.format("%.2f-%.2f", fvg.getLow(), fvg.getHigh()));
                    } else if (fvg.isBearish() && !isBullish) {
                        score.addBoost("BEARISH_FVG", 10);
                        log.info("{} {} SMC: Price at BEARISH FVG zone {}",
                            LOG_PREFIX, TraceContext.getShortPrefix(),
                            String.format("%.2f-%.2f", fvg.getLow(), fvg.getHigh()));
                    }
                    break; // Only boost for one FVG
                }
            }

            // Check for Liquidity sweep
            List<com.kotsin.consumer.smc.model.LiquidityZone> unsweptLZ = smcAnalyzer.getUnsweptLiquidityZones(symbol);
            for (com.kotsin.consumer.smc.model.LiquidityZone lz : unsweptLZ) {
                double distance = Math.abs(currentPrice - lz.getLevel()) / currentPrice * 100;
                if (distance < 0.5) { // Within 0.5% of liquidity level
                    if (lz.isBuySide() && isBullish) {
                        score.addBoost("BUY_SIDE_LIQ_TARGET", 8);
                        log.info("{} {} SMC: Buy-side liquidity target near {}",
                            LOG_PREFIX, TraceContext.getShortPrefix(),
                            String.format("%.2f", lz.getLevel()));
                    } else if (lz.isSellSide() && !isBullish) {
                        score.addBoost("SELL_SIDE_LIQ_TARGET", 8);
                        log.info("{} {} SMC: Sell-side liquidity target near {}",
                            LOG_PREFIX, TraceContext.getShortPrefix(),
                            String.format("%.2f", lz.getLevel()));
                    }
                    break;
                }
            }

            // Check market structure alignment
            SMCAnalyzer.MarketStructure ms = smcResult.getMarketStructure();
            if (ms != null) {
                if (ms.getTrend() == SMCAnalyzer.MarketStructure.Trend.BULLISH && isBullish) {
                    score.addBoost("SMC_TREND_ALIGN", 5);
                } else if (ms.getTrend() == SMCAnalyzer.MarketStructure.Trend.BEARISH && !isBullish) {
                    score.addBoost("SMC_TREND_ALIGN", 5);
                }
            }

        } catch (Exception e) {
            log.debug("{} {} SMC analysis failed: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), e.getMessage());
        }
    }

    /**
     * Detect patterns on multiple timeframes.
     * Supports: 5m, 15m, 30m, 1h, 2h, 4h, 1d
     */
    private void detectMTFPatterns(String symbol, String currentTimeframe) {
        if (patternDetectionTimeframes == null || patternDetectionTimeframes.isEmpty()) {
            return;
        }

        String[] timeframes = patternDetectionTimeframes.split(",");
        for (String tfLabel : timeframes) {
            tfLabel = tfLabel.trim();

            // Skip if same as current (already processed)
            if (tfLabel.equalsIgnoreCase(currentTimeframe)) {
                continue;
            }

            try {
                Timeframe tf = Timeframe.fromLabel(tfLabel);
                if (tf == null) {
                    log.debug("{} {} Unknown timeframe: {}", LOG_PREFIX, symbol, tfLabel);
                    continue;
                }

                // Get candles for this timeframe
                List<UnifiedCandle> candles = candleService.getCandleHistory(symbol, tf, 50);
                if (candles == null || candles.isEmpty()) {
                    continue;
                }

                UnifiedCandle current = candles.get(0);
                List<UnifiedCandle> history = candles.size() > 1 ? candles.subList(1, candles.size()) : List.of();

                // Analyze patterns for this timeframe
                PatternResult patternResult = patternAnalyzer.analyze(current, history, tfLabel);

                // Publish if high confidence patterns found
                if (patternResult.isHasHighConfidencePattern()) {
                    patternSignalProducer.publish(patternResult);
                    log.debug("{} {} [{}] MTF patterns detected: {}",
                        LOG_PREFIX, symbol, tfLabel, patternResult.getPatternNames());
                }

            } catch (Exception e) {
                log.debug("{} {} MTF pattern detection failed for {}: {}",
                    LOG_PREFIX, symbol, tfLabel, e.getMessage());
            }
        }
    }

    /**
     * Analyze Multi-Timeframe alignment.
     * Boosts FUDKII score based on MTF confluence.
     */
    private void analyzeMTF(String symbol, List<UnifiedCandle> candles, FudkiiScore score) {
        try {
            if (candles == null || candles.isEmpty()) {
                return;
            }

            UnifiedCandle current = candles.get(0);
            boolean isBullish = score.getDirection() == FudkiiScore.Direction.BULLISH;

            // Build MTF metrics from available data
            Map<String, com.kotsin.consumer.mtf.model.MultiTimeframeData.TimeframeMetrics> metricsMap = new HashMap<>();

            // Add current timeframe metrics
            com.kotsin.consumer.mtf.model.MultiTimeframeData.TimeframeMetrics m5Metrics =
                com.kotsin.consumer.mtf.model.MultiTimeframeData.TimeframeMetrics.builder()
                    .timeframe("5m")
                    .high(current.getHigh())
                    .low(current.getLow())
                    .close(current.getClose())
                    .rsi(50) // Default if not available
                    .macdHistogram(0)
                    .ema20(current.getClose())
                    .ema50(current.getClose())
                    .aboveEma20(true)
                    .aboveEma50(true)
                    .aboveVwap(true)
                    .superTrendBullish(score.getDirection() == FudkiiScore.Direction.BULLISH)
                    .build();
            metricsMap.put("5m", m5Metrics);

            // Run MTF analysis
            com.kotsin.consumer.mtf.model.MultiTimeframeData mtfData = mtfAnalyzer.analyze(symbol, metricsMap);

            if (mtfData == null) {
                return;
            }

            // Boost based on alignment
            com.kotsin.consumer.mtf.model.MultiTimeframeData.TrendAlignment alignment = mtfData.getOverallAlignment();
            if (alignment == com.kotsin.consumer.mtf.model.MultiTimeframeData.TrendAlignment.FULLY_ALIGNED) {
                score.addBoost("MTF_FULL_ALIGN", 15);
                log.info("{} {} MTF: Full alignment detected",
                    LOG_PREFIX, TraceContext.getShortPrefix());
            } else if (alignment == com.kotsin.consumer.mtf.model.MultiTimeframeData.TrendAlignment.MOSTLY_ALIGNED) {
                score.addBoost("MTF_PARTIAL_ALIGN", 8);
            }

            // Boost based on momentum alignment
            if (mtfData.isMomentumAligned()) {
                score.addBoost("MTF_MOMENTUM_ALIGN", 5);
            }

            // Boost based on level confluence
            if (mtfData.isHasLevelConfluence()) {
                score.addBoost("MTF_LEVEL_CONFLUENCE", 10);
                log.info("{} {} MTF: Level confluence detected",
                    LOG_PREFIX, TraceContext.getShortPrefix());
            }

            // Check signal matches direction
            com.kotsin.consumer.mtf.model.MultiTimeframeData.MTFSignal mtfSignal = mtfData.getSignal();
            if ((mtfSignal == com.kotsin.consumer.mtf.model.MultiTimeframeData.MTFSignal.STRONG_BUY ||
                 mtfSignal == com.kotsin.consumer.mtf.model.MultiTimeframeData.MTFSignal.BUY) && isBullish) {
                score.addBoost("MTF_SIGNAL_CONFIRM", 10);
            } else if ((mtfSignal == com.kotsin.consumer.mtf.model.MultiTimeframeData.MTFSignal.STRONG_SELL ||
                        mtfSignal == com.kotsin.consumer.mtf.model.MultiTimeframeData.MTFSignal.SELL) && !isBullish) {
                score.addBoost("MTF_SIGNAL_CONFIRM", 10);
            }

        } catch (Exception e) {
            log.debug("{} {} MTF analysis failed: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), e.getMessage());
        }
    }

    // ==================== BREAKOUT DETECTION ====================

    /**
     * Register key levels and detect breakouts/retests for a symbol.
     *
     * Flow:
     * 1. Load pivot levels (daily, weekly) and session levels (VWAP, OR)
     * 2. Register all levels into BreakoutDetector
     * 3. Call detect() to check for breakouts, retests, and failed breakouts
     * 4. Return events for downstream consumption by triggers
     */
    private List<BreakoutEvent> detectBreakouts(String symbol, UnifiedCandle current,
            List<UnifiedCandle> candles, TechnicalIndicators indicators) {
        try {
            String exch = current.getExchange() != null ? current.getExchange() : "N";
            String exchType = current.getExchangeType() != null ? current.getExchangeType() : "C";
            String scripCode = current.getScripCode() != null ? current.getScripCode() : symbol;

            // Register levels from pivots, SMC, and session structure
            registerLevelsForBreakoutDetection(scripCode, exch, exchType);

            // Calculate average volume for confirmation
            double avgVolume = 0;
            if (candles != null && candles.size() >= 20) {
                avgVolume = candles.stream().limit(20).mapToDouble(UnifiedCandle::getVolume).average().orElse(0);
            } else if (indicators != null && indicators.getAvgVolume20() > 0) {
                avgVolume = indicators.getAvgVolume20();
            }

            // Extract RSI and MACD for momentum confirmation
            Double rsi = indicators != null ? indicators.getRsi() : null;
            Double macdHist = indicators != null ? indicators.getMacdHistogram() : null;

            // Detect breakouts against registered levels
            List<BreakoutEvent> events = breakoutDetector.detect(
                scripCode, "5m",
                current.getHigh(), current.getLow(), current.getClose(),
                current.getVolume(), avgVolume,
                rsi, macdHist);

            if (events != null && !events.isEmpty()) {
                for (BreakoutEvent event : events) {
                    if (event.getType() == BreakoutType.RETEST && event.isRetestHeld()) {
                        log.info("{} {} RETEST DETECTED: {} at {} (quality={}, level={})",
                            LOG_PREFIX, TraceContext.getShortPrefix(),
                            event.getDirection(), String.format("%.2f", event.getBreakoutLevel()),
                            event.getRetestQuality(), event.getLevelDescription());
                    } else if (event.getType() == BreakoutType.BREAKOUT) {
                        log.info("{} {} BREAKOUT DETECTED: {} through {} (strength={}, vol={}x)",
                            LOG_PREFIX, TraceContext.getShortPrefix(),
                            event.getDirection(), String.format("%.2f", event.getBreakoutLevel()),
                            event.getStrength(), String.format("%.1f", event.getVolumeRatio()));
                    }
                }
            }

            return events != null ? events : List.of();
        } catch (Exception e) {
            log.debug("{} {} Breakout detection failed: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Register key price levels into BreakoutDetector for a symbol.
     * Sources: Daily/Weekly pivots, CPR, SMC order blocks, session levels (VWAP, OR).
     */
    private void registerLevelsForBreakoutDetection(String scripCode, String exch, String exchType) {
        // Clear old levels to re-register fresh
        breakoutDetector.clearLevels(scripCode);

        List<BreakoutDetector.LevelInfo> levels = new ArrayList<>();

        // 1. Register pivot levels
        try {
            Optional<MultiTimeframePivotState> pivotOpt = pivotLevelService.getOrLoadPivotLevels(scripCode, exch, exchType);
            if (pivotOpt.isPresent()) {
                MultiTimeframePivotState mtfPivots = pivotOpt.get();

                // Daily pivots
                if (mtfPivots.getDailyPivot() != null) {
                    com.kotsin.consumer.model.PivotLevels daily = mtfPivots.getDailyPivot();
                    addLevel(levels, daily.getS1(), LevelSource.SUPPORT, "Daily_S1");
                    addLevel(levels, daily.getS2(), LevelSource.SUPPORT, "Daily_S2");
                    addLevel(levels, daily.getR1(), LevelSource.RESISTANCE, "Daily_R1");
                    addLevel(levels, daily.getR2(), LevelSource.RESISTANCE, "Daily_R2");
                    addLevel(levels, daily.getPivot(), LevelSource.SUPPORT, "Daily_Pivot");
                    addLevel(levels, daily.getTc(), LevelSource.RESISTANCE, "Daily_TC");
                    addLevel(levels, daily.getBc(), LevelSource.SUPPORT, "Daily_BC");
                }

                // Weekly pivots
                if (mtfPivots.getWeeklyPivot() != null) {
                    com.kotsin.consumer.model.PivotLevels weekly = mtfPivots.getWeeklyPivot();
                    addLevel(levels, weekly.getS1(), LevelSource.SUPPORT, "Weekly_S1");
                    addLevel(levels, weekly.getR1(), LevelSource.RESISTANCE, "Weekly_R1");
                    addLevel(levels, weekly.getPivot(), LevelSource.SUPPORT, "Weekly_Pivot");
                }
            }
        } catch (Exception e) {
            log.debug("{} {} Failed to register pivot levels: {}",
                LOG_PREFIX, scripCode, e.getMessage());
        }

        // 2. Register SMC order blocks
        try {
            List<com.kotsin.consumer.smc.model.OrderBlock> orderBlocks = smcAnalyzer.getValidOrderBlocks(scripCode);
            for (com.kotsin.consumer.smc.model.OrderBlock ob : orderBlocks) {
                double midPrice = (ob.getHigh() + ob.getLow()) / 2;
                LevelSource source = LevelSource.ORDER_BLOCK;
                String desc = (ob.isBullish() ? "Bull_OB_" : "Bear_OB_") + String.format("%.0f", midPrice);
                addLevel(levels, midPrice, source, desc);
            }
        } catch (Exception e) {
            log.debug("{} {} Failed to register SMC levels: {}",
                LOG_PREFIX, scripCode, e.getMessage());
        }

        // 3. Register session levels (VWAP, Opening Range)
        try {
            SessionStructure session = sessionTracker.getSession(scripCode);
            if (session != null) {
                if (session.getVwap() > 0) {
                    addLevel(levels, session.getVwap(), LevelSource.VWAP, "VWAP");
                }
                if (session.isOpeningRangeComplete()) {
                    addLevel(levels, session.getOpeningRangeHigh30(), LevelSource.OPENING_RANGE, "OR_High_30m");
                    addLevel(levels, session.getOpeningRangeLow30(), LevelSource.OPENING_RANGE, "OR_Low_30m");
                }
            }
        } catch (Exception e) {
            log.debug("{} {} Failed to register session levels: {}",
                LOG_PREFIX, scripCode, e.getMessage());
        }

        // Register all collected levels
        if (!levels.isEmpty()) {
            breakoutDetector.registerLevels(scripCode, levels);
            log.debug("{} {} Registered {} levels for breakout detection",
                LOG_PREFIX, scripCode, levels.size());
        }
    }

    /**
     * Helper to add a level if valid (positive price).
     */
    private void addLevel(List<BreakoutDetector.LevelInfo> levels, double price,
                           LevelSource source, String description) {
        if (price > 0) {
            levels.add(new BreakoutDetector.LevelInfo(price, source, description));
        }
    }

    /**
     * Validate signal through gate chain (exchange-aware for MCX/NSE).
     */
    private ChainResult validateThroughGates(String symbol, FudkiiScore score,
                                              TechnicalIndicators indicators, double price,
                                              double target, double stopLoss,
                                              PatternResult patternResult, String exchange) {
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
                .exchange(exchange != null ? exchange : "N")  // MCX/NSE aware gate evaluation
                .custom("macdHistogram", indicators.getMacdHistogram())
                .custom("fudkiiScore", score)               // For QuantScoreGate
                .custom("patternResult", patternResult)     // For PatternGate
                .custom("breakoutEvents", symbolBreakoutEvents.get(symbol))  // For StructuralLevelGate
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
            TechnicalIndicators indicators,
            PatternResult patternResult) {

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

                // Validate through gate chain before activating (exchange-aware)
                ChainResult gateResult = validateThroughGates(
                    symbol, score, indicators, price, levels.target1, levels.stop, patternResult,
                    candle.getExchange());

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
                executePaperTrade(currentSignal, levels, "FUDKII");

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

    // ==================== STRATEGY TRIGGER METHODS ====================

    /**
     * Check Strategy 1: FUDKII trigger (SuperTrend flip + BB outside on 30m).
     */
    private com.kotsin.consumer.signal.trigger.FudkiiSignalTrigger.FudkiiTriggerResult checkFudkiiTrigger(
            String symbol, UnifiedCandle current) {
        try {
            log.debug("{} {} Checking FUDKII trigger (ST+BB on 30m)", LOG_PREFIX, TraceContext.getShortPrefix());

            var result = fudkiiTrigger.forceCheckTrigger(symbol);

            if (result.isTriggered()) {
                log.info("{} {} FUDKII TRIGGER FIRED: direction={}, reason={}",
                    LOG_PREFIX, TraceContext.getShortPrefix(),
                    result.getDirection(), result.getReason());
            } else {
                log.debug("{} {} FUDKII no trigger: {}", LOG_PREFIX, TraceContext.getShortPrefix(), result.getReason());
            }

            return result;
        } catch (Exception e) {
            log.warn("{} {} FUDKII trigger check failed: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), e.getMessage());
            return com.kotsin.consumer.signal.trigger.FudkiiSignalTrigger.FudkiiTriggerResult.noTrigger("Error: " + e.getMessage());
        }
    }

    /**
     * Check Strategy 2: Pivot Confluence trigger (HTF/LTF + Pivot + SMC + R:R).
     */
    private com.kotsin.consumer.signal.trigger.PivotConfluenceTrigger.PivotTriggerResult checkPivotConfluenceTrigger(
            String symbol, UnifiedCandle current) {
        try {
            log.debug("{} {} Checking Pivot Confluence trigger", LOG_PREFIX, TraceContext.getShortPrefix());

            String exch = current.getExchange() != null ? current.getExchange() : "N";
            String exchType = current.getExchangeType() != null ? current.getExchangeType() : "C";

            var result = pivotConfluenceTrigger.checkTrigger(symbol, exch, exchType);

            if (result.isTriggered()) {
                log.info("{} {} PIVOT CONFLUENCE TRIGGER FIRED: direction={}, score={}, R:R={}",
                    LOG_PREFIX, TraceContext.getShortPrefix(),
                    result.getDirection(),
                    String.format("%.1f", result.getScore()),
                    result.getRrCalc() != null ? String.format("%.2f", result.getRrCalc().getRiskReward()) : "N/A");
            } else {
                log.debug("{} {} Pivot Confluence no trigger: {}", LOG_PREFIX, TraceContext.getShortPrefix(), result.getReason());
            }

            return result;
        } catch (Exception e) {
            log.warn("{} {} Pivot Confluence trigger check failed: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), e.getMessage());
            return com.kotsin.consumer.signal.trigger.PivotConfluenceTrigger.PivotTriggerResult.noTrigger("Error: " + e.getMessage());
        }
    }

    /**
     * Check Strategy 3: MicroAlpha trigger (Microstructure Alpha Engine).
     *
     * Derivative Data Pipeline:
     * 1. Resolve equity scripCode → ScripGroup (equity + futures + options family)
     * 2. Enrich candle with FUT OI (cross-instrument: equity price + FUT OI)
     * 3. Fetch real options analytics from option chain (GEX, PCR, IV, max pain)
     * 4. Evaluate MicroAlpha with enriched derivative data
     */
    private com.kotsin.consumer.signal.trigger.MicroAlphaTrigger.MicroAlphaTriggerResult checkMicroAlphaTrigger(
            String symbol, UnifiedCandle current, TechnicalIndicators indicators) {
        try {
            log.debug("{} {} Checking MicroAlpha trigger for scripCode={}", LOG_PREFIX, TraceContext.getShortPrefix(), symbol);

            // --- Step 1: Resolve equity scripCode → ScripGroup ---
            com.kotsin.consumer.metadata.model.ScripGroup group = getScripGroup(symbol);
            String underlyingSymbol = null;
            if (group != null) {
                underlyingSymbol = group.getCompanyName();
                log.debug("{} {} MicroAlpha ScripGroup resolved: scripCode={} → underlying={}, futures={}, options={}",
                    LOG_PREFIX, TraceContext.getShortPrefix(), symbol, underlyingSymbol,
                    group.getFutures() != null ? group.getFutures().size() : 0,
                    group.getOptions() != null ? group.getOptions().size() : 0);
            } else {
                // Fallback: use candle symbol name
                underlyingSymbol = current.getSymbol() != null ? current.getSymbol() : symbol;
                log.debug("{} {} MicroAlpha no ScripGroup for scripCode={}, fallback to symbol={}",
                    LOG_PREFIX, TraceContext.getShortPrefix(), symbol, underlyingSymbol);
            }

            // --- Step 2: Enrich candle with FUT OI (cross-instrument lookup) ---
            boolean oiEnriched = enrichWithFuturesOI(current, group, symbol);
            if (oiEnriched) {
                log.debug("{} {} MicroAlpha FUT OI enriched: interp={}, oiChange={}%, velocity={}",
                    LOG_PREFIX, TraceContext.getShortPrefix(),
                    current.getOiInterpretation(),
                    String.format("%.2f", current.getOiChangePercent()),
                    String.format("%.2f", current.getOiVelocity() != null ? current.getOiVelocity() : 0.0));
            } else {
                log.debug("{} {} MicroAlpha no FUT OI available for {}",
                    LOG_PREFIX, TraceContext.getShortPrefix(), underlyingSymbol);
            }

            // --- Step 3: Fetch real options analytics from option chain ---
            com.kotsin.consumer.options.model.OptionsAnalytics options = fetchOptionsAnalytics(underlyingSymbol, current.getClose());
            if (options != null && options.getPcrByOI() > 0) {
                log.debug("{} {} MicroAlpha options loaded: PCR={}, maxPain={}, GEX={}, callWall={}, putWall={}",
                    LOG_PREFIX, TraceContext.getShortPrefix(),
                    String.format("%.2f", options.getPcrByOI()),
                    String.format("%.0f", options.getMaxPain()),
                    String.format("%.0f", options.getTotalGEX()),
                    String.format("%.0f", options.getCallOIWall()),
                    String.format("%.0f", options.getPutOIWall()));
            } else {
                log.debug("{} {} MicroAlpha no option chain for {}",
                    LOG_PREFIX, TraceContext.getShortPrefix(), underlyingSymbol);
            }

            // --- Step 4: Evaluate MicroAlpha with enriched data ---
            var result = microAlphaTrigger.evaluate(symbol, current, indicators, options);

            if (result.isTriggered()) {
                log.info("{} {} MICROALPHA TRIGGER FIRED: direction={}, conviction={}, mode={}, reason={}",
                    LOG_PREFIX, TraceContext.getShortPrefix(),
                    result.getDirection(),
                    String.format("%.1f", result.getConviction()),
                    result.getTradingMode(),
                    result.getReason());
            } else {
                log.debug("{} {} MicroAlpha no trigger: {}",
                    LOG_PREFIX, TraceContext.getShortPrefix(), result.getReason());
            }

            return result;
        } catch (Exception e) {
            log.warn("{} {} MicroAlpha trigger check failed: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), e.getMessage());
            return com.kotsin.consumer.signal.trigger.MicroAlphaTrigger.MicroAlphaTriggerResult.noTrigger("Error: " + e.getMessage());
        }
    }

    // ==================== MICROALPHA DERIVATIVE DATA HELPERS ====================

    /**
     * Get ScripGroup for equity scripCode (cached, 1hr TTL).
     * ScripGroup maps equity → futures[] + options[] in one document.
     */
    private com.kotsin.consumer.metadata.model.ScripGroup getScripGroup(String equityScripCode) {
        // Check cache
        Long cachedAt = scripGroupCacheTimestamp.get(equityScripCode);
        if (cachedAt != null && (System.currentTimeMillis() - cachedAt) < SCRIP_GROUP_CACHE_TTL_MS) {
            return scripGroupCache.get(equityScripCode); // may be null (no ScripGroup exists)
        }

        try {
            com.kotsin.consumer.metadata.model.ScripGroup group =
                scripGroupRepository.findByEquityScripCode(equityScripCode);

            // Cache result (including null for negative caching)
            scripGroupCacheTimestamp.put(equityScripCode, System.currentTimeMillis());
            if (group != null) {
                // Bug #22: Evict oldest entries when cache is full
                if (scripGroupCache.size() >= MAX_SCRIP_GROUP_CACHE_SIZE) {
                    String oldest = scripGroupCacheTimestamp.entrySet().stream()
                        .min(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey).orElse(null);
                    if (oldest != null) {
                        scripGroupCache.remove(oldest);
                        scripGroupCacheTimestamp.remove(oldest);
                    }
                }
                scripGroupCache.put(equityScripCode, group);
                log.info("{} ScripGroup cached: scripCode={} → {} (futures={}, options={})",
                    LOG_PREFIX, equityScripCode, group.getCompanyName(),
                    group.getFutures() != null ? group.getFutures().size() : 0,
                    group.getOptions() != null ? group.getOptions().size() : 0);
            } else {
                scripGroupCache.remove(equityScripCode);
                log.debug("{} No ScripGroup found for scripCode={}", LOG_PREFIX, equityScripCode);
            }

            return group;
        } catch (Exception e) {
            log.warn("{} ScripGroup lookup failed for {}: {}", LOG_PREFIX, equityScripCode, e.getMessage());
            scripGroupCacheTimestamp.put(equityScripCode, System.currentTimeMillis());
            return null;
        }
    }

    /**
     * Enrich equity UnifiedCandle with Futures OI data from ScripGroup.
     *
     * Flow: ScripGroup.futures[0].ScripCode → OIMetricsRepository → enrich candle
     * OI interpretation is recalculated against equity price change (not FUT price change).
     */
    private boolean enrichWithFuturesOI(UnifiedCandle candle,
                                         com.kotsin.consumer.metadata.model.ScripGroup group,
                                         String equityScripCode) {
        try {
            if (group == null || group.getFutures() == null || group.getFutures().isEmpty()) {
                return false;
            }

            // Get nearest-month FUT scripCode from ScripGroup
            // ScripGroup already filters to current month, pick first (nearest expiry)
            String futScripCode = null;
            String futExpiry = null;
            String today = java.time.LocalDate.now().toString();
            for (com.kotsin.consumer.metadata.model.Scrip fut : group.getFutures()) {
                String expiry = fut.getExpiry();
                String sc = fut.getScripCode();
                if (sc != null && expiry != null && expiry.compareTo(today) >= 0) {
                    if (futScripCode == null || expiry.compareTo(futExpiry) < 0) {
                        futScripCode = sc;
                        futExpiry = expiry;
                    }
                }
            }
            // Fallback: if all expired, use first available
            if (futScripCode == null && !group.getFutures().isEmpty()) {
                com.kotsin.consumer.metadata.model.Scrip first = group.getFutures().get(0);
                futScripCode = first.getScripCode();
                futExpiry = first.getExpiry();
            }
            if (futScripCode == null) {
                return false;
            }

            log.debug("{} {} FUT lookup: {} → FUT scripCode={} (expiry={})",
                LOG_PREFIX, TraceContext.getShortPrefix(), group.getCompanyName(), futScripCode, futExpiry);

            // Fetch latest FUT OI from MongoDB
            com.kotsin.consumer.model.OIMetrics futOI = oiMetricsRepository
                .findTopByScripCodeOrderByTimestampDesc(futScripCode)
                .orElse(null);

            if (futOI == null || futOI.getOpenInterest() <= 0) {
                log.debug("{} {} No FUT OI data for scripCode={}", LOG_PREFIX, TraceContext.getShortPrefix(), futScripCode);
                return false;
            }

            // Check staleness (OI data older than 5 min is stale)
            if (futOI.getTimestamp() != null &&
                futOI.getTimestamp().isBefore(Instant.now().minus(5, java.time.temporal.ChronoUnit.MINUTES))) {
                long ageSec = Duration.between(futOI.getTimestamp(), Instant.now()).getSeconds();
                log.debug("{} {} FUT OI stale for {} (age={}s, scripCode={})",
                    LOG_PREFIX, TraceContext.getShortPrefix(), group.getCompanyName(), ageSec, futScripCode);
                return false;
            }

            // Recalculate OI interpretation against EQUITY price change (not FUT price)
            double priceChange = 0;
            if (candle.getOpen() > 0) {
                priceChange = (candle.getClose() - candle.getOpen()) / candle.getOpen() * 100.0;
            }

            com.kotsin.consumer.model.OIMetrics.OIInterpretation interp =
                com.kotsin.consumer.model.OIMetrics.OIInterpretation.determine(
                    priceChange, futOI.getOiChangePercent(), 0.1, 0.5);

            double priceConfidence = Math.min(1.0, Math.abs(priceChange) / 1.0);
            double oiConfidence = Math.min(1.0, Math.abs(futOI.getOiChangePercent()) / 5.0);
            double confidence = (priceConfidence + oiConfidence) / 2.0;

            // Build enriched OIMetrics with equity-aware interpretation
            com.kotsin.consumer.model.OIMetrics enriched = com.kotsin.consumer.model.OIMetrics.builder()
                .symbol(group.getCompanyName())
                .scripCode(futScripCode)
                .openInterest(futOI.getOpenInterest())
                .oiChange(futOI.getOiChange())
                .oiChangePercent(futOI.getOiChangePercent())
                .interpretation(interp)
                .interpretationConfidence(confidence)
                .suggestsReversal(interp.suggestsExhaustion())
                .oiVelocity(futOI.getOiVelocity())
                .oiAcceleration(futOI.getOiAcceleration())
                .timestamp(futOI.getTimestamp())
                .build();

            // Enrich the equity candle with FUT OI
            candle.withOI(enriched);

            log.debug("{} {} FUT OI enriched for {}: FUT={}, OI={}, change={}%, interp={}, confidence={}",
                LOG_PREFIX, TraceContext.getShortPrefix(), group.getCompanyName(),
                futScripCode, futOI.getOpenInterest(),
                String.format("%.2f", futOI.getOiChangePercent()),
                interp, String.format("%.2f", confidence));

            return true;
        } catch (Exception e) {
            log.debug("{} {} FUT OI enrichment failed for scripCode={}: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), equityScripCode, e.getMessage());
            return false;
        }
    }

    /**
     * Fetch real options analytics for underlying using OptionsAnalyticsCalculator.
     * Uses calculateFromChainOnly() which queries MongoDB for real OI data and calculates
     * GEX, PCR, IV, max pain from the actual option chain.
     */
    private com.kotsin.consumer.options.model.OptionsAnalytics fetchOptionsAnalytics(
            String underlyingSymbol, double spotPrice) {
        try {
            // Check if underlying has options data via ScripGroupService
            if (!scripGroupService.hasDerivatives(underlyingSymbol)) {
                return null;
            }

            // Use calculateFromChainOnly - queries MongoDB OI data directly
            com.kotsin.consumer.options.model.OptionsAnalytics analytics =
                optionsAnalyticsCalculator.calculateFromChainOnly(underlyingSymbol);

            if (analytics == null) {
                return null;
            }

            // Validate we got real data (not just a timestamp-only stub)
            if (analytics.getPcrByOI() <= 0 && analytics.getMaxPain() <= 0) {
                log.debug("{} {} Option chain empty for {} — no strikes with OI",
                    LOG_PREFIX, TraceContext.getShortPrefix(), underlyingSymbol);
                return null;
            }

            return analytics;
        } catch (Exception e) {
            log.debug("{} {} Options analytics failed for {}: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), underlyingSymbol, e.getMessage());
            return null;
        }
    }

    /**
     * Process ALL strategy triggers independently (no priority chain).
     * Each strategy gets its own slot — FUDKII, PIVOT, and MICROALPHA can all be active simultaneously.
     */
    private void processAllStrategyTriggers(
            String symbol,
            UnifiedCandle candle,
            FudkiiScore score,
            PivotState pivotState,
            TechnicalIndicators indicators,
            PatternResult patternResult,
            com.kotsin.consumer.signal.trigger.FudkiiSignalTrigger.FudkiiTriggerResult fudkiiResult,
            com.kotsin.consumer.signal.trigger.PivotConfluenceTrigger.PivotTriggerResult pivotResult,
            com.kotsin.consumer.signal.trigger.MicroAlphaTrigger.MicroAlphaTriggerResult microAlphaResult) {

        double price = candle.getClose();

        // Check regime - avoid trading in AVOID mode
        MarketRegime regime = regimeDetector.getCurrentRegime(symbol);
        if (regime != null && regime.getRecommendedMode() == TradingMode.AVOID) {
            log.debug("{} {} Skipping - market regime suggests AVOID",
                LOG_PREFIX, TraceContext.getShortPrefix());
            return;
        }

        // Get or create per-symbol strategy map
        ConcurrentHashMap<String, TradingSignal> symbolSignals =
            activeSignals.computeIfAbsent(symbol, k -> new ConcurrentHashMap<>());

        // Strategy 1: FUDKII (ST flip + BB outside on 30m) — independent
        if (fudkiiResult != null && fudkiiResult.isTriggered() && !symbolSignals.containsKey("FUDKII")) {
            log.info("{} {} STRATEGY 1 (FUDKII) ACTIVATED: {} at {}",
                LOG_PREFIX, TraceContext.getShortPrefix(),
                fudkiiResult.getDirection(), String.format("%.2f", price));

            TradingSignal signal = createAndActivateSignal(
                symbol, candle, score, pivotState, indicators, patternResult,
                "FUDKII", fudkiiResult.getDirection().name(),
                fudkiiResult.getReason(),
                fudkiiResult.getBbst() != null ? fudkiiResult.getBbst().getSuperTrend() : price * 0.99
            );
            if (signal != null && signal.getState().isActive()) {
                symbolSignals.put("FUDKII", signal);
            }
        }

        // Strategy 2: Pivot Confluence (HTF/LTF + Pivot + SMC + R:R) — independent
        if (pivotResult != null && pivotResult.isTriggered() && !symbolSignals.containsKey("PIVOT_CONFLUENCE")) {
            log.info("{} {} STRATEGY 2 (PIVOT CONFLUENCE) ACTIVATED: {} at {}, score={}",
                LOG_PREFIX, TraceContext.getShortPrefix(),
                pivotResult.getDirection(), String.format("%.2f", price),
                String.format("%.1f", pivotResult.getScore()));

            double stopLoss = pivotResult.getRrCalc() != null ?
                pivotResult.getRrCalc().getStopLoss() : price * 0.99;

            TradingSignal signal = createAndActivateSignal(
                symbol, candle, score, pivotState, indicators, patternResult,
                "PIVOT_CONFLUENCE", pivotResult.getDirection().name(),
                pivotResult.getReason(), stopLoss
            );
            if (signal != null && signal.getState().isActive()) {
                symbolSignals.put("PIVOT_CONFLUENCE", signal);
            }
        }

        // Strategy 3: MicroAlpha (Microstructure Alpha - regime-adaptive) — independent
        if (microAlphaResult != null && microAlphaResult.isTriggered() && !symbolSignals.containsKey("MICROALPHA")) {
            log.info("{} {} STRATEGY 3 (MICROALPHA) ACTIVATED: {} at {}, conviction={}, mode={}",
                LOG_PREFIX, TraceContext.getShortPrefix(),
                microAlphaResult.getDirection(), String.format("%.2f", price),
                String.format("%.1f", microAlphaResult.getConviction()),
                microAlphaResult.getTradingMode());

            double stopLoss = microAlphaResult.getStopLoss() > 0 ?
                microAlphaResult.getStopLoss() : price * 0.99;

            TradingSignal signal = createAndActivateSignal(
                symbol, candle, score, pivotState, indicators, patternResult,
                "MICROALPHA", microAlphaResult.getDirection().name(),
                microAlphaResult.getReason(), stopLoss
            );
            if (signal != null && signal.getState().isActive()) {
                symbolSignals.put("MICROALPHA", signal);
            }
        }

        // Fallback: if no strategy triggered and no active signals, use original state machine
        if (symbolSignals.isEmpty()) {
            TradingSignal fallbackSignal = processStateMachine(
                symbol, candle, score, null, pivotState, indicators, patternResult);
            if (fallbackSignal != null && fallbackSignal.getState().isActive()) {
                symbolSignals.put("FUDKII_SCORE", fallbackSignal);
            }
        }

        // Clean up empty symbol entries
        if (symbolSignals.isEmpty()) {
            activeSignals.remove(symbol);
        }
    }

    /**
     * Create and immediately activate a signal from strategy trigger.
     */
    private TradingSignal createAndActivateSignal(
            String symbol, UnifiedCandle candle, FudkiiScore score,
            PivotState pivotState, TechnicalIndicators indicators, PatternResult patternResult,
            String strategyName, String direction, String reason, double suggestedStop) {

        double price = candle.getClose();
        boolean bullish = "BULLISH".equals(direction);

        // Create signal
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

        // Set direction on score if needed
        FudkiiScore adjustedScore = score;
        if (bullish && score.getDirection() != FudkiiScore.Direction.BULLISH) {
            adjustedScore = FudkiiScore.builder()
                .compositeScore(score.getCompositeScore())
                .direction(FudkiiScore.Direction.BULLISH)
                .confidence(score.getConfidence())
                .isWatchSetup(true)
                .isActiveTrigger(true)
                .reason(reason)
                .build();
        } else if (!bullish && score.getDirection() != FudkiiScore.Direction.BEARISH) {
            adjustedScore = FudkiiScore.builder()
                .compositeScore(score.getCompositeScore())
                .direction(FudkiiScore.Direction.BEARISH)
                .confidence(score.getConfidence())
                .isWatchSetup(true)
                .isActiveTrigger(true)
                .reason(reason)
                .build();
        }

        // Enter WATCH state
        signal.enterWatch(adjustedScore, price, watchExpiryMinutes);

        // Get MTF pivot state for proper pivot-to-pivot distance calculation
        MultiTimeframePivotState mtfPivotState = null;
        try {
            String scripCode = candle.getScripCode();
            String exchange = candle.getExchange();
            if (scripCode != null && exchange != null) {
                mtfPivotState = pivotLevelService.getOrLoadPivotLevels(scripCode, exchange, "C").orElse(null);
            }
        } catch (Exception e) {
            log.warn("{} Failed to get MTF pivot state for {}: {}", LOG_PREFIX, symbol, e.getMessage());
        }

        // Set symbol on score for ATR lookup
        FudkiiScore scoreWithSymbol = FudkiiScore.builder()
            .symbol(candle.getScripCode())
            .compositeScore(adjustedScore.getCompositeScore())
            .direction(adjustedScore.getDirection())
            .confidence(adjustedScore.getConfidence())
            .isWatchSetup(adjustedScore.isWatchSetup())
            .isActiveTrigger(adjustedScore.isActiveTrigger())
            .reason(adjustedScore.getReason())
            .build();

        // Calculate entry levels using proper pivot-to-pivot distances
        EntryLevels levels = calculateEntryLevels(scoreWithSymbol, pivotState, price, mtfPivotState);

        // Override stop if strategy provided a specific stop (e.g., from pattern)
        if (suggestedStop > 0 && suggestedStop != price) {
            // Validate suggested stop is reasonable (at least 1% away)
            double suggestedDistance = Math.abs(price - suggestedStop);
            double minDistance = price * 0.005; // At least 0.5%
            if (suggestedDistance >= minDistance) {
                levels.stop = suggestedStop;
                // Recalculate targets based on new stop
                double risk = Math.abs(levels.entry - levels.stop);
                if (bullish) {
                    levels.target1 = levels.entry + (risk * 2);
                    levels.target2 = levels.entry + (risk * 3);
                } else {
                    levels.target1 = levels.entry - (risk * 2);
                    levels.target2 = levels.entry - (risk * 3);
                }
                log.info("{} {} {} Using strategy-suggested stop: {}", LOG_PREFIX, TraceContext.getShortPrefix(),
                    strategyName, String.format("%.2f", suggestedStop));
            }
        }

        // Log detailed entry levels
        double risk = Math.abs(levels.entry - levels.stop);
        log.info("{} {} {} Entry Levels: entry={}, stop={}, T1={}, T2={}, R:R={}, Risk%={}%",
            LOG_PREFIX, TraceContext.getShortPrefix(), strategyName,
            String.format("%.2f", levels.entry),
            String.format("%.2f", levels.stop),
            String.format("%.2f", levels.target1),
            String.format("%.2f", levels.target2),
            String.format("%.2f", Math.abs(levels.target1 - levels.entry) / risk),
            String.format("%.2f", (risk / levels.entry) * 100));

        // Gate chain validation (applies to all strategies)
        ChainResult gateResult = validateThroughGates(symbol, adjustedScore, indicators,
            price, levels.target1, levels.stop, patternResult,
            candle.getExchange());
        if (gateResult != null && !gateResult.isPassed()) {
            log.info("{} {} {} Signal blocked by gates: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), strategyName, gateResult.getFailureReason());
            return null; // Signal rejected by gates
        }
        if (gateResult != null) {
            log.info("{} {} {} Gate score: {} ({})", LOG_PREFIX, TraceContext.getShortPrefix(),
                strategyName, String.format("%.1f", gateResult.getTotalScore()),
                gateResult.isHighQuality() ? "HIGH_QUALITY" : "STANDARD");
        }

        // Immediately activate
        signal.enterActive(adjustedScore, price, levels.entry, levels.stop, levels.target1, levels.target2);

        // Record to stats
        recordSignalToStats(signal, adjustedScore);

        // Execute paper trade if enabled
        if (paperTradeEnabled) {
            executePaperTrade(signal, levels, strategyName);
        }

        // Save and notify
        saveAndNotify(signal, SignalEvent.ACTIVE_TRIGGERED);

        log.info("{} {} {} SIGNAL ACTIVATED: {} {} at {} | Stop: {} | Target: {}",
            LOG_PREFIX, TraceContext.getShortPrefix(), strategyName,
            direction, symbol,
            String.format("%.2f", price),
            String.format("%.2f", levels.stop),
            String.format("%.2f", levels.target1));

        return signal;
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
     * Calculate entry, stop, and target levels using proper pivot-to-pivot distances.
     *
     * FIX: Uses actual pivot levels from MultiTimeframePivotState for realistic stop/target.
     * Validates against ATR to avoid stops that are too tight for normal volatility.
     */
    private EntryLevels calculateEntryLevels(FudkiiScore score, PivotState pivotState, double price) {
        return calculateEntryLevels(score, pivotState, price, null);
    }

    /**
     * Calculate entry levels with full pivot state for proper pivot-to-pivot distances.
     */
    private EntryLevels calculateEntryLevels(FudkiiScore score, PivotState pivotState, double price,
                                              MultiTimeframePivotState mtfPivotState) {
        EntryLevels levels = new EntryLevels();
        boolean bullish = score.getDirection() == FudkiiScore.Direction.BULLISH;
        levels.entry = price;

        // Get ATR for minimum stop distance validation (use 15m ATR)
        String scripCode = score.getSymbol() != null ? score.getSymbol() : "UNKNOWN";
        com.kotsin.consumer.service.ATRService.ATRData atrData = atrService != null ?
            atrService.getATR(scripCode, Timeframe.M15) : null;
        double atr = atrData != null ? atrData.getAtr() : price * 0.01; // Fallback 1%
        double minStopDistance = atr * 1.5; // Minimum 1.5x ATR for stop

        // ==================== STOP LOSS CALCULATION ====================
        // Priority: 1) MTF Pivot levels, 2) PivotState levels, 3) ATR-based

        double stopFromPivot = 0;
        String stopSource = "DEFAULT";

        if (mtfPivotState != null) {
            // Use actual pivot levels from Daily/Weekly/Monthly
            PivotLevels dailyPivot = mtfPivotState.getDailyPivot();
            PivotLevels weeklyPivot = mtfPivotState.getWeeklyPivot();

            if (bullish) {
                // LONG: Stop below nearest support pivot
                double bestSupport = findBestSupportForStop(price, dailyPivot, weeklyPivot, minStopDistance);
                if (bestSupport > 0) {
                    stopFromPivot = bestSupport * 0.998; // Slightly below the support
                    stopSource = "PIVOT_SUPPORT";
                }
            } else {
                // SHORT: Stop above nearest resistance pivot
                double bestResistance = findBestResistanceForStop(price, dailyPivot, weeklyPivot, minStopDistance);
                if (bestResistance > 0) {
                    stopFromPivot = bestResistance * 1.002; // Slightly above the resistance
                    stopSource = "PIVOT_RESISTANCE";
                }
            }
        }

        // Fallback to PivotState (swing levels) if no MTF pivot found
        if (stopFromPivot == 0 && pivotState != null) {
            if (bullish && pivotState.getSupportLevels() != null && !pivotState.getSupportLevels().isEmpty()) {
                PriceLevel nearestSupport = pivotState.getSupportLevels().get(0);
                if (nearestSupport != null && nearestSupport.getPrice() < price) {
                    double distance = price - nearestSupport.getPrice();
                    if (distance >= minStopDistance) {
                        stopFromPivot = nearestSupport.getPrice() * 0.998;
                        stopSource = "SWING_SUPPORT";
                    }
                }
            } else if (!bullish && pivotState.getResistanceLevels() != null && !pivotState.getResistanceLevels().isEmpty()) {
                PriceLevel nearestResistance = pivotState.getResistanceLevels().get(0);
                if (nearestResistance != null && nearestResistance.getPrice() > price) {
                    double distance = nearestResistance.getPrice() - price;
                    if (distance >= minStopDistance) {
                        stopFromPivot = nearestResistance.getPrice() * 1.002;
                        stopSource = "SWING_RESISTANCE";
                    }
                }
            }
        }

        // Final stop: use pivot-based if valid, otherwise ATR-based
        if (stopFromPivot > 0) {
            levels.stop = stopFromPivot;
        } else {
            // ATR-based stop (2x ATR for more room)
            levels.stop = bullish ? price - (atr * 2.0) : price + (atr * 2.0);
            stopSource = "ATR_BASED";
        }

        // Validate stop distance is reasonable (at least 1x ATR)
        double actualStopDistance = Math.abs(levels.entry - levels.stop);
        if (actualStopDistance < atr) {
            // Stop too tight, use ATR-based
            levels.stop = bullish ? price - (atr * 1.5) : price + (atr * 1.5);
            stopSource = "ATR_ADJUSTED";
            actualStopDistance = atr * 1.5;
        }

        // ==================== TARGET CALCULATION ====================
        // Priority: 1) Next pivot level, 2) Risk-multiple based

        double targetFromPivot = 0;
        double target2FromPivot = 0;
        String targetSource = "DEFAULT";

        if (mtfPivotState != null) {
            PivotLevels dailyPivot = mtfPivotState.getDailyPivot();
            PivotLevels weeklyPivot = mtfPivotState.getWeeklyPivot();

            if (bullish) {
                // LONG: Target at next resistance pivot
                double[] targets = findResistanceTargets(price, dailyPivot, weeklyPivot, actualStopDistance);
                if (targets[0] > 0) {
                    targetFromPivot = targets[0];
                    target2FromPivot = targets[1] > 0 ? targets[1] : targets[0] * 1.02;
                    targetSource = "PIVOT_RESISTANCE";
                }
            } else {
                // SHORT: Target at next support pivot
                double[] targets = findSupportTargets(price, dailyPivot, weeklyPivot, actualStopDistance);
                if (targets[0] > 0) {
                    targetFromPivot = targets[0];
                    target2FromPivot = targets[1] > 0 ? targets[1] : targets[0] * 0.98;
                    targetSource = "PIVOT_SUPPORT";
                }
            }
        }

        // Use pivot targets if they provide at least 1.5R
        if (targetFromPivot > 0) {
            double potentialReward = Math.abs(targetFromPivot - levels.entry);
            double rr = potentialReward / actualStopDistance;
            if (rr >= 1.5) {
                levels.target1 = targetFromPivot;
                levels.target2 = target2FromPivot;
            } else {
                // Pivot target too close, use R-multiple
                levels.target1 = bullish ? levels.entry + (actualStopDistance * 2) : levels.entry - (actualStopDistance * 2);
                levels.target2 = bullish ? levels.entry + (actualStopDistance * 3) : levels.entry - (actualStopDistance * 3);
                targetSource = "R_MULTIPLE";
            }
        } else {
            // No pivot target found, use R-multiple
            levels.target1 = bullish ? levels.entry + (actualStopDistance * 2) : levels.entry - (actualStopDistance * 2);
            levels.target2 = bullish ? levels.entry + (actualStopDistance * 3) : levels.entry - (actualStopDistance * 3);
            targetSource = "R_MULTIPLE";
        }

        // Log the calculation details
        double finalRR = Math.abs(levels.target1 - levels.entry) / actualStopDistance;
        log.info("{} {} Entry Levels Calculated: entry={}, stop={} ({}), T1={} ({}), T2={}, R:R={}, ATR={}",
            LOG_PREFIX, scripCode,
            String.format("%.2f", levels.entry),
            String.format("%.2f", levels.stop), stopSource,
            String.format("%.2f", levels.target1), targetSource,
            String.format("%.2f", levels.target2),
            String.format("%.2f", finalRR), String.format("%.2f", atr));

        return levels;
    }

    /**
     * Find the best support level for stop placement.
     * Returns a support that gives at least minDistance from price.
     */
    private double findBestSupportForStop(double price, PivotLevels daily, PivotLevels weekly, double minDistance) {
        List<Double> supports = new ArrayList<>();

        if (daily != null) {
            if (daily.getS1() > 0 && daily.getS1() < price) supports.add(daily.getS1());
            if (daily.getS2() > 0 && daily.getS2() < price) supports.add(daily.getS2());
            if (daily.getS3() > 0 && daily.getS3() < price) supports.add(daily.getS3());
            if (daily.getBc() > 0 && daily.getBc() < price) supports.add(daily.getBc());
            if (daily.getCamS3() > 0 && daily.getCamS3() < price) supports.add(daily.getCamS3());
        }
        if (weekly != null) {
            if (weekly.getS1() > 0 && weekly.getS1() < price) supports.add(weekly.getS1());
            if (weekly.getPivot() > 0 && weekly.getPivot() < price) supports.add(weekly.getPivot());
        }

        // Sort descending (nearest first)
        supports.sort((a, b) -> Double.compare(b, a));

        // Find the nearest support that gives at least minDistance
        for (double support : supports) {
            double distance = price - support;
            if (distance >= minDistance && distance <= price * 0.05) { // Max 5% stop
                return support;
            }
        }

        // If none found with minDistance, return the nearest one (might be tighter)
        if (!supports.isEmpty() && (price - supports.get(0)) >= minDistance * 0.5) {
            return supports.get(0);
        }

        return 0;
    }

    /**
     * Find the best resistance level for stop placement (SHORT trades).
     */
    private double findBestResistanceForStop(double price, PivotLevels daily, PivotLevels weekly, double minDistance) {
        List<Double> resistances = new ArrayList<>();

        if (daily != null) {
            if (daily.getR1() > 0 && daily.getR1() > price) resistances.add(daily.getR1());
            if (daily.getR2() > 0 && daily.getR2() > price) resistances.add(daily.getR2());
            if (daily.getR3() > 0 && daily.getR3() > price) resistances.add(daily.getR3());
            if (daily.getTc() > 0 && daily.getTc() > price) resistances.add(daily.getTc());
            if (daily.getCamR3() > 0 && daily.getCamR3() > price) resistances.add(daily.getCamR3());
        }
        if (weekly != null) {
            if (weekly.getR1() > 0 && weekly.getR1() > price) resistances.add(weekly.getR1());
            if (weekly.getPivot() > 0 && weekly.getPivot() > price) resistances.add(weekly.getPivot());
        }

        // Sort ascending (nearest first)
        resistances.sort(Double::compare);

        // Find the nearest resistance that gives at least minDistance
        for (double resistance : resistances) {
            double distance = resistance - price;
            if (distance >= minDistance && distance <= price * 0.05) { // Max 5% stop
                return resistance;
            }
        }

        if (!resistances.isEmpty() && (resistances.get(0) - price) >= minDistance * 0.5) {
            return resistances.get(0);
        }

        return 0;
    }

    /**
     * Find resistance levels for targets (LONG trades).
     * Returns [target1, target2].
     */
    private double[] findResistanceTargets(double price, PivotLevels daily, PivotLevels weekly, double risk) {
        List<Double> resistances = new ArrayList<>();

        if (daily != null) {
            if (daily.getR1() > 0 && daily.getR1() > price) resistances.add(daily.getR1());
            if (daily.getR2() > 0 && daily.getR2() > price) resistances.add(daily.getR2());
            if (daily.getR3() > 0 && daily.getR3() > price) resistances.add(daily.getR3());
            if (daily.getTc() > 0 && daily.getTc() > price) resistances.add(daily.getTc());
        }
        if (weekly != null) {
            if (weekly.getR1() > 0 && weekly.getR1() > price) resistances.add(weekly.getR1());
            if (weekly.getR2() > 0 && weekly.getR2() > price) resistances.add(weekly.getR2());
        }

        // Sort ascending
        resistances.sort(Double::compare);

        double target1 = 0, target2 = 0;
        double minReward = risk * 1.5; // Minimum 1.5R

        for (double r : resistances) {
            double reward = r - price;
            if (reward >= minReward) {
                if (target1 == 0) {
                    target1 = r;
                } else if (r > target1) {
                    target2 = r;
                    break;
                }
            }
        }

        return new double[]{target1, target2};
    }

    /**
     * Find support levels for targets (SHORT trades).
     * Returns [target1, target2].
     */
    private double[] findSupportTargets(double price, PivotLevels daily, PivotLevels weekly, double risk) {
        List<Double> supports = new ArrayList<>();

        if (daily != null) {
            if (daily.getS1() > 0 && daily.getS1() < price) supports.add(daily.getS1());
            if (daily.getS2() > 0 && daily.getS2() < price) supports.add(daily.getS2());
            if (daily.getS3() > 0 && daily.getS3() < price) supports.add(daily.getS3());
            if (daily.getBc() > 0 && daily.getBc() < price) supports.add(daily.getBc());
        }
        if (weekly != null) {
            if (weekly.getS1() > 0 && weekly.getS1() < price) supports.add(weekly.getS1());
            if (weekly.getS2() > 0 && weekly.getS2() < price) supports.add(weekly.getS2());
        }

        // Sort descending (nearest first)
        supports.sort((a, b) -> Double.compare(b, a));

        double target1 = 0, target2 = 0;
        double minReward = risk * 1.5; // Minimum 1.5R

        for (double s : supports) {
            double reward = price - s;
            if (reward >= minReward) {
                if (target1 == 0) {
                    target1 = s;
                } else if (s < target1) {
                    target2 = s;
                    break;
                }
            }
        }

        return new double[]{target1, target2};
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
        for (Map.Entry<String, ConcurrentHashMap<String, TradingSignal>> symbolEntry : activeSignals.entrySet()) {
            String symbol = symbolEntry.getKey();
            ConcurrentHashMap<String, TradingSignal> strategySignals = symbolEntry.getValue();

            try {
                // Get current price once per symbol
                UnifiedCandle current = candleService.getLatestCandle(
                    symbol, Timeframe.fromLabel(primaryTimeframe));
                if (current == null) continue;

                double price = current.getClose();

                // Check each strategy signal independently
                List<String> strategiesToRemove = new ArrayList<>();
                for (Map.Entry<String, TradingSignal> stratEntry : strategySignals.entrySet()) {
                    String strategyName = stratEntry.getKey();
                    TradingSignal signal = stratEntry.getValue();

                    if (signal.getState() != SignalState.ACTIVE) continue;

                    if (signal.isStopHit(price)) {
                        signal.enterComplete(TradingSignal.ExitReason.STOP_HIT, price);
                        recordExitToStats(signal, price, "STOP_HIT");
                        saveAndNotify(signal, SignalEvent.STOPPED_OUT);
                        strategiesToRemove.add(strategyName);
                    } else if (signal.isTargetHit(price)) {
                        signal.enterComplete(TradingSignal.ExitReason.TARGET_HIT, price);
                        recordExitToStats(signal, price, "TARGET_HIT");
                        saveAndNotify(signal, SignalEvent.TARGET_HIT);
                        strategiesToRemove.add(strategyName);
                    }
                }

                // Remove completed strategy signals
                for (String s : strategiesToRemove) {
                    strategySignals.remove(s);
                }

                // Clean up empty symbol entries
                if (strategySignals.isEmpty()) {
                    activeSignals.remove(symbol);
                }
            } catch (Exception e) {
                log.error("{} Error checking signals for symbol={}: {}",
                    LOG_PREFIX, symbol, e.getMessage());
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
     * Execute paper trade for activated signal and link trade ID back to signal.
     */
    private void executePaperTrade(TradingSignal signal, EntryLevels levels, String strategyName) {
        if (!paperTradeEnabled) return;

        try {
            TradeDirection direction = signal.getDirection() == FudkiiScore.Direction.BULLISH ?
                TradeDirection.LONG : TradeDirection.SHORT;

            // Execute paper trade and capture the returned trade
            com.kotsin.consumer.papertrade.model.PaperTrade paperTrade = paperTradeExecutor.executeMarketOrder(
                signal.getSymbol(),
                direction,
                levels.entry,
                levels.target1,
                levels.stop,
                signal.getSignalId(),
                strategyName
            );

            // Link paper trade ID back to the signal for tracking
            if (paperTrade != null) {
                signal.setPaperTradeId(paperTrade.getTradeId());
                log.info("{} {} Paper trade linked to signal: {} -> {}",
                    LOG_PREFIX, TraceContext.getShortPrefix(),
                    signal.getSignalId(), paperTrade.getTradeId());
            }
        } catch (Exception e) {
            log.error("{} {} Paper trade execution failed: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), e.getMessage(), e);
        }
    }

    /**
     * Record signal exit to stats.
     * Bug #3 FIX: Safe list access
     */
    private void recordExitToStats(TradingSignal signal, double exitPrice, String reason) {
        try {
            // Find active signal in stats tracker and record exit
            List<com.kotsin.consumer.stats.model.SignalHistory> signalHistoryList =
                statsTracker.getActiveSignals(signal.getSymbol());

            if (signalHistoryList != null && !signalHistoryList.isEmpty()) {
                com.kotsin.consumer.stats.model.SignalHistory history = signalHistoryList.get(0);
                if (history != null && history.getSignalId() != null) {
                    statsTracker.recordExit(history.getSignalId(), exitPrice, reason);
                }
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

        // Publish to Kafka topic
        publishTradingSignalToKafka(signal, event);

        // ==================== OPTION SELECTION FOR ACTIVE SIGNALS ====================
        // When signal becomes ACTIVE, select optimal option for trading
        if (event == SignalEvent.ACTIVE_TRIGGERED && optionSelectionEnabled) {
            selectAndPublishOptionRecommendation(signal);
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

    /**
     * Select optimal option for a trading signal and publish recommendation.
     */
    private void selectAndPublishOptionRecommendation(TradingSignal signal) {
        try {
            // Check if this symbol has derivatives
            String symbol = signal.getSymbol();
            if (!scripGroupService.hasDerivatives(symbol)) {
                log.debug("{} {} No derivatives available for {}",
                    LOG_PREFIX, TraceContext.getShortPrefix(), symbol);
                return;
            }

            // Select optimal option
            com.kotsin.consumer.options.service.OptionStrikeSelector.OptionRecommendation recommendation =
                optionStrikeSelector.selectOption(signal);

            if (recommendation == null || !recommendation.isHasRecommendation()) {
                log.debug("{} {} No option recommendation for {}: {}",
                    LOG_PREFIX, TraceContext.getShortPrefix(), symbol,
                    recommendation != null ? recommendation.getNoRecommendationReason() : "null");
                return;
            }

            // Publish option recommendation to Kafka
            publishOptionRecommendation(signal, recommendation);

            log.info("{} {} OPTION RECOMMENDATION: {} {} {} @ {} | delta={} | OI={} | {}",
                LOG_PREFIX, TraceContext.getShortPrefix(),
                recommendation.getDirection(),
                recommendation.getOptionType(),
                String.format("%.0f", recommendation.getStrike()),
                String.format("%.2f", recommendation.getSpotPrice()),
                String.format("%.2f", recommendation.getEstimatedDelta()),
                recommendation.getOpenInterest(),
                recommendation.getReason());

        } catch (Exception e) {
            log.error("{} {} Error selecting option for {}: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), signal.getSymbol(), e.getMessage());
        }
    }

    /**
     * Publish option recommendation to Kafka topic.
     */
    private void publishOptionRecommendation(TradingSignal signal,
            com.kotsin.consumer.options.service.OptionStrikeSelector.OptionRecommendation recommendation) {
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();

            // Signal reference
            payload.put("signalId", signal.getSignalId());
            payload.put("signalSymbol", signal.getSymbol());
            payload.put("signalDirection", signal.getDirection() != null ? signal.getDirection().name() : null);

            // Option recommendation
            payload.put("underlying", recommendation.getUnderlying());
            payload.put("scripCode", recommendation.getScripCode());
            payload.put("strike", recommendation.getStrike());
            payload.put("optionType", recommendation.getOptionType());
            payload.put("direction", recommendation.getDirection());
            payload.put("spotPrice", recommendation.getSpotPrice());
            payload.put("moneyness", recommendation.getMoneyness() != null ? recommendation.getMoneyness().name() : null);
            payload.put("distanceFromSpot", recommendation.getDistanceFromSpot());
            payload.put("openInterest", recommendation.getOpenInterest());
            payload.put("oiChange", recommendation.getOiChange());
            payload.put("oiInterpretation", recommendation.getOiInterpretation() != null ?
                recommendation.getOiInterpretation().name() : null);
            payload.put("estimatedDelta", recommendation.getEstimatedDelta());
            payload.put("expiry", recommendation.getExpiry());
            payload.put("confidence", recommendation.getConfidence());
            payload.put("reason", recommendation.getReason());
            payload.put("timestamp", recommendation.getTimestamp() != null ?
                recommendation.getTimestamp().toString() : null);

            // Signal entry levels for reference
            payload.put("signalEntry", recommendation.getSignalEntry());
            payload.put("signalStop", recommendation.getSignalStop());
            payload.put("signalTarget", recommendation.getSignalTarget());

            kafkaTemplate.send(optionRecommendationsTopic, signal.getSymbol(), payload);

            log.debug("{} {} Published option recommendation to Kafka: {} {} {}",
                LOG_PREFIX, TraceContext.getShortPrefix(),
                recommendation.getOptionType(), recommendation.getStrike(), recommendation.getUnderlying());

        } catch (Exception e) {
            log.error("{} {} Failed to publish option recommendation: {}",
                LOG_PREFIX, TraceContext.getShortPrefix(), e.getMessage());
        }
    }

    /**
     * Publish TradingSignal to Kafka topic.
     */
    private void publishTradingSignalToKafka(TradingSignal signal, SignalEvent event) {
        try {
            // Build a serializable payload for Kafka
            Map<String, Object> payload = new HashMap<>();
            payload.put("signalId", signal.getSignalId());
            payload.put("symbol", signal.getSymbol());
            payload.put("scripCode", signal.getScripCode());
            payload.put("exchange", signal.getExchange());
            payload.put("companyName", signal.getCompanyName());
            payload.put("timeframe", signal.getTimeframe());
            payload.put("state", signal.getState() != null ? signal.getState().name() : null);
            payload.put("event", event.name());
            payload.put("direction", signal.getDirection() != null ? signal.getDirection().name() : null);
            payload.put("currentPrice", signal.getCurrentPrice());
            payload.put("entryPrice", signal.getEntryPrice());
            payload.put("stopLoss", signal.getStopLoss());
            payload.put("target1", signal.getTarget1());
            payload.put("target2", signal.getTarget2());
            payload.put("createdAt", signal.getCreatedAt() != null ? signal.getCreatedAt().toString() : null);
            payload.put("watchedAt", signal.getWatchedAt() != null ? signal.getWatchedAt().toString() : null);
            payload.put("triggeredAt", signal.getTriggeredAt() != null ? signal.getTriggeredAt().toString() : null);
            payload.put("completedAt", signal.getCompletedAt() != null ? signal.getCompletedAt().toString() : null);
            payload.put("exitReason", signal.getExitReason() != null ? signal.getExitReason().name() : null);
            payload.put("actualExit", signal.getActualExit());

            // Add score info if available
            FudkiiScore score = signal.getCurrentScore();
            if (score != null) {
                payload.put("compositeScore", score.getCompositeScore());
                payload.put("confidence", score.getConfidence());
                payload.put("scoreDirection", score.getDirection() != null ? score.getDirection().name() : null);
                payload.put("isWatchSetup", score.isWatchSetup());
                payload.put("isActiveTrigger", score.isActiveTrigger());
                payload.put("reason", score.getReason());
            }

            payload.put("publishedAt", Instant.now().toString());

            // FIX: Add fields expected by QuantSignalConsumer (schema alignment)
            // timestamp as epoch millis for age check
            Instant signalTimestamp = signal.getTriggeredAt() != null ? signal.getTriggeredAt()
                    : signal.getWatchedAt() != null ? signal.getWatchedAt()
                    : signal.getCreatedAt() != null ? signal.getCreatedAt()
                    : Instant.now();
            payload.put("timestamp", signalTimestamp.toEpochMilli());

            // Map compositeScore -> quantScore for consumer compatibility
            if (score != null) {
                payload.put("quantScore", score.getCompositeScore());
                payload.put("quantLabel", score.getStrength() != null ? score.getStrength().name() : null);
            }

            // Map signal state to actionable flag and signalType
            boolean isActionable = signal.getState() == SignalState.ACTIVE
                    && signal.getEntryPrice() > 0 && signal.getStopLoss() > 0 && signal.getTarget1() > 0;
            payload.put("actionable", isActionable);
            payload.put("signalType", "FUDKII");
            payload.put("riskRewardRatio", signal.getRiskReward());

            kafkaTemplate.send(tradingSignalsTopic, signal.getScripCode(), payload);
            log.info("{} {} Published TradingSignal to Kafka topic: {} (event={})",
                LOG_PREFIX, signal.getSymbol(), tradingSignalsTopic, event);

        } catch (Exception e) {
            log.error("{} {} Failed to publish TradingSignal to Kafka: {}",
                LOG_PREFIX, signal.getSymbol(), e.getMessage());
        }
    }

    // ==================== PUBLIC API ====================

    /**
     * Get active signal for symbol (returns first active strategy signal found).
     */
    public Optional<TradingSignal> getActiveSignal(String symbol) {
        ConcurrentHashMap<String, TradingSignal> strategySignals = activeSignals.get(symbol);
        if (strategySignals == null || strategySignals.isEmpty()) {
            return Optional.empty();
        }
        return strategySignals.values().stream().findFirst();
    }

    /**
     * Get active signal for a specific strategy on a symbol.
     */
    public Optional<TradingSignal> getActiveSignal(String symbol, String strategyName) {
        ConcurrentHashMap<String, TradingSignal> strategySignals = activeSignals.get(symbol);
        if (strategySignals == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(strategySignals.get(strategyName));
    }

    /**
     * Get all active signals across all symbols and strategies.
     */
    public List<TradingSignal> getAllActiveSignals() {
        List<TradingSignal> all = new ArrayList<>();
        for (ConcurrentHashMap<String, TradingSignal> strategySignals : activeSignals.values()) {
            all.addAll(strategySignals.values());
        }
        return all;
    }

    /**
     * Get signals by state.
     */
    public List<TradingSignal> getSignalsByState(SignalState state) {
        return getAllActiveSignals().stream()
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
        stats.put("activeSymbols", activeSignals.size());
        stats.put("activeSignals", getAllActiveSignals().size());
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
