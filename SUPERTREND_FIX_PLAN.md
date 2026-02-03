# SuperTrend Calculation Fix - Comprehensive Solution

## Problem Summary

Our SuperTrend shows **5775.14** but broker shows **5750**. Fresh calculation gives **5762.99** (only 12.99 off).

### Root Cause

The persisted state from MongoDB (`superTrendValue: 5786.29`, `barsInCurrentTrend: 37`) was calculated with **API historical data** (50+ candles). When system restarts with only **24 MongoDB candles**, the code blindly applies this stale state at `index = atrPeriod - 1 = 6`, causing a mismatch.

The state's `finalUpperBand: 5786.29` doesn't match what we'd calculate fresh with current data (`5821.00` at that index), propagating errors through the calculation.

---

## Solution: 3-Part Fix

### Part 1: Pass Window Timestamps to Calculator

**File: `FudkiiSignalTrigger.java`**

The calculator needs to know which timestamp corresponds to which index to properly apply persisted state.

```java
// Line ~294-308: Build timestamp array alongside OHLC arrays
int n = allCandles.size();
double[] closes = new double[n];
double[] highs = new double[n];
double[] lows = new double[n];
Instant[] windowStarts = new Instant[n];  // NEW

for (int i = 0; i < n; i++) {
    Candle30m c = allCandles.get(i);
    closes[i] = c.close;
    highs[i] = c.high;
    lows[i] = c.low;
    windowStarts[i] = c.getWindowStart();  // NEW
}

// Line ~308: Pass timestamps to calculator
BBSuperTrend bbst = bbstCalculator.calculate(scripCode, "30m", closes, highs, lows, windowStarts, windowStart);
```

### Part 2: Intelligent State Application in Calculator

**File: `BBSuperTrendCalculator.java`**

Replace the current blind state application with intelligent matching.

```java
// New method signature
public BBSuperTrend calculate(String symbol, String timeframe,
                               double[] closes, double[] highs, double[] lows,
                               Instant[] windowStarts,  // NEW PARAMETER
                               Instant lastWindowStart) {

    int n = closes.length;
    int minRequired = Math.max(BB_PERIOD, atrPeriod) + 1;

    // Require more candles for proper warmup (50 instead of 21)
    int recommendedMinimum = 50;
    if (n < recommendedMinimum) {
        log.warn("[BBST-CALC] {} Only {} candles, recommended {} for accurate calculation",
            symbol, n, recommendedMinimum);
    }

    if (closes == null || n < minRequired) {
        log.debug("Insufficient data for {}: have {}, need {}", symbol, n, minRequired);
        return defaultBBST(symbol, timeframe);
    }

    // ==================== LOAD PERSISTED STATE ====================
    SuperTrendState savedState = loadPersistedState(symbol, timeframe);
    boolean usingPersistedState = false;
    int stateApplicationIndex = -1;

    // ==================== CALCULATE ATR FOR ALL CANDLES ====================
    // ... (existing ATR calculation - no changes) ...

    // ==================== INTELLIGENT STATE APPLICATION ====================
    int startIdx = atrPeriod - 1;

    if (savedState != null && savedState.getTrend() != null
        && savedState.getLastCandleWindowStart() != null && windowStarts != null) {

        // STEP 1: Find the index where persisted state's window matches our data
        int matchingIndex = -1;
        for (int i = 0; i < n; i++) {
            if (windowStarts[i] != null && windowStarts[i].equals(savedState.getLastCandleWindowStart())) {
                matchingIndex = i;
                break;
            }
        }

        if (matchingIndex >= 0) {
            // STEP 2: State window exists in our data - validate bands are reasonable
            double hl2AtMatch = (highs[matchingIndex] + lows[matchingIndex]) / 2;
            double atrAtMatch = matchingIndex >= atrPeriod - 1 ? atrValues[matchingIndex] : 0;

            // Calculate what bands SHOULD be at this index
            double expectedUpper = hl2AtMatch + ST_MULTIPLIER * atrAtMatch;
            double expectedLower = hl2AtMatch - ST_MULTIPLIER * atrAtMatch;

            // Check if persisted bands are within 5% of expected (allowing for smoothing)
            double tolerance = 0.05;
            boolean upperReasonable = Math.abs(savedState.getFinalUpperBand() - expectedUpper) / expectedUpper < tolerance;
            boolean lowerReasonable = Math.abs(savedState.getFinalLowerBand() - expectedLower) / expectedLower < tolerance;

            if (upperReasonable || lowerReasonable) {
                // STEP 3: Apply state at the CORRECT index (after the matching window)
                stateApplicationIndex = matchingIndex + 1;

                if (stateApplicationIndex < n && stateApplicationIndex >= atrPeriod - 1) {
                    // Apply persisted state values at the matching index
                    trends[matchingIndex] = savedState.getTrend();
                    superTrendValues[matchingIndex] = savedState.getSuperTrendValue();
                    finalUpperBands[matchingIndex] = savedState.getFinalUpperBand();
                    finalLowerBands[matchingIndex] = savedState.getFinalLowerBand();

                    usingPersistedState = true;
                    startIdx = stateApplicationIndex;

                    log.info("[BBST-CALC] {} Applied persisted state at index {} (window={}): trend={}, ST={}",
                        symbol, matchingIndex, savedState.getLastCandleWindowStart(),
                        savedState.getTrend(), String.format("%.2f", savedState.getSuperTrendValue()));
                } else {
                    log.info("[BBST-CALC] {} State window at end of data, calculating fresh from index {}",
                        symbol, startIdx);
                }
            } else {
                // Bands don't match - data context changed, calculate fresh
                log.warn("[BBST-CALC] {} Persisted bands don't match current data context. " +
                    "Expected upper ~{}, got {}. Calculating fresh.",
                    symbol, String.format("%.2f", expectedUpper),
                    String.format("%.2f", savedState.getFinalUpperBand()));

                // Clear the stale state
                clearState(symbol, timeframe);
            }
        } else {
            // State window NOT in our data array - data context is completely different
            log.warn("[BBST-CALC] {} Persisted state window {} not found in current data. " +
                "Data range: {} to {}. Calculating fresh.",
                symbol, savedState.getLastCandleWindowStart(),
                windowStarts[0], windowStarts[n-1]);

            // Clear the incompatible state
            clearState(symbol, timeframe);
        }
    }

    // ... (rest of calculation - no changes) ...
}
```

