# Price Action Confirmation + Learning System

## VISION
Build a complete system where:
1. **Pending signals are PROMOTED only when price action confirms direction**
2. **SL hits are CAPTURED and used to LEARN what NOT to do**
3. **ALL available data** (OI, Options, Greeks, VPIN, Events) contributes to decision making

---

## ARCHITECTURE OVERVIEW

```
                                    ┌─────────────────────────────────────┐
                                    │      PRICE ACTION CONFIRMER         │
                                    │  (validates direction alignment)    │
                                    └─────────────────────────────────────┘
                                                    ▲
                                                    │
┌──────────────┐    R:R < 2:1    ┌─────────────────┴─────────────────┐
│   SIGNAL     │ ─────────────► │     PENDING RR TRACKER             │
│  GENERATOR   │                │  (waits for R:R improvement)       │
└──────────────┘                └─────────────────┬─────────────────┘
       │                                          │
       │ R:R >= 2:1                               │ R:R reaches threshold
       │                                          ▼
       │                        ┌─────────────────────────────────────┐
       │                        │   PRICE ACTION CONFIRMATION CHECK   │
       │                        │                                     │
       │                        │  1. Retest validation               │
       │                        │  2. Candle pattern alignment        │
       │                        │  3. OFI/Volume delta alignment      │
       │                        │  4. Options flow confirmation       │
       │                        │  5. Session structure check         │
       │                        │  6. Multi-TF confluence             │
       │                        └─────────────────┬─────────────────┘
       │                                          │
       │                              ┌───────────┴───────────┐
       │                              │                       │
       │                         CONFIRMED                NOT CONFIRMED
       │                              │                       │
       │                              ▼                       ▼
       └──────────────────────► TRADING-SIGNALS-V2      INVALIDATE
                                      │                 (track reason)
                                      ▼
                              ┌─────────────────┐
                              │  PAPER TRADE    │
                              │  EXECUTOR       │
                              └────────┬────────┘
                                       │
                              ┌────────┴────────┐
                              │                 │
                           TARGET HIT       SL HIT
                              │                 │
                              ▼                 ▼
                      ┌─────────────┐   ┌─────────────────────┐
                      │   LEARN:    │   │   LEARN:            │
                      │   SUCCESS   │   │   FAILURE           │
                      │   PATTERN   │   │   CAPTURE CONTEXT   │
                      └─────────────┘   │   IDENTIFY WHY      │
                                        │   UPDATE BLOCKLIST  │
                                        └─────────────────────┘
```

---

## COMPONENT 1: PRICE ACTION CONFIRMER

### Purpose
Validates that price action truly aligns with signal direction BEFORE promoting a pending signal.

### File: `PriceActionConfirmer.java`

### Confirmation Checks (Scored 0-100)

#### 1. **Retest Validation (0-20 points)**
Use existing `RetestDetector.java`:
- LONG: Price pulled back to support and held
- SHORT: Price rallied to resistance and rejected
- Check: Did candle close confirm the retest?
- Data: `nearestSupportPrice`, `nearestResistancePrice` from QuantScore

#### 2. **Candle Pattern Alignment (0-15 points)**
Use existing pattern detection:
- LONG: Look for bullish patterns (hammer, engulfing, morning star)
- SHORT: Look for bearish patterns (shooting star, engulfing, evening star)
- Check: Last 3 candles show reversal/continuation pattern?
- Data: `CandlestickPattern` detection in FamilyCandle

#### 3. **OFI/Volume Delta Alignment (0-20 points)**
Use existing microstructure data:
- LONG: OFI positive, volume delta positive, buying pressure > selling
- SHORT: OFI negative, volume delta negative, selling pressure > buying
- Check: `ofiVelocity`, `ofiAcceleration` from FamilyCandle
- Data: `DetectedEvent.OFI_FLIP`, `BUYING_EXHAUSTION`, `SELLING_EXHAUSTION`

