package com.kotsin.consumer.service;

import com.kotsin.consumer.domain.model.OptionCandle;
import com.kotsin.consumer.model.IVSurface;
import com.kotsin.consumer.util.BlackScholesGreeks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * IVSurfaceCalculator - Calculates Implied Volatility Surface analytics.
 *
 * Provides comprehensive volatility analysis:
 * - Smile curve (IV by strike)
 * - Skew metrics (put vs call IV)
 * - Term structure (IV by expiry)
 * - IV dynamics (rank, velocity, crush risk)
 *
 * INSTITUTIONAL GRADE: Options strategy selection requires understanding
 * the full volatility surface, not just ATM IV.
 */
@Service
@Slf4j
public class IVSurfaceCalculator {

    // Configurable thresholds
    private static final double IV_ELEVATED_RANK = 70.0;       // IV rank > 70 = sell vol
    private static final double IV_DEPRESSED_RANK = 30.0;      // IV rank < 30 = buy vol
    private static final double EXTREME_SKEW_THRESHOLD = 5.0;  // 5% skew is extreme
    private static final int NEAR_TERM_DTE = 7;                // Days to expiry for near-term
    private static final double IV_CRUSH_DTE = 3;              // DTE threshold for crush risk
    private static final double IV_CRUSH_RANK = 60.0;          // IV rank threshold for crush

    /**
     * Calculate IV surface from list of option candles
     *
     * @param options List of OptionCandle in the family
     * @param spotPrice Current spot price for ATM determination
     * @param historicalIVRank Optional historical IV rank (0-100), pass null if unavailable
     * @return IVSurface with comprehensive volatility analytics
     */
    public IVSurface calculate(List<OptionCandle> options, double spotPrice, Double historicalIVRank) {
        if (options == null || options.isEmpty()) {
            return IVSurface.empty();
        }

        // 🔴 CRITICAL FIX: Estimate spotPrice from ATM options if not provided
        // This fixes IV Surface returning empty when family has options but no equity/future
        if (spotPrice <= 0) {
            spotPrice = estimateSpotPriceFromOptions(options);
            if (spotPrice > 0) {
                log.info("[IV-FIX] Estimated spotPrice from ATM options: {}", spotPrice);
            } else {
                log.warn("[IV-FIX] Could not estimate spotPrice - returning empty IV Surface");
                return IVSurface.empty();
            }
        }

        // Separate calls and puts
        List<OptionCandle> calls = options.stream()
            .filter(o -> o != null && o.isCall())
            .collect(Collectors.toList());

        List<OptionCandle> puts = options.stream()
            .filter(o -> o != null && !o.isCall())
            .collect(Collectors.toList());

        if (calls.isEmpty() && puts.isEmpty()) {
            return IVSurface.empty();
        }

        // Build IV by strike maps (FIX: pass spotPrice for IV calculation when missing)
        Map<Double, Double> callIVByStrike = buildIVByStrike(calls, spotPrice);
        Map<Double, Double> putIVByStrike = buildIVByStrike(puts, spotPrice);

        // Calculate ATM IV
        double atmCallIV = findATMIV(calls, spotPrice);
        double atmPutIV = findATMIV(puts, spotPrice);
        double atmIV = calculateATMIV(atmCallIV, atmPutIV);

        // Calculate smile metrics
        double smileSlope = calculateSmileSlope(callIVByStrike, putIVByStrike, spotPrice);
        double smileCurvature = calculateSmileCurvature(callIVByStrike, putIVByStrike, spotPrice);
        IVSurface.SmileShape smileShape = determineSmileShape(smileSlope, smileCurvature, atmCallIV, atmPutIV);

        // Calculate skew metrics
        double skew25Delta = calculateDeltaSkew(calls, puts, 0.25);
        double skew10Delta = calculateDeltaSkew(calls, puts, 0.10);
        double riskReversal = -skew25Delta; // Risk reversal is opposite of skew
        double butterflySpread = calculateButterflySpread(calls, puts, atmIV);

        // Build term structure
        Map<String, Double> ivByExpiry = buildIVByExpiry(options, spotPrice);
        double termSlope = calculateTermSlope(ivByExpiry);
        IVSurface.TermStructure termStructure = determineTermStructure(termSlope);

        // Calculate near/far term IV
        Double nearTermIV = findNearTermIV(options, spotPrice);
        Double farTermIV = findFarTermIV(options, spotPrice);

        // IV dynamics
        double ivRank = historicalIVRank != null ? historicalIVRank : estimateIVRank(atmIV);
        Double ivPercentile = historicalIVRank; // Same as rank if provided
        double ivChange1m = calculateIVChange(options, 1);
        double ivChange5m = calculateIVChange(options, 5);
        double ivVelocity = calculateIVVelocity(ivChange1m, ivChange5m);

        // IV crush risk detection
        boolean ivCrushRisk = detectIVCrushRisk(options, ivRank);

        // Historical IV premium
        Double historicalIV = estimateHistoricalIV(atmIV, ivRank);
        Double ivPremium = historicalIV != null ? atmIV - historicalIV : null;

        // Determine signal
        IVSurface.IVSignal ivSignal = determineIVSignal(ivRank, ivCrushRisk, ivVelocity);
        double ivSignalStrength = calculateSignalStrength(ivRank, ivVelocity, ivCrushRisk);

        return IVSurface.builder()
            // Smile curve
            .callIVByStrike(callIVByStrike)
            .putIVByStrike(putIVByStrike)
            .atmIV(atmIV)
            .smileSlope(smileSlope)
            .smileCurvature(smileCurvature)
            .smileShape(smileShape)

            // Skew metrics
            .skew25Delta(skew25Delta)
            .skew10Delta(skew10Delta)
            .riskReversal(riskReversal)
            .butterflySpread(butterflySpread)
            .atmCallIV(atmCallIV > 0 ? atmCallIV : null)
            .atmPutIV(atmPutIV > 0 ? atmPutIV : null)

            // Term structure
            .ivByExpiry(ivByExpiry)
            .termSlope(termSlope)
            .termStructure(termStructure)
            .nearTermIV(nearTermIV)
            .farTermIV(farTermIV)

            // IV dynamics
            .ivRank(ivRank)
            .ivPercentile(ivPercentile)
            .ivChange1m(ivChange1m)
            .ivChange5m(ivChange5m)
            .ivVelocity(ivVelocity)
            .ivCrushRisk(ivCrushRisk)
            .historicalIV(historicalIV)
            .ivPremium(ivPremium)

            // Signals
            .ivSignal(ivSignal)
            .ivSignalStrength(ivSignalStrength)

            .build();
    }

