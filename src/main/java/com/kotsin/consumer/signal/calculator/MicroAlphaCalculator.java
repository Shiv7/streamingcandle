package com.kotsin.consumer.signal.calculator;

import com.kotsin.consumer.model.OIMetrics.OIInterpretation;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.options.model.OptionsAnalytics;
import com.kotsin.consumer.session.model.SessionStructure;
import com.kotsin.consumer.indicator.model.TechnicalIndicators;
import com.kotsin.consumer.regime.model.MarketRegime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * MicroAlphaCalculator - Conviction scoring engine for Strategy 4.
 *
 * EDGE: Exploit information asymmetry when institutional footprint
 * (orderbook, OI, options positioning) diverges from price action.
 *
 * Sub-scores (each independently meaningful, NOT a blended soup):
 *
 * 1. FLOW CONVICTION     - OFI direction + depth absorption + VPIN toxicity
 * 2. OI CONVICTION       - OI interpretation + velocity divergence from price
 * 3. GAMMA REGIME        - GEX sign determines mean-reversion vs trend mode
 * 4. OPTIONS SENTIMENT   - PCR extremes + IV percentile + max pain gravity
 * 5. SESSION CONTEXT     - Opening range position + VWAP slope + time segment
 *
 * Each sub-score is -100 (strong bearish) to +100 (strong bullish).
 * Final conviction is regime-weighted, NOT a naive average.
 */
@Component
@Slf4j
public class MicroAlphaCalculator {

    private static final String LOG_PREFIX = "[MICRO-ALPHA-CALC]";

    @Value("${microalpha.flow.ofi.weight:0.40}")
    private double ofiWeight;

    @Value("${microalpha.flow.depth.weight:0.30}")
    private double depthWeight;

    @Value("${microalpha.flow.vpin.weight:0.30}")
    private double vpinWeight;

    @Value("${microalpha.oi.interpretation.weight:0.50}")
    private double oiInterpWeight;

    @Value("${microalpha.oi.velocity.weight:0.30}")
    private double oiVelocityWeight;

    @Value("${microalpha.oi.reversal.weight:0.20}")
    private double oiReversalWeight;

    // ==================== MAIN CALCULATION ====================

    /**
     * Calculate all MicroAlpha sub-scores from available data.
     *
     * @param candle     Current 1m unified candle (tick + orderbook + OI merged)
     * @param options    Chain-level options analytics (nullable)
     * @param session    Session structure (nullable)
     * @param indicators Technical indicators (nullable)
     * @param regime     Market regime (nullable)
     * @return MicroAlphaScore with all sub-scores and conviction
     */
    public MicroAlphaScore calculate(UnifiedCandle candle,
                                      OptionsAnalytics options,
                                      SessionStructure session,
                                      TechnicalIndicators indicators,
                                      MarketRegime regime) {

        if (candle == null) {
            return MicroAlphaScore.empty(null);
        }

        String symbol = candle.getSymbol();
        List<String> reasons = new ArrayList<>();

        // 1. Flow Conviction: OFI + depth absorption + VPIN
        FlowScore flow = calculateFlowConviction(candle, reasons);

        // 2. OI Conviction: interpretation + velocity divergence
        OIScore oi = calculateOIConviction(candle, reasons);

        // 3. Gamma Regime: GEX sign → strategy mode
        GammaRegimeScore gamma = calculateGammaRegime(candle, options, reasons);

        // 4. Options Sentiment: PCR + IV + max pain
        OptionsSentimentScore optSentiment = calculateOptionsSentiment(options, candle, reasons);

        // 5. Session Context: OR position + VWAP + time
        SessionContextScore sessionCtx = calculateSessionContext(session, candle, reasons);

        // 6. Determine trading mode from gamma + market regime
        TradingMode mode = determineTradingMode(gamma, regime);

        // 7. Calculate regime-weighted conviction
        double conviction = calculateRegimeWeightedConviction(
            flow, oi, gamma, optSentiment, sessionCtx, mode, indicators);

        // 8. Determine direction
        ConvictionDirection direction = conviction > 0 ? ConvictionDirection.BULLISH :
                                        conviction < 0 ? ConvictionDirection.BEARISH :
                                        ConvictionDirection.NEUTRAL;

        double absConviction = Math.abs(conviction);

        MicroAlphaScore score = MicroAlphaScore.builder()
            .symbol(symbol)
            .timestamp(Instant.now())
            // Sub-scores
            .flowScore(flow)
            .oiScore(oi)
            .gammaRegime(gamma)
            .optionsSentiment(optSentiment)
            .sessionContext(sessionCtx)
            // Overall
            .tradingMode(mode)
            .direction(direction)
            .conviction(conviction)
            .absConviction(absConviction)
            .reasons(reasons)
            // Data quality
            .hasOrderbook(candle.isHasOrderbook())
            .hasOI(candle.isHasOI())
            .hasOptions(options != null)
            .hasSession(session != null)
            .build();

        log.debug("{} {} conviction={} direction={} mode={} [flow={} oi={} gamma={} opts={} session={}]",
            LOG_PREFIX, symbol, String.format("%.1f", conviction), direction, mode,
            String.format("%.0f", flow.getScore()), String.format("%.0f", oi.getScore()),
            String.format("%.0f", gamma.getScore()),
            String.format("%.0f", optSentiment.getScore()), String.format("%.0f", sessionCtx.getScore()));

        return score;
    }