#### 4. **Options Flow Confirmation (0-15 points)**
Use existing options analysis:
- LONG: Call OI building, Put OI unwinding, PCR declining
- SHORT: Put OI building, Call OI unwinding, PCR rising
- Check: OI interpretation confidence > 0.6
- Data: `callOiBuildingUp`, `putOiBuildingUp`, `oiInterpretation` from FamilyCandle

#### 5. **Session Structure Check (0-10 points)**
Use existing session tracking:
- LONG: Not at session high (position < 85%), OR failed breakout bear reversal
- SHORT: Not at session low (position > 15%), OR failed breakout bull reversal
- Check: `sessionPositionInRange`, failed breakout events
- Data: `SessionStructure` from QuantScore

#### 6. **Multi-Timeframe Confluence (0-10 points)**
Use existing MTF analysis:
- LONG: Multiple timeframes show bullish bias
- SHORT: Multiple timeframes show bearish bias
- Check: 2/3 timeframes agree on direction
- Data: `multiTimeframeBreakoutConfirmed`, `directionalConsistency` from FamilyCandle

#### 7. **SuperTrend/BB Alignment (0-10 points)**
Use existing technical indicators:
- LONG: SuperTrend bullish OR price above BB middle
- SHORT: SuperTrend bearish OR price below BB middle
- Data: `superTrendBullish`, `bbPercentB` from TechnicalContext

### Minimum Score for Promotion: 60/100

### Output
```java
public class ConfirmationResult {
    boolean confirmed;
    int score;
    List<String> passedChecks;
    List<String> failedChecks;
    String recommendation;  // "PROMOTE", "WAIT", "INVALIDATE"
}
```

---

## COMPONENT 2: FAILURE LEARNING MODULE

### Purpose
When SL is hit, capture the FULL CONTEXT and learn what went wrong.

### File: `FailureLearningStore.java`

### Data Captured on SL Hit

```java
public class FailureContext {
    // Signal Info
    String signalId;
    String familyId;
    String scripCode;
    String direction;
    String setupType;

    // Entry Context (what was true at entry)
    double entryPrice;
    double stopLoss;
    double target;
    double entryRR;

    // Confirmation Scores at Entry
    int priceActionScore;
    int ofiScore;
    int optionsScore;
    int sessionScore;
    int mtfScore;

    // Market Context at Entry
    String oiInterpretation;      // LONG_BUILDUP, SHORT_COVERING, etc.
    double ofiAtEntry;
    double volumeDeltaAtEntry;
    boolean superTrendAligned;
    double sessionPosition;
    String gexRegime;

    // What Happened (SL Hit Context)
    double exitPrice;
    double maxAdverseExcursion;   // How far against before SL
    double maxFavorableExcursion; // Did it ever go profitable?
    long timeToSL;                // How quickly did it fail?

    // Events Before SL Hit
    List<String> eventsBeforeSL;  // What events occurred between entry and SL?

    // Failure Classification
    FailureType failureType;      // IMMEDIATE_REVERSAL, GRADUAL_DECAY, GAP_THROUGH, VOLATILITY_SPIKE

    // Learning Tags
    List<String> learningTags;    // Auto-generated tags for pattern recognition
}
```

### Failure Classification

```java
public enum FailureType {
    IMMEDIATE_REVERSAL,    // SL hit within 5 candles, never profitable
    GRADUAL_DECAY,         // Slow drift to SL over many candles
    GAP_THROUGH,           // SL hit on gap open
    VOLATILITY_SPIKE,      // Sudden volatility expansion
    NEWS_EVENT,            // SL hit around news time
    OI_DIVERGENCE,         // OI was diverging from price at entry
    SESSION_TRAP,          // Entered at session extreme
    FALSE_BREAKOUT,        // Breakout that reversed
    EXHAUSTION_IGNORED     // Had exhaustion signal but took trade anyway
}
```

### Learning Store Structure

