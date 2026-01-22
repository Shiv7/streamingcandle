# SuperTrend Calculation Bug Fix

**Date:** 21 Jan 2026
**Priority:** Critical
**Affects:** FUDKII Strategy, Stop Loss Calculations, Signal Generation

---

## Executive Summary

The SuperTrend indicator calculation in the codebase has **two critical bugs** causing values to diverge significantly from standard market calculations (TradingView, charting platforms).

| Commodity | Our Value | Market Value | Difference |
|-----------|-----------|--------------|------------|
| NATURALGAS Jan | 458.17 | 408.75 | +49.42 |
| SILVERM Feb | 338530.50 | 331967.70 | +6562.80 |
| CRUDEOIL Feb | 5505.80 | 5497.86 | +7.94 |
| ALUMINIUM Jan | 315.57 | 315.20 | +0.37 |

---

## Root Cause Analysis

### Two Separate Implementations Found

#### 1. `BBSuperTrendDetector.java` (Lines 239-275) - **CRITICAL**

**Used by:** FUDKIIProcessor (FUDKII strategy signals)

**Problem:** Completely broken implementation that does NOT track state.

```java
// CURRENT CODE - FUNDAMENTALLY WRONG
public double[] calculateSuperTrend(List<UnifiedCandle> candles, double atr) {
    // Comment in code admits: "Simple SuperTrend calculation"
    // "In full implementation, we'd track previous upper/lower bands"

    double basicUpperBand = hl2 + (superTrendMultiplier * atr);
    double basicLowerBand = hl2 - (superTrendMultiplier * atr);

    // WRONG: Just compares close to basic bands
    // Does NOT track previous bands or implement proper trailing
    if (close > basicUpperBand) {
        return new double[] { basicLowerBand, 1 };
    } else if (close < basicLowerBand) {
        return new double[] { basicUpperBand, -1 };
    } else {
        // Falls back to counting up/down closes - WRONG!
    }
}
```

**Issues:**
- No state tracking (previous upper/lower bands)
- No previous close tracking
- No proper band trailing logic
- No crossover detection for band reset

---

#### 2. `TechnicalIndicatorEnricher.java` (Lines 222-225) - **MODERATE**

**Used by:** General enrichment pipeline, Redis state storage

**Problem:** Missing price crossover condition in band calculation.

```java
// CURRENT CODE - PARTIALLY WRONG
double finalUpperBand = prevState.finalUpperBand > 0 ?
        Math.min(upperBand, prevState.finalUpperBand) : upperBand;
double finalLowerBand = prevState.finalLowerBand > 0 ?
        Math.max(lowerBand, prevState.finalLowerBand) : lowerBand;
```

**Issue:** Uses `Math.min`/`Math.max` unconditionally without checking if previous close crossed the band.

---

## Standard SuperTrend Formula (TradingView)

