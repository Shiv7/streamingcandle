# KOTSIN Trading System Rebuild Plan

## Executive Summary

**Problem**: Current system is a signal spam generator (700+ signals/day) with no trade lifecycle management.

**Solution**: State machine architecture with clear strategies, one trade per instrument at a time.

**Goal**: 20-50 high-quality signals/day, 45-55% win rate, 2:1 average R:R.

---

## Architecture Overview

### The State Machine (Per Instrument)

```
IDLE ──→ WATCHING ──→ READY ──→ POSITIONED ──→ COOLDOWN ──→ IDLE
  ↑                      │            │              │
  └──────────────────────┴────────────┴──────────────┘
         (invalidation / exit / timeout)
```

| State | Description | Duration | Signals |
|-------|-------------|----------|---------|
| IDLE | No setup forming | Until setup detected | 0 |
| WATCHING | Setup forming, monitoring | 5-30 min typically | 0 |
| READY | Entry triggered | Until fill or cancel | **1** |
| POSITIONED | Trade live | Until exit | 0 |
| COOLDOWN | Rest after trade | 30 min | 0 |

**Key Rule**: Only ONE signal per state transition to READY. Then silence.

---

## Strategies

### Strategy 1: PIVOT_RETEST

**Edge**: Price respects pivot levels. Retest with rejection = high probability reversal.

**Entry Conditions (ALL required)**:
1. Price within 0.3% of pivot level (S1/S2/S3/R1/R2/R3/PP/Camarilla)
2. Rejection candle: wick > 2x body pointing away from level
3. SuperTrend direction supports trade (or flipping)
4. OFI direction supports trade (z-score > 1.0 in trade direction)
5. Session position sensible:
   - LONG: session position < 70%
   - SHORT: session position > 30%
6. VPIN < 0.7 (not extreme informed trading against us)

**Stop Loss**: 0.5% beyond the pivot level

**Targets**:
- T1: Next pivot level (take 50%)
- T2: Second pivot level (trail remaining)

**Time Stop**: 4 hours

---

### Strategy 2: FUDKII (Bollinger + SuperTrend Break)

**Edge**: BB squeeze followed by SuperTrend flip with volume = strong directional move.

**Entry Conditions (ALL required)**:
1. Bollinger Band squeeze detected (BB width < 20th percentile)
2. SuperTrend flips direction (30m timeframe)
3. Price breaks outside BB (close > upper BB for LONG, < lower for SHORT)
4. Volume surge: current volume > 1.5x 20-period average
5. OFI confirms direction (z-score > 1.5)
6. MTF alignment: 15m and 30m SuperTrend agree

**Stop Loss**: SuperTrend value (dynamic)

**Targets**:
- T1: 1.5 ATR from entry
- T2: 2.5 ATR from entry (trail)

**Time Stop**: 2 hours (breakouts should move fast)

---

### Strategy 3: VCP (Volatility Contraction Pattern)

**Edge**: Decreasing volatility + tight range = energy building for breakout.

**Entry Conditions (ALL required)**:
1. Price in uptrend (higher lows for past 5+ candles)
2. Volatility contracting: ATR decreasing for 3+ periods
3. Range contracting: each swing smaller than previous
4. Volume drying up: volume < 70% of average
5. Then: Volume surge + price break above recent high
6. OFI positive (z-score > 1.0)

**Stop Loss**: Below the contraction low

**Targets**:
- T1: Measured move (height of pattern)
- T2: 1.5x measured move

**Time Stop**: 4 hours

---

### Strategy 4: OFI_VELOCITY_SURGE

**Edge**: Sudden surge in order flow = institutional entry. Front-run the move.

**Entry Conditions (ALL required)**:
1. OFI velocity spike: current OFI change > 3x rolling average
2. OFI z-score crosses 2.0 (strongly directional)
3. Price confirms: moving in OFI direction
4. VPIN elevated (> 0.6) = informed trading
5. Volume confirms: > 1.2x average
6. Not at session extreme (not chasing)