    /**
     * Build IV by strike map from options list
     * FIX: Calculate IV using Newton-Raphson if not present, using correct spot price
     */
    private Map<Double, Double> buildIVByStrike(List<OptionCandle> options, double spotPrice) {
        Map<Double, Double> ivByStrike = new HashMap<>();
        for (OptionCandle opt : options) {
            if (opt == null) continue;
            Double iv = opt.getImpliedVolatility();
            double strike = opt.getStrikePrice();

            // FIX: Calculate IV if not present using Newton-Raphson method
            if ((iv == null || iv <= 0) && spotPrice > 0 && strike > 0 && opt.getExpiry() != null) {
                try {
                    int dte = estimateDTE(opt.getExpiry());
                    if (dte > 0) {
                        double optionPrice = opt.getClose();
                        double timeToExpiryYears = dte / 365.0;
                        boolean isCall = opt.isCall();
                        iv = BlackScholesGreeks.estimateImpliedVolatility(
                            optionPrice, spotPrice, strike, timeToExpiryYears, isCall
                        );
                    }
                } catch (Exception e) {
                    log.debug("Failed to calculate IV for strike {}: {}", strike, e.getMessage());
                }
            }

            if (iv != null && iv > 0) {
                // If multiple options at same strike, average the IV
                ivByStrike.merge(strike, iv, (old, newIv) -> (old + newIv) / 2);
            }
        }
        return ivByStrike;
    }

