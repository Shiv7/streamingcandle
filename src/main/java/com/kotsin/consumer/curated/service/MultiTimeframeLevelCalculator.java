package com.kotsin.consumer.curated.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.curated.model.MultiTimeframeLevels;
import com.kotsin.consumer.curated.model.MultiTimeframeLevels.FibonacciLevels;
import com.kotsin.consumer.curated.model.MultiTimeframeLevels.PivotLevels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * MultiTimeframeLevelCalculator - Calculates Fibonacci and Pivot levels
 * across multiple timeframes (Daily, Weekly, Monthly)
 *
 * Uses 5paisa historical data API to get OHLC data and calculates swing levels.
 */
@Service
public class MultiTimeframeLevelCalculator {

    private static final Logger log = LoggerFactory.getLogger(MultiTimeframeLevelCalculator.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Value("${curated.levels.api.base-url:http://13.203.60.173:8002}")
    private String historicalApiBaseUrl;

    @Value("${curated.levels.api.timeout-ms:5000}")
    private long apiTimeoutMs;

    @Value("${curated.levels.enabled:false}")
    private boolean levelsEnabled;

    @Value("${curated.levels.api.exch:N}")
    private String defaultExch;

    @Value("${curated.levels.api.exch-type:C}")
    private String defaultExchType;

    private final RestTemplate restTemplate;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper;

    // Cache levels (valid until next period)
    private final ConcurrentHashMap<String, MultiTimeframeLevels> levelsCache = new ConcurrentHashMap<>();
    
    // Cache for historical OHLC data (key: scripCode, value: list of candles)
    private final ConcurrentHashMap<String, List<OHLCData>> ohlcCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> ohlcCacheTimestamp = new ConcurrentHashMap<>();
    private static final long OHLC_CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes

    public MultiTimeframeLevelCalculator() {
        this.restTemplate = new RestTemplate();
        this.executor = Executors.newFixedThreadPool(5);
        this.objectMapper = new ObjectMapper();
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
     * Fetch 1-minute OHLC data from 5paisa API and cache it
     */
    private List<OHLCData> fetchHistoricalData(String scripCode) {
        // Check cache
        Long cacheTime = ohlcCacheTimestamp.get(scripCode);
        if (cacheTime != null && (System.currentTimeMillis() - cacheTime) < OHLC_CACHE_TTL_MS) {
            List<OHLCData> cached = ohlcCache.get(scripCode);
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
        }

        try {
            LocalDate endDate = LocalDate.now(IST);
            LocalDate startDate = endDate.minusDays(50);

            // Build API URL: /getHisDataFromFivePaisa
            String url = String.format("%s/getHisDataFromFivePaisa?exch=%s&exch_type=%s&scrip_code=%s&start_date=%s&end_date=%s&interval=1m",
                    historicalApiBaseUrl, defaultExch, defaultExchType, scripCode,
                    startDate.format(DATE_FORMAT), endDate.format(DATE_FORMAT));

            log.debug("Fetching historical data from: {}", url);

            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return restTemplate.getForObject(url, String.class);
                } catch (Exception e) {
                    log.error("Error fetching historical data for {}: {}", scripCode, e.getMessage());
                    return null;
                }
            }, executor);

            String response = future.get(apiTimeoutMs, TimeUnit.MILLISECONDS);
            if (response == null || response.isEmpty()) {
                log.warn("Empty response for historical data: {}", scripCode);
                return Collections.emptyList();
            }

            // Parse JSON response
            List<OHLCData> candles = parseHistoricalResponse(response);
            
            if (!candles.isEmpty()) {
                ohlcCache.put(scripCode, candles);
                ohlcCacheTimestamp.put(scripCode, System.currentTimeMillis());
                log.info("Fetched {} 1m candles for {}", candles.size(), scripCode);
            }

            return candles;

        } catch (Exception e) {
            log.error("Error fetching historical data for {}: {}", scripCode, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Parse 5paisa API response to list of OHLC data
     */
    private List<OHLCData> parseHistoricalResponse(String response) {
        List<OHLCData> candles = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(response);
            
            // Handle both array and object responses
            JsonNode dataNode = root.isArray() ? root : root.path("data");
            if (dataNode.isMissingNode()) {
                dataNode = root.path("candles");
            }
            if (dataNode.isMissingNode()) {
                dataNode = root;
            }

            for (JsonNode candle : dataNode) {
                try {
                    OHLCData ohlc = new OHLCData();
                    
                    // Try different field names
                    ohlc.setOpen(getDoubleField(candle, "open", "Open", "o"));
                    ohlc.setHigh(getDoubleField(candle, "high", "High", "h"));
                    ohlc.setLow(getDoubleField(candle, "low", "Low", "l"));
                    ohlc.setClose(getDoubleField(candle, "close", "Close", "c"));
                    
                    // Parse timestamp
                    String timeStr = getStringField(candle, "datetime", "Datetime", "time", "Time", "timestamp");
                    if (timeStr != null) {
                        ohlc.setTimestamp(parseTimestamp(timeStr));
                    }

                    if (ohlc.getHigh() > 0 && ohlc.getLow() > 0) {
                        candles.add(ohlc);
                    }
                } catch (Exception e) {
                    // Skip invalid candle
                }
            }
        } catch (Exception e) {
            log.error("Error parsing historical response: {}", e.getMessage());
        }
        return candles;
    }

    private double getDoubleField(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && value.isNumber()) {
                return value.asDouble();
            }
        }
        return 0;
    }

    private String getStringField(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && value.isTextual()) {
                return value.asText();
            }
        }
        return null;
    }

    private long parseTimestamp(String timeStr) {
        try {
            // Try various formats
            if (timeStr.contains("T")) {
                return LocalDateTime.parse(timeStr.replace("Z", "")).atZone(IST).toInstant().toEpochMilli();
            } else if (timeStr.contains(" ")) {
                return LocalDateTime.parse(timeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(IST).toInstant().toEpochMilli();
            }
        } catch (Exception e) {
            // Ignore
        }
        return System.currentTimeMillis();
    }

    /**
     * Resample 1m candles to daily OHLC
     */
    private List<OHLCData> resampleToDaily(List<OHLCData> minuteCandles) {
        Map<LocalDate, OHLCData> dailyMap = new TreeMap<>();
        
        for (OHLCData candle : minuteCandles) {
            LocalDate date = Instant.ofEpochMilli(candle.getTimestamp())
                    .atZone(IST).toLocalDate();
            
            OHLCData daily = dailyMap.get(date);
            if (daily == null) {
                daily = new OHLCData(candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose());
                daily.setTimestamp(candle.getTimestamp());
                dailyMap.put(date, daily);
            } else {
                daily.setHigh(Math.max(daily.getHigh(), candle.getHigh()));
                daily.setLow(Math.min(daily.getLow(), candle.getLow()));
                daily.setClose(candle.getClose());
            }
        }
        
        return new ArrayList<>(dailyMap.values());
    }

    /**
     * Resample 1m candles to weekly OHLC
     */
    private List<OHLCData> resampleToWeekly(List<OHLCData> minuteCandles) {
        Map<Integer, OHLCData> weeklyMap = new TreeMap<>();
        
        for (OHLCData candle : minuteCandles) {
            LocalDate date = Instant.ofEpochMilli(candle.getTimestamp()).atZone(IST).toLocalDate();
            int weekKey = date.getYear() * 100 + date.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear());
            
            OHLCData weekly = weeklyMap.get(weekKey);
            if (weekly == null) {
                weekly = new OHLCData(candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose());
                weekly.setTimestamp(candle.getTimestamp());
                weeklyMap.put(weekKey, weekly);
            } else {
                weekly.setHigh(Math.max(weekly.getHigh(), candle.getHigh()));
                weekly.setLow(Math.min(weekly.getLow(), candle.getLow()));
                weekly.setClose(candle.getClose());
            }
        }
        
        return new ArrayList<>(weeklyMap.values());
    }

    /**
     * Resample 1m candles to monthly OHLC
     */
    private List<OHLCData> resampleToMonthly(List<OHLCData> minuteCandles) {
        Map<Integer, OHLCData> monthlyMap = new TreeMap<>();
        
        for (OHLCData candle : minuteCandles) {
            LocalDate date = Instant.ofEpochMilli(candle.getTimestamp()).atZone(IST).toLocalDate();
            int monthKey = date.getYear() * 100 + date.getMonthValue();
            
            OHLCData monthly = monthlyMap.get(monthKey);
            if (monthly == null) {
                monthly = new OHLCData(candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose());
                monthly.setTimestamp(candle.getTimestamp());
                monthlyMap.put(monthKey, monthly);
            } else {
                monthly.setHigh(Math.max(monthly.getHigh(), candle.getHigh()));
                monthly.setLow(Math.min(monthly.getLow(), candle.getLow()));
                monthly.setClose(candle.getClose());
            }
        }
        
        return new ArrayList<>(monthlyMap.values());
    }

    /**
     * Calculate Fibonacci retracement levels for a timeframe
     */
    private FibonacciLevels calculateFibonacci(String scripCode, String timeframe) {
        try {
            List<OHLCData> minuteCandles = fetchHistoricalData(scripCode);
            if (minuteCandles.isEmpty()) {
                return null;
            }

            // Resample to appropriate timeframe
            List<OHLCData> candles;
            switch (timeframe) {
                case "weekly":
                    candles = resampleToWeekly(minuteCandles);
                    break;
                case "monthly":
                    candles = resampleToMonthly(minuteCandles);
                    break;
                default: // daily
                    candles = resampleToDaily(minuteCandles);
            }

            if (candles.isEmpty()) {
                return null;
            }

            // Find swing high/low from resampled data
            double swingHigh = candles.stream().mapToDouble(OHLCData::getHigh).max().orElse(0);
            double swingLow = candles.stream().mapToDouble(OHLCData::getLow).min().orElse(0);

            if (swingHigh == 0 || swingLow == 0) {
                return null;
            }

            double range = swingHigh - swingLow;

            // Calculate Fibonacci retracement levels (from high to low)
            FibonacciLevels fib = FibonacciLevels.builder()
                    .timeframe(timeframe)
                    .swingHigh(swingHigh)
                    .swingLow(swingLow)
                    .range(range)
                    .fib236(swingHigh - (range * 0.236))
                    .fib382(swingHigh - (range * 0.382))
                    .fib50(swingHigh - (range * 0.5))
                    .fib618(swingHigh - (range * 0.618))    // Golden ratio
                    .fib786(swingHigh - (range * 0.786))
                    // Extensions (beyond high)
                    .fib1272(swingHigh + (range * 0.272))
                    .fib1618(swingHigh + (range * 0.618))   // Golden ratio extension
                    .fib200(swingHigh + range)              // 200% extension
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
            List<OHLCData> minuteCandles = fetchHistoricalData(scripCode);
            if (minuteCandles.isEmpty()) {
                return null;
            }

            // Resample to appropriate timeframe
            List<OHLCData> candles;
            switch (timeframe) {
                case "weekly":
                    candles = resampleToWeekly(minuteCandles);
                    break;
                case "monthly":
                    candles = resampleToMonthly(minuteCandles);
                    break;
                default: // daily
                    candles = resampleToDaily(minuteCandles);
            }

            if (candles.size() < 2) {
                return null;
            }

            // Get previous period OHLC (second to last)
            OHLCData ohlc = candles.get(candles.size() - 2);

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
    private static class OHLCData {
        private double open;
        private double high;
        private double low;
        private double close;
        private long timestamp;

        public OHLCData(double open, double high, double low, double close) {
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
