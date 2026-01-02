# Complete Pipeline Logging - ALL Stages

## Full Pipeline Flow

```
INPUT (Tick/OB/OI)
    ↓
CANDLE (1m instrument candles)
    ↓
FAMILY (Family candles with equity+future+options)
    ↓
REGIME (Index + Security + ACL regimes)
    ↓
SIGNALS (CSS + SOM + VTD)
    ↓
TRADE CLASSIFICATION
    ↓
MTIS (Multi-Timeframe Intelligence Score)
    ↓
FINAL MAGNITUDE (Ranked final score)
    ↓
OUTPUT (Watchlist, Kafka topics)
```

## What I Already Created

✓ **PipelineTraceLogger.java** - Core logger
✓ **Input logging** (Tick, OB, OI)
✓ **Candle logging** (InstrumentCandle)
✓ **Family logging** (FamilyCandle)
✓ **Security Regime logging** (SecurityRegime)
✓ **Signal logging** (CSS, SOM, VTD)

## What's MISSING (Need to Add)

❌ **Index Regime logging** (IndexRegimeCalculator)
❌ **ACL Regime logging** (AntiCycleLimiter)
❌ **Regime Processor logging** (RegimeProcessor - orchestrator)
❌ **Trade Classification logging** (TradeClassifier)
❌ **MTIS logging** (MTISCalculator)
❌ **Final Magnitude logging** (FinalMagnitudeAssembly)

---

## Extended PipelineTraceLogger Methods

Add these methods to `PipelineTraceLogger.java`:

```java
/**
 * Log index regime calculation
 */
public void logIndexRegimeCalculated(String indexCode, String indexName,
                                    long timestamp,
                                    String label, double strength,
                                    int flowAgreement, String flowState) {
    if (!TRACE_ENABLED) return;
    String time = formatTime(timestamp);
    log.info("├─[REGIME-IDX] {} | {} ({}) | label={} strength={:.2f} | flow={} state={}",
        time, indexCode, indexName, label, strength, flowAgreement, flowState);
}

/**
 * Log ACL output
 */
public void logACLCalculated(String scripCode, long timestamp,
                            String action, String reason, double multiplier) {
    if (!TRACE_ENABLED) return;
    String time = formatTime(timestamp);
    log.info("├─[REGIME-ACL] {} | {} | action={} mult={:.2f} | {}",
        time, scripCode, action, multiplier, reason);
}

/**
 * Log trade classification
 */
public void logTradeClassified(String scripCode, long timestamp,
                               String tradeType, String direction,
                               double confidence, String details) {
    if (!TRACE_ENABLED) return;
    String time = formatTime(timestamp);
    log.info("├─[TRADE-CLASS] {} | {} | type={} dir={} conf={:.2f} | {}",
        time, scripCode, tradeType, direction, confidence, details);
}

/**
 * Log MTIS calculation
 */
public void logMTISCalculated(String scripCode, long timestamp,
                             double mtisScore, double priceScore,
                             double foScore, double ipuScore,
                             double regimeScore, String timeframe) {
    if (!TRACE_ENABLED) return;
    String time = formatTime(timestamp);
    log.info("├─[MTIS] {} | {} | score={:.2f} TF={} | price={:.1f} fo={:.1f} ipu={:.1f} regime={:.1f}",
        time, scripCode, mtisScore, timeframe, priceScore, foScore, ipuScore, regimeScore);
}

/**
 * Log final magnitude calculation (THE MOST IMPORTANT!)
 */
public void logFinalMagnitude(String scripCode, long timestamp,
                             double finalMagnitude, String direction,
                             double baseSignal, double aclMult, double cssScore,
                             double somPenalty, double vtdPenalty,
                             int rank) {
    if (!TRACE_ENABLED) return;
    String time = formatTime(timestamp);
    log.info("├─[FINAL-MAG] {} | {} | MAGNITUDE={:.3f} rank={} dir={} | base={:.2f} acl={:.2f} css={:.2f} som={:.2f} vtd={:.2f}",
        time, scripCode, finalMagnitude, rank, direction,
        baseSignal, aclMult, cssScore, somPenalty, vtdPenalty);
}

/**
 * Log final output emission (watchlist, kafka)
 */
public void logFinalOutput(String scripCode, long timestamp,
                          String outputType, String destination,
                          String summary) {
    if (!TRACE_ENABLED) return;
    String time = formatTime(timestamp);
    log.info("└─[FINAL-OUT-{}] {} | {} | → {} | {}",
        outputType, time, scripCode, destination, summary);
}
```

---

## Integration Code for Missing Processors

### 1. IndexRegimeCalculator.java

**Add after line 10:**
```java
@Autowired(required = false)
private com.kotsin.consumer.logging.PipelineTraceLogger traceLogger;
```

**In `calculate()` method, before return (around line 120):**
```java
// Log index regime calculation
if (traceLogger != null) {
    traceLogger.logIndexRegimeCalculated(
        indexCode, indexName,
        System.currentTimeMillis(),
        result.getLabel().name(),
        result.getRegimeStrength(),
        result.getFlowAgreement(),
        result.getFlowState() != null ? result.getFlowState().name() : "N/A"
    );
}

return IndexRegime.builder()...  // existing return
```

