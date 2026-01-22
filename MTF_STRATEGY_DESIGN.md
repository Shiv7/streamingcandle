# MTF-Aware PivotRetestStrategy Design

## Problem Statement

Current PivotRetestStrategy generates meaningless signals because:
1. Uses 1m ATR (0.05 points) for stop calculation → 0.025 stop distance
2. Ignores weekly/monthly CPR compression signals
3. No HTF setup validation before LTF entry
4. Targets based on intraday pivots, not HTF levels

## Solution Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         MTF TRADING FLOW                                 │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  STEP 1: HTF SETUP DETECTION (Weekly/Daily CPR)                         │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  Conditions (ALL required):                                      │    │
│  │  ✓ Weekly CPR is NARROW (< 0.3%) → Breakout expected            │    │
│  │  ✓ Price within 1% of weekly pivot level                        │    │
│  │  ✓ Daily CPR confirms (NARROW or NORMAL)                        │    │
│  │  ✓ Monthly pivot provides context (support/resistance)          │    │
│  │                                                                  │    │
│  │  OUTPUT: HTF Setup Context (direction, key levels, confidence)  │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                              ↓                                           │
│  STEP 2: LTF ENTRY TRIGGER (5m candle data)                             │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  Conditions (ALL required):                                      │    │
│  │  ✓ Price at weekly/daily pivot level (within 0.3%)              │    │
│  │  ✓ Rejection candle on 5m                                       │    │
│  │  ✓ OFI confirms direction (z-score > 1.0)                       │    │
│  │  ✓ SuperTrend aligned                                           │    │
│  │  ✓ VPIN < 0.75                                                  │    │
│  │                                                                  │    │
│  │  OUTPUT: Entry Signal with 5m ATR-based stops                   │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                              ↓                                           │
│  STEP 3: STOP/TARGET CALCULATION                                        │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  Stop Loss:                                                      │    │
│  │  • Use 5m ATR (NOT 1m ATR)                                      │    │
│  │  • Stop = keyLevel ± (5m_ATR × 1.5)                             │    │
│  │  • Minimum stop = 0.3% of price                                 │    │
│  │                                                                  │    │
│  │  Targets:                                                        │    │
│  │  • T1 = Next weekly pivot level (R1/S1)                         │    │
│  │  • T2 = Second weekly level (R2/S2) or daily level              │    │
│  │                                                                  │    │
│  │  Validation:                                                     │    │
│  │  • R:R must be >= 1.5                                           │    │
│  │  • Stop must be >= 0.3% from entry                              │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Code Changes Required

### 1. TechnicalContext.java - Add 5m ATR Field

```java
// Add these fields to TechnicalContext.java

// ATR by timeframe (for proper stop sizing)
private Double atr1m;      // 1-minute ATR (for scalping)
private Double atr5m;      // 5-minute ATR (for swing/position) ← PRIMARY FOR STOPS
private Double atr15m;     // 15-minute ATR (for context)

// MTF CPR Context
private Boolean dailyCprNarrow;    // Daily CPR < 0.3%
private Boolean weeklyCprNarrow;   // Weekly CPR < 0.3%
private Double dailyCprWidth;      // Actual daily CPR width %
private Double weeklyCprWidth;     // Actual weekly CPR width %

// Confluence scoring
private Integer mtfConfluenceScore;  // 0-10 based on level alignment
```

### 2. TechnicalIndicatorEnricher.java - Calculate 5m ATR

```java
/**
 * Calculate and store ATR for 5m timeframe specifically
 * This is used for stop sizing in swing trades
 */
public double calculate5mATR(String familyId) {
    // Get 5m price history
    List<double[]> history5m = getPriceHistory(familyId, "5m", ATR_PERIOD + 5);

    if (history5m.size() < ATR_PERIOD) {
        // Fallback: use current timeframe ATR × multiplier
        return 0;
    }

    return calculateATR(history5m, ATR_PERIOD);
}

// In enrich() method, add:
double atr5m = calculate5mATR(familyId);
// Store in TechnicalContext
.atr5m(atr5m > 0 ? atr5m : atr * 5)  // Fallback: 1m ATR × 5
```

### 3. SetupContext - Add MTF Fields