```java
// Per-scrip learning
Map<String, ScripFailureProfile> scripFailures;

public class ScripFailureProfile {
    String scripCode;
    int totalFailures;
    int totalSuccesses;

    // Failure patterns specific to this scrip
    Map<FailureType, Integer> failureTypeCounts;
    Map<String, Integer> failedSetupTypes;  // e.g., "LONG_REVERSAL" -> 5 failures
    Map<String, Integer> failedConditions;  // e.g., "OI_DIVERGENCE" -> 3 failures

    // Time-based patterns
    Map<Integer, Integer> failuresByHour;   // Which hours have most failures?
    Map<String, Integer> failuresBySession; // OPENING, MID, CLOSING

    // Condition-based learning
    double avgOfiAtFailure;
    double avgSessionPositionAtFailure;
    List<String> commonEventsBeforeFailure;
}
```

### Learning Rules Generation

From the failure data, auto-generate rules like:
- "RELIANCE: Avoid LONG when OI shows SHORT_BUILDUP (5 failures, 0 successes)"
- "NIFTY: Avoid LONG at session position > 85% (8 failures, 2 successes)"
- "BANKNIFTY: Avoid SHORT in first 30 minutes (7 failures, 1 success)"

---

## COMPONENT 3: ENHANCED PENDING RR TRACKER

### Modifications to `PendingRRTracker.java`

#### Add Confirmation Check Before Promotion

```java
private ProcessResult processPriceUpdate(PendingSignal pending, FamilyCandle candle) {
    // ... existing R:R calculation ...

    if (newRR >= minRiskReward) {
        // NEW: Don't promote immediately, check price action confirmation
        ConfirmationResult confirmation = priceActionConfirmer.confirm(
            pending.getSignal(),
            candle,
            quantScore
        );

        if (confirmation.isConfirmed()) {
            promoteSignal(pending, potentialEntry, newRR, confirmation);
            return ProcessResult.PROMOTED;
        } else if (confirmation.getRecommendation().equals("INVALIDATE")) {
            invalidateSignal(pending, confirmation.getFailedChecks());
            return ProcessResult.INVALIDATED;
        } else {
            // WAIT - R:R is good but price action not confirmed yet
            pending.setAwaitingConfirmation(true);
            pending.setLastConfirmationScore(confirmation.getScore());
            return ProcessResult.AWAITING_CONFIRMATION;
        }
    }

    return ProcessResult.TRACKING;
}
```

#### Track Awaiting Confirmation State

```java
public static class PendingSignal {
    // ... existing fields ...

    // NEW: Confirmation tracking
    boolean awaitingConfirmation;
    int lastConfirmationScore;
    int confirmationAttempts;
    List<String> failedConfirmationReasons;
}
```

---

## COMPONENT 4: OUTCOME LEARNING INTEGRATION

### Modifications to `SignalOutcomeStore.java`

#### Add Failure Context Capture

```java
public void recordOutcome(TradeOutcomeMessage outcome, FamilyCandle exitCandle) {
    // ... existing outcome recording ...

    if (outcome.stopHit()) {
        // NEW: Capture full failure context for learning
        FailureContext failure = captureFailureContext(outcome, exitCandle);
        failureLearningStore.recordFailure(failure);

        // Update scrip-specific avoidance rules
        updateScripAvoidanceRules(failure);
    }
}
```

#### Integrate Learning into Signal Generation

