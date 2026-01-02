# Integrated Pipeline Logging Guide

## Problem
Current logs show isolated events without correlation:
```
DEBUG [UnifiedInstrumentCandleProcessor] 📊 EQUITY MUTHOOTFIN OHLC=3876.9/3877.4/3874.8/3877.4 vol=2694
DEBUG [FamilyCandleProcessor] [FAMILY] MUTHOOTFIN | equity=YES future=NO options=0
```

**You can't trace**: Did this candle come from ticks? Was OB/OI available? What signals were generated?

## Solution: Pipeline Trace Logging

Shows end-to-end flow for each instrument:

```
INFO  [PipelineTrace] ┌─[INPUT-TICK] 09:15:01 | MUTHOOTFIN | Muthoot Finance Ltd | price=3876.90 vol=500
INFO  [PipelineTrace] ┌─[INPUT-OB]   09:15:01 | MUTHOOTFIN | Muthoot Finance Ltd | bid=3876.80 ask=3877.00 spread=0.20
INFO  [PipelineTrace] ├─[CANDLE]     09:15:00 | MUTHOOTFIN | Muthoot Finance Ltd | OHLC=3876.90/3877.40/3874.80/3877.40 vol=2694 OB=✓ OI=✗
INFO  [PipelineTrace] ├─[FAMILY]     09:15:00 | MUTHOOTFIN | MUTHOOTFIN | EQ=✓ FUT=✗ OPT=0 | close=3877.40 vol=2694 | OI=NEUTRAL bias=NEUTRAL
INFO  [PipelineTrace] ├─[REGIME-SEC] 09:15:00 | MUTHOOTFIN | label=STRONG_BULLISH score=0.85 | EMA=BULLISH_ALIGNED ATR=EXPANDING | IndexAlign=✓ mult=1.10
INFO  [PipelineTrace] ├─[SIGNAL-CSS] 09:15:00 | MUTHOOTFIN | score=0.72 state=ACTIVE | VCP=YES CPS=HIGH
INFO  [PipelineTrace] └─[OUTPUT-CSS] 09:15:00 | MUTHOOTFIN | → css-output | score=0.72 cps=0.68
```

## How It Works

### 1. PipelineTraceLogger Component
Already created at: `src/main/java/com/kotsin/consumer/logging/PipelineTraceLogger.java`

### 2. Integration Points

#### A. UnifiedInstrumentCandleProcessor

**Add autowired logger:**
```java
@Autowired(required = false)
private com.kotsin.consumer.logging.PipelineTraceLogger traceLogger;
```

**Log tick inputs:**
```java
// In buildTopology(), replace peek() for ticks:
.peek((key, tick) -> {
    if (traceLogger != null) {
        traceLogger.logInputReceived("TICK", tick.getScripCode(), tick.getCompanyName(),
            tick.getTimestamp(),
            String.format("price=%.2f vol=%d", tick.getLastTradedPrice(), tick.getVolume()));
    }
})
```

**Log orderbook inputs:**
```java
// Replace peek() for orderbooks:
.peek((key, ob) -> {
    if (traceLogger != null) {
        traceLogger.logInputReceived("OB", String.valueOf(ob.getToken()), ob.getCompanyName(),
            ob.getTimestamp(),
            String.format("bid=%.2f ask=%.2f spread=%.2f",
                ob.getBestBid(), ob.getBestAsk(), ob.getBestAsk() - ob.getBestBid()));
    }
})
```

**Log OI inputs:**
```java
// Replace peek() for OI:
.peek((key, oi) -> {
    if (traceLogger != null) {
        traceLogger.logInputReceived("OI", String.valueOf(oi.getToken()), oi.getCompanyName(),
            oi.getReceivedTimestamp(),
            String.format("OI=%d", oi.getOpenInterest()));
    }
})
```

**Log candle output:**
```java
// Before .to(outputTopic, ...), add:
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

#### B. FamilyCandleProcessor

**Add autowired logger:**
```java
@Autowired(required = false)
private com.kotsin.consumer.logging.PipelineTraceLogger traceLogger;
```

**Log family candle creation:**
```java
// In buildTopology(), before .to(outputTopic, ...):
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

#### C. SecurityRegimeCalculator

**Add autowired logger:**
```java
@Autowired(required = false)
private com.kotsin.consumer.logging.PipelineTraceLogger traceLogger;
```

**Log regime calculation:**
```java
// At end of calculate() method, before return:
if (traceLogger != null) {
    traceLogger.logRegimeCalculated(
        scripCode, "SEC",
        System.currentTimeMillis(),
        result.getLabel().name(), result.getFinalRegimeScore(),
        result.getEmaAlignment().name(), result.getAtrState().name(),
        result.isAlignedWithIndex(), result.getIndexFlowMultiplier()
    );
}
```

#### D. UnifiedSignalProcessor

**Add autowired logger:**
```java
@Autowired(required = false)
private com.kotsin.consumer.logging.PipelineTraceLogger traceLogger;
```

**Log CSS output:**
```java
// In CSS stream, modify .peek():
cssStream.filter((k, v) -> v != null)
    .peek((k, v) -> {
        if (traceLogger != null) {
            traceLogger.logSignalGenerated("CSS", v.getScripCode(),
                System.currentTimeMillis(),
                v.getCssScore(), v.getStatus() != null ? v.getStatus() : "N/A",
                String.format("cps=%.2f", v.getCpsScore()));
        }
    })
```

