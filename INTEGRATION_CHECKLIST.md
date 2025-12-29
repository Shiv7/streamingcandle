# MASTER ARCHITECTURE - Integration Checklist

## ✅ COMPLETED - All Core Modules Implemented

### Phase 1-5: Implementation Complete
- ✅ 17 Java files created (4,610 lines)
- ✅ All modules compile successfully
- ✅ Redis infrastructure ready
- ✅ Orchestrator wired

---

## 🔧 INTEGRATION STEPS (In Order)

### Step 1: Wire Redis to Calculators ⚠️ HIGH PRIORITY

#### 1.1 VolumeCanonicalCalculator Integration
**File:** `src/main/java/com/kotsin/consumer/masterarch/calculator/VolumeCanonicalCalculator.java`

**Current Line 70-75:**
```java
// TODO: Replace with RedisPercentileService
double volPercentile = 50.0; // Placeholder
```

**Replace with:**
```java
// Get 1-year percentile from Redis
double volPercentile = redisPercentileService.getVolumeROCPercentile(
    scripCode,
    volROC5
);

// Store current value for future calculations
redisPercentileService.storeVolumeROC(
    scripCode,
    volROC5,
    System.currentTimeMillis()
);
```

**Add to constructor:**
```java
private final RedisPercentileService redisPercentileService;
```

---

#### 1.2 MasterArchIndexRegimeCalculator Integration
**File:** `src/main/java/com/kotsin/consumer/masterarch/calculator/MasterArchIndexRegimeCalculator.java`

**Current Line 120-125:**
```java
// TODO: Get from Redis
double atrHistorySMA = atr14; // Placeholder
```

**Replace with:**
```java
// Get 1-year ATR percentile from Redis
double atrHistorySMA = redisPercentileService.getATRRatioPercentile(
    scripCode,
    atr14
);

// Store current ATR ratio
redisPercentileService.storeATRRatio(
    scripCode,
    atrPct,
    System.currentTimeMillis()
);
```

**Add to constructor:**
```java
private final RedisPercentileService redisPercentileService;
```

---

#### 1.3 CorrelationGovernor Integration
**File:** `src/main/java/com/kotsin/consumer/masterarch/calculator/CorrelationGovernor.java`

**Current Line 85-90:**
```java
// TODO: Implement Redis cluster storage
private Map<String, List<String>> clusters = new ConcurrentHashMap<>();
```

**Replace with Redis keys:**
```java
// Store cluster membership
ZADD correlation:cluster:{cluster_id} {timestamp} {scripCode}
EXPIRE correlation:cluster:{cluster_id} {24h}

// Store rolling correlation
ZADD correlation:rolling:{scripCode1}:{scripCode2} {timestamp} {correlation}
ZREMRANGEBYSCORE correlation:rolling:{scripCode1}:{scripCode2} 0 {7_days_ago}
```

**Add methods to RedisPercentileService:**
```java
public void storeCorrelation(String scripCode1, String scripCode2, double correlation, long timestamp);
public double getRollingCorrelation(String scripCode1, String scripCode2);
public void addToCluster(String clusterId, String scripCode, long timestamp);
public List<String> getClusterMembers(String clusterId);
```

---

### Step 2: Historical Candle Fetching ⚠️ HIGH PRIORITY

#### 2.1 Create HistoricalCandleService
**New File:** `src/main/java/com/kotsin/consumer/masterarch/service/HistoricalCandleService.java`

```java
@Service
@RequiredArgsConstructor
public class HistoricalCandleService {

    private final RedisTemplate<String, UnifiedCandle> candleRedisTemplate;

    /**
     * Fetch last N candles for a scripCode from Redis
     */
    public List<UnifiedCandle> getHistoricalCandles(
            String scripCode,
            String timeframe,
            int lookback
    ) {
        String key = "candles:" + timeframe + ":" + scripCode;

        // Fetch from Redis sorted set (sorted by timestamp)
        Set<UnifiedCandle> candles = candleRedisTemplate
            .opsForZSet()
            .reverseRange(key, 0, lookback - 1);

        if (candles == null || candles.isEmpty()) {
            log.warn("No historical candles found for {}", scripCode);
            return List.of();
        }

        return new ArrayList<>(candles);
    }

    /**
     * Store candle in Redis for historical retrieval
     */
    public void storeCandle(UnifiedCandle candle, String timeframe) {
        String key = "candles:" + timeframe + ":" + candle.getScripCode();

        // Add to sorted set with timestamp as score
        candleRedisTemplate.opsForZSet().add(
            key,
            candle,
            candle.getWindowStartMillis()
        );

        // Keep only last 500 candles (configurable)
        long count = candleRedisTemplate.opsForZSet().zCard(key);
        if (count > 500) {
            candleRedisTemplate.opsForZSet().removeRange(key, 0, count - 500);
        }
    }
}
```

