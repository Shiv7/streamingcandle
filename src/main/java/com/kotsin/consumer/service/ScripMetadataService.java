package com.kotsin.consumer.service;

import com.kotsin.consumer.metadata.model.Scrip;
import com.kotsin.consumer.repository.ScripRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ScripMetadataService - Centralized service for scrip metadata lookups.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Lookup scrip metadata by scripCode</li>
 *   <li>Extract clean symbol (SymbolRoot) from scripCode</li>
 *   <li>Get company name, lot size, tick size, and other metadata</li>
 *   <li>Cache lookups to avoid repeated database queries</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Get clean symbol root
 * String symbol = scripMetadataService.getSymbolRoot(scripCode);
 *
 * // Get full scrip details
 * Optional<Scrip> scrip = scripMetadataService.getScrip(scripCode, "N", "D");
 * }</pre>
 *
 * @see Scrip
 * @see ScripRepository
 */
@Service
@Slf4j
public class ScripMetadataService {

    private static final String LOG_PREFIX = "[SCRIP-META]";

    @Autowired
    private ScripRepository scripRepository;

    /**
     * In-memory cache for scrip lookups.
     * Key: scripCode, Value: Scrip object
     */
    private final Map<String, Scrip> scripCache = new ConcurrentHashMap<>();

    /**
     * Cache for scripCodes that don't exist in database.
     * Prevents repeated failed lookups.
     */
    private final Map<String, Boolean> notFoundCache = new ConcurrentHashMap<>();

    // ==================== SYMBOL EXTRACTION ====================

    /**
     * Get the clean symbol root for a scripCode.
     *
     * <p>This is the authoritative way to get a symbol from scripCode.</p>
     * <p>Falls back to company name parsing if scrip not found in database.</p>
     *
     * @param scripCode   exchange scrip code
     * @param companyName company name for fallback parsing
     * @return symbol root (e.g., "NIFTY", "BANKNIFTY", "RELIANCE")
     */
    public String getSymbolRoot(String scripCode, String companyName) {
        if (scripCode == null) {
            return extractSymbolFromCompanyName(companyName, null);
        }

        // Check cache first
        Scrip cached = scripCache.get(scripCode);
        if (cached != null && cached.getSymbolRoot() != null) {
            return cached.getSymbolRoot();
        }

        // Check if already known to not exist
        if (notFoundCache.containsKey(scripCode)) {
            return extractSymbolFromCompanyName(companyName, scripCode);
        }

        // Lookup from database
        try {
            List<Scrip> scrips = scripRepository.findByScripCode(scripCode);
            if (scrips != null && !scrips.isEmpty()) {
                Scrip scrip = scrips.get(0);
                scripCache.put(scripCode, scrip);

                if (scrip.getSymbolRoot() != null && !scrip.getSymbolRoot().isEmpty()) {
                    return scrip.getSymbolRoot();
                }
            } else {
                notFoundCache.put(scripCode, true);
            }
        } catch (Exception e) {
            log.warn("{} Failed to lookup scripCode {}: {}", LOG_PREFIX, scripCode, e.getMessage());
            notFoundCache.put(scripCode, true);
        }

        // Fallback to company name parsing
        return extractSymbolFromCompanyName(companyName, scripCode);
    }

    /**
     * Get the full company name for a scripCode.
     *
     * @param scripCode exchange scrip code
     * @return full company name or null if not found
     */
    public String getCompanyName(String scripCode) {
        if (scripCode == null) return null;

        Scrip scrip = getScripByCode(scripCode);
        if (scrip != null) {
            // Prefer FullName, then Name
            if (scrip.getFullName() != null && !scrip.getFullName().isEmpty()) {
                return scrip.getFullName();
            }
            return scrip.getName();
        }
        return null;
    }

    /**
     * Get scrip by scripCode only.
     *
     * @param scripCode exchange scrip code
     * @return Scrip object or null if not found
     */
    public Scrip getScripByCode(String scripCode) {
        if (scripCode == null) return null;

        // Check cache
        Scrip cached = scripCache.get(scripCode);
        if (cached != null) return cached;

        // Check not found cache
        if (notFoundCache.containsKey(scripCode)) return null;

        // Lookup
        try {
            List<Scrip> scrips = scripRepository.findByScripCode(scripCode);
            if (scrips != null && !scrips.isEmpty()) {
                Scrip scrip = scrips.get(0);
                scripCache.put(scripCode, scrip);
                return scrip;
            } else {
                notFoundCache.put(scripCode, true);
            }
        } catch (Exception e) {
            log.warn("{} Failed to lookup scripCode {}: {}", LOG_PREFIX, scripCode, e.getMessage());
        }
        return null;
    }

