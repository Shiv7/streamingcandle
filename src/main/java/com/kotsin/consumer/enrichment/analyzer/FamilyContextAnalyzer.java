package com.kotsin.consumer.enrichment.analyzer;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.domain.model.OptionCandle;
import com.kotsin.consumer.enrichment.model.SessionStructure;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * FamilyContextAnalyzer - Analyzes ALL family instruments together for unified signals.
 *
 * THE POWER OF FAMILY CANDLE:
 * Having equity + futures + options in ONE place allows us to see what no single
 * instrument can show:
 *
 * 1. EQUITY shows: Price action, retail sentiment
 * 2. FUTURES shows: Institutional positioning (OI buildup/unwinding)
 * 3. OPTIONS shows: Smart money hedging, sentiment extremes (PCR), directional bets
 *
 * COMBINED ANALYSIS reveals:
 * - Divergences (price falling but call OI surging = accumulation)
 * - Confirmation (all instruments aligned = high-probability signal)
 * - Trapped traders (shorts at session low with high short OI = squeeze fuel)
 * - Smart money flow (futures OI + options flow in same direction)
 *
 * This class produces a unified FamilyContext that EventDetector uses for
 * context-aware signal generation.
 */
@Slf4j
@Component
public class FamilyContextAnalyzer {

    // Thresholds for signal detection
    private static final double MIN_PRICE_CHANGE_PCT = 0.1;     // 0.1% minimum move
    private static final double STRONG_PRICE_CHANGE_PCT = 0.5;  // 0.5% = strong move
    private static final double PCR_BULLISH_THRESHOLD = 0.7;    // Low PCR = bullish
    private static final double PCR_BEARISH_THRESHOLD = 1.3;    // High PCR = bearish
    private static final double PCR_EXTREME_LOW = 0.5;          // Extreme bullishness (contrarian bearish)
    private static final double PCR_EXTREME_HIGH = 1.5;         // Extreme fear (contrarian bullish)
    private static final long MIN_OI_CHANGE = 100;              // Minimum OI change to consider

