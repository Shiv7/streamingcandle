package com.kotsin.consumer.domain.calculator;

import com.kotsin.consumer.domain.model.OptionCandle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

/**
 * PCRCalculator - Put/Call Ratio Calculator
 * 
 * Calculates Put/Call Ratio from options data for sentiment analysis.
 * 
 * TWO MODES:
 * 1. OI-based PCR: For overnight/swing analysis (position buildup)
 * 2. Volume-based PCR: For intraday analysis (current activity)
 * 
 * Interpretation:
 * - PCR > 1.0: More puts than calls = bearish sentiment (contrarian bullish)
 * - PCR < 1.0: More calls than puts = bullish sentiment (contrarian bearish)
 * - PCR ≈ 1.0: Balanced sentiment
 * 
 * Extreme levels often indicate contrarian opportunities:
 * - PCR > 1.5: Extreme bearishness = often marks bottoms
 * - PCR < 0.5: Extreme bullishness = often marks tops
 */
@Slf4j
public class PCRCalculator {

    /**
     * PCR Result containing both OI and Volume based PCR
     */
    @lombok.Data
    @lombok.Builder
    @lombok.AllArgsConstructor
    public static class PCRResult {
        private Double oiPcr;           // OI-based PCR (for swing/overnight)
        private Double volumePcr;       // Volume-based PCR (for intraday)
        private Double combinedPcr;     // Weighted combination
        private long totalCallOI;
        private long totalPutOI;
        private long totalCallVolume;
        private long totalPutVolume;
        private String sentiment;       // EXTREME_BEARISH, BEARISH, NEUTRAL, BULLISH, EXTREME_BULLISH
        
        public boolean isValid() {
            return oiPcr != null || volumePcr != null;
        }
    }

    /**
     * Calculate comprehensive PCR from list of option candles
     * Returns both OI-based and Volume-based PCR for different timeframe analysis
     *
     * @param options List of option candles (should contain both CE and PE)
     * @param intradayMode If true, weight volume PCR higher; otherwise weight OI PCR higher
     * @return PCRResult with both PCR types (null if insufficient data)
     */
    public static PCRResult calculateComprehensive(List<OptionCandle> options, boolean intradayMode) {
        if (options == null || options.isEmpty()) {
            return null;
        }

        long totalCallOI = options.stream()
            .filter(OptionCandle::isCall)
            .mapToLong(OptionCandle::getOpenInterest)
            .sum();

        long totalPutOI = options.stream()
            .filter(OptionCandle::isPut)
            .mapToLong(OptionCandle::getOpenInterest)
            .sum();

        long totalCallVolume = options.stream()
            .filter(OptionCandle::isCall)
            .mapToLong(OptionCandle::getVolume)
            .sum();

        long totalPutVolume = options.stream()
            .filter(OptionCandle::isPut)
            .mapToLong(OptionCandle::getVolume)
            .sum();

        Double oiPcr = totalCallOI > 0 ? (double) totalPutOI / totalCallOI : null;
        Double volumePcr = totalCallVolume > 0 ? (double) totalPutVolume / totalCallVolume : null;

        // Combined PCR: weight based on trading mode
        Double combinedPcr = null;
        if (oiPcr != null && volumePcr != null) {
            if (intradayMode) {
                // Intraday: 70% volume, 30% OI
                combinedPcr = volumePcr * 0.7 + oiPcr * 0.3;
            } else {
                // Swing/Overnight: 70% OI, 30% volume
                combinedPcr = oiPcr * 0.7 + volumePcr * 0.3;
            }
        } else if (oiPcr != null) {
            combinedPcr = oiPcr;
        } else if (volumePcr != null) {
            combinedPcr = volumePcr;
        }

        String sentiment = classifySentiment(combinedPcr);

        return PCRResult.builder()
            .oiPcr(oiPcr)
            .volumePcr(volumePcr)
            .combinedPcr(combinedPcr)
            .totalCallOI(totalCallOI)
            .totalPutOI(totalPutOI)
            .totalCallVolume(totalCallVolume)
            .totalPutVolume(totalPutVolume)
            .sentiment(sentiment)
            .build();
    }

    /**
     * Calculate OI-based PCR from list of option candles (legacy method)
     *
     * @param options List of option candles (should contain both CE and PE)
     * @return PCR value (null if insufficient data)
     */
    public static Double calculate(List<OptionCandle> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }

