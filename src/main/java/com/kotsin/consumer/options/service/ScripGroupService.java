package com.kotsin.consumer.options.service;

import com.kotsin.consumer.model.OIMetrics;
import com.kotsin.consumer.repository.OIMetricsRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ScripGroupService - Manages option instrument data from MongoDB.
 *
 * Loads option instrument metadata from OIMetrics collection.
 * Provides lookup methods for:
 * - Finding options by underlying symbol
 * - Mapping option scripCode to underlying
 * - Getting ATM/OTM options for a given spot price
 */
@Service
@Slf4j
public class ScripGroupService {

    private static final String LOG_PREFIX = "[SCRIP-GROUP]";

    @Autowired
    private OIMetricsRepository oiMetricsRepository;

    @Value("${scripgroup.cache.refresh.minutes:5}")
    private int cacheRefreshMinutes;

    // Caches
    private final Map<String, OptionInstrument> optionByScripCode = new ConcurrentHashMap<>();
    private final Map<String, String> optionToUnderlying = new ConcurrentHashMap<>();
    private final Map<String, List<OptionInstrument>> optionsByUnderlying = new ConcurrentHashMap<>();
    private final Set<String> underlyingsWithOptions = ConcurrentHashMap.newKeySet();

    private volatile Instant lastRefresh = Instant.EPOCH;

    @PostConstruct
    public void init() {
        log.info("{} Initializing ScripGroupService from MongoDB", LOG_PREFIX);
        refreshFromDatabase();
    }

    /**
     * Scheduled refresh of option instrument cache.
     */
    @Scheduled(fixedDelayString = "${scripgroup.cache.refresh.ms:300000}")  // 5 minutes default
    public void scheduledRefresh() {
        refreshFromDatabase();
    }

    /**
     * Refresh option instruments from OIMetrics in MongoDB.
     */
    public synchronized void refreshFromDatabase() {
        // Check if refresh is needed (unless forced)
        if (lastRefresh.plusSeconds(cacheRefreshMinutes * 60L).isAfter(Instant.now())) {
            log.debug("{} Cache still valid, skipping refresh", LOG_PREFIX);
            return;
        }

        log.info("{} Refreshing option instruments from MongoDB", LOG_PREFIX);

        try {
            // Query recent OI metrics with option data (last 30 minutes)
            Instant cutoff = Instant.now().minus(30, ChronoUnit.MINUTES);
            List<OIMetrics> optionMetrics = oiMetricsRepository.findAllOptionsAfter(cutoff);

            if (optionMetrics.isEmpty()) {
                log.warn("{} No option metrics found in last 30 minutes", LOG_PREFIX);
                return;
            }

            // Build option instrument map - deduplicate by scripCode (keep latest)
            Map<String, OIMetrics> latestByScripCode = new HashMap<>();
            for (OIMetrics metric : optionMetrics) {
                if (metric.getScripCode() == null || metric.getOptionType() == null) {
                    continue;
                }
                OIMetrics existing = latestByScripCode.get(metric.getScripCode());
                if (existing == null ||
                    (metric.getTimestamp() != null && existing.getTimestamp() != null &&
                     metric.getTimestamp().isAfter(existing.getTimestamp()))) {
                    latestByScripCode.put(metric.getScripCode(), metric);
                }
            }

            // Clear and rebuild caches
            optionByScripCode.clear();
            optionToUnderlying.clear();
            Map<String, List<OptionInstrument>> tempOptionsByUnderlying = new HashMap<>();

            for (OIMetrics metric : latestByScripCode.values()) {
                OptionInstrument opt = OptionInstrument.builder()
                    .scripCode(metric.getScripCode())
                    .symbol(metric.getSymbol())
                    .underlyingSymbol(metric.getUnderlyingSymbol())
                    .strikePrice(metric.getStrikePrice())
                    .optionType(metric.getOptionType())
                    .expiry(metric.getExpiry())
                    .exchange(metric.getExchange())
                    .exchangeType(metric.getExchangeType())
                    .build();

                optionByScripCode.put(opt.getScripCode(), opt);

                if (opt.getUnderlyingSymbol() != null) {
                    optionToUnderlying.put(opt.getScripCode(), opt.getUnderlyingSymbol());
                    underlyingsWithOptions.add(opt.getUnderlyingSymbol());

                    tempOptionsByUnderlying
                        .computeIfAbsent(opt.getUnderlyingSymbol(), k -> new ArrayList<>())
                        .add(opt);
                }
            }

            // Sort options by strike within each underlying
            for (List<OptionInstrument> options : tempOptionsByUnderlying.values()) {
                options.sort(Comparator.comparingDouble(o ->
                    o.getStrikePrice() != null ? o.getStrikePrice() : 0));
            }

            optionsByUnderlying.clear();
            optionsByUnderlying.putAll(tempOptionsByUnderlying);

            lastRefresh = Instant.now();
            log.info("{} Loaded {} option instruments for {} underlyings from MongoDB",
                LOG_PREFIX, optionByScripCode.size(), optionsByUnderlying.size());

        } catch (Exception e) {
            log.error("{} Failed to refresh from MongoDB: {}", LOG_PREFIX, e.getMessage());
        }
    }

    // ==================== LOOKUP METHODS ====================

    /**
     * Get underlying symbol for an option scripCode.
     */
    public String getUnderlyingForOption(String optionScripCode) {
        return optionToUnderlying.get(optionScripCode);
    }

