# Pivot & Historical Data Integration Plan

## Overview

Integrate the Python FastAnalytics API (port 8002) to:
1. Fetch 40 days of 1-minute historical candles on startup
2. Fetch daily/weekly/monthly pivot levels
3. Calculate CPR width and confluence scores
4. Detect price bounces at pivot levels for top/bottom signals

---

## API Endpoints (FastAnalytics - localhost:8002)

### 1. Historical Candle Data
```bash
GET /getHisDataFromFivePaisa?exch=N&exch_type=C&scrip_code={scripCode}&start_date={today-40}&end_date={today}&interval=1m
```

**Response:**
```json
[
  {
    "Datetime": "2026-02-01T13:41:00",
    "Open": 1376.9,
    "High": 1377.5,
    "Low": 1376.9,
    "Close": 1377.0,
    "Volume": 217
  }
]
```

### 2. Pivot Data (Daily/Weekly/Monthly)
```bash
GET /getPivotData?exch=N&exch_type=C&scrip_code={scripCode}&start_date={prevPeriodStart}&end_date={prevPeriodEnd}&interval={1d|1wk|1mo}
```

**Response:**
```json
{
  "pivot": 1377.0,
  "s1": 1343.0, "s2": 1309.0, "s3": 1275.0, "s4": 1241.0,
  "r1": 1411.0, "r2": 1445.0, "r3": 1479.0, "r4": 1513.0,
  "fibS1": 1351.024, "fibS2": 1334.976, "fibS3": 1309.0,
  "fibR1": 1402.976, "fibR2": 1419.024, "fibR3": 1445.0,
  "camS1": 1370.77, "camS2": 1364.53, "camS3": 1358.3, "camS4": 1339.6,
  "camR1": 1383.23, "camR2": 1389.47, "camR3": 1395.7, "camR4": 1414.4,
  "tc": 1377.0,
  "bc": 1377.0
}
```

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         APPLICATION STARTUP                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  HistoricalDataBootstrapService (NEW)                                │   │
│  │  - Runs on @PostConstruct after TickAggregator                      │   │
│  │  - For each subscribed symbol:                                      │   │
│  │    1. Call /getHisDataFromFivePaisa (40 days, 1m interval)         │   │
│  │    2. Save to MongoDB tick_candles_1m collection                    │   │
│  │    3. Cache recent 100 candles in Redis                            │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  PivotLevelBootstrapService (NEW)                                   │   │
│  │  - Runs on @PostConstruct                                           │   │
│  │  - For each subscribed symbol:                                      │   │
│  │    1. Fetch TODAY's Daily pivot (yesterday's HLC)                  │   │
│  │    2. Fetch YESTERDAY's Daily pivot (for confluence)               │   │
│  │    3. Fetch THIS WEEK's Weekly pivot                               │   │
│  │    4. Fetch LAST WEEK's Weekly pivot (for confluence)              │   │
│  │    5. Fetch THIS MONTH's Monthly pivot                             │   │
│  │    6. Fetch LAST MONTH's Monthly pivot (for confluence)            │   │
│  │    7. Store in Redis: pivot:levels:{symbol}                        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                         RUNTIME PROCESSING                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  SignalEngine.processSymbol()                                        │   │
│  │                                                                      │   │
│  │  1. Get candles from CandleService (now has 10000+ from bootstrap)  │   │
│  │  2. Get pivot levels from PivotLevelService                         │   │
│  │  3. Calculate CPR analysis:                                         │   │
│  │     - CPR Width (thinner = higher probability)                     │   │
│  │     - Confluence Score (multiple pivots at same level)             │   │
│  │     - Price distance from key levels                               │   │
│  │  4. Check for price bounce at pivot levels                         │   │
│  │  5. Generate signal with pivot-enhanced scoring                    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## New Components