---

#### 2.2 Update MasterArchOrchestrator
**File:** `src/main/java/com/kotsin/consumer/masterarch/orchestrator/MasterArchOrchestrator.java`

**Current Line 278-304:**
```java
private List<UnifiedCandle> convertToUnifiedCandles(FamilyCandle family) {
    // TODO: This should fetch historical candles from Redis/StateStore
    // For now, just convert the current equity candle
    ...
}
```

**Replace with:**
```java
private final HistoricalCandleService historicalCandleService;

private List<UnifiedCandle> convertToUnifiedCandles(FamilyCandle family) {
    if (family.getEquity() == null) {
        return List.of();
    }

    // Convert current candle
    UnifiedCandle current = UnifiedCandle.builder()
        .scripCode(family.getEquity().getScripCode())
        .companyName(family.getEquity().getCompanyName())
        .exchange(family.getEquity().getExchange())
        .exchangeType(family.getEquity().getExchangeType())
        .timeframe(family.getEquity().getTimeframe())
        .windowStartMillis(family.getEquity().getWindowStartMillis())
        .windowEndMillis(family.getEquity().getWindowEndMillis())
        .open(family.getEquity().getOpen())
        .high(family.getEquity().getHigh())
        .low(family.getEquity().getLow())
        .close(family.getEquity().getClose())
        .volume(family.getEquity().getVolume())
        .buyVolume(family.getEquity().getBuyVolume())
        .sellVolume(family.getEquity().getSellVolume())
        .vwap(family.getEquity().getVwap())
        .build();

    // Fetch historical candles (last 100 candles for 30m timeframe)
    List<UnifiedCandle> historical = historicalCandleService.getHistoricalCandles(
        family.getEquity().getScripCode(),
        family.getTimeframe(),
        100
    );

    // Store current candle for future retrieval
    historicalCandleService.storeCandle(current, family.getTimeframe());

    // Combine historical + current
    List<UnifiedCandle> combined = new ArrayList<>(historical);
    combined.add(current);

    return combined;
}
```

---

### Step 3: Kafka Streams Integration ⚠️ HIGH PRIORITY

