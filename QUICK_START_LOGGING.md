# Quick Start: Integrated Pipeline Logging

## What You Get

Instead of this mess:
```
DEBUG 📊 EQUITY MUTHOOTFIN OHLC=3876.9/3877.4/3874.8/3877.4 vol=2694 hasOB=false hasOI=false
DEBUG 📊 FUTURE AUROPHARMA OHLC=1219.2/1219.2/1218.9/1218.9 vol=3850 hasOB=false hasOI=false
```

You get this clarity:
```
INFO ┌─[INPUT-TICK] 09:15:01 | RELIANCE | price=2856.50 vol=1250
INFO ├─[CANDLE]     09:15:00 | RELIANCE | OHLC=2855.00/2858.00/2854.50/2856.50 vol=45600 OB=✓ OI=✓
INFO ├─[FAMILY]     09:15:00 | RELIANCE | EQ=✓ FUT=✓ OPT=4 | OI=LONG_BUILDUP bias=BULLISH
INFO ├─[REGIME]     09:16:00 | RELIANCE | STRONG_BULLISH score=0.82
INFO ├─[SIGNAL-CSS] 09:16:00 | RELIANCE | score=0.75 cps=0.68
INFO └─[OUTPUT]     09:16:00 | RELIANCE | → css-output
```

**You can now trace the complete flow: Input → Candle → Family → Regime → Signal → Output**

---

## 5-Minute Setup

### 1. Files Already Created ✓

```
✓ PipelineTraceLogger.java          - Core logger (already in src/main/java/com/kotsin/consumer/logging/)
✓ INTEGRATED_LOGGING_GUIDE.md       - Detailed integration guide
✓ LOGGING_COMPARISON.md             - Before/after examples
✓ QUICK_START_LOGGING.md            - This file
```

### 2. Add Logger to Each Processor (Copy-Paste)

#### A. UnifiedInstrumentCandleProcessor.java

**Step 1:** Add autowired field (after line 57):
```java
@Autowired(required = false)
private com.kotsin.consumer.logging.PipelineTraceLogger traceLogger;
```

**Step 2:** Find line ~187 (tick input peek) and replace:
```java
// OLD:
.peek((key, tick) -> {
    if (log.isDebugEnabled()) {
        log.debug("[INPUT-TICK] {} | {} | price={} | vol={} | time={}",
            tick.getScripCode(), tick.getCompanyName(),
            tick.getLastTradedPrice(), tick.getVolume(),
            tick.getTimestamp());
    }
})

// NEW:
.peek((key, tick) -> {
    if (traceLogger != null) {
        traceLogger.logInputReceived("TICK", tick.getScripCode(), tick.getCompanyName(),
            tick.getTimestamp(),
            String.format("price=%.2f vol=%d", tick.getLastTradedPrice(), tick.getVolume()));
    }
})
```

**Step 3:** Find line ~204 (orderbook input peek) and replace:
```java
// OLD:
.peek((key, ob) -> {
    if (log.isDebugEnabled()) {
        log.debug("[INPUT-OB] {} | {} | bid={} ask={} | bidQ={} askQ={} | time={}",
            ob.getToken(), ob.getCompanyName(),
            ob.getBestBid(), ob.getBestAsk(),
            ob.getTotalBidQuantity(), ob.getTotalOfferQuantity(),
            ob.getTimestamp());
    }
})

// NEW:
.peek((key, ob) -> {
    if (traceLogger != null) {
        traceLogger.logInputReceived("OB", String.valueOf(ob.getToken()), ob.getCompanyName(),
            ob.getTimestamp(),
            String.format("bid=%.2f ask=%.2f spread=%.2f",
                ob.getBestBid(), ob.getBestAsk(), ob.getBestAsk() - ob.getBestBid()));
    }
})
```

**Step 4:** Find line ~222 (OI input peek) and replace:
```java
// OLD:
.peek((key, oi) -> {
    if (log.isDebugEnabled()) {
        log.debug("[INPUT-OI] {} | {} | OI={} | time={}",
            oi.getToken(), oi.getCompanyName(),
            oi.getOpenInterest(), oi.getReceivedTimestamp());
    }
})

// NEW:
.peek((key, oi) -> {
    if (traceLogger != null) {
        traceLogger.logInputReceived("OI", String.valueOf(oi.getToken()), oi.getCompanyName(),
            oi.getReceivedTimestamp(),
            String.format("OI=%d", oi.getOpenInterest()));
    }
})
```

