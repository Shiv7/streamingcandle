# Quality Gates Module

> **Package**: `com.kotsin.consumer.gate`

The gate module provides **quality filtering** for trading signals.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        GATE CHAIN                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Signal + Context                                                │
│       │                                                          │
│       ▼                                                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐        │
│  │  Volume  │─▶│  Trend   │─▶│   R:R    │─▶│ Momentum │        │
│  │   Gate   │  │   Gate   │  │   Gate   │  │   Gate   │        │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘        │
│       │              │              │              │             │
│       ▼              ▼              ▼              ▼             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐        │
│  │   VWAP   │─▶│  Time of │─▶│  FUDKII  │─▶│ Aggregate│        │
│  │   Gate   │  │ Day Gate │  │   Gate   │  │  Result  │        │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘        │
│                                                                  │
│  Output: ChainResult { passed, score, failedGates[] }           │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## GateChain

**File**: `GateChain.java` (281 lines)

Orchestrates multiple quality gates.

### Usage

```java
// Build context
Map<String, Object> context = gateChain.contextBuilder()
    .signalDirection("BULLISH")
    .prices(100.0, 105.0, 98.0)  // entry, target, stopLoss
    .volume(150000, 100000)      // current, average
    .emas(true, true)            // above EMA20, EMA50
    .superTrend(true)            // bullish
    .rsi(55.0)
    .vwap(99.5, true)            // vwap, above
    .fudkii(72.0)
    .build();

// Evaluate
ChainResult result = gateChain.evaluate("NIFTY", "FUDKII", context);

if (result.isPassed()) {
    // High quality signal
    emitSignal(signal);
} else {
    log.debug("Rejected by: {}", result.getFailedGates());
}
```

### ChainResult

```java
public class ChainResult {
    boolean passed;              // All required gates passed
    double score;                // Weighted aggregate (0-1)
    List<String> failedGates;   // Names of failed gates
    Map<String, GateResult> gateResults;  // Individual results
}
```

---

## Gate Implementations

### VolumeGate

**File**: `impl/VolumeGate.java`

Checks if volume is above average.

```java
// Pass condition
volume >= avgVolume * minRatio  // default minRatio = 1.2
```

**Config**:
```properties
gate.volume.required=false
gate.volume.min.ratio=1.2
gate.volume.weight=0.15
```

---

### TrendGate

**File**: `impl/TrendGate.java`

Checks trend alignment.

```java
// For BULLISH signals:
- Price above EMA20 AND EMA50
- SuperTrend bullish
- HTF trend aligned (optional)

// For BEARISH signals:
- Price below EMA20 AND EMA50
- SuperTrend bearish
```

**Config**:
```properties
gate.trend.required=true
gate.trend.weight=0.20
```

---

### RiskRewardGate

**File**: `impl/RiskRewardGate.java`

Checks risk:reward ratio.

```java
double risk = Math.abs(entry - stopLoss);
double reward = Math.abs(target - entry);
double ratio = reward / risk;

// Pass condition
ratio >= minRatio  // default 1.5
```

**Config**:
```properties
gate.risk.reward.required=true
gate.risk.reward.min=1.5
gate.risk.reward.weight=0.20
```

---

### MomentumGate

**File**: `impl/MomentumGate.java`

Checks RSI for extremes.

```java
// For BULLISH signals:
- RSI not overbought (< 75)
- Bonus if RSI coming from oversold

// For BEARISH signals:
- RSI not oversold (> 25)
- Bonus if RSI coming from overbought
```

**Config**:
```properties
gate.momentum.required=false
gate.momentum.rsi.overbought=75
gate.momentum.rsi.oversold=25
gate.momentum.weight=0.15
```

---

### VWAPGate

**File**: `impl/VWAPGate.java`

Checks price relative to VWAP.

```java
// For BULLISH signals:
- Price above VWAP (preferred)
- Or price near VWAP (acceptable)

// For BEARISH signals:
- Price below VWAP (preferred)
```

**Config**:
```properties
gate.vwap.required=false
gate.vwap.weight=0.10
```

---

### TimeOfDayGate

**File**: `impl/TimeOfDayGate.java`

Filters based on trading session.

```java
// Preferred sessions (full score):
- MORNING (9:30 - 11:30)
- AFTERNOON (13:30 - 15:00)

// Reduced score:
- MIDDAY (11:30 - 13:30) → 0.7x

// Blocked (fail gate if required):
- OPENING (9:15 - 9:30)
- CLOSING (15:00 - 15:30)
```

**Config**:
```properties
gate.time.of.day.required=false
gate.time.of.day.weight=0.10
```

---

### FUDKIIGate

**File**: `impl/FUDKIIGate.java`

Checks FUDKII score threshold.

```java
// Pass condition
fudkiiScore >= minScore  // default 40
```

**Config**:
```properties
gate.fudkii.required=false
gate.fudkii.min.score=40
gate.fudkii.weight=0.20
```

---

## Gate Statistics

Track pass/fail rates per gate.

```java
// Get stats for a specific gate
GateStats stats = gateChain.getGateStats("TrendGate");
// { total: 1000, passed: 650, failed: 350, passRate: 0.65 }

// Get all stats
Map<String, GateStats> allStats = gateChain.getAllGateStats();

// Get most restrictive gates (sorted by pass rate ascending)
List<String> restrictive = gateChain.getMostRestrictiveGates();
// ["RiskRewardGate", "TrendGate", "VolumeGate", ...]
```

---

## Adding Custom Gates

1. Implement `SignalGate` interface:

```java
@Component
public class CustomGate implements SignalGate {
    
    @Override
    public String getName() {
        return "CustomGate";
    }
    
    @Override
    public boolean isRequired() {
        return false;  // or configurable
    }
    
    @Override
    public double getWeight() {
        return 0.10;
    }
    
    @Override
    public GateResult evaluate(Map<String, Object> context) {
        // Your logic here
        boolean passed = ...;
        double score = ...;
        String reason = ...;
        
        return new GateResult(passed, score, reason);
    }
}
```

2. Spring auto-discovers via `@Component`

---

## Configuration Summary

```properties
# Enable/disable chain
gate.chain.enabled=true

# Individual gates
gate.volume.required=false
gate.trend.required=true
gate.risk.reward.required=true
gate.momentum.required=false
gate.vwap.required=false
gate.time.of.day.required=false
gate.fudkii.required=false

# Weights (must sum to ~1.0)
gate.volume.weight=0.15
gate.trend.weight=0.20
gate.risk.reward.weight=0.20
gate.momentum.weight=0.15
gate.vwap.weight=0.10
gate.time.of.day.weight=0.10
gate.fudkii.weight=0.20

# Thresholds
gate.volume.min.ratio=1.2
gate.risk.reward.min=1.5
gate.momentum.rsi.overbought=75
gate.momentum.rsi.oversold=25
gate.fudkii.min.score=40
```