### 2. AntiCycleLimiter.java (ACL)

**Add autowired logger:**
```java
@Autowired(required = false)
private com.kotsin.consumer.logging.PipelineTraceLogger traceLogger;
```

**In `calculate()` or `process()` method, when ACL output is created:**
```java
// Log ACL decision
if (traceLogger != null && aclOutput != null) {
    traceLogger.logACLCalculated(
        scripCode,
        System.currentTimeMillis(),
        aclOutput.getAction(),
        aclOutput.getReason(),
        aclOutput.getMultiplier()
    );
}
```

### 3. RegimeProcessor.java (Orchestrator)

**Add autowired logger (after line 60):**
```java
@Autowired(required = false)
private com.kotsin.consumer.logging.PipelineTraceLogger traceLogger;
```

**For index regime stream (around line 170):**
```java
// After calculating index regime, before .to():
.peek((key, indexRegime) -> {
    if (traceLogger != null && indexRegime != null) {
        traceLogger.logOutputEmitted("REGIME-IDX", indexRegime.getScripCode(),
            indexRegime.getTimestamp(), indexOutputTopic,
            String.format("label=%s strength=%.2f",
                indexRegime.getLabel().name(),
                indexRegime.getRegimeStrength()));
    }
})
.to(indexOutputTopic, ...)
```

**For security regime stream:**
```java
// Before .to(securityOutputTopic):
.peek((key, securityRegime) -> {
    if (traceLogger != null && securityRegime != null) {
        traceLogger.logOutputEmitted("REGIME-SEC", securityRegime.getScripCode(),
            securityRegime.getTimestamp(), securityOutputTopic,
            String.format("label=%s score=%.2f aligned=%s",
                securityRegime.getLabel().name(),
                securityRegime.getFinalRegimeScore(),
                securityRegime.isAlignedWithIndex() ? "✓" : "✗"));
    }
})
```

**For ACL stream:**
```java
// Before .to(aclOutputTopic):
.peek((key, aclOutput) -> {
    if (traceLogger != null && aclOutput != null) {
        traceLogger.logOutputEmitted("REGIME-ACL", aclOutput.getScripCode(),
            aclOutput.getTimestamp(), aclOutputTopic,
            String.format("action=%s mult=%.2f",
                aclOutput.getAction(),
                aclOutput.getMultiplier()));
    }
})
```

### 4. TradeClassifier.java

**Add autowired logger:**
```java
@Autowired(required = false)
private com.kotsin.consumer.logging.PipelineTraceLogger traceLogger;
```

**In classify() method, before return:**
```java
// Log trade classification
if (traceLogger != null && result != null) {
    traceLogger.logTradeClassified(
        scripCode,
        System.currentTimeMillis(),
        result.getTradeType(),
        result.getDirection(),
        result.getConfidence(),
        String.format("reason=%s", result.getReason())
    );
}
```

### 5. MTISCalculator.java

**Add autowired logger (after line 14):**
```java
@Autowired(required = false)
private com.kotsin.consumer.logging.PipelineTraceLogger traceLogger;
```

**In `calculate()` method, before return (around line 250):**
```java
// Log MTIS calculation
if (traceLogger != null && score != null) {
    traceLogger.logMTISCalculated(
        familyId,
        score.getTimestamp(),
        score.getMtisScore(),
        breakdown.getPriceScore(),
        breakdown.getFoScore(),
        breakdown.getIpuScore(),
        breakdown.getRegimeScore(),
        family.getTimeframe()
    );
}

return score;
```

### 6. FinalMagnitudeAssembly.java (CRITICAL!)

**Add autowired logger (after line 14):**
```java
@Autowired(required = false)
private com.kotsin.consumer.logging.PipelineTraceLogger traceLogger;
```

**In `calculate()` method, before return (around line 200):**
```java
// Log final magnitude - THIS IS THE MOST IMPORTANT LOG!
if (traceLogger != null && finalMag != null) {
    traceLogger.logFinalMagnitude(
        scripCode,
        finalMag.getTimestamp(),
        finalMag.getFinalMagnitude(),
        finalMag.getDirection(),
        finalMag.getBaseSignal(),
        finalMag.getAclMultiplier(),
        finalMag.getCssScore(),
        finalMag.getSomPenalty(),
        finalMag.getVtdPenalty(),
        0  // rank not known yet
    );
}

return finalMag;
```

**Also log when emitted to watchlist (if in processor):**
```java
// When FinalMagnitude is emitted to kafka:
.peek((key, finalMag) -> {
    if (traceLogger != null && finalMag != null) {
        traceLogger.logFinalOutput(
            finalMag.getScripCode(),
            finalMag.getTimestamp(),
            "WATCHLIST",
            "watchlist-ranked",
            String.format("magnitude=%.3f dir=%s rank=%d",
                finalMag.getFinalMagnitude(),
                finalMag.getDirection(),
                finalMag.getRank())
        );
    }
})
```

