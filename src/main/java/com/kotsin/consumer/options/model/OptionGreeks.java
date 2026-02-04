package com.kotsin.consumer.options.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * OptionGreeks - Greek values for an option.
 *
 * Contains:
 * - First Order Greeks: Delta, Gamma, Theta, Vega, Rho
 * - Second Order Greeks: Vanna, Charm, Vomma
 * - Derived metrics: IV, Moneyness, Time Value
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionGreeks {

    private String symbol;
    private String underlyingSymbol;
    private String scripCode;
    private double strikePrice;
    private OptionType optionType;
    private LocalDate expiry;
    private int daysToExpiry;

    private Instant timestamp;

    // ==================== PRICES ====================
    private double optionPrice;      // Current option price (LTP)
    private double underlyingPrice;  // Current underlying price
    private double bidPrice;
    private double askPrice;

    // ==================== FIRST ORDER GREEKS ====================
    private double delta;   // Price sensitivity to underlying (CE: 0-1, PE: -1-0)
    private double gamma;   // Delta sensitivity to underlying
    private double theta;   // Time decay (negative, per day)
    private double vega;    // Price sensitivity to volatility
    private double rho;     // Price sensitivity to interest rate

    // ==================== SECOND ORDER GREEKS ====================
    private double vanna;   // Delta sensitivity to volatility
    private double charm;   // Delta decay over time
    private double vomma;   // Vega sensitivity to volatility

    // ==================== VOLATILITY ====================
    private double impliedVolatility;  // IV in percentage (e.g., 20 for 20%)
    private double historicalVolatility;
    private double ivPercentile;       // IV rank (0-100)
    private double ivSkew;             // IV difference from ATM

    // ==================== DERIVED METRICS ====================
    private double intrinsicValue;
    private double timeValue;
    private double moneyness;         // Strike / Underlying
    private MoneynessType moneynessType;

    private double theoreticalPrice;  // BS calculated price
    private double mispricing;        // Market price - Theoretical price

    // ==================== RISK METRICS ====================
    private double maxPain;           // Option chain max pain
    private double breakeven;         // For CE: Strike + Premium, PE: Strike - Premium
    private double leverage;          // Delta * (Underlying / Option Price)

    // ==================== ENUMS ====================

    public enum OptionType {
        CALL, PUT
    }

    public enum MoneynessType {
        DEEP_ITM,    // > 5% ITM
        ITM,         // 0-5% ITM
        ATM,         // Within 1% of strike
        OTM,         // 0-5% OTM
        DEEP_OTM     // > 5% OTM
    }

    // ==================== HELPER METHODS ====================

    public boolean isCall() {
        return optionType == OptionType.CALL;
    }

    public boolean isPut() {
        return optionType == OptionType.PUT;
    }

    public boolean isITM() {
        if (isCall()) {
            return underlyingPrice > strikePrice;
        } else {
            return underlyingPrice < strikePrice;
        }
    }

    public boolean isATM() {
        double diff = Math.abs(underlyingPrice - strikePrice) / underlyingPrice;
        return diff < 0.01;  // Within 1%
    }

    public boolean isOTM() {
        return !isITM() && !isATM();
    }

    public double getAbsDelta() {
        return Math.abs(delta);
    }

    public boolean isHighGamma() {
        return gamma > 0.05;  // High gamma near ATM
    }

    public boolean isDecaying() {
        return theta < -0.5;  // Losing more than 0.5 per day
    }
}
