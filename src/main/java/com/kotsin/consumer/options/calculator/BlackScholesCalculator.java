package com.kotsin.consumer.options.calculator;

import com.kotsin.consumer.options.model.OptionGreeks;
import com.kotsin.consumer.options.model.OptionGreeks.MoneynessType;
import com.kotsin.consumer.options.model.OptionGreeks.OptionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * BlackScholesCalculator - Calculates option Greeks using Black-Scholes model.
 *
 * Supports:
 * - Option pricing (theoretical price)
 * - First order Greeks (Delta, Gamma, Theta, Vega, Rho)
 * - Second order Greeks (Vanna, Charm, Vomma)
 * - Implied Volatility calculation (Newton-Raphson)
 */
@Component
@Slf4j
public class BlackScholesCalculator {

    @Value("${options.risk.free.rate:0.07}")
    private double riskFreeRate;  // 7% default for India

    private static final double DAYS_PER_YEAR = 365.0;
    private static final double TRADING_DAYS_PER_YEAR = 252.0;
    private static final int MAX_IV_ITERATIONS = 100;
    private static final double IV_PRECISION = 0.0001;

    /**
     * Calculate all Greeks for an option.
     *
     * @param spot        Current underlying price
     * @param strike      Strike price
     * @param expiry      Expiry date
     * @param optionType  CALL or PUT
     * @param optionPrice Current market price of option
     * @return OptionGreeks with all calculated values
     */
    public OptionGreeks calculateGreeks(
            double spot, double strike, LocalDate expiry,
            OptionType optionType, double optionPrice) {

        return calculateGreeks(spot, strike, expiry, optionType, optionPrice, null);
    }

    /**
     * Calculate all Greeks for an option with known IV.
     */
    public OptionGreeks calculateGreeks(
            double spot, double strike, LocalDate expiry,
            OptionType optionType, double optionPrice, Double knownIV) {

        int daysToExpiry = (int) ChronoUnit.DAYS.between(LocalDate.now(), expiry);
        double t = Math.max(daysToExpiry / DAYS_PER_YEAR, 0.001);  // Time in years

        // Calculate IV if not provided
        double iv = knownIV != null ? knownIV / 100.0 :
            calculateImpliedVolatility(spot, strike, t, riskFreeRate, optionPrice, optionType);

        // Calculate d1 and d2
        double d1 = calculateD1(spot, strike, t, riskFreeRate, iv);
        double d2 = d1 - iv * Math.sqrt(t);

        // Standard normal CDF and PDF
        double nd1 = normalCDF(d1);
        double nd2 = normalCDF(d2);
        double nMinusD1 = normalCDF(-d1);
        double nMinusD2 = normalCDF(-d2);
        double npd1 = normalPDF(d1);

        // Calculate Greeks
        double delta, theta, rho;
        double theoreticalPrice;

        if (optionType == OptionType.CALL) {
            delta = nd1;
            theoreticalPrice = spot * nd1 - strike * Math.exp(-riskFreeRate * t) * nd2;
            theta = (-spot * npd1 * iv / (2 * Math.sqrt(t))
                    - riskFreeRate * strike * Math.exp(-riskFreeRate * t) * nd2) / DAYS_PER_YEAR;
            rho = strike * t * Math.exp(-riskFreeRate * t) * nd2 / 100;
        } else {
            delta = nd1 - 1;  // Negative for puts
            theoreticalPrice = strike * Math.exp(-riskFreeRate * t) * nMinusD2 - spot * nMinusD1;
            theta = (-spot * npd1 * iv / (2 * Math.sqrt(t))
                    + riskFreeRate * strike * Math.exp(-riskFreeRate * t) * nMinusD2) / DAYS_PER_YEAR;
            rho = -strike * t * Math.exp(-riskFreeRate * t) * nMinusD2 / 100;
        }

        // Gamma (same for calls and puts)
        double gamma = npd1 / (spot * iv * Math.sqrt(t));

        // Vega (same for calls and puts, per 1% IV change)
        double vega = spot * npd1 * Math.sqrt(t) / 100;

        // Second order Greeks
        double vanna = -npd1 * d2 / iv;
        double charm = -npd1 * (riskFreeRate / (iv * Math.sqrt(t)) - d2 / (2 * t)) / DAYS_PER_YEAR;
        double vomma = vega * d1 * d2 / iv;

        // Intrinsic and time value
        double intrinsicValue = optionType == OptionType.CALL ?
            Math.max(0, spot - strike) : Math.max(0, strike - spot);
        double timeValue = optionPrice - intrinsicValue;

        // Moneyness
        double moneyness = strike / spot;
        MoneynessType moneynessType = calculateMoneynessType(spot, strike, optionType);

        // Breakeven
        double breakeven = optionType == OptionType.CALL ?
            strike + optionPrice : strike - optionPrice;

        // Leverage
        double leverage = optionPrice > 0 ? Math.abs(delta) * spot / optionPrice : 0;

        return OptionGreeks.builder()
            .strikePrice(strike)
            .optionType(optionType)
            .expiry(expiry)
            .daysToExpiry(daysToExpiry)
            .timestamp(Instant.now())
            .optionPrice(optionPrice)
            .underlyingPrice(spot)
            // First order Greeks
            .delta(delta)
            .gamma(gamma)
            .theta(theta)
            .vega(vega)
            .rho(rho)
            // Second order Greeks
            .vanna(vanna)
            .charm(charm)
            .vomma(vomma)
            // Volatility
            .impliedVolatility(iv * 100)  // Convert to percentage
            // Derived
            .intrinsicValue(intrinsicValue)
            .timeValue(timeValue)
            .moneyness(moneyness)
            .moneynessType(moneynessType)
            .theoreticalPrice(theoreticalPrice)
            .mispricing(optionPrice - theoreticalPrice)
            .breakeven(breakeven)
            .leverage(leverage)
            .build();
    }

