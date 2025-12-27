# Playback Testing Guide - Old vs New Curated Signals

## 🎯 Objective

Compare the OLD signal generation system (TradingSignalProcessor) with the NEW curated signals system (CuratedSignalProcessor) using last week's historical data to validate the new approach.

---

## 📋 Pre-Playback Checklist

### 1. ✅ Topics Status (Already Done)
You've deleted all topics except:
- ✅ Forward testing data (historical candles)
- ✅ Orderbook data
- ✅ OI (Open Interest) data

### 2. Create Required Topics for Playback

```bash
# Create topics for OLD system output
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic trading-signals --partitions 3 --replication-factor 1

# Create topics for NEW system output
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic trading-signals-curated --partitions 3 --replication-factor 1

# Create intermediate topics (will be auto-created but good to pre-create)
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic unified-candle-1m --partitions 3 --replication-factor 1
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic unified-candle-2m --partitions 3 --replication-factor 1
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic unified-candle-3m --partitions 3 --replication-factor 1
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic unified-candle-5m --partitions 3 --replication-factor 1

# Module topics
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic regime-index-output --partitions 3 --replication-factor 1
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic regime-security-output --partitions 3 --replication-factor 1
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic regime-acl-output --partitions 3 --replication-factor 1
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic vcp-combined --partitions 3 --replication-factor 1
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic css-output --partitions 3 --replication-factor 1
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic ipu-signals-5m --partitions 3 --replication-factor 1
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic magnitude-final --partitions 3 --replication-factor 1
```

### 3. Configure Application for Playback

**Create**: `application-playback.yml`

```yaml
# Playback-specific configuration
spring:
  profiles:
    active: playback

# Enable both systems for comparison
trading:
  enabled: true  # OLD system

curated:
  enabled: true  # NEW system

  # F&O and Levels - Mock for playback
  fo:
    enabled: false  # Disable for playback (no historical F&O data)
  levels:
    enabled: false  # Disable for playback (no historical level data)

  scoring:
    min-curated-score: 60.0

# Logging configuration for comparison
logging:
  level:
    com.kotsin.consumer.processor.TradingSignalProcessor: INFO
    com.kotsin.consumer.curated.processor.CuratedSignalProcessor: INFO
    com.kotsin.consumer.curated.service: INFO
  pattern:
    console: "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
  file:
    name: logs/playback-comparison.log
```

---

## 🚀 Playback Execution Steps

### Step 1: Start Kafka

```bash
# Start Zookeeper
bin/zookeeper-server-start.sh config/zookeeper.properties

# Start Kafka
bin/kafka-server-start.sh config/server.properties
```

### Step 2: Reset Consumer Groups (Clean Start)

```bash
# Reset OLD system consumer group
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group trading-signal-processor --reset-offsets --to-earliest --all-topics --execute

# Reset NEW system consumer group
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group curated-signal-processor --reset-offsets --to-earliest --all-topics --execute
```

### Step 3: Start Signal Comparison Listener

```bash
# Terminal 1: Monitor OLD signals
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic trading-signals \
  --from-beginning \
  --property print.timestamp=true \
  --property print.key=true | tee logs/old-signals.log

# Terminal 2: Monitor NEW curated signals
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic trading-signals-curated \
  --from-beginning \
  --property print.timestamp=true \
  --property print.key=true | tee logs/new-signals.log
```

### Step 4: Start Application (Playback Mode)

```bash
# Build
mvn clean package -DskipTests

# Run with playback profile
java -jar target/streamingcandle-*.jar --spring.profiles.active=playback
```

### Step 5: Replay Historical Data

```bash
# If you have a playback script, run it
# Otherwise, manually produce data from your forward testing topic
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic forward-testing-data \
  --from-beginning | \
kafka-console-producer.sh --bootstrap-server localhost:9092 \
  --topic candle-unified-1m
```

---

## 📊 What to Monitor During Playback

### 1. Log Analysis

#### OLD System Logs Pattern:
```
🎯 TRADING SIGNAL | scrip=RELIANCE signal=BUY confidence=0.85 | High IPU (0.90) + Moderate VCP (0.65)
```

#### NEW System Logs Pattern:
```
🔍 BREAKOUT DETECTED | scrip=RELIANCE | TF_confirmations=3/3 | confluence=1.00 | volZ=3.5 | kyle=0.15
✅ ALL_GATES_PASSED | scrip=RELIANCE | indexRegime=STRONG_BULL | secRegime=BULL | aclState=ENTRY_ALLOWED
✅ RETEST ENTRY CONFIRMED: RELIANCE @ 2448.50 | Stop=2425.00 | Target=2490.00 | R:R=2.80
📤 ENHANCED CURATED SIGNAL EMITTED: RELIANCE | Score=87.5 | Entry=2448.50 | Stop=2425.00 | Target=2490.00 | R:R=2.80
```

