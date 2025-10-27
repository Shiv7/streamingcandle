# Next Steps - Testing the New Architecture

## ✅ Completed
- [x] Created new architecture with separate window aggregations
- [x] Implemented LEFT JOIN topology
- [x] Successfully compiled project

## 📋 Before You Start

### 1. Clean Kafka Streams State (REQUIRED)

The new topology uses different state stores. You MUST clean old state:

```bash
# Stop application if running
# Then clean state stores
rm -rf /tmp/kafka-streams/streamingcandle

# Or if using custom state dir (check application.properties):
# rm -rf /var/lib/kafka-streams/streamingcandle
```

### 2. Verify Kafka is Running

```bash
# Check Kafka broker
nc -zv localhost 9092

# List existing topics
kafka-topics --list --bootstrap-server localhost:9092
```

## 🚀 Starting the Application

```bash
cd /Users/shivendrapratap/Documents/kotsin/streamingcandle

# Start the application
mvn spring-boot:run
```

## 🔍 What to Look For in Logs

### Startup - Topology Building
```
🗃️ Building NEW ARCHITECTURE: separate window aggregations per stream
⏱️ Building topology for timeframe: 1m
  [1m] Building tick aggregation → intermediate-ohlcv-1m
  [1m] Building orderbook aggregation → intermediate-orderbook-1m
  [1m] Building OI aggregation → intermediate-oi-1m
  [1m] Building LEFT JOIN topology → candle-complete-1m
⏱️ Building topology for timeframe: 2m
...
```

### Processing - WindowedOHLCV Emissions
```
[1m] OHLCV emitted: scrip=220 vol=12345
[1m] OHLCV emitted: scrip=4503 vol=6789
...
```

### Processing - Orderbook Signals Emissions
```
[1m] Orderbook signals emitted: scrip=220
[1m] Orderbook signals emitted: scrip=4503
...
```

### Processing - OI Metrics Emissions
```
[1m] OI metrics emitted: scrip=220 oi=59200
...
```

### Final Output - Unified Candles
```
📤 EMITTING unified: tf=1m scrip=220 vol=12345 → candle-complete-1m
📤 EMITTING unified: tf=1m scrip=4503 vol=6789 → candle-complete-1m
```

### ✅ Success Indicators

1. **NO "late record" warnings** - Each stream processes independently
2. **All 18 intermediate topics auto-created** - Check in Kafka
3. **Candles appearing in output topics** - Check with consumer
4. **No compilation errors** - Build successful

### ❌ Failure Indicators

1. **No OHLCV emissions** - Check tick data arriving
2. **No orderbook emissions** - Check orderbook data arriving
3. **No final candles** - Join may be failing
4. **State store errors** - Clean state stores

## 📊 Monitoring Topics

### Check Intermediate Topics (should auto-create)

```bash
# List all topics
kafka-topics --list --bootstrap-server localhost:9092 | grep intermediate

# Expected output (18 topics):
# intermediate-ohlcv-1m
# intermediate-ohlcv-2m
# intermediate-ohlcv-3m
# intermediate-ohlcv-5m
# intermediate-ohlcv-15m
# intermediate-ohlcv-30m
# intermediate-orderbook-1m
# ... (similar for orderbook and oi)
```

### Consume Intermediate OHLCV

```bash
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic intermediate-ohlcv-1m \
  --from-beginning \
  --max-messages 5
```

Expected output:
```json
{
  "scripCode": "220",
  "windowStartMillis": 1761539760000,
  "windowEndMillis": 1761539820000,
  "open": 10.5,
  "high": 10.6,
  "low": 10.4,
  "close": 10.55,
  "volume": 12345
}
```

### Consume Final Output

```bash
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic candle-complete-1m \
  --from-beginning \
  --max-messages 5
```

Expected output (complete unified message):
```json
{
  "scripCode": "220",
  "timeframe": "1m",
  "candle": {
    "open": 10.5,
    "high": 10.6,
    "low": 10.4,
    "close": 10.55,
    "volume": 12345
  },
  "orderbookSignals": {
    "ofi": 15.2,
    "spreadAvg": 0.05,
    ...
  },
  "openInterest": {
    "oiClose": 59200,
    "oiChange": 200
  }
}
```

## 🐛 Troubleshooting

### Issue: No candles emitted

**Check 1: Are ticks arriving?**
```bash
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic forwardtesting-data \
  --max-messages 10
```

**Check 2: Is tick window aggregation working?**
Look for log: `[1m] OHLCV emitted: scrip=...`

**Check 3: Are intermediate topics being written?**
```bash
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic intermediate-ohlcv-1m \
  --max-messages 1
```

### Issue: Intermediate topics created but no final output

**Possible cause:** JOIN failing because keys don't match

**Check:** Compare scripCode in OHLCV vs Orderbook vs OI
```bash
# Check key in OHLCV topic
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic intermediate-ohlcv-1m \
  --property print.key=true \
  --max-messages 1

# Check key in orderbook topic
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic intermediate-orderbook-1m \
  --property print.key=true \
  --max-messages 1
```

Keys should match (both should be token as string).

### Issue: State store corruption

**Solution:** Clean and restart
```bash
# Stop app
rm -rf /tmp/kafka-streams/streamingcandle
# Restart app
mvn spring-boot:run
```

## ⚙️ Tuning

### If still seeing occasional late records:

**Increase grace period in application.properties:**
```properties
unified.streams.window.grace.period.seconds=20
```

### If OI updates very slow:

Already handled - OI has 3x grace period (30s vs 10s).

### If memory usage high:

**Reduce state store cache:**
```properties
spring.kafka.streams.properties.statestore.cache.max.bytes=52428800
```

## 🎯 Success Criteria

You'll know it's working when:

1. ✅ Application starts without errors
2. ✅ 18 intermediate topics auto-created
3. ✅ Log shows OHLCV emissions: `[1m] OHLCV emitted: scrip=...`
4. ✅ Log shows Orderbook emissions: `[1m] Orderbook signals emitted: scrip=...`
5. ✅ Log shows final emissions: `📤 EMITTING unified: tf=1m scrip=...`
6. ✅ `candle-complete-1m` topic has messages
7. ✅ **NO "late record" warnings** (this is the key fix!)

## 📝 Rollback (if needed)

If issues occur and you need to rollback:

```bash
# Stop application

# Restore old topology
mv src/main/java/com/kotsin/consumer/processor/TopologyConfiguration.java.backup \
   src/main/java/com/kotsin/consumer/processor/TopologyConfiguration.java

# Restore old debug transformer
mv src/main/java/com/kotsin/consumer/processor/DebugLateRecordTransformer.java.old \
   src/main/java/com/kotsin/consumer/processor/DebugLateRecordTransformer.java

# Remove intermediate topic config from application.properties
# (lines 68-88)

# Clean state
rm -rf /tmp/kafka-streams/streamingcandle

# Rebuild and restart
mvn clean compile -DskipTests
mvn spring-boot:run
```

## 📞 Questions?

Check these files for details:
- `NEW_ARCHITECTURE_DESIGN.md` - Design rationale
- `MIGRATION_SUMMARY.md` - What changed
- `NEXT_STEPS.md` - This file

Good luck! The new architecture should eliminate all "late record" issues. 🚀
