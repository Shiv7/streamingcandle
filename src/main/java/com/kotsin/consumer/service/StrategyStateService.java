package com.kotsin.consumer.service;

import com.kotsin.consumer.model.StrategyState;
import com.kotsin.consumer.model.StrategyState.*;
import com.kotsin.consumer.repository.StrategyStateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StrategyStateService - Service for managing strategy state with Redis caching.
 *
 * Design:
 * - Read-through cache: Check Redis first, fallback to MongoDB
 * - Write-through cache: Update both Redis and MongoDB
 * - Optimistic locking: Version check on update
 * - TTL-based expiry: State expires after inactivity
 *
 * Key Structure:
 * - strategy:{symbol}:{type}:{tf} → StrategyState JSON
 */
@Service
@Slf4j
public class StrategyStateService {

    @Autowired
    private StrategyStateRepository stateRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${v2.strategy.state.cache.ttl.minutes:30}")
    private int cacheTtlMinutes;

    @Value("${v2.strategy.state.cache.enabled:true}")
    private boolean cacheEnabled;

    // Local cache for hot state (reduces Redis calls)
    private final ConcurrentHashMap<String, StrategyState> localCache = new ConcurrentHashMap<>();

    private static final int LOCAL_CACHE_MAX_SIZE = 1000;

    // ==================== GET STATE ====================

    /**
     * Get strategy state (read-through cache).
     */
    public Optional<StrategyState> getState(String symbol, StrategyType type, String timeframe) {
        String key = buildKey(symbol, type, timeframe);

        // Check local cache first
        StrategyState localState = localCache.get(key);
        if (localState != null && !localState.isStale(5)) {
            return Optional.of(localState);
        }

        // Check Redis cache
        if (cacheEnabled) {
            try {
                StrategyState cachedState = (StrategyState) redisTemplate.opsForValue().get(key);
                if (cachedState != null) {
                    updateLocalCache(key, cachedState);
                    return Optional.of(cachedState);
                }
            } catch (Exception e) {
                log.warn("[STRATEGY-STATE] Redis get failed for {}: {}", key, e.getMessage());
            }
        }

        // Fallback to MongoDB
        Optional<StrategyState> dbState = stateRepository.findBySymbolAndStrategyTypeAndTimeframe(
            symbol, type, timeframe);

        if (dbState.isPresent()) {
            // Populate caches
            if (cacheEnabled) {
                cacheState(dbState.get());
            }
            updateLocalCache(key, dbState.get());
        }

        return dbState;
    }

    /**
     * Get VCP state for a symbol.
     */
    public Optional<VcpState> getVcpState(String symbol, String timeframe) {
        return getState(symbol, StrategyType.VCP, timeframe)
            .map(StrategyState::getVcpState);
    }

    /**
     * Get IPU state for a symbol.
     */
    public Optional<IpuState> getIpuState(String symbol, String timeframe) {
        return getState(symbol, StrategyType.IPU, timeframe)
            .map(StrategyState::getIpuState);
    }

    /**
     * Get Pivot state for a symbol.
     */
    public Optional<PivotState> getPivotState(String symbol, String timeframe) {
        return getState(symbol, StrategyType.PIVOT, timeframe)
            .map(StrategyState::getPivotState);
    }

    // ==================== SAVE STATE ====================

    /**
     * Save strategy state (write-through cache).
     */
    public StrategyState saveState(StrategyState state) {
        state.incrementVersion();
        state.setLastUpdated(Instant.now());

        // Save to MongoDB
        StrategyState saved = stateRepository.save(state);

        // Update caches
        String key = buildKey(state.getSymbol(), state.getStrategyType(), state.getTimeframe());
        if (cacheEnabled) {
            cacheState(saved);
        }
        updateLocalCache(key, saved);

        log.debug("[STRATEGY-STATE] Saved state for {} v{}", key, saved.getVersion());
        return saved;
    }

    /**
     * Save VCP state.
     */
    public StrategyState saveVcpState(String symbol, String scripCode, String timeframe, VcpState vcpState) {
        StrategyState state = getState(symbol, StrategyType.VCP, timeframe)
            .orElse(StrategyState.forVcp(symbol, scripCode, timeframe));

        state.setVcpState(vcpState);
        return saveState(state);
    }

    /**
     * Save IPU state.
     */
    public StrategyState saveIpuState(String symbol, String scripCode, String timeframe, IpuState ipuState) {
        StrategyState state = getState(symbol, StrategyType.IPU, timeframe)
            .orElse(StrategyState.forIpu(symbol, scripCode, timeframe));

        state.setIpuState(ipuState);
        return saveState(state);
    }

