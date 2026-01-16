package com.kotsin.consumer.domain.service;

import com.kotsin.consumer.domain.calculator.FuturesBuildupDetector;
import com.kotsin.consumer.domain.calculator.OISignalDetector;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * FamilySignalGenerator - Generates trading signals from FamilyCandle.
 * 
 * SOLID Principles Applied:
 * - Single Responsibility: Only generates signals, doesn't persist or publish
 * - Open/Closed: Signal types can be extended without modifying this class
 * - Dependency Inversion: Uses calculator interfaces, not implementations
 * 
 * Signal Generation Rules:
 * 1. Strong signals require 3+ confirming factors
 * 2. Always log the reasoning for auditability
 * 3. Include confidence scores for downstream filtering
 */
@Service
@Slf4j
public class FamilySignalGenerator {

    // Thresholds
    private static final double MIN_SIGNAL_CONFIDENCE = 0.6;
    private static final double STRONG_SIGNAL_CONFIDENCE = 0.8;
    private static final double PCR_EXTREME_LOW = 0.5;
    private static final double PCR_EXTREME_HIGH = 1.5;
    private static final double MIN_PREMIUM_CHANGE = 0.1;

    /**
     * Generated trading signal
     */
    @Data
    @Builder
    public static class TradingSignal {
        private String familyId;
        private String symbol;
        private long timestamp;
        private String signalType;       // BUY, SELL, HOLD
        private String signalStrength;   // STRONG, MODERATE, WEAK
        private double confidence;       // 0.0 - 1.0
        private String primaryReason;    // Main reason for signal
        private String confirmingFactors; // Additional confirmations
        private double targetPrice;      // Suggested target
        private double stopLoss;         // Suggested stop
        private String oiSignal;
        private String futuresBuildup;
        private Double pcr;
        private String regime;           // Current market regime
    }

    /**
     * Generate signal from FamilyCandle
     * 
     * @param family FamilyCandle with all instruments
     * @return TradingSignal or null if no actionable signal
     */
    public TradingSignal generate(FamilyCandle family) {
        if (family == null || family.getEquity() == null) {
            log.trace("Skipping signal generation: null family or equity");
            return null;
        }

        String familyId = family.getFamilyId();
        String symbol = family.getSymbol();
        InstrumentCandle equity = family.getEquity();

        log.debug("[{}] Analyzing family candle for signals - hasF={}, hasO={}", 
            symbol, family.isHasFuture(), family.isHasOptions());

        // Calculate confirming factors
        int bullishFactors = 0;
        int bearishFactors = 0;
        StringBuilder reasons = new StringBuilder();

        // 1. Price action
        double priceChange = equity.getPriceChangePercent();
        if (priceChange > 0.3) {
            bullishFactors++;
            reasons.append("price_up;");
            log.trace("[{}] Bullish factor: price up {}%", symbol, String.format("%.2f", priceChange));
        } else if (priceChange < -0.3) {
            bearishFactors++;
            reasons.append("price_down;");
            log.trace("[{}] Bearish factor: price down {}%", symbol, String.format("%.2f", priceChange));
        }

        // 2. OI Signal (most important)
        String oiSignal = family.getOiSignal();
        if (oiSignal != null) {
            OISignalDetector.OISignalType signalType = parseOISignal(oiSignal);
            if (OISignalDetector.isBullish(signalType)) {
                bullishFactors += 2;  // Weight OI signals higher
                reasons.append("oi_bullish:").append(oiSignal).append(";");
                log.debug("[{}] Strong bullish OI signal: {}", symbol, oiSignal);
            } else if (OISignalDetector.isBearish(signalType)) {
                bearishFactors += 2;
                reasons.append("oi_bearish:").append(oiSignal).append(";");
                log.debug("[{}] Strong bearish OI signal: {}", symbol, oiSignal);
            }
        }

        // 3. Futures buildup
        String futuresBuildup = family.getFuturesBuildup();
        if (futuresBuildup != null) {
            FuturesBuildupDetector.BuildupType buildup = parseBuildupType(futuresBuildup);
            if (FuturesBuildupDetector.isBullish(buildup)) {
                bullishFactors++;
                reasons.append("futures_bullish:").append(futuresBuildup).append(";");
                log.trace("[{}] Bullish futures buildup: {}", symbol, futuresBuildup);
            } else if (FuturesBuildupDetector.isBearish(buildup)) {
                bearishFactors++;
                reasons.append("futures_bearish:").append(futuresBuildup).append(";");
                log.trace("[{}] Bearish futures buildup: {}", symbol, futuresBuildup);
            }
        }

        // 4. PCR extremes (contrarian signals)
        Double pcr = family.getPcr();
        if (pcr != null) {
            if (pcr >= PCR_EXTREME_HIGH && priceChange < 0) {
                // Extreme bearishness at falling price - contrarian bullish
                bullishFactors++;
                reasons.append("pcr_contrarian_bullish;");
                log.debug("[{}] Contrarian bullish: PCR={} at falling price", symbol, String.format("%.2f", pcr));
            } else if (pcr <= PCR_EXTREME_LOW && priceChange > 0) {
                // Extreme bullishness at rising price - contrarian bearish
                bearishFactors++;
                reasons.append("pcr_contrarian_bearish;");
                log.debug("[{}] Contrarian bearish: PCR={} at rising price", symbol, String.format("%.2f", pcr));
            }
        }

        // 5. Spot-Future premium
        Double premium = family.getSpotFuturePremium();
        if (premium != null && Math.abs(premium) > MIN_PREMIUM_CHANGE) {
            if (premium > 0 && priceChange > 0) {
                bullishFactors++;  // Futures leading equity up
                reasons.append("premium_bullish;");
            } else if (premium < 0 && priceChange < 0) {
                bearishFactors++;  // Futures leading equity down
                reasons.append("premium_bearish;");
            }
        }

        // 6. Volume confirmation
        if (equity.getVolume() > 0 && equity.getBuyVolume() > equity.getSellVolume() * 1.5) {
            if (priceChange > 0) {
                bullishFactors++;
                reasons.append("buy_volume_surge;");
            }
        } else if (equity.getVolume() > 0 && equity.getSellVolume() > equity.getBuyVolume() * 1.5) {
            if (priceChange < 0) {
                bearishFactors++;
                reasons.append("sell_volume_surge;");
            }
        }

        // Determine signal
        int netScore = bullishFactors - bearishFactors;
        double confidence = calculateConfidence(bullishFactors, bearishFactors, family);

        if (confidence < MIN_SIGNAL_CONFIDENCE) {
            log.trace("[{}] No signal: confidence {} below threshold", symbol, String.format("%.2f", confidence));
            return null;
        }

        String signalType;
        String signalStrength;
        String primaryReason;

        if (netScore >= 3) {
            signalType = "BUY";
            signalStrength = confidence >= STRONG_SIGNAL_CONFIDENCE ? "STRONG" : "MODERATE";
            primaryReason = determinePrimaryReason(true, oiSignal, futuresBuildup, pcr);
            log.info("[{}] {} {} signal - confidence={}, factors: {} bullish, {} bearish",
                symbol, signalStrength, signalType, String.format("%.2f", confidence), bullishFactors, bearishFactors);
        } else if (netScore <= -3) {
            signalType = "SELL";
            signalStrength = confidence >= STRONG_SIGNAL_CONFIDENCE ? "STRONG" : "MODERATE";
            primaryReason = determinePrimaryReason(false, oiSignal, futuresBuildup, pcr);
            log.info("[{}] {} {} signal - confidence={}, factors: {} bullish, {} bearish",
                symbol, signalStrength, signalType, String.format("%.2f", confidence), bullishFactors, bearishFactors);
        } else {
            log.trace("[{}] No clear signal: netScore={}", symbol, netScore);
            return null;
        }

        // Calculate targets
        double currentPrice = equity.getClose();
        double atr = calculateATR(equity);
        double targetPrice = signalType.equals("BUY") 
            ? currentPrice + (atr * 2.0)
            : currentPrice - (atr * 2.0);
        double stopLoss = signalType.equals("BUY")
            ? currentPrice - (atr * 1.0)
            : currentPrice + (atr * 1.0);

        return TradingSignal.builder()
            .familyId(familyId)
            .symbol(symbol)
            .timestamp(family.getTimestamp())
            .signalType(signalType)
            .signalStrength(signalStrength)
            .confidence(confidence)
            .primaryReason(primaryReason)
            .confirmingFactors(reasons.toString())
            .targetPrice(targetPrice)
            .stopLoss(stopLoss)
            .oiSignal(oiSignal)
            .futuresBuildup(futuresBuildup)
            .pcr(pcr)
            .build();
    }