    /**
     * Analyze FamilyCandle with session context to produce unified FamilyContext.
     *
     * @param family FamilyCandle with all instruments
     * @param session Current session structure (position in range, etc.)
     * @return FamilyContext with alignment scores and signals
     */
    public FamilyContext analyze(FamilyCandle family, SessionStructure session) {
        if (family == null) {
            log.debug("[FAMILY] analyze() called with null family");
            return FamilyContext.empty();
        }

        String familyId = family.getFamilyId();
        long startTime = System.currentTimeMillis();

        log.debug("[FAMILY] {} Analyzing family context | hasEquity={} | hasFuture={} | optionCount={}",
                familyId,
                family.getEquity() != null,
                family.getFuture() != null,
                family.getOptions() != null ? family.getOptions().size() : 0);

        // Get position context
        double positionInRange = session != null ? session.getPositionInRange() : 0.5;
        boolean atSessionLow = session != null && session.isAtSessionLow();
        boolean atSessionHigh = session != null && session.isAtSessionHigh();
        boolean vBottomDetected = session != null && session.isVBottomDetected();
        boolean vTopDetected = session != null && session.isVTopDetected();

        log.debug("[FAMILY] {} Session context: pos={}% | atLow={} | atHigh={} | vBottom={} | vTop={}",
                familyId,
                String.format("%.1f", positionInRange * 100),
                atSessionLow, atSessionHigh, vBottomDetected, vTopDetected);

        // Analyze each instrument layer
        EquitySignal equitySignal = analyzeEquity(family);
        FuturesSignal futuresSignal = analyzeFutures(family);
        OptionsSignal optionsSignal = analyzeOptions(family);

        log.debug("[FAMILY] {} Signals: equity={}/chg={:.2f}% | futures={}/oiChg={:.2f}% | options={}/pcr={}",
                familyId,
                equitySignal.isBullish() ? "BULL" : equitySignal.isBearish() ? "BEAR" : "NEUTRAL",
                equitySignal.getPriceChangePct(),
                futuresSignal.getSignalType(),
                futuresSignal.getOiChangePct(),
                optionsSignal.getFlowSignal(),
                optionsSignal.getPcr() != null ? String.format("%.2f", optionsSignal.getPcr()) : "N/A");

        // Calculate alignment scores
        double bullishAlignment = calculateBullishAlignment(equitySignal, futuresSignal, optionsSignal);
        double bearishAlignment = calculateBearishAlignment(equitySignal, futuresSignal, optionsSignal);

        // Detect divergences (key reversal signals!)
        List<String> divergences = detectDivergences(equitySignal, futuresSignal, optionsSignal, positionInRange);

        // Determine overall bias
        FamilyBias overallBias = determineOverallBias(
                bullishAlignment, bearishAlignment, positionInRange, atSessionLow, atSessionHigh);

        // Context-aware interpretation
        String contextInterpretation = getContextInterpretation(
                overallBias, positionInRange, atSessionLow, atSessionHigh,
                vBottomDetected, vTopDetected, futuresSignal, optionsSignal);

        // Detect special setups
        boolean shortSqueezeSetup = isShortSqueezeSetup(
                atSessionLow, futuresSignal, optionsSignal, family.getPcr());
        boolean longSqueezeSetup = isLongSqueezeSetup(
                atSessionHigh, futuresSignal, optionsSignal, family.getPcr());

        // Calculate confidence
        double confidence = calculateConfidence(
                bullishAlignment, bearishAlignment, equitySignal, futuresSignal, optionsSignal);

        // Build result
        FamilyContext context = FamilyContext.builder()
                .familyId(familyId)
                .timestamp(family.getTimestamp())
                // Position context
                .positionInRange(positionInRange)
                .atSessionLow(atSessionLow)
                .atSessionHigh(atSessionHigh)
                .vBottomDetected(vBottomDetected)
                .vTopDetected(vTopDetected)
                // Individual signals
                .equitySignal(equitySignal)
                .futuresSignal(futuresSignal)
                .optionsSignal(optionsSignal)
                // Alignment
                .bullishAlignment(bullishAlignment)
                .bearishAlignment(bearishAlignment)
                .overallBias(overallBias)
                .confidence(confidence)
                // Divergences and interpretation
                .divergences(divergences)
                .hasDivergence(!divergences.isEmpty())
                .contextInterpretation(contextInterpretation)
                // Special setups
                .shortSqueezeSetup(shortSqueezeSetup)
                .longSqueezeSetup(longSqueezeSetup)
                // Flags
                .fullyAlignedBullish(bullishAlignment > 0.8)
                .fullyAlignedBearish(bearishAlignment > 0.8)
                .hasOptionsData(family.isHasOptions())
                .hasFuturesData(family.isHasFuture())
                .build();

        long elapsed = System.currentTimeMillis() - startTime;

        // Always log debug summary
        log.debug("[FAMILY] {} Analysis complete in {}ms | bias={} | bullAlign={}% | bearAlign={}% | conf={}%",
                familyId, elapsed, overallBias,
                String.format("%.0f", bullishAlignment * 100),
                String.format("%.0f", bearishAlignment * 100),
                String.format("%.0f", confidence * 100));

        // Log significant findings at INFO level for production monitoring
        if (context.isFullyAlignedBullish() || context.isFullyAlignedBearish()) {
            log.info("[FAMILY] {} FULLY ALIGNED {} | pos={}% | bullAlign={}% | bearAlign={}% | conf={}%",
                    familyId, overallBias,
                    String.format("%.0f", positionInRange * 100),
                    String.format("%.0f", bullishAlignment * 100),
                    String.format("%.0f", bearishAlignment * 100),
                    String.format("%.0f", confidence * 100));
        }
        if (!divergences.isEmpty()) {
            log.info("[FAMILY] {} DIVERGENCE detected: {} | pos={}% | equity={} | futures={} | options={}",
                    familyId, divergences,
                    String.format("%.0f", positionInRange * 100),
                    equitySignal.isBullish() ? "BULL" : equitySignal.isBearish() ? "BEAR" : "NEUTRAL",
                    futuresSignal.getSignalType(),
                    optionsSignal.getFlowSignal());
        }
        if (shortSqueezeSetup || longSqueezeSetup) {
            log.info("[FAMILY] {} {} SETUP at pos={}% | futuresOI={} | pcr={} | optFlow={}",
                    familyId, shortSqueezeSetup ? "SHORT_SQUEEZE" : "LONG_SQUEEZE",
                    String.format("%.0f", positionInRange * 100),
                    futuresSignal.getSignalType(),
                    String.format("%.2f", optionsSignal.getPcr()),
                    optionsSignal.getFlowSignal());
        }

        return context;
    }

