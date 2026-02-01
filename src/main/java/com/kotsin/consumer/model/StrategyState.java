package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * StrategyState - Persisted state for trading strategies.
 *
 * This is the unified state container for all strategy types:
 * - VCP (Volume Cluster Profile) state
 * - IPU (Institutional Participation & Urgency) state
 * - Pivot/Swing points state
 * - Any custom strategy state
 *
 * Design:
 * - One document per symbol per strategy type
 * - State is stored as typed sub-documents
 * - Version field for optimistic locking
 * - TTL for automatic cleanup of stale state
 *
 * Key: {symbol}:{strategyType}:{timeframe}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Document(collection = "strategy_state")
@CompoundIndexes({
    @CompoundIndex(name = "symbol_strategy_tf_idx", def = "{'symbol': 1, 'strategyType': 1, 'timeframe': 1}", unique = true),
    @CompoundIndex(name = "strategy_updated_idx", def = "{'strategyType': 1, 'lastUpdated': -1}")
})
public class StrategyState {

    @Id
    private String id;

    // ==================== IDENTITY ====================
    private String symbol;
    private String scripCode;
    private StrategyType strategyType;
    private String timeframe;  // "1m", "5m", "15m", etc.

    // ==================== VERSIONING ====================
    /**
     * Version for optimistic locking.
     * Incremented on each update to detect concurrent modifications.
     */
    private long version;

    // ==================== TIMING ====================
    @Indexed(expireAfter = "7d")  // TTL: 7 days of inactivity
    private Instant lastUpdated;

    private Instant createdAt;

    // ==================== VCP STATE ====================
    /**
     * Volume Cluster Profile state.
     * List of active clusters with their enrichments.
     */
    private VcpState vcpState;

    // ==================== IPU STATE ====================
    /**
     * Institutional Participation & Urgency state.
     * Rolling history of IPU calculations.
     */
    private IpuState ipuState;

    // ==================== PIVOT STATE ====================
    /**
     * Swing point and pivot level state.
     */
    private PivotState pivotState;

    // ==================== CUSTOM STATE ====================
    /**
     * Generic key-value store for custom strategy state.
     * Use for strategy-specific fields not covered above.
     */
    @Builder.Default
    private Map<String, Object> customState = new HashMap<>();

    // ==================== STRATEGY TYPES ====================

    public enum StrategyType {
        VCP,        // Volume Cluster Profile
        IPU,        // Institutional Participation & Urgency
        PIVOT,      // Pivot/Swing analysis
        FUDKII,     // FUDKII strategy
        REGIME,     // Market regime
        COMBINED    // Combined multi-strategy state
    }

    // ==================== VCP STATE ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VcpState {
        /**
         * Active volume clusters (max 10 per side).
         */
        @Builder.Default
        private List<VolumeCluster> supportClusters = new ArrayList<>();

        @Builder.Default
        private List<VolumeCluster> resistanceClusters = new ArrayList<>();

        /**
         * POC (Point of Control) price level.
         */
        private double pocPrice;

        /**
         * Value Area High/Low.
         */
        private double valueAreaHigh;
        private double valueAreaLow;

        /**
         * Runway scores for directional bias.
         */
        private double bullishRunway;  // 0-1: strength of support below
        private double bearishRunway;  // 0-1: strength of resistance above

        /**
         * Last N candles used for calculation.
         */
        private int lookbackCandles;
        private Instant calculatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VolumeCluster {
        private double price;
        private double strength;      // 0-1
        private long totalVolume;
        private double ofiBias;       // -1 to +1
        private double obValidation;  // Order book validation score
        private double oiAdjustment;  // OI-based adjustment
        private double breakoutDifficulty;
        private double proximity;     // To current price
        private double compositeScore;
        private int contributingCandles;
        private Instant formedAt;
    }

    // ==================== IPU STATE ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IpuState {
        /**
         * Rolling history of IPU scores (last N periods).
         */
        @Builder.Default
        private List<IpuSnapshot> history = new ArrayList<>();

        /**
         * Current IPU values.
         */
        private double currentIpuScore;
        private double currentExhaustion;
        private String currentMomentumState;  // ACCELERATING, DECELERATING, etc.
        private String currentDirection;      // BULLISH, BEARISH, NEUTRAL