    /**
     * Find ATM IV for a list of options
     * FIX: Calculate IV if not present using Newton-Raphson method
     */
    private double findATMIV(List<OptionCandle> options, double spotPrice) {
        if (options.isEmpty()) return 0.0;

        // Find option closest to ATM
        OptionCandle atmOption = options.stream()
            .min(Comparator.comparingDouble(o -> Math.abs(o.getStrikePrice() - spotPrice)))
            .orElse(null);

        if (atmOption == null) return 0.0;

        Double iv = atmOption.getImpliedVolatility();

        // FIX: Calculate IV if not present
        if ((iv == null || iv <= 0) && atmOption.getExpiry() != null && atmOption.getStrikePrice() > 0) {
            try {
                int dte = estimateDTE(atmOption.getExpiry());
                if (dte > 0) {
                    double timeToExpiryYears = dte / 365.0;
                    iv = BlackScholesGreeks.estimateImpliedVolatility(
                        atmOption.getClose(), spotPrice, atmOption.getStrikePrice(),
                        timeToExpiryYears, atmOption.isCall()
                    );
                }
            } catch (Exception e) {
                log.debug("Failed to calculate ATM IV: {}", e.getMessage());
            }
        }

        return iv != null && iv > 0 ? iv : 0.0;
    }

    /**
     * Calculate combined ATM IV from call and put ATM IVs
     */
    private double calculateATMIV(double atmCallIV, double atmPutIV) {
        if (atmCallIV > 0 && atmPutIV > 0) {
            return (atmCallIV + atmPutIV) / 2;
        } else if (atmCallIV > 0) {
            return atmCallIV;
        } else if (atmPutIV > 0) {
            return atmPutIV;
        }
        return 0.0;
    }

    /**
     * Calculate smile slope (rate of IV change per unit of moneyness)
     */
    private double calculateSmileSlope(Map<Double, Double> callIV, Map<Double, Double> putIV,
                                        double spotPrice) {
        // Combine all IVs
        Map<Double, Double> allIV = new HashMap<>();
        allIV.putAll(callIV);
        putIV.forEach((strike, iv) -> allIV.merge(strike, iv, (old, newIv) -> (old + newIv) / 2));

        if (allIV.size() < 2) return 0.0;

        // Calculate moneyness and fit linear regression
        List<double[]> points = new ArrayList<>();
        for (Map.Entry<Double, Double> entry : allIV.entrySet()) {
            double moneyness = entry.getKey() / spotPrice - 1.0; // % distance from ATM
            double iv = entry.getValue();
            points.add(new double[]{moneyness, iv});
        }

        return linearRegressionSlope(points);
    }

    /**
     * Calculate smile curvature (second derivative of IV smile)
     */
    private double calculateSmileCurvature(Map<Double, Double> callIV, Map<Double, Double> putIV,
                                            double spotPrice) {
        // Need at least 3 points for curvature
        Map<Double, Double> allIV = new HashMap<>();
        allIV.putAll(callIV);
        putIV.forEach((strike, iv) -> allIV.merge(strike, iv, (old, newIv) -> (old + newIv) / 2));

        if (allIV.size() < 3) return 0.0;

        // Sort by strike and calculate second differences
        List<Map.Entry<Double, Double>> sorted = allIV.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .collect(Collectors.toList());

        double curvatureSum = 0.0;
        int count = 0;

        for (int i = 1; i < sorted.size() - 1; i++) {
            double ivLeft = sorted.get(i - 1).getValue();
            double ivMid = sorted.get(i).getValue();
            double ivRight = sorted.get(i + 1).getValue();

            // Second difference (approximation of second derivative)
            double curvature = ivLeft - 2 * ivMid + ivRight;
            curvatureSum += curvature;
            count++;
        }

        return count > 0 ? curvatureSum / count : 0.0;
    }

    /**
     * Determine smile shape based on slope and curvature
     */
    private IVSurface.SmileShape determineSmileShape(double slope, double curvature,
                                                      double atmCallIV, double atmPutIV) {
        // Check for put/call skew
        if (atmPutIV > 0 && atmCallIV > 0) {
            double skew = atmPutIV - atmCallIV;
            if (skew > 2.0) { // Put IV significantly higher
                return IVSurface.SmileShape.STEEP_PUT;
            } else if (skew < -2.0) { // Call IV significantly higher
                return IVSurface.SmileShape.STEEP_CALL;
            }
        }

        // Check curvature
        if (Math.abs(curvature) < 0.01) {
            return IVSurface.SmileShape.FLAT;
        } else if (curvature > 0.02) {
            return IVSurface.SmileShape.NORMAL; // Classic smile
        } else if (slope < -0.1) {
            return IVSurface.SmileShape.SMIRK; // Asymmetric (typical equity)
        }

        return IVSurface.SmileShape.NORMAL;
    }