    // ==================== EQUITY ANALYSIS ====================

    private EquitySignal analyzeEquity(FamilyCandle family) {
        InstrumentCandle equity = family.getEquity();
        if (equity == null) {
            // For commodities, use future as primary
            equity = family.getFuture();
        }
        if (equity == null) {
            return EquitySignal.neutral();
        }

        double open = equity.getOpen();
        double close = equity.getClose();
        double high = equity.getHigh();
        double low = equity.getLow();

        if (open <= 0) {
            return EquitySignal.neutral();
        }

        double changePct = (close - open) / open * 100;
        boolean bullish = changePct > MIN_PRICE_CHANGE_PCT;
        boolean bearish = changePct < -MIN_PRICE_CHANGE_PCT;
        boolean strongMove = Math.abs(changePct) > STRONG_PRICE_CHANGE_PCT;

        // Candle structure analysis
        double body = Math.abs(close - open);
        double range = high - low;
        double upperWick = high - Math.max(open, close);
        double lowerWick = Math.min(open, close) - low;

        boolean bullishCandle = close > open;
        boolean doji = range > 0 && body / range < 0.1;
        boolean hammer = range > 0 && lowerWick / range > 0.6 && body / range < 0.3;
        boolean shootingStar = range > 0 && upperWick / range > 0.6 && body / range < 0.3;

        // Volume analysis
        long volume = equity.getVolume();
        long buyVolume = equity.getBuyVolume();
        long sellVolume = equity.getSellVolume();
        double buyRatio = volume > 0 ? (double) buyVolume / volume : 0.5;

        return EquitySignal.builder()
                .priceChangePct(changePct)
                .bullish(bullish)
                .bearish(bearish)
                .strongMove(strongMove)
                .bullishCandle(bullishCandle)
                .doji(doji)
                .hammer(hammer)
                .shootingStar(shootingStar)
                .buyVolumeRatio(buyRatio)
                .volumeDominance(buyRatio > 0.6 ? "BUY" : buyRatio < 0.4 ? "SELL" : "NEUTRAL")
                .build();
    }

    // ==================== FUTURES ANALYSIS ====================

