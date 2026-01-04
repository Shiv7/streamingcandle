package com.kotsin.consumer.service;

import com.kotsin.consumer.model.UnifiedCandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * GapAnalyzer - Analyzes overnight gaps and their behavior
 * 
 * PHASE 1 ENHANCEMENT: Gap context awareness
 * 
 * Gap behavior is CRITICAL for intraday trading:
 * - Gap up + holding = bullish continuation
 * - Gap up + filling = bearish reversal
 * - Gap down + holding = bearish continuation  
 * - Gap down + filling = bullish reversal
 * 
 * Professional traders ALWAYS consider gaps in their bias.
 * Our system was ignoring this completely!
 */
@Slf4j
@Service
public class GapAnalyzer {

    // Gap size thresholds (percentage)
    private static final double SMALL_GAP_THRESHOLD = 0.5;   // 0.5%
    private static final double MEDIUM_GAP_THRESHOLD = 1.0;  // 1.0%
    private static final double LARGE_GAP_THRESHOLD = 2.0;   // 2.0%
    
    // Gap fill detection (how close to gap level)
    private static final double FILL_PROXIMITY_THRESHOLD = 0.3;  // 0.3% from gap level

    /**
     * Gap context states
     */
    public enum GapContext {
        NO_GAP,                      // No significant gap
        GAP_UP_HOLDING_BULLISH,      // Gap up + price holding above previousClose
        GAP_UP_TESTING,              // Gap up + price near previousClose (decision point)
        GAP_UP_FILLED_BEARISH,       // Gap up + price filled back below previousClose
        GAP_DOWN_HOLDING_BEARISH,    // Gap down + price holding below previousClose
        GAP_DOWN_TESTING,            // Gap down + price near previousClose (decision point)
        GAP_DOWN_FILLED_BULLISH      // Gap down + price filled back above previousClose
    }

    /**
     * Gap strength classification
     */
    public enum GapStrength {
        SMALL,   // < 0.5%
        MEDIUM,  // 0.5% - 1.0%
        LARGE,   // 1.0% - 2.0%
        HUGE     // > 2.0%
    }

    /**
     * Analyze gap behavior for a candle
     * 
     * @param current Current candle
     * @return GapContext describing gap state
     */
    public GapContext analyzeGap(UnifiedCandle current) {
        // Check if gap exists
        if (!Boolean.TRUE.equals(current.getIsGapUp()) && !Boolean.TRUE.equals(current.getIsGapDown())) {
            return GapContext.NO_GAP;
        }

        double gapSize = Math.abs(current.getOvernightGap());
        double gapLevel = current.getPreviousClose();
        double currentPrice = current.getClose();
        
        // Only analyze significant gaps (> 0.2%)
        if (gapSize < 0.2) {
            return GapContext.NO_GAP;
        }

        // Calculate distance from gap level (as percentage)
        double distanceFromGap = Math.abs(currentPrice - gapLevel) / gapLevel;

        if (Boolean.TRUE.equals(current.getIsGapUp())) {
            return analyzeGapUp(currentPrice, gapLevel, distanceFromGap, gapSize);
        } else {
            return analyzeGapDown(currentPrice, gapLevel, distanceFromGap, gapSize);
        }
    }

    /**
     * Analyze gap up behavior
     */
    private GapContext analyzeGapUp(double currentPrice, double gapLevel, 
                                    double distanceFromGap, double gapSize) {
        if (currentPrice > gapLevel * (1 + FILL_PROXIMITY_THRESHOLD / 100)) {
            // Price significantly above gap level = gap holding
            log.debug("Gap UP HOLDING: price={} gap={} distance={}%", 
                     currentPrice, gapLevel, distanceFromGap * 100);
            return GapContext.GAP_UP_HOLDING_BULLISH;
            
        } else if (currentPrice < gapLevel) {
            // Price below gap level = gap filled (bearish reversal!)
            log.info("🔴 Gap UP FILLED (BEARISH): price={} < gap={} | Gap size was {}%",
                    currentPrice, gapLevel, gapSize);
            return GapContext.GAP_UP_FILLED_BEARISH;
            
        } else {
            // Price near gap level = testing (decision point)
            log.debug("Gap UP TESTING: price={} near gap={} | Critical level!",
                     currentPrice, gapLevel);
            return GapContext.GAP_UP_TESTING;
        }
    }