```java
// Add to TradingStrategy.SetupContext

// MTF Context (captured when setup detected)
private boolean htfSetupValid;           // HTF conditions met
private String htfSetupTimeframe;        // "weekly" or "daily"
private double weeklyPivotLevel;         // Weekly pivot for reference
private double monthlyPivotLevel;        // Monthly pivot for context
private boolean weeklyCprNarrow;         // Breakout expected
private boolean dailyCprNarrow;          // Confirmation
private int confluenceScore;             // 0-10

// ATR at setup detection (for comparison)
private double atr5mAtDetection;         // 5m ATR when setup formed
```

### 4. PivotRetestStrategy.java - Complete Rewrite

```java
/**
 * PivotRetestStrategy v2.0 - MTF-Aware Trading
 *
 * EDGE: Trade HTF CPR compression + pivot retest with LTF confirmation
 *
 * HTF SETUP (Weekly/Daily):
 * 1. Weekly CPR NARROW (< 0.3%) → Breakout expected
 * 2. Price within 1% of weekly/daily pivot
 * 3. Monthly level provides context
 *
 * LTF ENTRY (5m):
 * 1. Price at pivot level (within 0.3%)
 * 2. OFI confirms direction
 * 3. Rejection candle formed
 *
 * STOP: 5m ATR × 1.5 beyond level (minimum 0.3%)
 * TARGET: Next weekly pivot level
 */
@Slf4j
@Component
public class PivotRetestStrategy implements TradingStrategy {

    // ============ THRESHOLDS ============

    // HTF Setup thresholds
    private static final double HTF_PROXIMITY_PCT = 1.0;      // Within 1% for HTF setup
    private static final double CPR_NARROW_THRESHOLD = 0.3;   // < 0.3% = narrow CPR

    // LTF Entry thresholds
    private static final double LTF_PROXIMITY_PCT = 0.3;      // Within 0.3% for entry
    private static final double MIN_OFI_ZSCORE = 1.0;
    private static final double MAX_VPIN = 0.75;

    // Stop/Target thresholds
    private static final double STOP_ATR_MULT = 1.5;          // 1.5 × 5m ATR
    private static final double MIN_STOP_PCT = 0.003;         // Minimum 0.3% stop
    private static final double MIN_RR_RATIO = 1.5;           // Minimum R:R

    // ============ HTF SETUP DETECTION ============

    @Override
    public Optional<SetupContext> detectSetupForming(EnrichedQuantScore score) {
        if (score == null) return Optional.empty();

        MultiTimeframeLevels mtf = score.getMtfLevels();
        TechnicalContext tech = score.getTechnicalContext();
        double price = score.getClose();

        // STEP 1: Check HTF CPR compression
        if (!isHtfSetupValid(mtf, price)) {
            return Optional.empty();
        }

        // STEP 2: Find the key level (weekly pivot near price)
        PivotLevel keyLevel = findKeyLevelFromMTF(mtf, price);
        if (keyLevel == null) {
            return Optional.empty();
        }

        // STEP 3: Determine direction
        Direction direction = keyLevel.isSupport ? Direction.LONG : Direction.SHORT;

        // STEP 4: Get 5m ATR for stop calculation
        double atr5m = tech.getAtr5m() != null ? tech.getAtr5m() : tech.getAtr() * 5;

        // STEP 5: Calculate proposed stop/targets using HTF levels
        double stopLevel = calculateStop(keyLevel.level, atr5m, price, direction);
        Double target1 = findHtfTarget(mtf, price, direction, 1);
        Double target2 = findHtfTarget(mtf, price, direction, 2);

        // STEP 6: Calculate confluence score
        int confluence = calculateConfluenceScore(mtf, tech, price, direction);

        log.info("[PIVOT_MTF] HTF Setup: {} {} @ {} | weekly={} | cpr={} | conf={}",
                score.getScripCode(), direction, keyLevel.level,
                mtf.getWeeklyPivot() != null ? mtf.getWeeklyPivot().getCprType() : "N/A",
                mtf.getDailyPivot() != null ? mtf.getDailyPivot().getCprWidth() : 0,
                confluence);

        return Optional.of(SetupContext.builder()
                .strategyId(STRATEGY_ID)
                .familyId(score.getScripCode())
                .watchingStartTime(System.currentTimeMillis())
                .setupDescription(String.format("MTF %s at %s (%.2f) | Conf=%d",
                        direction, keyLevel.name, keyLevel.level, confluence))
                .keyLevel(keyLevel.level)
                .entryZone(keyLevel.level)
                .proposedStop(stopLevel)
                .proposedTarget1(target1 != null ? target1 : price * (direction == Direction.LONG ? 1.02 : 0.98))
                .proposedTarget2(target2 != null ? target2 : price * (direction == Direction.LONG ? 1.03 : 0.97))
                .direction(direction)
                .priceAtDetection(price)
                .superTrendAligned((direction == Direction.LONG && tech.isSuperTrendBullish()) ||
                                  (direction == Direction.SHORT && !tech.isSuperTrendBullish()))
                // NEW MTF FIELDS
                .htfSetupValid(true)
                .htfSetupTimeframe("weekly")
                .weeklyPivotLevel(mtf.getWeeklyPivot() != null ? mtf.getWeeklyPivot().getPivot() : 0)
                .weeklyCprNarrow(isWeeklyCprNarrow(mtf))
                .dailyCprNarrow(isDailyCprNarrow(mtf))
                .atr5mAtDetection(atr5m)
                .confluenceScore(confluence)
                .build());
    }

    /**
     * Check if HTF setup is valid (CPR compression + level proximity)
     */
    private boolean isHtfSetupValid(MultiTimeframeLevels mtf, double price) {
        if (mtf == null) {
            log.debug("[PIVOT_MTF] No MTF levels available");
            return false;
        }

        // Check weekly CPR compression
        if (mtf.getWeeklyPivot() == null) {
            log.debug("[PIVOT_MTF] No weekly pivot available");
            return false;
        }

        PivotLevels weekly = mtf.getWeeklyPivot();

        // CRITICAL: Weekly CPR should be NARROW for breakout setup
        boolean weeklyCprNarrow = weekly.getCprType() == CPRWidth.NARROW ||
                                  weekly.getCprWidth() < CPR_NARROW_THRESHOLD;

        if (!weeklyCprNarrow) {
            log.debug("[PIVOT_MTF] Weekly CPR not narrow: {} ({}%)",
                    weekly.getCprType(), weekly.getCprWidth());
            return false;
        }

        // Check if price is near weekly pivot
        double weeklyPivot = weekly.getPivot();
        double distPct = Math.abs(price - weeklyPivot) / price * 100;

        if (distPct > HTF_PROXIMITY_PCT) {
            log.debug("[PIVOT_MTF] Price too far from weekly pivot: {}% > {}%",
                    distPct, HTF_PROXIMITY_PCT);
            return false;
        }

        // Optional: Check daily CPR confirms
        if (mtf.getDailyPivot() != null) {
            boolean dailyConfirms = mtf.getDailyPivot().getCprType() != CPRWidth.WIDE;
            if (!dailyConfirms) {
                log.debug("[PIVOT_MTF] Daily CPR is WIDE - less reliable setup");
                // Don't reject, just note lower confidence
            }
        }

        return true;
    }

    /**
     * Find key level from MTF data (prioritize weekly, then daily)
     */
    private PivotLevel findKeyLevelFromMTF(MultiTimeframeLevels mtf, double price) {
        PivotLevel nearest = null;
        double minDistance = Double.MAX_VALUE;

        // Check weekly levels first (higher weight)
        if (mtf.getWeeklyPivot() != null) {
            PivotLevels weekly = mtf.getWeeklyPivot();

            // Weekly pivot
            double dist = Math.abs(price - weekly.getPivot());
            if (dist < minDistance) {
                minDistance = dist;
                nearest = new PivotLevel("W_PP", weekly.getPivot(), true);
            }

            // Weekly S1
            dist = Math.abs(price - weekly.getS1());
            if (dist < minDistance) {
                minDistance = dist;
                nearest = new PivotLevel("W_S1", weekly.getS1(), true);
            }

            // Weekly R1
            dist = Math.abs(price - weekly.getR1());
            if (dist < minDistance) {
                minDistance = dist;
                nearest = new PivotLevel("W_R1", weekly.getR1(), false);
            }
        }

        // Check monthly levels (even higher weight if close)
        if (mtf.getMonthlyPivot() != null) {
            PivotLevels monthly = mtf.getMonthlyPivot();

            double dist = Math.abs(price - monthly.getPivot());
            // Monthly pivot gets priority if within 0.5%
            if (dist / price < 0.005 && dist < minDistance * 2) {
                nearest = new PivotLevel("M_PP", monthly.getPivot(), true);
            }
        }

        return nearest;
    }

    /**
     * Calculate stop using 5m ATR with minimum threshold
     */
    private double calculateStop(double keyLevel, double atr5m, double price, Direction direction) {
        // Calculate ATR-based stop distance
        double stopDistance = atr5m * STOP_ATR_MULT;

        // Enforce minimum stop distance (0.3% of price)
        double minStop = price * MIN_STOP_PCT;
        if (stopDistance < minStop) {
            log.debug("[PIVOT_MTF] Stop too tight: {} < {} (min). Using minimum.",
                    stopDistance, minStop);
            stopDistance = minStop;
        }

        // Calculate stop level
        double stopLevel = direction == Direction.LONG ?
                keyLevel - stopDistance :
                keyLevel + stopDistance;

        return stopLevel;
    }

    /**
     * Find target from HTF levels (weekly/monthly pivots)
     */
    private Double findHtfTarget(MultiTimeframeLevels mtf, double price, Direction direction, int targetNum) {
        List<Double> candidates = new ArrayList<>();

        // Add weekly levels
        if (mtf.getWeeklyPivot() != null) {
            PivotLevels weekly = mtf.getWeeklyPivot();
            if (direction == Direction.LONG) {
                candidates.add(weekly.getR1());
                candidates.add(weekly.getR2());
                candidates.add(weekly.getR3());
            } else {
                candidates.add(weekly.getS1());
                candidates.add(weekly.getS2());
                candidates.add(weekly.getS3());
            }
        }

        // Add monthly levels
        if (mtf.getMonthlyPivot() != null) {
            PivotLevels monthly = mtf.getMonthlyPivot();
            if (direction == Direction.LONG) {
                candidates.add(monthly.getR1());
            } else {
                candidates.add(monthly.getS1());
            }
        }

        // Filter and sort
        if (direction == Direction.LONG) {
            return candidates.stream()
                    .filter(l -> l != null && l > price)
                    .sorted()
                    .skip(targetNum - 1)
                    .findFirst()
                    .orElse(null);
        } else {
            return candidates.stream()
                    .filter(l -> l != null && l < price)
                    .sorted(Comparator.reverseOrder())
                    .skip(targetNum - 1)
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Calculate confluence score (0-10)
     */
    private int calculateConfluenceScore(MultiTimeframeLevels mtf, TechnicalContext tech,
                                          double price, Direction direction) {
        int score = 0;

        // Weekly CPR narrow (+3)
        if (isWeeklyCprNarrow(mtf)) score += 3;

        // Daily CPR narrow (+2)
        if (isDailyCprNarrow(mtf)) score += 2;

        // Price at multiple TF levels (+2)
        if (isAtMultipleTfLevels(mtf, price)) score += 2;

        // SuperTrend aligned (+1)
        if ((direction == Direction.LONG && tech.isSuperTrendBullish()) ||
            (direction == Direction.SHORT && !tech.isSuperTrendBullish())) {
            score += 1;
        }

        // HTF SuperTrend aligned (+1)
        if (tech.isMtfHtfBullish() != null) {
            if ((direction == Direction.LONG && tech.isMtfHtfBullish()) ||
                (direction == Direction.SHORT && !tech.isMtfHtfBullish())) {
                score += 1;
            }
        }

        // Monthly level nearby (+1)
        if (isNearMonthlyLevel(mtf, price)) score += 1;

        return Math.min(10, score);
    }

    // ============ ENTRY TRIGGER (uses fresh 5m data) ============

    @Override
    public Optional<TradingSignal> checkEntryTrigger(EnrichedQuantScore score, SetupContext setup) {
        // ... (similar to current but uses 5m ATR and validates HTF context)

        TechnicalContext tech = score.getTechnicalContext();
        double price = score.getClose();
        Direction direction = setup.getDirection();

        // Get FRESH 5m ATR (not stale from setup)
        double atr5m = tech.getAtr5m() != null ? tech.getAtr5m() : setup.getAtr5mAtDetection();

        // Recalculate stop with fresh ATR
        double freshStop = calculateStop(setup.getKeyLevel(), atr5m, price, direction);

        // Get HTF targets
        MultiTimeframeLevels mtf = score.getMtfLevels();
        Double target1 = findHtfTarget(mtf, price, direction, 1);
        Double target2 = findHtfTarget(mtf, price, direction, 2);

        // Validate R:R
        double risk = Math.abs(price - freshStop);
        double reward = Math.abs((target1 != null ? target1 : setup.getProposedTarget1()) - price);
        double rr = reward / risk;

        if (rr < MIN_RR_RATIO) {
            log.debug("[PIVOT_MTF] R:R too low: {} < {}", rr, MIN_RR_RATIO);
            return Optional.empty();
        }

        // Build signal with MTF context
        return Optional.of(TradingSignal.builder()
                // ... standard fields ...
                .stopLoss(freshStop)
                .target1(target1 != null ? target1 : setup.getProposedTarget1())
                .target2(target2 != null ? target2 : setup.getProposedTarget2())
                .headline(String.format("MTF_PIVOT %s @ %.2f | W_CPR=%s | Conf=%d | R:R=%.1f",
                        direction, setup.getKeyLevel(),
                        setup.isWeeklyCprNarrow() ? "NARROW" : "NORMAL",
                        setup.getConfluenceScore(), rr))
                .build());
    }

    // ============ HELPER METHODS ============

    private boolean isWeeklyCprNarrow(MultiTimeframeLevels mtf) {
        return mtf != null && mtf.getWeeklyPivot() != null &&
               (mtf.getWeeklyPivot().getCprType() == CPRWidth.NARROW ||
                mtf.getWeeklyPivot().getCprWidth() < CPR_NARROW_THRESHOLD);
    }

    private boolean isDailyCprNarrow(MultiTimeframeLevels mtf) {
        return mtf != null && mtf.getDailyPivot() != null &&
               (mtf.getDailyPivot().getCprType() == CPRWidth.NARROW ||
                mtf.getDailyPivot().getCprWidth() < CPR_NARROW_THRESHOLD);
    }

    private boolean isAtMultipleTfLevels(MultiTimeframeLevels mtf, double price) {
        int count = 0;
        double tolerance = price * 0.005; // 0.5%

        if (mtf.getDailyPivot() != null &&
            Math.abs(price - mtf.getDailyPivot().getPivot()) < tolerance) count++;
        if (mtf.getWeeklyPivot() != null &&
            Math.abs(price - mtf.getWeeklyPivot().getPivot()) < tolerance) count++;
        if (mtf.getMonthlyPivot() != null &&
            Math.abs(price - mtf.getMonthlyPivot().getPivot()) < tolerance) count++;

        return count >= 2;
    }

    private boolean isNearMonthlyLevel(MultiTimeframeLevels mtf, double price) {
        if (mtf == null || mtf.getMonthlyPivot() == null) return false;

        PivotLevels monthly = mtf.getMonthlyPivot();
        double tolerance = price * 0.01; // 1%

        return Math.abs(price - monthly.getPivot()) < tolerance ||
               Math.abs(price - monthly.getS1()) < tolerance ||
               Math.abs(price - monthly.getR1()) < tolerance;
    }

    private record PivotLevel(String name, double level, boolean isSupport) {}
}
```