    private FuturesSignal analyzeFutures(FamilyCandle family) {
        InstrumentCandle future = family.getFuture();
        if (future == null || !family.isHasFuture()) {
            return FuturesSignal.neutral();
        }

        // Price direction
        double priceChange = future.getPriceChangePercent();
        boolean priceUp = priceChange > MIN_PRICE_CHANGE_PCT;
        boolean priceDown = priceChange < -MIN_PRICE_CHANGE_PCT;

        // OI analysis
        Long oiChange = future.getOiChange();
        Double oiChangePct = future.getOiChangePercent();
        boolean oiUp = oiChange != null && oiChange > MIN_OI_CHANGE;
        boolean oiDown = oiChange != null && oiChange < -MIN_OI_CHANGE;

        // OI interpretation (CRITICAL!)
        String buildup = family.getFuturesBuildup();  // Already calculated in FamilyCandle
        String oiSignal = family.getOiSignal();

        // Determine signal type
        FuturesSignalType signalType;
        if (priceUp && oiUp) {
            signalType = FuturesSignalType.LONG_BUILDUP;  // New longs entering - bullish
        } else if (priceUp && oiDown) {
            signalType = FuturesSignalType.SHORT_COVERING;  // Shorts exiting - bullish but exhausting
        } else if (priceDown && oiUp) {
            signalType = FuturesSignalType.SHORT_BUILDUP;  // New shorts entering - bearish
        } else if (priceDown && oiDown) {
            signalType = FuturesSignalType.LONG_UNWINDING;  // Longs exiting - bearish but exhausting
        } else {
            signalType = FuturesSignalType.NEUTRAL;
        }

        // Premium analysis
        Double premium = family.getSpotFuturePremium();
        boolean premiumPositive = premium != null && premium > 0.1;
        boolean premiumNegative = premium != null && premium < -0.1;

        return FuturesSignal.builder()
                .priceChangePct(priceChange)
                .priceUp(priceUp)
                .priceDown(priceDown)
                .oiChange(oiChange != null ? oiChange : 0)
                .oiChangePct(oiChangePct != null ? oiChangePct : 0)
                .oiUp(oiUp)
                .oiDown(oiDown)
                .signalType(signalType)
                .buildup(buildup)
                .oiSignal(oiSignal)
                .spotFuturePremium(premium)
                .premiumExpanding(premiumPositive)
                .premiumContracting(premiumNegative)
                .build();
    }

    // ==================== OPTIONS ANALYSIS ====================

    private OptionsSignal analyzeOptions(FamilyCandle family) {
        if (!family.isHasOptions() || family.getOptions() == null || family.getOptions().isEmpty()) {
            return OptionsSignal.neutral();
        }

        // Aggregate options data
        long totalCallOIChange = 0;
        long totalPutOIChange = 0;
        double totalCallPremiumChange = 0;
        double totalPutPremiumChange = 0;
        int callCount = 0;
        int putCount = 0;

        for (OptionCandle opt : family.getOptions()) {
            if (opt == null) continue;

            // Calculate premium change percentage from open/close
            double premiumChangePct = 0.0;
            if (opt.getOpen() > 0) {
                premiumChangePct = (opt.getClose() - opt.getOpen()) / opt.getOpen() * 100;
            }

            if (opt.isCall()) {
                totalCallOIChange += opt.getOiChange();
                totalCallPremiumChange += premiumChangePct;
                callCount++;
            } else {
                totalPutOIChange += opt.getOiChange();
                totalPutPremiumChange += premiumChangePct;
                putCount++;
            }
        }

        // PCR analysis
        Double pcr = family.getPcr();
        Double pcrChange = family.getPcrChange();
        boolean pcrBullish = pcr != null && pcr < PCR_BULLISH_THRESHOLD;
        boolean pcrBearish = pcr != null && pcr > PCR_BEARISH_THRESHOLD;
        boolean pcrExtremeLow = pcr != null && pcr < PCR_EXTREME_LOW;  // Euphoria
        boolean pcrExtremeHigh = pcr != null && pcr > PCR_EXTREME_HIGH;  // Panic
        boolean pcrFalling = pcrChange != null && pcrChange < -0.05;
        boolean pcrRising = pcrChange != null && pcrChange > 0.05;

        // OI direction
        boolean callOIUp = totalCallOIChange > MIN_OI_CHANGE;
        boolean callOIDown = totalCallOIChange < -MIN_OI_CHANGE;
        boolean putOIUp = totalPutOIChange > MIN_OI_CHANGE;
        boolean putOIDown = totalPutOIChange < -MIN_OI_CHANGE;

        // Premium direction (important for writing vs buying detection)
        double avgCallPremiumChange = callCount > 0 ? totalCallPremiumChange / callCount : 0;
        double avgPutPremiumChange = putCount > 0 ? totalPutPremiumChange / putCount : 0;

        // Determine options flow signal
        OptionsFlowSignal flowSignal;
        if (callOIUp && putOIDown) {
            flowSignal = OptionsFlowSignal.BULLISH_FLOW;  // Call accumulation + put exit
        } else if (putOIUp && callOIDown) {
            flowSignal = OptionsFlowSignal.BEARISH_FLOW;  // Put accumulation + call exit
        } else if (callOIUp && putOIUp) {
            // Both up - check premium to determine if writing or buying
            if (avgCallPremiumChange > 0 && avgPutPremiumChange < 0) {
                flowSignal = OptionsFlowSignal.BULLISH_FLOW;  // Call buying + put writing
            } else if (avgPutPremiumChange > 0 && avgCallPremiumChange < 0) {
                flowSignal = OptionsFlowSignal.BEARISH_FLOW;  // Put buying + call writing
            } else {
                flowSignal = OptionsFlowSignal.HEDGING;  // Both buying = hedging
            }
        } else if (callOIDown && putOIDown) {
            flowSignal = OptionsFlowSignal.UNWINDING;  // Both exiting
        } else {
            flowSignal = OptionsFlowSignal.NEUTRAL;
        }

        return OptionsSignal.builder()
                .callOIChange(totalCallOIChange)
                .putOIChange(totalPutOIChange)
                .callOIUp(callOIUp)
                .callOIDown(callOIDown)
                .putOIUp(putOIUp)
                .putOIDown(putOIDown)
                .pcr(pcr)
                .pcrChange(pcrChange)
                .pcrBullish(pcrBullish)
                .pcrBearish(pcrBearish)
                .pcrExtremeLow(pcrExtremeLow)
                .pcrExtremeHigh(pcrExtremeHigh)
                .pcrFalling(pcrFalling)
                .pcrRising(pcrRising)
                .avgCallPremiumChange(avgCallPremiumChange)
                .avgPutPremiumChange(avgPutPremiumChange)
                .flowSignal(flowSignal)
                .build();
    }

