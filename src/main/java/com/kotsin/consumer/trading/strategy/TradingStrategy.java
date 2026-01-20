package com.kotsin.consumer.trading.strategy;

import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import com.kotsin.consumer.trading.model.Position;
import com.kotsin.consumer.trading.model.TradeOutcome.ExitReason;
import lombok.Builder;
import lombok.Data;

import java.util.Optional;

/**
 * TradingStrategy - Interface for all trading strategies.
 *
 * Each strategy must implement:
 * 1. Setup detection (IDLE → WATCHING)
 * 2. Entry trigger (WATCHING → READY)
 * 3. Exit conditions (POSITIONED → COOLDOWN)
 *
 * The strategy does NOT manage state - that's InstrumentStateManager's job.
 * The strategy only answers: "Is setup forming?", "Should we enter?", "Should we exit?"
 */
public interface TradingStrategy {

    /**
     * Get unique strategy identifier
     */
    String getStrategyId();

    /**
     * Get strategy description
     */
    String getDescription();

    /**
     * Get strategy parameters
     */
    StrategyParams getParams();

    /**
     * Check if a setup is forming (used for IDLE → WATCHING transition)
     *
     * This is the FIRST filter. It answers:
     * "Is this instrument showing early signs of a tradeable setup?"
     *
     * Example for PIVOT_RETEST:
     * - Price approaching a pivot level (within 1%)
     * - Trend direction clear (SuperTrend aligned)
     *
     * @param score Enriched quant score with all 68 fields
     * @return SetupContext if setup is forming, empty otherwise
     */
    Optional<SetupContext> detectSetupForming(EnrichedQuantScore score);

    /**
     * Check if entry trigger fired (used for WATCHING → READY transition)
     *
     * This is the SECOND filter. It answers:
     * "Should we enter NOW?"
     *
     * Example for PIVOT_RETEST:
     * - Price at pivot level (within 0.3%)
     * - Rejection candle formed
     * - OFI confirms direction
     *
     * @param score Enriched quant score with all 68 fields
     * @param setupContext Context from when we started watching
     * @return TradingSignal if entry triggered, empty otherwise
     */
    Optional<TradingSignal> checkEntryTrigger(EnrichedQuantScore score, SetupContext setupContext);

    /**
     * Check if exit conditions met (used for POSITIONED management)
     *
     * @param position Current position
     * @param score Current market data
     * @return ExitSignal if should exit, empty otherwise
     */
    Optional<ExitSignal> checkExitConditions(Position position, EnrichedQuantScore score);

    /**
     * Check if setup should be invalidated (WATCHING → IDLE)
     *
     * @param score Current market data
     * @param setupContext Context from when we started watching
     * @return Invalidation reason if setup is no longer valid, empty otherwise
     */
    Optional<String> checkSetupInvalidation(EnrichedQuantScore score, SetupContext setupContext);

    // ======================== INNER CLASSES ========================

    /**
     * Strategy parameters
     */
    @Data
    @Builder
    class StrategyParams {
        private String strategyId;

        // Timing
        private String requiredTimeframe;     // e.g., "30m" for FUDKII
        private long maxWatchingDurationMs;   // How long to stay in WATCHING
        private long maxPositionDurationMs;   // Time stop
        private long cooldownDurationMs;      // Post-trade rest

        // Risk
        private double defaultStopPct;        // Default stop loss %
        private double target1Pct;            // Target 1 %
        private double target2Pct;            // Target 2 %
        private double minRiskRewardRatio;    // Minimum R:R to take trade

        // Filters
        private double minConfidence;         // Minimum signal confidence
        private double minOfiZscore;          // Minimum OFI z-score
        private double maxVpin;               // Maximum VPIN (avoid informed trading against us)

        // Session
        private double minSessionPosition;    // Don't SHORT below this
        private double maxSessionPosition;    // Don't LONG above this
    }

    /**
     * Setup context - captured when we start watching
     */
    @Data
    @Builder
    class SetupContext {
        private String strategyId;
        private String familyId;
        private long watchingStartTime;

        // The setup we're watching
        private String setupDescription;
        private double keyLevel;              // The pivot/support/resistance level
        private double entryZone;             // Where we want to enter
        private double proposedStop;          // Where stop would go
        private double proposedTarget1;
        private double proposedTarget2;

        // Direction
        private com.kotsin.consumer.enrichment.signal.model.TradingSignal.Direction direction;

        // Conditions at setup detection
        private double priceAtDetection;
        private boolean superTrendAligned;
        private double ofiZscoreAtDetection;
    }

    /**
     * Exit signal
     */
    @Data
    @Builder
    class ExitSignal {
        private ExitReason reason;
        private double exitPrice;
        private String description;
        private boolean partial;              // True if partial exit (e.g., T1 hit)
        private double exitQuantityPct;       // % of position to exit (e.g., 0.5 for 50%)
    }
}