### 2. Key Metrics to Extract from Logs

**For OLD System**:
- Count of total signals emitted
- Distribution by signal type (BUY/SELL/NO_SIGNAL)
- Average confidence scores
- Timestamps of signals

**For NEW System**:
- Count of breakouts detected
- Count of breakouts that passed gates
- Count of retests detected
- Count of final curated signals emitted
- Average curated scores
- Average R:R ratios
- Gate failure breakdowns

### 3. Critical Comparison Points

| Aspect | OLD System | NEW System |
|--------|-----------|------------|
| **Signal Count** | Every 5m | Only on retest |
| **Entry Timing** | Immediate | Wait for retest |
| **Risk Management** | Basic | Multi-TF levels |
| **Quality Filter** | Confidence > 0.5 | Score > 60 |
| **Structure** | IPU + VCP only | 16 modules + F&O + Levels |

---

## 🔍 Signal Comparison Script

Use the Python script below to analyze logs:

```python
# Save this as: compare_signals.py

import re
from datetime import datetime
from collections import defaultdict

def parse_old_signals(log_file):
    """Parse OLD system signals from log"""
    signals = []
    pattern = r'🎯 TRADING SIGNAL \| scrip=(\w+) signal=(\w+) confidence=([\d.]+)'

    with open(log_file, 'r') as f:
        for line in f:
            match = re.search(pattern, line)
            if match:
                signals.append({
                    'scrip': match.group(1),
                    'signal': match.group(2),
                    'confidence': float(match.group(3)),
                    'timestamp': extract_timestamp(line)
                })
    return signals

def parse_new_signals(log_file):
    """Parse NEW curated signals from log"""
    signals = []
    pattern = r'📤 ENHANCED CURATED SIGNAL EMITTED: (\w+) \| Score=([\d.]+) \| Entry=([\d.]+) \| Stop=([\d.]+) \| Target=([\d.]+) \| R:R=([\d.]+)'

    with open(log_file, 'r') as f:
        for line in f:
            match = re.search(pattern, line)
            if match:
                signals.append({
                    'scrip': match.group(1),
                    'score': float(match.group(2)),
                    'entry': float(match.group(3)),
                    'stop': float(match.group(4)),
                    'target': float(match.group(5)),
                    'rr_ratio': float(match.group(6)),
                    'timestamp': extract_timestamp(line)
                })
    return signals

def extract_timestamp(line):
    """Extract timestamp from log line"""
    match = re.match(r'(\d{2}:\d{2}:\d{2}\.\d{3})', line)
    return match.group(1) if match else None

def analyze_old_system(signals):
    """Analyze OLD system signals"""
    print("\n===== OLD SYSTEM ANALYSIS =====")
    print(f"Total Signals: {len(signals)}")

    signal_types = defaultdict(int)
    confidence_scores = []

    for sig in signals:
        signal_types[sig['signal']] += 1
        confidence_scores.append(sig['confidence'])

    print("\nSignal Distribution:")
    for sig_type, count in signal_types.items():
        print(f"  {sig_type}: {count} ({count/len(signals)*100:.1f}%)")

    if confidence_scores:
        print(f"\nConfidence Scores:")
        print(f"  Average: {sum(confidence_scores)/len(confidence_scores):.2f}")
        print(f"  Min: {min(confidence_scores):.2f}")
        print(f"  Max: {max(confidence_scores):.2f}")

def analyze_new_system(signals):
    """Analyze NEW curated system signals"""
    print("\n===== NEW CURATED SYSTEM ANALYSIS =====")
    print(f"Total Curated Signals: {len(signals)}")

    scores = [s['score'] for s in signals]
    rr_ratios = [s['rr_ratio'] for s in signals]

    if scores:
        print(f"\nCurated Scores:")
        print(f"  Average: {sum(scores)/len(scores):.1f}")
        print(f"  Min: {min(scores):.1f}")
        print(f"  Max: {max(scores):.1f}")
        print(f"  High Quality (>=80): {sum(1 for s in scores if s >= 80)} ({sum(1 for s in scores if s >= 80)/len(scores)*100:.1f}%)")

    if rr_ratios:
        print(f"\nRisk:Reward Ratios:")
        print(f"  Average: {sum(rr_ratios)/len(rr_ratios):.2f}")
        print(f"  Min: {min(rr_ratios):.2f}")
        print(f"  Max: {max(rr_ratios):.2f}")

def compare_signals(old_signals, new_signals):
    """Compare OLD vs NEW signals"""
    print("\n===== COMPARISON =====")
    print(f"Signal Volume Reduction: {len(old_signals)} -> {len(new_signals)} ({(1-len(new_signals)/len(old_signals))*100:.1f}% reduction)")

    # Find common scrips
    old_scrips = set(s['scrip'] for s in old_signals)
    new_scrips = set(s['scrip'] for s in new_signals)

    print(f"\nUnique Scrips:")
    print(f"  OLD: {len(old_scrips)}")
    print(f"  NEW: {len(new_scrips)}")
    print(f"  Overlap: {len(old_scrips & new_scrips)}")
    print(f"  Only in OLD: {len(old_scrips - new_scrips)}")
    print(f"  Only in NEW: {len(new_scrips - old_scrips)}")

if __name__ == "__main__":
    # Parse logs
    old_signals = parse_old_signals('logs/playback-comparison.log')
    new_signals = parse_new_signals('logs/playback-comparison.log')

    # Analyze
    analyze_old_system(old_signals)
    analyze_new_system(new_signals)
    compare_signals(old_signals, new_signals)
```