**Stop Loss**: 0.5% against entry

**Targets**:
- T1: 0.8% in direction (quick scalp)
- T2: 1.5% (if momentum continues)

**Time Stop**: 30 minutes (scalp strategy)

---

### Strategy 5: VPIN_IMBALANCE

**Edge**: High VPIN with price at support/resistance = informed traders positioning.

**Entry Conditions (ALL required)**:
1. VPIN > 0.75 (high informed trading)
2. VPIN percentile > 80th
3. Price at significant level (pivot, swing high/low)
4. OFI confirms direction
5. Volume delta confirms (buy-dominant for LONG, sell for SHORT)
6. SuperTrend not opposing

**Stop Loss**: Beyond the level (0.4%)

**Targets**:
- T1: Next level
- T2: Trail with SuperTrend

**Time Stop**: 2 hours

---

## Components to Build

### 1. InstrumentStateManager (THE BRAIN)

```java
public class InstrumentStateManager {
    // State per instrument
    Map<String, InstrumentState> states;

    // Process candle update
    void onCandleUpdate(FamilyCandle candle, EnrichedQuantScore score);

    // State transitions
    void transitionToWatching(String familyId, String strategyId);
    void transitionToReady(String familyId, TradingSignal signal);
    void transitionToPositioned(String familyId);
    void transitionToCooldown(String familyId, TradeOutcome outcome);
    void transitionToIdle(String familyId);

    // Query state
    InstrumentState getState(String familyId);
    boolean canGenerateSignal(String familyId);
}
```

### 2. Strategy Interface

```java
public interface TradingStrategy {
    String getStrategyId();

    // Check if setup is forming (IDLE → WATCHING)
    boolean isSetupForming(EnrichedQuantScore score);

    // Check if entry triggered (WATCHING → READY)
    Optional<TradingSignal> checkEntryTrigger(EnrichedQuantScore score);

    // Check if exit conditions met (POSITIONED → COOLDOWN)
    Optional<ExitSignal> checkExitConditions(Position position, EnrichedQuantScore score);

    // Get strategy parameters
    StrategyParams getParams();
}
```

### 3. Position Tracker

```java
public class PositionTracker {
    // Active positions
    Map<String, Position> positions;

    // Open position
    void openPosition(String familyId, TradingSignal signal);

    // Update P&L
    void updatePosition(String familyId, double currentPrice);

    // Close position
    TradeOutcome closePosition(String familyId, ExitReason reason, double exitPrice);

    // Trail stop
    void trailStop(String familyId, double newStop);
}
```

### 4. Trade Outcome Recorder

```java
public class TradeOutcomeRecorder {
    // Record completed trade
    void recordTrade(TradeOutcome outcome);

    // Get strategy performance
    StrategyPerformance getPerformance(String strategyId);

    // Daily P&L
    DailyPnL getDailyPnL();
}
```

---

## Data Flow

```
FamilyCandle
    │
    ▼
EnrichedQuantScore (all 68 fields available)
    │
    ▼
InstrumentStateManager
    │
    ├── If IDLE: Check all strategies for setup forming
    │       └── If found: transition to WATCHING
    │
    ├── If WATCHING: Check strategy for entry trigger
    │       └── If triggered: transition to READY, emit ONE signal
    │
    ├── If POSITIONED: Check exit conditions
    │       └── If exit: transition to COOLDOWN, record outcome
    │
    └── If COOLDOWN: Check timer
            └── If expired: transition to IDLE
    │
    ▼
TradingSignal (only on WATCHING → READY)
    │
    ▼
Kafka: trading-signals-v2 (20-50 signals/day)
```

---

## Files to DELETE (Not Disable)

