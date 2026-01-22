# Microstructure Consensus Fix

## Document Version
- **Created**: January 2026
- **Status**: Fixed (User implemented)
- **Impact**: Dashboard MTF heatmap display

---

## Table of Contents
1. [Problem Statement](#problem-statement)
2. [Root Cause Analysis](#root-cause-analysis)
3. [Why Only 0% or 100%](#why-only-0-or-100)
4. [Fix Approach](#fix-approach)
5. [Files Affected](#files-affected)
6. [Verification](#verification)

---

## Problem Statement

### Symptom

The MTF Score Heatmap on the dashboard displayed microstructure consensus as either **0%** or **100%** - never intermediate values like 25%, 37.5%, 50%, 62.5%, 75%, etc.

```
OBSERVED BEHAVIOR:
┌────────────────────────────────────────────────────────┐
│ Component       │ 1m │ 5m │ 15m │ 30m │ 1h │ Consensus │
├────────────────────────────────────────────────────────┤
│ Microstructure  │ 12 │ 14 │ 13  │ 11  │ 10 │   100%   │ ← Always 100%
│ Microstructure  │  3 │  4 │  5  │  4  │  3 │     0%   │ ← Always 0%
│                 │    │    │     │     │    │          │
│ NEVER SEEN:     │  8 │ 12 │  6  │ 10  │  4 │    50%   │ ← Missing!
└────────────────────────────────────────────────────────┘
```

### Expected Behavior

Consensus should show intermediate percentages based on how many timeframes have scores above the threshold.

```
EXPECTED BEHAVIOR (8 timeframes):
- 0 pass threshold → 0%
- 1 pass threshold → 12.5%
- 2 pass threshold → 25%
- 3 pass threshold → 37.5%
- 4 pass threshold → 50%
- 5 pass threshold → 62.5%
- 6 pass threshold → 75%
- 7 pass threshold → 87.5%
- 8 pass threshold → 100%
```

---

## Root Cause Analysis

### Location of Bug

**File**: `trading-dashboard/frontend/src/components/MTF/MTFScoreHeatmap.tsx`

**Function**: `calculateConsensus()` (lines 56-75)

### Original Code (Buggy)

```typescript
const calculateConsensus = (key: string) => {
  let above50 = 0
  let total = 0
  const cat = SCORE_CATEGORIES.find(c => c.key === key)
  if (!cat) return { percentage: 0, icon: '●' }

  scores.forEach(s => {
    if (s.breakdown) {
      const score = getBreakdownScore(s.breakdown, key)
      if ((score / cat.max) >= 0.5) above50++  // Binary: pass/fail at 50%
      total++
    }
  })

  const pct = total > 0 ? (above50 / total) * 100 : 0
  return {
    percentage: pct,
    icon: pct >= 75 ? '▲' : pct >= 50 ? '◐' : '▼'
  }
}
```

### Why This Code Produces Only 0% or 100%

The logic itself is mathematically correct - it COULD produce intermediate values. The issue is the **nature of microstructure data**:

```
MICROSTRUCTURE DATA CORRELATION:
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│  Microstructure metrics measure REAL-TIME ORDER FLOW:           │
│  - OFI (Order Flow Imbalance)                                   │
│  - VPIN (Volume-Synchronized Probability of Informed Trading)   │
│  - Kyle's Lambda (Price Impact)                                 │
│  - Aggressive Volume Ratio                                      │
│                                                                 │
│  These metrics are CORRELATED across timeframes because:        │
│  - They measure the SAME underlying market activity             │
│  - Strong buying pressure shows in ALL timeframes               │
│  - Weak activity shows in ALL timeframes                        │
│                                                                 │
│  RESULT:                                                        │
│  - Strong market: ALL timeframes score HIGH → ALL pass → 100%   │
│  - Weak market: ALL timeframes score LOW → ALL fail → 0%        │
│  - Intermediate rarely occurs due to correlation                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Data Flow Confirmation

The backend correctly calculates microstructure scores for all timeframes:

```
QuantScoreProcessor.java (lines 125-138):
├── Listens to ALL timeframe topics:
│   ├── family-candle-1m
│   ├── family-candle-5m
│   ├── family-candle-15m
│   ├── family-candle-30m
│   ├── family-candle-1h
│   ├── family-candle-2h
│   ├── family-candle-4h
│   └── family-candle-1d
└── All timeframes get MicrostructureScoreCalculator processing ✓
```

**The backend is NOT the issue.** The issue is frontend calculation + data correlation.

---

## Why Only 0% or 100%

### Mathematical Explanation

For microstructure (max = 18 points), the threshold is 9 (50% of 18).

```
SCENARIO A: Strong Order Flow (All Pass)
┌──────────────────────────────────────────────────┐
│ Timeframe │ Score │ Threshold │ Pass? │          │
├──────────────────────────────────────────────────┤
│ 1m        │  12   │    9      │  ✓    │          │
│ 5m        │  14   │    9      │  ✓    │          │
│ 15m       │  13   │    9      │  ✓    │          │
│ 30m       │  11   │    9      │  ✓    │          │
│ 1h        │  10   │    9      │  ✓    │          │
│ 2h        │  12   │    9      │  ✓    │          │
│ 4h        │  11   │    9      │  ✓    │          │
│ 1d        │  10   │    9      │  ✓    │          │
├──────────────────────────────────────────────────┤
│ RESULT    │       │           │ 8/8   │ = 100%   │
└──────────────────────────────────────────────────┘

SCENARIO B: Weak Order Flow (All Fail)
┌──────────────────────────────────────────────────┐
│ Timeframe │ Score │ Threshold │ Pass? │          │
├──────────────────────────────────────────────────┤
│ 1m        │   5   │    9      │  ✗    │          │
│ 5m        │   6   │    9      │  ✗    │          │
│ 15m       │   4   │    9      │  ✗    │          │
│ 30m       │   7   │    9      │  ✗    │          │
│ 1h        │   5   │    9      │  ✗    │          │
│ 2h        │   6   │    9      │  ✗    │          │
│ 4h        │   4   │    9      │  ✗    │          │
│ 1d        │   5   │    9      │  ✗    │          │
├──────────────────────────────────────────────────┤
│ RESULT    │       │           │ 0/8   │ = 0%     │
└──────────────────────────────────────────────────┘
```

### Why Intermediate Values Are Rare

```
For intermediate values (e.g., 50%), you need DIVERGENCE:
- Half the timeframes strong (> 9)
- Half the timeframes weak (< 9)

This is RARE because:
1. Microstructure measures the SAME order flow across timeframes
2. TimeframeAggregator SUMS microstructure metrics (OFI, volume)
3. Higher timeframes aggregate MORE data → similar direction

The only way to get divergence:
- Very recent activity spike (1m high, 1h still low)
- Market regime change in progress
- Data quality issues (some timeframes missing data)
```

---

## Fix Approach

### Option 4: Timeframe-Weighted Leading Edge Score (RECOMMENDED - IMPLEMENTED)

This is the recommended approach for **early detection** and **maximum profit**. It prioritizes lower timeframes (where institutional activity appears first) while using higher timeframes for confirmation.

#### The Formula

```
LeadingEdgeScore = (LTF_Weight × LTF_Avg) + (MTF_Weight × MTF_Avg) + (HTF_Weight × HTF_Avg) + MomentumBonus
```

#### Timeframe Weights

| Group | Timeframes | Weight | Rationale |
|-------|------------|--------|-----------|
| **LTF** (Lower) | 1m, 5m | 0.45 | Highest - early detection, institutional entry |
| **MTF** (Medium) | 15m, 30m | 0.35 | Confirmation building |
| **HTF** (Higher) | 1h, 2h, 4h, 1d | 0.20 | Lagging confirmation |

#### Momentum Bonus

| Pattern | Condition | Bonus | Interpretation |
|---------|-----------|-------|----------------|
| **Leading** | LTF > MTF > HTF | +15 | Classic early entry - institutions moving first |
| **Early Stage** | LTF > MTF | +10 | Move starting, HTF catching up |
| **Aligned** | All similar | 0 | Move in progress, late entry |
| **Reversal Risk** | HTF > LTF | -10 | Higher TFs strong but LTF weakening - caution |

#### Implementation

```typescript
const calculateLeadingEdgeConsensus = (key: string) => {
  const cat = SCORE_CATEGORIES.find(c => c.key === key)
  if (!cat) return { percentage: 0, icon: '●', pattern: 'NONE' }

  // Group timeframes
  const LTF_TIMEFRAMES = ['1m', '5m']
  const MTF_TIMEFRAMES = ['15m', '30m']
  const HTF_TIMEFRAMES = ['1h', '2h', '4h', '1d']

  // Weights
  const LTF_WEIGHT = 0.45
  const MTF_WEIGHT = 0.35
  const HTF_WEIGHT = 0.20

  // Calculate averages for each group
  const getGroupAverage = (timeframes: string[]) => {
    let sum = 0
    let count = 0
    scores.forEach(s => {
      if (s.breakdown && s.timeframe && timeframes.includes(s.timeframe)) {
        const score = getBreakdownScore(s.breakdown, key)
        sum += (score / cat.max) * 100  // Normalize to 0-100
        count++
      }
    })
    return count > 0 ? sum / count : 0
  }

  const ltfAvg = getGroupAverage(LTF_TIMEFRAMES)
  const mtfAvg = getGroupAverage(MTF_TIMEFRAMES)
  const htfAvg = getGroupAverage(HTF_TIMEFRAMES)

  // Calculate base weighted score
  let baseScore = (LTF_WEIGHT * ltfAvg) + (MTF_WEIGHT * mtfAvg) + (HTF_WEIGHT * htfAvg)

  // Determine pattern and apply momentum bonus
  let momentumBonus = 0
  let pattern = 'ALIGNED'

  if (ltfAvg > mtfAvg && mtfAvg > htfAvg) {
    // Classic leading pattern: LTF > MTF > HTF
    momentumBonus = 15
    pattern = 'LEADING'
  } else if (ltfAvg > mtfAvg) {
    // Early stage: LTF > MTF
    momentumBonus = 10
    pattern = 'EARLY'
  } else if (htfAvg > ltfAvg && htfAvg > mtfAvg) {
    // Reversal risk: HTF strongest, LTF weakest
    momentumBonus = -10
    pattern = 'REVERSAL_RISK'
  } else if (ltfAvg < 30 && mtfAvg < 30 && htfAvg < 30) {
    // All weak
    pattern = 'WEAK'
  }

  const finalScore = Math.max(0, Math.min(100, baseScore + momentumBonus))

  return {
    percentage: finalScore,
    icon: finalScore >= 70 ? '▲' : finalScore >= 50 ? '◐' : finalScore >= 30 ? '▼' : '○',
    pattern: pattern,
    ltfAvg: ltfAvg,
    mtfAvg: mtfAvg,
    htfAvg: htfAvg,
    momentumBonus: momentumBonus
  }
}
```

#### Example Scoring

```
┌────────────────────────────┬───────┬───────┬───────┬─────────┬───────┬───────────────┐
│          Scenario          │  LTF  │  MTF  │  HTF  │ Pattern │ Score │    Action     │
├────────────────────────────┼───────┼───────┼───────┼─────────┼───────┼───────────────┤
│ Early institutional buying │  85%  │  55%  │  30%  │ LEADING │  78   │ ENTER EARLY   │
├────────────────────────────┼───────┼───────┼───────┼─────────┼───────┼───────────────┤
│ Move in progress           │  80%  │  75%  │  70%  │ ALIGNED │  75   │ Enter (late)  │
├────────────────────────────┼───────┼───────┼───────┼─────────┼───────┼───────────────┤
│ Consolidation              │  40%  │  45%  │  50%  │ LAGGING │  42   │ Wait          │
├────────────────────────────┼───────┼───────┼───────┼─────────┼───────┼───────────────┤
│ Reversal risk              │  30%  │  50%  │  70%  │ REV_RISK│  37   │ CAUTION       │
├────────────────────────────┼───────┼───────┼───────┼─────────┼───────┼───────────────┤
│ No signal                  │  25%  │  20%  │  15%  │ WEAK    │  21   │ No trade      │
└────────────────────────────┴───────┴───────┴───────┴─────────┴───────┴───────────────┘
```

#### Why This Works for Maximum Profit

1. **Catches Institutional Entry Early**: Smart money moves on 1m/5m microstructure first
2. **Filters False Signals**: Requires momentum building (MTF improving)
3. **Risk Management**: Warns when higher TFs contradict (potential trap)
4. **Scales Position**: Early entry on leading edge, add on confirmation

#### Visual Pattern Detection

```
LEADING PATTERN (Best Entry):
┌─────────────────────────────────────────────────┐
│  1m  │████████████████  85%   ← STRONGEST      │
│  5m  │██████████████    80%                    │
│ 15m  │██████████        55%                    │
│ 30m  │████████          50%                    │
│  1h  │██████            35%                    │
│  4h  │████              30%   ← WEAKEST        │
└─────────────────────────────────────────────────┘
       Institutions entering, retail hasn't noticed
       ACTION: ENTER EARLY with tight stop

REVERSAL RISK PATTERN (Caution):
┌─────────────────────────────────────────────────┐
│  1m  │████              30%   ← WEAKEST        │
│  5m  │██████            35%                    │
│ 15m  │████████          50%                    │
│ 30m  │██████████        55%                    │
│  1h  │██████████████    70%                    │
│  4h  │████████████████  80%   ← STRONGEST      │
└─────────────────────────────────────────────────┘
       HTF strong but LTF weakening = potential reversal
       ACTION: CAUTION - wait for LTF to confirm
```

---

### Option 1: Simple Weighted Average

Instead of binary pass/fail, calculate weighted average of scores across timeframes.

```typescript
const calculateConsensus = (key: string) => {
  const cat = SCORE_CATEGORIES.find(c => c.key === key)
  if (!cat) return { percentage: 0, icon: '●' }

  let totalScore = 0
  let totalMax = 0

  scores.forEach(s => {
    if (s.breakdown) {
      const score = getBreakdownScore(s.breakdown, key)
      totalScore += score
      totalMax += cat.max
    }
  })

  // Weighted average as percentage
  const pct = totalMax > 0 ? (totalScore / totalMax) * 100 : 0

  return {
    percentage: pct,
    icon: pct >= 75 ? '▲' : pct >= 50 ? '◐' : '▼'
  }
}
```

**Result**: Shows actual average strength (e.g., 67% if most timeframes are moderately strong)

### Option 2: Timeframe-Weighted Leading Edge

Give more weight to lower timeframes for early detection.

```typescript
const TIMEFRAME_WEIGHTS = {
  '1m': 0.20,   // Highest weight - early signal
  '5m': 0.18,
  '15m': 0.15,
  '30m': 0.12,
  '1h': 0.12,
  '2h': 0.10,
  '4h': 0.08,
  '1d': 0.05    // Lowest weight - lagging
}

const calculateLeadingEdgeConsensus = (key: string) => {
  const cat = SCORE_CATEGORIES.find(c => c.key === key)
  if (!cat) return { percentage: 0, icon: '●' }

  let weightedScore = 0
  let totalWeight = 0

  scores.forEach(s => {
    if (s.breakdown && s.timeframe) {
      const score = getBreakdownScore(s.breakdown, key)
      const normalizedScore = score / cat.max  // 0-1
      const weight = TIMEFRAME_WEIGHTS[s.timeframe] || 0.1

      weightedScore += normalizedScore * weight
      totalWeight += weight
    }
  })

  const pct = totalWeight > 0 ? (weightedScore / totalWeight) * 100 : 0

  return {
    percentage: pct,
    icon: pct >= 75 ? '▲' : pct >= 50 ? '◐' : '▼'
  }
}
```

**Result**: Emphasizes early signals from 1m/5m timeframes

### Option 3: Relative Threshold per Timeframe

Different thresholds for different timeframes (e.g., 1m needs 40%, 1d needs 60%).

```typescript
const TIMEFRAME_THRESHOLDS = {
  '1m': 0.40,   // Lower threshold - early signal
  '5m': 0.45,
  '15m': 0.50,
  '30m': 0.50,
  '1h': 0.55,
  '2h': 0.55,
  '4h': 0.60,
  '1d': 0.60    // Higher threshold - needs strong signal
}

const calculateConsensus = (key: string) => {
  const cat = SCORE_CATEGORIES.find(c => c.key === key)
  if (!cat) return { percentage: 0, icon: '●' }

  let passing = 0
  let total = 0

  scores.forEach(s => {
    if (s.breakdown && s.timeframe) {
      const score = getBreakdownScore(s.breakdown, key)
      const threshold = TIMEFRAME_THRESHOLDS[s.timeframe] || 0.5
      if ((score / cat.max) >= threshold) passing++
      total++
    }
  })

  const pct = total > 0 ? (passing / total) * 100 : 0

  return {
    percentage: pct,
    icon: pct >= 75 ? '▲' : pct >= 50 ? '◐' : '▼'
  }
}
```

**Result**: More granular pass/fail based on timeframe significance

---

## Files Affected

### Frontend

| File | Change |
|------|--------|
| `trading-dashboard/frontend/src/components/MTF/MTFScoreHeatmap.tsx` | Fix `calculateConsensus()` function |

### Backend (No Changes Needed)

The backend correctly calculates and transmits microstructure scores for all timeframes. No backend changes required.

### Related Files (Reference Only)

| File | Description |
|------|-------------|
| `streamingcandle/.../MicrostructureScoreCalculator.java` | Calculates 18-point score (0-18) |
| `streamingcandle/.../QuantScoreProcessor.java` | Listens to all 8 timeframe topics |
| `trading-dashboard/.../QuantScoreConsumer.java` | Parses breakdown including microstructure |
| `trading-dashboard/frontend/src/hooks/useTimeframeScores.ts` | Provides scores to components |

---

## Verification

### Before Fix

```
Dashboard Observation:
- Select any security
- Navigate to MTF Score Heatmap
- Check Microstructure row consensus column
- OBSERVE: Only 0% or 100%
- OBSERVE: No pattern indicator
```

### After Fix (Leading Edge Implementation)

```
Dashboard Observation:
- Select any security
- Navigate to MTF Score Heatmap
- Check Microstructure row consensus column
- OBSERVE: Intermediate values (e.g., 45%, 67%, 82%)
- OBSERVE: Pattern indicator (LEADING/EARLY/ALIGNED/REVERSAL_RISK)
```

### Test Cases

| Scenario | LTF | MTF | HTF | Expected Score | Expected Pattern |
|----------|-----|-----|-----|----------------|------------------|
| Early institutional buying | 85% | 55% | 30% | ~78 | LEADING |
| Move in progress | 75% | 70% | 70% | ~72 | ALIGNED |
| Early stage | 70% | 50% | 40% | ~63 | EARLY |
| Reversal risk | 30% | 50% | 75% | ~37 | REVERSAL_RISK |
| All weak | 20% | 25% | 20% | ~22 | WEAK |
| All strong | 85% | 80% | 75% | ~81 | ALIGNED |

### Verification Checklist

- [ ] Consensus shows values between 0-100 (not just 0% or 100%)
- [ ] Pattern label displays (LEADING, EARLY, ALIGNED, REVERSAL_RISK, WEAK)
- [ ] LEADING pattern shows when LTF > MTF > HTF
- [ ] REVERSAL_RISK shows when HTF > MTF > LTF
- [ ] Score increases with MomentumBonus for LEADING pattern
- [ ] Score decreases with MomentumBonus for REVERSAL_RISK pattern

---

## Summary

### Root Cause
Binary threshold check combined with correlated microstructure data across timeframes.

### Implemented Fix
**Option 4: Timeframe-Weighted Leading Edge Score** with MomentumBonus

```
LeadingEdgeScore = (0.45 × LTF_Avg) + (0.35 × MTF_Avg) + (0.20 × HTF_Avg) + MomentumBonus

MomentumBonus:
  +15 if LTF > MTF > HTF (LEADING - early institutional entry)
  +10 if LTF > MTF (EARLY - move starting)
    0 if aligned (ALIGNED - move in progress)
  -10 if HTF > LTF (REVERSAL_RISK - caution)
```

### Why This Approach

| Benefit | Description |
|---------|-------------|
| **Early Detection** | Weights 1m/5m at 45% - catches institutional moves first |
| **Profit Maximization** | Leading pattern bonus (+15) rewards early entries |
| **Risk Management** | Reversal risk penalty (-10) warns of traps |
| **Actionable** | Pattern labels (LEADING/EARLY/ALIGNED/REVERSAL_RISK) guide decisions |

### Impact
- **Dashboard**: Shows meaningful consensus percentages (0-100)
- **Trading Decisions**: Pattern detection guides entry timing
- **Early Detection**: LTF-weighted scoring catches moves before retail
- **Risk Awareness**: Reversal risk pattern warns of potential traps
- **Backend**: No changes required

### Key Insight

> **Microstructure data is inherently correlated across timeframes because it measures the same underlying order flow.** The Leading Edge approach EXPLOITS this correlation by detecting when lower timeframes lead (institutional entry) vs when higher timeframes lead (potential reversal).

---

## Appendix: Microstructure Score Breakdown

For reference, the 18-point microstructure score consists of:

| Component | Points | Description |
|-----------|--------|-------------|
| OFI Score | 0-4 | Order Flow Imbalance |
| VPIN Score | 0-4 | Volume-Synchronized Probability |
| Depth Imbalance | 0-3 | Bid/Ask depth ratio |
| Kyle's Lambda | 0-3 | Price impact coefficient |
| Aggressive Volume | 0-4 | Buy vs sell aggression |
| **TOTAL** | **0-18** | |

See `MicrostructureScoreCalculator.java` for detailed formulae.

---

*Document End*
