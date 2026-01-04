package com.kotsin.consumer.curated.processor;

import com.kotsin.consumer.capital.model.FinalMagnitude;
import com.kotsin.consumer.curated.model.*;
import com.kotsin.consumer.curated.service.*;
import com.kotsin.consumer.curated.service.BBSuperTrendDetector;
import com.kotsin.consumer.data.model.SignalHistory;
import com.kotsin.consumer.data.service.SignalHistoryService;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.gate.HardGate;
import com.kotsin.consumer.gate.MTFConfluenceGate;
import com.kotsin.consumer.gate.SignalQualityGate;
import com.kotsin.consumer.gate.SignalStatsGate;
import com.kotsin.consumer.gate.model.GateResult;
import com.kotsin.consumer.model.IPUOutput;
import com.kotsin.consumer.model.MTVCPOutput;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.regime.model.ACLOutput;
import com.kotsin.consumer.regime.model.IndexRegime;
import com.kotsin.consumer.regime.model.SecurityRegime;
import com.kotsin.consumer.signal.model.CSSOutput;
import com.kotsin.consumer.stats.service.SignalStatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * CuratedSignalProcessor - Main orchestrator for the Curated Signal System
 *
 * This is a PARALLEL system that:
 * - Listens to existing family-candle topics (1m, 2m, 3m)
 * - Tracks structure and detects breakouts
 * - Waits for retest entries
 * - Integrates all 16 modules
 * - Produces to NEW topic: trading-signals-curated
 *
 * IMPORTANT: This does NOT modify any existing code or topics.
 */
@Component
public class CuratedSignalProcessor {

    private static final Logger log = LoggerFactory.getLogger(CuratedSignalProcessor.class);

    @Autowired
    private StructureTracker structureTracker;

    @Autowired
    private BreakoutDetector breakoutDetector;

    @Autowired
    private RetestDetector retestDetector;

    @Autowired
    private BBSuperTrendDetector bbSuperTrendDetector;

    @Autowired
    private MultiModuleScorer scorer;

    @Autowired(required = false)  // Optional - calculates from FamilyCandle data
    private com.kotsin.consumer.curated.service.FamilyCandleFOAlignmentCalculator foAlignmentCalculator;

    @Autowired
    private MultiTimeframeLevelCalculator levelCalculator;

    @Autowired
    private KafkaTemplate<String, CuratedSignal> curatedSignalProducer;

    // ========== Gate Chain Components ==========
    @Autowired
    private HardGate hardGate;

    @Autowired
    private MTFConfluenceGate mtfGate;

    @Autowired
    private SignalQualityGate qualityGate;

    @Autowired
    private SignalStatsGate statsGate;

    // ========== Data Services ==========
    @Autowired
    private SignalHistoryService signalHistoryService;

    @Autowired
    private SignalStatsService signalStatsService;

    @Value("${gate.chain.enabled:true}")
    private boolean gateChainEnabled;

    // 🛡️ MEMORY LEAK FIX: Replaced unbounded ConcurrentHashMaps with Caffeine caches
    // All caches now have size limits and TTL to prevent OOM

    // Cache for latest FamilyCandle (for gate evaluation)
    private final Cache<String, FamilyCandle> familyCandleCache = Caffeine.newBuilder()
            .maximumSize(5_000)  // Max active securities
            .expireAfterWrite(1, TimeUnit.HOURS)  // Clear after 1 hour
            .build();

    // Cache for active breakouts with TTL expiry (30 minutes)
    private final Cache<String, MultiTFBreakout> activeBreakouts = Caffeine.newBuilder()
            .maximumSize(1_000)  // Max concurrent breakouts
            .expireAfterWrite(30, TimeUnit.MINUTES)  // Auto-expire breakouts
            .build();

    // Cache for latest module outputs
    private final Cache<String, IndexRegime> indexRegimeCache = Caffeine.newBuilder()
            .maximumSize(100)  // ~50 indices (NIFTY, BANKNIFTY, etc.)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();

    private final Cache<String, SecurityRegime> securityRegimeCache = Caffeine.newBuilder()
            .maximumSize(5_000)  // Max securities
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();

    // FIX: Map scripCode to relevant index (not always NIFTY50)
    // This should be loaded from configuration or sector mapping service
    private static final Map<String, String> SCRIP_TO_INDEX_MAP = new ConcurrentHashMap<>();
    private static final String DEFAULT_INDEX = "NIFTY50";
    
