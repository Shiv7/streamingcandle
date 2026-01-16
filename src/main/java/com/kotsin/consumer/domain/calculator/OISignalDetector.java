package com.kotsin.consumer.domain.calculator;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.domain.model.OptionCandle;
import com.kotsin.consumer.enrichment.model.SessionStructure;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * OISignalDetector - Detects cross-instrument OI signals.
 * 
 * This is the KEY VALUE of the Family Candle architecture.
 * Analyzes OI changes across equity, future, and options to detect:
 * 
 * - BULLISH_ACCUMULATION: Price up + Future OI up + Call OI up + Put OI down
 * - BEARISH_DISTRIBUTION: Price down + Future OI up + Put OI up + Call OI down
 * - SHORT_COVERING_RALLY: Price up + Future OI down + Put OI down
 * - LONG_UNWINDING: Price down + Future OI down + Call OI down
 * 
 * Option Writing/Buying Detection (using OI change vs Price change):
 * - CALL_WRITING: Price down + Call OI up = Bearish (selling calls expecting further fall)
 * - CALL_BUYING: Price up + Call OI up = Bullish (buying calls expecting further rise)
 * - PUT_WRITING: Price up + Put OI up = Bullish (selling puts expecting further rise)
 * - PUT_BUYING: Price down + Put OI up = Bearish (buying puts expecting further fall)
 * 
 * Logic: When OI and price move in OPPOSITE directions = WRITING (selling)
 *        When OI and price move in SAME direction = BUYING (buying)
 * 
 * - NEUTRAL: No clear signal
 * 
 * This is what gives us the "deeper insight" the user requested.
 */
@Slf4j
public class OISignalDetector {

    // Default Thresholds (for high-liquidity NSE stocks)
    private static final double PRICE_THRESHOLD = 0.1;  // 0.1% price change
    private static final double OI_THRESHOLD = 2.0;     // 2% OI change for futures
    private static final long OPTION_OI_MIN_CHANGE = 1000;  // Minimum OI change for options

    // Dynamic thresholds for commodities (lower due to lower liquidity)
    private static final double COMMODITY_PRICE_THRESHOLD = 0.03;   // 0.03% (more sensitive)
    private static final double COMMODITY_OI_THRESHOLD = 0.3;       // 0.3% OI change
    private static final long COMMODITY_OPTION_OI_MIN_CHANGE = 50;  // 50 contracts

    /**
     * OI Signal Type enumeration
     */
    public enum OISignalType {
        BULLISH_ACCUMULATION,   // Strong buy: price up, all OI confirms
        BEARISH_DISTRIBUTION,   // Strong sell: price down, all OI confirms
        SHORT_COVERING_RALLY,   // Temporary bullish: shorts exiting
        LONG_UNWINDING,         // Temporary bearish: longs exiting
        CALL_WRITING,           // Bearish: Price down + Call OI up = Selling calls
        CALL_BUYING,            // Bullish: Price up + Call OI up = Buying calls
        PUT_WRITING,            // Bullish: Price up + Put OI up = Selling puts
        PUT_BUYING,             // Bearish: Price down + Put OI up = Buying puts
        NEUTRAL                 // No clear signal
    }