    /**
     * Calculate confidence based on factor counts and data availability
     */
    private double calculateConfidence(int bullish, int bearish, FamilyCandle family) {
        int total = Math.max(bullish, bearish);
        double base = Math.min(0.5 + (total * 0.1), 0.9);

        // Bonus for complete data
        if (family.isHasFuture()) base += 0.05;
        if (family.isHasOptions()) base += 0.05;

        return Math.min(1.0, base);
    }

    /**
     * Determine primary reason for signal
     */
    private String determinePrimaryReason(boolean bullish, String oiSignal, String buildup, Double pcr) {
        if (oiSignal != null && !oiSignal.equals("NEUTRAL")) {
            return bullish ? "OI accumulation suggests strong buying interest"
                          : "OI distribution suggests strong selling pressure";
        }
        if (buildup != null && !buildup.equals("NEUTRAL")) {
            return bullish ? "Futures long buildup indicates institutional buying"
                          : "Futures short buildup indicates institutional selling";
        }
        if (pcr != null) {
            if (pcr > PCR_EXTREME_HIGH) {
                return "Extreme PCR suggests potential reversal to upside";
            } else if (pcr < PCR_EXTREME_LOW) {
                return "Low PCR suggests potential reversal to downside";
            }
        }
        return bullish ? "Multiple bullish confirmations aligned"
                      : "Multiple bearish confirmations aligned";
    }

    /**
     * Simple ATR calculation from single candle
     */
    private double calculateATR(InstrumentCandle candle) {
        double range = candle.getHigh() - candle.getLow();
        return Math.max(range, candle.getClose() * 0.01);  // Minimum 1%
    }

    /**
     * Parse OI signal string to enum
     */
    private OISignalDetector.OISignalType parseOISignal(String signal) {
        try {
            return OISignalDetector.OISignalType.valueOf(signal);
        } catch (Exception e) {
            return OISignalDetector.OISignalType.NEUTRAL;
        }
    }

    /**
     * Parse buildup type string to enum
     */
    private FuturesBuildupDetector.BuildupType parseBuildupType(String buildup) {
        try {
            return FuturesBuildupDetector.BuildupType.valueOf(buildup);
        } catch (Exception e) {
            return FuturesBuildupDetector.BuildupType.NEUTRAL;
        }
    }
}
