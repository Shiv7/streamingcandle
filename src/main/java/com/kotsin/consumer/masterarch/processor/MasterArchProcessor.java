package com.kotsin.consumer.masterarch.processor;

import com.kotsin.consumer.config.KafkaTopics;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.masterarch.model.*;
import com.kotsin.consumer.masterarch.orchestrator.MasterArchOrchestrator;
import com.kotsin.consumer.model.MTVCPOutput;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MasterArchProcessor - Main processor for MASTER ARCHITECTURE signal generation.
 * 
 * Listens to:
 * - family-candle-30m (primary trigger - 30m is the reference timeframe)
 * - family-candle-5m (secondary for velocity)
 * - family-candle-1d (tertiary for velocity)
 * - vcp-combined (caches VCP scores)
 * 
 * Outputs to:
 * - score-final-opportunity : Final trade decision
 * - masterarch-index-regime : Index context scores  
 * - masterarch-security-regime : Security context scores
 * 
 * Follows MTISProcessor pattern for thread-safe caching and processing.
 */
@Slf4j
@Component
public class MasterArchProcessor {

    @Autowired
    private MasterArchOrchestrator orchestrator;

    @Autowired
    private KafkaTemplate<String, FinalOpportunityScore> finalOpportunityScoreProducer;

    @Autowired
    private KafkaTemplate<String, IndexContextScore> indexContextScoreProducer;

    @Autowired
    private KafkaTemplate<String, SecurityContextScore> securityContextScoreProducer;

    @Value("${masterarch.processor.enabled:true}")
    private boolean enabled;

    @Value("${masterarch.processor.index.scripcode:999920000}")
    private String niftyScripCode;

    // ==================== STATE CACHES ====================

    /** Cache of latest 30m candles for index (NIFTY) */
    private final Map<String, List<InstrumentCandle>> indexCandleCache = new ConcurrentHashMap<>();
    
    /** Cache of VCP scores by scripCode */
    private final Map<String, MTVCPOutput> vcpCache = new ConcurrentHashMap<>();
    
    /** Cache of pivot levels by scripCode */
    private final Map<String, PivotCache> pivotCache = new ConcurrentHashMap<>();
    
    /** Cache of OHM (Option Buyer Friendly) data */
    private final Map<String, OHMCache> ohmCache = new ConcurrentHashMap<>();
    
    /** Cache of latest FamilyCandle by scripCode for multi-TF tracking */
    private final Map<String, FamilyCandle> familyCache = new ConcurrentHashMap<>();