    // ==================== SUB-SCORE CALCULATORS ====================

    /**
     * Flow Conviction: OFI direction + depth absorption + VPIN toxicity.
     *
     * OFI > 0 + depth imbalance toward bid = strong buying flow.
     * VPIN spike = informed traders are active (directional uncertainty).
     * Depth slope divergence = hidden absorption (institutional).
     */
    private FlowScore calculateFlowConviction(UnifiedCandle candle, List<String> reasons) {
        if (!candle.isHasOrderbook()) {
            return FlowScore.builder().score(0).confidence(0).reason("No orderbook data").build();
        }

        // --- OFI Component: normalized to -100..+100 ---
        double ofiScore = 0;
        Double normOfi = candle.getNormalizedOfi();
        if (normOfi != null) {
            // Normalized OFI typically -2 to +2, scale to -100..+100
            ofiScore = Math.max(-100, Math.min(100, normOfi * 50));
        }

        // --- Depth Absorption Component ---
        // When depth imbalance diverges from OFI → absorption happening
        // Bid depth increasing while price drops = buy-side absorption = bullish
        double depthScore = 0;
        Double depthImb = candle.getDepthImbalance();
        if (depthImb != null) {
            // Direct depth imbalance: -1 (all asks) to +1 (all bids), scale to -100..+100
            depthScore = depthImb * 100;

            // Check for absorption: depth favors one side while OFI suggests opposite
            if (normOfi != null) {
                boolean ofiSellsButBidHolds = normOfi < -0.5 && depthImb > 0.2;
                boolean ofiBuysButAskHolds = normOfi > 0.5 && depthImb < -0.2;

                if (ofiSellsButBidHolds) {
                    depthScore += 30; // Absorption signal: bids holding despite sell flow
                    reasons.add("Bid-side absorption detected (OFI selling but bids hold)");
                }
                if (ofiBuysButAskHolds) {
                    depthScore -= 30; // Absorption signal: asks holding despite buy flow
                    reasons.add("Ask-side absorption detected (OFI buying but asks hold)");
                }
            }
            depthScore = Math.max(-100, Math.min(100, depthScore));
        }

        // --- VPIN Component ---
        // High VPIN = informed trading, amplifies directional signal
        double vpinScore = 0;
        double vpin = candle.getVpin();
        if (vpin > 0) {
            // VPIN > 0.7 = high toxicity (informed flow dominates)
            // Use as a confidence multiplier, not direction
            // If VPIN high + OFI directional → stronger signal
            double vpinIntensity = Math.min(1.0, vpin / 0.8);
            vpinScore = ofiScore * vpinIntensity * 0.5; // Amplify OFI direction
            if (vpin > 0.7) {
                reasons.add(String.format("High VPIN=%.2f (informed flow active)", vpin));
            }
        }

        // --- Anomaly boost ---
        Integer spoof = candle.getSpoofingCount();
        Boolean iceberg = candle.getIcebergDetected();
        double anomalyBoost = 0;
        if (spoof != null && spoof > 2) {
            // Spoofing often on the fake side; if bids spoofed, real pressure is sells
            anomalyBoost = -Math.signum(depthScore) * 15;
            reasons.add(String.format("Spoofing detected (%d events) - potential fake depth", spoof));
        }
        if (iceberg != null && iceberg) {
            // Iceberg = hidden size, confirms real interest on that side
            anomalyBoost += Math.signum(depthScore) * 10;
            reasons.add("Iceberg order detected - confirms hidden institutional interest");
        }

        // --- Combine ---
        double composite = ofiScore * ofiWeight + depthScore * depthWeight + vpinScore * vpinWeight + anomalyBoost;
        composite = Math.max(-100, Math.min(100, composite));

        double confidence = Math.min(1.0, (Math.abs(ofiScore) + Math.abs(depthScore)) / 100.0);

        return FlowScore.builder()
            .score(composite)
            .ofiComponent(ofiScore)
            .depthComponent(depthScore)
            .vpinComponent(vpinScore)
            .anomalyBoost(anomalyBoost)
            .confidence(confidence)
            .build();
    }