#### 3.1 Create MasterArchProcessor
**New File:** `src/main/java/com/kotsin/consumer/masterarch/processor/MasterArchProcessor.java`

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class MasterArchProcessor {

    private final MasterArchOrchestrator orchestrator;

    @Bean
    public KStream<String, FamilyCandle> masterArchProcessorStream(
            StreamsBuilder builder
    ) {
        // Input: family-candle-30m topic
        KStream<String, FamilyCandle> familyStream = builder.stream(
            "family-candle-30m",
            Consumed.with(Serdes.String(), FamilyCandle.serde())
        );

        // Process through orchestrator
        KStream<String, MasterArchOrchestrator.MasterArchResult> results = familyStream
            .filter((key, family) -> family != null && family.hasEquity())
            .mapValues(family -> {
                try {
                    // Fetch VCP, pivot, OHM scores from state stores or calculate
                    double vcpScore = fetchVCPScore(family.getFamilyId());
                    double nearestPivot = fetchNearestPivot(family.getFamilyId());
                    double pivotDistance = fetchPivotDistance(family.getFamilyId());
                    String pivotType = fetchPivotType(family.getFamilyId());
                    double ohmScore = fetchOHMScore(family.getFamilyId());
                    boolean optionBuyerFriendly = checkOptionBuyerFriendly(family);

                    // Fetch index candles (NIFTY50)
                    List<InstrumentCandle> indexCandles30m = fetchIndexCandles("999920000");

                    return orchestrator.process(
                        family,
                        indexCandles30m,
                        vcpScore,
                        nearestPivot,
                        pivotDistance,
                        pivotType,
                        ohmScore,
                        optionBuyerFriendly
                    );
                } catch (Exception e) {
                    log.error("Error processing family candle: {}", family.getFamilyId(), e);
                    return null;
                }
            })
            .filter((key, result) -> result != null);

        // Output to multiple topics
        results.to("score-final-opportunity",
            Produced.with(Serdes.String(), createResultSerde()));

        // Fan out to individual module topics
        results
            .mapValues(r -> r.getIndexContext())
            .to("regime-index-output",
                Produced.with(Serdes.String(), IndexContextScore.serde()));

        results
            .mapValues(r -> r.getSecurityContext())
            .to("regime-security-output",
                Produced.with(Serdes.String(), SecurityContextScore.serde()));

        results
            .mapValues(r -> r.getFudkii())
            .to("signal-fudkii-output",
                Produced.with(Serdes.String(), createFUDKIISerde()));

        // ... (repeat for all modules)

        return results;
    }
}
```

---

### Step 4: Add Serdes for All Output Models

**Create:** `src/main/java/com/kotsin/consumer/masterarch/serde/`

Files needed:
- `MasterArchResultSerde.java`
- `FUDKIIOutputSerde.java`
- `VolumeOutputSerde.java`
- `VelocityOutputSerde.java`
- `StructuralOutputSerde.java`
- `BehaviouralOutputSerde.java`
- `CorrelationOutputSerde.java`
- `HedgeOutputSerde.java`
- `PositionSizeOutputSerde.java`

**Template:**
```java
public class MasterArchResultSerde implements Serde<MasterArchResult> {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public Serializer<MasterArchResult> serializer() {
        return (topic, data) -> {
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed", e);
            }
        };
    }

    @Override
    public Deserializer<MasterArchResult> deserializer() {
        return (topic, bytes) -> {
            try {
                return MAPPER.readValue(bytes, MasterArchResult.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed", e);
            }
        };
    }
}
```

---

### Step 5: Add Kafka Topics to Configuration

**File:** `src/main/resources/application.yml` (or create if doesn't exist)

```yaml
kafka:
  topics:
    masterarch:
      input:
        family-candle-30m: family-candle-30m
      output:
        regime-index: regime-index-output
        regime-security: regime-security-output
        signal-fudkii: signal-fudkii-output
        signal-volume: signal-volume-output
        signal-velocity: signal-velocity-output
        validation-structural: validation-structural-output
        validation-behavioural: validation-behavioural-output
        validation-correlation: validation-correlation-output
        score-final: score-final-opportunity
        trade-position: trade-position-size
```

---

## 📋 Testing Checklist

### Unit Tests (Target: 80% Coverage)

- [ ] `MasterArchIndexRegimeCalculatorTest`
- [ ] `MasterArchSecurityRegimeCalculatorTest`
- [ ] `MasterArchFUDKIICalculatorTest`
- [ ] `VolumeCanonicalCalculatorTest`
- [ ] `VelocityMMSCalculatorTest`
- [ ] `StructuralValidatorTest`
- [ ] `BehaviouralValidatorTest`
- [ ] `CorrelationGovernorTest`
- [ ] `HedgeDecisionModuleTest`
- [ ] `PositionSizerTest`
- [ ] `ScoreDecayManagerTest`
- [ ] `MasterArchOrchestratorTest`
- [ ] `RedisPercentileServiceTest`
- [ ] `HistoricalCandleServiceTest`

### Integration Tests

- [ ] Full orchestrator flow test (mock Redis)
- [ ] Kafka Streams topology test
- [ ] Redis percentile calculation test (with real Redis)
- [ ] Historical candle fetching test
- [ ] Multi-module output verification

### Backtest Validation

- [ ] Load historical trades from MongoDB `backtest_trades` collection
- [ ] Run new system on same historical data
- [ ] Compare Final_Opportunity_Score vs old MTIS score
- [ ] Measure win rate, Sharpe ratio, max drawdown
- [ ] Generate comparison report

---

## 🚀 Deployment Steps

### 1. Local Testing
```bash
# Start Redis
docker run -d -p 6379:6379 redis:latest

