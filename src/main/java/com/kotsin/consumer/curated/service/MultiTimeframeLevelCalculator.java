package com.kotsin.consumer.curated.service;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.curated.model.MultiTimeframeLevels;
import com.kotsin.consumer.curated.model.MultiTimeframeLevels.FibonacciLevels;
import com.kotsin.consumer.curated.model.MultiTimeframeLevels.PivotLevels;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MultiTimeframeLevelCalculator - Calculates Fibonacci and Pivot levels
 * across multiple timeframes (Daily, Weekly, Monthly)
 *
 * Uses 5paisa historical data API (same as TradeExecutionModule) to get OHLC data.
 * 
 * API Response format:
 * [
 *   {
 *     "Datetime": "2025-12-26T09:15:00",
 *     "Open": 4115,
 *     "High": 4125,
 *     "Low": 4107,
 *     "Close": 4125,
 *     "Volume": 4300
 *   },
 *   ...
 * ]
 */
@Service
public class MultiTimeframeLevelCalculator {

    private static final Logger log = LoggerFactory.getLogger(MultiTimeframeLevelCalculator.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Value("${curated.levels.api.base-url:http://localhost:8002}")
    private String historicalApiBaseUrl;

    @Value("${curated.levels.api.timeout-ms:5000}")
    private long apiTimeoutMs;

    @Value("${curated.levels.enabled:false}")
    private boolean levelsEnabled;

    @Value("${curated.levels.api.exch:n}")
    private String defaultExch;

    @Value("${curated.levels.api.exch-type:c}")
    private String defaultExchType;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    // Thread pool for async HTTP calls
    private final ExecutorService httpExecutor;

    // Cache levels (valid until next period)
    private final ConcurrentHashMap<String, MultiTimeframeLevels> levelsCache = new ConcurrentHashMap<>();
    
    // Cache for historical OHLC data (key: scripCode, value: list of candles)
    private final ConcurrentHashMap<String, List<OHLCData>> ohlcCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> ohlcCacheTimestamp = new ConcurrentHashMap<>();
    // Cache for whole day - pivots/Fibonacci don't change during the day
    // Only refresh when day changes (checked in fetchHistoricalData)
    private static final long OHLC_CACHE_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours (whole day)

    public MultiTimeframeLevelCalculator() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
        // Thread pool for async HTTP calls (I/O bound work)
        this.httpExecutor = Executors.newFixedThreadPool(10, r -> {
            Thread t = new Thread(r, "level-calc-http");
            t.setDaemon(true);
            return t;
        });
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
     * Uses same approach as TradeExecutionModule's HistoricalDataClient
     */
    private List<OHLCData> fetchHistoricalData(String scripCode) {
        // Check cache - cache is valid for whole day (pivots/Fibonacci don't change during day)
        Long cacheTime = ohlcCacheTimestamp.get(scripCode);
        if (cacheTime != null) {
            long age = System.currentTimeMillis() - cacheTime;
            // Check if cached data is from same day (not just TTL)
            LocalDate cacheDate = Instant.ofEpochMilli(cacheTime).atZone(IST).toLocalDate();
            LocalDate today = LocalDate.now(IST);
            
            if (cacheDate.equals(today) && age < OHLC_CACHE_TTL_MS) {
                List<OHLCData> cached = ohlcCache.get(scripCode);
                if (cached != null && !cached.isEmpty()) {
                    log.debug("Cache HIT for {}: {} candles (cached today)", scripCode, cached.size());
                    return cached;
                }
            } else {
                // Cache expired (new day) - clear it
                log.debug("Cache expired for {} (new day), will fetch fresh data", scripCode);
                ohlcCache.remove(scripCode);
                ohlcCacheTimestamp.remove(scripCode);
            }
        }

        try {
            LocalDate endDate = LocalDate.now(IST).plusDays(1); // API needs +1 day to include last day
            LocalDate startDate = endDate.minusDays(51); // 50 days back from today

            // FIX: Detect MCX commodities based on scripCode range
            // MCX commodity tokens are typically in the 400000-550000 range
            String exch = defaultExch;
            String exchType = defaultExchType;
            try {
                int token = Integer.parseInt(scripCode);
                if (token >= 400000 && token < 550000) {
                    // MCX commodity - use M:D
                    exch = "m";
                    exchType = "d";
                    log.debug("Detected MCX commodity for scripCode {}, using exch={} exchType={}", 
                            scripCode, exch, exchType);
                }
            } catch (NumberFormatException e) {
                // Not a numeric scripCode, use defaults
            }

            // Build URL exactly like TradeExecutionModule
            HttpUrl url = HttpUrl.parse(historicalApiBaseUrl)
                    .newBuilder()
                    .addPathSegments("getHisDataFromFivePaisa")
                    .addQueryParameter("scrip_code", scripCode)
                    .addQueryParameter("start_date", startDate.toString())
                    .addQueryParameter("end_date", endDate.toString())
                    .addQueryParameter("exch", exch.toLowerCase())
                    .addQueryParameter("exch_type", exchType.toLowerCase())
                    .addQueryParameter("interval", "1m")
                    .build();

            log.debug("Fetching historical candles: {}", url);

            Request request = new Request.Builder().url(url).get().build();

            // Make async HTTP call with proper resource cleanup
            final AtomicReference<Response> responseRef = new AtomicReference<>();
            CompletableFuture<List<OHLCData>> dataFuture = CompletableFuture.supplyAsync(() -> {
                Response response = null;
                try {
                    response = httpClient.newCall(request).execute();
                    responseRef.set(response);  // Track for cleanup

                    if (!response.isSuccessful() || response.body() == null) {
                        log.warn("Historical API non-200/empty for {}: status={}", scripCode, response.code());
                        return Collections.emptyList();
                    }

                    // Parse JSON array response
                    List<OHLCData> candles = objectMapper.readValue(
                            response.body().byteStream(),
                            new TypeReference<List<OHLCData>>() {}
                    );

                    log.info("Fetched {} 1m candles for {} from {} to {}",
                            candles.size(), scripCode, startDate, endDate);

                    return candles;

                } catch (Exception e) {
                    log.error("HTTP call failed for {}: {}", scripCode, e.getMessage());
                    return Collections.emptyList();
                } finally {
                    // Always close response to prevent leaks
                    if (response != null) {
                        response.close();
                    }
                }
            }, httpExecutor);

            // Wait for response with timeout
            List<OHLCData> candles;
            try {
                candles = dataFuture.get(8, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.error("HTTP call timed out for {}: timeout", scripCode);
                // Cancel future and close any pending response
                dataFuture.cancel(true);
                Response pendingResponse = responseRef.get();
                if (pendingResponse != null) {
                    pendingResponse.close();
                }
                return Collections.emptyList();
            }

            // Cache the result if not empty
            if (candles != null && !candles.isEmpty()) {
                ohlcCache.put(scripCode, candles);
                ohlcCacheTimestamp.put(scripCode, System.currentTimeMillis());
            }

            return candles != null ? candles : Collections.emptyList();

        } catch (Exception e) {
            log.error("Error fetching historical data for {}: {}", scripCode, e.getMessage());
            return Collections.emptyList();
        }
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

    /**
     * OHLC Data matching 5paisa API response format (same as TradeExecutionModule)
     * 
     * API Response: {"Datetime": "2025-12-26T09:15:00", "Open": 4115, "High": 4125, "Low": 4107, "Close": 4125}
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class OHLCData {
        
        @JsonProperty("Open")
        @JsonAlias({"open"})
        private double open;
        
        @JsonProperty("High")
        @JsonAlias({"high"})
        private double high;
        
        @JsonProperty("Low")
        @JsonAlias({"low"})
        private double low;
        
        @JsonProperty("Close")
        @JsonAlias({"close"})
        private double close;
        
        @JsonProperty("Volume")
        @JsonAlias({"volume"})
        private long volume;
        
        private long timestamp;

        public OHLCData(double open, double high, double low, double close) {
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.timestamp = System.currentTimeMillis();
        }
        
        /**
         * Handle API's Datetime field and convert to timestamp
         */
        @JsonProperty("Datetime")
        @JsonAlias({"datetime"})
        public void setDatetime(String datetime) {
            if (datetime != null && !datetime.isEmpty()) {
                try {
                    LocalDateTime ldt = LocalDateTime.parse(datetime, DATETIME_FORMATTER);
                    this.timestamp = ldt.atZone(IST).toInstant().toEpochMilli();
                } catch (Exception e) {
                    // Fallback
                    this.timestamp = System.currentTimeMillis();
                }
            }
        }
    }
}
