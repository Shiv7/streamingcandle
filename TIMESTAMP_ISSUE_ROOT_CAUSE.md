# Timestamp Issue - Root Cause and Complete Fix

## Your Question: "If we consume correctly, how are we messing up while producing?"

Excellent question. Here's the end-to-end flow explaining exactly where the problem occurs:

## The Complete Flow

### 1. Consumer Side (INPUT topics) ✅ WORKING

```
forwardtesting-data (Kafka topic)
    ↓
[TickDataTimestampExtractor]  ← Clamps timestamp 1767343160000 → 1767340484000
    ↓
Kafka Streams Processing Context (timestamp = 1767340484000)
```

**Status:** This part works correctly. Timestamp extractor clamps future timestamps.

### 2. Kafka Streams Internal Processing ✅ WORKING

```
Processing Context (timestamp = 1767340484000)
    ↓
Windowing/Aggregation uses clamped timestamp
```

**Status:** Works correctly for windowing logic.

### 3. Producer Side (REPARTITION topics) ❌ FAILING

```
Kafka Streams needs to repartition data
    ↓
Produces record to: replay-20251230-v2-unified-instrument-candle-processor-tick-aggregate-store-repartition
    ↓
Record timestamp = ??? (SHOULD be 1767340484000, but ERROR shows 1767343160000)
    ↓
Kafka Broker validates timestamp
    ↓
REJECTED: InvalidTimestampException "Timestamp 1767343160000 is out of range"
```

**Status:** FAILING - Future timestamp appears in repartition topic.

## Root Cause

### The Issue

Kafka Streams has **two separate mechanisms** for timestamps:

1. **Extraction Timestamp**: Used for processing context (windowing, joins)
   - This is what `TimestampExtractor` controls
   - This gets clamped correctly to 1767340484000

2. **Record Timestamp**: The actual timestamp field in the Kafka record
   - This is what gets written to repartition topics
   - This is STILL 1767343160000 (the future timestamp)

### Why the Disconnect?

The repartition topics were created **BEFORE** we added the LogAppendTime configuration. They have:

```properties
# Existing repartition topics (WRONG CONFIG)
message.timestamp.type=CreateTime  # Uses record's embedded timestamp
max.message.time.difference.ms=86400000  # Default: 1 day, but broker rejects sooner
```

When Kafka Streams produces to repartition topics, it sends the record with timestamp from processing context. But the broker still sees the original record timestamp and rejects it.

## The Fix - Three Parts

### Part 1: Timestamp Extractors (✅ ALREADY DONE)

Modified extractors to clamp timestamps on consumer side:
- `TickDataTimestampExtractor.java:33-79`
- `OrderBookSnapshotTimestampExtractor.java:33-84`
- `OpenInterestTimestampExtractor.java:33-88`

### Part 2: Internal Topic Configuration (✅ ALREADY DONE)

Added to `KafkaConfig.java:142-153`:

```java
// Configure internal repartition topics to use LogAppendTime
props.put(StreamsConfig.TOPIC_PREFIX + "log.message.timestamp.type", "LogAppendTime");
props.put(StreamsConfig.TOPIC_PREFIX + "max.message.time.difference.ms", "3600000");
props.put(StreamsConfig.DEFAULT_PRODUCTION_EXCEPTION_HANDLER_CLASS_CONFIG,
        "org.apache.kafka.streams.errors.LogAndContinueExceptionHandler");
```

**Key:** `LogAppendTime` means the broker assigns timestamps when writing to repartition topics, ignoring the record's embedded timestamp.

### Part 3: Delete and Recreate Topics (⚠️ REQUIRED)

**THIS IS THE CRITICAL STEP YOU NEED TO DO:**

The configuration in Part 2 only applies to **newly created topics**. The existing repartition topics already have `CreateTime` configuration and Kafka doesn't allow changing this setting on existing topics.

**Solution:** Delete the repartition topics and let Kafka Streams recreate them with the new configuration.

## Deployment Steps

### On Your Server (13.203.60.173)

```bash
# Step 1: Upload the cleanup script
scp -i ~/Downloads/newDevinaKey.pem scripts/fix-timestamp-issue.sh ubuntu@13.203.60.173:/home/ubuntu/streamingcandle/

# Step 2: SSH to server
ssh -i ~/Downloads/newDevinaKey.pem ubuntu@13.203.60.173

# Step 3: Run cleanup script
cd /home/ubuntu/streamingcandle
chmod +x fix-timestamp-issue.sh
./fix-timestamp-issue.sh

# Step 4: Upload new JAR (from local machine)
# On local machine:
scp -i ~/Downloads/newDevinaKey.pem target/demo-0.0.1-SNAPSHOT.jar ubuntu@13.203.60.173:/home/ubuntu/streamingcandle/

# Step 5: Start application (on server)
nohup java -jar demo-0.0.1-SNAPSHOT.jar > nohup.out 2>&1 &

# Step 6: Monitor startup
tail -f nohup.out
```