    /**
     * OI Conviction: interpretation + velocity divergence.
     *
     * The key alpha: OI velocity divergence from price.
     * - Price up + OI velocity decelerating = exhaustion imminent
     * - Price consolidating + OI velocity accelerating = breakout loading
     * - LONG_BUILDUP + accelerating OI = strong continuation
     * - SHORT_COVERING + decelerating OI = weak bounce, will fade
     */
    private OIScore calculateOIConviction(UnifiedCandle candle, List<String> reasons) {
        if (!candle.isHasOI()) {
            return OIScore.builder().score(0).confidence(0).reason("No OI data").build();
        }

        OIInterpretation interp = candle.getOiInterpretation();
        Double oiVelocity = candle.getOiVelocity();
        Double oiConfidence = candle.getOiInterpretationConfidence();
        Boolean suggestsReversal = candle.getOiSuggestsReversal();

        // --- Interpretation component ---
        double interpScore = 0;
        if (interp != null) {
            switch (interp) {
                case LONG_BUILDUP:
                    interpScore = 80;
                    reasons.add("OI: Long buildup (price up + OI up = new longs entering)");
                    break;
                case SHORT_COVERING:
                    interpScore = 40; // Bullish but weaker (shorts exiting, not new longs)
                    reasons.add("OI: Short covering (price up + OI down = shorts exiting)");
                    break;
                case SHORT_BUILDUP:
                    interpScore = -80;
                    reasons.add("OI: Short buildup (price down + OI up = new shorts entering)");
                    break;
                case LONG_UNWINDING:
                    interpScore = -40; // Bearish but weaker (longs exiting, not new shorts)
                    reasons.add("OI: Long unwinding (price down + OI down = longs exiting)");
                    break;
                case NEUTRAL:
                default:
                    interpScore = 0;
                    break;
            }
            // Scale by confidence
            if (oiConfidence != null && oiConfidence > 0) {
                interpScore *= oiConfidence;
            }
        }

        // --- Velocity divergence component ---
        // This is the real alpha: when OI velocity diverges from price direction
        double velocityScore = 0;
        if (oiVelocity != null && interp != null) {
            boolean priceUp = candle.isBullish();

            if (priceUp && oiVelocity < -0.5) {
                // Price rising but OI contracting fast → exhaustion
                velocityScore = -50;
                reasons.add(String.format("OI velocity divergence: price up but OI contracting (vel=%.1f)", oiVelocity));
            } else if (!priceUp && oiVelocity > 0.5) {
                // Price dropping but OI expanding fast → short buildup has conviction
                velocityScore = -50;
                reasons.add(String.format("OI velocity divergence: price down + OI expanding (vel=%.1f)", oiVelocity));
            } else if (Math.abs(candle.getClose() - candle.getOpen()) / candle.getOpen() < 0.001
                       && Math.abs(oiVelocity) > 1.0) {
                // Price flat but OI velocity high → breakout loading
                double oiDirection = interp.isBullish() ? 60 : interp.isBearish() ? -60 : 0;
                velocityScore = oiDirection;
                reasons.add(String.format("OI loading: price flat + OI velocity high (%.1f) = breakout imminent", oiVelocity));
            }
        }

        // --- Reversal component ---
        double reversalScore = 0;
        if (suggestsReversal != null && suggestsReversal) {
            // Reversal signals are counter-trend, less weight
            reversalScore = interp != null && interp.isBullish() ? 30 : -30;
            reasons.add("OI suggests potential reversal");
        }

        double composite = interpScore * oiInterpWeight + velocityScore * oiVelocityWeight
                           + reversalScore * oiReversalWeight;
        composite = Math.max(-100, Math.min(100, composite));

        double confidence = oiConfidence != null ? oiConfidence : 0;

        return OIScore.builder()
            .score(composite)
            .interpretationComponent(interpScore)
            .velocityComponent(velocityScore)
            .reversalComponent(reversalScore)
            .interpretation(interp)
            .oiVelocity(oiVelocity != null ? oiVelocity : 0)
            .confidence(confidence)
            .build();
    }