**Run comparison:**
```bash
python3 compare_signals.py
```

---

## 📈 Expected Results

### OLD System (Expected):
- **Signal Volume**: 50-100 signals/day
- **Quality**: Mixed (many false signals)
- **Entry**: Immediate (may enter at worse price)
- **R:R**: Variable (no optimization)

### NEW System (Expected):
- **Signal Volume**: 2-4 signals/day (95-98% reduction)
- **Quality**: High (score > 60)
- **Entry**: Optimized (wait for retest)
- **R:R**: Minimum 2:1 (level-optimized)

---

## ✅ Success Criteria

The NEW system is successful if:

1. **✅ Signal Quality**:
   - Average curated score > 70
   - >50% of signals are "high quality" (score >= 80)

2. **✅ Risk Management**:
   - Average R:R ratio >= 2.0
   - All signals have clearly defined entry/stop/target

3. **✅ Volume Reduction**:
   - 90-98% fewer signals than OLD system
   - But captures all major opportunities

4. **✅ Structural Confirmation**:
   - All signals have multi-TF breakout confirmation
   - All signals have retest entry
   - All signals pass regime gates

5. **✅ Logging Completeness**:
   - Can trace every signal from breakout → gates → retest → emit
   - Can identify why signals were rejected
   - Gate failure reasons are clear

---

## 🐛 Troubleshooting

### Issue: No signals from NEW system

**Check**:
```bash
# Are breakouts being detected?
grep "BREAKOUT DETECTED" logs/playback-comparison.log

# Are they passing gates?
grep "GATE.*FAILED" logs/playback-comparison.log

# Are retests being detected?
grep "RETEST ENTRY CONFIRMED" logs/playback-comparison.log
```

### Issue: Too few signals

**Possible Causes**:
- Strict gates (especially index regime not tradeable)
- F&O/Levels disabled (reduces score)
- Min score threshold too high (60.0)

**Solution**:
- Temporarily lower min score to 50.0 for testing
- Check gate logs to see which gate is rejecting most

### Issue: OLD system not emitting

**Check**:
```bash
# Are VCP and IPU topics populated?
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic vcp-combined --max-messages 10
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic ipu-signals-5m --max-messages 10
```

---

## 📝 Final Checklist

After playback, verify:

- [ ] Both systems processed the same time period
- [ ] Logs captured all decision points
- [ ] Signal comparison script ran successfully
- [ ] Metrics collected for both systems
- [ ] Signal quality meets expectations
- [ ] No errors or exceptions in logs
- [ ] Can explain every signal rejection

---

## 🎯 Next Steps After Playback

1. **If Results are GOOD**:
   - Deploy NEW system to production
   - Monitor for 1 week alongside OLD system
   - Gradually transition traffic

2. **If Results are MIXED**:
   - Analyze specific failures
   - Tune parameters (min score, gate thresholds)
   - Re-run playback with adjustments

3. **If Results are BAD**:
   - Identify root cause (gates too strict? breakout detection faulty?)
   - Fix issues
   - Re-test

---

**Ready to start playback!** 🚀

Follow the steps above and analyze the results carefully. The detailed logs will tell you exactly why signals were accepted or rejected.
