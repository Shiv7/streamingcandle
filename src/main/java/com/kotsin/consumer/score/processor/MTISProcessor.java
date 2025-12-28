package com.kotsin.consumer.score.processor;

import com.kotsin.consumer.config.KafkaTopics;
import com.kotsin.consumer.curated.model.FuturesOptionsAlignment;
import com.kotsin.consumer.curated.model.MultiTimeframeLevels;
import com.kotsin.consumer.curated.service.MultiTimeframeLevelCalculator;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.model.IPUOutput;
import com.kotsin.consumer.model.MTVCPOutput;
import com.kotsin.consumer.regime.model.IndexRegime;
import com.kotsin.consumer.regime.model.SecurityRegime;
import com.kotsin.consumer.score.calculator.MTISCalculator;
import com.kotsin.consumer.score.model.FamilyIntelligenceState;
import com.kotsin.consumer.score.model.FamilyScore;
import com.kotsin.consumer.signal.model.FUDKIIOutput;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MTISProcessor - Main processor for Multi-Timeframe Intelligence Score.
 * 
 * Listens to:
 * - family-candle-* topics (triggers score calculation)
 * - regime-index-output (caches index regime)
 * - regime-security-output (caches security regime)
 * - ipu-combined (caches IPU output)
 * - fudkii-signals (caches FUDKII output)
 * - vcp-combined (caches VCP output)
 * 
 * Outputs to:
 * - family-score (single topic)
 * 
 * Uses ConcurrentHashMap for thread-safe caching.
 * Follows CuratedSignalProcessor pattern for stability.
 */
@Slf4j
@Component
public class MTISProcessor {

    @Autowired
    private MTISCalculator mtisCalculator;

    @Autowired
    private MultiTimeframeLevelCalculator levelCalculator;

    @Autowired
    private KafkaTemplate<String, FamilyScore> familyScoreProducer;

    @Value("${mtis.processor.enabled:true}")
    private boolean enabled;

    @Value("${mtis.processor.output.topic:family-score}")
    private String outputTopic;

    // ==================== STATE CACHES ====================
    // Thread-safe caches following CuratedSignalProcessor pattern

    private final Map<String, FamilyIntelligenceState> stateCache = new ConcurrentHashMap<>();
    private final Map<String, IndexRegime> indexRegimeCache = new ConcurrentHashMap<>();
    private final Map<String, SecurityRegime> securityRegimeCache = new ConcurrentHashMap<>();
    private final Map<String, IPUOutput> ipuCache = new ConcurrentHashMap<>();
    private final Map<String, FUDKIIOutput> fudkiiCache = new ConcurrentHashMap<>();
    private final Map<String, MTVCPOutput> vcpCache = new ConcurrentHashMap<>();
    private final Map<String, FuturesOptionsAlignment> foAlignmentCache = new ConcurrentHashMap<>();

    // Cache for latest NIFTY regime (for relative strength calculation)
    private volatile IndexRegime latestNiftyRegime;

    @PostConstruct
    public void init() {
        log.info("🎯 MTISProcessor initialized. Enabled: {}, Output topic: {}", enabled, outputTopic);
        log.info("🎯 Listening to: family-candle-*, regime-*, ipu-combined, fudkii-output, vcp-combined");
    }

    // ==================== MAIN LISTENER ====================

