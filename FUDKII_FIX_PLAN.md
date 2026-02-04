# FUDKII Signal Bug Fix - Implementation Plan

**Date:** 2026-02-02
**Issue:** False FUDKII signal for SupremeIND (3363) - ST calculated as 3467.34 with flip, real ST was 3461.26 (UP since Feb 1st 10:15)

---

## Root Cause Analysis

### Problem 1: Missing Historical Data
- System started at 11:00 AM on Feb 2nd, missing Feb 1st data
- MongoDB had ~50% duplicate 1m candles (9,802 out of 19,759)
- Historical API exists but was only called **once per scripCode** (negative cache)
- Feb 1st windows (13:15, 15:15) had incomplete data (<20 candles)

### Problem 2: Duplicate Candle Ingestion
- No deduplication at ingestion layer
- MongoDB index on `(scripCode, timestamp)` is not UNIQUE
- Same 1m candle can be saved multiple times (observed 50% duplication)

### Problem 3: SuperTrend Not Persisted
- SuperTrend calculated from scratch each time in `BBSuperTrendCalculator`
- First candle initialization: `close > midpoint ? UP : DOWN` (arbitrary)
- No historical ST state preservation - loses real trend direction

### Problem 4: NSE-Only Market Hours
- `FudkiiSignalTrigger` hardcoded for NSE: 9:15-15:30, boundaries at xx:15/xx:45
- MCX (exchange=M) requires 9:00-23:30, boundaries at xx:00/xx:30

---

## Implementation Plan

### Phase 1: Historical Data Backfill on Startup

**File:** `FudkiiSignalTrigger.java`

**Changes:**

1. **Add startup bootstrap method** (after `@PostConstruct`)
```java
@Value("${fudkii.trigger.bootstrap.enabled:true}")
private boolean bootstrapEnabled;

@Value("${fudkii.trigger.bootstrap.days-back:5}")
private int bootstrapDaysBack;

@Autowired
private HistoricalDataBootstrapService historicalBootstrapService;

@EventListener(ApplicationReadyEvent.class)
public void bootstrapHistoricalData() {
    if (!bootstrapEnabled) return;
    log.info("{} Bootstrapping historical 30m data for {} days back", LOG_PREFIX, bootstrapDaysBack);
    // This runs in background, doesn't block startup
    historicalBootstrapService.bootstrap30mCandlesAsync(bootstrapDaysBack);
}
```

2. **Modify `getOrBuildHistorical30mCandles`** to prioritize API on first load
```java
// CHANGE: Remove negative cache on first load - always try API if MongoDB < 21 candles
// Current line 476-498 logic change:

if (historical.size() < 21 && !apiAttemptedScripCodes.contains(scripCode)) {
    // Fetch from API with FULL date range (not just 40 days)
    ZonedDateTime startDate = beforeTime.atZone(IST).minusDays(bootstrapDaysBack);
    List<Candle30m> apiCandles = fetchHistorical30mFromAPI(scripCode, startDate, beforeTime);

    if (!apiCandles.isEmpty()) {
        // MERGE with MongoDB data (not replace) - prefer MongoDB for recent, API for old
        historical = mergeHistoricalCandles(historical, apiCandles);
    }
    apiAttemptedScripCodes.add(scripCode);
}
```

3. **Add merge logic** for combining MongoDB + API data without duplicates
```java
private List<Candle30m> mergeHistoricalCandles(List<Candle30m> mongoCandles, List<Candle30m> apiCandles) {
    // Use windowStart as unique key
    Map<Instant, Candle30m> merged = new TreeMap<>();

    // Add API candles first (older data)
    for (Candle30m c : apiCandles) {
        merged.put(c.getWindowStart(), c);
    }

    // Override with MongoDB candles (more recent, more accurate)
    for (Candle30m c : mongoCandles) {
        merged.put(c.getWindowStart(), c);
    }

    return new ArrayList<>(merged.values());
}
```

**Critical Review:**
- Bootstrap runs async to not block app startup
- API called once per scripCode (negative cache preserved)
- MongoDB data takes precedence over API for overlapping windows
- Config-driven days-back (default 5, covers weekends)

---