    // ==================== ALIGNMENT CALCULATION ====================

    private double calculateBullishAlignment(EquitySignal equity, FuturesSignal futures, OptionsSignal options) {
        double score = 0;
        int factors = 0;

        // Equity (weight: 0.3)
        if (equity.isBullish()) {
            score += 0.3;
            if (equity.isStrongMove()) score += 0.1;
        }
        if (equity.getBuyVolumeRatio() > 0.6) score += 0.1;
        factors++;

        // Futures (weight: 0.35)
        if (futures.getSignalType() == FuturesSignalType.LONG_BUILDUP) {
            score += 0.35;  // Strongest bullish signal
        } else if (futures.getSignalType() == FuturesSignalType.SHORT_COVERING) {
            score += 0.2;   // Bullish but weaker
        }
        factors++;

        // Options (weight: 0.35)
        if (options.getFlowSignal() == OptionsFlowSignal.BULLISH_FLOW) {
            score += 0.35;
        }
        if (options.isPcrFalling()) score += 0.1;
        if (options.isCallOIUp() && options.isPutOIDown()) score += 0.1;
        factors++;

        return Math.min(1.0, score);
    }

    private double calculateBearishAlignment(EquitySignal equity, FuturesSignal futures, OptionsSignal options) {
        double score = 0;

        // Equity (weight: 0.3)
        if (equity.isBearish()) {
            score += 0.3;
            if (equity.isStrongMove()) score += 0.1;
        }
        if (equity.getBuyVolumeRatio() < 0.4) score += 0.1;

        // Futures (weight: 0.35)
        if (futures.getSignalType() == FuturesSignalType.SHORT_BUILDUP) {
            score += 0.35;  // Strongest bearish signal
        } else if (futures.getSignalType() == FuturesSignalType.LONG_UNWINDING) {
            score += 0.2;   // Bearish but weaker
        }

        // Options (weight: 0.35)
        if (options.getFlowSignal() == OptionsFlowSignal.BEARISH_FLOW) {
            score += 0.35;
        }
        if (options.isPcrRising()) score += 0.1;
        if (options.isPutOIUp() && options.isCallOIDown()) score += 0.1;

        return Math.min(1.0, score);
    }

    // ==================== DIVERGENCE DETECTION ====================

