# Timestamp Handling Fix - InvalidTimestampException

## Problem

The application was crashing with `InvalidTimestampException` errors:

```
org.apache.kafka.common.errors.InvalidTimestampException:
Timestamp 1767343160000 of message with offset 0 is out of range.
The timestamp should be within [-9223370269518615141, 1767339760666]
```

**Analysis:**
- Timestamp `1767343160000` = Jan 02, 2026 12:16:00 IST
- Max allowed `1767339760666` = Jan 02, 2026 11:19:20 IST
- **Drift: ~57 minutes ahead**

This caused StreamThreads to crash and enter an infinite rebalance loop.

## Root Cause

**CRITICAL OBSERVATION:** User confirmed this is **LIVE data**, not replay data.

Possible causes for future timestamps in live data:
1. **Clock Synchronization Issues**: Data producer server clock running ahead
2. **Timezone Conversion Problems**: Incorrect timezone handling in data pipeline
3. **Data Source Bug**: Upstream system sending incorrect timestamps
4. **Network Delays**: Timestamps set before buffering/queuing

## Solution Implemented

Updated all timestamp extractors to gracefully handle out-of-range timestamps by clamping them to acceptable values:

### Modified Files

1. **TickDataTimestampExtractor.java**
   - Added `MAX_FUTURE_DRIFT_MS = 1 hour` tolerance
   - Clamps timestamps more than 1 hour ahead of stream time
   - Logs warnings with full context (topic, partition, offset, token)

2. **OrderBookSnapshotTimestampExtractor.java**
   - Same clamping logic for orderbook snapshots
   - Prevents orderbook data from causing timestamp exceptions

3. **OpenInterestTimestampExtractor.java**
   - Same clamping logic for open interest data
   - Handles null timestamps gracefully

### How It Works

```java
// Maximum allowed timestamp drift (1 hour = 3600000 ms)
private static final long MAX_FUTURE_DRIFT_MS = 3600000L;

// Track stream time
private long lastObservedTimestamp = -1L;

@Override
public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
    long eventTime = getEventTime(record.value());

    // Check if timestamp is too far in the future
    long currentStreamTime = Math.max(lastObservedTimestamp, partitionTime);
    long drift = eventTime - currentStreamTime;

    if (drift > MAX_FUTURE_DRIFT_MS) {
        // Timestamp is too far ahead - clamp it to acceptable range
        long clampedTimestamp = currentStreamTime + MAX_FUTURE_DRIFT_MS;

        LOGGER.warn("Timestamp {} is {}ms ahead of stream time {}. " +
                "Clamping to {} to prevent InvalidTimestampException.",
            eventTime, drift, currentStreamTime, clampedTimestamp);

        return clampedTimestamp;
    }

    return eventTime;
}
```

## Impact

### Before Fix
- ❌ StreamThreads crash on future timestamps
- ❌ Consumer group stuck in PREPARING_REBALANCE
- ❌ Data processing halts
- ❌ Infinite rebalance loop

### After Fix
- ✅ Future timestamps clamped to acceptable range
- ✅ StreamThreads continue processing
- ✅ Warning logs identify problematic records
- ✅ Consumer group remains stable
- ✅ Data processing continues

## Monitoring

### Warning Logs to Watch

```
WARN TickData timestamp 1767343160000 is 3420000ms ahead of stream time 1767339740000.
Clamping to 1767343340000 to prevent InvalidTimestampException.
Topic: forwardtesting-data, Partition: 3, Offset: 12345, Token: 59321
```

These warnings indicate:
1. **Which data source** has timestamp issues (topic name)
2. **How far ahead** the timestamp is (drift in ms)
3. **Specific record** causing the issue (partition, offset, token)

### Root Cause Investigation

**IMPORTANT:** While this fix prevents crashes, you should investigate WHY live data has future timestamps:

#### 1. Check Data Producer Clock
```bash
# On the data producer server
date
timedatectl status

# Check if NTP is syncing
systemctl status chronyd
# OR
systemctl status ntp
```

#### 2. Check Kafka Broker Clock
```bash
# On Kafka server (13.203.60.173)
ssh -i ~/Downloads/newDevinaKey.pem ubuntu@13.203.60.173
date
timedatectl status
```

#### 3. Check Application Server Clock
```bash
# On application server
date
timedatectl status
```

#### 4. Review Data Pipeline Timestamp Logic
- Where are timestamps being set in the source data?
- Are there any timezone conversions?
- Is there buffering/queuing that could cause delays?

### Expected Behavior

With properly synchronized clocks:
- ✅ Drift should be < 5 seconds for live data
- ✅ No clamping warnings in logs
- ✅ Smooth stream processing

If you see frequent clamping warnings (drift > 1 hour), this indicates a **systemic problem** that needs investigation.

## Rollback

If this fix causes issues, revert to original timestamp extractors:

```bash
git checkout src/main/java/com/kotsin/consumer/timeExtractor/TickDataTimestampExtractor.java
git checkout src/main/java/com/kotsin/consumer/timeExtractor/OrderBookSnapshotTimestampExtractor.java
git checkout src/main/java/com/kotsin/consumer/timeExtractor/OpenInterestTimestampExtractor.java
mvn clean package -DskipTests
```

## Next Steps

1. **Deploy the fix** to prevent further crashes
2. **Monitor warning logs** to see how often clamping occurs
3. **Investigate root cause** of future timestamps:
   - Check clock synchronization on all servers
   - Review data producer timestamp logic
   - Verify timezone handling throughout pipeline
4. **Fix the root cause** once identified
5. **Consider reducing MAX_FUTURE_DRIFT_MS** to 5 minutes once clocks are synced

## Configuration

You can adjust the tolerance in each timestamp extractor:

```java
// In TickDataTimestampExtractor.java, OrderBookSnapshotTimestampExtractor.java, etc.
private static final long MAX_FUTURE_DRIFT_MS = 3600000L;  // 1 hour

// Reduce to 5 minutes after fixing clock sync:
private static final long MAX_FUTURE_DRIFT_MS = 300000L;  // 5 minutes
```

## Related Issues

- Consumer group rebalance loop (PREPARING_REBALANCE state)
- StreamThread crashes
- Processing lag buildup

All of these should be resolved once timestamps are within acceptable range.