```java
public boolean shouldAvoidSignal(TradingSignal signal, EnrichedQuantScore quantScore) {
    String scripCode = signal.getScripCode();
    ScripFailureProfile profile = failureLearningStore.getProfile(scripCode);

    if (profile == null) return false;

    // Check learned avoidance rules
    List<String> violations = new ArrayList<>();

    // Rule 1: Setup type has high failure rate for this scrip
    String setupType = signal.getSetupId();
    if (profile.getSetupFailureRate(setupType) > 0.7) {
        violations.add("SETUP_TYPE_HIGH_FAILURE:" + setupType);
    }

    // Rule 2: OI interpretation conflict learned from failures
    String oiType = quantScore.getOiInterpretation();
    if (signal.isLong() && profile.isOiTypeBadForLong(oiType)) {
        violations.add("OI_CONFLICT:" + oiType);
    }

    // Rule 3: Session position learned from failures
    double sessionPos = quantScore.getSessionPositionInRange();
    if (profile.isSessionPositionRisky(signal.getDirection(), sessionPos)) {
        violations.add("SESSION_POSITION_RISKY:" + sessionPos);
    }

    // Rule 4: Time-based patterns
    int hour = LocalTime.now(IST).getHour();
    if (profile.isHourRisky(signal.getDirection(), hour)) {
        violations.add("HOUR_RISKY:" + hour);
    }

    if (!violations.isEmpty()) {
        log.warn("[LEARNING] {} {} blocked by learned rules: {}",
            scripCode, signal.getDirection(), violations);
        return true;
    }

    return false;
}
```

---

## COMPONENT 5: COMPREHENSIVE CONFIRMATION DATA FLOW

### Data Available at Each Stage

#### At Signal Generation:
- `EnrichedQuantScore` with all technical indicators
- `FamilyCandle` with OI, Greeks, Options flow
- `DetectedEvents` list
- Historical success rate for setup type

#### At Pending Tracking (price updates):
- Fresh `FamilyCandle` every minute
- Updated OFI velocity/acceleration
- Latest session structure
- Real-time options flow

#### At Confirmation Check:
All of the above PLUS:
- Entry price vs current price relationship
- Candles since signal generation
- Events that occurred after signal
- Multi-timeframe confirmation status

#### At Failure Learning:
- Full entry context (what we thought would happen)
- Full exit context (what actually happened)
- Events between entry and exit
- Time to failure
- Max favorable/adverse excursion

---

## IMPLEMENTATION ORDER

### Phase 1: Price Action Confirmer (Core)
1. Create `PriceActionConfirmer.java`
2. Implement 7 confirmation checks
3. Add scoring system
4. Integrate with `PendingRRTracker`

### Phase 2: Failure Learning Module
1. Create `FailureContext.java` model
2. Create `FailureLearningStore.java`
3. Integrate with `SignalOutcomeStore` for outcome capture
4. Implement failure classification

### Phase 3: Learning Integration
1. Add `shouldAvoidSignal()` to `SignalGenerator`
2. Implement per-scrip learning rules
3. Add dashboard/logging for learned rules

### Phase 4: Refinement
1. Tune confirmation score thresholds
2. Add more failure types based on observed patterns
3. Implement adaptive threshold based on market regime

---

## CONFIGURATION

```yaml
# Price Action Confirmation
confirmation:
  enabled: true
  min-score: 60
  checks:
    retest:
      enabled: true
      weight: 20
    candle-pattern:
      enabled: true
      weight: 15
    ofi-alignment:
      enabled: true
      weight: 20
    options-flow:
      enabled: true
      weight: 15
    session-structure:
      enabled: true
      weight: 10
    mtf-confluence:
      enabled: true
      weight: 10
    supertrend-bb:
      enabled: true
      weight: 10

# Failure Learning
learning:
  enabled: true
  min-samples-for-rule: 5
  max-failure-rate-to-block: 0.7
  scrip-specific: true
  time-based: true
  session-based: true
```

---

## EXPECTED OUTCOMES

### Before Implementation
- Signals promoted purely on R:R threshold
- No price action validation
- No learning from failures
- Same mistakes repeated across scrips

### After Implementation
- Signals promoted ONLY when R:R + Price Action align
- Every failure is captured with full context
- System learns per-scrip what works and what doesn't
- Automatic avoidance of high-failure patterns
- Continuous improvement through feedback loop

### Success Metrics
- Win rate improvement (target: 40%+ vs current 30%)
- Reduced "immediate reversal" failures
- Per-scrip optimization showing effect
- Clear audit trail of why signals were promoted/blocked