Reference: [TradingView SuperTrend Documentation](https://www.tradingview.com/support/solutions/43000634738-supertrend/)

### Band Calculation

```
hl2 = (high + low) / 2
basicUpperBand = hl2 + (multiplier × ATR)
basicLowerBand = hl2 - (multiplier × ATR)

// Upper Band - KEY: includes prevClose crossover check
upperBand = (basicUpperBand < prevUpperBand) OR (prevClose > prevUpperBand)
            ? basicUpperBand
            : prevUpperBand

// Lower Band - KEY: includes prevClose crossover check
lowerBand = (basicLowerBand > prevLowerBand) OR (prevClose < prevLowerBand)
            ? basicLowerBand
            : prevLowerBand
```

### Direction Logic

```
if (prevSuperTrend == prevUpperBand):  // was bearish
    if (close > upperBand):
        direction = BULLISH
        superTrend = lowerBand
    else:
        direction = BEARISH
        superTrend = upperBand
else:  // was bullish
    if (close < lowerBand):
        direction = BEARISH
        superTrend = upperBand
    else:
        direction = BULLISH
        superTrend = lowerBand
```

### Key Insight

The conditions `prevClose > prevUpperBand` and `prevClose < prevLowerBand` allow bands to **RESET** when price crosses through them. Without these conditions, bands only move in one direction and get "stuck."

---

## Required Fixes

### Fix 1: BBSuperTrendDetector.java (Primary - Critical)

**File:** `src/main/java/com/kotsin/consumer/curated/service/BBSuperTrendDetector.java`

**Action:** Complete rewrite of `calculateSuperTrend` method with proper state tracking.

```java
// Add state class
private record SuperTrendState(
    double superTrend,
    double finalUpperBand,
    double finalLowerBand,
    boolean bullish,
    double previousClose
) {}

// Add state storage
private final Map<String, SuperTrendState> superTrendStateCache = new ConcurrentHashMap<>();

/**
 * Calculate SuperTrend with proper state tracking
 *
 * @param candles Price history
 * @param atr Current ATR value
 * @param stateKey Unique key for state tracking (e.g., scripCode + timeframe)
 * @return [superTrendValue, direction (1=bullish, -1=bearish)]
 */
public double[] calculateSuperTrend(List<UnifiedCandle> candles, double atr, String stateKey) {
    if (candles.isEmpty() || atr <= 0) {
        return new double[] { 0, 0 };
    }

    UnifiedCandle current = candles.get(candles.size() - 1);
    double high = current.getHigh();
    double low = current.getLow();
    double close = current.getClose();
    double hl2 = (high + low) / 2;

    double basicUpperBand = hl2 + (superTrendMultiplier * atr);
    double basicLowerBand = hl2 - (superTrendMultiplier * atr);

    // Get previous state
    SuperTrendState prev = superTrendStateCache.get(stateKey);

    double finalUpperBand;
    double finalLowerBand;
    double superTrend;
    boolean bullish;

    if (prev == null) {
        // First calculation
        finalUpperBand = basicUpperBand;
        finalLowerBand = basicLowerBand;
        bullish = close > hl2;
        superTrend = bullish ? finalLowerBand : finalUpperBand;
    } else {
        // Upper Band: reset if basicUB < prevUB OR prevClose crossed above prevUB
        if (basicUpperBand < prev.finalUpperBand || prev.previousClose > prev.finalUpperBand) {
            finalUpperBand = basicUpperBand;
        } else {
            finalUpperBand = prev.finalUpperBand;
        }

        // Lower Band: reset if basicLB > prevLB OR prevClose crossed below prevLB
        if (basicLowerBand > prev.finalLowerBand || prev.previousClose < prev.finalLowerBand) {
            finalLowerBand = basicLowerBand;
        } else {
            finalLowerBand = prev.finalLowerBand;
        }

        // Determine direction
        if (prev.superTrend == prev.finalUpperBand) {
            // Was bearish
            if (close > finalUpperBand) {
                bullish = true;
                superTrend = finalLowerBand;
            } else {
                bullish = false;
                superTrend = finalUpperBand;
            }
        } else {
            // Was bullish
            if (close < finalLowerBand) {
                bullish = false;
                superTrend = finalUpperBand;
            } else {
                bullish = true;
                superTrend = finalLowerBand;
            }
        }
    }

    // Store new state
    superTrendStateCache.put(stateKey, new SuperTrendState(
        superTrend, finalUpperBand, finalLowerBand, bullish, close
    ));

    return new double[] { superTrend, bullish ? 1 : -1 };
}
```

**Also update FUDKIIProcessor.java** to pass the state key:
```java
// Change from:
double[] stCurrent = bbSuperTrendDetector.calculateSuperTrend(candles30m, atr);

// To:
String stateKey = scripCode + ":30m";
double[] stCurrent = bbSuperTrendDetector.calculateSuperTrend(candles30m, atr, stateKey);
```

---

### Fix 2: TechnicalIndicatorEnricher.java (Secondary)

**File:** `src/main/java/com/kotsin/consumer/enrichment/enricher/TechnicalIndicatorEnricher.java`

**Lines 222-225:** Replace band calculation with crossover check.

```java
// BEFORE (wrong):
double finalUpperBand = prevState.finalUpperBand > 0 ?
        Math.min(upperBand, prevState.finalUpperBand) : upperBand;
double finalLowerBand = prevState.finalLowerBand > 0 ?
        Math.max(lowerBand, prevState.finalLowerBand) : lowerBand;

// AFTER (correct):
double finalUpperBand;
if (prevState.finalUpperBand <= 0) {
    finalUpperBand = upperBand;
} else if (upperBand < prevState.finalUpperBand || prevState.previousClose > prevState.finalUpperBand) {
    finalUpperBand = upperBand;
} else {
    finalUpperBand = prevState.finalUpperBand;
}

double finalLowerBand;
if (prevState.finalLowerBand <= 0) {
    finalLowerBand = lowerBand;
} else if (lowerBand > prevState.finalLowerBand || prevState.previousClose < prevState.finalLowerBand) {
    finalLowerBand = lowerBand;
} else {
    finalLowerBand = prevState.finalLowerBand;
}
```

**Line 743:** Add `previousClose` to state record.

```java
// BEFORE:
private record SuperTrendState(double superTrend, double finalUpperBand, double finalLowerBand,
                                boolean bullish, int candlesSinceFlip) {}

// AFTER:
private record SuperTrendState(double superTrend, double finalUpperBand, double finalLowerBand,
                                boolean bullish, int candlesSinceFlip, double previousClose) {}
```

**Update state storage (line 671):**
```java
// BEFORE:
String value = String.format("%f,%f,%f,%b,%d",
        state.superTrend, state.finalUpperBand, state.finalLowerBand,
        state.bullish, state.candlesSinceFlip);

// AFTER:
String value = String.format("%f,%f,%f,%b,%d,%f",
        state.superTrend, state.finalUpperBand, state.finalLowerBand,
        state.bullish, state.candlesSinceFlip, state.previousClose);
```

**Update state parsing (around line 657):**
```java
// Handle both old format (5 fields) and new format (6 fields)
return new SuperTrendState(
        Double.parseDouble(parts[0]),
        Double.parseDouble(parts[1]),
        Double.parseDouble(parts[2]),
        Boolean.parseBoolean(parts[3]),
        Integer.parseInt(parts[4]),
        parts.length > 5 ? Double.parseDouble(parts[5]) : 0
);
```

---

## Impact Analysis

### Components Affected

| Component | Current Behavior | After Fix |
|-----------|------------------|-----------|
| FUDKII signals | Wrong SuperTrend values, incorrect flips | Correct values matching market |
| Stop loss (FudkiiStrategy) | Based on wrong SuperTrend | Correct stop levels |
| MTF aggregation | Aggregating wrong values | Correct aggregation |
| Redis state | Storing wrong values | Correct state |

### Risk Assessment

| Risk | Severity | Mitigation |
|------|----------|------------|
| Signal frequency change | Medium | Expected - old signals were based on wrong data |
| Existing positions | Medium | Review open positions after deployment |
| Backtest results differ | Low | Old backtests used incorrect values |

---

## Deployment Steps

1. **Code Changes**
   - Apply Fix 1 to `BBSuperTrendDetector.java`
   - Apply Fix 2 to `TechnicalIndicatorEnricher.java`
   - Update `FUDKIIProcessor.java` to pass state key

2. **Testing**
   - Unit test with known market data
   - Compare output with TradingView values
   - Verify FUDKII signals match expected behavior

3. **Deployment**
   - Deploy during market closed hours
   - Clear Redis SuperTrend state keys:
     ```bash
     redis-cli KEYS "smtis:tech:state:*:supertrend" | xargs redis-cli DEL
     ```
   - Monitor first few signals for correctness

4. **Validation**
   - Compare new SuperTrend values with market data
   - Verify signals are generating correctly
   - Check stop loss calculations

---

## References

- [TradingView SuperTrend Documentation](https://www.tradingview.com/support/solutions/43000634738-supertrend/)
- [Pine Script SuperTrend Implementation](https://www.tradingview.com/script/P5Gu6F8k/)
- Standard Parameters: ATR Period = 10, Multiplier = 3.0