        /**
         * Rolling averages for context.
         */
        private double avgIpuScore10;   // 10-period average
        private double avgIpuScore20;   // 20-period average

        /**
         * Trend detection.
         */
        private boolean ipuRising;       // IPU trending up
        private boolean exhaustionBuilding;  // Exhaustion increasing

        private Instant calculatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IpuSnapshot {
        private Instant timestamp;
        private double ipuScore;
        private double exhaustionScore;
        private double instProxy;
        private double momentumContext;
        private String direction;
    }

    // ==================== PIVOT STATE ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PivotState {
        /**
         * Recent swing highs (last N).
         */
        @Builder.Default
        private List<SwingLevel> swingHighs = new ArrayList<>();

        /**
         * Recent swing lows (last N).
         */
        @Builder.Default
        private List<SwingLevel> swingLows = new ArrayList<>();

        /**
         * Key support levels (derived from swing lows).
         */
        @Builder.Default
        private List<PriceLevel> supportLevels = new ArrayList<>();

        /**
         * Key resistance levels (derived from swing highs).
         */
        @Builder.Default
        private List<PriceLevel> resistanceLevels = new ArrayList<>();

        /**
         * Market structure.
         */
        private String structure;  // UPTREND, DOWNTREND, RANGE, CONSOLIDATION
        private boolean higherHighs;
        private boolean higherLows;
        private boolean lowerHighs;
        private boolean lowerLows;

        /**
         * Last significant swing.
         */
        private SwingLevel lastSwingHigh;
        private SwingLevel lastSwingLow;

        private Instant calculatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SwingLevel {
        private double price;
        private Instant timestamp;
        private long volume;
        private int barIndex;
        private boolean confirmed;  // Confirmed by subsequent price action
        private int retestCount;    // Number of times retested
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriceLevel {
        private double price;
        private double strength;    // 0-1 based on touches and volume
        private int touchCount;
        private Instant firstTouch;
        private Instant lastTouch;
        private boolean broken;     // Has been broken
        private Instant brokenAt;
    }

    // ==================== HELPER METHODS ====================

    /**
     * Create key for this state.
     */
    public String getKey() {
        return symbol + ":" + strategyType + ":" + timeframe;
    }

    /**
     * Check if state is stale (not updated in last N minutes).
     */
    public boolean isStale(int minutesThreshold) {
        if (lastUpdated == null) return true;
        return lastUpdated.isBefore(Instant.now().minusSeconds(minutesThreshold * 60L));
    }

    /**
     * Increment version for optimistic locking.
     */
    public void incrementVersion() {
        this.version++;
        this.lastUpdated = Instant.now();
    }

    /**
     * Builder helper for VCP state.
     */
    public static StrategyState forVcp(String symbol, String scripCode, String timeframe) {
        return StrategyState.builder()
            .symbol(symbol)
            .scripCode(scripCode)
            .strategyType(StrategyType.VCP)
            .timeframe(timeframe)
            .version(0)
            .vcpState(VcpState.builder().build())
            .createdAt(Instant.now())
            .lastUpdated(Instant.now())
            .build();
    }

    /**
     * Builder helper for IPU state.
     */
    public static StrategyState forIpu(String symbol, String scripCode, String timeframe) {
        return StrategyState.builder()
            .symbol(symbol)
            .scripCode(scripCode)
            .strategyType(StrategyType.IPU)
            .timeframe(timeframe)
            .version(0)
            .ipuState(IpuState.builder().build())
            .createdAt(Instant.now())
            .lastUpdated(Instant.now())
            .build();
    }

    /**
     * Builder helper for Pivot state.
     */
    public static StrategyState forPivot(String symbol, String scripCode, String timeframe) {
        return StrategyState.builder()
            .symbol(symbol)
            .scripCode(scripCode)
            .strategyType(StrategyType.PIVOT)
            .timeframe(timeframe)
            .version(0)
            .pivotState(PivotState.builder().build())
            .createdAt(Instant.now())
            .lastUpdated(Instant.now())
            .build();
    }
}