    /**
     * Gamma Regime: GEX sign → determines strategy mode.
     *
     * POSITIVE GEX (dealers long gamma):
     *   - Dealers sell rallies, buy dips → mean-reversion environment
     *   - Price gets pinned toward max pain
     *   - Use: fade moves away from max pain, buy dips, sell rallies
     *
     * NEGATIVE GEX (dealers short gamma):
     *   - Dealers amplify moves (sell into dips, buy into rallies) → trend environment
     *   - Realized vol explodes, big moves happen
     *   - Use: trend-following, breakout strategies
     */
    private GammaRegimeScore calculateGammaRegime(UnifiedCandle candle, OptionsAnalytics options,
                                                    List<String> reasons) {
        if (options == null) {
            return GammaRegimeScore.builder()
                .mode(GammaMode.UNKNOWN)
                .score(0)
                .confidence(0)
                .reason("No options data")
                .build();
        }

        boolean positiveGamma = options.isPositiveGamma();
        double totalGex = options.getTotalGEX();
        double gexFlip = options.getGexFlipPoint();
        double spot = options.getSpotPrice();
        double maxPainDist = options.getMaxPainDistance();

        GammaMode mode;
        double score = 0;

        if (positiveGamma) {
            mode = GammaMode.POSITIVE;

            // In positive gamma: price pinned to max pain
            // Score = how far from max pain (further = more reversion expected)
            // Positive score = expect price to go UP toward max pain
            // Negative score = expect price to go DOWN toward max pain
            if (maxPainDist < -0.005) {
                // Price below max pain → expect move UP
                score = Math.min(80, Math.abs(maxPainDist) * 5000);
                reasons.add(String.format("Positive GEX + price %.1f%% below max pain → mean-reversion UP expected",
                    maxPainDist * 100));
            } else if (maxPainDist > 0.005) {
                // Price above max pain → expect move DOWN
                score = -Math.min(80, Math.abs(maxPainDist) * 5000);
                reasons.add(String.format("Positive GEX + price %.1f%% above max pain → mean-reversion DOWN expected",
                    maxPainDist * 100));
            } else {
                score = 0;
                reasons.add("Positive GEX + price near max pain → pinned, avoid");
            }
        } else {
            mode = GammaMode.NEGATIVE;

            // In negative gamma: dealers amplify moves
            // Score follows the flow direction (trend amplification)
            // Use flow direction to determine which way the amplification goes
            double priceVsFlip = spot > 0 && gexFlip > 0 ? (spot - gexFlip) / spot * 100 : 0;

            if (priceVsFlip > 0.5) {
                score = 60; // Above GEX flip → upside amplification
                reasons.add(String.format("Negative GEX + price above flip point → upside amplification"));
            } else if (priceVsFlip < -0.5) {
                score = -60; // Below GEX flip → downside amplification
                reasons.add(String.format("Negative GEX + price below flip point → downside amplification"));
            } else {
                score = 0;
                reasons.add("Negative GEX near flip point → volatile, direction unclear");
            }
        }

        double confidence = options.getAtmIV() > 0 ? 0.7 : 0.3;

        return GammaRegimeScore.builder()
            .mode(mode)
            .score(score)
            .totalGex(totalGex)
            .gexFlipPoint(gexFlip)
            .maxPainDistance(maxPainDist)
            .confidence(confidence)
            .build();
    }