---

## Example Signal (After Fix)

**Before (broken):**
```json
{
    "entryPrice": 191.65,
    "stopLoss": 191.625,     // 0.025 points - MEANINGLESS!
    "target1": 191.733,
    "riskRewardRatio": 5.7
}
```

**After (MTF-aware):**
```json
{
    "entryPrice": 191.65,
    "stopLoss": 189.80,      // 1.85 points (5m ATR × 1.5)
    "target1": 194.50,       // Weekly R1
    "target2": 197.20,       // Weekly R2
    "riskRewardRatio": 1.54,
    "mtfContext": {
        "htfSetupTimeframe": "weekly",
        "weeklyCprType": "NARROW",
        "weeklyCprWidth": 0.25,
        "confluenceScore": 7,
        "atr5m": 1.23
    }
}
```

---

## Files to Modify

| File | Changes |
|------|---------|
| `TechnicalContext.java` | Add `atr5m`, `dailyCprNarrow`, `weeklyCprNarrow`, `mtfConfluenceScore` |
| `TechnicalIndicatorEnricher.java` | Add `calculate5mATR()`, populate new fields |
| `TradingStrategy.java` | Add MTF fields to `SetupContext` |
| `PivotRetestStrategy.java` | Complete rewrite with MTF logic |
| `EnrichedQuantScoreCalculator.java` | Pass CPR info to TechnicalContext |

---

## Key Principles

1. **HTF for Setup, LTF for Entry**: Identify compression on weekly/daily, enter on 5m confirmation
2. **5m ATR for Stops**: Never use 1m ATR for swing trades
3. **Minimum Stop Enforcement**: Always >= 0.3% of price
4. **HTF Targets**: Use weekly/monthly levels, not intraday
5. **Confluence Scoring**: Higher score = higher confidence = larger position size