    /**
     * Analyze gap down behavior
     */
    private GapContext analyzeGapDown(double currentPrice, double gapLevel,
                                      double distanceFromGap, double gapSize) {
        if (currentPrice < gapLevel * (1 - FILL_PROXIMITY_THRESHOLD / 100)) {
            // Price significantly below gap level = gap holding
            log.debug("Gap DOWN HOLDING: price={} gap={} distance={}%",
                     currentPrice, gapLevel, distanceFromGap * 100);
            return GapContext.GAP_DOWN_HOLDING_BEARISH;
            
        } else if (currentPrice > gapLevel) {
            // Price above gap level = gap filled (bullish reversal!)
            log.info("🟢 Gap DOWN FILLED (BULLISH): price={} > gap={} | Gap size was {}%",
                    currentPrice, gapLevel, gapSize);
            return GapContext.GAP_DOWN_FILLED_BULLISH;
            
        } else {
            // Price near gap level = testing (decision point)
            log.debug("Gap DOWN TESTING: price={} near gap={} | Critical level!",
                     currentPrice, gapLevel);
            return GapContext.GAP_DOWN_TESTING;
        }
    }

    /**
     * Get gap strength classification
     */
    public GapStrength getGapStrength(UnifiedCandle current) {
        double gapSize = Math.abs(current.getOvernightGap());
        
        if (gapSize < SMALL_GAP_THRESHOLD) {
            return GapStrength.SMALL;
        } else if (gapSize < MEDIUM_GAP_THRESHOLD) {
            return GapStrength.MEDIUM;
        } else if (gapSize < LARGE_GAP_THRESHOLD) {
            return GapStrength.LARGE;
        } else {
            return GapStrength.HUGE;
        }
    }

    /**
     * Calculate directional bias adjustment based on gap context
     * 
     * Returns a multiplier for directional conviction:
     * - 1.0-1.5 = gap reinforces direction (stronger conviction)
     * - 0.5-0.9 = gap opposes direction (weaker conviction)
     * - 0.0-0.5 = gap fill = reversal signal (flip direction!)
     * 
     * @param context Current gap context
     * @param baseDirection Directional conviction from IPU/VCP
     * @return Adjustment multiplier
     */
    public double calculateDirectionalAdjustment(GapContext context, double baseDirection) {
        switch (context) {
            case GAP_UP_HOLDING_BULLISH:
                // Gap holding + bullish base = STRONG BUY
                return baseDirection > 0 ? 1.3 : 0.7;  // Boost bulls, dampen bears
                
            case GAP_UP_FILLED_BEARISH:
                // Gap filled = REVERSAL (strong bearish signal)
                return baseDirection > 0 ? 0.3 : 1.5;  // Flip to bearish!
                
            case GAP_UP_TESTING:
                // At decision point = reduce conviction (wait)
                return 0.8;  // Reduce all signals by 20%
                
            case GAP_DOWN_HOLDING_BEARISH:
                // Gap holding + bearish base = STRONG SELL
                return baseDirection < 0 ? 1.3 : 0.7;  // Boost bears, dampen bulls
                
            case GAP_DOWN_FILLED_BULLISH:
                // Gap filled = REVERSAL (strong bullish signal)
                return baseDirection < 0 ? 0.3 : 1.5;  // Flip to bullish!
                
            case GAP_DOWN_TESTING:
                // At decision point = reduce conviction (wait)
                return 0.8;  // Reduce all signals by 20%
                
            case NO_GAP:
            default:
                return 1.0;  // No adjustment
        }
    }

    /**
     * Check if gap context is bullish
     */
    public boolean isBullishGapContext(GapContext context) {
        return context == GapContext.GAP_UP_HOLDING_BULLISH 
            || context == GapContext.GAP_DOWN_FILLED_BULLISH;
    }

    /**
     * Check if gap context is bearish
     */
    public boolean isBearishGapContext(GapContext context) {
        return context == GapContext.GAP_UP_FILLED_BEARISH 
            || context == GapContext.GAP_DOWN_HOLDING_BEARISH;
    }

    /**
     * Check if gap context suggests waiting (at decision point)
     */
    public boolean shouldWaitForGapDecision(GapContext context) {
        return context == GapContext.GAP_UP_TESTING 
            || context == GapContext.GAP_DOWN_TESTING;
    }

    /**
     * Get human-readable gap description
     */
    public String getGapDescription(UnifiedCandle current, GapContext context) {
        if (context == GapContext.NO_GAP) {
            return "No gap";
        }

        double gapSize = current.getOvernightGap();
        GapStrength strength = getGapStrength(current);
        double gapLevel = current.getPreviousClose();

        return String.format("%s gap of %.2f%% (level: %.2f) - %s",
                            strength.name(),
                            gapSize,
                            gapLevel,
                            context.name());
    }
}