### Phase 2: Deduplication at Ingestion Layer

**File:** `TickCandle.java` (Model)

**Change:** Add unique compound index
```java
@CompoundIndexes({
    @CompoundIndex(name = "symbol_timestamp_idx", def = "{'symbol': 1, 'timestamp': -1}"),
    @CompoundIndex(name = "scripCode_timestamp_idx", def = "{'scripCode': 1, 'timestamp': -1}"),
    // ADD: Unique index for deduplication
    @CompoundIndex(name = "scripCode_windowStart_unique_idx",
                   def = "{'scripCode': 1, 'windowStart': 1}",
                   unique = true)
})
```

**File:** `CandleService.java` or wherever candles are saved

**Change:** Use upsert instead of insert
```java
// Find existing method that saves candles (likely in CompletedCandleService or CandleService)
// Change from:
//   tickCandleRepository.save(candle);
// To:
public void saveCandle(TickCandle candle) {
    // Upsert: insert if not exists, update if exists (by scripCode + windowStart)
    Query query = new Query(Criteria.where("scripCode").is(candle.getScripCode())
                                    .and("windowStart").is(candle.getWindowStart()));

    Update update = new Update()
        .set("open", candle.getOpen())
        .set("high", candle.getHigh())
        .set("low", candle.getLow())
        .set("close", candle.getClose())
        .set("volume", candle.getVolume())
        .setOnInsert("createdAt", Instant.now())
        // ... all other fields
        ;

    mongoTemplate.upsert(query, update, TickCandle.class);
}
```

**Alternative (simpler):** Catch DuplicateKeyException silently
```java
public void saveCandle(TickCandle candle) {
    try {
        tickCandleRepository.save(candle);
    } catch (DuplicateKeyException e) {
        log.debug("Duplicate candle ignored: {} @ {}",
                  candle.getScripCode(), candle.getWindowStart());
    }
}
```

**Critical Review:**
- Unique index prevents duplicates at DB level (guaranteed)
- Upsert approach preferred for updating existing candles with latest data
- Alternative catch-ignore is simpler but loses update capability
- Index creation may take time on existing data (run during maintenance window)

---

### Phase 3: Pre-Calculated SuperTrend Persistence

**New File:** `SuperTrendState.java` (Model)
```java
@Data
@Builder
@Document(collection = "supertrend_state")
@CompoundIndex(name = "scripCode_timeframe_unique_idx",
               def = "{'scripCode': 1, 'timeframe': 1}", unique = true)
public class SuperTrendState {
    @Id
    private String id;

    private String scripCode;
    private String timeframe;          // "30m"
    private Instant lastUpdated;

    // Core SuperTrend values
    private TrendDirection trend;      // UP or DOWN
    private double superTrendValue;    // Current ST line value
    private double finalUpperBand;
    private double finalLowerBand;

    // ATR for next calculation
    private double lastAtr;
    private double[] recentTrueRanges; // Last ATR_PERIOD values

    // For flip detection
    private Instant lastFlipTime;
    private int barsInCurrentTrend;

    // Candle reference
    private Instant lastCandleWindowStart;
    private double lastClose;
}
```

**New File:** `SuperTrendStateRepository.java`
```java
@Repository
public interface SuperTrendStateRepository extends MongoRepository<SuperTrendState, String> {
    Optional<SuperTrendState> findByScripCodeAndTimeframe(String scripCode, String timeframe);
}
```

**File:** `BBSuperTrendCalculator.java`

**Changes:**

1. **Add state injection**
```java
@Autowired
private SuperTrendStateRepository stateRepository;
```