    /**
     * Calculate delta-based skew
     * Skew = IV(delta put) - IV(delta call)
     */
    private double calculateDeltaSkew(List<OptionCandle> calls, List<OptionCandle> puts,
                                       double targetDelta) {
        // Find call with delta closest to target
        Double callIV = findIVByDelta(calls, targetDelta);
        // Find put with delta closest to -target (puts have negative delta)
        Double putIV = findIVByDelta(puts, -targetDelta);

        if (callIV != null && putIV != null) {
            return putIV - callIV;
        }
        return 0.0;
    }

    /**
     * Find IV for option with delta closest to target
     */
    private Double findIVByDelta(List<OptionCandle> options, double targetDelta) {
        return options.stream()
            .filter(o -> o.getDelta() != null && o.getImpliedVolatility() != null && o.getImpliedVolatility() > 0)
            .min(Comparator.comparingDouble(o -> Math.abs(o.getDelta() - targetDelta)))
            .map(OptionCandle::getImpliedVolatility)
            .orElse(null);
    }

    /**
     * Calculate butterfly spread = 0.5 × (25Δ call IV + 25Δ put IV) - ATM IV
     */
    private double calculateButterflySpread(List<OptionCandle> calls, List<OptionCandle> puts,
                                             double atmIV) {
        Double call25D = findIVByDelta(calls, 0.25);
        Double put25D = findIVByDelta(puts, -0.25);

        if (call25D != null && put25D != null && atmIV > 0) {
            return 0.5 * (call25D + put25D) - atmIV;
        }
        return 0.0;
    }

    /**
     * Build IV term structure (ATM IV by expiry)
     */
    private Map<String, Double> buildIVByExpiry(List<OptionCandle> options, double spotPrice) {
        // Group by expiry
        Map<String, List<OptionCandle>> byExpiry = options.stream()
            .filter(o -> o != null && o.getExpiry() != null)
            .collect(Collectors.groupingBy(OptionCandle::getExpiry));

        Map<String, Double> ivByExpiry = new HashMap<>();

        for (Map.Entry<String, List<OptionCandle>> entry : byExpiry.entrySet()) {
            String expiry = entry.getKey();
            List<OptionCandle> expiryOptions = entry.getValue();

            // Find ATM IV for this expiry
            double atmIV = expiryOptions.stream()
                .filter(o -> o.getImpliedVolatility() != null && o.getImpliedVolatility() > 0)
                .min(Comparator.comparingDouble(o -> Math.abs(o.getStrikePrice() - spotPrice)))
                .map(OptionCandle::getImpliedVolatility)
                .orElse(0.0);

            if (atmIV > 0) {
                ivByExpiry.put(expiry, atmIV);
            }
        }

        return ivByExpiry;
    }

    /**
     * Calculate term structure slope (far IV - near IV)
     */
    private double calculateTermSlope(Map<String, Double> ivByExpiry) {
        if (ivByExpiry.size() < 2) return 0.0;

        // Sort by expiry date
        List<Map.Entry<String, Double>> sorted = ivByExpiry.entrySet().stream()
            .sorted(Comparator.comparing(e -> parseExpiry(e.getKey())))
            .collect(Collectors.toList());

        if (sorted.size() < 2) return 0.0;

        double nearIV = sorted.get(0).getValue();
        double farIV = sorted.get(sorted.size() - 1).getValue();

        return farIV - nearIV;
    }

    /**
     * Determine term structure type
     */
    private IVSurface.TermStructure determineTermStructure(double termSlope) {
        if (termSlope > 1.0) {
            return IVSurface.TermStructure.CONTANGO;
        } else if (termSlope < -1.0) {
            return IVSurface.TermStructure.BACKWARDATION;
        }
        return IVSurface.TermStructure.FLAT;
    }