    /**
     * Options Sentiment: PCR extremes + IV percentile + OI walls.
     *
     * - PCR > 1.5 = extreme fear → contrarian bullish
     * - PCR < 0.5 = extreme greed → contrarian bearish
     * - IV percentile > 80 = high fear → sell-the-rip
     * - IV percentile < 20 = complacency → buy-the-dip or breakout
     * - Price near call wall = resistance, near put wall = support
     */
    private OptionsSentimentScore calculateOptionsSentiment(OptionsAnalytics options, UnifiedCandle candle,
                                                             List<String> reasons) {
        if (options == null) {
            return OptionsSentimentScore.builder().score(0).confidence(0).reason("No options data").build();
        }

        double score = 0;
        double spot = candle.getClose();

        // --- PCR Component ---
        double pcrScore = 0;
        double pcr = options.getPcrByOI();
        if (pcr > 1.5) {
            pcrScore = 50; // Extreme fear → contrarian bullish
            reasons.add(String.format("PCR=%.2f (extreme fear) → contrarian bullish", pcr));
        } else if (pcr > 1.2) {
            pcrScore = 25;
        } else if (pcr < 0.5) {
            pcrScore = -50; // Extreme greed → contrarian bearish
            reasons.add(String.format("PCR=%.2f (extreme greed) → contrarian bearish", pcr));
        } else if (pcr < 0.8) {
            pcrScore = -25;
        }

        // --- IV Percentile Component ---
        double ivScore = 0;
        double ivPct = options.getIvPercentile();
        if (ivPct > 80) {
            // High IV = fear, premium expensive, options writers profit
            // In positive gamma → mean-reversion (sell vol), contrarian signals
            ivScore = -20; // Slightly bearish (fear environment, rip-selling)
            reasons.add(String.format("IV percentile %.0f (high fear environment)", ivPct));
        } else if (ivPct < 20) {
            // Low IV = complacency, cheap options
            ivScore = 10; // Slightly bullish (complacent, breakout setup)
            reasons.add(String.format("IV percentile %.0f (complacent, breakout possible)", ivPct));
        }

        // --- OI Wall Component ---
        double wallScore = 0;
        double callWall = options.getCallOIWall();
        double putWall = options.getPutOIWall();

        if (callWall > 0 && putWall > 0 && spot > 0) {
            double distToCallWall = (callWall - spot) / spot * 100;
            double distToPutWall = (spot - putWall) / spot * 100;

            if (distToCallWall < 0.5) {
                wallScore = -30; // Very close to call wall = resistance
                reasons.add(String.format("Price near call OI wall (%.0f, %.1f%% away) = resistance", callWall, distToCallWall));
            } else if (distToPutWall < 0.5) {
                wallScore = 30; // Very close to put wall = support
                reasons.add(String.format("Price near put OI wall (%.0f, %.1f%% away) = support", putWall, distToPutWall));
            }

            // If price between walls with more room to call wall → bullish asymmetry
            if (distToCallWall > distToPutWall * 1.5) {
                wallScore += 10;
            } else if (distToPutWall > distToCallWall * 1.5) {
                wallScore -= 10;
            }
        }

        score = pcrScore * 0.35 + ivScore * 0.25 + wallScore * 0.40;
        score = Math.max(-100, Math.min(100, score));

        return OptionsSentimentScore.builder()
            .score(score)
            .pcrComponent(pcrScore)
            .ivComponent(ivScore)
            .wallComponent(wallScore)
            .pcrByOI(pcr)
            .ivPercentile(ivPct)
            .callWall(callWall)
            .putWall(putWall)
            .confidence(0.6)
            .build();
    }