    /**
     * Detect OI signal from FamilyCandle
     *
     * @param family FamilyCandle with equity, future, and options data
     * @return OISignalType classification
     */
    public static OISignalType detect(FamilyCandle family) {
        // FIX: Support both NSE (equity-based) and MCX (future-only) families
        if (family == null) {
            return OISignalType.NEUTRAL;
        }

        InstrumentCandle equity = family.getEquity();
        InstrumentCandle future = family.getFuture();
        List<OptionCandle> options = family.getOptions();

        // FIX: Use primary instrument (equity for NSE, future for MCX commodities)
        InstrumentCandle primary = equity != null ? equity : future;
        if (primary == null) {
            return OISignalType.NEUTRAL;
        }

        // Get price direction from primary instrument
        double priceChangePercent = primary.getPriceChangePercent();
        // FIX: Handle NaN/Infinity values
        if (Double.isNaN(priceChangePercent) || Double.isInfinite(priceChangePercent)) {
            return OISignalType.NEUTRAL;
        }
        
        boolean priceUp = priceChangePercent > PRICE_THRESHOLD;
        boolean priceDown = priceChangePercent < -PRICE_THRESHOLD;

        // Get future OI direction
        boolean futureOiUp = false;
        boolean futureOiDown = false;
        if (future != null && future.hasOI() && future.getOiChangePercent() != null) {
            futureOiUp = future.getOiChangePercent() > OI_THRESHOLD;
            futureOiDown = future.getOiChangePercent() < -OI_THRESHOLD;
        }

        // Get options OI direction
        long callOiChange = 0L;
        long putOiChange = 0L;
        if (options != null && !options.isEmpty()) {
            callOiChange = options.stream()
                .filter(OptionCandle::isCall)
                .mapToLong(OptionCandle::getOiChange)
                .sum();
            putOiChange = options.stream()
                .filter(OptionCandle::isPut)
                .mapToLong(OptionCandle::getOiChange)
                .sum();
        }

        boolean callOiUp = callOiChange > OPTION_OI_MIN_CHANGE;
        boolean callOiDown = callOiChange < -OPTION_OI_MIN_CHANGE;
        boolean putOiUp = putOiChange > OPTION_OI_MIN_CHANGE;
        boolean putOiDown = putOiChange < -OPTION_OI_MIN_CHANGE;

        // === THE KEY SIGNALS ===

        // BULLISH ACCUMULATION: Price up + Future OI up + Call OI up + Put OI down/neutral
        if (priceUp && futureOiUp && callOiUp && !putOiUp) {
            return OISignalType.BULLISH_ACCUMULATION;
        }

        // BEARISH DISTRIBUTION: Price down + Future OI up + Put OI up + Call OI down/neutral
        if (priceDown && futureOiUp && putOiUp && !callOiUp) {
            return OISignalType.BEARISH_DISTRIBUTION;
        }

        // SHORT COVERING RALLY: Price up + Future OI down + Put OI down
        if (priceUp && futureOiDown && putOiDown) {
            return OISignalType.SHORT_COVERING_RALLY;
        }

        // LONG UNWINDING: Price down + Future OI down + Call OI down
        if (priceDown && futureOiDown && callOiDown) {
            return OISignalType.LONG_UNWINDING;
        }

        // FIX: Use OI change vs Price change relationship to detect writing vs buying
        // When OI and price move in OPPOSITE directions = WRITING (selling)
        // When OI and price move in SAME direction = BUYING (buying)
        
        // CALL WRITING: Price down + Call OI up = Selling calls (bearish)
        // Logic: Price falling but call OI increasing = traders selling calls expecting further fall
        if (priceDown && callOiUp) {
            return OISignalType.CALL_WRITING;
        }

        // CALL BUYING: Price up + Call OI up = Buying calls (bullish)
        // Logic: Price rising and call OI increasing = traders buying calls expecting further rise
        if (priceUp && callOiUp) {
            return OISignalType.CALL_BUYING;
        }

        // PUT WRITING: Price up + Put OI up = Selling puts (bullish)
        // Logic: Price rising but put OI increasing = traders selling puts expecting further rise
        if (priceUp && putOiUp) {
            return OISignalType.PUT_WRITING;
        }

        // PUT BUYING: Price down + Put OI up = Buying puts (bearish)
        // Logic: Price falling and put OI increasing = traders buying puts expecting further fall
        if (priceDown && putOiUp) {
            return OISignalType.PUT_BUYING;
        }

        return OISignalType.NEUTRAL;
    }