    static {
        // Example mappings - in production, load from config/DB
        // Bank stocks should align with BANKNIFTY
        // IT stocks should align with NIFTYIT
        // This is a simplified version - full implementation would use sector-mappings.csv
    }
    
    /**
     * FIX: Get relevant index for a scrip instead of hardcoded NIFTY50
     */
    private String getRelevantIndex(String scripCode) {
        // First check if we have a specific mapping
        String mappedIndex = SCRIP_TO_INDEX_MAP.get(scripCode);
        if (mappedIndex != null) {
            return mappedIndex;
        }
        
        // Try to infer from security regime (using parentIndexCode)
        SecurityRegime secRegime = securityRegimeCache.getIfPresent(scripCode);
        if (secRegime != null && secRegime.getParentIndexCode() != null) {
            return secRegime.getParentIndexCode();
        }
        
        // Default fallback
        return DEFAULT_INDEX;
    }
    private final Cache<String, ACLOutput> aclCache = Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterWrite(2, TimeUnit.HOURS)
            .build();

    private final Cache<String, MTVCPOutput> vcpCache = Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterWrite(2, TimeUnit.HOURS)
            .build();

    private final Cache<String, CSSOutput> cssCache = Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterWrite(2, TimeUnit.HOURS)
            .build();

    private final Cache<String, IPUOutput> ipuCache = Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterWrite(2, TimeUnit.HOURS)
            .build();

    private final Cache<String, FinalMagnitude> fmaCache = Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterWrite(2, TimeUnit.HOURS)
            .build();

