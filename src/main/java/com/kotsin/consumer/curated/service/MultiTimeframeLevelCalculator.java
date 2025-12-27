package com.kotsin.consumer.curated.service;

import com.kotsin.consumer.curated.model.MultiTimeframeLevels;
import com.kotsin.consumer.curated.model.MultiTimeframeLevels.FibonacciLevels;
import com.kotsin.consumer.curated.model.MultiTimeframeLevels.PivotLevels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * MultiTimeframeLevelCalculator - Calculates Fibonacci and Pivot levels
 * across multiple timeframes (Daily, Weekly, Monthly)
 *
 * Uses historical price data API to get swing highs/lows and OHLC data.
 */
@Service
public class MultiTimeframeLevelCalculator {

    private static final Logger log = LoggerFactory.getLogger(MultiTimeframeLevelCalculator.class);

    @Value("${curated.levels.api.base-url:http://localhost:8080/api/historical}")
    private String historicalApiBaseUrl;

    @Value("${curated.levels.api.timeout-ms:3000}")
    private long apiTimeoutMs;

    @Value("${curated.levels.enabled:true}")
    private boolean levelsEnabled;

    private final RestTemplate restTemplate;
    private final ExecutorService executor;

    // Cache levels (valid until next period)
    private final ConcurrentHashMap<String, MultiTimeframeLevels> levelsCache = new ConcurrentHashMap<>();

    public MultiTimeframeLevelCalculator() {
        this.restTemplate = new RestTemplate();
        this.executor = Executors.newFixedThreadPool(5);
    }

