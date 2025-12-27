package com.kotsin.consumer.curated.processor;

import com.kotsin.consumer.capital.model.FinalMagnitude;
import com.kotsin.consumer.curated.model.*;
import com.kotsin.consumer.curated.service.*;
import com.kotsin.consumer.model.IPUOutput;
import com.kotsin.consumer.model.MTVCPOutput;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.regime.model.ACLOutput;
import com.kotsin.consumer.regime.model.IndexRegime;
import com.kotsin.consumer.regime.model.SecurityRegime;
import com.kotsin.consumer.signal.model.CSSOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CuratedSignalProcessor - Main orchestrator for the Curated Signal System
 *
 * This is a PARALLEL system that:
 * - Listens to existing unified-candle topics (1m, 2m, 3m)
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

    // Cache for active breakouts (waiting for retest)
    private final Map<String, MultiTFBreakout> activeBreakouts = new ConcurrentHashMap<>();

    // Cache for latest module outputs
    private final Map<String, IndexRegime> indexRegimeCache = new ConcurrentHashMap<>();
    private final Map<String, SecurityRegime> securityRegimeCache = new ConcurrentHashMap<>();
    private final Map<String, ACLOutput> aclCache = new ConcurrentHashMap<>();
    private final Map<String, MTVCPOutput> vcpCache = new ConcurrentHashMap<>();
    private final Map<String, CSSOutput> cssCache = new ConcurrentHashMap<>();
    private final Map<String, IPUOutput> ipuCache = new ConcurrentHashMap<>();
    private final Map<String, FinalMagnitude> fmaCache = new ConcurrentHashMap<>();

    /**
     * Listen to unified candle streams (1m, 2m, 3m)
     * This is READ-ONLY - does not modify existing flow
     */
    @KafkaListener(
            topics = {"unified-candle-1m", "unified-candle-2m", "unified-candle-3m"},
            groupId = "curated-signal-processor",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void processCandle(UnifiedCandle candle) {
        try {
            String scripCode = candle.getScripCode();
            String timeframe = candle.getTimeframe();

            // 1. Update structure tracker
            structureTracker.updateCandle(candle);

            // 2. Check if we have an active breakout for this scrip
            MultiTFBreakout activeBreakout = activeBreakouts.get(scripCode);

            if (activeBreakout != null) {
                // We're waiting for retest - check if this candle is retesting
                checkForRetest(activeBreakout, candle);
            } else {
                // No active breakout - check if this candle creates a new breakout
                // Only check on 3m candles to avoid spam
                if ("3m".equals(timeframe)) {
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
            groupId = "curated-signal-processor",
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
            groupId = "curated-signal-processor",
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
            groupId = "curated-signal-processor",
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
            groupId = "curated-signal-processor",
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
            groupId = "curated-signal-processor",
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
            groupId = "curated-signal-processor",
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
            groupId = "curated-signal-processor",
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
            // Check gates before adding to active breakouts
            if (!passesGates(scripCode)) {
                log.info("⛔ Breakout rejected for {}: Failed gates", scripCode);
                return;
            }

            log.info("🎯 Adding {} to active breakouts (waiting for retest)", scripCode);
            activeBreakouts.put(scripCode, breakout);
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
     * Generate curated signal when retest is detected (ENHANCED with F&O + Levels)
     */
    private void generateCuratedSignal(MultiTFBreakout breakout, RetestEntry entry) {
        String scripCode = breakout.getScripCode();

        // Fetch all module outputs
        IndexRegime indexRegime = indexRegimeCache.get("NIFTY50");
        SecurityRegime securityRegime = securityRegimeCache.get(scripCode);
        ACLOutput acl = aclCache.get(scripCode);
        MTVCPOutput vcp = vcpCache.get(scripCode);
        CSSOutput css = cssCache.get(scripCode);
        IPUOutput ipu = ipuCache.get(scripCode);
        FinalMagnitude fma = fmaCache.get(scripCode);

        // 🆕 ENHANCED: Fetch F&O alignment data
        FuturesOptionsAlignment foAlignment = null;
        try {
            foAlignment = futuresOptionsService.calculateAlignment(scripCode, entry.getEntryPrice());
            if (foAlignment != null && foAlignment.isUsable()) {
                log.debug("F&O alignment for {}: score={}, bias={}",
                    scripCode,
                    String.format("%.2f", foAlignment.getAlignmentScore()),
                    foAlignment.getBias());
            }
        } catch (Exception e) {
            log.warn("Failed to fetch F&O data for {}: {}", scripCode, e.getMessage());
            // Continue without F&O - graceful degradation
        }

        // 🆕 ENHANCED: Calculate multi-timeframe levels
        MultiTimeframeLevels levels = null;
        try {
            levels = levelCalculator.calculateLevels(scripCode, entry.getEntryPrice());
            if (levels != null) {
                log.debug("Multi-TF levels calculated for {}", scripCode);
            }
        } catch (Exception e) {
            log.warn("Failed to calculate levels for {}: {}", scripCode, e.getMessage());
            // Continue without levels - graceful degradation
        }

        // 🆕 ENHANCED: Optimize entry/stop/target using multi-TF levels
        RetestEntry optimizedEntry = entry;
        if (levels != null) {
            double atr = getATR(scripCode);
            optimizedEntry = retestDetector.optimizeEntryWithLevels(entry, levels, atr);
        }

        // 🆕 ENHANCED: Calculate curated score with F&O + Levels
        double curatedScore = scorer.calculateCuratedScore(
                breakout, indexRegime, securityRegime, acl, vcp, css, ipu,
                foAlignment, levels, optimizedEntry.getEntryPrice()
        );

        // 🆕 Check minimum score threshold (increased from 50.0 to 60.0)
        if (curatedScore < 60.0) {
            log.info("⛔ Signal rejected for {}: Low score={} (minimum=60.0)", scripCode,
                String.format("%.1f", curatedScore));
            return;
        }

        // 🆕 ENHANCED: Calculate position size multiplier with F&O adjustment
        double positionMultiplier = scorer.calculatePositionSizeMultiplier(curatedScore, foAlignment);
        optimizedEntry.setPositionSizeMultiplier(positionMultiplier);

        // 🆕 Build curated reason (enhanced with F&O)
        String reason = buildCuratedReason(breakout, indexRegime, ipu, acl, foAlignment);

        // 🆕 ENHANCED: Build final curated signal with F&O + Levels
        CuratedSignal signal = CuratedSignal.builder()
                .scripCode(scripCode)
                .companyName(vcp != null ? vcp.getCompanyName() : scripCode)
                .timestamp(System.currentTimeMillis())
                .breakout(breakout)
                .pattern(breakout.getPattern())
                .indexRegime(indexRegime)
                .securityRegime(securityRegime)
                .acl(acl)
                .vcp(vcp)
                .css(css)
                .ipu(ipu)
                .finalMagnitude(fma)
                .foAlignment(foAlignment)          // 🆕 F&O alignment
                .levels(levels)                    // 🆕 Multi-TF levels
                .entry(optimizedEntry)             // 🆕 Optimized entry
                .curatedScore(curatedScore)
                .curatedReason(reason)
                .positionSizeMultiplier(positionMultiplier)
                .riskRewardRatio(optimizedEntry.getRiskReward())
                .build();

        // Send to Kafka
        curatedSignalProducer.send("trading-signals-curated", scripCode, signal);

        log.info("📤 ENHANCED CURATED SIGNAL EMITTED: {} | Score={} | Entry={} | Stop={} | Target={} | R:R={}",
                scripCode,
                String.format("%.1f", curatedScore),
                String.format("%.2f", optimizedEntry.getEntryPrice()),
                String.format("%.2f", optimizedEntry.getStopLoss()),
                String.format("%.2f", optimizedEntry.getTarget()),
                String.format("%.2f", optimizedEntry.getRiskReward()));
        if (foAlignment != null && foAlignment.isUsable()) {
            log.info("   F&O: {} (score={}, aligned={})",
                foAlignment.getBias(),
                String.format("%.2f", foAlignment.getAlignmentScore()),
                foAlignment.isAligned());
        }
        log.info("   Reason: {}", reason);
    }

    /**
     * Check if scrip passes all gates
     */
    private boolean passesGates(String scripCode) {
        // Gate 1: Index regime must be tradeable
        IndexRegime indexRegime = indexRegimeCache.get("NIFTY50");
        if (indexRegime == null || !indexRegime.isTradeable()) {
            log.debug("Gate failed: Index regime not tradeable");
            return false;
        }

        // Gate 2: Avoid OPENING/CLOSING sessions
        if (indexRegime.getSessionPhase() == IndexRegime.SessionPhase.OPENING ||
                indexRegime.getSessionPhase() == IndexRegime.SessionPhase.CLOSING) {
            log.debug("Gate failed: Session phase = {}", indexRegime.getSessionPhase());
            return false;
        }

        // Gate 3: Security regime must be aligned with index
        SecurityRegime securityRegime = securityRegimeCache.get(scripCode);
        if (securityRegime == null || !securityRegime.isAlignedWithIndex()) {
            log.debug("Gate failed: Security not aligned with index");
            return false;
        }

        // Gate 4: ACL must allow entry
        ACLOutput acl = aclCache.get(scripCode);
        if (acl == null || !acl.isEntryAllowed()) {
            log.debug("Gate failed: ACL does not allow entry");
            return false;
        }

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
}
