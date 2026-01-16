package com.kotsin.consumer.enrichment.enricher;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.OptionCandle;
import com.kotsin.consumer.enrichment.config.CommodityConfig;
import com.kotsin.consumer.enrichment.model.ExpiryContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * ExpiryContextEnricher - Adds expiry awareness to adjust signal confidence
 *
 * Options behave very differently based on Days to Expiry (DTE):
 *
 * FAR EXPIRY (>14 DTE):
 * - Gamma is low, delta changes slowly
 * - Theta decay is slow and predictable
 * - Price trends matter more than gamma effects
 * - Position sizing can be normal
 *
 * MEDIUM EXPIRY (8-14 DTE):
 * - Theta starts accelerating
 * - Gamma becoming more significant
 * - Start being aware of time decay on long positions
 *
 * NEAR EXPIRY (4-7 DTE):
 * - Gamma rising significantly
 * - Theta decay becoming painful for long options
 * - Max pain starting to become relevant
 *
 * EXPIRY WEEK (1-3 DTE):
 * - Gamma explosion possible
 * - Theta crushing long option holders
 * - Max pain becomes important for positioning
 * - Pin risk around major strikes
 *
 * EXPIRY DAY (0 DTE):
 * - Extreme gamma - small moves cause huge delta changes
 * - Max pain most relevant (especially afternoon)
 * - Pin around strikes with high OI
 * - AVOID large directional bets unless confident
 *
 * USAGE:
 * - Apply max pain bias on expiry day afternoon
 * - Warn about theta decay for long options near expiry
 * - Boost gamma-related signals (GEX) near expiry
 * - Reduce confidence on expiry day (high volatility, unpredictable)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpiryContextEnricher {

    private final CommodityConfig commodityConfig;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime AFTERNOON_START = LocalTime.of(12, 0);

    // Standard expiry patterns (can be enhanced with holiday calendar)
    private static final DayOfWeek NSE_WEEKLY_EXPIRY = DayOfWeek.THURSDAY;
    private static final DayOfWeek MCX_CRUDE_EXPIRY = DayOfWeek.FRIDAY;  // Last Friday (simplified)

    /**
     * Enrich with expiry context
     *
     * @param family FamilyCandle to analyze
     * @return ExpiryContext with DTE info and modifiers
     */
    public ExpiryContext enrich(FamilyCandle family) {
        if (family == null) {
            return ExpiryContext.notApplicable();
        }

        // Determine current timestamp
        long timestamp = family.getWindowEndMillis() > 0 ?
                family.getWindowEndMillis() : System.currentTimeMillis();

        ZonedDateTime zdt = Instant.ofEpochMilli(timestamp).atZone(IST);
        LocalDate currentDate = zdt.toLocalDate();
        LocalTime currentTime = zdt.toLocalTime();

        // Check if this is an options family
        boolean hasOptions = family.getOptions() != null && !family.getOptions().isEmpty();

        if (!hasOptions) {
            return ExpiryContext.builder()
                    .familyId(family.getFamilyId())
                    .currentDate(currentDate)
                    .currentTime(currentTime)
                    .daysToExpiry(-1)
                    .category(null)
                    .confidenceModifier(1.0)
                    .applyMaxPain(false)
                    .tradingRecommendation("No options data - N/A")
                    .build();
        }

        // Find nearest expiry from options
        LocalDate nearestExpiry = findNearestExpiry(family.getOptions(), currentDate);

        if (nearestExpiry == null) {
            // Fallback: estimate from typical expiry pattern
            boolean isCommodity = family.isCommodity() || isCommodityFamily(family);
            nearestExpiry = estimateNextExpiry(currentDate, isCommodity);
        }

        // Calculate DTE
        int daysToExpiry = (int) ChronoUnit.DAYS.between(currentDate, nearestExpiry);
        double fractionalDTE = calculateFractionalDTE(currentDate, currentTime, nearestExpiry);

        // Determine category
        ExpiryContext.ExpiryCategory category = determineCategory(daysToExpiry);

        // Calculate flags
        boolean isExpiryDay = daysToExpiry == 0;
        boolean isExpiryWeek = daysToExpiry <= 3;
        boolean isNearExpiry = daysToExpiry <= 7;
        boolean isExpiryDayAfternoon = isExpiryDay && currentTime.isAfter(AFTERNOON_START);

        // Calculate modifiers
        double maxPainRelevance = calculateMaxPainRelevance(daysToExpiry, isExpiryDayAfternoon);
        double gammaSignificance = calculateGammaSignificance(daysToExpiry);
        double thetaWarningLevel = calculateThetaWarningLevel(daysToExpiry);
        double confidenceModifier = calculateConfidenceModifier(category, isExpiryDayAfternoon);

        // Determine if max pain should be applied
        boolean applyMaxPain = daysToExpiry <= 1 || (daysToExpiry <= 3 && isExpiryDayAfternoon);

        // Generate recommendation
        String recommendation = generateRecommendation(category, isExpiryDay, isExpiryDayAfternoon);

        return ExpiryContext.builder()
                .familyId(family.getFamilyId())
                .currentDate(currentDate)
                .currentTime(currentTime)
                .nearestExpiry(nearestExpiry)
                .daysToExpiry(daysToExpiry)
                .fractionalDTE(fractionalDTE)
                .category(category)
                .isExpiryDay(isExpiryDay)
                .isExpiryWeek(isExpiryWeek)
                .isNearExpiry(isNearExpiry)
                .isExpiryDayAfternoon(isExpiryDayAfternoon)
                .maxPainRelevance(maxPainRelevance)
                .gammaSignificance(gammaSignificance)
                .thetaWarningLevel(thetaWarningLevel)
                .confidenceModifier(confidenceModifier)
                .applyMaxPain(applyMaxPain)
                .tradingRecommendation(recommendation)
                .build();
    }

    // ======================== EXPIRY DETECTION ========================

    /**
     * Find nearest expiry from options list
     */
    private LocalDate findNearestExpiry(List<OptionCandle> options, LocalDate currentDate) {
        LocalDate nearest = null;

        for (OptionCandle opt : options) {
            if (opt == null) continue;

            LocalDate expiry = parseExpiryDate(opt);
            if (expiry == null) continue;

            // Only consider future or today's expiry
            if (!expiry.isBefore(currentDate)) {
                if (nearest == null || expiry.isBefore(nearest)) {
                    nearest = expiry;
                }
            }
        }

        return nearest;
    }

    /**
     * Parse expiry date from OptionCandle
     * Tries various sources: expiry field, symbol parsing, etc.
     */
    private LocalDate parseExpiryDate(OptionCandle opt) {
        // Try direct expiry field if available
        if (opt.getExpiry() != null && !opt.getExpiry().isEmpty()) {
            try {
                return LocalDate.parse(opt.getExpiry(), DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (Exception e) {
                // Try other formats
                try {
                    return LocalDate.parse(opt.getExpiry(), DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                } catch (Exception e2) {
                    // Continue to symbol parsing
                }
            }
        }

        // Try to parse from symbol (e.g., NIFTY24JAN18CE)
        String symbol = opt.getSymbol();
        if (symbol != null) {
            return parseExpiryFromSymbol(symbol);
        }

        return null;
    }

    /**
     * Parse expiry from NSE/MCX option symbol format
     * Examples: NIFTY24JAN18CE, BANKNIFTY24118CE, CRUDEOIL24JAN19CE
     */
    private LocalDate parseExpiryFromSymbol(String symbol) {
        // This is a simplified parser - actual implementation would need
        // to handle various symbol formats used by NSE and MCX
        try {
            // Pattern: 2-digit year, 3-letter month or 2-digit month, 2-digit day
            // Extract from end of symbol before CE/PE
            String upper = symbol.toUpperCase();
            int ceIdx = upper.lastIndexOf("CE");
            int peIdx = upper.lastIndexOf("PE");
            int idx = Math.max(ceIdx, peIdx);

            if (idx < 0) return null;

            // Look for date pattern before CE/PE
            // This is simplified - real implementation would be more robust
            String beforeType = upper.substring(0, idx);

            // Try to find YYMONDD pattern (e.g., 24JAN18)
            if (beforeType.length() >= 7) {
                String lastSeven = beforeType.substring(beforeType.length() - 7);
                if (lastSeven.matches("\\d{2}[A-Z]{3}\\d{2}")) {
                    int year = 2000 + Integer.parseInt(lastSeven.substring(0, 2));
                    String mon = lastSeven.substring(2, 5);
                    int day = Integer.parseInt(lastSeven.substring(5, 7));
                    int month = monthFromString(mon);
                    if (month > 0) {
                        return LocalDate.of(year, month, day);
                    }
                }
            }

            // Try YYMDD pattern (e.g., 24118 for Jan 18 2024)
            if (beforeType.length() >= 5) {
                String lastFive = beforeType.substring(beforeType.length() - 5);
                if (lastFive.matches("\\d{5}")) {
                    int year = 2000 + Integer.parseInt(lastFive.substring(0, 2));
                    int month = Integer.parseInt(lastFive.substring(2, 3));
                    int day = Integer.parseInt(lastFive.substring(3, 5));
                    // Handle month 10, 11, 12 encoded differently
                    return LocalDate.of(year, month, day);
                }
            }
        } catch (Exception e) {
            log.trace("Could not parse expiry from symbol: {}", symbol);
        }

        return null;
    }

    private int monthFromString(String mon) {
        return switch (mon) {
            case "JAN" -> 1;
            case "FEB" -> 2;
            case "MAR" -> 3;
            case "APR" -> 4;
            case "MAY" -> 5;
            case "JUN" -> 6;
            case "JUL" -> 7;
            case "AUG" -> 8;
            case "SEP" -> 9;
            case "OCT" -> 10;
            case "NOV" -> 11;
            case "DEC" -> 12;
            default -> 0;
        };
    }

    /**
     * Estimate next expiry based on standard patterns
     */
    private LocalDate estimateNextExpiry(LocalDate currentDate, boolean isCommodity) {
        // For NSE: every Thursday
        // For MCX: varies by commodity (simplified to Friday)
        DayOfWeek expiryDay = isCommodity ? MCX_CRUDE_EXPIRY : NSE_WEEKLY_EXPIRY;

        LocalDate candidate = currentDate;
        while (candidate.getDayOfWeek() != expiryDay) {
            candidate = candidate.plusDays(1);
        }

        // If today is expiry day, return today
        if (currentDate.getDayOfWeek() == expiryDay) {
            return currentDate;
        }

        return candidate;
    }

    // ======================== DTE CALCULATIONS ========================

    /**
     * Calculate fractional DTE (includes time of day)
     */
    private double calculateFractionalDTE(LocalDate currentDate, LocalTime currentTime, LocalDate expiry) {
        int wholeDays = (int) ChronoUnit.DAYS.between(currentDate, expiry);

        if (wholeDays < 0) return 0;
        if (wholeDays == 0) {
            // On expiry day, calculate fraction of day remaining
            // Assuming market closes at 15:30 for NSE
            LocalTime marketClose = LocalTime.of(15, 30);
            if (currentTime.isAfter(marketClose)) return 0;

            long minutesRemaining = ChronoUnit.MINUTES.between(currentTime, marketClose);
            long totalMinutes = ChronoUnit.MINUTES.between(LocalTime.of(9, 15), marketClose);
            return Math.max(0, (double) minutesRemaining / totalMinutes * 0.5);
        }

        // Add fraction of current day remaining
        LocalTime marketClose = LocalTime.of(15, 30);
        LocalTime marketOpen = LocalTime.of(9, 15);

        if (currentTime.isBefore(marketOpen)) {
            return wholeDays + 0.5; // Half day of trading
        } else if (currentTime.isAfter(marketClose)) {
            return wholeDays;
        } else {
            long minutesRemaining = ChronoUnit.MINUTES.between(currentTime, marketClose);
            long totalMinutes = ChronoUnit.MINUTES.between(marketOpen, marketClose);
            return wholeDays + (double) minutesRemaining / totalMinutes * 0.5;
        }
    }

    /**
     * Determine expiry category from DTE
     */
    private ExpiryContext.ExpiryCategory determineCategory(int dte) {
        if (dte <= 0) return ExpiryContext.ExpiryCategory.EXPIRY_DAY;
        if (dte <= 3) return ExpiryContext.ExpiryCategory.EXPIRY_WEEK;
        if (dte <= 7) return ExpiryContext.ExpiryCategory.NEAR_EXPIRY;
        if (dte <= 14) return ExpiryContext.ExpiryCategory.MEDIUM_EXPIRY;
        return ExpiryContext.ExpiryCategory.FAR_EXPIRY;
    }

    // ======================== MODIFIER CALCULATIONS ========================

    /**
     * Calculate max pain relevance (0-1)
     * Increases as expiry approaches, peaks on expiry day afternoon
     */
    private double calculateMaxPainRelevance(int dte, boolean isAfternoon) {
        if (dte > 7) return 0.0;
        if (dte > 3) return 0.2;
        if (dte > 1) return 0.4;
        if (dte == 1) return 0.6;

        // Expiry day
        return isAfternoon ? 1.0 : 0.8;
    }

    /**
     * Calculate gamma significance (0-1)
     * Gamma effects become more pronounced near expiry
     */
    private double calculateGammaSignificance(int dte) {
        if (dte > 14) return 0.2;
        if (dte > 7) return 0.4;
        if (dte > 3) return 0.6;
        if (dte > 1) return 0.8;
        return 1.0; // Expiry day - gamma at maximum significance
    }

    /**
     * Calculate theta warning level (0-1)
     * Theta decay accelerates near expiry
     */
    private double calculateThetaWarningLevel(int dte) {
        if (dte > 14) return 0.1;
        if (dte > 7) return 0.3;
        if (dte > 3) return 0.5;
        if (dte > 1) return 0.8;
        return 1.0; // Expiry day - maximum theta crush
    }

    /**
     * Calculate overall confidence modifier
     * Generally reduce confidence near expiry due to unpredictability
     */
    private double calculateConfidenceModifier(ExpiryContext.ExpiryCategory category, boolean isExpiryDayAfternoon) {
        if (category == null) return 1.0;

        return switch (category) {
            case FAR_EXPIRY -> 1.0;      // Normal confidence
            case MEDIUM_EXPIRY -> 0.95;  // Slight reduction
            case NEAR_EXPIRY -> 0.9;     // Moderate reduction
            case EXPIRY_WEEK -> 0.8;     // Significant reduction
            case EXPIRY_DAY -> isExpiryDayAfternoon ? 0.6 : 0.7; // High reduction
        };
    }

    // ======================== HELPERS ========================

    private boolean isCommodityFamily(FamilyCandle family) {
        if (family.getFuture() != null && family.getFuture().getExchange() != null) {
            return commodityConfig.isMCXExchange(family.getFuture().getExchange());
        }
        return false;
    }

    /**
     * Generate trading recommendation based on expiry context
     */
    private String generateRecommendation(ExpiryContext.ExpiryCategory category,
                                           boolean isExpiryDay, boolean isExpiryDayAfternoon) {
        if (category == null) return "Normal trading";

        return switch (category) {
            case FAR_EXPIRY -> "Normal trading - focus on trend and momentum";
            case MEDIUM_EXPIRY -> "Normal trading - be aware of time decay on long options";
            case NEAR_EXPIRY -> "CAUTION: Theta accelerating, gamma rising. Consider rolling positions";
            case EXPIRY_WEEK -> "HIGH RISK: Gamma explosion possible. Reduce position size, watch max pain";
            case EXPIRY_DAY -> isExpiryDayAfternoon ?
                    "EXTREME RISK: Expiry afternoon - MAX PAIN dominant, pin risk HIGH" :
                    "EXTREME RISK: Expiry day - Gamma extreme, avoid large directional bets";
        };
    }
}