    /**
     * Find near-term ATM IV (options expiring within 7 days)
     */
    private Double findNearTermIV(List<OptionCandle> options, double spotPrice) {
        return options.stream()
            .filter(o -> o != null && o.getExpiry() != null && o.getImpliedVolatility() != null && o.getImpliedVolatility() > 0)
            .filter(o -> estimateDTE(o.getExpiry()) <= NEAR_TERM_DTE)
            .min(Comparator.comparingDouble(o -> Math.abs(o.getStrikePrice() - spotPrice)))
            .map(OptionCandle::getImpliedVolatility)
            .orElse(null);
    }

    /**
     * Find far-term ATM IV (options expiring beyond 30 days)
     */
    private Double findFarTermIV(List<OptionCandle> options, double spotPrice) {
        return options.stream()
            .filter(o -> o != null && o.getExpiry() != null && o.getImpliedVolatility() != null && o.getImpliedVolatility() > 0)
            .filter(o -> estimateDTE(o.getExpiry()) > 30)
            .min(Comparator.comparingDouble(o -> Math.abs(o.getStrikePrice() - spotPrice)))
            .map(OptionCandle::getImpliedVolatility)
            .orElse(null);
    }

    /**
     * Estimate IV rank when historical data is unavailable
     * Uses heuristic based on absolute IV level
     */
    private double estimateIVRank(double atmIV) {
        // Heuristic: typical equity IV ranges 10-50%
        // Scale linearly, capped at 0-100
        if (atmIV <= 0) return 50.0; // Default to neutral

        double rank = (atmIV - 10) / (50 - 10) * 100;
        return Math.max(0, Math.min(100, rank));
    }

    /**
     * Calculate IV change over time period
     * Note: In real implementation, would need historical IV data
     */
    private double calculateIVChange(List<OptionCandle> options, int minutes) {
        // Get average ivChange from options that have it
        double totalChange = 0.0;
        int count = 0;

        for (OptionCandle opt : options) {
            if (opt != null && opt.getIvChange() != null) {
                double ivChange = opt.getIvChange();
                if (ivChange != 0) {
                    totalChange += ivChange;
                    count++;
                }
            }
        }

        // Scale by time period (assuming ivChange is per-candle)
        return count > 0 ? (totalChange / count) * minutes : 0.0;
    }

    /**
     * Calculate IV velocity (rate of IV change)
     */
    private double calculateIVVelocity(double ivChange1m, double ivChange5m) {
        // Velocity = derivative of IV change
        // If both periods show same direction change, velocity is confirmed
        if (ivChange1m * ivChange5m > 0) {
            // Same direction - velocity is recent rate
            return ivChange1m;
        } else if (Math.abs(ivChange1m) > Math.abs(ivChange5m)) {
            // Acceleration in 1m period
            return ivChange1m * 1.5;
        }
        return ivChange1m;
    }

    /**
     * Detect IV crush risk
     * High IV + near expiry = crush risk
     */
    private boolean detectIVCrushRisk(List<OptionCandle> options, double ivRank) {
        if (ivRank < IV_CRUSH_RANK) return false;

        // Check for near-term options
        return options.stream()
            .filter(o -> o != null && o.getExpiry() != null)
            .anyMatch(o -> estimateDTE(o.getExpiry()) <= IV_CRUSH_DTE);
    }

    /**
     * Estimate historical IV based on current IV and rank
     */
    private Double estimateHistoricalIV(double atmIV, double ivRank) {
        if (atmIV <= 0 || ivRank <= 0 || ivRank >= 100) return null;

        // Reverse engineer: if current IV is at rank X, historical range is implied
        // Assume typical range is current ± 50%
        double ivRange = atmIV * 0.5;
        double minIV = atmIV - ivRange;
        double maxIV = atmIV + ivRange;

        // Historical IV is somewhere in this range based on rank
        return minIV + (maxIV - minIV) * (ivRank / 100.0);
    }

