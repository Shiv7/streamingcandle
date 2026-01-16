package com.kotsin.consumer.util;

import lombok.extern.slf4j.Slf4j;

/**
 * Black-Scholes Option Greeks Calculator
 * 
 * Calculates Delta, Gamma, Vega, and Theta for European options
 * 
 * Formulas based on:
 * - Black-Scholes-Merton model
 * - Standard greeks formulas from Hull (Options, Futures, and Other Derivatives)
 * 
 * Assumptions:
 * - European options (exercise only at expiry)
 * - Constant volatility
 * - Risk-free rate: 6% (India 10-year bond yield approximation)
 */
@Slf4j
public class BlackScholesGreeks {
    
    // Risk-free rate for India (approximate 10-year government bond yield)
    private static final double RISK_FREE_RATE = 0.06; // 6% per annum
    
    // Minimum time to expiry (1 hour) to avoid division by zero
    private static final double MIN_TIME_TO_EXPIRY_YEARS = 1.0 / (365.0 * 24.0); // ~0.000114 years
    
    /**
     * Calculate all option greeks
     * 
     * @param underlyingPrice Current price of underlying asset
     * @param strikePrice Strike price of option
     * @param timeToExpiryYears Time to expiry in years
     * @param volatility Annualized volatility (0.0 to 1.0, e.g., 0.25 = 25%)
     * @param isCall true for call option, false for put option
     * @return GreeksResult with delta, gamma, vega, theta
     */
    public static GreeksResult calculateGreeks(
            double underlyingPrice,
            double strikePrice,
            double timeToExpiryYears,
            double volatility,
            boolean isCall) {
        
        // Input validation
        if (underlyingPrice <= 0 || strikePrice <= 0) {
            log.warn("Invalid prices: S={}, K={}", underlyingPrice, strikePrice);
            return GreeksResult.zero();
        }
        
        if (timeToExpiryYears < MIN_TIME_TO_EXPIRY_YEARS) {
            // Option expiring very soon - use minimum time to avoid numerical issues
            timeToExpiryYears = MIN_TIME_TO_EXPIRY_YEARS;
        }
        
        // FIX: Changed volatility < 0 to volatility <= 0 to catch zero volatility
        // Zero volatility causes division by zero in gamma calculation
        if (volatility <= 0 || volatility > 2.0) {
            // Unreasonable volatility - use 30% as default
            log.warn("Invalid volatility: {}, using 0.30", volatility);
            volatility = 0.30;
        }
        
        // Calculate d1 and d2 (standard Black-Scholes parameters)
        double d1 = calculateD1(underlyingPrice, strikePrice, timeToExpiryYears, volatility);
        double d2 = d1 - volatility * Math.sqrt(timeToExpiryYears);
        
        // Calculate cumulative standard normal distribution
        double N_d1 = cumulativeNormalDistribution(d1);
        double N_d2 = cumulativeNormalDistribution(d2);
        double N_neg_d1 = cumulativeNormalDistribution(-d1);
        double N_neg_d2 = cumulativeNormalDistribution(-d2);
        
        // Calculate standard normal PDF
        double phi_d1 = normalPDF(d1);
        
        // Calculate Greeks
        double delta = isCall ? N_d1 : (N_d1 - 1.0);
        double gamma = phi_d1 / (underlyingPrice * volatility * Math.sqrt(timeToExpiryYears));
        double vega = underlyingPrice * phi_d1 * Math.sqrt(timeToExpiryYears) / 100.0; // Per 1% vol change
        double theta = calculateTheta(underlyingPrice, strikePrice, timeToExpiryYears, volatility, 
                                     d1, d2, phi_d1, N_d2, N_neg_d2, isCall);
        
        return new GreeksResult(delta, gamma, vega, theta);
    }
    
    /**
     * Calculate d1 parameter for Black-Scholes
     */
    private static double calculateD1(double S, double K, double T, double sigma) {
        if (T <= 0 || sigma <= 0) {
            return 0.0;
        }
        double numerator = Math.log(S / K) + (RISK_FREE_RATE + 0.5 * sigma * sigma) * T;
        double denominator = sigma * Math.sqrt(T);
        return numerator / denominator;
    }
    