    /**
     * Save Pivot state.
     */
    public StrategyState savePivotState(String symbol, String scripCode, String timeframe, PivotState pivotState) {
        StrategyState state = getState(symbol, StrategyType.PIVOT, timeframe)
            .orElse(StrategyState.forPivot(symbol, scripCode, timeframe));

        state.setPivotState(pivotState);
        return saveState(state);
    }

    // ==================== UPDATE OPERATIONS ====================

    /**
     * Update VCP clusters.
     */
    public void updateVcpClusters(String symbol, String scripCode, String timeframe,
                                   List<VolumeCluster> supportClusters,
                                   List<VolumeCluster> resistanceClusters) {
        VcpState vcpState = getVcpState(symbol, timeframe)
            .orElse(VcpState.builder().build());

        vcpState.setSupportClusters(supportClusters);
        vcpState.setResistanceClusters(resistanceClusters);
        vcpState.setCalculatedAt(Instant.now());

        saveVcpState(symbol, scripCode, timeframe, vcpState);
    }

    /**
     * Add IPU snapshot to history.
     */
    public void addIpuSnapshot(String symbol, String scripCode, String timeframe, IpuSnapshot snapshot) {
        IpuState ipuState = getIpuState(symbol, timeframe)
            .orElse(IpuState.builder().history(new ArrayList<>()).build());

        // Add to history (keep last 100)
        List<IpuSnapshot> history = ipuState.getHistory();
        if (history == null) {
            history = new ArrayList<>();
        }
        history.add(0, snapshot);  // Most recent first
        if (history.size() > 100) {
            history = new ArrayList<>(history.subList(0, 100));
        }
        ipuState.setHistory(history);

        // Update current values
        ipuState.setCurrentIpuScore(snapshot.getIpuScore());
        ipuState.setCurrentExhaustion(snapshot.getExhaustionScore());
        ipuState.setCurrentDirection(snapshot.getDirection());
        ipuState.setCalculatedAt(Instant.now());

        // Calculate rolling averages
        if (history.size() >= 10) {
            double avg10 = history.subList(0, 10).stream()
                .mapToDouble(IpuSnapshot::getIpuScore).average().orElse(0);
            ipuState.setAvgIpuScore10(avg10);
        }
        if (history.size() >= 20) {
            double avg20 = history.subList(0, 20).stream()
                .mapToDouble(IpuSnapshot::getIpuScore).average().orElse(0);
            ipuState.setAvgIpuScore20(avg20);
        }

        saveIpuState(symbol, scripCode, timeframe, ipuState);
    }

    /**
     * Add swing point to pivot state.
     */
    public void addSwingPoint(String symbol, String scripCode, String timeframe,
                               SwingLevel swing, boolean isHigh) {
        PivotState pivotState = getPivotState(symbol, timeframe)
            .orElse(PivotState.builder()
                .swingHighs(new ArrayList<>())
                .swingLows(new ArrayList<>())
                .supportLevels(new ArrayList<>())
                .resistanceLevels(new ArrayList<>())
                .build());

        List<SwingLevel> swings = isHigh ? pivotState.getSwingHighs() : pivotState.getSwingLows();
        if (swings == null) {
            swings = new ArrayList<>();
        }

        // Add new swing (keep last 20)
        swings.add(0, swing);
        if (swings.size() > 20) {
            swings = new ArrayList<>(swings.subList(0, 20));
        }

        if (isHigh) {
            pivotState.setSwingHighs(swings);
            pivotState.setLastSwingHigh(swing);
        } else {
            pivotState.setSwingLows(swings);
            pivotState.setLastSwingLow(swing);
        }

        // Update structure detection
        updateMarketStructure(pivotState);

        pivotState.setCalculatedAt(Instant.now());
        savePivotState(symbol, scripCode, timeframe, pivotState);
    }