**Log SOM output:**
```java
// In SOM stream:
somStream.filter((k, v) -> v != null)
    .peek((k, v) -> {
        if (traceLogger != null) {
            traceLogger.logSignalGenerated("SOM", v.getScripCode(),
                System.currentTimeMillis(),
                v.getSomScore(), v.getSentimentState().name(),
                String.format("penalty=%.2f", v.getSomPenalty()));
        }
    })
```

**Log VTD output:**
```java
// In VTD stream:
vtdStream.filter((k, v) -> v != null && v.isTrapActive())
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

## Sample Output

### Complete Flow for RELIANCE:

```
2026-01-02 09:15:01.234 INFO  [PipelineTrace] ┌─[INPUT-TICK] 09:15:01 | RELIANCE | Reliance Industries Ltd | price=2856.50 vol=1250
2026-01-02 09:15:01.245 INFO  [PipelineTrace] ┌─[INPUT-OB]   09:15:01 | RELIANCE | Reliance Industries Ltd | bid=2856.40 ask=2856.60 spread=0.20
2026-01-02 09:15:01.256 INFO  [PipelineTrace] ┌─[INPUT-OI]   09:15:01 | RELIANCE | Reliance Industries Ltd | OI=8450000
2026-01-02 09:16:00.123 INFO  [PipelineTrace] ├─[CANDLE]     09:15:00 | RELIANCE | Reliance Industries Ltd | OHLC=2855.00/2858.00/2854.50/2856.50 vol=45600 OB=✓ OI=✓
2026-01-02 09:16:00.234 INFO  [PipelineTrace] ├─[FAMILY]     09:15:00 | RELIANCE | RELIANCE | EQ=✓ FUT=✓ OPT=4 | close=2856.50 vol=45600 | OI=LONG_BUILDUP bias=BULLISH
2026-01-02 09:16:05.345 INFO  [PipelineTrace] ├─[REGIME-SEC] 09:16:00 | RELIANCE | label=STRONG_BULLISH score=0.82 | EMA=BULLISH_ALIGNED ATR=EXPANDING | IndexAlign=✓ mult=1.10
2026-01-02 09:16:05.456 INFO  [PipelineTrace] ├─[SIGNAL-CSS] 09:16:00 | RELIANCE | score=0.75 state=ACTIVE | cps=0.68
2026-01-02 09:16:05.467 INFO  [PipelineTrace] ├─[SIGNAL-SOM] 09:16:00 | RELIANCE | score=0.65 state=STABLE | penalty=0.00
2026-01-02 09:16:05.478 INFO  [PipelineTrace] └─[OUTPUT-CSS] 09:16:00 | RELIANCE | → css-output | score=0.75 cps=0.68
```

### Missing Data Example (No OB/OI):

```
2026-01-02 09:15:01.234 INFO  [PipelineTrace] ┌─[INPUT-TICK] 09:15:01 | MUTHOOTFIN | Muthoot Finance Ltd | price=3876.90 vol=500
2026-01-02 09:16:00.123 INFO  [PipelineTrace] ├─[CANDLE]     09:15:00 | MUTHOOTFIN | Muthoot Finance Ltd | OHLC=3876.90/3877.40/3874.80/3877.40 vol=2694 OB=✗ OI=✗
2026-01-02 09:16:00.234 INFO  [PipelineTrace] ├─[FAMILY]     09:15:00 | MUTHOOTFIN | MUTHOOTFIN | EQ=✓ FUT=✗ OPT=0 | close=3877.40 vol=2694 | OI=NEUTRAL bias=NEUTRAL
2026-01-02 09:16:00.345 INFO  [PipelineTrace] ⚠️ [FAMILY] MUTHOOTFIN | No derivatives found - equity-only family
```

## Configuration

Add to `application.properties`:

```properties
# Enable/disable pipeline trace logging
pipeline.trace.enabled=true

# Log level for PipelineTraceLogger
logging.level.com.kotsin.consumer.logging.PipelineTraceLogger=INFO

# Filter specific instruments (comma-separated)
pipeline.trace.filter.instruments=RELIANCE,INFY,TCS

# Disable trace for high-frequency logs (only show warnings/errors)
pipeline.trace.high.frequency.disabled=false
```

## Benefits

1. **Complete Flow Visibility**: See exactly how data flows through the system
2. **Missing Data Detection**: Immediately see when OB/OI is missing
3. **Timing Analysis**: See time gaps between stages
4. **Signal Correlation**: Trace which inputs led to which signals
5. **Debugging**: Quickly identify where data is lost or corrupted
6. **Performance Monitoring**: Identify slow stages

## Next Steps

1. **Copy the PipelineTraceLogger.java** (already created)
2. **Add autowired logger to each processor** (shown above)
3. **Add logging calls** (shown above for each processor)
4. **Restart application**
5. **Watch integrated logs flow!**

## Advanced: Correlation IDs

For even better tracing, add correlation IDs to models:

```java
// Add to InstrumentCandle, FamilyCandle, etc:
private String correlationId;

// Generate on first input:
String correlationId = UUID.randomUUID().toString();

// Pass through pipeline
```

Then logs become:
```
[CORR:abc123] [INPUT-TICK] ...
[CORR:abc123] [CANDLE] ...
[CORR:abc123] [FAMILY] ...
[CORR:abc123] [SIGNAL] ...
```

This allows `grep 'CORR:abc123' logs.txt` to see complete flow for one data point!
