package com.kotsin.consumer.options.service;

import com.kotsin.consumer.model.OIMetrics;
import com.kotsin.consumer.options.model.OptionGreeks.MoneynessType;
import com.kotsin.consumer.options.service.OptionChainService.OptionChain;
import com.kotsin.consumer.options.service.OptionChainService.StrikeEntry;
import com.kotsin.consumer.service.ScripMetadataService;
import com.kotsin.consumer.signal.model.TradingSignal;
import com.kotsin.consumer.signal.model.FudkiiScore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OptionStrikeSelector - Selects optimal option strikes for trading signals.
 *
 * Selection Logic for Option Buying:
 * 1. BULLISH signal → Buy CE (Call)
 *    - Select OTM CE with delta 0.40-0.50 for leverage
 *    - Or ATM CE with delta ~0.50 for directional
 *
 * 2. BEARISH signal → Buy PE (Put)
 *    - Select OTM PE with delta -0.40 to -0.50
 *    - Or ATM PE for directional
 *
 * Strike Selection Criteria:
 * - Moneyness: ATM or 1-2 strikes OTM
 * - OI buildup: Prefer strikes with LONG_BUILDUP interpretation
 * - Liquidity: Prefer strikes with higher OI (better liquidity)
 * - Premium: Avoid deep OTM (low delta, high theta decay)
 */
@Service
@Slf4j
public class OptionStrikeSelector {

    private static final String LOG_PREFIX = "[STRIKE-SELECTOR]";

    @Autowired
    private OptionChainService optionChainService;

    @Autowired
    private ScripMetadataService scripMetadataService;

    @Value("${option.selector.max.otm.strikes:3}")
    private int maxOTMStrikes;

    @Value("${option.selector.min.oi:1000}")
    private long minOI;

    @Value("${option.selector.prefer.buildup:true}")
    private boolean preferBuildup;

    /**
     * Select optimal option for a trading signal.
     *
     * @param signal The triggered trading signal
     * @return OptionRecommendation with selected strike details
     */
    public OptionRecommendation selectOption(TradingSignal signal) {
        if (signal == null || signal.getDirection() == null) {
            return OptionRecommendation.noRecommendation("No signal direction");
        }

        String underlying = extractUnderlying(signal.getSymbol(), signal.getCompanyName());
        boolean isBullish = signal.getDirection() == FudkiiScore.Direction.BULLISH;

        log.info("{} Selecting option for {} {} at {}",
            LOG_PREFIX, underlying, isBullish ? "BULLISH" : "BEARISH",
            String.format("%.2f", signal.getCurrentPrice()));

        // Build option chain
        OptionChain chain = optionChainService.buildChain(underlying);
        if (chain.isEmpty()) {
            return OptionRecommendation.noRecommendation("No option chain available for " + underlying);
        }

        // Select strike based on direction
        if (isBullish) {
            return selectCallOption(chain, signal);
        } else {
            return selectPutOption(chain, signal);
        }
    }

    /**
     * Select CE option for bullish signal.
     */
    private OptionRecommendation selectCallOption(OptionChain chain, TradingSignal signal) {
        double spotPrice = chain.getSpotPrice();
        List<StrikeEntry> candidates = chain.getStrikes().stream()
            // For calls: OTM means strike > spot
            .filter(s -> s.getStrike() >= spotPrice)
            // Limit to ATM + few OTM strikes
            .filter(s -> s.getDistanceFromSpot() <= maxOTMStrikes * getStrikeInterval(chain.getUnderlyingSymbol()) / spotPrice * 100)
            // Must have call scripCode
            .filter(s -> s.getCallScripCode() != null)
            // Prefer strikes with OI
            .filter(s -> s.getCallOI() >= minOI || !preferBuildup)
            .sorted(Comparator.comparingDouble(StrikeEntry::getStrike))
            .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            // Fallback: get ATM or nearest OTM
            candidates = chain.getStrikes().stream()
                .filter(s -> s.getStrike() >= spotPrice && s.getCallScripCode() != null)
                .sorted(Comparator.comparingDouble(s -> Math.abs(s.getStrike() - spotPrice)))
                .limit(3)
                .collect(Collectors.toList());
        }

        if (candidates.isEmpty()) {
            return OptionRecommendation.noRecommendation("No suitable call strikes found");
        }

        // Score candidates
        StrikeEntry selected = scoreAndSelectBest(candidates, true, spotPrice);

        // Calculate estimated delta (simplified)
        double estimatedDelta = estimateDelta(selected.getStrike(), spotPrice, true);

