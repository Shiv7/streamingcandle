# Timestamp Issue - End-to-End Debugging Guide

## The Problem

Even with timestamp extractors that clamp future timestamps, we're still seeing `InvalidTimestampException` when producing to repartition topics. The user is correct - if we're consuming correctly, why are we messing up while producing?

## Root Cause Analysis

### The Flow
1. **Consume** from input topics (`forwardtesting-data`, `Orderbook`, `OpenInterest`)
   - Timestamp extractors run here and clamp future timestamps
   - Extracted timestamp is used for stream processing context

2. **Process** - Group by instrument key
   - Kafka Streams creates a repartition topic: `replay-20251230-v2-unified-instrument-candle-processor-tick-aggregate-store-repartition`

3. **Produce** to repartition topic
   - **THIS IS WHERE IT FAILS** - Timestamp `1767343160000` is rejected as too far in future

### Why Timestamp Extractor Doesn't Fix Repartition

The timestamp extractor only affects:
- Which timestamp value is used for **windowing** and **processing context**
- It does NOT modify the original record's timestamp field

When producing to repartition topics, Kafka Streams uses the processing context timestamp, which SHOULD be the clamped value. But the error shows it's still using the future timestamp.

## Critical Issues to Check

### 1. Repartition Topics Already Exist with Wrong Config

The repartition topics were created BEFORE we added the LogAppendTime configuration. They still have:
- `message.timestamp.type=CreateTime` (uses record timestamp)
- `max.message.time.difference.ms=default (low value)`

**Solution:** Delete repartition topics and let them be recreated with new config.

### 2. State Directory Contains Old Data

The Kafka Streams state directory has cached data with old timestamps.

**Solution:** Clear state directory.

### 3. Production Exception Handler Not Working

Despite configuring `LogAndContinueExceptionHandler`, the logs show:
```
Exception handler choose to FAIL the processing
```

This suggests the handler isn't configured correctly.

## Deployment Steps to Fix

### Step 1: SSH to Server

```bash
ssh -i ~/Downloads/newDevinaKey.pem ubuntu@13.203.60.173
```

### Step 2: Stop the Application

```bash
# Find the process
ps aux | grep streamingcandle | grep java

# Kill it
pkill -f streamingcandle

# Verify it's stopped
ps aux | grep streamingcandle
```

### Step 3: Delete Repartition Topics

These topics were created with the wrong timestamp configuration and must be recreated:

```bash
# List all repartition topics for this application
kafka-topics.sh --bootstrap-server localhost:9094 --list | grep "replay-20251230-v2-unified-instrument-candle-processor.*repartition"

# Delete each repartition topic
for topic in $(kafka-topics.sh --bootstrap-server localhost:9094 --list | grep "replay-20251230-v2-unified-instrument-candle-processor.*repartition"); do
    echo "Deleting repartition topic: $topic"
    kafka-topics.sh --bootstrap-server localhost:9094 --delete --topic "$topic"
done

# Also delete changelog topics (state stores)
for topic in $(kafka-topics.sh --bootstrap-server localhost:9094 --list | grep "replay-20251230-v2-unified-instrument-candle-processor.*changelog"); do
    echo "Deleting changelog topic: $topic"
    kafka-topics.sh --bootstrap-server localhost:9094 --delete --topic "$topic"
done
```

### Step 4: Clear State Directory

```bash
# Clear Kafka Streams state
rm -rf /tmp/kafka-streams/streamingcandle/*

# If using a different state directory, clear that too
# Check application.properties for spring.kafka.streams.state-dir value
```

### Step 5: Check Kafka Broker Timestamp Settings

The broker itself might be rejecting timestamps. Check broker configuration:

```bash
# Check broker server.properties
cat /path/to/kafka/config/server.properties | grep -E "message.timestamp|log.message.timestamp"

# Key settings to look for:
# log.message.timestamp.type=CreateTime (or LogAppendTime)
# max.message.time.difference.ms=900000 (should be at least 15 minutes)
```

**If broker has restrictive settings, temporarily increase them:**

```properties
# In broker's server.properties
log.message.timestamp.type=CreateTime
max.message.time.difference.ms=3600000  # 1 hour tolerance

# Then restart Kafka broker (CAUTION: this affects all topics)
```

