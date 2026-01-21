# Price Action Fix & Signal Decay Model

## Document Version
- **Created**: January 2026
- **Status**: Proposed (Not Implemented)
- **Impact**: QuantScore accuracy, Signal quality, Dashboard display

---

## Table of Contents
1. [Problem Statement](#problem-statement)
2. [Root Cause Analysis](#root-cause-analysis)
3. [Price Action Fix](#price-action-fix)
4. [Signal Decay Model](#signal-decay-model)
5. [Divergence Strength Scoring](#divergence-strength-scoring)
6. [Signal Invalidation Rules](#signal-invalidation-rules)
7. [Implementation Plan](#implementation-plan)
8. [Impact Analysis](#impact-analysis)

---

## Problem Statement

### Issue 1: Price Action Summary Returns NULL

The dashboard displays NULL values for all Price Action fields:
- Wyckoff Phase: `-`
- Sequence Type: `-`
- Momentum Slope: `-`
- PCR Divergence: `-`
- OI Divergence: `-`

### Issue 2: Stale Signals Not Decaying

Signals retain their initial score even when:
- Time has elapsed (signal is stale)
- Price has moved toward target (edge consumed)
- Divergence has resolved (premise invalid)
- Momentum has reversed (trend changed)

---

## Root Cause Analysis

### Why Price Action is NULL

```
┌─────────────────────────────────────────────────────────────────────┐
│                    DATA FLOW ANALYSIS                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  1m Candles (FamilyCandleProcessor.java:897-915)                   │
│  ├── subCandles.size() <= 1 (no aggregation for 1m)                │
│  └── mtfDistribution = NULL ❌                                      │
│                                                                     │
│  Higher Timeframes (TimeframeAggregator.java)                      │
│  ├── mergeCandles() does NOT copy mtfDistribution                  │
│  └── mtfDistribution = NULL ❌                                      │
│                                                                     │
│  QuantScoreCalculator.buildPriceActionSummary():442-445            │
│  ├── if (mtf == null || mtf.getEvolution() == null)                │
│  └── return PriceActionSummary.builder().build() → ALL NULLS ❌    │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Affected Calculators

| Calculator | Points | Current Behavior | Impact |
|------------|--------|------------------|--------|
| PriceActionScoreCalculator | 0-12 | Uses fallback `calculateBasicPriceActionScore()` | Score underestimated |
| VolumeProfileScoreCalculator | 0-8 | Uses fallback `calculateFromOptions()` | Score may vary |
| OptionsFlowScoreCalculator | 0-15 | Uses fallback `calculateBasicOIScore()` | OI evolution missed |

### Files Requiring Changes

```
streamingcandle/
├── src/main/java/com/kotsin/consumer/infrastructure/kafka/
│   ├── TimeframeAggregator.java        ← Add mtfDistribution to mergeCandles()
│   └── FamilyCandleProcessor.java      ← Handle single-candle case for 1m
└── src/main/java/com/kotsin/consumer/quant/calculator/
    └── QuantScoreCalculator.java       ← Already has fallback (no change needed)
```

---

## Price Action Fix

### Fix 1: TimeframeAggregator - Preserve mtfDistribution

**Location**: `TimeframeAggregator.java` - `mergeCandles()` method

**Current Code** (Missing mtfDistribution):
```java
private FamilyCandle mergeCandles(FamilyCandle aggregate, FamilyCandle incoming, String timeframe) {
    // Copies: equity, future, options, pcr, greeks, etc.
    // MISSING: mtfDistribution is NEVER copied
}
```

**Proposed Fix**:
```java
private FamilyCandle mergeCandles(FamilyCandle aggregate, FamilyCandle incoming, String timeframe) {
    // ... existing code ...

    // FIX: Preserve mtfDistribution from incoming candle
    // For aggregated timeframes, recalculate from sub-candles if available
    if (incoming.getMtfDistribution() != null) {
        builder.mtfDistribution(incoming.getMtfDistribution());
    } else if (aggregate.getMtfDistribution() != null) {
        builder.mtfDistribution(aggregate.getMtfDistribution());
    }

    // ... rest of method ...
}
```

### Fix 2: FamilyCandleProcessor - Handle 1m Candles

**Location**: `FamilyCandleProcessor.java` lines 897-915

**Current Code**:
```java
if (subCandles != null && !subCandles.isEmpty() && subCandles.size() > 1) {
    // Calculate MTFDistribution
} else {
    // MTFDistribution NOT set for 1m candles
    log.debug("MTF Distribution skipped - insufficient sub-candles");
}
```

**Proposed Fix**:
```java
if (subCandles != null && !subCandles.isEmpty() && subCandles.size() > 1) {
    // Calculate MTFDistribution from sub-candles
    MTFDistribution dist = mtfDistributionCalculator.calculate(subCandles);
    builder.mtfDistribution(dist);
} else {
    // FIX: For 1m candles, create minimal MTFDistribution from current candle
    MTFDistribution minimalDist = createMinimalDistribution(equity, future);
    builder.mtfDistribution(minimalDist);
}
```

**New Helper Method**:
```java
private MTFDistribution createMinimalDistribution(UnifiedCandle equity, UnifiedCandle future) {
    UnifiedCandle primary = equity != null ? equity : future;
    if (primary == null) {
        return MTFDistribution.builder()
            .totalSubCandles(0)
            .confidence(0)
            .evolution(EvolutionMetrics.empty())
            .build();
    }

    // Determine direction from single candle
    MTFDistribution.Direction direction = primary.getClose() > primary.getOpen()
        ? MTFDistribution.Direction.BULLISH
        : primary.getClose() < primary.getOpen()
            ? MTFDistribution.Direction.BEARISH
            : MTFDistribution.Direction.NEUTRAL;

    // Calculate momentum from single candle
    double range = primary.getHigh() - primary.getLow();
    double momentum = range > 0 ? (primary.getClose() - primary.getOpen()) / range : 0;

    return MTFDistribution.builder()
        .bullishSubCandles(direction == MTFDistribution.Direction.BULLISH ? 1 : 0)
        .bearishSubCandles(direction == MTFDistribution.Direction.BEARISH ? 1 : 0)
        .totalSubCandles(1)
        .directionalConsistency(1.0)  // Single candle = 100% consistent
        .dominantDirection(direction)
        .earlyMomentum(momentum)
        .lateMomentum(momentum)
        .momentumShift(0)
        .confidence(0.5)  // Lower confidence for single candle
        .evolution(EvolutionMetrics.minimal(direction, momentum))
        .build();
}
```

---

## Signal Decay Model

### Overview

Signals should dynamically decay over time unless they strengthen. This prevents traders from acting on stale signals where the edge has been consumed.

```
SIGNAL VALUE OVER TIME:

Score
100 ─┬─────────────────────────────────────────────────
     │ ████ INITIAL SIGNAL
 90 ─┤ ████
 80 ─┤ ████──┐
     │       │ Time decay
 70 ─┤       ▼
 60 ─┤       ████──┐
     │             │ Target progress decay
 50 ─┤             ▼
 40 ─┤             ████──┐
     │                   │ Divergence weakening
 30 ─┤                   ▼
 20 ─┤                   ████
 15 ─┼─ ─ ─ ─ ─ ─ ─ ─ ─ ─ INVALIDATION THRESHOLD ─ ─
 10 ─┤                         ░░░░ INVALID
  0 ─┴─────────────────────────────────────────────────
     T0    T+15   T+30   T+45   T+60   T+75   T+90
                    Time (minutes)
```

### Master Decay Formula

```java
CurrentSignalValue = InitialSignalValue × TimeDecay × TargetDecay × DivergenceDecay × MomentumDecay
```

### Decay Components

#### 1. Time Decay (Half-Life Model)

```java
/**
 * Time decay using half-life model.
 * Signal loses 50% value every 30 minutes.
 */
public double calculateTimeDecay(long signalTimestamp) {
    double HALF_LIFE_MINUTES = 30.0;
    double minutesElapsed = (System.currentTimeMillis() - signalTimestamp) / 60000.0;
    return Math.pow(0.5, minutesElapsed / HALF_LIFE_MINUTES);
}

// Examples:
// T+0 min:  decay = 1.00
// T+15 min: decay = 0.71
// T+30 min: decay = 0.50
// T+60 min: decay = 0.25
// T+90 min: decay = 0.125
```

#### 2. Target Achievement Decay

```java
/**
 * Target decay - aggressive decay after 25% of Target1 achieved.
 * Based on premise: Once 25% of target reached, "easy money" is taken.
 */
public double calculateTargetDecay(SignalContext signal, double currentPrice) {
    double entryPrice = signal.getEntryPrice();
    double target1 = signal.getTarget1();

    double targetDistance = Math.abs(target1 - entryPrice);
    double currentProgress = Math.abs(currentPrice - entryPrice);
    double targetAchievedPct = currentProgress / targetDistance;

    // ATR-adaptive threshold
    double atr5m = signal.getAtr5m();
    double thresholdByTarget = targetDistance * 0.25;  // 25% of target
    double thresholdByATR = atr5m * 1.5;               // 1.5 × ATR
    double adaptiveThreshold = Math.max(thresholdByTarget, thresholdByATR);

    if (currentProgress >= adaptiveThreshold) {
        // Aggressive decay after threshold
        return Math.max(0.1, 1.0 - (targetAchievedPct * 1.5));
    } else {
        // Minimal decay before threshold
        return 1.0 - (targetAchievedPct * 0.5);
    }
}

// Examples (Target1 = +5% from entry):
// +0% achieved:  decay = 1.00
// +10% achieved: decay = 0.95
// +25% achieved: decay = 0.625 (threshold crossed)
// +50% achieved: decay = 0.25
// +75% achieved: decay = 0.10 (floor)
```

#### 3. Divergence Resolution Decay

```java
/**
 * Divergence decay - based on how much divergence has resolved.
 * If divergence reversed, signal is invalid.
 */
public double calculateDivergenceDecay(SignalContext signal, EnrichedQuantScore current) {
    double initialMagnitude = signal.getInitialDivergenceMagnitude();
    double currentMagnitude = getCurrentDivergenceMagnitude(current);

    if (initialMagnitude == 0) {
        return 1.0;  // No divergence to decay
    }

    double divergenceRatio = currentMagnitude / initialMagnitude;

    if (divergenceRatio < 0) {
        // Divergence REVERSED - signal invalid
        return 0.0;
    } else if (divergenceRatio < 0.2) {
        // Divergence mostly resolved - heavy decay
        return 0.2;
    } else if (divergenceRatio < 0.5) {
        // Divergence significantly weakened
        return 0.4;
    } else if (divergenceRatio < 0.8) {
        // Divergence moderately weakened
        return 0.7;
    } else {
        // Divergence still strong
        return 1.0;
    }
}
```

#### 4. Momentum Exhaustion Decay

```java
/**
 * Momentum decay - based on momentum slope changes.
 * If momentum reversed, heavy penalty.
 */
public double calculateMomentumDecay(SignalContext signal, EnrichedQuantScore current) {
    double initialSlope = signal.getInitialMomentumSlope();
    double currentSlope = getCurrentMomentumSlope(current);

    if (initialSlope == 0) {
        return 1.0;  // No initial momentum
    }

    // Check for reversal
    if (Math.signum(currentSlope) != Math.signum(initialSlope)) {
        return 0.2;  // Momentum reversed - heavy decay
    }

    // Check for exhaustion
    double slopeRatio = Math.abs(currentSlope) / Math.abs(initialSlope);
    if (slopeRatio < 0.3) {
        return 0.4;  // Momentum largely exhausted
    } else if (slopeRatio < 0.6) {
        return 0.7;  // Momentum weakening
    } else if (slopeRatio > 1.2) {
        return 1.2;  // Momentum strengthening (boost!)
    }

    return 1.0;
}
```

---

## Divergence Strength Scoring

### Formula

```java
DivergenceStrength = BaseStrength × DurationFactor × MagnitudeFactor × VelocityFactor × ConfluenceFactor
```

### Components

| Factor | Formula | Range | Description |
|--------|---------|-------|-------------|
| **BaseStrength** | `divergenceDetected ? 50 : 0` | 0-50 | Binary presence of divergence |
| **DurationFactor** | `min(1.5, 1 + (candleCount / 10))` | 1.0-1.5 | Longer divergence = stronger |
| **MagnitudeFactor** | `min(1.5, 1 + (divergencePct / 30))` | 1.0-1.5 | Larger divergence = stronger |
| **VelocityFactor** | Accelerating: 1.2, Stable: 1.0, Decelerating: 0.7 | 0.7-1.2 | Rate of change |
| **ConfluenceFactor** | `(pcrDiv + oiDiv) / 2 + 0.5` | 0.5-1.5 | Multiple confirmations |

### Implementation

```java
public class DivergenceStrengthCalculator {

    public double calculate(DivergenceContext context) {
        if (!context.isDivergenceDetected()) {
            return 0;
        }

        double baseStrength = 50.0;

        // Duration: More candles = stronger signal
        double durationFactor = Math.min(1.5, 1.0 + (context.getCandleCount() / 10.0));

        // Magnitude: Larger divergence = stronger signal
        double magnitudeFactor = Math.min(1.5, 1.0 + (context.getDivergencePct() / 30.0));

        // Velocity: Accelerating divergence = stronger signal
        double velocityFactor;
        if (context.isAccelerating()) {
            velocityFactor = 1.2;
        } else if (context.isDecelerating()) {
            velocityFactor = 0.7;
        } else {
            velocityFactor = 1.0;
        }

        // Confluence: Multiple divergence types = stronger signal
        int divergenceTypes = 0;
        if (context.hasPcrDivergence()) divergenceTypes++;
        if (context.hasOiDivergence()) divergenceTypes++;
        double confluenceFactor = (divergenceTypes / 2.0) + 0.5;

        double strength = baseStrength
            * durationFactor
            * magnitudeFactor
            * velocityFactor
            * confluenceFactor;

        return Math.min(100, strength);
    }
}
```

### Example Calculations

| Scenario | Base | Duration | Magnitude | Velocity | Confluence | **Strength** |
|----------|------|----------|-----------|----------|------------|--------------|
| Weak (2 candles, 10% PCR) | 50 | 1.2 | 1.33 | 1.0 | 1.0 | **80** |
| Medium (5 candles, 25% PCR) | 50 | 1.5 | 1.5 | 1.0 | 1.0 | **112→100** |
| Strong (8 candles, 35% PCR+OI) | 50 | 1.5 | 1.5 | 1.2 | 1.5 | **202→100** |
| Weakening (5 candles, decelerating) | 50 | 1.5 | 1.5 | 0.7 | 1.0 | **79** |

---

## Signal Invalidation Rules

### Rule Matrix

| Rule ID | Condition | Threshold | Action |
|---------|-----------|-----------|--------|
| `INV_TARGET_25` | Target progress | ≥ 25% of Target1 | INVALIDATE |
| `INV_TIME_60` | Time elapsed | > 60 minutes | INVALIDATE |
| `INV_DIV_RESOLVED` | Divergence magnitude | < 20% of initial | INVALIDATE |
| `INV_DIV_REVERSED` | Divergence sign | Opposite of initial | INVALIDATE |
| `INV_MOM_REVERSED` | Momentum direction | Opposite for 3+ candles | INVALIDATE |
| `INV_DECAY_FLOOR` | Combined decay | < 0.15 (15%) | INVALIDATE |
| `INV_STOP_BREACH` | Price vs stop | Crossed stop level | INVALIDATE |

### Signal Status Enum

```java
public enum SignalStatus {
    ACTIVE,           // Signal valid, conditions met
    STRENGTHENING,    // Signal improving (divergence/momentum increasing)
    WEAKENING,        // Signal degrading but still valid

    // Invalid states
    INVALID_TARGET_PARTIALLY_ACHIEVED,
    INVALID_TIME_EXPIRED,
    INVALID_DIVERGENCE_RESOLVED,
    INVALID_DIVERGENCE_REVERSED,
    INVALID_MOMENTUM_REVERSED,
    INVALID_DECAYED_TOO_MUCH,
    INVALID_STOP_BREACHED
}
```

### Evaluation Logic

```java
public SignalStatus evaluateSignalStatus(SignalContext signal, EnrichedQuantScore current) {
    double currentValue = calculateCurrentSignalValue(signal, current);
    double targetAchievedPct = calculateTargetAchieved(signal, current.getClose());
    long minutesElapsed = (System.currentTimeMillis() - signal.getTimestamp()) / 60000;

    // === INVALIDATION CHECKS (priority order) ===

    // 1. Stop breached
    if (isStopBreached(signal, current.getClose())) {
        return SignalStatus.INVALID_STOP_BREACHED;
    }

    // 2. Divergence reversed
    if (isDivergenceReversed(signal, current)) {
        return SignalStatus.INVALID_DIVERGENCE_REVERSED;
    }

    // 3. Target 25% achieved
    if (targetAchievedPct >= 0.25) {
        return SignalStatus.INVALID_TARGET_PARTIALLY_ACHIEVED;
    }

    // 4. Time expired
    if (minutesElapsed > 60) {
        return SignalStatus.INVALID_TIME_EXPIRED;
    }

    // 5. Divergence resolved
    if (isDivergenceResolved(signal, current)) {
        return SignalStatus.INVALID_DIVERGENCE_RESOLVED;
    }

    // 6. Momentum reversed
    if (isMomentumReversed(signal, current)) {
        return SignalStatus.INVALID_MOMENTUM_REVERSED;
    }

    // 7. Combined decay too low
    if (currentValue < signal.getInitialScore() * 0.15) {
        return SignalStatus.INVALID_DECAYED_TOO_MUCH;
    }

    // === ACTIVE STATES ===

    // Strengthening (divergence/momentum increasing)
    if (currentValue > signal.getInitialScore() * 1.1) {
        return SignalStatus.STRENGTHENING;
    }

    // Weakening but still valid
    if (currentValue < signal.getInitialScore() * 0.5) {
        return SignalStatus.WEAKENING;
    }

    return SignalStatus.ACTIVE;
}
```

---

## Implementation Plan

### Phase 1: Price Action Fix

#### Step 1.1: Fix TimeframeAggregator

**File**: `streamingcandle/src/main/java/com/kotsin/consumer/infrastructure/kafka/TimeframeAggregator.java`

**Task**: Add mtfDistribution preservation in `mergeCandles()` method

**Estimated Effort**: Low

**Risk**: Low (additive change, won't break existing logic)

#### Step 1.2: Fix FamilyCandleProcessor

**File**: `streamingcandle/src/main/java/com/kotsin/consumer/infrastructure/kafka/FamilyCandleProcessor.java`

**Task**: Create minimal MTFDistribution for 1m candles

**Estimated Effort**: Medium

**Risk**: Low (fallback behavior exists)

#### Step 1.3: Add EvolutionMetrics.minimal()

**File**: `streamingcandle/src/main/java/com/kotsin/consumer/model/EvolutionMetrics.java`

**Task**: Add static factory method for minimal evolution metrics

**Estimated Effort**: Low

**Risk**: None (new method)

---

### Phase 2: Signal Decay Model

#### Step 2.1: Create SignalContext

**New File**: `streamingcandle/src/main/java/com/kotsin/consumer/signal/model/SignalContext.java`

```java
@Data
@Builder
public class SignalContext {
    private String signalId;
    private String scripCode;
    private long timestamp;
    private double entryPrice;
    private double target1;
    private double target2;
    private double stopLoss;
    private double initialScore;
    private double atr5m;

    // Divergence context
    private double initialDivergenceMagnitude;
    private boolean hasPcrDivergence;
    private boolean hasOiDivergence;

    // Momentum context
    private double initialMomentumSlope;

    // Wyckoff context
    private String initialWyckoffPhase;

    // Tracking
    private SignalStatus currentStatus;
    private double currentScore;
    private long lastUpdateTime;
}
```

#### Step 2.2: Create SignalDecayCalculator

**New File**: `streamingcandle/src/main/java/com/kotsin/consumer/signal/service/SignalDecayCalculator.java`

```java
@Service
@Slf4j
public class SignalDecayCalculator {

    @Value("${signal.decay.time.halflife.minutes:30}")
    private double timeHalfLifeMinutes;

    @Value("${signal.decay.target.threshold:0.25}")
    private double targetThreshold;

    public double calculateCurrentSignalValue(SignalContext signal, EnrichedQuantScore current) {
        double timeDecay = calculateTimeDecay(signal.getTimestamp());
        double targetDecay = calculateTargetDecay(signal, current.getClose());
        double divergenceDecay = calculateDivergenceDecay(signal, current);
        double momentumDecay = calculateMomentumDecay(signal, current);

        double totalDecay = timeDecay * targetDecay * divergenceDecay * momentumDecay;
        double currentValue = signal.getInitialScore() * totalDecay;

        log.debug("[SIGNAL_DECAY] {} | initial={:.1f} | time={:.2f} | target={:.2f} | " +
                  "div={:.2f} | mom={:.2f} | CURRENT={:.1f}",
                  signal.getScripCode(), signal.getInitialScore(),
                  timeDecay, targetDecay, divergenceDecay, momentumDecay, currentValue);

        return currentValue;
    }

    // ... individual decay methods as documented above ...
}
```

#### Step 2.3: Create DivergenceStrengthCalculator

**New File**: `streamingcandle/src/main/java/com/kotsin/consumer/signal/service/DivergenceStrengthCalculator.java`

Implementation as documented in [Divergence Strength Scoring](#divergence-strength-scoring).

#### Step 2.4: Create SignalLifecycleManager

**New File**: `streamingcandle/src/main/java/com/kotsin/consumer/signal/service/SignalLifecycleManager.java`

```java
@Service
@Slf4j
public class SignalLifecycleManager {

    private final SignalDecayCalculator decayCalculator;
    private final Cache<String, SignalContext> activeSignals;

    public void registerSignal(SignalContext signal) { ... }

    public void updateSignal(String signalId, EnrichedQuantScore current) { ... }

    public SignalStatus evaluateSignalStatus(SignalContext signal, EnrichedQuantScore current) { ... }

    public void invalidateSignal(String signalId, SignalStatus reason) { ... }

    public List<SignalContext> getActiveSignals(String scripCode) { ... }
}
```

#### Step 2.5: Modify SignalCoordinator

**File**: `streamingcandle/src/main/java/com/kotsin/consumer/enrichment/signal/coordinator/SignalCoordinator.java`

**Task**: Integrate SignalLifecycleManager for decay tracking

#### Step 2.6: Add Invalidation Rules to TradingStrategy

**File**: `streamingcandle/src/main/java/com/kotsin/consumer/trading/strategy/TradingStrategy.java`

```java
public interface TradingStrategy {
    // Existing methods...

    /**
     * Check if signal should be invalidated based on current market state.
     * Called on each tick/candle update.
     */
    default Optional<SignalStatus> checkSignalInvalidation(
            SignalContext signal,
            EnrichedQuantScore current) {
        return Optional.empty();  // Default: no custom invalidation
    }
}
```

---

### Phase 3: Dashboard Updates

#### Step 3.1: Add Signal Freshness to DTO

**File**: `trading-dashboard/backend/src/main/java/com/kotsin/dashboard/model/dto/TradingSignalDTO.java`

```java
// New fields
private double currentScore;       // Decayed score
private double initialScore;       // Original score
private double decayPercentage;    // (initial - current) / initial * 100
private String signalStatus;       // ACTIVE, WEAKENING, STRENGTHENING, INVALID
private long signalAgeSeconds;     // Time since signal generation
private double targetProgress;     // % of Target1 achieved
```

#### Step 3.2: Frontend Signal Freshness Display

**File**: `trading-dashboard/frontend/src/components/signals/SignalCard.tsx`

Add visual indicators:
- 🟢 Fresh signal (< 15 min, < 10% decay)
- 🟡 Aging signal (15-30 min, 10-30% decay)
- 🟠 Stale signal (30-45 min, 30-50% decay)
- 🔴 Expiring signal (> 45 min, > 50% decay)

#### Step 3.3: Add Decay Progress Bar

```tsx
<div className="signal-freshness">
  <div className="freshness-bar" style={{ width: `${100 - decayPercentage}%` }} />
  <span>{signalAgeMinutes}m old | {decayPercentage.toFixed(0)}% decay</span>
</div>
```

---

## Impact Analysis

### Affected Components

| Component | Impact | Risk Level |
|-----------|--------|------------|
| QuantScoreCalculator | Score values may change | MEDIUM |
| PriceActionScoreCalculator | Will use full EvolutionMetrics | LOW |
| VolumeProfileScoreCalculator | Will use full EvolutionMetrics | LOW |
| OptionsFlowScoreCalculator | Will use OIEvolution | LOW |
| Signal generation | More accurate signals | POSITIVE |
| Dashboard | Real values instead of nulls | POSITIVE |
| FUDKII Strategy | No direct impact | NONE |
| PivotRetest Strategy | No direct impact | NONE |

### Expected Outcomes

| Metric | Before | After | Change |
|--------|--------|-------|--------|
| PriceAction nulls | 100% | 0% | ✅ Fixed |
| Divergence detection | Never | Always | ✅ Enabled |
| Stale signal trades | Common | Rare | ✅ Reduced |
| Signal accuracy | Partial data | Full data | ✅ Improved |
| False confidence | Possible | Mitigated | ✅ Improved |

### Rollback Plan

If issues arise post-deployment:

1. **Feature Flag**: Add `smtis.priceaction.fix.enabled=false` to disable fix
2. **Fallback**: Existing `calculateBasicPriceActionScore()` remains functional
3. **Signal Decay**: Can be disabled via `signal.decay.enabled=false`

---

## Testing Checklist

### Unit Tests

- [ ] `TimeframeAggregator.mergeCandles()` preserves mtfDistribution
- [ ] `FamilyCandleProcessor` creates minimal MTFDistribution for 1m
- [ ] `SignalDecayCalculator.calculateTimeDecay()` follows half-life curve
- [ ] `SignalDecayCalculator.calculateTargetDecay()` applies 25% threshold
- [ ] `DivergenceStrengthCalculator` produces correct scores
- [ ] `SignalLifecycleManager` transitions states correctly

### Integration Tests

- [ ] QuantScore includes non-null PriceActionSummary for all timeframes
- [ ] Signals decay over time in SignalCoordinator
- [ ] Dashboard displays signal freshness indicators
- [ ] Invalid signals are filtered from execution

### Manual Tests

- [ ] Verify dashboard shows Wyckoff phase, sequence type, momentum slope
- [ ] Verify signal score decreases after 30 minutes
- [ ] Verify signal invalidates after 25% target achieved
- [ ] Verify divergence signals show strength score

---

## Configuration

### Application Properties

```properties
# Price Action Fix
smtis.priceaction.fix.enabled=true
smtis.priceaction.minimal.confidence=0.5

# Signal Decay
signal.decay.enabled=true
signal.decay.time.halflife.minutes=30
signal.decay.target.threshold=0.25
signal.decay.divergence.resolved.threshold=0.20
signal.decay.invalidation.floor=0.15

# Signal Invalidation
signal.invalidation.time.max.minutes=60
signal.invalidation.target.threshold=0.25
signal.invalidation.momentum.reversal.candles=3

# Divergence Strength
divergence.strength.duration.factor.divisor=10
divergence.strength.magnitude.factor.divisor=30
```

---

## References

- **Root Cause Analysis**: `QuantScoreCalculator.java:442-445`, `TimeframeAggregator.java`
- **Affected Strategies**: `FUDKIICalculator.java`, `PivotRetestStrategy.java`
- **Dashboard**: `QuantScoresPage.tsx:688-713`
- **MTF Distribution**: `MTFDistributionCalculator.java`

---

## Appendix: Decay Visualization

```
COMBINED DECAY EXAMPLE:

Initial Score: 85

Time: T+0    T+15   T+30   T+45   T+60
─────────────────────────────────────────
Time Decay:   1.00   0.71   0.50   0.35   0.25
Target Decay: 1.00   0.95   0.80   0.50   0.30  (price moving toward target)
Div Decay:    1.00   1.00   0.80   0.60   0.40  (divergence resolving)
Mom Decay:    1.00   1.00   1.00   0.80   0.50  (momentum weakening)
─────────────────────────────────────────
Combined:     1.00   0.67   0.32   0.08   0.015
Score:        85     57     27     7      1.3 → INVALID (< 15%)
```

---

*Document End*
