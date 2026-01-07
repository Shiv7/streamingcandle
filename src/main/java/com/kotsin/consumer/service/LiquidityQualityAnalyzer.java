package com.kotsin.consumer.service;

import com.kotsin.consumer.model.UnifiedCandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * LiquidityQualityAnalyzer - Assess market liquidity quality
 * 
 * PHASE 1 ENHANCEMENT: Liquidity-aware signal filtering
 * 
 * CRITICAL INSIGHT: You can have a perfect signal setup, but if liquidity is poor:
 * - Wide spreads = slippage kills your edge
 * - Low tick density = price can gap through stops
 * - No algo activity = low participation, erratic moves
 * 
 * This analyzer uses tick microstructure to:
 * 1. Score liquidity quality (0-1)
 * 2. Estimate execution slippage
 * 3. Flag instruments to avoid (illiquid death traps!)
 * 4. Adjust position sizing based on liquidity
 */
@Slf4j
@Service
public class LiquidityQualityAnalyzer {

    // Tick density thresholds (ticks per second)
    private static final double HIGH_LIQUIDITY_TPS = 10.0;     // Highly liquid (e.g., NIFTY)
    private static final double MEDIUM_LIQUIDITY_TPS = 5.0;    // Moderately liquid
    private static final double LOW_LIQUIDITY_TPS = 2.0;       // Low liquidity
    // Below 2 TPS = very illiquid (avoid!)

    // Spread thresholds (basis points)
    private static final double TIGHT_SPREAD_BPS = 10.0;       // < 10 bps = tight
    private static final double MODERATE_SPREAD_BPS = 25.0;    // < 25 bps = moderate
    private static final double WIDE_SPREAD_BPS = 50.0;        // > 50 bps = wide (risky!)

    /**
     * Liquidity quality tiers
     */
    public enum LiquidityTier {
        EXCELLENT,      // 1.0 score - tight spreads, high tick density, algos active
        GOOD,           // 0.7-0.9 score
        MODERATE,       // 0.4-0.6 score
        POOR,           // 0.2-0.3 score
        VERY_POOR       // < 0.2 score - AVOID TRADING!
    }

    /**
     * Calculate comprehensive liquidity quality score (0-1)
     * 
     * Combines:
     * - Tick density (how many ticks per second)
     * - Spread quality (tight vs wide)
     * - Spread stability (volatile spreads = liquidity withdrawal!)
     * 
     * @param current UnifiedCandle
     * @return Liquidity score 0-1 (1.0 = excellent, 0.0 = avoid!)
     */
    public double calculateLiquidityScore(UnifiedCandle current) {
        // Component 1: Tick density Score (50% weight)
        double tickDensity = current.getTickCount() / 60.0;  // Ticks per second
        double tickScore = calculateTickDensityScore(tickDensity);

        // Component 2: Spread Quality Score (40% weight)
        double spreadBps = calculateSpreadBps(current);
        double spreadScore = calculateSpreadScore(spreadBps);

        // Component 3: Spread Stability Score (10% weight) - UnifiedCandle doesn't have spreadVolatility
        // Use a conservative fixed value for now
        double stabilityScore = 0.7;  // Assume moderate stability

        // Weighted combination
        double liquidityScore = (tickScore * 0.5) 
                              + (spreadScore * 0.4) 
                              + (stabilityScore * 0.1);

        // Log if poor liquidity
        if (liquidityScore < 0.4) {
            log.warn("⚠️ POOR LIQUIDITY for {}: score={:.2f} | ticks/s={:.2f} | spread={}bps",
                    current.getScripCode(), liquidityScore, tickDensity, (int)spreadBps);
        }

        return Math.max(0.0, Math.min(1.0, liquidityScore));
    }

    /**
     * Calculate tick density score
     */
    private double calculateTickDensityScore(double ticksPerSecond) {
        if (ticksPerSecond >= HIGH_LIQUIDITY_TPS) {
            return 1.0;  // Excellent
        } else if (ticksPerSecond >= MEDIUM_LIQUIDITY_TPS) {
            // Linear interpolation between medium and high
            return 0.7 + (0.3 * (ticksPerSecond - MEDIUM_LIQUIDITY_TPS) / 
                         (HIGH_LIQUIDITY_TPS - MEDIUM_LIQUIDITY_TPS));
        } else if (ticksPerSecond >= LOW_LIQUIDITY_TPS) {
            // Linear interpolation between low and medium
            return 0.4 + (0.3 * (ticksPerSecond - LOW_LIQUIDITY_TPS) / 
                         (MEDIUM_LIQUIDITY_TPS - LOW_LIQUIDITY_TPS));
        } else if (ticksPerSecond >= 1.0) {
            return 0.2;  // Poor
        } else {
            return 0.1;  // Very poor - avoid!
        }
    }