    /**
     * Update market structure based on swing points.
     */
    private void updateMarketStructure(PivotState state) {
        List<SwingLevel> highs = state.getSwingHighs();
        List<SwingLevel> lows = state.getSwingLows();

        if (highs == null || highs.size() < 2 || lows == null || lows.size() < 2) {
            state.setStructure("UNKNOWN");
            return;
        }

        // Check last 3 swings for pattern
        boolean hh = highs.size() >= 2 && highs.get(0).getPrice() > highs.get(1).getPrice();
        boolean hl = lows.size() >= 2 && lows.get(0).getPrice() > lows.get(1).getPrice();
        boolean lh = highs.size() >= 2 && highs.get(0).getPrice() < highs.get(1).getPrice();
        boolean ll = lows.size() >= 2 && lows.get(0).getPrice() < lows.get(1).getPrice();

        state.setHigherHighs(hh);
        state.setHigherLows(hl);
        state.setLowerHighs(lh);
        state.setLowerLows(ll);

        // Determine structure
        if (hh && hl) {
            state.setStructure("UPTREND");
        } else if (lh && ll) {
            state.setStructure("DOWNTREND");
        } else if (lh && hl) {
            state.setStructure("CONSOLIDATION");
        } else {
            state.setStructure("RANGE");
        }
    }

    // ==================== DELETE OPERATIONS ====================

    /**
     * Delete state for a symbol.
     */
    public void deleteState(String symbol) {
        stateRepository.deleteBySymbol(symbol);

        // Clear from caches
        for (StrategyType type : StrategyType.values()) {
            for (String tf : Arrays.asList("1m", "5m", "15m", "30m", "1h")) {
                String key = buildKey(symbol, type, tf);
                localCache.remove(key);
                if (cacheEnabled) {
                    try {
                        redisTemplate.delete(key);
                    } catch (Exception e) {
                        log.warn("[STRATEGY-STATE] Redis delete failed for {}", key);
                    }
                }
            }
        }

        log.info("[STRATEGY-STATE] Deleted all state for {}", symbol);
    }

    /**
     * Clean up stale states.
     */
    public int cleanupStaleStates(int staleMinutes) {
        Instant threshold = Instant.now().minusSeconds(staleMinutes * 60L);
        List<StrategyState> staleStates = new ArrayList<>();

        for (StrategyType type : StrategyType.values()) {
            staleStates.addAll(stateRepository.findStaleStates(type, threshold));
        }

        if (!staleStates.isEmpty()) {
            stateRepository.deleteByLastUpdatedBefore(threshold);
            log.info("[STRATEGY-STATE] Cleaned up {} stale states older than {} minutes",
                staleStates.size(), staleMinutes);
        }

        return staleStates.size();
    }

    // ==================== QUERY OPERATIONS ====================

    /**
     * Get all active symbols with VCP state.
     */
    public List<String> getSymbolsWithActiveVcp() {
        return stateRepository.findSymbolsWithActiveVcpState().stream()
            .map(StrategyState::getSymbol)
            .distinct()
            .toList();
    }

    /**
     * Get symbols with high IPU score.
     */
    public List<String> getSymbolsWithHighIpu(double minScore) {
        return stateRepository.findSymbolsWithHighIpuScore(minScore).stream()
            .map(StrategyState::getSymbol)
            .distinct()
            .toList();
    }

    /**
     * Get state count by type.
     */
    public Map<StrategyType, Long> getStateCounts() {
        Map<StrategyType, Long> counts = new HashMap<>();
        for (StrategyType type : StrategyType.values()) {
            counts.put(type, stateRepository.countByStrategyType(type));
        }
        return counts;
    }

    // ==================== CACHE HELPERS ====================

    private String buildKey(String symbol, StrategyType type, String timeframe) {
        return String.format("strategy:%s:%s:%s", symbol, type.name().toLowerCase(), timeframe);
    }

    private void cacheState(StrategyState state) {
        String key = buildKey(state.getSymbol(), state.getStrategyType(), state.getTimeframe());
        try {
            redisTemplate.opsForValue().set(key, state, Duration.ofMinutes(cacheTtlMinutes));
        } catch (Exception e) {
            log.warn("[STRATEGY-STATE] Redis cache failed for {}: {}", key, e.getMessage());
        }
    }

    private void updateLocalCache(String key, StrategyState state) {
        // Evict old entries if cache is full
        if (localCache.size() >= LOCAL_CACHE_MAX_SIZE) {
            // Simple eviction: remove first 10%
            int toRemove = LOCAL_CACHE_MAX_SIZE / 10;
            Iterator<String> iter = localCache.keySet().iterator();
            while (toRemove > 0 && iter.hasNext()) {
                iter.next();
                iter.remove();
                toRemove--;
            }
        }
        localCache.put(key, state);
    }

    /**
     * Clear all caches (for testing).
     */
    public void clearCaches() {
        localCache.clear();
        log.info("[STRATEGY-STATE] Cleared local cache");
    }
}