    private List<String> detectDivergences(EquitySignal equity, FuturesSignal futures,
                                            OptionsSignal options, double positionInRange) {
        List<String> divergences = new ArrayList<>();

        // Price falling but options bullish = accumulation (potential reversal)
        if (equity.isBearish() && options.getFlowSignal() == OptionsFlowSignal.BULLISH_FLOW) {
            divergences.add("BULLISH_DIVERGENCE: Price down but call accumulation");
        }

        // Price rising but options bearish = distribution
        if (equity.isBullish() && options.getFlowSignal() == OptionsFlowSignal.BEARISH_FLOW) {
            divergences.add("BEARISH_DIVERGENCE: Price up but put accumulation");
        }

        // Price falling but short covering = exhaustion
        if (equity.isBearish() && futures.getSignalType() == FuturesSignalType.SHORT_COVERING) {
            divergences.add("SELLING_EXHAUSTION: Price down but shorts covering");
        }

        // Price rising but long unwinding = exhaustion
        if (equity.isBullish() && futures.getSignalType() == FuturesSignalType.LONG_UNWINDING) {
            divergences.add("BUYING_EXHAUSTION: Price up but longs exiting");
        }

        // At session low with short buildup = squeeze fuel (contrarian!)
        if (positionInRange < 0.15 && futures.getSignalType() == FuturesSignalType.SHORT_BUILDUP) {
            divergences.add("SHORT_SQUEEZE_FUEL: Shorts building at session low");
        }

        // At session high with long buildup = distribution (contrarian!)
        if (positionInRange > 0.85 && futures.getSignalType() == FuturesSignalType.LONG_BUILDUP) {
            divergences.add("LONG_SQUEEZE_FUEL: Longs building at session high");
        }

        // Extreme PCR at wrong position
        if (options.isPcrExtremeHigh() && positionInRange < 0.3) {
            divergences.add("PCR_CONTRARIAN_BULLISH: Extreme fear at lows");
        }
        if (options.isPcrExtremeLow() && positionInRange > 0.7) {
            divergences.add("PCR_CONTRARIAN_BEARISH: Extreme greed at highs");
        }

        return divergences;
    }

    // ==================== BIAS DETERMINATION ====================

    private FamilyBias determineOverallBias(double bullishAlignment, double bearishAlignment,
                                             double positionInRange, boolean atSessionLow, boolean atSessionHigh) {
        double netAlignment = bullishAlignment - bearishAlignment;

        // Strong alignment
        if (netAlignment > 0.4) return FamilyBias.STRONG_BULLISH;
        if (netAlignment < -0.4) return FamilyBias.STRONG_BEARISH;

        // Moderate alignment
        if (netAlignment > 0.2) return FamilyBias.BULLISH;
        if (netAlignment < -0.2) return FamilyBias.BEARISH;

        // Weak or conflicting
        if (Math.abs(netAlignment) < 0.1) return FamilyBias.NEUTRAL;

        return netAlignment > 0 ? FamilyBias.WEAK_BULLISH : FamilyBias.WEAK_BEARISH;
    }

    // ==================== CONTEXT INTERPRETATION ====================

