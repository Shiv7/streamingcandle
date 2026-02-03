package com.kotsin.consumer.options.calculator;

import com.kotsin.consumer.options.model.OptionGreeks;
import com.kotsin.consumer.options.model.OptionGreeks.OptionType;
import com.kotsin.consumer.options.model.OptionsAnalytics;
import com.kotsin.consumer.options.service.OptionChainService;
import com.kotsin.consumer.options.service.OptionChainService.OptionChain;
import com.kotsin.consumer.options.service.OptionChainService.StrikeEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OptionsAnalyticsCalculator - Calculates chain-level options analytics.
 *
 * Provides:
 * - Max Pain calculation
 * - GEX (Gamma Exposure) profile
 * - Put-Call Ratio (PCR)
 * - IV Surface analysis
 * - Strike-wise OI analysis
 */
@Component
@Slf4j
public class OptionsAnalyticsCalculator {

    private static final String LOG_PREFIX = "[OPTIONS-ANALYTICS]";

    @Autowired
    private BlackScholesCalculator blackScholesCalculator;

    @Autowired
    private OptionChainService optionChainService;

    /**
     * Calculate complete options analytics using real OI data from OptionChainService.
     *
     * @param underlyingSymbol  The underlying symbol (e.g., "NIFTY", "BANKNIFTY")
     * @param options           List of OptionGreeks for the chain
     * @param spotPrice         Current underlying price
     * @return OptionsAnalytics with all metrics including real OI-based calculations
     */
    public OptionsAnalytics calculateWithRealOI(String underlyingSymbol, List<OptionGreeks> options, double spotPrice) {
        // Fetch real OI data from OptionChainService
        OptionChain chain = optionChainService.buildChain(underlyingSymbol);

        if (chain.isEmpty()) {
            log.warn("{} No option chain data for {}, falling back to price-based proxies",
                LOG_PREFIX, underlyingSymbol);
            return calculate(options, spotPrice);
        }

        log.debug("{} Using real OI data for {}: {} strikes, PCR={}",
            LOG_PREFIX, underlyingSymbol, chain.getStrikeCount(),
            String.format("%.2f", chain.getPcrByOI()));

        return calculateWithChain(options, spotPrice, chain);
    }

    /**
     * Calculate analytics with pre-built option chain.
     */
    public OptionsAnalytics calculateWithChain(List<OptionGreeks> options, double spotPrice, OptionChain chain) {
        if (options == null || options.isEmpty()) {
            return OptionsAnalytics.builder()
                .timestamp(Instant.now())
                .build();
        }

        // Separate calls and puts
        List<OptionGreeks> calls = options.stream()
            .filter(o -> o.getOptionType() == OptionType.CALL)
            .collect(Collectors.toList());
        List<OptionGreeks> puts = options.stream()
            .filter(o -> o.getOptionType() == OptionType.PUT)
            .collect(Collectors.toList());

        OptionsAnalytics.OptionsAnalyticsBuilder builder = OptionsAnalytics.builder()
            .underlyingSymbol(options.get(0).getUnderlyingSymbol())
            .spotPrice(spotPrice)
            .timestamp(Instant.now());

        // Max Pain from real OI data
        builder.maxPain(chain.getMaxPain())
               .maxPainDistance(spotPrice > 0 ? (chain.getMaxPain() - spotPrice) / spotPrice : 0)
               .totalPainAtMaxPain(0);  // Already calculated in chain

        // PCR from real OI data
        builder.pcrByOI(chain.getPcrByOI())
               .pcrByVolume(chain.getPcrByOI())  // Use same as OI for now
               .pcrByPremium(calculatePCRByPremium(calls, puts))
               .pcrSignal(chain.getPCRSignal());

        // GEX with real OI
        GEXResult gex = calculateGEXWithOI(options, spotPrice, chain);
        builder.totalGEX(gex.totalGEX)
               .gexFlipPoint(gex.flipPoint)
               .gexProfile(gex.profile)
               .isPositiveGamma(gex.isPositive);

        // IV Analysis (unchanged - uses greeks)
        IVAnalysisResult ivAnalysis = analyzeIV(options, spotPrice);
        builder.atmIV(ivAnalysis.atmIV)
               .ivSkew(ivAnalysis.skew)
               .ivSmile(ivAnalysis.smile)
               .ivPercentile(ivAnalysis.percentile)
               .ivTerm(ivAnalysis.term);

        // OI Analysis from real data
        builder.maxCallOIStrike(chain.getMaxCallOIStrike())
               .maxPutOIStrike(chain.getMaxPutOIStrike())
               .callOIWall(chain.getMaxCallOIStrike())
               .putOIWall(chain.getMaxPutOIStrike())
               .oiBasedRange(chain.getOiBasedRange());

        // Derived signals using real PCR
        double pcr = chain.getPcrByOI();
        builder.bullishSignal(pcr > 1.2 && gex.isPositive)
               .bearishSignal(pcr < 0.8 && !gex.isPositive)
               .rangebound(Math.abs(chain.getMaxPain() - spotPrice) / spotPrice < 0.02);

        log.info("{} Analytics for {}: PCR={}, MaxPain={}, GEX={}, Range=[{}, {}]",
            LOG_PREFIX, chain.getUnderlyingSymbol(),
            String.format("%.2f", pcr), chain.getMaxPain(),
            String.format("%.0f", gex.totalGEX),
            chain.getMaxPutOIStrike(), chain.getMaxCallOIStrike());

        return builder.build();
    }

