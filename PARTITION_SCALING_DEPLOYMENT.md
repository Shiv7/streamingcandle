# Kafka Partition Scaling Deployment Guide

## Overview
This guide covers scaling your Kafka topics from current partitions to DOUBLED partitions for improved throughput.

### Partition Changes
| Topic | Before | After | Change |
|-------|--------|-------|--------|
| forwardtesting-data | 5 | 10 | +5 partitions |
| OpenInterest | 3 | 6 | +3 partitions |
| Orderbook | 10 | 20 | +10 partitions |

### Thread Configuration
- **Before**: 10 threads
- **After**: 20 threads
- **Reason**: Match max partition count (Orderbook = 20)

### Grace Period
- **Before**: 5 seconds
- **After**: 60 seconds
- **Reason**: Prevent expired window warnings (371K+ instances)

---

## Deployment Steps

### Step 1: SSH to Kafka Server

```bash
ssh -i ~/Downloads/newDevinaKey.pem ubuntu@13.203.60.173
```

### Step 2: Run Partition Creation Script

The script will create/update topics with doubled partitions:

```bash
cd /home/ubuntu/streamingcandle
./scripts/create-doubled-partitions.sh
```

**What it does:**
- Checks if topics exist
- Creates new topics OR increases partitions on existing topics
- Shows final partition configuration

**Expected output:**
```
======================================
Creating Kafka Topics (Doubled Partitions)
======================================

=== Main Input Topics ===
Processing topic: forwardtesting-data (10 partitions)
  ✓ Successfully increased partitions from 5 to 10

Processing topic: OpenInterest (6 partitions)
  ✓ Successfully increased partitions from 3 to 6

Processing topic: Orderbook (20 partitions)
  ✓ Successfully increased partitions from 10 to 20
...
```

### Step 3: Verify Topics

```bash
# Check all topics
kafka-topics.sh --bootstrap-server localhost:9094 --list | grep -E "forwardtesting|OpenInterest|Orderbook"

# Check specific topic partitions
kafka-topics.sh --bootstrap-server localhost:9094 --describe --topic Orderbook
```

You should see:
```
Topic: Orderbook    PartitionCount: 20    ReplicationFactor: 1
```

### Step 4: Stop Application (if running)

```bash
# Find the process
ps aux | grep streamingcandle

# Kill it
pkill -f streamingcandle

# Or if running in screen/tmux
screen -r
# Then Ctrl+C
```

### Step 5: Clear Old State (IMPORTANT!)

Since partitions changed, Kafka Streams state is now invalid:

```bash
# Clear state directory
rm -rf /tmp/kafka-streams/streamingcandle/*

# Or if using production state dir
rm -rf /var/lib/kafka-streams/streamingcandle/*
```

**Why?** Partition changes invalidate the state store mappings. Kafka Streams will rebuild from changelog topics.

### Step 6: Deploy Updated Application

Your application already has the new configuration:
- ✅ 20 threads (application.properties:46)
- ✅ 60s grace period (application.properties:70, 86)

```bash
# Build the application
cd /home/ubuntu/streamingcandle
mvn clean package -DskipTests

# Start the application
nohup mvn spring-boot:run > nohup.out 2>&1 &

# Or if you have a jar
nohup java -jar target/demo-0.0.1-SNAPSHOT.jar > nohup.out 2>&1 &
```

### Step 7: Monitor Startup

```bash
# Watch logs
tail -f nohup.out

# Look for these indicators:
# 1. StreamThread-1 through StreamThread-20 starting
# 2. No "Skipping record for expired window" warnings
# 3. "ACCEPT-CANDLE" messages appearing
```

**Expected log patterns:**
```
StreamThread-1 started
StreamThread-2 started
...
StreamThread-20 started

✅ [ACCEPT-CANDLE] 11:35:00 | 59321 | hasOB=✓ hasOI=✗ | volume=103950
```

### Step 8: Verify Performance

After 5-10 minutes, check:

```bash
# Count expired window warnings (should be MUCH lower or zero)
grep "Skipping record for expired window" nohup.out | wc -l

# Check thread utilization
grep "StreamThread-" nohup.out | grep "in_progress" | tail -20

# Verify candles being produced
grep "ACCEPT-CANDLE" nohup.out | tail -10
```