    /**
     * 🔴 CRITICAL FIX Bug #3: Detect OI signal with dynamic thresholds based on instrument type.
     *
     * BEFORE: Fixed thresholds (2% OI, 1000 option contracts) - too high for commodities
     * AFTER: Dynamic thresholds based on family type and liquidity
     *
     * For NATURALGAS with OI ~30,000:
     * - Old: 90 change = 0.29% < 2% threshold → NEUTRAL (WRONG)
     * - New: 90 change = 0.29% > 0.3% commodity threshold → Detects signal (CORRECT)
     *
     * @param family FamilyCandle to analyze
     * @return OISignalType classification
     */
    public static OISignalType detectWithDynamicThresholds(FamilyCandle family) {
        if (family == null) {
            return OISignalType.NEUTRAL;
        }

        // Determine if this is a commodity (MCX)
        boolean isCommodity = isCommodityFamily(family);

        // Select appropriate thresholds
        double priceThreshold = isCommodity ? COMMODITY_PRICE_THRESHOLD : PRICE_THRESHOLD;
        double oiThreshold = isCommodity ? COMMODITY_OI_THRESHOLD : OI_THRESHOLD;
        long optionOiMinChange = isCommodity ? COMMODITY_OPTION_OI_MIN_CHANGE : OPTION_OI_MIN_CHANGE;

        // Further adjust based on OI liquidity
        InstrumentCandle future = family.getFuture();
        if (future != null && future.hasOI()) {
            long futureOI = future.getOpenInterest() != null ? future.getOpenInterest() : 0L;
            // For very low OI instruments, use even lower thresholds
            if (futureOI < 10000) {
                oiThreshold = Math.min(oiThreshold, 0.2);
            } else if (futureOI < 50000) {
                oiThreshold = Math.min(oiThreshold, 0.5);
            } else if (futureOI < 200000) {
                oiThreshold = Math.min(oiThreshold, 1.0);
            }
            // High OI instruments (NIFTY/BANKNIFTY) keep default 2%
        }

        // Use primary instrument for price direction
        InstrumentCandle equity = family.getEquity();
        InstrumentCandle primary = equity != null ? equity : future;
        if (primary == null) {
            return OISignalType.NEUTRAL;
        }

        // Calculate price change from aggregated OHLC (more accurate than stored value)
        double priceChangePercent;
        if (primary.getOpen() > 0) {
            priceChangePercent = (primary.getClose() - primary.getOpen()) / primary.getOpen() * 100.0;
        } else {
            priceChangePercent = primary.getPriceChangePercent();
        }

        if (Double.isNaN(priceChangePercent) || Double.isInfinite(priceChangePercent)) {
            return OISignalType.NEUTRAL;
        }

        boolean priceUp = priceChangePercent > priceThreshold;
        boolean priceDown = priceChangePercent < -priceThreshold;

        // Get future OI direction with dynamic threshold
        boolean futureOiUp = false;
        boolean futureOiDown = false;
        if (future != null && future.hasOI()) {
            Double oiChangePercent = future.getOiChangePercent();
            if (oiChangePercent != null && !Double.isNaN(oiChangePercent)) {
                futureOiUp = oiChangePercent > oiThreshold;
                futureOiDown = oiChangePercent < -oiThreshold;
            }
        }

        // Get options OI direction with dynamic threshold
        long callOiChange = 0L;
        long putOiChange = 0L;
        List<OptionCandle> options = family.getOptions();
        if (options != null && !options.isEmpty()) {
            callOiChange = options.stream()
                .filter(OptionCandle::isCall)
                .mapToLong(OptionCandle::getOiChange)
                .sum();
            putOiChange = options.stream()
                .filter(OptionCandle::isPut)
                .mapToLong(OptionCandle::getOiChange)
                .sum();
        }

        boolean callOiUp = callOiChange > optionOiMinChange;
        boolean callOiDown = callOiChange < -optionOiMinChange;
        boolean putOiUp = putOiChange > optionOiMinChange;
        boolean putOiDown = putOiChange < -optionOiMinChange;

        // Log detection details for debugging
        if (isCommodity) {
            log.debug("[OI-SIGNAL-DYNAMIC] {} | isCommodity={} | price={}% (threshold={}) up={} down={} | " +
                      "futureOI={}% (threshold={}) up={} down={} | callOI={} putOI={} (threshold={})",
                family.getFamilyId(), isCommodity,
                String.format("%.3f", priceChangePercent), priceThreshold, priceUp, priceDown,
                future != null ? String.format("%.3f", future.getOiChangePercent()) : "N/A",
                oiThreshold, futureOiUp, futureOiDown,
                callOiChange, putOiChange, optionOiMinChange);
        }

        // === THE KEY SIGNALS (same logic, dynamic thresholds) ===

        // BULLISH ACCUMULATION: Price up + Future OI up + Call OI up + Put OI down/neutral
        if (priceUp && futureOiUp && callOiUp && !putOiUp) {
            return OISignalType.BULLISH_ACCUMULATION;
        }

        // BEARISH DISTRIBUTION: Price down + Future OI up + Put OI up + Call OI down/neutral
        if (priceDown && futureOiUp && putOiUp && !callOiUp) {
            return OISignalType.BEARISH_DISTRIBUTION;
        }

        // SHORT COVERING RALLY: Price up + Future OI down + Put OI down
        if (priceUp && futureOiDown && putOiDown) {
            return OISignalType.SHORT_COVERING_RALLY;
        }

        // LONG UNWINDING: Price down + Future OI down + Call OI down
        if (priceDown && futureOiDown && callOiDown) {
            return OISignalType.LONG_UNWINDING;
        }

        // CALL WRITING: Price down + Call OI up = Selling calls (bearish)
        if (priceDown && callOiUp) {
            return OISignalType.CALL_WRITING;
        }

        // CALL BUYING: Price up + Call OI up = Buying calls (bullish)
        if (priceUp && callOiUp) {
            return OISignalType.CALL_BUYING;
        }

        // PUT WRITING: Price up + Put OI up = Selling puts (bullish)
        if (priceUp && putOiUp) {
            return OISignalType.PUT_WRITING;
        }

        // PUT BUYING: Price down + Put OI up = Buying puts (bearish)
        if (priceDown && putOiUp) {
            return OISignalType.PUT_BUYING;
        }

        // 🔴 NEW: Additional signals for when only price or only OI crosses threshold
        // This helps catch signals in low-liquidity instruments

        // LONG_BUILDUP (weaker): Price up + OI up (even if options don't confirm)
        if (priceUp && futureOiUp) {
            log.debug("[OI-SIGNAL-WEAK] {} | LONG_BUILDUP detected (price+OI up, no option confirmation)",
                family.getFamilyId());
            return OISignalType.CALL_BUYING;  // Map to existing bullish signal
        }

        // SHORT_BUILDUP (weaker): Price down + OI up (even if options don't confirm)
        if (priceDown && futureOiUp) {
            log.debug("[OI-SIGNAL-WEAK] {} | SHORT_BUILDUP detected (price down+OI up, no option confirmation)",
                family.getFamilyId());
            return OISignalType.PUT_BUYING;  // Map to existing bearish signal
        }

        return OISignalType.NEUTRAL;
    }

