package com.kotsin.consumer.options.service;

import com.kotsin.consumer.model.OIMetrics;
import com.kotsin.consumer.options.model.OptionGreeks;
import com.kotsin.consumer.options.model.OptionGreeks.MoneynessType;
import com.kotsin.consumer.options.model.OptionGreeks.OptionType;
import com.kotsin.consumer.repository.OIMetricsRepository;
import com.kotsin.consumer.service.RedisCacheService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OptionChainService - Aggregates OIMetrics into full option chains.
 *
 * Responsibilities:
 * 1. Query OIMetrics by underlying symbol
 * 2. Group by strike and option type (CE/PE)
 * 3. Calculate chain-level metrics (PCR, max OI strikes, etc.)
 * 4. Provide option chain data for analytics
 */
@Service
@Slf4j
public class OptionChainService {

    private static final String LOG_PREFIX = "[OPTION-CHAIN]";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired
    private OIMetricsRepository oiMetricsRepository;

    @Autowired
    private RedisCacheService redisCacheService;

    /**
     * Build complete option chain for an underlying symbol.
     *
     * @param underlyingSymbol e.g., "NIFTY", "BANKNIFTY"
     * @return OptionChain with all strikes and OI data
     */
    public OptionChain buildChain(String underlyingSymbol) {
        return buildChain(underlyingSymbol, null);
    }

    /**
     * Build option chain for specific expiry.
     *
     * @param underlyingSymbol e.g., "NIFTY", "BANKNIFTY"
     * @param expiry           Expiry date string (e.g., "30JAN" or "2026-01-30"), null for all expiries
     * @return OptionChain with filtered strikes
     */
    public OptionChain buildChain(String underlyingSymbol, String expiry) {
        log.debug("{} Building chain for {} expiry={}", LOG_PREFIX, underlyingSymbol, expiry);

        // Get current spot price from Redis
        Double spotPrice = getSpotPrice(underlyingSymbol);
        if (spotPrice == null || spotPrice <= 0) {
            log.warn("{} No spot price for {}", LOG_PREFIX, underlyingSymbol);
            return OptionChain.empty(underlyingSymbol);
        }

        // Query latest OIMetrics for this underlying
        // Get all recent metrics (within last 5 minutes)
        Instant cutoff = Instant.now().minus(5, ChronoUnit.MINUTES);
        List<OIMetrics> allMetrics = queryRecentMetrics(underlyingSymbol, cutoff);

        if (allMetrics.isEmpty()) {
            log.warn("{} No OI metrics found for {}", LOG_PREFIX, underlyingSymbol);
            return OptionChain.empty(underlyingSymbol);
        }

        log.debug("{} Found {} OI metrics for {}", LOG_PREFIX, allMetrics.size(), underlyingSymbol);

        // Filter by expiry if specified
        if (expiry != null && !expiry.isEmpty()) {
            allMetrics = allMetrics.stream()
                .filter(m -> m.getExpiry() != null && m.getExpiry().contains(expiry))
                .collect(Collectors.toList());
        }

        // Group by strike -> optionType -> OIMetrics
        Map<Double, Map<String, OIMetrics>> strikeMap = new TreeMap<>();

        for (OIMetrics metric : allMetrics) {
            if (metric.getStrikePrice() == null || metric.getOptionType() == null) {
                continue;
            }

            double strike = metric.getStrikePrice();
            String optType = metric.getOptionType();

            strikeMap.computeIfAbsent(strike, k -> new HashMap<>())
                     .put(optType, metric);
        }

        // Build strike entries
        List<StrikeEntry> strikes = new ArrayList<>();
        long totalCallOI = 0;
        long totalPutOI = 0;
        double maxCallOIStrike = 0;
        double maxPutOIStrike = 0;
        long maxCallOI = 0;
        long maxPutOI = 0;

        for (Map.Entry<Double, Map<String, OIMetrics>> entry : strikeMap.entrySet()) {
            double strike = entry.getKey();
            Map<String, OIMetrics> types = entry.getValue();

            OIMetrics callMetric = types.get("CE");
            OIMetrics putMetric = types.get("PE");

            long callOI = callMetric != null ? callMetric.getOpenInterest() : 0;
            long putOI = putMetric != null ? putMetric.getOpenInterest() : 0;
            long callOIChange = callMetric != null ? callMetric.getOiChange() : 0;
            long putOIChange = putMetric != null ? putMetric.getOiChange() : 0;

            totalCallOI += callOI;
            totalPutOI += putOI;

            if (callOI > maxCallOI) {
                maxCallOI = callOI;
                maxCallOIStrike = strike;
            }
            if (putOI > maxPutOI) {
                maxPutOI = putOI;
                maxPutOIStrike = strike;
            }

            // Determine moneyness
            MoneynessType moneyness = calculateMoneyness(strike, spotPrice);

            StrikeEntry strikeEntry = StrikeEntry.builder()
                .strike(strike)
                .callOI(callOI)
                .putOI(putOI)
                .callOIChange(callOIChange)
                .putOIChange(putOIChange)
                .callScripCode(callMetric != null ? callMetric.getScripCode() : null)
                .putScripCode(putMetric != null ? putMetric.getScripCode() : null)
                .callInterpretation(callMetric != null ? callMetric.getInterpretation() : null)
                .putInterpretation(putMetric != null ? putMetric.getInterpretation() : null)
                .moneyness(moneyness)
                .distanceFromSpot((strike - spotPrice) / spotPrice * 100)
                .expiry(callMetric != null ? callMetric.getExpiry() :
                        (putMetric != null ? putMetric.getExpiry() : null))
                .build();

            strikes.add(strikeEntry);
        }

        // Calculate PCR
        double pcrByOI = totalCallOI > 0 ? (double) totalPutOI / totalCallOI : 1.0;

        // Find ATM strike (closest to spot)
        double atmStrike = findATMStrike(strikes, spotPrice);

        // Calculate max pain
        double maxPain = calculateMaxPain(strikes, spotPrice);

        // Determine OI-based expected range
        double[] oiRange = new double[]{maxPutOIStrike, maxCallOIStrike};

        OptionChain chain = OptionChain.builder()
            .underlyingSymbol(underlyingSymbol)
            .spotPrice(spotPrice)
            .atmStrike(atmStrike)
            .strikes(strikes)
            .totalCallOI(totalCallOI)
            .totalPutOI(totalPutOI)
            .pcrByOI(pcrByOI)
            .maxCallOIStrike(maxCallOIStrike)
            .maxPutOIStrike(maxPutOIStrike)
            .maxPain(maxPain)
            .oiBasedRange(oiRange)
            .timestamp(Instant.now())
            .expiry(expiry)
            .strikeCount(strikes.size())
            .build();

        log.info("{} Built chain for {}: {} strikes, PCR={}, ATM={}, MaxPain={}",
            LOG_PREFIX, underlyingSymbol, strikes.size(),
            String.format("%.2f", pcrByOI), atmStrike, maxPain);

        return chain;
    }