    /**
     * Calculate all levels for a scrip
     */
    public MultiTimeframeLevels calculateLevels(String scripCode, double currentPrice) {
        if (!levelsEnabled) {
            log.debug("Multi-timeframe levels disabled");
            return null;
        }

        // Check cache (levels are valid until period changes)
        String cacheKey = scripCode + ":" + getCurrentPeriodKey();
        MultiTimeframeLevels cached = levelsCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            log.debug("Calculating multi-timeframe levels for {}", scripCode);

            // Calculate levels for each timeframe
            FibonacciLevels dailyFib = calculateFibonacci(scripCode, "daily");
            PivotLevels dailyPivot = calculatePivots(scripCode, "daily");

            FibonacciLevels weeklyFib = calculateFibonacci(scripCode, "weekly");
            PivotLevels weeklyPivot = calculatePivots(scripCode, "weekly");

            FibonacciLevels monthlyFib = calculateFibonacci(scripCode, "monthly");
            PivotLevels monthlyPivot = calculatePivots(scripCode, "monthly");

            MultiTimeframeLevels levels = MultiTimeframeLevels.builder()
                    .scripCode(scripCode)
                    .currentPrice(currentPrice)
                    .timestamp(System.currentTimeMillis())
                    .dailyFib(dailyFib)
                    .dailyPivot(dailyPivot)
                    .weeklyFib(weeklyFib)
                    .weeklyPivot(weeklyPivot)
                    .monthlyFib(monthlyFib)
                    .monthlyPivot(monthlyPivot)
                    .build();

            // Cache it
            levelsCache.put(cacheKey, levels);

            log.info("Multi-TF levels calculated for {}: D_Pivot={}, W_Pivot={}, M_Pivot={}",
                    scripCode,
                    dailyPivot != null ? String.format("%.2f", dailyPivot.getPivot()) : "N/A",
                    weeklyPivot != null ? String.format("%.2f", weeklyPivot.getPivot()) : "N/A",
                    monthlyPivot != null ? String.format("%.2f", monthlyPivot.getPivot()) : "N/A");

            return levels;

        } catch (Exception e) {
            log.error("Error calculating levels for {}: {}", scripCode, e.getMessage());
            return null;
        }
    }

    /**
     * Calculate Fibonacci retracement levels for a timeframe
     */
    private FibonacciLevels calculateFibonacci(String scripCode, String timeframe) {
        try {
            // Fetch swing high/low from API
            String url = historicalApiBaseUrl + "/swing/" + scripCode + "?timeframe=" + timeframe;

            CompletableFuture<SwingData> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return restTemplate.getForObject(url, SwingData.class);
                } catch (Exception e) {
                    log.error("Error fetching swing data for {} {}: {}", scripCode, timeframe, e.getMessage());
                    return null;
                }
            }, executor);

            SwingData swingData = future.get(apiTimeoutMs, TimeUnit.MILLISECONDS);

            if (swingData == null || swingData.getHigh() == 0 || swingData.getLow() == 0) {
                log.warn("Invalid swing data for {} {}", scripCode, timeframe);
                return null;
            }

            double high = swingData.getHigh();
            double low = swingData.getLow();
            double range = high - low;

            // Calculate Fibonacci retracement levels (from high to low)
            FibonacciLevels fib = FibonacciLevels.builder()
                    .timeframe(timeframe)
                    .swingHigh(high)
                    .swingLow(low)
                    .range(range)
                    .fib236(high - (range * 0.236))
                    .fib382(high - (range * 0.382))
                    .fib50(high - (range * 0.5))
                    .fib618(high - (range * 0.618))    // Golden ratio
                    .fib786(high - (range * 0.786))
                    // Extensions (beyond high)
                    .fib1272(high + (range * 0.272))
                    .fib1618(high + (range * 0.618))   // Golden ratio extension
                    .fib200(high + range)              // 200% extension
                    .build();

            log.debug("Fibonacci {} for {}: 0.382={}, 0.618={}", timeframe, scripCode,
                    String.format("%.2f", fib.getFib382()),
                    String.format("%.2f", fib.getFib618()));

            return fib;

        } catch (Exception e) {
            log.error("Error calculating Fibonacci for {} {}: {}", scripCode, timeframe, e.getMessage());
            return null;
        }
    }

    /**
     * Calculate pivot points for a timeframe
     */
    private PivotLevels calculatePivots(String scripCode, String timeframe) {
        try {
            // Fetch OHLC for previous period
            String url = historicalApiBaseUrl + "/ohlc/" + scripCode + "?timeframe=" + timeframe + "&period=previous";

            CompletableFuture<OHLCData> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return restTemplate.getForObject(url, OHLCData.class);
                } catch (Exception e) {
                    log.error("Error fetching OHLC for {} {}: {}", scripCode, timeframe, e.getMessage());
                    return null;
                }
            }, executor);

            OHLCData ohlc = future.get(apiTimeoutMs, TimeUnit.MILLISECONDS);

            if (ohlc == null || ohlc.getHigh() == 0 || ohlc.getLow() == 0) {
                log.warn("Invalid OHLC data for {} {}", scripCode, timeframe);
                return null;
            }

            double high = ohlc.getHigh();
            double low = ohlc.getLow();
            double close = ohlc.getClose();

            // Classic Pivot Point formula
            double pivot = (high + low + close) / 3.0;

            // Resistance levels
            double r1 = (2 * pivot) - low;
            double r2 = pivot + (high - low);
            double r3 = high + (2 * (pivot - low));
            double r4 = high + (3 * (pivot - low));

            // Support levels
            double s1 = (2 * pivot) - high;
            double s2 = pivot - (high - low);
            double s3 = low - (2 * (high - pivot));
            double s4 = low - (3 * (high - pivot));

            // CPR (Camarilla)
            double tc = close + ((high - low) * 1.1 / 12);
            double bc = close - ((high - low) * 1.1 / 12);
            double cprWidth = ((tc - bc) / close) * 100;

            // Classify CPR width
            PivotLevels.CPRWidth cprType;
            if (cprWidth < 0.3) {
                cprType = PivotLevels.CPRWidth.NARROW;
            } else if (cprWidth < 0.7) {
                cprType = PivotLevels.CPRWidth.NORMAL;
            } else {
                cprType = PivotLevels.CPRWidth.WIDE;
            }

            PivotLevels pivots = PivotLevels.builder()
                    .timeframe(timeframe)
                    .high(high)
                    .low(low)
                    .close(close)
                    .pivot(pivot)
                    .r1(r1)
                    .r2(r2)
                    .r3(r3)
                    .r4(r4)
                    .s1(s1)
                    .s2(s2)
                    .s3(s3)
                    .s4(s4)
                    .tc(tc)
                    .bc(bc)
                    .cprWidth(cprWidth)
                    .cprType(cprType)
                    .build();

            log.debug("Pivots {} for {}: P={}, R1={}, S1={}, CPR={}",
                    timeframe, scripCode,
                    String.format("%.2f", pivot),
                    String.format("%.2f", r1),
                    String.format("%.2f", s1),
                    cprType);

            return pivots;

        } catch (Exception e) {
            log.error("Error calculating pivots for {} {}: {}", scripCode, timeframe, e.getMessage());
            return null;
        }
    }

    /**
     * Get cache key based on current period (day/week/month)
     * Levels change when period changes
     */
    private String getCurrentPeriodKey() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        int dayOfYear = today.getDayOfYear();
        int weekOfYear = today.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear());
        int month = today.getMonthValue();

        return dayOfYear + "-" + weekOfYear + "-" + month;
    }

    /**
     * Clear cache (call daily)
     */
    public void clearCache() {
        levelsCache.clear();
        log.info("Multi-timeframe levels cache cleared");
    }

    // DTO classes for API responses

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    private static class SwingData {
        private double high;
        private double low;
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    private static class OHLCData {
        private double open;
        private double high;
        private double low;
        private double close;
    }
}