    private String getContextInterpretation(FamilyBias bias, double positionInRange,
                                             boolean atSessionLow, boolean atSessionHigh,
                                             boolean vBottomDetected, boolean vTopDetected,
                                             FuturesSignal futures, OptionsSignal options) {
        StringBuilder sb = new StringBuilder();

        // Position context
        if (atSessionLow) {
            sb.append("AT SESSION LOW (").append(String.format("%.0f%%", positionInRange * 100)).append("). ");

            if (bias == FamilyBias.BEARISH || bias == FamilyBias.STRONG_BEARISH) {
                sb.append("⚠️ CAUTION: Bearish signals at support may indicate REVERSAL setup, not continuation. ");
                if (futures.getSignalType() == FuturesSignalType.SHORT_BUILDUP) {
                    sb.append("SHORTS AT SUPPORT = SQUEEZE FUEL if support holds. ");
                }
            } else if (bias == FamilyBias.BULLISH || bias == FamilyBias.STRONG_BULLISH) {
                sb.append("✅ CONFIRMED: Bullish signals at support = STRONG LONG setup. ");
            }

            if (vBottomDetected) {
                sb.append("V-BOTTOM PATTERN DETECTED! ");
            }

        } else if (atSessionHigh) {
            sb.append("AT SESSION HIGH (").append(String.format("%.0f%%", positionInRange * 100)).append("). ");

            if (bias == FamilyBias.BULLISH || bias == FamilyBias.STRONG_BULLISH) {
                sb.append("⚠️ CAUTION: Bullish signals at resistance may indicate DISTRIBUTION, not breakout. ");
                if (futures.getSignalType() == FuturesSignalType.LONG_BUILDUP) {
                    sb.append("LONGS AT RESISTANCE = TRAPPED if resistance holds. ");
                }
            } else if (bias == FamilyBias.BEARISH || bias == FamilyBias.STRONG_BEARISH) {
                sb.append("✅ CONFIRMED: Bearish signals at resistance = STRONG SHORT setup. ");
            }

            if (vTopDetected) {
                sb.append("V-TOP PATTERN DETECTED! ");
            }

        } else {
            sb.append("MID-RANGE (").append(String.format("%.0f%%", positionInRange * 100)).append("). ");
            sb.append("Standard interpretation applies. ");
        }

        // PCR context
        if (options.isPcrExtremeHigh()) {
            sb.append("EXTREME FEAR (PCR=").append(String.format("%.2f", options.getPcr())).append(") - Contrarian bullish. ");
        } else if (options.isPcrExtremeLow()) {
            sb.append("EXTREME GREED (PCR=").append(String.format("%.2f", options.getPcr())).append(") - Contrarian bearish. ");
        }

        return sb.toString().trim();
    }

    // ==================== SQUEEZE SETUP DETECTION ====================

    private boolean isShortSqueezeSetup(boolean atSessionLow, FuturesSignal futures,
                                         OptionsSignal options, Double pcr) {
        if (!atSessionLow) return false;

        // Need shorts building at support
        boolean shortsBuilding = futures.getSignalType() == FuturesSignalType.SHORT_BUILDUP;

        // Need fear (high PCR) or put accumulation
        boolean fearPresent = (pcr != null && pcr > 1.2) || options.isPutOIUp();

        return shortsBuilding && fearPresent;
    }

    private boolean isLongSqueezeSetup(boolean atSessionHigh, FuturesSignal futures,
                                        OptionsSignal options, Double pcr) {
        if (!atSessionHigh) return false;

        // Need longs building at resistance
        boolean longsBuilding = futures.getSignalType() == FuturesSignalType.LONG_BUILDUP;

        // Need greed (low PCR) or call accumulation
        boolean greedPresent = (pcr != null && pcr < 0.8) || options.isCallOIUp();

        return longsBuilding && greedPresent;
    }

    // ==================== CONFIDENCE CALCULATION ====================

    private double calculateConfidence(double bullishAlignment, double bearishAlignment,
                                        EquitySignal equity, FuturesSignal futures, OptionsSignal options) {
        double confidence = 0.5;  // Base

        // Alignment strength
        double maxAlignment = Math.max(bullishAlignment, bearishAlignment);
        confidence += maxAlignment * 0.3;

        // Data completeness
        if (futures.getSignalType() != FuturesSignalType.NEUTRAL) confidence += 0.1;
        if (options.getFlowSignal() != OptionsFlowSignal.NEUTRAL) confidence += 0.1;

        // Strong moves
        if (equity.isStrongMove()) confidence += 0.05;

        return Math.min(1.0, confidence);
    }

    // ==================== DATA MODELS ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FamilyContext {
        private String familyId;
        private long timestamp;

        // Position context
        private double positionInRange;
        private boolean atSessionLow;
        private boolean atSessionHigh;
        private boolean vBottomDetected;
        private boolean vTopDetected;