    /**
     * Check if family is a commodity (MCX)
     */
    private static boolean isCommodityFamily(FamilyCandle family) {
        if (family == null) return false;

        // Check isCommodity flag
        if (family.isCommodity()) return true;

        // Fallback: check future exchange
        if (family.getFuture() != null) {
            String exchange = family.getFuture().getExchange();
            return "M".equalsIgnoreCase(exchange);
        }

        // Fallback: check equity exchange (if somehow we have equity for commodity)
        if (family.getEquity() != null) {
            String exchange = family.getEquity().getExchange();
            return "M".equalsIgnoreCase(exchange);
        }

        return false;
    }

    /**
     * Check if signal is bullish
     */
    public static boolean isBullish(OISignalType signal) {
        return signal == OISignalType.BULLISH_ACCUMULATION 
            || signal == OISignalType.SHORT_COVERING_RALLY
            || signal == OISignalType.PUT_WRITING
            || signal == OISignalType.CALL_BUYING;
    }

    /**
     * Check if signal is bearish
     */
    public static boolean isBearish(OISignalType signal) {
        return signal == OISignalType.BEARISH_DISTRIBUTION 
            || signal == OISignalType.LONG_UNWINDING
            || signal == OISignalType.CALL_WRITING
            || signal == OISignalType.PUT_BUYING;
    }

    /**
     * Check if signal is strong (new positions being created)
     */
    public static boolean isStrong(OISignalType signal) {
        return signal == OISignalType.BULLISH_ACCUMULATION 
            || signal == OISignalType.BEARISH_DISTRIBUTION;
    }

    /**
     * Get signal confidence score
     *
     * @param family FamilyCandle
     * @param signal Detected signal
     * @return Confidence 0.0 to 1.0
     */
    public static double getConfidence(FamilyCandle family, OISignalType signal) {
        if (signal == OISignalType.NEUTRAL) {
            return 0.0;
        }

        double confidence = 0.5;  // Base confidence

        // Higher confidence if we have all data
        if (family.isHasFuture()) {
            confidence += 0.2;
        }
        if (family.isHasOptions()) {
            confidence += 0.2;
        }

        // Higher confidence for strong signals
        if (isStrong(signal)) {
            confidence += 0.1;
        }

        return Math.min(1.0, confidence);
    }

    /**
     * Get directional bias from signal
     *
     * @param signal OISignalType
     * @return 1 for bullish, -1 for bearish, 0 for neutral
     */
    public static int getDirectionalBias(OISignalType signal) {
        if (isBullish(signal)) return 1;
        if (isBearish(signal)) return -1;
        return 0;
    }