### 1. FastAnalyticsClient.java
```java
@Service
public class FastAnalyticsClient {

    @Value("${fastanalytics.base-url:http://localhost:8002}")
    private String baseUrl;

    private final RestTemplate restTemplate;

    /**
     * Fetch historical candle data from FivePaisa API.
     * @param exch Exchange (N for NSE, B for BSE, M for MCX)
     * @param exchType Exchange type (C for cash, D for derivatives)
     * @param scripCode Instrument scrip code
     * @param startDate Start date (YYYY-MM-DD)
     * @param endDate End date (YYYY-MM-DD)
     * @param interval Candle interval (1m, 5m, 15m, 30m, 1d, etc.)
     */
    public List<HistoricalCandle> getHistoricalData(String exch, String exchType,
            String scripCode, String startDate, String endDate, String interval);

    /**
     * Fetch pivot data for a given period.
     * @param interval 1d (daily), 1wk (weekly), 1mo (monthly)
     */
    public PivotLevels getPivotData(String exch, String exchType,
            String scripCode, String startDate, String endDate, String interval);
}
```

### 2. PivotLevels.java (Model)
```java
@Data
@Builder
public class PivotLevels {
    // Standard Pivots
    private double pivot;
    private double s1, s2, s3, s4;
    private double r1, r2, r3, r4;

    // Fibonacci Pivots
    private double fibS1, fibS2, fibS3;
    private double fibR1, fibR2, fibR3;

    // Camarilla Pivots
    private double camS1, camS2, camS3, camS4;
    private double camR1, camR2, camR3, camR4;

    // CPR (Central Pivot Range)
    private double tc;  // Top Central
    private double bc;  // Bottom Central

    // Derived
    public double getCprWidth() {
        return Math.abs(tc - bc);
    }

    public double getCprWidthPercent(double price) {
        return (getCprWidth() / price) * 100;
    }
}
```

### 3. MultiTimeframePivotState.java (Model)
```java
@Data
@Builder
public class MultiTimeframePivotState {
    private String symbol;

    // Today's pivots (from yesterday's HLC)
    private PivotLevels dailyPivot;
    private PivotLevels prevDailyPivot;  // For confluence

    // This week's pivots (from last week's HLC)
    private PivotLevels weeklyPivot;
    private PivotLevels prevWeeklyPivot;  // For confluence

    // This month's pivots (from last month's HLC)
    private PivotLevels monthlyPivot;
    private PivotLevels prevMonthlyPivot;  // For confluence

    private Instant lastUpdated;
}
```