    /**
     * Calculate PCR by premium only.
     */
    private double calculatePCRByPremium(List<OptionGreeks> calls, List<OptionGreeks> puts) {
        double callPremium = calls.stream().mapToDouble(c -> c.getOptionPrice() * 100).sum();
        double putPremium = puts.stream().mapToDouble(p -> p.getOptionPrice() * 100).sum();
        return callPremium > 0 ? putPremium / callPremium : 1.0;
    }

    /**
     * Calculate GEX with real OI data.
     * GEX = Gamma * OI * Spot^2 * 0.01 * contractMultiplier
     */
    private GEXResult calculateGEXWithOI(List<OptionGreeks> options, double spotPrice, OptionChain chain) {
        GEXResult result = new GEXResult();
        Map<Double, Double> profile = new TreeMap<>();

        // Build OI lookup from chain
        Map<Double, StrikeEntry> oiLookup = chain.getStrikes().stream()
            .collect(Collectors.toMap(StrikeEntry::getStrike, s -> s, (a, b) -> a));

        double totalGEX = 0;
        int contractMultiplier = getContractMultiplier(chain.getUnderlyingSymbol());

        for (OptionGreeks opt : options) {
            double gamma = opt.getGamma();
            double strike = opt.getStrikePrice();

            // Get real OI from chain
            StrikeEntry strikeData = oiLookup.get(strike);
            long oi = 0;
            if (strikeData != null) {
                oi = opt.getOptionType() == OptionType.CALL ?
                    strikeData.getCallOI() : strikeData.getPutOI();
            }

            // GEX = Gamma * OI * Spot^2 * 0.01 * contractMultiplier
            double gex = gamma * oi * spotPrice * spotPrice * 0.01 * contractMultiplier;

            // Calls have positive GEX, puts have negative (dealers are short gamma on puts)
            if (opt.getOptionType() == OptionType.PUT) {
                gex = -gex;
            }

            totalGEX += gex;
            profile.merge(strike, gex, Double::sum);
        }

        result.totalGEX = totalGEX;
        result.isPositive = totalGEX > 0;
        result.profile = profile;

        // Find GEX flip point
        double cumGEX = 0;
        result.flipPoint = spotPrice;
        for (Map.Entry<Double, Double> entry : profile.entrySet()) {
            double prevCum = cumGEX;
            cumGEX += entry.getValue();
            if (prevCum * cumGEX < 0) {
                result.flipPoint = entry.getKey();
                break;
            }
        }

        return result;
    }

    /**
     * Get contract multiplier for underlying.
     */
    private int getContractMultiplier(String underlying) {
        if (underlying == null) return 50;
        switch (underlying.toUpperCase()) {
            case "NIFTY": return 50;
            case "BANKNIFTY": return 25;
            case "FINNIFTY": return 40;
            default: return 1;  // Stock options
        }
    }