    /**
     * Listen to family candle streams (1m, 2m, 3m)
     * This is READ-ONLY - does not modify existing flow
     */
    @KafkaListener(
            topics = {"family-candle-1m", "family-candle-2m", "family-candle-3m"},
            groupId = "${kafka.consumer.curated-group:curated-signal-processor-v2}",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void processCandle(FamilyCandle familyCandle) {
        try {
            if (familyCandle == null) return;

            // Extract equity InstrumentCandle
            InstrumentCandle equity = familyCandle.getEquity();
            if (equity == null) {
                log.warn("No equity data in FamilyCandle");
                return;
            }

            // Convert to UnifiedCandle for backwards compatibility
            UnifiedCandle candle = convertToUnifiedCandle(equity);

            String scripCode = candle.getScripCode();
            // BUG-FIX: Use FamilyCandle's timeframe, NOT equity's timeframe!
            // The equity InstrumentCandle always has timeframe="1m" even in 3m FamilyCandles
            String timeframe = familyCandle.getTimeframe();

            // Cache the FamilyCandle for gate evaluation
            familyCandleCache.put(scripCode, familyCandle);

            // 1. Update structure tracker - pass the correct timeframe
            candle.setTimeframe(timeframe);  // Override with correct timeframe
            structureTracker.updateCandle(candle);

            // 1.5 Check for BB+SuperTrend confluence (runs on all timeframes)
            bbSuperTrendDetector.processCandle(candle);

            // 2. Check if we have an active breakout for this scrip
            // Note: Caffeine cache automatically expires breakouts after 30 minutes
            MultiTFBreakout activeBreakout = activeBreakouts.getIfPresent(scripCode);

            if (activeBreakout != null) {
                // We're waiting for retest - check if this candle is retesting
                checkForRetest(activeBreakout, candle);
            } else {
                // No active breakout - check if this candle creates a new breakout
                // Only check on 3m candles to avoid spam
                if ("3m".equals(timeframe)) {
                    log.debug("Checking for breakout on 3m candle: {}", scripCode);
                    checkForNewBreakout(scripCode);
                }
            }

        } catch (Exception e) {
            log.error("Error processing candle: {}", e.getMessage(), e);
        }
    }

    /**
     * Listen to Index Regime topic
     */
    @KafkaListener(
            topics = "regime-index-output",
            groupId = "${kafka.consumer.curated-group:curated-signal-processor-v2}",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void processIndexRegime(IndexRegime indexRegime) {
        indexRegimeCache.put(indexRegime.getIndexName(), indexRegime);
    }

    /**
     * Listen to Security Regime topic
     */
    @KafkaListener(
            topics = "regime-security-output",
            groupId = "${kafka.consumer.curated-group:curated-signal-processor-v2}",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void processSecurityRegime(SecurityRegime securityRegime) {
        securityRegimeCache.put(securityRegime.getScripCode(), securityRegime);
    }

    /**
     * Listen to ACL topic
     */
    @KafkaListener(
            topics = "regime-acl-output",
            groupId = "${kafka.consumer.curated-group:curated-signal-processor-v2}",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void processACL(ACLOutput acl) {
        aclCache.put(acl.getScripCode(), acl);
    }

    /**
     * Listen to VCP topic
     */
    @KafkaListener(
            topics = "vcp-combined",
            groupId = "${kafka.consumer.curated-group:curated-signal-processor-v2}",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void processVCP(MTVCPOutput vcp) {
        vcpCache.put(vcp.getScripCode(), vcp);
    }

    /**
     * Listen to CSS topic
     */
    @KafkaListener(
            topics = "css-output",
            groupId = "${kafka.consumer.curated-group:curated-signal-processor-v2}",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void processCSS(CSSOutput css) {
        cssCache.put(css.getScripCode(), css);
    }

    /**
     * Listen to IPU topic
     */
    @KafkaListener(
            topics = "ipu-signals-5m",
            groupId = "${kafka.consumer.curated-group:curated-signal-processor-v2}",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void processIPU(IPUOutput ipu) {
        ipuCache.put(ipu.getScripCode(), ipu);
    }

    /**
     * Listen to Final Magnitude topic
     */
    @KafkaListener(
            topics = "magnitude-final",
            groupId = "${kafka.consumer.curated-group:curated-signal-processor-v2}",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void processFinalMagnitude(FinalMagnitude fma) {
        fmaCache.put(fma.getScripCode(), fma);
    }

    /**
     * Check if current candle creates a new breakout
     * FIX: Now emits signal immediately on breakout acceptance (no retest required)
     */
    private void checkForNewBreakout(String scripCode) {
        // Detect multi-TF breakout
        MultiTFBreakout breakout = breakoutDetector.detectMultiTFBreakout(scripCode);

        if (breakout != null && breakout.isValid()) {
            log.info("🔍 BREAKOUT DETECTED | scrip={} | TF_confirmations={}/3 | confluence={} | volZ={} | kyle={}",
                scripCode,
                breakout.getConfirmations(),
                String.format("%.2f", breakout.getConfluenceScore()),
                String.format("%.1f", breakout.getAvgVolumeZScore()),
                String.format("%.2f", breakout.getAvgKyleLambda()));

            // Check gates before emitting signal
            if (!passesGates(scripCode)) {
                log.info("⛔ BREAKOUT REJECTED | scrip={} | reason=Failed_gates | See gate logs above", scripCode);
                return;
            }

            log.info("✅ BREAKOUT ACCEPTED | scrip={} | pivot={} | high={} | direction={}",
                scripCode,
                String.format("%.2f", breakout.getPrimaryBreakout().getPivotLevel()),
                String.format("%.2f", breakout.getPrimaryBreakout().getBreakoutHigh()),
                breakout.getPrimaryBreakout().getDirection());
            
            // FIX: Emit signal IMMEDIATELY on breakout acceptance (no retest wait)
            generateBreakoutSignal(breakout);
        }
    }
    
    /**
     * FIX: Generate curated signal immediately on breakout (no retest required)
     * Entry is at breakout price, stop loss is at pivot level
     */
    private void generateBreakoutSignal(MultiTFBreakout breakout) {
        String scripCode = breakout.getScripCode();
        String direction = breakout.getPrimaryBreakout().getDirection();
        String signalType = "BREAKOUT_IMMEDIATE";

        // Fetch all module outputs
        String relevantIndex = getRelevantIndex(scripCode);
        IndexRegime indexRegime = indexRegimeCache.getIfPresent(relevantIndex);
        if (indexRegime == null) {
            indexRegime = indexRegimeCache.getIfPresent(DEFAULT_INDEX);
        }
        SecurityRegime securityRegime = securityRegimeCache.getIfPresent(scripCode);
        FamilyCandle familyCandle = familyCandleCache.getIfPresent(scripCode);
        ACLOutput acl = aclCache.getIfPresent(scripCode);
        MTVCPOutput vcp = vcpCache.getIfPresent(scripCode);
        CSSOutput css = cssCache.getIfPresent(scripCode);
        IPUOutput ipu = ipuCache.getIfPresent(scripCode);
        FinalMagnitude fma = fmaCache.getIfPresent(scripCode);

        // Calculate entry/stop/target based on breakout direction
        double entryPrice = breakout.getPrimaryBreakout().getBreakoutPrice();
        double pivotLevel = breakout.getPrimaryBreakout().getPivotLevel();
        double atr = getATR(scripCode);
        
        double stopLoss, target;
        if ("BULLISH".equals(direction)) {
            stopLoss = pivotLevel - (0.5 * atr);  // Below pivot with buffer
            double breakoutRange = entryPrice - pivotLevel;
            target = entryPrice + (breakoutRange * 2);  // 2x measured move
        } else {
            stopLoss = pivotLevel + (0.5 * atr);  // Above pivot with buffer
            double breakoutRange = pivotLevel - entryPrice;
            target = entryPrice - (breakoutRange * 2);  // 2x measured move
        }
        
        // Calculate R:R
        double riskAmount = Math.abs(entryPrice - stopLoss);
        double rewardAmount = Math.abs(target - entryPrice);
        double riskReward = riskAmount > 0 ? rewardAmount / riskAmount : 0;

        // Build entry object
        RetestEntry entry = RetestEntry.builder()
                .scripCode(scripCode)
                .timestamp(breakout.getTimestamp())
                .entryPrice(entryPrice)
                .stopLoss(stopLoss)
                .target(target)
                .riskReward(riskReward)
                .pivotLevel(pivotLevel)
                .positionSizeMultiplier(1.0)
                .buyingPressure("BULLISH".equals(direction))
                .build();

        // Create signal history record
        SignalHistory history = signalHistoryService.createHistory(
                scripCode, signalType, direction, breakout, familyCandle,
                indexRegime, securityRegime, vcp, ipu, css, acl, fma,
                null, null, entry, breakout.getConfirmations()
        );

        // Calculate curated score
        double curatedScore = scorer.calculateCuratedScore(
                breakout, indexRegime, securityRegime, acl, vcp, css, ipu,
                null, null, entryPrice
        );

        // FIX: Lower minimum score threshold for immediate breakout signals
        if (curatedScore < 50.0) {
            signalHistoryService.markRejected(history, "LOW_SCORE: " + String.format("%.1f", curatedScore));
            signalHistoryService.save(history);
            log.info("⛔ SIGNAL REJECTED | {} | reason=LOW_SCORE | score={}", scripCode, String.format("%.1f", curatedScore));
            return;
        }

        // Mark signal as emitted and save history
        signalHistoryService.markEmitted(history, curatedScore, entry.getPositionSizeMultiplier());
        signalHistoryService.save(history);

        // Record signal for stats tracking
        signalStatsService.recordSignal(scripCode, signalType);

        // Build curated reason
        String reason = buildCuratedReason(breakout, indexRegime, ipu, acl, null);

        // Build final curated signal
        CuratedSignal signal = CuratedSignal.builder()
                .scripCode(scripCode)
                .companyName(vcp != null ? vcp.getCompanyName() : scripCode)
                .timestamp(System.currentTimeMillis())
                .signalId(history.getSignalId())
                .breakout(breakout)
                .pattern(breakout.getPattern())
                .indexRegime(indexRegime)
                .securityRegime(securityRegime)
                .acl(acl)
                .vcp(vcp)
                .css(css)
                .ipu(ipu)
                .finalMagnitude(fma)
                .entry(entry)
                .curatedScore(curatedScore)
                .curatedReason(reason)
                .positionSizeMultiplier(entry.getPositionSizeMultiplier())
                .riskRewardRatio(riskReward)
                .build();

        // Send to Kafka
        curatedSignalProducer.send("trading-signals-curated", scripCode, signal);

        log.info("📤 CURATED SIGNAL EMITTED (IMMEDIATE) | {} | dir={} | Score={} | Entry={} | SL={} | Target={} | R:R={}",
                scripCode,
                direction,
                String.format("%.1f", curatedScore),
                String.format("%.2f", entry.getEntryPrice()),
                String.format("%.2f", entry.getStopLoss()),
                String.format("%.2f", entry.getTarget()),
                String.format("%.2f", riskReward));
        log.info("   Reason: {}", reason);
    }

    // Note: Manual cleanup method removed - Caffeine cache automatically expires
    // breakouts after 30 minutes using expireAfterWrite()

    /**
     * Check if price is retesting the breakout pivot
     */
    private void checkForRetest(MultiTFBreakout breakout, UnifiedCandle candle) {
        String scripCode = breakout.getScripCode();

        // Get current ATR from security regime or calculate
        double atr = getATR(scripCode);

        // Detect retest
        RetestEntry entry = retestDetector.detectRetest(
                breakout.getPrimaryBreakout(),
                candle,
                atr
        );

        if (entry != null) {
            // RETEST CONFIRMED - Generate curated signal
            generateCuratedSignal(breakout, entry);

            // Remove from active breakouts (Caffeine cache uses invalidate instead of remove)
            activeBreakouts.invalidate(scripCode);
        }
    }

    /**
     * Generate curated signal when retest is detected (ENHANCED with Gate Chain + History)
     */
    private void generateCuratedSignal(MultiTFBreakout breakout, RetestEntry entry) {
        String scripCode = breakout.getScripCode();
        // BUG-FIX: Use BreakoutBar's direction field instead of inferring
        String direction = breakout.getPrimaryBreakout().getDirection();
        String signalType = "BREAKOUT_RETEST";

        // Fetch all module outputs
        String relevantIndex = getRelevantIndex(scripCode);
        IndexRegime indexRegime = indexRegimeCache.getIfPresent(relevantIndex);
        if (indexRegime == null) {
            indexRegime = indexRegimeCache.getIfPresent(DEFAULT_INDEX);
        }
        SecurityRegime securityRegime = securityRegimeCache.getIfPresent(scripCode);
        FamilyCandle familyCandle = familyCandleCache.getIfPresent(scripCode);
        ACLOutput acl = aclCache.getIfPresent(scripCode);
        MTVCPOutput vcp = vcpCache.getIfPresent(scripCode);
        CSSOutput css = cssCache.getIfPresent(scripCode);
        IPUOutput ipu = ipuCache.getIfPresent(scripCode);
        FinalMagnitude fma = fmaCache.getIfPresent(scripCode);

        // Calculate F&O alignment from FamilyCandle data (no external API needed)
        FuturesOptionsAlignment foAlignment = null;
        if (foAlignmentCalculator != null && familyCandle != null) {
            try {
                foAlignment = foAlignmentCalculator.calculateAlignment(familyCandle);
            } catch (Exception e) {
                log.debug("F&O alignment calculation failed for {}: {}", scripCode, e.getMessage());
            }
        }

        // Calculate multi-timeframe levels
        MultiTimeframeLevels levels = null;
        try {
            levels = levelCalculator.calculateLevels(scripCode, entry.getEntryPrice());
        } catch (Exception e) {
            log.debug("Levels unavailable for {}: {}", scripCode, e.getMessage());
        }

        // Optimize entry with levels
        RetestEntry optimizedEntry = entry;
        if (levels != null) {
            double atr = getATR(scripCode);
            optimizedEntry = retestDetector.optimizeEntryWithLevels(entry, levels, atr);
        }

        // Count MTF confirmations for history
        int mtfConfirmations = breakout.getConfirmations();

        // Create signal history record (saves ALL signals, even rejected)
        SignalHistory history = signalHistoryService.createHistory(
                scripCode, signalType, direction, breakout, familyCandle,
                indexRegime, securityRegime, vcp, ipu, css, acl, fma,
                foAlignment, levels, optimizedEntry, mtfConfirmations
        );

        // ========== GATE CHAIN EVALUATION ==========
        if (gateChainEnabled) {
            // Layer 1: Hard Gate
            GateResult hardResult = hardGate.evaluate(familyCandle, indexRegime, securityRegime);
            if (!hardResult.isPassed()) {
                signalHistoryService.recordGateResults(history, hardResult, null, null, null);
                signalHistoryService.markRejected(history, "HARD_GATE: " + hardResult.getReason());
                signalHistoryService.save(history);
                log.info("⛔ SIGNAL REJECTED | {} | gate=HARD | reason={}", scripCode, hardResult.getReason());
                return;
            }

            // Layer 2: MTF Confluence Gate
            GateResult mtfResult = mtfGate.evaluate(scripCode, direction, vcp);
            if (!mtfResult.isPassed()) {
                signalHistoryService.recordGateResults(history, hardResult, mtfResult, null, null);
                signalHistoryService.markRejected(history, "MTF_GATE: " + mtfResult.getReason());
                signalHistoryService.save(history);
                log.info("⛔ SIGNAL REJECTED | {} | gate=MTF | reason={}", scripCode, mtfResult.getReason());
                return;
            }

            // Layer 3: Signal Quality Gate
            GateResult qualityResult = qualityGate.evaluate(familyCandle, ipu, direction);
            if (!qualityResult.isPassed()) {
                signalHistoryService.recordGateResults(history, hardResult, mtfResult, qualityResult, null);
                signalHistoryService.markRejected(history, "QUALITY_GATE: " + qualityResult.getReason());
                signalHistoryService.save(history);
                log.info("⛔ SIGNAL REJECTED | {} | gate=QUALITY | reason={}", scripCode, qualityResult.getReason());
                return;
            }

            // Layer 4: Stats Gate (Online Learning)
            GateResult statsResult = statsGate.evaluate(scripCode, signalType);
            if (!statsResult.isPassed()) {
                signalHistoryService.recordGateResults(history, hardResult, mtfResult, qualityResult, statsResult);
                signalHistoryService.markRejected(history, "STATS_GATE: " + statsResult.getReason());
                signalHistoryService.save(history);
                log.info("⛔ SIGNAL REJECTED | {} | gate=STATS | reason={}", scripCode, statsResult.getReason());
                return;
            }

            // All gates passed - record results
            signalHistoryService.recordGateResults(history, hardResult, mtfResult, qualityResult, statsResult);

            // Calculate combined position multiplier from all gates
            double gateMultiplier = hardResult.getPositionMultiplier() 
                    * mtfResult.getPositionMultiplier() 
                    * qualityResult.getPositionMultiplier() 
                    * statsResult.getPositionMultiplier();

            // Apply gate multiplier to entry
            optimizedEntry.setPositionSizeMultiplier(
                    optimizedEntry.getPositionSizeMultiplier() * gateMultiplier);
        }

        // Calculate curated score
        double curatedScore = scorer.calculateCuratedScore(
                breakout, indexRegime, securityRegime, acl, vcp, css, ipu,
                foAlignment, levels, optimizedEntry.getEntryPrice()
        );

        // Check minimum score threshold
        if (curatedScore < 60.0) {
            signalHistoryService.markRejected(history, "LOW_SCORE: " + String.format("%.1f", curatedScore));
            signalHistoryService.save(history);
            log.info("⛔ SIGNAL REJECTED | {} | reason=LOW_SCORE | score={}", scripCode, String.format("%.1f", curatedScore));
            return;
        }

        // Calculate final position size multiplier
        double positionMultiplier = scorer.calculatePositionSizeMultiplier(curatedScore, foAlignment);
        positionMultiplier *= optimizedEntry.getPositionSizeMultiplier();
        optimizedEntry.setPositionSizeMultiplier(Math.max(0.5, Math.min(1.5, positionMultiplier)));

        // Build curated reason
        String reason = buildCuratedReason(breakout, indexRegime, ipu, acl, foAlignment);

        // Mark signal as emitted and save history
        signalHistoryService.markEmitted(history, curatedScore, optimizedEntry.getPositionSizeMultiplier());
        signalHistoryService.save(history);

        // Record signal for stats tracking
        signalStatsService.recordSignal(scripCode, signalType);

        // Build final curated signal
        CuratedSignal signal = CuratedSignal.builder()
                .scripCode(scripCode)
                .companyName(vcp != null ? vcp.getCompanyName() : scripCode)
                .timestamp(System.currentTimeMillis())
                .signalId(history.getSignalId())  // Link for outcome tracking
                .breakout(breakout)
                .pattern(breakout.getPattern())
                .indexRegime(indexRegime)
                .securityRegime(securityRegime)
                .acl(acl)
                .vcp(vcp)
                .css(css)
                .ipu(ipu)
                .finalMagnitude(fma)
                .foAlignment(foAlignment)
                .levels(levels)
                .entry(optimizedEntry)
                .curatedScore(curatedScore)
                .curatedReason(reason)
                .positionSizeMultiplier(optimizedEntry.getPositionSizeMultiplier())
                .riskRewardRatio(optimizedEntry.getRiskReward())
                .build();

        // Send to Kafka
        curatedSignalProducer.send("trading-signals-curated", scripCode, signal);

        log.info("📤 CURATED SIGNAL EMITTED | {} | Score={} | Entry={} | SL={} | Target={} | R:R={} | PosMulti={}",
                scripCode,
                String.format("%.1f", curatedScore),
                String.format("%.2f", optimizedEntry.getEntryPrice()),
                String.format("%.2f", optimizedEntry.getStopLoss()),
                String.format("%.2f", optimizedEntry.getTarget()),
                String.format("%.2f", optimizedEntry.getRiskReward()),
                String.format("%.2f", optimizedEntry.getPositionSizeMultiplier()));
        log.info("   Reason: {}", reason);
    }

    /**
     * Check if scrip passes all gates (ENHANCED with detailed logging)
     * FIX: Uses dynamic index mapping instead of hardcoded NIFTY50
     */
    private boolean passesGates(String scripCode) {
        // FIX: Get relevant index for this scrip (not hardcoded NIFTY50)
        String relevantIndex = getRelevantIndex(scripCode);
        
        // Gate 1: Index regime must be tradeable
        IndexRegime indexRegime = indexRegimeCache.getIfPresent(relevantIndex);
        if (indexRegime == null) {
            // FIX: Fallback to NIFTY50 if specific index not available
            indexRegime = indexRegimeCache.getIfPresent(DEFAULT_INDEX);
            if (indexRegime == null) {
                log.info("🚫 GATE_1_FAILED | scrip={} | gate=INDEX_REGIME | reason=Index_regime_null | tried={},{}",
                    scripCode, relevantIndex, DEFAULT_INDEX);
                return false;
            }
            log.debug("Using fallback index {} for scrip {} (wanted {})", DEFAULT_INDEX, scripCode, relevantIndex);
        }
        if (!indexRegime.isTradeable()) {
            log.info("🚫 GATE_1_FAILED | scrip={} | gate=INDEX_REGIME | reason=Not_tradeable | index={} | regime={} | strength={}",
                scripCode,
                relevantIndex,
                indexRegime.getLabel(),
                String.format("%.2f", indexRegime.getRegimeStrength()));
            return false;
        }

        // Gate 2: Avoid OPENING/CLOSING sessions
        if (indexRegime.getSessionPhase() == IndexRegime.SessionPhase.OPENING ||
                indexRegime.getSessionPhase() == IndexRegime.SessionPhase.CLOSING) {
            log.info("🚫 GATE_2_FAILED | scrip={} | gate=SESSION_PHASE | reason=Avoid_{}",
                scripCode, indexRegime.getSessionPhase());
            return false;
        }

        // Gate 3: Security regime must be aligned with index
        SecurityRegime securityRegime = securityRegimeCache.getIfPresent(scripCode);
        if (securityRegime == null) {
            log.info("🚫 GATE_3_FAILED | scrip={} | gate=SECURITY_REGIME | reason=Security_regime_null", scripCode);
            return false;
        }
        if (!securityRegime.isAlignedWithIndex()) {
            log.info("🚫 GATE_3_FAILED | scrip={} | gate=SECURITY_REGIME | reason=Not_aligned_with_index | secRegime={} | idxRegime={}",
                scripCode,
                securityRegime.getLabel(),
                indexRegime.getLabel());
            return false;
        }

        // Gate 4: ACL must allow entry
        ACLOutput acl = aclCache.getIfPresent(scripCode);
        if (acl == null) {
            log.info("🚫 GATE_4_FAILED | scrip={} | gate=ACL | reason=ACL_null", scripCode);
            return false;
        }
        if (!acl.isEntryAllowed()) {
            log.info("🚫 GATE_4_FAILED | scrip={} | gate=ACL | reason=Entry_not_allowed | aclState={} | multiplier={}",
                scripCode,
                acl.getAclState(),
                String.format("%.2f", acl.getAclMultiplier()));
            return false;
        }

        log.info("✅ ALL_GATES_PASSED | scrip={} | indexRegime={} | secRegime={} | aclState={}",
            scripCode,
            indexRegime.getLabel(),
            securityRegime.getLabel(),
            acl.getAclState());
        return true;  // All gates passed
    }

    /**
     * Get ATR for stop loss calculation
     */
    private double getATR(String scripCode) {
        SecurityRegime securityRegime = securityRegimeCache.getIfPresent(scripCode);
        if (securityRegime != null && securityRegime.getAtr14() > 0) {
            return securityRegime.getAtr14();
        }

        // Fallback: Calculate from recent candles
        UnifiedCandle candle = structureTracker.getLatestCandle(scripCode, "3m");
        return candle != null ? candle.getRange() : 1.0;
    }

    /**
     * Build human-readable reason (ENHANCED with F&O)
     */
    private String buildCuratedReason(MultiTFBreakout breakout, IndexRegime indexRegime,
                                      IPUOutput ipu, ACLOutput acl, FuturesOptionsAlignment foAlignment) {
        StringBuilder sb = new StringBuilder();

        sb.append(breakout.getConfirmations()).append("/3 TF breakout");

        if (indexRegime != null) {
            sb.append(" | ").append(indexRegime.getLabel());
        }

        if (acl != null) {
            sb.append(" | ").append(acl.getAclState());
        }

        // 🆕 Add F&O information
        if (foAlignment != null && foAlignment.isUsable()) {
            sb.append(" | F&O: ").append(foAlignment.getBias());
            if (foAlignment.isAligned()) {
                sb.append(" (aligned)");
            }
        }

        if (ipu != null && ipu.isXfactorFlag()) {
            sb.append(" | X-FACTOR");
        }

        sb.append(" | Vol Z=").append(String.format("%.1f", breakout.getAvgVolumeZScore()));
        sb.append(" | Kyle=").append(String.format("%.2f", breakout.getAvgKyleLambda()));

        return sb.toString();
    }

    /**
     * Convert InstrumentCandle to UnifiedCandle for backwards compatibility
     */
    private static UnifiedCandle convertToUnifiedCandle(InstrumentCandle instrument) {
        return UnifiedCandle.builder()
                .scripCode(instrument.getScripCode())
                .companyName(instrument.getCompanyName())
                .exchange(instrument.getExchange())
                .exchangeType(instrument.getExchangeType())
                .timeframe(instrument.getTimeframe())
                .windowStartMillis(instrument.getWindowStartMillis())
                .windowEndMillis(instrument.getWindowEndMillis())
                .humanReadableStartTime(instrument.getHumanReadableTime())
                .humanReadableEndTime(instrument.getHumanReadableTime())
                // OHLCV
                .open(instrument.getOpen())
                .high(instrument.getHigh())
                .low(instrument.getLow())
                .close(instrument.getClose())
                .volume(instrument.getVolume())
                .buyVolume(instrument.getBuyVolume())
                .sellVolume(instrument.getSellVolume())
                .vwap(instrument.getVwap())
                .tickCount(instrument.getTickCount())
                // Volume Profile
                .volumeAtPrice(instrument.getVolumeAtPrice())
                .poc(instrument.getPoc())
                .valueAreaHigh(instrument.getVah())
                .valueAreaLow(instrument.getVal())
                // Imbalance
                .volumeImbalance(instrument.getVolumeImbalance())
                .dollarImbalance(instrument.getDollarImbalance())
                .vpin(instrument.getVpin())
                // Orderbook (may be null)
                .ofi(instrument.getOfi() != null ? instrument.getOfi() : 0.0)
                .depthImbalance(instrument.getDepthImbalance() != null ? instrument.getDepthImbalance() : 0.0)
                .kyleLambda(instrument.getKyleLambda() != null ? instrument.getKyleLambda() : 0.0)
                .microprice(instrument.getMicroprice() != null ? instrument.getMicroprice() : 0.0)
                .bidAskSpread(instrument.getBidAskSpread() != null ? instrument.getBidAskSpread() : 0.0)
                .weightedDepthImbalance(instrument.getWeightedDepthImbalance() != null ? instrument.getWeightedDepthImbalance() : 0.0)
                .totalBidDepth(instrument.getAverageBidDepth() != null ? instrument.getAverageBidDepth() : 0.0)
                .totalAskDepth(instrument.getAverageAskDepth() != null ? instrument.getAverageAskDepth() : 0.0)
                // OI (may be null)
                .oiOpen(instrument.getOiOpen())
                .oiHigh(instrument.getOiHigh())
                .oiLow(instrument.getOiLow())
                .oiClose(instrument.getOiClose())
                .oiChange(instrument.getOiChange())
                .oiChangePercent(instrument.getOiChangePercent())
                // Derived fields
                .volumeDeltaPercent(instrument.getVolumeDeltaPercent())
                .range(instrument.getRange())
                .isBullish(instrument.isBullish())
                .build();
    }
}