### 4. PivotConfluenceAnalyzer.java
```java
@Component
public class PivotConfluenceAnalyzer {

    private static final double CONFLUENCE_THRESHOLD_PERCENT = 0.5; // 0.5% proximity

    /**
     * Analyze CPR characteristics.
     */
    public CprAnalysis analyzeCpr(PivotLevels daily, double currentPrice) {
        double cprWidth = daily.getCprWidth();
        double cprWidthPercent = daily.getCprWidthPercent(currentPrice);

        // Thinner CPR = Higher breakout probability
        CprType type;
        if (cprWidthPercent < 0.3) {
            type = CprType.ULTRA_THIN;  // Very high probability breakout
        } else if (cprWidthPercent < 0.5) {
            type = CprType.THIN;         // High probability
        } else if (cprWidthPercent < 1.0) {
            type = CprType.NORMAL;       // Normal range
        } else {
            type = CprType.WIDE;         // Range bound expected
        }

        // Price position relative to CPR
        PricePosition position;
        if (currentPrice > daily.getTc()) {
            position = PricePosition.ABOVE_CPR;  // Bullish bias
        } else if (currentPrice < daily.getBc()) {
            position = PricePosition.BELOW_CPR;  // Bearish bias
        } else {
            position = PricePosition.INSIDE_CPR; // Consolidating
        }

        return CprAnalysis.builder()
            .cprWidth(cprWidth)
            .cprWidthPercent(cprWidthPercent)
            .type(type)
            .pricePosition(position)
            .breakoutProbability(calculateBreakoutProbability(type))
            .build();
    }

    /**
     * Calculate confluence score at a price level.
     * Higher score = More timeframes agree = Stronger level
     */
    public ConfluenceResult analyzeConfluence(MultiTimeframePivotState state, double price) {
        List<ConfluenceLevel> levels = new ArrayList<>();
        int totalConfluence = 0;

        // Check all pivot levels within threshold
        totalConfluence += checkLevelConfluence(levels, price, state.getDailyPivot(), "DAILY");
        totalConfluence += checkLevelConfluence(levels, price, state.getPrevDailyPivot(), "PREV_DAILY");
        totalConfluence += checkLevelConfluence(levels, price, state.getWeeklyPivot(), "WEEKLY");
        totalConfluence += checkLevelConfluence(levels, price, state.getPrevWeeklyPivot(), "PREV_WEEKLY");
        totalConfluence += checkLevelConfluence(levels, price, state.getMonthlyPivot(), "MONTHLY");

        // Calculate strength
        ConfluenceStrength strength;
        if (totalConfluence >= 4) {
            strength = ConfluenceStrength.VERY_STRONG;  // 4+ levels agree
        } else if (totalConfluence >= 3) {
            strength = ConfluenceStrength.STRONG;       // 3 levels
        } else if (totalConfluence >= 2) {
            strength = ConfluenceStrength.MODERATE;     // 2 levels
        } else {
            strength = ConfluenceStrength.WEAK;         // 0-1 levels
        }

        return ConfluenceResult.builder()
            .confluenceCount(totalConfluence)
            .strength(strength)
            .levels(levels)
            .build();
    }

    /**
     * Detect price bounce at pivot level.
     * Returns potential top/bottom signal.
     */
    public BounceSignal detectBounce(MultiTimeframePivotState state,
            List<UnifiedCandle> recentCandles, double currentPrice) {

        if (recentCandles.size() < 3) return null;

        UnifiedCandle current = recentCandles.get(0);
        UnifiedCandle prev1 = recentCandles.get(1);
        UnifiedCandle prev2 = recentCandles.get(2);

        // Find nearest support/resistance
        NearestLevel nearest = findNearestLevel(state, currentPrice);

        // Check for bullish bounce (at support)
        if (nearest.getType() == LevelType.SUPPORT) {
            // Price touched support and bounced up
            boolean touchedSupport = prev1.getLow() <= nearest.getPrice() * 1.002;
            boolean bouncing = current.getClose() > prev1.getClose() &&
                              current.getClose() > current.getOpen();

            if (touchedSupport && bouncing && nearest.getConfluence() >= 2) {
                return BounceSignal.builder()
                    .type(BounceType.BULLISH_BOUNCE)
                    .level(nearest.getPrice())
                    .levelName(nearest.getName())
                    .confluence(nearest.getConfluence())
                    .confidence(calculateBounceConfidence(nearest))
                    .message("Bullish bounce at " + nearest.getName() +
                            " (confluence: " + nearest.getConfluence() + ")")
                    .build();
            }
        }

        // Check for bearish bounce (at resistance)
        if (nearest.getType() == LevelType.RESISTANCE) {
            boolean touchedResistance = prev1.getHigh() >= nearest.getPrice() * 0.998;
            boolean rejecting = current.getClose() < prev1.getClose() &&
                               current.getClose() < current.getOpen();

            if (touchedResistance && rejecting && nearest.getConfluence() >= 2) {
                return BounceSignal.builder()
                    .type(BounceType.BEARISH_BOUNCE)
                    .level(nearest.getPrice())
                    .levelName(nearest.getName())
                    .confluence(nearest.getConfluence())
                    .confidence(calculateBounceConfidence(nearest))
                    .message("Bearish rejection at " + nearest.getName() +
                            " (confluence: " + nearest.getConfluence() + ")")
                    .build();
            }
        }

        return null;
    }
}
```

