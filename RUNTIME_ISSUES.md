# Streamingcandle Runtime Issues Report

**Analysis Date**: 2026-01-04 19:30  
**Log File**: `logs/streamingcandle.log` (3.7MB)  
**Runtime Duration**: ~30 minutes  
**Application Status**: RUNNING (with errors)  

---

## 🔴 Critical Runtime Issues

### **RT-1: Missing Kafka Topics (BLOCKER)**

**Severity**: CRITICAL - Prevents data processing  
**Frequency**: Continuous (all 20 stream threads affected)

**Error**:
```
org.apache.kafka.streams.errors.MissingSourceTopicException: One or more source topics were missing during rebalance
```

**Missing Topics**:
1. **`instrument-candle-1m`** - Main input for FamilyCandleProcessor
2. **Repartition topics** (auto-created by Kafka Streams):
   - `comprehensive-logging-scriprepo-20260104-family-candle-processor-family-members-store-1m-repartition`
   - `comprehensive-logging-scriprepo-20260104-unified-instrument-candle-processor-tick-repartitioned-repartition`
   - `comprehensive-logging-scriprepo-20260104-unified-instrument-candle-processor-orderbook-repartitioned-repartition`
   - `comprehensive-logging-scriprepo-20260104-unified-instrument-candle-processor-oi-repartitioned-repartition`

**Impact**:
- ❌ FamilyCandleProcessor cannot process ANY data
- ❌ All 20 stream threads continuously throwing exceptions
- ❌ Threads are being replaced repeatedly (REPLACE_THREAD handler)
- ❌ No family candles being produced
- ❌ Downstream processors (MTIS, MasterArch, etc.) have no data

**Root Cause**:
- UnifiedInstrumentCandleProcessor may not have run long enough to create output topic
- OR the processor is failing before producing any data
- OR topic auto-creation is disabled on Kafka broker

**Fix Required**:
```bash
# Option 1: Create missing topics manually
kafka-topics.sh --create --topic instrument-candle-1m \\
  --partitions 20 --replication-factor 1 \\
  --bootstrap-server 13.203.60.173:9094

# Option 2: Enable auto.create.topics.enable on broker
# Option 3: Fix UnifiedInstrumentCandleProcessor to actually produce data
```

---

### **RT-2: Invalid Timestamps (Data Quality Issue)**

**Severity**: HIGH - Causes data rejection  
**Frequency**: Hundreds of warnings per second

**Error**:
```
WARN c.k.c.t.TickDataTimestampExtractor - Invalid timestamp 0 for token 477895, using record timestamp
WARN c.k.c.logging.PipelineTraceLogger - ❌ [REJECT-TICK] 05:30:00 | 477895 | Outside trading hours | exchange=M | timestamp=0
```

**Affected Tokens** (sample):
- 477895, 488541, 491822, 506506, 506509, 492806, 507278, 486633, 488447
- All are MCX (Commodity) instruments (`exchange=M`)

**Impact**:
- ❌ All MCX commodity ticks are being **REJECTED**
- ❌ No commodity data in instrument candles
- ❌ No commodity family candles
- ❌ Traders cannot get signals for Gold, Crude, Copper, etc.

**Root Cause**:
- TickData objects have `timestamp=0` for MCX instruments
- Data producer is not setting timestamps correctly
- OR timestamp field mapping is incorrect for MCX data

**Fix Required**:
1. **Immediate**: Fix data producer to set valid timestamps
2. **Workaround**: Use `receivedTimestamp` or Kafka record timestamp as fallback
3. **Code fix** in `TickDataTimestampExtractor.java`:
```java
// Current code already has fallback, but it's rejecting based on trading hours
// Need to adjust trading hours check for MCX (different timing than NSE)

// MCX trading hours: 9:00 AM - 11:30 PM (with break)
// NSE trading hours: 9:15 AM - 3:30 PM
```

---

### **RT-3: Task Corruption and Thread Replacement**

**Severity**: MEDIUM-HIGH - Performance degradation  
**Frequency**: Multiple times during startup

**Error**:
```
org.apache.kafka.streams.errors.TaskCorruptedException: Tasks [0_0] are corrupted and hence need to be re-initialized
```

**Impact**:
- Stream threads are being replaced repeatedly
- State stores need re-initialization
- Performance overhead from thread churn
- Potential data loss during rebalancing

