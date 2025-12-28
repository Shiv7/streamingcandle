package com.kotsin.consumer.curated.processor;

import com.kotsin.consumer.capital.model.FinalMagnitude;
import com.kotsin.consumer.curated.model.*;
import com.kotsin.consumer.curated.service.*;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private MultiModuleScorer scorer;

    @Autowired
    private FuturesOptionsService futuresOptionsService;

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

    // Cache for latest FamilyCandle (for gate evaluation)
    private final Map<String, FamilyCandle> familyCandleCache = new ConcurrentHashMap<>();

    // FIX: Cache for active breakouts with TTL expiry
    // Breakouts that don't get retested within 30 minutes should expire
    private static final long BREAKOUT_EXPIRY_MS = 30 * 60 * 1000L;  // 30 minutes
    private final Map<String, MultiTFBreakout> activeBreakouts = new ConcurrentHashMap<>();
    private final Map<String, Long> breakoutTimestamps = new ConcurrentHashMap<>();  // Track when breakout was detected

    // Cache for latest module outputs
    private final Map<String, IndexRegime> indexRegimeCache = new ConcurrentHashMap<>();
    private final Map<String, SecurityRegime> securityRegimeCache = new ConcurrentHashMap<>();
    
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
        SecurityRegime secRegime = securityRegimeCache.get(scripCode);
        if (secRegime != null && secRegime.getParentIndexCode() != null) {
            return secRegime.getParentIndexCode();
        }
        
        // Default fallback
        return DEFAULT_INDEX;
    }
    private final Map<String, ACLOutput> aclCache = new ConcurrentHashMap<>();
    private final Map<String, MTVCPOutput> vcpCache = new ConcurrentHashMap<>();
    private final Map<String, CSSOutput> cssCache = new ConcurrentHashMap<>();
    private final Map<String, IPUOutput> ipuCache = new ConcurrentHashMap<>();
    private final Map<String, FinalMagnitude> fmaCache = new ConcurrentHashMap<>();

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

            // FIX: Periodically cleanup expired breakouts (every ~100 candles)
            if (System.currentTimeMillis() % 100 == 0) {
                cleanupExpiredBreakouts();
            }

            // 2. Check if we have an active breakout for this scrip
            MultiTFBreakout activeBreakout = activeBreakouts.get(scripCode);

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

            // Check gates before adding to active breakouts
            if (!passesGates(scripCode)) {
                log.info("⛔ BREAKOUT REJECTED | scrip={} | reason=Failed_gates | See gate logs above", scripCode);
                return;
            }

                log.info("✅ BREAKOUT ACCEPTED | scrip={} | status=WAITING_FOR_RETEST | pivot={} | high={}",
                scripCode,
                String.format("%.2f", breakout.getPrimaryBreakout().getPivotLevel()),
                String.format("%.2f", breakout.getPrimaryBreakout().getBreakoutHigh()));
            activeBreakouts.put(scripCode, breakout);
            breakoutTimestamps.put(scripCode, System.currentTimeMillis());  // FIX: Track timestamp
        }
    }

    /**
     * FIX: Clean up expired breakouts that never got retested
     * Called periodically to prevent memory leaks
     * BUG-FIX: Fixed race condition in cleanup + corrected age calculation
     */
    private synchronized void cleanupExpiredBreakouts() {
        long now = System.currentTimeMillis();
        List<String> expired = new java.util.ArrayList<>();
        java.util.Map<String, Long> expiredAges = new java.util.HashMap<>();
        
        breakoutTimestamps.forEach((scripCode, timestamp) -> {
            long age = now - timestamp;
            if (age > BREAKOUT_EXPIRY_MS) {
                expired.add(scripCode);
                expiredAges.put(scripCode, age);  // BUG-FIX: Store age before removal
            }
        });
        
        for (String scripCode : expired) {
            MultiTFBreakout removed = activeBreakouts.remove(scripCode);
            Long removedTimestamp = breakoutTimestamps.remove(scripCode);
            if (removed != null) {
                // BUG-FIX: Use stored age, not post-removal lookup
                long ageMinutes = expiredAges.getOrDefault(scripCode, 0L) / 60000;
                log.info("🗑️ BREAKOUT EXPIRED | scrip={} | age={}min | reason=No_retest_within_window",
                    scripCode, ageMinutes);
            }
        }
    }

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

            // Remove from active breakouts
            activeBreakouts.remove(scripCode);
        }
    }

    /**
     * Generate curated signal when retest is detected (ENHANCED with Gate Chain + History)
     */
    private void generateCuratedSignal(MultiTFBreakout breakout, RetestEntry entry) {
        String scripCode = breakout.getScripCode();
        // Infer direction from breakout price vs pivot level
        // If breakout price > pivot, it's bullish; otherwise bearish
        String direction = (breakout.getPrimaryBreakout().getBreakoutPrice() > breakout.getPrimaryBreakout().getPivotLevel()) 
                ? "BULLISH" : "BEARISH";
        String signalType = "BREAKOUT_RETEST";

        // Fetch all module outputs
        String relevantIndex = getRelevantIndex(scripCode);
        IndexRegime indexRegime = indexRegimeCache.get(relevantIndex);
        if (indexRegime == null) {
            indexRegime = indexRegimeCache.get(DEFAULT_INDEX);
        }
        SecurityRegime securityRegime = securityRegimeCache.get(scripCode);
        FamilyCandle familyCandle = familyCandleCache.get(scripCode);
        ACLOutput acl = aclCache.get(scripCode);
        MTVCPOutput vcp = vcpCache.get(scripCode);
        CSSOutput css = cssCache.get(scripCode);
        IPUOutput ipu = ipuCache.get(scripCode);
        FinalMagnitude fma = fmaCache.get(scripCode);

        // Fetch F&O alignment data
        FuturesOptionsAlignment foAlignment = null;
        try {
            foAlignment = futuresOptionsService.calculateAlignment(scripCode, entry.getEntryPrice());
        } catch (Exception e) {
            log.debug("F&O data unavailable for {}: {}", scripCode, e.getMessage());
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
        IndexRegime indexRegime = indexRegimeCache.get(relevantIndex);
        if (indexRegime == null) {
            // FIX: Fallback to NIFTY50 if specific index not available
            indexRegime = indexRegimeCache.get(DEFAULT_INDEX);
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
        SecurityRegime securityRegime = securityRegimeCache.get(scripCode);
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
        ACLOutput acl = aclCache.get(scripCode);
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
        SecurityRegime securityRegime = securityRegimeCache.get(scripCode);
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