    /**
     * Session Context: Opening range + VWAP slope + time segment.
     *
     * - OR breakout with volume = strongest intraday setup
     * - VWAP slope change = institutional flow direction shift
     * - OPENING/MORNING segments most liquid and directional
     * - MIDDAY segment = noise, avoid
     * - CLOSING segment = positioning for next day
     */
    private SessionContextScore calculateSessionContext(SessionStructure session, UnifiedCandle candle,
                                                         List<String> reasons) {
        if (session == null) {
            return SessionContextScore.builder().score(0).confidence(0).reason("No session data").build();
        }

        double score = 0;

        // --- Opening Range Component ---
        double orScore = 0;
        if (session.isOpeningRangeComplete()) {
            double price = candle.getClose();
            double orHigh = session.getOpeningRangeHigh30();
            double orLow = session.getOpeningRangeLow30();

            if (price > orHigh) {
                double penetration = (price - orHigh) / orHigh * 100;
                orScore = Math.min(60, penetration * 30); // More penetration = stronger signal
                reasons.add(String.format("Price %.1f%% above 30m Opening Range (bullish breakout)", penetration));
            } else if (price < orLow) {
                double penetration = (orLow - price) / orLow * 100;
                orScore = -Math.min(60, penetration * 30);
                reasons.add(String.format("Price %.1f%% below 30m Opening Range (bearish breakdown)", penetration));
            } else {
                // Inside opening range — neutral, wait for breakout
                orScore = 0;
            }
        }

        // --- VWAP Component ---
        double vwapScore = 0;
        double vwapSlope = session.getVwapSlope();
        double vwap = session.getVwap();
        double price = candle.getClose();

        if (vwap > 0) {
            boolean aboveVwap = price > vwap;
            if (aboveVwap && vwapSlope > 0) {
                vwapScore = 40; // Price above rising VWAP = institutional buying
                reasons.add("Price above VWAP with positive slope → institutional buying");
            } else if (!aboveVwap && vwapSlope < 0) {
                vwapScore = -40; // Price below falling VWAP = institutional selling
                reasons.add("Price below VWAP with negative slope → institutional selling");
            } else if (aboveVwap && vwapSlope < 0) {
                vwapScore = 10; // Price above but VWAP slope turning → weakening
            } else if (!aboveVwap && vwapSlope > 0) {
                vwapScore = -10; // Price below but VWAP slope turning → potential reversal
            }
        }

        // --- Time Segment Component ---
        double timeScore = 0;
        SessionStructure.SessionSegment segment = session.getCurrentSegment();
        double timeMultiplier = 1.0;

        if (segment != null) {
            switch (segment) {
                case OPENING:
                    timeMultiplier = 1.3; // Highest conviction period
                    timeScore = 10;
                    break;
                case MORNING:
                    timeMultiplier = 1.1;
                    timeScore = 5;
                    break;
                case MIDDAY:
                    timeMultiplier = 0.6; // Noise period, reduce conviction
                    timeScore = -15;
                    reasons.add("Midday session → reduced conviction (low volume noise)");
                    break;
                case AFTERNOON:
                    timeMultiplier = 0.9;
                    timeScore = 0;
                    break;
                case CLOSING:
                    timeMultiplier = 0.7; // End-of-day positioning, unreliable
                    timeScore = -10;
                    break;
                default:
                    timeMultiplier = 0.3; // Pre/post market
                    timeScore = -30;
                    break;
            }
        }

        // --- Gap analysis boost ---
        double gapScore = 0;
        if (session.getGapType() != null && session.getGapType() != SessionStructure.GapType.NO_GAP) {
            if (session.getGapType() == SessionStructure.GapType.GAP_UP && !session.isGapFilled()) {
                gapScore = 20; // Unfilled gap up = bullish
                reasons.add("Unfilled gap up → bullish continuation bias");
            } else if (session.getGapType() == SessionStructure.GapType.GAP_DOWN && !session.isGapFilled()) {
                gapScore = -20; // Unfilled gap down = bearish
                reasons.add("Unfilled gap down → bearish continuation bias");
            }
        }

        score = (orScore * 0.35 + vwapScore * 0.30 + timeScore * 0.15 + gapScore * 0.20) * timeMultiplier;
        score = Math.max(-100, Math.min(100, score));

        return SessionContextScore.builder()
            .score(score)
            .orComponent(orScore)
            .vwapComponent(vwapScore)
            .timeComponent(timeScore)
            .gapComponent(gapScore)
            .timeMultiplier(timeMultiplier)
            .currentSegment(segment)
            .confidence(session.isOpeningRangeComplete() ? 0.7 : 0.3)
            .build();
    }