**Step 5:** Find line ~312 (candle output peek) and replace:
```java
// OLD:
.peek((windowedKey, candle) -> logCandle(candle))
.map((windowedKey, candle) -> KeyValue.pair(windowedKey.key(), candle))
.peek((key, candle) -> {
    if (log.isDebugEnabled()) {
        log.debug("[CANDLE] {} | {} | OHLC={}/{}/{}/{} vol={} hasOB={} hasOI={} | emitted to {}",
            candle.getScripCode(), candle.getCompanyName(),
            candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose(),
            candle.getVolume(), candle.hasOrderbook(), candle.hasOI(), outputTopic);
    }
})

// NEW:
.map((windowedKey, candle) -> KeyValue.pair(windowedKey.key(), candle))
.peek((key, candle) -> {
    if (traceLogger != null) {
        traceLogger.logCandleAggregated(
            candle.getScripCode(), candle.getCompanyName(),
            candle.getWindowStartMillis(), candle.getWindowEndMillis(),
            candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose(),
            candle.getVolume(), candle.hasOrderbook(), candle.hasOI()
        );
    }
})
```

#### B. FamilyCandleProcessor.java

**Step 1:** Add autowired field (after line 61):
```java
@Autowired(required = false)
private com.kotsin.consumer.logging.PipelineTraceLogger traceLogger;
```

**Step 2:** Find line ~206 (family output peek) and replace:
```java
// OLD:
.peek((key, familyCandle) -> {
    if (log.isDebugEnabled() && familyCandle != null) {
        String familyId = familyCandle.getFamilyId();
        InstrumentCandle equity = familyCandle.getEquity();
        String scripCode = equity != null ? equity.getScripCode() : "N/A";
        log.debug("[FAMILY] {} | {} | equity={} future={} options={} | OHLC={}/{}/{}/{} vol={} | emitted to {}",
            familyId, scripCode,
            equity != null ? "YES" : "NO",
            familyCandle.getFuture() != null ? "YES" : "NO",
            familyCandle.getOptions() != null ? familyCandle.getOptions().size() : 0,
            familyCandle.getOpen(), familyCandle.getHigh(),
            familyCandle.getLow(), familyCandle.getClose(),
            familyCandle.getVolume(), outputTopic);
    }
})

// NEW:
.peek((key, familyCandle) -> {
    if (traceLogger != null && familyCandle != null) {
        InstrumentCandle equity = familyCandle.getEquity();
        traceLogger.logFamilyCandleCreated(
            familyCandle.getFamilyId(),
            familyCandle.getSymbol(),
            familyCandle.getWindowStartMillis(),
            equity != null, familyCandle.getFuture() != null,
            familyCandle.getOptions() != null ? familyCandle.getOptions().size() : 0,
            familyCandle.getClose(), familyCandle.getVolume(),
            familyCandle.getOiSignal() != null ? familyCandle.getOiSignal() : "N/A",
            familyCandle.getDirectionalBias() != null ? familyCandle.getDirectionalBias() : "N/A"
        );
    }
})
```

#### C. SecurityRegimeCalculator.java

**Step 1:** Add autowired field (after line 10):
```java
@Autowired(required = false)
private com.kotsin.consumer.logging.PipelineTraceLogger traceLogger;
```

**Step 2:** At end of `calculate()` method (before line 160 return statement), add:
```java
// Log regime calculation
if (traceLogger != null) {
    SecurityRegime result = // ... (your existing return object)
    traceLogger.logRegimeCalculated(
        scripCode, "SEC",
        System.currentTimeMillis(),
        result.getLabel().name(), result.getFinalRegimeScore(),
        result.getEmaAlignment().name(), result.getAtrState().name(),
        result.isAlignedWithIndex(), result.getIndexFlowMultiplier()
    );
}

return SecurityRegime.builder()...  // existing return
```