# Start Kafka
docker-compose up -d kafka

# Run application
mvn spring-boot:run
```

### 2. Create Kafka Topics
```bash
kafka-topics.sh --create --topic regime-index-output --partitions 3 --replication-factor 2
kafka-topics.sh --create --topic regime-security-output --partitions 3 --replication-factor 2
kafka-topics.sh --create --topic signal-fudkii-output --partitions 3 --replication-factor 2
kafka-topics.sh --create --topic signal-volume-output --partitions 3 --replication-factor 2
kafka-topics.sh --create --topic signal-velocity-output --partitions 3 --replication-factor 2
kafka-topics.sh --create --topic validation-structural-output --partitions 3 --replication-factor 2
kafka-topics.sh --create --topic validation-behavioural-output --partitions 3 --replication-factor 2
kafka-topics.sh --create --topic validation-correlation-output --partitions 3 --replication-factor 2
kafka-topics.sh --create --topic score-final-opportunity --partitions 3 --replication-factor 2
kafka-topics.sh --create --topic trade-position-size --partitions 3 --replication-factor 2
```

### 3. Monitor Redis Data Population
```bash
# Check Volume ROC percentile data
redis-cli ZCARD volumes:roc:N:D:49812

# Check OI percentile data
redis-cli ZCARD oi:percentile:N:D:49812

# Check ATR ratio data
redis-cli ZCARD atr:ratio:999920000
```

### 4. Parallel Rollout (Old vs New System)

**Week 1:**
- Run both old and new systems in parallel
- New system outputs to `*-output-v2` topics
- Compare outputs in Grafana dashboards

**Week 2:**
- Analyze score distributions
- Validate rejection reasons
- Measure latency impact

**Week 3:**
- Gradual traffic migration: 10% → 30% → 50%
- Monitor error rates and latency

**Week 4:**
- 100% migration to new system
- Deprecate old calculators

---

## 📊 Monitoring Dashboard (Grafana)

### Metrics to Track

**Latency:**
- `masterarch.orchestrator.latency` (p50, p95, p99)
- `masterarch.calculator.{module}.latency`
- `redis.percentile.latency`

**Throughput:**
- `masterarch.orchestrator.throughput` (candles/sec)
- `kafka.topic.{topic}.lag`

**Score Distributions:**
- `masterarch.score.final.histogram`
- `masterarch.score.index_context.histogram`
- `masterarch.score.security_context.histogram`
- `masterarch.signal.fudkii.histogram`

**Rejection Reasons:**
- `masterarch.rejection.structural.count`
- `masterarch.rejection.behavioural.count`
- `masterarch.rejection.low_confidence.count`

**Redis Health:**
- `redis.connections.active`
- `redis.percentile.data_count`
- `redis.percentile.cache_hit_rate`

---

## ✅ Final Validation Checklist

Before going live, ensure:

- [ ] All Redis integrations complete
- [ ] Historical candle fetching working
- [ ] Kafka Streams topology deployed
- [ ] All 10 output topics created
- [ ] Unit test coverage ≥80%
- [ ] Integration tests passing
- [ ] Backtest validation complete
- [ ] Monitoring dashboards live
- [ ] Latency targets met (<50ms p95)
- [ ] Parallel rollout tested
- [ ] Old system running in parallel
- [ ] Error rates acceptable (<0.1%)
- [ ] Documentation updated

---

## 🎯 Success Criteria

### Week 1
- ✅ All modules compiled
- ⚠️ Redis integration complete
- ⚠️ Historical candle service deployed

### Week 2
- ⚠️ Kafka topology live
- ⚠️ Parallel system running
- ⚠️ Initial comparison data available

### Week 3
- ⚠️ Backtest validation complete
- ⚠️ 50% traffic on new system
- ⚠️ Latency targets validated

### Week 4
- ⚠️ 100% migration complete
- ⚠️ Old system deprecated
- ⚠️ Production monitoring stable

---

**Status:** ✅ Phase 5 Implementation Complete - Integration In Progress
**Next:** Complete Step 1 (Redis Integration) and Step 2 (Historical Candles)