    /**
     * Describe signal in human-readable format
     *
     * @param signal OISignalType
     * @return Description string
     */
    public static String describe(OISignalType signal) {
        switch (signal) {
            case BULLISH_ACCUMULATION:
                return "Strong bullish: New longs being built, smart money bullish";
            case BEARISH_DISTRIBUTION:
                return "Strong bearish: New shorts being built, smart money bearish";
            case SHORT_COVERING_RALLY:
                return "Short-term bullish: Shorts exiting, potential rally";
            case LONG_UNWINDING:
                return "Short-term bearish: Longs exiting, potential decline";
            case CALL_WRITING:
                return "Bearish: Price down + Call OI up = Selling calls (expecting further fall)";
            case CALL_BUYING:
                return "Bullish: Price up + Call OI up = Buying calls (expecting further rise)";
            case PUT_WRITING:
                return "Bullish: Price up + Put OI up = Selling puts (expecting further rise)";
            case PUT_BUYING:
                return "Bearish: Price down + Put OI up = Buying puts (expecting further fall)";
            default:
                return "No clear signal from OI analysis";
        }
    }

    // ==================== CONTEXT-AWARE OI INTERPRETATION ====================
    // CRITICAL: The same OI signal means OPPOSITE at session extremes!
    //
    // At SESSION LOW (positionInRange < 0.15):
    //   - SHORT_BUILDUP = trapped shorts = SQUEEZE FUEL = BULLISH!
    //   - BEARISH signals at support = reversal setup, not continuation
    //
    // At SESSION HIGH (positionInRange > 0.85):
    //   - LONG_BUILDUP = trapped longs = DISTRIBUTION = BEARISH!
    //   - BULLISH signals at resistance = exhaustion, not continuation
    //
    // WITHOUT THIS CONTEXT, signals are BLIND to session position and give
    // WRONG interpretations at exactly the most important moments!

    /**
     * Context-aware interpretation result
     */
    public static class ContextAwareSignal {
        private final OISignalType rawSignal;
        private final OISignalType contextSignal;  // May be flipped based on session position
        private final boolean wasFlipped;
        private final String interpretation;
        private final double confidenceModifier;
        private final String positionContext;

        public ContextAwareSignal(OISignalType rawSignal, OISignalType contextSignal,
                                   boolean wasFlipped, String interpretation,
                                   double confidenceModifier, String positionContext) {
            this.rawSignal = rawSignal;
            this.contextSignal = contextSignal;
            this.wasFlipped = wasFlipped;
            this.interpretation = interpretation;
            this.confidenceModifier = confidenceModifier;
            this.positionContext = positionContext;
        }

        public OISignalType getRawSignal() { return rawSignal; }
        public OISignalType getContextSignal() { return contextSignal; }
        public boolean wasFlipped() { return wasFlipped; }
        public String getInterpretation() { return interpretation; }
        public double getConfidenceModifier() { return confidenceModifier; }
        public String getPositionContext() { return positionContext; }

        public boolean isBullish() { return OISignalDetector.isBullish(contextSignal); }
        public boolean isBearish() { return OISignalDetector.isBearish(contextSignal); }

        @Override
        public String toString() {
            return String.format("ContextAwareSignal[raw=%s, context=%s, flipped=%s, pos=%s] %s",
                    rawSignal, contextSignal, wasFlipped, positionContext, interpretation);
        }
    }