    /**
     * Calculate spread score
     */
    private double calculateSpreadScore(double spreadBps) {
        if (spreadBps < TIGHT_SPREAD_BPS) {
            return 1.0;  // Excellent - tight spreads
        } else if (spreadBps < MODERATE_SPREAD_BPS) {
            // Linear interpolation
            return 0.7 + (0.3 * (MODERATE_SPREAD_BPS - spreadBps) / 
                         (MODERATE_SPREAD_BPS - TIGHT_SPREAD_BPS));
        } else if (spreadBps < WIDE_SPREAD_BPS) {
            return 0.4;  // Moderate
        } else {
            return 0.2;  // Poor - wide spreads = execution risk!
        }
    }

    /**
     * Calculate stability score (lower volatility = better)
     */
    private double calculateStabilityScore(double spreadVolatility) {
        if (spreadVolatility < 0.1) {
            return 1.0;  // Very stable
        } else if (spreadVolatility < 0.3) {
            return 0.8;  // Stable
        } else if (spreadVolatility < 0.5) {
            return 0.5;  // Moderate volatility
        } else {
            return 0.2;  // High volatility = market makers pulling liquidity!
        }
    }

    /**
     * Calculate spread in basis points
     */
    private double calculateSpreadBps(UnifiedCandle current) {
        if (current.getClose() == 0) {
            return 999.0;  // Invalid
        }
        double bidAskSpread = current.getBidAskSpread();
        if (bidAskSpread <= 0) {
            return 50.0;  // Default moderate spread if unavailable
        }
        return (bidAskSpread / current.getClose()) * 10000;
    }

    /**
     * Get liquidity tier classification
     */
    public LiquidityTier getLiquidityTier(double liquidityScore) {
        if (liquidityScore >= 0.9) {
            return LiquidityTier.EXCELLENT;
        } else if (liquidityScore >= 0.7) {
            return LiquidityTier.GOOD;
        } else if (liquidityScore >= 0.4) {
            return LiquidityTier.MODERATE;
        } else if (liquidityScore >= 0.2) {
            return LiquidityTier.POOR;
        } else {
            return LiquidityTier.VERY_POOR;
        }
    }

    /**
     * Check if instrument is safe to trade
     * 
     * Returns false if:
     * - Spread too wide (> 50 bps)
     * - Tick density too low (< 1 tick/sec)
     */
    public boolean isSafeToTrade(UnifiedCandle current) {
        double spreadBps = calculateSpreadBps(current);
        double tickDensity = current.getTickCount() / 60.0;

        // Check hard limits
        if (spreadBps > WIDE_SPREAD_BPS) {
            log.warn("🚫 UNSAFE TO TRADE {}: Wide spread ({}bps > 50bps)",
                    current.getScripCode(), (int)spreadBps);
            return false;
        }

        if (tickDensity < 1.0) {
            log.warn("🚫 UNSAFE TO TRADE {}: Very low tick density ({:.2f} < 1.0 ticks/s)",
                    current.getScripCode(), tickDensity);
            return false;
        }

        return true;  // Safe to trade
    }

    /**
     * Estimate slippage in price terms
     * 
     * Slippage depends on:
     * - Base spread (half-spread = typical slippage)
     * - Order size (not available here, uses constant factor)
     */
    public double estimateSlippage(UnifiedCandle current) {
        double bidAskSpread = current.getBidAskSpread();
        if (bidAskSpread <= 0) {
            return current.getClose() * 0.001;  // Default 0.1% slippage
        }
        
        double baseSlippage = bidAskSpread / 2.0;  // Half spread
        
        if (baseSlippage > current.getClose() * 0.001) {  // > 0.1% slippage
            log.debug("High slippage estimate for {}: ₹{:.2f} ({:.2f}%)",
                     current.getScripCode(), baseSlippage, 
                     (baseSlippage / current.getClose()) * 100);
        }

        return baseSlippage;
    }

    /**
     * Calculate position size multiplier based on liquidity
     * 
     * Returns:
     * - 1.0 for excellent liquidity (full size)
     * - 0.5-0.7 for moderate liquidity (reduce size)
     * - 0.25 for poor liquidity (quarter size)
     * - 0.0 for very poor liquidity (DON'T TRADE!)
     */
    public double getPositionSizeMultiplier(double liquidityScore) {
        LiquidityTier tier = getLiquidityTier(liquidityScore);

        switch (tier) {
            case EXCELLENT:
                return 1.0;   // Full size
            case GOOD:
                return 0.8;   // 80% size
            case MODERATE:
                return 0.5;   // Half size
            case POOR:
                return 0.25;  // Quarter size
            case VERY_POOR:
            default:
                return 0.0;   // DON'T TRADE!
        }
    }

    /**
     * Get human-readable liquidity description
     */
    public String getLiquidityDescription(UnifiedCandle current, double liquidityScore) {
        LiquidityTier tier = getLiquidityTier(liquidityScore);
        double tickDensity = current.getTickCount() / 60.0;
        double spreadBps = calculateSpreadBps(current);

        return String.format("%s liquidity (score: %.2f) | %.2f ticks/s | %d bps spread",
                            tier.name(),
                            liquidityScore,
                            tickDensity,
                            (int)spreadBps);
    }
}
