package com.kotsin.consumer.calculator;

import com.kotsin.consumer.config.InstrumentConfig;
import com.kotsin.consumer.model.TickData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * TradeClassifier - Classifies trades as buy or sell using quote-rule and tick-rule
 *
 * EXTRACTED FROM: EnrichedCandlestick (SRP violation fix)
 *
 * Algorithm:
 * 1. Quote Rule: If trade is at ask → BUY, if at bid → SELL
 * 2. Tick Rule: If price > lastPrice → BUY, if price < lastPrice → SELL
 * 3. Default: SELL (to avoid buy inflation)
 *
 * Thread-safety: Stateless component, thread-safe
 */
@Component
@Slf4j
public class TradeClassifier {

    private final InstrumentConfig instrumentConfig;

    @Autowired
    public TradeClassifier(InstrumentConfig instrumentConfig) {
        this.instrumentConfig = instrumentConfig;
    }

    /**
     * Classify trade as buy (true) or sell (false)
     *
     * @param tick Current tick data
     * @param currentPrice Current trade price
     * @param lastPrice Previous trade price (null if first trade)
     * @param instrumentTickSize Instrument-specific tick size (0 = use default)
     * @return true if BUY, false if SELL
     */
    public boolean classifyTrade(TickData tick, double currentPrice, Double lastPrice, double instrumentTickSize) {
        double bidPrice = tick.getBidRate();
        double askPrice = tick.getOfferRate();

        double threshold = getClassificationThreshold(currentPrice, tick, instrumentTickSize);

        // Quote rule: compare to bid/ask
        if (bidPrice > 0 && askPrice > 0) {
            if (Math.abs(currentPrice - askPrice) <= threshold) {
                return true;  // At ask = buy
            } else if (Math.abs(currentPrice - bidPrice) <= threshold) {
                return false;  // At bid = sell
            }
        }

        // Tick rule: compare to last price
        if (lastPrice != null && Math.abs(currentPrice - lastPrice) > threshold) {
            return currentPrice > lastPrice;
        }

        // Default: sell (avoid buy inflation)
        return false;
    }

    /**
     * Calculate classification threshold based on tick size, spread, and BPS
     *
     * @param currentPrice Current trade price
     * @param tick Tick data with bid/ask
     * @param instrumentTickSize Instrument-specific tick size
     * @return Threshold for price comparison
     */
    private double getClassificationThreshold(double currentPrice, TickData tick, double instrumentTickSize) {
        InstrumentConfig.TradeClassification config = instrumentConfig.getTradeClassification();

        // Base threshold: max of minAbsolute and BPS-based
        double base = Math.max(config.getMinAbsolute(), Math.abs(currentPrice) * config.getBasisPoints());

        // Spread term: fraction of bid-ask spread
        double spreadTerm = 0.0;
        if (tick != null && tick.getOfferRate() > 0 && tick.getBidRate() > 0) {
            double spread = Math.max(0.0, tick.getOfferRate() - tick.getBidRate());
            spreadTerm = config.getSpreadMultiplier() * spread;
        }

        // Tick size term
        double tickTerm = getEffectiveTickSize(tick != null ? tick.getExchangeType() : null, instrumentTickSize);

        return Math.max(base, Math.max(spreadTerm, tickTerm));
    }

    /**
     * Get effective tick size for instrument
     *
     * @param exchangeType Exchange type (C=Cash, D=Derivatives)
     * @param instrumentTickSize Instrument-specific tick size
     * @return Effective tick size
     */
    private double getEffectiveTickSize(String exchangeType, double instrumentTickSize) {
        if (instrumentTickSize > 0) {
            return instrumentTickSize;  // Use instrument-specific if available
        }

        // Fallback to configured defaults
        if ("D".equalsIgnoreCase(exchangeType) || "F".equalsIgnoreCase(exchangeType) || "O".equalsIgnoreCase(exchangeType)) {
            return instrumentConfig.getTickSize().getDerivatives();
        }

        return instrumentConfig.getTickSize().getCash();
    }

    /**
     * Check if price change is significant (above threshold)
     *
     * @param price1 First price
     * @param price2 Second price
     * @param exchangeType Exchange type
     * @param instrumentTickSize Instrument-specific tick size
     * @return true if change is significant
     */
    public boolean isPriceChangeSignificant(double price1, double price2, String exchangeType, double instrumentTickSize) {
        double threshold = getEffectiveTickSize(exchangeType, instrumentTickSize);
        return Math.abs(price1 - price2) >= threshold;
    }
}