    /**
     * Calculate analytics using only real OI data (no greeks required).
     * Useful when only chain-level metrics are needed.
     *
     * @param underlyingSymbol The underlying symbol
     * @return OptionsAnalytics with OI-based metrics
     */
    public OptionsAnalytics calculateFromChainOnly(String underlyingSymbol) {
        OptionChain chain = optionChainService.buildChain(underlyingSymbol);

        if (chain.isEmpty()) {
            log.warn("{} No option chain data for {}", LOG_PREFIX, underlyingSymbol);
            return OptionsAnalytics.builder()
                .underlyingSymbol(underlyingSymbol)
                .timestamp(Instant.now())
                .build();
        }

        double spotPrice = chain.getSpotPrice();
        double pcr = chain.getPcrByOI();

        return OptionsAnalytics.builder()
            .underlyingSymbol(underlyingSymbol)
            .spotPrice(spotPrice)
            .timestamp(Instant.now())
            // PCR from real OI
            .pcrByOI(pcr)
            .pcrByVolume(pcr)
            .pcrSignal(chain.getPCRSignal())
            // Max Pain
            .maxPain(chain.getMaxPain())
            .maxPainDistance((chain.getMaxPain() - spotPrice) / spotPrice)
            // OI Walls
            .maxCallOIStrike(chain.getMaxCallOIStrike())
            .maxPutOIStrike(chain.getMaxPutOIStrike())
            .callOIWall(chain.getMaxCallOIStrike())
            .putOIWall(chain.getMaxPutOIStrike())
            .oiBasedRange(chain.getOiBasedRange())
            // Signals
            .bullishSignal(pcr > 1.2)
            .bearishSignal(pcr < 0.8)
            .rangebound(Math.abs(chain.getMaxPain() - spotPrice) / spotPrice < 0.02)
            .build();
    }

    /**
     * Calculate complete options analytics from a list of options.
     * Falls back to price-based proxies when OI data is not available.
     *
     * @param options    List of OptionGreeks for the chain
     * @param spotPrice  Current underlying price
     * @return OptionsAnalytics with all metrics
     */
    public OptionsAnalytics calculate(List<OptionGreeks> options, double spotPrice) {
        if (options == null || options.isEmpty()) {
            return OptionsAnalytics.builder()
                .timestamp(Instant.now())
                .build();
        }

        // Separate calls and puts
        List<OptionGreeks> calls = options.stream()
            .filter(o -> o.getOptionType() == OptionType.CALL)
            .collect(Collectors.toList());
        List<OptionGreeks> puts = options.stream()
            .filter(o -> o.getOptionType() == OptionType.PUT)
            .collect(Collectors.toList());

        OptionsAnalytics.OptionsAnalyticsBuilder builder = OptionsAnalytics.builder()
            .underlyingSymbol(options.get(0).getUnderlyingSymbol())
            .spotPrice(spotPrice)
            .timestamp(Instant.now());

        // Max Pain
        MaxPainResult maxPain = calculateMaxPain(options, spotPrice);
        builder.maxPain(maxPain.maxPainStrike)
               .maxPainDistance(maxPain.distance)
               .totalPainAtMaxPain(maxPain.totalPain);

        // PCR (Put-Call Ratio)
        PCRResult pcr = calculatePCR(calls, puts);
        builder.pcrByOI(pcr.pcrByOI)
               .pcrByVolume(pcr.pcrByVolume)
               .pcrByPremium(pcr.pcrByPremium)
               .pcrSignal(pcr.signal);

        // GEX (Gamma Exposure)
        GEXResult gex = calculateGEX(options, spotPrice);
        builder.totalGEX(gex.totalGEX)
               .gexFlipPoint(gex.flipPoint)
               .gexProfile(gex.profile)
               .isPositiveGamma(gex.isPositive);

        // IV Analysis
        IVAnalysisResult ivAnalysis = analyzeIV(options, spotPrice);
        builder.atmIV(ivAnalysis.atmIV)
               .ivSkew(ivAnalysis.skew)
               .ivSmile(ivAnalysis.smile)
               .ivPercentile(ivAnalysis.percentile)
               .ivTerm(ivAnalysis.term);

        // OI Analysis
        OIAnalysisResult oiAnalysis = analyzeOI(options, spotPrice);
        builder.maxCallOIStrike(oiAnalysis.maxCallOIStrike)
               .maxPutOIStrike(oiAnalysis.maxPutOIStrike)
               .callOIWall(oiAnalysis.callWall)
               .putOIWall(oiAnalysis.putWall)
               .oiBasedRange(oiAnalysis.range);

        // Derived signals
        builder.bullishSignal(pcr.pcrByOI > 1.2 && gex.isPositive)
               .bearishSignal(pcr.pcrByOI < 0.8 && !gex.isPositive)
               .rangebound(Math.abs(maxPain.distance) < 0.02);

        return builder.build();
    }