    /**
     * Determine IV trading signal
     */
    private IVSurface.IVSignal determineIVSignal(double ivRank, boolean ivCrushRisk,
                                                  double ivVelocity) {
        if (ivCrushRisk) {
            return IVSurface.IVSignal.CRUSH_WARNING;
        }

        if (ivRank > IV_ELEVATED_RANK) {
            return IVSurface.IVSignal.SELL_VOL;
        } else if (ivRank < IV_DEPRESSED_RANK) {
            return IVSurface.IVSignal.BUY_VOL;
        }

        return IVSurface.IVSignal.NEUTRAL;
    }

    /**
     * Calculate signal strength (0-1)
     */
    private double calculateSignalStrength(double ivRank, double ivVelocity, boolean ivCrushRisk) {
        double strength = 0.0;

        // IV rank contribution (0-0.5)
        double rankDistance = Math.abs(ivRank - 50) / 50.0; // Distance from neutral
        strength += rankDistance * 0.5;

        // IV velocity contribution (0-0.3)
        double velocityContribution = Math.min(Math.abs(ivVelocity) / 5.0, 1.0) * 0.3;
        strength += velocityContribution;

        // Crush risk adds urgency (0.2)
        if (ivCrushRisk) {
            strength += 0.2;
        }

        return Math.min(strength, 1.0);
    }

    /**
     * Linear regression slope calculation
     */
    private double linearRegressionSlope(List<double[]> points) {
        if (points.size() < 2) return 0.0;

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        int n = points.size();

        for (double[] point : points) {
            sumX += point[0];
            sumY += point[1];
            sumXY += point[0] * point[1];
            sumX2 += point[0] * point[0];
        }

        double denominator = n * sumX2 - sumX * sumX;
        if (Math.abs(denominator) < 0.0001) return 0.0;

        return (n * sumXY - sumX * sumY) / denominator;
    }

    /**
     * Parse expiry string to LocalDate
     */
    private LocalDate parseExpiry(String expiry) {
        try {
            return LocalDate.parse(expiry);
        } catch (Exception e) {
            return LocalDate.now().plusYears(1); // Default to far future
        }
    }

    /**
     * 🔴 CRITICAL FIX: Estimate spotPrice from ATM option strikes
     * When family has no equity/future but has options, we can estimate spot
     * by finding the strike closest to where Call premium ≈ Put premium (ATM)
     */
    private double estimateSpotPriceFromOptions(List<OptionCandle> options) {
        if (options == null || options.isEmpty()) {
            return 0.0;
        }

        // Find strike with highest OI (typically ATM)
        double maxOIStrike = 0.0;
        long maxOI = 0;

        for (OptionCandle opt : options) {
            if (opt == null) continue;
            double strike = opt.getStrikePrice();
            long oi = opt.getOpenInterest();
            if (strike > 0 && oi > maxOI) {
                maxOI = oi;
                maxOIStrike = strike;
            }
        }

        if (maxOIStrike > 0) {
            log.debug("[IV-ESTIMATE] Using max OI strike as spotPrice estimate: {} (OI={})", maxOIStrike, maxOI);
            return maxOIStrike;
        }

        // Fallback: median strike
        return options.stream()
            .filter(o -> o != null && o.getStrikePrice() > 0)
            .mapToDouble(OptionCandle::getStrikePrice)
            .sorted()
            .skip(options.size() / 2)
            .findFirst()
            .orElse(0.0);
    }

    /**
     * Estimate days to expiry from expiry string
     * Handles both ISO format (2026-01-27) and NSE format (27 JAN 2026)
     */
    private int estimateDTE(String expiry) {
        if (expiry == null || expiry.isEmpty()) {
            return 30; // Default to far term
        }

        try {
            LocalDate expiryDate;
            // Try ISO format first (2026-01-27)
            if (expiry.contains("-")) {
                expiryDate = LocalDate.parse(expiry);
            } else {
                // Try NSE format (27 JAN 2026) - CASE INSENSITIVE for uppercase months
                DateTimeFormatter nseFormatter = new DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendPattern("d MMM yyyy")
                    .toFormatter(java.util.Locale.ENGLISH);
                expiryDate = LocalDate.parse(expiry, nseFormatter);
            }
            LocalDate today = LocalDate.now();
            return (int) ChronoUnit.DAYS.between(today, expiryDate);
        } catch (Exception e) {
            log.debug("Failed to parse expiry date: {}", expiry);
            return 30;
        }
    }
}
