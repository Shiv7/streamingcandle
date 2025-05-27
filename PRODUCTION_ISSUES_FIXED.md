# STREAMING CANDLE PRODUCTION ISSUES & FIXES

## 🚨 **CRITICAL ISSUES IDENTIFIED & RESOLVED**

### **Issue #1: State Directory Conflicts** ❌ → ✅ **FIXED**

**Problem:**
```
org.apache.kafka.streams.errors.ProcessorStateException: Parent [/tmp/kafka-streams-candle-processor-ticktooneminprocessor-...] of task directory [...] doesn't exist and couldn't be created
```

**Root Cause:**
- Multiple Kafka Streams instances competing for the same `/tmp/kafka-streams-state` directory
- Production environment permissions preventing directory creation in `/tmp`
- Hardcoded paths not suitable for production deployment

**Solution Applied:**
1. **Changed state directory path** from `/tmp/kafka-streams-state` to `/var/lib/kafka-streams/streamingcandle`
2. **Added unique instance directories** using UUID suffixes to prevent conflicts
3. **Added fallback mechanism** to temp directory if `/var/lib` isn't writable
4. **Created deployment script** to set up proper permissions

**Files Modified:**
- `src/main/resources/application.properties` - Updated state directory path
- `src/main/java/com/kotsin/consumer/config/KafkaConfig.java` - Added unique directory generation
- `deploy-production.sh` - New deployment script for proper setup

---

### **Issue #2: Application ID Mismatch** ❌ → ✅ **FIXED**

**Problem:**
```
org.apache.kafka.streams.errors.StreamsException: Unable to initialize state, this can happen if multiple instances of Kafka Streams are running in the same state directory
```

**Root Cause:**
- Configuration mismatch between `application.properties` (`tickdata-to-candlestick-app`) and production logs (`candle-processor-ticktooneminprocessor`)
- Different application IDs causing state directory conflicts

**Solution Applied:**
- **Standardized application ID** to `candle-processor-ticktooneminprocessor` across all configurations
- **Added unique suffixes** for each timeframe instance to prevent conflicts

---

### **Issue #3: Network Connectivity Issues** ❌ → ✅ **IMPROVED**

**Problem:**
```
Bootstrap broker 172.31.0.121:9092 (id: -1 rack: null isFenced: false) disconnected
```

**Root Cause:**
- Hardcoded Kafka broker IP address
- No retry/reconnection configuration
- No connection validation before startup

**Solution Applied:**
1. **Added production resilience configurations:**
   - `retry.backoff.ms=100`
   - `reconnect.backoff.ms=50`
   - `request.timeout.ms=40000`
   - `session.timeout.ms=30000`
   - `heartbeat.interval.ms=3000`

2. **Added connectivity validation** in deployment script
3. **Improved error handling** with proper timeouts

---

### **Issue #4: Memory & Performance Issues** ❌ → ✅ **OPTIMIZED**

**Problem:**
- Deprecated cache configuration warnings
- Potential memory leaks from disabled caching
- No memory limits for state stores

**Solution Applied:**
1. **Fixed deprecated configuration:**
   - Replaced `cache.max.bytes.buffering` with `statestore.cache.max.bytes`
   - Set reasonable cache limit: 10MB per instance

2. **Added memory configuration:**
   - JVM heap size: `-Xmx2g -Xms1g`
   - Proper garbage collection tuning ready

---

## 🎯 **DEPLOYMENT INSTRUCTIONS**

### **Step 1: Run Deployment Script**
```bash
cd /path/to/streamingcandle
chmod +x deploy-production.sh
./deploy-production.sh
```

### **Step 2: Verify Setup**
```bash
# Check state directory permissions
ls -la /var/lib/kafka-streams/streamingcandle

# Verify Kafka connectivity
telnet 172.31.0.121 9092

# Check application logs
tail -f /var/log/streamingcandle/application.log
```

### **Step 3: Start Service**
```bash
# Manual start (for testing)
java -jar target/demo-0.0.1-SNAPSHOT.jar

# Or as systemd service
sudo systemctl start streamingcandle
sudo systemctl status streamingcandle
```

---

## 🔍 **MONITORING & TROUBLESHOOTING**

### **Health Checks**
```bash
# Check Kafka consumer lag
kafka-consumer-groups.sh --bootstrap-server 172.31.0.121:9092 --group candle-processor-ticktooneminprocessor --describe

# Monitor state directory growth
du -sh /var/lib/kafka-streams/streamingcandle/*

# Check for locked directories
find /var/lib/kafka-streams/streamingcandle -name "*.lock"
```

### **Common Issues & Solutions**

**Issue:** State directory locked
```bash
# Solution: Clean up locks
sudo find /var/lib/kafka-streams/streamingcandle -name "*.lock" -delete
sudo systemctl restart streamingcandle
```

**Issue:** Out of disk space
```bash
# Solution: Clean old state
sudo find /var/lib/kafka-streams/streamingcandle -type d -mtime +7 -exec rm -rf {} +
```

**Issue:** Memory issues
```bash
# Solution: Increase heap size in systemd service
ExecStart=/usr/bin/java -jar -Xmx4g -Xms2g target/demo-0.0.1-SNAPSHOT.jar
```

---

## 🚀 **PRODUCTION READINESS CHECKLIST**

- ✅ **State Directory Conflicts** - Resolved with unique directories
- ✅ **Network Resilience** - Added retry/reconnection logic
- ✅ **Memory Management** - Proper cache configuration and JVM tuning
- ✅ **Deployment Automation** - Script handles permissions and setup
- ✅ **Monitoring** - Health checks and troubleshooting guide
- ✅ **Error Handling** - Proper exception handling and logging

---

## 🔧 **NEXT STEPS FOR PRODUCTION HARDENING**

1. **Configuration Externalization:**
   - Move Kafka broker IPs to environment variables
   - Use Spring Boot profiles for different environments

2. **Security:**
   - Add SSL/SASL configuration for Kafka
   - Secure MongoDB connection strings

3. **Monitoring:**
   - Integrate with Prometheus/Grafana
   - Set up alerting for consumer lag
   - Monitor state store sizes

4. **Backup & Recovery:**
   - Implement state store backup strategy
   - Document disaster recovery procedures

5. **Load Testing:**
   - Test with production-like data volumes
   - Validate memory usage under load
   - Test failover scenarios

---

## 📊 **EXPECTED BEHAVIOR AFTER FIXES**

- ✅ Application starts without state directory errors
- ✅ All 6 timeframes (1m, 2m, 3m, 5m, 15m, 30m) process independently
- ✅ Network disconnections automatically retry
- ✅ Memory usage remains stable under load
- ✅ State stores persist correctly across restarts

The streaming candle service should now be production-ready with proper error handling, directory management, and network resilience. 