### Signal Generation Spam
- `PatternSignalPublisher.java` - DELETE entirely
- `SequenceTemplateRegistry.java` - DELETE (TREND_CONTINUATION lives here)
- `SetupDefinitionRegistry.java` - DELETE (8 setup types → 5 clean strategies)
- `SetupTracker.java` - DELETE
- `SetupEvaluator.java` - DELETE
- `ForecastEngine.java` - DELETE
- `OpportunityForecaster.java` - DELETE

### Signal Generator Gutting
- `SignalGenerator.java` - REWRITE to only proxy to InstrumentStateManager
- `SignalCoordinator.java` - DELETE (just created, but wrong approach)

### Pattern Matching
- `PatternMatcher.java` - DELETE
- `SequenceRecognizer.java` - DELETE
- All pattern-related classes in `/pattern/` directory

---

## Files to KEEP (As Filters)

### Enrichment (All 68 Fields)
- `EnrichedQuantScoreCalculator.java` - KEEP (this is gold)
- `HistoricalContextEnricher.java` - KEEP
- `TechnicalIndicatorEnricher.java` - KEEP
- `MTFSuperTrendAggregator.java` - KEEP
- `SessionStructureTracker.java` - KEEP
- `FamilyContextAnalyzer.java` - KEEP

### Detection (As Filters, Not Signal Generators)
- `EventDetector.java` - KEEP (but don't emit signals)
- `ConfluenceCalculator.java` - KEEP
- `GEXCalculator.java` - KEEP
- `MaxPainCalculator.java` - KEEP

### Models
- `TradingSignal.java` - KEEP (update if needed)
- `TechnicalContext.java` - KEEP
- `SessionStructure.java` - KEEP
- All model classes - KEEP

---

## Implementation Order

### Phase 1: Core Infrastructure (Day 1)
1. Create `InstrumentState` enum
2. Create `InstrumentStateManager`
3. Create `TradingStrategy` interface
4. Create `Position` and `PositionTracker`
5. Create `TradeOutcome` and `TradeOutcomeRecorder`

### Phase 2: Strategies (Day 2)
1. Implement `PivotRetestStrategy`
2. Implement `FudkiiStrategy`
3. Implement `VCPStrategy`
4. Implement `OFIVelocitySurgeStrategy`
5. Implement `VPINImbalanceStrategy`

### Phase 3: Integration (Day 3)
1. Wire InstrumentStateManager to receive candles
2. Route signal output to Kafka
3. Add position tracking on fill confirmation
4. Add exit monitoring

### Phase 4: Cleanup (Day 4)
1. DELETE all spam-generating files
2. REWRITE SignalGenerator to simple proxy
3. Remove dead code paths
4. Clean up Kafka topics

### Phase 5: Testing (Day 5-7)
1. Paper trade for 3 days
2. Track all outcomes
3. Calculate metrics
4. Tune parameters

---

## Success Metrics

| Metric | Target | Measurement |
|--------|--------|-------------|
| Signals/day | 20-50 | Count from Kafka |
| Win rate | 45-55% | Wins / Total trades |
| Avg R-multiple | 1.5-2.5 | Avg(P&L / Risk) |
| Max signals/instrument/day | 3 | Count per familyId |
| Time in POSITIONED state | 30min-4hr avg | State duration |
| Cooldown compliance | 100% | No signals during cooldown |

---

## Risk Controls

1. **Position Sizing**: 1 share per trade (paper trading phase)
2. **Max Positions**: 10 concurrent across all instruments
3. **Daily Loss Limit**: Stop trading if down 5% on day
4. **Per-Trade Risk**: Max 1% of capital per trade
5. **Correlation Check**: Don't take same direction in correlated instruments

---

## Questions Resolved

- State machine approach: **YES**
- Strategies: **5 defined above**
- Risk per trade: **1 share (paper)**
- Keep detection logic: **YES, as filters**
- Paper trade first: **YES, 3-5 days minimum**

---

## Next Steps

1. Review this plan
2. Confirm strategy definitions
3. Begin Phase 1 implementation
4. Daily progress updates

---

*Document created: 2026-01-20*
*Author: Claude (Quant Mode)*