---

## Complete Example: RELIANCE End-to-End Flow

```
09:15:01.234 INFO [PipelineTrace] ┌─[INPUT-TICK]  09:15:01 | RELIANCE | Reliance Industries Ltd | price=2856.50 vol=1250
09:15:01.245 INFO [PipelineTrace] ┌─[INPUT-OB]    09:15:01 | RELIANCE | Reliance Industries Ltd | bid=2856.40 ask=2856.60 spread=0.20
09:15:01.256 INFO [PipelineTrace] ┌─[INPUT-OI]    09:15:01 | RELIANCE | Reliance Industries Ltd | OI=8450000
09:16:00.123 INFO [PipelineTrace] ├─[CANDLE]      09:15:00 | RELIANCE | Reliance Industries Ltd | OHLC=2855.00/2858.00/2854.50/2856.50 vol=45600 OB=✓ OI=✓
09:16:00.234 INFO [PipelineTrace] ├─[FAMILY]      09:15:00 | RELIANCE | RELIANCE | EQ=✓ FUT=✓ OPT=4 | close=2856.50 vol=45600 | OI=LONG_BUILDUP bias=BULLISH
09:16:01.345 INFO [PipelineTrace] ├─[REGIME-IDX]  09:16:00 | 999920000 (NIFTY50) | label=STRONG_BULLISH strength=0.88 | flow=1 state=EXPANDING
09:16:01.456 INFO [PipelineTrace] ├─[REGIME-SEC]  09:16:00 | RELIANCE | label=STRONG_BULLISH score=0.82 | EMA=BULLISH_ALIGNED ATR=EXPANDING | IndexAlign=✓ mult=1.10
09:16:01.567 INFO [PipelineTrace] ├─[REGIME-ACL]  09:16:00 | RELIANCE | action=ALLOW mult=1.05 | Aligned with index, trending
09:16:01.678 INFO [PipelineTrace] ├─[SIGNAL-CSS]  09:16:00 | RELIANCE | score=0.75 state=ACTIVE | cps=0.68
09:16:01.789 INFO [PipelineTrace] ├─[SIGNAL-SOM]  09:16:00 | RELIANCE | score=0.65 state=STABLE | penalty=0.00
09:16:01.890 INFO [PipelineTrace] ├─[SIGNAL-VTD]  09:16:00 | RELIANCE | score=0.90 state=NORMAL | penalty=0.00
09:16:02.001 INFO [PipelineTrace] ├─[TRADE-CLASS] 09:16:00 | RELIANCE | type=MOMENTUM dir=LONG conf=0.85 | reason=Strong volume, aligned regime
09:16:02.112 INFO [PipelineTrace] ├─[MTIS]        09:16:00 | RELIANCE | score=68.5 TF=5m | price=10.2 fo=18.5 ipu=12.8 regime=14.2
09:16:02.223 INFO [PipelineTrace] ├─[FINAL-MAG]   09:16:00 | RELIANCE | MAGNITUDE=0.825 rank=2 dir=BULLISH | base=0.72 acl=1.05 css=0.75 som=0.00 vtd=0.00
09:16:02.334 INFO [PipelineTrace] └─[FINAL-OUT-WATCHLIST] 09:16:00 | RELIANCE | → watchlist-ranked | magnitude=0.825 dir=BULLISH rank=2
```

**Now you can see EVERYTHING:**
- ✓ Input data received (tick, OB, OI)
- ✓ Candle aggregated with OB/OI
- ✓ Family created with F&O
- ✓ **Index regime** calculated (NIFTY50 STRONG_BULLISH)
- ✓ **Security regime** calculated (aligned with index, 10% boost)
- ✓ **ACL** decision (ALLOW, 5% boost)
- ✓ Signals generated (CSS, SOM, VTD)
- ✓ **Trade classified** (MOMENTUM LONG, 85% confidence)
- ✓ **MTIS** calculated (68.5 points)
- ✓ **FINAL MAGNITUDE** calculated (0.825, rank #2)
- ✓ **Final output** emitted to watchlist

---

## Summary

### Files to Update:

1. ✅ **PipelineTraceLogger.java** - Add new methods (see above)
2. ❌ **IndexRegimeCalculator.java** - Add index regime logging
3. ❌ **AntiCycleLimiter.java** - Add ACL logging
4. ❌ **RegimeProcessor.java** - Add regime output logging
5. ❌ **TradeClassifier.java** - Add trade classification logging
6. ❌ **MTISCalculator.java** - Add MTIS logging
7. ❌ **FinalMagnitudeAssembly.java** - Add final magnitude logging (CRITICAL!)

### Time Estimate:
- **PipelineTraceLogger updates**: 5 min
- **Each processor integration**: 3-5 min
- **Total**: 30-40 minutes for COMPLETE end-to-end visibility

### Result:
You'll see the **complete data journey** from tick → candle → family → regime → signals → classification → MTIS → final magnitude → watchlist output!

Every step logged with timing, scores, and decisions. No more mystery! 🎯