    /**
     * Detect OI signal with session context awareness.
     *
     * CRITICAL: This method FLIPS signal interpretation at session extremes!
     *
     * @param family FamilyCandle to analyze
     * @param sessionStructure Current session structure (position in range)
     * @return ContextAwareSignal with possibly flipped interpretation
     */
    public static ContextAwareSignal detectWithContext(FamilyCandle family, SessionStructure sessionStructure) {
        // First, get the raw signal using dynamic thresholds
        OISignalType rawSignal = detectWithDynamicThresholds(family);

        // If no session structure, return raw signal
        if (sessionStructure == null) {
            return new ContextAwareSignal(
                    rawSignal, rawSignal, false,
                    describe(rawSignal), 1.0, "NO_SESSION_DATA");
        }

        double positionInRange = sessionStructure.getPositionInRange();
        String positionDesc = sessionStructure.getPositionDescription();
        boolean atLow = sessionStructure.isAtSessionLow();
        boolean atHigh = sessionStructure.isAtSessionHigh();

        // If not at extremes, return raw signal with position context
        if (!atLow && !atHigh) {
            double modifier = 1.0;
            // Slightly boost signals aligned with position
            if (positionInRange < 0.35 && isBullish(rawSignal)) {
                modifier = 1.1;  // Bullish in lower range = good
            } else if (positionInRange > 0.65 && isBearish(rawSignal)) {
                modifier = 1.1;  // Bearish in upper range = good
            }

            return new ContextAwareSignal(
                    rawSignal, rawSignal, false,
                    describe(rawSignal) + " [" + positionDesc + "]",
                    modifier, positionDesc);
        }

        // === AT SESSION EXTREME - FLIP INTERPRETATION ===
        OISignalType contextSignal = rawSignal;
        boolean wasFlipped = false;
        String interpretation = describe(rawSignal);  // Default initialization
        double confidenceModifier = 1.0;

        if (atLow) {
            // At session LOW - bearish signals often mean reversal setup
            switch (rawSignal) {
                case BEARISH_DISTRIBUTION:
                case PUT_BUYING:
                    // Bearish at low = potential V-bottom setup
                    // Shorts building at support = squeeze fuel!
                    contextSignal = OISignalType.SHORT_COVERING_RALLY;  // Interpret as potential reversal
                    wasFlipped = true;
                    interpretation = "SHORT SQUEEZE SETUP: Bearish OI at session LOW = trapped shorts! " +
                            "If support holds, shorts must cover → squeeze rally";
                    confidenceModifier = 1.3;  // Boost confidence for reversal setup
                    log.info("[OI-CONTEXT] {} FLIP at session LOW: {} → {} | {}",
                            family.getFamilyId(), rawSignal, contextSignal, interpretation);
                    break;

                case CALL_WRITING:
                    // Call writing at low = aggressive bearishness at support
                    contextSignal = OISignalType.SHORT_COVERING_RALLY;  // Contrarian bullish
                    wasFlipped = true;
                    interpretation = "CONTRARIAN BULLISH: Aggressive call writing at session LOW = " +
                            "extreme bearishness at support. Often precedes reversal.";
                    confidenceModifier = 1.2;
                    log.info("[OI-CONTEXT] {} FLIP at session LOW: {} → contrarian bullish | {}",
                            family.getFamilyId(), rawSignal, interpretation);
                    break;

                case BULLISH_ACCUMULATION:
                case CALL_BUYING:
                case PUT_WRITING:
                    // Bullish at low = CONFIRMATION of support
                    interpretation = "SUPPORT CONFIRMATION: Bullish OI at session LOW = " +
                            "smart money buying at support. Strong long setup.";
                    confidenceModifier = 1.4;  // Strong boost for confirmed support
                    log.info("[OI-CONTEXT] {} CONFIRMED at session LOW: {} | {}",
                            family.getFamilyId(), rawSignal, interpretation);
                    break;

                default:
                    interpretation = describe(rawSignal) + " [AT SESSION LOW - watch for reversal]";
            }

            // Additional boost if V-bottom detected
            if (sessionStructure.isVBottomDetected()) {
                interpretation += " V-BOTTOM DETECTED!";
                confidenceModifier *= 1.2;
            }

            // Additional boost if failed breakdown detected
            if (sessionStructure.getFailedBreakdownCount() > 0) {
                interpretation += String.format(" (Failed breakdowns: %d)",
                        sessionStructure.getFailedBreakdownCount());
                confidenceModifier *= 1.15;
            }

        } else if (atHigh) {
            // At session HIGH - bullish signals often mean exhaustion/distribution
            switch (rawSignal) {
                case BULLISH_ACCUMULATION:
                case CALL_BUYING:
                    // Bullish at high = potential distribution/exhaustion
                    // Longs building at resistance = trapped longs!
                    contextSignal = OISignalType.LONG_UNWINDING;  // Interpret as potential reversal
                    wasFlipped = true;
                    interpretation = "DISTRIBUTION SETUP: Bullish OI at session HIGH = trapped longs! " +
                            "If resistance holds, longs must exit → pullback";
                    confidenceModifier = 1.3;  // Boost confidence for reversal setup
                    log.info("[OI-CONTEXT] {} FLIP at session HIGH: {} → {} | {}",
                            family.getFamilyId(), rawSignal, contextSignal, interpretation);
                    break;

                case PUT_WRITING:
                    // Put writing at high = aggressive bullishness at resistance
                    contextSignal = OISignalType.LONG_UNWINDING;  // Contrarian bearish
                    wasFlipped = true;
                    interpretation = "CONTRARIAN BEARISH: Aggressive put writing at session HIGH = " +
                            "extreme bullishness at resistance. Often precedes reversal.";
                    confidenceModifier = 1.2;
                    log.info("[OI-CONTEXT] {} FLIP at session HIGH: {} → contrarian bearish | {}",
                            family.getFamilyId(), rawSignal, interpretation);
                    break;

                case BEARISH_DISTRIBUTION:
                case PUT_BUYING:
                case CALL_WRITING:
                    // Bearish at high = CONFIRMATION of resistance
                    interpretation = "RESISTANCE CONFIRMATION: Bearish OI at session HIGH = " +
                            "smart money selling at resistance. Strong short setup.";
                    confidenceModifier = 1.4;  // Strong boost for confirmed resistance
                    log.info("[OI-CONTEXT] {} CONFIRMED at session HIGH: {} | {}",
                            family.getFamilyId(), rawSignal, interpretation);
                    break;

                default:
                    interpretation = describe(rawSignal) + " [AT SESSION HIGH - watch for reversal]";
            }

            // Additional boost if V-top detected
            if (sessionStructure.isVTopDetected()) {
                interpretation += " V-TOP DETECTED!";
                confidenceModifier *= 1.2;
            }

            // Additional boost if failed breakout detected
            if (sessionStructure.getFailedBreakoutCount() > 0) {
                interpretation += String.format(" (Failed breakouts: %d)",
                        sessionStructure.getFailedBreakoutCount());
                confidenceModifier *= 1.15;
            }
        }

        return new ContextAwareSignal(
                rawSignal, contextSignal, wasFlipped,
                interpretation, confidenceModifier, positionDesc);
    }

