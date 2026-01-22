package com.kotsin.consumer.enrichment;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.enrichment.analyzer.FamilyContextAnalyzer;
import com.kotsin.consumer.enrichment.analyzer.FamilyContextAnalyzer.FamilyContext;
import com.kotsin.consumer.enrichment.calculator.ConfluenceCalculator;
import com.kotsin.consumer.enrichment.calculator.GEXCalculator;
import com.kotsin.consumer.enrichment.calculator.MaxPainCalculator;
import com.kotsin.consumer.enrichment.calculator.ThresholdCalculator;
import com.kotsin.consumer.enrichment.config.CommodityConfig;
import com.kotsin.consumer.enrichment.detector.EventDetector;
import com.kotsin.consumer.enrichment.enricher.ExpiryContextEnricher;
import com.kotsin.consumer.enrichment.enricher.HistoricalContextEnricher;
import com.kotsin.consumer.enrichment.enricher.TechnicalIndicatorEnricher;
import com.kotsin.consumer.enrichment.enricher.TimeContextEnricher;
import com.kotsin.consumer.enrichment.model.*;
import com.kotsin.consumer.enrichment.tracker.SessionStructureTracker;
import com.kotsin.consumer.enrichment.tracker.SwingPointTracker;
import com.kotsin.consumer.enrichment.tracker.SwingPointTracker.SwingAnalysis;
import com.kotsin.consumer.curated.model.MultiTimeframeLevels;
import com.kotsin.consumer.curated.service.MultiTimeframeLevelCalculator;
import com.kotsin.consumer.enrichment.store.EventStore;
import com.kotsin.consumer.trading.mtf.HtfCandleAggregator;
import com.kotsin.consumer.trading.mtf.MtfSmcAnalyzer;
import com.kotsin.consumer.trading.mtf.MtfSmcContext;
import com.kotsin.consumer.quant.calculator.QuantScoreCalculator;
import com.kotsin.consumer.quant.model.QuantScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * EnrichedQuantScoreCalculator - Wraps QuantScoreCalculator with Phase 1, 2, 3 & 4 enrichment.
 *
 * This is the integration point between:
 * - Existing QuantScoreCalculator (stateless)
 * - Phase 1: HistoricalContextEnricher (stateful, adaptive)
 * - Phase 2: GEX, MaxPain, TimeContext, ExpiryContext enrichers
 * - Phase 3: EventDetector, TechnicalIndicatorEnricher, ConfluenceCalculator
 * - Phase 4: PatternMatcher (sequence recognition, predictive patterns)
 *
 * Enhancements:
 * 1. Adds historical context before score calculation
 * 2. Uses adaptive thresholds based on percentiles
 * 3. Applies commodity-specific modifiers
 * 4. Detects flips and derived signals
 * 5. Adjusts confidence based on data completeness
 * 6. GEX regime detection (trending vs mean-reverting)
 * 7. Max Pain analysis (expiry day positioning)
 * 8. Time context (session quality, prime trading hours)
 * 9. Expiry context (DTE awareness, gamma effects)
 * 10. Technical indicators (SuperTrend, BB, Pivots)
 * 11. Event detection (OFI flip, exhaustion, absorption, etc.)
 * 12. Confluence zones (S/R level clustering)
 * 13. Pattern recognition (sequence matching, predictive signals)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnrichedQuantScoreCalculator {

    private final QuantScoreCalculator baseCalculator;
    private final HistoricalContextEnricher historicalEnricher;
    private final ThresholdCalculator thresholdCalculator;
    private final CommodityConfig commodityConfig;

    // Phase 2 enrichers
    private final GEXCalculator gexCalculator;
    private final MaxPainCalculator maxPainCalculator;
    private final TimeContextEnricher timeContextEnricher;
    private final ExpiryContextEnricher expiryContextEnricher;

    // Phase 3 enrichers
    private final TechnicalIndicatorEnricher technicalEnricher;
    private final EventDetector eventDetector;
    private final ConfluenceCalculator confluenceCalculator;
    private final EventStore eventStore;

    // Session Structure Tracking (CRITICAL for context-aware signals)
    private final SessionStructureTracker sessionStructureTracker;

    // Family Context Analyzer (unified equity + futures + options analysis)
    private final FamilyContextAnalyzer familyContextAnalyzer;

    // Swing Point Tracker (swing highs/lows for structure analysis)
    private final SwingPointTracker swingPointTracker;

    // Multi-Timeframe Level Calculator (daily/weekly/monthly pivots and Fibonacci)
    private final MultiTimeframeLevelCalculator mtfLevelCalculator;

    // HTF Candle Aggregator (aggregates 1m into 5m/15m/1H/4H/Daily)
    private final HtfCandleAggregator htfCandleAggregator;

    // MTF SMC Analyzer (Multi-Timeframe Smart Money Concepts)
    private final MtfSmcAnalyzer mtfSmcAnalyzer;

    /**
     * Calculate enriched QuantScore with historical context and Phase 2, 3, 4 enrichments
     *
     * @param family FamilyCandle to score
     * @return QuantScore with historical context and options intelligence integration
     */
    public EnrichedQuantScore calculate(FamilyCandle family) {
        long startTime = System.nanoTime();

        if (family == null) {
            log.trace("[ENRICH] Received null family, returning empty score");
            return EnrichedQuantScore.empty();
        }

        String familyId = family.getFamilyId();
        double price = family.getPrimaryPrice();

        log.debug("[ENRICH] {} Starting enrichment pipeline | price={} | hasEquity={} | hasFuture={} | optionCount={}",
                familyId, String.format("%.2f", price), family.getEquity() != null, family.getFuture() != null,
                family.getOptions() != null ? family.getOptions().size() : 0);

        try {
            // =============== SESSION STRUCTURE (CRITICAL for context-aware signals) ===============
            // Must be done BEFORE any signal interpretation to know WHERE we are in the session
            long t0 = System.nanoTime();
            SessionStructure sessionStructure = sessionStructureTracker.update(family);
            long sessionMs = (System.nanoTime() - t0) / 1_000_000;

            // =============== PHASE 1: Historical Context ===============
            t0 = System.nanoTime();
            HistoricalContext historicalContext = historicalEnricher.enrich(family);
            long histMs = (System.nanoTime() - t0) / 1_000_000;

            // =============== FAMILY CONTEXT (unified equity + futures + options) ===============
            // This analyzes ALL instruments together for alignment/divergence detection
            t0 = System.nanoTime();
            FamilyContext familyContext = familyContextAnalyzer.analyze(family, sessionStructure);
            long familyMs = (System.nanoTime() - t0) / 1_000_000;

            // =============== SWING POINT TRACKING ===============
            // Track swing highs/lows for structure analysis (trend, support, resistance)
            t0 = System.nanoTime();
            SwingAnalysis swingAnalysis = swingPointTracker.update(family);
            long swingMs = (System.nanoTime() - t0) / 1_000_000;

            // =============== MULTI-TIMEFRAME LEVELS ===============
            // Get daily/weekly/monthly pivot and Fibonacci levels
            // These provide key support/resistance levels across timeframes
            String mtfScripCode = family.getFuture() != null ? family.getFuture().getScripCode() :
                               family.getEquity() != null ? family.getEquity().getScripCode() : null;
            MultiTimeframeLevels mtfLevels = null;
            if (mtfScripCode != null && mtfLevelCalculator != null) {
                mtfLevels = mtfLevelCalculator.calculateLevels(mtfScripCode, family.getPrimaryPrice());
            }

            applyHistoricalModifiers(family, historicalContext);

            log.trace("[ENRICH] {} Phase 1 complete | sessionMs={} | histMs={} | familyMs={} | swingMs={}",
                    familyId, sessionMs, histMs, familyMs, swingMs);

            // =============== PHASE 2: Options Intelligence ===============
            t0 = System.nanoTime();

            // GEX Analysis (market regime detection)
            GEXProfile gexProfile = gexCalculator.calculate(family);

            // Max Pain Analysis (expiry day positioning)
            MaxPainProfile maxPainProfile = maxPainCalculator.calculate(family);

            // Time Context (session awareness)
            TimeContext timeContext = timeContextEnricher.enrich(family);

            // Expiry Context (DTE awareness)
            ExpiryContext expiryContext = expiryContextEnricher.enrich(family);

            long phase2Ms = (System.nanoTime() - t0) / 1_000_000;
            log.trace("[ENRICH] {} Phase 2 complete | gexRegime={} | maxPainBias={} | session={} | dte={} | timeMs={}",
                    familyId,
                    gexProfile != null ? gexProfile.getRegime() : "none",
                    maxPainProfile != null ? maxPainProfile.getBias() : "none",
                    timeContext != null ? timeContext.getSession() : "none",
                    expiryContext != null ? expiryContext.getDaysToExpiry() : -1,
                    phase2Ms);

            // =============== PHASE 3: Signal Intelligence ===============
            t0 = System.nanoTime();

            // Technical Indicators (SuperTrend, BB, Pivots, ATR)
            TechnicalContext technicalContext = technicalEnricher.enrich(family);

            // Confluence Zones (S/R level clustering)
            // BUG FIX: Use getPrimaryPrice() to avoid 0 price for equity-only instruments
            double currentPrice = family.getPrimaryPrice();
            ConfluenceCalculator.ConfluenceResult confluenceResult = confluenceCalculator.calculate(
                    currentPrice, technicalContext, gexProfile, maxPainProfile);

            // Event Detection (OFI flip, exhaustion, absorption, etc.)
            // ENHANCED: Now uses context-aware detection with session structure and family context
            // This enables:
            // 1. Position-aware exhaustion (lower threshold at session extremes)
            // 2. Failed breakout detection
            // 3. Family alignment events (all instruments agreeing)
            // 4. Session reversal events (V-bottom, V-top)
            // 5. Divergence events (price vs options flow)
            List<DetectedEvent> detectedEvents = eventDetector.detectEventsWithContext(
                    family,
                    historicalContext,
                    gexProfile,
                    maxPainProfile,
                    technicalContext,
                    sessionStructure,
                    familyContext
            );

            // Add swing events to detected events
            if (swingAnalysis != null && swingAnalysis.getEvents() != null) {
                detectedEvents.addAll(swingAnalysis.getEvents());
            }

            // Store detected events for lifecycle management
            for (DetectedEvent event : detectedEvents) {
                eventStore.storeEvent(event);
            }

            // Check and update lifecycle of existing events
            eventStore.checkEventLifecycles(family.getFamilyId(), currentPrice);

            long phase3Ms = (System.nanoTime() - t0) / 1_000_000;
            log.trace("[ENRICH] {} Phase 3 complete | techAvail={} | stFlip={} | confluence={} | events={} | timeMs={}",
                    familyId,
                    technicalContext != null,
                    technicalContext != null && technicalContext.isSuperTrendFlip(),
                    confluenceResult != null && confluenceResult.isInConfluenceZone(),
                    detectedEvents.size(),
                    phase3Ms);

            // =============== HTF CANDLE AGGREGATION ===============
            // Aggregate 1m candles into 5m/15m/1H/4H/Daily for proper MTF analysis
            // BUG FIX: Only process 1m candles - higher timeframes would corrupt aggregation!
            // The aggregator is designed for 1m candles only. Processing 5m/15m/30m candles
            // would incorrectly treat them as single-minute data points.
            t0 = System.nanoTime();
            String candleTimeframe = family.getTimeframe();
            boolean is1mCandle = candleTimeframe == null || "1m".equals(candleTimeframe);
            if (htfCandleAggregator != null && is1mCandle) {
                htfCandleAggregator.processCandle(family);
            }

            // =============== MTF SMC ANALYSIS (Multi-Timeframe Smart Money Concepts) ===============
            // REAL institutional analysis: HTF for bias, LTF for entry
            MtfSmcContext mtfSmcContext = null;
            if (mtfSmcAnalyzer != null && currentPrice > 0) {
                mtfSmcContext = mtfSmcAnalyzer.analyze(familyId, currentPrice);

                if (mtfSmcContext != null && mtfSmcContext.getHtfBias() != null) {
                    log.debug("[ENRICH] {} MTF_SMC | HTF={} | zone={} | atPOI={} | ltfSwept={} | conf={}",
                            familyId,
                            mtfSmcContext.getHtfBias(),
                            mtfSmcContext.isInDiscount() ? "DISCOUNT" : (mtfSmcContext.isInPremium() ? "PREMIUM" : "NEUTRAL"),
                            mtfSmcContext.isAtHtfDemandZone() || mtfSmcContext.isAtHtfSupplyZone(),
                            mtfSmcContext.isLtfLiquidityJustSwept(),
                            mtfSmcContext.getMtfConfluenceScore());
                }
            }
            long smcMs = (System.nanoTime() - t0) / 1_000_000;

            // =============== Calculate Base Score ===============
            QuantScore baseScore = baseCalculator.calculate(family);

            // =============== Apply All Modifiers ===============
            // FIX: Clamp each modifier to valid range to prevent NaN/crash from stacking
            // Phase 1 adaptive modifier
            double adaptiveModifier = clampModifier(calculateAdaptiveModifier(historicalContext, family));

            // Phase 2 modifiers
            double gexModifier = clampModifier(calculateGEXModifier(gexProfile, baseScore));
            double maxPainModifier = clampModifier(calculateMaxPainModifier(maxPainProfile, expiryContext, baseScore));
            double timeModifier = clampModifier(timeContext != null ? timeContext.getConfidenceModifier() : 1.0);
            double expiryModifier = clampModifier(expiryContext != null ? expiryContext.getConfidenceModifier() : 1.0);

            // Phase 3 modifiers
            double technicalModifier = clampModifier(calculateTechnicalModifier(technicalContext, baseScore));
            double confluenceModifier = clampModifier(calculateConfluenceModifier(confluenceResult, baseScore));
            double eventModifier = clampModifier(calculateEventModifier(detectedEvents));

            // Combine all modifiers using geometric mean to prevent extreme swings
            // FIX: Pure multiplication (0.8^8 = 0.17) creates unstable outputs
            // Using sqrt(product * average) provides balanced combination
            double[] modifiers = {adaptiveModifier, gexModifier, maxPainModifier,
                    timeModifier, expiryModifier, technicalModifier, confluenceModifier, eventModifier};

            double product = 1.0;
            double sum = 0.0;
            int count = 0;
            for (double m : modifiers) {
                // FIX: Skip invalid modifiers (NaN, Infinity, 0, negative)
                if (Double.isFinite(m) && m > 0) {
                    product *= m;
                    sum += m;
                    count++;
                }
            }

            // Geometric mean approach: sqrt(product * average) balances extremes
            double average = count > 0 ? sum / count : 1.0;
            double geometricMean = count > 0 ? Math.pow(product, 1.0 / count) : 1.0;
            double combinedModifier = Math.sqrt(geometricMean * average);

            // FIX: Additional safety check for NaN/Infinity from pow operations
            if (!Double.isFinite(combinedModifier)) {
                log.warn("[ENRICH] {} combinedModifier NaN/Inf, resetting to 1.0 | modifiers={}",
                        familyId, java.util.Arrays.toString(modifiers));
                combinedModifier = 1.0;
            }
            combinedModifier = Math.max(0.5, Math.min(1.5, combinedModifier)); // Tighter clamp range

            double adjustedScore = Math.min(100, baseScore.getQuantScore() * combinedModifier);

            // =============== Adjust Confidence ===============
            double adjustedConfidence = calculateAdjustedConfidence(
                    baseScore.getConfidence(),
                    historicalContext,
                    timeContext,
                    expiryContext
            );

            // =============== Determine Actionable Moment ===============
            boolean isActionableMoment = determineActionableMoment(
                    historicalContext, gexProfile, maxPainProfile, expiryContext,
                    technicalContext, confluenceResult, detectedEvents, baseScore);

            // =============== Generate Enrichment Note ===============
            String enrichmentNote = generateEnrichmentNote(
                    historicalContext, gexProfile, maxPainProfile, timeContext, expiryContext,
                    technicalContext, confluenceResult, detectedEvents);

            // =============== Build Enriched Score ===============
            // FIX: Extract scripCode, companyName, exchange, and priceTimestamp from FamilyCandle
            // Previously these were set on tempScore but NOT included in the final build!
            // BUG FIX: scripCode and companyName should fall back to equity if no future exists!
            // Previously only checked future, causing equity-only instruments to have NULL scripCode
            // which made the state machine skip them entirely!
            String scripCode = family.getFuture() != null ? family.getFuture().getScripCode() :
                              (family.getEquity() != null ? family.getEquity().getScripCode() : null);
            String companyName = family.getFuture() != null ? family.getFuture().getCompanyName() :
                                (family.getEquity() != null ? family.getEquity().getCompanyName() : null);
            // FIX: Extract exchange from instrument - critical for MCX vs NSE distinction
            String exchange = family.getFuture() != null ? family.getFuture().getExchange() :
                             (family.getEquity() != null ? family.getEquity().getExchange() : "N");
            // FIX: Capture the candle's timestamp for accurate price-time correlation
            long priceTimestamp = family.getWindowEndMillis() > 0 ? family.getWindowEndMillis() :
                                  (family.getTimestamp() > 0 ? family.getTimestamp() : System.currentTimeMillis());

            // DIAGNOSTIC: Log scripCode extraction
            if (scripCode == null) {
                log.warn("[ENRICH_DIAG] scripCode is NULL! | familyId={} | hasFuture={} | hasEquity={} | " +
                        "futureScripCode={} | equityScripCode={}",
                        familyId,
                        family.getFuture() != null,
                        family.getEquity() != null,
                        family.getFuture() != null ? family.getFuture().getScripCode() : "N/A",
                        family.getEquity() != null ? family.getEquity().getScripCode() : "N/A");
            } else {
                log.debug("[ENRICH_DIAG] scripCode extracted | familyId={} | scripCode={} | exchange={} | " +
                        "price={} | hasTech={}",
                        familyId, scripCode, exchange, currentPrice, technicalContext != null);
            }

            EnrichedQuantScore enrichedScore = EnrichedQuantScore.builder()
                    .baseScore(baseScore)
                    // FIX: Include scripCode, companyName, exchange, close, and priceTimestamp
                    // These were previously missing, causing NULL values in TradingSignal
                    .scripCode(scripCode)
                    .companyName(companyName)
                    .exchange(exchange)
                    .close(currentPrice)
                    .priceTimestamp(priceTimestamp)
                    // Session Structure (CRITICAL for context-aware signals)
                    .sessionStructure(sessionStructure)
                    // Family Context (unified equity + futures + options analysis)
                    .familyContext(familyContext)
                    // Swing Analysis (swing highs/lows for structure)
                    .swingAnalysis(swingAnalysis)
                    // Multi-Timeframe Levels (daily/weekly/monthly pivots and Fibonacci)
                    .mtfLevels(mtfLevels)
                    // Phase 1
                    .historicalContext(historicalContext)
                    // Phase 2
                    .gexProfile(gexProfile)
                    .maxPainProfile(maxPainProfile)
                    .timeContext(timeContext)
                    .expiryContext(expiryContext)
                    // Phase 3
                    .technicalContext(technicalContext)
                    .confluenceResult(confluenceResult)
                    .detectedEvents(detectedEvents)
                    // MTF SMC Context (Multi-Timeframe Smart Money Concepts)
                    .mtfSmcContext(mtfSmcContext)
                    // Adjusted scores
                    .adjustedQuantScore(adjustedScore)
                    .adjustedConfidence(adjustedConfidence)
                    // Modifiers
                    .adaptiveModifier(adaptiveModifier)
                    .gexModifier(gexModifier)
                    .maxPainModifier(maxPainModifier)
                    .timeModifier(timeModifier)
                    .expiryModifier(expiryModifier)
                    .technicalModifier(technicalModifier)
                    .confluenceModifier(confluenceModifier)
                    .eventModifier(eventModifier)
                    .combinedModifier(combinedModifier)
                    // Actionable moment
                    .isActionableMoment(isActionableMoment)
                    .actionableMomentReason(getActionableMomentReason(
                            historicalContext, gexProfile, maxPainProfile, expiryContext,
                            technicalContext, confluenceResult, detectedEvents, baseScore))
                    .enrichmentNote(enrichmentNote)
                    .build();

            long totalMs = (System.nanoTime() - startTime) / 1_000_000;

            // Summary logging
            if (isActionableMoment) {
                log.info("[ENRICH] {} COMPLETE | price={} | score={} | conf={}% | " +
                                "actionable={} | events={} | " +
                                "pos={}% | familyBias={} | combinedMod={} | totalMs={}",
                        familyId, String.format("%.2f", currentPrice),
                        String.format("%.0f", adjustedScore),
                        String.format("%.0f", adjustedConfidence * 100),
                        isActionableMoment, detectedEvents.size(),
                        String.format("%.0f", sessionStructure != null ? sessionStructure.getPositionInRange() * 100 : 50),
                        familyContext != null ? familyContext.getOverallBias() : "UNKNOWN",
                        String.format("%.2f", combinedModifier), totalMs);
            } else {
                log.debug("[ENRICH] {} Complete | price={} | score={} | conf={}% | " +
                                "events={} | pos={}% | totalMs={}",
                        familyId, String.format("%.2f", currentPrice),
                        String.format("%.0f", adjustedScore),
                        String.format("%.0f", adjustedConfidence * 100),
                        detectedEvents.size(),
                        String.format("%.0f", sessionStructure != null ? sessionStructure.getPositionInRange() * 100 : 50),
                        totalMs);
            }

            return enrichedScore;

        } catch (Exception e) {
            log.error("Error calculating enriched score for {}: {}",
                    family.getFamilyId(), e.getMessage(), e);

            // Fallback to base calculation
            QuantScore baseScore = baseCalculator.calculate(family);
            return EnrichedQuantScore.builder()
                    .baseScore(baseScore)
                    .adjustedQuantScore(baseScore.getQuantScore())
                    .adjustedConfidence(baseScore.getConfidence() * 0.8) // Reduce confidence on error
                    .adaptiveModifier(1.0)
                    .combinedModifier(1.0)
                    .isActionableMoment(false)
                    .enrichmentNote("Enrichment failed - using base score")
                    .build();
        }
    }

    // ======================== MODIFIER UTILITIES ========================

    /**
     * FIX: Clamp modifier to valid range to prevent NaN/crash from stacking
     * Ensures no modifier is 0, negative, NaN, or Infinity
     */
    private double clampModifier(double modifier) {
        // Handle NaN/Infinity
        if (!Double.isFinite(modifier)) {
            return 1.0;
        }
        // Clamp to valid range [0.5, 2.0] - prevents extreme stacking
        return Math.max(0.5, Math.min(2.0, modifier));
    }

    // ======================== PHASE 2 MODIFIER CALCULATIONS ========================

    /**
     * Calculate GEX-based modifier
     * Adjusts signal confidence based on market regime
     */
    private double calculateGEXModifier(GEXProfile gex, QuantScore baseScore) {
        if (gex == null || gex.getRegime() == null) {
            return 1.0;
        }

        // Determine if this is a momentum or mean-reversion signal
        // High scores with bullish direction = momentum signal
        boolean isMomentumSignal = baseScore.getQuantScore() >= 60;

        double modifier = gex.getSignalModifier(isMomentumSignal);

        // Additional adjustment if near gamma flip
        if (gex.isNearGammaFlip()) {
            modifier *= 0.9; // Reduce confidence near regime change
            log.debug("[GEX] Near gamma flip - reducing confidence");
        }

        log.debug("[GEX] Regime: {}, Momentum: {}, Modifier: {}",
                gex.getRegime(), isMomentumSignal, modifier);

        return modifier;
    }

    /**
     * Calculate Max Pain modifier
     * Strongest effect on expiry day afternoon
     */
    private double calculateMaxPainModifier(MaxPainProfile maxPain, ExpiryContext expiry, QuantScore baseScore) {
        if (maxPain == null || expiry == null || !expiry.shouldApplyMaxPain()) {
            return 1.0;
        }

        // Get signal direction
        boolean isLongSignal = baseScore.getQuantScore() >= 50; // Simplified - would check actual direction

        // Apply max pain modifier
        double modifier = maxPain.getSignalModifier(
                isLongSignal,
                expiry.isExpiryDay(),
                expiry.isExpiryDayAfternoon()
        );

        // Weight by max pain relevance
        double relevance = expiry.getMaxPainRelevance();
        modifier = 1.0 + (modifier - 1.0) * relevance;

        if (maxPain.isInPinZone()) {
            log.debug("[MAXPAIN] In pin zone around {} - reducing directional signals",
                    maxPain.getMaxPainStrike());
        }

        log.debug("[MAXPAIN] Bias: {}, Long: {}, Relevance: {}, Modifier: {}",
                maxPain.getBias(), isLongSignal, relevance, modifier);

        return modifier;
    }

    // ======================== PHASE 3 MODIFIER CALCULATIONS ========================

    /**
     * Calculate technical indicator modifier
     * Based on SuperTrend alignment and Bollinger Band position
     */
    private double calculateTechnicalModifier(TechnicalContext tech, QuantScore baseScore) {
        if (tech == null) {
            return 1.0;
        }

        double modifier = 1.0;
        boolean isLongSignal = baseScore.getQuantScore() >= 50;

        // SuperTrend alignment
        if (tech.isSuperTrendFlip()) {
            // Recent flip - boost confidence if aligned with signal direction
            boolean stBullish = tech.isSuperTrendBullish();
            if ((isLongSignal && stBullish) || (!isLongSignal && !stBullish)) {
                modifier *= 1.15; // Boost for aligned SuperTrend flip
                log.debug("[TECH] SuperTrend flip aligned with signal - boost 1.15");
            } else {
                modifier *= 0.85; // Reduce for opposing SuperTrend flip
                log.debug("[TECH] SuperTrend flip opposes signal - reduce 0.85");
            }
        } else if (tech.getSuperTrendValue() > 0) {
            // No flip, but check alignment
            boolean stBullish = tech.isSuperTrendBullish();
            if ((isLongSignal && stBullish) || (!isLongSignal && !stBullish)) {
                modifier *= 1.05; // Small boost for alignment
            }
        }

        // Bollinger Band position (bbPercentB is 0-1 when valid)
        double percentB = tech.getBbPercentB();
        if (percentB >= 0) {
            if (isLongSignal && percentB <= 0) {
                // Long signal at lower band - good entry
                modifier *= 1.1;
                log.debug("[TECH] Long signal at BB lower - boost 1.1");
            } else if (!isLongSignal && percentB >= 1) {
                // Short signal at upper band - good entry
                modifier *= 1.1;
                log.debug("[TECH] Short signal at BB upper - boost 1.1");
            } else if (isLongSignal && percentB >= 1) {
                // Long signal at upper band - risky
                modifier *= 0.9;
            } else if (!isLongSignal && percentB <= 0) {
                // Short signal at lower band - risky
                modifier *= 0.9;
            }
        }

        // BB squeeze - volatility expansion likely
        if (tech.isBbSqueezing()) {
            modifier *= 1.05; // Slight boost for potential breakout
            log.debug("[TECH] BB squeeze detected - boost 1.05");
        }

        log.debug("[TECH] Technical modifier: {}", modifier);
        return Math.max(0.7, Math.min(1.3, modifier));
    }

    /**
     * Calculate confluence zone modifier
     * Boost signals at confluence, reduce against it
     */
    private double calculateConfluenceModifier(ConfluenceCalculator.ConfluenceResult confluence, QuantScore baseScore) {
        if (confluence == null) {
            return 1.0;
        }

        double modifier = 1.0;
        boolean isLongSignal = baseScore.getQuantScore() >= 50;

        // In confluence zone
        if (confluence.isInConfluenceZone() && confluence.getCurrentZone() != null) {
            ConfluenceCalculator.ConfluenceZone zone = confluence.getCurrentZone();

            // Check if trading with the zone (support for longs, resistance for shorts)
            boolean tradingWithZone =
                    (isLongSignal && zone.getType() == ConfluenceCalculator.ZoneType.SUPPORT) ||
                    (!isLongSignal && zone.getType() == ConfluenceCalculator.ZoneType.RESISTANCE);

            if (tradingWithZone) {
                modifier = zone.getSignalModifier(false); // Trading with zone
                log.debug("[CONFLUENCE] Trading with {} zone - modifier: {}",
                        zone.getType(), modifier);
            } else {
                modifier = zone.getSignalModifier(true); // Trading into zone
                log.debug("[CONFLUENCE] Trading against {} zone - modifier: {}",
                        zone.getType(), modifier);
            }
        } else {
            // Not in zone - use confluence signal modifier
            modifier = confluence.getSignalModifier(isLongSignal);
        }

        log.debug("[CONFLUENCE] Confluence modifier: {}", modifier);
        return Math.max(0.6, Math.min(1.3, modifier));
    }

    /**
     * Calculate event-based modifier
     * Boost for high-confidence aligned events
     */
    private double calculateEventModifier(List<DetectedEvent> events) {
        if (events == null || events.isEmpty()) {
            return 1.0;
        }

        double modifier = 1.0;

        // Count high-confidence events
        long highConfidenceCount = events.stream()
                .filter(e -> e.getStrength() >= 0.75)
                .count();

        // Boost for high-confidence events
        if (highConfidenceCount > 0) {
            modifier *= 1.0 + (highConfidenceCount * 0.05); // 5% boost per high-confidence event
            log.debug("[EVENT] {} high-confidence events - boost modifier: {}", highConfidenceCount, modifier);
        }

        // Check for specific actionable events
        boolean hasFlipEvent = events.stream()
                .anyMatch(e -> e.getEventType() == DetectedEvent.EventType.OFI_FLIP ||
                        e.getEventType() == DetectedEvent.EventType.SUPERTREND_FLIP);
        if (hasFlipEvent) {
            modifier *= 1.1;
            log.debug("[EVENT] Flip event detected - boost modifier: {}", modifier);
        }

        // Check for exhaustion (reversal signal)
        boolean hasExhaustion = events.stream()
                .anyMatch(e -> e.getEventType() == DetectedEvent.EventType.SELLING_EXHAUSTION ||
                        e.getEventType() == DetectedEvent.EventType.BUYING_EXHAUSTION);
        if (hasExhaustion) {
            modifier *= 1.05;
            log.debug("[EVENT] Exhaustion event detected - boost modifier: {}", modifier);
        }

        // Check for absorption (institutional activity)
        boolean hasAbsorption = events.stream()
                .anyMatch(e -> e.getEventType() == DetectedEvent.EventType.ABSORPTION);
        if (hasAbsorption) {
            modifier *= 1.1;
            log.debug("[EVENT] Absorption event detected - boost modifier: {}", modifier);
        }

        log.debug("[EVENT] Final event modifier: {}", modifier);
        return Math.max(0.8, Math.min(1.5, modifier));
    }

    /**
     * Calculate adjusted confidence with Phase 2 context
     */
    private double calculateAdjustedConfidence(double baseConfidence, HistoricalContext historical,
                                                TimeContext time, ExpiryContext expiry) {
        double adjusted = calculateAdjustedConfidence(baseConfidence, historical);

        // Apply time context
        if (time != null && time.shouldAvoidTrading()) {
            adjusted *= 0.5; // Significantly reduce during bad sessions
            log.debug("[TIME] Reducing confidence - {}", time.getLowConfidenceReason());
        }

        // Apply expiry context
        if (expiry != null && expiry.isExpiryDay()) {
            adjusted *= 0.8; // Reduce on expiry day due to unpredictability
        }

        return Math.max(0.1, Math.min(1.0, adjusted));
    }

    /**
     * Apply historical context insights to family (modifies in-place for downstream processors)
     */
    private void applyHistoricalModifiers(FamilyCandle family, HistoricalContext ctx) {
        if (ctx == null || ctx.isInLearningMode()) {
            return;
        }

        // Set directional bias based on historical context
        MetricContext.MetricRegime regime = ctx.getDominantRegime();
        String bias = switch (regime) {
            case STRONG_POSITIVE -> "STRONG_BULLISH";
            case POSITIVE -> "BULLISH";
            case STRONG_NEGATIVE -> "STRONG_BEARISH";
            case NEGATIVE -> "BEARISH";
            case NEUTRAL -> "NEUTRAL";
        };

        if (family.getDirectionalBias() == null || "NEUTRAL".equals(family.getDirectionalBias())) {
            family.setDirectionalBias(bias);
        }

        // Boost bias confidence if regime is consistent
        if (ctx.getOfiContext() != null && ctx.getOfiContext().getConsecutiveCount() >= 3) {
            double currentConf = family.getBiasConfidence();
            family.setBiasConfidence(Math.min(1.0, currentConf + 0.1));
        }
    }

    /**
     * Calculate adaptive modifier based on historical context
     */
    private double calculateAdaptiveModifier(HistoricalContext ctx, FamilyCandle family) {
        if (ctx == null || ctx.isInLearningMode()) {
            return 0.8; // Reduced modifier in learning mode
        }

        double modifier = 1.0;

        // Boost for flip detection
        if (ctx.getTotalFlipsDetected() > 0) {
            modifier *= 1.1;
            log.debug("[ADAPTIVE] Flip detected for {}, boost: 1.1", family.getFamilyId());
        }

        // Boost for absorption (institutional accumulation)
        if (ctx.isAbsorptionDetected()) {
            modifier *= 1.15;
            log.debug("[ADAPTIVE] Absorption detected for {}, boost: 1.15", family.getFamilyId());
        }

        // Boost for informed flow
        if (ctx.isInformedFlowActive()) {
            modifier *= 1.05 + (ctx.getInformedFlowIntensity() * 0.1);
            log.debug("[ADAPTIVE] Informed flow for {}, intensity: {}",
                    family.getFamilyId(), ctx.getInformedFlowIntensity());
        }

        // Reduce for liquidity withdrawal (caution mode)
        if (ctx.isLiquidityWithdrawal()) {
            modifier *= 0.85;
            log.debug("[ADAPTIVE] Liquidity withdrawal for {}, reduce: 0.85", family.getFamilyId());
        }

        // Reduce for exhaustion (reversal risk)
        if (ctx.isSellingExhaustion() || ctx.isBuyingExhaustion()) {
            modifier *= 0.9;
            log.debug("[ADAPTIVE] Exhaustion detected for {}, reduce: 0.9", family.getFamilyId());
        }

        // Apply commodity-specific adjustment
        boolean isCommodity = commodityConfig.isMCXExchange(
                family.getFuture() != null ? family.getFuture().getExchange() : null);
        if (isCommodity) {
            // Commodities get slightly lower base confidence due to lower liquidity
            modifier *= 0.95;
        }

        // Clamp to reasonable range
        return Math.max(0.5, Math.min(1.5, modifier));
    }

    /**
     * Calculate adjusted confidence based on data completeness
     */
    private double calculateAdjustedConfidence(double baseConfidence, HistoricalContext ctx) {
        if (ctx == null) {
            return baseConfidence * 0.5;
        }

        double historicalConfidence = ctx.getHistoricalConfidence();
        double dataCompleteness = ctx.getDataCompleteness();

        // Weight: 60% base confidence, 40% historical confidence
        double adjusted = baseConfidence * 0.6 + historicalConfidence * 0.4;

        // Apply data completeness penalty
        if (dataCompleteness < 0.8) {
            adjusted *= (0.5 + dataCompleteness * 0.5);
        }

        return Math.max(0.1, Math.min(1.0, adjusted));
    }

    /**
     * Determine if this is an actionable moment based on all contexts (Phase 1 + Phase 2 + Phase 3)
     */
    private boolean determineActionableMoment(HistoricalContext ctx, GEXProfile gex,
                                               MaxPainProfile maxPain, ExpiryContext expiry,
                                               TechnicalContext tech,
                                               ConfluenceCalculator.ConfluenceResult confluence,
                                               List<DetectedEvent> events,
                                               QuantScore baseScore) {
        // Phase 1 checks
        if (ctx != null && !ctx.isInLearningMode()) {
            // 1. Significant flip detected
            if (ctx.getTotalFlipsDetected() > 0 && ctx.getSignificantFlipZscore() > 1.5) {
                return true;
            }

            // 2. Absorption detected (institutional activity)
            if (ctx.isAbsorptionDetected()) {
                return true;
            }

            // 3. Momentum building with strong base score
            if (ctx.isMomentumBuilding() && baseScore.getQuantScore() >= 60) {
                return true;
            }

            // 4. Exhaustion at extreme levels
            if ((ctx.isSellingExhaustion() || ctx.isBuyingExhaustion()) &&
                    ctx.getOfiContext() != null &&
                    (ctx.getOfiContext().getPercentile() < 10 || ctx.getOfiContext().getPercentile() > 90)) {
                return true;
            }
        }

        // Phase 2 checks
        // 5. Strong GEX regime with matching signal
        if (gex != null && (gex.getRegime() == GEXProfile.GEXRegime.STRONG_TRENDING ||
                gex.getRegime() == GEXProfile.GEXRegime.STRONG_MEAN_REVERTING)) {
            return true;
        }

        // 6. Max pain actionable on expiry day
        if (maxPain != null && expiry != null && expiry.isExpiryDayAfternoon() && maxPain.isActionable()) {
            return true;
        }

        // Phase 3 checks
        // 7. SuperTrend flip detected
        if (tech != null && tech.isSuperTrendFlip()) {
            return true;
        }

        // 8. At strong confluence zone
        if (confluence != null && confluence.isInConfluenceZone() &&
                confluence.getCurrentZone() != null && confluence.getCurrentZone().isStrong()) {
            return true;
        }

        // 9. High-confidence event detected
        if (events != null && !events.isEmpty()) {
            boolean hasHighConfidenceEvent = events.stream()
                    .anyMatch(e -> e.getStrength() >= 0.75);
            if (hasHighConfidenceEvent) {
                return true;
            }
        }

        return false;
    }

    /**
     * Get reason for actionable moment (Phase 1 + Phase 2 + Phase 3)
     */
    private String getActionableMomentReason(HistoricalContext ctx, GEXProfile gex,
                                              MaxPainProfile maxPain, ExpiryContext expiry,
                                              TechnicalContext tech,
                                              ConfluenceCalculator.ConfluenceResult confluence,
                                              List<DetectedEvent> events,
                                              QuantScore baseScore) {
        StringBuilder reasons = new StringBuilder();

        // Phase 1 reasons
        if (ctx != null && !ctx.isInLearningMode()) {
            if (ctx.getTotalFlipsDetected() > 0 && ctx.getSignificantFlipZscore() > 1.5) {
                reasons.append(String.format("Significant %s flip (z=%.2f). ",
                        ctx.getSignificantFlipMetric(), ctx.getSignificantFlipZscore()));
            }

            if (ctx.isAbsorptionDetected()) {
                reasons.append("Institutional absorption detected. ");
            }

            if (ctx.isMomentumBuilding()) {
                reasons.append(String.format("Momentum building (%s). ", ctx.getMomentumDirection()));
            }

            if (ctx.isSellingExhaustion()) {
                reasons.append("Selling exhaustion at support. ");
            }

            if (ctx.isBuyingExhaustion()) {
                reasons.append("Buying exhaustion at resistance. ");
            }

            if (ctx.isInformedFlowActive()) {
                reasons.append(String.format("Informed flow active (%.0f%%). ",
                        ctx.getInformedFlowIntensity() * 100));
            }
        } else if (ctx != null && ctx.isInLearningMode()) {
            reasons.append("Learning mode - building history. ");
        }

        // Phase 2 reasons
        if (gex != null && gex.getRegime() != null) {
            if (gex.getRegime() == GEXProfile.GEXRegime.STRONG_TRENDING) {
                reasons.append("Strong TRENDING regime - breakouts will run. ");
            } else if (gex.getRegime() == GEXProfile.GEXRegime.STRONG_MEAN_REVERTING) {
                reasons.append("Strong MEAN-REVERTING regime - fade extremes. ");
            }
        }

        if (maxPain != null && expiry != null && expiry.isExpiryDayAfternoon() && maxPain.isActionable()) {
            reasons.append(maxPain.getBiasDescription()).append(" ");
        }

        // Phase 3 reasons
        if (tech != null && tech.isSuperTrendFlip()) {
            String direction = tech.isSuperTrendBullish() ? "BULLISH" : "BEARISH";
            reasons.append(String.format("SuperTrend flipped %s. ", direction));
        }

        if (confluence != null && confluence.isInConfluenceZone() &&
                confluence.getCurrentZone() != null && confluence.getCurrentZone().isStrong()) {
            reasons.append(String.format("At strong confluence zone (%.2f, %d sources). ",
                    confluence.getCurrentZone().getCenterPrice(),
                    confluence.getCurrentZone().getSourceCount()));
        }

        if (events != null && !events.isEmpty()) {
            for (DetectedEvent event : events) {
                if (event.getStrength() >= 0.75) {
                    reasons.append(String.format("%s event detected (%.0f%% confidence). ",
                            event.getEventType().name(), event.getStrength() * 100));
                }
            }
        }

        return reasons.length() > 0 ? reasons.toString().trim() : "No specific actionable moment";
    }

    /**
     * Generate enrichment note summarizing all context
     */
    private String generateEnrichmentNote(HistoricalContext historical, GEXProfile gex,
                                           MaxPainProfile maxPain, TimeContext time,
                                           ExpiryContext expiry, TechnicalContext tech,
                                           ConfluenceCalculator.ConfluenceResult confluence,
                                           List<DetectedEvent> events) {
        StringBuilder note = new StringBuilder();

        // Time context
        if (time != null) {
            note.append(String.format("[Session: %s (%.0f%%)] ",
                    time.getSession(), time.getSessionQuality() * 100));

            if (!time.isGoodTimeToTrade()) {
                note.append("AVOID: ").append(time.getLowConfidenceReason()).append(". ");
            }
        }

        // Expiry context
        if (expiry != null && expiry.getDaysToExpiry() >= 0) {
            note.append(String.format("[DTE: %d - %s] ",
                    expiry.getDaysToExpiry(),
                    expiry.getCategory() != null ? expiry.getCategory().name() : "UNKNOWN"));

            if (expiry.isExpiryDay()) {
                note.append("EXPIRY DAY - reduced confidence. ");
            }
        }

        // GEX regime
        if (gex != null && gex.getRegime() != null) {
            note.append(String.format("[GEX: %s] ", gex.getRegime().name()));

            if (gex.isNearGammaFlip()) {
                note.append("Near gamma flip level. ");
            }
        }

        // Max Pain
        if (maxPain != null && maxPain.getMaxPainStrike() > 0) {
            note.append(String.format("[MaxPain: %.0f (%.1f%% away)] ",
                    maxPain.getMaxPainStrike(), maxPain.getAbsDistancePct()));

            if (maxPain.isInPinZone()) {
                note.append("IN PIN ZONE. ");
            }
        }

        // Historical context
        if (historical != null) {
            if (historical.isInLearningMode()) {
                note.append("[LEARNING MODE - %.0f%% complete] ".formatted(historical.getDataCompleteness() * 100));
            } else {
                note.append(String.format("[Regime: %s] ", historical.getDominantRegime()));

                if (historical.getTotalFlipsDetected() > 0) {
                    note.append("FLIP detected. ");
                }
            }
        }

        // Phase 3: Technical context
        if (tech != null) {
            // SuperTrend
            if (tech.isSuperTrendFlip()) {
                String direction = tech.isSuperTrendBullish() ? "BULLISH" : "BEARISH";
                note.append(String.format("[SuperTrend: %s FLIP] ", direction));
            } else if (tech.getSuperTrendValue() > 0) {
                String direction = tech.isSuperTrendBullish() ? "BULLISH" : "BEARISH";
                note.append(String.format("[SuperTrend: %s] ", direction));
            }

            // Bollinger Bands
            double bbPctB = tech.getBbPercentB();
            if (bbPctB >= 0) {
                if (bbPctB <= 0.05) {
                    note.append("[BB: LOWER TOUCH] ");
                } else if (bbPctB >= 0.95) {
                    note.append("[BB: UPPER TOUCH] ");
                } else if (tech.isBbSqueezing()) {
                    note.append("[BB: SQUEEZE] ");
                }
            }

            // Volatility
            if (tech.isVolatilityExpanding()) {
                note.append("[VOL: EXPANDING] ");
            }
        }

        // Confluence zones
        if (confluence != null) {
            if (confluence.isInConfluenceZone() && confluence.getCurrentZone() != null) {
                note.append(String.format("[IN %s ZONE: %.2f (%d sources)] ",
                        confluence.getCurrentZone().getType(),
                        confluence.getCurrentZone().getCenterPrice(),
                        confluence.getCurrentZone().getSourceCount()));
            } else {
                if (confluence.getNearestSupport() != null) {
                    note.append(String.format("[Support: %.2f (%.1f%%)] ",
                            confluence.getNearestSupportPrice(),
                            confluence.getSupportDistance()));
                }
                if (confluence.getNearestResistance() != null) {
                    note.append(String.format("[Resistance: %.2f (%.1f%%)] ",
                            confluence.getNearestResistancePrice(),
                            confluence.getResistanceDistance()));
                }
            }
        }

        // Events
        if (events != null && !events.isEmpty()) {
            int eventCount = events.size();
            note.append(String.format("[%d EVENT%s: ", eventCount, eventCount > 1 ? "S" : ""));
            note.append(events.stream()
                    .map(e -> e.getEventType().name())
                    .limit(3)
                    .collect(java.util.stream.Collectors.joining(", ")));
            if (events.size() > 3) {
                note.append("...");
            }
            note.append("] ");
        }

        return note.length() > 0 ? note.toString().trim() : "No enrichment context";
    }

    /**
     * Enriched QuantScore wrapper with Phase 1 + Phase 2 + Phase 3 + Phase 4 contexts
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class EnrichedQuantScore {
        // Base score
        private QuantScore baseScore;

        // Instrument context
        private String scripCode;
        private String companyName;
        private String exchange;  // FIX: "N" for NSE, "M" for MCX, "B" for BSE
        private double close;

        /**
         * FIX: Microprice - Orderbook-derived fair value.
         * More accurate than candle close for real-time/forward testing.
         * Use for entry price when available (with fallback to close).
         */
        private Double microprice;

        /**
         * FIX: Timestamp when the close price was captured (from FamilyCandle.windowEndMillis).
         * CRITICAL: This should be used for signal timestamps to ensure price-time correlation.
         * Previously signals used Instant.now() which caused price/timestamp mismatch.
         * Example: Signal shows entryPrice=1980 at 12:14:09, but 1980 was actually the price at 12:14:00
         */
        private long priceTimestamp;

        /**
         * Session Structure - CRITICAL for context-aware signal interpretation.
         *
         * Contains:
         * - positionInRange: 0.0 = at session low, 1.0 = at session high
         * - Opening range high/low
         * - Level test counts
         * - V-bottom/V-top detection
         * - Failed breakout/breakdown counts
         *
         * WHY THIS MATTERS:
         * The same OI signal means OPPOSITE at session extremes:
         * - SHORT_BUILDUP at session LOW = trapped shorts = BULLISH (squeeze fuel)
         * - SHORT_BUILDUP at session HIGH = continuation = BEARISH
         *
         * Without this context, OI signals are BLIND to position in session.
         */
        private SessionStructure sessionStructure;

        /**
         * Family Context - Unified analysis of equity + futures + options.
         *
         * THE POWER OF FAMILY CANDLE:
         * Having all instruments in ONE place allows detection of:
         * - ALIGNMENT: All instruments agreeing (strongest signal)
         * - DIVERGENCE: Options flow contradicting price (reversal signal)
         * - SQUEEZE SETUPS: Shorts trapped at support, longs trapped at resistance
         *
         * Contains:
         * - bullishAlignment / bearishAlignment scores
         * - equitySignal, futuresSignal, optionsSignal
         * - divergences detected
         * - shortSqueezeSetup / longSqueezeSetup flags
         */
        private FamilyContext familyContext;

        /**
         * Swing Analysis - Tracks swing highs/lows for structure analysis.
         *
         * WHY SWINGS MATTER:
         * - Trend structure: HH+HL = uptrend, LH+LL = downtrend
         * - Key levels: Swing points become support/resistance
         * - Pattern entry: Many patterns trigger on swing reversals
         *
         * Contains:
         * - lastSwingHigh / lastSwingLow
         * - trendStructure (UPTREND, DOWNTREND, RANGE, CONVERGING)
         * - swingHighBroken / swingLowBroken flags
         * - recentSwings list
         */
        private SwingAnalysis swingAnalysis;

        /**
         * Multi-Timeframe Levels - Daily/Weekly/Monthly pivot and Fibonacci levels.
         *
         * MTF LEVELS PROVIDE:
         * - Key support/resistance across timeframes
         * - Confluence when multiple TF levels align
         * - Higher TF levels = stronger levels
         *
         * Contains:
         * - dailyPivot, weeklyPivot, monthlyPivot (S1-S4, R1-R4, CPR)
         * - dailyFib, weeklyFib, monthlyFib (0.236, 0.382, 0.5, 0.618, 0.786)
         */
        private MultiTimeframeLevels mtfLevels;

        // Phase 1: Historical Context
        private HistoricalContext historicalContext;

        // Phase 2: Options Intelligence
        private GEXProfile gexProfile;
        private MaxPainProfile maxPainProfile;
        private TimeContext timeContext;
        private ExpiryContext expiryContext;

        // Phase 3: Signal Intelligence
        private TechnicalContext technicalContext;
        private ConfluenceCalculator.ConfluenceResult confluenceResult;
        private List<DetectedEvent> detectedEvents;

        /**
         * MTF SMC Context (Multi-Timeframe Smart Money Concepts)
         *
         * THIS IS REAL INSTITUTIONAL ANALYSIS:
         *
         * HTF (1H/4H) Analysis:
         * - Market Structure bias (bullish/bearish)
         * - Major Order Blocks (where institutions placed orders)
         * - Major FVGs (real imbalances, not 1m noise)
         *
         * LTF (15m) Analysis:
         * - Liquidity sweeps (THE entry signal!)
         * - CHoCH/BOS confirmation
         * - Precise entry at LTF OB
         *
         * Premium/Discount (from Daily range):
         * - Buy in discount (below 50%)
         * - Sell in premium (above 50%)
         *
         * WHY THIS MATTERS:
         * - HTF gives DIRECTION (don't fight the trend)
         * - HTF gives KEY LEVELS (where smart money operates)
         * - LTF gives TIMING (when to pull the trigger)
         */
        private MtfSmcContext mtfSmcContext;

        // Adjusted scores
        private double adjustedQuantScore;
        private double adjustedConfidence;

        // Individual modifiers
        private double adaptiveModifier;      // Phase 1 modifier
        private double gexModifier;           // GEX regime modifier
        private double maxPainModifier;       // Max pain modifier
        private double timeModifier;          // Session quality modifier
        private double expiryModifier;        // DTE modifier
        private double technicalModifier;     // Phase 3: Technical indicator modifier
        private double confluenceModifier;    // Phase 3: Confluence zone modifier
        private double eventModifier;         // Phase 3: Event-based modifier
        private double combinedModifier;      // All modifiers combined

        // Actionable moment
        private boolean isActionableMoment;
        private String actionableMomentReason;

        // Summary note
        private String enrichmentNote;

        public static EnrichedQuantScore empty() {
            return EnrichedQuantScore.builder()
                    .adjustedQuantScore(0)
                    .adjustedConfidence(0)
                    .adaptiveModifier(1.0)
                    .gexModifier(1.0)
                    .maxPainModifier(1.0)
                    .timeModifier(1.0)
                    .expiryModifier(1.0)
                    .technicalModifier(1.0)
                    .confluenceModifier(1.0)
                    .eventModifier(1.0)
                    .combinedModifier(1.0)
                    .isActionableMoment(false)
                    .detectedEvents(Collections.emptyList())
                    .build();
        }

        /**
         * Get final score to use for signal generation
         */
        public double getFinalScore() {
            return adjustedQuantScore;
        }

        /**
         * Get final confidence
         */
        public double getFinalConfidence() {
            return adjustedConfidence;
        }

        /**
         * Check if historical context is available
         */
        public boolean hasHistoricalContext() {
            return historicalContext != null && !historicalContext.isInLearningMode();
        }

        /**
         * Check if options intelligence is available
         */
        public boolean hasOptionsIntelligence() {
            return gexProfile != null || maxPainProfile != null;
        }

        /**
         * Get GEX regime if available
         */
        public GEXProfile.GEXRegime getGexRegime() {
            return gexProfile != null ? gexProfile.getRegime() : null;
        }

        /**
         * Get max pain bias if available
         */
        public MaxPainProfile.MaxPainBias getMaxPainBias() {
            return maxPainProfile != null ? maxPainProfile.getBias() : null;
        }

        /**
         * Check if currently in good trading session
         */
        public boolean isGoodSession() {
            return timeContext != null && timeContext.isGoodTimeToTrade();
        }

        /**
         * Check if expiry day
         */
        public boolean isExpiryDay() {
            return expiryContext != null && expiryContext.isExpiryDay();
        }

        /**
         * Get days to expiry
         */
        public int getDaysToExpiry() {
            return expiryContext != null ? expiryContext.getDaysToExpiry() : -1;
        }

        // ======================== PHASE 3 HELPERS ========================

        /**
         * Check if technical context is available
         */
        public boolean hasTechnicalContext() {
            return technicalContext != null;
        }

        /**
         * Check if SuperTrend is bullish
         */
        public boolean isSuperTrendBullish() {
            return technicalContext != null && technicalContext.isSuperTrendBullish();
        }

        /**
         * Check if SuperTrend just flipped
         */
        public boolean hasSuperTrendFlip() {
            return technicalContext != null && technicalContext.isSuperTrendFlip();
        }

        /**
         * Check if price is in a confluence zone
         */
        public boolean isInConfluenceZone() {
            return confluenceResult != null && confluenceResult.isInConfluenceZone();
        }

        /**
         * Check if at support
         */
        public boolean isAtSupport() {
            return confluenceResult != null && confluenceResult.isAtSupport();
        }

        /**
         * Check if at resistance
         */
        public boolean isAtResistance() {
            return confluenceResult != null && confluenceResult.isAtResistance();
        }

        /**
         * Get nearest support price
         */
        public Double getNearestSupportPrice() {
            return confluenceResult != null ? confluenceResult.getNearestSupportPrice() : null;
        }

        /**
         * Get nearest resistance price
         */
        public Double getNearestResistancePrice() {
            return confluenceResult != null ? confluenceResult.getNearestResistancePrice() : null;
        }

        /**
         * Check if any events were detected
         */
        public boolean hasEvents() {
            return detectedEvents != null && !detectedEvents.isEmpty();
        }

        /**
         * Get count of detected events
         */
        public int getEventCount() {
            return detectedEvents != null ? detectedEvents.size() : 0;
        }

        /**
         * Get high-confidence events (confidence >= 0.75)
         */
        public List<DetectedEvent> getHighConfidenceEvents() {
            if (detectedEvents == null) return java.util.Collections.emptyList();
            return detectedEvents.stream()
                    .filter(e -> e.getStrength() >= 0.75)
                    .collect(java.util.stream.Collectors.toList());
        }

        /**
         * Check if signal intelligence (Phase 3) is fully available
         */
        public boolean hasSignalIntelligence() {
            return technicalContext != null || confluenceResult != null ||
                    (detectedEvents != null && !detectedEvents.isEmpty());
        }

        /**
         * Get Bollinger Band %B if available
         */
        public Double getBbPercentB() {
            return technicalContext != null ? technicalContext.getBbPercentB() : null;
        }

        /**
         * Check if BB is squeezing (low volatility)
         */
        public boolean isBbSqueezing() {
            return technicalContext != null && technicalContext.isBbSqueezing();
        }

        /**
         * Check if volatility is expanding
         */
        public boolean isVolatilityExpanding() {
            return technicalContext != null && technicalContext.isVolatilityExpanding();
        }

        /**
         * Get ATR percentage if available
         */
        public Double getAtrPct() {
            return technicalContext != null ? technicalContext.getAtrPct() : null;
        }

        /**
         * Get confluence score from zones
         */
        public double getConfluenceScore() {
            if (confluenceResult == null) return 0;
            return confluenceResult.isInConfluenceZone() ? 0.8 : 0.4;
        }

        /**
         * Get confluence zones
         */
        public List<ConfluenceZone> getConfluenceZones() {
            if (confluenceResult == null || confluenceResult.getZones() == null) {
                return Collections.emptyList();
            }
            return confluenceResult.getZones().stream()
                    .map(z -> new ConfluenceZone(z.getCenterPrice(), z.getType().name(), z.getStrength()))
                    .collect(java.util.stream.Collectors.toList());
        }

        /**
         * Simple confluence zone representation
         */
        @lombok.Data
        @lombok.AllArgsConstructor
        public static class ConfluenceZone {
            private double centerPrice;
            private String type;
            private double strength;
        }

        // ======================== SESSION STRUCTURE HELPERS ========================

        /**
         * Check if session structure is available
         */
        public boolean hasSessionStructure() {
            return sessionStructure != null;
        }

        /**
         * Get position in session range (0.0 = at low, 1.0 = at high)
         * CRITICAL for context-aware OI interpretation
         */
        public double getPositionInRange() {
            return sessionStructure != null ? sessionStructure.getPositionInRange() : 0.5;
        }

        /**
         * Check if price is at session extreme (top/bottom 10%)
         * IMPORTANT: Signal interpretation should be FLIPPED at extremes
         */
        public boolean isAtSessionExtreme() {
            return sessionStructure != null && sessionStructure.isAtSessionExtreme();
        }

        /**
         * Check if at session low zone (bottom 15%)
         */
        public boolean isAtSessionLow() {
            return sessionStructure != null && sessionStructure.isAtSessionLow();
        }

        /**
         * Check if at session high zone (top 15%)
         */
        public boolean isAtSessionHigh() {
            return sessionStructure != null && sessionStructure.isAtSessionHigh();
        }

        /**
         * Should OI interpretation be flipped based on session position?
         * At session low: SHORT_BUILDUP = trapped shorts = BULLISH
         * At session high: LONG_BUILDUP = trapped longs = BEARISH
         */
        public boolean shouldFlipOIInterpretation() {
            return sessionStructure != null && sessionStructure.shouldFlipOIInterpretation();
        }

        /**
         * Get position bias for signal modification
         * Returns: 1.0 for bullish context (at low), -1.0 for bearish (at high), 0 for neutral
         */
        public double getPositionBias() {
            return sessionStructure != null ? sessionStructure.getPositionBias() : 0.0;
        }

        /**
         * Get structure modifier for signal confidence
         */
        public double getStructureModifier(boolean isLongSignal) {
            return sessionStructure != null ? sessionStructure.getStructureModifier(isLongSignal) : 1.0;
        }

        /**
         * Check if V-bottom pattern detected at session low
         */
        public boolean hasVBottomDetected() {
            return sessionStructure != null && sessionStructure.isVBottomDetected();
        }

        /**
         * Check if V-top pattern detected at session high
         */
        public boolean hasVTopDetected() {
            return sessionStructure != null && sessionStructure.isVTopDetected();
        }

        /**
         * Get number of failed breakouts at session high
         */
        public int getFailedBreakoutCount() {
            return sessionStructure != null ? sessionStructure.getFailedBreakoutCount() : 0;
        }

        /**
         * Get number of failed breakdowns at session low
         */
        public int getFailedBreakdownCount() {
            return sessionStructure != null ? sessionStructure.getFailedBreakdownCount() : 0;
        }

        /**
         * Check if opening range is established
         */
        public boolean isOpeningRangeEstablished() {
            return sessionStructure != null && sessionStructure.isOpeningRangeEstablished();
        }

        /**
         * Get opening range high
         */
        public Double getOpeningRangeHigh() {
            return sessionStructure != null ? sessionStructure.getOpeningRangeHigh() : null;
        }

        /**
         * Get opening range low
         */
        public Double getOpeningRangeLow() {
            return sessionStructure != null ? sessionStructure.getOpeningRangeLow() : null;
        }

        /**
         * Get session position description for logging
         */
        public String getSessionPositionDescription() {
            return sessionStructure != null ? sessionStructure.getPositionDescription() : "UNKNOWN";
        }

        // ======================== FAMILY CONTEXT HELPERS ========================

        /**
         * Check if family context is available
         */
        public boolean hasFamilyContext() {
            return familyContext != null;
        }

        /**
         * Get family bullish alignment score (0.0 - 1.0)
         * Score > 0.6 = aligned, > 0.8 = fully aligned
         */
        public double getFamilyBullishAlignment() {
            return familyContext != null ? familyContext.getBullishAlignment() : 0;
        }

        /**
         * Get family bearish alignment score (0.0 - 1.0)
         * Score > 0.6 = aligned, > 0.8 = fully aligned
         */
        public double getFamilyBearishAlignment() {
            return familyContext != null ? familyContext.getBearishAlignment() : 0;
        }

        /**
         * Check if all family instruments are bullish aligned (equity + futures + options)
         */
        public boolean isFamilyFullyBullish() {
            return familyContext != null && familyContext.isFullyAlignedBullish();
        }

        /**
         * Check if all family instruments are bearish aligned
         */
        public boolean isFamilyFullyBearish() {
            return familyContext != null && familyContext.isFullyAlignedBearish();
        }

        /**
         * Check if there's a divergence between price and options flow
         * Divergence = potential reversal signal
         */
        public boolean hasFamilyDivergence() {
            return familyContext != null && familyContext.isHasDivergence();
        }

        /**
         * Get divergence descriptions
         */
        public List<String> getFamilyDivergences() {
            return familyContext != null && familyContext.getDivergences() != null ?
                    familyContext.getDivergences() : Collections.emptyList();
        }

        /**
         * Check if short squeeze setup detected
         * (shorts building at session low with high PCR)
         */
        public boolean hasShortSqueezeSetup() {
            return familyContext != null && familyContext.isShortSqueezeSetup();
        }

        /**
         * Check if long squeeze setup detected
         * (longs building at session high with low PCR)
         */
        public boolean hasLongSqueezeSetup() {
            return familyContext != null && familyContext.isLongSqueezeSetup();
        }

        /**
         * Get overall family bias
         */
        public FamilyContextAnalyzer.FamilyBias getFamilyBias() {
            return familyContext != null ? familyContext.getOverallBias() : null;
        }

        /**
         * Get context interpretation (human-readable explanation)
         */
        public String getFamilyContextInterpretation() {
            return familyContext != null ? familyContext.getContextInterpretation() : null;
        }

        /**
         * Get confidence from family analysis
         */
        public double getFamilyConfidence() {
            return familyContext != null ? familyContext.getConfidence() : 0.5;
        }

        // ======================== SWING ANALYSIS HELPERS ========================

        /**
         * Check if swing analysis is available
         */
        public boolean hasSwingAnalysis() {
            return swingAnalysis != null && swingAnalysis.hasSwings();
        }

        /**
         * Get swing-based resistance level
         */
        public Double getSwingResistance() {
            return swingAnalysis != null ? swingAnalysis.getSwingResistance() : null;
        }

        /**
         * Get swing-based support level
         */
        public Double getSwingSupport() {
            return swingAnalysis != null ? swingAnalysis.getSwingSupport() : null;
        }

        /**
         * Check if in uptrend (higher highs + higher lows)
         */
        public boolean isSwingUptrend() {
            return swingAnalysis != null && swingAnalysis.isUptrend();
        }

        /**
         * Check if in downtrend (lower highs + lower lows)
         */
        public boolean isSwingDowntrend() {
            return swingAnalysis != null && swingAnalysis.isDowntrend();
        }

        /**
         * Check if consolidating (converging or range)
         */
        public boolean isSwingConsolidating() {
            return swingAnalysis != null && swingAnalysis.isConsolidating();
        }

        /**
         * Check if swing high was broken (bullish breakout)
         */
        public boolean isSwingHighBroken() {
            return swingAnalysis != null && swingAnalysis.isSwingHighBroken();
        }

        /**
         * Check if swing low was broken (bearish breakdown)
         */
        public boolean isSwingLowBroken() {
            return swingAnalysis != null && swingAnalysis.isSwingLowBroken();
        }

        /**
         * Get trend structure description
         */
        public String getSwingTrendStructure() {
            if (swingAnalysis == null) return "UNKNOWN";
            return swingAnalysis.getTrendStructure().name();
        }

        // ======================== MTF LEVELS HELPERS ========================

        /**
         * Check if MTF levels are available
         */
        public boolean hasMtfLevels() {
            return mtfLevels != null;
        }

        /**
         * Get nearest MTF support level across all timeframes
         */
        public Double getMtfNearestSupport() {
            if (mtfLevels == null || close <= 0) return null;
            double support = mtfLevels.getNearestSupport(close);
            return support > 0 ? support : null;
        }

        /**
         * Get nearest MTF resistance level across all timeframes
         */
        public Double getMtfNearestResistance() {
            if (mtfLevels == null || close <= 0) return null;
            double resistance = mtfLevels.getNearestResistance(close);
            return resistance > 0 ? resistance : null;
        }

        /**
         * Check if price is near a significant MTF level (within 0.5%)
         */
        public boolean isNearMtfLevel() {
            return mtfLevels != null && close > 0 && mtfLevels.isNearSignificantLevel(close);
        }

        /**
         * Get daily pivot level
         */
        public Double getDailyPivot() {
            if (mtfLevels == null || mtfLevels.getDailyPivot() == null) return null;
            return mtfLevels.getDailyPivot().getPivot();
        }

        /**
         * Get weekly pivot level
         */
        public Double getWeeklyPivot() {
            if (mtfLevels == null || mtfLevels.getWeeklyPivot() == null) return null;
            return mtfLevels.getWeeklyPivot().getPivot();
        }

        /**
         * Get monthly pivot level
         */
        public Double getMonthlyPivot() {
            if (mtfLevels == null || mtfLevels.getMonthlyPivot() == null) return null;
            return mtfLevels.getMonthlyPivot().getPivot();
        }

        /**
         * Get daily Fibonacci 61.8% level (golden ratio)
         */
        public Double getDailyFib618() {
            if (mtfLevels == null || mtfLevels.getDailyFib() == null) return null;
            return mtfLevels.getDailyFib().getFib618();
        }

        /**
         * Check if CPR (Central Pivot Range) is narrow (expect breakout)
         */
        public boolean isDailyCprNarrow() {
            if (mtfLevels == null || mtfLevels.getDailyPivot() == null) return false;
            return mtfLevels.getDailyPivot().getCprType() ==
                   MultiTimeframeLevels.PivotLevels.CPRWidth.NARROW;
        }

        /**
         * Get distance to nearest MTF support as percentage
         */
        public Double getMtfSupportDistancePct() {
            Double support = getMtfNearestSupport();
            if (support == null || close <= 0) return null;
            return (close - support) / close * 100;
        }

        /**
         * Get distance to nearest MTF resistance as percentage
         */
        public Double getMtfResistanceDistancePct() {
            Double resistance = getMtfNearestResistance();
            if (resistance == null || close <= 0) return null;
            return (resistance - close) / close * 100;
        }
    }
}