### 5. HistoricalDataBootstrapService.java
```java
@Service
@Slf4j
public class HistoricalDataBootstrapService {

    private static final String LOG_PREFIX = "[HIST-BOOTSTRAP]";

    @Autowired
    private FastAnalyticsClient fastAnalyticsClient;

    @Autowired
    private TickCandleRepository tickCandleRepository;

    @Autowired
    private RedisCacheService redisCacheService;

    @Value("${bootstrap.historical.days:40}")
    private int historicalDays;

    @Value("${bootstrap.historical.enabled:true}")
    private boolean enabled;

    private final ExecutorService bootstrapExecutor =
        Executors.newFixedThreadPool(4, r -> new Thread(r, "hist-bootstrap"));

    /**
     * Bootstrap historical data for a symbol.
     */
    public CompletableFuture<Boolean> bootstrapSymbol(String scripCode, String exch, String exchType) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String endDate = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
                String startDate = LocalDate.now().minusDays(historicalDays)
                    .format(DateTimeFormatter.ISO_DATE);

                log.info("{} Bootstrapping {} from {} to {}",
                    LOG_PREFIX, scripCode, startDate, endDate);

                // Fetch from FastAnalytics API
                List<HistoricalCandle> candles = fastAnalyticsClient.getHistoricalData(
                    exch, exchType, scripCode, startDate, endDate, "1m");

                if (candles == null || candles.isEmpty()) {
                    log.warn("{} No historical data for {}", LOG_PREFIX, scripCode);
                    return false;
                }

                log.info("{} Fetched {} candles for {}", LOG_PREFIX, candles.size(), scripCode);

                // Convert to TickCandle and save
                List<TickCandle> tickCandles = candles.stream()
                    .map(c -> convertToTickCandle(c, scripCode, exch, exchType))
                    .collect(Collectors.toList());

                // Batch save to MongoDB
                tickCandleRepository.saveAll(tickCandles);

                // Cache recent 100 in Redis
                tickCandles.stream()
                    .sorted(Comparator.comparing(TickCandle::getTimestamp).reversed())
                    .limit(100)
                    .forEach(redisCacheService::cacheTickCandle);

                log.info("{} Bootstrapped {} candles for {}",
                    LOG_PREFIX, candles.size(), scripCode);
                return true;

            } catch (Exception e) {
                log.error("{} Bootstrap failed for {}: {}",
                    LOG_PREFIX, scripCode, e.getMessage());
                return false;
            }
        }, bootstrapExecutor);
    }
}
```

### 6. PivotLevelService.java
```java
@Service
@Slf4j
public class PivotLevelService {

    private static final String LOG_PREFIX = "[PIVOT-LEVEL]";
    private static final String REDIS_KEY_PREFIX = "pivot:levels:";

    @Autowired
    private FastAnalyticsClient fastAnalyticsClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // In-memory cache for quick access
    private final ConcurrentHashMap<String, MultiTimeframePivotState> pivotCache =
        new ConcurrentHashMap<>();

    /**
     * Load all pivot levels for a symbol.
     */
    public MultiTimeframePivotState loadPivotLevels(String scripCode, String exch, String exchType) {
        log.info("{} Loading pivot levels for {}", LOG_PREFIX, scripCode);

        LocalDate today = LocalDate.now();

        // Calculate date ranges for each timeframe
        // Daily: yesterday's data for today's pivots
        String dailyEnd = today.format(DateTimeFormatter.ISO_DATE);
        String dailyStart = today.minusDays(1).format(DateTimeFormatter.ISO_DATE);
        String prevDailyEnd = today.minusDays(1).format(DateTimeFormatter.ISO_DATE);
        String prevDailyStart = today.minusDays(2).format(DateTimeFormatter.ISO_DATE);

        // Weekly: last week's data for this week's pivots
        LocalDate lastWeekEnd = today.with(java.time.DayOfWeek.SUNDAY).minusWeeks(1);
        LocalDate lastWeekStart = lastWeekEnd.minusDays(6);
        String weeklyEnd = lastWeekEnd.plusDays(1).format(DateTimeFormatter.ISO_DATE);
        String weeklyStart = lastWeekStart.format(DateTimeFormatter.ISO_DATE);
        String prevWeeklyEnd = lastWeekStart.format(DateTimeFormatter.ISO_DATE);
        String prevWeeklyStart = lastWeekStart.minusDays(7).format(DateTimeFormatter.ISO_DATE);

        // Monthly: last month's data for this month's pivots
        LocalDate lastMonthEnd = today.withDayOfMonth(1).minusDays(1);
        LocalDate lastMonthStart = lastMonthEnd.withDayOfMonth(1);
        String monthlyEnd = today.withDayOfMonth(1).format(DateTimeFormatter.ISO_DATE);
        String monthlyStart = lastMonthStart.format(DateTimeFormatter.ISO_DATE);

        try {
            MultiTimeframePivotState state = MultiTimeframePivotState.builder()
                .symbol(scripCode)
                .dailyPivot(fastAnalyticsClient.getPivotData(
                    exch, exchType, scripCode, dailyStart, dailyEnd, "1d"))
                .prevDailyPivot(fastAnalyticsClient.getPivotData(
                    exch, exchType, scripCode, prevDailyStart, prevDailyEnd, "1d"))
                .weeklyPivot(fastAnalyticsClient.getPivotData(
                    exch, exchType, scripCode, weeklyStart, weeklyEnd, "1wk"))
                .prevWeeklyPivot(fastAnalyticsClient.getPivotData(
                    exch, exchType, scripCode, prevWeeklyStart, prevWeeklyEnd, "1wk"))
                .monthlyPivot(fastAnalyticsClient.getPivotData(
                    exch, exchType, scripCode, monthlyStart, monthlyEnd, "1mo"))
                .lastUpdated(Instant.now())
                .build();

            // Cache in memory and Redis
            pivotCache.put(scripCode, state);
            cacheToRedis(scripCode, state);

            log.info("{} Loaded pivot levels for {} - Daily CPR Width: {:.2f}%",
                LOG_PREFIX, scripCode,
                state.getDailyPivot().getCprWidthPercent(state.getDailyPivot().getPivot()));

            return state;

        } catch (Exception e) {
            log.error("{} Failed to load pivots for {}: {}", LOG_PREFIX, scripCode, e.getMessage());
            return null;
        }
    }

    /**
     * Get cached pivot levels.
     */
    public Optional<MultiTimeframePivotState> getPivotLevels(String scripCode) {
        // Try memory cache first
        MultiTimeframePivotState cached = pivotCache.get(scripCode);
        if (cached != null) {
            return Optional.of(cached);
        }

        // Try Redis
        return loadFromRedis(scripCode);
    }
}
```