    /**
     * Get option instrument by scripCode.
     */
    public Optional<OptionInstrument> getByScripCode(String scripCode) {
        return Optional.ofNullable(optionByScripCode.get(scripCode));
    }

    /**
     * Get all options for an underlying symbol.
     */
    public List<OptionInstrument> getOptionsForUnderlying(String underlyingSymbol) {
        return optionsByUnderlying.getOrDefault(underlyingSymbol, Collections.emptyList());
    }

    /**
     * Get ATM options for underlying at given spot price.
     */
    public List<OptionInstrument> getATMOptions(String underlyingSymbol, double spotPrice, int count) {
        List<OptionInstrument> allOptions = getOptionsForUnderlying(underlyingSymbol);
        if (allOptions.isEmpty()) return Collections.emptyList();

        return allOptions.stream()
            .filter(o -> o.getStrikePrice() != null)
            .sorted(Comparator.comparingDouble(o -> Math.abs(o.getStrikePrice() - spotPrice)))
            .limit(count)
            .collect(Collectors.toList());
    }

    /**
     * Get options near a specific strike for underlying.
     */
    public List<OptionInstrument> getOptionsNearStrike(String underlyingSymbol, double strike, int count) {
        List<OptionInstrument> allOptions = getOptionsForUnderlying(underlyingSymbol);
        if (allOptions.isEmpty()) return Collections.emptyList();

        return allOptions.stream()
            .filter(o -> o.getStrikePrice() != null)
            .sorted(Comparator.comparingDouble(o -> Math.abs(o.getStrikePrice() - strike)))
            .limit(count)
            .collect(Collectors.toList());
    }

    /**
     * Get CE options for underlying.
     */
    public List<OptionInstrument> getCallOptions(String underlyingSymbol) {
        return getOptionsForUnderlying(underlyingSymbol).stream()
            .filter(o -> "CE".equals(o.getOptionType()))
            .sorted(Comparator.comparingDouble(o -> o.getStrikePrice() != null ? o.getStrikePrice() : 0))
            .collect(Collectors.toList());
    }

    /**
     * Get PE options for underlying.
     */
    public List<OptionInstrument> getPutOptions(String underlyingSymbol) {
        return getOptionsForUnderlying(underlyingSymbol).stream()
            .filter(o -> "PE".equals(o.getOptionType()))
            .sorted(Comparator.comparingDouble(o -> o.getStrikePrice() != null ? o.getStrikePrice() : 0))
            .collect(Collectors.toList());
    }

    /**
     * Get option by strike, type and optionally expiry.
     */
    public Optional<OptionInstrument> findOption(String underlyingSymbol, double strike,
                                                   String optionType, String expiry) {
        return getOptionsForUnderlying(underlyingSymbol).stream()
            .filter(o -> o.getStrikePrice() != null &&
                        Math.abs(o.getStrikePrice() - strike) < 0.01 &&
                        optionType.equals(o.getOptionType()) &&
                        (expiry == null || expiry.equals(o.getExpiry())))
            .findFirst();
    }

    /**
     * Get nearest expiry options for underlying.
     */
    public List<OptionInstrument> getNearestExpiryOptions(String underlyingSymbol) {
        List<OptionInstrument> allOptions = getOptionsForUnderlying(underlyingSymbol);
        if (allOptions.isEmpty()) return Collections.emptyList();

        // Find options with expiry and get the nearest one
        Optional<String> nearestExpiry = allOptions.stream()
            .map(OptionInstrument::getExpiry)
            .filter(Objects::nonNull)
            .distinct()
            .min(Comparator.naturalOrder());  // Assumes expiry format sorts chronologically

        if (nearestExpiry.isEmpty()) return allOptions;

        String expiry = nearestExpiry.get();
        return allOptions.stream()
            .filter(o -> expiry.equals(o.getExpiry()))
            .collect(Collectors.toList());
    }

    /**
     * Check if symbol has derivatives (options).
     */
    public boolean hasDerivatives(String symbol) {
        // Check if this symbol is an underlying with options
        if (underlyingsWithOptions.contains(symbol)) {
            return true;
        }
        // Also check normalized versions (e.g., "NIFTY" vs "NIFTY 50")
        String normalized = symbol.toUpperCase().replace(" ", "");
        if (normalized.contains("NIFTY") || normalized.contains("BANKNIFTY")) {
            return underlyingsWithOptions.stream()
                .anyMatch(u -> u.toUpperCase().replace(" ", "").contains(normalized) ||
                              normalized.contains(u.toUpperCase().replace(" ", "")));
        }
        return false;
    }

    /**
     * Get all underlying symbols that have options.
     */
    public Set<String> getAllOptionableUnderlyings() {
        return new HashSet<>(underlyingsWithOptions);
    }

    /**
     * Get cache stats.
     */
    public CacheStats getCacheStats() {
        return CacheStats.builder()
            .optionCount(optionByScripCode.size())
            .underlyingWithOptionsCount(optionsByUnderlying.size())
            .lastRefresh(lastRefresh)
            .build();
    }

    // ==================== DATA CLASSES ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionInstrument {
        private String scripCode;
        private String symbol;
        private String underlyingSymbol;
        private String exchange;
        private String exchangeType;
        private Double strikePrice;
        private String optionType;  // CE or PE
        private String expiry;

        public boolean isCall() {
            return "CE".equals(optionType);
        }

        public boolean isPut() {
            return "PE".equals(optionType);
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CacheStats {
        private int optionCount;
        private int underlyingWithOptionsCount;
        private Instant lastRefresh;
    }
}