    /**
     * Calculate Theta (time decay)
     * Theta is negative for long positions (options lose value over time)
     */
    private static double calculateTheta(double S, double K, double T, double sigma,
                                        double d1, double d2, double phi_d1,
                                        double N_d2, double N_neg_d2, boolean isCall) {
        if (T <= 0 || sigma <= 0) {
            return 0.0;
        }
        
        double term1 = -(S * phi_d1 * sigma) / (2.0 * Math.sqrt(T));
        double term2 = -RISK_FREE_RATE * K * Math.exp(-RISK_FREE_RATE * T);
        
        if (isCall) {
            term2 *= N_d2;
        } else {
            term2 *= N_neg_d2;
        }
        
        // Theta per day (divide by 365)
        return (term1 + term2) / 365.0;
    }
    
    /**
     * Cumulative standard normal distribution (approximation)
     * Uses Abramowitz and Stegun approximation
     */
    private static double cumulativeNormalDistribution(double x) {
        // Constants for approximation
        double a1 = 0.254829592;
        double a2 = -0.284496736;
        double a3 = 1.421413741;
        double a4 = -1.453152027;
        double a5 = 1.061405429;
        double p = 0.3275911;
        
        // Save sign of x
        int sign = 1;
        if (x < 0) {
            sign = -1;
            x = -x;
        }
        
        // A&S formula 7.1.26
        double t = 1.0 / (1.0 + p * x);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);
        
        return 0.5 * (1.0 + sign * y);
    }
    
    /**
     * Standard normal probability density function
     */
    private static double normalPDF(double x) {
        return (1.0 / Math.sqrt(2.0 * Math.PI)) * Math.exp(-0.5 * x * x);
    }
    
    /**
     * Estimate implied volatility from option price (simplified)
     * Uses iterative approximation (Newton-Raphson method)
     * 
     * @param optionPrice Current market price of option
     * @param underlyingPrice Current price of underlying
     * @param strikePrice Strike price
     * @param timeToExpiryYears Time to expiry in years
     * @param isCall true for call, false for put
     * @return Estimated implied volatility (0.0 to 1.0)
     */
    public static double estimateImpliedVolatility(
            double optionPrice,
            double underlyingPrice,
            double strikePrice,
            double timeToExpiryYears,
            boolean isCall) {
        
        if (optionPrice <= 0 || underlyingPrice <= 0 || strikePrice <= 0 || timeToExpiryYears <= 0) {
            return 0.30; // Default 30% volatility
        }
        
        // Initial guess: 30% volatility
        double volatility = 0.30;
        double tolerance = 0.001; // 0.1% accuracy
        int maxIterations = 50;
        
        for (int i = 0; i < maxIterations; i++) {
            double theoreticalPrice = blackScholesPrice(underlyingPrice, strikePrice, 
                                                       timeToExpiryYears, volatility, isCall);
            double error = theoreticalPrice - optionPrice;
            
            if (Math.abs(error) < tolerance) {
                break;
            }
            
            // Vega (sensitivity to volatility) for Newton-Raphson
            double vega = underlyingPrice * normalPDF(calculateD1(underlyingPrice, strikePrice, 
                                                                  timeToExpiryYears, volatility)) 
                         * Math.sqrt(timeToExpiryYears) / 100.0;
            
            if (Math.abs(vega) < 1e-10) {
                break; // Avoid division by zero
            }
            
            // Update volatility
            volatility = Math.max(0.01, Math.min(2.0, volatility - error / vega));
        }
        
        return volatility;
    }
    
    /**
     * Black-Scholes option price
     */
    private static double blackScholesPrice(double S, double K, double T, double sigma, boolean isCall) {
        if (T <= 0 || sigma <= 0) {
            return Math.max(0, isCall ? (S - K) : (K - S));
        }
        
        double d1 = calculateD1(S, K, T, sigma);
        double d2 = d1 - sigma * Math.sqrt(T);
        
        double N_d1 = cumulativeNormalDistribution(d1);
        double N_d2 = cumulativeNormalDistribution(d2);
        double N_neg_d1 = cumulativeNormalDistribution(-d1);
        double N_neg_d2 = cumulativeNormalDistribution(-d2);
        
        if (isCall) {
            return S * N_d1 - K * Math.exp(-RISK_FREE_RATE * T) * N_d2;
        } else {
            return K * Math.exp(-RISK_FREE_RATE * T) * N_neg_d2 - S * N_neg_d1;
        }
    }
    
    /**
     * Result container for option greeks
     */
    public static class GreeksResult {
        public final double delta;
        public final double gamma;
        public final double vega;
        public final double theta;
        
        public GreeksResult(double delta, double gamma, double vega, double theta) {
            this.delta = delta;
            this.gamma = gamma;
            this.vega = vega;
            this.theta = theta;
        }
        
        public static GreeksResult zero() {
            return new GreeksResult(0.0, 0.0, 0.0, 0.0);
        }
    }
}