### Part 3: Increase Minimum Candle Threshold

**File: `FudkiiSignalTrigger.java`**

```java
// Line ~286: Increase from 21 to 50 for proper warmup
if (allCandles.size() < 50) {
    log.warn("{} {} Insufficient 30m candles: have {}, need 50 for accurate calculation",
        LOG_PREFIX, scripCode, allCandles.size());
    // Still process if we have minimum for BB(20), but warn
    if (allCandles.size() < 21) {
        lastProcessed30mWindow.put(scripCode, windowEnd);
        return FudkiiTriggerResult.noTrigger(
            String.format("Insufficient 30m candles: got %d, need 21", allCandles.size()));
    }
}

// Line ~562: Increase API fetch threshold from 21 to 50
if (mongoCandles.size() < 50 && !apiAttemptedScripCodes.contains(scripCode)) {
    log.info("{} {} MongoDB has only {} 30m candles, fetching from API...",
        LOG_PREFIX, scripCode, mongoCandles.size());
    // ... existing API fetch logic ...
}
```

---

## Why This Solution is Resilient

| Scenario | Current Behavior | Fixed Behavior |
|----------|-----------------|----------------|
| Fresh start, no state | Calculate from scratch | Same - no change |
| Restart with same data | Apply state blindly at index 6 | Find matching window, apply at correct index |
| Restart with different data | Apply incompatible state → wrong values | Detect mismatch, calculate fresh |
| Data gap (missing candles) | State applied incorrectly | Detect window not in data, calculate fresh |
| API data merged with MongoDB | State may not match | Validate bands match context |
| Insufficient candles (< 50) | Calculate anyway | Warn and try to fetch more from API |

---

## Implementation Checklist

1. [ ] Add `Instant[] windowStarts` parameter to `BBSuperTrendCalculator.calculate()`
2. [ ] Build and pass `windowStarts` array from `FudkiiSignalTrigger`
3. [ ] Implement intelligent state matching (find index by timestamp)
4. [ ] Add band validation (check if persisted bands are reasonable)
5. [ ] Clear incompatible state instead of using it
6. [ ] Increase API fetch threshold from 21 to 50
7. [ ] Add warning logs for insufficient data
8. [ ] Test with fresh data (no state)
9. [ ] Test with restart (matching state)
10. [ ] Test with data gap (incompatible state)

---

## Expected Results After Fix

- **Fresh calculation**: 5762.99 (matches current manual calculation)
- **Broker value**: 5750
- **Difference**: ~13 points (0.2%) - acceptable due to ATR warmup with limited history
- **With 50+ candles**: Should match broker even more closely
