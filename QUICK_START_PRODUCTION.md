# StreamingCandle - Quick Start for Production

## 🚀 Fast Track Deployment

### Option 1: Automated Deployment (Recommended)

```bash
# Make script executable
chmod +x DEPLOY_TO_PRODUCTION.sh

# Run deployment
./DEPLOY_TO_PRODUCTION.sh
```

The script will:
1. ✅ Copy fixed files
2. ✅ Commit to git
3. ✅ Deploy to production server
4. ✅ Build and start application
5. ✅ Verify startup

---

### Option 2: Manual Deployment

#### On Your Local Machine

```bash
cd /Users/shivendrapratap/Documents/kotsin/streamingcandle

# Ensure all changes are committed
git add .
git commit -m "fix: StreamingCandle compilation and runtime fixes"
git push origin main
```

#### On Production Server (ubuntu@13.203.60.173)

```bash
# SSH to server
ssh ubuntu@13.203.60.173

# Stop existing process
pkill -f streamingcandle

# Navigate to app directory
cd ~/streamingcandle

# Pull latest changes
git pull origin main

# Clean and rebuild
mvn clean package -DskipTests

# Start application
nohup mvn spring-boot:run > streamingcandle.log 2>&1 &

# Check logs
tail -f streamingcandle.log
```

---

## ✅ Verification Steps

### 1. Check Application is Running

```bash
ps aux | grep streamingcandle
```

Expected: Should show Java process

---

### 2. Check Logs for Successful Startup

```bash
tail -50 ~/streamingcandle/streamingcandle.log
```

Expected output:
```
🚀 Initializing instrument family cache...
✅ Instrument family cache initialized successfully
🚀 Starting Unified Market Data Processor...
📊 Input topics: forwardtesting-data, OpenInterest, Orderbook
📤 Output topic: enriched-market-data
✅ Unified Market Data Processor started successfully
Started ConsumerApplication in 3.5 seconds
```

---

### 3. Check Health Endpoint

```bash
curl http://localhost:8081/cache/health
```

Expected response:
```json
{
  "status": "UP",
  "totalFamilies": 1250,
  "cacheHitRate": 0.95
}
```

---

### 4. Verify Kafka Output

```bash
kafka-console-consumer --bootstrap-server 13.203.60.173:9094 \
  --topic enriched-market-data \
  --max-messages 5
```

Expected: Should see enriched market data messages

---

## 🔧 Troubleshooting

### Issue: RestTemplate Bean Not Found

**Error**: `No qualifying bean of type 'org.springframework.web.client.RestTemplate'`

**Solution**: Ensure `WebConfig.java` exists and is compiled:
```bash
ls src/main/java/com/kotsin/consumer/config/WebConfig.java
mvn clean compile
```

---

### Issue: Redis Connection Refused

**Error**: `Unable to connect to Redis at localhost:6379`

**Solution**: Start Redis:
```bash
sudo systemctl start redis
redis-cli ping  # Should respond: PONG
```

---

### Issue: Kafka Connection Failed

**Error**: `Failed to connect to 13.203.60.173:9094`

**Solution**: Check Kafka broker is running:
```bash
telnet 13.203.60.173 9094
```

---

### Issue: ScripFinder API Unreachable

**Error**: `Connection refused when calling http://13.203.60.173:8102`

**Solution**: Start scripFinder module:
```bash
# On the server where scripFinder runs
cd ~/scripFinder
mvn spring-boot:run
```

---

## 📊 Monitoring

### View Live Logs

```bash
ssh ubuntu@13.203.60.173
tail -f ~/streamingcandle/streamingcandle.log
```

### Check Consumer Lag

```bash
kafka-consumer-groups --bootstrap-server 13.203.60.173:9094 \
  --describe --group unified-market-processor
```

### Monitor System Resources

```bash
# CPU and Memory
top -p $(pgrep -f streamingcandle)

# Disk space for state stores
df -h /home/ubuntu/kstreams/consumer
```

---

## 🛑 Stopping the Application

```bash
# Find process
ps aux | grep streamingcandle

# Kill gracefully
pkill -SIGTERM -f streamingcandle

# Or force kill if needed
pkill -9 -f streamingcandle
```

---

## 📁 Important Files

| File | Purpose | Location |
|------|---------|----------|
| `streamingcandle.log` | Application logs | `~/streamingcandle/` |
| `application.properties` | Configuration | `~/streamingcandle/src/main/resources/` |
| `target/*.jar` | Compiled JAR | `~/streamingcandle/target/` |
| State stores | Kafka Streams state | `/home/ubuntu/kstreams/consumer/` |

---

## 🎯 Success Criteria

Application is healthy when:

- ✅ Process is running (check with `ps aux | grep streamingcandle`)
- ✅ No ERROR logs (check with `grep ERROR streamingcandle.log`)
- ✅ Health endpoint returns 200 (check with `curl http://localhost:8081/cache/health`)
- ✅ Enriched topic has data (check with kafka-console-consumer)
- ✅ Consumer lag < 100 messages (check with kafka-consumer-groups)
- ✅ CPU usage < 30% (check with `top`)

---

## 🔗 Related Documentation

- **COMPILATION_FIXES_COMPLETED.md** - All fixes applied
- **RUNTIME_FIX_APPLIED.md** - RestTemplate bean fix
- **STREAMINGCANDLE_CONTEXT_AND_FIXES.md** - Complete context
- **MODULE_DOCUMENTATION.md** - Full module docs
- **STREAMINGCANDLE_FIX_PLAN.md** - Migration plan

---

## 📞 Support

If issues persist:

1. Check all logs: `tail -100 streamingcandle.log`
2. Verify all dependencies running (Redis, Kafka, ScripFinder)
3. Check disk space: `df -h`
4. Check memory: `free -h`
5. Restart application: `pkill -f streamingcandle && nohup mvn spring-boot:run > streamingcandle.log 2>&1 &`

---

**Status**: ✅ Ready for Production Deployment  
**Last Updated**: October 20, 2025  
**Version**: 1.0