    /**
     * Get context-aware directional bias considering session position
     *
     * @param rawSignal The raw OI signal
     * @param sessionStructure Current session structure
     * @return +1 for bullish, -1 for bearish, 0 for neutral (after context adjustment)
     */
    public static int getContextAwareDirectionalBias(OISignalType rawSignal, SessionStructure sessionStructure) {
        if (sessionStructure == null) {
            return getDirectionalBias(rawSignal);
        }

        ContextAwareSignal contextSignal = detectWithContext(null, sessionStructure);
        // Note: We can't call detectWithContext without family, so use simple logic

        int rawBias = getDirectionalBias(rawSignal);

        // Flip bias at extremes
        if (sessionStructure.isAtSessionLow() && rawBias < 0) {
            // Bearish at low = potential bullish reversal
            return 1;
        }
        if (sessionStructure.isAtSessionHigh() && rawBias > 0) {
            // Bullish at high = potential bearish reversal
            return -1;
        }

        return rawBias;
    }

    /**
     * Describe signal with session context
     *
     * @param signal OISignalType
     * @param sessionStructure Current session structure
     * @return Context-aware description
     */
    public static String describeWithContext(OISignalType signal, SessionStructure sessionStructure) {
        String baseDesc = describe(signal);

        if (sessionStructure == null) {
            return baseDesc;
        }

        String position = sessionStructure.getPositionDescription();
        double positionInRange = sessionStructure.getPositionInRange();

        StringBuilder sb = new StringBuilder(baseDesc);
        sb.append(" [Session: ").append(position);
        sb.append(", pos=").append(String.format("%.0f%%", positionInRange * 100)).append("]");

        // Add warning if signal might mean opposite
        if (sessionStructure.isAtSessionLow() && isBearish(signal)) {
            sb.append(" ⚠️ CAUTION: Bearish at session LOW may indicate squeeze setup!");
        } else if (sessionStructure.isAtSessionHigh() && isBullish(signal)) {
            sb.append(" ⚠️ CAUTION: Bullish at session HIGH may indicate distribution!");
        }

        // Add pattern info
        if (sessionStructure.isVBottomDetected()) {
            sb.append(" [V-BOTTOM DETECTED]");
        }
        if (sessionStructure.isVTopDetected()) {
            sb.append(" [V-TOP DETECTED]");
        }

        return sb.toString();
    }
}
