# 🚀 DEPLOYMENT GUIDE - StreamingCandle

**Version:** 1.0 - All Critical Issues Fixed  
**Date:** October 23, 2025  
**Status:** ✅ Ready for Production Testing

---

## 📋 PRE-DEPLOYMENT CHECKLIST

- [ ] JAR file built successfully
- [ ] Scripts copied to server
- [ ] Kafka broker accessible (13.203.60.173:9094)
- [ ] MongoDB accessible
- [ ] Existing application stopped
- [ ] State stores backed up

---

## 📦 STEP 1: COPY FILES TO SERVER

```bash
# From your local machine
cd /Users/shivendrapratap/Documents/kotsin/streamingcandle

# Copy JAR
scp target/demo-0.0.1-SNAPSHOT.jar ubuntu@13.203.60.173:/home/ubuntu/streamingcandle/demo.jar

# Copy scripts
scp scripts/cleanup-state-stores.sh ubuntu@13.203.60.173:/home/ubuntu/streamingcandle/scripts/
scp scripts/create-changelog-topics.sh ubuntu@13.203.60.173:/home/ubuntu/streamingcandle/scripts/

# Make scripts executable
ssh ubuntu@13.203.60.173 "chmod +x /home/ubuntu/streamingcandle/scripts/*.sh"
```

---

## 🛑 STEP 2: STOP EXISTING APPLICATION

```bash
# SSH to server
ssh ubuntu@13.203.60.173

# Stop application
pkill -f demo.jar

# Verify it's stopped
ps aux | grep demo.jar
# Should show nothing (except the grep command itself)
```

---

## 🗑️ STEP 3: CLEAN CORRUPTED STATE STORES

```bash
cd /home/ubuntu/streamingcandle

# Run cleanup script
./scripts/cleanup-state-stores.sh

# This will:
# 1. Backup existing state stores
# 2. Delete corrupted stores
# 3. Let Kafka Streams rebuild from changelogs
```

---

## 📊 STEP 4: CREATE ALL KAFKA TOPICS

**CRITICAL:** Run this with BASH, not SH!

```bash
cd /home/ubuntu/streamingcandle/scripts

# Create ALL topics (candles + family + changelogs)
bash create-changelog-topics.sh 13.203.60.173:9094

# Expected output:
# 🚀 Creating all required Kafka topics...
# 📊 Creating candle output topics...
#   ✅ candle-complete-1m
#   ✅ candle-complete-2m
#   ... (6 total)
# 👨‍👩‍👧‍👦 Creating family aggregation topics...
#   ✅ family-structured-1m
#   ... (6 total)
# 🗂️ Creating changelog topics...
#   ✅ instrument-instrument-delta-volume-store-changelog
#   ... (3 total)
# ✅ All topics created successfully!
```

**Verify Topics:**
```bash
kafka-topics.sh --bootstrap-server 13.203.60.173:9094 --list | grep -E '(candle|family|changelog)'
```

You should see **15 topics total**:
- 6 candle topics
- 6 family topics  
- 3 changelog topics

---

## 🚀 STEP 5: START APPLICATION

```bash
cd /home/ubuntu/streamingcandle

# Create logs directory if not exists
mkdir -p logs

# Set production profile
export SPRING_PROFILES_ACTIVE=production

# Start application
nohup java -jar demo.jar > logs/app.log 2>&1 &

# Get process ID
echo $! > app.pid
echo "Application started with PID: $(cat app.pid)"
```

---

## 📊 STEP 6: MONITOR STARTUP

```bash
# Watch logs in real-time
tail -f logs/app.log

# Look for these SUCCESS indicators:
# ✅ Started per-instrument candle stream
# ⏳ Waiting for instrument stream to create candle topics...
# ✅ State has complete windows: scripCode=XXX, ready for emission
# 📤 candle emit tf=1m scrip=XXX vol=XXX → candle-complete-1m
# ✅ Family streams started successfully on attempt 1
# ✅ Unified Market Data Processor started successfully

# Look for these ERROR indicators:
# ❌ Failed to start streams
# ❌ Required candle topic does not exist
# ⚠️ Attempt X failed to start family streams
```

**Startup should complete in ~10-15 seconds**

---

## ✅ STEP 7: VERIFY DATA FLOW

### **7.1: Check Consumer Groups**
```bash
kafka-consumer-groups.sh --bootstrap-server 13.203.60.173:9094 --list
```

**Expected groups:**
- `instrument`
- `family-1m`
- `family-2m`
- `family-5m`
- `family-15m`
- `family-30m`

**Check group status:**
```bash
kafka-consumer-groups.sh --bootstrap-server 13.203.60.173:9094 \
  --describe --group instrument
```

**Expected:** STABLE state, 0 lag

### **7.2: Verify Candle Topics Have Data**
```bash
# Check 1-minute candles
kafka-console-consumer.sh --bootstrap-server 13.203.60.173:9094 \
  --topic candle-complete-1m \
  --from-beginning \
  --max-messages 5 \
  --timeout-ms 30000
```

**Expected:** JSON candle objects with scripCode, OHLC, volume, etc.

### **7.3: Verify Family Topics Have Data**
```bash
# Check 1-minute family aggregation
kafka-console-consumer.sh --bootstrap-server 13.203.60.173:9094 \
  --topic family-structured-1m \
  --from-beginning \
  --max-messages 5 \
  --timeout-ms 30000
```