    // ==================== REGIME-WEIGHTED CONVICTION ====================

    /**
     * Determine trading mode from gamma regime + market regime.
     */
    private TradingMode determineTradingMode(GammaRegimeScore gamma, MarketRegime regime) {
        if (gamma.getMode() == GammaMode.POSITIVE) {
            // Positive gamma → mean-reversion preferred
            if (regime != null && regime.isTrending() &&
                regime.getTrendStrength() == MarketRegime.TrendStrength.VERY_STRONG) {
                return TradingMode.TREND_WITH_CAUTION; // Strong trend overrides gamma
            }
            return TradingMode.MEAN_REVERSION;
        }

        if (gamma.getMode() == GammaMode.NEGATIVE) {
            // Negative gamma → trend-following preferred
            if (regime != null && regime.isRanging()) {
                return TradingMode.BREAKOUT_AWAITING; // Negative gamma in range = breakout imminent
            }
            return TradingMode.TREND_FOLLOWING;
        }

        // Unknown gamma → use market regime to determine mode (don't default to CAUTIOUS)
        if (regime == null) return TradingMode.TREND_FOLLOWING; // Assume trend when no data

        if (regime.isTrending()) return TradingMode.TREND_FOLLOWING;
        if (regime.isBreakoutSetup()) return TradingMode.BREAKOUT_AWAITING;
        if (regime.isRanging()) return TradingMode.MEAN_REVERSION;
        if (regime.isChoppy()) return TradingMode.AVOID;

        return TradingMode.TREND_FOLLOWING;
    }

    /**
     * Calculate final conviction with regime-adaptive weighting.
     *
     * Different modes weight the sub-scores differently:
     * - TREND_FOLLOWING: Flow 35%, OI 30%, Gamma 15%, Options 10%, Session 10%
     * - MEAN_REVERSION: Gamma 30%, Options 30%, Flow 15%, OI 15%, Session 10%
     * - BREAKOUT_AWAITING: OI 30%, Flow 25%, Session 20%, Gamma 15%, Options 10%
     */
    private double calculateRegimeWeightedConviction(FlowScore flow, OIScore oi,
                                                      GammaRegimeScore gamma, OptionsSentimentScore opts,
                                                      SessionContextScore session, TradingMode mode,
                                                      TechnicalIndicators indicators) {
        double flowW, oiW, gammaW, optsW, sessionW;

        // Check if options data is available (gamma/opts scores are 0 when null)
        boolean hasOptionsData = gamma.getConfidence() > 0 || opts.getConfidence() > 0;

        switch (mode) {
            case TREND_FOLLOWING:
                flowW = 0.35; oiW = 0.30; gammaW = 0.15; optsW = 0.10; sessionW = 0.10;
                break;
            case MEAN_REVERSION:
                flowW = 0.15; oiW = 0.15; gammaW = 0.30; optsW = 0.30; sessionW = 0.10;
                break;
            case BREAKOUT_AWAITING:
                flowW = 0.25; oiW = 0.30; gammaW = 0.15; optsW = 0.10; sessionW = 0.20;
                break;
            case TREND_WITH_CAUTION:
                flowW = 0.30; oiW = 0.25; gammaW = 0.20; optsW = 0.15; sessionW = 0.10;
                break;
            case AVOID:
                return 0; // Don't trade
            case CAUTIOUS:
            default:
                flowW = 0.20; oiW = 0.20; gammaW = 0.20; optsW = 0.20; sessionW = 0.20;
                break;
        }

        // When options data unavailable, redistribute gamma+opts weights to flow/OI/session
        if (!hasOptionsData) {
            double redistributed = gammaW + optsW;
            gammaW = 0;
            optsW = 0;
            // Redistribute proportionally to remaining weights
            double remaining = flowW + oiW + sessionW;
            if (remaining > 0) {
                flowW += redistributed * (flowW / remaining);
                oiW += redistributed * (oiW / remaining);
                sessionW += redistributed * (sessionW / remaining);
            }
        }

        double conviction = flow.getScore() * flowW
                          + oi.getScore() * oiW
                          + gamma.getScore() * gammaW
                          + opts.getScore() * optsW
                          + session.getScore() * sessionW;

        // Technical indicator alignment bonus/penalty
        if (indicators != null) {
            boolean techBullish = indicators.isBullish();
            boolean techBearish = indicators.isBearish();

            if (conviction > 30 && techBullish) {
                conviction *= 1.15; // 15% boost for alignment
            } else if (conviction < -30 && techBearish) {
                conviction *= 1.15;
            } else if ((conviction > 30 && techBearish) || (conviction < -30 && techBullish)) {
                conviction *= 0.70; // 30% penalty for divergence
            }
        }

        return Math.max(-100, Math.min(100, conviction));
    }

