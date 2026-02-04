# Signal Generation Module

> **Package**: `com.kotsin.consumer.signal`

The signal module is responsible for **generating trading signals** from market data.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      SIGNAL GENERATION                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  UnifiedCandle ──────────────────────────────────────────┐      │
│       +                                                   │      │
│  History (50 candles)                                     ▼      │
│       │                                            ┌───────────┐ │
│       ├──▶ VcpProcessor ──▶ VcpState ─────────────▶│           │ │
│       │                                            │  FUDKII   │ │
│       ├──▶ IpuProcessor ──▶ IpuState ─────────────▶│Calculator │ │
│       │                                            │           │ │
│       └──▶ PivotProcessor ──▶ PivotState ─────────▶│           │ │
│                                                    └─────┬─────┘ │
│                                                          │       │
│                                                          ▼       │
│                                                    ┌───────────┐ │
│                                                    │ GateChain │ │
│                                                    └─────┬─────┘ │
│                                                          │       │
│                                                          ▼       │
│                                                  TradingSignal   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## SignalEngine

**File**: `engine/SignalEngine.java` (868 lines)

The main orchestrator that runs on a schedule (every 5 seconds).

### Lifecycle

```java
@PostConstruct
public void start() {
    // Initialize processors
    // Start scheduled processing
}

@Scheduled(fixedRateString = "${signal.engine.schedule.interval.ms:5000}")
public void processAllSymbols() {
    for (String symbol : configuredSymbols) {
        processSymbol(symbol, primaryTimeframe);
    }
}
```

### Processing Flow

```java
void processSymbol(String symbol, Timeframe timeframe) {
    // 1. Get data
    UnifiedCandle candle = candleService.getLatestCandle(symbol, timeframe);
    List<UnifiedCandle> history = candleService.getCandleHistory(symbol, timeframe, 50);
    
    // 2. Run processors
    VcpState vcpState = vcpProcessor.process(candle, history, timeframe);
    IpuState ipuState = ipuProcessor.process(candle, history);
    PivotState pivotState = pivotProcessor.process(candle, history);
    
    // 3. Calculate FUDKII score
    FudkiiScore score = fudkiiCalculator.calculate(
        candle, history, vcpState, ipuState, pivotState);
    
    // 4. Calculate indicators
    TechnicalIndicators indicators = calculateIndicators(symbol, history);
    
    // 5. Validate through gates
    ChainResult gateResult = validateThroughGates(symbol, score, indicators, ...);
    
    // 6. Process state machine
    processStateMachine(symbol, candle, score, currentSignal, pivotState, indicators);
}
```

### State Machine

```
    ┌──────┐
    │ IDLE │ ◀────────────────────────────┐
    └──┬───┘                              │
       │ score > watchThreshold            │
       │ + direction aligned               │
       ▼                                   │
    ┌───────┐                              │
    │ WATCH │ ─────── timeout ─────────────┤
    └──┬────┘                              │
       │ score > activeThreshold           │
       │ + gates passed                    │
       │ + trigger (VIB/DIB/pivot)         │
       ▼                                   │
    ┌────────┐                             │
    │ ACTIVE │ ── target/stop/reversal ────┘
    └────────┘
```

---

## FudkiiCalculator

**File**: `calculator/FudkiiCalculator.java` (578 lines)

Calculates the composite **FUDKII** score:
- **F**low - OFI momentum
- **U**rgency - Institutional urgency + exhaustion
- **D**irection - VCP runway
- **K**yle - Kyle's Lambda
- **I**mbalance - VIB/DIB triggers
- **I**ntensity - Tick activity

### Component Weights

```properties
signal.fudkii.weight.flow=0.15
signal.fudkii.weight.urgency=0.20
signal.fudkii.weight.direction=0.25
signal.fudkii.weight.kyle=0.10
signal.fudkii.weight.imbalance=0.15
signal.fudkii.weight.intensity=0.15
```

### Calculation Methods