    /**
     * Get spot price for underlying from Redis cache.
     */
    private Double getSpotPrice(String underlyingSymbol) {
        // Try multiple scripCode patterns for index
        String[] possibleCodes = {
            underlyingSymbol,
            underlyingSymbol.equals("NIFTY") ? "999920000" : null,
            underlyingSymbol.equals("BANKNIFTY") ? "999920005" : null
        };

        for (String code : possibleCodes) {
            if (code != null) {
                Double price = redisCacheService.getLastPrice(code);
                if (price != null && price > 0) {
                    return price;
                }
            }
        }

        log.warn("{} Could not find spot price for {}", LOG_PREFIX, underlyingSymbol);
        return null;
    }

    /**
     * Query recent OI metrics for underlying.
     */
    private List<OIMetrics> queryRecentMetrics(String underlyingSymbol, Instant cutoff) {
        // Query by underlyingSymbol field
        // Get latest metrics for each scripCode
        List<OIMetrics> results = new ArrayList<>();

        // First try direct query
        try {
            // Get all recent metrics and filter
            List<OIMetrics> recent = oiMetricsRepository
                .findByScripCodeOrderByTimestampDesc(underlyingSymbol, PageRequest.of(0, 1000));

            // Also query by underlying
            // This is a workaround - ideally we'd have a proper index
            // For now, we query recent metrics and filter by underlying
            List<OIMetrics> allRecent = new ArrayList<>();

            // Query for common underlying patterns
            String[] patterns = {
                underlyingSymbol,
                underlyingSymbol.toUpperCase()
            };

            for (String pattern : patterns) {
                List<OIMetrics> metrics = oiMetricsRepository
                    .findByScripCodeOrderByTimestampDesc(pattern, PageRequest.of(0, 500));
                allRecent.addAll(metrics);
            }

            // Filter by underlying symbol and recent timestamp
            Map<String, OIMetrics> latestByScripCode = new HashMap<>();
            for (OIMetrics metric : allRecent) {
                if (metric.getUnderlyingSymbol() != null &&
                    metric.getUnderlyingSymbol().equalsIgnoreCase(underlyingSymbol) &&
                    metric.getTimestamp() != null &&
                    metric.getTimestamp().isAfter(cutoff)) {

                    String key = metric.getScripCode();
                    OIMetrics existing = latestByScripCode.get(key);
                    if (existing == null || metric.getTimestamp().isAfter(existing.getTimestamp())) {
                        latestByScripCode.put(key, metric);
                    }
                }
            }

            results.addAll(latestByScripCode.values());

        } catch (Exception e) {
            log.error("{} Error querying OI metrics: {}", LOG_PREFIX, e.getMessage());
        }

        return results;
    }

    /**
     * Calculate moneyness type.
     */
    private MoneynessType calculateMoneyness(double strike, double spot) {
        double diff = (strike - spot) / spot;

        if (Math.abs(diff) < 0.01) return MoneynessType.ATM;
        if (diff > 0.05) return MoneynessType.DEEP_OTM;  // For calls
        if (diff > 0) return MoneynessType.OTM;
        if (diff < -0.05) return MoneynessType.DEEP_ITM;  // For calls
        return MoneynessType.ITM;
    }