    // ==================== RESULT MODEL ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MicroAlphaScore {
        private String symbol;
        private Instant timestamp;

        // Sub-scores
        private FlowScore flowScore;
        private OIScore oiScore;
        private GammaRegimeScore gammaRegime;
        private OptionsSentimentScore optionsSentiment;
        private SessionContextScore sessionContext;

        // Overall
        private TradingMode tradingMode;
        private ConvictionDirection direction;
        private double conviction;       // -100 to +100
        private double absConviction;    // 0 to 100

        // Reasons
        private List<String> reasons;

        // Data quality flags
        private boolean hasOrderbook;
        private boolean hasOI;
        private boolean hasOptions;
        private boolean hasSession;

        public boolean isActionable() {
            return absConviction >= 40 && tradingMode != TradingMode.AVOID;
        }

        public boolean isHighConviction() {
            return absConviction >= 65;
        }

        public static MicroAlphaScore empty(String symbol) {
            return MicroAlphaScore.builder()
                .symbol(symbol)
                .timestamp(Instant.now())
                .flowScore(FlowScore.builder().build())
                .oiScore(OIScore.builder().build())
                .gammaRegime(GammaRegimeScore.builder().mode(GammaMode.UNKNOWN).build())
                .optionsSentiment(OptionsSentimentScore.builder().build())
                .sessionContext(SessionContextScore.builder().build())
                .tradingMode(TradingMode.CAUTIOUS)
                .direction(ConvictionDirection.NEUTRAL)
                .conviction(0)
                .absConviction(0)
                .reasons(new ArrayList<>())
                .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FlowScore {
        private double score;            // -100 to +100
        private double ofiComponent;
        private double depthComponent;
        private double vpinComponent;
        private double anomalyBoost;
        private double confidence;       // 0 to 1
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OIScore {
        private double score;
        private double interpretationComponent;
        private double velocityComponent;
        private double reversalComponent;
        private OIInterpretation interpretation;
        private double oiVelocity;
        private double confidence;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GammaRegimeScore {
        private GammaMode mode;
        private double score;
        private double totalGex;
        private double gexFlipPoint;
        private double maxPainDistance;
        private double confidence;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionsSentimentScore {
        private double score;
        private double pcrComponent;
        private double ivComponent;
        private double wallComponent;
        private double pcrByOI;
        private double ivPercentile;
        private double callWall;
        private double putWall;
        private double confidence;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionContextScore {
        private double score;
        private double orComponent;
        private double vwapComponent;
        private double timeComponent;
        private double gapComponent;
        private double timeMultiplier;
        private SessionStructure.SessionSegment currentSegment;
        private double confidence;
        private String reason;
    }

    // ==================== ENUMS ====================

    public enum GammaMode {
        POSITIVE,    // Dealers long gamma → mean-reversion
        NEGATIVE,    // Dealers short gamma → trend-following
        UNKNOWN      // No options data
    }

    public enum TradingMode {
        TREND_FOLLOWING,      // Ride the trend (negative gamma + trending)
        MEAN_REVERSION,       // Fade extremes (positive gamma + ranging)
        BREAKOUT_AWAITING,    // Wait for breakout (negative gamma + ranging)
        TREND_WITH_CAUTION,   // Very strong trend overrides positive gamma
        CAUTIOUS,             // Insufficient data, reduce size
        AVOID                 // Don't trade (choppy, no edge)
    }

    public enum ConvictionDirection {
        BULLISH,
        BEARISH,
        NEUTRAL
    }
}