2. **Modify calculate() to use persisted state**
```java
public BBSuperTrend calculate(String symbol, String timeframe,
                               double[] closes, double[] highs, double[] lows) {
    // ... existing validation code ...

    // LOAD PERSISTED STATE (if exists)
    Optional<SuperTrendState> savedState = stateRepository.findByScripCodeAndTimeframe(symbol, timeframe);

    // ... ATR calculation unchanged ...

    // MODIFIED: Start with persisted state if available
    int startIdx = ATR_PERIOD - 1;

    if (savedState.isPresent()) {
        SuperTrendState state = savedState.get();
        // Find where saved state ends in our data
        int stateIndex = findCandleIndex(closes, highs, lows, state.getLastCandleWindowStart());

        if (stateIndex >= 0 && stateIndex < n - 1) {
            // Initialize from saved state
            trends[stateIndex] = state.getTrend();
            superTrendValues[stateIndex] = state.getSuperTrendValue();
            finalUpperBands[stateIndex] = state.getFinalUpperBand();
            finalLowerBands[stateIndex] = state.getFinalLowerBand();
            startIdx = stateIndex + 1;

            log.debug("[BBST-CALC] {} Using persisted state from {}: trend={}, ST={}",
                symbol, state.getLastCandleWindowStart(), state.getTrend(), state.getSuperTrendValue());
        }
    }

    // Continue calculation from startIdx (uses persisted state if available)
    for (int i = startIdx; i < n; i++) {
        // ... existing SuperTrend calculation logic ...
    }

    // ... rest of calculation ...

    // SAVE STATE after calculation
    saveState(symbol, timeframe, trends, superTrendValues, finalUpperBands, finalLowerBands,
              atrValues, closes, windowStart /* need to pass this */);

    return bbst;
}

private void saveState(String symbol, String timeframe, TrendDirection[] trends,
                        double[] stValues, double[] upperBands, double[] lowerBands,
                        double[] atrValues, double[] closes, Instant windowStart) {
    int lastIdx = trends.length - 1;

    SuperTrendState state = SuperTrendState.builder()
        .scripCode(symbol)
        .timeframe(timeframe)
        .lastUpdated(Instant.now())
        .trend(trends[lastIdx])
        .superTrendValue(stValues[lastIdx])
        .finalUpperBand(upperBands[lastIdx])
        .finalLowerBand(lowerBands[lastIdx])
        .lastAtr(atrValues[lastIdx])
        .lastCandleWindowStart(windowStart)
        .lastClose(closes[lastIdx])
        .barsInCurrentTrend(countBarsInTrend(trends))
        .build();

    stateRepository.save(state);
}
```

**Critical Review:**
- State persisted per scripCode+timeframe combination
- Includes all values needed to continue calculation
- Falls back to from-scratch if state not found or stale
- State saved after every successful calculation
- Prevents recalculating entire history on every trigger
- Correctly detects flips by comparing with previous state

---

### Phase 4: MCX Market Extension

**File:** `FudkiiSignalTrigger.java`

**Changes:**

1. **Add MCX market hours constants**
```java
// NSE market timing (existing)
private static final int NSE_MARKET_OPEN_HOUR = 9;
private static final int NSE_MARKET_OPEN_MINUTE = 15;
private static final int NSE_MARKET_CLOSE_HOUR = 15;
private static final int NSE_MARKET_CLOSE_MINUTE = 30;

// MCX market timing (NEW)
private static final int MCX_MARKET_OPEN_HOUR = 9;
private static final int MCX_MARKET_OPEN_MINUTE = 0;
private static final int MCX_MARKET_CLOSE_HOUR = 23;
private static final int MCX_MARKET_CLOSE_MINUTE = 30;
```

2. **Modify onCandleClose() to handle both exchanges**
```java
public FudkiiTriggerResult onCandleClose(String scripCode, TickCandle candle1m) {
    // ... existing validation ...

    String exchange = candle1m.getExchange(); // "N" or "M"

    // Check if this is a 30m boundary based on exchange
    boolean is30mBoundary = is30mBoundary(minute, exchange);

    if (!is30mBoundary) {
        return FudkiiTriggerResult.noTrigger("Not a 30m boundary");
    }

    // Check market hours based on exchange
    if (!isMarketHours(istTime, exchange)) {
        return FudkiiTriggerResult.noTrigger("Outside market hours");
    }

    // ... rest of logic using exchange-aware methods ...
}

/**
 * Check if minute is a 30m boundary for the given exchange.
 * NSE: xx:15 and xx:45
 * MCX: xx:00 and xx:30
 */
private boolean is30mBoundary(int minute, String exchange) {
    if ("M".equalsIgnoreCase(exchange)) {
        // MCX: boundaries at :00 and :30
        return minute == 0 || minute == 30;
    } else {
        // NSE (default): boundaries at :15 and :45
        return minute == 15 || minute == 45;
    }
}
```