**Root Cause**:
- State store corruption (likely due to unclean shutdown previously)
- OR missing topics causing initialization failures
- OR incompatible state store format from previous version

**Fix Required**:
```bash
# Option 1: Clean state stores
rm -rf /tmp/kafka-streams/<application-id>/*

# Option 2: Change application ID to force new consumer group
# Already done: comprehensive-logging-scriprepo-20260104-*

# Option 3: Fix unclean shutdown handling
# Add cleanup.policy=compact,delete for internal topics
```

---

### **RT-4: UNKNOWN_TOPIC_OR_PARTITION Warnings**

**Severity**: MEDIUM - Indicates infrastructure issues  
**Frequency**: Continuous during rebalancing

**Warning**:
```
WARN o.a.kafka.clients.NetworkClient - Error while fetching metadata with correlation id 2 : 
{comprehensive-logging-scriprepo-20260104-family-candle-processor-family-members-store-1m-repartition=UNKNOWN_TOPIC_OR_PARTITION, 
 instrument-candle-1m=UNKNOWN_TOPIC_OR_PARTITION}
```

**Impact**:
- Network chattiness (metadata requests every few seconds)
- Consumer group rebalancing delays
- Thread coordination issues

**Root Cause**:
- Same as RT-1: Topics don't exist yet
- Kafka Streams is waiting for topics to be created

**Fix**: Same as RT-1 - create missing topics

---

## 🟡 Warnings and Minor Issues

### **W-1: Unassigned Subscribed Topics**

**Warning**:
```
WARN o.a.k.c.c.i.ConsumerCoordinator - The following subscribed topics are not assigned to any members: 
[comprehensive-logging-scriprepo-20260104-family-candle-processor-family-members-store-1m-repartition, 
 instrument-candle-1m]
```

**Impact**: Consumer group can't consume from missing topics (expected behavior)

---

### **W-2: Incomplete Source Topic Metadata**

**Error Code**: `INCOMPLETE_SOURCE_TOPIC_METADATA`

**Impact**: Streams waiting for metadata, causing delays

---

## ✅ Successfully Started Components

Despite the errors, several components DID start successfully:

1. ✅ **UnifiedInstrumentCandleProcessor** - Started (but may not be producing)
2. ✅ **FamilyCandleProcessor** - Started (but blocked on missing input topic)
3. ✅ **TimeframeAggregator** - All 9 timeframes started (2m, 3m, 5m, 15m, 30m, 1h, 2h, 4h, 1d)
4. ✅ **IPUProcessor** - Initialized with caches (TTL=600s, maxSize=5000)
5. ✅ **FUDKIIProcessor** - Initialized (minStrength=0.55)
6. ✅ **MasterArchProcessor** - Initialized
7. ✅ **MTISProcessor** - Initialized (output: family-score)
8. ✅ **RegimeProcessor** - Started
9. ✅ **RedisPercentileService** - Initialized (1-year rolling window)
10. ✅ **RedisCandleHistoryService** - Initialized (40 days bootstrap, 500 candles max)
11. ✅ **ScripFinderClient** - Initialized (circuit breaker: threshold=5, timeout=60s)
12. ✅ **OptionHealthModule** - Initialized (API enabled, cache TTL=5min)

**Disabled Components** (as configured):
- ⏸️ FMAProcessor - disabled by config
- ⏸️ UnifiedSignalProcessor - disabled by config

---

## 📊 Application Health Summary

| Component | Status | Issue |
|-----------|--------|-------|
| UnifiedInstrumentCandleProcessor | 🟢 Started | May not be producing data |
| FamilyCandleProcessor | 🔴 Blocked | Missing input topic |
| TimeframeAggregators (9x) | 🟢 Started | No data to aggregate |
| IPU/VCP/Regime Processors | 🟢 Started | No data to process |
| MTIS Processor | 🟢 Started | No data to score |
| MasterArch Processor | 🟢 Started | No data for signals |
| Kafka Stream Threads | 🔴 Error Loop | Missing topics |
| MCX Commodity Data | 🔴 Rejected | Invalid timestamps |
| State Stores | 🟡 Corrupted | Task re-initialization |

---

## 🎯 Action Plan (Prioritized)

### **Immediate (Next 10 minutes)**

