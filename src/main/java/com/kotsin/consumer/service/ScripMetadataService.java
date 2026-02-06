package com.kotsin.consumer.service;

import com.kotsin.consumer.metadata.model.Scrip;
import com.kotsin.consumer.options.service.ScripGroupService;
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
     * Bug #5: ScripGroupService for derivative→equity mapping.
     */
    @Autowired(required = false)
    private ScripGroupService scripGroupService;

    // Bug #16: Bounded cache with max size
    private static final int MAX_CACHE_SIZE = 5000;

    // Bug #16: Not-found cache TTL (1 hour in millis)
    private static final long NOT_FOUND_TTL_MS = 3600_000L;

    /**
     * In-memory cache for scrip lookups.
     * Key: scripCode, Value: Scrip object
     */
    private final Map<String, Scrip> scripCache = new ConcurrentHashMap<>();

    /**
     * Bug #16: Cache for scripCodes that don't exist in database.
     * Value is timestamp when entry was cached (for TTL expiry).
     */
    private final Map<String, Long> notFoundCache = new ConcurrentHashMap<>();

    // ==================== SYMBOL EXTRACTION ====================

    /**
     * Get the clean symbol root for a scripCode.
     *
     * <p>This is the authoritative way to get a symbol from scripCode.</p>
     *
     * @param scripCode   exchange scrip code
     * @return symbol root (e.g., "NIFTY", "BANKNIFTY", "RELIANCE")
     */
    public String getSymbolRoot(String scripCode) {
        // Check cache first
        Scrip cached = scripCache.get(scripCode);
        if (cached != null && cached.getSymbolRoot() != null) {
            return cached.getSymbolRoot();
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
                notFoundCache.put(scripCode, System.currentTimeMillis());
            }
        } catch (Exception e) {
            log.warn("{} Failed to lookup scripCode {}: {}", LOG_PREFIX, scripCode, e.getMessage());
            notFoundCache.put(scripCode, System.currentTimeMillis());
        }
        return "";
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
                // Bug #16: Check not-found cache with TTL
        Long notFoundAt = notFoundCache.get(scripCode);
        if (notFoundAt != null && (System.currentTimeMillis() - notFoundAt) < NOT_FOUND_TTL_MS) return null;
        if (notFoundAt != null) notFoundCache.remove(scripCode); // TTL expired, retry

        // Lookup
        try {
            List<Scrip> scrips = scripRepository.findByScripCode(scripCode);
            if (scrips != null && !scrips.isEmpty()) {
                Scrip scrip = scrips.get(0);
                enforceMaxCacheSize();
                scripCache.put(scripCode, scrip);
                return scrip;
            } else {
                notFoundCache.put(scripCode, System.currentTimeMillis());
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

    // ==================== FAMILY CORRELATION (Bug #5) ====================

    /**
     * Bug #5: Get the equity scripCode for a derivative (future/option) scripCode.
     * Uses ScripGroup lookup via ScripGroupService.
     *
     * @param scripCode derivative scripCode
     * @return equity scripCode, or null if not found or not a derivative
     */
    public String getEquityScripCode(String scripCode) {
        if (scripGroupService == null || scripCode == null) return null;
        return scripGroupService.getEquityScripCode(scripCode);
    }

    // ==================== CACHE MANAGEMENT ====================

    /**
     * Bug #16: Enforce max cache size by removing oldest entries.
     */
    private void enforceMaxCacheSize() {
        if (scripCache.size() > MAX_CACHE_SIZE) {
            // Remove ~10% of entries to avoid constant eviction
            int toRemove = scripCache.size() - (int)(MAX_CACHE_SIZE * 0.9);
            scripCache.keySet().stream().limit(toRemove).toList()
                .forEach(scripCache::remove);
            log.debug("{} Evicted {} entries from scripCache (size={})", LOG_PREFIX, toRemove, scripCache.size());
        }
        // Also clean expired not-found entries periodically
        long now = System.currentTimeMillis();
        notFoundCache.entrySet().removeIf(e -> (now - e.getValue()) > NOT_FOUND_TTL_MS);
    }

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
}