    /**
     * Find ATM strike (closest to spot).
     */
    private double findATMStrike(List<StrikeEntry> strikes, double spotPrice) {
        return strikes.stream()
            .min(Comparator.comparingDouble(s -> Math.abs(s.getStrike() - spotPrice)))
            .map(StrikeEntry::getStrike)
            .orElse(spotPrice);
    }

    /**
     * Calculate max pain strike.
     */
    private double calculateMaxPain(List<StrikeEntry> strikes, double spotPrice) {
        if (strikes.isEmpty()) return spotPrice;

        double minPain = Double.MAX_VALUE;
        double maxPainStrike = spotPrice;

        for (StrikeEntry testStrike : strikes) {
            double pain = 0;

            for (StrikeEntry s : strikes) {
                // Call pain: call OI * max(0, testStrike - callStrike)
                if (s.getCallOI() > 0) {
                    pain += s.getCallOI() * Math.max(0, testStrike.getStrike() - s.getStrike());
                }
                // Put pain: put OI * max(0, putStrike - testStrike)
                if (s.getPutOI() > 0) {
                    pain += s.getPutOI() * Math.max(0, s.getStrike() - testStrike.getStrike());
                }
            }

            if (pain < minPain) {
                minPain = pain;
                maxPainStrike = testStrike.getStrike();
            }
        }

        return maxPainStrike;
    }

    /**
     * Get strikes near a specific price level.
     */
    public List<StrikeEntry> getStrikesNearPrice(String underlyingSymbol, double price, int count) {
        OptionChain chain = buildChain(underlyingSymbol);
        if (chain.getStrikes() == null || chain.getStrikes().isEmpty()) {
            return Collections.emptyList();
        }

        return chain.getStrikes().stream()
            .sorted(Comparator.comparingDouble(s -> Math.abs(s.getStrike() - price)))
            .limit(count)
            .collect(Collectors.toList());
    }

    /**
     * Get ATM and nearby strikes.
     */
    public List<StrikeEntry> getATMStrikes(String underlyingSymbol, int countEachSide) {
        OptionChain chain = buildChain(underlyingSymbol);
        if (chain.getStrikes() == null || chain.getStrikes().isEmpty()) {
            return Collections.emptyList();
        }

        double atm = chain.getAtmStrike();
        List<StrikeEntry> sorted = chain.getStrikes().stream()
            .sorted(Comparator.comparingDouble(StrikeEntry::getStrike))
            .collect(Collectors.toList());

        int atmIndex = 0;
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getStrike() >= atm) {
                atmIndex = i;
                break;
            }
        }

        int start = Math.max(0, atmIndex - countEachSide);
        int end = Math.min(sorted.size(), atmIndex + countEachSide + 1);

        return sorted.subList(start, end);
    }

    // ==================== DATA CLASSES ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionChain {
        private String underlyingSymbol;
        private double spotPrice;
        private double atmStrike;
        private List<StrikeEntry> strikes;
        private long totalCallOI;
        private long totalPutOI;
        private double pcrByOI;
        private double maxCallOIStrike;
        private double maxPutOIStrike;
        private double maxPain;
        private double[] oiBasedRange;
        private Instant timestamp;
        private String expiry;
        private int strikeCount;

        public static OptionChain empty(String underlying) {
            return OptionChain.builder()
                .underlyingSymbol(underlying)
                .strikes(Collections.emptyList())
                .pcrByOI(1.0)
                .timestamp(Instant.now())
                .strikeCount(0)
                .build();
        }

        public boolean isEmpty() {
            return strikes == null || strikes.isEmpty();
        }

        public String getPCRSignal() {
            if (pcrByOI > 1.5) return "EXTREME_BEARISH";
            if (pcrByOI > 1.2) return "BEARISH";
            if (pcrByOI < 0.5) return "EXTREME_BULLISH";
            if (pcrByOI < 0.8) return "BULLISH";
            return "NEUTRAL";
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StrikeEntry {
        private double strike;
        private long callOI;
        private long putOI;
        private long callOIChange;
        private long putOIChange;
        private String callScripCode;
        private String putScripCode;
        private OIMetrics.OIInterpretation callInterpretation;
        private OIMetrics.OIInterpretation putInterpretation;
        private MoneynessType moneyness;
        private double distanceFromSpot;
        private String expiry;

        public long getTotalOI() {
            return callOI + putOI;
        }

        public double getStrikePCR() {
            return callOI > 0 ? (double) putOI / callOI : 1.0;
        }

        public boolean isCallBullish() {
            return callInterpretation == OIMetrics.OIInterpretation.LONG_BUILDUP ||
                   callInterpretation == OIMetrics.OIInterpretation.SHORT_COVERING;
        }

        public boolean isPutBullish() {
            return putInterpretation == OIMetrics.OIInterpretation.SHORT_COVERING ||
                   putInterpretation == OIMetrics.OIInterpretation.LONG_UNWINDING;
        }
    }
}