        long totalCallOI = options.stream()
            .filter(OptionCandle::isCall)
            .mapToLong(OptionCandle::getOpenInterest)
            .sum();

        long totalPutOI = options.stream()
            .filter(OptionCandle::isPut)
            .mapToLong(OptionCandle::getOpenInterest)
            .sum();

        if (totalCallOI <= 0) {
            return null;  // Avoid division by zero
        }

        return (double) totalPutOI / totalCallOI;
    }

    /**
     * Calculate volume-based PCR (using option volume instead of OI)
     *
     * @param options List of option candles
     * @return Volume-based PCR
     */
    public static Double calculateVolumeBasedPCR(List<OptionCandle> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }

        long totalCallVolume = options.stream()
            .filter(OptionCandle::isCall)
            .mapToLong(OptionCandle::getVolume)
            .sum();

        long totalPutVolume = options.stream()
            .filter(OptionCandle::isPut)
            .mapToLong(OptionCandle::getVolume)
            .sum();

        if (totalCallVolume <= 0) {
            return null;
        }

        return (double) totalPutVolume / totalCallVolume;
    }

    /**
     * Calculate PCR change from previous period
     *
     * @param currentPCR Current PCR
     * @param previousPCR Previous PCR
     * @return Change in PCR
     */
    public static Double calculateChange(Double currentPCR, Double previousPCR) {
        if (currentPCR == null || previousPCR == null || previousPCR == 0) {
            return null;
        }
        return currentPCR - previousPCR;
    }

    /**
     * Get total Call OI from options list
     *
     * @param options List of option candles
     * @return Total Call OI
     */
    public static long getTotalCallOI(List<OptionCandle> options) {
        if (options == null) return 0L;
        return options.stream()
            .filter(OptionCandle::isCall)
            .mapToLong(OptionCandle::getOpenInterest)
            .sum();
    }

    /**
     * Get total Put OI from options list
     *
     * @param options List of option candles
     * @return Total Put OI
     */
    public static long getTotalPutOI(List<OptionCandle> options) {
        if (options == null) return 0L;
        return options.stream()
            .filter(OptionCandle::isPut)
            .mapToLong(OptionCandle::getOpenInterest)
            .sum();
    }

    /**
     * Get total Call OI change
     *
     * @param options List of option candles
     * @return Total Call OI change
     */
    public static long getTotalCallOIChange(List<OptionCandle> options) {
        if (options == null) return 0L;
        return options.stream()
            .filter(OptionCandle::isCall)
            .mapToLong(OptionCandle::getOiChange)
            .sum();
    }

    /**
     * Get total Put OI change
     *
     * @param options List of option candles
     * @return Total Put OI change
     */
    public static long getTotalPutOIChange(List<OptionCandle> options) {
        if (options == null) return 0L;
        return options.stream()
            .filter(OptionCandle::isPut)
            .mapToLong(OptionCandle::getOiChange)
            .sum();
    }

    /**
     * Classify PCR into sentiment category
     *
     * @param pcr PCR value
     * @return Sentiment string
     */
    public static String classifySentiment(Double pcr) {
        if (pcr == null) return "UNKNOWN";
        
        if (pcr >= 1.5) {
            return "EXTREME_BEARISH";  // Contrarian bullish signal
        } else if (pcr >= 1.2) {
            return "BEARISH";
        } else if (pcr >= 0.8) {
            return "NEUTRAL";
        } else if (pcr >= 0.5) {
            return "BULLISH";
        } else {
            return "EXTREME_BULLISH";  // Contrarian bearish signal
        }
    }

    /**
     * Check if PCR indicates contrarian opportunity
     *
     * @param pcr PCR value
     * @param priceDirection 1 for up, -1 for down
     * @return True if contrarian signal present
     */
    public static boolean isContrarianSignal(Double pcr, int priceDirection) {
        if (pcr == null) return false;
        
        // Extreme bearishness while price falling = potential bottom
        if (pcr >= 1.5 && priceDirection < 0) {
            return true;
        }
        
        // Extreme bullishness while price rising = potential top
        if (pcr <= 0.5 && priceDirection > 0) {
            return true;
        }
        
        return false;
    }
}