    // ==================== INNER CLASSES ====================

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    static class PivotCache {
        private double nearestPivot;
        private double pivotDistance;
        private String pivotType;
        private long timestamp;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    static class OHMCache {
        private double ohmScore;
        private boolean optionBuyerFriendly;
        private long timestamp;
    }

    // ==================== LIFECYCLE ====================

    @PostConstruct
    public void init() {
        log.info("🏗️ MasterArchProcessor initialized. Enabled: {}", enabled);
        log.info("🏗️ Outputs to: {}, {}, {}", 
                KafkaTopics.SCORE_FINAL_OPPORTUNITY,
                KafkaTopics.MASTER_ARCH_INDEX_REGIME,
                KafkaTopics.MASTER_ARCH_SECURITY_REGIME);
    }

    // ==================== MAIN LISTENER (30M TRIGGER) ====================

    /**
     * Primary trigger: 30m candles trigger full MASTER ARCHITECTURE calculation.
     * Per spec, 30m is the reference timeframe for all formulas.
     */
    @KafkaListener(
            topics = {KafkaTopics.FAMILY_CANDLE_30M},
            groupId = "${kafka.consumer.masterarch-group:masterarch-processor-v1}",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void process30mCandle(FamilyCandle familyCandle) {
        if (!enabled) return;

        try {
            if (familyCandle == null || familyCandle.getEquity() == null) {
                return;
            }

            String scripCode = familyCandle.getFamilyId();
            String symbol = familyCandle.getSymbol();
            
            // Cache the family candle
            familyCache.put(scripCode, familyCandle);

            // Check if this is an index (NIFTY) - cache for other securities
            if (isNiftyFamily(scripCode)) {
                cacheIndexCandles(familyCandle);
                log.debug("📊 Cached NIFTY 30m candle for index regime calculation");
                // Don't process NIFTY as a security, only as index
                return;
            }

            // Get cached data
            List<InstrumentCandle> indexCandles = indexCandleCache.get(niftyScripCode);
            if (indexCandles == null || indexCandles.isEmpty()) {
                log.debug("⏳ Skipping {} - waiting for NIFTY index candles", symbol);
                return;
            }

            double vcpScore = getVCPScore(scripCode);
            PivotCache pivots = pivotCache.getOrDefault(scripCode, 
                    PivotCache.builder().nearestPivot(0).pivotDistance(0).pivotType("NONE").build());
            OHMCache ohm = ohmCache.getOrDefault(scripCode, 
                    OHMCache.builder().ohmScore(0).optionBuyerFriendly(false).build());

            // Run full MASTER ARCHITECTURE orchestration
            MasterArchOrchestrator.MasterArchResult result = orchestrator.process(
                    familyCandle,
                    indexCandles,
                    vcpScore,
                    pivots.getNearestPivot(),
                    pivots.getPivotDistance(),
                    pivots.getPivotType(),
                    ohm.getOhmScore(),
                    ohm.isOptionBuyerFriendly()
            );

            if (result == null) {
                log.debug("No result from MASTER ARCH for {}", symbol);
                return;
            }

            // Emit all scores to respective topics
            emitScores(scripCode, result);

            // Log based on actionability
            if (result.isActionable()) {
                log.info("🎯 MASTER ARCH SIGNAL | {} | decision={} | score={:.2f} | conf={:.2f} | lots={} | {}",
                        symbol,
                        result.getDecision(),
                        result.getFinalScore().getFinalScore().getCurrent(),
                        result.getFinalScore().getDirectionConfidence(),
                        result.getPosition() != null ? result.getPosition().getRecommendedLots() : 0,
                        result.getRationale());
            } else {
                log.debug("[MASTER ARCH] {} | {} | score={:.2f}",
                        symbol,
                        result.getDecision(),
                        result.getFinalScore() != null && result.getFinalScore().getFinalScore() != null 
                            ? result.getFinalScore().getFinalScore().getCurrent() : 0.0);
            }

        } catch (Exception e) {
            log.error("❌ Error processing 30m candle for MASTER ARCH: {}", e.getMessage(), e);
        }
    }

    // ==================== SUPPORTING DATA LISTENERS ====================

    /**
     * Cache VCP output for structural validation
     */
    @KafkaListener(
            topics = {KafkaTopics.VCP_COMBINED},
            groupId = "${kafka.consumer.masterarch-group:masterarch-processor-v1}",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void processVCP(MTVCPOutput vcp) {
        if (vcp == null) return;
        try {
            vcpCache.put(vcp.getScripCode(), vcp);
            log.debug("📊 VCP cached for {}: score={}", vcp.getScripCode(), vcp.getVcpCombinedScore());
        } catch (Exception e) {
            log.error("Error caching VCP: {}", e.getMessage());
        }
    }

    // ==================== SCORE EMISSION ====================

    private void emitScores(String scripCode, MasterArchOrchestrator.MasterArchResult result) {
        try {
            // 1. Emit Final Opportunity Score (main signal)
            if (result.getFinalScore() != null) {
                finalOpportunityScoreProducer.send(
                        KafkaTopics.SCORE_FINAL_OPPORTUNITY,
                        scripCode,
                        result.getFinalScore()
                );
                log.debug("📤 Emitted FinalOpportunityScore for {} to {}", 
                        scripCode, KafkaTopics.SCORE_FINAL_OPPORTUNITY);
            }

            // 2. Emit Index Context Score
            if (result.getIndexContext() != null) {
                indexContextScoreProducer.send(
                        KafkaTopics.MASTER_ARCH_INDEX_REGIME,
                        result.getIndexContext().getScripCode(),
                        result.getIndexContext()
                );
            }

            // 3. Emit Security Context Score
            if (result.getSecurityContext() != null) {
                securityContextScoreProducer.send(
                        KafkaTopics.MASTER_ARCH_SECURITY_REGIME,
                        scripCode,
                        result.getSecurityContext()
                );
            }

        } catch (Exception e) {
            log.error("❌ Failed to emit scores for {}: {}", scripCode, e.getMessage());
        }
    }

    // ==================== HELPER METHODS ====================

    private boolean isNiftyFamily(String scripCode) {
        return niftyScripCode.equals(scripCode) || 
               "NIFTY50".equalsIgnoreCase(scripCode) ||
               scripCode.contains("NIFTY");
    }

    private void cacheIndexCandles(FamilyCandle familyCandle) {
        if (familyCandle.getEquity() != null) {
            // For now, just cache the latest candle
            // TODO: Maintain history for EMA/ATR calculations
            indexCandleCache.put(niftyScripCode, List.of(familyCandle.getEquity()));
        }
    }

    private double getVCPScore(String scripCode) {
        MTVCPOutput vcp = vcpCache.get(scripCode);
        if (vcp != null) {
            return vcp.getVcpCombinedScore();  // Already 0-1 normalized
        }
        return 0.5;  // Neutral if no VCP data
    }

    // ==================== MANAGEMENT ====================

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("enabled", enabled);
        stats.put("familyCacheSize", familyCache.size());
        stats.put("indexCacheSize", indexCandleCache.size());
        stats.put("vcpCacheSize", vcpCache.size());
        stats.put("pivotCacheSize", pivotCache.size());
        stats.put("ohmCacheSize", ohmCache.size());
        return stats;
    }

    public void clearCaches() {
        familyCache.clear();
        indexCandleCache.clear();
        vcpCache.clear();
        pivotCache.clear();
        ohmCache.clear();
        log.info("🗑️ MasterArchProcessor caches cleared");
    }

    /**
     * Manually update pivot cache (can be called from external sources)
     */
    public void updatePivotCache(String scripCode, double nearestPivot, double distance, String type) {
        pivotCache.put(scripCode, PivotCache.builder()
                .nearestPivot(nearestPivot)
                .pivotDistance(distance)
                .pivotType(type)
                .timestamp(System.currentTimeMillis())
                .build());
    }

    /**
     * Manually update OHM cache (can be called from external sources)
     */
    public void updateOHMCache(String scripCode, double ohmScore, boolean optionBuyerFriendly) {
        ohmCache.put(scripCode, OHMCache.builder()
                .ohmScore(ohmScore)
                .optionBuyerFriendly(optionBuyerFriendly)
                .timestamp(System.currentTimeMillis())
                .build());
    }
}