#### D. UnifiedSignalProcessor.java

**Step 1:** Add autowired field (after line 78):
```java
@Autowired(required = false)
private com.kotsin.consumer.logging.PipelineTraceLogger traceLogger;
```

**Step 2:** In CSS stream (around line 204), update peek:
```java
// After filter, before .to():
.peek((k, v) -> {
    if (traceLogger != null) {
        traceLogger.logSignalGenerated("CSS", v.getScripCode(),
            System.currentTimeMillis(),
            v.getCssScore(), v.getStatus() != null ? v.getStatus() : "N/A",
            String.format("cps=%.2f", v.getCpsScore()));
    }
})
```

**Step 3:** In SOM stream (around line 220), update peek:
```java
.peek((k, v) -> {
    if (traceLogger != null) {
        traceLogger.logSignalGenerated("SOM", v.getScripCode(),
            System.currentTimeMillis(),
            v.getSomScore(), v.getSentimentState().name(),
            String.format("penalty=%.2f", v.getSomPenalty()));
    }
})
```

**Step 4:** In VTD stream (around line 236), update peek:
```java
.peek((k, v) -> {
    if (traceLogger != null) {
        traceLogger.logSignalGenerated("VTD", v.getScripCode(),
            System.currentTimeMillis(),
            v.getVtdScore(), v.getTrapType().name(),
            String.format("penalty=%.2f iv=%.1f",
                v.getVtdPenalty(),
                v.getIvPercentile() != null ? v.getIvPercentile() : 0));
    }
})
```

### 3. Build and Run

```bash
mvn clean compile
mvn spring-boot:run
```

### 4. Watch the Magic!

```bash
tail -f logs/streamingcandle.log | grep "PipelineTrace"
```

You'll see:
```
INFO [PipelineTrace] ┌─[INPUT-TICK] 09:15:01 | RELIANCE | ...
INFO [PipelineTrace] ├─[CANDLE]     09:15:00 | RELIANCE | ...
INFO [PipelineTrace] ├─[FAMILY]     09:15:00 | RELIANCE | ...
INFO [PipelineTrace] ├─[REGIME-SEC] 09:16:00 | RELIANCE | ...
INFO [PipelineTrace] ├─[SIGNAL-CSS] 09:16:00 | RELIANCE | ...
INFO [PipelineTrace] └─[OUTPUT-CSS] 09:16:00 | RELIANCE | ...
```

---

## Useful Commands

### Follow specific instrument:
```bash
tail -f logs/streamingcandle.log | grep "RELIANCE"
```

### See only signals:
```bash
tail -f logs/streamingcandle.log | grep "SIGNAL-"
```

### Find missing data:
```bash
tail -f logs/streamingcandle.log | grep "OB=✗\|OI=✗"
```

### See complete flow (pretty):
```bash
tail -f logs/streamingcandle.log | grep "PipelineTrace" | sed 's/.*PipelineTrace]//'
```

---

## Configuration (Optional)

Add to `application.properties`:

```properties
# Set logger level
logging.level.com.kotsin.consumer.logging.PipelineTraceLogger=INFO

# Filter noise (set to WARN to see only warnings/errors)
logging.level.com.kotsin.consumer.logging.PipelineTraceLogger=INFO
```

---

## Troubleshooting

**Q: Not seeing any logs?**
- Check `logging.level.com.kotsin.consumer.logging.PipelineTraceLogger=INFO` in application.properties
- Verify PipelineTraceLogger.java is in correct package

**Q: Seeing too many logs?**
- Set `logging.level.com.kotsin.consumer.logging.PipelineTraceLogger=WARN`
- Or filter with grep: `grep -v "INPUT-TICK"`

**Q: Want to disable temporarily?**
- Don't remove code, just set level to ERROR: `logging.level.com.kotsin.consumer.logging.PipelineTraceLogger=ERROR`

---

## Summary

**Time to implement:** 10-15 minutes
**Benefit:** Complete visibility into data flow
**Impact:** Debugging time reduced from hours to minutes

Now you can **see the forest AND the trees**! 🌲🌳🌲