    /**
     * Calculate Max Pain - the strike at which option writers have minimum loss.
     */
    private MaxPainResult calculateMaxPain(List<OptionGreeks> options, double spotPrice) {
        MaxPainResult result = new MaxPainResult();

        // Get unique strikes
        Set<Double> strikes = options.stream()
            .map(OptionGreeks::getStrikePrice)
            .collect(Collectors.toSet());

        double minPain = Double.MAX_VALUE;
        double maxPainStrike = spotPrice;

        for (double strike : strikes) {
            double totalPain = 0;

            for (OptionGreeks opt : options) {
                double intrinsicValue;
                if (opt.getOptionType() == OptionType.CALL) {
                    intrinsicValue = Math.max(0, strike - opt.getStrikePrice());
                } else {
                    intrinsicValue = Math.max(0, opt.getStrikePrice() - strike);
                }
                // Pain = OI * intrinsic value (assuming OI is available)
                // For now, use 1 as default OI
                totalPain += intrinsicValue;
            }

            if (totalPain < minPain) {
                minPain = totalPain;
                maxPainStrike = strike;
            }
        }

        result.maxPainStrike = maxPainStrike;
        result.totalPain = minPain;
        result.distance = spotPrice > 0 ? (maxPainStrike - spotPrice) / spotPrice : 0;

        return result;
    }

    /**
     * Calculate Put-Call Ratio.
     */
    private PCRResult calculatePCR(List<OptionGreeks> calls, List<OptionGreeks> puts) {
        PCRResult result = new PCRResult();

        // By volume (using optionPrice * 100 as proxy if volume not available)
        double callPremium = calls.stream().mapToDouble(c -> c.getOptionPrice() * 100).sum();
        double putPremium = puts.stream().mapToDouble(p -> p.getOptionPrice() * 100).sum();

        result.pcrByPremium = callPremium > 0 ? putPremium / callPremium : 1.0;
        result.pcrByOI = 1.0;  // Would need OI data
        result.pcrByVolume = 1.0;  // Would need volume data

        // Signal interpretation
        if (result.pcrByPremium > 1.5) {
            result.signal = "EXTREME_BEARISH";
        } else if (result.pcrByPremium > 1.2) {
            result.signal = "BEARISH";
        } else if (result.pcrByPremium < 0.5) {
            result.signal = "EXTREME_BULLISH";
        } else if (result.pcrByPremium < 0.8) {
            result.signal = "BULLISH";
        } else {
            result.signal = "NEUTRAL";
        }

        return result;
    }

    /**
     * Calculate Gamma Exposure (GEX).
     * GEX = Gamma * OI * Spot^2 * 0.01
     */
    private GEXResult calculateGEX(List<OptionGreeks> options, double spotPrice) {
        GEXResult result = new GEXResult();
        Map<Double, Double> profile = new TreeMap<>();

        double totalGEX = 0;

        for (OptionGreeks opt : options) {
            double gamma = opt.getGamma();
            double gex = gamma * spotPrice * spotPrice * 0.01;  // Simplified without OI

            // Calls have positive GEX, puts have negative (dealers are short gamma on puts)
            if (opt.getOptionType() == OptionType.PUT) {
                gex = -gex;
            }

            totalGEX += gex;
            profile.merge(opt.getStrikePrice(), gex, Double::sum);
        }

        result.totalGEX = totalGEX;
        result.isPositive = totalGEX > 0;
        result.profile = profile;

        // Find GEX flip point (where GEX changes sign)
        double cumGEX = 0;
        result.flipPoint = spotPrice;
        for (Map.Entry<Double, Double> entry : profile.entrySet()) {
            double prevCum = cumGEX;
            cumGEX += entry.getValue();
            if (prevCum * cumGEX < 0) {
                result.flipPoint = entry.getKey();
                break;
            }
        }

        return result;
    }