**Expected:** JSON family objects with equity + derivatives data

### **7.4: Check Health Endpoint**
```bash
curl http://localhost:8081/api/v1/health
```

**Expected:**
```json
{
  "status": "HEALTHY",
  "timestamp": 1729622400000,
  "systemMetrics": {...},
  "streamStates": {
    "instrument": "RUNNING",
    "family-1m": "RUNNING",
    ...
  }
}
```

---

## 🔍 STEP 8: TROUBLESHOOTING

### **Issue: Family Streams Fail to Start**

**Symptoms:**
```
⚠️ Attempt 3 failed to start family streams: Required candle topic does not exist
```

**Solution:**
```bash
# 1. Check if candle topics exist
kafka-topics.sh --bootstrap-server 13.203.60.173:9094 --list | grep candle-complete

# 2. If missing, create them
bash scripts/create-changelog-topics.sh 13.203.60.173:9094

# 3. Restart application
pkill -f demo.jar
nohup java -jar demo.jar > logs/app.log 2>&1 &
```

### **Issue: State Store Corruption**

**Symptoms:**
```
TaskCorruptedException: Tasks [0_0] are corrupted
OffsetOutOfRangeException: Fetch position {offset=156092} is out of range
```

**Solution:**
```bash
# 1. Stop application
pkill -f demo.jar

# 2. Clean state stores
./scripts/cleanup-state-stores.sh

# 3. Restart (will rebuild from changelogs)
nohup java -jar demo.jar > logs/app.log 2>&1 &
```

### **Issue: No Candle Data**

**Symptoms:**
```
✅ Completed 1m window [...]  # Windows complete
(but no "📤 candle emit" messages)
```

**Solution:**
```bash
# Check logs for filtering issues
grep -E "(FILTERED OUT|INVALID CANDLE|EXTRACTION FAILED)" logs/app.log

# Check if topics have wrong names
grep "candle-complete" logs/app.log

# Verify configuration
grep "stream.outputs.candles" application-prod.properties
```

### **Issue: High Consumer Lag**

**Symptoms:**
Consumer group shows lag > 1000

**Solution:**
```bash
# Check application is running
ps aux | grep demo.jar

# Check logs for errors
tail -100 logs/app.log | grep -E "(ERROR|❌)"

# Check Kafka broker health
kafka-broker-api-versions.sh --bootstrap-server 13.203.60.173:9094
```

---

## 📊 MONITORING (Post-Deployment)

### **Continuous Monitoring**

```bash
# Watch for errors
tail -f logs/app.log | grep -E "(ERROR|❌|⚠️)"

# Watch for successful emissions
tail -f logs/app.log | grep "📤 candle emit"

# Monitor consumer lag
watch -n 5 "kafka-consumer-groups.sh --bootstrap-server 13.203.60.173:9094 --describe --group instrument | tail -5"
```

### **Key Metrics to Monitor**

1. **Consumer Lag:** Should be < 100 under normal load
2. **Emission Rate:** Should see "📤 candle emit" every 1-2 minutes per instrument
3. **Stream State:** All groups should show STABLE
4. **Memory Usage:** Should stabilize after 5-10 minutes
5. **Error Rate:** Should be 0 or very low

---

## 🎯 SUCCESS CRITERIA

✅ **Application is HEALTHY if:**

1. All consumer groups show STABLE state
2. Consumer lag is < 100
3. Candle topics receiving data (check with console consumer)
4. Family topics receiving data
5. Health endpoint returns 200 OK
6. No ERROR/❌ messages in logs (some WARN is OK)
7. Memory usage < 2GB

---

## 🛑 ROLLBACK PROCEDURE

**If deployment fails:**

```bash
# 1. Stop new version
pkill -f demo.jar

# 2. Restore previous JAR (if you have backup)
cp demo.jar.backup demo.jar

# 3. Restore state stores from backup
BACKUP_DIR=$(ls -td /tmp/kafka-streams/streamingcandle_backup_* | head -1)
cp -r "$BACKUP_DIR" /tmp/kafka-streams/streamingcandle

# 4. Start old version
nohup java -jar demo.jar > logs/app.log 2>&1 &
```

---

## 📝 POST-DEPLOYMENT CHECKLIST

- [ ] Application started successfully (no errors in first 2 minutes)
- [ ] All consumer groups showing STABLE
- [ ] Candle topics have data
- [ ] Family topics have data  
- [ ] Consumer lag < 100
- [ ] Health endpoint returns OK
- [ ] Monitoring set up (Prometheus/Grafana if available)
- [ ] Alert rules configured
- [ ] Documentation updated

---

## 🔧 MAINTENANCE

### **Daily Checks**
- Consumer lag
- Error rate in logs
- Disk space (state stores grow over time)

### **Weekly Checks**
- Review logs for warnings
- Check topic retention (should be 7 days)
- Monitor state store size

### **Monthly Tasks**
- Review and archive old logs
- Update changelog retention if needed
- Performance tuning based on metrics

---

## 📞 SUPPORT

**If issues persist:**

1. Collect logs: `tar -czf logs-$(date +%Y%m%d).tar.gz logs/`
2. Collect consumer group status
3. Check Kafka broker logs
4. Review `application-prod.properties`
5. Contact team with collected information

---

**Status:** ✅ DEPLOYMENT READY  
**Confidence:** HIGH (All critical issues fixed)  
**Next:** Deploy and monitor! 🚀

