package com.kotsin.consumer.aggregator.state;

import com.kotsin.consumer.metadata.model.Scrip;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OptionMetadata - Parsed metadata from option company names.
 *
 * <p>Parses NSE/BSE option naming conventions to extract key option contract details
 * from the company name string provided by 5paisa API.</p>
 *
 * <h2>Extracted Fields</h2>
 * <ul>
 *   <li><b>strikePrice</b>: Option strike price (e.g., 1360.00)</li>
 *   <li><b>optionType</b>: Call (CE) or Put (PE)</li>
 *   <li><b>expiry</b>: Expiry date in ISO format (e.g., "2026-02-24")</li>
 *   <li><b>daysToExpiry</b>: Days remaining until expiry (calculated from IST)</li>
 * </ul>
 *
 * <h2>Supported Format Examples</h2>
 * <pre>
 * "ICICIBANK 24 FEB 2026 CE 1360.00"  → Strike: 1360.00, Type: CE, Expiry: 2026-02-24
 * "NIFTY 30 JAN 2026 PE 23500.00"     → Strike: 23500.00, Type: PE, Expiry: 2026-01-30
 * "BANKNIFTY 29 JAN 2026 CE 50000.00" → Strike: 50000.00, Type: CE, Expiry: 2026-01-29
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * OptionMetadata meta = OptionMetadata.parse("NIFTY 30 JAN 2026 PE 23500.00");
 * if (meta != null) {
 *     double strike = meta.getStrikePrice();
 *     String type = meta.getOptionType();
 * }
 * }</pre>
 *
 * @see TickAggregateState
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionMetadata {

    // ==================== CONSTANTS ====================

    /**
     * Primary regex pattern for parsing option details.
     * <p>Format: DD MMM YYYY (CE|PE) STRIKE</p>
     * <p>Example: "24 FEB 2026 CE 1360.00"</p>
     */
    private static final Pattern FULL_OPTION_PATTERN = Pattern.compile(
        "(\\d{1,2})\\s+(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)\\s+(\\d{4})\\s+(CE|PE)\\s+([\\d.]+)"
    );

    /**
     * Fallback pattern for extracting just the strike price.
     * <p>Format: (CE|PE) STRIKE at end of string</p>
     */
    private static final Pattern STRIKE_ONLY_PATTERN = Pattern.compile("(CE|PE)\\s+([\\d.]+)$");

    /**
     * Indian Standard Time zone for days-to-expiry calculation.
     */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ==================== FIELDS ====================

    /**
     * Option strike price.
     * <p>Example: 1360.00, 23500.00</p>
     */
    private Double strikePrice;

    /**
     * Option type: "CE" (Call) or "PE" (Put).
     */
    private String optionType;

    /**
     * Expiry date in ISO format.
     * <p>Example: "2026-02-24"</p>
     */
    private String expiry;

    /**
     * Days remaining until expiry.
     * <p>Calculated from current IST date to expiry date.</p>
     * <p>Can be negative if option has expired.</p>
     */
    private Integer daysToExpiry;

    /**
     * Underlying symbol (e.g., "BANKNIFTY", "NIFTY", "RELIANCE").
     * <p>From Scrip.SymbolRoot field.</p>
     */
    private String underlyingSymbol;

    /**
     * Lot size for trading.
     * <p>Options must be traded in multiples of lot size.</p>
     */
    private Integer lotSize;

    /**
     * Tick size (minimum price movement).
     */
    private Double tickSize;

    /**
     * Maximum quantity per order.
     */
    private Integer quantityLimit;

    // ==================== FACTORY METHODS ====================

    /**
     * Create OptionMetadata from Scrip object (PREFERRED METHOD).
     *
     * <p>Uses authoritative data from the scrip database instead of parsing company name.</p>
     * <p>This is more accurate and handles all edge cases correctly.</p>
     *
     * <p>Example Scrip data:</p>
     * <pre>
     * ScripType: "CE"
     * StrikeRate: "82500"
     * Expiry: "2026-06-30"
     * SymbolRoot: "BANKNIFTY"
     * LotSize: "30"
     * TickSize: "0.05"
     * </pre>
     *
     * @param scrip Scrip object from database
     * @return OptionMetadata if scrip is an option, null otherwise
     */
    public static OptionMetadata fromScrip(Scrip scrip) {
        if (scrip == null) {
            return null;
        }

        // Check if it's an option
        String scripType = scrip.getScripType();
        if (!"CE".equals(scripType) && !"PE".equals(scripType)) {
            return null;
        }

        OptionMetadata metadata = new OptionMetadata();
        metadata.optionType = scripType;
        metadata.underlyingSymbol = scrip.getSymbolRoot();

        // Parse strike rate
        if (scrip.getStrikeRate() != null) {
            try {
                metadata.strikePrice = Double.parseDouble(scrip.getStrikeRate());
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        // Expiry is already in ISO format (2026-06-30)
        metadata.expiry = scrip.getExpiry();

        // Calculate days to expiry
        if (scrip.getExpiry() != null) {
            try {
                LocalDate expiryDate = LocalDate.parse(scrip.getExpiry());
                LocalDate today = LocalDate.now(IST);
                metadata.daysToExpiry = (int) ChronoUnit.DAYS.between(today, expiryDate);
            } catch (Exception e) {
                // ignore parsing errors
            }
        }

        // Lot size
        if (scrip.getLotSize() != null) {
            try {
                metadata.lotSize = Integer.parseInt(scrip.getLotSize());
            } catch (NumberFormatException e) {
                metadata.lotSize = 1;
            }
        }

        // Tick size
        if (scrip.getTickSize() != null) {
            try {
                metadata.tickSize = Double.parseDouble(scrip.getTickSize());
            } catch (NumberFormatException e) {
                metadata.tickSize = 0.05;
            }
        }

        // Quantity limit
        if (scrip.getQtyLimit() != null) {
            try {
                metadata.quantityLimit = Integer.parseInt(scrip.getQtyLimit());
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        return metadata;
    }

    // ==================== LEGACY FACTORY METHOD (FALLBACK) ====================

    /**
     * Parse option metadata from company name (FALLBACK METHOD).
     *
     * <p><b>NOTE:</b> Prefer using {@link #fromScrip(Scrip)} when Scrip data is available,
     * as it provides more accurate data from the database.</p>
     *
     * <p>Attempts to extract option details from the company name string
     * using pattern matching. Handles various NSE/BSE naming formats.</p>
     *
     * <p>Parsing strategy:</p>
     * <ol>
     *   <li>Check if string contains CE or PE (option indicator)</li>
     *   <li>Try full pattern matching for expiry + strike</li>
     *   <li>Fallback to strike-only pattern if full pattern fails</li>
     * </ol>
     *
     * @param companyName full company name containing option details
     * @return parsed OptionMetadata if string represents an option, null otherwise
     * @deprecated Use {@link #fromScrip(Scrip)} instead when Scrip data is available
     */
    public static OptionMetadata parse(String companyName) {
        if (companyName == null || companyName.isEmpty()) {
            return null;
        }

        String upper = companyName.toUpperCase().trim();

        // Check if it's an option (contains CE or PE)
        if (!upper.contains(" CE ") && !upper.contains(" PE ") &&
            !upper.endsWith(" CE") && !upper.endsWith(" PE")) {
            return null;
        }

        OptionMetadata metadata = new OptionMetadata();

        // Extract option type (CE or PE)
        if (upper.contains(" CE ") || upper.endsWith(" CE")) {
            metadata.optionType = "CE";
        } else if (upper.contains(" PE ") || upper.endsWith(" PE")) {
            metadata.optionType = "PE";
        }

        // Try full pattern matching
        Matcher matcher = FULL_OPTION_PATTERN.matcher(upper);

        if (matcher.find()) {
            try {
                // Parse expiry date
                int day = Integer.parseInt(matcher.group(1));
                String monthStr = matcher.group(2);
                int year = Integer.parseInt(matcher.group(3));

                Month month = parseMonth(monthStr);

                if (month != null) {
                    LocalDate expiryDate = LocalDate.of(year, month, day);
                    metadata.expiry = expiryDate.toString(); // ISO format: "2026-02-24"

                    // Calculate days to expiry
                    LocalDate today = LocalDate.now(IST);
                    metadata.daysToExpiry = (int) ChronoUnit.DAYS.between(today, expiryDate);
                }

                // Parse strike price
                metadata.strikePrice = Double.parseDouble(matcher.group(5));

            } catch (Exception e) {
                // Parsing failed, metadata will have partial data
            }
        } else {
            // Fallback: Try to extract just the strike price
            Matcher strikeMatcher = STRIKE_ONLY_PATTERN.matcher(upper);
            if (strikeMatcher.find()) {
                try {
                    metadata.strikePrice = Double.parseDouble(strikeMatcher.group(2));
                } catch (NumberFormatException e) {
                    // Ignore parsing failure
                }
            }
        }

        return metadata;
    }

    // ==================== HELPER METHODS ====================

    /**
     * Parse month abbreviation to Month enum.
     *
     * @param monthStr three-letter month abbreviation (e.g., "JAN", "FEB")
     * @return corresponding Month enum, or null if not recognized
     */
    private static Month parseMonth(String monthStr) {
        return switch (monthStr) {
            case "JAN" -> Month.JANUARY;
            case "FEB" -> Month.FEBRUARY;
            case "MAR" -> Month.MARCH;
            case "APR" -> Month.APRIL;
            case "MAY" -> Month.MAY;
            case "JUN" -> Month.JUNE;
            case "JUL" -> Month.JULY;
            case "AUG" -> Month.AUGUST;
            case "SEP" -> Month.SEPTEMBER;
            case "OCT" -> Month.OCTOBER;
            case "NOV" -> Month.NOVEMBER;
            case "DEC" -> Month.DECEMBER;
            default -> null;
        };
    }

    // ==================== CONVENIENCE METHODS ====================

    /**
     * Check if this is a Call option.
     *
     * @return true if option type is CE (Call)
     */
    public boolean isCall() {
        return "CE".equals(optionType);
    }

    /**
     * Check if this is a Put option.
     *
     * @return true if option type is PE (Put)
     */
    public boolean isPut() {
        return "PE".equals(optionType);
    }

    /**
     * Check if option has expired.
     *
     * @return true if daysToExpiry is negative
     */
    public boolean isExpired() {
        return daysToExpiry != null && daysToExpiry < 0;
    }

    /**
     * Check if option expires today.
     *
     * @return true if daysToExpiry is 0
     */
    public boolean isExpiryDay() {
        return daysToExpiry != null && daysToExpiry == 0;
    }

    // ==================== LOT SIZE METHODS ====================

    /**
     * Get lot size for this option.
     *
     * @return lot size, or 1 if not set
     */
    public int getLotSizeOrDefault() {
        return lotSize != null ? lotSize : 1;
    }

    /**
     * Check if quantity is a valid multiple of lot size.
     *
     * @param quantity order quantity
     * @return true if valid
     */
    public boolean isValidQuantity(long quantity) {
        int lot = getLotSizeOrDefault();
        return quantity > 0 && quantity % lot == 0;
    }

    /**
     * Round quantity up to nearest lot size multiple.
     *
     * @param quantity desired quantity
     * @return valid quantity (rounded up)
     */
    public long roundUpToLotSize(long quantity) {
        int lot = getLotSizeOrDefault();
        long remainder = quantity % lot;
        if (remainder == 0) return quantity;
        return quantity + (lot - remainder);
    }

    /**
     * Get minimum tradeable quantity (1 lot).
     *
     * @return minimum quantity
     */
    public int getMinQuantity() {
        return getLotSizeOrDefault();
    }

    /**
     * Calculate number of lots for a quantity.
     *
     * @param quantity total quantity
     * @return number of lots
     */
    public int calculateLots(long quantity) {
        return (int) (quantity / getLotSizeOrDefault());
    }

    /**
     * Get total value for given lots.
     *
     * @param lots number of lots
     * @param premium option premium per unit
     * @return total value (lots * lotSize * premium)
     */
    public double calculateValue(int lots, double premium) {
        return lots * getLotSizeOrDefault() * premium;
    }
}