---

## Integration with SignalEngine

### Modified SignalEngine.processSymbol()

```java
public void processSymbol(String symbol, Timeframe timeframe) {
    // ... existing code ...

    // Get candles (now has 10000+ from bootstrap)
    List<UnifiedCandle> candles = candleService.getCandleHistory(symbol, timeframe, 100);

    // NEW: Get multi-timeframe pivot levels
    Optional<MultiTimeframePivotState> pivotState = pivotLevelService.getPivotLevels(symbol);

    if (pivotState.isPresent()) {
        MultiTimeframePivotState state = pivotState.get();
        double currentPrice = candles.get(0).getClose();

        // 1. Analyze CPR
        CprAnalysis cprAnalysis = pivotConfluenceAnalyzer.analyzeCpr(
            state.getDailyPivot(), currentPrice);

        log.debug("{} {} CPR Analysis: width={:.2f}%, type={}, position={}",
            LOG_PREFIX, symbol,
            cprAnalysis.getCprWidthPercent(),
            cprAnalysis.getType(),
            cprAnalysis.getPricePosition());

        // 2. Check confluence at current price
        ConfluenceResult confluence = pivotConfluenceAnalyzer.analyzeConfluence(
            state, currentPrice);

        if (confluence.getStrength() == ConfluenceStrength.STRONG ||
            confluence.getStrength() == ConfluenceStrength.VERY_STRONG) {
            log.info("{} {} CONFLUENCE DETECTED: {} levels at price {:.2f}",
                LOG_PREFIX, symbol, confluence.getConfluenceCount(), currentPrice);
        }

        // 3. Check for bounce signal
        BounceSignal bounce = pivotConfluenceAnalyzer.detectBounce(
            state, candles, currentPrice);

        if (bounce != null) {
            log.info("{} {} BOUNCE SIGNAL: {} at {} (confidence: {:.1f}%)",
                LOG_PREFIX, symbol,
                bounce.getType(),
                bounce.getLevelName(),
                bounce.getConfidence() * 100);

            // Enhance FUDKII score based on bounce
            if (bounce.getType() == BounceType.BULLISH_BOUNCE) {
                // Boost bullish score
                score.setBullishBoost(bounce.getConfidence() * 20);
            } else if (bounce.getType() == BounceType.BEARISH_BOUNCE) {
                // Boost bearish score
                score.setBearishBoost(bounce.getConfidence() * 20);
            }
        }

        // 4. Add CPR context to signal
        if (cprAnalysis.getType() == CprType.ULTRA_THIN) {
            log.info("{} {} ULTRA-THIN CPR: High breakout probability!",
                LOG_PREFIX, symbol);
            score.setBreakoutProbability(0.8);
        }
    }

    // ... rest of existing code ...
}
```