### Step 6: Deploy Updated Application

```bash
cd /home/ubuntu/streamingcandle

# Upload the new JAR file
# (Use scp from your local machine)
# From local machine:
# scp -i ~/Downloads/newDevinaKey.pem target/demo-0.0.1-SNAPSHOT.jar ubuntu@13.203.60.173:/home/ubuntu/streamingcandle/

# Start the application
nohup java -jar demo-0.0.1-SNAPSHOT.jar > nohup.out 2>&1 &

# Monitor startup
tail -f nohup.out
```

### Step 7: Monitor for Timestamp Warnings

Watch for these log patterns:

```bash
# Look for timestamp clamping warnings (should appear if fix is working)
tail -f nohup.out | grep -E "Clamping to prevent InvalidTimestampException"

# Look for InvalidTimestampException (should NOT appear after fix)
tail -f nohup.out | grep -E "InvalidTimestampException"

# Look for successful repartition topic creation
tail -f nohup.out | grep -E "Creating topic.*repartition"
```

## Alternative Fix: Change Timestamp Type to LogAppendTime at Broker Level

If the above doesn't work, the most robust solution is to configure the broker to use `LogAppendTime` for all internal topics:

### On Kafka Broker

```bash
# Edit broker config
sudo nano /path/to/kafka/config/server.properties

# Add these lines:
log.message.timestamp.type=LogAppendTime
max.message.time.difference.ms=3600000

# Restart Kafka broker
sudo systemctl restart kafka
# OR
/path/to/kafka/bin/kafka-server-stop.sh
/path/to/kafka/bin/kafka-server-start.sh -daemon /path/to/kafka/config/server.properties
```

**WARNING:** This affects ALL topics on the broker, not just your application's topics.

## Debug: Check What Timestamp Is Actually Being Used

Add this logging to see what timestamps are being extracted:

```bash
# In application.properties, increase logging for timestamp extractors
logging.level.com.kotsin.consumer.timeExtractor=DEBUG
```

Expected logs:
```
DEBUG TickDataTimestampExtractor - Extracted timestamp 1767340000000 (clamped from 1767343160000)
```

If you DON'T see clamping logs, it means:
1. Timestamp extractor isn't being invoked
2. Timestamps aren't actually in the future
3. Something else is setting timestamps after extraction

## Verify Topic Configuration After Recreation

After deleting and restarting, check the new repartition topic configs:

```bash
# Find the repartition topic
REPARTITION_TOPIC=$(kafka-topics.sh --bootstrap-server localhost:9094 --list | grep "replay-20251230-v2-unified-instrument-candle-processor.*tick-aggregate-store-repartition" | head -1)

# Check its configuration
kafka-configs.sh --bootstrap-server localhost:9094 \
  --entity-type topics \
  --entity-name "$REPARTITION_TOPIC" \
  --describe

# Should show:
# message.timestamp.type=LogAppendTime
# max.message.time.difference.ms=3600000
```

## Still Not Working? Nuclear Option

If nothing else works, change the app-id-prefix to force complete recreation:

```properties
# In application.properties
kafka.streams.app-id-prefix=replay-20260102-
```

This creates entirely new consumer groups and topics, bypassing all old state.

## Root Cause Investigation (Parallel Track)

While fixing the immediate issue, investigate WHY live data has future timestamps:

```bash
# Check system clocks on all servers
date  # On application server
ssh <data-producer-server> date  # On data producer
ssh 13.203.60.173 date  # On Kafka broker

# Check NTP synchronization
timedatectl status
systemctl status chronyd  # or 'ntp'

# Check timezone settings
timedatectl | grep "Time zone"
```

## Success Criteria

After deployment, you should see:
1. ✅ No `InvalidTimestampException` errors
2. ✅ Consumer group in `Stable` state (not `PREPARING_REBALANCE`)
3. ✅ `[ACCEPT-CANDLE]` messages in logs
4. ✅ Repartition topics created with `LogAppendTime` config
5. ✅ StreamThreads 1-20 all running without crashes

## Contact for Help

If still facing issues after these steps, collect:
1. Full stacktrace from nohup.out
2. Output of `kafka-topics.sh --describe` for repartition topic
3. Timestamp values from error messages
4. Output of `date` command from all servers