3. **Modify isMarketHours() to be exchange-aware**
```java
private boolean isMarketHours(ZonedDateTime istTime, String exchange) {
    int hour = istTime.getHour();
    int minute = istTime.getMinute();

    if ("M".equalsIgnoreCase(exchange)) {
        // MCX: 9:00 AM - 11:30 PM
        if (hour < MCX_MARKET_OPEN_HOUR) return false;
        if (hour == MCX_MARKET_OPEN_HOUR && minute < MCX_MARKET_OPEN_MINUTE) return false;
        if (hour > MCX_MARKET_CLOSE_HOUR) return false;
        if (hour == MCX_MARKET_CLOSE_HOUR && minute > MCX_MARKET_CLOSE_MINUTE) return false;
        return true;
    } else {
        // NSE (default): 9:15 AM - 3:30 PM
        if (hour < NSE_MARKET_OPEN_HOUR) return false;
        if (hour == NSE_MARKET_OPEN_HOUR && minute < NSE_MARKET_OPEN_MINUTE) return false;
        if (hour > NSE_MARKET_CLOSE_HOUR) return false;
        if (hour == NSE_MARKET_CLOSE_HOUR && minute > NSE_MARKET_CLOSE_MINUTE) return false;
        return true;
    }
}
```

4. **Modify get30mWindowStart() and get30mWindowEnd() for MCX**
```java
private Instant get30mWindowStart(ZonedDateTime istTime, String exchange) {
    int minute = istTime.getMinute();
    ZonedDateTime windowStart;

    if ("M".equalsIgnoreCase(exchange)) {
        // MCX: windows at :00 and :30
        if (minute == 0) {
            // At xx:00, window that closed is (xx-1):30 to xx:00
            windowStart = istTime.minusMinutes(30).withSecond(0).withNano(0);
        } else if (minute == 30) {
            // At xx:30, window that closed is xx:00 to xx:30
            windowStart = istTime.minusMinutes(30).withSecond(0).withNano(0);
        } else if (minute > 30) {
            windowStart = istTime.withMinute(30).withSecond(0).withNano(0);
        } else {
            windowStart = istTime.withMinute(0).withSecond(0).withNano(0);
        }
    } else {
        // NSE: existing logic (windows at :15 and :45)
        if (minute == 15) {
            windowStart = istTime.minusMinutes(30).withSecond(0).withNano(0);
        } else if (minute == 45) {
            windowStart = istTime.minusMinutes(30).withSecond(0).withNano(0);
        } else if (minute > 45) {
            windowStart = istTime.withMinute(45).withSecond(0).withNano(0);
        } else if (minute > 15) {
            windowStart = istTime.withMinute(15).withSecond(0).withNano(0);
        } else {
            windowStart = istTime.minusHours(1).withMinute(45).withSecond(0).withNano(0);
        }
    }

    return windowStart.toInstant();
}
```

5. **Modify API calls for MCX**
```java
private List<Candle30m> fetchHistorical30mFromAPI(String scripCode, String exchange, Instant beforeTime) {
    // Determine exchange parameters
    String exch = "M".equalsIgnoreCase(exchange) ? "M" : "N";
    String exchType = "M".equalsIgnoreCase(exchange) ? "D" : "C"; // MCX typically derivatives

    List<HistoricalCandle> apiCandles = fastAnalyticsClient.getHistoricalData(
        exch, exchType, scripCode, startDateStr, endDateStr, "30m");

    // ... rest of conversion logic with exchange-aware market hours ...
}
```

**Critical Review:**
- Exchange detected from `TickCandle.exchange` field
- All time-sensitive methods now exchange-aware
- MCX windows: 9:00, 9:30, 10:00, ..., 23:00, 23:30
- NSE windows: 9:15, 9:45, 10:15, ..., 15:15 (unchanged)
- API calls use correct exchange parameter ("M" vs "N")

---

## MCX 30m Candle Boundaries Reference