    /**
     * Analyze Implied Volatility across the chain.
     */
    private IVAnalysisResult analyzeIV(List<OptionGreeks> options, double spotPrice) {
        IVAnalysisResult result = new IVAnalysisResult();

        // Find ATM options
        Optional<OptionGreeks> atmCall = options.stream()
            .filter(o -> o.getOptionType() == OptionType.CALL)
            .min(Comparator.comparingDouble(o -> Math.abs(o.getStrikePrice() - spotPrice)));

        Optional<OptionGreeks> atmPut = options.stream()
            .filter(o -> o.getOptionType() == OptionType.PUT)
            .min(Comparator.comparingDouble(o -> Math.abs(o.getStrikePrice() - spotPrice)));

        // ATM IV (average of ATM call and put)
        double callIV = atmCall.map(OptionGreeks::getImpliedVolatility).orElse(20.0);
        double putIV = atmPut.map(OptionGreeks::getImpliedVolatility).orElse(20.0);
        result.atmIV = (callIV + putIV) / 2;

        // IV Skew (OTM put IV - OTM call IV)
        Optional<OptionGreeks> otmPut = options.stream()
            .filter(o -> o.getOptionType() == OptionType.PUT && o.getStrikePrice() < spotPrice * 0.95)
            .max(Comparator.comparingDouble(OptionGreeks::getStrikePrice));

        Optional<OptionGreeks> otmCall = options.stream()
            .filter(o -> o.getOptionType() == OptionType.CALL && o.getStrikePrice() > spotPrice * 1.05)
            .min(Comparator.comparingDouble(OptionGreeks::getStrikePrice));

        double otmPutIV = otmPut.map(OptionGreeks::getImpliedVolatility).orElse(result.atmIV);
        double otmCallIV = otmCall.map(OptionGreeks::getImpliedVolatility).orElse(result.atmIV);
        result.skew = otmPutIV - otmCallIV;

        // IV Smile (difference between OTM average and ATM)
        result.smile = ((otmPutIV + otmCallIV) / 2) - result.atmIV;

        // IV Percentile (would need historical data, defaulting to 50)
        result.percentile = 50.0;

        // Term structure (would need multiple expiries)
        result.term = "FLAT";

        return result;
    }

    /**
     * Analyze Open Interest distribution.
     */
    private OIAnalysisResult analyzeOI(List<OptionGreeks> options, double spotPrice) {
        OIAnalysisResult result = new OIAnalysisResult();

        // Without actual OI data, use option price as proxy for interest
        Optional<OptionGreeks> maxCallOI = options.stream()
            .filter(o -> o.getOptionType() == OptionType.CALL)
            .filter(o -> o.getStrikePrice() > spotPrice)
            .max(Comparator.comparingDouble(OptionGreeks::getOptionPrice));

        Optional<OptionGreeks> maxPutOI = options.stream()
            .filter(o -> o.getOptionType() == OptionType.PUT)
            .filter(o -> o.getStrikePrice() < spotPrice)
            .max(Comparator.comparingDouble(OptionGreeks::getOptionPrice));

        result.maxCallOIStrike = maxCallOI.map(OptionGreeks::getStrikePrice).orElse(spotPrice * 1.05);
        result.maxPutOIStrike = maxPutOI.map(OptionGreeks::getStrikePrice).orElse(spotPrice * 0.95);

        // Walls
        result.callWall = result.maxCallOIStrike;
        result.putWall = result.maxPutOIStrike;

        // Range
        result.range = new double[]{result.putWall, result.callWall};

        return result;
    }

    // ==================== RESULT CLASSES ====================

    private static class MaxPainResult {
        double maxPainStrike;
        double totalPain;
        double distance;
    }

    private static class PCRResult {
        double pcrByOI;
        double pcrByVolume;
        double pcrByPremium;
        String signal;
    }

    private static class GEXResult {
        double totalGEX;
        double flipPoint;
        Map<Double, Double> profile;
        boolean isPositive;
    }

    private static class IVAnalysisResult {
        double atmIV;
        double skew;
        double smile;
        double percentile;
        String term;
    }

    private static class OIAnalysisResult {
        double maxCallOIStrike;
        double maxPutOIStrike;
        double callWall;
        double putWall;
        double[] range;
    }
}