---

## Performance Expectations

### Before (5/3/10 partitions, 10 threads, 5s grace)
- ❌ 371,179 expired window warnings
- ❌ High processing lag
- ❌ Many records skipped
- ⚠️ 10 threads, only max 10 partitions processable

### After (10/6/20 partitions, 20 threads, 60s grace)
- ✅ 0-100 expired window warnings (expected)
- ✅ 2x partition parallelism
- ✅ 60s grace period handles lag spikes
- ✅ 20 threads = full parallelism on all topics

### Throughput Impact
- **Orderbook**: 2x throughput (10→20 partitions, 10→20 threads)
- **forwardtesting-data**: 2x throughput (5→10 partitions)
- **OpenInterest**: 2x throughput (3→6 partitions)

---

## Rollback Plan (If Needed)

If something goes wrong:

1. **Stop the application**
   ```bash
   pkill -f streamingcandle
   ```

2. **Revert configuration**
   ```bash
   cd /home/ubuntu/streamingcandle
   git checkout src/main/resources/application*.properties
   ```

3. **Kafka topics remain at doubled partitions**
   - You CANNOT reduce partition count
   - But you CAN run with fewer threads
   - Set threads back to 10-12 in config

4. **Restart with old thread count**
   ```bash
   # Edit application.properties
   # Change: num.stream.threads=20 → num.stream.threads=10
   mvn spring-boot:run
   ```

**Note:** Once partitions are increased, they cannot be decreased. The only rollback is to create entirely new topics.

---

## Troubleshooting

### Issue: Application won't start after partition increase

**Cause:** Stale state directory

**Solution:**
```bash
rm -rf /tmp/kafka-streams/streamingcandle/*
# Restart application
```

### Issue: Still seeing expired window warnings

**Check:**
1. Grace period is 60s: `grep grace.seconds application.properties`
2. Threads are 20: `grep num.stream.threads application.properties`
3. Topics have correct partitions: `kafka-topics.sh --describe --topic Orderbook`

### Issue: Only some threads active

**Cause:** Topics haven't been scaled yet

**Solution:**
```bash
# Verify all topics are scaled
kafka-topics.sh --list | while read topic; do
    echo -n "$topic: "
    kafka-topics.sh --describe --topic "$topic" | grep PartitionCount
done
```

### Issue: High memory usage

**Cause:** 20 threads × state stores = more memory

**Solution:** Increase JVM heap
```bash
export MAVEN_OPTS="-Xmx4g -Xms2g"
mvn spring-boot:run
```

---

## Configuration Summary

All changes already applied to your codebase:

### application.properties
```properties
# Line 46: Threads
spring.kafka.streams.properties.num.stream.threads=20

# Line 70: Unified grace period
unified.window.grace.seconds=60

# Line 86: Family grace period
family.candle.window.grace.seconds=60
```

### application-prod.properties
```properties
# Line 19: Production threads
spring.kafka.streams.properties.num.stream.threads=20
```

### application-dev.properties
```properties
# Line 13: Dev threads
spring.kafka.streams.properties.num.stream.threads=20
```

---

## Next Steps After Deployment

1. **Monitor for 1 hour**
   - Watch expired window warnings (should drop to near-zero)
   - Check CPU usage (should be distributed across 20 threads)
   - Verify throughput increased

2. **Check consumer lag**
   ```bash
   kafka-consumer-groups.sh --bootstrap-server localhost:9094 \
     --describe --group replay-20251230-v2-unified-instrument-candle-processor
   ```

3. **Validate data quality**
   - Check that candles have valid OFI, VPIN, Kyle's Lambda values
   - Verify no data gaps

4. **Document baseline metrics**
   - Records/second processed
   - Average latency
   - CPU/memory usage

---

## Contact

If you encounter issues during deployment, check:
1. Application logs: `tail -f nohup.out`
2. Kafka broker logs
3. System resources: `top`, `htop`

Remember: This is a ONE-WAY change. Test in dev first if possible!