    /**
     * Listen to all family candle timeframes.
     * This is the main trigger for MTIS calculation.
     */
    @KafkaListener(
            topics = {
                    KafkaTopics.FAMILY_CANDLE_1M, KafkaTopics.FAMILY_CANDLE_2M, KafkaTopics.FAMILY_CANDLE_3M,
                    KafkaTopics.FAMILY_CANDLE_5M, KafkaTopics.FAMILY_CANDLE_15M, KafkaTopics.FAMILY_CANDLE_30M,
                    KafkaTopics.FAMILY_CANDLE_1H, KafkaTopics.FAMILY_CANDLE_2H, KafkaTopics.FAMILY_CANDLE_4H,
                    KafkaTopics.FAMILY_CANDLE_1D
            },
            groupId = "mtis-processor",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void processCandle(FamilyCandle familyCandle) {
        if (!enabled) return;

        try {
            if (familyCandle == null) return;

            InstrumentCandle equity = familyCandle.getEquity();
            if (equity == null) {
                log.debug("No equity in FamilyCandle for {}", familyCandle.getFamilyId());
                return;
            }

            String familyId = familyCandle.getFamilyId();
            String timeframe = familyCandle.getTimeframe();

            // 1. Get or create state
            FamilyIntelligenceState state = stateCache.computeIfAbsent(familyId, 
                    k -> FamilyIntelligenceState.builder()
                            .familyId(familyId)
                            .symbol(familyCandle.getSymbol())
                            .build());

            // 2. Update TF state
            updateTFState(state, familyCandle, timeframe);

            // 3. Get cached external data
            IndexRegime indexRegime = getIndexRegime(familyId);
            SecurityRegime securityRegime = securityRegimeCache.get(familyId);
            IPUOutput ipu = ipuCache.get(familyId);
            FUDKIIOutput fudkii = fudkiiCache.get(familyId);
            FuturesOptionsAlignment foAlignment = foAlignmentCache.get(familyId);
            double vcpScore = getVCPScore(familyId);

            // 4. Get levels (use existing calculator)
            MultiTimeframeLevels levels = null;
            try {
                levels = levelCalculator.calculateLevels(familyId, familyCandle.getSpotPrice());
            } catch (Exception e) {
                log.debug("Could not calculate levels for {}: {}", familyId, e.getMessage());
            }

            // 5. Calculate MTIS
            FamilyScore score = mtisCalculator.calculate(
                    familyCandle,
                    state,
                    indexRegime,
                    securityRegime,
                    ipu,
                    fudkii,
                    foAlignment,
                    levels,
                    vcpScore
            );

            if (score == null) {
                return;
            }

            // 6. Update state with new MTIS
            updateStateAfterCalculation(state, score, familyCandle);

            // 7. Emit to output topic
            try {
                familyScoreProducer.send(outputTopic, familyId, score);
                
                // Log at INFO for actionable signals, DEBUG for others
                if (score.isActionable()) {
                    log.info("🎯 MTIS | {} | score={} ({}) | {} | F&O={} IPU={} FUDKII={}",
                            familyCandle.getSymbol(),
                            String.format("%+.1f", score.getMtis()),
                            score.getMtisLabel(),
                            timeframe,
                            String.format("%.0f", score.getBreakdown().getFoAlignmentScore()),
                            String.format("%.0f", score.getBreakdown().getIpuScore()),
                            score.isFudkiiIgnition() ? "🔥" : "-");
                } else {
                    log.debug("[{}] MTIS={} ({}) triggered by {} candle",
                            familyCandle.getSymbol(),
                            String.format("%.1f", score.getMtis()),
                            score.getMtisLabel(),
                            timeframe);
                }
            } catch (Exception e) {
                log.error("❌ Failed to send FamilyScore for {}: {}", familyId, e.getMessage());
            }

        } catch (Exception e) {
            log.error("❌ Error processing candle for MTIS: {}", e.getMessage(), e);
        }
    }

    // ==================== EXTERNAL DATA LISTENERS ====================

    @KafkaListener(
            topics = KafkaTopics.REGIME_INDEX,
            groupId = "mtis-processor",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void processIndexRegime(IndexRegime indexRegime) {
        if (indexRegime == null) return;
        try {
            indexRegimeCache.put(indexRegime.getIndexName(), indexRegime);
            
            // Track NIFTY for relative strength
            if ("NIFTY50".equals(indexRegime.getIndexName()) || 
                "999920000".equals(indexRegime.getScripCode())) {
                latestNiftyRegime = indexRegime;
                log.info("📊 MTIS | NIFTY regime updated: {} (bias={})", 
                        indexRegime.getLabel(), 
                        String.format("%.2f", indexRegime.getDirectionalBias()));
            }
        } catch (Exception e) {
            log.error("❌ Error processing IndexRegime: {}", e.getMessage());
        }
    }

    @KafkaListener(
            topics = KafkaTopics.REGIME_SECURITY,
            groupId = "mtis-processor",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void processSecurityRegime(SecurityRegime securityRegime) {
        if (securityRegime == null) return;
        try {
            securityRegimeCache.put(securityRegime.getScripCode(), securityRegime);
        } catch (Exception e) {
            log.error("Error processing SecurityRegime: {}", e.getMessage());
        }
    }

    @KafkaListener(
            topics = KafkaTopics.IPU_COMBINED,
            groupId = "mtis-processor",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void processIPU(IPUOutput ipu) {
        if (ipu == null) return;
        try {
            ipuCache.put(ipu.getScripCode(), ipu);
        } catch (Exception e) {
            log.error("Error processing IPU: {}", e.getMessage());
        }
    }

    @KafkaListener(
            topics = KafkaTopics.FUDKII_OUTPUT,  // "fudkii-output" - CRITICAL: was wrong
            groupId = "mtis-processor",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void processFUDKII(FUDKIIOutput fudkii) {
        if (fudkii == null) return;
        try {
            fudkiiCache.put(fudkii.getScripCode(), fudkii);
            if (fudkii.isIgnitionFlag()) {
                log.info("🔥 MTIS | FUDKII ignition cached for {} (sim={})", 
                        fudkii.getScripCode(), fudkii.getSimultaneityScore());
            }
        } catch (Exception e) {
            log.error("❌ Error processing FUDKII: {}", e.getMessage());
        }
    }

    @KafkaListener(
            topics = KafkaTopics.VCP_COMBINED,
            groupId = "mtis-processor",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void processVCP(MTVCPOutput vcp) {
        if (vcp == null) return;
        try {
            vcpCache.put(vcp.getScripCode(), vcp);
        } catch (Exception e) {
            log.error("Error processing VCP: {}", e.getMessage());
        }
    }

    // ==================== HELPER METHODS ====================

    private void updateTFState(FamilyIntelligenceState state, FamilyCandle family, String timeframe) {
        InstrumentCandle equity = family.getEquity();
        
        // Calculate TF-specific score (simplified for now)
        double tfScore = calculateSimpleTFScore(family);
        
        state.updateTFState(
                timeframe,
                tfScore,
                equity.getVwap(),
                equity.getClose(),
                family.isHasFuture() && family.getFuture() != null && family.getFuture().hasOI(),
                equity.hasOrderbook(),
                family.isHasFuture(),
                family.isHasOptions()
        );
    }

    private double calculateSimpleTFScore(FamilyCandle family) {
        InstrumentCandle equity = family.getEquity();
        double score = 0;
        
        // Price vs VWAP
        if (equity.getVwap() > 0) {
            double vwapDist = (equity.getClose() - equity.getVwap()) / equity.getVwap() * 100;
            score += clamp(vwapDist * 5, -15, 15);
        }
        
        // Volume delta
        score += clamp(equity.getVolumeDeltaPercent() / 5, -10, 10);
        
        // OI signal
        if (family.getOiSignal() != null) {
            if (family.getOiSignal().contains("BULLISH")) score += 10;
            else if (family.getOiSignal().contains("BEARISH")) score -= 10;
        }
        
        return clamp(score, -50, 50);
    }

    private void updateStateAfterCalculation(FamilyIntelligenceState state, FamilyScore score, 
                                              FamilyCandle family) {
        // Update MTIS values
        state.setPreviousMtis(state.getMtis());
        state.setMtis(score.getMtis());
        state.setLastMtisUpdate(System.currentTimeMillis());
        
        // Track previous values for divergence detection
        Long futureOI = null;
        if (family.getFuture() != null) {
            futureOI = family.getFuture().getOpenInterest();
        }
        state.trackPreviousValues(family.getSpotPrice(), futureOI, family.getPcr());
        
        // Update external data cache in state
        IPUOutput ipu = ipuCache.get(family.getFamilyId());
        if (ipu != null) {
            state.setLatestIpuScore(ipu.getFinalIpuScore());
            state.setLatestIpuUrgency(ipu.getUrgencyLevel() != null ? ipu.getUrgencyLevel().name() : null);
            state.setLatestIpuExhaustion(ipu.isExhaustionWarning());
        }
        
        FUDKIIOutput fudkii = fudkiiCache.get(family.getFamilyId());
        if (fudkii != null) {
            state.setLatestFudkiiIgnition(fudkii.isIgnitionFlag());
            state.setLatestFudkiiSimultaneity(fudkii.getSimultaneityScore());
        }
    }

    private IndexRegime getIndexRegime(String familyId) {
        // Try to find specific index regime, fallback to NIFTY
        IndexRegime regime = indexRegimeCache.get(familyId);
        if (regime != null) return regime;
        
        // Fallback to NIFTY for relative strength calculations
        return latestNiftyRegime;
    }

    private double getVCPScore(String familyId) {
        MTVCPOutput vcp = vcpCache.get(familyId);
        if (vcp != null) {
            // Use combined score if available
            return vcp.getVcpCombinedScore() * 100;  // Convert 0-1 to 0-100
        }
        return 0;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ==================== MANAGEMENT ====================

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("enabled", enabled);
        stats.put("stateCount", stateCache.size());
        stats.put("indexRegimeCount", indexRegimeCache.size());
        stats.put("securityRegimeCount", securityRegimeCache.size());
        stats.put("ipuCount", ipuCache.size());
        stats.put("fudkiiCount", fudkiiCache.size());
        stats.put("vcpCount", vcpCache.size());
        return stats;
    }

    public void clearCaches() {
        stateCache.clear();
        indexRegimeCache.clear();
        securityRegimeCache.clear();
        ipuCache.clear();
        fudkiiCache.clear();
        vcpCache.clear();
        foAlignmentCache.clear();
        log.info("MTIS caches cleared");
    }
}