---

## Confluence Logic Explained

### CPR Width Analysis
```
CPR Width % = (TC - BC) / Pivot * 100

< 0.3%  → ULTRA_THIN  → 80% breakout probability
0.3-0.5% → THIN       → 60% breakout probability
0.5-1.0% → NORMAL     → 40% breakout probability
> 1.0%   → WIDE       → Range-bound, mean reversion
```

### Confluence Scoring
```
Price at level where multiple pivots converge = STRONG Support/Resistance

Example at price 1375:
- Daily Pivot: 1377 (within 0.5%) ✓
- Yesterday's S1: 1374 (within 0.5%) ✓
- Weekly Pivot: 1380 (within 0.5%) ✓
- Monthly S1: 1370 (within 0.5%) ✓

Confluence Score: 4 → VERY_STRONG level
→ If price bounces here, high probability reversal
```

### Bounce Detection
```
Bullish Bounce:
1. Price touches support level (within 0.2%)
2. Current candle closes above previous candle
3. Current candle is bullish (close > open)
4. Support has confluence >= 2

Bearish Bounce:
1. Price touches resistance level (within 0.2%)
2. Current candle closes below previous candle
3. Current candle is bearish (close < open)
4. Resistance has confluence >= 2
```

---

## Configuration (application.properties)

```properties
# FastAnalytics API
fastanalytics.base-url=http://localhost:8002
fastanalytics.connect-timeout-ms=5000
fastanalytics.read-timeout-ms=30000

# Historical Data Bootstrap
bootstrap.historical.enabled=true
bootstrap.historical.days=40
bootstrap.historical.threads=4

# Pivot Level Bootstrap
bootstrap.pivot.enabled=true
bootstrap.pivot.refresh-cron=0 0 9 * * MON-FRI  # Refresh at 9 AM on trading days

# Confluence Analysis
pivot.confluence.threshold-percent=0.5
pivot.cpr.thin-threshold=0.5
pivot.cpr.ultra-thin-threshold=0.3
```

---

## Files to Create

1. `src/main/java/com/kotsin/consumer/client/FastAnalyticsClient.java`
2. `src/main/java/com/kotsin/consumer/model/PivotLevels.java`
3. `src/main/java/com/kotsin/consumer/model/MultiTimeframePivotState.java`
4. `src/main/java/com/kotsin/consumer/model/CprAnalysis.java`
5. `src/main/java/com/kotsin/consumer/model/ConfluenceResult.java`
6. `src/main/java/com/kotsin/consumer/model/BounceSignal.java`
7. `src/main/java/com/kotsin/consumer/service/HistoricalDataBootstrapService.java`
8. `src/main/java/com/kotsin/consumer/service/PivotLevelService.java`
9. `src/main/java/com/kotsin/consumer/signal/analyzer/PivotConfluenceAnalyzer.java`

## Files to Modify

1. `src/main/java/com/kotsin/consumer/signal/engine/SignalEngine.java` - Integrate pivot analysis
2. `src/main/java/com/kotsin/consumer/signal/model/FudkiiScore.java` - Add pivot boost fields
3. `src/main/resources/application.properties` - Add configurations

---

## Summary

This integration provides:

1. **40 days of historical 1m candles** on startup → Enough data for proper swing detection
2. **Multi-timeframe pivot levels** (Daily, Weekly, Monthly + previous periods)
3. **CPR Width Analysis** → Thin CPR = high breakout probability
4. **Confluence Detection** → Multiple pivots at same level = strong S/R
5. **Bounce Detection** → Price reversal at pivot levels = potential top/bottom signal
6. **Enhanced scoring** → FUDKII score boosted by pivot confluence
