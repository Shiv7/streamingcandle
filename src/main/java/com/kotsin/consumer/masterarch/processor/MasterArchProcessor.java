package com.kotsin.consumer.masterarch.processor;

import com.kotsin.consumer.config.KafkaTopics;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.infrastructure.redis.RedisCandleHistoryService;
import com.kotsin.consumer.masterarch.model.*;
import com.kotsin.consumer.masterarch.orchestrator.MasterArchOrchestrator;
import com.kotsin.consumer.model.MTVCPOutput;
import com.kotsin.consumer.model.UnifiedCandle;
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
 * - kotsin_FF1 : Final trade decision
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

    @Autowired
    private RedisCandleHistoryService candleHistoryService;

    @Value("${masterarch.processor.enabled:true}")
    private boolean enabled;

    @Value("${masterarch.processor.index.scripcode:999920000}")
    private String niftyScripCode;

    @Value("${masterarch.processor.history.candles:100}")
    private int historyCandles;

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
                KafkaTopics.KOTSIN_FF1,
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
            groupId = "${kafka.consumer.masterarch-group:masterarch-processor-v2}",
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

            // Store current candle in Redis for history
            storeSecurityCandle(familyCandle);

            // Get historical candles from Redis (100 30m candles for security)
            String exchange = familyCandle.getEquity().getExchange();
            String exchangeType = familyCandle.getEquity().getExchangeType();
            List<UnifiedCandle> securityCandles = candleHistoryService.getCandles(
                    scripCode, "30m", historyCandles, exchange, exchangeType);
            
            // Get historical index candles from Redis (100 30m candles for NIFTY)
            List<UnifiedCandle> indexCandles = candleHistoryService.getCandles(
                    niftyScripCode, "30m", historyCandles, "N", "C");
            
            if (indexCandles == null || indexCandles.size() < 55) {
                log.debug("⏳ Skipping {} - need 55+ NIFTY candles, have {}", 
                        symbol, indexCandles != null ? indexCandles.size() : 0);
                return;
            }
            
            if (securityCandles == null || securityCandles.size() < 55) {
                log.debug("⏳ Skipping {} - need 55+ security candles, have {}", 
                        symbol, securityCandles != null ? securityCandles.size() : 0);
                return;
            }

            double vcpScore = getVCPScore(scripCode);
            PivotCache pivots = pivotCache.getOrDefault(scripCode, 
                    PivotCache.builder().nearestPivot(0).pivotDistance(0).pivotType("NONE").build());
            OHMCache ohm = ohmCache.getOrDefault(scripCode, 
                    OHMCache.builder().ohmScore(0).optionBuyerFriendly(false).build());

            // Run full MASTER ARCHITECTURE orchestration with historical candles
            MasterArchOrchestrator.MasterArchResult result = orchestrator.process(
                    familyCandle,
                    securityCandles,
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

            // Log based on actionability with enhanced details
            if (result.isActionable()) {
                log.info("🎯 MASTER ARCH SIGNAL | {} | decision={} | finalScore={:.3f} | direction={} | lots={}",
                        symbol,
                        result.getDecision(),
                        result.getFinalScore().getFinalScore().getCurrent(),
                        result.getFinalScore().getDirectionConfidence() > 0 ? "BULLISH" : "BEARISH",
                        result.getPosition() != null ? result.getPosition().getRecommendedLots() : 0);
                
                // Log component scores
                log.info("   📊 Components | index={:.2f} | security={:.2f} | fudkii={:.2f} | volume={:.2f}",
                        result.getIndexContext() != null ? result.getIndexContext().getContextScore().getCurrent() : 0,
                        result.getSecurityContext() != null ? result.getSecurityContext().getContextScore().getCurrent() : 0,
                        result.getFudkii() != null ? result.getFudkii().getStrength() : 0,
                        result.getVolume() != null ? result.getVolume().getVolumeCertainty() : 0);
                
                // Log microstructure if present
                if (result.getMicrostructure() != null) {
                    var micro = result.getMicrostructure();
                    log.info("   🔬 Microstructure | VPIN={:.2f}({}) | OFI={:.2f}({}) | PCR={:.2f}({}) | oiBuildUp={} | mult={:.2f}",
                            micro.getVpin(), micro.getVpinSignal(),
                            micro.getOfi(), micro.getOfiDirection(),
                            micro.getPcr(), micro.getPcrSignal(),
                            micro.isOiBuildingUp(),
                            micro.getMicrostructureMultiplier());
                }
            } else {
                // Log all signals, not just actionable ones
                log.info("📈 {} | {} | finalScore={:.3f} | fudkii={:.2f} | micro={}",
                        symbol,
                        result.getDecision(),
                        result.getFinalScore() != null && result.getFinalScore().getFinalScore() != null 
                            ? result.getFinalScore().getFinalScore().getCurrent() : 0.0,
                        result.getFudkii() != null ? result.getFudkii().getStrength() : 0.0,
                        result.getMicrostructure() != null ? 
                            String.format("%.2f", result.getMicrostructure().getMicrostructureMultiplier()) : "N/A");
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
            groupId = "${kafka.consumer.masterarch-group:masterarch-processor-v2}",
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
                        KafkaTopics.KOTSIN_FF1,
                        scripCode,
                        result.getFinalScore()
                );
                log.debug("📤 Emitted FinalOpportunityScore for {} to {}", 
                        scripCode, KafkaTopics.KOTSIN_FF1);
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
            // Store index candle in Redis for historical retrieval
            InstrumentCandle equity = familyCandle.getEquity();
            UnifiedCandle candle = convertToUnifiedCandle(equity);
            candleHistoryService.storeCandle(candle);
            log.trace("Stored NIFTY 30m candle in Redis at {}", equity.getWindowStartMillis());
        }
    }

    private void storeSecurityCandle(FamilyCandle familyCandle) {
        if (familyCandle.getEquity() != null) {
            InstrumentCandle equity = familyCandle.getEquity();
            UnifiedCandle candle = convertToUnifiedCandle(equity);
            candleHistoryService.storeCandle(candle);
        }
    }

    private UnifiedCandle convertToUnifiedCandle(InstrumentCandle ic) {
        return UnifiedCandle.builder()
                .scripCode(ic.getScripCode())
                .companyName(ic.getCompanyName())
                .exchange(ic.getExchange())
                .exchangeType(ic.getExchangeType())
                .timeframe(ic.getTimeframe())
                .windowStartMillis(ic.getWindowStartMillis())
                .windowEndMillis(ic.getWindowEndMillis())
                .open(ic.getOpen())
                .high(ic.getHigh())
                .low(ic.getLow())
                .close(ic.getClose())
                .volume(ic.getVolume())
                .buyVolume(ic.getBuyVolume())
                .sellVolume(ic.getSellVolume())
                .vwap(ic.getVwap())
                .build();
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
