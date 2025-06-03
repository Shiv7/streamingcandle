# STREAMING CANDLE DELAY ANALYSIS & FIXES

## 🚨 **CRITICAL ISSUE: 57-MINUTE DELAY IDENTIFIED**

**Current Behavior:** 30-minute candles showing ~57 minutes delay (10:45 candle appearing at 11:42)

## **ROOT CAUSES IDENTIFIED:**

### **1. Window Suppression Strategy (PRIMARY CAUSE)** ❌
**Location:** Lines 141 & 576 in `CandlestickProcessor.java`
```java
.suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));
```

**Problem:** 
- Waits for ENTIRE 30-minute window to complete before emitting candle
- Adds processing + network delays on top
- Result: 30+ minutes base delay + processing time

**Fix:**
```java
// BEFORE (causes 30+ min delay)
.suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

// AFTER (reduces to ~1-2 min delay)
.suppress(Suppressed.untilTimeLimit(
    Duration.ofSeconds(30), 
    Suppressed.BufferConfig.maxRecords(1000)
));
```

### **2. Two-Stage Aggregation Pipeline** ⚠️
**Current Flow:**
```
Raw Ticks → 1-min candles → 30-min candles
   ↓            ↓              ↓
  Delay     +  Delay     +   Delay
```

**Each stage adds:**
- Window completion wait time
- Processing time
- Network transmission time

### **3. Grace Period Configuration** ⚠️
**Location:** Lines 126 & 533 in `CandlestickProcessor.java`
```java
TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(windowSize))
```

**Problem:** No grace period = strict window boundaries
**Fix:** Add grace period for late-arriving data

### **4. Commit Interval** ⚠️
**Location:** `application.properties` line 30
```properties
spring.kafka.streams.properties.commit.interval.ms=1000
```

## **IMMEDIATE FIXES TO IMPLEMENT:**

### **Fix 1: Change Suppression Strategy (CRITICAL)**
Replace in both locations (lines 141 & 576):

```java
// OLD - causes major delays
.suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

// NEW - reduces delay to ~30 seconds
.suppress(Suppressed.untilTimeLimit(
    Duration.ofSeconds(30), 
    Suppressed.BufferConfig.maxRecords(1000).shutDownWhenFull()
));
```

### **Fix 2: Add Grace Periods**
Replace window configurations:

```java
// OLD - no grace period
TimeWindows windows = TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(windowSize))
    .advanceBy(Duration.ofMinutes(windowSize));

// NEW - with grace period for late data
TimeWindows windows = TimeWindows.ofSizeAndGrace(
    Duration.ofMinutes(windowSize), 
    Duration.ofSeconds(30)  // 30-second grace period
).advanceBy(Duration.ofMinutes(windowSize));
```

### **Fix 3: Reduce Commit Interval**
In `application.properties`:

```properties
# OLD - 1 second commits
spring.kafka.streams.properties.commit.interval.ms=1000

# NEW - faster commits for lower latency
spring.kafka.streams.properties.commit.interval.ms=100
```

### **Fix 4: Optimize Network Settings**
Add to `application.properties`:

```properties
# Reduce network delays
spring.kafka.streams.properties.linger.ms=5
spring.kafka.streams.properties.batch.size=16384
spring.kafka.streams.properties.buffer.memory=33554432
```

## **EXPECTED RESULTS AFTER FIXES:**

| Component | Current Delay | After Fixes |
|-----------|---------------|-------------|
| Window Wait | ~30 minutes | ~30 seconds |
| Processing | ~2-5 minutes | ~10-30 seconds |
| Network | ~1-2 minutes | ~5-10 seconds |
| **TOTAL** | **~57 minutes** | **~1-2 minutes** |

## **TESTING RECOMMENDATIONS:**

### **Monitor These Metrics:**
1. **Consumer Lag:**
   ```bash
   kafka-consumer-groups.sh --bootstrap-server 172.31.12.118:9092 \
     --group candle-processor-ticktooneminprocessor-30minute --describe
   ```

2. **Message Timestamps:**
   - Check timestamp difference between production and consumption
   - Monitor `1-min-candle` vs `30-min-candle` timing

3. **State Store Health:**
   ```bash
   # Check state store sizes
   du -sh /var/lib/kafka-streams/streamingcandle/*/rocksdb/*
   ```

### **Progressive Deployment:**
1. Deploy fix for **15-minute candles first**
2. Monitor improvement
3. Apply to **30-minute candles**
4. Monitor production impact

## **CODE CHANGES NEEDED:**

### **File 1: CandlestickProcessor.java**
- Line 126: Add grace period to TimeWindows
- Line 141: Change suppression strategy  
- Line 533: Add grace period to TimeWindows
- Line 576: Change suppression strategy

### **File 2: application.properties**
- Reduce commit interval
- Add network optimization properties

## **MONITORING AFTER DEPLOYMENT:**

Watch for:
- ✅ Candle delays reduced to 1-2 minutes
- ✅ Consumer lag stays low
- ⚠️ No increase in duplicate candles
- ⚠️ Memory usage remains stable

## **ROLLBACK PLAN:**

If issues occur:
1. Revert suppression to `untilWindowCloses`
2. Increase commit interval back to 1000ms
3. Remove grace periods
4. Monitor for stability

---

**Priority:** 🔴 **CRITICAL** - Deploy immediately to reduce 57-minute delays
**Impact:** Will reduce candle delays from ~57 minutes to ~1-2 minutes 