    /**
     * Get scrip by scripCode, exchange, and exchange type (most precise lookup).
     *
     * @param scripCode    exchange scrip code
     * @param exchange     exchange code (N, B, M)
     * @param exchangeType exchange type (C, D)
     * @return Optional containing Scrip if found
     */
    public Optional<Scrip> getScrip(String scripCode, String exchange, String exchangeType) {
        if (scripCode == null) return Optional.empty();

        // For precise lookups, always query database
        try {
            List<Scrip> scrips = scripRepository.findByScripCodeAndExchAndExchType(scripCode, exchange, exchangeType);
            if (scrips != null && !scrips.isEmpty()) {
                Scrip scrip = scrips.get(0);
                scripCache.put(scripCode, scrip); // Cache for future simple lookups
                return Optional.of(scrip);
            }
        } catch (Exception e) {
            log.warn("{} Failed to lookup scripCode {} with exchange {}/{}: {}",
                LOG_PREFIX, scripCode, exchange, exchangeType, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Get lot size for a scripCode.
     *
     * @param scripCode exchange scrip code
     * @return lot size as integer, or 1 if not found
     */
    public int getLotSize(String scripCode) {
        Scrip scrip = getScripByCode(scripCode);
        if (scrip != null && scrip.getLotSize() != null) {
            try {
                return Integer.parseInt(scrip.getLotSize());
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return 1;
    }

    /**
     * Get tick size for a scripCode.
     *
     * @param scripCode exchange scrip code
     * @return tick size as double, or 0.05 if not found
     */
    public double getTickSize(String scripCode) {
        Scrip scrip = getScripByCode(scripCode);
        if (scrip != null && scrip.getTickSize() != null) {
            try {
                return Double.parseDouble(scrip.getTickSize());
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return 0.05;
    }

    /**
     * Get expiry date string for a scripCode.
     *
     * @param scripCode exchange scrip code
     * @return expiry date string or null if not found/applicable
     */
    public String getExpiry(String scripCode) {
        Scrip scrip = getScripByCode(scripCode);
        return scrip != null ? scrip.getExpiry() : null;
    }

    /**
     * Get strike rate for options.
     *
     * @param scripCode exchange scrip code
     * @return strike rate as double, or null if not applicable
     */
    public Double getStrikeRate(String scripCode) {
        Scrip scrip = getScripByCode(scripCode);
        if (scrip != null && scrip.getStrikeRate() != null) {
            try {
                return Double.parseDouble(scrip.getStrikeRate());
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return null;
    }

    /**
     * Get scrip type (EQ, FUT, CE, PE, etc.).
     *
     * @param scripCode exchange scrip code
     * @return scrip type or null if not found
     */
    public String getScripType(String scripCode) {
        Scrip scrip = getScripByCode(scripCode);
        return scrip != null ? scrip.getScripType() : null;
    }

    /**
     * Check if scrip is an option (CE or PE).
     *
     * @param scripCode exchange scrip code
     * @return true if option, false otherwise
     */
    public boolean isOption(String scripCode) {
        String scripType = getScripType(scripCode);
        return "CE".equals(scripType) || "PE".equals(scripType);
    }

    /**
     * Check if scrip is a Call option.
     *
     * @param scripCode exchange scrip code
     * @return true if Call option
     */
    public boolean isCallOption(String scripCode) {
        return "CE".equals(getScripType(scripCode));
    }

    /**
     * Check if scrip is a Put option.
     *
     * @param scripCode exchange scrip code
     * @return true if Put option
     */
    public boolean isPutOption(String scripCode) {
        return "PE".equals(getScripType(scripCode));
    }

    // ==================== LOT SIZE VALIDATION ====================

    /**
     * Validate if quantity is a valid lot size multiple.
     *
     * <p>Options must be traded in lot size multiples. For example, if lot size is 30,
     * valid quantities are 30, 60, 90, etc.</p>
     *
     * @param scripCode exchange scrip code
     * @param quantity  order quantity to validate
     * @return true if quantity is valid (multiple of lot size), false otherwise
     */
    public boolean isValidQuantity(String scripCode, long quantity) {
        if (quantity <= 0) return false;

        int lotSize = getLotSize(scripCode);
        return quantity % lotSize == 0;
    }

    /**
     * Round quantity up to nearest valid lot size.
     *
     * <p>Useful when user wants to buy "approximately" a certain quantity.</p>
     *
     * @param scripCode exchange scrip code
     * @param quantity  desired quantity
     * @return quantity rounded up to nearest lot size multiple
     */
    public long roundUpToLotSize(String scripCode, long quantity) {
        int lotSize = getLotSize(scripCode);
        if (lotSize <= 0) return quantity;

        long remainder = quantity % lotSize;
        if (remainder == 0) return quantity;
        return quantity + (lotSize - remainder);
    }

    /**
     * Round quantity down to nearest valid lot size.
     *
     * @param scripCode exchange scrip code
     * @param quantity  desired quantity
     * @return quantity rounded down to nearest lot size multiple, or lotSize if less than one lot
     */
    public long roundDownToLotSize(String scripCode, long quantity) {
        int lotSize = getLotSize(scripCode);
        if (lotSize <= 0) return quantity;

        long rounded = (quantity / lotSize) * lotSize;
        return rounded > 0 ? rounded : lotSize;
    }

    /**
     * Get minimum order quantity (1 lot).
     *
     * @param scripCode exchange scrip code
     * @return minimum quantity (equals lot size for derivatives, 1 for equity)
     */
    public int getMinQuantity(String scripCode) {
        return getLotSize(scripCode);
    }

    /**
     * Calculate number of lots for a given quantity.
     *
     * @param scripCode exchange scrip code
     * @param quantity  total quantity
     * @return number of lots (floored)
     */
    public int calculateLots(String scripCode, long quantity) {
        int lotSize = getLotSize(scripCode);
        if (lotSize <= 0) return (int) quantity;
        return (int) (quantity / lotSize);
    }

    /**
     * Get quantity limit for a scripCode.
     *
     * @param scripCode exchange scrip code
     * @return maximum quantity allowed per order, or Integer.MAX_VALUE if not found
     */
    public int getQuantityLimit(String scripCode) {
        Scrip scrip = getScripByCode(scripCode);
        if (scrip != null && scrip.getQtyLimit() != null) {
            try {
                return Integer.parseInt(scrip.getQtyLimit());
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * Validate order quantity against all rules.
     *
     * <p>Checks:</p>
     * <ol>
     *   <li>Quantity must be positive</li>
     *   <li>Quantity must be multiple of lot size</li>
     *   <li>Quantity must not exceed quantity limit</li>
     * </ol>
     *
     * @param scripCode exchange scrip code
     * @param quantity  order quantity
     * @return null if valid, error message if invalid
     */
    public String validateOrderQuantity(String scripCode, long quantity) {
        if (quantity <= 0) {
            return "Quantity must be positive";
        }

        int lotSize = getLotSize(scripCode);
        if (quantity % lotSize != 0) {
            return String.format("Quantity %d is not a multiple of lot size %d. Valid quantities: %d, %d, %d...",
                quantity, lotSize, lotSize, lotSize * 2, lotSize * 3);
        }

        int qtyLimit = getQuantityLimit(scripCode);
        if (quantity > qtyLimit) {
            return String.format("Quantity %d exceeds maximum limit %d", quantity, qtyLimit);
        }

        return null; // Valid
    }

    // ==================== CACHE MANAGEMENT ====================

    /**
     * Clear all caches. Useful for testing or when scrip data is updated.
     */
    public void clearCache() {
        scripCache.clear();
        notFoundCache.clear();
        log.info("{} Cache cleared", LOG_PREFIX);
    }

    /**
     * Get cache statistics.
     *
     * @return map with cache stats
     */
    public Map<String, Object> getCacheStats() {
        return Map.of(
            "cachedScrips", scripCache.size(),
            "notFoundEntries", notFoundCache.size()
        );
    }

    // ==================== FALLBACK PARSING ====================

    /**
     * Extract symbol from company name when database lookup fails.
     *
     * <p>Handles special cases for index options:</p>
     * <ul>
     *   <li>"BANK NIFTY ..." → "BANKNIFTY"</li>
     *   <li>"NIFTY BANK ..." → "BANKNIFTY"</li>
     *   <li>"FIN NIFTY ..." → "FINNIFTY"</li>
     * </ul>
     *
     * @param companyName full company name
     * @param scripCode   fallback scripCode
     * @return extracted symbol
     */
    private String extractSymbolFromCompanyName(String companyName, String scripCode) {
        if (companyName == null || companyName.isEmpty()) {
            return scripCode;
        }

        String upper = companyName.toUpperCase().trim();

        // Handle special cases for index options
        if (upper.startsWith("BANK NIFTY") || upper.startsWith("BANKNIFTY")) {
            return "BANKNIFTY";
        }
        if (upper.startsWith("NIFTY BANK")) {
            return "BANKNIFTY";
        }
        if (upper.startsWith("FIN NIFTY") || upper.startsWith("FINNIFTY")) {
            return "FINNIFTY";
        }
        if (upper.startsWith("NIFTY")) {
            return "NIFTY";
        }

        // Extract first word (skip if purely numeric)
        String[] parts = upper.split("\\s+");
        if (parts.length > 0 && !parts[0].matches("^\\d+$")) {
            return parts[0];
        }

        return scripCode;
    }
}
