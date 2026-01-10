# ROADMAP TO TRADING INTELLIGENCE
## From Stateless Data Processor to Predictive Market Intelligence System

**Document Version:** 1.0  
**Date:** January 10, 2026  
**Author:** Architecture Team  
**Status:** Strategic Roadmap

---

## EXECUTIVE SUMMARY

This document provides a plain-English roadmap to transform your current trading system from a **data processor** that answers "What is happening?" into a **market intelligence system** that answers:

1. **WHY** is price here?
2. **WHO** is in control?
3. **WHAT** opportunities are forming?
4. **WHEN** will events likely occur?
5. **HOW** confident should we be?

The transformation requires adding four new capabilities on top of your existing solid data foundation:
- **Memory** (remember what happened before)
- **Pattern Recognition** (recognize sequences that predict outcomes)
- **Narrative Building** (explain the market story)
- **Opportunity Forecasting** (predict what comes next)

---

## PART 1: WHERE YOU ARE TODAY

### Your Current System Does This Well

Your system has an excellent data pipeline that most trading systems lack:

```
Ticks + Orderbook + OI  →  InstrumentCandle  →  FamilyCandle  →  Multi-Timeframe
```

**What you capture (that others don't):**
- Order Flow Imbalance (OFI) - who's aggressive
- Kyle's Lambda - price impact / liquidity
- VPIN - informed vs uninformed trading
- Depth slopes - book shape
- Options OI with changes - institutional positioning
- Volume delta - buyer vs seller pressure
- Spread dynamics - liquidity conditions

**This is valuable.** You have 163+ fields of market microstructure data per candle. Most retail systems have OHLCV (5 fields).

### The Core Problem: Amnesia

Your system processes each candle in isolation. When a candle arrives:

```
CANDLE ARRIVES
    ↓
Calculate metrics (OFI, VPIN, etc.)
    ↓
Apply fixed thresholds (if OFI > 500, signal BUY)
    ↓
Emit candle
    ↓
FORGET EVERYTHING
    ↓
Next candle arrives (no memory of previous)
```

**This means you cannot detect:**

| What You Miss | Why It Matters |
|---------------|----------------|
| OFI was negative for 10 candles, just flipped positive | This is a REVERSAL signal, not just "OFI is positive" |
| Volume delta has been accelerating for 5 minutes | Momentum is building, not just "volume is high" |
| Price is at the same level where OFI flipped yesterday | Historical support/resistance |
| Sellers have been exhausting for 15 minutes | Exhaustion precedes reversal |
| This exact pattern happened 847 times before with 73% success | Pattern recognition |

### The Threshold Problem

Your current logic uses hardcoded thresholds:

```java
// From your OISignalDetector.java
private static final int OPTION_OI_MIN_CHANGE = 1000;
private static final double OI_THRESHOLD = 2.0; // percent
```

**The problem:**

| Instrument | OI Base | +1000 OI Change | Reality |
|------------|---------|-----------------|---------|
| NATURALGAS | 4,000 | 25% increase | HUGE signal - should trigger |
| NIFTY | 10,000,000 | 0.01% increase | Noise - should ignore |

Your system treats them the same because the threshold is absolute (1000), not relative to the instrument's typical range.

### The Unused Data Problem

You calculate sophisticated metrics but never use them for signals:

| Metric | Calculated In | Used For Signals | Should Be Used For |
|--------|---------------|------------------|-------------------|
| Kyle's Lambda | InstrumentCandle | ❌ Stored only | Absorption detection, liquidity regime |
| VPIN | InstrumentCandle | ❌ Stored only | Informed flow detection, fade vs follow |
| Depth slopes | InstrumentCandle | ❌ Stored only | Institutional presence, book shape |
| Spread volatility | InstrumentCandle | ❌ Stored only | Liquidity withdrawal warning |
| Large trade count | InstrumentCandle | ❌ Stored only | Institutional participation |

**You're computing these expensive calculations and throwing away the insights.**

### The Timing Blindness Problem

Your system treats all times equally:

| Time | Reality | Your System |
|------|---------|-------------|
| 9:30 AM | Opening noise, 60% false signals | Same confidence |
| 10:30 AM | Prime trading, signals reliable | Same confidence |
| 12:30 PM | Lunch chop, 80% false signals | Same confidence |
| Expiry day | OI meaningless, gamma dominates | Same confidence |

### The Single-Timeframe Problem

Your 1-minute processor doesn't know what the daily chart says:

| Scenario | Correct Action | Your System |
|----------|----------------|-------------|
| 1m bullish + Daily bullish | Full position swing | Treats same as... |
| 1m bullish + Daily bearish | Scalp only, tight stop | ...this scenario |

---

## PART 2: WHERE YOU NEED TO GO

### The Target: Market Intelligence System

Instead of just processing candles, you need a system that builds understanding:

```
CANDLE ARRIVES
    ↓
Load historical context (what happened before?)
    ↓
Calculate relative metrics (is this unusual for THIS instrument?)
    ↓
Detect regime (what mode is the market in?)
    ↓
Detect events (did something significant just happen?)
    ↓
Match patterns (have we seen this sequence before?)
    ↓
Track setups (how complete are trading opportunities?)
    ↓
Build narrative (why is price here?)
    ↓
Forecast opportunities (what's likely to happen next?)
    ↓
Generate signals (what should we do?)
    ↓
REMEMBER EVERYTHING
```

### The Transformation in Plain English

**Before:** "OFI is +597"

**After:** "OFI just flipped from -434 to +597, a 2.5 standard deviation move. This is the 3rd consecutive positive candle after 12 negative candles. We've seen this pattern 847 times before - 73% of the time, price rises 1.2% within 30 minutes. This is happening at a confluence support zone where Daily S2 meets Weekly VAL. Options flow confirms with puts unwinding and calls building. SuperTrend flip is expected within 15-25 minutes based on similar historical patterns."

---

## PART 3: THE FOUR MISSING CAPABILITIES

### Capability 1: MEMORY (Historical Context)

**What it does:** Remembers the last N values of every metric and calculates statistics.

**How it works:**

For each family + timeframe combination, maintain rolling windows:

```
NATURALGAS:1m:history:ofi = [-434, 597, 455, 320, ...]  (last 20 values)
NATURALGAS:1m:history:volumeDelta = [-27, 46, 18, ...]
NATURALGAS:1m:history:oiChange = [19, 25, -10, ...]
```

From these histories, calculate:
- **Mean:** What's the average OFI for this instrument?
- **Standard Deviation:** How much does it typically vary?
- **Z-Score:** How unusual is the current value? (current - mean) / stddev
- **Percentile:** Where does current value rank? (80th percentile = higher than 80% of recent values)

**Why it matters:**

| Without Memory | With Memory |
|----------------|-------------|
| OFI = +597 | OFI = +597 (z-score 2.5, 95th percentile, flip from negative detected) |
| "OFI is positive" | "OFI is extremely positive and just flipped - this is unusual" |

**Storage:** Redis lists with 24-hour TTL

### Capability 2: PATTERN RECOGNITION (Sequence Detection)

**What it does:** Recognizes that a series of events forms a predictive pattern.

**How it works:**

Define pattern templates based on historical analysis:

```
Pattern: REVERSAL_FROM_SUPPORT
---------------------------------
Required Events (in order, within 10 minutes):
  1. SELLING_EXHAUSTION at support level
  2. OFI_FLIP (negative to positive)
  3. VOLUME_DELTA positive OR ABSORPTION detected

Optional Boosters (add confidence):
  + PUT_OI_UNWINDING → +15% probability
  + CALL_OI_SURGE → +12% probability
  + VPIN declining from high → +10% probability

Historical Stats:
  - Seen 847 times in backtest
  - Success rate: 73%
  - Average gain on success: 1.2%
  - Average loss on failure: 0.6%
  - Expected value: +0.72% per trade

Expected Outcome:
  - Price increase of 0.5-1.5% within 30 minutes
  - SuperTrend flip within 15-30 minutes
```

As events occur, track pattern progress:

```
NATURALGAS at 21:13:00
-----------------------
Pattern: REVERSAL_FROM_SUPPORT
Progress: 80% (4 of 5 events matched)

Events matched:
  ✓ SELLING_EXHAUSTION at 295.7 (21:07)
  ✓ OFI_FLIP at 295.8 (21:08)
  ✓ ABSORPTION detected (21:08-21:13)
  ✓ PUT_OI_UNWINDING (21:13)
  
Awaiting:
  ○ CALL_OI_SURGE (in progress, +360 so far)

Current probability: 73%
Expected outcome: SuperTrend flip within 20 minutes
```

**Why it matters:**

| Without Pattern Recognition | With Pattern Recognition |
|-----------------------------|--------------------------|
| Individual events: "OFI flipped", "Exhaustion detected" | "REVERSAL_FROM_SUPPORT pattern 80% complete with 73% historical success rate" |
| No prediction | "SuperTrend flip expected in 15-25 minutes" |

**Storage:** Redis hashes for active patterns, MongoDB for historical pattern outcomes

### Capability 3: NARRATIVE BUILDING (Market Story)

**What it does:** Generates human-readable explanation of why price is where it is.

**How it works:**

Synthesize all available context into a coherent story:

```
NATURALGAS Market Narrative at 21:13:00
----------------------------------------

WHY IS PRICE HERE?
Price is at 296.6 after bouncing from support at 295.5. The selling 
pressure that drove price down from 312.9 has exhausted. Key support 
held - Daily S2 (295.5) confluenced with weekly VAL (295.8).

WHAT HAPPENED (Last 25 min):
• 21:07 - Sellers exhausted at support (OFI velocity turning positive)
• 21:08 - Order flow flipped to buyers (OFI: -434 → +597)
• 21:08 - Large buyer started absorbing selling pressure
• 21:13 - Put writers covering (-71 OI), Call buyers entering (+360 OI)
• 21:13 - Price rallied 0.3% from low, momentum building

WHO'S IN CONTROL?
BUYERS [████████░░] Strong

Evidence:
• OFI positive for 5 consecutive candles
• Volume delta positive and accelerating
• Absorption of selling pressure detected
• Call OI building, Put OI declining

SMART MONEY ACTIVITY:
Informed traders (VPIN = 0.72) were active during the bounce.
VPIN is now declining (0.72 → 0.55), suggesting informed buying
is complete. This often precedes a larger retail-driven move.

OPTIONS MARKET SIGNAL:
GEX is negative (-4.5M) indicating a TRENDING market regime.
Max pain at 305 is pulling price higher. Gamma squeeze setup
forming at 300 strike.
```

**Why it matters:**

| Without Narrative | With Narrative |
|-------------------|----------------|
| Raw numbers: OFI=597, VPIN=0.55, etc. | "Price bounced from confluence support, buyers took control, smart money already positioned, squeeze setup forming" |
| You must mentally synthesize | System explains the story |

**Storage:** Generated on-demand, optionally cached in Redis for 5 minutes

### Capability 4: OPPORTUNITY FORECASTING (Predictions)

**What it does:** Predicts what's likely to happen next and what opportunities are forming.

**How it works:**

Based on patterns, context, and historical outcomes, generate forecasts:

```
OPPORTUNITY FORECAST - NATURALGAS at 21:13:00
----------------------------------------------

IMMEDIATE OPPORTUNITIES (Ready Now):
┌─────────────────────────────────────────────────────────┐
│  SCALP LONG - READY                                     │
│  Confidence: 82%                                        │
│                                                         │
│  Entry: 296.6 (current price)                          │
│  Stop: 295.0 (below support confluence)                │
│  Target 1: 298.5 (measured move)                       │
│  Target 2: 300.0 (gamma wall)                          │
│  Risk:Reward: 1.7                                      │
│                                                         │
│  WHY: REVERSAL_FROM_SUPPORT pattern 80% complete,      │
│       OI confirms, GEX regime = TRENDING, prime session │
└─────────────────────────────────────────────────────────┘

PENDING TRIGGERS (Watching For):
┌─────────────────────────────────────────────────────────┐
│  GAMMA SQUEEZE                                          │
│  Trigger: Price crosses 297.2                          │
│  Current: 296.6 (0.2% away)                            │
│  Probability of trigger: 65%                           │
│                                                         │
│  IF TRIGGERED:                                          │
│  → Dealers forced to buy (short gamma at 300 strike)   │
│  → Target: 302-305                                     │
│  → Timeframe: 30 min                                   │
└─────────────────────────────────────────────────────────┘

DEVELOPING SETUPS (Not Ready Yet):
┌─────────────────────────────────────────────────────────┐
│  SWING LONG                                             │
│  Progress: 40%                                          │
│                                                         │
│  Needs to complete:                                     │
│  ✗ SuperTrend flip                                     │
│  ✗ 15m OFI regime = BUY                                │
│  ✗ Price above daily pivot                             │
│                                                         │
│  Potential target: 315 over 2-3 days                   │
└─────────────────────────────────────────────────────────┘

PREDICTED EVENTS:
• SuperTrend flip - 68% probability within 15-25 min
• BB middle test - 72% probability within 20-40 min

INVALIDATION CONDITIONS (Exit if any occur):
• OFI flips negative (below -200)
• Price breaks below 295.0
• Call OI starts declining
• VPIN spikes above 0.75 with bearish flow
```

**Why it matters:**

| Without Forecasting | With Forecasting |
|---------------------|------------------|
| "Signal: LONG" | "SCALP LONG ready now at 82% confidence, GAMMA SQUEEZE triggers at 297.2, SWING LONG needs 3 more confirmations" |
| React to events | Anticipate events |

**Storage:** Generated per candle, stored in MongoDB for backtesting

---

## PART 4: THE TECHNICAL ARCHITECTURE

### New Layer Stack

Your current system has 3 layers. You need to add 5 more:

```
CURRENT LAYERS (Keep As-Is):
━━━━━━━━━━━━━━━━━━━━━━━━━━━
Layer 1: Data Capture
  Ticks + Orderbook + OI → InstrumentCandle

Layer 2: Family Grouping  
  InstrumentCandles → FamilyCandle (equity + derivatives)

Layer 3: Timeframe Aggregation
  1m FamilyCandle → 3m, 5m, 15m, 30m, 1h, 1d

NEW LAYERS (Build These):
━━━━━━━━━━━━━━━━━━━━━━━━━━━
Layer 4: ENRICHMENT
  Add historical context, Greeks, technical indicators
  Input: FamilyCandle
  Output: EnrichedFamilyCandle

Layer 5: EVENT DETECTION
  Detect significant market events
  Input: EnrichedFamilyCandle
  Output: List<DetectedEvent>

Layer 6: SEQUENCE RECOGNITION
  Match events to known patterns
  Input: DetectedEvent stream
  Output: List<ActiveSequence> with progress %

Layer 7: SETUP TRACKING
  Track trading setup completeness
  Input: EnrichedFamilyCandle + ActiveSequences
  Output: List<ActiveSetup> with confidence

Layer 8: INTELLIGENCE GENERATION
  Build narrative + forecast opportunities
  Input: All above
  Output: MarketNarrative + OpportunityForecast + TradingSignal
```

### Storage Architecture

You need three storage systems working together:

```
┌─────────────────────────────────────────────────────────────────────┐
│                      STORAGE ARCHITECTURE                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  REDIS (Hot State - Sub-millisecond Access)                        │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━                          │
│  • Rolling history buffers (last 20 OFI values, etc.)              │
│  • Current regime state (OFI regime = BUY, etc.)                   │
│  • Previous candle data                                             │
│  • Greeks snapshot                                                  │
│  • Session state (VWAP, high, low)                                 │
│  • Pending events awaiting confirmation                            │
│  • Active patterns and their progress                              │
│  • MTF alignment state                                              │
│                                                                     │
│  TTL: 24 hours (auto-cleanup)                                      │
│                                                                     │
│  MONGODB (Cold State - Persistent Storage)                         │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━                          │
│  • Daily/Weekly/Monthly indicators (pivots, BB, ATR)               │
│  • Enriched candles (for backtesting)                              │
│  • Event history with outcomes (for ML training)                   │
│  • Signal history with P&L (for strategy evaluation)               │
│  • Pattern statistics (for probability calculation)                │
│                                                                     │
│  Retention: 90 days for candles, 1 year for events/signals         │
│                                                                     │
│  KAFKA (Event Streaming)                                            │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━                          │
│  • enriched-family-candle-{tf} (enriched candle stream)            │
│  • detected-events (event stream)                                  │
│  • active-sequences (pattern progress updates)                     │
│  • active-setups (setup progress updates)                          │
│  • market-narrative (human-readable stories)                       │
│  • opportunity-forecast (predictions)                              │
│  • trading-signals (actionable signals)                            │
│                                                                     │
│  Retention: 7-30 days depending on topic                           │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Data Flow Diagram

```
                                    ┌─────────────────┐
                                    │     REDIS       │
                                    │   (Hot State)   │
                                    └────────┬────────┘
                                             │ read/write
                                             ▼
┌──────────────┐    ┌──────────────┐    ┌──────────────────────────────────────┐
│ family-      │    │ Enrichment   │    │                                      │
│ candle-{tf}  │───▶│ Orchestrator │───▶│  ENRICHMENT PIPELINE                 │
│              │    │              │    │                                      │
└──────────────┘    └──────────────┘    │  1. TimeContextEnricher              │
                                        │     → Add session, expiry context    │
                                        │                                      │
                    ┌──────────────┐    │  2. HistoricalContextEnricher        │
                    │   MONGODB    │◀──▶│     → Load history, calc stats       │
                    │ (Indicators) │    │     → Detect regimes, flips          │
                    └──────────────┘    │                                      │
                                        │  3. GreeksCalculator                 │
                                        │     → Calculate IV, Delta, Gamma     │
                                        │     → GEX regime, max pain, squeeze  │
                                        │                                      │
                                        │  4. TechnicalIndicatorEnricher       │
                                        │     → Load pivots, BB, ATR           │
                                        │     → Calculate SuperTrend           │
                                        │                                      │
                                        │  5. ConfluenceCalculator             │
                                        │     → Find S/R zones                 │
                                        │     → Nearest support/resistance     │
                                        │                                      │
                                        │  6. EventDetector                    │
                                        │     → Detect OFI flip, exhaustion    │
                                        │     → Track event lifecycle          │
                                        │                                      │
                                        │  7. SequenceRecognizer               │
                                        │     → Match events to patterns       │
                                        │     → Track pattern progress         │
                                        │                                      │
                                        │  8. SetupTracker                     │
                                        │     → Evaluate setup conditions      │
                                        │     → Calculate confidence           │
                                        │                                      │
                                        │  9. MTFConfluenceCalculator          │
                                        │     → LTF/HTF consensus              │
                                        │     → Alignment score                │
                                        │                                      │
                                        │  10. NarrativeGenerator              │
                                        │      → Build market story            │
                                        │                                      │
                                        │  11. OpportunityForecaster           │
                                        │      → Predict opportunities         │
                                        │                                      │
                                        │  12. SignalGenerator                 │
                                        │      → Generate trading signals      │
                                        │                                      │
                                        └──────────────────┬───────────────────┘
                                                           │
                    ┌──────────────────────────────────────┼───────────────────┐
                    │                                      │                   │
                    ▼                                      ▼                   ▼
           ┌──────────────┐                     ┌──────────────┐    ┌──────────────┐
           │ enriched-    │                     │ trading-     │    │ market-      │
           │ family-      │                     │ signals      │    │ narrative    │
           │ candle-{tf}  │                     │              │    │              │
           └──────┬───────┘                     └──────────────┘    └──────────────┘
                  │
                  ▼
           ┌──────────────┐
           │   MONGODB    │
           │  (Archive)   │
           └──────────────┘
```

---

## PART 5: THE IMPLEMENTATION ROADMAP

### Phase Overview

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                         IMPLEMENTATION PHASES                                  │
├───────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  PHASE 1: FOUNDATION (Weeks 1-3)                                             │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━                                              │
│  Goal: Add memory and relative thresholds                                     │
│  Modules: StateManager, HistoryBuffer, StatisticsCalculator                  │
│  Value: System starts remembering, thresholds become adaptive                │
│                                                                               │
│  PHASE 2: ENRICHMENT (Weeks 4-6)                                             │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━                                               │
│  Goal: Calculate Greeks, detect regimes, add time context                    │
│  Modules: GreeksCalculator, TimeContextEnricher, HistoricalContextEnricher   │
│  Value: System understands market regime and option dynamics                 │
│                                                                               │
│  PHASE 3: EVENTS (Weeks 7-9)                                                 │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━                                                 │
│  Goal: Detect and track market events                                        │
│  Modules: EventDetector, EventStore, TechnicalIndicatorEnricher             │
│  Value: System identifies significant market moments                         │
│                                                                               │
│  PHASE 4: PATTERNS (Weeks 10-12)                                             │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━                                               │
│  Goal: Recognize sequences that predict outcomes                             │
│  Modules: SequenceTemplates, SequenceTracker, PatternMatcher                │
│  Value: System predicts future events based on historical patterns          │
│                                                                               │
│  PHASE 5: INTELLIGENCE (Weeks 13-15)                                         │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━                                            │
│  Goal: Generate narrative and forecast opportunities                        │
│  Modules: NarrativeGenerator, OpportunityForecaster, SetupTracker           │
│  Value: System explains market and predicts opportunities                   │
│                                                                               │
│  PHASE 6: SIGNALS (Weeks 16-18)                                              │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━                                               │
│  Goal: Generate actionable trading signals with full context                │
│  Modules: SignalGenerator, MTFConfluenceCalculator, ConfluenceCalculator    │
│  Value: System produces trade recommendations with reasoning                │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘
```

### Phase 1: FOUNDATION (Memory & Adaptive Thresholds)

**Goal:** Give the system memory and make thresholds relative instead of absolute.

**What You Build:**

1. **RedisStateClient** - Interface to Redis for state operations
2. **HistoryBuffer** - Manages rolling windows of metric values
3. **StatisticsCalculator** - Calculates mean, stddev, z-score, percentile
4. **ThresholdCalculator** - Converts absolute thresholds to percentile-based

**How It Works:**

```
BEFORE (Current):
if (oiChange > 1000) → signal

AFTER (Phase 1):
1. Get history: oiChangeHistory = Redis.get("NATURALGAS:1m:history:oiChange")
2. Calculate stats: mean=50, stddev=150, current=360
3. Calculate z-score: (360-50)/150 = 2.07
4. Calculate percentile: 98th percentile
5. Apply relative threshold: if (percentile > 90) → signal
```

**Redis Keys Created:**

| Key Pattern | Example | Contents |
|-------------|---------|----------|
| `{family}:{tf}:history:{metric}` | `NATURALGAS:1m:history:ofi` | List of last 20 values |
| `{family}:{tf}:prev` | `NATURALGAS:1m:prev` | Previous candle snapshot |
| `{family}:{tf}:stats:{metric}` | `NATURALGAS:1m:stats:ofi` | mean, stddev (cached) |

**Testing:**

```
Test 1: History maintains exactly 20 values (oldest drops when 21st added)
Test 2: Z-score calculation matches manual calculation
Test 3: Percentile correctly ranks current value
Test 4: Same OI change triggers for NATURALGAS (small base) but not NIFTY (large base)
```

**Definition of Done:**
- All metrics have rolling history in Redis
- Statistics calculated for every metric
- Thresholds converted to percentile-based
- Unit tests pass for all calculators

### Phase 2: ENRICHMENT (Greeks & Context)

**Goal:** Calculate Greeks from raw data, add time/expiry awareness, detect regimes.

**What You Build:**

1. **BlackScholesCalculator** - Core Black-Scholes formulas
2. **IVCalculator** - Newton-Raphson implied volatility solver
3. **GreeksCalculator** - Delta, Gamma, Theta, Vega, GEX, Max Pain
4. **TimeContextEnricher** - Session classification, expiry context
5. **HistoricalContextEnricher** - Regime detection, flip detection
6. **RegimeDetector** - Classifies market regimes

**Greeks Calculation Flow:**

```
For each option in FamilyCandle:
  1. Extract: option_price, spot, strike, days_to_expiry
  2. Calculate IV using Newton-Raphson iteration
  3. Calculate d1, d2 from Black-Scholes
  4. Calculate Delta, Gamma, Theta, Vega
  5. Calculate this option's GEX contribution

Aggregate to family level:
  - Total Delta = Σ(delta × OI × multiplier)
  - Total Gamma = Σ(gamma × OI × multiplier)
  - Total GEX = Σ(strike_gex)
  
Determine GEX regime:
  - GEX < 0 → TRENDING (breakouts work)
  - GEX > 0 → MEAN_REVERTING (fades work)

Calculate Max Pain:
  - For each possible settlement price
  - Sum (call pain + put pain)
  - Min pain = max pain strike
```

**Regime Detection:**

```
For each metric (OFI, Volume Delta, OI Change, etc.):
  1. Get percentile (from Phase 1)
  2. Get consecutive count in same direction
  
  Classification:
    percentile > 80 AND consecutive >= 3 → STRONG_POSITIVE
    percentile > 60 OR consecutive >= 2  → POSITIVE
    percentile < 20 AND consecutive >= 3 → STRONG_NEGATIVE
    percentile < 40 OR consecutive >= 2  → NEGATIVE
    else → NEUTRAL
    
  Flip Detection:
    if previous_regime was NEGATIVE and current is POSITIVE:
      flip_detected = TRUE
      flip_magnitude = current - previous
```

**Time Context:**

| Session (NSE) | Time | Quality | Action |
|--------------|------|---------|--------|
| PRE_MARKET | 09:00-09:15 | 0.0 | No signals |
| OPENING_AUCTION | 09:15-09:20 | 0.2 | Watch only |
| OPENING_RANGE | 09:20-10:00 | 0.4 | Reduced size |
| MORNING_TREND | 10:00-12:00 | 1.0 | PRIME - full size |
| LUNCH_CHOP | 12:00-13:30 | 0.2 | Avoid |
| AFTERNOON | 13:30-14:30 | 0.8 | Normal |
| POWER_HOUR | 14:30-15:30 | 0.6 | Volatile |

**Testing:**

```
Test 1: IV solver converges for ITM, ATM, OTM options
Test 2: Greeks match Bloomberg/external source for known inputs
Test 3: GEX regime correctly identifies trending vs mean-reverting days
Test 4: Max pain calculation matches manual calculation
Test 5: Regime detection catches OFI flip in NATURALGAS example
Test 6: Time context correctly reduces confidence during lunch
```

**Definition of Done:**
- Greeks calculated for all options
- GEX regime determined per family
- Regimes detected for all key metrics
- Time context modifiers applied
- EnrichedFamilyCandle model complete

### Phase 3: EVENTS (Detection & Lifecycle)

**Goal:** Detect significant market events and track them through confirmation/failure.

**What You Build:**

1. **EventDetector** - Detects various event types
2. **EventStore** - Manages pending events in Redis
3. **EventConfirmationJob** - Checks pending events for confirmation
4. **TechnicalIndicatorEnricher** - SuperTrend, BB, pivots
5. **ConfluenceCalculator** - S/R zone detection

**Event Types:**

| Category | Event | Detection Criteria |
|----------|-------|-------------------|
| Microstructure | OFI_FLIP | OFI regime changed sign |
| Microstructure | SELLING_EXHAUSTION | OFI negative but velocity positive |
| Microstructure | ABSORPTION | Lambda low + depth imbalance + price stable |
| Technical | SUPERTREND_FLIP | SuperTrend direction changed |
| Technical | BB_LOWER_TOUCH | Price <= lower Bollinger Band |
| Options | CALL_OI_SURGE | Call OI change > 2 stddev |
| Options | PUT_OI_UNWINDING | Put OI declining + price rising |
| Options | GAMMA_SQUEEZE_SETUP | GEX negative + price near strike |

**Event Lifecycle:**

```
DETECTED ─────► PENDING ─────► CONFIRMED
                   │              │
                   │              └──► Store outcome for ML
                   │
                   └─────► FAILED
                   │         │
                   │         └──► Store failure reason
                   │
                   └─────► EXPIRED (no clear outcome)
```

**Confirmation Criteria Examples:**

| Event | Confirmation | Failure | Window |
|-------|-------------|---------|--------|
| OFI_FLIP (bullish) | Price +0.5% | Price -0.5% | 30 min |
| SUPERTREND_FLIP | Price stays above ST for 3 candles | Price back below | 15 min |
| BB_LOWER_TOUCH | Price bounces to middle BB | Price breaks lower | 60 min |
| GAMMA_SQUEEZE | Price exceeds strike | Price reverses | 30 min |

**Technical Indicators Added:**

```
Daily/Weekly/Monthly Pivots:
  - Standard: PP, R1, R2, R3, S1, S2, S3
  - Fibonacci: PP, 38.2%, 61.8% levels
  
Bollinger Bands:
  - Upper, Middle, Lower
  - Width (for squeeze detection)
  - %B (where price is within bands)
  
ATR:
  - 14-period ATR
  - ATR % of price
  
SuperTrend:
  - Current value
  - Direction (BULLISH/BEARISH)
  - Candles since last flip
```

**Testing:**

```
Test 1: OFI_FLIP detected when regime changes from NEGATIVE to POSITIVE
Test 2: Event moves to CONFIRMED when price rises 0.5%
Test 3: Event moves to FAILED when price falls 0.5%
Test 4: Expired events cleaned up after window
Test 5: SuperTrend calculation matches TradingView
Test 6: Confluence zone correctly groups nearby S/R levels
```

**Definition of Done:**
- All event types detected
- Event lifecycle tracked in Redis
- Confirmation/failure logic working
- Technical indicators calculated
- Confluence zones identified

### Phase 4: PATTERNS (Sequence Recognition)

**Goal:** Recognize that sequences of events predict outcomes.

**What You Build:**

1. **SequenceTemplateRegistry** - Defines known predictive patterns
2. **SequenceTracker** - Tracks active sequences per family
3. **PatternMatcher** - Matches incoming events to templates
4. **PatternOutcomeStore** - Stores historical pattern outcomes

**Pattern Template Structure:**

```java
public class SequenceTemplate {
    String id;                        // "REVERSAL_FROM_SUPPORT"
    String description;               // Human-readable description
    
    List<EventCondition> requiredEvents;   // Must occur in order
    List<EventCondition> boosterEvents;    // Optional, add probability
    List<EventCondition> invalidationEvents; // Any of these kills pattern
    
    Duration maxDuration;             // Pattern must complete within
    
    ExpectedOutcome outcome;          // What should happen if pattern completes
    HistoricalStats stats;            // From backtesting
}

public class EventCondition {
    String eventType;                 // "OFI_FLIP"
    String direction;                 // "BULLISH"
    Map<String, Object> constraints;  // Additional requirements
}

public class HistoricalStats {
    int occurrences;                  // 847
    double successRate;               // 0.73
    double avgGainOnSuccess;          // 1.2%
    double avgLossOnFailure;          // 0.6%
    double expectedValue;             // 0.72%
}
```

**Initial Pattern Library (Start with 5):**

| Pattern ID | Required Events | Expected Outcome |
|------------|----------------|------------------|
| REVERSAL_FROM_SUPPORT | EXHAUSTION → OFI_FLIP → ABSORPTION | Price +0.5-1.5% in 30min |
| REVERSAL_FROM_RESISTANCE | EXHAUSTION → OFI_FLIP → ABSORPTION | Price -0.5-1.5% in 30min |
| GAMMA_SQUEEZE_LONG | OI_SURGE + GEX_NEGATIVE + MOMENTUM | Price to strike + 2% |
| BREAKOUT_CONFIRMATION | RESISTANCE_TEST → VOLUME_SURGE → OFI_STRONG | Price +1-2% continuation |
| TREND_CONTINUATION | ST_BULLISH + OFI_POSITIVE + OI_BUILDUP | Price +0.5% in 15min |

**Active Sequence State:**

```json
{
  "familyId": "NATURALGAS",
  "templateId": "REVERSAL_FROM_SUPPORT",
  "startedAt": "2026-01-09T21:07:00",
  "progress": 0.80,
  "eventsMatched": [
    {"type": "SELLING_EXHAUSTION", "at": "21:07", "price": 295.7},
    {"type": "OFI_FLIP", "at": "21:08", "price": 295.8},
    {"type": "ABSORPTION", "at": "21:08-21:13"},
    {"type": "PUT_OI_UNWINDING", "at": "21:13"}
  ],
  "eventsAwaiting": ["CALL_OI_SURGE"],
  "currentProbability": 0.73,
  "expiresAt": "2026-01-09T21:17:00"
}
```

**Matching Algorithm:**

```
On each new DetectedEvent:
  1. Check if event matches FIRST event of any template
     → If yes, start new active sequence
     
  2. For each active sequence:
     a. Check if event matches NEXT required event
        → If yes, advance progress
     b. Check if event matches any booster
        → If yes, add probability
     c. Check if event matches any invalidation
        → If yes, terminate sequence
     d. Check if sequence expired
        → If yes, terminate sequence
        
  3. If sequence completed all required events:
     → Emit prediction
     → Track for outcome measurement
```

**Testing:**

```
Test 1: REVERSAL_FROM_SUPPORT pattern correctly matches NATURALGAS sequence
Test 2: Pattern terminates on invalidation event
Test 3: Pattern expires if not completed in time
Test 4: Booster events correctly increase probability
Test 5: Multiple patterns can be active simultaneously
Test 6: Outcome tracking records success/failure
```

**Definition of Done:**
- 5 initial patterns defined
- Pattern matching working real-time
- Progress tracking in Redis
- Outcome measurement in MongoDB
- Predictions generated when patterns complete

### Phase 5: INTELLIGENCE (Narrative & Forecasting)

**Goal:** Generate human-readable market stories and predict opportunities.

**What You Build:**

1. **NarrativeGenerator** - Synthesizes context into story
2. **OpportunityForecaster** - Predicts upcoming opportunities
3. **SetupTracker** - Tracks trading setup progress
4. **SetupDefinitionRegistry** - Defines trading setups

**Narrative Generation Logic:**

```
NarrativeGenerator.generate(EnrichedFamilyCandle candle, List<DetectedEvent> events, 
                            List<ActiveSequence> sequences):

  1. Build "Why is price here?" section:
     - Get last significant move from history
     - Find key support/resistance that held or broke
     - Identify what changed (OFI flip, etc.)
     
  2. Build "What happened" section:
     - Get events from last 30 minutes
     - Order chronologically
     - Describe each in plain English
     
  3. Build "Who's in control" section:
     - Check OFI regime, volume delta, absorption
     - Determine: BUYERS, SELLERS, or BALANCED
     - List supporting evidence
     
  4. Build "Smart money activity" section:
     - Check VPIN level and trend
     - Check large trade count
     - Check absorption detection
     
  5. Build "Options signal" section:
     - State GEX regime (TRENDING vs MEAN_REVERTING)
     - State distance to max pain
     - Note any squeeze setups
```

**Setup Definition Structure:**

```java
public class SetupDefinition {
    String id;                        // "SCALP_REVERSAL_LONG"
    String horizon;                   // "SCALP", "SWING", "POSITIONAL"
    String direction;                 // "LONG", "SHORT"
    
    List<Condition> requiredConditions;   // Must ALL be true
    List<Condition> boosterConditions;    // Nice to have
    List<Condition> invalidationConditions; // Any kills setup
    
    double baseConfidence;            // 0.65 when all required met
    double maxConfidence;             // 0.90 with all boosters
}
```

**Initial Setup Library:**

| Setup ID | Required Conditions | Entry Logic |
|----------|---------------------|-------------|
| SCALP_REVERSAL_LONG | Price at support + OFI BUY + Volume positive | Enter at current, stop below support |
| SCALP_REVERSAL_SHORT | Price at resistance + OFI SELL + Volume negative | Enter at current, stop above resistance |
| SWING_LONG | ST bullish + OFI BUY + OI buildup + HTF bullish | Enter on pullback to ST, stop below ST |
| SWING_SHORT | ST bearish + OFI SELL + OI buildup + HTF bearish | Enter on pullback to ST, stop above ST |
| BREAKOUT_LONG | Price above resistance + Volume surge + OFI strong | Enter on break, stop below resistance |

**Opportunity Forecast Structure:**

```java
public class OpportunityForecast {
    List<Opportunity> immediateOpportunities;  // Ready now
    List<PendingTrigger> pendingTriggers;      // Waiting for condition
    List<DevelopingSetup> developingSetups;    // Forming but not ready
    List<PredictedEvent> predictedEvents;      // What should happen
    List<String> invalidationConditions;       // What kills outlook
}
```

**Testing:**

```
Test 1: Narrative correctly describes NATURALGAS reversal scenario
Test 2: Setup progress correctly calculated (80% when 4/5 conditions met)
Test 3: Boosters correctly add confidence
Test 4: Invalidation correctly kills setup
Test 5: Immediate opportunities only shown when confidence > 60%
Test 6: Predicted events have reasonable probabilities
```

**Definition of Done:**
- Narrative generation produces coherent stories
- 5 setup definitions created
- Setup tracking working real-time
- Opportunity forecasting generates predictions
- All output via Kafka topics

### Phase 6: SIGNALS (Generation & Integration)

**Goal:** Generate trading signals with full context and reasoning.

**What You Build:**

1. **SignalGenerator** - Produces trading signals
2. **MTFConfluenceCalculator** - Multi-timeframe alignment
3. **ConfidenceCalculator** - Combines all modifiers
4. **SignalEnhancer** - Adds narrative and predictions to signals

**Signal Structure:**

```java
public class TradingSignal {
    // Identity
    String signalId;
    String familyId;
    Instant timestamp;
    String timeframe;
    
    // Core Signal
    String horizon;           // SCALP, SWING, POSITIONAL
    String direction;         // LONG, SHORT
    String signalType;        // REVERSAL, MOMENTUM, BREAKOUT
    double confidence;        // 0.0 - 1.0
    
    // Trade Management
    double entryPrice;
    double stopLoss;
    double target1;
    double target2;
    double riskReward;
    double recommendedSize;   // 0.5 - 1.2 based on alignment
    
    // Reasoning (NEW)
    List<String> entryReasons;
    String narrative;         // Why this signal?
    String sequenceBasis;     // Which pattern?
    List<String> predictedEvents;  // What should happen next?
    List<String> invalidationWatch; // What to monitor?
    
    // Context
    String mtfAlignment;
    String gexRegime;
    String session;
    String expiryPhase;
    double nearestSupport;
    double nearestResistance;
    
    // Modifiers Applied
    Map<String, Double> modifiers;
}
```

**Confidence Calculation:**

```
base_confidence = 0.5

// Add for supporting signals
for each signal in supporting_signals:
    base_confidence += signal.weight  // 0.05 - 0.15 each

// Apply modifiers
confidence = base_confidence
confidence *= time_context.modifier         // 0.3 - 1.0
confidence *= expiry_context.oi_weight      // 0.0 - 1.0
confidence *= gex_regime_modifier           // 0.7 or 1.2
confidence *= vpin_modifier                 // 0.8 - 1.1
confidence *= mtf_alignment_modifier        // 0.5 - 1.2
confidence *= lambda_modifier               // 0.9 - 1.1

// Cap
confidence = min(0.95, confidence)

// Threshold
if confidence < 0.60:
    DO NOT EMIT
```

**MTF Alignment Calculation:**

```
For each timeframe group:
  LTF (1m, 3m, 5m):
    - Check OFI regime
    - Check volume delta regime
    - Calculate consensus: BULLISH, BEARISH, NEUTRAL
    
  MTF (15m, 30m):
    - Same metrics
    - Add SuperTrend direction
    - Calculate consensus
    
  HTF (1h, 1d):
    - Same metrics
    - Weight more heavily
    - Calculate consensus

Overall Alignment:
  - All 3 groups same → FULLY_ALIGNED (confidence × 1.2)
  - LTF vs HTF conflict → COUNTER_TREND (confidence × 0.7, reduce size)
  - Mixed → PARTIAL (confidence × 0.9)
```

**GEX Regime Filter:**

```
if gex_regime == TRENDING:
    // Breakouts work, fades fail
    BOOST: BREAKOUT, MOMENTUM signals (× 1.2)
    REDUCE: MEAN_REVERSION, FADE signals (× 0.7)
    
if gex_regime == MEAN_REVERTING:
    // Fades work, breakouts fail
    BOOST: BB_TOUCH, MEAN_REVERSION signals (× 1.2)
    REDUCE: BREAKOUT, MOMENTUM signals (× 0.7)
```

**Testing:**

```
Test 1: Signal confidence correctly combines all modifiers
Test 2: Signal NOT emitted when confidence < 60%
Test 3: MTF conflict correctly reduces size recommendation
Test 4: GEX regime correctly filters signal types
Test 5: Signal contains full narrative and reasoning
Test 6: End-to-end: NATURALGAS example produces expected signal
```

**Definition of Done:**
- Signals generated with all context
- Confidence calculation correct
- MTF alignment working
- GEX regime filtering working
- Signals include narrative and predictions
- Full system test passes

---

## PART 6: SUCCESS METRICS

### How You Know It's Working

| Metric | Current State | Target State | How to Measure |
|--------|---------------|--------------|----------------|
| Signal Accuracy | ~50% (random) | >60% directional | Track signal outcomes in MongoDB |
| Signal Latency | N/A | <50ms per candle | Instrument enrichment pipeline |
| Pattern Recognition | 0 patterns | 5+ with >70% accuracy | Backtest patterns |
| Narrative Quality | None | Human-readable | Manual review |
| Prediction Accuracy | None | >65% event predictions | Track predicted vs actual events |
| Threshold Adaptiveness | 0% (hardcoded) | 100% percentile-based | Code review |
| Data Utilization | ~40% | 100% | Audit unused fields |

### Example: The NATURALGAS Test

Your system must correctly handle this scenario from January 9, 2026:

```
Input: NATURALGAS 1m candles from 20:45 to 21:30

Expected Output at 21:13:

1. NARRATIVE:
   "Price bounced from 295.5 support after sellers exhausted. 
    OFI flipped positive at 21:08. Buyers now in control."

2. ACTIVE PATTERN:
   "REVERSAL_FROM_SUPPORT - 80% complete, 73% probability"

3. PREDICTION:
   "SuperTrend flip expected within 15-25 minutes"

4. SIGNAL:
   "SCALP LONG, confidence 82%, entry 296.6, stop 295.0, target 298.5"

5. INVALIDATION WATCH:
   "Exit if OFI flips negative or price breaks 295.0"

Actual Outcome (21:30):
- SuperTrend DID flip (prediction correct)
- Price DID reach 298+ (signal correct)
- Pattern completed successfully
```

---

## PART 7: RISKS AND MITIGATIONS

### Technical Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| Redis fails | Lose state, degraded signals | Failover to partial enrichment, emit with reduced confidence |
| Latency exceeds 50ms | Stale signals | Profile each enricher, optimize hotspots, add circuit breaker |
| Pattern overfitting | False confidence | Strict train/test split, out-of-sample validation |
| MongoDB bottleneck | Slow persistence | Async writes, batch operations, sharding |

### Business Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| Scope creep | Delayed delivery | Fixed phase scope, defer nice-to-haves |
| Threshold calibration wrong | Bad signals | Extensive backtesting, paper trading phase |
| Greeks calculation errors | Wrong regime | Validate against external source (Bloomberg, NSE) |
| Pattern library too small | Missed opportunities | Start with 5, add based on research |

### Operational Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| State corruption | Wrong signals | Daily Redis validation, recovery procedures |
| Version mismatch | Incompatible data | Schema versioning in all models |
| Rollback needed | System downtime | Feature flags for each phase |

---

## PART 8: NEXT STEPS

### Immediate Actions (This Week)

1. **Review and approve this roadmap**
    - Confirm scope of each phase
    - Agree on success metrics
    - Identify any missing requirements

2. **Set up infrastructure**
    - Redis cluster for state storage
    - MongoDB collections for persistence
    - New Kafka topics

3. **Start Phase 1**
    - Implement StateManager interface
    - Build HistoryBuffer with Redis backend
    - Convert first threshold to percentile-based

### Week 1 Deliverables

- StateManager interface defined
- RedisStateClient implemented
- HistoryBuffer storing OFI, volumeDelta, oiChange for 1 family
- StatisticsCalculator with mean, stddev, z-score, percentile
- Unit tests for all components

---

## APPENDIX A: COMPLETE MODULE LIST

| ID | Module | Phase | Purpose |
|----|--------|-------|---------|
| M-01 | EnrichmentOrchestrator | 2 | Coordinates all enrichers |
| M-02 | EnrichmentPipeline | 2 | Manages enricher sequence |
| M-03 | EnrichmentContext | 2 | Carries state through pipeline |
| M-04 | TimeContextEnricher | 2 | Session and expiry context |
| M-05 | HistoricalContextEnricher | 2 | History and regime detection |
| M-06 | GreeksCalculator | 2 | IV, Greeks, GEX, Max Pain |
| M-07 | TechnicalIndicatorEnricher | 3 | Pivots, BB, ATR, SuperTrend |
| M-08 | ConfluenceCalculator | 3 | S/R zone detection |
| M-09 | EventDetector | 3 | Detect market events |
| M-10 | MTFConfluenceCalculator | 6 | Multi-timeframe alignment |
| M-11 | SignalGenerator | 6 | Generate trading signals |
| M-12 | StateManager | 1 | State operations interface |
| M-13 | HistoryBuffer | 1 | Rolling history management |
| M-14 | RegimeDetector | 2 | Classify market regimes |
| M-15 | EventStore | 3 | Event lifecycle management |
| M-16 | BlackScholesCalculator | 2 | Black-Scholes formulas |
| M-17 | IVCalculator | 2 | Implied volatility solver |
| M-18 | GEXCalculator | 2 | Gamma exposure calculation |
| M-19 | MaxPainCalculator | 2 | Max pain strike |
| M-20 | StatisticsCalculator | 1 | Mean, stddev, z-score |
| M-21 | ThresholdCalculator | 1 | Percentile-based thresholds |
| M-22 | EnrichedFamilyCandle | 2 | Enhanced candle model |
| M-23 | HistoricalContext | 2 | History context model |
| M-24 | TimeContext | 2 | Time context model |
| M-25 | GreeksPortfolio | 2 | Greeks model |
| M-26 | TechnicalContext | 3 | Technical indicators model |
| M-27 | ConfluenceZone | 3 | S/R zone model |
| M-28 | DetectedEvent | 3 | Event model |
| M-29 | MTFState | 6 | MTF alignment model |
| M-30 | TradingSignal | 6 | Signal model |
| M-31 | RegimeState | 2 | Regime model |
| M-32 | EnrichmentConfig | 2 | Configuration |
| M-33 | ThresholdConfig | 1 | Threshold configuration |
| M-34 | SessionConfig | 2 | Session times configuration |
| M-35 | GreeksConfig | 2 | Greeks parameters |
| M-36 | MongoSinkService | 3 | MongoDB persistence |
| M-37 | IndicatorPersistenceService | 3 | Indicator storage |
| M-38 | EnrichedCandleRepository | 3 | Candle repository |
| M-39 | EventRepository | 3 | Event repository |
| M-40 | SignalRepository | 6 | Signal repository |
| M-41 | IndicatorRepository | 3 | Indicator repository |
| M-42 | DailyIndicatorJob | 3 | Daily pivot calculation |
| M-43 | WeeklyIndicatorJob | 3 | Weekly pivot calculation |
| M-44 | MonthlyIndicatorJob | 3 | Monthly pivot calculation |
| M-45 | RedisStateClient | 1 | Redis operations |
| M-46 | RedisHistoryClient | 1 | History operations |
| M-47 | SequenceTemplateRegistry | 4 | Pattern definitions |
| M-48 | SequenceTracker | 4 | Active pattern tracking |
| M-49 | PatternMatcher | 4 | Event to pattern matching |
| M-50 | SetupDefinitionRegistry | 5 | Setup definitions |
| M-51 | SetupScorer | 5 | Setup progress calculation |
| M-52 | SetupMonitor | 5 | Active setup tracking |
| M-53 | NarrativeGenerator | 5 | Market story generation |
| M-54 | OpportunityForecaster | 5 | Opportunity prediction |

---

## APPENDIX B: KEY DATA MODELS

### EnrichedFamilyCandle (extends FamilyCandle)

```
EnrichedFamilyCandle
├── [All existing FamilyCandle fields]
├── timeContext
│   ├── sessionName (MORNING_TREND, LUNCH_CHOP, etc.)
│   ├── sessionQuality (0.0 - 1.0)
│   ├── expiryPhase (FAR, NEAR, EXPIRY_DAY)
│   └── modifiers (time, expiry, gamma weights)
├── historicalContext
│   ├── ofiContext (history, mean, stddev, zscore, percentile, regime, flip)
│   ├── volumeDeltaContext (same structure)
│   ├── oiChangeContext (same structure)
│   ├── lambdaContext (same structure)
│   ├── vpinContext (same structure)
│   ├── absorptionDetected (boolean)
│   ├── liquidityWithdrawal (boolean)
│   └── informedFlowActive (boolean)
├── greeksPortfolio
│   ├── optionGreeks[] (per option)
│   ├── totalDelta, totalGamma, totalTheta, totalVega
│   ├── totalGex, gexRegime (TRENDING/MEAN_REVERTING)
│   ├── maxPainStrike, distanceToMaxPain
│   ├── ivSkew, ivSkewZscore
│   └── squeezeAlert, squeezeDirection, squeezeTarget
├── technicalContext
│   ├── dailyPivots (PP, R1-R3, S1-S3)
│   ├── weeklyPivots, monthlyPivots
│   ├── bollingerBands (upper, middle, lower, width)
│   ├── atr, atrPercent
│   ├── superTrend, superTrendDirection
│   └── sessionVwap, sessionHigh, sessionLow
├── confluenceZones
│   ├── zones[] (price, strength, type, sources)
│   ├── nearestSupport, nearestResistance
│   └── inConfluenceZone (boolean)
├── eventContext
│   ├── detectedEvents[] (this candle)
│   ├── pendingEvents[] (awaiting confirmation)
│   ├── confirmedEvents[] (recently confirmed)
│   └── failedEvents[] (recently failed)
├── mtfState
│   ├── ltfConsensus, ltfScore
│   ├── mtfConsensus, mtfScore
│   ├── htfConsensus, htfScore
│   ├── overallAlignment
│   └── positionSizeMultiplier
└── enrichmentMeta
    ├── enrichmentVersion
    ├── totalLatencyMs
    └── enrichersApplied[]
```

---

## APPENDIX C: REDIS KEY PATTERNS

| Purpose | Key Pattern | Example | Type | TTL |
|---------|-------------|---------|------|-----|
| Rolling history | `{family}:{tf}:history:{metric}` | `NATURALGAS:1m:history:ofi` | List | 24h |
| Regime state | `{family}:{tf}:regime` | `NATURALGAS:1m:regime` | Hash | None |
| Previous candle | `{family}:{tf}:prev` | `NATURALGAS:1m:prev` | Hash | 2h |
| Greeks snapshot | `{family}:greeks` | `NATURALGAS:greeks` | Hash | None |
| Greeks history | `{family}:greeks:history:{metric}` | `NATURALGAS:greeks:history:ivSkew` | List | 24h |
| Session state | `{family}:session:{date}` | `NATURALGAS:session:2026-01-09` | Hash | 26h |
| Pending events | `{family}:events:pending:{eventId}` | `NATURALGAS:events:pending:evt_123` | Hash | 1h |
| Event log | `{family}:events:log` | `NATURALGAS:events:log` | Sorted Set | 7d |
| MTF state | `{family}:mtf` | `NATURALGAS:mtf` | Hash | None |
| Active sequences | `{family}:sequences:active` | `NATURALGAS:sequences:active` | Hash | 1h |
| Active setups | `{family}:setups:active` | `NATURALGAS:setups:active` | Hash | None |

---

## APPENDIX D: KAFKA TOPICS

| Topic | Key | Value | Retention | Purpose |
|-------|-----|-------|-----------|---------|
| enriched-family-candle-{tf} | familyId | EnrichedFamilyCandle | 7d | Full enriched candles |
| detected-events | familyId | DetectedEvent | 30d | Individual events |
| active-sequences | familyId | ActiveSequence | 7d | Pattern progress |
| active-setups | familyId | ActiveSetup | 7d | Setup progress |
| market-narrative | familyId | MarketNarrative | 7d | Human-readable stories |
| opportunity-forecast | familyId | OpportunityForecast | 7d | Predictions |
| trading-signals | familyId | TradingSignal | 30d | Actionable signals |
| regime-changes | familyId:tf:metric | RegimeChange | 7d | Regime transitions |

---

**END OF DOCUMENT**

*This roadmap provides the high-level vision and approach. Detailed design documents will be created for each phase before implementation begins.*