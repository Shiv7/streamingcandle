package com.kotsin.consumer.quant.signal;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.quant.config.QuantScoreConfig;
import com.kotsin.consumer.quant.model.QuantScore;
import com.kotsin.consumer.quant.model.QuantTradingSignal;
import com.kotsin.consumer.quant.model.QuantTradingSignal.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * QuantSignalGenerator - Generates actionable trading signals from QuantScore.
 *
 * Determines:
 * - Signal type based on dominant category
 * - Entry/exit prices with ATR-based stops
 * - Position sizing based on risk parameters
 * - Trailing stop configuration
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QuantSignalGenerator {

    private final QuantScoreConfig config;
    private final HedgingRecommendationEngine hedgingEngine;

    private static final DateTimeFormatter TIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata"));

    /**
     * Generate trading signal from QuantScore and FamilyCandle
     *
     * @param score QuantScore with breakdown
     * @param family FamilyCandle with market data
     * @return QuantTradingSignal with full execution parameters
     */
    public QuantTradingSignal generate(QuantScore score, FamilyCandle family) {
        if (score == null || !score.isActionable()) {
            return QuantTradingSignal.noSignal(
                score != null ? score.getScripCode() : "UNKNOWN",
                score != null ? score.getSymbol() : "UNKNOWN"
            );
        }

        // Determine signal type from dominant category
        SignalType signalType = determineSignalType(score, family);

        // Determine direction
        SignalDirection direction = score.isBullish() ?
            SignalDirection.LONG : score.isBearish() ?
            SignalDirection.SHORT : SignalDirection.NEUTRAL;

        if (direction == SignalDirection.NEUTRAL) {
            return QuantTradingSignal.noSignal(score.getScripCode(), score.getSymbol());
        }

        // Get price data
        InstrumentCandle primary = family.getPrimaryInstrumentOrFallback();
        if (primary == null) {
            return QuantTradingSignal.noSignal(score.getScripCode(), score.getSymbol());
        }

        double currentPrice = primary.getClose();
        double atr = calculateATR(primary);

        // Calculate entry zone
        double entryPrice = currentPrice;
        double entryMin = direction == SignalDirection.LONG ?
            currentPrice * 0.998 : currentPrice * 0.995;
        double entryMax = direction == SignalDirection.LONG ?
            currentPrice * 1.005 : currentPrice * 1.002;

        // Calculate stop loss (ATR-based)
        double slMultiplier = calculateSLMultiplier(score);
        double stopLoss = direction == SignalDirection.LONG ?
            currentPrice - (atr * slMultiplier) :
            currentPrice + (atr * slMultiplier);

        double riskPerShare = Math.abs(entryPrice - stopLoss);
        double stopLossPercent = riskPerShare / entryPrice * 100;

        // Calculate targets (R-multiples)
        double target1 = direction == SignalDirection.LONG ?
            entryPrice + (riskPerShare * 2) : entryPrice - (riskPerShare * 2);
        double target2 = direction == SignalDirection.LONG ?
            entryPrice + (riskPerShare * 3) : entryPrice - (riskPerShare * 3);
        double target3 = direction == SignalDirection.LONG ?
            entryPrice + (riskPerShare * 5) : entryPrice - (riskPerShare * 5);

        // Calculate risk-reward ratio
        double rrRatio = Math.abs(target1 - entryPrice) / riskPerShare;

        // Position sizing
        PositionSizing sizing = calculatePositionSizing(score, currentPrice, riskPerShare);

        // Trailing stop configuration
        TrailingStopConfig trailing = buildTrailingStopConfig(score);

        // Hedging recommendation
        HedgingRecommendation hedging = hedgingEngine.recommend(score, family, direction);

        // Build rationale
        String rationale = buildRationale(score, signalType);
        String primaryDriver = score.getStrongestCategory();

        // Signal strength
        SignalStrength strength = SignalStrength.fromConfidence(score.getConfidence());

        // Time constraints
        TimeConstraints timeConstraints = buildTimeConstraints(family);

        // FIX: Use candle timestamp instead of processing time for accurate price-time correlation
        long signalTimestamp = family.getWindowEndMillis() > 0
            ? family.getWindowEndMillis()
            : family.getTimestamp() > 0
                ? family.getTimestamp()
                : System.currentTimeMillis();

        // FIX: Make quantLabel consistent with direction to avoid confusing signals
        // quantLabel from score is based on score value (>65 = BUY), but direction is from isBullish()/isBearish()
        // Override quantLabel to match direction for consistency
        String consistentQuantLabel = switch (direction) {
            case LONG -> "BUY";
            case SHORT -> "SELL";
            case NEUTRAL -> "HOLD";
        };

        return QuantTradingSignal.builder()
            .signalId(UUID.randomUUID().toString())
            .scripCode(score.getScripCode())
            .symbol(score.getSymbol())
            .companyName(family.getSymbol())
            .exchange("NSE")
            .timestamp(signalTimestamp)
            .humanReadableTime(TIME_FORMATTER.format(Instant.ofEpochMilli(signalTimestamp)))

            // Quant score reference
            .quantScore(score.getQuantScore())
            .quantLabel(consistentQuantLabel)  // FIX: Use direction-consistent label instead of score.getQuantLabel()
            .scoreBreakdown(score.getBreakdown())
            .confidence(score.getConfidence())

            // Signal classification
            .signalType(signalType)
            .direction(direction)
            .strength(strength)
            .rationale(rationale)
            .primaryDriver(primaryDriver)

            // Entry parameters
            .entryPrice(entryPrice)
            .entryPriceMin(entryMin)
            .entryPriceMax(entryMax)
            .entryType(EntryType.LIMIT)

            // Stop loss
            .stopLoss(stopLoss)
            .stopLossPercent(stopLossPercent)
            .slType(StopLossType.ATR_BASED)

            // Targets
            .target1(target1)
            .target2(target2)
            .target3(target3)
            .target1Pct(50)  // Exit 50% at T1
            .target2Pct(30)  // Exit 30% at T2
            .target3Pct(20)  // Exit 20% at T3

            // Risk parameters
            .riskRewardRatio(rrRatio)
            .maxRiskPercent(config.getSizing().getMaxRiskPercent())
            .riskPerShare(riskPerShare)

            // Position sizing
            .sizing(sizing)

            // Trailing stop
            .trailingStop(trailing)

            // Hedging
            .hedging(hedging)

            // Time constraints
            .timeConstraints(timeConstraints)

            // Flags
            .longSignal(direction == SignalDirection.LONG)
            .shortSignal(direction == SignalDirection.SHORT)
            .hedgeRecommended(hedging != null && hedging.isHedgeNeeded())
            .partialExitRecommended(true)  // Always recommend partial exits
            .scaleInRecommended(score.getQuantScore() >= 80)
            .highConviction(strength == SignalStrength.VERY_STRONG || strength == SignalStrength.STRONG)
            .categoriesAboveThreshold(score.countStrongCategories())
            .sourceProcessor("QuantSignalGenerator")
            .timeframe(family.getTimeframe())

            .build();
    }

    /**
     * Determine signal type based on dominant category
     */
    private SignalType determineSignalType(QuantScore score, FamilyCandle family) {
        String strongest = score.getStrongestCategory();
        boolean bullish = score.isBullish();

        // Check for specific patterns
        if (score.getGreeksSummary() != null && score.getGreeksSummary().isGammaSqueezeRisk()) {
            return bullish ? SignalType.GAMMA_SQUEEZE_LONG : SignalType.GAMMA_SQUEEZE_SHORT;
        }

        if (score.getIvSummary() != null) {
            if (score.getIvSummary().isIvCrushRisk()) {
                return SignalType.IV_CRUSH_ENTRY;
            }
            if (score.getIvSummary().getIvRank() > 80) {
                return SignalType.VEGA_CONTRACTION;
            }
            if (score.getIvSummary().getIvRank() < 20) {
                return SignalType.VEGA_EXPANSION;
            }
        }

        // Based on dominant category
        switch (strongest) {
            case "GREEKS":
                return bullish ? SignalType.DELTA_MOMENTUM_LONG : SignalType.DELTA_MOMENTUM_SHORT;
            case "IV_SURFACE":
                return SignalType.SKEW_REVERSAL;
            case "MICROSTRUCTURE":
                return bullish ? SignalType.MICROSTRUCTURE_IMBALANCE_LONG :
                                 SignalType.MICROSTRUCTURE_IMBALANCE_SHORT;
            case "OPTIONS_FLOW":
                return bullish ? SignalType.FLOW_REVERSAL_LONG : SignalType.FLOW_REVERSAL_SHORT;
            case "PRICE_ACTION":
                if (score.getPriceActionSummary() != null) {
                    String wyckoff = score.getPriceActionSummary().getWyckoffPhase();
                    if ("ACCUMULATION".equals(wyckoff)) return SignalType.WYCKOFF_ACCUMULATION;
                    if ("DISTRIBUTION".equals(wyckoff)) return SignalType.WYCKOFF_DISTRIBUTION;
                }
                return SignalType.REVERSAL_PATTERN;
            case "CONFLUENCE":
                return bullish ? SignalType.CONFLUENCE_BREAKOUT : SignalType.CONFLUENCE_BREAKDOWN;
            default:
                return SignalType.MULTI_TIMEFRAME_ALIGNMENT;
        }
    }

    /**
     * Calculate ATR (simplified using range)
     */
    private double calculateATR(InstrumentCandle candle) {
        double range = candle.getHigh() - candle.getLow();
        // Use 1.5x range as ATR approximation
        return range * 1.5;
    }

    /**
     * Calculate stop loss multiplier based on score
     */
    private double calculateSLMultiplier(QuantScore score) {
        // Higher conviction = tighter stop allowed
        if (score.getConfidence() >= 0.8) {
            return 1.5;  // Tight stop for high conviction
        } else if (score.getConfidence() >= 0.6) {
            return 2.0;  // Standard stop
        } else {
            return 2.5;  // Wider stop for lower conviction
        }
    }

    /**
     * Calculate position sizing
     */
    private PositionSizing calculatePositionSizing(QuantScore score, double price, double riskPerShare) {
        double riskPercent = config.getSizing().getDefaultRiskPercent();

        // Adjust for conviction
        double sizeMultiplier = 1.0;
        if (score.getConfidence() >= 0.8) {
            sizeMultiplier = config.getSizing().getHighConvictionMultiplier();
        } else if (score.getConfidence() < 0.6) {
            sizeMultiplier = config.getSizing().getLowConvictionMultiplier();
        }

        // Assume 100,000 capital for calculation
        double capital = 100000;
        double riskAmount = capital * (riskPercent / 100) * sizeMultiplier;
        int quantity = riskPerShare > 0 ? (int) (riskAmount / riskPerShare) : 0;
        double notional = quantity * price;

        // F&O lot size estimation (simplified)
        int lotSize = estimateLotSize(price);
        int lots = quantity / lotSize;

        return PositionSizing.builder()
            .suggestedQuantity(quantity)
            .notionalValue(notional)
            .riskAmount(riskAmount)
            .lotSize(lotSize)
            .lotsRecommended(Math.max(1, lots))
            .marginRequired(notional * 0.15)  // ~15% margin
            .sizeMultiplier(sizeMultiplier)
            .sizingRationale(String.format("%.1f%% risk with %.2fx multiplier",
                riskPercent, sizeMultiplier))
            .capitalAllocationPct(notional / capital * 100)
            .maxPositionValue(capital * 0.1)  // 10% max per position
            .build();
    }

    /**
     * Estimate lot size based on price
     */
    private int estimateLotSize(double price) {
        if (price > 10000) return 25;
        if (price > 5000) return 50;
        if (price > 2000) return 100;
        if (price > 1000) return 250;
        if (price > 500) return 500;
        return 1000;
    }

    /**
     * Build trailing stop configuration
     */
    private TrailingStopConfig buildTrailingStopConfig(QuantScore score) {
        return TrailingStopConfig.builder()
            .enabled(true)
            .type(TrailingStopConfig.TrailingType.R_MULTIPLE_STAGED)
            .activationR(1.0)
            .stage1R(1.0)
            .stage1StopR(0.0)   // Move to breakeven at 1R
            .stage2R(1.5)
            .stage2StopR(0.5)   // Move to 0.5R profit at 1.5R
            .stage3R(2.0)
            .stage3StopR(1.0)   // Move to 1R profit at 2R
            .build();
    }

    /**
     * Build signal rationale
     */
    private String buildRationale(QuantScore score, SignalType signalType) {
        StringBuilder sb = new StringBuilder();
        sb.append(signalType.name().replace("_", " "));
        sb.append(" signal. ");

        sb.append(String.format("Score: %.1f (%s). ",
            score.getQuantScore(), score.getQuantLabel()));

        sb.append(String.format("%d of 8 categories above threshold. ",
            score.countStrongCategories()));

        if (score.getGreeksSummary() != null && score.getGreeksSummary().isGammaSqueezeRisk()) {
            sb.append("Gamma squeeze proximity detected. ");
        }

        if (score.getIvSummary() != null) {
            sb.append(String.format("IV Rank: %.0f. ", score.getIvSummary().getIvRank()));
        }

        return sb.toString().trim();
    }

    /**
     * Build time constraints
     */
    private TimeConstraints buildTimeConstraints(FamilyCandle family) {
        return TimeConstraints.builder()
            .validUntil(System.currentTimeMillis() + (5 * 60 * 1000))  // 5 minutes validity
            .maxHoldingMinutes(180)  // 3 hour max hold for intraday
            .avoidFirstHour(false)
            .avoidLastHour(true)
            .intradayOnly("1m".equals(family.getTimeframe()) || "5m".equals(family.getTimeframe()))
            .build();
    }
}