| Window Start | Window End | Trigger Time |
|--------------|------------|--------------|
| 09:00        | 09:30      | 09:30        |
| 09:30        | 10:00      | 10:00        |
| 10:00        | 10:30      | 10:30        |
| ...          | ...        | ...          |
| 22:30        | 23:00      | 23:00        |
| 23:00        | 23:30      | 23:30        |

**Total MCX 30m windows:** 29 per day (9:00 to 23:30)
**Total NSE 30m windows:** 13 per day (9:15 to 15:30)

---

## Configuration Changes

**File:** `application.properties`
```properties
# FUDKII Bootstrap (NEW)
fudkii.trigger.bootstrap.enabled=true
fudkii.trigger.bootstrap.days-back=5

# Existing (unchanged)
fudkii.trigger.enabled=true
fudkii.trigger.timeframe=30m
fudkii.trigger.bb.period=20
fudkii.trigger.st.period=10
fudkii.trigger.require.both.conditions=true
```

---

## MongoDB Index Migration Script

Run once to create unique index (handles existing duplicates):
```javascript
// Remove duplicates first (keep latest)
db.tick_candles_1m.aggregate([
  { $group: {
      _id: { scripCode: "$scripCode", windowStart: "$windowStart" },
      count: { $sum: 1 },
      ids: { $push: "$_id" },
      latestId: { $last: "$_id" }
  }},
  { $match: { count: { $gt: 1 } } }
]).forEach(function(doc) {
  // Remove all except latest
  doc.ids.forEach(function(id) {
    if (id.toString() !== doc.latestId.toString()) {
      db.tick_candles_1m.deleteOne({ _id: id });
    }
  });
});

// Create unique index
db.tick_candles_1m.createIndex(
  { "scripCode": 1, "windowStart": 1 },
  { unique: true, name: "scripCode_windowStart_unique_idx" }
);
```

---

## Testing Plan

### Unit Tests
1. `BBSuperTrendCalculatorTest` - Verify state persistence and recovery
2. `FudkiiSignalTriggerTest` - Verify MCX boundary detection
3. `CandleDeduplicationTest` - Verify upsert behavior

### Integration Tests
1. **Historical Bootstrap Test**
   - Start with empty DB, verify API fetch for missing data
   - Start with partial data, verify merge logic

2. **Duplicate Prevention Test**
   - Send same candle twice, verify only one in DB
   - Verify update behavior on existing candle

3. **SuperTrend State Test**
   - Calculate ST, restart app, verify state recovered
   - Verify flip detection across app restarts

4. **MCX Boundary Test**
   - Send MCX candle at 10:00, verify trigger evaluation
   - Send MCX candle at 10:15, verify NO trigger (not boundary)

### Manual Verification
1. Run for SupremeIND (3363) with Feb 1st data
2. Verify ST = 3461.26 (not 3467.34)
3. Verify no false flip detected at 13:45

---

## Rollout Plan

1. **Phase 1 (Immediate):** Deploy deduplication fix + unique index
2. **Phase 2 (Same day):** Deploy historical bootstrap + API merge
3. **Phase 3 (Next day):** Deploy SuperTrend state persistence
4. **Phase 4 (After testing):** Deploy MCX support

---

## Rollback Plan

Each phase is independently rollback-able:
- Phase 1: Remove unique index, revert save() method
- Phase 2: Disable bootstrap via config flag
- Phase 3: Delete SuperTrendState collection, calculator falls back to from-scratch
- Phase 4: Exchange filter in onCandleClose() to skip "M" exchange

---

## Summary

| Issue | Root Cause | Fix |
|-------|------------|-----|
| Missing Feb 1st data | API called once, negative cached | Bootstrap on startup + merge logic |
| Duplicate candles | No unique constraint | Unique index + upsert |
| Wrong ST calculation | State not persisted | New SuperTrendState collection |
| No MCX support | Hardcoded NSE hours | Exchange-aware methods |

**Estimated Changes:**
- `FudkiiSignalTrigger.java`: ~150 lines modified/added
- `BBSuperTrendCalculator.java`: ~50 lines modified
- `TickCandle.java`: 3 lines (index annotation)
- New files: `SuperTrendState.java`, `SuperTrendStateRepository.java`
- Config: 2 new properties