        // Individual signals
        private EquitySignal equitySignal;
        private FuturesSignal futuresSignal;
        private OptionsSignal optionsSignal;

        // Alignment
        private double bullishAlignment;
        private double bearishAlignment;
        private FamilyBias overallBias;
        private double confidence;

        // Divergences
        private List<String> divergences;
        private boolean hasDivergence;
        private String contextInterpretation;

        // Special setups
        private boolean shortSqueezeSetup;
        private boolean longSqueezeSetup;

        // Flags
        private boolean fullyAlignedBullish;
        private boolean fullyAlignedBearish;
        private boolean hasOptionsData;
        private boolean hasFuturesData;

        public static FamilyContext empty() {
            return FamilyContext.builder()
                    .positionInRange(0.5)
                    .overallBias(FamilyBias.NEUTRAL)
                    .equitySignal(EquitySignal.neutral())
                    .futuresSignal(FuturesSignal.neutral())
                    .optionsSignal(OptionsSignal.neutral())
                    .divergences(new ArrayList<>())
                    .build();
        }

        public boolean isBullish() {
            return overallBias == FamilyBias.BULLISH || overallBias == FamilyBias.STRONG_BULLISH;
        }

        public boolean isBearish() {
            return overallBias == FamilyBias.BEARISH || overallBias == FamilyBias.STRONG_BEARISH;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EquitySignal {
        private double priceChangePct;
        private boolean bullish;
        private boolean bearish;
        private boolean strongMove;
        private boolean bullishCandle;
        private boolean doji;
        private boolean hammer;
        private boolean shootingStar;
        private double buyVolumeRatio;
        private String volumeDominance;

        public static EquitySignal neutral() {
            return EquitySignal.builder()
                    .buyVolumeRatio(0.5)
                    .volumeDominance("NEUTRAL")
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FuturesSignal {
        private double priceChangePct;
        private boolean priceUp;
        private boolean priceDown;
        private long oiChange;
        private double oiChangePct;
        private boolean oiUp;
        private boolean oiDown;
        private FuturesSignalType signalType;
        private String buildup;
        private String oiSignal;
        private Double spotFuturePremium;
        private boolean premiumExpanding;
        private boolean premiumContracting;

        public static FuturesSignal neutral() {
            return FuturesSignal.builder()
                    .signalType(FuturesSignalType.NEUTRAL)
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionsSignal {
        private long callOIChange;
        private long putOIChange;
        private boolean callOIUp;
        private boolean callOIDown;
        private boolean putOIUp;
        private boolean putOIDown;
        private Double pcr;
        private Double pcrChange;
        private boolean pcrBullish;
        private boolean pcrBearish;
        private boolean pcrExtremeLow;
        private boolean pcrExtremeHigh;
        private boolean pcrFalling;
        private boolean pcrRising;
        private double avgCallPremiumChange;
        private double avgPutPremiumChange;
        private OptionsFlowSignal flowSignal;

        public static OptionsSignal neutral() {
            return OptionsSignal.builder()
                    .flowSignal(OptionsFlowSignal.NEUTRAL)
                    .build();
        }
    }

    public enum FamilyBias {
        STRONG_BULLISH,
        BULLISH,
        WEAK_BULLISH,
        NEUTRAL,
        WEAK_BEARISH,
        BEARISH,
        STRONG_BEARISH
    }

    public enum FuturesSignalType {
        LONG_BUILDUP,      // Price up + OI up = new longs (bullish)
        SHORT_COVERING,    // Price up + OI down = shorts exiting (bullish, exhausting)
        SHORT_BUILDUP,     // Price down + OI up = new shorts (bearish)
        LONG_UNWINDING,    // Price down + OI down = longs exiting (bearish, exhausting)
        NEUTRAL
    }

    public enum OptionsFlowSignal {
        BULLISH_FLOW,      // Call accumulation + put exit
        BEARISH_FLOW,      // Put accumulation + call exit
        HEDGING,           // Both accumulating
        UNWINDING,         // Both exiting
        NEUTRAL
    }
}