1. **Check if UnifiedInstrumentCandleProcessor is actually processing data**:
   ```bash
   # Check for input data
   kafka-console-consumer.sh --bootstrap-server 13.203.60.173:9094 \\
     --topic forwardtesting-data --max-messages 10
   
   # Check if processor is producing
   kafka-console-consumer.sh --bootstrap-server 13.203.60.173:9094 \\
     --topic instrument-candle-1m --max-messages 10 --timeout-ms 5000
   ```

2. **If no data being produced, check why**:
   ```bash
   # Check for errors in UnifiedInstrumentCandleProcessor logs
   grep "UnifiedInstrumentCandleProcessor" logs/streamingcandle.log | grep -E "ERROR|Exception"
   ```

3. **Create missing output topic manually**:
   ```bash
   kafka-topics.sh --create --topic instrument-candle-1m \\
     --partitions 20 --replication-factor 1 \\
     --bootstrap-server 13.203.60.173:9094
   ```

4. **Verify topic creation**:
   ```bash
   kafka-topics.sh --list --bootstrap-server 13.203.60.173:9094 | grep instrument-candle
   ```

### **Short Term (Next hour)**

5. **Fix MCX timestamp issue**:
   - Identify why `timestamp=0` for MCX data
   - Check data producer configuration
   - Add MCX-specific trading hours validation

6. **Clean corrupted state stores**:
   ```bash
   # Stop application first
   rm -rf /tmp/kafka-streams/comprehensive-logging-scriprepo-20260104-*
   # Restart application
   ```

7. **Monitor for successful data flow**:
   ```bash
   # Watch for successful candle production
   tail -f logs/streamingcandle.log | grep -E "CANDLE|🎯|✅"
   ```

### **Medium Term (Next day)**

8. **Add monitoring alerts** for:
   - Missing topics (@startup)
   - Task corruption (>5 per hour)
   - Invalid timestamps (>1% of ticks)
   - Thread replacement (>10 per hour)

9. **Review application.properties**:
   - Check `auto.offset.reset=earliest` is correct
   - Verify `processing.guarantee=at_least_once`
   - Consider enabling `auto.create.topics.enable` on Kafka

10. **Add health check endpoint** returning:
    - Kafka Streams state (RUNNING/REBALANCING/ERROR)
    - Missing topics list
    - Invalid timestamp rate
    - Data flow metrics

---

## 🔍 Diagnostic Commands

```bash
# 1. Check Kafka broker connection
telnet 13.203.60.173 9094

# 2. List all topics
kafka-topics.sh --list --bootstrap-server 13.203.60.173:9094

# 3. Check consumer group lag
kafka-consumer-groups.sh --bootstrap-server 13.203.60.173:9094 \\
  --group comprehensive-logging-scriprepo-20260104-family-candle-processor \\
  --describe

# 4. Count invalid timestamps
grep "Invalid timestamp 0" logs/streamingcandle.log | wc -l

# 5. Count rejected ticks by exchange
grep "REJECT-TICK" logs/streamingcandle.log | grep -o "exchange=[A-Z]" | sort | uniq -c

# 6. List corrupted tasks
grep "TaskCorruptedException" logs/streamingcandle.log | grep -o "Tasks \\[.*\\]" | sort | uniq

# 7. Monitor thread replacement rate
grep "Replacing thread" logs/streamingcandle.log | wc -l
```

---

## 📈 Expected Behavior After Fixes

Once RT-1 (missing topics) is fixed, you should see:

```
✅ [CANDLE] NIFTY | OHLC=24000/24050/23980/24030 | vol=1500000 | hasOB=✓ hasOI=✓
✅ [FAMILY] NIFTY | equity=YES future=YES options=4 | PCR=1.25
🎯 [MTIS-SCORE] NIFTY | MTIS=72.5 (BULLISH) | TF=5m | F&O=80 | IPU=65 | Regime=70
🎯 MASTER ARCH SIGNAL | NIFTY | decision=BUY | finalScore=0.785 | direction=BULLISH | lots=2
```

---

## Summary

**The application is ALIVE but DATA PIPELINE IS BROKEN** due to:
1. **Missing `instrument-candle-1m` topic** (blocking all downstream processing)
2. **Invalid timestamps for MCX data** (rejecting all commodity ticks)
3. **State store corruption** (causing thread churn)

**Fix Priority**: Create `instrument-candle-1m` topic IMMEDIATELY - this will unblock the entire pipeline.

**Good News**: All processors initialized successfully and are ready to process data once the topics exist.