```java
FlowResult calculateFlow(UnifiedCandle candle, List<UnifiedCandle> history) {
    // Normalize OFI relative to recent average
    double avgOfi = getAverageOfi(history);
    double stdOfi = getStdDevOfi(history, avgOfi);
    double zScore = (candle.getOfi() - avgOfi) / stdOfi;
    return new FlowResult(normalize(zScore), momentum, rawOfi);
}

UrgencyResult calculateUrgency(UnifiedCandle candle, IpuState ipuState) {
    // Use IPU score, detect exhaustion
    double exhaustion = ipuState.getExhaustion();
    return new UrgencyResult(score, bias, exhaustion, momentumState);
}

DirectionResult calculateDirection(UnifiedCandle candle, VcpState vcp, PivotState pivot) {
    // Calculate runway to support/resistance
    double bullishRunway = (candle.getClose() - vcp.getNearestSupport()) / candle.getClose();
    double bearishRunway = (vcp.getNearestResistance() - candle.getClose()) / candle.getClose();
    return new DirectionResult(score, bullishRunway, bearishRunway, structure);
}
```

### Watch vs Active

```java
boolean isWatchSetup(double score, double confidence, ...) {
    return score >= 40 &&           // watchThreshold
           confidence >= 0.5 &&
           urgency.exhaustion < 0.7;
}

boolean isActiveTrigger(double score, double confidence, ...) {
    return score >= 60 &&           // activeThreshold
           confidence >= 0.6 &&
           (imbalance.vibTriggered || imbalance.dibTriggered || pivotConfirmed);
}
```

---

## Strategy Processors

### VcpProcessor

**File**: `processor/VcpProcessor.java`

Calculates Volume Cluster Profile (support/resistance levels).

```java
public class VcpState {
    List<Cluster> clusters;      // Price levels with volume
    double poc;                  // Point of Control
    double vah, val;             // Value Area
    double nearestSupport;       // Below current price
    double nearestResistance;    // Above current price
    double bullishRunway;        // Distance to resistance
    double bearishRunway;        // Distance to support
}
```

### IpuProcessor

**File**: `processor/IpuProcessor.java`

Calculates Institutional Participation & Urgency.

```java
public class IpuState {
    double score;            // 0-100
    double momentum;         // Rate of change
    double exhaustion;       // Reversal warning (0-1)
    MomentumState state;     // ACCELERATING, STEADY, DECELERATING
    Direction bias;          // BULLISH, BEARISH, NEUTRAL
}
```

### PivotProcessor

**File**: `processor/PivotProcessor.java`

Identifies swing highs/lows for pivot levels.

```java
public class PivotState {
    List<Pivot> pivots;          // Historical pivots
    Pivot nearestHigherHigh;
    Pivot nearestLowerLow;
    boolean pivotBreak;          // Structure break detected
    boolean pivotRetest;         // Retest of broken level
}
```

---

## TradingSignal

**File**: `model/TradingSignal.java`

Output signal model.

```java
@Document(collection = "trading_signals")
public class TradingSignal {
    String id;
    String symbol;
    SignalState state;           // WATCH, ACTIVE, CLOSED
    Direction direction;         // BULLISH, BEARISH
    
    // Levels
    double entryPrice;
    double stopLoss;
    double target;
    double riskRewardRatio;
    
    // Scores
    double fudkiiScore;
    double confidence;
    
    // Timing
    Instant createdAt;
    Instant activatedAt;
    Instant closedAt;
    
    // Exit
    String exitReason;           // TARGET, STOPLOSS, REVERSAL, EXPIRED
    double exitPrice;
    double pnlPercent;
}
```

---

## Configuration

```properties
# Signal Engine
signal.engine.enabled=true
signal.engine.schedule.interval.ms=5000
signal.engine.primary.timeframe=5m
signal.engine.symbols=NIFTY,BANKNIFTY

# Thresholds
signal.watch.threshold=40
signal.active.threshold=60
signal.confidence.min=0.5

# Expiry
signal.watch.expiry.minutes=30
signal.active.expiry.minutes=120
```