## What Will Happen After Fix

### Immediate Effect

1. **Application starts**
2. **Kafka Streams creates new repartition topics** with `LogAppendTime` config
3. **Broker assigns timestamps** when writing to repartition topics (ignores record timestamp)
4. **No more InvalidTimestampException** on repartition topics

### Expected Logs

#### Good Signs ✅
```
INFO  Creating Kafka Streams with app-id: replay-20251230-v2-unified-instrument-candle-processor
INFO  StreamThread-1 started
INFO  StreamThread-20 started
WARN  TickData timestamp 1767343160000 is 3420000ms ahead of stream time.
      Clamping to prevent InvalidTimestampException.
INFO  ✅ [ACCEPT-CANDLE] 12:24:00 | 59321 | hasOB=✓ hasOI=✗ | volume=103950
```

#### Bad Signs (Should NOT see) ❌
```
ERROR InvalidTimestampException: Timestamp 1767343160000 is out of range
ERROR Consumer group stuck in PREPARING_REBALANCE
```

## Verify Success

After 5 minutes of running:

```bash
# 1. Check consumer group status (should be Stable)
kafka-consumer-groups.sh --bootstrap-server localhost:9094 \
  --describe --group replay-20251230-v2-unified-instrument-candle-processor | grep STATE

# Expected: STATE: Stable

# 2. Verify repartition topic config
REPARTITION_TOPIC=$(kafka-topics.sh --bootstrap-server localhost:9094 --list | grep "repartition" | head -1)
kafka-configs.sh --bootstrap-server localhost:9094 \
  --entity-type topics \
  --entity-name "$REPARTITION_TOPIC" \
  --describe | grep message.timestamp.type

# Expected: message.timestamp.type=LogAppendTime

# 3. Count InvalidTimestampException (should be 0)
grep -c "InvalidTimestampException" nohup.out

# 4. Check for candle output
grep "ACCEPT-CANDLE" nohup.out | tail -10
```

## Still Having Issues?

If you still see `InvalidTimestampException` after these steps:

### Check 1: Broker Configuration

The Kafka **broker** itself might have restrictive timestamp validation. Check:

```bash
# On Kafka broker server
cat /path/to/kafka/config/server.properties | grep -E "message.timestamp|log.message.timestamp"

# Increase if needed:
# max.message.time.difference.ms=3600000  # 1 hour
```

### Check 2: Data Source Investigation

Investigate WHY your live data has timestamps 57 minutes in the future:

```bash
# Compare clocks
date  # On application server
ssh <data-producer-server> date  # On data producer
ssh 13.203.60.173 date  # On Kafka broker

# Check NTP sync
timedatectl status
systemctl status chronyd
```

### Check 3: Nuclear Option

If nothing else works, change the app-id-prefix to force complete recreation:

```properties
# In application.properties
kafka.streams.app-id-prefix=replay-20260102-v3-
```

This creates entirely new consumer groups and topics, bypassing ALL old state.

## Summary

### What We Fixed

1. ✅ **Timestamp extractors** - Clamp future timestamps on consumer side
2. ✅ **Internal topic config** - Use LogAppendTime for repartition topics
3. ✅ **Production exception handler** - Continue processing despite errors
4. ⚠️ **Topic deletion required** - Old topics have wrong config (YOU NEED TO DO THIS)

### What You Need to Do

1. Run `fix-timestamp-issue.sh` on server to clean up
2. Upload new JAR file
3. Start application
4. Monitor logs for success criteria
5. Investigate data source timestamp issue (parallel track)

### Expected Outcome

- ✅ No more InvalidTimestampException crashes
- ✅ Consumer group stable
- ✅ StreamThreads running without crashes
- ✅ Candles being produced
- ⚠️ Timestamp clamping warnings (indicates data source issue that needs fixing separately)

## Root Cause of Future Timestamps

While the fix prevents crashes, you MUST investigate why live data has timestamps 57 minutes in the future. Likely causes:

1. **Data producer server clock is 57 minutes ahead**
2. **Timezone confusion in data pipeline**
3. **Data source bug**

Check all server clocks and synchronize using NTP.