    /**
     * Calculate theoretical option price using Black-Scholes.
     */
    public double calculatePrice(double spot, double strike, double timeYears,
                                  double volatility, OptionType optionType) {
        double d1 = calculateD1(spot, strike, timeYears, riskFreeRate, volatility);
        double d2 = d1 - volatility * Math.sqrt(timeYears);

        if (optionType == OptionType.CALL) {
            return spot * normalCDF(d1) - strike * Math.exp(-riskFreeRate * timeYears) * normalCDF(d2);
        } else {
            return strike * Math.exp(-riskFreeRate * timeYears) * normalCDF(-d2) - spot * normalCDF(-d1);
        }
    }

    /**
     * Calculate Implied Volatility using Newton-Raphson method.
     */
    public double calculateImpliedVolatility(double spot, double strike, double timeYears,
                                              double rate, double marketPrice, OptionType optionType) {
        if (marketPrice <= 0 || timeYears <= 0) {
            return 0.20;  // Default 20% IV
        }

        // Initial guess based on approximate formula
        double iv = Math.sqrt(2 * Math.PI / timeYears) * marketPrice / spot;
        iv = Math.max(0.01, Math.min(iv, 5.0));  // Bound between 1% and 500%

        for (int i = 0; i < MAX_IV_ITERATIONS; i++) {
            double price = calculatePrice(spot, strike, timeYears, iv, optionType);
            double vega = calculateVega(spot, strike, timeYears, iv);

            if (Math.abs(vega) < 1e-10) break;

            double diff = marketPrice - price;
            if (Math.abs(diff) < IV_PRECISION) break;

            iv = iv + diff / (vega * 100);  // Vega is per 1% change
            iv = Math.max(0.01, Math.min(iv, 5.0));
        }

        return iv;
    }

    /**
     * Calculate just Vega for IV calculation.
     */
    private double calculateVega(double spot, double strike, double timeYears, double volatility) {
        double d1 = calculateD1(spot, strike, timeYears, riskFreeRate, volatility);
        return spot * normalPDF(d1) * Math.sqrt(timeYears) / 100;
    }

    /**
     * Calculate d1 parameter.
     */
    private double calculateD1(double spot, double strike, double timeYears, double rate, double volatility) {
        return (Math.log(spot / strike) + (rate + volatility * volatility / 2) * timeYears)
               / (volatility * Math.sqrt(timeYears));
    }

    /**
     * Standard Normal CDF (Cumulative Distribution Function).
     * Uses Abramowitz and Stegun approximation.
     */
    private double normalCDF(double x) {
        if (x > 6) return 1.0;
        if (x < -6) return 0.0;

        double b1 = 0.319381530;
        double b2 = -0.356563782;
        double b3 = 1.781477937;
        double b4 = -1.821255978;
        double b5 = 1.330274429;
        double p = 0.2316419;
        double c = 0.39894228;

        double a = Math.abs(x);
        double t = 1.0 / (1.0 + a * p);
        double b = c * Math.exp(-x * x / 2.0);
        double n = ((((b5 * t + b4) * t + b3) * t + b2) * t + b1) * t;
        n = 1.0 - b * n;

        return x < 0 ? 1.0 - n : n;
    }

    /**
     * Standard Normal PDF (Probability Density Function).
     */
    private double normalPDF(double x) {
        return Math.exp(-x * x / 2) / Math.sqrt(2 * Math.PI);
    }

    /**
     * Determine moneyness type.
     */
    private MoneynessType calculateMoneynessType(double spot, double strike, OptionType optionType) {
        double diff = (spot - strike) / spot;

        if (optionType == OptionType.PUT) {
            diff = -diff;  // Invert for puts
        }

        if (diff > 0.05) return MoneynessType.DEEP_ITM;
        if (diff > 0) return MoneynessType.ITM;
        if (diff > -0.01) return MoneynessType.ATM;
        if (diff > -0.05) return MoneynessType.OTM;
        return MoneynessType.DEEP_OTM;
    }

    /**
     * Calculate Put-Call Parity implied price.
     * Call - Put = Spot - Strike * e^(-rt)
     */
    public double calculatePutCallParity(double callPrice, double putPrice, double spot,
                                          double strike, double timeYears) {
        double discountedStrike = strike * Math.exp(-riskFreeRate * timeYears);
        double theoreticalDiff = spot - discountedStrike;
        double actualDiff = callPrice - putPrice;
        return actualDiff - theoreticalDiff;  // Parity violation
    }
}
