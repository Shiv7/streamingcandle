# ⚙️ **StreamingCandle Configuration Guide**

## **Quick Profile Selection**

```bash
# Local Development (connects to remote Kafka for testing)
java -jar demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=local

# Production (uses production Kafka and persistent state)
java -jar demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod

# Default (localhost - NOT recommended, for reference only)
java -jar demo-0.0.1-SNAPSHOT.jar
```

---

## 🔑 **Key Differences Between Profiles**

### **application.properties** (Default - Not for Production!)
- **Kafka**: `localhost:9092` ❌
- **State Dir**: `/home/ubuntu/kstreams/${spring.application.name}`
- **Consumer Group**: default
- **Use Case**: Fallback only, NOT for production

### **application-local.properties** (Local Testing)
- **Kafka**: `13.203.60.173:9094` ✅
- **State Dir**: `/tmp/kafka-streams-local` (temporary)
- **Consumer Group**: `streamingcandle-local-test-group`
- **Logging**: DEBUG level
- **Use Case**: Local development connecting to remote Kafka

### **application-prod.properties** (Production)
- **Kafka**: `13.203.60.173:9094` ✅ (update as needed)
- **State Dir**: `/var/lib/kafka-streams/streamingcandle` (persistent)
- **Consumer Group**: `streamingcandle-production`
- **Logging**: INFO level, file-based
- **Log File**: `/var/log/streamingcandle/application.log`
- **Use Case**: Production deployment

---

## 📝 **What You Need to Update for Production**

### **Before deploying to production, update these in `application-prod.properties`:**

```properties
# 1. Kafka Broker (if different from current)
spring.kafka.bootstrap-servers=<YOUR_PRODUCTION_KAFKA_BROKER>

# 2. MongoDB Connection (add credentials if needed)
spring.data.mongodb.uri=mongodb://<username>:<password>@<host>:<port>/tradeIngestion

# 3. Verify state directory path is correct
spring.kafka.streams.state-dir=/var/lib/kafka-streams/streamingcandle

# 4. Verify log directory path is correct
logging.file.name=/var/log/streamingcandle/application.log
```

---

## ✅ **What's Already Correct**

All these settings are the SAME across local and production:

```properties
# Information Bars Configuration
information.bars.enabled=true
information.bars.thresholds=1.0,2.0,5.0

# All 4 bar types enabled
information.bars.vib.enabled=true
information.bars.dib.enabled=true
information.bars.trb.enabled=true
information.bars.vrb.enabled=true

# EWMA and thresholds
information.bars.ewma.span=100.0
information.bars.min.expected.volume=100.0
information.bars.expected.bar.size=50.0

# Microstructure Features
microstructure.enabled=true
microstructure.window.size=50
microstructure.min.observations=20
microstructure.emit.interval.ms=1000

# Kafka Topics (same names across environments)
information.bars.input.topic=forwardtesting-data
information.bars.vib.output.topic=volume-imbalance-bars
information.bars.dib.output.topic=dollar-imbalance-bars
information.bars.trb.output.topic=tick-runs-bars
information.bars.vrb.output.topic=volume-runs-bars
microstructure.input.topic=Orderbook
microstructure.output.topic=microstructure-features

# Kafka Streams Performance Settings
spring.kafka.streams.properties.processing.guarantee=exactly_once_v2
spring.kafka.streams.properties.commit.interval.ms=100
spring.kafka.streams.properties.statestore.cache.max.bytes=10485760
```

---

## 🚀 **Quick Start Commands**

### **Local Testing**
```bash
cd /Users/shivendrapratap/Documents/kotsin/streamingcandle

# Build
mvn clean package -DskipTests

# Run with local profile
java -jar target/demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

### **Production Deployment**
```bash
# On production server

# Ensure directories exist
sudo mkdir -p /var/lib/kafka-streams/streamingcandle
sudo mkdir -p /var/log/streamingcandle
sudo chown -R ubuntu:ubuntu /var/lib/kafka-streams/streamingcandle
sudo chown -R ubuntu:ubuntu /var/log/streamingcandle

# Run with production profile
java -Xms2g -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=20 \
  -jar demo-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=prod
```

---

## 🎯 **Summary**

✅ **What's Good**:
- Configuration is properly externalized
- All business logic settings are consistent
- Production profile created with proper paths
- Exactly-once semantics enabled
- Multi-threshold information bars configured correctly

⚠️ **What You Must Do**:
1. Update Kafka broker address in prod config (if different)
2. Update MongoDB connection in prod config (if different)
3. Create state directory on prod server before deployment
4. Create log directory on prod server before deployment
5. Use `--spring.profiles.active=prod` when deploying

🔒 **What's Protected**:
- Local and prod use different consumer groups (no conflicts)
- Local and prod use different state directories (no conflicts)
- Local and prod use different application IDs (no conflicts)

---

**Ready for Production**: ✅ (after updating broker/MongoDB addresses if needed)