        // Build recommendation
        return OptionRecommendation.builder()
            .hasRecommendation(true)
            .underlying(chain.getUnderlyingSymbol())
            .scripCode(selected.getCallScripCode())
            .strike(selected.getStrike())
            .optionType("CE")
            .direction("BUY")
            .spotPrice(spotPrice)
            .moneyness(selected.getMoneyness())
            .distanceFromSpot(selected.getDistanceFromSpot())
            .openInterest(selected.getCallOI())
            .oiChange(selected.getCallOIChange())
            .oiInterpretation(selected.getCallInterpretation())
            .estimatedDelta(estimatedDelta)
            .expiry(selected.getExpiry())
            .signalEntry(signal.getEntryPrice())
            .signalStop(signal.getStopLoss())
            .signalTarget(signal.getTarget1())
            .reason(buildReason(selected, true, spotPrice))
            .confidence(calculateConfidence(selected, true, chain))
            .timestamp(Instant.now())
            .build();
    }

    /**
     * Select PE option for bearish signal.
     */
    private OptionRecommendation selectPutOption(OptionChain chain, TradingSignal signal) {
        double spotPrice = chain.getSpotPrice();
        List<StrikeEntry> candidates = chain.getStrikes().stream()
            // For puts: OTM means strike < spot
            .filter(s -> s.getStrike() <= spotPrice)
            // Limit to ATM + few OTM strikes
            .filter(s -> Math.abs(s.getDistanceFromSpot()) <= maxOTMStrikes * getStrikeInterval(chain.getUnderlyingSymbol()) / spotPrice * 100)
            // Must have put scripCode
            .filter(s -> s.getPutScripCode() != null)
            // Prefer strikes with OI
            .filter(s -> s.getPutOI() >= minOI || !preferBuildup)
            .sorted(Comparator.comparingDouble(s -> -s.getStrike()))  // Descending (closest to ATM first)
            .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            // Fallback: get ATM or nearest OTM
            candidates = chain.getStrikes().stream()
                .filter(s -> s.getStrike() <= spotPrice && s.getPutScripCode() != null)
                .sorted(Comparator.comparingDouble(s -> Math.abs(s.getStrike() - spotPrice)))
                .limit(3)
                .collect(Collectors.toList());
        }

        if (candidates.isEmpty()) {
            return OptionRecommendation.noRecommendation("No suitable put strikes found");
        }

        // Score candidates
        StrikeEntry selected = scoreAndSelectBest(candidates, false, spotPrice);

        // Calculate estimated delta (simplified)
        double estimatedDelta = estimateDelta(selected.getStrike(), spotPrice, false);

        // Build recommendation
        return OptionRecommendation.builder()
            .hasRecommendation(true)
            .underlying(chain.getUnderlyingSymbol())
            .scripCode(selected.getPutScripCode())
            .strike(selected.getStrike())
            .optionType("PE")
            .direction("BUY")
            .spotPrice(spotPrice)
            .moneyness(selected.getMoneyness())
            .distanceFromSpot(selected.getDistanceFromSpot())
            .openInterest(selected.getPutOI())
            .oiChange(selected.getPutOIChange())
            .oiInterpretation(selected.getPutInterpretation())
            .estimatedDelta(estimatedDelta)
            .expiry(selected.getExpiry())
            .signalEntry(signal.getEntryPrice())
            .signalStop(signal.getStopLoss())
            .signalTarget(signal.getTarget1())
            .reason(buildReason(selected, false, spotPrice))
            .confidence(calculateConfidence(selected, false, chain))
            .timestamp(Instant.now())
            .build();
    }

    /**
     * Score and select best strike from candidates.
     */
    private StrikeEntry scoreAndSelectBest(List<StrikeEntry> candidates, boolean isCall, double spotPrice) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        double bestScore = -1;
        StrikeEntry bestStrike = candidates.get(0);

        for (StrikeEntry candidate : candidates) {
            double score = 0;

            // Distance score: prefer ATM (max 30 points)
            double distance = Math.abs(candidate.getDistanceFromSpot());
            score += Math.max(0, 30 - distance * 10);

            // OI score: higher OI = better liquidity (max 30 points)
            long oi = isCall ? candidate.getCallOI() : candidate.getPutOI();
            score += Math.min(30, oi / 10000.0);

            // OI interpretation score (max 20 points)
            OIMetrics.OIInterpretation interp = isCall ?
                candidate.getCallInterpretation() : candidate.getPutInterpretation();

            if (interp != null) {
                if (isCall) {
                    // For calls: LONG_BUILDUP is bullish
                    if (interp == OIMetrics.OIInterpretation.LONG_BUILDUP) score += 20;
                    else if (interp == OIMetrics.OIInterpretation.SHORT_COVERING) score += 15;
                } else {
                    // For puts: LONG_BUILDUP means puts being bought (bearish)
                    if (interp == OIMetrics.OIInterpretation.LONG_BUILDUP) score += 20;
                    else if (interp == OIMetrics.OIInterpretation.SHORT_COVERING) score += 15;
                }
            }

            // OI change score: positive change means activity (max 20 points)
            long oiChange = isCall ? candidate.getCallOIChange() : candidate.getPutOIChange();
            if (oiChange > 0) {
                score += Math.min(20, oiChange / 1000.0);
            }

            log.debug("{} Strike {} score: {} (distance={}, OI={}, interp={})",
                LOG_PREFIX, candidate.getStrike(), String.format("%.1f", score),
                String.format("%.2f", distance), oi, interp);

            if (score > bestScore) {
                bestScore = score;
                bestStrike = candidate;
            }
        }

        return bestStrike;
    }

    /**
     * Estimate delta based on moneyness (simplified Black-Scholes approximation).
     */
    private double estimateDelta(double strike, double spot, boolean isCall) {
        double moneyness = (strike - spot) / spot;

        if (isCall) {
            // Call delta: 0.5 at ATM, decreases as OTM increases
            if (Math.abs(moneyness) < 0.01) return 0.50;  // ATM
            if (moneyness > 0) {
                // OTM call
                return Math.max(0.10, 0.50 - moneyness * 5);
            } else {
                // ITM call
                return Math.min(0.90, 0.50 - moneyness * 5);
            }
        } else {
            // Put delta: -0.5 at ATM, becomes more negative as ITM
            if (Math.abs(moneyness) < 0.01) return -0.50;  // ATM
            if (moneyness < 0) {
                // OTM put (strike < spot)
                return Math.max(-0.90, -0.50 + moneyness * 5);
            } else {
                // ITM put (strike > spot)
                return Math.min(-0.10, -0.50 + moneyness * 5);
            }
        }
    }

    /**
     * Get strike interval for underlying.
     */
    private double getStrikeInterval(String underlying) {
        if (underlying == null) return 50;

        switch (underlying.toUpperCase()) {
            case "NIFTY":
                return 50;
            case "BANKNIFTY":
                return 100;
            case "FINNIFTY":
                return 50;
            default:
                return 50;  // Default for stocks
        }
    }

    /**
     * Extract underlying symbol from signal using ScripMetadataService.
     * Falls back to symbol parsing if scripCode not available.
     */
    private String extractUnderlying(String symbol, String companyName) {
        // Use ScripMetadataService for authoritative symbol resolution
        return scripMetadataService.getSymbolRoot(symbol, companyName);
    }

    /**
     * Build reason string.
     */
    private String buildReason(StrikeEntry strike, boolean isCall, double spotPrice) {
        StringBuilder reason = new StringBuilder();
        reason.append(isCall ? "CE " : "PE ");
        reason.append(String.format("%.0f", strike.getStrike()));
        reason.append(" selected: ");

        // Moneyness
        reason.append(strike.getMoneyness().name());

        // OI
        long oi = isCall ? strike.getCallOI() : strike.getPutOI();
        reason.append(String.format(", OI=%d", oi));

        // Interpretation
        OIMetrics.OIInterpretation interp = isCall ?
            strike.getCallInterpretation() : strike.getPutInterpretation();
        if (interp != null) {
            reason.append(", ").append(interp.name());
        }

        return reason.toString();
    }

    /**
     * Calculate recommendation confidence.
     */
    private double calculateConfidence(StrikeEntry strike, boolean isCall, OptionChain chain) {
        double confidence = 0.5;  // Base

        // OI factor
        long oi = isCall ? strike.getCallOI() : strike.getPutOI();
        if (oi > 50000) confidence += 0.15;
        else if (oi > 10000) confidence += 0.10;
        else if (oi > 1000) confidence += 0.05;

        // Interpretation factor
        OIMetrics.OIInterpretation interp = isCall ?
            strike.getCallInterpretation() : strike.getPutInterpretation();
        if (interp == OIMetrics.OIInterpretation.LONG_BUILDUP) confidence += 0.15;
        else if (interp == OIMetrics.OIInterpretation.SHORT_COVERING) confidence += 0.10;

        // Moneyness factor
        if (strike.getMoneyness() == MoneynessType.ATM) confidence += 0.10;
        else if (strike.getMoneyness() == MoneynessType.OTM) confidence += 0.05;

        // PCR alignment
        if (isCall && chain.getPcrByOI() > 1.0) confidence += 0.05;  // High PCR = bullish
        if (!isCall && chain.getPcrByOI() < 1.0) confidence += 0.05;  // Low PCR = bearish

        return Math.min(1.0, confidence);
    }

    // ==================== DATA CLASSES ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionRecommendation {
        private boolean hasRecommendation;
        private String underlying;
        private String scripCode;
        private double strike;
        private String optionType;      // "CE" or "PE"
        private String direction;       // "BUY" or "SELL"
        private double spotPrice;
        private MoneynessType moneyness;
        private double distanceFromSpot;
        private long openInterest;
        private long oiChange;
        private OIMetrics.OIInterpretation oiInterpretation;
        private double estimatedDelta;
        private String expiry;
        private double signalEntry;
        private double signalStop;
        private double signalTarget;
        private String reason;
        private double confidence;
        private Instant timestamp;
        private String noRecommendationReason;

        public static OptionRecommendation noRecommendation(String reason) {
            return OptionRecommendation.builder()
                .hasRecommendation(false)
                .noRecommendationReason(reason)
                .timestamp(Instant.now())
                .build();
        }

        public boolean isCall() {
            return "CE".equals(optionType);
        }

        public boolean isPut() {
            return "PE".equals(optionType);
        }

        public boolean isBuy() {
            return "BUY".equals(direction);
        }
    }
}
